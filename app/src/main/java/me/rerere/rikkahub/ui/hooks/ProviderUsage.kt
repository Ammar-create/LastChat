package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ProviderUsage
import org.koin.compose.koinInject

/** A bounded, cancellable quota read for settings and the model selector. */
@Composable
internal fun rememberProviderUsage(provider: ProviderSetting.OpenAI, refresh: Int = 0): Result<ProviderUsage?>? {
    val providers = koinInject<ProviderManager>()
    val result by produceState<Result<ProviderUsage?>?>(null, provider.id, provider.baseUrl,
        provider.subscriptionAccountVersion, provider.subscriptionDnsFallback, provider.apiKey, provider.apiKeyPool, refresh) {
        value = null
        value = try {
            Result.success(withTimeout(20_000) {
                withContext(Dispatchers.IO) { providers.getProviderByType(provider).getUsage(provider) }
            })
        } catch (_: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Usage loading timed out. Try Refresh."))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    return result
}
