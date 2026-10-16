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

/** Engine17.2: mid-morph frames at editor size (492 px) vs the static end frame, for resolution. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class MorphResStripTest {
    @Test
    fun renderMorphResolution() {
        val dir = System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() } ?: return
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val B = Color(0xFF009FE0); val W = Color.White; val G = MarkColors.genericalEyeFade(B, W, 1f)
        val px = 492
        val pairs = listOf("square" to "star", "blob" to "clover", "heart" to "flower", "cloud" to "sparkle")
        val us = listOf(0.35f, 0.65f, 1f)
        val img = ImageBitmap(px * us.size * 2, px * pairs.size)
        val canvas = androidx.compose.ui.graphics.Canvas(img)
        canvas.drawRect(0f, 0f, img.width.toFloat(), img.height.toFloat(), androidx.compose.ui.graphics.Paint().apply { color = Color(0xFF1B1D22) })
        val scope = CanvasDrawScope()
        for ((r, p) in pairs.withIndex()) for ((m, grok) in listOf(false, true).withIndex()) {
            val a = MarkShapes.resolve(p.first)!!; val b = MarkShapes.resolve(p.second)!!
            val h = MorphHolder()
            fun at(c: Int, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
                canvas.save(); canvas.translate((c * px).toFloat(), (r * px).toFloat())
                scope.draw(Density(3f), LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat()), block)
                canvas.restore()
            }
            val off = androidx.compose.ui.graphics.ImageBitmap(px, px)
            val oc = androidx.compose.ui.graphics.Canvas(off)
            scope.draw(Density(3f), LayoutDirection.Ltr, oc, Size(px.toFloat(), px.toFloat())) { drawLive(h, a, p.first, grok, MotionFrame(), true, B, W, G, 1f) }
            h.signatureChanged()
            scope.draw(Density(3f), LayoutDirection.Ltr, oc, Size(px.toFloat(), px.toFloat())) { drawLive(h, b, p.second, grok, MotionFrame(), true, B, W, G, 0f) }
            h.forceZero = false
            for ((k, u) in us.withIndex()) at(m * us.size + k) { drawLive(h, b, p.second, grok, MotionFrame(), true, B, W, G, u) }
        }
        File(dir).mkdirs()
        File(dir, "morph_res.png").outputStream().use { img.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
