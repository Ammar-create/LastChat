package me.rerere.rikkahub.ui.components.avatar.animated

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import kotlin.math.sqrt

/**
 * Engine17.2 extreme-contrast safety outline.
 *
 * When the body is nearly the same colour as what it sits on (chat background / editor
 * surface), or the eyes nearly match the body, a thin flat outline in a contrasting tone
 * keeps the silhouette readable. Only extreme cases: it needs BOTH a tiny luminance
 * contrast ratio AND a tiny RGB distance, so any normal colour pairing scores 0 and is
 * drawn exactly as before. Strength is a smoothstep of the (already animated) colours,
 * so it fades in/out as colours glide; it strokes the same projected paths the fill uses,
 * so it follows morphs, turns and spins.
 */
internal object ContrastGuard {
    /** Backdrop behind the avatar; null = MaterialTheme background. */
    val LocalAvatarBackdrop = staticCompositionLocalOf<Color?> { null }

    // Per-draw state (drawing is single-threaded). Reset after every Canvas draw.
    var bodyK = 0f; var bodyLine = Color.Transparent
    var eyeK = 0f; var eyeLine = Color.Transparent

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t)
    }

    fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance(); val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    fun rgbDistance(a: Color, b: Color): Float {
        val dr = a.red - b.red; val dg = a.green - b.green; val db = a.blue - b.blue
        return sqrt((dr * dr + dg * dg + db * db) / 3f)
    }

    /** 0 = normal (no outline) … 1 = colours practically identical. */
    fun strength(fg: Color, bg: Color): Float {
        val lumK = 1f - smooth(1.12f, 1.30f, contrastRatio(fg, bg))
        val distK = 1f - smooth(0.06f, 0.13f, rgbDistance(fg, bg))
        return lumK * distK
    }

    /** Flat contrasting tone: the colour pushed toward black on light pairs, white on dark. */
    fun lineFor(fg: Color, bg: Color): Color {
        val dark = (fg.luminance() + bg.luminance()) / 2f < 0.36f
        return androidx.compose.ui.graphics.lerp(fg, if (dark) Color.White else Color.Black, if (dark) 0.42f else 0.30f)
    }

    fun set(body: Color, eye: Color, backdrop: Color) {
        bodyK = strength(body, backdrop); bodyLine = lineFor(body, backdrop)
        eyeK = strength(eye, body); eyeLine = lineFor(eye, body)
    }

    fun reset() { bodyK = 0f; eyeK = 0f }

    fun DrawScope.bodyOutline(p: Path, he: Float) {
        if (bodyK < 0.01f) return
        drawPath(p, bodyLine.copy(alpha = bodyLine.alpha * bodyK), style = Stroke(width = he * 0.036f))
    }

    fun DrawScope.eyeOutline(p: Path, he: Float) {
        if (eyeK < 0.01f) return
        drawPath(p, eyeLine.copy(alpha = eyeLine.alpha * eyeK), style = Stroke(width = he * 0.030f))
    }
}
