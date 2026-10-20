package me.rerere.rikkahub.ui.hooks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.*
import org.junit.Assert.*
import org.junit.Test

class ProviderUsageRepositoryTest {
    private val usage = ProviderUsage(listOf(ProviderUsageWindow("Weekly", 25.0, null)))

    @Test fun settingsAndPickerShareCacheAndDeduplicateRefresh() = runBlocking {
        withTimeout(5_000) {
            var calls = 0
            var now = 0L
            val complete = CompletableDeferred<Unit>()
            val repository = ProviderUsageRepository(this, fetch = { calls++; complete.await(); usage }, now = { now })
            val provider = ProviderSetting.OpenAI(subscriptionAccount = SubscriptionAccountType.CHATGPT)
            val settings = repository.state(provider)
            val picker = repository.state(provider)
            repository.refresh(provider)
            repository.refresh(provider)
            assertTrue(picker.value.refreshing)
            complete.complete(Unit)
            settings.first { !it.refreshing && it.result != null }
            assertEquals(1, calls)
            assertEquals(usage, picker.value.result!!.getOrNull())
            repository.refresh(provider)
            assertEquals(1, calls)
            now = 60_001
            repository.refresh(provider)
            assertEquals(usage, picker.value.result!!.getOrNull())
            settings.first { !it.refreshing }
            assertEquals(2, calls)
        }
    }

    @Test fun failedRefreshRetainsWindowsAndReconnectCannotReuseAnotherAccountsQuota() = runBlocking {
        withTimeout(5_000) {
            var fail = false
            val repository = ProviderUsageRepository(this, fetch = {
                if (fail) error("offline") else usage
            }, now = { 0L })
            val provider = ProviderSetting.OpenAI(subscriptionAccount = SubscriptionAccountType.CHATGPT)
            val state = repository.state(provider)
            repository.refresh(provider)
            state.first { !it.refreshing && it.result != null }
            fail = true
            repository.refresh(provider, force = true)
            assertEquals(usage, state.value.result!!.getOrNull())
            state.first { !it.refreshing }
            assertEquals(usage, state.value.result!!.getOrNull())
            assertNull(repository.state(provider.copy(subscriptionAccountVersion = 1)).value.result)
            assertNull(repository.state(provider.copy(apiKey = "changed-key")).value.result)
        }
    }
}
