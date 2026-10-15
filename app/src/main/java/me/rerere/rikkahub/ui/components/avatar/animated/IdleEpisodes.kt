package me.rerere.rikkahub.ui.components.avatar.animated

import kotlin.random.Random

/*
 * Engine16 idle episode catalogue. See docs/avatar-character-engine.md, "Engine16".
 *
 * One planner picks at most one self-started episode at a time while the avatar is idle and
 * free. Every episode has a reason (a mood / attention / fatigue condition), a rarity tier,
 * its own cooldown and a global spacing, so a user sees the common ones within minutes and
 * the rare ones only over hours. Doing nothing is always the most likely outcome: stillness
 * is the default. Spins are NOT in this catalogue — idle never spins (engine16).
 */

internal enum class Tier(val w: Float) { Common(1f), Uncommon(0.38f), Rare(0.1f), VeryRare(0.03f) }

internal enum class Episode(val tier: Tier, val cooldown: Float, val why: String) {
    // ---- common: small attention beats
    CuriousLR(Tier.Common, 45f, "heard / imagined something: looks left, then right, then back"),
    CheckOnYou(Tier.Common, 60f, "checks on the person in front of the screen"),
    TiltChain(Tier.Common, 70f, "puzzling something over: soft head tilt one way, then the other"),
    Scan(Tier.Common, 60f, "curious about the room: slow pan across a few related points"),
    // ---- uncommon: little character
    SkepticalLR(Tier.Uncommon, 150f, "sizing something up: left with one brow raised, right with the other"),
    HmmLean(Tier.Uncommon, 90f, "a quiet 'hmm': small lean, puzzled eyes, sometimes a nod of 'got it'"),
    DoubleTake(Tier.Uncommon, 140f, "glanced past something, then 'wait —' looks back"),
    Peek(Tier.Uncommon, 100f, "leans to peek at something off to one side"),
    FollowSomething(Tier.Uncommon, 160f, "eyes track something drifting across, then lose it"),
    LookUpRemember(Tier.Uncommon, 150f, "trying to remember: looks up-left, then 'ah' — small nod"),
    ContentSlowBlink(Tier.Uncommon, 120f, "content: a slow cat-like blink with a soft smile"),
    NoticePill(Tier.Uncommon, 120f, "notices the activity pill"),
    // ---- rare: tired / long stillness
    Stretch(Tier.Rare, 240f, "stiff after stillness: stretches up, eyes shut, exhale"),
    Yawn(Tier.Rare, 300f, "tired: a slow yawn (stretch up, eyes squeezed), maybe a shake to wake up"),
    SleepyDrift(Tier.Rare, 150f, "tired: head sinks … catches itself"),
    RestEyes(Tier.Rare, 200f, "untouched for a long while: rests his eyes, maybe dozes off"),
    WorriedGlance(Tier.Rare, 300f, "a passing worry: glance down to the side, then reassures itself"),
    ComeToSenses(Tier.Rare, 260f, "zoned out: a little head shake and blink-blink, back to the present"),
    // ---- very rare: easter-egg beats
    Smug(Tier.VeryRare, 600f, "remembers the nice exchange earlier: a small smug nod"),
    LookAround360(Tier.VeryRare, 700f, "really curious: a long survey left → far right → back, with a tilt"),
    Sneeze(Tier.VeryRare, 900f, "a tiny sneeze: recoil + blink-blink"),
}

internal class EpisodePlanner(private val rng: Random) {
    private var clock = 0f
    private var lastAny = -10f
    private var checkIn = 5f
    private val lastAt = HashMap<Episode, Float>()
    private val recent = ArrayDeque<Episode>()
    val seen = LinkedHashMap<Episode, Int>()

    private fun cool(e: Episode): Float {
        val f = ((clock - (lastAt[e] ?: -1e5f)) / e.cooldown).coerceIn(0f, 1f)
        return f * f
    }

    fun note(e: Episode) {
        lastAt[e] = clock; lastAny = clock
        recent.addLast(e); while (recent.size > 5) recent.removeFirst()
        seen[e] = (seen[e] ?: 0) + 1
    }

    /** Context the conditions read. */
    class Ctx(
        val t: Temperament,
        val quietSec: Float,
        val drowsy: Boolean,
        val valence: Float,
        val hasPill: Boolean,
        val niceRecently: Boolean,
        val playfulness: Float,
    )

    /** Weight multiplier from the episode's reason; 0 = no reason right now. */
    private fun reason(e: Episode, c: Ctx): Float {
        val t = c.t
        val tired = c.drowsy || t.state == Temperament.State.Tired
        return when (e) {
            Episode.CuriousLR -> 0.5f + t.curiosity
            Episode.CheckOnYou -> if (c.quietSec in 12f..120f) 0.6f + 0.5f * c.valence.coerceAtLeast(0f) else 0.2f
            Episode.TiltChain -> 0.4f + 0.8f * t.curiosity
            Episode.Scan -> 0.4f * t.curiosity + 0.25f * t.boredom
            Episode.SkepticalLR -> if (tired) 0f else 0.5f + 0.5f * t.curiosity
            Episode.HmmLean -> if (tired) 0.2f else 0.7f
            Episode.DoubleTake -> if (tired) 0f else 0.4f + 0.6f * t.curiosity
            Episode.Peek -> t.curiosity * (0.5f + c.playfulness)
            Episode.FollowSomething -> if (tired) 0.2f else 0.6f * (0.4f + t.curiosity)
            Episode.LookUpRemember -> 0.7f
            Episode.ContentSlowBlink -> if (c.valence > 0.15f) 0.8f + c.valence else 0f
            Episode.NoticePill -> if (c.hasPill) 0.6f * t.curiosity else 0f
            Episode.Stretch -> if (c.quietSec > 40f && (t.boredom > 0.3f || t.energy < 0.35f)) 1.2f else 0f
            Episode.Yawn -> if (tired || (c.quietSec > 60f && t.energy < 0.4f)) 2.5f else if (c.quietSec > 90f) 0.4f else 0f
            Episode.SleepyDrift -> if (tired || t.boredom > 0.55f) 1.5f else 0f
            Episode.RestEyes -> if (c.drowsy && c.quietSec > 90f) 6f else 0f
            Episode.WorriedGlance -> if (c.valence < 0.35f && !tired) 0.8f else 0.2f
            Episode.ComeToSenses -> if (c.quietSec > 45f && t.boredom > 0.3f) 1.2f else 0f
            Episode.Smug -> if (c.niceRecently) 3f else 0f
            Episode.LookAround360 -> if (tired) 0f else t.curiosity
            Episode.Sneeze -> 1f
        }
    }

    /** A self-started episode, or null. [free] = idle, nothing else driving face or gaze. */
    fun update(dt: Float, c: Ctx, free: Boolean): Episode? {
        clock += dt
        checkIn -= dt
        if (checkIn > 0f || !free) return null
        checkIn = 3.5f + rng.nextFloat() * 5f
        // Global spacing: 30 s calm … 14 s lively, longer when tired (stillness is fine).
        val act = ((c.t.activity - 0.1f) / 0.7f).coerceIn(0f, 1f)
        val spacing = (30f - 16f * act) * (if (c.drowsy) 1.3f else 1f)
        if (clock - lastAny < spacing) return null
        val opts = Episode.entries.map { e ->
            val hist = if (recent.lastOrNull() == e) 0.15f else if (e in recent) 0.5f else 1f
            e to e.tier.w * reason(e, c) * cool(e) * hist
        }.filter { it.second > 0f }
        val none = 0.9f + 0.6f * (1f - c.t.activity)
        val total = opts.sumOf { it.second.toDouble() }.toFloat() + none
        var x = rng.nextFloat() * total
        for ((e, w) in opts) { x -= w; if (x <= 0f) { note(e); return e } }
        lastAny = clock - spacing * 0.5f // chose stillness: look again a bit later
        return null
    }

    internal fun debugReady() { lastAny = -1e4f; checkIn = 0f; lastAt.clear() }
}
