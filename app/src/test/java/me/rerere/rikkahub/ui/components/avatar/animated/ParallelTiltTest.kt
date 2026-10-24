package me.rerere.rikkahub.ui.components.avatar.animated

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * Engine21.2 — Julian's rule: two eyes tilted the same way (// or \\) is never good; mirrored
 * tilts (/\ or \/) and one tilted eye next to an upright one are fine.
 *
 * Fails when both eyes' tilt angles share a sign above [THRESH]:
 *  - every expression's and every accent's REST pose as authored (no guard), both eye styles;
 *  - as rendered (with [TiltGuard]): every expression + accent, mid-morphs between every pair of
 *    expressions, Grok gaze turns, and head roll (measured relative to the roll).
 * Generical eyes are measured on the top and bottom lid edges; Grok pills on their long axis.
 * Report: parallel_tilt_report.txt in lastchat.renderDir. `-Dlastchat.gridModes=raw` audits
 * without the guard (report only, to see what the guard is catching).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParallelTiltTest {
    private val outDir: File? = System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }
    private val raw = System.getProperty("lastchat.gridModes") == "raw"

    private class Pair2(val tag: String, val l: Float, val r: Float)

    private fun parallel(p: Pair2) = (p.l > THRESH && p.r > THRESH) || (p.l < -THRESH && p.r < -THRESH)
    private fun fmt(p: Pair2) = String.format("%-60s L %+6.1f°  R %+6.1f°", p.tag, p.l, p.r)

    private fun bottomTilt(x: FloatArray, y: FloatArray): Float {
        val ny = FloatArray(y.size) { -y[it] }      // the bottom edge = the top edge of the flipped eye
        return -TiltGuard.topTilt(x, ny)
    }

    private fun genPairs(from: String, to: String, t: Float, face: FaceParams, tag: String, guard: Boolean): List<Pair2> {
        val lo = GenericalFace.outline(0, from, to, t, 0f) ?: return emptyList()
        val ro = GenericalFace.outline(1, from, to, t, 0f) ?: return emptyList()
        FaceRig.shapeOutline(lo.x, lo.y, 0, face.left); FaceRig.shapeOutline(ro.x, ro.y, 1, face.right)
        if (guard && !raw) TiltGuard.generical(lo.x, lo.y, ro.x, ro.y)
        return listOf(
            Pair2("generical $tag top", TiltGuard.topTilt(lo.x, lo.y), TiltGuard.topTilt(ro.x, ro.y)),
            Pair2("generical $tag bottom", bottomTilt(lo.x, lo.y), bottomTilt(ro.x, ro.y)),
        )
    }

    private fun grokPair(
        from: String, to: String, t: Float, face: FaceParams, tag: String, guard: Boolean,
        pitch: Float = 0f, yaw: Float = 0f, roll: Float = 0f,
    ): Pair2 {
        val sym = face.grokSymmetric()
        val pair = WhSphereEyes.morphPair(from, to, t, 1f).mapIndexed { i, e -> FaceRig.shapeCapsule(e, i, sym.eye(i)) }
        var (l, r) = WhSphereEyes.projectPair(pair, pitch, yaw, roll, 100f)
        if (guard && !raw) TiltGuard.grok(l, r, roll).let { l = it.first; r = it.second }
        val (tl, tr) = TiltGuard.grokTilts(l, r, roll)
        return Pair2("grok $tag", tl, tr)
    }

    private fun accentFace(a: FaceAccent): FaceParams = FaceRig(Random(1)).accentPreview(a)

    @Test
    fun noParallelTilt() {
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val exprs = (AvatarPackData.generical.expressions.keys + "confused left").distinct()
        val accents = FaceAccent.entries.filter { it != FaceAccent.None && it != FaceAccent.Dizzy }
        val rest = ArrayList<Pair2>(); val combo = ArrayList<Pair2>(); val morph = ArrayList<Pair2>(); val gaze = ArrayList<Pair2>()
        // Rest poses as AUTHORED (no guard): every expression, every accent on the rest face.
        for (e in exprs) { rest += genPairs(e, e, 1f, FaceParams.ID, e, false); rest += grokPair(e, e, 1f, FaceParams.ID, e, false) }
        for (a in accents) {
            val f = accentFace(a)
            rest += genPairs("Neutral", "Neutral", 1f, f, "·$a", false); rest += grokPair("Neutral", "Neutral", 1f, f, "·$a", false)
        }
        // As rendered (guard on): expression × accent, mid-morphs, Grok gaze turns.
        for (e in exprs) {
            for (a in accents) {
                val f = accentFace(a)
                combo += genPairs(e, e, 1f, f, "$e+$a", true); combo += grokPair(e, e, 1f, f, "$e+$a", true)
            }
            for (p in listOf(-14f, 0f, 14f)) for (y in listOf(-18f, 0f, 18f)) if (p != 0f || y != 0f)
                gaze += grokPair(e, e, 1f, FaceParams.ID, "$e gaze p$p y$y", true, p, y)
        }
        for (a in exprs) for (b in exprs) if (a != b) for (t in listOf(0.25f, 0.5f, 0.75f)) {
            morph += genPairs(a, b, t, FaceParams.ID, "$a>$b@$t", true); morph += grokPair(a, b, t, FaceParams.ID, "$a>$b@$t", true)
        }
        val fr = rest.filter(::parallel); val fc = combo.filter(::parallel); val fm = morph.filter(::parallel); val fg = gaze.filter(::parallel)
        outDir?.let {
            File(it, "parallel_tilt_report.txt").writeText(
                "threshold $THRESH°${if (raw) "  (RAW: guard off)" else ""}  rest ${rest.size} (fail ${fr.size})  combo ${combo.size} (fail ${fc.size})  morph ${morph.size} (fail ${fm.size})  grok gaze ${gaze.size} (fail ${fg.size})\n" +
                    "--- rest fails\n" + fr.joinToString("\n", transform = ::fmt) +
                    "\n--- combo fails\n" + fc.joinToString("\n", transform = ::fmt) +
                    "\n--- morph fails\n" + fm.joinToString("\n", transform = ::fmt) +
                    "\n--- gaze fails\n" + fg.joinToString("\n", transform = ::fmt) +
                    "\n--- all rest\n" + rest.joinToString("\n", transform = ::fmt) + "\n")
        }
        if (raw) return
        // Head roll is a whole-head tilt: the measure is relative to it, so a rolled neutral
        // reads as two upright eyes, and the guard leaves it alone.
        for (rl in listOf(-9f, 9f)) for (g in listOf(false, true)) {
            val p = grokPair("Neutral", "Neutral", 1f, FaceParams.ID, "Neutral roll $rl", g, roll = rl)
            assertTrue("roll $rl guard $g: ${p.l} ${p.r}", abs(p.l) < 1f && abs(p.r) < 1f)
        }
        assertTrue("parallel tilts (rest):\n" + fr.take(25).joinToString("\n", transform = ::fmt), fr.isEmpty())
        assertTrue("parallel tilts (expression + accent):\n" + fc.take(25).joinToString("\n", transform = ::fmt), fc.isEmpty())
        assertTrue("parallel tilts (mid-morph):\n" + fm.take(25).joinToString("\n", transform = ::fmt), fm.isEmpty())
        assertTrue("parallel tilts (gaze):\n" + fg.take(25).joinToString("\n", transform = ::fmt), fg.isEmpty())
    }

    companion object { const val THRESH = 3f }
}
