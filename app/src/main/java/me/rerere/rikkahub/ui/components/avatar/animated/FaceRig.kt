package me.rerere.rikkahub.ui.components.avatar.animated

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Parametric eye shaping layered on top of the authored expressions (see
 * docs/avatar-aliveness.md). Each eye gets a small set of continuous parameters —
 * scale, offset, rotation and upper / lower lids with a slant — that are smoothed by
 * springs, so any change (an accent, the mood, a saccade, a blink) morphs instead
 * of switching. Grok capsules take the parameters that keep them capsules (scale,
 * offset, rotation, openness); Generical outlines take the full lid treatment.
 */
data class EyeShape(
    val sx: Float = 1f,
    val sy: Float = 1f,
    /** Offset in head radii (x right, y down). */
    val dx: Float = 0f,
    val dy: Float = 0f,
    /** Rotation in degrees, positive = clockwise on screen. */
    val rot: Float = 0f,
    /** Openness of the top / bottom half (1 = as authored, 0 = shut to the midline). */
    val upper: Float = 1f,
    val lower: Float = 1f,
    /** Upper-lid slant: + = inner corner lower (cross), - = inner corner higher (worried). */
    val slant: Float = 0f,
) {
    fun isIdentity(): Boolean =
        abs(sx - 1f) < 1e-3f && abs(sy - 1f) < 1e-3f && abs(dx) < 1e-4f && abs(dy) < 1e-4f &&
            abs(rot) < 0.05f && abs(upper - 1f) < 1e-3f && abs(lower - 1f) < 1e-3f && abs(slant) < 1e-3f

    companion object {
        val ID = EyeShape()
        const val COUNT = 8
    }

    fun toArray(out: FloatArray, at: Int) {
        out[at] = sx; out[at + 1] = sy; out[at + 2] = dx; out[at + 3] = dy
        out[at + 4] = rot; out[at + 5] = upper; out[at + 6] = lower; out[at + 7] = slant
    }
}

fun eyeShapeOf(a: FloatArray, at: Int) = EyeShape(a[at], a[at + 1], a[at + 2], a[at + 3], a[at + 4], a[at + 5], a[at + 6], a[at + 7])

data class FaceParams(val left: EyeShape = EyeShape.ID, val right: EyeShape = EyeShape.ID) {
    fun eye(side: Int) = if (side == 0) left else right
    companion object { val ID = FaceParams() }
}

/** Named parametric looks. Values are offsets from identity (see [FaceRig.accentTarget]). */
enum class FaceAccent {
    None, Curious, Annoyed, Shy, Startled, Dizzy, Smug, Focused, Drowsy, Worried, Glee, WakeSquint, Sad, Puzzled,
}

/** Two-axis mood (valence −1…1, arousal 0…1) with slow decay toward a baseline. */
class MoodEngine(private val rng: Random) {
    var valence = 0.1f; private set
    var arousal = 0.5f; private set
    /** Baseline arousal: lower late at night / early morning. Set from the clock. */
    var arousalBase = 0.5f

    fun nudge(dv: Float, da: Float) {
        valence = (valence + dv).coerceIn(-1f, 1f)
        arousal = (arousal + da).coerceIn(0f, 1f)
    }

    fun update(dt: Float, quietSec: Float) {
        // Valence returns to slightly positive in ~40 s; arousal to its baseline in
        // ~60 s, and slowly below it the longer nothing happens (boredom).
        val boredom = ((quietSec - 30f) / 120f).coerceIn(0f, 0.3f)
        valence += (0.1f - valence) * (1f - exp(-dt / 40f))
        arousal += ((arousalBase - boredom) - arousal) * (1f - exp(-dt / 60f))
    }

    companion object {
        /** Arousal baseline by local hour: alert in the day, sleepy after midnight. */
        fun baseForHour(h: Int): Float = when (h) {
            in 0..5 -> 0.28f
            in 6..8 -> 0.42f
            in 9..20 -> 0.55f
            21, 22 -> 0.45f
            else -> 0.36f
        }
    }
}

/**
 * Springs every eye parameter toward the sum of: the active accent, the mood bias,
 * saccade stretch, blink squash and micro jitter. Underdamped (ζ≈0.62) for a little
 * follow-through overshoot when an accent lands; offsets are critically damped.
 */
class FaceRig(private val rng: Random) {
    private val x = FloatArray(16)
    private val v = FloatArray(16)
    private val target = FloatArray(16)
    var accent: FaceAccent = FaceAccent.None; private set
    private var accentLeft = 0f
    private var accentSide = 1f
    private var t = 0f
    private var jitterIn = 0.5f
    private val jitter = FloatArray(4)

    init {
        EyeShape.ID.toArray(x, 0); EyeShape.ID.toArray(x, 8)
    }

    val active: Boolean get() = accent != FaceAccent.None

    fun play(a: FaceAccent, sec: Float) {
        accent = a
        accentLeft = sec
        accentSide = if (rng.nextBoolean()) 1f else -1f
    }

    fun clear() { accent = FaceAccent.None; accentLeft = 0f }

    /** The settled look of an accent (harness galleries). */
    internal fun staticFace(a: FaceAccent, time: Float = 0f): FaceParams =
        FaceParams(clampEye(accentTarget(a, 0, time)), clampEye(accentTarget(a, 1, time)))

    /**
     * [eyeVx]/[eyeVy]: gaze eye velocity (gaze units/s) — eyes stretch along a fast
     * saccade. [blink] 0…1 closure — eyes squash wider as the lids meet.
     * [valence]/[arousal] from [MoodEngine]; [calm] false while busy (mood bias softer).
     */
    fun update(dt: Float, eyeVx: Float, eyeVy: Float, blink: Float, valence: Float, arousal: Float, still: Boolean): FaceParams {
        t += dt
        if (accentLeft > 0f) {
            accentLeft -= dt
            if (accentLeft <= 0f) accent = FaceAccent.None
        }
        for (side in 0..1) {
            val e = accentTarget(accent, side, t)
            val o = side * 8
            e.toArray(target, o)
            // Mood: happy lifts the lower lids a touch, low mood slants them worried,
            // high arousal opens the eyes a little, low arousal lowers the upper lids.
            val pos = max(0f, valence); val neg = max(0f, -valence)
            target[o + 6] -= 0.1f * pos
            target[o + 7] -= 0.12f * neg
            target[o + 0] *= 1f + 0.05f * (arousal - 0.5f)
            target[o + 1] *= 1f + 0.07f * (arousal - 0.5f)
            target[o + 5] -= 0.22f * ((0.38f - arousal) / 0.38f).coerceIn(0f, 1f)
            // Saccade stretch (along the motion) — a few percent at most.
            val sp = kotlin.math.hypot(eyeVx, eyeVy)
            if (sp > 0.5f) {
                val k = (sp * 0.006f).coerceAtMost(0.07f)
                val fx = abs(eyeVx) / sp; val fy = abs(eyeVy) / sp
                target[o + 0] *= 1f + k * fx - k * 0.5f * fy
                target[o + 1] *= 1f + k * fy - k * 0.5f * fx
            }
            // Blink squash: wider and a hair lower as the lids meet.
            target[o + 0] *= 1f + 0.07f * blink
            target[o + 3] += 0.012f * blink
            // Micro jitter (tiny, only while still).
            if (still) {
                target[o + 2] += jitter[side * 2]
                target[o + 3] += jitter[side * 2 + 1]
            }
        }
        jitterIn -= dt
        if (jitterIn <= 0f) {
            jitterIn = 0.35f + rng.nextFloat() * 1.2f
            for (i in 0..3) jitter[i] = (rng.nextFloat() - 0.5f) * 0.006f
            jitter[2] = jitter[0] * 0.7f + jitter[2] * 0.3f // both eyes move mostly together
            jitter[3] = jitter[1] * 0.7f + jitter[3] * 0.3f
        }
        var rem = dt
        while (rem > 1e-6f) {
            val h = min(rem, 1f / 120f)
            for (i in 0 until 16) {
                val k = i % 8
                val w = if (k == 2 || k == 3) 22f else 17f
                val zeta = if (k == 2 || k == 3) 1f else 0.62f
                val a = w * w * (target[i] - x[i]) - 2f * zeta * w * v[i]
                v[i] += a * h
                x[i] += v[i] * h
            }
            rem -= h
        }
        return FaceParams(clampEye(eyeShapeOf(x, 0)), clampEye(eyeShapeOf(x, 8)))
    }

    private fun clampEye(e: EyeShape) = e.copy(
        sx = e.sx.coerceIn(0.7f, 1.3f), sy = e.sy.coerceIn(0.6f, 1.35f),
        dx = e.dx.coerceIn(-0.08f, 0.08f), dy = e.dy.coerceIn(-0.08f, 0.08f),
        rot = e.rot.coerceIn(-25f, 25f),
        upper = e.upper.coerceIn(0.15f, 1.15f), lower = e.lower.coerceIn(0.3f, 1.15f),
        slant = e.slant.coerceIn(-0.5f, 0.5f),
    )

    /** side 0 = left (screen), 1 = right. "inner" is toward the other eye. */
    private fun accentTarget(a: FaceAccent, side: Int, time: Float): EyeShape {
        val s = accentSide
        val lead = if ((side == 0) == (s > 0f)) 1f else 0f // the eye on the accent side
        return when (a) {
            FaceAccent.None -> EyeShape.ID
            // One eye a little bigger, the other narrowed, head-tilt feel.
            FaceAccent.Curious -> if (lead > 0f) EyeShape(sx = 1.06f, sy = 1.14f, dy = -0.01f, rot = 4f * s)
            else EyeShape(sx = 0.98f, sy = 0.9f, upper = 0.86f, rot = 4f * s)
            FaceAccent.Annoyed -> EyeShape(upper = 0.74f, slant = 0.14f, sy = 0.97f) // engine13: was 0.32 slant, read too angry
            FaceAccent.Shy -> EyeShape(lower = 0.68f, sy = 0.94f, dy = 0.02f)
            FaceAccent.Startled -> EyeShape(sx = 1.2f, sy = 1.4f, dy = -0.016f)
            FaceAccent.Dizzy -> {
                // Eyes circle in opposite phase, slowly settling.
                val ph = time * 7f + side * PI.toFloat()
                EyeShape(dx = cos(ph) * 0.03f, dy = sin(ph) * 0.03f, rot = sin(ph) * 6f, sy = 0.95f)
            }
            FaceAccent.Smug -> if (lead > 0f) EyeShape(upper = 0.62f, lower = 0.85f, slant = -0.1f)
            else EyeShape(upper = 0.7f, slant = -0.08f)
            FaceAccent.Focused -> EyeShape(upper = 0.8f, lower = 0.9f, sx = 1.02f)
            FaceAccent.Drowsy -> EyeShape(upper = 0.48f, slant = -0.18f, dy = 0.01f)
            FaceAccent.Worried -> EyeShape(slant = -0.3f, sy = 1.05f, upper = 0.92f)
            FaceAccent.Glee -> EyeShape(lower = 0.58f, sx = 1.04f, sy = 0.96f)
            FaceAccent.WakeSquint -> EyeShape(upper = 0.42f, lower = 0.78f, sx = 1.04f)
            FaceAccent.Sad -> EyeShape(slant = -0.24f, upper = 0.84f, dy = 0.02f)
            FaceAccent.Puzzled -> if (lead > 0f) EyeShape(sy = 1.1f, dy = -0.012f) else EyeShape(upper = 0.66f, slant = 0.12f)
        }
    }

    companion object {
        /** Capsule rotation per unit of slant (sign chosen so + = inner corner lower). */
        const val SLANT_DEG = 14f

        /**
         * Shape a Generical outline (500 viewBox, y down) in place. [side] 0 = left.
         * Lids compress the top / bottom halves toward a midline; the slant varies the
         * upper compression across the eye (inner vs outer corner).
         */
        fun shapeOutline(xs: FloatArray, ys: FloatArray, side: Int, p: EyeShape) {
            if (p.isIdentity()) return
            val n = xs.size
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var sumX = 0f
            for (i in 0 until n) {
                minX = min(minX, xs[i]); maxX = max(maxX, xs[i]); minY = min(minY, ys[i]); maxY = max(maxY, ys[i]); sumX += xs[i]
            }
            val cx = sumX / n
            val cy = (minY + maxY) / 2f
            val hw = max(1f, (maxX - minX) / 2f)
            val inward = if (side == 0) 1f else -1f
            val r = Math.toRadians(p.rot.toDouble()).toFloat()
            val cr = cos(r); val sr = sin(r)
            for (i in 0 until n) {
                var x0 = xs[i] - cx
                var y0 = ys[i] - cy
                val xn = (x0 / hw).coerceIn(-1f, 1f) * inward // +1 = inner corner
                // Lids close by sliding the top/bottom edge in, not by squashing, so the
                // hand-made corner radii stay the same (straight sides shorten instead).
                val hh = max(1f, (maxY - minY) / 2f)
                if (y0 < 0f) {
                    val f = (p.upper - p.slant * xn).coerceIn(0.05f, 1.2f)
                    y0 = if (f >= 1f) y0 * f else min(0f, y0 + (1f - f) * hh)
                } else {
                    val f = p.lower.coerceIn(0.05f, 1.2f)
                    y0 = if (f >= 1f) y0 * f else max(0f, y0 - (1f - f) * hh)
                }
                x0 *= p.sx; y0 *= p.sy
                val rx = x0 * cr - y0 * sr
                val ry = x0 * sr + y0 * cr
                xs[i] = cx + rx + p.dx * 250f
                ys[i] = cy + ry + p.dy * 250f
            }
        }

        /** Shape a Grok capsule: stays a capsule (scale, offset, rotation, openness). */
        private fun isBent(pts: List<FloatArray>): Boolean {
            if (pts.size < 3) return false
            val a = pts.first(); val b = pts.last(); val m = pts[pts.size / 2]
            val dx = b[0] - a[0]; val dy = b[1] - a[1]
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-4f)
            return kotlin.math.abs((m[0] - a[0]) * dy - (m[1] - a[1]) * dx) / len > 0.04f
        }

        fun shapeCapsule(e: WhSphereEyes.EyeState, side: Int, p: EyeShape): WhSphereEyes.EyeState {
            if (p.isIdentity()) return e
            // Capsule-local x points outward for each eye (left eye mirrored), y up.
            val mirror = if (side == 0) -1f else 1f
            val open = ((p.upper + p.lower) / 2f).coerceIn(0.1f, 1.2f)
            // Slant reads as a small symmetric rotation (mirrored frame does the rest).
            val rotDeg = p.rot * mirror + p.slant * SLANT_DEG
            // Upper lid closing more than the lower moves the visible eye down.
            val shiftY = (p.upper - p.lower) * 0.03f
            return e.copy(
                offX = e.offX + p.dx * mirror,
                offY = e.offY - p.dy + shiftY,
                tilt = e.tilt + Math.toRadians(rotDeg.toDouble()).toFloat(),
                wid = e.wid * p.sx.coerceIn(0.8f, 1.2f),
                lidY = e.lidY * open,
                // Bent spines (^ arcs) scale uniformly so the arc stays clean and the two
                // eyes stay mirror images; straight capsules may stretch freely.
                pts = if (isBent(e.pts)) { val u = (p.sx + p.sy) / 2f; e.pts.map { floatArrayOf(it[0] * u, it[1] * u) } }
                    else e.pts.map { floatArrayOf(it[0] * p.sx, it[1] * p.sy) },
            )
        }
    }
}
