package me.rerere.rikkahub.ui.components.avatar.animated

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.ui.theme.findPresetTheme
import me.rerere.rikkahub.ui.theme.withLastChatAmoledSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ContrastGuardRenderTest {
    private fun scheme(dark: Boolean) = findPresetTheme(PresetThemes[0].id).getColorScheme(dark).withLastChatAmoledSurface(dark)

    @Test
    fun normalColoursNeverOutlined() {
        val bgs = listOf(scheme(true).background, scheme(false).background, scheme(true).surfaceContainerHighest, scheme(false).surfaceContainerHighest)
        val normals = listOf(0xFF009FE0, 0xFFE53935, 0xFFFFC107, 0xFF43A047, 0xFF8E24AA, 0xFF757575, 0xFFFF7043).map { Color(it) }
        for (b in bgs) for (c in normals) assertEquals("${c.toArgb()} on ${b.toArgb()}", 0f, ContrastGuard.strength(c, b), 0.001f)
        // White eyes on a normal body, and body colour on itself.
        for (c in normals) assertEquals(0f, ContrastGuard.strength(Color.White, c), 0.001f)
        assertTrue(ContrastGuard.strength(Color(0xFF111111), Color.Black) > 0.9f)
        assertTrue(ContrastGuard.strength(Color.White, Color(0xFFF8F8F8)) > 0.9f)
    }

    @Test
    fun renderContrastCases() {
        val dir = System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() } ?: return
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val dk = scheme(true); val lt = scheme(false)
        data class Case(val label: String, val shape: String, val grok: Boolean, val body: Color, val eye: Color, val bg: Color, val frame: MotionFrame = MotionFrame(), val calm: Boolean = true)
        val spin = MotionFrame(spinYaw = 38f)
        val cases = listOf(
            // edge
            Case("E black on AMOLED", "blob", false, Color.Black, Color.White, dk.background),
            Case("E white on light", "square", true, Color.White, Color(0xFF202020), lt.background),
            Case("E =editor dark", "flower", false, dk.surfaceContainerHighest, Color.White, dk.surfaceContainerHighest),
            Case("E =editor light", "heart", true, lt.surfaceContainerHighest, Color(0xFF202020), lt.surfaceContainerHighest),
            Case("E eyes=body grok", "cloud", true, Color(0xFF009FE0), Color(0xFF05A3E2), dk.background),
            Case("E eyes=body gen", "clover", false, Color(0xFFE0E0E0), Color(0xFFE6E6E6), dk.background),
            Case("E spin", "star", true, Color.Black, Color.White, dk.background, spin, false),
            Case("X spin noguard", "star", true, Color.Black, Color.White, dk.background, spin, false),
            // normal
            Case("N blue dark", "blob", false, Color(0xFF009FE0), Color.White, dk.background),
            Case("N blue light", "square", true, Color(0xFF009FE0), Color.White, lt.background),
            Case("N grey dark", "flower", false, Color(0xFF757575), Color.White, dk.background),
            Case("N yellow light", "heart", true, Color(0xFFFFC107), Color(0xFF202020), lt.background),
            Case("N red editor", "cloud", false, Color(0xFFE53935), Color.White, lt.surfaceContainerHighest),
            Case("N dark grey AMOLED", "clover", true, Color(0xFF3A3A3A), Color.White, dk.background),
            Case("N spin", "star", true, Color(0xFF009FE0), Color.White, dk.background, spin, false),
        )
        val px = 300; val cols = 8
        val img = ImageBitmap(px * cols, px * 2)
        val canvas = androidx.compose.ui.graphics.Canvas(img)
        val scope = CanvasDrawScope()
        val log = StringBuilder("label,bodyK,eyeK\n")
        for ((i, c) in cases.withIndex()) {
            canvas.save(); canvas.translate(((i % cols) * px).toFloat(), ((i / cols) * px).toFloat())
            scope.draw(Density(3f), LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat())) {
                drawRect(c.bg)
                ContrastGuard.set(c.body, c.eye, if (c.label.startsWith("X")) Color.White else c.bg)
                log.append("${c.label},${ContrastGuard.bodyK},${ContrastGuard.eyeK}\n")
                drawMark(MarkShapes.resolve(c.shape)!!, c.shape, c.grok, c.frame, c.calm, 1f, c.body, c.eye, MarkColors.genericalEyeFade(c.body, c.eye, 1f))
                ContrastGuard.reset()
            }
            canvas.restore()
        }
        File(dir).mkdirs()
        save(img, File(dir, "contrast_cases.png")); File(dir, "contrast_cases.csv").writeText(log.toString())

        // Fade: body glides from blue into the AMOLED background (what the animated colour does).
        val n = 8
        val fade = ImageBitmap(px * n, px * 2); val fc = androidx.compose.ui.graphics.Canvas(fade)
        val flog = StringBuilder("step,bodyK\n")
        for (row in 0..1) for (k in 0 until n) {
            val t = k / (n - 1f)
            val bg = if (row == 0) dk.background else lt.background
            val body = androidx.compose.ui.graphics.lerp(Color(0xFF009FE0), bg, t)
            fc.save(); fc.translate((k * px).toFloat(), (row * px).toFloat())
            scope.draw(Density(3f), LayoutDirection.Ltr, fc, Size(px.toFloat(), px.toFloat())) {
                drawRect(bg); ContrastGuard.set(body, Color.White, bg)
                flog.append("$row:$t,${ContrastGuard.bodyK}\n")
                drawMark(MarkShapes.resolve("blob")!!, "blob", false, MotionFrame(), true, 1f, body, Color.White, MarkColors.genericalEyeFade(body, Color.White, 1f))
                ContrastGuard.reset()
            }
            fc.restore()
        }
        save(fade, File(dir, "contrast_fade.png")); File(dir, "contrast_fade.csv").writeText(flog.toString())

        // Mid-morph with the guard on (black on AMOLED, square -> star).
        val us = listOf(0f, 0.35f, 0.65f, 1f)
        val mi = ImageBitmap(px * us.size, px); val mc = androidx.compose.ui.graphics.Canvas(mi)
        val h = MorphHolder(); val a = MarkShapes.resolve("square")!!; val b = MarkShapes.resolve("star")!!
        val B = Color.Black; val W = Color.White; val G = MarkColors.genericalEyeFade(B, W, 1f)
        val off = ImageBitmap(px, px); val oc = androidx.compose.ui.graphics.Canvas(off)
        fun d(cv: androidx.compose.ui.graphics.Canvas, block: DrawScope.() -> Unit) = scope.draw(Density(3f), LayoutDirection.Ltr, cv, Size(px.toFloat(), px.toFloat())) {
            drawRect(dk.background); ContrastGuard.set(B, W, dk.background); block(); ContrastGuard.reset()
        }
        d(oc) { drawLive(h, a, "square", false, MotionFrame(), true, B, W, G, 1f) }
        h.signatureChanged(); d(oc) { drawLive(h, b, "star", true, MotionFrame(), true, B, W, G, 0f) }; h.forceZero = false
        for ((k, u) in us.withIndex()) { mc.save(); mc.translate((k * px).toFloat(), 0f); d(mc) { drawLive(h, b, "star", true, MotionFrame(), true, B, W, G, u) }; mc.restore() }
        save(mi, File(dir, "contrast_morph.png"))
    }

    @Test
    fun renderSurfaces() {
        val dir = System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() } ?: return
        val w = 1100; val hgt = 520
        val img = ImageBitmap(w, hgt * 2); val cv = androidx.compose.ui.graphics.Canvas(img)
        val scope = CanvasDrawScope()
        val log = StringBuilder()
        for ((row, dark) in listOf(false, true).withIndex()) {
            val s = scheme(dark)
            log.append("${if (dark) "dark" else "light"}: drawer=${hex(s.surfaceContainerLow)} searchBar(surfaceContainerHighest)=${hex(s.surfaceContainerHighest)} editorBg=${hex(s.background)} editorContainer=${hex(s.surfaceContainerHighest)}\n")
            cv.save(); cv.translate(0f, (row * hgt).toFloat())
            scope.draw(Density(3f), LayoutDirection.Ltr, cv, Size(w.toFloat(), hgt.toFloat())) {
                // Left: drawer sheet with its search field.
                drawRect(s.surfaceContainerLow, size = Size(520f, hgt.toFloat()))
                drawRoundRect(s.surfaceContainerHighest, Offset(30f, 40f), Size(460f, 150f), CornerRadius(75f))
                for (k in 0..3) drawRoundRect(s.onSurface.copy(alpha = 0.10f), Offset(40f, 240f + k * 65f), Size(380f, 34f), CornerRadius(17f))
                // Right: editor page background with its containers.
                drawRect(s.background, Offset(560f, 0f), Size(540f, hgt.toFloat()))
                drawRoundRect(s.surfaceContainerHighest, Offset(590f, 40f), Size(480f, 250f), CornerRadius(84f))
                drawRoundRect(s.surfaceContainerHighest, Offset(590f, 320f), Size(480f, 160f), CornerRadius(66f))
            }
            cv.restore()
        }
        File(dir).mkdirs(); save(img, File(dir, "surfaces_drawer_vs_editor.png")); File(dir, "surfaces.txt").writeText(log.toString())
    }

    private fun hex(c: Color) = "#%08X".format(c.toArgb())
    private fun save(i: ImageBitmap, f: File) = f.outputStream().use { i.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
}
