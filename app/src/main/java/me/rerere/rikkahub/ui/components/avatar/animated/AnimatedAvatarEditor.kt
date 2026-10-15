package me.rerere.rikkahub.ui.components.avatar.animated

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomThemeColorDialog

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AnimatedAvatarEditor(
    initialAvatar: Avatar.Animated?,
    onDismiss: () -> Unit,
    onSave: (Avatar.Animated) -> Unit,
) {
    val start = initialAvatar ?: Avatar.Animated.defaultGenerical()
    val initialShape = start.shape
    var shape by remember {
        mutableStateOf(
            if (initialShape == "teardrop" || initialShape == "droplet") "blob"
            else initialShape
        )
    }
    var eyeType by remember { mutableStateOf(start.eyeType) }
    // Canonical pair: the selected swatch is exactly the colour on screen.
    val startBody = remember { MarkColors.canonicalBody(start.colorHex, start.colorPreset, start.eyeType) }
    var colorHex by remember { mutableStateOf(startBody.first) }
    var colorPreset by remember { mutableStateOf(startBody.second) }
    var eyeColorHex by remember { mutableStateOf(initialAvatar?.eyeColorHex) }
    var eyeShade by remember { mutableStateOf(initialAvatar?.eyeShade ?: true) }
    // Personality knobs are set by the model, not here; keep whatever is stored.
    val traits = initialAvatar?.traits
    var showColorPicker by remember { mutableStateOf(false) }
    var showEyeColorPicker by remember { mutableStateOf(false) }

    if (showColorPicker) {
        CustomThemeColorDialog(
            initialHex = colorHex,
            onDismiss = { showColorPicker = false },
            onSave = { hex ->
                colorHex = MarkColors.normalizeHex(hex)
                colorPreset = null
                showColorPicker = false
            },
        )
    }
    if (showEyeColorPicker) {
        CustomThemeColorDialog(
            initialHex = eyeColorHex ?: MarkColors.defaultEyeHex(eyeType),
            onDismiss = { showEyeColorPicker = false },
            onSave = { hex ->
                eyeColorHex = MarkColors.normalizeHex(hex)
                showEyeColorPicker = false
            },
        )
    }

    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    // Open fully expanded: a half-expanded sheet hid the lower shape rows.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        AnimatedAvatarEditorContent(
            shape = shape,
            onShape = { shape = it },
            eyeType = eyeType,
            onEyeType = { eyeType = it },
            colorHex = colorHex,
            colorPreset = colorPreset,
            onColor = { hex, preset -> colorHex = hex; colorPreset = preset },
            eyeColorHex = eyeColorHex,
            onEyeColor = { eyeColorHex = it },
            eyeShade = eyeShade,
            onEyeShade = { eyeShade = it },
            traits = traits,
            onPickColor = { showColorPicker = true },
            onPickEyeColor = { showEyeColorPicker = true },
            onDismiss = onDismiss,
            onSave = {
                onSave(
                    Avatar.Animated(
                        shape = shape,
                        eyeType = eyeType.lowercase(),
                        colorHex = MarkColors.normalizeHex(colorHex),
                        colorPreset = colorPreset,
                        eyeColorHex = eyeColorHex?.let { MarkColors.normalizeHex(it) },
                        eyeShade = eyeShade,
                        traits = traits?.takeIf { it.isNotEmpty() },
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding(),
        )
    }
}

/** Mode default eye color (what `eyeColorHex == null` renders as). */
internal fun defaultEyeHex(eyeType: String): String = MarkColors.defaultEyeHex(eyeType)

/**
 * Eye-colour swatches, mode default first, no duplicates (Generical default = white,
 * Grok default = ink; both are presets too).
 */
internal fun eyeSwatches(eyeType: String): List<String> {
    val def = defaultEyeHex(eyeType).uppercase()
    val presets = MarkColors.EYE_PRESETS.values.filter { it.isNotBlank() }.map { it.uppercase() }
    return (listOf(def) + presets).distinct()
}

/** Sheet body — split out so the render harness can screenshot it without a popup. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnimatedAvatarEditorContent(
    shape: String,
    onShape: (String) -> Unit,
    eyeType: String,
    onEyeType: (String) -> Unit,
    colorHex: String,
    colorPreset: String?,
    onColor: (String, String?) -> Unit,
    eyeColorHex: String?,
    onEyeColor: (String?) -> Unit,
    eyeShade: Boolean = true,
    onEyeShade: (Boolean) -> Unit = {},
    traits: Map<String, Float>? = null,
    onPickColor: () -> Unit,
    onPickEyeColor: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    val density = LocalDensity.current
    var barHeight by remember { mutableStateOf(80.dp) }
    val cs = MaterialTheme.colorScheme
    val bodyColor = MarkColors.hexToColor(colorHex)
    val isGenerical = eyeType.equals("generical", ignoreCase = true)
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = barHeight + 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- Stage: the live avatar on a soft spotlight of its own colour.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(232.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(cs.surfaceContainerHigh)
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(bodyColor.copy(alpha = 0.22f), Color.Transparent),
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedMarkAvatar(
                    shapeId = shape,
                    eyeType = eyeType,
                    colorHex = colorHex,
                    colorPreset = colorPreset,
                    eyeColorHex = eyeColorHex,
                    eyeShade = eyeShade,
                    personality = Personality.resolve(eyeType, traits),
                    isLoading = false,
                    calmPreview = true,
                    modifier = Modifier.size(164.dp),
                )
                Text(
                    text = (if (isGenerical) stringResource(R.string.avatar_animated_eye_generical) else stringResource(R.string.avatar_animated_eye_grok)) +
                        "  ·  " + shape.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(CircleShape)
                        .background(cs.surfaceContainerHighest.copy(alpha = 0.7f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            // ---- Shape
            EditorSection(stringResource(R.string.avatar_animated_shape)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MarkShapes.SHAPES.forEach { s ->
                        val sel = shape == s
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (sel) bodyColor.copy(alpha = 0.16f) else cs.surfaceContainerHighest)
                                .then(if (sel) Modifier.border(2.dp, bodyColor, RoundedCornerShape(16.dp)) else Modifier)
                                .clickable { onShape(s) },
                            contentAlignment = Alignment.Center,
                        ) {
                            AnimatedMarkAvatar(
                                shapeId = s,
                                eyeType = eyeType,
                                colorHex = colorHex,
                                colorPreset = colorPreset,
                                eyeColorHex = eyeColorHex,
                                eyeShade = eyeShade,
                                isLoading = false,
                                calmPreview = true,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                }
            }

            // ---- Body colour
            EditorSection(stringResource(R.string.avatar_animated_color)) {
                SwatchRow {
                    MarkColors.PRESETS.forEach { (name, hex) ->
                        Swatch(MarkColors.hexToColor(hex), selected = colorPreset == name) { onColor(hex, name) }
                    }
                    CustomSwatch(current = if (colorPreset == null) bodyColor else null, contentDescription = stringResource(R.string.avatar_animated_color), onClick = onPickColor)
                }
            }

            // ---- Eyes: style, colour, shade
            EditorSection(stringResource(R.string.avatar_animated_eyes)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cs.surfaceContainerHighest)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for ((id, label) in listOf("generical" to R.string.avatar_animated_eye_generical, "grok" to R.string.avatar_animated_eye_grok)) {
                        val sel = eyeType.equals(id, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (sel) cs.surfaceContainerLow else Color.Transparent)
                                .clickable { onEyeType(id) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AnimatedMarkAvatar(
                                shapeId = "blob", eyeType = id, colorHex = colorHex, colorPreset = colorPreset,
                                isLoading = false, calmPreview = true, modifier = Modifier.size(26.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(label),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (sel) cs.onSurface else cs.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.avatar_animated_eye_color), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                val def = defaultEyeHex(eyeType)
                val current = MarkColors.normalizeHex(eyeColorHex?.takeIf { it.isNotBlank() } ?: def)
                val swatches = eyeSwatches(eyeType)
                SwatchRow {
                    swatches.forEach { hex ->
                        val isDefault = hex.equals(def, ignoreCase = true)
                        // Picking the mode default stores null so it tracks the mode.
                        Swatch(MarkColors.hexToColor(hex), selected = current.equals(hex, ignoreCase = true),
                            contentDescription = if (isDefault) stringResource(R.string.avatar_animated_eye_color_default) else null,
                        ) { onEyeColor(if (isDefault) null else hex) }
                    }
                    val custom = eyeColorHex?.takeIf { h -> swatches.none { it.equals(MarkColors.normalizeHex(h), ignoreCase = true) } }
                    CustomSwatch(current = custom?.let { MarkColors.hexToColor(it) }, contentDescription = stringResource(R.string.avatar_animated_eye_color), onClick = onPickEyeColor)
                }
                if (isGenerical) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onEyeShade(!eyeShade) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.avatar_animated_eye_shade),
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.Switch(checked = eyeShade, onCheckedChange = onEyeShade)
                    }
                }
            }
        }

        // Pinned actions; scroll content is padded by their measured height.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(cs.surfaceContainerLow)
                .onSizeChanged { barHeight = with(density) { it.height.toDp() } },
        ) {
            androidx.compose.material3.HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.avatar_cancel))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onSave,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.avatar_animated_save), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SwatchRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Colour dot; selection is a ring with a gap (no check glyph over the colour). */
@Composable
private fun Swatch(color: Color, selected: Boolean, contentDescription: String? = null, onClick: () -> Unit) {
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.border(2.dp, ring, CircleShape) else Modifier)
            .clickable(onClickLabel = contentDescription) { onClick() }
            .padding(if (selected) 5.dp else 3.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                when {
                    color.luminance() < 0.08f -> Modifier.border(1.dp, Color(0x33FFFFFF), CircleShape)
                    color.luminance() > 0.9f -> Modifier.border(1.dp, Color(0x22000000), CircleShape)
                    else -> Modifier
                }
            ),
    )
}

/** "Custom…" swatch: a colour-wheel ring; filled with the custom colour once one is chosen. */
@Composable
private fun CustomSwatch(current: Color?, contentDescription: String, onClick: () -> Unit) {
    val wheel = androidx.compose.ui.graphics.Brush.sweepGradient(
        listOf(Color(0xFFFF453A), Color(0xFFFFD60A), Color(0xFF30D158), Color(0xFF64D2FF), Color(0xFF0A84FF), Color(0xFFBF5AF2), Color(0xFFFF453A))
    )
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(if (current != null) 2.5.dp else 2.dp, wheel, CircleShape)
            .clickable { onClick() }
            .padding(5.dp)
            .clip(CircleShape)
            .background(current ?: MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Edit,
            contentDescription = contentDescription,
            tint = when {
                current == null -> MaterialTheme.colorScheme.onSurfaceVariant
                current.luminance() > 0.5f -> Color.Black
                else -> Color.White
            },
            modifier = Modifier.size(16.dp),
        )
    }
}
