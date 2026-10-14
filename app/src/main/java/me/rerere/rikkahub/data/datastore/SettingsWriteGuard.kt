package me.rerere.rikkahub.data.datastore

/**
 * Whether a value re-read from DataStore may replace the in-memory settings. Re-reads older than
 * the last write this process made ([emittedRevision] < [lastWrittenRevision]) are stale echoes
 * and must not roll back the user's edit; nothing is taken while a write is still in flight.
 */
internal fun shouldAcceptPersisted(
    pendingWrites: Int,
    emittedRevision: Long,
    lastWrittenRevision: Long,
): Boolean = pendingWrites == 0 && emittedRevision >= lastWrittenRevision
