package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsMergeTest {
    @Test fun aDisplayEditKeepsLargeUnchangedProviderAndAssistantCollectionsByReference() {
        val provider = me.rerere.ai.provider.ProviderSetting.OpenAI(
            models = List(512) { me.rerere.ai.provider.Model(modelId = "model-$it") },
        )
        val base = Settings(providers = listOf(provider))
        val latest = base.copy(titlePrompt = "changed elsewhere")
        val edited = base.copy(displaySetting = base.displaySetting.copy(fontSizeRatio = 1.2f))
        val merged = mergeSettingsEdit(base, edited, latest)
        assertSame(latest.providers, merged.providers)
        assertSame(latest.assistants, merged.assistants)
        assertEquals(1.2f, merged.displaySetting.fontSizeRatio)
        assertEquals("changed elsewhere", merged.titlePrompt)
    }

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

    @Test fun providerRenameAndModelReorderCommute() {
        val a = me.rerere.ai.provider.Model(modelId = "a")
        val b = me.rerere.ai.provider.Model(modelId = "b")
        val original = me.rerere.ai.provider.ProviderSetting.OpenAI(name = "original", models = listOf(a, b))
        val reordered = original.copy(models = listOf(b, a))
        val renamed = original.copy(name = "renamed", models = listOf(a.copy(displayName = "Edited"), b))
        val merged = mergeProviderEdit(original, renamed, reordered)
        assertEquals("renamed", merged.name)
        assertEquals(listOf(b.id, a.id), merged.models.map { it.id })
        assertEquals("Edited", merged.models.last().displayName)
        val reverse = mergeProviderEdit(original, reordered, renamed)
        assertEquals(merged, reverse)
    }

    @Test fun reorderDoesNotResurrectDeletedModelsOrDropNewOnes() {
        val a = me.rerere.ai.provider.Model(modelId = "a")
        val b = me.rerere.ai.provider.Model(modelId = "b")
        val added = me.rerere.ai.provider.Model(modelId = "added")
        val original = me.rerere.ai.provider.ProviderSetting.OpenAI(models = listOf(a, b))
        val merged = mergeProviderEdit(original, original.copy(models = listOf(b, a)),
            original.copy(models = listOf(a.copy(displayName = "edited"), added)))
        assertEquals(listOf(a.id, added.id), merged.models.map { it.id })
        assertEquals("edited", merged.models.first().displayName)
    }

    @Test fun assistantFieldsAndRegexOrderCommute() {
        val a = me.rerere.rikkahub.data.model.AssistantRegex(id = kotlin.uuid.Uuid.random(), name = "a")
        val b = me.rerere.rikkahub.data.model.AssistantRegex(id = kotlin.uuid.Uuid.random(), name = "b")
        val base = me.rerere.rikkahub.data.model.Assistant(regexes = listOf(a, b), systemPrompt = "before")
        val ordered = base.copy(regexes = listOf(b, a))
        val edited = base.copy(systemPrompt = "after", regexes = listOf(a.copy(name = "renamed"), b))
        val merged = mergeAssistantEdit(base, edited, ordered)
        assertEquals("after", merged.systemPrompt)
        assertEquals(listOf(b.id, a.id), merged.regexes.map { it.id })
        assertEquals("renamed", merged.regexes.last().name)
    }

    @Test fun ttsVoiceEditsKeepANewerVoiceOrder() {
        val a = me.rerere.tts.provider.TTSVoice(name = "a")
        val b = me.rerere.tts.provider.TTSVoice(name = "b")
        val base = me.rerere.tts.provider.TTSProviderSetting.OpenAI(voices = listOf(a, b))
        val ordered = base.copy(voices = listOf(b, a))
        val edited = base.copy(voices = listOf(a.copy(speed = 1.3f), b))
        val merged = mergeTtsProviderEdit(base, edited, ordered)
        assertEquals(listOf(b.id, a.id), merged.voices.map { it.id })
        assertEquals(1.3f, merged.voices.last().speed)
    }
}
