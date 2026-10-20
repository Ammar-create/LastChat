package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.pow

/**
 * Stable Material surface roles, independent of OEM dynamic-palette tone changes.
 * Keep wallpaper/preset accents and the AMOLED canvas; use a quiet, ordered neutral
 * ladder for cards, sheets and dialogs. All normal text pairs meet WCAG AA.
 */
fun ColorScheme.withLastChatContrast(dark: Boolean, contrast: Float = 0f): ColorScheme {
    val textRatio = 4.5f + 2.5f * contrast.coerceIn(0f, 1f)
    val canvas = if (dark) Color.Black else neutralTone(surface, 98f)
    val lowest = neutralTone(surface, if (dark) 4f else 100f)
    val low = neutralTone(surface, if (dark) 10f else 96f)
    val container = neutralTone(surface, if (dark) 12f else 94f)
    val high = neutralTone(surface, if (dark) 17f else 92f)
    val highest = neutralTone(surface, if (dark) 22f else 90f)
    val surfaces = listOf(canvas, lowest, low, container, high, highest)
    val readablePrimary = primary.contrastOn(surfaces, textRatio)
    val readableSecondary = secondary.contrastOn(surfaces, textRatio)
    val readableTertiary = tertiary.contrastOn(surfaces, textRatio)
    val readableError = error.contrastOn(surfaces, textRatio)
    return copy(
        background = canvas, surface = canvas,
        surfaceDim = if (dark) canvas else neutralTone(surface, 87f),
        surfaceBright = if (dark) neutralTone(surface, 24f) else canvas,
        surfaceContainerLowest = lowest, surfaceContainerLow = low,
        surfaceContainer = container, surfaceContainerHigh = high,
        surfaceContainerHighest = highest, surfaceVariant = highest,
        onBackground = onBackground.contrastOn(surfaces, textRatio),
        onSurface = onSurface.contrastOn(surfaces, textRatio),
        onSurfaceVariant = onSurfaceVariant.contrastOn(surfaces, textRatio),
        outline = outline.contrastOn(listOf(canvas, low), 3f),
        outlineVariant = outlineVariant.contrastOn(listOf(canvas, low), 1.5f),
        primary = readablePrimary, onPrimary = onPrimary.contrastOn(listOf(readablePrimary), textRatio),
        secondary = readableSecondary, onSecondary = onSecondary.contrastOn(listOf(readableSecondary), textRatio),
        tertiary = readableTertiary, onTertiary = onTertiary.contrastOn(listOf(readableTertiary), textRatio),
        error = readableError, onError = onError.contrastOn(listOf(readableError), textRatio),
        onPrimaryContainer = onPrimaryContainer.contrastOn(listOf(primaryContainer), textRatio),
        onSecondaryContainer = onSecondaryContainer.contrastOn(listOf(secondaryContainer), textRatio),
        onTertiaryContainer = onTertiaryContainer.contrastOn(listOf(tertiaryContainer), textRatio),
        onErrorContainer = onErrorContainer.contrastOn(listOf(errorContainer), textRatio),
        inverseOnSurface = inverseOnSurface.contrastOn(listOf(inverseSurface), textRatio),
        inversePrimary = inversePrimary.contrastOn(listOf(inverseSurface), textRatio),
        surfaceTint = readablePrimary,
    )
}

fun colorContrast(foreground: Color, background: Color): Float {
    val a = foreground.luminance()
    val b = background.luminance()
    return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}

/** Preserve the original hue unless its contrast needs repair. */
private fun Color.contrastOn(backgrounds: List<Color>, minimum: Float): Color {
    val opaque = copy(alpha = 1f)
    fun score(color: Color) = backgrounds.minOf { colorContrast(color, it) }
    if (score(opaque) >= minimum) return opaque
    val target = if (score(Color.White) >= score(Color.Black)) Color.White else Color.Black
    var start = 0f
    var end = 1f
    repeat(18) {
        val middle = (start + end) / 2f
        if (score(lerp(opaque, target, middle)) >= minimum) end = middle else start = middle
    }
    return lerp(opaque, target, end)
}

/** L* tone with a bounded neutral tint: saturated OEM surfaces cannot flood the UI. */
private fun neutralTone(seed: Color, tone: Float): Color {
    val y = if (tone > 8f) ((tone + 16f) / 116f).pow(3) else tone / 903.2963f
    val gray = if (y <= 0.0031308f) y * 12.92f else 1.055f * y.pow(1f / 2.4f) - 0.055f
    val average = (seed.red + seed.green + seed.blue) / 3f
    fun channel(value: Float) = (gray + (value - average).coerceIn(-0.018f, 0.018f)).coerceIn(0f, 1f)
    return Color(channel(seed.red), channel(seed.green), channel(seed.blue))
}
