package me.rerere.rikkahub.ui.components.avatar.animated

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rerere.rikkahub.data.model.Avatar

/** Still image of a mark at its calm pose (launcher shortcuts and other non-Compose surfaces). */
object MarkBitmap {
    /**
     * Draws [avatar] into a transparent [sizePx] square; the mark spans [markFraction]
     * of it, centred. Null if the avatar pack can't be loaded.
     */
    fun render(context: Context, avatar: Avatar.Animated, sizePx: Int, markFraction: Float = 1f): Bitmap? {
        runCatching { AvatarPackData.ensureLoaded(context) }
        if (!AvatarPackData.isLoaded()) return null
        val shape = MarkShapes.resolve(avatar.shape) ?: return null
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val markPx = sizePx * markFraction.coerceIn(0.1f, 1f)
        val inset = (sizePx - markPx) / 2f
        val body = MarkColors.bodyColor(avatar.colorHex, avatar.colorPreset, avatar.eyeType)
        val eye = MarkColors.eyeColor(avatar.eyeType, avatar.eyeColorHex, avatar.colorHex, avatar.colorPreset)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(android.graphics.Canvas(bitmap)),
            size = Size(markPx, markPx),
        ) {
            translate(inset, inset) {
                drawMark(
                    shape = shape,
                    shapeId = avatar.shape,
                    isGrok = avatar.eyeType.equals("grok", true),
                    f = CALM_FRAME,
                    calm = true,
                    morph = 1f,
                    body = body,
                    eye = eye,
                    eyeFade = MarkColors.genericalEyeFade(body, eye),
                )
            }
        }
        return bitmap
    }
}
