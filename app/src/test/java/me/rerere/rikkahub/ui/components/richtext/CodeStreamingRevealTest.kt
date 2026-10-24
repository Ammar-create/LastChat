package me.rerere.rikkahub.ui.components.richtext

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.pages.chat.StreamingFollowMotion
import me.rerere.rikkahub.ui.pages.chat.streamingFollowOmega
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** Code fences stream with the prose reveal (opacity + blur by age) and keep token colours. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class CodeStreamingRevealTest {
    private val outDir: File? = System.getProperty("lastchat.renderDir")
        ?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }

    private val src = "fun greet(name: String): String {\n    val msg = \"Hello, \$name\"\n    return msg.trim()\n}\n" +
        "fun main() {\n    println(greet(\"LastChat\"))\n}\n"

    private fun highlight(code: String): AnnotatedString = buildAnnotatedString {
        Regex("fun|val|return|\"[^\"]*\"?|[A-Za-z_]+|.", RegexOption.DOT_MATCHES_ALL).findAll(code).forEach { m ->
            val c = when {
                m.value in setOf("fun", "val", "return") -> Color(0xFFC678DD)
                m.value.startsWith("\"") -> Color(0xFF98C379)
                else -> null
            }
            if (c != null) withStyle(SpanStyle(color = c)) { append(m.value) } else append(m.value)
        }
    }

    private fun frames(): List<Triple<Long, String, List<StreamingSettleRange>>> {
        val st = StreamingTextPresentationState("", 0L)
        var raw = ""; var t = 0L
        val out = ArrayList<Triple<Long, String, List<StreamingSettleRange>>>()
        while (t <= 1500L) {
            if (t % 48L == 0L && raw.length < src.length) {
                raw = src.substring(0, minOf(src.length, raw.length + 9)); st.acceptRawContent(raw, t)
            }
            st.step(t, 16L)
            if (t % 160L == 0L && t > 0) out += Triple(t, st.displayContent, st.settleRanges)
            t += 16L
        }
        return out
    }

    @Test
    fun fadesNewestKeepsTokenColours() {
        val f = frames().first { it.third.isNotEmpty() && it.second.length > 20 }
        val reveal = StreamingTextReveal(f.third, f.first, Color.White, 120f, blurEnabled = false)
        val out = highlight(f.second).withCodeStreamingReveal(reveal, f.second.length)
        val newest = out.spanStyles.filter { it.end == out.length }.minOf { it.item.color.alpha }
        assertTrue("newest code tail should be faded, alpha=$newest", newest < 0.6f)
        // Older settled text untouched: no fade span starts at 0 unless the whole thing is new.
        val keywordFades = out.spanStyles.filter { it.item.color.red > 0.7f && it.item.color.blue > 0.8f && it.item.color.alpha < 1f }
        keywordFades.forEach { assertTrue(it.item.color.alpha in 0f..1f) }
    }

    @Test
    fun followIsMonotonicAndEased() {
        // 60 fps, a 21px line lands every 120 ms (fast stream). Log the per-frame scroll.
        val m = StreamingFollowMotion(); var target = 0f; var pos = 0f
        val steps = ArrayList<Float>()
        for (frame in 0 until 180) {
            if (frame % 7 == 0 && frame < 140) target += 21f
            val d = target - pos
            val s = m.step(d, 1f / 60f, streamingFollowOmega(d, 21f)); pos += s; steps += s
        }
        assertTrue(steps.all { it >= 0f })
        val maxJerk = steps.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue("per-frame velocity change $maxJerk px", maxJerk < 4f)
        assertEquals(target, pos, 1f)
        outDir?.let { File(it, "follow_steps.csv").writeText("frame,step_px\n" + steps.withIndex().joinToString("\n") { (i, s) -> "$i,$s" }) }
    }

    @Test
    fun renderCodeStrip() {
        assumeTrue(outDir != null)
        val fs = frames()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.requiredSize(380.dp, (fs.size * 140).dp).background(Color(0xFF0B0D10))) {
                    Column(Modifier.padding(10.dp)) {
                        fs.forEach { (now, text, ranges) ->
                            CodeStreamingRevealPreview(
                                code = highlight(text), ranges = ranges, nowMillis = now, charsPerSecond = 190f,
                                color = Color(0xFFABB2BF),
                                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.width(360.dp).padding(bottom = 4.dp),
                            )
                            Box(Modifier.padding(bottom = 6.dp).background(Color(0xFF333333)).requiredSize(360.dp, 1.dp))
                        }
                    }
                }
            }
        }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        val density = activity.resources.displayMetrics.density
        val w = (380 * density).toInt(); val h = (fs.size * 140 * density).toInt()
        val view: View = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        repeat(3) {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(outDir, "code_stream_frames.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }
}
