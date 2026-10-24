package me.rerere.rikkahub.ui.components.avatar.animated

import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * Engine18 frame-level behaviour audit. Uses only what the rendered frames show (plus the
 * behaviour log), so the very same file runs on engine17.2 for an honest before/after.
 * The scenario: 12 min — quiet idle, a poke burst, single pokes, typing, a reply that
 * thinks and streams, then long idle again.
 */
class MindFrameAuditTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun loadClips() {
            val f = listOf(File("src/main/assets/avatar/clips068.json"), File("app/src/main/assets/avatar/clips068.json")).first { it.exists() }
            AvatarPackData.parseClips(f.readText())
        }

        val POKES = listOf(240f, 241.0f, 241.7f, 242.3f, 300f, 420f, 500f, 500.6f, 501.2f, 501.8f, 502.4f, 503.0f, 503.6f, 620f)
        fun hintsAt(t: Float): AvatarMotionHints = when {
            t in 360f..370f -> AvatarMotionHints(inputFocused = true, isTyping = true)
            t in 370f..380f -> AvatarMotionHints(isGenerating = true)
            t in 380f..410f -> AvatarMotionHints(isGenerating = true, hasStreamedTokens = true)
            else -> AvatarMotionHints()
        }
        const val STREAM_END = 410f

        class Result(
            val seed: Int, val faceChanges: Int, val interrupted: Int, val overlaps: Int, val minGap: Float,
            val blinkMidMorph: Int, val blinks: Int, val spins: Int, val idleSpins: Int,
            val maxPerMinQuiet: Int, val maxPerMin: Int, val uncaused: Int, val rare: Int, val negSpins: Int, val state: AvatarMotionState,
        )

        fun run(seed: Int, minutes: Float = 12f): Result {
            AvatarDebugMetrics.enabled = true
            val now = java.util.Calendar.getInstance().apply { set(2026, 9, 13, 15, 0, 0) }.timeInMillis
            val s = AvatarMotionState(Random(seed))
            val dt = 1f / 30f
            var t = 0f
            val pokes = POKES.toMutableList()
            var prevTo = ""; var prevTop: String? = null; var prevT = 1f
            var prevAccent = FaceAccent.None
            var prevLid = 1f; var lidOpen = true
            var spinning = false
            val changes = ArrayList<Float>()
            val spinStarts = ArrayList<Float>()
            var interrupted = 0; var blinkMid = 0; var blinks = 0; var spins = 0; var idleSpins = 0; var negSpins = 0
            val neg = setOf("Angry", "angered or concentrated", "Sad", "pouty", "Bored", "hurt or cute")
            var lastNeg = -99f
            val n = (minutes * 60f / dt).toInt()
            repeat(n) {
                t += dt
                while (pokes.isNotEmpty() && t >= pokes[0]) { pokes.removeAt(0); s.poke() }
                s.tick(dt, hintsAt(t), now + (t * 1000).toLong())
                val f = s.frame
                // Expression starts / interruptions.
                if (f.exprTo != prevTo) {
                    if (prevT < 0.97f && prevTo.isNotEmpty() && f.exprFrom != prevTo) interrupted++
                    changes += t
                }
                if (f.exprTop != null && f.exprTop != prevTop) { interrupted++; changes += t }
                if (f.exprTop == null && f.exprTo == prevTo && f.exprT < prevT - 0.02f && prevT < 0.999f) interrupted++ // retreat
                // Accent onsets.
                val a = s.currentAccent
                if (a != prevAccent && a != FaceAccent.None) changes += t
                prevAccent = a
                // Blinks (lid dips) and blinks that start mid-morph.
                if (lidOpen && f.lid < 0.35f && prevLid >= 0.35f) {
                    blinks++; lidOpen = false
                    if (f.exprT in 0.1f..0.9f && f.exprFrom != f.exprTo) blinkMid++
                }
                if (f.lid > 0.85f) lidOpen = true
                prevLid = f.lid
                // Spins.
                if (f.exprTo in neg || f.exprFrom in neg || f.exprTop in neg || s.currentAccent == FaceAccent.Annoyed) lastNeg = t
                val sp = abs(f.spinYaw) > 2f || abs(f.spinPitch) > 2f || abs(f.spinRoll) > 2f
                if (sp && !spinning) {
                    spins++; spinStarts += t
                    if (t - lastNeg < 1.0f) negSpins++
                    val poked = POKES.any { t - it in 0f..20f }
                    val afterReply = t - STREAM_END in 0f..15f || t in 360f..STREAM_END
                    if (!poked && !afterReply) idleSpins++
                }
                spinning = sp
                prevTo = f.exprTo; prevTop = f.exprTop; prevT = f.exprT
            }
            changes.sort()
            var overlaps = 0; var minGap = 99f
            for (i in 1 until changes.size) {
                val g = changes[i] - changes[i - 1]
                if (g < minGap) minGap = g
                if (g < 0.4f) overlaps++
            }
            fun quiet(t0: Float) = POKES.none { it in (t0 - 10f)..(t0 + 60f) } && (t0 + 60f < 355f || t0 > STREAM_END + 30f)
            var maxQ = 0; var maxAll = 0
            var w = 0f
            while (w + 60f <= minutes * 60f) {
                val c = changes.count { it >= w && it < w + 60f } + spinStarts.count { it >= w && it < w + 60f }
                if (c > maxAll) maxAll = c
                if (quiet(w) && c > maxQ) maxQ = c
                w += 5f
            }
            val log = s.behaviorLog
            val uncaused = log.count { it.contains("cause=unspecified") }
            val rare = log.count { l -> listOf("Stretch", "Yawn", "SleepyDrift", "RestEyes", "ConcernGlance", "ComeToSenses", "Smug ", "LookAround360", "Sneeze").any { l.contains("episode $it") } }
            File(System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() } ?: "build").also { it.mkdirs() }
                .resolve("mind_log_$seed.txt").writeText(log.joinToString("\n"))
            AvatarDebugMetrics.enabled = false
            return Result(seed, changes.size, interrupted, overlaps, minGap, blinkMid, blinks, spins, idleSpins, maxQ, maxAll, uncaused, rare, negSpins, s)
        }
    }

    @Test
    fun frameAudit() {
        val sb = StringBuilder("seed,faceChanges,interruptedMorphs,overlaps<0.4s,minGapS,blinkMidMorph,blinks,spins,idleSpins,maxPerMinQuiet,maxPerMin,uncaused,rareEpisodes,spinsWhileNegative\n")
        for (seed in listOf(3, 11, 19, 27)) {
            val r = run(seed)
            sb.append("${r.seed},${r.faceChanges},${r.interrupted},${r.overlaps},${"%.2f".format(r.minGap)},${r.blinkMidMorph},${r.blinks},${r.spins},${r.idleSpins},${r.maxPerMinQuiet},${r.maxPerMin},${r.uncaused},${r.rare},${r.negSpins}\n")
        }
        println(sb)
        val dir = System.getProperty("lastchat.renderDir")?.takeIf { it.isNotBlank() } ?: return
        File(dir).mkdirs(); File(dir, "frame_audit.csv").writeText(sb.toString())
    }
}
