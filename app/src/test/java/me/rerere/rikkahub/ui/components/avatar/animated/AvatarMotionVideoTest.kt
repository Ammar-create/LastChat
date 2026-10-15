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
    private val videoShapes = System.getProperty("lastchat.videoShapes")?.takeIf { it.isNotBlank() }?.split(",") ?: listOf("blob", "heart")
    private val only = System.getProperty("lastchat.videoOnly")?.takeIf { it.isNotBlank() }?.split(",")?.toSet()
    private val fps = 30
    private val cellDp = 120
    private val perStrip = 15

    private class Script(val name: String, val seconds: Float, val preRoll: Float = 0f, val seed: Int = 7, val at: (AvatarMotionState, AvatarStage, Float, Long) -> AvatarMotionHints)

    private val scripts = listOf(
        Script("busy", 30f) { _, _, t, _ ->
            when {
                t < 1f -> AvatarMotionHints()
                t < 11f -> AvatarMotionHints(isGenerating = true)
                t < 25f -> AvatarMotionHints(isGenerating = true, hasStreamedTokens = true)
                else -> AvatarMotionHints()
            }
        },
        Script("idle", 90f, preRoll = 20f) { _, _, _, _ -> AvatarMotionHints() },
        // Same stimulus six times (variation, latency, sometimes nothing), then one
        // off-gaze / out-of-view event that has to be noticed late.
        Script("reactions", 38f, preRoll = 10f) { s, _, t, _ ->
            val pings = listOf(2f, 7f, 12f, 17f, 22f, 27f)
            for (p in pings) if (t >= p && fired.add(p)) s.debugStimulus("ping", 0.45f, 0.35f, 0.7f)
            if (t >= 32f && fired.add(32f)) s.debugStimulus("behind", -0.95f, 0.05f, 0.85f, inView = false)
            AvatarMotionHints()
        },
        Script("startle", 7f, preRoll = 30f) { s, _, t, _ ->
            if (t >= 1.5f && fired.add(1.5f)) s.debugPerform(Response.Startle)
            AvatarMotionHints()
        },
        Script("spin", 24f, preRoll = 10f) { s, _, t, _ ->
            if (t >= 1f && fired.add(1f)) s.debugSpin(runUp = false, shake = false)
            if (t >= 5.5f && fired.add(5.5f)) s.debugSpin(runUp = true, shake = false)
            if (t >= 10f && fired.add(10f)) s.debugSpin(runUp = false, shake = true)
            if (t >= 14.5f && fired.add(14.5f)) s.debugSpin(runUp = true, shake = true)
            if (t >= 19.5f && fired.add(19.5f)) s.debugSpin(runUp = null, shake = null)
            AvatarMotionHints()
        },
        Script("spin2", 24f, preRoll = 10f, seed = 21) { s, _, t, _ ->
            for (p in listOf(1f, 5.5f, 10f, 14.5f, 19.5f)) if (t >= p && fired.add(p)) s.debugSpin(runUp = null, shake = null)
            AvatarMotionHints()
        },
        Script("moments", 30f, preRoll = 10f) { s, _, t, _ ->
            if (t >= 1f && fired.add(1f)) s.debugMoment(Moment.Scan)
            if (t >= 7.5f && fired.add(7.5f)) s.debugMoment(Moment.Peek)
            if (t >= 11.5f && fired.add(11.5f)) s.debugMoment(Moment.Stretch)
            if (t >= 16.5f && fired.add(16.5f)) s.debugMoment(Moment.SleepyDrift)
            if (t >= 23f && fired.add(23f)) s.debugMoment(Moment.Wiggle)
            if (t >= 26.5f && fired.add(26.5f)) s.debugMoment(Moment.SmugNod)
            AvatarMotionHints()
        },
        // Every unordered pair of Julian's 13 frames exactly once (13 is prime: steps 1..6
        // around the ring are Hamiltonian cycles), 1.1 s per face incl. path<->rect morphs.
        Script("morphs", 86f) { s, _, t, _ ->
            val i = (t / 1.1f).toInt()
            if (i < MORPH_SEQ.size && fired.add(i.toFloat())) s.debugReact(MORPH_SEQ[i], 3f)
            AvatarMotionHints()
        },
        Script("expr", 14f) { s, _, t, _ ->
            val seq = listOf("Happy", "Sad", "Surprised", "confused", "Bored", "angered or concentrated", "Worried")
            val i = (t / 2f).toInt()
            if (t % 2f < 1f / 24f && i < seq.size) s.debugReact(seq[i], 1.4f)
            AvatarMotionHints()
        },
    )

    private val MORPH_SEQ: List<String> = run {
        val ids = listOf("Neutral", "Bored", "Sad", "Happy", "Eyes closed", "confused", "Surprised", "Worried",
            "angered or concentrated", "Angry", "pouty", "hurt or cute", "Sleeping")
        (1..6).flatMap { k -> (0 until 13).map { ids[(it * k) % 13] } } + ids[0]
    }

    private var lastLog: List<String> = emptyList()
    private val fired = HashSet<Float>()

    private fun simulate(sc: Script, seed: Int): List<MotionFrame> {
        fired.clear()
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
        lastLog = s.behaviorLog.map { l -> val (a, b) = l.split(',', limit = 2); "%.2f,%s".format(a.toFloat() - sc.preRoll, b) }
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

    /** Neutral Grok + Generical sheet of every picker shape (eye spacing vs face width). */
    @Test
    fun renderShapeSheet() {
        val out = outDir
        assumeTrue(out != null && (only == null || "shapes" in only))
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val f = MotionFrame()
        for (mode in listOf("grok", "generical")) for (shape in MarkShapes.PICKER_IDS) {
            captureStrip(File(out, "shape_${mode}_$shape.png"), shape, mode, listOf(f))
        }
    }

    /** Spin sweep 0…355° (5° steps) per shape, through the real soft-3D solid. */
    @Test
    fun renderSpinSweep() {
        val out = outDir
        assumeTrue(out != null && (only == null || "sweep" in only))
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val frames = (0 until 72).map { MotionFrame(spinYaw = it * 5f + 0.01f) }
        for (mode in listOf("grok", "generical")) for (shape in listOf("heart", "blob", "star", "flower", "hex")) {
            captureStrip(File(out, "sweep_${mode}_$shape.png"), shape, mode, frames)
        }
    }

    /** Every expression id, static, both modes (mapping sheet) + gaze-shape poses. */
    @Test
    fun renderExprSheet() {
        val out = outDir
        assumeTrue(out != null && (only == null || "exprsheet" in only))
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val ids = listOf("Neutral", "Bored", "Sad", "Happy", "Eyes closed", "confused", "Surprised", "Worried",
            "angered or concentrated", "Angry", "pouty", "hurt or cute", "Sleeping", "Group 3")
        for (mode in listOf("generical", "grok")) {
            captureStrip(File(out, "exprsheet_$mode.png"), "blob", mode, ids.map { MotionFrame(exprFrom = it, exprTo = it) })
            captureStrip(File(out, "gazeshape_$mode.png"), "blob", mode,
                listOf(-0.95f, -0.6f, -0.3f, 0f, 0.3f, 0.6f, 0.95f).map { MotionFrame(headLookX = it) })
        }
        File(out, "exprsheet_ids.txt").writeText(ids.joinToString("\n"))
    }

    /** Draw cost per frame: rest vs spin, through the real drawMark (software canvas). */
    @Test
    fun spinDrawCost() {
        val out = outDir
        assumeTrue(out != null && (only == null || "cost" in only))
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val sb = StringBuilder("shape,mode,phase,mean_ms,p95_ms,max_ms\n")
        val px = 160
        for (shape in listOf("heart", "blob", "star", "flower", "hex")) for (mode in listOf("grok", "generical")) {
            val gs = MarkShapes.resolve(shape) ?: continue
            for (phase in listOf("rest", "spin")) {
                val ts = ArrayList<Double>()
                for (i in 0 until 150) {
                    val f = if (phase == "rest") MotionFrame(timeSec = i / 60f)
                        else MotionFrame(spinYaw = 3f + i * 357f / 150f, timeSec = 0f)
                    val img = androidx.compose.ui.graphics.ImageBitmap(px, px)
                    val cv = androidx.compose.ui.graphics.Canvas(img)
                    val t0 = System.nanoTime()
                    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                        androidx.compose.ui.unit.Density(2f), androidx.compose.ui.unit.LayoutDirection.Ltr, cv,
                        androidx.compose.ui.geometry.Size(px.toFloat(), px.toFloat()),
                    ) {
                        drawMark(gs, shape, mode == "grok", f, false, 1f, Color(0xFF2A92FE), Color(0xFF14171C), Color(0xFF14171C))
                    }
                    if (i >= 10) ts += (System.nanoTime() - t0) / 1e6
                }
                ts.sort()
                sb.append("%s,%s,%s,%.2f,%.2f,%.2f\n".format(shape, mode, phase, ts.average(), ts[(ts.size * 0.95).toInt()], ts.last()))
            }
        }
        File(out, "draw_cost.csv").writeText(sb.toString())
    }

    @Test
    fun renderMotionVideos() {
        val out = outDir
        assumeTrue(out != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        AvatarDebugMetrics.enabled = true
        AvatarDebugMetrics.rows.clear()
        for (sc in scripts) {
            AvatarDebugMetrics.label = sc.name
            if (only != null && sc.name !in only) continue
            val frames = simulate(sc, sc.seed)
            File(out, "${sc.name}_behavior.csv").writeText("t,event\n" + lastLog.joinToString("\n") + "\n")
            if (sc.name == "startle") {
                // The startle must visibly play: a quick stretch pop right after typing starts.
                val win = frames.subList((1.5f * fps).toInt(), (2.3f * fps).toInt())
                val pop = win.maxOf { it.body.squashY }
                org.junit.Assert.assertTrue("startle pop missing (max squashY $pop)", pop > 1.09f)
            }
            File(out, "${sc.name}.csv").writeText("t,pitch,yaw,roll,posY,sqX,sqY,lookX,lookY,lid,exprFrom,exprTo,exprT,spin,leadX\n" +
                frames.withIndex().joinToString("\n") { (i, f) ->
                    "%.3f,%.2f,%.2f,%.2f,%.4f,%.4f,%.4f,%.3f,%.3f,%.3f,%s,%s,%.3f,%.2f,%.4f".format(
                        i.toFloat() / fps, f.body.pitch, f.body.yaw, f.body.roll, f.body.posY, f.body.squashX, f.body.squashY,
                        f.headLookX, f.headLookY, f.lid, f.exprFrom, f.exprTo, f.exprT, f.spinYaw, f.leadX)
                })
            for (mode in listOf("generical", "grok")) for (shape in videoShapes) {
                val dir = File(out, "${sc.name}_${mode}_$shape").apply { mkdirs() }
                frames.chunked(perStrip).forEachIndexed { k, chunk ->
                    captureStrip(File(dir, "strip%03d.png".format(k)), shape, mode, chunk)
                }
            }
        }
        AvatarDebugMetrics.enabled = false
        File(out, "metrics.csv").writeText("scenario,mode,shape,exprFrom,exprTo,exprT,gap_over_he,inset_over_he,eye_cx,eye_cy\n" +
            AvatarDebugMetrics.rows.joinToString("\n"))
    }
}
