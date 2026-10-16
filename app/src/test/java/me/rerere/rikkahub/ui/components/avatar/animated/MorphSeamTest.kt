package me.rerere.rikkahub.ui.components.avatar.animated

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Engine16.2: the morph renderer must draw exactly what drawMark draws at its endpoints
 * (no size/position pop when a shape or eye-style morph starts or ends), also turned.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class MorphSeamTest {
    private fun cover(draw: DrawScope.() -> Unit): Int {
        val img = ImageBitmap(300, 300)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(300f, 300f)) {
            translate(75f, 75f) { drawContext.size = Size(150f, 150f); draw() }
        }
        val b = img.asAndroidBitmap()
        var n = 0
        for (y in 0 until 300) for (x in 0 until 300) if ((b.getPixel(x, y) ushr 24) > 128) n++
        return n
    }

    @Test
    fun morphEndpointsMatchMark() {
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val poses = listOf(
            MotionFrame(),
            MotionFrame(body = BodyPose(pitch = 6f, yaw = -14f, roll = 3f, posY = 0.05f), headLookX = 0.3f, headLookY = 0.2f),
            MotionFrame(body = BodyPose(pitch = -4f, yaw = 18f, roll = -4f), headLookX = -0.4f, headLookY = -0.1f),
        )
        for (id in MarkShapes.SHAPES) for (grok in listOf(false, true)) for ((pi, f) in poses.withIndex()) {
            val sh = MarkShapes.resolve(id)!!
            val mark = cover { drawMark(sh, id, grok, f, false, 1f, Color.Blue, Color.White, Color.Gray) }
            val morph = cover {
                val g = MarkGeom().also { drawMark(sh, id, grok, f, false, 1f, Color.Blue, Color.White, Color.Gray, it) }
                drawMorph(g, MorphPts.of(g), g, 0.5f, MarkGeom())
            }
            val d = kotlin.math.abs(mark - morph).toFloat() / mark
            assertTrue("$id grok=$grok pose=$pi coverage mark=$mark morph=$morph", d < 0.03f)
        }
    }
}
