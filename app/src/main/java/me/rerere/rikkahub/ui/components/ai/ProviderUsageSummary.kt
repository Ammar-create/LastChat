package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ProviderUsage
import me.rerere.rikkahub.ui.hooks.rememberProviderUsage
import java.text.DateFormat
import java.util.Date
import kotlin.time.Clock
import kotlin.math.roundToInt

/** Restores the old Codex selector's compact account allowance indicators. */
@Composable
internal fun ProviderUsageSummary(provider: ProviderSetting.OpenAI, onManage: () -> Unit) {
    val result = rememberProviderUsage(provider)
    when {
        result == null -> Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text("Loading usage…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        result.isFailure -> Text("Usage unavailable · Account settings", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onManage).padding(vertical = 12.dp))
        else -> result.getOrNull()?.takeIf { it.windows.isNotEmpty() }?.let { usage ->
            ProviderUsageIndicators(usage, Modifier.clickable(onClick = onManage).padding(vertical = 8.dp))
        }
    }
}

@Composable
internal fun ProviderUsageIndicators(usage: ProviderUsage, modifier: Modifier = Modifier) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        usage.windows.forEach { window ->
            val remaining = (100.0 - window.usedPercent).coerceIn(0.0, 100.0)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(progress = { (remaining / 100).toFloat() }, modifier = Modifier.size(24.dp),
                    color = if (window.usedPercent >= 100) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                Column {
                    Text("${window.label.removeSuffix(" window")} · ${remaining.roundToInt()}% left",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    window.resetsAt?.let { reset ->
                        val date = remember(reset) {
                            val seconds = reset.epochSeconds - Clock.System.now().epochSeconds
                            if (seconds in 0..86_399) {
                                val hours = seconds / 3600
                                val minutes = (seconds % 3600) / 60
                                if (hours > 0) "in ${hours}h ${minutes}m" else "in ${minutes}m"
                            } else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(reset.toEpochMilliseconds()))
                        }
                        Text("Resets $date", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
