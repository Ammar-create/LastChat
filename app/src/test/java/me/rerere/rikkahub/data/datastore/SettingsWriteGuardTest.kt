package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsWriteGuardTest {
    @Test fun nothingTakenWhileWriting() = assertFalse(shouldAcceptPersisted(1, 9, 5))
    @Test fun olderReReadRejected() = assertFalse(shouldAcceptPersisted(0, 4, 5))
    @Test fun echoOfLastWriteAccepted() = assertTrue(shouldAcceptPersisted(0, 5, 5))
    @Test fun newerExternalAccepted() = assertTrue(shouldAcceptPersisted(0, 6, 5))
}
