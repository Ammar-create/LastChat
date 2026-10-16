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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.PI
import kotlin.math.sin
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
    /** Engine17: optional third layer blended over the pair (interrupted morph). */
    val exprTop: String? = null,
    val exprTopT: Float = 0f,
    val lifecycle: MarkLifecycle = MarkLifecycle.Idle,
    val eyeGap: Float = 0.94f,
    val eyeScale: Float = 1f,
    val timeSec: Float = 0f,
    /** Parametric per-eye shaping on top of the expression (see [FaceRig]). */
    val face: FaceParams = FaceParams.ID,
    /** Character-moment spin (deg), added after the per-shape turn cap (see drawMark). */
    val spinYaw: Float = 0f,
    /** Engine16: spin about other axes (deg): forward flip / cartwheel / tilted-axis paths. */
    val spinPitch: Float = 0f,
    val spinRoll: Float = 0f,
)

/**
 * Gaze (engine11): eyes saccade (critically damped, ~60 ms, no overshoot) or drift by
 * smooth pursuit; the head follows only bigger shifts, ~100 ms later, critically damped
 * (springy only in a playful mood). Fixations come from [IdlePlanner] (human timing).
 */
internal class GazeDirector(
    private val rng: Random,
    private val planner: IdlePlanner,
    private val temper: Temperament,
) {
    private var tx = 0f
    private var ty = 0f
    /** Debug: counts planner fixations (one log line each). */
    var fixSeq = 0; private set
    var lastFix: Fixation? = null; private set
    var eyeX = 0f; private set
    var eyeY = 0f; private set
    var evx = 0f; private set
    var evy = 0f; private set
    private var htx = 0f
    private var hty = 0f
    private var pendingHx = 0f
    private var pendingHy = 0f
    private var headDelay = 0f
    var headX = 0f; private set
    var headY = 0f; private set
    private var hvx = 0f
    private var hvy = 0f
    private var holdLeft = 1.5f
    private var focusLeft = 0f
    private var focusPriority = 0
    private var returnToCenter = false
    private var microLeft = 2f
    private var mx = 0f
    private var my = 0f
    // Engine17: fixational drift — slow, smooth, never-repeating wander (incommensurate
    // sines with random phases), so a hold is alive instead of a dead plateau.
    private var driftClock = 0f
    private val driftPh = FloatArray(6) { rng.nextFloat() * 6.2832f }
    // Smooth pursuit.
    private var pursuitT = 1f
    private var pursuitDur = 0f
    private var p0x = 0f; private var p0y = 0f; private var p1x = 0f; private var p1y = 0f

    /** Playful mood: head moves get a small overshoot (set each tick by the owner). */
    var playful = false
    /** Last fixation kind (debug / behaviour log). */
    var kind = "rest"; private set

    /** Set when a big gaze jump happens (blink sometimes rides it). */
    var bigShift = false; private set
    /** Attention event from the idle episode ("notice" / "ack"), consumed by the owner. */
    private var event: String? = null
    fun takeEvent(): String? = event.also { event = null }
    val episode: String get() = planner.episodeName

    fun focus(gx: Float, gy: Float, holdSec: Float, priority: Int, withHead: Boolean = true, travel: Float = 0f) {
        if (priority < focusPriority && focusLeft > 0f) return
        // Engine17.1: a glance must go somewhere. A target within MIN_GLANCE of rest while
        // the eyes are already resting is not worth a look (it read as a twitch + snap-back).
        val dRest = hypot(gx - REST_X, gy - REST_Y)
        if (travel <= 0f && dRest < MIN_GLANCE * 0.8f && hypot(tx - REST_X, ty - REST_Y) < 0.12f) {
            auditNearRestRaw++; return
        }
        if (travel <= 0f && dRest >= MIN_GLANCE * 0.8f) { auditGlances++; auditMinGlance = min(auditMinGlance, dRest) }
        focusPriority = priority
        // Up-right is only ever a short glance.
        val upRight = gx > 0.15f && gy < -0.08f
        // Hold proportional to the move (a far look is held longer); up-right stays a glance.
        focusLeft = if (upRight) holdSec.coerceAtMost(0.9f) else holdSec.coerceAtLeast(0.35f + 1.6f * dRest)
        if (dRest > 0.2f) returnToCenter = true // come back as a glide, never a snap
        if (upRight) returnToCenter = true
        returning = false
        if (travel > 0f) {
            // Smooth pursuit (a pan), the head following along.
            p0x = tx; p0y = ty; p1x = gx.coerceIn(-1f, 1f); p1y = gy.coerceIn(-1f, 1f)
            pursuitDur = travel; pursuitT = 0f
            focusLeft += travel
        } else {
            pursuitT = 1f
            setTarget(gx, gy, withHead)
        }
    }

    fun releaseFocus(awayX: Float? = null) {
        focusLeft = 0f
        focusPriority = 0
        if (awayX != null) {
            pursuitT = 1f
            setTarget(awayX, rng.nextFloat() * 0.2f, true)
            holdLeft = 0.8f + rng.nextFloat() * 0.8f
            returnToCenter = true
        } else {
            holdLeft = 0.4f + rng.nextFloat() * 0.6f
        }
    }

    val focused: Boolean get() = focusLeft > 0f

    private fun setTarget(x: Float, y: Float, withHead: Boolean) {
        val gx = x.coerceIn(-1f, 1f)
        val gy = y.coerceIn(-1f, 1f)
        val jump = hypot(gx - tx, gy - ty)
        returning = false // a new saccade ends any return glide (audit)
        if (jump > 0.55f) bigShift = true
        tx = gx; ty = gy
        // Eye–head coordination: small shifts are eyes only; bigger ones bring the head
        // ~75 % of the way, starting ~80–140 ms after the eyes.
        val headJoins = withHead || jump > 0.45f
        if (headJoins) {
            // Eyes lead, the head follows a beat later and carries most of a big turn.
            pendingHx = gx * 0.8f; pendingHy = gy * 0.72f
            headDelay = 0.12f + rng.nextFloat() * 0.1f
        }
    }

    fun update(dt: Float, life: MarkLifecycle, drowsy: Boolean) {
        bigShift = false
        if (focusLeft > 0f) {
            focusLeft -= dt
            if (focusLeft <= 0f) {
                focusPriority = 0
                holdLeft = if (returnToCenter) 0.15f else 0.3f + rng.nextFloat() * 0.5f
            }
        } else {
            holdLeft -= dt
            if (holdLeft <= 0f) chooseFixation(life, drowsy)
        }
        // Smooth pursuit: target glides with an ease-in-out.
        if (returning) {
            auditReturnPeak = max(auditReturnPeak, hypot(evx, evy))
            if (pursuitT >= 1f) returning = false
        }
        if (pursuitT < 1f) {
            pursuitT = (pursuitT + dt / pursuitDur).coerceAtMost(1f)
            val e = MotionVocab.easeInOutSine(pursuitT)
            tx = p0x + (p1x - p0x) * e; ty = p0y + (p1y - p0y) * e
            htx = tx * 0.75f; hty = ty * 0.7f
        }
        // Micro-saccades: small, irregular (1.2–4 s), eyes only.
        microLeft -= dt
        if (microLeft <= 0f) {
            microLeft = 1.2f + rng.nextFloat() * 2.8f
            val amp = if (drowsy) 0.008f else 0.022f
            mx = (rng.nextFloat() - 0.5f) * 2f * amp
            my = (rng.nextFloat() - 0.5f) * 2f * amp * 0.7f
        }
        if (headDelay > 0f) {
            headDelay -= dt
            if (headDelay <= 0f) { htx = pendingHx; hty = pendingHy }
        }
        driftClock += dt
        val dc = driftClock; val dk = if (drowsy) 0.5f else 1f
        val dex = dk * (0.006f * sin(0.83f * dc + driftPh[0]) + 0.004f * sin(1.97f * dc + driftPh[1]))
        val dey = dk * (0.005f * sin(0.61f * dc + driftPh[2]) + 0.003f * sin(1.43f * dc + driftPh[3]))
        val dhx = 0.014f * sin(0.37f * dc + driftPh[4]) + 0.006f * sin(0.89f * dc + driftPh[5])
        val dhy = 0.010f * sin(0.29f * dc + driftPh[5]) + 0.005f * sin(0.73f * dc + driftPh[0])
        val sacc = MotionVocab[Action.Saccade]
        val headSpec = MotionVocab[if (playful) Action.HeadTurnPlayful else Action.HeadTurn]
        val eyeW = if (drowsy) 12f else sacc.omega
        val headW = if (drowsy) 3.5f else headSpec.omega * temper.tempo
        val headZ = if (drowsy) 1f else headSpec.zeta
        var rem = dt
        while (rem > 1e-6f) {
            val h = min(rem, 1f / 120f)
            val ax = eyeW * eyeW * (tx + mx + dex - eyeX) - 2f * eyeW * evx
            val ay = eyeW * eyeW * (ty + my + dey - eyeY) - 2f * eyeW * evy
            evx += ax * h; evy += ay * h
            eyeX += evx * h; eyeY += evy * h
            val bx = headW * headW * (htx + dhx - headX) - 2f * headZ * headW * hvx
            val by = headW * headW * (hty + dhy - headY) - 2f * headZ * headW * hvy
            hvx += bx * h; hvy += by * h
            headX += hvx * h; headY += hvy * h
            rem -= h
        }
    }

    // ---- Engine17.1 gaze audit (read by tests) ----
    var auditNearRestRaw = 0; private set   // planner asked for a near-rest "interest" target
    var auditNearRest = 0; private set      // …that still went out near rest (must stay 0)
    var auditGlances = 0; private set
    var auditMinGlance = 9f; private set
    var auditReturns = 0; private set
    var auditReturnPeak = 0f; private set   // max eye speed (gaze units/s) during return glides
    private var returning = false

    /** Back to the rest point as an eased glide (never a snap-back), slower for longer trips. */
    private fun glideHome(x: Float, y: Float) {
        val d = hypot(x - tx, y - ty)
        if (d < 0.06f) { setTarget(x, y, true); return }
        p0x = tx; p0y = ty; p1x = x; p1y = y
        pursuitDur = 0.55f + 1.1f * d.coerceAtMost(0.55f); pursuitT = 0f
        returning = true; auditReturns++
    }

    private fun chooseFixation(life: MarkLifecycle, drowsy: Boolean) {
        fun rr(a: Float, b: Float) = a + (b - a) * rng.nextFloat()
        if (returnToCenter) {
            returnToCenter = false
            kind = "return"
            glideHome(rr(-0.08f, 0.08f), rr(-0.03f, 0.06f))
            holdLeft = pursuitDur + rr(2f, 4.5f)
            return
        }
        var f = planner.next(life, temper, drowsy)
        // Engine17.1: an interest glance must be a meaningful look somewhere — no near-rest
        // micro targets (tiny drifts during holds are separate). Push it out along its own
        // direction, never into the up-right.
        if (f.kind in GLANCE_KINDS && f.travel <= 0f) {
            val dx = f.x - REST_X; val dy = f.y - REST_Y
            val d = hypot(dx, dy)
            if (d < MIN_GLANCE) {
                auditNearRestRaw++
                val ang = if (d > 0.02f) kotlin.math.atan2(dy, dx) else (if (rng.nextBoolean()) 0f else PI.toFloat()) + rr(-0.5f, 0.5f)
                var nx = REST_X + cos(ang) * MIN_GLANCE * rr(1f, 1.15f)
                var ny = REST_Y + sin(ang) * MIN_GLANCE * rr(1f, 1.15f)
                if (nx > 0.15f && ny < -0.08f) ny = -ny
                f = f.copy(x = nx.coerceIn(-1f, 1f), y = ny.coerceIn(-1f, 1f))
            }
            val d2 = hypot(f.x - REST_X, f.y - REST_Y)
            auditGlances++; auditMinGlance = min(auditMinGlance, d2)
            if (d2 < MIN_GLANCE * 0.95f) auditNearRest++
            // Hold proportional to the move: a bigger look is worth looking at longer.
            f = f.copy(hold = f.hold.coerceAtLeast(0.45f + 2.2f * d2))
        }
        kind = f.kind
        fixSeq++; lastFix = f
        if (f.event != null) event = f.event
        if (f.thenCenter) returnToCenter = true
        val homeward = f.travel <= 0f && hypot(f.x - REST_X, f.y - REST_Y) < 0.12f && hypot(tx - REST_X, ty - REST_Y) > 0.18f
        if (homeward) {
            glideHome(f.x, f.y)
            holdLeft = f.hold + pursuitDur
        } else if (f.travel > 0f) {
            p0x = tx; p0y = ty; p1x = f.x; p1y = f.y
            pursuitDur = f.travel; pursuitT = 0f
            holdLeft = f.hold + f.travel
        } else {
            pursuitT = 1f
            setTarget(f.x, f.y, f.withHead)
            holdLeft = f.hold
        }
    }
}

/**
 * Expression transitions (engine12): quick, smooth and strictly monotonic, style per change.
 *  - [Style.Blink]: the face swaps while the lids are actually shut (waits for the blink to
 *    close, swaps in ~50 ms, the blink reopens on the new face). Falls back to a snap if the
 *    lids don't close within 180 ms.
 *  - [Style.Snap]: 120–220 ms ease-out — reactions.
 *  - [Style.Soft]: 280–380 ms ease-in-out — unhurried, self-initiated changes.
 *  - [Style.Gradual]: 0.9–1.4 s ease-in-out — mood drift, drowsiness.
 * engine11's "hesitate" (partial move + plateau) read as a stutter and is gone. One system
 * owns the morph; a new target never interrupts a running morph (it queues).
 */
/** Engine17.1 gaze rules. */
private val GLANCE_KINDS = setOf("interest", "explore", "think", "ack", "pan")
private const val MIN_GLANCE = 0.24f
private const val REST_X = 0f
private const val REST_Y = 0.02f

internal class ExpressionDirector(private val rng: Random) {
    enum class Style { Blink, Snap, Soft, Gradual }

    /**
     * Engine17: layered, interruptible blend. The displayed face is
     * base → layer1 (w1) → layer2 (w2). A new target never waits: it becomes a new layer
     * that grows over whatever is currently shown, so a change of mind mid-morph bends
     * smoothly instead of finishing, pausing and restarting (the old "gearbox").
     */
    private class Layer(val name: String, val style: Style, val dur: Float) {
        var t = 0f
        var waitLids = -1f
        /** Change of mind back to the face underneath: the layer retreats along its own path. */
        var back = false
        var backFrom = 0f
        var backT = 0f
        val eased: Float get() = if (back) backFrom * (1f - backT * backT * backT * (backT * (backT * 6f - 15f) + 10f)) else fwd
        val fwd: Float get() = when (style) {
            // Reactions: quick attack, long soft landing (no hard stop).
            Style.Snap, Style.Blink -> 1f - (1f - t).let { it * it * it * it }
            Style.Soft, Style.Gradual -> t * t * t * (t * (t * 6f - 15f) + 10f)
        }
    }

    private var base = "Neutral"
    private val layers = ArrayList<Layer>(2)
    private var pending: String? = null
    private var pendingStyle: Style? = null
    private var blinkNow = false
    var style = Style.Snap; private set

    /** Context for the next change (set by the owner just before [want]). */
    var hint: Style? = null

    val from: String get() = base
    /** The face being moved toward (latest intent). */
    val to: String get() {
        val l = layers.lastOrNull() ?: return base
        return if (l.back) (layers.getOrNull(layers.size - 2)?.name ?: base) else l.name
    }
    /** Pair partner of [from] in the frame (layer 1). */
    val pairTo: String get() = layers.firstOrNull()?.name ?: base
    val eased: Float get() = layers.firstOrNull()?.eased ?: 1f
    val top: String? get() = layers.getOrNull(1)?.name
    val topEased: Float get() = layers.getOrNull(1)?.eased ?: 0f
    val morphing: Boolean get() = layers.isNotEmpty()

    fun want(raw: String) {
        val target = normalize(raw)
        val h = hint
        hint = null
        if (target == to) { pending = null; return }
        val last = layers.lastOrNull()
        val under = layers.getOrNull(layers.size - 2)?.name ?: base
        if (last != null && target == under && !last.back && last.waitLids < 0f) {
            last.backFrom = last.eased; last.backT = 0f; last.back = true; pending = null; return
        }
        if (layers.size >= 2) {
            // Third change inside one morph: if the oldest pose is nearly gone, drop it.
            val effBase = (1f - layers[0].eased) * (1f - layers[1].eased)
            if (effBase < 0.04f) { base = layers.removeAt(0).name } else {
                pending = target; pendingStyle = h ?: pendingStyle; return
            }
        }
        start(target, h)
    }

    private fun start(target: String, h: Style?) {
        val st = h ?: run {
            val r = rng.nextFloat()
            when {
                r < 0.4f -> Style.Blink
                r < 0.75f -> Style.Snap
                else -> Style.Soft
            }
        }
        // A blink can only mask the change if nothing else is mid-morph.
        val s2 = if (st == Style.Blink && layers.isNotEmpty()) Style.Soft else st
        val dur = when (s2) {
            Style.Blink -> 0.06f
            Style.Snap -> 0.24f + rng.nextFloat() * 0.1f
            Style.Soft -> 0.48f + rng.nextFloat() * 0.18f
            Style.Gradual -> 1.0f + rng.nextFloat() * 0.5f
        }
        val l = Layer(target, s2, dur)
        if (s2 == Style.Blink) { blinkNow = true; l.waitLids = 0f }
        layers += l
        style = s2
        pending = null; pendingStyle = null
    }

    /** [lidsClosed] 0…1 from the blinker. Returns true when a masking blink should start now. */
    fun update(dt: Float, lidsClosed: Float): Boolean {
        val blink = blinkNow
        blinkNow = false
        for (l in layers) {
            if (l.waitLids >= 0f) {
                l.waitLids += dt
                if (lidsClosed >= 0.85f || l.waitLids > 0.18f) l.waitLids = -1f else continue
            }
            val d = if (l.style == Style.Blink && lidsClosed < 0.85f) 0.3f else l.dur
            if (l.back) l.backT = min(1f, l.backT + dt / (d * (0.4f + 0.6f * l.backFrom)))
            else l.t = min(1f, l.t + dt / d)
        }
        // A fully retreated layer is gone (the face is exactly what was underneath).
        layers.removeAll { it.back && it.backT >= 1f }
        // Collapse finished layers: a complete layer hides everything beneath it.
        val iDone = layers.indexOfLast { !it.back && it.t >= 1f }
        if (iDone >= 0) {
            base = layers[iDone].name
            repeat(iDone + 1) { layers.removeAt(0) }
        }
        val p = pending
        if (p != null && layers.size < 2) start(p, pendingStyle)
        return blink
    }

    companion object {
        const val MIN_DWELL = 0.25f

        fun normalize(raw: String): String =
            EyeStyle.resolveGenericalExpressionId(raw)
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

    /** Character engine (traits, emotions, drives, spontaneous beats). */
    internal val aliveness = AlivenessEngine(rng)

    /** Character traits; set per avatar (see [Personality.defaultFor]). */
    var personality: Personality
        get() = aliveness.personality
        set(v) { aliveness.personality = v }

    /** Harness hooks. */
    internal fun debugConfused() = setReaction("confused", 1.1f)
    internal fun debugReact(expr: String, sec: Float) = setReaction(expr, sec)
    internal fun debugBoredom(v: Float) = aliveness.debugBoredom(v)

    /** Per-message tone from outside (e.g. a classifier); colours mood and face. */
    fun feedTone(t: ToneHint) {
        aliveness.feedTone(t)
        // A nice message sometimes earns a happy wiggle (never every time, never twice in a row).
        if (t.valence > 0.5f && t.intensity > 0.5f && rng.nextFloat() < 0.25f + 0.25f * personality.playfulness &&
            moments.allow(Moment.Wiggle, 40f)
        ) {
            if (temper.playful(personality) && rng.nextFloat() < 0.3f && lastSpinAgo > 45f) { remember("nice"); trySpin("delighted by a nice message") }
            else { moments.note(Moment.Wiggle); pendingWiggleIn = 0.25f + rng.nextFloat() * 0.5f }
        }
    }
    private var pendingWiggleIn = -1f
    // ---- human behaviour model (engine11) ----
    internal val temper = Temperament(rng)
    private val attention = Attention(rng)
    private val repertoire = Repertoire(rng)
    private val planner = IdlePlanner(rng)
    private val gaze = GazeDirector(rng, planner, temper)
    /** Engine17.1: gaze audit counters (tests). */
    internal val debugGaze: GazeDirector get() = gaze
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
    private var lastSpinAgo = 20f
    private var nextSpinAt = -1f
    private var lastShownTarget = "Neutral"
    private var lastFixSeq = 0
    private var lastGazeKind = ""
    private var lastReactionShown: String? = null
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

    // Mood + parametric face.
    private val mood = MoodEngine(rng)
    private val rig = FaceRig(rng)
    private var hourCheckIn = 0f
    private var accentCooldown = 0f
    private var mirror: Pair<FaceAccent, Float>? = null
    private var mirrorIn = 0f
    private var flingCooldown = 0f
    private var forgiveIn = -1f
    private var contactSec = 0f

    /** Mood readout (tests / harness). */
    internal val moodValence: Float get() = mood.valence
    internal val moodArousal: Float get() = mood.arousal
    internal val currentAccent: FaceAccent get() = rig.accent

    /** Harness: show a parametric look right away. */
    internal fun debugAccent(a: FaceAccent, sec: Float = 3f) = rig.play(a, sec)

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

    // ------------------------------------------------ editor cues (engine16.2)
    private class CueFire(var at: Float, val cue: EditorCue)
    private val cueQueue = ArrayList<CueFire>()
    private val cueTimes = ArrayDeque<Float>()
    private var lastCueAt = -99f
    private var lastCueKind: EditorCue.Kind? = null
    private var lastGlanceAt = -99f
    private var curiousAt = -99f
    private var afterDone = true

    /** The editor changed something: notice it after a human latency, not on every tap. */
    fun editorCue(cue: EditorCue) {
        if (!animate) return
        cueQueue += CueFire(0.25f + rng.nextFloat() * 0.25f, cue)
    }

    private fun editorUpdate(dt: Float): Boolean {
        var any = false
        val it = cueQueue.iterator()
        while (it.hasNext()) {
            val c = it.next()
            c.at -= dt
            if (c.at > 0f) continue
            it.remove(); any = true
            onEditorCue(c.cue)
        }
        if (!afterDone && time - lastCueAt > 1.3f) {
            afterDone = true
            val r = rng.nextFloat()
            when {
                lastCueKind == EditorCue.Kind.Shape && r < 0.3f -> {
                    log("editor after-shape: looks down at itself")
                    gaze.focus(rr(-0.08f, 0.08f), rr(0.38f, 0.46f), rr(1.0f, 1.5f), priority = 2)
                }
                r < 0.5f -> { log("editor after: pleased"); accent(FaceAccent.Glee, rr(0.9f, 1.3f)) }
                r < 0.65f -> { log("editor after: nod"); director.gesture(MotionDirector.Gesture.Nod) }
                else -> log("editor after: just settles")
            }
        }
        return any
    }

    private fun rr(a: Float, b: Float) = a + (b - a) * rng.nextFloat()

    private fun onEditorCue(cue: EditorCue) {
        sinceStimulus = 0f
        lastCueAt = time; lastCueKind = cue.kind; afterDone = false
        cueTimes.addLast(time); while (cueTimes.isNotEmpty() && time - cueTimes.first() > 4f) cueTimes.removeFirst()
        val (gx, gy) = cue.at?.let { lookAt(it) } ?: (0f to 0.4f)
        if (cue.kind == EditorCue.Kind.EyeStyle && rng.nextFloat() < 0.5f) blink.request()
        if (cueTimes.size >= 3 && time - curiousAt > 8f) {
            curiousAt = time
            log("editor: browsing fast → curious")
            accent(FaceAccent.Curious, rr(1.3f, 1.9f))
            gaze.focus(gx, gy, rr(1.2f, 1.8f), priority = 2)
            lastGlanceAt = time
            return
        }
        if (time - lastGlanceAt > 1.6f && rng.nextFloat() < 0.55f) {
            lastGlanceAt = time
            log("editor: glance at ${cue.kind}")
            gaze.focus(gx, gy, rr(0.8f, 1.5f), priority = 2)
        } else log("editor: ${cue.kind} noticed, no move")
    }

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
        if (live && prev != null && hints.isTyping && !prev.isTyping) stimulus(Stimulus("typing", 0f, 0.85f, 0.45f), { r -> if (r != Response.None) onTypingStarted() })
        prevHints = hints
        stimulus = processStageEvents(live) || stimulus
        if (live) stimulus = editorUpdate(dt) || stimulus
        if (live) typingUpdate(dt, hints, nowMs)
        if (pendingPokes > 0) {
            if (live) {
                stimulus = true
                repeat(pendingPokes) { stimulus(Stimulus("poke", 0f, 0f, 0.9f, direct = true), { r -> onPoke(r) }, allowNone = false) }
            }
            pendingPokes = 0
        }
        val calmLife = director.lifecycle == MarkLifecycle.Idle && desired == MarkLifecycle.Idle
        if (stimulus || !calmLife) {
            if (drowsy) wake()
            if (quietSec > 20f) log("quiet reset after ${quietSec.toInt()} s (stimulus=$stimulus calm=$calmLife)")
            quietSec = 0f
        } else {
            quietSec += dt
        }
        if (!drowsy && calmLife && quietSec > drowsyAfter * (0.55f + mood.arousal)) {
            drowsy = true
            log("drowsy onset after ${quietSec.toInt()} s untouched")
            droopIn = 6f + rng.nextFloat() * 6f
        }
        director.drowsy = drowsy

        if (live) {
            aliveUpdate(dt, nowMs)
        } else {
            reaction = null
            reactionQueue = null
            train.clear()
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
            if (live && social == null && followLeft <= 0f && !gaze.focused && recoverT <= 0f) {
                // Look at the real thing when it is on screen.
                val real = when (c.kind) {
                    "pill" -> targetCenter(AvatarStage.Target.ActivityPill)
                    "read" -> stage?.target(AvatarStage.Target.StreamingText)?.let { r ->
                        // Somewhere along the last lines of the arriving text.
                        Offset(r.left + r.width * (0.15f + 0.7f * rng.nextFloat()), r.bottom - minOf(r.height, 120f) * rng.nextFloat())
                    }
                    else -> null
                }
                if (real != null) {
                    val (gx, gy) = lookAt(real)
                    gaze.focus(gx, gy, c.holdSec, priority = 1)
                } else {
                    gaze.focus(c.x, c.y, c.holdSec, priority = 1)
                }
                if (c.kind == "ponder" && rng.nextFloat() < 0.2f) accent(FaceAccent.Puzzled, 1.4f + rng.nextFloat())
            }
        }

        // ---- gaze ----
        gaze.update(dt, director.lifecycle, drowsy)
        // Big eye jumps often carry a blink (people blink as they look somewhere new).
        if (gaze.bigShift && rng.nextFloat() < 0.4f) blink.request(minGap = 0.9f)
        gaze.takeEvent()?.let { ev -> if (live) attentionEvent(ev) }
        if (gaze.fixSeq != lastFixSeq) {
            lastFixSeq = gaze.fixSeq
            gaze.lastFix?.let { f -> log("fix ${f.kind} → (%.2f,%.2f) hold %.1fs".format(f.x, f.y, f.hold)) }
        }
        if (gaze.kind != lastGazeKind) {
            lastGazeKind = gaze.kind
            log("gaze ${gaze.kind} (%.2f,%.2f)".format(gaze.eyeX, gaze.eyeY))
        }

        // ---- expression ----
        // Never swap faces mid-nod (a morph under a nod reads as a size pop).
        // Engine17.1: the face keeps living through a spin (a frozen mid-morph read as a
        // stall); changes that start during a turn use the soft blend, never a blink swap.
        val spinFace = abs(spinYaw) > 0.5f || abs(spinV) > 1f
        if (!director.nodding) {
            val target = currentExpressionTarget()
            if (target != lastShownTarget) {
                // Every face change is logged with its cause (reactions log their own).
                if (reaction == null || reaction != target) log("face ${lastShownTarget}→$target cause=" + when {
                    reaction == null && lastShownTarget == lastReactionShown -> "reaction ends, back to rest"
                    MotionDirector.isBusy(director.lifecycle) -> "busy phase (${director.lifecycle})"
                    else -> "lifecycle ${director.lifecycle}"
                })
                lastShownTarget = target
                lastReactionShown = reaction
            }
            if (ExpressionDirector.normalize(target) != expr.to) {
                expr.hint = when {
                    reaction != null && target == reaction -> reactionStyle.also { reactionStyle = null }
                    drowsy || temper.state == Temperament.State.Tired -> ExpressionDirector.Style.Gradual
                    else -> null
                }.let { h -> if (spinFace && (h == null || h == ExpressionDirector.Style.Blink)) ExpressionDirector.Style.Soft else h }
            }
            expr.want(target)
        }
        if (expr.update(dt, blink.closed) && !spinFace) blink.request(minGap = 0f)
        director.exprMorphing = expr.morphing

        // ---- blink + lids ----
        blink.update(dt, director.lifecycle, drowsy)
        val pose = MarkLifecycleMapper.pose(director.lifecycle)
        val k = 1f - exp(-dt / 0.35f)
        lidKnob += (pose.lid - lidKnob) * k
        gapKnob += (pose.eyeGap - gapKnob) * k
        scaleKnob += (pose.eyeScale - scaleKnob) * k
        val drowsyTarget = if (drowsy) 0.55f else 1f
        drowsyLid += (drowsyTarget - drowsyLid) * (1f - exp(-dt / if (drowsy) 2.5f else 0.3f))
        // Startle window: eyes snap wide open and stay open (~0.55 s) — no blink, no droop.
        if (director.startling && !wasStartling) startleHoldT = 0.6f
        wasStartling = director.startling
        startleHoldT -= dt
        val inStartle = startleHoldT > 0f
        val lid = if (inStartle) 1f
            else (lidKnob.coerceAtMost(1f) * body.lidOpen * drowsyLid * momentLid * restLid * slowBlinkLid() * shakeLid * (1f - blink.closed)).coerceIn(0f, 1f)

        // ---- mood + parametric face ----
        hourCheckIn -= dt
        if (hourCheckIn <= 0f) {
            hourCheckIn = 60f
            val h = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }.get(java.util.Calendar.HOUR_OF_DAY)
            mood.arousalBase = MoodEngine.baseForHour(h)
        }
        mood.update(dt, quietSec)
        director.moodValence = mood.valence
        director.moodArousal = mood.arousal
        val busyNow = MotionDirector.isBusy(director.lifecycle)
        val faceLive = rig.update(
            dt,
            gaze.evx, gaze.evy,
            blink.closed,
            valence = if (live) mood.valence * (if (busyNow) 0.5f else 1f) else 0f,
            arousal = if (live) mood.arousal else 0.5f,
            still = live && !gaze.bigShift && social == null,
        )

        stage?.setCalm(stageId, calmLife && social == null && reaction == null && !drowsy)

        // Blend toward the static rest pose (weight eased so it starts and lands softly).
        val w = MotionDirector.easeInOutCubic(settle)
        val keep = 1f - w
        val awakeLife = if (drowsy || director.lifecycle == MarkLifecycle.Sleeping) 0.5f else 1f
        humanUpdate(dt, live)
        // Breathing: slow sinusoid (≈ 4.2 s, slower when calm / tired), barely visible.
        val brPeriod = MotionVocab[Action.Breathing].dur * (1.25f - 0.35f * temper.energy)
        breathPh += dt * 2f * PI.toFloat() / brPeriod
        val br = sin(breathPh) * keep * awakeLife
        // Sigh: one slow deep breath with the head sinking a little (ease-in-out bump).
        val sighK = if (sighU < 1f) sin(PI.toFloat() * MotionVocab.easeInOutSine(sighU)) * keep else 0f
        // Fidget: occasional small posture shift (critically damped).
        run {
            val om = MotionVocab[Action.Fidget].omega
            val h = dt.coerceAtMost(1f / 30f)
            fidgetYawV += (om * om * (fidgetYawT - fidgetYaw) - 2f * om * fidgetYawV) * h; fidgetYaw += fidgetYawV * h
            fidgetRollV += (om * om * (fidgetRollT - fidgetRoll) - 2f * om * fidgetRollV) * h; fidgetRoll += fidgetRollV * h
        }
        val bodyBase0 = body.lerpTo(BodyPose.REST, w)
        // Moment channels: wiggle (damped roll oscillation), stretch (rise + hold + settle),
        // sleepy drift (head sinks, then perks up), peek lean.
        val wig = if (wiggleU < 1f) wiggleA * sin(2f * PI.toFloat() * 1.5f * wiggleU) * (1f - wiggleU) * (1f - wiggleU) else 0f
        val stK = if (stretchU < 1f) when {
            // Small crouch first (anticipation), then the rise.
            stretchU < 0.12f -> -0.3f * sin(PI.toFloat() * stretchU / 0.12f)
            stretchU < 0.35f -> MotionVocab.easeInOutSine((stretchU - 0.12f) / 0.23f)
            stretchU < 0.6f -> 1f
            else -> 1f - MotionVocab.easeInOutSine((stretchU - 0.6f) / 0.4f)
        } else 0f
        val drK = if (driftU < 1f) when {
            driftU < 0.8f -> MotionVocab.easeInOutSine(driftU / 0.8f)
            else -> 1f - MotionVocab.easeOutCubic((driftU - 0.8f) / 0.2f) // perk: quick lift
        } else 0f
        val bodyBase = bodyBase0.copy(
            yaw = bodyBase0.yaw + (fidgetYaw + peekYaw + shakeYaw) * keep,
            roll = bodyBase0.roll + (fidgetRoll + peekRoll + wig) * keep,
            pitch = bodyBase0.pitch + (4f * sighK + driftDepth * drK - 3f * stK - 7f * yawnK() + 5f * restK) * keep,
        )
        var bodyOut = bodyBase.copy(
            posY = bodyBase.posY - 0.008f * br + 0.012f * sighK - 0.03f * stK * keep + 0.4f * spinCrouchK - 0.02f * yawnK() * keep + 0.012f * restK * keep,
            squashX = bodyBase.squashX * (1f - 0.008f * br + 0.018f * sighK - 0.06f * stK * keep + 0.6f * spinCrouchK - 0.04f * yawnK() * keep + 0.012f * restK * keep),
            squashY = bodyBase.squashY * (1f + 0.012f * br - 0.03f * sighK + 0.1f * stK * keep - spinCrouchK + 0.07f * yawnK() * keep - 0.015f * restK * keep),
        )
        momentLid = (1f - 0.75f * stK.coerceAtLeast(0f) - 0.45f * drK - 0.85f * yawnK()).coerceIn(0f, 1f)
        var hx = (gaze.headX * keep).coerceIn(-1f, 1f)
        var hy = (gaze.headY * keep).coerceIn(-1f, 1f)
        // Eyes lag a head shake a touch (secondary motion) and lag a wiggle.
        var lx = ((gaze.eyeX - gaze.headX) * 0.07f + gaze.eyeX * 0.03f) * keep - (shakeYaw * 0.004f + wig * 0.003f) * keep
        var ly = ((gaze.eyeY - gaze.headY) * 0.06f + gaze.eyeY * 0.025f) * keep
        // Dumbfounded freeze: snap, then rigid (stylistic stiffness) — the whole look holds.
        if (freezeLeft > 0f) {
            val fz = frozen
            if (fz == null) frozen = FrozenLook(bodyOut, hx, hy, lx, ly)
            else { bodyOut = fz.body.copy(squashX = fz.body.squashX, squashY = fz.body.squashY); hx = fz.hx; hy = fz.hy; lx = fz.lx; ly = fz.ly }
        } else frozen = null
        // Up-right is a glance, never a rest: after ~0.75 s the up component eases out.
        val upNow = bodyOut.yaw + 18f * hx > 3f && bodyOut.pitch + 14f * hy < -2f
        upRightT = if (upNow) upRightT + dt else (upRightT - 2f * dt).coerceAtLeast(0f)
        if (upRightT > 0.75f && upK < 0.05f) gaze.focus(0.05f * (rng.nextFloat() - 0.5f), 0.04f, 0.8f, priority = 5)
        upK += ((if (upRightT > 0.75f) 1f else 0f) - upK) * (1f - exp(-dt / 0.15f))
        if (upK > 1e-3f) {
            val f = 1f - upK
            if (hy < 0f) hy *= f
            if (ly < 0f) ly *= f
            if (bodyOut.pitch < 0f) bodyOut = bodyOut.copy(pitch = bodyOut.pitch * f)
            if (bodyOut.yaw > 0f) bodyOut = bodyOut.copy(yaw = bodyOut.yaw * (1f - 0.5f * upK))
        }
        val bodyRc = bodyOut
        val pitchOut = bodyRc.pitch
        val built = MotionFrame(
            body = bodyRc,
            headLookX = hx,
            headLookY = hy,
            leadX = lx,
            leadY = ly,
            lid = lid + (1f - lid) * w,
            exprFrom = expr.from,
            exprTo = expr.pairTo,
            exprT = expr.eased,
            exprTop = expr.top,
            exprTopT = expr.topEased,
            lifecycle = director.lifecycle,
            eyeGap = gapKnob + (REST_FRAME.eyeGap - gapKnob) * w,
            eyeScale = scaleKnob + (REST_FRAME.eyeScale - scaleKnob) * w,
            timeSec = time,
            face = lerpFace(faceLive, w),
            spinYaw = spinAngles().first * keep,
            spinPitch = spinAngles().second * keep,
            spinRoll = spinAngles().third * keep,
        )
        // During a spin the face is rigid: gaze, head look, lead, lids and expression hold the
        // values they had when the spin began, so the eyes ride the surface with no drift.
        val spinningNow = abs(spinYaw) > 0.5f || abs(spinV) > 1f
        if (spinningNow) {
            var held = spinHeld ?: frame.also { spinHeld = it }
            // A blink that was under way when the spin began finishes opening (never spin
            // with shut eyes); after that the lid holds.
            if (built.lid > held.lid) { held = held.copy(lid = built.lid); spinHeld = held }
            frame = built.copy(
                // No other layer adds or fights rotation while it spins.
                body = built.body.copy(yaw = held.body.yaw, pitch = held.body.pitch, roll = held.body.roll),
                headLookX = held.headLookX, headLookY = held.headLookY,
                leadX = held.leadX, leadY = held.leadY, lid = held.lid,
                face = held.face, eyeGap = held.eyeGap, eyeScale = held.eyeScale,
                timeSec = held.timeSec,
            )
            spinRelease = 0f
        } else {
            val held = spinHeld
            if (held != null && spinRelease < 1f) {
                // Hand back to the live layers smoothly (no pop after the spin).
                spinRelease = (spinRelease + dt / 0.35f).coerceAtMost(1f)
                val k = MotionVocab.easeInOutSine(spinRelease)
                fun l(a: Float, b: Float) = a + (b - a) * k
                frame = built.copy(
                    body = built.body.copy(yaw = l(held.body.yaw, built.body.yaw), pitch = l(held.body.pitch, built.body.pitch), roll = l(held.body.roll, built.body.roll)),
                    headLookX = l(held.headLookX, built.headLookX), headLookY = l(held.headLookY, built.headLookY),
                    leadX = l(held.leadX, built.leadX), leadY = l(held.leadY, built.leadY), lid = l(held.lid, built.lid),
                    face = lerpFaceParams(held.face, built.face, k),
                )
                if (spinRelease >= 1f) spinHeld = null
            } else {
                spinHeld = null
                frame = built
            }
        }
        withFollow()
    }
    private var spinHeld: MotionFrame? = null
    private fun withFollow() {
        if (spinFollowX == 0f) return
        // The face keeps sliding a touch along the spin direction (vertical for a flip).
        frame = if (spPath == SpinPath.Flip) frame.copy(leadY = frame.leadY + spinFollowX * 0.8f)
            else frame.copy(leadX = frame.leadX + spinFollowX)
    }
    private fun spinAngles(): Triple<Float, Float, Float> =
        if (abs(spinYaw) < 1e-4f) ZERO3 else spPath.angles(spinYaw / (360f * spDir), spDir)
    private val ZERO3 = Triple(0f, 0f, 0f)
    private var spinRelease = 1f

    private var breathPh = 0f
    private var startleHoldT = 0f
    private var momentLid = 1f
    private var wasStartling = false

    // ------------------------------------------------------- human behaviour (engine11)

    // ---- character moments (engine12) ----
    private val moments = MomentPlanner(rng)
    private var spinYaw = 0f; private var spinV = 0f
    /**
     * Spin (engine13), one timeline per spin:
     *  run-up (optional): a small counter-turn of a few degrees with a slight crouch, eased;
     *  turn: from the wind-up through 360°, velocity rising from rest and then a long, friction-
     *        like deceleration to a dead stop (no body overshoot);
     *  follow-through: the body has stopped, the face keeps sliding a little further along the
     *        surface in the spin direction (ease-out), then glides back (ease-in-out, no wobble);
     *  shake-off (optional, mood-dependent): a few small decaying head shakes, eyes lagging.
     * Variants (run-up, shake, direction, speed, sizes) are randomised and history-aware.
     */
    /** After a spin (and any shake-off): a short "recovering" pause. No new glances or
     *  moments, eyes rest near centre with only micro-saccades; real stimuli still win. */
    private var recoverT = 0f
    /** Seconds since the last perceived outside event: no self-started moment right on top of a reaction. */
    private var sinceStimulus = 99f
    private fun beginRecover() {
        recoverT = 0.8f + rng.nextFloat() * 1.7f
        gaze.focus((rng.nextFloat() - 0.5f) * 0.08f, (rng.nextFloat() - 0.5f) * 0.06f, recoverT, priority = 3)
        // Engine16: varied recovery, picked with a reason (how big the spin was, mood).
        if (spShake) return // the shake-off already was the recovery
        val big = spPath != SpinPath.Turn && spPath != SpinPath.Top
        val r = rng.nextFloat()
        when {
            big && r < 0.3f -> { log("recover dizzy (after a $spPath)"); accent(FaceAccent.Dizzy, 0.9f + 0.5f * rng.nextFloat(), force = true) }
            r < 0.45f -> { log("recover blink-blink"); blink.request(double = true, minGap = 0f) }
            r < 0.6f && mood.valence > 0.1f -> { log("recover pleased"); accent(FaceAccent.Glee, 1.0f, force = true) }
            r < 0.7f -> { log("recover nod"); director.gesture(MotionDirector.Gesture.Nod) }
            else -> log("recover still")
        }
    }
    private var spT = -1f; private var spDir = 1f
    private var spRun = 0f; private var spRunDeg = 0f; private var spCrouch = 0f
    private var spMain = 1.2f
    private var spFollow = 0f; private var spFollowOut = 0.2f; private var spFollowBack = 0.5f
    private var spShake = false; private var spShakeDelay = 0.15f; private var spShakeAmp = 5f
    private var spShakeHz = 4.5f; private var spShakeCycles = 2.5f
    private var spLastRun = false; private var spLastShake = false
    private var spDirRun = 0
    private var spinCrouchK = 0f
    private var spinFollowX = 0f
    private var shakeYaw = 0f
    private var forceRun: Boolean? = null; private var forceShake: Boolean? = null
    private var forcePath: SpinPath? = null
    internal var spPath = SpinPath.Turn; private set
    private var spLastPath: SpinPath? = null
    private val spPathHist = ArrayDeque<SpinPath>()
    internal fun debugSpin(runUp: Boolean?, shake: Boolean?, path: SpinPath? = null) { forceRun = runUp; forceShake = shake; forcePath = path; moments.note(Moment.Spin); playMoment(Moment.Spin) }

    /** Engine16: spins only in lively contexts (never self-started while idle). */
    private fun trySpin(cause: String): Boolean {
        if (spT >= 0f || lastSpinAgo < 20f || drowsy || restPhase > 0) return false
        log("spin cause=$cause")
        moments.note(Moment.Spin); playMoment(Moment.Spin)
        lastSpinAgo = 0f; nextSpinAt = -1f
        return true
    }

    private fun startSpin(playful: Boolean) {
        fun rr(a: Float, b: Float) = a + (b - a) * rng.nextFloat()
        // Direction: random, but never three the same way in a row.
        var dir = if (rng.nextBoolean()) 1f else -1f
        if (abs(spDirRun) >= 2 && kotlin.math.sign(dir) == kotlin.math.sign(spDirRun.toFloat())) dir = -dir
        spDirRun = if (dir > 0f) (if (spDirRun > 0) spDirRun + 1 else 1) else (if (spDirRun < 0) spDirRun - 1 else -1)
        spDir = dir
        // History-aware variant choice: what happened last time is less likely now.
        val pRun = (if (playful) 0.6f else 0.45f) * (if (spLastRun) 0.55f else 1.25f)
        val run = forceRun ?: (rng.nextFloat() < pRun)
        spRun = if (run) rr(0.3f, 0.44f) else 0f
        spRunDeg = if (run) rr(3f, 7f) else 0f
        spCrouch = if (run) rr(0.015f, 0.035f) else 0f
        // engine14.1: weighty spin, 360° in ~1.8–1.98s, peak ~280–320°/s.
        spMain = if (playful) rr(2.05f, 2.15f) else rr(2.1f, 2.3f) // engine17: longer glide, same weight
        spFollow = rr(0.045f, 0.08f) * (1.6f - 0.3f * spMain) // faster spin → a bit more drift
        spFollowOut = rr(0.2f, 0.3f); spFollowBack = rr(0.5f, 0.75f)
        val pShake = (0.18f + 0.22f * (if (playful) 1f else 0f) + 0.15f * (1f - temper.energy)) * (if (spLastShake) 0.45f else 1.2f)
        spShake = forceShake ?: (rng.nextFloat() < pShake)
        spShakeDelay = rr(0.05f, 0.25f); spShakeAmp = rr(3.5f, 6.5f); spShakeHz = rr(4f, 5.2f); spShakeCycles = rr(2f, 3f)
        // Never the same way twice in a row: if the variant would repeat, change one thing.
        if (forceRun == null && forceShake == null && run == spLastRun && spShake == spLastShake && rng.nextFloat() < 0.8f) {
            if (rng.nextBoolean()) { spShake = !spShake } else {
                val r2 = !run
                spRun = if (r2) rr(0.3f, 0.44f) else 0f; spRunDeg = if (r2) rr(3f, 7f) else 0f; spCrouch = if (r2) rr(0.015f, 0.035f) else 0f
            }
        }
        spPeakAt = rr(0.24f, 0.36f)
        // Engine16: the axis / path varies (never the same path twice in a row).
        spPath = forcePath ?: run {
            val opts = SpinPath.entries.map { it to it.weight * (if (it == spLastPath) 0.1f else if (it in spPathHist) 0.6f else 1f) }
            var x = rng.nextFloat() * opts.sumOf { it.second.toDouble() }.toFloat()
            opts.firstOrNull { x -= it.second; x <= 0f }?.first ?: SpinPath.Turn
        }
        spLastPath = spPath; spPathHist.addLast(spPath); while (spPathHist.size > 3) spPathHist.removeFirst()
        forcePath = null
        // Flips and cartwheels carry more visible mass: a touch slower.
        if (spPath == SpinPath.Flip) spMain *= rr(0.94f, 1.0f).let { 1f / it }.coerceAtMost(1.06f)
        spLastRun = spRun > 0f; spLastShake = spShake
        forceRun = null; forceShake = null
        spT = 0f
        log("spin path=$spPath dir=${dir.toInt()} runUp=${spRun > 0f} shake=$spShake main=${"%.2f".format(spMain)}")
    }

    private var spPeakAt = 0.3f
    private val SPIN_RISE = 0.22f
    private val SPIN_GLIDE = 0.4f

    /**
     * Engine17: normalised turn progress with mass. Velocity follows a smooth asymmetric
     * bump v(u) ∝ u^1.5·(1-u)^3.2 — eases out of rest, peaks around a third of the way,
     * then a long frictional glide to a dead stop. No constant-speed (linear) plateau.
     */
    private fun spinCurve(u: Float, @Suppress("UNUSED_PARAMETER") a0: Float): Float {
        if (u <= 0f) return 0f
        if (u >= 1f) return 1f
        val x = u * (SPIN_CDF.size - 1)
        val i = x.toInt().coerceAtMost(SPIN_CDF.size - 2)
        val f = x - i
        return SPIN_CDF[i] + (SPIN_CDF[i + 1] - SPIN_CDF[i]) * f
    }

    private val SPIN_CDF: FloatArray = run {
        val n = 512
        val v = DoubleArray(n + 1) { k -> val u = k / n.toDouble(); Math.pow(u, 1.5) * Math.pow(1 - u, 3.2) }
        val c = FloatArray(n + 1); var acc = 0.0
        for (k in 1..n) { acc += (v[k] + v[k - 1]) * 0.5; c[k] = acc.toFloat() }
        for (k in 0..n) c[k] /= acc.toFloat()
        c
    }

    /** Advances the spin timeline. */
    private fun spinUpdate(dt: Float) {
        var prev = spinYaw
        spinCrouchK = 0f; spinFollowX = 0f; shakeYaw = 0f
        if (spT >= 0f) {
            spT += dt
            val tRun = spRun; val tMain = tRun + spMain
            val tFol = tMain + spFollowOut + spFollowBack
            val tShake = tFol + spShakeDelay
            val shakeDur = spShakeCycles / spShakeHz
            when {
                spT < tRun -> {
                    val u = MotionVocab.easeInOutSine(spT / tRun)
                    spinYaw = -spDir * spRunDeg * u
                    spinCrouchK = spCrouch * u
                }
                spT < tMain -> {
                    val u = ((spT - tRun) / spMain).coerceIn(0f, 1f)
                    // Starts from rest, peaks early, long smooth deceleration to a dead stop.
                    val c = spinCurve(u, spPeakAt)
                    val from = -spDir * spRunDeg
                    spinYaw = from + (spDir * 360f - from) * c
                    // Crouch releases quickly as the turn begins.
                    spinCrouchK = spCrouch * (1f - MotionVocab.easeOutCubic(((spT - tRun) / 0.15f).coerceIn(0f, 1f)))
                }
                else -> {
                    if (abs(spinYaw) > 1f) { spinYaw = 0f; prev = 0f; log("spin stop") } // 360° ≡ rest pose
                    val ft = spT - tMain
                    spinFollowX = spDir * spFollow * when {
                        ft < spFollowOut -> MotionVocab.easeOutCubic(ft / spFollowOut)
                        ft < spFollowOut + spFollowBack -> 1f - MotionVocab.easeInOutSine((ft - spFollowOut) / spFollowBack)
                        else -> 0f
                    }
                    // Engine17.1: the shake-off is the one shared head shake.
                    if (spShake && spT >= tShake) { wakeShake(spShakeAmp, cause = "spin shake-off"); spT = -1f }
                    else if (!spShake && spT >= tFol) { spT = -1f; beginRecover() }
                }
            }
        }
        spinV = if (dt > 0f) (spinYaw - prev) / dt else 0f
    }
    private var wiggleU = 1f; private var wiggleA = 0f; private var wiggleDur = 0.9f
    private var stretchU = 1f; private var stretchDur = 2.6f
    private var driftU = 1f; private var driftDur = 4f; private var driftDepth = 9f
    private var peekRoll = 0f; private var peekRollV = 0f; private var peekRollT = 0f
    private var peekYaw = 0f; private var peekYawV = 0f; private var peekYawT = 0f
    private var peekLeft = 0f
    private var peekDelay = 0f; private var peekPendRoll = 0f; private var peekPendYaw = 0f
    private var pillTiltIn = -1f
    private val scan = ArrayList<FloatArray>() // [startAt, x, y, hold]
    private var scanT = 0f
    private var upRightT = 0f
    private var upK = 0f

    /** Harness: let a moment fire on the next check / play one now. */
    internal fun debugMomentsReady() = moments.debugReady()
    internal fun debugMoment(m: Moment) { moments.note(m); playMoment(m) }

    private fun playMoment(m: Moment) {
        fun rr(a: Float, b: Float) = a + (b - a) * rng.nextFloat()
        val side = if (rng.nextBoolean()) 1f else -1f
        log("moment $m")
        when (m) {
            Moment.Spin -> {
                val playful = temper.playful(personality) || temper.valence > 0.2f
                startSpin(playful)
                if (playful && rng.nextFloat() < 0.5f) setReaction("Happy", rr(1.6f, 2.4f), ExpressionDirector.Style.Soft, cause = "spin joy")
            }
            Moment.Scan -> {
                val n = 2 + rng.nextInt(3)
                var at = 0f
                var x = side * rr(0.35f, 0.6f)
                scan.clear(); scanT = 0f
                if (rng.nextFloat() < 0.3f) blink.request(minGap = 0f) // blinks often ride big gaze shifts
                // Engine15: a slow pan across related points (one direction, small steps),
                // not a teleport between random spots.
                var y = rr(-0.1f, 0.3f)
                val stepX = -side * rr(0.22f, 0.34f); val stepY = rr(-0.06f, 0.06f)
                repeat(n) { k ->
                    val hold = rr(0.7f, 1.5f)
                    scan += floatArrayOf(at, x, y, hold)
                    at += hold + rr(0.1f, 0.25f)
                    x = (x + stepX).coerceIn(-0.85f, 0.85f); y = (y + stepY).coerceIn(-0.3f, 0.45f)
                    if (k == n - 1) scan += floatArrayOf(at, rr(-0.06f, 0.06f), rr(-0.02f, 0.05f), rr(1.5f, 3f))
                }
            }
            Moment.Peek -> {
                // Eyes go first; the lean follows ~150 ms later (critically damped, no bounce).
                peekPendRoll = -side * rr(5f, 8f); peekPendYaw = side * rr(5f, 8f); peekDelay = rr(0.12f, 0.22f)
                peekLeft = rr(1.2f, 2f) + peekDelay
                gaze.focus(side * rr(0.55f, 0.75f), rr(0f, 0.25f), peekLeft, priority = 2)
                if (rng.nextFloat() < 0.5f) accent(FaceAccent.Curious, peekLeft)
            }
            Moment.Stretch -> {
                stretchU = 0f; stretchDur = rr(2.3f, 3f)
            }
            Moment.SleepyDrift -> {
                driftU = 0f; driftDur = rr(3.5f, 5f); driftDepth = rr(7f, 11f)
            }
            Moment.NoticePill -> {
                val pill = targetCenter(AvatarStage.Target.ActivityPill)
                val (gx, gy) = pill?.let { lookAt(it) } ?: (0.3f to 0.5f)
                gaze.focus(gx, gy, rr(1.3f, 2.2f), priority = 2)
                if (rng.nextFloat() < 0.45f) pillTiltIn = rr(0.25f, 0.45f) // head tilts after the eyes land
                if (rng.nextFloat() < 0.35f) accent(FaceAccent.Glee, rr(0.9f, 1.4f))
            }
            Moment.Wiggle -> {
                wiggleU = 0f; wiggleA = rr(3f, 5f) * side; wiggleDur = rr(0.7f, 0.95f)
                remember("nice")
                setReaction("Happy", rr(1.3f, 2f), ExpressionDirector.Style.Snap, cause = "nice message")
            }
            Moment.SmugNod -> {
                accent(FaceAccent.Smug, rr(1.2f, 1.8f))
                director.gesture(MotionDirector.Gesture.Nod)
            }
        }
    }

    private fun momentsUpdate(dt: Float) {
        val calm = director.lifecycle == MarkLifecycle.Idle && reaction == null && social == null &&
            freezeLeft <= 0f && takeT < 0f && scan.isEmpty() && driftU >= 1f && stretchU >= 1f &&
            spT < 0f && abs(spinYaw) < 0.5f && recoverT <= 0f && sinceStimulus > 3f
        sinceStimulus += dt
        recoverT = (recoverT - dt).coerceAtLeast(0f)
        // Engine16: spontaneous idle beats come from the episode planner (see aliveUpdate).
        moments.update(dt, temper, personality, false, quietSec, false)
        val h = dt.coerceAtMost(1f / 30f)
        spinUpdate(dt)
        channelsUpdate(dt)
        // Peek channel (critically damped lean).
        if (peekDelay > 0f) { peekDelay -= dt; if (peekDelay <= 0f) { peekRollT = peekPendRoll; peekYawT = peekPendYaw } }
        if (peekLeft > 0f) { peekLeft -= dt; if (peekLeft <= 0f) { peekRollT = 0f; peekYawT = 0f } }
        if (pillTiltIn > 0f) { pillTiltIn -= dt; if (pillTiltIn <= 0f) director.gesture(MotionDirector.Gesture.Tilt) }
        val om = 7f
        peekRollV += (om * om * (peekRollT - peekRoll) - 2f * om * peekRollV) * h; peekRoll += peekRollV * h
        peekYawV += (om * om * (peekYawT - peekYaw) - 2f * om * peekYawV) * h; peekYaw += peekYawV * h
        // Scan timeline.
        if (scan.isNotEmpty()) {
            val t0 = scanT; scanT += dt
            val it = scan.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (t0 <= p[0] && scanT > p[0]) { gaze.focus(p[1], p[2], p[3], priority = 2, travel = if (p[0] > 0f) 0.7f else 0f); it.remove() }
            }
        }
        if (wiggleU < 1f) wiggleU = (wiggleU + dt / wiggleDur).coerceAtMost(1f)
        if (stretchU < 1f) {
            val before = stretchU
            stretchU = (stretchU + dt / stretchDur).coerceAtMost(1f)
            if (before < 0.85f && stretchU >= 0.85f) {
                blink.request(minGap = 0f)
                if (rng.nextFloat() < 0.5f && sighU >= 1f) sighU = 0f // a relaxed exhale to finish
            }
        }
        if (driftU < 1f) {
            val before = driftU
            driftU = (driftU + dt / driftDur).coerceAtMost(1f)
            if (before < 0.82f && driftU >= 0.82f) {
                // Caught itself: perk up.
                blink.request(double = rng.nextFloat() < 0.35f, minGap = 0f)
                accent(FaceAccent.WakeSquint, 0.7f, force = true)
            }
        }
    }

    private class FrozenLook(val body: BodyPose, val hx: Float, val hy: Float, val lx: Float, val ly: Float)
    private var frozen: FrozenLook? = null
    private var freezeLeft = 0f
    private var sighU = 1f
    private var sighIn = 40f
    private var fidgetIn = 12f
    private var fidgetYaw = 0f; private var fidgetYawV = 0f; private var fidgetYawT = 0f
    private var fidgetRoll = 0f; private var fidgetRollV = 0f; private var fidgetRollT = 0f
    private class Pending(var left: Float, val pc: Percept, val handler: ((Response) -> Unit)?, val allowNone: Boolean)
    private val pending = ArrayList<Pending>()
    private var takeT = -1f
    private var takeX = 0f
    private var takeY = 0f

    /** Debug / harness: what the behaviour layer perceived and decided ("t,what"). */
    internal val behaviorLog = ArrayList<String>()
    private fun log(msg: String) { if (AvatarDebugMetrics.enabled) behaviorLog += "%.2f,%s".format(time, msg) }

    /** Harness: play a specific response now (e.g. the startle clip). */
    internal fun debugPerform(r: Response) = perform(r, Stimulus("debug", 0f, 0f, 1f))

    /** Harness: present a stimulus (direction in gaze units). */
    internal fun debugStimulus(kind: String, dirX: Float, dirY: Float, salience: Float, inView: Boolean = true) =
        stimulus(Stimulus(kind, dirX, dirY, salience, inView), null)

    /**
     * Route a happening through perception: maybe missed, otherwise it lands after a
     * human latency and a response is picked from the repertoire ([handler] gets it).
     */
    private fun stimulus(st: Stimulus, handler: ((Response) -> Unit)?, allowNone: Boolean = true) {
        sinceStimulus = 0f
        val pc = attention.perceive(st, gaze.eyeX, gaze.eyeY, temper, personality)
        if (pc == null) { log("missed ${st.kind} sal=%.2f".format(st.salience)); return }
        temper.onEvent(st.salience * 0.5f)
        log("will-notice ${st.kind} in %.2fs%s".format(pc.delay, if (pc.lateNotice) " (late)" else ""))
        pending += Pending(pc.delay, pc, handler, allowNone)
    }

    private fun humanUpdate(dt: Float, live: Boolean) {
        val busy = MotionDirector.isBusy(director.lifecycle)
        temper.valence = mood.valence
        temper.update(dt, personality, busy, quietSec, if (drowsy) 0.4f else 0f)
        attention.absorption = when {
            followLeft > 0f -> 0.7f
            director.lifecycle == MarkLifecycle.Thinking -> 0.75f
            director.lifecycle == MarkLifecycle.Streaming -> 0.6f
            director.lifecycle == MarkLifecycle.Working -> 0.65f
            gaze.kind == "stare" || gaze.kind == "think" -> 0.5f
            else -> 0.3f
        } - 0.15f * temper.boredom
        director.activity = temper.activity
        director.playful = temper.playful(personality)
        aliveness.activity = temper.activity
        gaze.playful = director.playful
        if (!live) { pending.clear(); return }

        val it = pending.iterator()
        val landed = ArrayList<Pending>()
        while (it.hasNext()) {
            val pd = it.next()
            pd.left -= dt
            if (pd.left <= 0f) { it.remove(); landed += pd }
        }
        for (pd in landed) {
            var r = repertoire.choose(pd.pc, temper, personality)
            if (!pd.allowNone && r == Response.None) r = Response.GlanceBlink
            log("noticed ${pd.pc.stim.kind} -> $r")
            val h = pd.handler
            if (h != null) h(r) else perform(r, pd.pc.stim)
        }
        // Double-take timeline: glance … start back … snap back and hold.
        if (takeT >= 0f) {
            val t0 = takeT
            takeT += dt
            if (t0 < 0.45f && takeT >= 0.45f) gaze.focus(takeX * 0.2f, takeY * 0.2f, 0.3f, priority = 4)
            if (t0 < 0.7f && takeT >= 0.7f) {
                gaze.focus(takeX, takeY, 1.6f + rng.nextFloat(), priority = 4)
                blink.request(minGap = 0f)
                if (rng.nextFloat() < 0.5f) setReaction("Surprised", 0.7f, ExpressionDirector.Style.Snap, cause = "double take")
                takeT = -1f
            }
        }
        if (freezeLeft > 0f) {
            freezeLeft -= dt
            if (freezeLeft <= 0f) blink.request(minGap = 0f) // thaw with a blink
        }
        if (sighU < 1f) sighU = (sighU + dt / MotionVocab[Action.Sigh].dur).coerceAtMost(1f)
        sighIn -= dt
        if (sighIn <= 0f) {
            sighIn = 25f + rng.nextFloat() * 40f
            if (!busy && (temper.boredom > 0.4f || temper.energy < 0.3f) && rng.nextFloat() < 0.5f) {
                sighU = 0f; log("sigh")
            }
        }
        fidgetIn -= dt
        if (fidgetIn <= 0f) {
            fidgetIn = (10f + rng.nextFloat() * 18f) / (0.4f + temper.activity)
            val a = temper.amplitude
            fidgetYawT = (rng.nextFloat() * 2f - 1f) * 3.5f * a
            fidgetRollT = (rng.nextFloat() * 2f - 1f) * 2.5f * a
            log("fidget")
        }
        if (pendingWiggleIn >= 0f) { pendingWiggleIn -= dt; if (pendingWiggleIn < 0f) playMoment(Moment.Wiggle) }
        momentsUpdate(dt)
    }

    /** Execute a picked response to [st]. */
    private fun perform(r: Response, st: Stimulus) {
        fun hold(a: Float, b: Float) = a + (b - a) * rng.nextFloat()
        when (r) {
            Response.None -> Unit
            Response.EyesOnly -> gaze.focus(st.dirX * 0.9f, st.dirY * 0.9f, hold(0.8f, 2.2f), priority = 2, withHead = false)
            Response.Glance -> gaze.focus(st.dirX, st.dirY, hold(1.2f, 2.8f), priority = 2)
            Response.GlanceBlink -> { gaze.focus(st.dirX, st.dirY, hold(1f, 2.4f), priority = 2); blink.request(minGap = 0.3f) }
            Response.Acknowledge -> { gaze.focus(st.dirX, st.dirY, hold(1.2f, 2.2f), priority = 2); director.gesture(MotionDirector.Gesture.Nod) }
            Response.Smile -> { gaze.focus(st.dirX, st.dirY, hold(1f, 2f), priority = 2); accent(FaceAccent.Glee, hold(1f, 1.8f)) }
            Response.CuriousTilt -> {
                gaze.focus(st.dirX, st.dirY, hold(1.4f, 2.4f), priority = 2)
                director.gesture(MotionDirector.Gesture.Tilt)
                // Something odd caught its attention: now and then a brief confused look.
                val r = rng.nextFloat()
                if (r < 0.08f && reaction == null) setReaction("confused", hold(0.8f, 1.2f), cause = "odd stimulus")
                else if (r < 0.6f) accent(FaceAccent.Curious, hold(1f, 1.6f))
            }
            Response.Startle -> {
                director.gesture(MotionDirector.Gesture.Recoil)
                accent(FaceAccent.Startled, 0.75f, force = true)
                gaze.focus(st.dirX, st.dirY, hold(1.2f, 2f), priority = 3)
            }
            Response.Freeze -> {
                freezeLeft = hold(1.1f, 1.7f)
                accent(FaceAccent.Startled, freezeLeft + 0.2f, force = true)
            }
            Response.DoubleTake -> {
                takeX = st.dirX.coerceIn(-0.9f, 0.9f); takeY = st.dirY.coerceIn(-0.6f, 0.6f)
                takeT = 0f
                gaze.focus(takeX * 0.8f, takeY * 0.8f, 0.45f, priority = 4)
            }
            Response.SlowBlink -> blink.request(minGap = 0.4f)
            Response.Sigh -> if (sighU >= 1f) sighU = 0f
        }
    }

    private fun lerpFaceParams(a: FaceParams, b: FaceParams, k: Float): FaceParams {
        fun l(x: Float, y: Float) = x + (y - x) * k
        fun e(p: EyeShape, q: EyeShape) = EyeShape(
            sx = l(p.sx, q.sx), sy = l(p.sy, q.sy), dx = l(p.dx, q.dx), dy = l(p.dy, q.dy), rot = l(p.rot, q.rot),
            upper = l(p.upper, q.upper), lower = l(p.lower, q.lower), slant = l(p.slant, q.slant),
        )
        return FaceParams(e(a.left, b.left), e(a.right, b.right))
    }

    private fun lerpFace(f: FaceParams, w: Float): FaceParams {
        if (w <= 0f) return f
        fun e(p: EyeShape) = EyeShape(
            sx = p.sx + (1f - p.sx) * w, sy = p.sy + (1f - p.sy) * w,
            dx = p.dx * (1f - w), dy = p.dy * (1f - w), rot = p.rot * (1f - w),
            upper = p.upper + (1f - p.upper) * w, lower = p.lower + (1f - p.lower) * w,
            slant = p.slant * (1f - w),
        )
        return FaceParams(e(f.left), e(f.right))
    }

    /** Play a parametric look (with a cooldown so faces don't pile up) and let neighbours see it. */
    private fun accent(a: FaceAccent, sec: Float, force: Boolean = false): Boolean {
        if (!force && (accentCooldown > 0f || rig.active)) return false
        rig.play(a, sec)
        accentCooldown = sec + 1.5f + rng.nextFloat() * 2f
        stage?.let { st ->
            st.entry(stageId)?.let { me -> st.emit(AvatarStage.EventKind.AvatarAccent, at = me.center, source = stageId, extra = a.ordinal) }
        }
        return true
    }

    private fun targetCenter(kind: AvatarStage.Target): Offset? = stage?.target(kind)?.center

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
        // Busy phases rotate through a varied, non-repeating set of faces instead of
        // holding one "thinking" face the whole time.
        if (MotionDirector.isBusy(life)) return aliveness.busyFace
        return MarkLifecycleMapper.defaultExpression(life)
    }

    private fun playBeat(b: AlivenessEngine.Beat) {
        when (b) {
            is AlivenessEngine.Beat.Gesture -> if (b.g == MotionDirector.Gesture.Spin) {
                if (director.lifecycle != MarkLifecycle.Idle || recently("nice", 12f) || pokeCount > 0) trySpin("lively beat")
            } else director.gesture(b.g)
            is AlivenessEngine.Beat.Accent -> accent(b.a, b.sec, force = b.a == FaceAccent.Startled)
            is AlivenessEngine.Beat.Face -> if (reaction == null) setReaction(b.expr, b.sec, cause = "feeling (tone/fatigue)")
            is AlivenessEngine.Beat.Blink -> blink.request(minGap = 0.8f)
            is AlivenessEngine.Beat.DoubleBlink -> blink.request(double = true, minGap = 0.8f)
            is AlivenessEngine.Beat.Glance -> {
                if (gaze.focused) return
                val real = if (b.kind == "read") stage?.target(AvatarStage.Target.StreamingText)?.let { r ->
                    Offset(r.left + r.width * (0.15f + 0.7f * rng.nextFloat()), r.bottom - minOf(r.height, 120f) * rng.nextFloat())
                } else null
                if (real != null) {
                    val (gx, gy) = lookAt(real)
                    gaze.focus(gx, gy, b.sec, priority = 0)
                } else gaze.focus(b.x, b.y, b.sec, priority = 0)
            }
        }
    }

    // ---------------------------------------------------- engine15: causal trains

    /** One delayed beat of a micro-story (notice → react → settle). */
    private class TrainStep(var at: Float, val what: String, val act: () -> Unit)
    private val train = ArrayList<TrainStep>()
    /** Recent-event memory: what happened, and when (seconds of [time]). */
    private val memory = ArrayDeque<Pair<String, Float>>()
    private fun remember(what: String) { memory.addLast(what to time); while (memory.size > 8) memory.removeFirst() }
    private fun recently(what: String, sec: Float) = memory.any { it.first == what && time - it.second < sec }

    private fun tell(story: String, vararg steps: Pair<Float, String>, acts: List<() -> Unit>) {
        train.clear()
        steps.forEachIndexed { i, (d, w) -> train += TrainStep(d, "$story/$w", acts[i]) }
        log("story $story: " + steps.joinToString(" → ") { "%.2f %s".format(it.first, it.second) })
    }

    private fun trainUpdate(dt: Float) {
        if (train.isEmpty()) return
        train.forEach { it.at -= dt }
        while (train.isNotEmpty() && train[0].at <= 0f) {
            val st = train.removeAt(0)
            log("beat ${st.what}")
            st.act()
        }
    }

    /**
     * The idle attention episode landed on something. React after a perception delay, and
     * only with a reason: notice → (curious) → maybe pleased + nod; or acknowledge it.
     */
    private fun attentionEvent(ev: String) {
        fun rr(a: Float, b: Float) = a + (b - a) * rng.nextFloat()
        val free = director.lifecycle == MarkLifecycle.Idle && !drowsy && reaction == null &&
            social == null && train.isEmpty() && spT < 0f && recoverT <= 0f
        if (!free) return
        remember(ev)
        // The eyes are only just leaving: arrival (~0.25 s) plus perception delay.
        val d = rr(0.45f, 0.75f)
        when (ev) {
            "notice" -> {
                val r = rng.nextFloat()
                when {
                    mood.valence > 0.15f && r < 0.3f && !recently("face:Happy", 30f) -> {
                        val c = d + rr(0.7f, 1.0f)
                        tell("notice→pleased", d to "curious", c to "Happy", c + 0.25f to "nod", acts = listOf(
                            { accent(FaceAccent.Curious, c - d + 0.3f) },
                            { setReaction("Happy", rr(1.2f, 1.8f), ExpressionDirector.Style.Soft, cause = "thought completes: likes what it noticed") },
                            { director.gesture(MotionDirector.Gesture.Nod) },
                        ))
                    }
                    r < 0.65f -> tell("notice→curious", d to "curious", acts = listOf({
                        accent(FaceAccent.Curious, rr(0.9f, 1.5f))
                        if (rng.nextFloat() < 0.3f) director.gesture(MotionDirector.Gesture.Tilt)
                    }))
                    else -> tell("notice→blink", d to "blink", acts = listOf({ blink.request(minGap = 0.4f) }))
                }
            }
            "ack" -> tell("acknowledge", d to "nod", d + 0.35f to "blink", acts = listOf(
                { director.gesture(MotionDirector.Gesture.Nod) },
                { blink.request(minGap = 0.3f) },
            ))
        }
    }

    private val lowFaces = setOf("Bored", "Sleeping", "Sad", "Eyes closed")
    private val brightFaces = setOf("Happy", "Group 3", "playful")

    private fun setReaction(e0: String, sec: Float, style: ExpressionDirector.Style? = null, cause: String = "unspecified") {
        val from = currentExpressionTarget()
        // Never cut low → bright (bored → happy): go through noticing it first.
        if (from in lowFaces && e0 in brightFaces) {
            fun rr(a: Float, b: Float) = a + (b - a) * rng.nextFloat()
            val n = rr(0.3f, 0.45f); val c = n + rr(0.35f, 0.5f); val t = c + rr(0.45f, 0.7f)
            setReactionRaw("Neutral", t + 0.1f, ExpressionDirector.Style.Blink, "notice ($cause)", from)
            tell("bridge $from→$e0", n to "curious", t to e0, acts = listOf(
                { accent(FaceAccent.Curious, c - n + 0.5f) },
                { setReactionRaw(e0, sec, style, cause, "Neutral") },
            ))
            return
        }
        // Bright → low settles through a beat of neutral (the smile fades first).
        if (from in brightFaces && e0 in lowFaces && e0 != "Sleeping") {
            setReactionRaw("Neutral", 1.4f, ExpressionDirector.Style.Gradual, "settle ($cause)", from)
            tell("settle $from→$e0", 1.2f to e0, acts = listOf({ setReactionRaw(e0, sec, style, cause, "Neutral") }))
            return
        }
        setReactionRaw(e0, sec, style, cause, from)
    }

    private fun setReactionRaw(e0: String, sec: Float, style: ExpressionDirector.Style?, cause: String, from: String) {
        log("expr $from→$e0 cause=$cause")
        remember("face:$e0")
        // Confused has a left/right variant: the raised brow goes on the side it is looking
        // toward; when looking ahead, either side (not the same one every time).
        val e = if (e0 != "confused") e0 else {
            val hx = frame.headLookX
            val left = when { hx < -0.12f -> true; hx > 0.12f -> false; else -> !lastConfusedLeft || rng.nextFloat() < 0.35f }
            lastConfusedLeft = left
            if (left) "confused left" else "confused"
        }
        reaction = e
        reactionLeft = sec
        reactionStyle = style ?: when (e) {
            "Surprised" -> ExpressionDirector.Style.Snap
            "Bored", "Sleeping" -> ExpressionDirector.Style.Gradual
            else -> null
        }
    }
    private var reactionStyle: ExpressionDirector.Style? = null
    private var lastConfusedLeft = false

    private fun aliveUpdate(dt: Float, nowMs: Long) {
        lastSpinAgo += dt
        trainUpdate(dt)
        accentCooldown -= dt
        flingCooldown -= dt
        eventCooldown -= dt
        mirror?.let { (a, sec) ->
            mirrorIn -= dt
            if (mirrorIn <= 0f) { mirror = null; accent(a, sec) }
        }
        if (forgiveIn > 0f) {
            forgiveIn -= dt
            if (forgiveIn <= 0f) {
                // Can't stay cross for long: a blink and a small smile.
                blink.request(minGap = 0.3f)
                accent(FaceAccent.Glee, 1.4f, force = true)
                mood.nudge(0.25f, 0f)
            }
        }
        // ---- aliveness engine: spontaneous, context-aware beats ----
        val engineCalm = social == null && reaction == null && followLeft <= 0f && !drowsy && train.isEmpty()
        aliveness.update(dt, director.lifecycle, mood.valence, mood.arousal, engineCalm)?.let { playBeat(it) }
        aliveness.takeMoodNudge()?.let { (v, a) -> mood.nudge(v, a) }
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
                setReaction(e, sec, cause = "queued")
                reactionQueue = null
            }
        }

        val calm = director.lifecycle == MarkLifecycle.Idle

        // Reply finished → a little happy beat (not every time).
        if (director.finishedBusy) {
            director.finishedBusy = false
            val r = rng.nextFloat()
            if (r < 0.14f && mood.valence > 0.15f && lastSpinAgo > 50f && trySpin("reply finished, good mood")) {
                remember("nice")
            } else if (r < 0.6f) {
                remember("nice")
                setReaction("Happy", 1.6f + rng.nextFloat(), cause = "reply finished")
                val g = rng.nextFloat()
                when {
                    g < 0.35f -> director.gesture(MotionDirector.Gesture.Hop)
                    g < 0.6f -> director.gesture(MotionDirector.Gesture.Nod)
                }
            } else {
                if (rng.nextFloat() < 0.25f && moments.allow(Moment.SmugNod, 50f)) {
                    moments.note(Moment.SmugNod); playMoment(Moment.SmugNod)
                } else aliveness.onReplyFinished()?.let { playBeat(it) } ?: blink.request()
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

        // Engine16: no spins while idle. Instead, the episode planner (rare, causal beats).
        episodeUpdate(dt)

        // Drowsy: now and then nod off a little, then catch yourself.
        if (drowsy) {
            droopIn -= dt
            if (droopIn <= 0f) {
                droopIn = 9f + rng.nextFloat() * 9f
                if (restPhase == 0 && rng.nextFloat() < 0.45f && director.gesture(MotionDirector.Gesture.Droop)) {
                    perkIn = 2.4f + rng.nextFloat() * 0.8f
                }
            }
        }

        // Engine15: no random mood faces. The face changes on its own only with a reason.
        moodIn -= dt
        if (moodIn <= 0f) {
            moodIn = 8f + rng.nextFloat() * 6f
            if (!drowsy && reaction == null && social == null && train.isEmpty()) {
                val v = mood.valence; val a = mood.arousal
                when {
                    // Boredom settles after a long stillness.
                    quietSec > 50f && aliveness.boredom > 0.45f && a < 0.5f && !recently("face:Bored", 60f) ->
                        setReaction("Bored", 3f + rng.nextFloat() * 3f, ExpressionDirector.Style.Gradual, cause = "boredom settles after stillness")
                    // Afterglow: a recent nice thing still warms the face, once.
                    v > 0.35f && (recently("nice", 25f)) && !recently("face:Happy", 30f) ->
                        setReaction("Happy", 1.6f + rng.nextFloat(), ExpressionDirector.Style.Gradual, cause = "afterglow of a nice moment")
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
            setReaction("confused", 1.1f + rng.nextFloat() * 0.5f, cause = "skeptic of neighbour")
            if (rng.nextFloat() < 0.7f) director.gesture(MotionDirector.Gesture.Tilt)
        }
        if (s.looking) contactSec += dt
        if (s.t >= s.end) {
            social = null
            // Held eye contact a moment: sometimes a shy look away.
            if (contactSec > 1f && s.skepticAt < 0f && rng.nextFloat() < 0.3f) {
                accent(FaceAccent.Shy, 1.4f)
                gaze.focus(if (lookAt(other.center).first > 0f) -0.45f else 0.45f, 0.4f, 1.0f, priority = 2)
                contactSec = 0f
                return
            }
            contactSec = 0f
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
        targetCenter(AvatarStage.Target.Composer)?.let { return it }
        val me = stage?.entry(stageId) ?: return null
        if (me.windowH <= 0f) return null
        return Offset(me.windowW * 0.5f, me.windowH * 0.93f)
    }

    // ------------------------------------------------------- composer (typing)

    /** Where the caret roughly is: the bottom input, left → right as the line grows. */
    private fun caretPoint(): Offset? {
        val st = stage ?: return null
        val perLine = CARET_CHARS_PER_LINE
        val frac = (st.composerLineLen % perLine) / perLine.toFloat()
        st.target(AvatarStage.Target.Composer)?.let { r ->
            // The real input field: caret runs left → right inside it.
            return Offset(r.left + r.width * (0.08f + 0.8f * frac), r.center.y)
        }
        val me = st.entry(stageId) ?: return null
        if (me.windowW <= 0f || me.windowH <= 0f) return null
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
                if (rng.nextFloat() < 0.45f) setReaction("Surprised", 0.55f, cause = "typing started")
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
        // If the sender's avatar is on screen below, look at it (a turn-taking look).
        val sender = me?.let { m ->
            stage?.neighbors(stageId, max(m.windowW, m.windowH))?.firstOrNull { it.center.y > m.center.y + m.radius }
        }
        val at = e.at ?: sender?.center ?: targetCenter(AvatarStage.Target.Composer)
            ?: me?.let { Offset(it.windowW * 0.72f, it.windowH * 0.8f) } ?: return
        val (gx, gy) = lookAt(at)
        gaze.focus(gx, gy, 0.6f + rng.nextFloat() * 0.3f, priority = 2)
        if (rng.nextFloat() < 0.5f) blink.request(minGap = 0.4f)
        val calm = director.lifecycle == MarkLifecycle.Idle || director.lifecycle == MarkLifecycle.Listening
        if (calm && reaction == null && rng.nextFloat() < 0.3f) setReaction("Happy", 0.7f, cause = "message arrived")
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
        var any = false
        for (e in evs) {
            // The avatar's own reply tone is not perceived, it is felt: straight through.
            if (e.kind == AvatarStage.EventKind.ReplyTone) { legacyEvent(e); continue }
            // Its own face changes are not something it "notices" (that read as a double take).
            if (e.kind == AvatarStage.EventKind.AvatarAccent && e.source == stageId) continue
            any = true
            val (dx, dy) = e.at?.let { lookAt(it) } ?: (0f to 0.6f)
            val st = Stimulus(e.kind.name, dx, dy, salienceFor(e.kind), direct = e.kind == AvatarStage.EventKind.AppResumed || e.kind == AvatarStage.EventKind.Error)
            stimulus(st, { r ->
                when {
                    // Felt, not seen (errors, app resume): always registers, own handling.
                    st.direct -> legacyEvent(e)
                    r == Response.None -> Unit
                    r == Response.Startle || r == Response.Freeze || r == Response.DoubleTake -> { aliveness.onEvent(e); perform(r, st) }
                    else -> legacyEvent(e)
                }
            }, allowNone = !st.direct)
        }
        // Engine16: only outside happenings count as activity (its own accents don't keep it awake).
        return any
    }

    private fun salienceFor(k: AvatarStage.EventKind): Float = when (k) {
        AvatarStage.EventKind.Error -> 1f
        AvatarStage.EventKind.AppResumed -> 0.9f
        AvatarStage.EventKind.MessageSent -> 0.75f
        AvatarStage.EventKind.Deleted -> 0.7f
        AvatarStage.EventKind.MessageArrived -> 0.6f
        AvatarStage.EventKind.PillExpanded -> 0.6f
        AvatarStage.EventKind.Fling -> 0.5f
        AvatarStage.EventKind.KeyboardOpened, AvatarStage.EventKind.MenuOpened, AvatarStage.EventKind.Regenerated -> 0.5f
        AvatarStage.EventKind.UserTyping -> 0.5f
        AvatarStage.EventKind.AvatarAccent -> 0.4f
        AvatarStage.EventKind.Copied -> 0.4f
        AvatarStage.EventKind.Scroll -> 0.3f
        else -> 0.4f
    }

    /** The pre-engine11 handling of one stage event, run once it has been perceived. */
    private fun legacyEvent(e: AvatarStage.Event) {
        val calm = director.lifecycle == MarkLifecycle.Idle || director.lifecycle == MarkLifecycle.Listening
        run {
            if (e.kind == AvatarStage.EventKind.MessageSent) {
                onMessageSent(e)
                return
            }
            if (onUiEvent(e, calm)) return
            if (!calm || social != null || eventCooldown > 0f) return
            when (e.kind) {
                AvatarStage.EventKind.MessageArrived -> if (rng.nextFloat() < 0.45f) {
                    val at = e.at ?: windowBottom() ?: return
                    val (gx, gy) = lookAt(at)
                    gaze.focus(gx, gy, 0.9f + rng.nextFloat() * 0.7f, priority = 2)
                    eventCooldown = 8f
                }
                AvatarStage.EventKind.Scroll -> if (rng.nextFloat() < 0.25f) {
                    gaze.focus((rng.nextFloat() - 0.5f) * 0.3f, -0.32f * e.direction, 0.6f + rng.nextFloat() * 0.4f, priority = 1)
                    eventCooldown = 12f
                }
                AvatarStage.EventKind.UserTyping -> onTypingStarted()
                else -> Unit
            }
        }
    }

    private fun onPoke(r: Response) {
        pokeCount++
        pokeWindow = 2.2f
        mood.nudge(0f, 0.12f)
        when {
            pokeCount >= 6 && lastSpinAgo > 30f -> {
                // Fine, you win: a spin, then all is forgiven.
                trySpin("poked a lot: fine, you win")
                pokeCount = 0
                forgiveIn = 1.6f
            }
            pokeCount >= 3 -> {
                director.gesture(MotionDirector.Gesture.TurnAway)
                setReaction("pouty", 1.6f, cause = "poked too much")
                accent(FaceAccent.Annoyed, 1.8f, force = true)
                mood.nudge(-0.12f, 0.05f)
                forgiveIn = 2.6f + rng.nextFloat() * 1.5f
            }
            pokeCount == 2 -> {
                director.gesture(MotionDirector.Gesture.Recoil)
                setReaction("Surprised", 0.9f, cause = "poke")
                blink.request(minGap = 0.2f)
            }
            // First poke: not the same reaction every time.
            else -> when (r) {
                Response.Startle -> {
                    director.gesture(MotionDirector.Gesture.Recoil)
                    accent(FaceAccent.Startled, 0.45f, force = true)
                    if (rng.nextFloat() < 0.4f) { reactionQueue = "Happy" to 1.2f; reactionQueueIn = 0.6f }
                }
                Response.Freeze -> perform(Response.Freeze, Stimulus("poke", 0f, 0f, 0.9f))
                Response.Smile -> { blink.request(minGap = 0f); accent(FaceAccent.Glee, 1.2f, force = true) }
                Response.CuriousTilt -> { director.gesture(MotionDirector.Gesture.Tilt); accent(FaceAccent.Curious, 1.2f) }
                Response.Acknowledge -> director.gesture(MotionDirector.Gesture.Nod)
                else -> { blink.request(minGap = 0f); gaze.focus(0f, 0.05f, 1.5f, priority = 3) }
            }
        }
    }

    /**
     * App / UI happenings. Returns true when handled (some apply while busy too).
     * Every avatar on screen hears the same event, so a surprise lands on all of
     * them at once.
     */
    private fun onUiEvent(e: AvatarStage.Event, calm: Boolean): Boolean {
        if (e.source != stageId) aliveness.onEvent(e)
        fun glance(at: Offset?, hold: Float, prio: Int = 2) {
            val p = at ?: return
            val (gx, gy) = lookAt(p)
            gaze.focus(gx, gy, hold, priority = prio)
        }
        when (e.kind) {
            AvatarStage.EventKind.Error -> {
                mood.nudge(-0.4f, 0.15f)
                // Engine17.1: the worried-neutral face is retired — a calm, restrained concentration.
                setReaction("angered or concentrated", 2.4f, ExpressionDirector.Style.Soft, cause = "error event: concentrates on it")
                blink.request(minGap = 0.3f)
            }
            AvatarStage.EventKind.Deleted -> {
                mood.nudge(-0.1f, 0.1f)
                // Watch it go, then a small reaction.
                glance(e.at, 0.9f, prio = 3)
                if (rng.nextFloat() < 0.5f) accent(FaceAccent.Startled, 0.4f, force = true)
                reactionQueue = (if (rng.nextFloat() < 0.6f) "Sad" else "Surprised") to 0.9f
                reactionQueueIn = 0.45f
            }
            AvatarStage.EventKind.Copied -> {
                mood.nudge(0.12f, 0.05f)
                if (calm && rng.nextFloat() < 0.5f) {
                    accent(FaceAccent.Glee, 1.1f)
                    if (rng.nextFloat() < 0.4f) director.gesture(MotionDirector.Gesture.Nod)
                }
            }
            AvatarStage.EventKind.Regenerated -> {
                accent(FaceAccent.Puzzled, 0.9f, force = true)
                blink.request(minGap = 0.3f)
            }
            AvatarStage.EventKind.AppResumed -> {
                // Back from the background: a sleepy squint that opens up, blink-blink.
                mood.nudge(0f, 0.15f)
                if (rng.nextFloat() < 0.7f) {
                    accent(FaceAccent.WakeSquint, 0.9f + rng.nextFloat() * 0.5f, force = true)
                    perkIn = 0.9f
                }
                if (drowsy) wake()
            }
            AvatarStage.EventKind.KeyboardOpened -> {
                mood.nudge(0f, 0.08f)
                if (calm && social == null && rng.nextFloat() < 0.45f) {
                    glance(windowBottom(), 0.8f + rng.nextFloat() * 0.6f)
                    if (rng.nextFloat() < 0.3f) accent(FaceAccent.Curious, 1.0f)
                }
            }
            AvatarStage.EventKind.KeyboardClosed -> {
                if (followLeft > 0f) { followLeft = 0f; gaze.releaseFocus() }
                if (rng.nextFloat() < 0.3f) blink.request()
            }
            AvatarStage.EventKind.Fling -> {
                // A really fast fling, rarely: a little dizzy.
                if (calm && flingCooldown <= 0f && e.magnitude > 18f && rng.nextFloat() < 0.3f) {
                    flingCooldown = 90f
                    accent(FaceAccent.Dizzy, 1.3f, force = true)
                    director.gesture(MotionDirector.Gesture.Tilt)
                    reactionQueue = "confused" to 0.8f
                    reactionQueueIn = 1.1f
                }
            }
            AvatarStage.EventKind.MenuOpened -> {
                if (calm && social == null && rng.nextFloat() < 0.5f) {
                    glance(e.at, 1.0f)
                    if (rng.nextFloat() < 0.35f) accent(FaceAccent.Curious, 1.0f)
                }
            }
            AvatarStage.EventKind.PillExpanded -> {
                // Peek at what opened.
                if (social == null && rng.nextFloat() < 0.7f) {
                    glance(e.at ?: targetCenter(AvatarStage.Target.ActivityPill), 1.3f + rng.nextFloat() * 0.6f)
                    if (calm) director.gesture(MotionDirector.Gesture.Attend, 1.4f)
                }
            }
            AvatarStage.EventKind.AvatarAccent -> {
                // Now and then mirror a neighbour's face, a beat later.
                if (e.source != stageId && calm && social == null && mirror == null && rng.nextFloat() < 0.12f) {
                    val a = FaceAccent.entries.getOrNull(e.extra)
                    if (a != null && a != FaceAccent.Dizzy && a != FaceAccent.None) {
                        glance(e.at, 0.8f)
                        mirror = a to 1.0f
                        mirrorIn = 0.35f + rng.nextFloat() * 0.3f
                    }
                }
            }
            else -> return false
        }
        return true
    }

    private fun wake() {
        if (restPhase > 0) {
            log("wake from ${if (restPhase == 2) "doze" else "resting eyes"} cause=activity returned")
            if (restPhase == 2 && reaction == "Sleeping") { reaction = null; log("expr Sleeping→Neutral cause=woken gently") }
            restPhase = 0; restLeft = 0f
            if (rng.nextFloat() < 0.35f) wakeShake(3.5f, cause = "wake")
            sinceStimulus = -6f // just woke up: give it a moment before any self-started beat
        }
        drowsy = false
        drowsyAfter = 100f + rng.nextFloat() * 60f
        blink.request(double = true, minGap = 0.2f)
        accent(FaceAccent.WakeSquint, 0.8f, force = true)
    }

    // ------------------------------------------------- engine16: idle episodes + channels

    internal val episodes = EpisodePlanner(rng)
    /** 0 = awake, 1 = resting eyes, 2 = dozing. */
    internal var restPhase = 0; private set
    private var restLeft = 0f
    private var restLid = 1f
    private var restK = 0f
    private var yawnU = 1f; private var yawnDur = 2.8f
    private var sbT = -1f; private val SB_DUR = 1.35f
    private var wsT = -1f; private var wsAmp = 4f; private var wsHz = 4.6f; private var wsCycles = 2.5f
    private var busySpinIn = 25f

    private fun yawnK(): Float = if (yawnU >= 1f) 0f else when {
        yawnU < 0.35f -> MotionVocab.easeInOutSine(yawnU / 0.35f)
        yawnU < 0.65f -> 1f
        else -> 1f - MotionVocab.easeInOutSine((yawnU - 0.65f) / 0.35f)
    }

    private fun slowBlinkLid(): Float {
        if (sbT < 0f) return 1f
        val u = sbT / SB_DUR
        val k = when {
            u < 0.32f -> MotionVocab.easeInOutSine(u / 0.32f)
            u < 0.55f -> 1f
            else -> 1f - MotionVocab.easeInOutSine((u - 0.55f) / 0.45f)
        }
        return 1f - 0.93f * k
    }

    /**
     * Engine17.1: THE head shake (wake shake, come-to-senses, yawn, spin shake-off) — a
     * deliberate "no-no" shake, not a vibration: 2–3 slow, larger swings (~1.7–2.1 Hz,
     * 8–11°) with a soft attack and decaying envelope, eyes squeezed shut throughout;
     * then the eyes open with the head's small settle and a blink. [amp] is the legacy
     * strength (3.5…6.5), mapped onto the new swing size.
     */
    private fun wakeShake(amp: Float, cause: String = "wake") {
        wsT = 0f
        wsAmp = (7.5f + (amp - 3.5f) * 0.9f).coerceIn(7.5f, 11f) * (0.92f + 0.16f * rng.nextFloat())
        wsHz = 1.7f + 0.4f * rng.nextFloat()
        wsCycles = 2f + rng.nextFloat()
        wsDir = if (rng.nextBoolean()) 1f else -1f
        log("head-shake cause=$cause swings=${"%.1f".format(wsCycles)} amp=${"%.1f".format(wsAmp)}°")
    }
    private var wsDir = 1f
    /** Eye closure during a head shake (1 = open). */
    private var shakeLid = 1f

    private fun lean(roll: Float, yaw: Float) { peekRollT = roll; peekYawT = yaw }

    /** Runs every frame (idle or not): yawn / slow blink / wake shake / rest / busy-spin timers. */
    private fun channelsUpdate(dt: Float) {
        if (yawnU < 1f) yawnU = (yawnU + dt / yawnDur).coerceAtMost(1f)
        if (sbT >= 0f) { sbT += dt; if (sbT >= SB_DUR) sbT = -1f }
        if (wsT >= 0f) {
            val dur = wsCycles / wsHz
            val squeezeIn = 0.14f
            if (wsT < squeezeIn + dur) {
                // Eyes squeeze shut first, then the head swings.
                val st = wsT - squeezeIn
                if (st > 0f) {
                    val u = st / dur
                    val attack = (st / 0.12f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                    val env = attack * (1f - u).let { it * it * (1f + 0.6f * u) }.coerceIn(0f, 1f)
                    shakeYaw += wsDir * wsAmp * env * sin(2f * PI.toFloat() * wsHz * st)
                }
                val lidT = 0.04f
                shakeLid += (lidT - shakeLid) * (1f - exp(-dt / 0.05f))
                wsT += dt
            } else {
                wsT = -1f
                blink.request(minGap = 0.25f) // eyes open with a settle, then a blink
            }
        } else if (shakeLid < 1f) {
            // Open gently (the head is still settling).
            shakeLid += (1f - shakeLid) * (1f - exp(-dt / 0.12f))
            if (shakeLid > 0.995f) shakeLid = 1f
        }
        // Resting eyes / dozing.
        val lidT = when (restPhase) { 1 -> 0.06f; 2 -> 0f; else -> 1f }
        restLid += (lidT - restLid) * (1f - exp(-dt / if (lidT < restLid) 1.1f else 0.45f))
        val kT = if (restPhase > 0) 1f else 0f
        restK += (kT - restK) * (1f - exp(-dt / 1.6f))
        if (restPhase > 0) {
            restLeft -= dt
            if (restPhase == 2 && reaction == "Sleeping") reactionLeft = 5f
            if (restLeft <= 0f) {
                if (restPhase == 1 && (forceDoze || (quietSec > 150f && rng.nextFloat() < 0.5f))) {
                    forceDoze = false
                    restPhase = 2; restLeft = 35f + rng.nextFloat() * 45f
                    gaze.focus(0f, 0.18f, restLeft + 1f, priority = 3, travel = 1.5f) // asleep: the head stays put
                    setReaction("Sleeping", 6f, ExpressionDirector.Style.Gradual, cause = "dozed off while resting eyes (untouched ${quietSec.toInt()} s)")
                } else {
                    // Opens again on its own, slowly: blink-blink, sometimes a yawn after a doze.
                    val was = restPhase
                    restPhase = 0
                    if (was == 2 && reaction == "Sleeping") { reaction = null; log("expr Sleeping→Neutral cause=wakes up from a doze on its own") }
                    log("rest ends (opens eyes on its own)")
                    blink.request(double = true, minGap = 0f)
                    accent(FaceAccent.WakeSquint, 0.9f, force = true)
                    if (was == 2 && rng.nextFloat() < 0.4f) { episodes.note(Episode.Yawn); tell("after-doze", 1.2f to "yawn", acts = listOf({ playEpisode(Episode.Yawn) })) }
                }
            }
        }
        // Engine16: the only self-started spins — rarely, while answering in a really good mood.
        if (director.lifecycle == MarkLifecycle.Streaming) {
            busySpinIn -= dt
            if (busySpinIn <= 0f) {
                busySpinIn = 20f + rng.nextFloat() * 20f
                if (temper.playful(personality) && mood.valence > 0.3f && lastSpinAgo > 90f && rng.nextFloat() < 0.15f)
                    trySpin("lively while answering")
            }
        }
    }

    private fun episodeUpdate(dt: Float) {
        val free = director.lifecycle == MarkLifecycle.Idle && reaction == null && social == null && train.isEmpty() &&
            freezeLeft <= 0f && takeT < 0f && scan.isEmpty() && driftU >= 1f && stretchU >= 1f && yawnU >= 1f &&
            spT < 0f && recoverT <= 0f && sinceStimulus > 8f && lastSpinAgo > 8f && restPhase == 0 && followLeft <= 0f && !gaze.focused && peekLeft <= 0f
        val ctx = EpisodePlanner.Ctx(temper, quietSec, drowsy, mood.valence, targetCenter(AvatarStage.Target.ActivityPill) != null,
            recently("nice", 120f), personality.playfulness)
        episodes.update(dt, ctx, free)?.let { playEpisode(it) }
    }

    internal fun debugEpisode(e: Episode) { episodes.note(e); playEpisode(e) }
    private var forceDoze = false
    /** Harness: as if untouched for a long while — rest the eyes now ([doze] = then doze off). */
    internal fun debugRest(doze: Boolean) {
        drowsy = true; quietSec = max(quietSec, 200f); forceDoze = doze
        episodes.note(Episode.RestEyes); playEpisode(Episode.RestEyes); restLeft = 4.5f
    }

    private fun playEpisode(e: Episode) {
        fun rr(a: Float, b: Float) = a + (b - a) * rng.nextFloat()
        val s = if (rng.nextBoolean()) 1f else -1f
        log("episode $e (${e.tier}) why=${e.why}")
        when (e) {
            Episode.CuriousLR -> {
                val a = rr(1.0f, 1.3f); val b = a + rr(1.0f, 1.3f); val curious = rng.nextFloat() < 0.35f
                tell("curious-lr", 0f to "look ${if (s > 0) "right" else "left"}", a to "look other way", b to "back to rest", b + 0.1f to "maybe blink", acts = listOf(
                    { gaze.focus(s * rr(0.5f, 0.65f), rr(0f, 0.1f), a + 0.1f, priority = 2) },
                    { gaze.focus(-s * rr(0.5f, 0.65f), rr(0f, 0.1f), b - a + 0.1f, priority = 2); if (curious) accent(FaceAccent.Curious, 1.1f) },
                    { gaze.focus(rr(-0.05f, 0.05f), rr(0f, 0.05f), rr(1.5f, 2.5f), priority = 2, travel = 0.5f) },
                    { if (rng.nextFloat() < 0.5f) blink.request(minGap = 0.3f) },
                ))
            }
            Episode.CheckOnYou -> tell("check-on-you", 0f to "look at you", 0.7f to "react", acts = listOf(
                { gaze.focus(rr(-0.04f, 0.04f), rr(0.08f, 0.16f), rr(2f, 3f), priority = 2, travel = 0.6f) },
                { if (mood.valence > 0.25f) accent(FaceAccent.Glee, rr(1f, 1.5f)) else blink.request(minGap = 0.3f) },
            ))
            Episode.TiltChain -> {
                val r1 = s * rr(5f, 7f); val r2 = -s * rr(4f, 6f)
                tell("tilt-chain", 0f to "eyes to it", 0.25f to "tilt", 0.45f to "curious", 1.6f to "tilt other way", 2.8f to "straighten", acts = listOf(
                    { gaze.focus(s * rr(0.25f, 0.35f), rr(0f, 0.12f), 3.6f, priority = 2) },
                    { lean(r1, s * 2f) },
                    { if (rng.nextFloat() < 0.5f) accent(FaceAccent.Curious, 2f) },
                    { lean(r2, -s * 1.5f) },
                    { lean(0f, 0f) },
                ))
            }
            Episode.SkepticalLR -> {
                val a = rr(1.6f, 1.9f)
                tell("skeptical-lr", 0f to "look left", 0.6f to "brow up (left)", a to "look right", a + 0.6f to "other brow up (right)",
                    a + 1.9f to "back to rest", a + 2.1f to "blink", acts = listOf(
                    { gaze.focus(-rr(0.5f, 0.6f), rr(0f, 0.06f), a, priority = 2) },
                    { setReaction("confused", a - 0.75f, cause = "skeptical: sizing something up on the left") },
                    { gaze.focus(rr(0.5f, 0.6f), rr(0f, 0.06f), 1.9f, priority = 2) },
                    { setReaction("confused", 1.1f, cause = "skeptical: and the right doesn't convince either") },
                    { gaze.focus(rr(-0.04f, 0.04f), rr(0f, 0.05f), 2f, priority = 2, travel = 0.5f) },
                    { blink.request(minGap = 0.2f) },
                ))
            }
            Episode.HmmLean -> {
                val nod = rng.nextFloat() < 0.5f
                tell("hmm", 0f to "look down-side", 0.3f to "lean", 0.5f to "puzzled", 2.3f to "straighten", 2.45f to if (nod) "got it: nod" else "blink", acts = listOf(
                    { gaze.focus(s * rr(0.28f, 0.4f), rr(0.18f, 0.3f), 2.4f, priority = 2) },
                    { lean(s * rr(2.5f, 3.5f), s * rr(3f, 4.5f)) },
                    { accent(FaceAccent.Puzzled, 1.7f) },
                    { lean(0f, 0f) },
                    { if (nod) director.gesture(MotionDirector.Gesture.Nod) else blink.request(minGap = 0.2f) },
                ))
            }
            Episode.DoubleTake -> perform(Response.DoubleTake, Stimulus("idle-flicker", s * rr(0.6f, 0.75f), rr(0f, 0.15f), 0.6f))
            Episode.Peek -> playMoment(Moment.Peek)
            Episode.Scan -> playMoment(Moment.Scan)
            Episode.NoticePill -> playMoment(Moment.NoticePill)
            Episode.Stretch -> playMoment(Moment.Stretch)
            Episode.SleepyDrift -> playMoment(Moment.SleepyDrift)
            Episode.Smug -> playMoment(Moment.SmugNod)
            Episode.FollowSomething -> {
                val tilt = rng.nextFloat() < 0.5f
                tell("follow", 0f to "spots it", 0.6f to "tracks it across", 3.2f to "lost it: blink", 3.4f to if (tilt) "tilt" else "-", 3.6f to "back to rest", acts = listOf(
                    { gaze.focus(s * rr(0.65f, 0.75f), rr(-0.08f, 0.05f), 0.65f, priority = 2) },
                    { gaze.focus(-s * rr(0.55f, 0.65f), rr(0.05f, 0.15f), 0.6f, priority = 2, travel = rr(2.2f, 2.6f)) },
                    { blink.request(minGap = 0f) },
                    { if (tilt) director.gesture(MotionDirector.Gesture.Tilt) },
                    { gaze.focus(0f, 0.03f, 1.5f, priority = 2, travel = 0.6f) },
                ))
            }
            Episode.LookUpRemember -> tell("remember", 0f to "look up-left", 0.45f to "puzzled", 1.8f to "ah: back", 1.95f to "nod", acts = listOf(
                { gaze.focus(-rr(0.35f, 0.45f), -rr(0.22f, 0.3f), 1.8f, priority = 2) },
                { accent(FaceAccent.Puzzled, 1.3f) },
                { gaze.focus(0f, 0.05f, 1.6f, priority = 2) },
                { director.gesture(MotionDirector.Gesture.Nod); if (mood.valence > 0.05f) accent(FaceAccent.Glee, 0.9f, force = true) },
            ))
            Episode.ContentSlowBlink -> tell("content", 0f to "slow blink", 0.25f to "soft smile", acts = listOf(
                { sbT = 0f },
                { accent(FaceAccent.Glee, 1.6f) },
            ))
            Episode.ConcernGlance -> {
                val nod = rng.nextFloat() < 0.4f
                tell("concern", 0f to "glance down-side", 0.55f to "concentrated", 2f to "blink", 2.1f to "back", 2.5f to if (nod) "reassured nod" else "-", acts = listOf(
                    { gaze.focus(s * rr(0.4f, 0.5f), rr(0.35f, 0.45f), 2f, priority = 2) },
                    { setReaction("angered or concentrated", 1.5f, ExpressionDirector.Style.Soft, cause = "concern glance: concentrated") },
                    { blink.request(minGap = 0f) },
                    { gaze.focus(0f, 0.03f, 1.6f, priority = 2, travel = 0.5f) },
                    { if (nod) director.gesture(MotionDirector.Gesture.Nod) },
                ))
            }
            Episode.ComeToSenses -> tell("come-to-senses", 0f to "blink-blink", 0.3f to "shake", 0.35f to "squint", 1.3f to "present", acts = listOf(
                { blink.request(double = true, minGap = 0f) },
                { wakeShake(rr(4f, 5.5f), cause = "come to senses") },
                { accent(FaceAccent.WakeSquint, 0.8f, force = true) },
                { gaze.focus(0f, 0.02f, 1.4f, priority = 2) },
            ))
            Episode.Yawn -> {
                yawnU = 0f; yawnDur = rr(2.6f, 3.2f)
                val shake = rng.nextFloat() < 0.5f
                val end = yawnDur
                tell("yawn", 0.15f * end to "eyes squeeze", end * 0.98f to if (shake) "shake to wake up" else "eyes open slowly", end + 0.2f to if (shake) "squint" else "-", acts = listOf(
                    { setReaction("Eyes closed", 0.8f * end, ExpressionDirector.Style.Gradual, cause = "yawn (tired)") },
                    { if (shake) wakeShake(rr(4.5f, 6f), cause = "yawn") },
                    { if (shake) { blink.request(double = true, minGap = 0f); accent(FaceAccent.WakeSquint, 0.8f, force = true) } },
                ))
            }
            Episode.RestEyes -> {
                restPhase = 1; restLeft = rr(5f, 10f)
                gaze.focus(0f, 0.15f, restLeft + 1f, priority = 3, travel = 1.2f)
                log("rest eyes for %.1fs cause=untouched ${quietSec.toInt()} s, drowsy".format(restLeft))
            }
            Episode.LookAround360 -> tell("survey", 0f to "far left", 1.1f to "middle", 2.2f to "far right", 3.5f to "tilt", 4f to "back", 4.1f to "blink", acts = listOf(
                { gaze.focus(-0.85f, 0.05f, 1.1f, priority = 2) },
                { gaze.focus(-0.25f, 0f, 0.6f, priority = 2, travel = 0.8f) },
                { gaze.focus(0.85f, 0.08f, 1.3f, priority = 2, travel = 1.1f) },
                { director.gesture(MotionDirector.Gesture.Tilt) },
                { gaze.focus(0f, 0.03f, 2f, priority = 2, travel = 0.7f) },
                { blink.request(minGap = 0.2f) },
            ))
            Episode.Sneeze -> tell("sneeze", 0f to "scrunch", 0.45f to "achoo", 0.55f to "blink-blink", acts = listOf(
                { accent(FaceAccent.WakeSquint, 0.6f, force = true) },
                { director.gesture(MotionDirector.Gesture.Recoil) },
                { blink.request(double = true, minGap = 0f) },
            ))
        }
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
