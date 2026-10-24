package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** One writer per conversation, including the previous stream's cancellation/final flush. */
internal class ConversationGenerationJobs(private val scope: CoroutineScope) {
    private class Lane {
        val mutex = Mutex()
        var references = 0
        val jobs = mutableListOf<Job>()
    }

    private val lock = Any()
    private val lanes = mutableMapOf<Uuid, Lane>()
    private val mutableJobs = MutableStateFlow<Map<Uuid, Job?>>(emptyMap())
    val jobs = mutableJobs.asStateFlow()

    private fun retainLane(id: Uuid): Lane = synchronized(lock) {
        lanes.getOrPut(id, ::Lane).also { it.references++ }
    }

    private fun releaseLane(id: Uuid, lane: Lane) = synchronized(lock) {
        lane.references--
        if (lane.references == 0) lanes.remove(id)
    }

    fun get(id: Uuid): Job? = jobs.value[id]

    fun launch(id: Uuid, onCompletion: () -> Unit = {}, block: suspend () -> Unit): Job {
        val lane = retainLane(id)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            lane.mutex.withLock {
                currentCoroutineContext().ensureActive()
                block()
            }
        }
        val previous = synchronized(lock) {
            val previous = lane.jobs.toList()
            lane.jobs += job
            mutableJobs.value = mutableJobs.value + (id to job)
            previous
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                // A cancelled predecessor must never clear its replacement's loading state.
                lane.jobs.remove(job)
                mutableJobs.value = lane.jobs.lastOrNull()?.let { mutableJobs.value + (id to it) }
                    ?: (mutableJobs.value - id)
            }
            releaseLane(id, lane)
            onCompletion()
        }
        previous.forEach { it.cancel() }
        job.start()
        return job
    }

    fun cancel(id: Uuid) {
        val pending = synchronized(lock) { lanes[id]?.jobs?.toList().orEmpty() }
        pending.forEach { it.cancel() }
    }

    fun cancelAll() {
        val pending = synchronized(lock) { lanes.values.flatMap { it.jobs } }
        pending.forEach { it.cancel() }
    }

    /** Finalize the stream before reading the snapshot to edit/delete/change versions. */
    suspend fun <T> mutate(id: Uuid, cancelGeneration: Boolean = true, block: suspend () -> T): T {
        val lane = retainLane(id)
        try {
            if (cancelGeneration) cancel(id)
            return lane.mutex.withLock {
                // Also cancel a replacement that was queued while the prior stream flushed.
                if (cancelGeneration) cancel(id)
                block()
            }
        } finally {
            releaseLane(id, lane)
        }
    }
}
