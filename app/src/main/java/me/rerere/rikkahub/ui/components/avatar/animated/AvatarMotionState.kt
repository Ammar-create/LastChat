package me.rerere.rikkahub.ui.components.avatar.animated

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import androidx.compose.ui.geometry.Offset
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

val LocalAvatarMotionHints = compositionLocalOf { AvatarMotionHints() }

/**
 * Whether animated avatars below should move. Chat sets it to false for every message
 * except the newest one (per role), so older avatars rest as still marks.
 */
val LocalAvatarAnimate = compositionLocalOf { true }

/**
 * Everything the renderer needs for one frame. Gaze is in screen convention
 * (x: + right, y: + down, both -1..1); lead is the eyes' offset on the body surface
 * (normalized face units, y down) so eyes move a beat before the head does.
 */
data class MotionFrame(
    val body: BodyPose = BodyPose.REST,
    val headLookX: Float = 0f,
    val headLookY: Float = 0f,
    val leadX: Float = 0f,
    val leadY: Float = 0f,
    /** Final eye openness incl. blink / drowsiness (0 shut … 1 open). */
    val lid: Float = 1f,
    val exprFrom: String = "Neutral",
    val exprTo: String = "Neutral",
    /** Eased 0..1 morph progress from [exprFrom] to [exprTo]. */
    val exprT: Float = 1f,
    val lifecycle: MarkLifecycle = MarkLifecycle.Idle,
    val eyeGap: Float = 0.94f,
    val eyeScale: Float = 1f,
    val timeSec: Float = 0f,
)

/**
 * Gaze: eyes saccade to a fixation (fast spring), the head follows more slowly.
 * Never parks up-right (Grok brand look).
 */
internal class GazeDirector(private val rng: Random) {
    private var tx = 0f
    private var ty = 0f
    var eyeX = 0f; private set
    var eyeY = 0f; private set
    private var evx = 0f
    private var evy = 0f
    var headX = 0f; private set
    var headY = 0f; private set
    private var hvx = 0f
    private var hvy = 0f
    private var holdLeft = 0f
    private var focusLeft = 0f
    private var focusPriority = 0
    private var returnToCenter = false
    private var microLeft = 1f
    private var mx = 0f
    private var my = 0f

    /** Set by the director when a big gaze jump happens (blink sometimes rides it). */
    var bigShift = false; private set

    fun focus(gx: Float, gy: Float, holdSec: Float, priority: Int) {
        if (priority < focusPriority && focusLeft > 0f) return
        focusPriority = priority
        focusLeft = holdSec
        setTarget(gx, gy)
    }

    fun releaseFocus(awayX: Float? = null) {
        focusLeft = 0f
        focusPriority = 0
        if (awayX != null) {
            setTarget(awayX, rng.nextFloat() * 0.2f)
            holdLeft = 0.6f + rng.nextFloat() * 0.6f
            returnToCenter = true
        } else {
            holdLeft = 0f
        }
    }

    val focused: Boolean get() = focusLeft > 0f

    private fun setTarget(x: Float, y: Float) {
        val gx = x.coerceIn(-1f, 1f)
        var gy = y.coerceIn(-1f, 1f)
        // No up-right parking (brand). A real target up-right still gets a softer look.
        if (gx > 0.25f && gy < -0.1f) gy *= 0.4f
        if (hypot(gx - tx, gy - ty) > 0.55f) bigShift = true
        tx = gx; ty = gy
    }

    fun update(dt: Float, life: MarkLifecycle, drowsy: Boolean) {
        bigShift = false
        if (focusLeft > 0f) {
            focusLeft -= dt
            if (focusLeft <= 0f) {
                focusPriority = 0
                holdLeft = 0.2f
            }
        } else {
            holdLeft -= dt
            if (holdLeft <= 0f) chooseFixation(life, drowsy)
        }
        // Tiny eye-only drift while holding a fixation — barely visible life.
        microLeft -= dt
        if (microLeft <= 0f) {
            microLeft = 0.7f + rng.nextFloat() * 1.6f
            val amp = if (drowsy) 0.01f else 0.025f
            mx = (rng.nextFloat() - 0.5f) * 2f * amp
            my = (rng.nextFloat() - 0.5f) * 2f * amp * 0.7f
        }
        val eyeW = if (drowsy) 12f else 26f
        val headW = if (drowsy) 3.5f else 6.5f
        var rem = dt
        while (rem > 1e-6f) {
            val h = min(rem, 1f / 120f)
            val ax = eyeW * eyeW * (tx + mx - eyeX) - 2f * eyeW * evx
            val ay = eyeW * eyeW * (ty + my - eyeY) - 2f * eyeW * evy
            evx += ax * h; evy += ay * h
            eyeX += evx * h; eyeY += evy * h
            val bx = headW * headW * (tx * 0.82f - headX) - 2f * headW * hvx
            val by = headW * headW * (ty * 0.82f - headY) - 2f * headW * hvy
            hvx += bx * h; hvy += by * h
            headX += hvx * h; headY += hvy * h
            rem -= h
        }
    }

    private fun chooseFixation(life: MarkLifecycle, drowsy: Boolean) {
        val r = rng.nextFloat()
        fun rr(a: Float, b: Float) = a + (b - a) * rng.nextFloat()
        if (returnToCenter) {
            returnToCenter = false
            setTarget(rr(-0.08f, 0.08f), rr(-0.03f, 0.06f))
            holdLeft = rr(1.2f, 3.5f)
            return
        }
        if (drowsy) {
            setTarget(rr(-0.2f, 0.2f), rr(0.15f, 0.4f))
            holdLeft = rr(3f, 7f)
            return
        }
        when (life) {
            MarkLifecycle.Working -> {
                setTarget(rr(-0.12f, 0.12f), rr(0f, 0.15f)); holdLeft = rr(0.6f, 1.6f)
            }
            MarkLifecycle.Listening -> if (r < 0.7f) {
                setTarget(rr(-0.15f, 0.15f), rr(0.35f, 0.55f)); holdLeft = rr(1.2f, 3f)
            } else {
                setTarget(rr(-0.1f, 0.1f), rr(0f, 0.1f)); holdLeft = rr(0.8f, 1.8f)
            }
            MarkLifecycle.Thinking -> if (r < 0.45f) {
                // Thinking look-aside: sideways / slightly up on the left, level on the right.
                val x = if (rng.nextBoolean()) rr(0.3f, 0.6f) else -rr(0.3f, 0.6f)
                val y = if (x < 0f) rr(-0.28f, 0.05f) else rr(0f, 0.12f)
                setTarget(x, y); holdLeft = rr(0.9f, 2.2f)
            } else {
                setTarget(rr(-0.1f, 0.1f), rr(0f, 0.15f)); holdLeft = rr(0.8f, 2f)
            }
            MarkLifecycle.Streaming -> when {
                r < 0.5f -> { setTarget(rr(-0.08f, 0.08f), rr(0f, 0.08f)); holdLeft = rr(1.2f, 3f) }
                r < 0.8f -> { setTarget(rr(-0.25f, 0.25f), rr(0.25f, 0.45f)); holdLeft = rr(0.8f, 2f) }
                else -> { setTarget(if (rng.nextBoolean()) rr(0.35f, 0.6f) else -rr(0.35f, 0.6f), rr(0f, 0.15f)); holdLeft = rr(0.6f, 1.2f); returnToCenter = true }
            }
            MarkLifecycle.Failed -> {
                setTarget(rr(-0.2f, 0.2f), rr(0.3f, 0.5f)); holdLeft = rr(1.5f, 3f)
            }
            MarkLifecycle.Sleeping -> {
                setTarget(rr(-0.15f, 0.15f), rr(0.3f, 0.45f)); holdLeft = rr(4f, 8f)
            }
            else -> when {
                // Idle: look straight ahead a lot (doing nothing is fine) …
                r < 0.40f -> { setTarget(rr(-0.06f, 0.06f), rr(-0.03f, 0.05f)); holdLeft = rr(1.6f, 5.5f) }
                // … look somewhere around the screen …
                r < 0.70f -> {
                    val x = rr(-0.75f, 0.75f)
                    val y = rr(-0.18f, 0.35f)
                    setTarget(x, y); holdLeft = rr(0.8f, 2.6f)
                }
                // … glance down at the conversation …
                r < 0.85f -> { setTarget(rr(-0.3f, 0.3f), rr(0.3f, 0.5f)); holdLeft = rr(0.9f, 2.4f) }
                // … or a quick side glance and back.
                else -> {
                    setTarget(if (rng.nextBoolean()) rr(0.5f, 0.85f) else -rr(0.5f, 0.85f), rr(-0.05f, 0.2f))
                    holdLeft = rr(0.45f, 1.0f)
                    returnToCenter = true
                }
            }
        }
    }
}

/** Eased, never-interrupted expression morphs (queued while one is running). */
internal class ExpressionDirector(private val rng: Random) {
    var from = "Neutral"; private set
    var to = "Neutral"; private set
    private var t = 1f
    private var dur = 0.35f
    private var dwell = 10f
    private var pending: String? = null
    private var blinkAt = -1f
    val eased: Float get() = MotionDirector.easeInOutCubic(t)
    val morphing: Boolean get() = t < 1f

    fun want(raw: String) {
        val target = normalize(raw)
        if (target == to) {
            pending = null
            return
        }
        if (t < 1f || dwell < MIN_DWELL) {
            pending = target
            return
        }
        start(target)
    }

    private fun start(target: String) {
        from = to
        to = target
        t = 0f
        val big = group(from) != group(to)
        dur = (0.3f + rng.nextFloat() * 0.12f) * if (big) 1.25f else 1f
        blinkAt = if (big && rng.nextFloat() < 0.5f) dur * 0.3f else -1f
        pending = null
    }

    /** Returns true when a masking blink should start now. */
    fun update(dt: Float): Boolean {
        var blink = false
        if (t < 1f) {
            val before = t * dur
            t = min(1f, t + dt / dur)
            val after = t * dur
            if (blinkAt in before..after) {
                blink = true
                blinkAt = -1f
            }
            if (t >= 1f) dwell = 0f
        } else {
            dwell += dt
            val p = pending
            if (p != null && dwell >= MIN_DWELL) start(p)
        }
        return blink
    }

    companion object {
        const val MIN_DWELL = 0.3f

        fun normalize(raw: String): String =
            if (raw.equals("skeptical", true)) "skeptical" else EyeStyle.resolveGenericalExpressionId(raw)

        private fun group(e: String): Int = when (e) {
            "Happy", "Sleeping", "Eyes closed", "Bored", "hurt or cute" -> 1
            "confused", "skeptical", "Group 3" -> 2
            else -> 0
        }
    }
}

/**
 * Procedural blink, deliberately irregular: log-normal gaps (mostly 2–6 s, sometimes a
 * long stare), each blink with its own speed and depth (some don't close fully),
 * occasional double / rare triple blinks, fewer blinks while concentrating, and blinks
 * that ride gaze shifts, turns and expression changes. After any blink the next
 * spontaneous one is re-drawn, so requested and spontaneous blinks never stack into
 * a rhythm.
 */
internal class Blinker(private val rng: Random) {
    var closed = 0f; private set
    private var phase = -1f
    private var closeT = 0.07f
    private var holdT = 0.03f
    private var openT = 0.12f
    private var depth = 1f
    private var next = 0.8f + rng.nextFloat() * 3f
    private var repeats = 0
    private var gapLeft = -1f
    private var sinceLast = 10f

    fun request(double: Boolean = false, minGap: Float = 1.4f) {
        if (phase >= 0f || gapLeft >= 0f || sinceLast < minGap) return
        begin(if (double) 1 else 0, drowsy = false)
    }

    private fun begin(extra: Int, drowsy: Boolean) {
        phase = 0f
        repeats = extra
        val speed = 0.8f + rng.nextFloat() * 0.5f // per-blink tempo
        closeT = if (drowsy) 0.15f + rng.nextFloat() * 0.08f else (0.05f + rng.nextFloat() * 0.03f) * speed
        holdT = if (drowsy) 0.1f + rng.nextFloat() * 0.12f else (0.01f + rng.nextFloat() * 0.05f) * speed
        openT = if (drowsy) 0.25f + rng.nextFloat() * 0.12f else (0.1f + rng.nextFloat() * 0.07f) * speed
        // ~1 in 8 blinks is a lazy half-blink.
        depth = if (!drowsy && rng.nextFloat() < 0.12f) 0.65f + rng.nextFloat() * 0.25f else 1f
    }

    fun update(dt: Float, life: MarkLifecycle, drowsy: Boolean) {
        sinceLast += dt
        if (phase < 0f) {
            if (gapLeft >= 0f) {
                gapLeft -= dt
                if (gapLeft < 0f) begin(repeats, drowsy)
            } else {
                next -= dt
                if (next <= 0f && life != MarkLifecycle.Sleeping) {
                    val r = rng.nextFloat()
                    val extra = when {
                        drowsy -> 0
                        r < 0.015f -> 2
                        r < 0.10f -> 1
                        else -> 0
                    }
                    begin(extra, drowsy = drowsy)
                }
            }
        }
        if (phase >= 0f) {
            phase += dt
            val total = closeT + holdT + openT
            closed = depth * when {
                phase < closeT -> (phase / closeT).let { it * it }
                phase < closeT + holdT -> 1f
                phase < total -> ((total - phase) / openT).let { it * (2f - it) }
                else -> 0f
            }
            if (phase >= total) {
                phase = -1f
                closed = 0f
                sinceLast = 0f
                if (repeats > 0) {
                    repeats--
                    gapLeft = 0.07f + rng.nextFloat() * 0.12f
                } else {
                    next = nextInterval(life, drowsy)
                }
            }
        }
    }

    private fun nextInterval(life: MarkLifecycle, drowsy: Boolean): Float {
        // Log-normal, median ~3.4 s; Box–Muller from two uniforms (no java.util.Random).
        val u1 = rng.nextFloat().coerceAtLeast(1e-4f)
        val u2 = rng.nextFloat()
        val n = kotlin.math.sqrt(-2f * kotlin.math.ln(u1)) * kotlin.math.cos(2f * PI_F * u2)
        var base = 3.4f * kotlin.math.exp(0.42f * n)
        if (rng.nextFloat() < 0.08f) base += 3f + rng.nextFloat() * 5f // a long stare now and then
        base = base.coerceIn(1.1f, 13f)
        return when {
            drowsy -> base * 1.7f
            // Concentrating: blinks get rarer (they come at gaze shifts instead).
            life == MarkLifecycle.Working || life == MarkLifecycle.Thinking -> base * (1.25f + rng.nextFloat() * 0.3f)
            life == MarkLifecycle.Streaming -> base * 1.1f
            else -> base
        }
    }

    private companion object {
        const val PI_F = 3.1415927f
    }
}

/**
 * Mark motion: [MotionDirector] (body), gaze, expressions, blinks, plus a small
 * "alive" layer — rare social glances between on-screen avatars, glances at app
 * events, poke reactions, a rare spin, drowsiness after a long quiet spell and a
 * little happy beat when a reply finishes. All transitions are continuous.
 */
@Stable
class AvatarMotionState(private val rng: Random = Random.Default) {
    var frame by mutableStateOf(MotionFrame())
        private set

    /**
     * False = shown static (e.g. an older chat message): the mark eases to its rest
     * pose and neutral face, then the frame loop stops. True again = it eases back
     * into motion from that rest pose. Never a cut either way.
     */
    var animate by mutableStateOf(true)
        internal set

    /** 0 = fully live … 1 = fully at rest. */
    private var settle = 0f

    /** At rest, neutral, nothing in flight: safe to stop ticking. */
    internal val isSettled: Boolean
        get() = !animate && settle >= 1f && expr.to == "Neutral" && !expr.morphing

    /** Start already at rest (an avatar that first appears as a static one). */
    internal fun startSettled() {
        animate = false
        settle = 1f
    }

    val lifecycle: MarkLifecycle get() = director.lifecycle

    internal val director = MotionDirector(rng)
    private val gaze = GazeDirector(rng)
    private val expr = ExpressionDirector(rng)
    private val blink = Blinker(rng)

    // Smoothed pose knobs (never switch instantly with lifecycle).
    private var lidKnob = 1f
    private var gapKnob = 0.94f
    private var scaleKnob = 1f
    private var drowsyLid = 1f
    private var time = 0f

    // Stage binding (set by the composable).
    internal var stage: AvatarStage? = null
    internal var stageId: Long = 0L
    private var lastEventSeq = -1L

    // Alive-layer state.
    private var prevHints: AvatarMotionHints? = null
    private var quietSec = 0f
    private var drowsyAfter = 100f + rng.nextFloat() * 60f
    private var drowsy = false
    private var droopIn = 12f
    private var perkIn = 0f
    private var spinIn = 30f + rng.nextFloat() * 30f
    private var lastSpinAgo = 999f
    private var socialIn = 8f + rng.nextFloat() * 12f
    private var eventCooldown = 0f
    private var typingCooldown = 0f
    private var moodIn = 20f + rng.nextFloat() * 30f
    private var reaction: String? = null
    private var reactionLeft = 0f
    private var reactionQueue: Pair<String, Float>? = null
    private var reactionQueueIn = 0f
    private var pokeCount = 0
    private var pokeWindow = 0f
    private var pendingPokes = 0
    private var followLeft = 0f
    private var followCheckIn = 6f
    private var curiousDone = false
    private var attendedTypingAt = -100f

    private class Social(
        val other: Long,
        val lookAt: Float,
        val skepticAt: Float,
        val end: Float,
        var t: Float = 0f,
        var looking: Boolean = false,
        var skepticDone: Boolean = false,
    )
    private var social: Social? = null

    /** Called from the pointer handler (main thread). */
    fun poke() {
        if (animate) pendingPokes++
    }

    internal fun tick(dtIn: Float, rawHints: AvatarMotionHints, nowMs: Long) {
        val dt = dtIn.coerceIn(0f, 0.1f)
        time += dt
        val live = animate
        settle = if (live) max(0f, settle - dt / SETTLE_IN_SEC) else min(1f, settle + dt / SETTLE_OUT_SEC)
        // A static avatar winds down as if nothing were going on.
        val hints = if (live) rawHints else QUIET_HINTS
        val desired = MarkLifecycleMapper.resolve(hints)
        director.setDesired(desired)

        // ---- stimulus / drowsiness ----
        val prev = prevHints
        var stimulus = prev != null && prev != hints
        if (live && prev != null && hints.isTyping && !prev.isTyping) onTypingStarted()
        prevHints = hints
        stimulus = processStageEvents(live) || stimulus
        if (live) typingUpdate(dt, hints, nowMs)
        if (pendingPokes > 0) {
            if (live) {
                stimulus = true
                repeat(pendingPokes) { onPoke() }
            }
            pendingPokes = 0
        }
        val calmLife = director.lifecycle == MarkLifecycle.Idle && desired == MarkLifecycle.Idle
        if (stimulus || !calmLife) {
            if (drowsy) wake()
            quietSec = 0f
        } else {
            quietSec += dt
        }
        if (!drowsy && calmLife && quietSec > drowsyAfter) {
            drowsy = true
            droopIn = 6f + rng.nextFloat() * 6f
        }
        director.drowsy = drowsy

        if (live) {
            aliveUpdate(dt, nowMs)
        } else {
            reaction = null
            reactionQueue = null
            followLeft = 0f
            if (social != null) {
                social = null
                gaze.releaseFocus()
            }
        }

        // ---- body ----
        val body = director.update(dt)
        if (director.clipBlink) blink.request()
        director.takeGazeCue()?.let { c ->
            // A busy beat asked to look somewhere (turn, the pill, the text, away in thought).
            if (live && social == null && followLeft <= 0f && !gaze.focused) {
                gaze.focus(c.x, c.y, c.holdSec, priority = 1)
            }
        }

        // ---- gaze ----
        gaze.update(dt, director.lifecycle, drowsy)
        // Big eye jumps often carry a blink (people blink as they look somewhere new).
        if (gaze.bigShift && rng.nextFloat() < 0.4f) blink.request(minGap = 0.9f)

        // ---- expression ----
        expr.want(currentExpressionTarget())
        if (expr.update(dt)) blink.request(minGap = 0.2f)

        // ---- blink + lids ----
        blink.update(dt, director.lifecycle, drowsy)
        val pose = MarkLifecycleMapper.pose(director.lifecycle)
        val k = 1f - exp(-dt / 0.35f)
        lidKnob += (pose.lid - lidKnob) * k
        gapKnob += (pose.eyeGap - gapKnob) * k
        scaleKnob += (pose.eyeScale - scaleKnob) * k
        val drowsyTarget = if (drowsy) 0.55f else 1f
        drowsyLid += (drowsyTarget - drowsyLid) * (1f - exp(-dt / if (drowsy) 2.5f else 0.3f))
        val lid = (lidKnob.coerceAtMost(1f) * body.lidOpen * drowsyLid * (1f - blink.closed)).coerceIn(0f, 1f)

        stage?.setCalm(stageId, calmLife && social == null && reaction == null && !drowsy)

        // Blend toward the static rest pose (weight eased so it starts and lands softly).
        val w = MotionDirector.easeInOutCubic(settle)
        val keep = 1f - w
        frame = MotionFrame(
            body = body.lerpTo(BodyPose.REST, w),
            headLookX = gaze.headX * keep,
            headLookY = gaze.headY * keep,
            leadX = ((gaze.eyeX - gaze.headX) * 0.07f + gaze.eyeX * 0.03f) * keep,
            leadY = ((gaze.eyeY - gaze.headY) * 0.06f + gaze.eyeY * 0.025f) * keep,
            lid = lid + (1f - lid) * w,
            exprFrom = expr.from,
            exprTo = expr.to,
            exprT = expr.eased,
            lifecycle = director.lifecycle,
            eyeGap = gapKnob + (REST_FRAME.eyeGap - gapKnob) * w,
            eyeScale = scaleKnob + (REST_FRAME.eyeScale - scaleKnob) * w,
            timeSec = time,
        )
    }

    // ----------------------------------------------------------- alive layer

    private fun currentExpressionTarget(): String {
        if (!animate) return "Neutral"
        reaction?.let { return it }
        val life = director.lifecycle
        if (drowsy) return "Neutral"
        val clipExpr = director.clipExpression
        if (clipExpr != null && life != MarkLifecycle.Idle && life != MarkLifecycle.Listening) {
            mapClipExpression(clipExpr)?.let { return it }
        }
        return MarkLifecycleMapper.defaultExpression(life)
    }

    private fun setReaction(e: String, sec: Float) {
        reaction = e
        reactionLeft = sec
    }

    private fun aliveUpdate(dt: Float, nowMs: Long) {
        lastSpinAgo += dt
        eventCooldown -= dt
        typingCooldown -= dt
        pokeWindow -= dt
        if (pokeWindow <= 0f) pokeCount = 0

        if (perkIn > 0f) {
            perkIn -= dt
            // Caught yourself nodding off: a quick double blink.
            if (perkIn <= 0f) blink.request(double = true, minGap = 0.2f)
        }
        if (reaction != null) {
            reactionLeft -= dt
            if (reactionLeft <= 0f) reaction = null
        }
        reactionQueue?.let { (e, sec) ->
            reactionQueueIn -= dt
            if (reactionQueueIn <= 0f) {
                setReaction(e, sec)
                reactionQueue = null
            }
        }

        val calm = director.lifecycle == MarkLifecycle.Idle

        // Reply finished → a little happy beat (not every time).
        if (director.finishedBusy) {
            director.finishedBusy = false
            val r = rng.nextFloat()
            if (r < 0.6f) {
                setReaction("Happy", 1.6f + rng.nextFloat())
                val g = rng.nextFloat()
                when {
                    g < 0.35f -> director.gesture(MotionDirector.Gesture.Hop)
                    g < 0.6f -> director.gesture(MotionDirector.Gesture.Nod)
                }
            } else {
                blink.request()
            }
        }

        // Social glances.
        social?.let { s -> runSocial(s, dt) }
        val st = stage
        if (st != null && social == null) {
            st.takeGlanceFor(stageId)?.let { g ->
                if (calm && !drowsy && reaction == null) {
                    social = Social(
                        other = g.from,
                        lookAt = 0.3f + rng.nextFloat() * 0.4f,
                        skepticAt = if (g.skeptic == stageId) 1.0f + rng.nextFloat() * 0.3f else -1f,
                        end = 2.0f + rng.nextFloat() * 0.8f,
                    )
                }
            }
            socialIn -= dt
            if (social == null && socialIn <= 0f) {
                socialIn = 8f + rng.nextFloat() * 12f
                if (calm && !drowsy && reaction == null && rng.nextFloat() < 0.2f) {
                    val me = st.entry(stageId)
                    val maxD = me?.let { max(it.windowW, it.windowH) * 0.7f } ?: 0f
                    st.tryStartGlance(stageId, maxD, nowMs)?.let { g ->
                        social = Social(
                            other = g.to,
                            lookAt = 0f,
                            skepticAt = if (g.skeptic == stageId) 1.1f + rng.nextFloat() * 0.3f else -1f,
                            end = 2.2f + rng.nextFloat() * 0.8f,
                        )
                    }
                }
            }
        }

        if (!calm) return

        // Rare spin.
        spinIn -= dt
        if (spinIn <= 0f) {
            spinIn = 25f + rng.nextFloat() * 25f
            if (!drowsy && social == null && reaction == null && lastSpinAgo > 150f && rng.nextFloat() < 0.1f) {
                if (director.gesture(MotionDirector.Gesture.Spin)) lastSpinAgo = 0f
            }
        }

        // Drowsy: now and then nod off a little, then catch yourself.
        if (drowsy) {
            droopIn -= dt
            if (droopIn <= 0f) {
                droopIn = 9f + rng.nextFloat() * 9f
                if (rng.nextFloat() < 0.45f && director.gesture(MotionDirector.Gesture.Droop)) {
                    perkIn = 2.4f + rng.nextFloat() * 0.8f
                }
            }
        }

        // Occasional quiet mood (rare; mostly the face just rests).
        moodIn -= dt
        if (moodIn <= 0f) {
            moodIn = 18f + rng.nextFloat() * 35f
            if (!drowsy && reaction == null && social == null) {
                val r = rng.nextFloat()
                when {
                    r < 0.22f -> setReaction("Happy", 2f + rng.nextFloat() * 2f)
                    r < 0.32f && quietSec > 45f -> setReaction("Bored", 3f + rng.nextFloat() * 3f)
                }
            }
        }
    }

    private fun runSocial(s: Social, dt: Float) {
        s.t += dt
        val st = stage
        val other = st?.entry(s.other)
        if (other == null || director.lifecycle != MarkLifecycle.Idle) {
            social = null
            gaze.releaseFocus()
            return
        }
        if (!s.looking && s.t >= s.lookAt) {
            s.looking = true
            director.gesture(MotionDirector.Gesture.Attend, s.end - s.t + 0.3f)
            val (gx, gy) = lookAt(other.center)
            gaze.focus(gx, gy, s.end - s.t + 0.2f, priority = 3)
        }
        if (s.looking) {
            // Keep tracking if either avatar moves (scrolling).
            val (gx, gy) = lookAt(other.center)
            gaze.focus(gx, gy, max(0.05f, s.end - s.t), priority = 3)
        }
        if (s.skepticAt > 0f && !s.skepticDone && s.t >= s.skepticAt) {
            s.skepticDone = true
            setReaction("skeptical", 1.1f + rng.nextFloat() * 0.5f)
            if (rng.nextFloat() < 0.7f) director.gesture(MotionDirector.Gesture.Tilt)
        }
        if (s.t >= s.end) {
            social = null
            val (gx, _) = lookAt(other.center)
            gaze.releaseFocus(awayX = if (gx > 0f) -(0.3f + rng.nextFloat() * 0.3f) else 0.3f + rng.nextFloat() * 0.3f)
            if (rng.nextFloat() < 0.5f) blink.request()
        }
    }

    private fun lookAt(target: Offset): Pair<Float, Float> {
        val me = stage?.entry(stageId) ?: return 0f to 0f
        val dx = target.x - me.center.x
        val dy = target.y - me.center.y
        val d = max(hypot(dx, dy), me.radius * 2.2f).coerceAtLeast(1f)
        return (dx / d * 0.85f) to (dy / d * 0.6f)
    }

    private fun windowBottom(): Offset? {
        val me = stage?.entry(stageId) ?: return null
        if (me.windowH <= 0f) return null
        return Offset(me.windowW * 0.5f, me.windowH * 0.93f)
    }

    // ------------------------------------------------------- composer (typing)

    /** Where the caret roughly is: the bottom input, left → right as the line grows. */
    private fun caretPoint(): Offset? {
        val st = stage ?: return null
        val me = st.entry(stageId) ?: return null
        if (me.windowW <= 0f || me.windowH <= 0f) return null
        val perLine = CARET_CHARS_PER_LINE
        val frac = (st.composerLineLen % perLine) / perLine.toFloat()
        return Offset(me.windowW * (0.12f + 0.76f * frac), me.windowH * 0.93f)
    }

    private fun startFollow(sec: Float) {
        if (social != null || caretPoint() == null) return
        followLeft = sec
        attendedTypingAt = time
    }

    private fun onTypingStarted() {
        if (typingCooldown > 0f) return
        val r = rng.nextFloat()
        when {
            // Now and then: read along while you type.
            r < 0.14f -> {
                typingCooldown = 8f
                startFollow(2.5f + rng.nextFloat() * 2.5f)
            }
            // Often just a quick look down at the input.
            r < 0.5f -> {
                typingCooldown = 6f
                val at = windowBottom() ?: return
                val (gx, gy) = lookAt(at)
                gaze.focus(gx, gy, 1.0f + rng.nextFloat() * 0.8f, priority = 2)
                attendedTypingAt = time
            }
            // Otherwise, nothing at all.
        }
    }

    /**
     * Rare, quiet reactions to the composer: following the caret for a few seconds,
     * one small curious tilt when a message gets long. Never more than one at once.
     */
    private fun typingUpdate(dt: Float, hints: AvatarMotionHints, nowMs: Long) {
        val st = stage ?: return
        if (followLeft > 0f) {
            followLeft -= dt
            val paused = nowMs - st.composerLastKeyMs > 1600L
            if (!hints.isTyping || paused || followLeft <= 0f || social != null) {
                followLeft = 0f
                // Done reading along: drift back up, sometimes with a blink.
                gaze.releaseFocus()
                if (rng.nextFloat() < 0.3f) blink.request()
            } else {
                caretPoint()?.let { p ->
                    val (gx, gy) = lookAt(p)
                    gaze.focus(gx, gy, 0.35f, priority = 2)
                }
            }
        }
        if (!hints.isTyping) {
            if (st.composerLen == 0) curiousDone = false
            followCheckIn = 5f + rng.nextFloat() * 5f
            return
        }
        // Mid-message: a slim chance to start reading along.
        followCheckIn -= dt
        if (followCheckIn <= 0f) {
            followCheckIn = 6f + rng.nextFloat() * 8f
            if (followLeft <= 0f && social == null && reaction == null && rng.nextFloat() < 0.1f) {
                startFollow(2f + rng.nextFloat() * 2.5f)
            }
        }
        // A long message: once per message, maybe, a small "oh, interesting" tilt.
        val longMessage = st.composerLen > 160 ||
            (st.composerBurstStartMs > 0L && nowMs - st.composerBurstStartMs > 20_000L)
        if (!curiousDone && longMessage) {
            curiousDone = true
            if (rng.nextFloat() < 0.35f && social == null && followLeft <= 0f && reaction == null) {
                caretPoint()?.let { p ->
                    val (gx, gy) = lookAt(p)
                    gaze.focus(gx, gy, 1.3f + rng.nextFloat() * 0.6f, priority = 2)
                }
                director.gesture(MotionDirector.Gesture.Tilt)
                if (rng.nextFloat() < 0.45f) setReaction("Surprised", 0.55f)
                attendedTypingAt = time
            }
        }
    }

    /** Message sent: sometimes a short look at it, a blink, a tiny pleased beat. */
    private fun onMessageSent(e: AvatarStage.Event) {
        if (social != null) return
        if (followLeft > 0f) {
            followLeft = 0f
            gaze.releaseFocus()
        }
        val wasWatching = time - attendedTypingAt < 10f
        if (rng.nextFloat() > if (wasWatching) 0.5f else 0.25f) return
        val me = stage?.entry(stageId)
        val at = e.at ?: me?.let { Offset(it.windowW * 0.72f, it.windowH * 0.8f) } ?: return
        val (gx, gy) = lookAt(at)
        gaze.focus(gx, gy, 0.6f + rng.nextFloat() * 0.3f, priority = 2)
        if (rng.nextFloat() < 0.5f) blink.request(minGap = 0.4f)
        val calm = director.lifecycle == MarkLifecycle.Idle || director.lifecycle == MarkLifecycle.Listening
        if (calm && reaction == null && rng.nextFloat() < 0.3f) setReaction("Happy", 0.7f)
        eventCooldown = 6f
    }

    /** Returns true if any event arrived (counts as a stimulus). */
    private fun processStageEvents(live: Boolean): Boolean {
        val st = stage ?: return false
        if (lastEventSeq < 0L) {
            lastEventSeq = st.latestSeq
            return false
        }
        val evs = st.eventsAfter(lastEventSeq)
        if (evs.isEmpty()) return false
        lastEventSeq = evs.last().seq
        if (!live) return false
        val calm = director.lifecycle == MarkLifecycle.Idle || director.lifecycle == MarkLifecycle.Listening
        for (e in evs) {
            if (e.kind == AvatarStage.EventKind.MessageSent) {
                onMessageSent(e)
                continue
            }
            if (!calm || social != null || eventCooldown > 0f) continue
            when (e.kind) {
                AvatarStage.EventKind.MessageArrived -> if (rng.nextFloat() < 0.45f) {
                    val at = e.at ?: windowBottom() ?: continue
                    val (gx, gy) = lookAt(at)
                    gaze.focus(gx, gy, 0.9f + rng.nextFloat() * 0.7f, priority = 2)
                    eventCooldown = 8f
                }
                AvatarStage.EventKind.Scroll -> if (rng.nextFloat() < 0.25f) {
                    gaze.focus((rng.nextFloat() - 0.5f) * 0.3f, -0.32f * e.direction, 0.6f + rng.nextFloat() * 0.4f, priority = 1)
                    eventCooldown = 12f
                }
                AvatarStage.EventKind.UserTyping -> onTypingStarted()
                AvatarStage.EventKind.MessageSent -> Unit
            }
        }
        return true
    }

    private fun onPoke() {
        pokeCount++
        pokeWindow = 2.2f
        when {
            pokeCount >= 5 && lastSpinAgo > 20f -> {
                if (director.gesture(MotionDirector.Gesture.Spin)) lastSpinAgo = 0f
                setReaction("Happy", 1.4f)
                pokeCount = 0
            }
            pokeCount >= 3 -> {
                director.gesture(MotionDirector.Gesture.TurnAway)
                setReaction("pouty", 1.6f)
            }
            pokeCount == 2 -> {
                director.gesture(MotionDirector.Gesture.Recoil)
                setReaction("hurt or cute", 1.0f)
                blink.request(minGap = 0.2f)
            }
            else -> {
                director.gesture(MotionDirector.Gesture.Recoil)
                blink.request(minGap = 0.2f)
                setReaction("Surprised", 0.45f)
                if (rng.nextFloat() < 0.4f) {
                    reactionQueue = "Happy" to 1.2f
                    reactionQueueIn = 0.5f
                }
            }
        }
    }

    private fun wake() {
        drowsy = false
        drowsyAfter = 100f + rng.nextFloat() * 60f
        blink.request(double = true, minGap = 0.2f)
        setReaction("Surprised", 0.5f)
    }

    private companion object {
        const val SETTLE_OUT_SEC = 0.8f
        const val SETTLE_IN_SEC = 0.6f
        const val CARET_CHARS_PER_LINE = 34
        val QUIET_HINTS = AvatarMotionHints()
        val REST_FRAME = MotionFrame()
    }

    private fun mapClipExpression(e: String): String? = when (e.lowercase()) {
        "neutral" -> "Neutral"
        "happy" -> "Happy"
        "surprised" -> "Surprised"
        "sad" -> "Sad"
        "sleepy" -> "Sleeping"
        "squinted", "calm" -> "Bored"
        "confused" -> "confused"
        // Never the Grok brand glance.
        else -> null
    }

    internal suspend fun run(hints: State<AvatarMotionHints>) {
        var last = withFrameNanos { it }
        while (true) {
            if (isSettled) {
                // Static and at rest: no frames at all until it should move again.
                snapshotFlow { animate }.first { it }
                last = withFrameNanos { it }
            }
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000.0).toFloat()
                last = now
                tick(dt, hints.value, System.currentTimeMillis())
            }
        }
    }
}

@Composable
fun rememberAvatarMotionState(
    isLoading: Boolean = false,
    isTyping: Boolean = false,
    hintsOverride: AvatarMotionHints? = null,
    /** False for Create Avatar calm tiles — no loops at all. */
    enabled: Boolean = true,
    /** False = static (eases to rest, then stops); see [AvatarMotionState.animate]. */
    animate: Boolean = true,
): AvatarMotionState {
    val local = LocalAvatarMotionHints.current
    val hints = (hintsOverride ?: local).let { base ->
        base.copy(
            isGenerating = base.isGenerating || isLoading,
            isTyping = base.isTyping || isTyping,
            inputFocused = base.inputFocused || isTyping,
        )
    }
    // Hints flow through a State so a change never restarts (and never cancels
    // mid-blink / mid-glance) the motion loop — that restart was one of the hard cuts.
    val hintsState = rememberUpdatedState(hints)
    val state = remember { AvatarMotionState().also { if (!animate) it.startSettled() } }
    SideEffect { state.animate = animate }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        state.run(hintsState)
    }
    return state
}

@Composable
fun ProvideAvatarMotionHints(
    hints: AvatarMotionHints,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAvatarMotionHints provides hints, content = content)
}
