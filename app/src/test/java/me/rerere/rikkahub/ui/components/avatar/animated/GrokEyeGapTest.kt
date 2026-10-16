package me.rerere.rikkahub.ui.components.avatar.animated

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

/**
 * Grok eyes never touch: every expression, every picker shape, sampled morph frames
 * between every pair of expressions, face accents and turned poses are rendered and
 * the two eye blobs must stay separate with a clear gap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GrokEyeGapTest {
    private val outDir: File? = System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }
    private val cellDp = 48
    private val markFrac = 0.8f

    private val exprs = listOf(
        "Neutral", "Happy", "Surprised", "Sad", "Worried", "Sleeping", "Eyes closed", "Bored",
        "angered or concentrated", "pouty", "hurt or cute", "confused", "Angry", "Group 3",
    )

    private fun frames(): List<Pair<String, MotionFrame>> {
        val out = ArrayList<Pair<String, MotionFrame>>()
        for (e in exprs) out += e to MotionFrame(exprFrom = e, exprTo = e, exprT = 1f)
        for (a in exprs) for (b in exprs) if (a != b) for (t in listOf(0.35f, 0.7f))
            out += "$a>$b@$t" to MotionFrame(exprFrom = a, exprTo = b, exprT = t)
        for (e in exprs) for (yaw in listOf(-24f, 24f))
            out += "$e yaw$yaw" to MotionFrame(exprFrom = e, exprTo = e, exprT = 1f, body = BodyPose(yaw = yaw, pitch = 6f, roll = yaw / 4f))
        // Blinks / closed slits mid-morph, and squash-and-stretch.
        for (e in exprs) for (lid in listOf(0f, 0.3f, 0.6f))
            out += "$e lid$lid" to MotionFrame(exprFrom = "Neutral", exprTo = e, exprT = 0.5f, lid = lid)
        for (e in exprs) out += "$e squash" to MotionFrame(exprFrom = e, exprTo = e, exprT = 1f,
            body = BodyPose(squashX = 1.07f, squashY = 0.93f, pitch = -6f, yaw = -10f))
        // Frames from a live simulation (as rendered in the motion videos).
        val sim = AvatarMotionState(Random(3))
        val seq = listOf("Happy", "Sad", "Worried", "Bored", "hurt or cute", "confused", "Surprised")
        var t = 0f
        while (t < 14f) {
            if (t % 2f < 1f / 120f) sim.debugReact(seq[((t / 2f).toInt()) % seq.size], 1.4f)
            sim.tick(1f / 120f, AvatarMotionHints(isGenerating = t > 7f), 1_700_000_000_000L + (t * 1000).toLong())
            // Lids fully shut (mid blink-swap, lid < 3 %): the slits are sub-pixel, nothing to separate.
            if (((t * 120f).toInt()) % 12 == 0 && sim.frame.lid >= 0.03f) out += "sim@%.2f".format(t) to sim.frame
            t += 1f / 120f
        }
        // Dense mid-blend sampling for the tilted faces (Λ / V tilts reach toward each other).
        val tilted = listOf("Surprised", "Sad", "Worried", "confused", "Happy", "Angry", "angered or concentrated")
        for (x in tilted) for (y in tilted) if (x != y) for (t in listOf(0.15f, 0.5f, 0.85f))
            out += "$x>$y@$t yaw" to MotionFrame(exprFrom = x, exprTo = y, exprT = t, body = BodyPose(yaw = 8f, pitch = 4f, roll = 3f))
        val rig = FaceRig(Random(1))
        for (a in FaceAccent.entries) if (a != FaceAccent.None) for (e in listOf("Neutral", "Sad", "Happy"))
            out += "$e+$a" to MotionFrame(exprFrom = e, exprTo = e, exprT = 1f, face = rig.staticFace(a, 0.3f))
        return out
    }

    private fun capture(shape: String, fs: List<MotionFrame>): Bitmap {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.requiredSize((cellDp * fs.size).dp, cellDp.dp)) {
                    // White page so only the (dark) eyes are dark pixels.
                    Row(Modifier.background(Color.White)) {
                        for (f in fs) Box(Modifier.size(cellDp.dp), contentAlignment = Alignment.Center) {
                            // Ink eyes pinned: this measures geometry (eyes default to white since 16.2c).
                            AnimatedMarkAvatar(shape, "grok", "#2A92FE", false, colorPreset = "blue", eyeColorHex = "#1A1A1A",
                                modifier = Modifier.size((cellDp * markFrac).dp), debugFrame = f)
                        }
                    }
                }
            }
        }
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }
        val d = activity.resources.displayMetrics.density
        val w = (cellDp * fs.size * d).toInt(); val h = (cellDp * d).toInt()
        val view: View = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        controller.pause().stop().destroy()
        return bmp
    }

    /** Components of the dark-eye mask after growing each pixel by [r] (gap > 2r keeps them apart). */
    private fun components(mask: Array<BooleanArray>, r: Int): Int {
        val h = mask.size; val w = mask[0].size
        val grown = Array(h) { BooleanArray(w) }
        for (y in 0 until h) for (x in 0 until w) if (mask[y][x]) {
            for (dy in -r..r) for (dx in -r..r) {
                if (dx * dx + dy * dy > r * r) continue
                val yy = y + dy; val xx = x + dx
                if (yy in 0 until h && xx in 0 until w) grown[yy][xx] = true
            }
        }
        val seen = Array(h) { BooleanArray(w) }
        var n = 0
        val stack = ArrayDeque<Int>()
        for (y in 0 until h) for (x in 0 until w) if (grown[y][x] && !seen[y][x]) {
            n++; seen[y][x] = true; stack.addLast(y * w + x)
            while (stack.isNotEmpty()) {
                val p = stack.removeLast(); val py = p / w; val px = p % w
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val xx = px + dx; val yy = py + dy
                    if (yy in 0 until h && xx in 0 until w && grown[yy][xx] && !seen[yy][xx]) { seen[yy][xx] = true; stack.addLast(yy * w + xx) }
                }
            }
        }
        return n
    }

    @Test
    fun grokEyesKeepAGapEverywhere() {
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val all = frames()
        val failures = ArrayList<String>()
        val sheetRows = ArrayList<Bitmap>()
        for (shape in MarkShapes.SHAPES) {
            all.chunked(40).forEach { chunk ->
                val bmp = capture(shape, chunk.map { it.second })
                val c = bmp.height
                // Mark radius in px ≈ half the mark box; required clear gap ~5 % of it
                // (MIN_GAP is 40 %; the rest is anti-aliasing slack). Required clear gap ≈ 12 %.
                val he = c * markFrac / 2f
                val r = kotlin.math.max(1, (he * 0.06f).toInt())
                for ((i, pair) in chunk.withIndex()) {
                    val mask = Array(c) { y -> BooleanArray(c) { x ->
                        val px = bmp.getPixel(i * c + x, y)
                        val rr = (px shr 16) and 0xff; val gg = (px shr 8) and 0xff; val bb = px and 0xff
                        rr + gg + bb < 200
                    } }
                    val raw = components(mask, 0)
                    // Eyes must never vanish (closed = thin slit), and never merge.
                    if (raw < 2) { failures += "$shape ${pair.first} raw=$raw (eyes vanished or merged)"; continue }
                    val grown = components(mask, r)
                    if (grown < 2) failures += "$shape ${pair.first} raw=$raw grown=$grown"
                }
                if (sheetRows.size < 400) sheetRows += bmp
            }
        }
        outDir?.let { dir ->
            // Gap sheet: first strip (all expressions face-on) per shape.
            val firsts = sheetRows.filterIndexed { i, _ -> i % ((all.size + 39) / 40) == 0 }
            val w = firsts.maxOf { it.width }; val h = firsts.sumOf { it.height }
            val sheet = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(sheet); var y = 0f
            for (b in firsts) { cv.drawBitmap(b, 0f, y, null); y += b.height }
            File(dir, "grok_gap_sheet.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "grok_gap_report.txt").writeText("frames/shape=${all.size} shapes=${MarkShapes.SHAPES.size}\nfailures=${failures.size}\n" + failures.joinToString("\n"))
        }
        assertTrue("Grok eyes touch in ${failures.size} frames, e.g. ${failures.take(12)}", failures.isEmpty())
    }
}
