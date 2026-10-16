package me.rerere.rikkahub.ui.components.avatar.animated

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath

/**
 * Engine20.1: editor selection rings.
 *
 * [silhouette] is the exact outline a calm preview draws in a [canvasPx] square canvas,
 * built by the same steps [drawMark] uses (centred body → face-on projection → body transform),
 * so the ring follows the real drawn shape, not an assumed size.
 *
 * [ring] is a true constant-distance band around it: the region between the offsets at
 * distance gap and gap + stroke. An outward offset is the Minkowski sum with a disk, built from
 * round-joined stroke outlines, so convex corners come out as arcs. At concave corners or notches
 * (heart top, star waist, clover lobes) a plain offset leaves a sharp inward cusp. Both offsets
 * therefore get a morphological closing with radius [round] (offset out by r, then back in by r),
 * which turns those cusps into fillets. The stroke stays even and every corner reads as round.
 */
internal object SelectionRing {
    private val cache = HashMap<String, Path>()

    fun silhouette(shapeId: String, canvasPx: Float, polygon: Boolean = true): Path? {
        val shape = MarkShapes.resolve(shapeId) ?: return null
        val f = CALM_FRAME
        val he = AvatarPackData.grok.headCenter
        val body = Path().apply { addPath(AvatarPackData.parseSvgPath(shape.pathData), Offset(-he, -he)) }
        val sizePx = canvasPx * Soft3DMark.FILL * shape.scale.coerceAtLeast(0.95f)
        val proj = Soft3DMark.projectBody(body, shape.solid, 0f, 0f, 0f, he, shapeId = shape.id)
        val clipScale = MarkClipEngine.visualScale(MarkClipEngine.DEFAULT_ZOOM * f.body.scale)
        val k = (sizePx * 0.5f) / he.coerceAtLeast(1f) * clipScale
        val m = android.graphics.Matrix().apply {
            setScale(k * f.body.squashX, k * f.body.squashY)
            postTranslate(canvasPx / 2f, canvasPx / 2f - f.body.posY * sizePx * 0.18f)
        }
        val out = android.graphics.Path(proj.asAndroidPath()); out.transform(m)
        // Outer boundary only, as a clean dense polygon (some SVG contours carry cusps / tiny
        // self-overlaps that make the stroker's outline unreliable for path ops).
        val union = out.asComposePath().let { p -> Path().apply { op(p, Path(), PathOperation.Union) } }
        if (!polygon) return union
        val pts = resamplePath(union, 360)
        if (pts.size < 3) return union
        return Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y); close() }
    }

    /** P ⊕ disk(d): the fill plus a round-joined stroke of width 2d. */
    private fun grow(p: Path, d: Float): Path {
        if (d <= 0f) return p
        val paint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f * d
            strokeJoin = android.graphics.Paint.Join.ROUND; strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val s = android.graphics.Path(); paint.getFillPath(p.asAndroidPath(), s)
        return Path().apply { op(p, s.asComposePath(), PathOperation.Union) }
    }

    /** P ⊖ disk(d) = P minus a stroke of width 2d along its boundary. */
    private fun shrink(p: Path, d: Float): Path {
        val paint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f * d
            strokeJoin = android.graphics.Paint.Join.ROUND; strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val s = android.graphics.Path(); paint.getFillPath(p.asAndroidPath(), s)
        return Path().apply { op(p, s.asComposePath(), PathOperation.Difference) }
    }

    /** Offset by d with concave corners filleted (closing radius r). */
    fun offset(p: Path, d: Float, r: Float): Path = shrink(grow(p, d + r), r)

    /** The ring band in canvas pixels (cached per shape and size). */
    fun ring(shapeId: String, canvasPx: Float, gapPx: Float, strokePx: Float, roundPx: Float): Path? {
        val key = "$shapeId/${canvasPx.toInt()}/${gapPx.toInt()}/${strokePx}/${roundPx.toInt()}"
        cache[key]?.let { return it }
        fun build(poly: Boolean): Path? {
            val sil = silhouette(shapeId, canvasPx, poly) ?: return null
            val outer = offset(sil, gapPx + strokePx, roundPx)
            val inner = offset(sil, gapPx, roundPx)
            return Path().apply { op(outer, inner, PathOperation.Difference) }
        }
        // Path ops occasionally give up on one representation of a contour; use the other.
        val ring = build(true)?.takeUnless { it.isEmpty || it.getBounds().width < canvasPx * 0.5f } ?: build(false) ?: return null
        cache[key] = ring
        return ring
    }
}
