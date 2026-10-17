package me.rerere.rikkahub.ui.components.avatar.animated

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Engine21.1 steering (b): the reopen settle after a full blink must stay microscopic
 * (peak ≤ 2 % of eye height, gone within 0.4 s). With lastchat.renderDir set, also writes
 * frame strips (60 fps) of the same blink with the settle off (top) and on (bottom).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SettleStripTest {
    private fun blinkFrames(): List<Pair<Float, Float>> {
        // Find a seed whose first scheduled blink is a full one.
        for (seed in 1..50) {
            val b = Blinker(kotlin.random.Random(seed))
            val out = ArrayList<Pair<Float, Float>>()
            var started = false; var full = false
            repeat(60 * 12) {
                b.update(1f / 60f, MarkLifecycle.Idle, false)
                if (b.closed > 0f) started = true
                if (b.closed > 0.995f) full = true
                if (started) out += b.closed to b.settle
                if (started && out.size >= 40) return@repeat
            }
            if (full && out.size >= 40) return out.take(40)
        }
        error("no full blink")
    }

    @Test
    fun settleIsMicroscopic() {
        val fs = blinkFrames()
        val peak = fs.maxOf { it.second }
        assertTrue("settle missing", peak > 0.005f)
        assertTrue("settle too big: $peak", peak <= 0.02f)
        assertTrue("settle while lids still move", fs.none { it.first > 0.06f && it.second != 0f })
        val dir = System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() } ?: return
        File(dir).mkdirs()
        File(dir, "settle.csv").writeText("frame,closed,settle\n" + fs.mapIndexed { i, (c, s) -> "$i,%.4f,%.4f".format(c, s) }.joinToString("\n"))
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val px = 300
        val pick = fs.indices.filter { it % 1 == 0 }.drop(4).take(24)
        for (mode in listOf("generical", "grok")) {
            val sh = MarkShapes.resolve("blob") ?: return
            val img = ImageBitmap(px * pick.size, px * 2)
            val canvas = androidx.compose.ui.graphics.Canvas(img)
            canvas.drawRect(0f, 0f, (px * pick.size).toFloat(), (px * 2).toFloat(), androidx.compose.ui.graphics.Paint().apply { color = Color(0xFF1B1D22) })
            for (row in 0..1) for ((k, i) in pick.withIndex()) {
                val (c, s) = fs[i]
                val f = MotionFrame(lid = 1f - c, eyePop = if (row == 1) s else 0f)
                canvas.save(); canvas.translate((k * px).toFloat(), (row * px).toFloat())
                CanvasDrawScope().draw(Density(3f), LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat())) {
                    drawMark(sh, "blob", mode == "grok", f, false, 1f, Color(0xFF009FE0), Color.White,
                        MarkColors.genericalEyeFade(Color(0xFF009FE0), Color.White, 1f))
                }
                canvas.restore()
            }
            File(dir, "settle_$mode.png").outputStream().use { img.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
