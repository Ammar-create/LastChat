package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.rerere.ai.ui.MessageNode
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationGenerationJobsTest {
    @Test
    fun replacementWaitsForFinalFlushAndKeepsItsLoadingState() = runBlocking {
        withTimeout(5_000) {
            val jobs = ConversationGenerationJobs(this)
            val id = Uuid.random()
            val started = CompletableDeferred<Unit>()
            val flushing = CompletableDeferred<Unit>()
            val finishFlush = CompletableDeferred<Unit>()
            val replacementStarted = CompletableDeferred<Unit>()
            val finishReplacement = CompletableDeferred<Unit>()
            val old = jobs.launch(id) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        flushing.complete(Unit)
                        finishFlush.await()
                    }
                }
            }
            started.await()
            val replacement = jobs.launch(id) {
                replacementStarted.complete(Unit)
                finishReplacement.await()
            }
            flushing.await()
            assertFalse(replacementStarted.isCompleted)
            assertSame(replacement, jobs.get(id))
            finishFlush.complete(Unit)
            old.join()
            replacementStarted.await()
            assertSame(replacement, jobs.get(id))
            finishReplacement.complete(Unit)
            replacement.join()
            assertTrue(jobs.jobs.value.isEmpty())
        }
    }

    @Test
    fun deletionReadsTheFinalSnapshotAndCannotBeResurrectedByTheCancelledStream() = runBlocking {
        withTimeout(5_000) {
            val jobs = ConversationGenerationJobs(this)
            val id = Uuid.random()
            val started = CompletableDeferred<Unit>()
            val flushing = CompletableDeferred<Unit>()
            val finishFlush = CompletableDeferred<Unit>()
            var messages = listOf("user", "partial")
            val old = jobs.launch(id) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        flushing.complete(Unit)
                        finishFlush.await()
                        messages = listOf("user", "final pending tokens")
                    }
                }
            }
            started.await()
            val delete = async {
                jobs.mutate(id) {
                    assertEquals("final pending tokens", messages.last())
                    messages = listOf("user")
                }
            }
            flushing.await()
            assertFalse(delete.isCompleted)
            finishFlush.complete(Unit)
            delete.await()
            old.join()
            assertEquals(listOf("user"), messages)
            assertTrue(jobs.jobs.value.isEmpty())
        }
    }

    @Test
    fun cancellingAQueuedReplacementStillTracksThePriorFinalFlush() = runBlocking {
        withTimeout(5_000) {
            val jobs = ConversationGenerationJobs(this)
            val id = Uuid.random()
            val started = CompletableDeferred<Unit>()
            val flushing = CompletableDeferred<Unit>()
            val finishFlush = CompletableDeferred<Unit>()
            val old = jobs.launch(id) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        flushing.complete(Unit)
                        finishFlush.await()
                    }
                }
            }
            started.await()
            val replacement = jobs.launch(id) { fail("Cancelled queued generation ran") }
            flushing.await()
            replacement.cancel()
            replacement.join()
            assertSame(old, jobs.get(id))
            finishFlush.complete(Unit)
            old.join()
            assertTrue(jobs.jobs.value.isEmpty())
        }
    }

    @Test
    fun repeatedRegenerationCannotOverlapOrReviveSupersededTurns() = runBlocking {
        withTimeout(5_000) {
            val jobs = ConversationGenerationJobs(this)
            val id = Uuid.random()
            val entered = CompletableDeferred<Unit>()
            val flush = CompletableDeferred<Unit>()
            var visibleReply = "original"
            val first = jobs.launch(id) {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        flush.await()
                        visibleReply = "old final flush"
                    }
                }
            }
            entered.await()
            val replacements = (1..100).map { version ->
                jobs.launch(id) { visibleReply = "reply $version" }
            }
            yield()
            assertEquals("original", visibleReply)
            flush.complete(Unit)
            first.join()
            replacements.forEach { it.join() }
            assertEquals("reply 100", visibleReply)
            assertTrue(jobs.jobs.value.isEmpty())
        }
    }

    @Test
    fun concurrentChatsNeverLoseEachOthersJobState() = runBlocking {
        withTimeout(10_000) {
            val jobs = ConversationGenerationJobs(this)
            val finish = CompletableDeferred<Unit>()
            val ids = (1..100).map { Uuid.random() }
            val tasks = ids.map { id ->
                async(Dispatchers.Default) { jobs.launch(id) { finish.await() } }
            }.awaitAll()
            assertEquals(ids.toSet(), jobs.jobs.value.keys)
            finish.complete(Unit)
            tasks.forEach { it.join() }
            assertTrue(jobs.jobs.value.isEmpty())
        }
    }

    @Test
    fun historyMutationAlsoSerializesNewGeneration() = runBlocking {
        withTimeout(5_000) {
            val jobs = ConversationGenerationJobs(this)
            val id = Uuid.random()
            val editing = CompletableDeferred<Unit>()
            val saveEdit = CompletableDeferred<Unit>()
            var snapshot = "before edit"
            val edit = async {
                jobs.mutate(id) {
                    editing.complete(Unit)
                    saveEdit.await()
                    snapshot = "after edit"
                }
            }
            editing.await()
            val generation = jobs.launch(id) {
                assertEquals("after edit", snapshot)
                snapshot += " + reply"
            }
            yield()
            assertEquals("before edit", snapshot)
            saveEdit.complete(Unit)
            edit.await()
            generation.join()
            assertEquals("after edit + reply", snapshot)
            assertTrue(jobs.jobs.value.isEmpty())
        }
    }

    @Test
    fun backgroundSummaryWaitsForTheReplyWithoutCancellingIt() = runBlocking {
        withTimeout(5_000) {
            val jobs = ConversationGenerationJobs(this)
            val id = Uuid.random()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val generation = jobs.launch(id) {
                started.complete(Unit)
                finish.await()
            }
            started.await()
            val summary = async {
                jobs.mutate(id, cancelGeneration = false) { assertTrue(finish.isCompleted) }
            }
            yield()
            assertTrue(generation.isActive)
            assertFalse(summary.isCompleted)
            finish.complete(Unit)
            generation.join()
            summary.await()
        }
    }

    @Test
    fun queuedMetadataPersistenceCannotRestoreADeletedTurn() = runBlocking {
        withTimeout(5_000) {
            val locks = ConversationWriteLocks()
            val id = Uuid.random()
            val original = Conversation.ofId(id, messages = listOf(
                MessageNode.of(UIMessage.user("presentation")),
                MessageNode.of(UIMessage.assistant("tool follow-up")),
            )).copy(updateAt = Instant.parse("2026-10-20T00:00:00Z"))
            var live = original
            var persisted = original
            val deleting = CompletableDeferred<Unit>()
            val commitDelete = CompletableDeferred<Unit>()
            val deletion = async {
                locks.withLock(id) {
                    live = original.copy(messageNodes = original.messageNodes.take(1),
                        updateAt = original.updateAt.plusSeconds(1))
                    deleting.complete(Unit)
                    commitDelete.await()
                    persisted = live
                }
            }
            deleting.await()
            val lateTitleSave = async {
                locks.withLock(id) {
                    persisted = mergeLiveMessagesIfIncomingIsStale(live, original.copy(title = "Presentation"))
                }
            }
            yield()
            assertFalse(lateTitleSave.isCompleted)
            commitDelete.complete(Unit)
            deletion.await()
            lateTitleSave.await()
            assertEquals(live.messageNodes, persisted.messageNodes)
            assertEquals("Presentation", persisted.title)
        }
    }

    @Test
    fun writingOneChatDoesNotBlockAnotherChat() = runBlocking {
        withTimeout(5_000) {
            val locks = ConversationWriteLocks()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = async {
                locks.withLock(Uuid.random()) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            locks.withLock(Uuid.random()) { assertFalse(first.isCompleted) }
            release.complete(Unit)
            first.await()
        }
    }

    @Test
    fun lateMetadataSaveCannotUndoDeletionOrReplyVersionSelection() {
        val user = MessageNode.of(UIMessage.user("attached presentation"))
        val oldTurn = MessageNode.of(UIMessage.assistant("old tool follow-up"))
        val previous = Conversation.ofId(Uuid.random(), messages = listOf(user, oldTurn))
            .copy(updateAt = Instant.parse("2026-10-20T00:00:00Z"))
        val deleted = previous.copy(messageNodes = listOf(user), updateAt = previous.updateAt.plusSeconds(1))
        val lateTitle = previous.copy(title = "Presentation")
        val merged = mergeLiveMessagesIfIncomingIsStale(deleted, lateTitle)
        assertEquals(listOf(user), merged.messageNodes)
        assertEquals("Presentation", merged.title)

        val nextTurn = MessageNode.of(UIMessage.assistant("selected replacement"))
        val selected = deleted.copy(messageNodes = listOf(user, nextTurn), updateAt = deleted.updateAt.plusSeconds(1))
        assertEquals(selected.messageNodes, mergeLiveMessagesIfIncomingIsStale(selected, lateTitle).messageNodes)
    }
}
