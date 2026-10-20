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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    val state = rememberProviderUsage(provider, refresh)
    ProviderUsageContent(provider, state.result, refreshing = state.refreshing,
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
    refreshing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val openCode = OpenCodeEndpoint.from(provider.baseUrl)
    var manageMenu by remember { mutableStateOf(false) }
    val manageUrl = when {
        openCode != null -> "https://opencode.ai/console"
        provider.subscriptionAccount == SubscriptionAccountType.GROK -> "https://accounts.x.ai"
        else -> "https://chatgpt.com/settings/usage"
    }
    Card(modifier = modifier, shape = AppShapes.CardLarge, colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Usage", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                IconButton(onClick = onRefresh, enabled = !refreshing) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh usage")
                }
            }
            val usage = result?.getOrNull()
            val slots = maxOf(usage?.windows?.size ?: 0, if (openCode?.go == true) 3 else 2)
            Box(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(slots) { index ->
                        UsageWindowSlot(usage?.windows?.getOrNull(index), showGhost = result == null)
                    }
                }
                when {
                    result?.isFailure == true -> Text(
                        result.exceptionOrNull()?.message ?: "Usage could not be loaded. Try Refresh.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    result != null && usage == null -> Text("View usage and billing in your account dashboard.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    usage?.windows?.isEmpty() == true -> Text("No usage windows were reported.",
                        style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun UsageWindowSlot(window: me.rerere.ai.provider.ProviderUsageWindow?, showGhost: Boolean) {
    val remaining = window?.let { (100.0 - it.usedPercent).coerceIn(0.0, 100.0) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (window == null) {
                UsagePlaceholderLine("5-hour window", MaterialTheme.typography.bodyMedium, 128.dp,
                    showGhost, Modifier.weight(1f))
                UsagePlaceholderLine("100% left", MaterialTheme.typography.labelLarge, 56.dp, showGhost)
            } else {
                Text(window.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("${remaining!!.roundToInt()}% left", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (window == null) {
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (showGhost) me.rerere.rikkahub.ui.components.ai.UsageGhost(Modifier.fillMaxSize())
            }
        } else LinearProgressIndicator(progress = { (remaining!! / 100).toFloat() },
            modifier = Modifier.fillMaxWidth().height(4.dp), color = if (window.usedPercent >= 100)
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        val reset = window?.resetsAt
        if (reset == null) {
            UsagePlaceholderLine("Resets 10/20/26, 8:00 PM", MaterialTheme.typography.bodySmall, 168.dp, showGhost)
        } else {
            val date = remember(reset) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(reset.toEpochMilliseconds())) }
            Text("Resets $date", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UsagePlaceholderLine(
    sample: String,
    style: androidx.compose.ui.text.TextStyle,
    width: androidx.compose.ui.unit.Dp,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.then(Modifier.clearAndSetSemantics { })) {
        Text(sample, style = style, color = androidx.compose.ui.graphics.Color.Transparent, maxLines = 1)
        if (visible) me.rerere.rikkahub.ui.components.ai.UsageGhost(
            Modifier.width(width).height(12.dp).align(Alignment.CenterStart))
    }
}
