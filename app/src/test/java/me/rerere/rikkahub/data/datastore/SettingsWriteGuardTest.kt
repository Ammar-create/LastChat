package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsWriteGuardTest {
    @Test
    fun staleEmissionIgnoredWhileWriteInFlight() {
        assertFalse(shouldAcceptPersisted(pendingWrites = 1, nowNanos = 10, settleUntilNanos = 0, matchesInMemory = false))
    }

    @Test
    fun staleEmissionIgnoredDuringSettleWindow() {
        assertFalse(shouldAcceptPersisted(pendingWrites = 0, nowNanos = 10, settleUntilNanos = 20, matchesInMemory = false))
    }

    @Test
    fun matchingEmissionAlwaysAccepted() {
        assertTrue(shouldAcceptPersisted(pendingWrites = 2, nowNanos = 10, settleUntilNanos = 20, matchesInMemory = true))
    }

    @Test
    fun externalChangeAcceptedAfterSettle() {
        assertTrue(shouldAcceptPersisted(pendingWrites = 0, nowNanos = 30, settleUntilNanos = 20, matchesInMemory = false))
    }
}
