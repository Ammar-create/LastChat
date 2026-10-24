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

/**
 * Engine17: zoomed editor-size renders (the big 164 dp preview at xxhdpi) of expressions,
 * transition midpoints and blinks, to catch faceted/jagged outlines and other artifacts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ArtifactStripTest {
    private val exprs = listOf("Neutral", "Happy", "Bored", "Sad", "Surprised", "Worried", "confused", "Angry",
        "angered or concentrated", "pouty", "hurt or cute", "Eyes closed", "Sleeping")

    private fun frames(): List<Pair<String, MotionFrame>> {
        val rig = FaceRig(kotlin.random.Random(3))
        val out = ArrayList<Pair<String, MotionFrame>>()
        for (e in exprs) out += e to MotionFrame(exprFrom = e, exprTo = e)
        for ((a, b) in listOf("Neutral" to "Happy", "Neutral" to "Bored", "Happy" to "Surprised", "Neutral" to "Eyes closed", "Sad" to "Worried"))
            for (t in listOf(0.3f, 0.5f, 0.7f)) out += "$a>$b@$t" to MotionFrame(exprFrom = a, exprTo = b, exprT = t)
        for (l in listOf(0.7f, 0.4f, 0.15f)) out += "lid$l" to MotionFrame(lid = l)
        for (a in listOf(FaceAccent.Curious, FaceAccent.Glee, FaceAccent.Puzzled, FaceAccent.Drowsy, FaceAccent.Worried, FaceAccent.Smug))
            out += "acc_${a.name}" to MotionFrame(face = rig.staticFace(a, 0.3f))
        out += "turned" to MotionFrame(body = BodyPose(yaw = -16f, pitch = 6f, roll = 3f), headLookX = 0.35f)
        return out
    }

    @Test
    fun renderArtifactStrips() {
        val dir = System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() } ?: return
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val px = 492 // 164 dp @ 3x = editor preview
        val shapes = (System.getProperty("lastchat.artShapes") ?: "cloud,blob,square,heart,star").split(",")
        for (mode in listOf("generical", "grok")) for (id in shapes) {
            val sh = MarkShapes.resolve(id) ?: continue
            val fs = frames()
            val cols = 8; val rows = (fs.size + cols - 1) / cols
            val img = ImageBitmap(px * cols, px * rows)
            val canvas = androidx.compose.ui.graphics.Canvas(img)
            canvas.drawRect(0f, 0f, (px * cols).toFloat(), (px * rows).toFloat(), androidx.compose.ui.graphics.Paint().apply { color = Color(0xFF1B1D22) })
            for ((k, p) in fs.withIndex()) {
                canvas.save(); canvas.translate(((k % cols) * px).toFloat(), ((k / cols) * px).toFloat())
                CanvasDrawScope().draw(Density(3f), LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat())) {
                    drawMark(sh, id, mode == "grok", p.second, false, 1f, Color(0xFF009FE0), Color.White,
                        MarkColors.genericalEyeFade(Color(0xFF009FE0), Color.White, 1f))
                }
                canvas.restore()
            }
            File(dir).mkdirs()
            File(dir, "art_${mode}_$id.png").outputStream().use { img.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "art_${mode}_$id.txt").writeText(fs.mapIndexed { i, p -> "$i ${p.first}" }.joinToString("\n"))
        }
    }
}
