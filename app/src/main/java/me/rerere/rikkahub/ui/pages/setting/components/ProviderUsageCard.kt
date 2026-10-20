package me.rerere.rikkahub.ui.pages.setting.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ProviderUsage
import me.rerere.ai.provider.SubscriptionAccountType
import me.rerere.ai.provider.providers.opencode.OpenCodeEndpoint
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.hooks.rememberProviderUsage
import me.rerere.rikkahub.ui.theme.AppShapes
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun ProviderUsageCard(provider: ProviderSetting.OpenAI) {
    val context = LocalContext.current
    val haptics = rememberPremiumHaptics()
    var refresh by remember(provider.id) { mutableIntStateOf(0) }
    val result = rememberProviderUsage(provider, refresh)
    ProviderUsageContent(provider, result,
        onRefresh = { haptics.perform(HapticPattern.Tick); refresh++ },
        onBrowse = { url ->
            haptics.perform(HapticPattern.Pop)
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        })
}

@Composable
internal fun ProviderUsageContent(
    provider: ProviderSetting.OpenAI,
    result: Result<ProviderUsage?>?,
    onRefresh: () -> Unit,
    onBrowse: (String) -> Unit,
) {
    val openCode = OpenCodeEndpoint.from(provider.baseUrl)
    var manageMenu by remember { mutableStateOf(false) }
    val manageUrl = when {
        openCode != null -> "https://opencode.ai/console"
        provider.subscriptionAccount == SubscriptionAccountType.GROK -> "https://accounts.x.ai"
        else -> "https://chatgpt.com/settings/usage"
    }
    Card(shape = AppShapes.CardLarge, colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Usage", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = result != null) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh usage")
                }
            }
            val usage = result?.getOrNull()
            when {
                result == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                result.isFailure -> Text(result.exceptionOrNull()?.message ?: "Usage could not be loaded. Try Refresh.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                usage == null -> Text("View usage and billing in your account dashboard.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                usage.windows.isEmpty() -> Text("No usage windows were reported.", style = MaterialTheme.typography.bodyMedium)
                else -> usage.windows.forEach { window ->
                    val remaining = (100.0 - window.usedPercent).coerceIn(0.0, 100.0)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(window.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("${remaining.roundToInt()}% left", style = MaterialTheme.typography.labelLarge)
                        }
                        LinearProgressIndicator(progress = { (remaining / 100).toFloat() },
                            modifier = Modifier.fillMaxWidth(), color = if (window.usedPercent >= 100)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        window.resetsAt?.let { reset ->
                            val date = remember(reset) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(reset.toEpochMilliseconds())) }
                            Text("Resets $date", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box {
                Row(Modifier.fillMaxWidth().clickable {
                    if (openCode == null) onBrowse(manageUrl) else manageMenu = true
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (openCode != null) "Usage and model rates" else "Manage plan usage",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.OpenInNew, null, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = manageMenu, onDismissRequest = { manageMenu = false }) {
                    DropdownMenuItem(text = { Text("Open Console") }, onClick = { manageMenu = false; onBrowse(manageUrl) })
                    DropdownMenuItem(text = { Text("Model rates and limits") }, onClick = {
                        manageMenu = false
                        onBrowse(if (openCode?.go == true) "https://opencode.ai/docs/go/" else "https://opencode.ai/docs/zen/")
                    })
                }
            }
        }
    }
}
