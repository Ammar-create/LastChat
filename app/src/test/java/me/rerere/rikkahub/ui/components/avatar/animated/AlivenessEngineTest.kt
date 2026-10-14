package me.rerere.rikkahub.ui.components.avatar.animated

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AlivenessEngineTest {
    private fun simulate(seed: Int, life: MarkLifecycle, p: Personality, sec: Float = 300f): Pair<Map<String, Float>, Map<String, Int>> {
        val e = AlivenessEngine(Random(seed)).apply { personality = p }
        val faceTime = HashMap<String, Float>()
        val beats = HashMap<String, Int>()
        var t = 0f
        val dt = 1f / 30f
        while (t < sec) {
            e.update(dt, life, 0.2f, 0.5f, calm = true)?.let { b ->
                val k = when (b) {
                    is AlivenessEngine.Beat.Gesture -> b.g.name
                    is AlivenessEngine.Beat.Accent -> b.a.name
                    is AlivenessEngine.Beat.Glance -> "glance:${b.kind}"
                    is AlivenessEngine.Beat.Face -> "face:${b.expr}"
                    else -> b.toString()
                }
                beats[k] = (beats[k] ?: 0) + 1
            }
            faceTime[e.busyFace] = (faceTime[e.busyFace] ?: 0f) + dt
            t += dt
        }
        return faceTime to beats
    }

    @Test
    fun busyFacesVaryAndThinkingFaceIsOnlySometimes() {
        for (seed in 1..6) for (life in listOf(MarkLifecycle.Thinking, MarkLifecycle.Streaming)) {
            val (faces, _) = simulate(seed, life, Personality.GENERICAL)
            val total = faces.values.sum()
            assertTrue("$seed $life faces=$faces", faces.size >= 4)
            assertTrue("$seed $life focused share", (faces["angered or concentrated"] ?: 0f) / total < 0.4f)
            assertTrue("$seed $life neutral not constant", (faces["Neutral"] ?: 0f) / total < 0.85f)
        }
    }

    @Test
    fun streamingNodsAlongOnlySometimes() {
        var nods = 0
        for (seed in 1..10) nods += simulate(seed, MarkLifecycle.Streaming, Personality.GENERICAL, 40f).second["Nod"] ?: 0
        assertTrue("nods=$nods", nods in 1..30)
    }

    @Test
    fun personalityChangesTempo() {
        val lively = Personality(energy = 1f, calmness = 0f, expressiveness = 1f)
        val calm = Personality(energy = 0f, calmness = 1f, expressiveness = 0f)
        var a = 0; var b = 0
        for (seed in 1..5) {
            a += simulate(seed, MarkLifecycle.Idle, lively).second.values.sum()
            b += simulate(seed, MarkLifecycle.Idle, calm).second.values.sum()
        }
        assertTrue("lively=$a calm=$b", a > b)
    }
}

class ToneAndHobbyTest {
    @Test
    fun toneReadsObviousCases() {
        assertTrue(ToneAnalyzer.read("Haha, that's a funny one 😂")?.emotion == Emotion.Amusement)
        assertTrue(ToneAnalyzer.read("I'm sorry, unfortunately that failed.")?.emotion == Emotion.Concern)
        assertTrue(ToneAnalyzer.read("Here is the list you asked for.") == null)
    }

    @Test
    fun boredIdleCharacterDoesHobbiesAndNeverStaresUpRight() {
        val glances = ArrayList<AlivenessEngine.Beat.Glance>()
        val kinds = HashSet<String>()
        for (seed in 1..8) {
            val e = AlivenessEngine(Random(seed)).apply { personality = Personality.GENERICAL }
            var t = 0f
            while (t < 900f) {
                e.update(1f / 30f, MarkLifecycle.Idle, 0.2f, 0.4f, true)?.let { b ->
                    kinds += b::class.simpleName + ((b as? AlivenessEngine.Beat.Gesture)?.g?.name ?: "")
                    if (b is AlivenessEngine.Beat.Glance) glances += b
                }
                t += 1f / 30f
            }
        }
        assertTrue("kinds=$kinds", kinds.size >= 6)
        assertTrue("up-right glance", glances.none { it.x > 0.1f && it.y < -0.05f })
    }
}
