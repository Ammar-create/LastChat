package me.rerere.rikkahub.ui.components.avatar.animated

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Engine18: Angry / Sad stills (Grok + Generical, two shapes) for the tone-down before/after. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ToneStillTest {
    @Test
    fun renderAngrySad() {
        val dir = System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() } ?: return
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val B = Color(0xFF009FE0); val W = Color.White; val G = MarkColors.genericalEyeFade(B, W, 1f)
        val px = 360
        val exprs = listOf("Neutral", "Angry", "Sad")
        val rows = listOf("blob" to false, "blob" to true, "square" to false, "square" to true)
        val img = ImageBitmap(px * exprs.size, px * rows.size)
        val canvas = androidx.compose.ui.graphics.Canvas(img)
        val scope = CanvasDrawScope()
        for ((ri, row) in rows.withIndex()) for ((ci, e) in exprs.withIndex()) {
            canvas.save(); canvas.translate((ci * px).toFloat(), (ri * px).toFloat())
            scope.draw(Density(3f), LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat())) {
                drawRect(Color(0xFF1B1D22))
                drawMark(MarkShapes.resolve(row.first)!!, row.first, row.second, MotionFrame(exprFrom = e, exprTo = e, exprT = 1f), true, 1f, B, W, G)
            }
            canvas.restore()
        }
        File(dir).mkdirs()
        File(dir, "tone_angry_sad.png").outputStream().use { img.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
