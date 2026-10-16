package me.rerere.rikkahub.ui.components.avatar.animated

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Engine16.2: the morph renderer must draw exactly what drawMark draws at its endpoints
 * (no size/position pop when a shape or eye-style morph starts or ends), also turned.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class MorphSeamTest {
    private fun cover(draw: DrawScope.() -> Unit): Int {
        val img = ImageBitmap(300, 300)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(300f, 300f)) {
            translate(75f, 75f) { drawContext.size = Size(150f, 150f); draw() }
        }
        val b = img.asAndroidBitmap()
        var n = 0
        for (y in 0 until 300) for (x in 0 until 300) if ((b.getPixel(x, y) ushr 24) > 128) n++
        return n
    }

    @Test
    fun morphEndpointsMatchMark() {
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val poses = listOf(
            MotionFrame(),
            MotionFrame(body = BodyPose(pitch = 6f, yaw = -14f, roll = 3f, posY = 0.05f), headLookX = 0.3f, headLookY = 0.2f),
            MotionFrame(body = BodyPose(pitch = -4f, yaw = 18f, roll = -4f), headLookX = -0.4f, headLookY = -0.1f),
        )
        for (id in MarkShapes.SHAPES) for (grok in listOf(false, true)) for ((pi, f) in poses.withIndex()) {
            val sh = MarkShapes.resolve(id)!!
            val mark = cover { drawMark(sh, id, grok, f, false, 1f, Color.Blue, Color.White, Color.Gray) }
            val morph = cover {
                val g = MarkGeom().also { drawMark(sh, id, grok, f, false, 1f, Color.Blue, Color.White, Color.Gray, it) }
                drawMorph(g, MorphPts.of(g), g, 0.5f, MarkGeom())
            }
            val d = kotlin.math.abs(mark - morph).toFloat() / mark
            assertTrue("$id grok=$grok pose=$pi coverage mark=$mark morph=$morph", d < 0.03f)
        }
    }

    private fun pixels(draw: DrawScope.() -> Unit): IntArray {
        val img = ImageBitmap(300, 300)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(300f, 300f)) { draw() }
        val b = img.asAndroidBitmap(); val px = IntArray(300 * 300); b.getPixels(px, 0, 300, 0, 0, 300, 300); return px
    }
    /** Pixels that differ visibly (any channel by > 64), not just in edge antialiasing. */
    private fun diff(a: IntArray, b: IntArray) = a.indices.count { i ->
        val x = a[i]; val y = b[i]
        (0..3).any { s -> kotlin.math.abs(((x ushr (s * 8)) and 255) - ((y ushr (s * 8)) and 255)) > 64 }
    }

    /** Engine16.2c: the first frame after a shape change is exactly the previous shape. */
    @Test
    fun firstMorphFrameIsPreviousShape() {
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val B = Color(0xFF2A92FE); val W = Color.White; val G = Color.Gray
        val f = MotionFrame(body = BodyPose(pitch = 3f, yaw = -10f, roll = 2f), headLookX = 0.2f)
        val pairs = listOf("square" to "cloud", "blob" to "heart", "star" to "house", "heart" to "square")
        for ((a, b) in pairs) for (grok in listOf(false, true)) {
            val sa = MarkShapes.resolve(a)!!; val sb = MarkShapes.resolve(b)!!
            val h = MorphHolder()
            pixels { drawLive(h, sa, a, grok, f, false, B, W, G, 1f) } // steady on the old shape
            h.signatureChanged()
            // animValue is still the stale 1f on the change frame (the bug made this the target).
            val frame0 = pixels { drawLive(h, sb, b, grok, f, false, B, W, G, 1f) }
            val prev = pixels { drawMark(sa, a, grok, f, false, 1f, B, W, G) }
            val target = pixels { drawMark(sb, b, grok, f, false, 1f, B, W, G) }
            val d = diff(frame0, prev); val dt = diff(frame0, target)
            System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() }?.let { dir ->
                val bm = android.graphics.Bitmap.createBitmap(900, 300, android.graphics.Bitmap.Config.ARGB_8888)
                bm.setPixels(prev, 0, 300, 0, 0, 300, 300); bm.setPixels(frame0, 0, 300, 300, 0, 300, 300)
                val dd = IntArray(90000) { i -> if (diff(intArrayOf(prev[i]), intArrayOf(frame0[i])) > 0) 0xFFFF0040.toInt() else 0xFF000000.toInt() }
                bm.setPixels(dd, 0, 300, 600, 0, 300, 300)
                File(dir).mkdirs(); File(dir, "frame0_${a}_${b}_$grok.png").outputStream().use { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            // ≤1 % of pixels may differ (antialiasing of the resampled outline), and frame 0
            // must be far from the target.
            assertTrue("$a→$b grok=$grok frame0 vs previous differs in $d px (a 1 px outline ring is ~1100)", d < 1100)
            println("FRAME0 $a→$b grok=$grok vsPrev=$d vsTarget=$dt")
            assertTrue("$a→$b grok=$grok frame0 looks like the target ($dt px)", dt > d * 3)
        }
    }

    /** Engine16.2c: white is the default eye in both styles, even on a near-white body. */
    @Test
    fun eyeDefaultsWhiteAndSwatchesConsistent() {
        for (mode in listOf("grok", "generical")) for (body in listOf("#2A92FE", "#F2F2F7", "#000000", "#FFFFFF")) {
            org.junit.Assert.assertEquals("$mode on $body", Color.White, MarkColors.eyeColor(mode, null, body, null))
        }
        org.junit.Assert.assertEquals(eyeSwatches("grok"), eyeSwatches("generical"))
        org.junit.Assert.assertEquals("#FFFFFF", eyeSwatches("grok").first())
    }

    /** Strip proving no flash: steady, change frame 0, then the morph. */
    @Test
    fun renderChangeStrip() {
        val outDir = System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() } ?: return
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val B = Color(0xFF2A92FE); val W = Color.White; val G = Color.Gray
        for ((a, b, grok) in listOf(Triple("square", "cloud", false), Triple("blob", "heart", true), Triple("star", "house", false))) {
            val sa = MarkShapes.resolve(a)!!; val sb = MarkShapes.resolve(b)!!
            val cells = 14; val c = 160
            val img = ImageBitmap(c * cells, c)
            val canvas = androidx.compose.ui.graphics.Canvas(img)
            canvas.drawRect(0f, 0f, (c * cells).toFloat(), c.toFloat(), androidx.compose.ui.graphics.Paint().apply { color = Color(0xFF14171C) })
            val h = MorphHolder()
            for (k in 0 until cells) {
                val f = MotionFrame(body = BodyPose(yaw = -10f + k, pitch = 2f))
                val ease = { x: Float -> val q = x.coerceIn(0f, 1f); q * q * (3 - 2 * q) }
                canvas.save(); canvas.translate((k * c).toFloat() + c * 0.14f, c * 0.14f)
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(c * 0.72f, c * 0.72f)) {
                    when {
                        k < 3 -> drawLive(h, sa, a, grok, f, false, B, W, G, 1f)
                        k == 3 -> { h.signatureChanged(); drawLive(h, sb, b, grok, f, false, B, W, G, 1f) }
                        else -> { h.forceZero = false; drawLive(h, sb, b, grok, f, false, B, W, G, ease((k - 3) / 9f)) }
                    }
                }
                canvas.restore()
            }
            File(outDir).mkdirs()
            File(outDir, "change_strip_${a}_to_${b}_${if (grok) "grok" else "generical"}.png").outputStream().use { img.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
