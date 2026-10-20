package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.SubscriptionAccountType
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.setting.SubscriptionAccountVM
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.androidx.compose.koinViewModel

@Composable
fun SubscriptionAccountCard(provider: ProviderSetting.OpenAI, onEdit: (ProviderSetting.OpenAI) -> Unit) {
    val type = provider.subscriptionAccount ?: return
    val vm = koinViewModel<SubscriptionAccountVM>(key = "subscription-${provider.id}")
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberPremiumHaptics()
    val latestProvider by rememberUpdatedState(provider)
    val latestEdit by rememberUpdatedState(onEdit)
    var connectionOptions by remember(provider.id) { mutableStateOf(false) }

    LaunchedEffect(provider.id, provider.subscriptionAccountVersion) { vm.refresh(provider) }
    LaunchedEffect(state.completed) {
        if (state.completed) {
            val current = latestProvider
            val existing = current.models.associateBy { it.modelId }
            latestEdit(current.copy(subscriptionAccountVersion = current.subscriptionAccountVersion + 1,
                models = state.models?.map { existing[it.modelId]?.copy(providerOverwrite = null) ?: it } ?: current.models))
            vm.consumeCompletion()
            haptics.perform(HapticPattern.Success)
        }
    }
    LaunchedEffect(state.dnsFailure) { if (state.dnsFailure) connectionOptions = true }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(shape = AppShapes.CardLarge, colors = CardDefaults.cardColors(containerColor =
            if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // The provider logo already appears in the toolbar. Account controls need no second logo.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Account", style = MaterialTheme.typography.titleMedium)
                    Text(state.account ?: "Sign in with your ${type.displayName} subscription",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (state.prompt == null) "Checking your connection…" else "Finish sign-in in your browser, then return here.",
                        style = MaterialTheme.typography.bodyMedium)
                    state.prompt?.code?.let { code ->
                        Text("Your sign-in code", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(code, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        state.prompt?.let { prompt ->
                            OutlinedButton(onClick = { haptics.perform(HapticPattern.Pop); vm.openBrowser(prompt.url) },
                                shape = AppShapes.ButtonPill, modifier = Modifier.weight(1f)) {
                                Text("Open browser")
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Rounded.OpenInNew, null, modifier = Modifier.size(18.dp))
                            }
                        }
                        TextButton(onClick = { vm.cancel(); haptics.perform(HapticPattern.Cancel) }, shape = AppShapes.ButtonPill) { Text("Cancel") }
                    }
                } else {
                    Button(onClick = { haptics.perform(HapticPattern.Pop); vm.connect(latestProvider) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = AppShapes.ButtonPill) {
                        Text(if (state.account != null) "Reconnect ${type.displayName}" else "Continue with ${type.displayName}")
                    }
                    if (type == SubscriptionAccountType.CHATGPT) {
                        TextButton(onClick = { haptics.perform(HapticPattern.Pop); vm.connect(latestProvider, deviceCode = true) },
                            shape = AppShapes.ButtonPill, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Use a sign-in code instead") }
                    }
                    if (state.account != null) {
                        TextButton(onClick = { haptics.perform(HapticPattern.Pop); vm.signOut(latestProvider) },
                            modifier = Modifier.align(Alignment.End), shape = AppShapes.ButtonPill) { Text("Sign out on this device") }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { connectionOptions = !connectionOptions; haptics.perform(HapticPattern.Tick) },
                    shape = AppShapes.ButtonPill, modifier = Modifier.align(Alignment.Start)) { Text("Connection options") }
                if (connectionOptions) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Secure DNS fallback", style = MaterialTheme.typography.bodyMedium)
                            Text("If system DNS fails, resolve account service hostnames through Cloudflare. Other app traffic keeps your DNS settings.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HapticSwitch(checked = provider.subscriptionDnsFallback, enabled = !state.busy,
                            onCheckedChange = { latestEdit(latestProvider.copy(subscriptionDnsFallback = it)) })
                    }
                }
                Text(if (type == SubscriptionAccountType.CHATGPT)
                    "Uses your plan’s Codex allowance and available models. Your account’s limits apply."
                else "Uses your eligible Grok subscription. Your account’s model availability and limits apply.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.account != null) ProviderUsageCard(provider)
    }
}
