package me.rerere.rikkahub.ui.components.avatar.animated

import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * Engine19 trace scenarios. Uses only the public frame + poke API, so the same file runs on
 * engine17.2 / engine18 checkouts for honest baselines; [attach] lets Engine19 hook its
 * internal [ChannelTrace] (owner, cause, blinks, saccades) on top.
 */
object ChannelScenarios {
    fun loadClips() {
        if (!AvatarPackData.isLoaded()) AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val f = listOf(File("src/main/assets/avatar/clips068.json"), File("app/src/main/assets/avatar/clips068.json")).first { it.exists() }
        AvatarPackData.parseClips(f.readText())
    }

    val NAMES = listOf("pan_poke", "rapid_pokes", "idle12", "session")

    private fun hintsSession(t: Float): AvatarMotionHints = when {
        t in 360f..370f -> AvatarMotionHints(inputFocused = true, isTyping = true)
        t in 370f..380f -> AvatarMotionHints(isGenerating = true)
        t in 380f..410f -> AvatarMotionHints(isGenerating = true, hasStreamedTokens = true)
        t in 520f..528f -> AvatarMotionHints(isGenerating = true)
        t in 528f..560f -> AvatarMotionHints(isGenerating = true, hasStreamedTokens = true)
        else -> AvatarMotionHints()
    }
    private val SESSION_POKES = listOf(240f, 241.0f, 241.7f, 242.3f, 300f, 420f, 500f, 500.6f, 501.2f, 501.8f, 502.4f, 503.0f, 503.6f, 620f)

    fun run(name: String, seed: Int, hz: Int = 60, attach: ((AvatarMotionState) -> ChannelTrace?)? = null): ChannelTrace {
        val now = java.util.Calendar.getInstance().apply { set(2026, 9, 13, 15, 0, 0) }.timeInMillis
        val s = AvatarMotionState(Random(seed))
        val internal = attach?.invoke(s)
        val tr = internal ?: ChannelTrace()
        val dt = 1f / hz
        val dur = when (name) { "pan_poke" -> 420f; "rapid_pokes" -> 180f; "idle12" -> 720f; else -> 720f }
        val pokes = when (name) {
            "rapid_pokes" -> (0 until 4).flatMap { k -> val b = 30f + k * 40f; List(5) { b + it * 0.35f } }.toMutableList()
            "session" -> SESSION_POKES.toMutableList()
            else -> ArrayList()
        }
        var t = 0f
        var lastPoke = -99f
        var prevYaw = 0f
        var prevYaw2 = 0f
        val n = (dur * hz).toInt()
        repeat(n) {
            t += dt
            while (pokes.isNotEmpty() && t >= pokes[0]) { pokes.removeAt(0); s.poke(); if (internal == null) tr.poke(t) }
            if (name == "pan_poke" && t > 15f && t - lastPoke > 15f) {
                // Poke mid-turn: while the visible turn is ≥ 12° and still moving.
                val f = s.frame
                val vy = f.body.yaw + 18f * f.headLookX
                if (abs(vy) > 7f && abs(prevYaw - prevYaw2) > 0.01f && abs(f.spinYaw) < 0.5f) {
                    s.poke(); lastPoke = t; if (internal == null) tr.poke(t)
                }
            }
            val hints = if (name == "session") hintsSession(t) else AvatarMotionHints()
            s.tick(dt, hints, now + (t * 1000).toLong())
            val f = s.frame
            prevYaw2 = prevYaw
            prevYaw = f.body.yaw + 18f * f.headLookX
            if (internal == null) tr.add(ChannelTrace.fromFrame(t, f))
        }
        return tr
    }

    /** Frame-only metric subset (comparable across engines). */
    val FRAME_KEYS = listOf(
        "neutral_dips", "stopgo_all_min", "dead_frac", "stopgo_expr_min", "stopgo_yaw_min", "stopgo_head_min", "jerk_yaw_rms", "jerk_yaw_p995",
        "jerk_pitch_rms", "jerk_pitch_p995", "rev_yaw_s", "rev_head_s", "face_targets_min", "pokes", "react_gaze_max",
        "react_face_max", "recenter_before_react", "energy_yaw",
    )
}
