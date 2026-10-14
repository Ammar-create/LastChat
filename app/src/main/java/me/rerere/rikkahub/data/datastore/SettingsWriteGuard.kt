package me.rerere.rikkahub.data.datastore

internal const val SETTLE_WINDOW_NANOS = 1_500_000_000L

/**
 * Whether a value re-read from DataStore may replace the in-memory settings. While our own
 * write is in flight, or shortly after it, only a value equal to what's in memory is taken;
 * older re-emissions would otherwise roll back the change the user just made.
 */
internal fun shouldAcceptPersisted(
    pendingWrites: Int,
    nowNanos: Long,
    settleUntilNanos: Long,
    matchesInMemory: Boolean,
): Boolean {
    if (matchesInMemory) return true
    if (pendingWrites > 0) return false
    return nowNanos - settleUntilNanos >= 0
}
