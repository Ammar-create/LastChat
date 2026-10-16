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
 * Engine17.1: critical Grok audit sheets. One row per likely transition (t = 0 … 1) and one
 * blink row per expression (lid 1 → 0.05), rendered at editor scale on circle/heart/square.
 * Writes grok_audit_{shape}.png + .txt row index into lastchat.renderDir.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class GrokAuditTest {
    private val pairs = listOf(
        "Neutral" to "Happy", "Neutral" to "Bored", "Neutral" to "Sad", "Neutral" to "Surprised",
        "Neutral" to "confused", "Neutral" to "Angry", "Neutral" to "Eyes closed", "Happy" to "Surprised",
        "Happy" to "Sad", "Happy" to "Bored", "Surprised" to "Sad", "Sad" to "Bored", "Bored" to "Eyes closed",
        "Happy" to "Eyes closed", "Angry" to "Sad", "confused" to "Happy",
    )
    private val exprs = listOf("Neutral", "Happy", "Bored", "Sad", "Surprised", "confused", "Angry", "Eyes closed")
    private val ts = listOf(0f, 0.15f, 0.3f, 0.45f, 0.55f, 0.7f, 0.85f, 1f)
    private val lids = listOf(1f, 0.8f, 0.6f, 0.4f, 0.25f, 0.12f, 0.05f, 0.5f)

    @Test
    fun renderGrokAudit() {
        val dir = System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() } ?: return
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val px = 300
        val rows = ArrayList<Pair<String, List<MotionFrame>>>()
        for ((a, b) in pairs) rows += "$a>$b" to ts.map { MotionFrame(exprFrom = a, exprTo = b, exprT = it) }
        for (e in exprs) rows += "blink $e" to lids.map { MotionFrame(exprFrom = e, exprTo = e, lid = it) }
        for (id in listOf("blob", "heart", "square")) {
            val sh = MarkShapes.resolve(id) ?: continue
            val cols = ts.size
            val img = ImageBitmap(px * cols, px * rows.size)
            val canvas = androidx.compose.ui.graphics.Canvas(img)
            canvas.drawRect(0f, 0f, (px * cols).toFloat(), (px * rows.size).toFloat(), androidx.compose.ui.graphics.Paint().apply { color = Color(0xFF1B1D22) })
            for ((r, row) in rows.withIndex()) for ((c, f) in row.second.withIndex()) {
                canvas.save(); canvas.translate((c * px).toFloat(), (r * px).toFloat())
                CanvasDrawScope().draw(Density(3f), LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat())) {
                    drawMark(sh, id, true, f, false, 1f, Color(0xFF009FE0), Color.White,
                        MarkColors.genericalEyeFade(Color(0xFF009FE0), Color.White, 1f))
                }
                canvas.restore()
            }
            File(dir).mkdirs()
            File(dir, "grok_audit_$id.png").outputStream().use { img.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "grok_audit_$id.txt").writeText(rows.mapIndexed { i, p -> "$i ${p.first}" }.joinToString("\n"))
        }
    }
}
