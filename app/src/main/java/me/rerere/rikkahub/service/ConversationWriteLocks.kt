package me.rerere.rikkahub.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Serializes database writes for one chat without blocking other chats or retaining old IDs. */
internal class ConversationWriteLocks {
    private class Entry {
        val mutex = Mutex()
        var references = 0
    }

    private val lock = Any()
    private val entries = mutableMapOf<Uuid, Entry>()

    suspend fun <T> withLock(id: Uuid, block: suspend () -> T): T {
        val entry = synchronized(lock) { entries.getOrPut(id, ::Entry).also { it.references++ } }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(lock) {
                entry.references--
                if (entry.references == 0) entries.remove(id)
            }
        }
    }
}
