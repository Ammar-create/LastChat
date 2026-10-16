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
    private val markColor = System.getProperty("lastchat.markColor")?.takeIf { it.isNotBlank() } ?: "#2A92FE"
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
        Script("idle150", 150f, preRoll = 20f, seed = 11) { _, _, _, _ -> AvatarMotionHints() },
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
        Script("confused", 10f) { s, _, t, _ ->
            if (t >= 1f && fired.add(1f)) s.debugConfused()
            if (t >= 4.5f && fired.add(4.5f)) s.debugConfused()
            if (t >= 7.5f && fired.add(7.5f)) s.debugConfused()
            AvatarMotionHints()
        },
        Script("expr", 14f) { s, _, t, _ ->
            val seq = listOf("Happy", "Sad", "Surprised", "confused", "Bored", "angered or concentrated", "pouty")
            val i = (t / 2f).toInt()
            if (t % 2f < 1f / 24f && i < seq.size) s.debugReact(seq[i], 1.4f)
            AvatarMotionHints()
        },
        // ---------------- engine16 previews
        Script("idle60", 60f, preRoll = 20f, seed = 9) { _, _, _, _ -> AvatarMotionHints() },
        Script("idle_gaze", 90f, preRoll = 15f, seed = 23) { _, _, _, _ -> AvatarMotionHints() },
        Script("idle180", 180f, preRoll = 20f, seed = 5) { _, _, _, _ -> AvatarMotionHints() },
        Script("ep_attention", 40f, preRoll = 10f, seed = 31) { s, _, t, _ ->
            ep(s, t, 1f to Episode.CuriousLR, 6f to Episode.CheckOnYou, 11f to Episode.TiltChain, 17f to Episode.FollowSomething,
                23f to Episode.LookAround360, 30f to Episode.Scan); AvatarMotionHints()
        },
        Script("ep_character", 54f, preRoll = 10f, seed = 33) { s, _, t, _ ->
            ep(s, t, 1f to Episode.SkepticalLR, 7.5f to Episode.HmmLean, 13f to Episode.DoubleTake, 18.5f to Episode.Peek,
                23.5f to Episode.LookUpRemember, 29f to Episode.ContentSlowBlink, 33f to Episode.ConcernGlance, 39f to Episode.Smug,
                43f to Episode.Sneeze, 47f to Episode.ComeToSenses); AvatarMotionHints()
        },
        Script("ep_tired", 34f, preRoll = 10f, seed = 35) { s, _, t, _ ->
            ep(s, t, 1f to Episode.Yawn, 7f to Episode.Stretch, 13f to Episode.SleepyDrift, 20f to Episode.Yawn, 26f to Episode.ComeToSenses); AvatarMotionHints()
        },
        Script("skeptical", 18f, preRoll = 10f, seed = 37) { s, _, t, _ ->
            ep(s, t, 1f to Episode.SkepticalLR, 7f to Episode.SkepticalLR, 13f to Episode.SkepticalLR); AvatarMotionHints()
        },
        Script("curious_lr", 16f, preRoll = 10f, seed = 39) { s, _, t, _ ->
            ep(s, t, 1f to Episode.CuriousLR, 6f to Episode.CuriousLR, 11f to Episode.CuriousLR); AvatarMotionHints()
        },
        Script("yawn", 22f, preRoll = 10f, seed = 41) { s, _, t, _ ->
            ep(s, t, 1f to Episode.Yawn, 8f to Episode.Yawn, 15f to Episode.Yawn); AvatarMotionHints()
        },
        Script("rest_eyes", 40f, preRoll = 10f, seed = 43) { s, _, t, _ ->
            if (t >= 1f && fired.add(1f)) s.debugRest(doze = false)
            if (t >= 12f && fired.add(12f)) s.debugRest(doze = true)
            if (t >= 33f && fired.add(33f)) s.poke() // activity returns: gentle wake
            AvatarMotionHints()
        },
        // Spins only happen in lively contexts: here while answering (generating/streaming).
        Script("spin_paths", 33f, preRoll = 6f, seed = 45) { s, _, t, _ ->
            val ps = SpinPath.entries
            (ps + ps).forEachIndexed { i, p -> val at = 1f + i * 5.3f; if (t >= at && fired.add(at)) s.debugSpin(null, null, p) }
            AvatarMotionHints(isGenerating = true, hasStreamedTokens = t > 3f)
        },
        Script("busy_think", 40f, preRoll = 5f, seed = 47) { _, _, t, _ ->
            if (t < 1f) AvatarMotionHints() else if (t < 26f) AvatarMotionHints(isGenerating = true) else if (t < 37f) AvatarMotionHints(isGenerating = true, hasStreamedTokens = true) else AvatarMotionHints()
        },
        Script("pokes", 16f, preRoll = 10f, seed = 49) { s, _, t, _ ->
            for (p in listOf(1f, 4f, 5f, 5.6f, 6.2f, 6.8f, 7.4f)) if (t >= p && fired.add(p)) s.poke()
            AvatarMotionHints()
        },
    )

    private fun ep(s: AvatarMotionState, t: Float, vararg at: Pair<Float, Episode>) {
        for ((a, e) in at) if (t >= a && fired.add(a)) s.debugEpisode(e)
    }

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
                            AnimatedMarkAvatar(shape, mode, markColor, false, colorPreset = if (markColor == "#2A92FE") "blue" else null,
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

    /** Engine16: Generical eye colours x shade on/off on a few bodies (outline must equal the eye colour). */
    @Test
    fun renderEyeColorSheet() {
        val out = outDir
        assumeTrue(out != null && (only == null || "eyecolors" in only))
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val eyes = listOf<String?>(null, "#FFFFFF", "#FFD60A", "#FF453A", "#30D158", "#BF5AF2", "#1C1C1E", "#0A84FF")
        val bodies = listOf("#2A92FE", "#FF9F0A", "#1C1C1E", "#F2F2F7")
        val frames = listOf(MotionFrame(), MotionFrame(exprFrom = "Happy", exprTo = "Happy"))
        for (body in bodies) for (shade in listOf(true, false)) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = controller.get()
            activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.requiredSize((cellDp * eyes.size).dp, (cellDp * 2).dp)) {
                        androidx.compose.foundation.layout.Column(Modifier.background(Color(0xFF14171C))) {
                            for ((fi, f) in frames.withIndex()) Row {
                                for (e in eyes) Box(Modifier.size(cellDp.dp), contentAlignment = Alignment.Center) {
                                    AnimatedMarkAvatar(if (fi == 0) "blob" else "heart", "generical", body, false, eyeColorHex = e, eyeShade = shade,
                                        modifier = Modifier.size((cellDp * 0.72f).dp), debugFrame = f)
                                }
                            }
                        }
                    }
                }
            }
            repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500)) }
            val d = activity.resources.displayMetrics.density
            val w = (cellDp * eyes.size * d).toInt(); val h = (cellDp * 2 * d).toInt()
            val view: View = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            File(out, "eyecolors_${body.drop(1)}_shade${if (shade) "On" else "Off"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            controller.pause().stop().destroy()
        }
    }

    /** Engine16.1: every Grok expression id + every face accent (and a half-closed lid), circle + heart. */
    @Test
    fun renderGrokAudit() {
        val out = outDir
        assumeTrue(out != null && (only == null || "grokaudit" in only))
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val ids = listOf("Neutral", "Bored", "Sad", "Happy", "Eyes closed", "confused", "confused left", "Surprised", "Worried",
            "angered or concentrated", "Angry", "pouty", "hurt or cute", "Sleeping", "Group 3")
        val rig = FaceRig(Random(3))
        val acc = FaceAccent.entries.filter { it != FaceAccent.None }
        for (sh in listOf("blob", "heart")) {
            captureStrip(File(out, "grok_expr_$sh.png"), sh, "grok", ids.map { MotionFrame(exprFrom = it, exprTo = it) })
            captureStrip(File(out, "grok_accent_$sh.png"), sh, "grok", acc.map { MotionFrame(face = rig.staticFace(it, 0.3f)) })
            captureStrip(File(out, "grok_blinkclosed_$sh.png"), sh, "grok", listOf("Eyes closed", "Sleeping").flatMap { e -> listOf(1f, 0.5f, 0f).map { MotionFrame(exprFrom = e, exprTo = e, lid = it) } })
        }
        File(out, "grok_ids.txt").writeText(ids.joinToString("\n") + "\n--\n" + acc.joinToString("\n"))
    }

    /** Engine16: non-horizontal spin paths, 0…355° (10° steps). */
    @Test
    fun renderAxisSweep() {
        val out = outDir
        assumeTrue(out != null && (only == null || "axissweep" in only))
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val paths = SpinPath.values()
        for (pth in paths) {
            val frames = (0 until 36).map { i -> val (y, p, r) = pth.angles(i * 10f / 360f + 0.0001f, 1f); MotionFrame(spinYaw = y, spinPitch = p, spinRoll = r) }
            for (mode in listOf("grok", "generical")) for (shape in listOf("heart", "blob"))
                captureStrip(File(out, "axis_${pth.name}_${mode}_$shape.png"), shape, mode, frames)
        }
    }

    /** Every expression id, static, both modes (mapping sheet) + gaze-shape poses. */
    @Test
    fun renderExprSheet() {
        val out = outDir
        assumeTrue(out != null && (only == null || "exprsheet" in only))
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val ids = listOf("Neutral", "Bored", "Sad", "Happy", "Eyes closed", "confused", "Surprised", "Worried",
            "angered or concentrated", "Angry", "pouty", "hurt or cute", "Sleeping", "Group 3", "confused left")
        for (mode in listOf("generical", "grok")) {
            captureStrip(File(out, "exprsheet_$mode.png"), "blob", mode, ids.map { MotionFrame(exprFrom = it, exprTo = it) })
            // Look-around: Neutral eye shapes, gaze only via the 3D turn / surface projection.
            for (sh in listOf("blob", "heart"))
                captureStrip(File(out, "lookaround_${mode}_$sh.png"), sh, mode,
                    listOf(-0.95f to 0f, -0.5f to 0.4f, 0f to 0.9f, 0.6f to 0.6f, 0.95f to 0f, 0.6f to -0.6f, 0f to -0.9f, -0.6f to -0.6f)
                        .map { (x, y) -> MotionFrame(headLookX = x, headLookY = y) })
        }
        File(out, "exprsheet_ids.txt").writeText(ids.joinToString("\n"))
    }

    /** Every picker shape x expression, static (spacing table + Generical 1:1 checks). */
    @Test
    fun renderShapeExprGrid() {
        val out = outDir
        assumeTrue(out != null && (only == null || "shapegrid" in only))
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val ids = listOf("Neutral", "Bored", "Sad", "Happy", "Eyes closed", "confused", "Surprised", "Worried",
            "angered or concentrated", "Angry", "pouty", "hurt or cute", "Sleeping", "Group 3", "confused left")
        val shapes = listOf("blob", "cloud", "square", "sparkle", "clover", "heart", "flower", "teardrop", "tablet", "wedge", "house", "star", "hex")
        val modes = (System.getProperty("lastchat.gridModes")?.takeIf { it.isNotBlank() }?.split(",")) ?: listOf("grok", "generical")
        for (mode in modes) for (sh in shapes)
            captureStrip(File(out, "grid_${mode}_$sh.png"), sh, mode, ids.map { MotionFrame(exprFrom = it, exprTo = it) })
        File(out, "grid_ids.txt").writeText(ids.joinToString("\n"))
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

    /** Engine16.2b: gaze-staying specials must be very rare (≈ one per 15-35 idle min). */
    @Test
    fun gazeSpecialsAudit() {
        val out = File(System.getProperty("lastchat.videoDir")?.takeIf { it.isNotBlank() } ?: "build/audit").apply { mkdirs() }
        fun isSpecial(k: String) = k.startsWith("rest-") || k.endsWith("-long")
        val sb = StringBuilder("run,seed,minutes,longHolds,offFrontRests,exploreEpisodes,specialsTotal,tenMinWindowsWithNone\n")
        // 1) Planner awake for 60 min (worst case: never dozes).
        for (seed in listOf(1, 2, 3, 4, 5, 6, 7, 8)) {
            val pl = IdlePlanner(Random(seed)); val tp = Temperament(Random(seed))
            var t = 0f; var long = 0; var off = 0; var exp = 0; var prev = ""
            val wins = IntArray(6)
            while (t < 3600f) {
                val f = pl.next(MarkLifecycle.Idle, tp, false)
                val w = (t / 600f).toInt().coerceAtMost(5)
                if (f.kind.endsWith("-long")) long++
                if (f.kind.startsWith("rest-")) off++
                if (f.kind == "explore" && prev != "explore") exp++
                if (isSpecial(f.kind) || (f.kind == "explore" && prev != "explore")) wins[w]++
                prev = f.kind; t += f.hold + f.travel
            }
            val tot = off + exp
            sb.append("planner60,$seed,60,$long,$off,$exp,$tot,${wins.count { it == 0 }}/6\n")
            org.junit.Assert.assertTrue("too many specials seed $seed: $tot", tot <= 5)
        }
        // 2) Full engine, 60 untouched minutes (dozes as it would in the app).
        AvatarDebugMetrics.enabled = true
        for (seed in listOf(3, 11, 19, 27)) {
            simulate(Script("g$seed", 3600f, preRoll = 0f) { _, _, _, _ -> AvatarMotionHints() }, seed)
            val fx = lastLog.filter { it.contains(",fix ") }.map { it.substringBefore(",").toFloat() to it.substringAfter(",fix ").substringBefore(" ") }
            var prev = ""; var long = 0; var off = 0; var exp = 0; val wins = IntArray(6)
            for ((t, k) in fx) {
                val w = (t / 600f).toInt().coerceIn(0, 5)
                if (k.endsWith("-long")) long++
                if (k.startsWith("rest-")) off++
                val ne = k == "explore" && prev != "explore"
                if (ne) exp++
                if (isSpecial(k) || ne) wins[w]++
                prev = k
            }
            sb.append("engine60,$seed,60,$long,$off,$exp,${off + exp},${wins.count { it == 0 }}/6\n")
        }
        AvatarDebugMetrics.enabled = false
        File(out, "gaze_specials.csv").writeText(sb.toString())
        println(sb)
    }

    @Test
    fun behaviourAudit() {
        val out = File(System.getProperty("lastchat.videoDir")?.takeIf { it.isNotBlank() } ?: "build/audit").apply { mkdirs() }
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        AvatarDebugMetrics.enabled = true
        val sb = StringBuilder("seed,spins,faceChanges,uncaused,lowToBright,gazeChanges,storiesNotice,storiesAck,episodes,distinctEpisodes\n")
        val base = scripts.first { it.name == "idle150" }
        for (seed in listOf(3, 11, 19, 27, 35)) {
            simulate(Script("audit$seed", 600f, preRoll = 20f) { _, _, _, _ -> AvatarMotionHints() }, seed)
            val log = lastLog
            File(out, "audit_seed$seed.log").writeText(log.joinToString("\n"))
            val spins = log.count { it.contains(",moment Spin") || it.contains(",spin cause=") }
            val faces = log.filter { it.contains(",expr ") || it.contains(",face ") }
            val unc = faces.count { it.contains("cause=unspecified") }
            val lb = faces.count { l -> Regex("(Bored|Sleeping|Sad|Eyes closed|Worried)→(Happy|Group 3|playful)").containsMatchIn(l) }
            sb.append("$seed,$spins,${faces.size},$unc,$lb,${log.count { it.contains(",gaze ") }},${log.count { it.contains("story notice") }},${log.count { it.contains("story acknowledge") }},${log.count { it.contains(",episode ") }},${log.filter { it.contains(",episode ") }.map { it.substringAfter(",episode ").substringBefore(" ") }.toSet().size}\n")
            org.junit.Assert.assertEquals("idle must never spin (seed $seed)", 0, spins)
            org.junit.Assert.assertEquals("uncaused face change (seed $seed)", 0, unc)
            org.junit.Assert.assertEquals("low→bright cut (seed $seed)", 0, lb)
        }
        File(out, "audit_summary.csv").writeText(sb.toString())
        // One long untouched hour: catalogue distribution (how rare the rare ones are).
        simulate(Script("hour", 3600f, preRoll = 0f) { _, _, _, _ -> AvatarMotionHints() }, 77)
        val eps = lastLog.filter { it.contains(",episode ") }.groupingBy { it.substringAfter(",episode ").substringBefore(" ") }.eachCount()
        File(out, "audit_hour_episodes.csv").writeText("episode,count\n" + eps.entries.sortedByDescending { it.value }.joinToString("\n") { "${it.key},${it.value}" } +
            "\nspins," + lastLog.count { it.contains(",spin cause=") || it.contains(",moment Spin") } +
            "\nuncaused," + lastLog.count { (it.contains(",expr ") || it.contains(",face ")) && it.contains("cause=unspecified") } + "\n")
        File(out, "audit_hour.log").writeText(lastLog.joinToString("\n"))
        AvatarDebugMetrics.enabled = false
        println(sb)
        @Suppress("UNUSED_VARIABLE") val unused = base
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

    // ------------------------------------------------------------ engine16.2 editor clip
    private data class Cfg(val shape: String, val eye: String, val body: String, val preset: String?, val eyeHex: String?, val shade: Boolean)
    private data class Change(val t: Float, val kind: EditorCue.Kind, val cfg: Cfg)

    private val editorTimeline: List<Change> = run {
        var c = Cfg("blob", "generical", "#2A92FE", "blue", null, true)
        val out = ArrayList<Change>()
        fun at(t: Float, k: EditorCue.Kind, f: (Cfg) -> Cfg) { c = f(c); out += Change(t, k, c) }
        at(1.5f, EditorCue.Kind.Shape) { it.copy(shape = "heart") }
        at(5.0f, EditorCue.Kind.Shape) { it.copy(shape = "square") }
        at(8.5f, EditorCue.Kind.EyeStyle) { it.copy(eye = "grok") }
        at(12f, EditorCue.Kind.BodyColor) { it.copy(body = "#FF781C", preset = "orange") }
        at(15f, EditorCue.Kind.EyeColor) { it.copy(eyeHex = "#1A1A1A") }
        at(18f, EditorCue.Kind.EyeStyle) { it.copy(eye = "generical", eyeHex = null) }
        at(21f, EditorCue.Kind.Shade) { it.copy(shade = false) }
        at(23.5f, EditorCue.Kind.Shade) { it.copy(shade = true) }
        // browsing quickly through shapes
        for ((k, sh) in listOf("cloud", "sparkle", "clover", "flower", "star").withIndex()) at(27f + k * 0.7f, EditorCue.Kind.Shape) { it.copy(shape = sh) }
        at(31f, EditorCue.Kind.BodyColor) { it.copy(body = "#2A92FE", preset = "blue") }
        out
    }
    private val cueSpot = mapOf(
        EditorCue.Kind.Shape to Offset(540f, 1500f), EditorCue.Kind.BodyColor to Offset(540f, 1850f),
        EditorCue.Kind.EyeStyle to Offset(540f, 2000f), EditorCue.Kind.EyeColor to Offset(540f, 2150f), EditorCue.Kind.Shade to Offset(540f, 2250f),
    )

    @Test
    fun renderEditorMorph() {
        val out = outDir
        assumeTrue(out != null && (only == null || "editor_morph" in only))
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        AvatarDebugMetrics.enabled = true
        val start = Cfg("blob", "generical", "#2A92FE", "blue", null, true)
        var seq = 0
        val sc = Script("editor_morph", 42f, preRoll = 6f, seed = 61) { st, _, t, _ ->
            for (ch in editorTimeline) if (t >= ch.t && fired.add(ch.t)) st.editorCue(EditorCue(++seq, ch.kind, cueSpot[ch.kind]))
            AvatarMotionHints()
        }
        val frames = simulate(sc, sc.seed)
        File(out, "editor_morph_behavior.csv").writeText("t,event\n" + lastLog.joinToString("\n") + "\n")
        fun cfgAt(t: Float): Triple<Cfg, Cfg, Float> {
            var prev = start; var cur = start; var since = 99f
            for (ch in editorTimeline) if (t >= ch.t) { prev = cur; cur = ch.cfg; since = t - ch.t }
            return Triple(prev, cur, since)
        }
        val labels = ArrayList<String>()
        val dir = File(out, "editor_morph_editor_live").apply { mkdirs() }
        frames.withIndex().chunked(perStrip).forEachIndexed { k, chunk ->
            captureEditorStrip(File(dir, "strip%03d.png".format(k)), chunk.map { (i, f) -> val t = i.toFloat() / fps; val c = cfgAt(t); labels += "${c.second.shape} ${c.second.eye}"; Triple(f, c, t) })
        }
        File(out, "editor_morph.csv").writeText("t\n" + labels.withIndex().joinToString("\n") { (i, l) -> "%.3f,,,,,,,,,,%s,,".format(i.toFloat() / fps, l) } + "\n")
        AvatarDebugMetrics.enabled = false
    }

    private fun ease(x: Float): Float { val u = x.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }

    private fun captureEditorStrip(file: File, frames: List<Triple<MotionFrame, Triple<Cfg, Cfg, Float>, Float>>) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.requiredSize((cellDp * frames.size).dp, cellDp.dp)) {
                    Row(Modifier.background(Color(0xFF14171C))) {
                        for ((f, c, _) in frames) Box(Modifier.size(cellDp.dp), contentAlignment = Alignment.Center) {
                            androidx.compose.foundation.Canvas(Modifier.size((cellDp * 0.72f).dp)) {
                                val (a, b, since) = c
                                val cu = ease(since / 0.38f)
                                fun cols(x: Cfg): Triple<Color, Color, Float> = Triple(
                                    MarkColors.bodyColor(x.body, x.preset, x.eye), MarkColors.eyeColor(x.eye, x.eyeHex, x.body, x.preset), if (x.shade) 1f else 0f)
                                val (ba, ea, sa) = cols(a); val (bb, eb, sb) = cols(b)
                                val body = androidx.compose.ui.graphics.lerp(ba, bb, cu)
                                val eye = androidx.compose.ui.graphics.lerp(ea, eb, cu)
                                val fade = MarkColors.genericalEyeFade(body, eye, sa + (sb - sa) * cu)
                                val geo = a.shape != b.shape || a.eye != b.eye
                                val u = if (geo) ease(since / 0.56f) else 1f
                                fun mk(x: Cfg, sink: MarkGeom?) { val sh = MarkShapes.resolve(x.shape) ?: return
                                    drawMark(sh, x.shape, x.eye == "grok", f, false, 1f, body, eye, fade, sink) }
                                if (u < 1f) {
                                    val ga = MarkGeom().also { mk(a, it) }; val gb = MarkGeom().also { mk(b, it) }
                                    drawMorph(ga, MorphPts.of(ga), gb, u, MarkGeom())
                                } else mk(b, null)
                            }
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
}
