package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ProviderUsage
import org.koin.compose.koinInject

internal data class ProviderUsageState(
    val result: Result<ProviderUsage?>? = null,
    val refreshing: Boolean = false,
)

/** Shared, account-scoped stale-while-refresh cache. Quotas and credentials never go to disk. */
internal class ProviderUsageRepository(
    private val scope: CoroutineScope,
    private val fetch: suspend (ProviderSetting.OpenAI) -> ProviderUsage?,
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private data class Key(val id: kotlin.uuid.Uuid, val url: String, val version: Int,
        val dns: Boolean, val credentials: String)
    private class Entry {
        val state = MutableStateFlow(ProviderUsageState())
        var updatedAt: Long? = null
    }
    private val entries = linkedMapOf<Key, Entry>()
    private fun key(provider: ProviderSetting.OpenAI) = Key(provider.id, provider.baseUrl,
        provider.subscriptionAccountVersion, provider.subscriptionDnsFallback,
        provider.apiKey + provider.apiKeyPool.toString())

    @Synchronized
    private fun entry(provider: ProviderSetting.OpenAI): Entry {
        val key = key(provider)
        // A reconnect/key change must not expose the previous account's allowance.
        entries.keys.removeAll { it.id == key.id && it != key }
        return entries.getOrPut(key) {
            if (entries.size >= 32) entries.entries.firstOrNull { !it.value.state.value.refreshing }
                ?.key?.let(entries::remove)
            Entry()
        }
    }

    fun state(provider: ProviderSetting.OpenAI) = entry(provider).state.asStateFlow()

    fun refresh(provider: ProviderSetting.OpenAI, force: Boolean = false) {
        val entry = entry(provider)
        synchronized(entry) {
            if (entry.state.value.refreshing) return
            if (!force && entry.updatedAt?.let { now() - it < 60_000 } == true) return
            entry.state.value = entry.state.value.copy(refreshing = true)
        }
        scope.launch(Dispatchers.IO) {
            try {
                val usage = withTimeout(20_000) { fetch(provider) }
                entry.updatedAt = now()
                entry.state.value = ProviderUsageState(Result.success(usage))
            } catch (error: CancellationException) {
                if (error is kotlinx.coroutines.TimeoutCancellationException) {
                    entry.state.value = ProviderUsageState(entry.state.value.result?.takeIf { it.isSuccess }
                        ?: Result.failure(IllegalStateException("Usage loading timed out. Try Refresh.")))
                } else {
                    entry.state.value = entry.state.value.copy(refreshing = false)
                    throw error
                }
            } catch (error: Exception) {
                // Keep the last successful windows in place during temporary failures.
                entry.state.value = ProviderUsageState(entry.state.value.result?.takeIf { it.isSuccess }
                    ?: Result.failure(error))
            }
        }
    }
}

@Composable
internal fun rememberProviderUsage(provider: ProviderSetting.OpenAI, refresh: Int = 0): ProviderUsageState {
    val repository = koinInject<ProviderUsageRepository>()
    val flow = remember(provider.id, provider.baseUrl, provider.subscriptionAccountVersion,
        provider.subscriptionDnsFallback, provider.apiKey, provider.apiKeyPool) { repository.state(provider) }
    val state by flow.collectAsStateWithLifecycle()
    LaunchedEffect(flow, refresh) { repository.refresh(provider, force = refresh > 0) }
    return state
}
