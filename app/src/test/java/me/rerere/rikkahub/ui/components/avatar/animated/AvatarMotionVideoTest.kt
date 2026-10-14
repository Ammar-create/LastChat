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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assume.assumeTrue
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
 * Motion review: simulates the live motion state over time for a few scenarios and
 * writes numbered frames (30 fps) per scenario / mode / shape, plus a CSV of the
 * motion channels. A script turns the frames into MP4s.
 *   -Dlastchat.videoDir=/path  [-Dlastchat.videoOnly=busy,idle]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarMotionVideoTest {
    private val outDir: File? = System.getProperty("lastchat.videoDir")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }
    private val only = System.getProperty("lastchat.videoOnly")?.takeIf { it.isNotBlank() }?.split(",")?.toSet()
    private val fps = 30
    private val cellDp = 120
    private val perStrip = 15

    private class Script(val name: String, val seconds: Float, val preRoll: Float = 0f, val at: (AvatarMotionState, AvatarStage, Float, Long) -> AvatarMotionHints)

    private val scripts = listOf(
        Script("busy", 22f) { _, _, t, _ ->
            when {
                t < 1f -> AvatarMotionHints()
                t < 9f -> AvatarMotionHints(isGenerating = true)
                t < 18f -> AvatarMotionHints(isGenerating = true, hasStreamedTokens = true)
                else -> AvatarMotionHints()
            }
        },
        Script("idle", 24f, preRoll = 20f) { s, _, t, _ ->
            if (t < 0.05f) s.debugBoredom(0.8f)
            AvatarMotionHints()
        },
        Script("startle", 7f, preRoll = 30f) { s, st, t, ms ->
            if (t < 0.05f) s.debugBoredom(0.9f)
            if (t in 1.5f..1.55f) st.emit(AvatarStage.EventKind.UserTyping, nowMs = ms)
            AvatarMotionHints(isTyping = t > 1.5f && t < 4f)
        },
        Script("expr", 14f) { s, _, t, _ ->
            val seq = listOf("Happy", "Sad", "Surprised", "confused", "Bored", "angered or concentrated", "Worried")
            val i = (t / 2f).toInt()
            if (t % 2f < 1f / 24f && i < seq.size) s.debugReact(seq[i], 1.4f)
            AvatarMotionHints()
        },
    )

    private fun simulate(sc: Script, seed: Int): List<MotionFrame> {
        val stage = AvatarStage(Random(seed))
        val s = AvatarMotionState(Random(seed))
        val id = stage.newId()
        s.stage = stage; s.stageId = id
        stage.update(id, 1, Offset(300f, 900f), 60f, "blob|generical", 1080f, 2400f)
        val dt = 1f / 120f
        var t = -sc.preRoll
        var ms = 1_700_000_000_000L
        val frames = ArrayList<MotionFrame>()
        var nextFrame = 0f
        while (t < sc.seconds) {
            val hints = if (t < 0f) AvatarMotionHints() else sc.at(s, stage, t, ms)
            s.tick(dt, hints, ms)
            if (t >= nextFrame - 1e-4f) { frames += s.frame; nextFrame += 1f / fps }
            t += dt; ms += (dt * 1000).toLong()
        }
        return frames
    }

    private fun captureStrip(file: File, shape: String, mode: String, frames: List<MotionFrame>) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.requiredSize((cellDp * frames.size).dp, cellDp.dp)) {
                    Row(Modifier.background(Color(0xFF14171C))) {
                        for (f in frames) Box(Modifier.size(cellDp.dp), contentAlignment = Alignment.Center) {
                            AnimatedMarkAvatar(shape, mode, "#2A92FE", false, colorPreset = "blue",
                                modifier = Modifier.size((cellDp * 0.72f).dp), debugFrame = f)
                        }
                    }
                }
            }
        }
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }
        val d = activity.resources.displayMetrics.density
        val w = (cellDp * frames.size * d).toInt(); val h = (cellDp * d).toInt()
        val view: View = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    @Test
    fun renderMotionVideos() {
        val out = outDir
        assumeTrue(out != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        for (sc in scripts) {
            if (only != null && sc.name !in only) continue
            val frames = simulate(sc, 7)
            File(out, "${sc.name}.csv").writeText("t,pitch,yaw,roll,posY,sqX,sqY,lookX,lookY,lid,exprFrom,exprTo,exprT\n" +
                frames.withIndex().joinToString("\n") { (i, f) ->
                    "%.3f,%.2f,%.2f,%.2f,%.4f,%.4f,%.4f,%.3f,%.3f,%.3f,%s,%s,%.3f".format(
                        i.toFloat() / fps, f.body.pitch, f.body.yaw, f.body.roll, f.body.posY, f.body.squashX, f.body.squashY,
                        f.headLookX, f.headLookY, f.lid, f.exprFrom, f.exprTo, f.exprT)
                })
            for (mode in listOf("generical", "grok")) for (shape in listOf("blob", "heart")) {
                val dir = File(out, "${sc.name}_${mode}_$shape").apply { mkdirs() }
                frames.chunked(perStrip).forEachIndexed { k, chunk ->
                    captureStrip(File(dir, "strip%03d.png".format(k)), shape, mode, chunk)
                }
            }
        }
    }
}
