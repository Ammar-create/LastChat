package me.rerere.rikkahub.ui.components.avatar.animated

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * Generating motion and blinks over several minutes: varied, no visible loop, no
 * slow side-to-side sway, no jumps; blinks irregular.
 */
class BusyVarietyTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun loadClips() {
            val f = listOf(
                File("src/main/assets/avatar/clips068.json"),
                File("app/src/main/assets/avatar/clips068.json"),
            ).first { it.exists() }
            AvatarPackData.parseClips(f.readText())
        }
        private const val DT = 1f / 60f
    }

    private fun lifeAt(t: Float, seed: Int): MarkLifecycle {
        // Long generating session: think → tools → stream, repeated with varied lengths.
        val s = t % 150f
        return when {
            s < 20f + seed % 5 -> MarkLifecycle.Thinking
            s < 90f -> MarkLifecycle.Working
            else -> MarkLifecycle.Streaming
        }
    }

    @Test
    fun generatingIsVariedSmoothAndSwayFree() {
        val outDir = System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }
        for (seed in 1..6) {
            val d = MotionDirector(Random(seed)) { AvatarPackData.clips[it] }
            val csv = StringBuilder("t,life,beat,pitch,yaw,roll,scale\n")
            val beats = ArrayList<String>()
            var lastName = ""
            var prev: BodyPose? = null
            var maxYawStep = 0f
            var maxScaleStep = 0f
            var t = 0f
            val seconds = 300f
            while (t < seconds) {
                d.setDesired(lifeAt(t, seed))
                val p = d.update(DT)
                if (d.activeName != lastName) { lastName = d.activeName; beats += lastName }
                prev?.let {
                    if (!(lastName.startsWith("spin"))) maxYawStep = maxOf(maxYawStep, abs(MarkClipEngine.wrap180(p.yaw - it.yaw)))
                    maxScaleStep = maxOf(maxScaleStep, abs(p.scale - it.scale))
                }
                prev = p
                if (((t / DT).toInt() % 3) == 0) csv.append("%.3f,%s,%s,%.2f,%.2f,%.2f,%.4f\n".format(t, d.lifecycle, d.activeName, p.pitch, p.yaw, p.roll, p.scale))
                t += DT
            }
            outDir?.let { File(it, "busy_seed$seed.csv").writeText(csv.toString()) }
            val busyBeats = beats.filter { !it.startsWith("blend") }
            val kinds = busyBeats.map { it.removeSuffix("B") }.toSet()
            assertTrue("seed $seed only ${kinds.size} beat kinds: $kinds", kinds.size >= 7)
            // No authored Working/Streaming clip (their side-sway was the loop).
            assertTrue("seed $seed still plays sway clips", busyBeats.none { it.startsWith("Working") || it.startsWith("Streaming") })
            // No 4-beat sequence recurring a lot.
            val grams = busyBeats.windowed(4).groupingBy { it }.eachCount()
            val worst = grams.values.maxOrNull() ?: 0
            assertTrue("seed $seed repeats a 4-beat pattern $worst× in ${busyBeats.size} beats", worst <= maxOf(4, busyBeats.size / 25))
            assertTrue("seed $seed yaw step $maxYawStep", maxYawStep < 13f)
            assertTrue("seed $seed scale step $maxScaleStep", maxScaleStep < 0.01f)
        }
    }

    @Test
    fun blinksAreIrregular() {
        var seedsWithDoubles = 0
        for (seed in 1..6) {
            val b = Blinker(Random(seed))
            var t = 0f
            var wasClosed = false
            val starts = ArrayList<Float>()
            while (t < 600f) {
                b.update(DT, if (t < 300f) MarkLifecycle.Idle else MarkLifecycle.Working, false)
                val c = b.closed > 0.3f
                if (c && !wasClosed) starts += t
                wasClosed = c
                t += DT
            }
            val gaps = starts.zipWithNext { a, c -> c - a }
            val mean = gaps.average().toFloat()
            val sd = kotlin.math.sqrt(gaps.map { (it - mean) * (it - mean) }.average()).toFloat()
            // Rate ~ 8–25 per minute incl. doubles, coefficient of variation high (no rhythm).
            val perMin = starts.size / 10f
            assertTrue("seed $seed blinks/min $perMin", perMin in 6f..28f)
            // Engine19 scheduler: refractory 1.8 s and max 7 s bound the spread (review §6).
            assertTrue("seed $seed CV ${sd / mean}", sd / mean > 0.4f)
            if (gaps.any { it < 0.4f }) seedsWithDoubles++
            assertTrue("seed $seed no long stare", gaps.any { it > 7f })
        }
        // Doubles are rare by design (p 0.04, ≥6 s apart): most, not all, 10-min runs show one.
        assertTrue("doubles in $seedsWithDoubles/6 seeds", seedsWithDoubles >= 4)
    }
}
