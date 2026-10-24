package me.rerere.rikkahub.ui.components.avatar.animated

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Per-frame CPU cost of the editor morph paths (Robolectric native canvas; relative numbers). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class MorphBenchTest {
    private val img = ImageBitmap(492, 492)
    private val canvas = androidx.compose.ui.graphics.Canvas(img)
    private val scope = CanvasDrawScope()
    private fun frame(i: Int) = MotionFrame(body = BodyPose(pitch = 4f * kotlin.math.sin(i * 0.05f), yaw = 14f * kotlin.math.sin(i * 0.03f), roll = 2f), headLookX = 0.3f * kotlin.math.sin(i * 0.04f))
    private fun draw(block: DrawScope.() -> Unit) = scope.draw(Density(3f), LayoutDirection.Ltr, canvas, Size(492f, 492f), block)

    private fun bench(name: String, sb: StringBuilder, n: Int = 240, body: DrawScope.(Int) -> Unit) {
        repeat(minOf(60, n)) { i -> draw { body(i) } } // warm-up
        val ts = LongArray(n)
        for (i in 0 until n) { val t0 = System.nanoTime(); draw { body(i) }; ts[i] = System.nanoTime() - t0 }
        ts.sort()
        val line = "%s,%.3f,%.3f".format(name, ts[n / 2] / 1e6, ts[(n * 0.95).toInt()] / 1e6)
        sb.append(line).append('\n'); println("BENCH $line")
    }

    @Test
    fun morphFrameCost() {
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val sq = MarkShapes.resolve("square")!!; val cl = MarkShapes.resolve("cloud")!!
        val sb = StringBuilder("case,median_ms,p95_ms\n")
        pipelines(sb, sq, cl)
        val out = File(System.getProperty("lastchat.videoDir")?.takeIf { it.isNotBlank() } ?: "build/bench").apply { mkdirs() }
        File(out, "morph_bench_${System.getProperty("lastchat.benchTag") ?: "run"}.csv").writeText(sb.toString())
    }

    private fun pipelines(sb: StringBuilder, sq: GrokShape, cl: GrokShape) {
        val B = Color(0xFF2A92FE); val W = Color.White; val G = Color.Gray
        bench("steady_plain_drawMark", sb) { i -> drawMark(sq, "square", false, frame(i), false, 1f, B, W, G) }
        // NEW pipeline (16.2c) = the app's drawLive.
        val hs = MorphHolder()
        bench("new_steady_live", sb) { i -> drawLive(hs, sq, "square", false, frame(i), false, B, W, G, 1f) }
        fun morphing(toShape: GrokShape, toId: String, grok: Boolean): MorphHolder {
            val h = MorphHolder(); draw { drawLive(h, sq, "square", false, frame(0), false, B, W, G, 1f) }
            h.signatureChanged(); draw { drawLive(h, toShape, toId, grok, frame(0), false, B, W, G, 1f) }; h.forceZero = false
            return h
        }
        val hm = morphing(cl, "cloud", false)
        bench("new_shape_morph", sb) { i -> drawLive(hm, cl, "cloud", false, frame(i), false, B, W, G, 0.5f) }
        val he = morphing(sq, "square", true)
        bench("new_eyestyle_morph", sb) { i -> drawLive(he, sq, "square", true, frame(i), false, B, W, G, 0.5f) }
        // change frame (captures "from" once + first blend)
        bench("new_change_frame", sb, n = 40) { i ->
            val h = MorphHolder(); drawLive(h, sq, "square", false, frame(i), false, B, W, G, 1f)
            h.signatureChanged(); drawLive(h, sq, "square", true, frame(i), false, B, W, G, 1f)
        }
    }
}
