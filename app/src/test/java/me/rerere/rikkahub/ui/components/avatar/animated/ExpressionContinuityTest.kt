package me.rerere.rikkahub.ui.components.avatar.animated

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * Engine17: the expression system must be continuous — a change of mind mid-morph bends
 * the face toward the new target at once (no queue, no dwell plateau, no pop).
 * Compares the engine16 director (verbatim legacy copy) with the layered one on the same
 * expression train, measured on real Generical outlines.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class ExpressionContinuityTest {

    /** Engine16 director, verbatim logic (queue + dwell). */
    private class Legacy {
        var from = "Neutral"; var to = "Neutral"; var t = 1f; var dur = 0.18f; var dwell = 10f
        var pending: String? = null; var pendingDur = 0f; var snap = true
        val eased get() = if (snap) MotionVocab.easeOutCubic(t) else MotionVocab.easeInOutSine(t)
        fun want(x: String, d: Float, s: Boolean) {
            if (x == to) { pending = null; return }
            if (t < 1f || dwell < 0.25f) { pending = x; pendingDur = d; return }
            from = to; to = x; t = 0f; dur = d; snap = s; pending = null
        }
        fun update(dt: Float) {
            if (t < 1f) { t = min(1f, t + dt / dur); if (t >= 1f) dwell = 0f }
            else { dwell += dt; val p = pending; if (p != null && dwell >= 0.25f) { from = to; to = p; t = 0f; dur = pendingDur; pending = null } }
        }
    }

    private val train = listOf( // time, target, style
        0.0f to ("Happy" to ExpressionDirector.Style.Snap),
        0.12f to ("Surprised" to ExpressionDirector.Style.Snap), // poke mid-smile
        0.9f to ("Worried" to ExpressionDirector.Style.Soft),
        1.08f to ("Sad" to ExpressionDirector.Style.Soft),       // change of mind
        2.2f to ("Neutral" to ExpressionDirector.Style.Soft),
        2.32f to ("confused" to ExpressionDirector.Style.Snap),
        3.4f to ("Happy" to ExpressionDirector.Style.Soft),
        3.5f to ("Surprised" to ExpressionDirector.Style.Snap),
        3.62f to ("Happy" to ExpressionDirector.Style.Snap),
        5.0f to ("Neutral" to ExpressionDirector.Style.Soft),
    )

    private fun shape(from: String, to: String, t: Float, top: String?, topT: Float): GenericalFace.Outline =
        GenericalFace.outline(0, from, to, t, 0f, top, topT)!!

    /** Symmetric mean nearest-point distance (index-independent). */
    private fun dist(a: GenericalFace.Outline, b: GenericalFace.Outline): Float {
        fun one(p: GenericalFace.Outline, q: GenericalFace.Outline): Float {
            var s = 0f
            for (i in p.x.indices) { var m = Float.MAX_VALUE; for (j in q.x.indices) m = min(m, hypot(p.x[i] - q.x[j], p.y[i] - q.y[j])); s += m }
            return s / p.x.size
        }
        return 0.5f * (one(a, b) + one(b, a))
    }

    private data class Stats(val maxStep: Float, val stallFrames: Int, val maxJerk: Float, val arrive: Float)

    private fun run(legacy: Boolean, csv: StringBuilder): Stats {
        val dt = 1f / 60f
        val lg = Legacy(); val nw = ExpressionDirector(Random(1))
        var k = 0; var prev: GenericalFace.Outline? = null; var prevStep = 0f
        var maxStep = 0f; var stalls = 0; var maxJerk = 0f; var intent = "Neutral"; var arrive = 0f
        val finalShape = HashMap<String, GenericalFace.Outline>()
        for (f in 0 until (6.5f / dt).toInt()) {
            val time = f * dt
            while (k < train.size && train[k].first <= time + 1e-4f) {
                val (x, st) = train[k].second; intent = x
                val d = if (st == ExpressionDirector.Style.Snap) 0.17f else 0.33f
                if (legacy) lg.want(x, d, st == ExpressionDirector.Style.Snap) else { nw.hint = st; nw.want(x) }
                k++
            }
            if (legacy) lg.update(dt) else nw.update(dt, 0f)
            val s = if (legacy) shape(lg.from, lg.to, lg.eased, null, 0f)
                else shape(nw.from, nw.pairTo, nw.eased, nw.top, nw.topEased)
            val goal = finalShape.getOrPut(intent) { shape(intent, intent, 1f, null, 0f) }
            val off = dist(s, goal)
            val step = prev?.let { dist(it, s) } ?: 0f
            if (f > 0) {
                maxStep = maxOf(maxStep, step); maxJerk = maxOf(maxJerk, abs(step - prevStep))
                if (step < 0.05f && off > 1.5f) stalls++
                if (off > 0.5f) arrive = time
            }
            csv.append("${if (legacy) "legacy" else "engine17"},%.3f,%.3f,%.3f,%s\n".format(time, step, off, intent))
            prev = s; prevStep = step
        }
        return Stats(maxStep, stalls, maxJerk, arrive)
    }

    @Test
    fun layeredDirectorIsContinuous() {
        AvatarPackData.ensureLoaded(org.robolectric.RuntimeEnvironment.getApplication())
        val csv = StringBuilder("director,t,step,offTarget,intent\n")
        val old = run(true, csv); val new = run(false, csv)
        val report = "legacy   $old\nengine17 $new\n"
        println(report)
        System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() }?.let {
            File(it).mkdirs(); File(it, "expr_continuity.csv").writeText(csv.toString()); File(it, "expr_continuity.txt").writeText(report)
        }
        // Remaining "stalls" are ease-in first frames and a ≤4-frame queue when three
        // changes land within ~0.2 s; legacy froze for the whole dwell + queue.
        assertTrue("far fewer stalls than legacy (${new.stallFrames} vs ${old.stallFrames})", new.stallFrames * 4 <= old.stallFrames)
        assertTrue("no bigger pop than legacy", new.maxStep <= old.maxStep * 1.05f)
    }

    @Test
    fun interruptBendsImmediately() {
        val d = ExpressionDirector(Random(2))
        d.hint = ExpressionDirector.Style.Soft; d.want("Happy")
        repeat(10) { d.update(1f / 60f, 0f) }
        d.hint = ExpressionDirector.Style.Soft; d.want("Sad")
        assertEquals("Sad", d.to)
        d.update(1f / 60f, 0f)
        assertTrue("new layer starts growing on the next frame", d.topEased > 0f)
        repeat(120) { d.update(1f / 60f, 0f) }
        assertTrue(!d.morphing && d.from == "Sad")
    }
}
