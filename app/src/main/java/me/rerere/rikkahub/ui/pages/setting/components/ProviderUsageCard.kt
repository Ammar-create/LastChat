package me.rerere.rikkahub.ui.pages.setting.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ProviderUsage
import me.rerere.ai.provider.providers.opencode.OpenCodeEndpoint
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun ProviderUsageCard(provider: ProviderSetting.OpenAI) {
    val providers = koinInject<ProviderManager>()
    val context = LocalContext.current
    val haptics = rememberPremiumHaptics()
    val openCode = OpenCodeEndpoint.from(provider.baseUrl)
    val grok = provider.subscriptionAccount == me.rerere.ai.provider.SubscriptionAccountType.GROK
    var refresh by remember(provider.id) { mutableIntStateOf(0) }
    var result by remember(provider.id) { mutableStateOf<Result<ProviderUsage?>?>(null) }
    val latestProvider by rememberUpdatedState(provider)
    LaunchedEffect(provider.id, provider.subscriptionAccountVersion, provider.subscriptionDnsFallback, provider.apiKey,
        provider.apiKeyPool, refresh) {
        result = null
        result = try {
            Result.success(withTimeout(20_000) { withContext(Dispatchers.IO) { providers.getProviderByType(latestProvider).getUsage(latestProvider) } })
        } catch (_: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Usage loading timed out. Try Refresh."))
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { Result.failure(e) }
    }
    fun browse(url: String) {
        haptics.perform(HapticPattern.Pop)
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    Card(shape = AppShapes.CardLarge, colors = CardDefaults.cardColors(containerColor =
        if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Usage", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { haptics.perform(HapticPattern.Tick); refresh++ }, enabled = result != null) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh usage")
                }
            }
            val usage = result?.getOrNull()
            when {
                result == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                result?.isFailure == true -> Text(result?.exceptionOrNull()?.message ?: "Usage could not be loaded. Try again.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                usage == null -> Text("Check your provider’s usage and billing in its account dashboard.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                usage.windows.isEmpty() -> Text("No usage windows were reported for this account.", style = MaterialTheme.typography.bodyMedium)
                else -> usage.windows.forEach { window ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row {
                            Text(window.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("${window.usedPercent.roundToInt()}% used", style = MaterialTheme.typography.labelLarge)
                        }
                        LinearProgressIndicator(progress = { (window.usedPercent / 100).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(), color = if (window.usedPercent >= 100)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        window.resetsAt?.let { reset ->
                            val date = remember(reset) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(reset.toEpochMilliseconds())) }
                            Text("Resets $date", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { browse(if (openCode != null) "https://opencode.ai/console" else if (grok) "https://accounts.x.ai" else "https://chatgpt.com/settings/usage") },
                shape = AppShapes.ButtonPill, modifier = Modifier.fillMaxWidth()) { Text(if (openCode != null) "Open Console" else if (grok) "Manage account" else "Manage plan usage") }
            if (openCode != null) TextButton(onClick = { browse(if (openCode.go) "https://opencode.ai/docs/go/" else "https://opencode.ai/docs/zen/") },
                shape = AppShapes.ButtonPill, modifier = Modifier.align(Alignment.End)) { Text("Model rates and limits") }
        }
    }
}
