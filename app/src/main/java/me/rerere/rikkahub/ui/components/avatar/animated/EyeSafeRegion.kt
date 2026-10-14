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
        val center: androidx.compose.ui.geometry.Offset
        init {
            val bb = android.graphics.RectF()
            path.asAndroidPath().computeBounds(bb, true)
            center = androidx.compose.ui.geometry.Offset(bb.centerX(), bb.centerY())
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

    /**
     * Second line of defence: when even the untilted seat touches the edge (pointy
     * shapes, big turns), slide the eyes toward the body centre just far enough.
     * Returns the offset to add (Zero when already inside).
     */
    fun nudge(body: Body, eyes: List<Path>, margin: Float): androidx.compose.ui.geometry.Offset {
        if (inside(body, eyes, margin)) return androidx.compose.ui.geometry.Offset.Zero
        var sx = 0f; var sy = 0f; var n = 0
        for (e in eyes) { if (e.isEmpty) continue; val b = e.getBounds(); sx += b.center.x; sy += b.center.y; n++ }
        if (n == 0) return androidx.compose.ui.geometry.Offset.Zero
        val dir = body.center - androidx.compose.ui.geometry.Offset(sx / n, sy / n)
        fun moved(t: Float) = eyes.map { Path().apply { addPath(it, dir * t) } }
        if (!inside(body, moved(1f), margin)) return dir
        var lo = 0f; var hi = 1f
        repeat(7) { val mid = (lo + hi) / 2f; if (inside(body, moved(mid), margin)) hi = mid else lo = mid }
        return dir * hi
    }

    /** Smallest clear distance from the eye outlines to the body edge (debug metric). */
    fun inset(body: Body, eyes: List<Path>, maxM: Float): Float {
        if (!inside(body, eyes, 0f)) return -1f
        var lo = 0f; var hi = maxM
        if (inside(body, eyes, hi)) return hi
        repeat(10) { val mid = (lo + hi) / 2f; if (inside(body, eyes, mid)) lo = mid else hi = mid }
        return lo
    }
}

/** Debug-only per-frame metrics recorded by drawMark when enabled (render harness). */
internal object AvatarDebugMetrics {
    @Volatile var enabled = false
    /** (label, grok gap / he, edge inset / he) per drawn eye pair. */
    val rows = java.util.Collections.synchronizedList(ArrayList<Triple<String, Float, Float>>())
    @Volatile var label = ""
}
