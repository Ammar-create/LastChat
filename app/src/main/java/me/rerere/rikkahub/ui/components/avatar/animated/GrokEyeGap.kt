package me.rerere.rikkahub.ui.components.avatar.animated

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot
import kotlin.math.max

/**
 * Keeps the two seated Grok eyes apart: in any expression, pose or mid-morph, the
 * closest points of the two outlines stay at least [MIN_GAP] × mark radius apart.
 * When they would come closer, both eyes slide apart symmetrically along the line
 * between their centres — just enough, so a normal face is untouched and spacing
 * changes continuously (no pops during morphs).
 */
object GrokEyeGap {
    /** Minimum clear gap between the eyes, as a fraction of the mark radius. */
    const val MIN_GAP = 0.40f
    private const val SAMPLES = 72

    fun outline(p: Path): Array<FloatArray> {
        val pm = android.graphics.PathMeasure(p.asAndroidPath(), true)
        val xs = ArrayList<Float>(SAMPLES)
        val ys = ArrayList<Float>(SAMPLES)
        val pos = FloatArray(2)
        do {
            val len = pm.length
            if (len <= 0f) continue
            for (i in 0 until SAMPLES) {
                pm.getPosTan(len * i / SAMPLES, pos, null)
                xs += pos[0]; ys += pos[1]
            }
        } while (pm.nextContour())
        return arrayOf(xs.toFloatArray(), ys.toFloatArray())
    }

    private fun inside(px: Float, py: Float, o: Array<FloatArray>): Boolean {
        val x = o[0]; val y = o[1]; val n = x.size
        var c = false
        var j = n - 1
        for (i in 0 until n) {
            if ((y[i] > py) != (y[j] > py) && px < (x[j] - x[i]) * (py - y[i]) / (y[j] - y[i]) + x[i]) c = !c
            j = i
        }
        return c
    }

    /** Signed clearance between outlines (negative = overlapping). */
    fun clearance(a: Array<FloatArray>, b: Array<FloatArray>, dx: Float = 0f, dy: Float = 0f): Float {
        var best = Float.MAX_VALUE
        val ax = a[0]; val ay = a[1]; val bx = b[0]; val by = b[1]
        for (i in ax.indices) for (j in bx.indices) {
            val d = hypot(ax[i] - (bx[j] + dx), ay[i] - (by[j] + dy))
            if (d < best) best = d
        }
        var overlap = false
        for (i in ax.indices step 3) if (inside(ax[i] - dx, ay[i] - dy, b)) { overlap = true; break }
        if (!overlap) for (j in bx.indices step 3) if (inside(bx[j] + dx, by[j] + dy, a)) { overlap = true; break }
        return if (overlap) -best else best
    }

    /**
     * Returns the offsets to apply to (left, right) so the eyes keep the minimum gap.
     * [he] = mark radius in the same units as the paths.
     */
    /**
     * [faceScale] < 1 for shapes whose face is smaller than the circle's (heart, star, …):
     * the gap is the circle's 0.40 relative to *that face*, so the spacing reads the same.
     */
    fun separation(left: Path, right: Path, he: Float, faceScale: Float = 1f): Pair<Offset, Offset> {
        if (left.isEmpty || right.isEmpty) return Offset.Zero to Offset.Zero
        val a = outline(left); val b = outline(right)
        if (a[0].isEmpty() || b[0].isEmpty()) return Offset.Zero to Offset.Zero
        val need = MIN_GAP * faceScale * he
        if (clearance(a, b) >= need) return Offset.Zero to Offset.Zero
        // Direction: from the left eye's centre to the right eye's.
        var ux = b[0].average().toFloat() - a[0].average().toFloat()
        var uy = b[1].average().toFloat() - a[1].average().toFloat()
        val len = hypot(ux, uy)
        if (len < 1e-3f) { ux = 1f; uy = 0f } else { ux /= len; uy /= len }
        // Smallest extra spacing s (split between both eyes) with enough clearance.
        var lo = 0f
        var hi = max(need * 2f, he * 0.1f)
        while (clearance(a, b, ux * hi, uy * hi) < need && hi < he * 1.5f) hi *= 1.6f
        repeat(14) {
            val mid = (lo + hi) / 2f
            if (clearance(a, b, ux * mid, uy * mid) >= need) hi = mid else lo = mid
        }
        val h = hi / 2f
        return Offset(-ux * h, -uy * h) to Offset(ux * h, uy * h)
    }
}
