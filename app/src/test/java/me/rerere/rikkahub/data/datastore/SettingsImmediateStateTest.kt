package me.rerere.rikkahub.data.datastore

import android.app.Application
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ApiKeyEntry
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.transformers.MessageTemplateCache
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real DataStore with a paused persistence lane, so a fast disk cannot hide a UI rollback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsImmediateStateTest {
    private val appScope = AppScope()
    private val scheduler = TestCoroutineScheduler()
    private lateinit var store: SettingsStore
    private var cacheInvalidations = 0
    private lateinit var secrets: SecretKeyManager

    @Before fun setUp() {
        startKoin { modules(module {
            single<MessageTemplateCache> { object : MessageTemplateCache { override fun invalidateAll() { cacheInvalidations++ } } }
        }) }
        val context = RuntimeEnvironment.getApplication()
        secrets = SecretKeyManager(SecureStore(context))
        store = SettingsStore(context, appScope, QuickSettingsCache(context), secrets, StandardTestDispatcher(scheduler))
    }

    @After fun tearDown() { appScope.cancel(); stopKoin() }

    private fun pumpUntil(condition: () -> Boolean) = runBlocking {
        withTimeout(20_000) {
            while (!condition()) { scheduler.runCurrent(); delay(10) }
        }
    }

    private fun load() { pumpUntil { !store.settingsFlow.value.init } }
    private fun persist(vararg edits: Deferred<*>) {
        pumpUntil { edits.all { it.isCompleted } }
        runBlocking { edits.forEach { it.await() }; store.awaitQueuedWrites() }
    }
    private fun disk() = runBlocking { withTimeout(20_000) { store.settingsFlowRaw.first() } }

    @Test fun aTapPublishesBeforeAnyPersistenceWorkCanRun() {
        load()
        val before = store.settingsFlow.value
        val save = store.enqueueUpdate { it.copy(enableWebSearch = !it.enableWebSearch) }
        assertEquals(!before.enableWebSearch, store.settingsFlow.value.enableWebSearch)
        assertFalse("The disk lane is paused", save.isCompleted)
        assertEquals(before.enableWebSearch, disk().enableWebSearch)
        persist(save)
        assertEquals(!before.enableWebSearch, disk().enableWebSearch)
    }

    @Test fun rapidEditsApplyOnceAndAnOlderAcknowledgementCannotRevertTheNextTap() {
        load()
        var transforms = 0
        val first = store.enqueueUpdate { transforms++; it.copy(learningModePrompt = "first") }
        scheduler.runCurrent() // first write may now be in flight, while continuations stay paused
        val second = store.enqueueUpdate { transforms++; it.copy(learningModePrompt = "second") }
        val third = store.enqueueUpdate { transforms++; it.copy(enableWebSearch = !it.enableWebSearch) }
        val revision = store.settingsFlow.value.storeRevision
        val expectedSearch = store.settingsFlow.value.enableWebSearch
        pumpUntil {
            assertEquals("second", store.settingsFlow.value.learningModePrompt)
            assertTrue(store.settingsFlow.value.storeRevision >= revision)
            first.isCompleted && second.isCompleted && third.isCompleted
        }
        assertEquals(3, transforms)
        assertEquals("second", disk().learningModePrompt)
        assertEquals(expectedSearch, disk().enableWebSearch)
    }

    @Test fun editsSubmittedBeforeLoadingAreAppliedInOrderWithoutUsingDummyDefaults() {
        assertTrue(store.settingsFlow.value.init)
        var transforms = 0
        val first = store.enqueueUpdate { transforms++; it.copy(titlePrompt = it.titlePrompt + "|first") }
        val second = store.enqueueUpdate { transforms++; it.copy(titlePrompt = it.titlePrompt + "|second") }
        persist(first, second)
        assertEquals(2, transforms)
        assertTrue(disk().titlePrompt.endsWith("|first|second"))
        assertEquals(disk().titlePrompt, store.settingsFlow.value.titlePrompt)
    }

    @Test fun cancellingAnAcknowledgementDoesNotCancelTheUserEdit() {
        load()
        val save = store.enqueueUpdate { it.copy(ocrPrompt = "keep-after-navigation") }
        save.cancel()
        val later = store.enqueueUpdate { it.copy(suggestionPrompt = "also-keep") }
        persist(later)
        assertEquals("keep-after-navigation", disk().ocrPrompt)
        assertEquals("also-keep", disk().suggestionPrompt)
    }

    @Test fun aTemplateEditInvalidatesItsCacheBeforeTheDiskAcknowledgement() {
        load()
        val before = cacheInvalidations
        val save = store.enqueueUpdate { current -> current.copy(assistants = current.assistants.mapIndexed { index, assistant ->
            if (index == 0) assistant.copy(messageTemplate = "changed: {{message}}") else assistant
        }) }
        assertFalse(save.isCompleted)
        assertTrue(cacheInvalidations > before)
        assertEquals("changed: {{message}}", store.settingsFlow.value.assistants.first().messageTemplate)
        persist(save)
    }

    @Test fun coalescedChangesStillClearSecureKeysWithoutWritingPlaintext() {
        load()
        val provider = ProviderSetting.OpenAI(name = "Secure test", apiKeyPool = listOf(ApiKeyEntry(key = "not-a-real-secret")))
        val add = store.enqueueUpdate { it.copy(providers = it.providers + provider) }
        persist(add)
        assertEquals("", (disk().providers.first { it.id == provider.id } as ProviderSetting.OpenAI).apiKeyPool.single().key)
        assertEquals("not-a-real-secret", (store.settingsFlow.value.providers.first { it.id == provider.id } as ProviderSetting.OpenAI).apiKeyPool.single().key)
        val clear = store.enqueueUpdate { settings -> settings.copy(providers = settings.providers.map {
            if (it.id == provider.id) (it as ProviderSetting.OpenAI).copy(apiKeyPool = emptyList()) else it
        }) }
        val other = store.enqueueUpdate { it.copy(ocrPrompt = "unrelated") }
        persist(clear, other)
        val rehydrated = secrets.populateSecretsForExport(disk())
        assertTrue(rehydrated.providers.first { it.id == provider.id }.apiKeyPool.isEmpty())
    }
}
