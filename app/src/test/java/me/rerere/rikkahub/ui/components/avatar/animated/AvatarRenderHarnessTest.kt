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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
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
 * Visual render harness — NOT a pass/fail screenshot diff. Renders the real
 * AnimatedMarkAvatar (calm editor preview + live chat path at t=0) for every
 * picker shape × {Generical, Grok}, plus the editor sheet body, to PNGs.
 *
 * Run: ./gradlew :app:testStableReleaseUnitTest \
 *        --tests '*AvatarRenderHarnessTest*' -Dlastchat.renderDir=/path
 * Skipped when lastchat.renderDir is unset (keeps CI unit runs fast).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AvatarRenderHarnessTest {

    private val outDir: File? = System.getProperty("lastchat.renderDir")
        ?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }

    private fun capture(name: String, widthDp: Int, heightDp: Int, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) { content() }
            }
        }
        // Calm / debugPose renders have no infinite loops → safe to advance the
        // clock so the 450 ms shape-morph settles at full size.
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }
        val density = activity.resources.displayMetrics.density
        val w = (widthDp * density).toInt()
        val h = (heightDp * density).toInt()
        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val view: View = root.getChildAt(0)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            view.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, w, h)
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    private fun pose(pitch: Float, yaw: Float, roll: Float = 0f) = ClipSample(
        clipName = "Harness",
        time = 0f,
        duration = 1f,
        squashAnchor = 0f,
        rotationDeg = floatArrayOf(pitch, yaw, roll),
        position = floatArrayOf(0f, 0f),
        squash = floatArrayOf(1f, 1f),
        scale = MarkClipEngine.DEFAULT_ZOOM,
        lids = 1f,
        expression = "neutral",
    )

    /** calm = editor preview; rest = live chat path at clip t=0; rest = look-around. */
    private val poses: List<Pair<String, ClipSample?>> by lazy {
        listOf(
            "calm" to null,
            "rest" to pose(0f, 0f),
            "yawL" to pose(0f, -22f),
            "yawR" to pose(0f, 22f),
            "up" to pose(-16f, 0f),
            "down" to pose(16f, 0f),
            "roll" to pose(0f, 0f, 15f),
            "combo" to pose(10f, -26f, 8f),
            "yawFar" to pose(4f, 40f, -4f),
        )
    }

    @Test
    fun renderAllShapesBothModes() {
        assumeTrue(outDir != null)
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        AvatarPackData.ensureLoaded(ctx)
        for (mode in listOf("generical", "grok")) {
            for (shape in MarkShapes.SHAPES) {
                for ((tag, p) in poses) {
                    // 200dp canvas around a 160dp avatar: rolled / turned bodies overhang
                    // their box a little (the app doesn't clip them either).
                    capture("mark_${mode}_${shape}_$tag", 200, 200) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF14171C)), contentAlignment = Alignment.Center) {
                            AnimatedMarkAvatar(
                                shapeId = shape,
                                eyeType = mode,
                                colorHex = "#2A92FE",
                                colorPreset = "blue",
                                eyeColorHex = null,
                                isLoading = false,
                                calmPreview = p == null,
                                modifier = Modifier.size(160.dp),
                                debugPose = p,
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ strips

    @Composable
    private fun Strip(shape: String, mode: String, frames: List<MotionFrame>, cellDp: Int) {
        Row(Modifier.background(Color(0xFF14171C))) {
            for (f in frames) {
                Box(Modifier.size(cellDp.dp), contentAlignment = Alignment.Center) {
                    AnimatedMarkAvatar(
                        shapeId = shape,
                        eyeType = mode,
                        colorHex = "#2A92FE",
                        colorPreset = "blue",
                        isLoading = false,
                        modifier = Modifier.size((cellDp * 0.8f).dp),
                        debugFrame = f,
                    )
                }
            }
        }
    }

    private fun stripCapture(name: String, shape: String, mode: String, frames: List<MotionFrame>, cellDp: Int = 110) =
        capture(name, cellDp * frames.size, cellDp) { Strip(shape, mode, frames, cellDp) }

    /** Every expression, face-on, for every shape (spacing review vs baseline). */
    @Test
    fun renderExpressionSheets() {
        assumeTrue(outDir != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val names = listOf("Neutral", "Happy", "Surprised", "Sad", "Worried", "Sleeping", "Bored", "angered or concentrated", "pouty", "hurt or cute", "confused")
        for (mode in listOf("grok", "generical")) for (shape in MarkShapes.SHAPES) {
            stripCapture("exprsheet_${mode}_$shape", shape, mode,
                names.map { MotionFrame(exprFrom = it, exprTo = it, exprT = 1f) }, cellDp = 90)
        }
    }

    /** Expression morphs: 7 frames across each transition, face-on and turned. */
    @Test
    fun renderExpressionMorphStrips() {
        assumeTrue(outDir != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val transitions = listOf(
            "Neutral" to "Happy",
            "Neutral" to "Surprised",
            "Happy" to "Sad",
            "Neutral" to "confused",
            "Neutral" to "angered or concentrated",
            "Surprised" to "Sleeping",
        )
        val ts = listOf(0f, 0.17f, 0.33f, 0.5f, 0.67f, 0.83f, 1f)
        for (mode in listOf("generical", "grok")) {
            for (shape in listOf("blob", "square", "heart", "cloud", "tablet")) {
                for ((a, b) in transitions) {
                    for (rot in listOf(false, true)) {
                        val frames = ts.map { t ->
                            MotionFrame(
                                body = if (rot) BodyPose(pitch = 8f, yaw = -22f, roll = 5f) else BodyPose.REST,
                                exprFrom = a, exprTo = b, exprT = MotionDirector.easeInOutCubic(t),
                            )
                        }
                        val tag = (if (rot) "_rot" else "")
                        stripCapture("morph_${mode}_${shape}_${a.replace(' ', '-')}__${b.replace(' ', '-')}$tag", shape, mode, frames)
                    }
                }
                // Blink: lids 1 → 0 → 1.
                val lids = listOf(1f, 0.75f, 0.5f, 0.25f, 0.05f, 0.4f, 1f)
                stripCapture("blink_${mode}_$shape", shape, mode, lids.map { MotionFrame(lid = it) })
            }
        }
    }

    /** Every expression at rest on every picker shape (one strip per mode × shape). */
    @Test
    fun renderExpressionGallery() {
        assumeTrue(outDir != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val exprs = listOf(
            "Neutral", "Happy", "Surprised", "Sad", "Eyes closed", "Bored",
            "confused", "hurt or cute", "pouty", "angered or concentrated",
        )
        for (mode in listOf("grok", "generical")) {
            for (shape in MarkShapes.SHAPES) {
                stripCapture("gallery_${mode}_$shape", shape, mode, exprs.map { e ->
                    MotionFrame(exprFrom = e, exprTo = e, exprT = 1f)
                })
            }
        }
    }

    /**
     * Live motion through lifecycle changes (idle → thinking → streaming → idle → typing),
     * sampled from the real [AvatarMotionState] with a fixed seed: every frame is the
     * actual pose, gaze, lids and expression morph the app would draw.
     */
    @Test
    fun renderClipTransitionStrips() {
        assumeTrue(outDir != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        for ((mode, shape) in listOf("grok" to "blob", "generical" to "square", "generical" to "heart", "grok" to "tablet")) {
            val st = AvatarMotionState(Random(7))
            val dt = 1f / 60f
            var t = 0f
            val frames = ArrayList<Pair<Float, MotionFrame>>()
            var nextCap = 0f
            while (t < 24f) {
                val hints = when {
                    t < 5f -> AvatarMotionHints()
                    t < 7f -> AvatarMotionHints(isGenerating = true)
                    t < 11f -> AvatarMotionHints(isGenerating = true, hasStreamedTokens = true)
                    t < 18f -> AvatarMotionHints()
                    else -> AvatarMotionHints(isTyping = true, inputFocused = true)
                }
                st.tick(dt, hints, (t * 1000).toLong())
                if (t >= nextCap) {
                    frames += t to st.frame
                    nextCap += 0.2f
                }
                t += dt
            }
            // 12 frames per row (2.4 s of motion per row).
            frames.chunked(12).forEachIndexed { i, row ->
                stripCapture("trans_${mode}_${shape}_%02d".format(i), shape, mode, row.map { it.second }, cellDp = 96)
            }
            File(outDir, "trans_${mode}_${shape}.txt").writeText(frames.joinToString("\n") { (tt, f) ->
                "%.2f %s %s->%s %.2f lid=%.2f look=(%.2f,%.2f) p=%.1f y=%.1f r=%.1f s=%.3f".format(
                    tt, f.lifecycle, f.exprFrom, f.exprTo, f.exprT, f.lid, f.headLookX, f.headLookY,
                    f.body.pitch, f.body.yaw, f.body.roll, f.body.scale)
            })
        }
    }

    /**
     * Two avatars on a shared stage: run the real alive layer until one of them starts
     * a glance at the other, then capture both side by side through the moment.
     */
    @Test
    fun renderSocialGlance() {
        assumeTrue(outDir != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        for ((pairTag, modes) in listOf("mixed" to ("grok" to "generical"), "twins" to ("generical" to "generical"))) {
            val stage = AvatarStage(Random(3))
            val a = AvatarMotionState(Random(21))
            val b = AvatarMotionState(Random(22))
            val idA = stage.newId(); val idB = stage.newId()
            a.stage = stage; a.stageId = idA
            b.stage = stage; b.stageId = idB
            stage.update(idA, 1, androidx.compose.ui.geometry.Offset(300f, 900f), 60f, "blob|${modes.first}", 1080f, 2400f)
            stage.update(idB, 1, androidx.compose.ui.geometry.Offset(780f, 900f), 60f, "blob|${modes.second}", 1080f, 2400f)
            val dt = 1f / 60f
            var t = 0f
            var ms = System.currentTimeMillis()
            val frames = ArrayList<Pair<MotionFrame, MotionFrame>>()
            var started = -1f
            var skepticSeen = false
            var cooldownUntil = 0f
            var glances = 0
            var nextNudge = 50f
            while (t < 1500f) {
                // Someone is using the app now and then (keeps them from dozing off).
                if (t >= nextNudge && started < 0f) {
                    stage.emit(AvatarStage.EventKind.Scroll, direction = 1, nowMs = ms)
                    nextNudge += 50f
                }
                a.tick(dt, AvatarMotionHints(), ms)
                b.tick(dt, AvatarMotionHints(), ms)
                val looking = a.frame.headLookX > 0.4f && b.frame.headLookX < -0.4f
                if (started < 0f && looking && t > cooldownUntil) {
                    started = t - 0.5f
                    frames.clear()
                    skepticSeen = false
                    glances++
                }
                if (started >= 0f) {
                    if (frames.isEmpty() || (t - started) >= frames.size * 0.4f) frames += a.frame to b.frame
                    if (a.frame.exprTo == "confused" || b.frame.exprTo == "confused") skepticSeen = true
                    if (t - started > 3.4f) {
                        if (skepticSeen) break
                        started = -1f
                        cooldownUntil = t + 5f
                    }
                }
                t += dt
                ms += 16
            }
            File(outDir, "social_$pairTag.txt").writeText("glances=$glances lastStart=$started skeptic=$skepticSeen frames=${frames.size} simulated=${t}s\n" +
                frames.joinToString("\n") { (fa, fb) ->
                    "A look=%.2f yaw=%.1f lead=%.3f %s->%s %.2f | B look=%.2f yaw=%.1f lead=%.3f %s->%s %.2f".format(
                        fa.headLookX, fa.body.yaw, fa.leadX, fa.exprFrom, fa.exprTo, fa.exprT,
                        fb.headLookX, fb.body.yaw, fb.leadX, fb.exprFrom, fb.exprTo, fb.exprT)
                })
            if (frames.isEmpty()) continue
            val cell = 96
            capture("social_$pairTag", cell * 2 * minOf(frames.size, 9) + 16 * minOf(frames.size, 9), cell) {
                Row(Modifier.background(Color(0xFF14171C))) {
                    for ((fa, fb) in frames.take(9)) {
                        Row(Modifier.size((cell * 2 + 16).dp, cell.dp)) {
                            Box(Modifier.size(cell.dp), contentAlignment = Alignment.Center) {
                                AnimatedMarkAvatar("blob", modes.first, "#2A92FE", false, colorPreset = "blue",
                                    modifier = Modifier.size((cell * 0.8f).dp), debugFrame = fa)
                            }
                            Box(Modifier.size(cell.dp), contentAlignment = Alignment.Center) {
                                AnimatedMarkAvatar("blob", modes.second, "#7C5CFF", false, colorPreset = null,
                                    modifier = Modifier.size((cell * 0.8f).dp), debugFrame = fb)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Swatch vs render: every eye swatch (both modes, two bodies) and every body swatch
     * (both modes, plus the legacy "cyan + #009FE0" pair) as calm marks; the contact
     * sheet script puts the swatch next to each render and checks the pixels.
     */
    @Test
    fun renderColorSwatches() {
        assumeTrue(outDir != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val bodies = listOf("indigo" to "#7C5CFF", "orange" to "#FF781C", "green" to "#00C972", "black" to "#000000", "azure" to "#009FE0")
        for (mode in listOf("generical", "grok")) {
            for ((bodyName, bodyHex) in bodies) {
                for (eyeHex in eyeSwatches(mode)) {
                    val isDefault = eyeHex.equals(MarkColors.defaultEyeHex(mode), true)
                    capture("eyeswatch_${mode}_${bodyName}_${eyeHex.drop(1)}", 120, 120) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF14171C)), contentAlignment = Alignment.Center) {
                            AnimatedMarkAvatar(
                                shapeId = "blob", eyeType = mode, colorHex = bodyHex, colorPreset = bodyName,
                                eyeColorHex = if (isDefault) null else eyeHex, isLoading = false,
                                calmPreview = true, modifier = Modifier.size(104.dp),
                            )
                        }
                    }
                }
            }
            val bodyCases = MarkColors.PRESETS.entries.map { Triple(it.key, it.value, it.key as String?) } +
                Triple("legacycyan", "#009FE0", "cyan")
            for ((tag, h, preset) in bodyCases) {
                capture("bodyswatch_${mode}_$tag", 120, 120) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF14171C)), contentAlignment = Alignment.Center) {
                        AnimatedMarkAvatar(
                            shapeId = "square", eyeType = mode, colorHex = h, colorPreset = preset,
                            isLoading = false, calmPreview = true, modifier = Modifier.size(104.dp),
                        )
                    }
                }
            }
        }
    }

    /** The default Generical mark, at profile size and chat size. */
    @Test
    fun renderDefaultGenerical() {
        assumeTrue(outDir != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val d = me.rerere.rikkahub.data.model.Avatar.Animated.defaultGenerical()
        capture("default_generical", 220, 130) {
            Row(Modifier.fillMaxSize().background(Color(0xFF14171C)), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
                    AnimatedMarkAvatar(d.shape, d.eyeType, d.colorHex, false, colorPreset = d.colorPreset,
                        eyeColorHex = d.eyeColorHex, calmPreview = true, modifier = Modifier.size(96.dp))
                }
                Box(Modifier.size(90.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalAvatarAnimate provides false) {
                        me.rerere.rikkahub.ui.components.ui.UIAvatar(
                            name = "Generical", value = d, modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
    }

    /**
     * Chat column: older assistant / user turns are static, only the newest of each role
     * moves. Rendered through the real UIAvatar + LocalAvatarAnimate, at the same clock.
     */
    @Test
    fun renderChatNewestStrip() {
        assumeTrue(outDir != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val gen = me.rerere.rikkahub.data.model.Avatar.Animated.defaultGenerical()
        val grok = me.rerere.rikkahub.data.model.Avatar.Animated(shape = "square", eyeType = "grok", colorHex = "#2A92FE", colorPreset = "blue")
        for ((tag, av) in listOf("generical" to gen, "grok" to grok)) {
            capture("chat_newest_$tag", 360, 330) {
                androidx.compose.foundation.layout.Column(
                    Modifier.fillMaxSize().background(Color(0xFF14171C)).padding(12.dp),
                ) {
                    for (i in 0 until 4) {
                        val newest = i == 3
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.runtime.CompositionLocalProvider(LocalAvatarAnimate provides newest) {
                                me.rerere.rikkahub.ui.components.ui.UIAvatar(name = "A", value = av, modifier = Modifier.size(36.dp))
                            }
                            Box(
                                Modifier.padding(start = 10.dp)
                                    .size(width = (180 + 30 * (i % 2)).dp, height = 48.dp)
                                    .background(if (newest) Color(0xFF2B3340) else Color(0xFF222831)),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Newest → older and back: real [AvatarMotionState], busy pose, then animate=false
     * (eases to rest), then animate=true (eases back in). 0.1 s per frame.
     */
    @Test
    fun renderSettleStrips() {
        assumeTrue(outDir != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        for ((mode, shape) in listOf("grok" to "blob", "generical" to "heart", "grok" to "square", "generical" to "blob")) {
            val st = AvatarMotionState(Random(11))
            val dt = 1f / 60f
            var ms = 0L
            var t = 0f
            while (t < 3.4f) { st.tick(dt, AvatarMotionHints(isGenerating = true, hasStreamedTokens = t > 1.5f), ms); ms += 16; t += dt }
            val out = ArrayList<MotionFrame>()
            val log = StringBuilder()
            fun run(sec: Float, hints: AvatarMotionHints) {
                var u = 0f
                var next = 0f
                while (u < sec) {
                    if (u >= next) {
                        out += st.frame
                        val f = st.frame
                        log.append("%.2f anim=%s settled=%s y=%.1f p=%.1f r=%.1f s=%.3f lid=%.2f %s->%s %.2f look=(%.2f,%.2f)\n".format(
                            out.size * 0.1f, st.animate, st.isSettled, f.body.yaw, f.body.pitch, f.body.roll, f.body.scale,
                            f.lid, f.exprFrom, f.exprTo, f.exprT, f.headLookX, f.headLookY))
                        next += 0.1f
                    }
                    st.tick(dt, hints, ms); ms += 16; u += dt
                }
            }
            st.animate = false
            run(1.2f, AvatarMotionHints(isGenerating = true, hasStreamedTokens = true))
            run(0.4f, AvatarMotionHints())
            st.animate = true
            run(1.6f, AvatarMotionHints())
            File(outDir, "settle_${mode}_$shape.txt").writeText(log.toString())
            out.chunked(16).forEachIndexed { i, row ->
                stripCapture("settle_${mode}_${shape}_$i", shape, mode, row, cellDp = 80)
            }
        }
    }

    /** Side drawer: expanded bottom pill + collapsed rail button with animated marks. */
    @Test
    fun renderSidePanelButtons() {
        assumeTrue(outDir != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val gen = me.rerere.rikkahub.data.model.Avatar.Animated.defaultGenerical()
        val grok = me.rerere.rikkahub.data.model.Avatar.Animated(shape = "star", eyeType = "grok", colorHex = "#FF781C", colorPreset = "orange")
        capture("side_panel", 360, 200) {
            Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                androidx.compose.foundation.layout.Column(
                    Modifier.size(width = 80.dp, height = 200.dp).background(Color(0xFF1B1F26)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                ) {
                    me.rerere.rikkahub.ui.pages.chat.DrawerRailAssistantButton("Generical", gen, onClick = {})
                    me.rerere.rikkahub.ui.pages.chat.DrawerRailAssistantButton("Star", grok, onClick = {})
                }
                androidx.compose.foundation.layout.Column(
                    Modifier.size(width = 280.dp, height = 200.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                ) {
                    for ((n, a) in listOf("Generical" to gen, "Star" to grok)) {
                        me.rerere.rikkahub.ui.pages.chat.DrawerAssistantPill(
                            assistantName = n, avatar = a,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            avatarSize = 30.dp, onNameClick = {}, onAvatarClick = {},
                            modifier = Modifier.padding(horizontal = 8.dp).size(width = 260.dp, height = 42.dp),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun renderEditorSheet() {
        assumeTrue(outDir != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        // 411x820 = roomy phone; 360x600 = narrow/short sheet that MUST scroll
        // (5 tiles per row → 3 shape rows), scrolled to the end to prove the last
        // row clears the pinned Cancel/Save bar.
        val cases = listOf(
            Triple("blob", false, 411 to 820),
            Triple("square", true, 411 to 820),
            Triple("blob", false, 360 to 600),
            Triple("square", true, 360 to 600),
        )
        for (mode in listOf("generical", "grok")) {
            for ((shape, scrolled, dims) in cases) {
                val tag = (if (scrolled) "bottom" else "top") + "_${dims.first}x${dims.second}"
                capture("editor_${mode}_${shape}_$tag", dims.first, dims.second) {
                    // Int.MAX_VALUE is clamped to maxValue on first layout → bottom.
                    val scroll = rememberScrollState(if (scrolled) Int.MAX_VALUE else 0)
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        AnimatedAvatarEditorContent(
                            shape = shape,
                            onShape = {},
                            eyeType = mode,
                            onEyeType = {},
                            colorHex = "#2A92FE",
                            colorPreset = "blue",
                            onColor = { _, _ -> },
                            eyeColorHex = null,
                            onEyeColor = {},
                            onPickColor = {},
                            onPickEyeColor = {},
                            onDismiss = {},
                            onSave = {},
                            modifier = Modifier.fillMaxSize(),
                            scrollState = scroll,
                            livePreview = false,
                        )
                    }
                }
            }
        }
        // Engine16.2c: light theme too (soft surfaces must read in both).
        for (mode in listOf("generical", "grok")) capture("editor_${mode}_blob_top_411x820_light", 411, 820) {
            MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme()) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    AnimatedAvatarEditorContent(
                        shape = "blob", onShape = {}, eyeType = mode, onEyeType = {}, colorHex = "#2A92FE", colorPreset = "blue",
                        onColor = { _, _ -> }, eyeColorHex = null, onEyeColor = {}, onPickColor = {}, onPickEyeColor = {},
                        onDismiss = {}, onSave = {}, modifier = Modifier.fillMaxSize(), livePreview = false,
                    )
                }
            }
        }
        @Suppress("UNUSED_VARIABLE") val unused = runBlocking { }
    }

    /**
     * Engine20 "screen recording" of the editor: real composition, real animations, frames
     * every 33 ms while a script taps a shape, a colour, switches the eye type there and
     * back, then scrolls to the end. Frames land in editor_rec/fNNNN.png.
     */
    @Test
    fun renderEditorRecording() {
        assumeTrue(outDir != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val dir = File(outDir, "editor_rec").apply { mkdirs() }
        val shape = androidx.compose.runtime.mutableStateOf("blob")
        val eye = androidx.compose.runtime.mutableStateOf("generical")
        val col = androidx.compose.runtime.mutableStateOf("#2A92FE" to "blue")
        val eyeHex = androidx.compose.runtime.mutableStateOf<String?>(null)
        val doScroll = androidx.compose.runtime.mutableStateOf(false)
        // Robolectric's animator scale defaults to 0 (every Compose animation would snap).
        android.provider.Settings.Global.putFloat(org.robolectric.RuntimeEnvironment.getApplication().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val wDp = 411; val hDp = 820
        activity.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.requiredSize(wDp.dp, hDp.dp).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    val sc = rememberScrollState(0)
                    androidx.compose.runtime.LaunchedEffect(doScroll.value) {
                        if (doScroll.value) sc.animateScrollTo(sc.maxValue, androidx.compose.animation.core.tween(900))
                    }
                    AnimatedAvatarEditorContent(
                        shape = shape.value, onShape = {}, eyeType = eye.value, onEyeType = {},
                        colorHex = col.value.first, colorPreset = col.value.second, onColor = { _, _ -> },
                        eyeColorHex = eyeHex.value, onEyeColor = {}, onPickColor = {}, onPickEyeColor = {},
                        onDismiss = {}, onSave = {}, modifier = Modifier.fillMaxSize(), scrollState = sc, livePreview = false,
                    )
                }
            }
        }
        val d = activity.resources.displayMetrics.density
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
        val view: View = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        var n = 0
        fun frames(sec: Float) {
            repeat((sec / 0.033f).toInt()) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(33))
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, w, h)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bmp))
                File(dir, "f%04d.png".format(n++)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            }
        }
        frames(0.6f)
        shape.value = "heart"; frames(0.9f)
        shape.value = "clover"; frames(0.9f)
        col.value = "#FF781C" to "orange"; frames(0.9f)
        doScroll.value = true
        frames(1.4f)
        eye.value = "grok"; frames(1.2f)
        eye.value = "generical"; frames(1.4f)
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
        controller.pause().stop().destroy()
    }

    /** Engine17: chat input hint text before (default onSurfaceVariant) vs after (dimmed). */
    @Test
    fun renderChatInputHint() {
        assumeTrue(outDir != null)
        for (dark in listOf(true, false)) capture("chat_hint_${if (dark) "dark" else "light"}", 411, 220) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
                androidx.compose.foundation.layout.Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    for (after in listOf(false, true)) {
                        androidx.compose.material3.Text(if (after) "AFTER" else "BEFORE", color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelSmall)
                        val st = androidx.compose.foundation.text.input.rememberTextFieldState()
                        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh,
                            androidx.compose.foundation.shape.RoundedCornerShape(28.dp))) {
                            androidx.compose.material3.TextField(
                                state = st, modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    androidx.compose.material3.Text("Message Grok…", maxLines = 1,
                                        color = if (after) me.rerere.rikkahub.ui.components.ai.chatInputHintColor()
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                colors = androidx.compose.material3.TextFieldDefaults.colors().copy(
                                    unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Engine16.2b: the onboarding (first-time setup) bottom buttons, for side-by-side with the editor bar. */
    @Test
    fun renderSetupButtons() {
        assumeTrue(outDir != null)
        for (dark in listOf(true, false)) capture("setup_buttons_${if (dark) "dark" else "light"}", 411, 140) {
            androidx.compose.runtime.CompositionLocalProvider(me.rerere.rikkahub.ui.theme.LocalDarkMode provides dark) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
                    Box(Modifier.fillMaxSize().background(if (dark) Color.Black else MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 40.dp)) {
                        // Same as onboarding TwoSetupButtons: 12 dp gap, outlined left, filled right.
                        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                            me.rerere.rikkahub.ui.pages.onboarding.SetupButtonFace(Modifier.weight(1f), text = "Back", filled = false, onClick = {})
                            me.rerere.rikkahub.ui.pages.onboarding.SetupButtonFace(Modifier.weight(1f), text = "Continue",
                                icon = androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowForward, filled = true, onClick = {})
                        }
                    }
                }
            }
        }
    }

    /** Parametric looks on every shape, both eye modes (neutral + a few base expressions). */
    @Test
    fun renderAccentGallery() {
        assumeTrue(outDir != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val rig = FaceRig(Random(3))
        val looks = FaceAccent.entries.filter { it != FaceAccent.None }
        for (mode in listOf("grok", "generical")) {
            for (shape in MarkShapes.SHAPES) {
                val frames = listOf(MotionFrame()) + looks.map { a ->
                    MotionFrame(face = rig.staticFace(a, 0.3f))
                } + listOf(
                    MotionFrame(exprFrom = "Happy", exprTo = "Happy", face = rig.staticFace(FaceAccent.Glee)),
                    MotionFrame(exprFrom = "Sad", exprTo = "Sad", face = rig.staticFace(FaceAccent.Worried)),
                    MotionFrame(body = BodyPose(pitch = 6f, yaw = -20f, roll = 6f), face = rig.staticFace(FaceAccent.Curious)),
                )
                stripCapture("accent_${mode}_$shape", shape, mode, frames, cellDp = 96)
            }
        }
    }

    /** Assistant header: avatar left of the pill, bubbles on/off, older turns without avatar, slot transition. */
    @Test
    fun renderChatHeaderStrips() {
        assumeTrue(outDir != null)
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val gen = me.rerere.rikkahub.data.model.Avatar.Animated.defaultGenerical()
        @Composable
        fun pill(w: Int, expanded: Boolean = false) {
            Box(
                Modifier.size(width = w.dp, height = if (expanded) 150.dp else 36.dp)
                    .background(Color(0xFF2E3644), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            )
        }
        @Composable
        fun body(bubble: Boolean) {
            Box(
                Modifier.padding(top = 6.dp).size(width = 280.dp, height = 44.dp)
                    .background(if (bubble) Color(0xFF262D38) else Color(0x00000000))
            ) {
                if (!bubble) Box(Modifier.padding(4.dp).size(width = 250.dp, height = 10.dp).background(Color(0xFF5A6270)))
            }
        }
        for (bubble in listOf(true, false)) {
            capture("chat_header_${if (bubble) "bubble" else "plain"}", 360, 760) {
                androidx.compose.foundation.layout.Column(
                    Modifier.fillMaxSize().background(Color(0xFF14171C)).padding(12.dp),
                ) {
                    // older turns (no avatar), with and without pill / name
                    listOf(Triple(false, true, false), Triple(false, false, true), Triple(true, true, true)).forEachIndexed { i, (newest, hasPill, name) ->
                        androidx.compose.runtime.CompositionLocalProvider(LocalAvatarAnimate provides newest) {
                            me.rerere.rikkahub.ui.components.chat.AssistantTurnHeader(
                                showIcon = true,
                                avatarVisible = newest,
                                avatarName = "Generical",
                                avatarValue = gen,
                                loading = false,
                                showModelName = name,
                                nameAlpha = 1f,
                                pill = if (hasPill) ({ pill(150 + 20 * i) }) else null,
                            )
                        }
                        body(bubble)
                        Box(Modifier.size(1.dp, 18.dp))
                    }
                    // newest turn with the pill expanded into its panel: avatar stays on row 1
                    me.rerere.rikkahub.ui.components.chat.AssistantTurnHeader(
                        showIcon = true, avatarVisible = true, avatarName = "Generical", avatarValue = gen,
                        loading = false, showModelName = true, nameAlpha = 1f,
                        pill = { pill(230, expanded = true) },
                    )
                    body(bubble)
                    Box(Modifier.size(1.dp, 18.dp))
                    // transition frames of the slot (0 → 1)
                    Row {
                        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                            Box(Modifier.size(width = 66.dp, height = 44.dp)) {
                                me.rerere.rikkahub.ui.components.chat.AssistantTurnHeader(
                                    showIcon = true, avatarVisible = p > 0.5f, avatarName = "G", avatarValue = gen,
                                    loading = false, showModelName = false, nameAlpha = 1f,
                                    pill = { pill(18) }, avatarProgressOverride = p,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
