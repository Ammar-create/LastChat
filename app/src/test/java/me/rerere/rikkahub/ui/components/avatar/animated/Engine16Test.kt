package me.rerere.rikkahub.ui.components.avatar.animated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class Engine16Test {
    @Test
    fun spinPathsStartAndEndAtRest() {
        for (p in SpinPath.entries) for (dir in listOf(1f, -1f)) {
            val (y0, p0, r0) = p.angles(0f, dir)
            assertTrue("$p start", abs(y0) + abs(p0) + abs(r0) < 1e-3f)
            val (y1, p1, r1) = p.angles(1f, dir)
            fun m(a: Float) = abs(((a % 360f) + 540f) % 360f - 180f)
            assertTrue("$p end ($y1,$p1,$r1)", m(y1) < 0.5f && m(p1) < 0.5f && m(r1) < 0.5f)
        }
        // At least four paths actually leave the horizontal plane.
        assertTrue(SpinPath.entries.count { val (_, pp, rr) = it.angles(0.5f, 1f); abs(pp) > 20f || abs(rr) > 20f } >= 4)
    }

    @Test
    fun plannerChoosesStillnessOftenAndRespectsReasons() {
        val pl = EpisodePlanner(Random(3))
        val t = Temperament(Random(3))
        val ctx = EpisodePlanner.Ctx(t, quietSec = 20f, drowsy = false, valence = 0f, hasPill = false, niceRecently = false, playfulness = 0.5f)
        var n = 0
        val seen = HashSet<Episode>()
        repeat(600 * 60) { pl.update(1f / 60f, ctx, free = true)?.let { n++; seen += it } }
        // 10 min awake: a handful of beats, never a stream (≤ 1 per ~25 s).
        assertTrue("episodes $n", n in 5..26)
        // No reason → never: rest-eyes needs drowsiness, smug needs a nice moment.
        assertTrue(Episode.RestEyes !in seen && Episode.Smug !in seen && Episode.ContentSlowBlink !in seen)
        assertEquals(n, pl.seen.values.sum())
    }
}
