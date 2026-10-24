package me.rerere.rikkahub.ui.components.avatar.animated

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Engine21 CI gate: context appropriateness. While generating / streaming / the user is
 * active the avatar stays awake: eyes never shut longer than a short squeeze, no Sleeping
 * or Bored face — even when the context starts while it was resting its eyes or dozing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ContextFitTest {

    @Test
    fun tableBansLowEnergyWhenAttentive() {
        val low = listOf(ContextRules.Behaviour.LongEyesClosed, ContextRules.Behaviour.EyeRest, ContextRules.Behaviour.Sleepy,
            ContextRules.Behaviour.Bored, ContextRules.Behaviour.Yawn, ContextRules.Behaviour.DrowsyAccent)
        for (c in ContextRules.Ctx.entries) if (ContextRules.attentive(c)) for (b in low)
            assertTrue("$b must be banned in $c", !ContextRules.allowed(c, b))
        assertTrue(!ContextRules.faceOk(ContextRules.Ctx.Streaming, "Eyes closed", 0.3f))
        assertTrue(ContextRules.faceOk(ContextRules.Ctx.Thinking, "Eyes closed", 0.5f))
        assertTrue(!ContextRules.faceOk(ContextRules.Ctx.Thinking, "Eyes closed", 1.5f))
    }

    /** (start, end, hints) windows; between them the avatar is left alone. */
    private val PLAN = listOf(
        Triple(150f, 160f, AvatarMotionHints(isGenerating = true)),
        Triple(160f, 215f, AvatarMotionHints(isGenerating = true, hasStreamedTokens = true)),
        Triple(420f, 432f, AvatarMotionHints(inputFocused = true, isTyping = true)),
        Triple(432f, 445f, AvatarMotionHints(isGenerating = true)),
        Triple(445f, 520f, AvatarMotionHints(isGenerating = true, hasStreamedTokens = true)),
    )

    private val GRACE = 1.2f

    @Test
    fun awakeWhileGeneratingOrUserActive() {
        ChannelScenarios.loadClips()
        val fails = ArrayList<String>()
        val report = StringBuilder()
        AvatarDebugMetrics.enabled = true
        val dbg = System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() }
        for (seed in 1..8) {
            val s = AvatarMotionState(Random(seed))
            var worstAt = 0f; val frames = StringBuilder()
            val now = java.util.Calendar.getInstance().apply { set(2026, 9, 13, 15, 0, 0) }.timeInMillis
            val dt = 1f / 60f
            var t = 0f
            var closedRun = 0f; var worstClosed = 0f; var lowFace = 0f; var attentiveSec = 0f
            var ctxStart = -1f
            repeat((560f / dt).toInt()) {
                t += dt
                // Long quiet before each busy window: rest the eyes / doze off right before it.
                if (kotlin.math.abs(t - 140f) < dt / 2) s.debugRest(doze = seed % 2 == 0)
                if (kotlin.math.abs(t - 410f) < dt / 2) s.debugRest(doze = seed % 2 == 1)
                val w = PLAN.firstOrNull { t >= it.first && t < it.second }
                val hints = w?.third ?: AvatarMotionHints()
                s.tick(dt, hints, now + (t * 1000).toLong())
                val att = w != null
                if (att && ctxStart < 0f) ctxStart = t
                if (!att) ctxStart = -1f
                if (att && t - ctxStart > GRACE) {
                    attentiveSec += dt
                    val f = s.frame
                    val ws = ChannelTrace.legacyWeights(f)
                    val closedW = (ws["Eyes closed"] ?: 0f) + (ws["Sleeping"] ?: 0f)
                    val closed = f.lid < 0.3f || closedW > 0.5f
                    closedRun = if (closed) closedRun + dt else 0f
                    if (closedRun > worstClosed) { worstClosed = closedRun; worstAt = t }
                    if ((ws["Sleeping"] ?: 0f) > 0.5f || (ws["Bored"] ?: 0f) > 0.5f) { lowFace += dt; if (worstAt == 0f) worstAt = t }
                    if (closed || (ws["Sleeping"] ?: 0f) > 0.5f || (ws["Bored"] ?: 0f) > 0.5f) frames.append("%.2f lid=%.2f ws=%s\n".format(t, f.lid, ws.entries.joinToString { "${it.key}:%.2f".format(it.value) }))
                } else closedRun = 0f
            }
            if (dbg != null) java.io.File(dbg, "ctx_seed$seed.txt").apply { parentFile?.mkdirs() }.writeText(frames.toString() + "\n--- log\n" + s.behaviorLog.joinToString("\n"))
            report.append("seed $seed: worst closed ${"%.2f".format(worstClosed)} s, low face ${"%.2f".format(lowFace)} s over ${attentiveSec.toInt()} s attentive\n")
            if (worstClosed > 1.0f) fails += "seed $seed: eyes shut ${"%.2f".format(worstClosed)} s while attentive"
            if (lowFace > 0f) fails += "seed $seed: Sleeping/Bored shown ${"%.2f".format(lowFace)} s while attentive"
        }
        AvatarDebugMetrics.enabled = false
        println(report)
        assertTrue("Engine21 context gate failed:\n" + fails.joinToString("\n"), fails.isEmpty())
    }
}
