package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LastChatColorSchemeTest {
    private fun surfaces(scheme: ColorScheme) = with(scheme) {
        listOf(background, surface, surfaceContainerLowest, surfaceContainerLow,
            surfaceContainer, surfaceContainerHigh, surfaceContainerHighest)
    }

    @Test fun everyPresetHasReadableTextAndActionsOnEverySurface() {
        for (id in listOf("black", "sakura", "seafoam_mint", "ocean", "spring", "autumn")) {
            for (dark in listOf(false, true)) {
                val scheme = presetColorScheme(id, dark).withLastChatContrast(dark)
                with(scheme) {
                    for (surface in surfaces(scheme)) {
                        for (text in listOf(onSurface, onSurfaceVariant, primary, secondary, tertiary, error)) {
                            assertTrue(colorContrast(text, surface) >= 4.49f, "$id dark=$dark: $text on $surface")
                        }
                    }
                    for ((foreground, background) in listOf(onPrimary to primary, onSecondary to secondary,
                        onTertiary to tertiary, onPrimaryContainer to primaryContainer,
                        onSecondaryContainer to secondaryContainer, onTertiaryContainer to tertiaryContainer,
                        onError to error, onErrorContainer to errorContainer, inverseOnSurface to inverseSurface)) {
                        assertTrue(colorContrast(foreground, background) >= 4.49f, "$id dark=$dark: paired role")
                    }
                    val ladder = listOf(surfaceContainerLowest, surfaceContainerLow, surfaceContainer,
                        surfaceContainerHigh, surfaceContainerHighest).map { it.luminance() }
                    ladder.zipWithNext().forEach { (a, b) -> assertTrue(if (dark) a < b else a > b) }
                    if (dark) assertEquals(Color.Black, background)
                }
            }
        }
    }

    @Test fun inconsistentOemNeutralRolesAreRebuiltWithoutLosingAccentHue() {
        val source = darkColorScheme(primary = Color(0xFF35417B), surface = Color(0xFF302470),
            onSurface = Color(0xFF313131), onSurfaceVariant = Color(0xFF202020),
            surfaceContainerLow = Color.White, surfaceContainerHighest = Color.Black)
        val fixed = source.withLastChatContrast(true, 1f)
        assertTrue(fixed.primary.blue > fixed.primary.red)
        surfaces(fixed).forEach {
            assertTrue(colorContrast(fixed.onSurface, it) >= 6.99f)
            assertTrue(colorContrast(fixed.onSurfaceVariant, it) >= 6.99f)
        }
        assertTrue(fixed.surfaceContainerLowest.luminance() < fixed.surfaceContainerHighest.luminance())
    }
}
