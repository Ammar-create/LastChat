package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.context.ContextCompressionStyle
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ContextManagementMode
import me.rerere.rikkahub.data.model.effectiveContextManagementMode
import me.rerere.rikkahub.data.model.withContextManagementMode
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.motion.ExpandableContent
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupInputItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.AppSpacing

@Composable
fun AssistantContextManagementSubPage(
    assistant: Assistant,
    hasSummarizerModelConfigured: Boolean,
    onUpdate: (Assistant) -> Unit,
    onNavigateToLorebooks: () -> Unit,
    onNavigateToSummarizerSettings: () -> Unit,
) {
    var showAdvanced by remember(assistant.id) { mutableStateOf(false) }
    val mode = assistant.effectiveContextManagementMode
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        SettingsGroup(title = "Summarization") {
            ContextChoice(
                "Automatic", "Summarize older exchanges as needed.",
                mode == ContextManagementMode.AUTOMATIC,
            ) { onUpdate(assistant.withContextManagementMode(ContextManagementMode.AUTOMATIC)) }
            ContextChoice(
                "Only when I ask", null,
                mode == ContextManagementMode.MANUAL,
            ) { onUpdate(assistant.withContextManagementMode(ContextManagementMode.MANUAL)) }
        }
        SettingsGroup(title = "What matters most") {
            ContextCompressionStyle.entries.forEach { style ->
                ContextChoice(
                    style.title(), null, assistant.contextCompressionStyle == style,
                ) { onUpdate(assistant.copy(contextCompressionStyle = style)) }
            }
        }
        SettingsGroup(title = "Options") {
            SettingGroupItem(
                title = "Advanced",
                trailing = {
                    Icon(if (showAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                },
                onClick = { showAdvanced = !showAdvanced },
            )
            ExpandableContent(visible = showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
                    SettingGroupInputItem(
                        title = "Keep recent exchanges",
                        subtitle = "Adaptive follows the model’s context window.",
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            listOf<Int?>(null, 6, 12, 20).forEach { count ->
                                FilterChip(
                                    selected = assistant.contextRecentTurns == count,
                                    onClick = { onUpdate(assistant.copy(contextRecentTurns = count)) },
                                    label = { Text(count?.toString() ?: "Adaptive") },
                                    shape = AppShapes.ButtonPill,
                                )
                            }
                        }
                    }
                    SettingGroupInputItem(
                        title = "Details to preserve",
                    ) {
                        OutlinedTextField(
                            value = assistant.contextSummaryInstructions,
                            onValueChange = { onUpdate(assistant.copy(contextSummaryInstructions = it.take(4_000))) },
                            placeholder = { Text("Relationships, decisions, unfinished plans…") },
                            minLines = 3, maxLines = 6,
                            shape = AppShapes.InputField,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SettingGroupItem(
                        title = "Summary model",
                        subtitle = if (hasSummarizerModelConfigured) "Dedicated model"
                            else "Chat model",
                        onClick = onNavigateToSummarizerSettings,
                    )
                }
            }
        }
        SettingsGroup(title = "Additional context") {
            SettingGroupItem(
                title = "Time awareness",
                subtitle = "Current time and gaps between messages.",
                trailing = {
                    HapticSwitch(
                        checked = assistant.enableTimeAwareness,
                        onCheckedChange = { onUpdate(assistant.copy(enableTimeAwareness = it)) },
                    )
                },
                onClick = { onUpdate(assistant.copy(enableTimeAwareness = !assistant.enableTimeAwareness)) },
            )
            SettingGroupItem(
                title = "Lorebooks",
                subtitle = "Bring in relevant character and world details when needed.",
                icon = { Icon(Icons.Rounded.Book, null) },
                onClick = onNavigateToLorebooks,
            )
        }
        Spacer(Modifier.height(AppSpacing.xxl))
    }
}

@Composable
private fun ContextChoice(title: String, description: String?, selected: Boolean, onClick: () -> Unit) {
    SettingGroupItem(
        title = title,
        subtitle = description,
        trailing = { RadioButton(selected = selected, onClick = null) },
        onClick = onClick,
    )
}

internal fun ContextCompressionStyle.title(): String = when (this) {
    ContextCompressionStyle.MORE_DETAIL -> "More detail"
    ContextCompressionStyle.BALANCED -> "Balanced"
    ContextCompressionStyle.MORE_ROOM -> "More room"
}
