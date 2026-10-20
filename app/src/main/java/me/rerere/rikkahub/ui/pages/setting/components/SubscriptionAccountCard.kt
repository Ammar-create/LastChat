package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
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
import me.rerere.rikkahub.ui.pages.setting.SubscriptionAccountState
import me.rerere.rikkahub.ui.pages.setting.SubscriptionAccountVM
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.androidx.compose.koinViewModel

@Composable
fun SubscriptionAccountCard(provider: ProviderSetting.OpenAI, onEdit: (ProviderSetting.OpenAI) -> Unit) {
    if (provider.subscriptionAccount == null) return
    val vm = koinViewModel<SubscriptionAccountVM>(key = "subscription-${provider.id}")
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberPremiumHaptics()
    val latestProvider by rememberUpdatedState(provider)
    val latestEdit by rememberUpdatedState(onEdit)

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
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SubscriptionAccountContent(provider, state, onEdit,
            onConnect = { vm.connect(latestProvider, deviceCode = it) },
            onCancel = vm::cancel, onOpenBrowser = vm::openBrowser,
            onSignOut = { vm.signOut(latestProvider) }, onFeedback = haptics::perform)
        if (state.account != null) ProviderUsageCard(provider)
    }
}

/** Stateless account UI shared with the visual review harness. */
@Composable
internal fun SubscriptionAccountContent(
    provider: ProviderSetting.OpenAI,
    state: SubscriptionAccountState,
    onEdit: (ProviderSetting.OpenAI) -> Unit,
    onConnect: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    onSignOut: () -> Unit,
    onFeedback: (HapticPattern) -> Unit = {},
) {
    val type = provider.subscriptionAccount ?: return
    val latestProvider by rememberUpdatedState(provider)
    val latestEdit by rememberUpdatedState(onEdit)
    var connectionOptions by remember(provider.id) { mutableStateOf(false) }
    LaunchedEffect(state.dnsFailure) { if (state.dnsFailure) connectionOptions = true }
    var accountMenu by remember(provider.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(shape = AppShapes.CardLarge, colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Account", style = MaterialTheme.typography.titleMedium)
                        Text(state.account ?: "Connect your ${type.displayName} account",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.account != null && !state.busy) Text("Connected · Plan allowance",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (state.account != null || type == SubscriptionAccountType.CHATGPT) Box {
                        IconButton(onClick = { accountMenu = true; onFeedback(HapticPattern.Tick) }, enabled = !state.busy) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Account options")
                        }
                        DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                            if (state.account != null) DropdownMenuItem(text = { Text("Reconnect account") }, onClick = {
                                accountMenu = false; onFeedback(HapticPattern.Pop); onConnect(false)
                            })
                            if (type == SubscriptionAccountType.CHATGPT) DropdownMenuItem(text = { Text("Sign in with a code") }, onClick = {
                                accountMenu = false; onFeedback(HapticPattern.Pop); onConnect(true)
                            })
                            if (state.account != null) DropdownMenuItem(text = { Text("Sign out") }, onClick = {
                                accountMenu = false; onFeedback(HapticPattern.Pop); onSignOut()
                            })
                        }
                    }
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
                            OutlinedButton(onClick = { onFeedback(HapticPattern.Pop); onOpenBrowser(prompt.url) },
                                shape = AppShapes.ButtonPill) {
                                Text("Open browser")
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Rounded.OpenInNew, null, modifier = Modifier.size(18.dp))
                            }
                        }
                        TextButton(onClick = { onCancel(); onFeedback(HapticPattern.Cancel) }, shape = AppShapes.ButtonPill) { Text("Cancel") }
                    }
                } else if (state.account == null) {
                    Text(if (type == SubscriptionAccountType.CHATGPT) "Use Codex models included in your ChatGPT plan."
                        else "Use models included in your eligible Grok plan.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { onFeedback(HapticPattern.Pop); onConnect(false) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = AppShapes.ButtonPill) {
                        Text("Continue with ${type.displayName}")
                    }
                }
                state.error?.let { error ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = AppShapes.concentricShape(28.dp, 16.dp)) {
                        Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().clickable {
                    connectionOptions = !connectionOptions; onFeedback(HapticPattern.Tick)
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Connection", style = MaterialTheme.typography.bodyMedium)
                        Text(if (provider.subscriptionDnsFallback) "Secure DNS fallback enabled" else "System DNS",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (connectionOptions) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (connectionOptions) "Hide connection options" else "Show connection options")
                }
                if (connectionOptions) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Secure DNS fallback", style = MaterialTheme.typography.bodyMedium)
                        Text("If your DNS blocks account services, try Cloudflare for those hostnames. Other app traffic keeps system DNS.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HapticSwitch(checked = provider.subscriptionDnsFallback, enabled = !state.busy,
                        onCheckedChange = { latestEdit(latestProvider.copy(subscriptionDnsFallback = it)) })
                }
            }
        }
    }
}
