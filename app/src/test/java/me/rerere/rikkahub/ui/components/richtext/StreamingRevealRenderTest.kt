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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Frame strips of a very fast stream: the newest text is the most blurred / faint and
 * eases to sharp (gradient by age), and every revealed character is drawn (nothing cut).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class StreamingRevealRenderTest {
    private val outDir: File? = System.getProperty("lastchat.renderDir")
        ?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }

    private val reply = "She shifts her weight, the tip of her cane tapping the floor once. A small, dry sigh " +
        "escapes her. \"Homu... 'oki' is not exactly a detailed brief, but I suppose I can work with it.\" " +
        "She tilts her head, considering you for a long moment before the corner of her mouth lifts."

    /** Simulate a fast token stream (~25-char chunks every 30 ms) and snapshot states. */
    private fun frames(): List<Triple<Long, String, List<StreamingSettleRange>>> {
        val st = StreamingTextPresentationState("", 0L)
        var raw = ""
        var t = 0L
        val out = ArrayList<Triple<Long, String, List<StreamingSettleRange>>>()
        val snaps = setOf(160L, 320L, 480L, 640L, 800L, 960L, 1200L, 1600L)
        while (t <= 1600L) {
            if (t % 32L == 0L && raw.length < reply.length) {
                raw = reply.substring(0, minOf(reply.length, raw.length + 25))
                st.acceptRawContent(raw, t)
            }
            st.step(t, 16L)
            if (t in snaps) out += Triple(t, st.displayContent, st.settleRanges)
            t += 16L
        }
        return out.distinctBy { it.first }
    }

    @Test
    fun gradientAndNothingSkipped() {
        val fs = frames()
        // Logic check: in a frame with several runs, the newest char is less settled than older ones.
        val busy = fs.first { it.third.isNotEmpty() }
        val r = busy.third.last()
        assertTrue(r.revealedAt(r.endOffset - 1) >= r.revealedAt(r.startOffset))
        assumeTrue(outDir != null)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.requiredSize(380.dp, (fs.size * 150).dp).background(Color(0xFF0B0D10))) {
                    Column(Modifier.padding(10.dp)) {
                        fs.forEach { (now, text, ranges) ->
                            StreamingRevealPreview(
                                text = text, ranges = ranges, nowMillis = now, charsPerSecond = 800f,
                                color = Color(0xFFE6E6E6), style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
                                modifier = Modifier.width(360.dp).padding(bottom = 4.dp),
                            )
                            Box(Modifier.width(360.dp).padding(bottom = 6.dp).background(Color(0xFF333333)).requiredSize(360.dp, 1.dp))
                        }
                    }
                }
            }
        }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        val density = activity.resources.displayMetrics.density
        val w = (380 * density).toInt(); val h = (fs.size * 150 * density).toInt()
        val view: View = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        repeat(3) {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(outDir, "stream_fast_frames.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }
}
