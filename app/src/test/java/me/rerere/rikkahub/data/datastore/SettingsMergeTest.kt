package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMergeTest {
    @Test
    fun snapshotEditKeepsChangeSavedMeanwhile() {
        val base = Settings(titlePrompt = "t0", translatePrompt = "x0")
        val latest = base.copy(translatePrompt = "x1") // saved by another screen meanwhile
        val edited = base.copy(titlePrompt = "t1")     // stale snapshot + this edit
        val merged = mergeSettingsEdit(base, edited, latest)
        assertEquals("t1", merged.titlePrompt)
        assertEquals("x1", merged.translatePrompt)
    }

    @Test
    fun nestedObjectsMergePerField() {
        val base = Settings()
        val latest = base.copy(displaySetting = base.displaySetting.copy(enableUIHaptics = !base.displaySetting.enableUIHaptics))
        val edited = base.copy(displaySetting = base.displaySetting.copy(fontSizeRatio = 1.3f))
        val merged = mergeSettingsEdit(base, edited, latest)
        assertEquals(1.3f, merged.displaySetting.fontSizeRatio)
        assertEquals(latest.displaySetting.enableUIHaptics, merged.displaySetting.enableUIHaptics)
    }
}
