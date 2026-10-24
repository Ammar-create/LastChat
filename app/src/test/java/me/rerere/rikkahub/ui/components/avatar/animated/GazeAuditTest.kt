package me.rerere.rikkahub.ui.components.avatar.animated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Engine17.1 gaze audit: no near-rest "interest" glances (a look must go somewhere), and
 * returns to rest are eased glides (bounded peak eye speed), over 10 idle minutes × seeds.
 */
class GazeAuditTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun loadClips() {
            val f = listOf(File("src/main/assets/avatar/clips068.json"), File("app/src/main/assets/avatar/clips068.json")).first { it.exists() }
            AvatarPackData.parseClips(f.readText())
        }
    }

    @Test
    fun glancesAreMeaningfulAndReturnsGlide() {
        val now = java.util.Calendar.getInstance().apply { set(2026, 9, 13, 15, 0, 0) }.timeInMillis
        val sb = StringBuilder("seed,glances,nearRestRequested,nearRestGlances,minGlanceOffset,returns,returnPeakEyeSpeed\n")
        for (seed in listOf(3, 11, 19)) {
            val s = AvatarMotionState(Random(seed))
            repeat((600f * 60f).toInt()) { s.tick(1f / 60f, AvatarMotionHints(), now) }
            val g = s.debugGaze
            sb.append("$seed,${g.auditGlances},${g.auditNearRestRaw},${g.auditNearRest},${"%.3f".format(g.auditMinGlance)},${g.auditReturns},${"%.3f".format(g.auditReturnPeak)}\n")
            assertEquals("near-rest interest glances (seed $seed)", 0, g.auditNearRest)
            assertTrue("returns glide (peak ${g.auditReturnPeak})", g.auditReturns == 0 || g.auditReturnPeak < 1.6f)
        }
        println(sb)
        System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() }?.let { File(it).mkdirs(); File(it, "gaze_audit.csv").writeText(sb.toString()) }
    }
}
