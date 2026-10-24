package me.rerere.rikkahub.ui.components.avatar.animated

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.random.Random

/**
 * Engine21.1 CI gate: no teleports. Every channel that moves the eyes on screen — body
 * pitch / yaw / roll, the gaze (head look) and the eye lead offsets — must change by at most a
 * spring-sized step per 60 fps frame, from the very first frame on (no warm-up exemption).
 * Spin frames (deliberate whip turns, capped by the output spring) are exempt for the angles.
 *
 * Caught: the Engine21 inertialization evaluated its quintic past its own (shortened) length;
 * the t⁵ term blew the body pitch into a multi-turn whirl that read as erratic jumping eyes at
 * the start of e21_ui.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TeleportGateTest {
    private class Sc(val name: String, val pre: Float, val sec: Float, val fps: Int, val at: (AvatarMotionState, AvatarStage, Float, HashSet<Float>) -> AvatarMotionHints)

    private val scenarios = listOf(
        Sc("idle-cold", 0f, 12f, 60) { _, _, _, _ -> AvatarMotionHints() },
        Sc("ui-hooks-cold", 0f, 14f, 60) { s, st, t, f ->
            s.debugHooksAlways = true
            st.setTarget(AvatarStage.Target.Composer, this, Rect(40f, 2150f, 1040f, 2330f))
            val ev = listOf(0.1f to AvatarStage.EventKind.AttachmentAdded, 0.6f to AvatarStage.EventKind.ModelPickerOpened,
                1.1f to AvatarStage.EventKind.EditorOpened, 1.5f to AvatarStage.EventKind.SettingToggled, 2f to AvatarStage.EventKind.Copied,
                4f to AvatarStage.EventKind.Regenerated, 6f to AvatarStage.EventKind.Scroll, 8f to AvatarStage.EventKind.Error)
            for ((a, k) in ev) if (t >= a && f.add(a)) st.emit(k, at = Offset(600f, 1400f), direction = 1)
            AvatarMotionHints()
        },
        // The exact Engine21 e21_ui set-up (8 s pre-roll, 30 fps sampling of 120 Hz ticks).
        Sc("e21_ui", 8f, 12f, 30) { s, st, t, f ->
            s.debugHooksAlways = true
            st.setTarget(AvatarStage.Target.Composer, this, Rect(40f, 2150f, 1040f, 2330f))
            val ev = listOf(1f to AvatarStage.EventKind.AttachmentAdded, 5f to AvatarStage.EventKind.ModelPickerOpened, 9f to AvatarStage.EventKind.EditorOpened)
            for ((a, k) in ev) if (t >= a && f.add(a)) st.emit(k, at = Offset(600f, 1400f), direction = 1)
            AvatarMotionHints()
        },
        Sc("timeline-cold", 0f, 12f, 60) { s, st, t, f ->
            s.debugHooksAlways = true
            st.setTarget(AvatarStage.Target.ActivityPill, this, Rect(380f, 700f, 1040f, 2000f))
            for (o in listOf(0.2f, 5f)) if (t >= o && f.add(o)) st.emit(AvatarStage.EventKind.PillExpanded, at = Offset(700f, 720f))
            if (t >= 1.4f && f.add(1.4f)) st.emit(AvatarStage.EventKind.PillCollapsed, at = Offset(700f, 720f))
            AvatarMotionHints()
        },
        Sc("pokes-cold", 0f, 8f, 60) { s, _, t, f ->
            for (p in listOf(0.05f, 0.4f, 0.9f, 1.3f, 3f)) if (t >= p && f.add(p)) s.poke()
            AvatarMotionHints()
        },
        Sc("generate-cold", 0f, 14f, 60) { _, _, t, _ ->
            when { t < 0.3f -> AvatarMotionHints(); t < 4f -> AvatarMotionHints(isGenerating = true)
                t < 10f -> AvatarMotionHints(isGenerating = true, hasStreamedTokens = true); else -> AvatarMotionHints() }
        },
    )

    /** Per 60 fps frame limits (scaled ×2 for 30 fps sampling). */
    private val maxAngle = 9f      // deg / frame, body pitch / yaw / roll (non-spin)
    private val maxTilt = 45f      // deg, |pitch| / |roll| outside spins (a whirl goes through ±180)
    private val maxLook = 0.16f    // gaze (head look) units / frame
    private val maxLead = 0.06f    // eye lead offset / frame (a saccade peaks ≈ 0.04)

    private fun w(a: Float): Float { var d = a % 360f; if (d > 180f) d -= 360f; if (d < -180f) d += 360f; return d }

    @Test
    fun noTeleports() {
        ChannelScenarios.loadClips()
        val fails = ArrayList<String>()
        val rep = StringBuilder()
        val dump = if (System.getProperty("lastchat.renderDir").isNullOrBlank()) null else StringBuilder()
        val seeds = (System.getProperty("lastchat.teleportSeeds")?.toIntOrNull() ?: 12)
        for (sc in scenarios) {
            var mA = 0f; var mL = 0f; var mD = 0f; var mAt = ""
            // seed 83 = the exact Engine21 e21_ui clip that showed the whirl.
            for (seed in (1..seeds) + 83) {
                val stage = AvatarStage(Random(seed))
                val s = AvatarMotionState(Random(seed))
                val id = stage.newId()
                s.stage = stage; s.stageId = id
                stage.update(id, 1, Offset(300f, 900f), 60f, "blob|generical", 1080f, 2400f)
                val fired = HashSet<Float>()
                val tick = if (sc.fps == 30) 1f / 120f else 1f / 60f
                val k = if (sc.fps == 30) 2f else 1f
                var t = -sc.pre; var ms = 1_700_000_000_000L; var next = 0f
                var prev: MotionFrame? = null
                while (t < sc.sec) {
                    val h = if (t < 0f) AvatarMotionHints() else sc.at(s, stage, t, fired)
                    s.tick(tick, h, ms)
                    t += tick; ms += (tick * 1000).toLong()
                    if (t < 0f || t < next - 1e-4f) continue
                    next += 1f / sc.fps
                    val f = s.frame
                    val p = prev; prev = f
                    if (p == null) continue
                    val spin = f.spinYaw != 0f || f.spinPitch != 0f || f.spinRoll != 0f || p.spinYaw != 0f || p.spinPitch != 0f || p.spinRoll != 0f
                    val da = if (spin) 0f else maxOf(abs(w(f.body.pitch - p.body.pitch)), abs(w(f.body.yaw - p.body.yaw)), abs(w(f.body.roll - p.body.roll))) / k
                    val dl = maxOf(abs(f.headLookX - p.headLookX), abs(f.headLookY - p.headLookY)) / k
                    val dd = maxOf(abs(f.leadX - p.leadX), abs(f.leadY - p.leadY)) / k
                    val at = "seed $seed t=%.3f".format(t)
                    if (da > mA) { mA = da; mAt = at }
                    mL = maxOf(mL, dl); mD = maxOf(mD, dd)
                    if (!spin && maxOf(abs(f.body.pitch), abs(f.body.roll)) > maxTilt) fails += "${sc.name} $at: body tilt %.1f° outside a spin (whirl)".format(maxOf(abs(f.body.pitch), abs(f.body.roll)))
                    if (da > maxAngle) fails += "${sc.name} $at: body angle step %.2f°".format(da)
                    if (dl > maxLook) fails += "${sc.name} $at: gaze step %.3f".format(dl)
                    if (dump != null && sc.name == "idle-cold" && seed == 4) dump.appendLine("DUMP %.3f lx=%.4f ly=%.4f hx=%.3f hy=%.3f p=%.2f".format(t, f.leadX, f.leadY, f.headLookX, f.headLookY, f.body.pitch))
                    if (dd > maxLead) fails += "${sc.name} $at: eye lead step %.4f".format(dd)
                }
            }
            rep.append("%-14s maxAngle %.2f°/f (%s)  maxLook %.3f  maxLead %.4f\n".format(sc.name, mA, mAt, mL, mD))
        }
        System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() }?.let { java.io.File(it).mkdirs(); java.io.File(it, "teleport_gate.txt").writeText(rep.toString() + fails.take(40).joinToString("\n")) }
        System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() }?.let { java.io.File(it, "teleport_dump.txt").writeText(dump.toString()) }
        println(rep)
        assertTrue("teleports:\n" + fails.take(20).joinToString("\n") + "\n" + rep, fails.isEmpty())
    }

    /**
     * The body's beat target itself (before the output spring) must never whirl: across many
     * seeds, lifecycle changes and gestures its pitch / roll stay small and every frame-to-frame
     * step is spring-sized. The Engine21 quintic overrun failed this within a few seeds.
     */
    @Test
    fun beatTargetNeverWhirls() {
        MotionDirectorTest.loadClips()
        val fails = ArrayList<String>()
        val cycle = listOf(MarkLifecycle.Idle, MarkLifecycle.Thinking, MarkLifecycle.Streaming, MarkLifecycle.Idle, MarkLifecycle.Working, MarkLifecycle.Idle)
        var worst = 0f; var worstStep = 0f
        for (seed in 1..60) {
            val d = MotionDirector(Random(seed)) { AvatarPackData.clips[it] }
            val rng = Random(seed * 13 + 1)
            var t = 0f; var nextLife = 2f; var li = 0; var nextG = 1f
            var prev: BodyPose? = null
            while (t < 40f) {
                if (t >= nextLife) { li = (li + 1) % cycle.size; d.setDesired(cycle[li]); nextLife = t + 0.3f + rng.nextFloat() * 6f }
                if (t >= nextG) { d.gesture(MotionDirector.Gesture.entries[rng.nextInt(MotionDirector.Gesture.entries.size)]); nextG = t + 0.5f + rng.nextFloat() * 4f }
                d.update(1f / 60f)
                val r = d.rawPose
                val tilt = maxOf(abs(r.pitch), abs(r.roll))
                worst = maxOf(worst, tilt)
                prev?.let { p ->
                    val st = maxOf(abs(w(r.pitch - p.pitch)), abs(w(r.roll - p.roll)))
                    worstStep = maxOf(worstStep, st)
                    if (st > 12f) fails += "seed $seed t=%.2f: beat target step %.1f°".format(t, st)
                }
                if (tilt > 60f) fails += "seed $seed t=%.2f: beat target tilt %.1f°".format(t, tilt)
                prev = r
                t += 1f / 60f
            }
        }
        assertTrue("beat target whirls (max tilt %.1f°, step %.1f°):\n".format(worst, worstStep) + fails.take(15).joinToString("\n"), fails.isEmpty())
    }
}
