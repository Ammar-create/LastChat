package me.rerere.rikkahub.ui.components.avatar.animated

import android.graphics.Region
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath

/**
 * Keeps the eyes inside the shape's safe face region during turns: on narrow or
 * pointy silhouettes (heart, triangle…) a strong turn would otherwise slide the eyes
 * onto the edge. The caller scales the turn applied to the eyes by the largest
 * factor that keeps every outline point (pushed outward by a margin) inside the body.
 */
internal object EyeSafeRegion {
    private const val SCALE = 4f

    class Body(path: Path) {
        private val region = Region()
        init {
            val p = android.graphics.Path(path.asAndroidPath())
            val m = android.graphics.Matrix().apply { setScale(SCALE, SCALE) }
            p.transform(m)
            val b = android.graphics.RectF()
            p.computeBounds(b, true)
            region.setPath(p, Region(b.left.toInt() - 2, b.top.toInt() - 2, b.right.toInt() + 2, b.bottom.toInt() + 2))
        }
        fun contains(x: Float, y: Float) = region.contains((x * SCALE).toInt(), (y * SCALE).toInt())
    }

    fun inside(body: Body, eyes: List<Path>, margin: Float): Boolean {
        for (e in eyes) {
            if (e.isEmpty) continue
            val o = GrokEyeGap.outline(e)
            val xs = o[0]; val ys = o[1]
            if (xs.isEmpty()) continue
            val cx = xs.average().toFloat(); val cy = ys.average().toFloat()
            for (i in xs.indices step 3) {
                val dx = xs[i] - cx; val dy = ys[i] - cy
                val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
                if (!body.contains(xs[i] + dx / l * margin, ys[i] + dy / l * margin)) return false
            }
        }
        return true
    }

    /** Largest k in 0..1 (k = 1 first) for which [seat](k) keeps the eyes inside. */
    fun <T> fit(body: Body, margin: Float, seat: (Float) -> T, paths: (T) -> List<Path>): T {
        val full = seat(1f)
        if (inside(body, paths(full), margin)) return full
        var lo = 0f; var hi = 1f
        var best = seat(0f)
        if (!inside(body, paths(best), margin)) return best
        repeat(5) {
            val mid = (lo + hi) / 2f
            val r = seat(mid)
            if (inside(body, paths(r), margin)) { lo = mid; best = r } else hi = mid
        }
        return best
    }
}
