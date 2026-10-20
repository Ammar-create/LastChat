package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.subscription.SubscriptionAuthManager
import me.rerere.rikkahub.data.ai.subscription.SubscriptionLoginPrompt

internal data class SubscriptionAccountState(
    val account: String? = null,
    val busy: Boolean = false,
    val prompt: SubscriptionLoginPrompt? = null,
    val error: String? = null,
    val completed: Boolean = false,
    val models: List<Model>? = null,
    val dnsFailure: Boolean = false,
)

/** The browser transaction survives activity recreation and recomposition. */
internal class SubscriptionAccountVM(
    private val auth: SubscriptionAuthManager,
    private val providers: ProviderManager,
    context: Context,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(SubscriptionAccountState())
    val state = mutableState.asStateFlow()
    private var login: Job? = null

    fun refresh(setting: ProviderSetting.OpenAI) = viewModelScope.launch {
        mutableState.update { it.copy(account = auth.accountLabel(setting)) }
    }

    fun openBrowser(url: String) {
        runCatching {
            appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { mutableState.update { it.copy(error = "No browser could open sign-in. Set a default browser and try again.") } }
    }

    fun connect(setting: ProviderSetting.OpenAI, deviceCode: Boolean = false) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, prompt = null, error = null, completed = false, models = null, dnsFailure = false) }
        login = viewModelScope.launch {
            val service = Intent(appContext, me.rerere.rikkahub.data.ai.subscription.SubscriptionLoginService::class.java)
            try {
                androidx.core.content.ContextCompat.startForegroundService(appContext, service)
                auth.signIn(setting, deviceCode) { prompt ->
                    withContext(Dispatchers.Main) {
                        mutableState.update { it.copy(prompt = prompt) }
                        openBrowser(prompt.url)
                    }
                }
                mutableState.update { it.copy(account = auth.accountLabel(setting), prompt = null) }
                val models = try {
                    withTimeout(30_000) { withContext(Dispatchers.IO) { providers.getProviderByType(setting).listModels(setting) } }
                } catch (_: TimeoutCancellationException) {
                    mutableState.update { it.copy(error = "Connected. Model loading timed out; refresh in the Models tab.") }
                    null
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    val dns = generateSequence<Throwable>(e) { it.cause }.any { it is java.net.UnknownHostException }
                    mutableState.update { it.copy(dnsFailure = dns, error = if (dns)
                        "Connected. Your DNS or ad blocker blocked model loading. Check connection options, then refresh the Models tab."
                        else "Connected. Models could not be loaded; refresh them in the Models tab.") }
                    null
                }
                mutableState.update { it.copy(completed = true, models = models) }
            } catch (_: TimeoutCancellationException) {
                mutableState.update { it.copy(error = "Sign-in timed out. Please try again.") }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                val dns = generateSequence<Throwable>(e) { it.cause }.any {
                    it is java.net.UnknownHostException || it.message?.contains("Unable to resolve host", true) == true
                }
                mutableState.update { it.copy(dnsFailure = dns, error = if (dns)
                    "Your DNS or ad blocker could not resolve the account service. Allow the service in your blocker, or try secure DNS below."
                    else e.message?.takeIf { message -> message.length < 250 }
                        ?: "Sign-in failed. Check your connection and try again.") }
            } finally {
                appContext.stopService(service)
                mutableState.update { it.copy(busy = false, prompt = null) }
            }
        }
    }

    fun cancel() { login?.cancel() }
    fun consumeCompletion() { mutableState.update { it.copy(completed = false, models = null) } }

    fun signOut(setting: ProviderSetting.OpenAI) = viewModelScope.launch {
        auth.signOut(setting.id)
        mutableState.update { it.copy(account = null, error = null, completed = true, models = null) }
    }
}
