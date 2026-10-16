package me.rerere.rikkahub.ui.components.avatar.animated

import androidx.compose.ui.graphics.Path

/**
 * Engine17: closed outlines drawn as a smooth curve through their sample points
 * (uniform Catmull-Rom → cubic Béziers) instead of straight segments. At the big editor
 * preview the 64–96-point eye polygons showed visible facets (narrow lids, round caps,
 * transitions); the curve passes through the same points, so shapes are unchanged at
 * small sizes and stay smooth when large.
 */
internal object SmoothPath {
    /** [x]/[y] give point i of [n]; appended to [into] as one closed contour. */
    inline fun closed(n: Int, into: Path = Path(), x: (Int) -> Float, y: (Int) -> Float): Path {
        if (n < 3) return into
        into.moveTo(x(0), y(0))
        for (i in 0 until n) {
            val i0 = (i - 1 + n) % n; val i2 = (i + 1) % n; val i3 = (i + 2) % n
            val x1 = x(i); val y1 = y(i); val x2 = x(i2); val y2 = y(i2)
            into.cubicTo(
                x1 + (x2 - x(i0)) / 6f, y1 + (y2 - y(i0)) / 6f,
                x2 - (x(i3) - x1) / 6f, y2 - (y(i3) - y1) / 6f,
                x2, y2,
            )
        }
        into.close()
        return into
    }
}
