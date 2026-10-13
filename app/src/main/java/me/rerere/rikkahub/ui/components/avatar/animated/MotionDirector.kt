package me.rerere.rikkahub.ui.components.avatar.animated

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Procedural motion that is gentle throughout: a busy phase may hand over any time. */
class CalmProceduralSource(
    override val name: String,
    override val duration: Float,
    private val fn: (Float) -> BodyPose,
) : MotionSource {
    override fun pose(t: Float): BodyPose = fn((t / duration).coerceIn(0f, 1f))
}

/**
 * One body pose. Rotation in degrees (pitch: + looks down, yaw: + looks right, roll),
 * posY = clip bob (desktop units), squash per axis, scale relative to rest size
 * (1 = rest), lidOpen 0..1 from clip lid tracks.
 */
data class BodyPose(
    val pitch: Float = 0f,
    val yaw: Float = 0f,
    val roll: Float = 0f,
    val posY: Float = 0f,
    val squashX: Float = 1f,
    val squashY: Float = 1f,
    val scale: Float = 1f,
    val lidOpen: Float = 1f,
) {
    /** Shortest-arc blend toward [o] by [w] (0..1). */
    fun lerpTo(o: BodyPose, w: Float): BodyPose {
        if (w <= 0f) return this
        return BodyPose(
            pitch = pitch + MarkClipEngine.wrap180(o.pitch - pitch) * w,
            yaw = yaw + MarkClipEngine.wrap180(o.yaw - yaw) * w,
            roll = roll + MarkClipEngine.wrap180(o.roll - roll) * w,
            posY = posY + (o.posY - posY) * w,
            squashX = squashX + (o.squashX - squashX) * w,
            squashY = squashY + (o.squashY - squashY) * w,
            scale = scale + (o.scale - scale) * w,
            lidOpen = lidOpen + (o.lidOpen - lidOpen) * w,
        )
    }

    companion object {
        val REST = BodyPose()
    }
}

/** Something a layer can play: a carved clip, a still "settle" pose, or a small gesture. */
interface MotionSource {
    val name: String
    /** Natural end in local seconds. */
    val duration: Float
    fun pose(t: Float): BodyPose
    /** Clip-authored expression at [t] (null = this source has no opinion). */
    fun expression(t: Float): String? = null
    /** Clip-authored blink key crossed in (t0, t1]. */
    fun blinkIn(t0: Float, t1: Float): Boolean = false
    /** Pose at [t] is near rest, so leaving here never cuts a gesture. */
    fun calmAt(t: Float): Boolean = true
}

/**
 * Plays a desktop clip with per-play variation (amplitude, mirror). Looking UP is
 * the Grok brand cue, so upward pitch is softened continuously (never a step).
 */
class ClipSource(
    val clip: MarkClip,
    private val amp: Float,
    private val mirror: Boolean,
    windowEnd: Float,
    private val squashAmp: Float = 0.5f,
) : MotionSource {
    private val spin = MarkClipEngine.spinAxes(clip, windowEnd + 1.5f)
    private val hasExpr = !clip.tracks["expression"].isNullOrEmpty()
    private val calmTimes: List<Float> by lazy {
        val out = ArrayList<Float>()
        var t = 0f
        while (t <= clip.duration) {
            if (MarkClipEngine.isCalmAt(clip, min(t, clip.duration - 1e-3f))) out += t
            t += 0.05f
        }
        out
    }
    override val name: String get() = clip.name
    override val duration: Float get() = clip.duration

    override fun pose(t: Float): BodyPose {
        val s = MarkClipEngine.sample(clip, t.coerceIn(0f, clip.duration - 1e-3f))
        var p = s.rotationDeg[0]
        var y = s.rotationDeg[1]
        var r = s.rotationDeg[2]
        if (!spin[1]) y *= amp
        if (!spin[2]) r *= amp
        if (mirror) {
            y = -y; r = -r
        }
        if (!spin[0]) p = MotionDirector.softenUp(p, y) * amp
        return BodyPose(
            pitch = p,
            yaw = y,
            roll = r,
            posY = s.position[1] * amp,
            squashX = 1f + (s.squash[0] - 1f) * squashAmp * amp,
            squashY = 1f + (s.squash[1] - 1f) * squashAmp * amp,
            // Desktop zoom swings (Working_B dips to 0.42) read as the avatar changing
            // size. Keep only a whisper of it.
            scale = 1f + (s.scale / MarkClipEngine.DEFAULT_ZOOM - 1f) * 0.12f,
            lidOpen = s.lids,
        )
    }

    override fun expression(t: Float): String? =
        if (hasExpr) MarkClipEngine.sample(clip, t.coerceIn(0f, clip.duration - 1e-3f)).expression else null

    override fun blinkIn(t0: Float, t1: Float): Boolean {
        val keys = clip.tracks["expression"] ?: return false
        return keys.any { it.expression == "blink" && it.t > t0 && it.t <= t1 }
    }

    override fun calmAt(t: Float): Boolean {
        val tt = t.coerceIn(0f, clip.duration)
        return calmTimes.any { abs(it - tt) <= 0.051f }
    }

    /** First calm time ≥ [t] (or null). */
    fun nextCalm(t: Float): Float? = calmTimes.firstOrNull { it >= t }
    fun calmStarts(maxT: Float): List<Float> = calmTimes.filter { it <= maxT }
}

class HoldSource(override val name: String, private val p: BodyPose, override val duration: Float) : MotionSource {
    override fun pose(t: Float): BodyPose = p
}

class ProceduralSource(
    override val name: String,
    override val duration: Float,
    private val fn: (Float) -> BodyPose,
) : MotionSource {
    override fun pose(t: Float): BodyPose = fn((t / duration).coerceIn(0f, 1f))
    override fun calmAt(t: Float): Boolean = t >= duration
}

/**
 * Motion director — replaces the old clip loop + 0.35 s bridge.
 *
 * Root causes of the hard cuts it fixes:
 *  - the bridge eased to the next clip's t=0 pose and then jumped to a random phase;
 *  - same-clip "soft re-phase" set clipElapsed directly (a jump);
 *  - brand-stare suppression was a step function (pose snapped mid-clip);
 *  - loops wrapped (`t % duration`) even when last key ≠ first key;
 *  - pose knobs (lid/eyeGap/position gain) switched instantly with lifecycle.
 *
 * Now every beat is a layer that fades in over the still-playing previous layer
 * (smootherstep crossfade, shortest-arc euler), clips play a varied window once and
 * never wrap, the output runs through a light critically-damped spring, and busy
 * clips finish their phrase (or reach a calm pose) before handing back to idle.
 */
class MotionDirector(
    private val rng: Random = Random.Default,
    private val clipLookup: (String) -> MarkClip? = { if (AvatarPackData.isLoaded()) AvatarPackData.clips[it] else null },
) {
    enum class Gesture { Spin, Hop, Nod, Recoil, TurnAway, Droop, Tilt, Attend }

    private class Layer(
        val source: MotionSource,
        var t: Float,
        val tEnd: Float,
        val speed: Float,
        val fadeIn: Float,
        val kind: String,
        val gesture: Boolean = false,
    ) {
        var progress = 0f
        val weight: Float get() = smootherstep(progress)
        fun pose() = source.pose(t)
        fun isCalm() = source.calmAt(t)
    }

    var lifecycle: MarkLifecycle = MarkLifecycle.Idle
        private set
    private var desired: MarkLifecycle = MarkLifecycle.Idle
    private val layers = ArrayList<Layer>()
    private var lifeSec = 0f
    private var pendingSince = -1f
    private val recentKinds = ArrayDeque<String>()
    private val recentClips = ArrayDeque<String>()

    /** Mood from the motion state (valence −1…1, arousal 0…1): biases idle choices. */
    var moodValence: Float = 0f
    var moodArousal: Float = 0.5f

    /** Set by the alive brain: long idle → slower, drooping holds. */
    var drowsy: Boolean = false

    /** One-shot flag: a busy phase just handed back to Idle (reply finished). */
    var finishedBusy: Boolean = false

    /** Expression authored by the dominant clip layer (null = none). */
    var clipExpression: String? = null
        private set

    /** A clip-authored blink key was crossed this frame. */
    var clipBlink: Boolean = false
        private set

    /** True on frames where a layer was pushed or dropped (tests: no jump allowed there). */
    var stackChanged: Boolean = false
        private set

    /** Blended pose before the output spring (tests check this is continuous too). */
    var rawPose: BodyPose = BodyPose.REST
        private set

    internal fun debugLayers(): String = layers.joinToString("|") { "%s@%.2f/%.2f w%.2f".format(it.source.name, it.t, it.tEnd, it.weight) }

    /** Name of the dominant layer (debug / tests). */
    val activeName: String get() = layers.lastOrNull()?.source?.name ?: "-"

    // Post-filter (critically damped spring) state: pitch, yaw, roll, posY, sqX, sqY, scale, lid
    private val fx = FloatArray(8)
    private val fv = FloatArray(8)
    private val rawUnwrapped = FloatArray(3)
    private val lastRaw = FloatArray(3)
    private var filterReady = false

    fun setDesired(l: MarkLifecycle) {
        desired = l
    }

    /** Start a small gesture now (crossfaded). Returns false when not appropriate. */
    /** [holdSec] is only used by [Gesture.Attend] (how long to keep still and look). */
    fun gesture(g: Gesture, holdSec: Float = 0f): Boolean {
        val calm = lifecycle == MarkLifecycle.Idle || lifecycle == MarkLifecycle.Listening ||
            lifecycle == MarkLifecycle.Sleeping
        if (!calm || desired != lifecycle) return false
        val top = layers.lastOrNull()
        // Recoil may interrupt anything; a tilt may ride on top of an attentive hold (🤨).
        val overAttend = top?.kind == "attend" && g == Gesture.Tilt
        if (top != null && top.gesture && g != Gesture.Recoil && !overAttend) return false
        val layer = when (g) {
            Gesture.Spin -> {
                val dir = if (rng.nextBoolean()) 1f else -1f
                val d = range(1.45f, 1.8f)
                Layer(ProceduralSource("spin", d) { u ->
                    val e = easeInOutCubic(u)
                    BodyPose(
                        pitch = sin(PI.toFloat() * u) * 3f,
                        yaw = dir * 360f * e,
                        roll = -dir * sin(PI.toFloat() * u) * 4f,
                    )
                }, 0f, d, 1f, 0.3f, "spin", gesture = true)
            }
            Gesture.Hop -> {
                val clip = clipLookup("Done_B")
                if (clip != null) {
                    Layer(ClipSource(clip, 0.42f, rng.nextBoolean(), 0.95f, squashAmp = 1f), 0f, 0.95f, range(0.9f, 1.05f), 0.18f, "hop", gesture = true)
                } else null
            }
            Gesture.Nod -> {
                val d = range(0.75f, 0.95f)
                val deep = range(6f, 9f)
                Layer(ProceduralSource("nod", d) { u ->
                    val s = sin(PI.toFloat() * u)
                    BodyPose(pitch = deep * s * s)
                }, 0f, d, 1f, 0.2f, "nod", gesture = true)
            }
            Gesture.Recoil -> {
                val d = 0.65f
                Layer(ProceduralSource("recoil", d) { u ->
                    val k = sin(PI.toFloat() * u) * (1f - u)
                    BodyPose(
                        pitch = -6f * k,
                        squashX = 1f + 0.06f * k,
                        squashY = 1f - 0.06f * k,
                    )
                }, 0f, d, 1f, 0.1f, "recoil", gesture = true)
            }
            Gesture.TurnAway -> {
                val side = if (rng.nextBoolean()) 1f else -1f
                val d = range(1.2f, 1.8f)
                Layer(HoldSource("turnAway", BodyPose(yaw = side * range(16f, 22f), roll = -side * 3f, pitch = 2f), d),
                    0f, d, 1f, 0.35f, "turnAway", gesture = true)
            }
            Gesture.Tilt -> {
                val side = if (rng.nextBoolean()) 1f else -1f
                val d = range(1.2f, 1.6f)
                Layer(HoldSource("tilt", BodyPose(roll = side * range(6f, 9f), yaw = side * range(2f, 5f), pitch = 1f), d),
                    0f, d, 1f, 0.35f, "tiltG", gesture = true)
            }
            Gesture.Attend -> {
                // Settle the idle sway so a look at something reads as a deliberate look.
                val d = holdSec.coerceIn(0.8f, 4f)
                Layer(HoldSource("attend", BodyPose(pitch = 1f), d), 0f, d, 1f, 0.45f, "attend", gesture = true)
            }
            Gesture.Droop -> {
                val d = range(2.2f, 3.4f)
                Layer(HoldSource("droop", BodyPose(pitch = range(10f, 14f), roll = range(-5f, 5f)), d),
                    0f, d, 1f, range(1.6f, 2.4f), "droop", gesture = true)
            }
        } ?: return false
        push(layer)
        return true
    }

    fun update(dtIn: Float): BodyPose {
        val dt = dtIn.coerceIn(0f, 0.1f)
        lifeSec += dt
        clock += dt
        clipBlink = false
        stackChanged = false
        handOverDelta = 0f
        if (layers.isEmpty()) {
            push(nextBeat(desired, entering = true).also { it.progress = 1f })
            lifecycle = desired
        }

        // --- lifecycle hand-over (finish the phrase when leaving busy) ---
        if (desired != lifecycle) {
            val leavingBusy = isBusy(lifecycle) && !isBusy(desired) && desired != MarkLifecycle.Failed
            var commit = true
            if (leavingBusy) {
                if (pendingSince < 0f) pendingSince = lifeSec
                val waited = lifeSec - pendingSince
                val top = layers.last()
                val topDone = top.t >= top.tEnd
                commit = lifeSec >= MIN_BUSY_SEC && (top.isCalm() || topDone || waited >= MAX_FINISH_WAIT)
            }
            if (commit) {
                if (leavingBusy && desired == MarkLifecycle.Idle) finishedBusy = true
                lifecycle = desired
                lifeSec = 0f
                pendingSince = -1f
                push(nextBeat(lifecycle, entering = true))
            }
        } else {
            pendingSince = -1f
        }

        // --- advance layers ---
        for (l in layers) {
            val t0 = l.t
            l.t += dt * l.speed
            l.progress = min(1f, l.progress + dt / l.fadeIn.coerceAtLeast(0.02f))
            if (l === layers.last() && l.source.blinkIn(t0, l.t)) clipBlink = true
        }
        // Drop everything underneath a fully faded-in layer.
        val topFull = layers.indexOfLast { it.progress >= 1f }
        if (topFull > 0) {
            val before = composeNow()
            repeat(topFull) { layers.removeAt(0) }
            stackChanged = true
            noteHandOver(before)
        }

        // --- schedule the next beat when the top window ends ---
        val top = layers.last()
        val waitingToLeaveBusy = desired != lifecycle
        if (top.t >= top.tEnd && !(waitingToLeaveBusy && isBusy(lifecycle))) {
            push(nextBeat(lifecycle, entering = false))
        }

        // --- compose ---
        val pose = composeNow()
        val dom = layers.lastOrNull { it.weight >= 0.5f } ?: layers[0]
        clipExpression = dom.source.expression(dom.t)
        rawPose = pose

        return filter(pose, dt)
    }

    // ------------------------------------------------------------------ beats

    private fun composeNow(): BodyPose {
        var pose = layers[0].pose()
        for (i in 1 until layers.size) pose = pose.lerpTo(layers[i].pose(), layers[i].weight)
        return pose
    }

    /**
     * Largest pose change caused purely by a stack edit (push / fold / drop) this frame,
     * evaluated at the same instant before and after the edit. A hard cut shows up here;
     * the authored motion of the clips themselves does not.
     */
    internal var handOverDelta = 0f
        private set

    private fun noteHandOver(before: BodyPose) {
        if (layers.isEmpty()) return
        val after = composeNow()
        val d = maxOf(
            abs(MarkClipEngine.wrap180(after.pitch - before.pitch)),
            abs(MarkClipEngine.wrap180(after.yaw - before.yaw)),
            abs(MarkClipEngine.wrap180(after.roll - before.roll)),
            abs(after.scale - before.scale) * 100f,
        )
        handOverDelta = maxOf(handOverDelta, d)
    }

    private fun push(l: Layer) {
        stackChanged = true
        val before = if (layers.isEmpty()) null else composeNow()
        // Stack full: fold the two bottom layers into one frozen pose so nothing
        // that is still visible gets dropped (dropping it would be a jump).
        if (layers.size >= 4) {
            val a = layers[0]
            val b = layers[1]
            val merged = a.pose().lerpTo(b.pose(), b.weight)
            layers.removeAt(0)
            layers[0] = Layer(HoldSource("blend", merged, 0.5f), 0f, 0.5f, 1f, 1f, "blend").also { it.progress = 1f }
        }
        layers += l
        before?.let { noteHandOver(it) }
        recentKinds.addLast(l.kind)
        while (recentKinds.size > 3) recentKinds.removeFirst()
        (l.source as? ClipSource)?.let {
            recentClips.addLast(it.name)
            while (recentClips.size > 2) recentClips.removeFirst()
        }
    }

    private fun nextBeat(life: MarkLifecycle, entering: Boolean): Layer {
        val fade = if (entering) range(0.9f, 1.3f) else range(0.75f, 1.2f)
        return when (life) {
            MarkLifecycle.Idle -> idleBeat(fade)
            MarkLifecycle.Listening -> if (rng.nextFloat() < 0.65f || lastKind() != "hold") {
                hold("listen", BodyPose(pitch = range(2f, 5f), yaw = gauss(3f), roll = gauss(2f)), range(2f, 5f), fade)
            } else clipBeat("Idle_B", 0.3f, 0.5f, fade, "idleB") ?: hold("listen", BodyPose(pitch = 3f), 3f, fade)
            MarkLifecycle.Thinking, MarkLifecycle.Working, MarkLifecycle.Streaming -> busyBeat(life, fade)
            MarkLifecycle.Finished -> clipBeat(if (rng.nextBoolean()) "Done_A" else "Done_C", 0.4f, 0.55f, fade, "done")
                ?: idleBeat(fade)
            MarkLifecycle.Failed -> clipBeat(if (lastKind() == "blocked") "Blocked_B" else "Blocked_A", 0.6f, 0.8f, fade, "blocked")
                ?: hold("sad", BodyPose(pitch = 12f), 3f, fade)
            MarkLifecycle.Sleeping -> hold("sleep", BodyPose(pitch = range(7f, 11f), roll = gauss(4f)), range(4f, 9f), range(1.4f, 2.2f))
        }
    }

    private fun idleBeat(fade: Float): Layer {
        if (drowsy) {
            return if (rng.nextFloat() < 0.75f) {
                hold("drowsy", BodyPose(pitch = range(5f, 9f), yaw = gauss(4f), roll = gauss(4f)), range(4f, 9f), range(1.4f, 2.2f))
            } else clipBeat("Idle_B", 0.25f, 0.4f, range(1.2f, 1.8f), "idleB", speed = range(0.65f, 0.8f))
                ?: hold("drowsy", BodyPose(pitch = 7f), 5f, 1.6f)
        }
        val last = lastKind()
        val opts = ArrayList<Pair<String, Float>>()
        // Mood: low arousal holds still more, high arousal looks around more; a good
        // mood tilts the head now and then.
        val a = moodArousal.coerceIn(0f, 1f)
        val pos = moodValence.coerceAtLeast(0f)
        if (last != "hold") opts += "hold" to 0.40f * (1.5f - a)
        if (last != "idleB" || recentKinds.count { it == "idleB" } < 2) opts += "idleB" to 0.32f
        if (last != "look") opts += "look" to 0.17f * (0.5f + a)
        if (last != "tilt" && last != "hold") opts += "tilt" to 0.09f * (0.7f + 0.6f * a + pos)
        return when (weighted(opts)) {
            "idleB" -> clipBeat("Idle_B", 0.55f, 1f, fade, "idleB")
            "look" -> {
                val clip = clipLookup("Idle_A")
                if (clip != null) {
                    // Look-around part only (the authored ending is a full spin; spins are
                    // a rare gesture now, not every time Idle_A comes round).
                    val end = range(3.0f, 3.85f)
                    val start = range(0.2f, 0.45f)
                    Layer(ClipSource(clip, range(0.5f, 0.8f), rng.nextBoolean(), end), start, end,
                        range(0.85f, 1.05f), fade, "look")
                } else null
            }
            "tilt" -> {
                val side = if (rng.nextBoolean()) 1f else -1f
                hold("tilt", BodyPose(pitch = range(0f, 3f), yaw = side * range(3f, 7f), roll = side * range(5f, 9f)),
                    range(1.4f, 3.2f), fade, kind = "tilt")
            }
            else -> null
        } ?: hold("settle", BodyPose(pitch = range(0f, 3f), yaw = gauss(3.5f).coerceIn(-7f, 7f), roll = gauss(2.5f).coerceIn(-5f, 5f)),
            range(1.8f, 5.5f) * (1.3f - 0.6f * moodArousal.coerceIn(0f, 1f)), fade)
    }

    // ------------------------------------------------------------ busy repertoire
    //
    // No authored Working/Streaming clips any more: their slow side-to-side sway was
    // the one thing that read as a loop. A busy phase is a stream of small, distinct
    // beats picked by weighted chance with per-kind cooldowns, each with its own
    // random size, side, timing and fade, so nothing lines up into a rhythm.

    /** A look the body beat would like the eyes to make (consumed by the motion state). */
    data class GazeCue(val x: Float, val y: Float, val holdSec: Float, val kind: String = "")

    /** Set when a beat starts that wants the eyes somewhere; cleared on read. */
    var gazeCue: GazeCue? = null
        private set

    fun takeGazeCue(): GazeCue? = gazeCue.also { gazeCue = null }

    private val lastUsed = HashMap<String, Float>()
    private var clock = 0f
    /** Turn side we are currently on (beats prefer to go somewhere new). */
    private var lastSide = 0f

    private fun ago(kind: String) = clock - (lastUsed[kind] ?: -1000f)

    private fun busyBeat(life: MarkLifecycle, fade: Float): Layer {
        val busyFor = lifeSec
        val last = lastKind()
        // Base weights per phase; cooldowns scale them down after use.
        val base: List<Pair<String, Float>> = when (life) {
            MarkLifecycle.Thinking -> listOf(
                "turn" to 0.22f, "lookAround" to 0.2f, "ponder" to 0.2f, "pause" to 0.16f,
                "tilt" to 0.12f, "nod" to 0.05f, "pill" to 0.05f,
            )
            MarkLifecycle.Streaming -> listOf(
                "read" to 0.28f, "turn" to 0.16f, "pause" to 0.18f, "nod" to 0.1f,
                "tilt" to 0.1f, "lookAround" to 0.12f, "bob" to 0.06f,
            )
            else -> listOf(
                "turn" to 0.24f, "lookAround" to 0.18f, "pause" to 0.13f, "ponder" to 0.1f,
                "tilt" to 0.1f, "nod" to 0.07f, "pill" to 0.1f, "bob" to 0.06f, "spin" to 0.02f,
            )
        }
        val cool = mapOf(
            "turn" to 3f, "lookAround" to 7f, "ponder" to 9f, "pause" to 2f, "tilt" to 6f,
            "nod" to 8f, "pill" to 14f, "read" to 3f, "bob" to 12f, "spin" to 45f,
        )
        val opts = base.mapNotNull { (k, w) ->
            if (k == last && k != "pause") return@mapNotNull null
            if (k == "spin" && (busyFor < 6f || ago("spin") < 45f)) return@mapNotNull null
            // The activity pill only gets attention once the work has run a while.
            if (k == "pill" && busyFor < 5f) return@mapNotNull null
            val c = cool[k] ?: 3f
            val a = ago(k)
            val f = if (a >= c) 1f else (a / c).coerceIn(0.05f, 1f)
            // Two pauses in a row are fine now and then, three never.
            val extra = if (k == "pause" && recentKinds.takeLast(2).all { it == "pause" }) 0f else 1f
            (k to w * f * f * extra).takeIf { it.second > 0f }
        }.ifEmpty { listOf("pause" to 1f) }
        val kind = weighted(opts)
        lastUsed[kind] = clock
        val side = pickSide()
        val layer = when (kind) {
            "turn" -> {
                // A real turn: ease to an angle, settle there a moment.
                val yaw = side * range(12f, 30f)
                val p = BodyPose(pitch = range(-1f, 4f), yaw = yaw, roll = -side * range(0f, 4f))
                gazeCue = GazeCue(side * range(0.3f, 0.7f), range(-0.05f, 0.25f), range(0.6f, 1.6f), "turn")
                hold("turn", p, logRange(0.9f, 3.2f), range(0.55f, 1.1f), kind = "turn")
            }
            "lookAround" -> keyframed("lookAround", fade) {
                // 2–4 stops at irregular places and irregular times.
                val stops = 2 + rng.nextInt(3)
                var s = side
                List(stops) {
                    s = if (rng.nextFloat() < 0.7f) -s else s
                    BodyPose(pitch = range(-1f, 6f), yaw = s * range(5f, 24f), roll = gauss(2.5f)) to logRange(0.5f, 1.7f)
                }
            }
            "ponder" -> {
                // Thoughtful: head a little up and to the left (never up-right), eyes away.
                val p = BodyPose(pitch = -range(1f, 4f), yaw = -range(6f, 16f), roll = range(3f, 8f))
                gazeCue = GazeCue(-range(0.35f, 0.65f), -range(0.05f, 0.25f), range(1f, 2.2f), "ponder")
                hold("ponder", p, logRange(1.4f, 3.4f), range(0.8f, 1.3f), kind = "ponder")
            }
            "tilt" -> hold("tiltB", BodyPose(pitch = range(0f, 3f), yaw = side * range(2f, 8f), roll = side * range(5f, 10f)),
                logRange(1f, 2.6f), range(0.6f, 1f), kind = "tilt")
            "pill" -> {
                // Glance down-right at the activity pill / timeline, as if checking on it.
                val p = BodyPose(pitch = range(6f, 11f), yaw = range(4f, 12f), roll = -range(0f, 3f))
                gazeCue = GazeCue(range(0.15f, 0.45f), range(0.45f, 0.7f), range(1.2f, 2.2f), "pill")
                hold("pill", p, logRange(1.2f, 2.6f), range(0.6f, 0.95f), kind = "pill")
            }
            "read" -> {
                // Following the text as it arrives: down, drifting along a line or two.
                val y0 = range(6f, 10f)
                gazeCue = GazeCue(range(-0.1f, 0.35f), range(0.35f, 0.55f), range(1f, 2f), "read")
                keyframed("read", range(0.5f, 0.8f)) {
                    var x = range(-6f, -1f)
                    List(1 + rng.nextInt(3)) {
                        x += range(3f, 9f)
                        BodyPose(pitch = y0 + gauss(1f), yaw = x, roll = gauss(1.5f)) to logRange(0.6f, 1.6f)
                    }
                }
            }
            "nod" -> {
                val d = range(0.6f, 0.95f)
                val deep = range(4f, 8f)
                val twice = rng.nextFloat() < 0.35f
                Layer(ProceduralSource("nodB", d * if (twice) 1.6f else 1f) { u ->
                    val k = if (twice) sin(PI.toFloat() * 2f * u).let { it * it } * (1f - 0.4f * u) else sin(PI.toFloat() * u).let { it * it }
                    BodyPose(pitch = deep * k, yaw = side * 2f)
                }, 0f, d * if (twice) 1.6f else 1f, 1f, 0.25f, "nod")
            }
            "bob" -> {
                // A small "got it" bounce (squash, not a size change).
                val d = range(0.55f, 0.8f)
                Layer(ProceduralSource("bob", d) { u ->
                    val k = sin(PI.toFloat() * u)
                    BodyPose(posY = -0.03f * k, squashX = 1f - 0.03f * k, squashY = 1f + 0.03f * k, pitch = -2f * k)
                }, 0f, d, 1f, 0.2f, "bob")
            }
            "spin" -> {
                val d = range(1.4f, 1.9f)
                Layer(ProceduralSource("spinB", d) { u ->
                    val e = easeInOutCubic(u)
                    BodyPose(pitch = sin(PI.toFloat() * u) * 3f, yaw = side * 360f * e, roll = -side * sin(PI.toFloat() * u) * 4f)
                }, 0f, d, 1f, 0.35f, "spin")
            }
            else -> hold("pause", BodyPose(pitch = range(0f, 4f), yaw = gauss(3f).coerceIn(-6f, 6f), roll = gauss(2f).coerceIn(-4f, 4f)),
                logRange(0.6f, 2.8f), range(0.7f, 1.3f), kind = "pause")
        }
        return layer
    }

    /** Prefer the other side, but not always (a run of same-side beats is natural too). */
    private fun pickSide(): Float {
        val s = if (lastSide == 0f) (if (rng.nextBoolean()) 1f else -1f)
        else if (rng.nextFloat() < 0.68f) -lastSide else lastSide
        lastSide = s
        return s
    }

    /** Log-uniform: short values common, long ones possible. */
    private fun logRange(a: Float, b: Float): Float =
        (kotlin.math.ln(a) + (kotlin.math.ln(b) - kotlin.math.ln(a)) * rng.nextFloat()).let { kotlin.math.exp(it) }

    /**
     * Stops reached with eased moves and held; total length is the sum. Moves take a
     * random 35–55 % of each segment so the pace keeps changing.
     */
    private fun keyframed(name: String, fade: Float, build: () -> List<Pair<BodyPose, Float>>): Layer {
        val stops = build()
        val starts = FloatArray(stops.size)
        val moveFrac = FloatArray(stops.size) { range(0.35f, 0.55f) }
        var acc = 0f
        for (i in stops.indices) { starts[i] = acc; acc += stops[i].second }
        val total = acc
        val src = CalmProceduralSource(name, total) { u ->
            val t = u * total
            val i = (0 until stops.size).lastOrNull { starts[it] <= t } ?: 0
            val from = if (i == 0) stops[0].first else stops[i - 1].first
            val seg = stops[i].second
            val k = ((t - starts[i]) / (seg * moveFrac[i])).coerceIn(0f, 1f)
            if (i == 0) stops[0].first else from.lerpTo(stops[i].first, smootherstep(k))
        }
        return Layer(src, 0f, total, 1f, fade, name)
    }

    private fun clipBeat(
        name: String,
        ampMin: Float,
        ampMax: Float,
        fade: Float,
        kind: String,
        speed: Float = range(0.82f, 1.12f),
    ): Layer? {
        val clip = clipLookup(name) ?: return null
        val dur = clip.duration
        val probe = ClipSource(clip, 1f, false, dur)
        // Start at the top or at a calm point in the first third; play to the end
        // (or stop a little early — the crossfade carries the motion out).
        val starts = probe.calmStarts(dur * 0.35f)
        val start = if (starts.isNotEmpty() && rng.nextFloat() < 0.5f) starts[rng.nextInt(starts.size)] else 0f
        val end = if (rng.nextFloat() < 0.35f) max(start + dur * 0.5f, dur * range(0.82f, 0.95f)) else dur
        return Layer(ClipSource(clip, range(ampMin, ampMax), rng.nextBoolean(), end), start, end, speed, fade, kind)
    }

    private fun hold(name: String, p: BodyPose, dur: Float, fade: Float, kind: String = "hold"): Layer =
        Layer(HoldSource(name, p, dur), 0f, dur, 1f, fade, kind)

    private fun lastKind(): String? = recentKinds.lastOrNull()

    private fun weighted(opts: List<Pair<String, Float>>): String {
        val total = opts.sumOf { it.second.toDouble() }.toFloat()
        var r = rng.nextFloat() * total
        for ((k, w) in opts) {
            r -= w
            if (r <= 0f) return k
        }
        return opts.last().first
    }

    private fun range(a: Float, b: Float) = a + (b - a) * rng.nextFloat()
    private fun gauss(sd: Float): Float {
        // Irwin–Hall approximation — no java.util.Random dependency for tests.
        var s = 0f
        repeat(4) { s += rng.nextFloat() }
        return (s - 2f) * sd * 1.73f
    }

    // ----------------------------------------------------------------- filter

    private fun filter(p: BodyPose, dt: Float): BodyPose {
        val raw = floatArrayOf(p.pitch, p.yaw, p.roll)
        if (!filterReady) {
            for (a in 0..2) {
                rawUnwrapped[a] = raw[a]; lastRaw[a] = raw[a]
            }
        } else {
            for (a in 0..2) {
                rawUnwrapped[a] += MarkClipEngine.wrap180(raw[a] - lastRaw[a])
                lastRaw[a] = raw[a]
            }
        }
        val target = floatArrayOf(
            rawUnwrapped[0], rawUnwrapped[1], rawUnwrapped[2],
            p.posY, p.squashX, p.squashY, p.scale, p.lidOpen,
        )
        if (!filterReady) {
            for (i in target.indices) {
                fx[i] = target[i]; fv[i] = 0f
            }
            filterReady = true
        } else {
            var rem = dt
            while (rem > 1e-6f) {
                val h = min(rem, 1f / 120f)
                for (i in target.indices) {
                    val w = if (i == 7) 30f else OMEGA
                    val a = w * w * (target[i] - fx[i]) - 2f * w * fv[i]
                    fv[i] += a * h
                    // Desktop whip-spins (Working_A/F flips, Working_B turn) peak at
                    // 60–80°/frame; cap turn speed so they read as a quick spin.
                    if (i < 3) fv[i] = fv[i].coerceIn(-MAX_TURN_DEG_S, MAX_TURN_DEG_S)
                    fx[i] += fv[i] * h
                }
                rem -= h
            }
        }
        // Keep the unwrapped accumulators bounded (spins add ±360 per turn).
        for (a in 0..2) {
            if (abs(fx[a]) > 720f) {
                val k = 360f * kotlin.math.round(fx[a] / 360f)
                fx[a] -= k; rawUnwrapped[a] -= k
            }
        }
        return BodyPose(
            pitch = MarkClipEngine.wrap180(fx[0]),
            yaw = MarkClipEngine.wrap180(fx[1]),
            roll = MarkClipEngine.wrap180(fx[2]),
            posY = fx[3],
            squashX = fx[4],
            squashY = fx[5],
            // Size never jumps: clamp the rest-relative scale to a narrow band.
            scale = fx[6].coerceIn(0.95f, 1.04f),
            lidOpen = fx[7].coerceIn(0f, 1f),
        )
    }

    companion object {
        /** Spring stiffness for the output filter (rad/s): ~60 ms settle, no lag you can see. */
        const val OMEGA = 16f
        /** Fastest the body may turn (deg/s) — a full spin still takes ≥ 0.5 s. */
        const val MAX_TURN_DEG_S = 700f
        /** A busy phase lasts at least this long, even if the reply came back instantly. */
        const val MIN_BUSY_SEC = 1.3f
        /** Longest we wait for a busy clip to reach a calm pose before crossfading anyway. */
        const val MAX_FINISH_WAIT = 2.6f

        fun isBusy(l: MarkLifecycle) =
            l == MarkLifecycle.Thinking || l == MarkLifecycle.Working || l == MarkLifecycle.Streaming

        fun smootherstep(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * t * (t * (t * 6f - 15f) + 10f)
        }

        fun easeInOutCubic(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
        }

        /**
         * Upward pitch (negative) is the Grok brand cue, worst when combined with a
         * right turn. Soften continuously so a pose never snaps.
         */
        fun softenUp(pitch: Float, yaw: Float): Float {
            if (pitch >= 0f) return pitch
            val right = ((yaw - 6f) / 20f).coerceIn(0f, 1f)
            val k = 0.45f * (1f - 0.5f * right * right * (3f - 2f * right))
            return pitch * k
        }
    }
}
