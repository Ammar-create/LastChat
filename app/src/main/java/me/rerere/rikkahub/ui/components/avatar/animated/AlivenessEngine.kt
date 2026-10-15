package me.rerere.rikkahub.ui.components.avatar.animated

import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * Character engine: the behaviour layer above the body director and the face rig.
 *
 * - [personality] (continuous traits) parameterises timing, amplitude, expression
 *   weights, gaze habits, blink rate, idle behaviour and reaction choices.
 * - Short-term [Emotion]s (with decay) and drives (boredom, attention, social
 *   interest) sit on top of the shared mood (valence/arousal).
 * - A utility selector picks beats by weighted chance with per-kind cooldowns and a
 *   memory of recent beats, so nothing falls into a rhythm.
 *
 * It only proposes [Beat]s; the motion state turns them into eased gestures, morphs
 * and gaze moves, so nothing here can cut. External input: [personality],
 * [feedTone] (per-message tone) and [onEvent] (chat / UI events).
 */
internal class AlivenessEngine(private val rng: Random) {

    sealed interface Beat {
        data class Gesture(val g: MotionDirector.Gesture) : Beat
        data class Accent(val a: FaceAccent, val sec: Float) : Beat
        data class Glance(val x: Float, val y: Float, val sec: Float, val kind: String) : Beat
        data class Face(val expr: String, val sec: Float) : Beat
        data object Blink : Beat
        data object DoubleBlink : Beat
    }

    /** Temperament activity (set by the motion state): spontaneous beats get rarer when calm. */
    var activity: Float = 0.35f

    var personality: Personality = Personality.GENERICAL
        set(v) { field = v.clamped() }

    // ---- drives (0…1) ----
    /** Rises with nothing happening; lowers on any event. */
    var boredom = 0f; private set
    /** How engaged with the chat right now (spikes on events, decays). */
    var attention = 0.5f; private set
    /** Wish to look at other avatars. */
    var social = 0.2f; private set

    internal fun debugBoredom(v: Float) { boredom = v.coerceIn(0f, 1f) }

    // ---- short-term emotion ----
    var emotion: Emotion? = null; private set
    var emotionLevel = 0f; private set

    /** Mood nudge the engine wants applied (valence, arousal); read and cleared by the owner. */
    var moodNudge: Pair<Float, Float>? = null
        private set
    fun takeMoodNudge() = moodNudge.also { moodNudge = null }

    /** Current busy-phase face (Generical expression id). */
    var busyFace: String = "Neutral"; private set
    private var faceLeft = 0f
    private val recentFaces = ArrayDeque<String>()
    private val recentBeats = ArrayDeque<String>()
    private val lastUsed = HashMap<String, Float>()
    private var clock = 0f
    private var beatIn = 2f + rng.nextFloat() * 3f
    private var wasBusy = false
    private var nodsThisReply = 0
    /** Scheduled beats (absolute engine clock time → beat), played in order. */
    private val scheduled = ArrayList<Pair<Float, Beat>>()

    /** Time-of-day tiredness 0…1 (late night / early morning), checked once a minute. */
    private var fatigueCache = 0f
    private var fatigueAt = -100f
    private fun fatigue(): Float {
        if (clock - fatigueAt > 60f) {
            fatigueAt = clock
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            fatigueCache = when (h) { 23, 0 -> 0.5f; in 1..5 -> 0.85f; 6 -> 0.4f; 22 -> 0.25f; else -> 0f }
        }
        return fatigueCache
    }

    private fun ago(k: String) = clock - (lastUsed[k] ?: -1000f)
    private fun logRange(a: Float, b: Float) = exp(ln(a) + (ln(b) - ln(a)) * rng.nextFloat())
    private fun chance(p: Float) = rng.nextFloat() < p.coerceIn(0f, 1f)

    private fun <T> weighted(opts: List<Pair<T, Float>>): T {
        val total = opts.sumOf { it.second.toDouble() }.toFloat()
        var r = rng.nextFloat() * total
        for ((k, w) in opts) { r -= w; if (r <= 0f) return k }
        return opts.last().first
    }

    /** Cooldown factor: 0 right after use, back to 1 after [c] seconds (squared ease). */
    private fun cool(k: String, c: Float): Float {
        val f = (ago(k) / c).coerceIn(0f, 1f)
        return f * f
    }

    /** Recently used beats lose weight (anti-pattern memory). */
    private fun fresh(k: String): Float {
        val n = recentBeats.count { it == k }
        return when (n) { 0 -> 1f; 1 -> 0.5f; else -> 0.2f }
    }

    private fun remember(k: String) {
        lastUsed[k] = clock
        recentBeats.addLast(k)
        while (recentBeats.size > 6) recentBeats.removeFirst()
    }

    /** Feed a per-message tone (e.g. from a classifier). Colours mood and face briefly. */
    fun feedTone(t: ToneHint) {
        val p = personality
        val k = 0.25f + 0.5f * p.expressiveness
        moodNudge = (t.valence * 0.4f * k) to ((t.arousal - 0.5f) * 0.3f * k)
        t.emotion?.let { feel(it, t.intensity * k) }
    }

    fun feel(e: Emotion, level: Float) {
        if (emotion == null || level >= emotionLevel * 0.8f) {
            emotion = e
            emotionLevel = level.coerceIn(0f, 1f)
        }
    }

    /** Chat / UI events (everything the stage reports, plus lifecycle edges). */
    fun onEvent(e: AvatarStage.Event) {
        val p = personality
        val wasDazed = boredom > 0.6f
        boredom = (boredom - 0.4f).coerceAtLeast(0f)
        attention = (attention + 0.2f + 0.2f * p.attentiveness).coerceAtMost(1f)
        when (e.kind) {
            AvatarStage.EventKind.MessageSent -> {
                feel(Emotion.Interest, 0.4f + 0.4f * p.curiosity)
                if (wasDazed) startle()
                else if (chance(0.25f * p.warmth)) queue(Beat.Gesture(MotionDirector.Gesture.Attend), logRange(0.15f, 0.5f))
            }
            AvatarStage.EventKind.UserTyping -> {
                feel(Emotion.Interest, 0.3f + 0.3f * p.curiosity)
                if (wasDazed) startle()
            }
            AvatarStage.EventKind.Error -> feel(Emotion.Concern, 0.5f + 0.3f * p.warmth)
            AvatarStage.EventKind.Copied -> if (chance(0.3f * p.warmth)) feel(Emotion.Pride, 0.5f)
            AvatarStage.EventKind.Regenerated -> if (chance(0.4f)) feel(Emotion.Confusion, 0.3f + 0.3f * p.expressiveness)
            AvatarStage.EventKind.AvatarAccent -> social = (social + 0.25f * (0.5f + p.warmth)).coerceAtMost(1f)
            AvatarStage.EventKind.ReplyTone -> {
                val emo = Emotion.entries.getOrNull(e.extra - 1)
                val intensity = (e.direction / 100f).coerceIn(0f, 1f)
                feedTone(ToneHint(e.magnitude, 0.5f, emo, intensity))
                if (emo != null) reactToTone(emo, intensity)
            }
            else -> {}
        }
    }

    /** Snapped out of a daze: a small jolt, then a double blink. */
    private fun startle() {
        if (ago("startle") < 30f) return
        remember("startle")
        sequence(
            0f to Beat.Gesture(MotionDirector.Gesture.Recoil),
            0.01f to Beat.Accent(FaceAccent.Startled, 0.75f),
            1.15f to Beat.DoubleBlink,
        )
    }

    /** The character's own reply had a tone: show it, scaled by expressiveness. */
    private fun reactToTone(emo: Emotion, intensity: Float) {
        val p = personality
        if (!chance((0.35f + 0.6f * p.expressiveness) * (0.5f + intensity))) return
        val d = logRange(0.3f, 0.9f)
        when (emo) {
            Emotion.Joy -> sequence(d to Beat.Face("Happy", logRange(1.2f, 2.2f)),
                d + 0.2f to if (chance(0.25f * p.playfulness)) Beat.Gesture(MotionDirector.Gesture.Hop) else Beat.Blink)
            Emotion.Amusement -> sequence(d to Beat.Accent(FaceAccent.Glee, logRange(1.0f, 1.8f)),
                d + 0.3f to Beat.Gesture(MotionDirector.Gesture.Nod))
            Emotion.Concern -> sequence(d to Beat.Accent(FaceAccent.Worried, logRange(1.2f, 2.0f)))
            Emotion.Sadness -> sequence(d to Beat.Accent(FaceAccent.Sad, logRange(1.4f, 2.4f)),
                d + 1.2f to Beat.Gesture(MotionDirector.Gesture.Droop))
            Emotion.Surprise -> sequence(d to Beat.Accent(FaceAccent.Startled, 0.6f), d + 0.7f to Beat.Blink)
            Emotion.Interest -> sequence(d to Beat.Accent(FaceAccent.Curious, logRange(1.0f, 1.6f)),
                d + 0.1f to Beat.Gesture(MotionDirector.Gesture.Tilt))
            Emotion.Confusion -> sequence(d to Beat.Face("confused", logRange(0.8f, 1.4f)))
            Emotion.Pride, Emotion.Calm -> sequence(d to Beat.Accent(FaceAccent.Smug, logRange(0.9f, 1.4f)))
        }
    }

    /** A reply just finished: sometimes a small nod of agreement with itself. */
    fun onReplyFinished(): Beat? {
        val p = personality
        feel(Emotion.Pride, 0.3f + 0.3f * p.warmth)
        if (chance(0.15f + 0.25f * p.expressiveness) && ago("nod") > 6f) {
            remember("nod")
            return Beat.Gesture(MotionDirector.Gesture.Nod)
        }
        return null
    }

    private fun queue(b: Beat, inSec: Float) = sequence(inSec to b)

    /** Schedule a multi-step behaviour (delays relative to now). Replaces anything pending. */
    private fun sequence(vararg steps: Pair<Float, Beat>) {
        scheduled.clear()
        steps.forEach { (d, b) -> scheduled += (clock + d) to b }
    }

    /**
     * Advance; returns at most one beat to play this frame. [calm] = nobody else is
     * driving the face (no reaction, social look or typing follow).
     */
    fun update(dt: Float, life: MarkLifecycle, valence: Float, arousal: Float, calm: Boolean): Beat? {
        clock += dt
        val p = personality
        // Drives.
        val busy = MotionDirector.isBusy(life)
        boredom = if (busy || life == MarkLifecycle.Listening) (boredom - dt * 0.05f).coerceAtLeast(0f)
        else (boredom + dt / (90f + 120f * p.calmness)).coerceAtMost(1f)
        attention += ((0.35f + 0.3f * p.attentiveness) - attention) * (1f - exp(-dt / 20f))
        social += ((0.15f + 0.3f * p.warmth * (1f - p.shyness)) - social) * (1f - exp(-dt / 30f))
        if (emotion != null) {
            emotionLevel -= dt / (6f + 8f * p.expressiveness)
            if (emotionLevel <= 0f) { emotion = null; emotionLevel = 0f }
        }

        if (busy != wasBusy) {
            wasBusy = busy
            nodsThisReply = 0
            busyFace = "Neutral"
            faceLeft = logRange(0.8f, 2.4f)
            if (!busy) beatIn = logRange(1.5f, 4f)
        }
        if (busy) {
            faceLeft -= dt
            if (faceLeft <= 0f) pickBusyFace(life, valence, arousal)
        }
        if (scheduled.isNotEmpty()) {
            val (at, b) = scheduled[0]
            if (clock >= at) {
                scheduled.removeAt(0)
                // A sequence step that can't play now is dropped, not delayed into a cut.
                val startleBeat = (b is Beat.Gesture && b.g == MotionDirector.Gesture.Recoil) ||
                    (b is Beat.Accent && b.a == FaceAccent.Startled)
                if (calm || startleBeat || b is Beat.Blink || b is Beat.DoubleBlink) return b
            }
            return null
        }
        beatIn -= dt
        if (beatIn > 0f || !calm) return null
        // Livelier mood and character → beats more often.
        val pace = (1.3f - 0.5f * arousal) / p.tempo.coerceAtLeast(0.3f) * (1f + 0.5f * fatigue())
        beatIn = logRange(4.0f, 14f) * pace * (1.9f - 1.3f * activity.coerceIn(0.1f, 0.8f))
        return if (busy) busyBeat(life, valence) else idleBeat(valence, arousal)
    }

    private fun pickBusyFace(life: MarkLifecycle, valence: Float, arousal: Float) {
        val p = personality
        val thinking = life == MarkLifecycle.Thinking
        val streaming = life == MarkLifecycle.Streaming
        val pos = valence.coerceIn(0f, 1f)
        val show = p.showiness
        val emo = emotion
        val el = emotionLevel
        val base = listOf(
            "Neutral" to 0.34f * (1.4f - 0.5f * show),
            "angered or concentrated" to (if (thinking) 0.15f else 0.09f) * (0.6f + p.attentiveness),
            "Bored" to 0.07f * (1.2f - arousal) * (0.6f + p.calmness),
            "Happy" to (if (streaming) 0.11f else 0.04f) * (0.5f + pos) * (0.5f + p.warmth) * show *
                (if (emo == Emotion.Joy || emo == Emotion.Amusement) 1f + 2f * el else 1f),
            "confused" to (if (thinking) 0.05f else 0.015f) * show * (if (emo == Emotion.Confusion) 1f + 3f * el else 1f),
            "Surprised" to 0.025f * (0.5f + arousal) * show * (if (emo == Emotion.Surprise) 1f + 3f * el else 1f),
            "Worried" to (if (emo == Emotion.Concern) 0.08f * el else 0f),
        )
        val opts = base.mapNotNull { (k, w) ->
            if (k == busyFace) return@mapNotNull null
            val c = when (k) { "Neutral" -> 0f; "angered or concentrated" -> 8f; else -> 16f }
            val penalty = if (recentFaces.contains(k) && k != "Neutral") 0.35f else 1f
            val f = if (c == 0f) 1f else cool("face:$k", c)
            (k to w * f * penalty).takeIf { it.second > 0f }
        }.ifEmpty { listOf("Neutral" to 1f) }
        val k = weighted(opts)
        lastUsed["face:$k"] = clock
        recentFaces.addLast(k)
        while (recentFaces.size > 4) recentFaces.removeFirst()
        busyFace = k
        val hold = 0.8f + 0.5f * p.calmness
        faceLeft = hold * when (k) {
            "Neutral" -> logRange(1.2f, 6.5f)
            "angered or concentrated" -> logRange(0.9f, 3.6f)
            "Bored" -> logRange(0.7f, 2.2f)
            "Happy" -> logRange(0.7f, 1.8f)
            "Surprised" -> logRange(0.45f, 0.9f)
            "hurt or cute" -> logRange(0.5f, 1.0f)
            else -> logRange(0.8f, 2.2f)
        }
    }

    private fun busyBeat(life: MarkLifecycle, valence: Float): Beat? {
        val p = personality
        val streaming = life == MarkLifecycle.Streaming
        val opts = listOf(
            // Nodding along with its own reply: only sometimes, a few per reply at most.
            "nod" to (if (streaming && nodsThisReply < 3) 0.14f else 0.02f) * (0.5f + p.warmth) * cool("nod", 14f),
            "blink" to 0.16f * p.blinkRate,
            "dblink" to 0.04f * p.blinkRate * cool("dblink", 20f),
            "glanceUpLeft" to 0.11f * (0.5f + p.curiosity) * cool("glanceUpLeft", 10f),
            "glanceDown" to (if (streaming) 0.16f else 0.08f) * (0.5f + p.attentiveness) * cool("glanceDown", 6f),
            "focus" to 0.09f * (0.5f + p.attentiveness) * cool("focus", 12f),
            "puzzled" to (if (streaming) 0.02f else 0.06f) * p.showiness * cool("puzzled", 20f),
            "glee" to (if (streaming) 0.06f else 0.02f) * (0.6f + valence.coerceAtLeast(0f)) * (0.5f + p.warmth) * cool("glee", 25f),
            "tilt" to 0.05f * (0.5f + p.curiosity) * cool("tilt", 15f),
            "nothing" to 0.22f * (0.6f + p.calmness),
        ).map { (k, w) -> k to w * fresh(k) }.filter { it.second > 0f }
        val k = weighted(opts)
        remember(k)
        return when (k) {
            "nod" -> { nodsThisReply++; Beat.Gesture(MotionDirector.Gesture.Nod) }
            "blink" -> Beat.Blink
            "dblink" -> Beat.DoubleBlink
            // A thinking glance up to either side (any direction is fine; holds stay short).
            "glanceUpLeft" -> Beat.Glance((if (rng.nextBoolean()) 1f else -1f) * (0.3f + 0.35f * rng.nextFloat()), -(0.1f + 0.2f * rng.nextFloat()), logRange(0.5f, 1.2f), "think")
            "glanceDown" -> Beat.Glance(-0.2f + 0.5f * rng.nextFloat(), 0.35f + 0.25f * rng.nextFloat(), logRange(0.5f, 1.3f), "read")
            "focus" -> Beat.Accent(FaceAccent.Focused, logRange(1.0f, 2.4f))
            "puzzled" -> Beat.Accent(FaceAccent.Puzzled, logRange(1.0f, 1.8f))
            "glee" -> Beat.Accent(FaceAccent.Glee, logRange(0.8f, 1.4f))
            "tilt" -> Beat.Gesture(MotionDirector.Gesture.Tilt)
            else -> null
        }
    }

    private fun idleBeat(valence: Float, arousal: Float): Beat? {
        val p = personality
        val emo = emotion
        val opts = listOf(
            "nothing" to 0.4f * (0.6f + p.calmness),
            "blink" to 0.12f * p.blinkRate,
            "curious" to 0.05f * (0.4f + p.curiosity) * (0.5f + arousal) * cool("curious", 30f) *
                (if (emo == Emotion.Interest) 1f + 2f * emotionLevel else 1f),
            "smile" to 0.04f * (0.3f + p.warmth) * (0.4f + valence.coerceAtLeast(0f)) * cool("smile", 40f) *
                (if (emo == Emotion.Pride || emo == Emotion.Joy) 1f + 2f * emotionLevel else 1f),
            "glance" to 0.12f * (0.5f + p.curiosity) * cool("glance", 8f),
            "drowsy" to 0.05f * boredom * (1.2f - arousal) * cool("drowsy", 60f),
            "tilt" to 0.03f * (0.5f + p.curiosity) * cool("tiltI", 40f),
            "shyLook" to 0.03f * p.shyness * cool("shyLook", 50f),
            "hop" to 0.012f * p.playfulness * p.energy * 4f * (1f - boredom) * cool("hop", 90f),
            "worried" to (if (emo == Emotion.Concern) 0.08f * emotionLevel else 0f),
            // Hobbies: short multi-step routines, more likely as boredom grows.
            "daydream" to 0.05f * (0.3f + boredom) * (0.5f + p.calmness) * cool("daydream", 70f),
            "survey" to 0.05f * (0.3f + boredom) * (0.5f + p.curiosity) * cool("survey", 60f),
            "hum" to 0.03f * (0.2f + boredom) * (0.4f + p.playfulness) * (0.5f + valence.coerceAtLeast(0f)) * cool("hum", 90f),
            "peekaboo" to 0.012f * p.shyness * p.playfulness * 4f * cool("peekaboo", 180f),
            // Easter eggs: rare.
            "sneeze" to 0.004f * cool("sneeze", 600f),
            "yawn" to 0.04f * fatigue() * cool("yawn", 240f),
            "twirl" to 0.003f * p.playfulness * p.energy * 4f * (0.5f + valence.coerceAtLeast(0f)) * cool("twirl", 900f),
        ).map { (k, w) -> k to w * fresh(k) }.filter { it.second > 0f }
        val k = weighted(opts)
        remember(k)
        return when (k) {
            "blink" -> Beat.Blink
            "curious" -> Beat.Accent(FaceAccent.Curious, logRange(0.9f, 1.6f))
            "smile" -> Beat.Accent(FaceAccent.Glee, logRange(0.8f, 1.3f))
            "glance" -> {
                val side = if (rng.nextBoolean()) 1f else -1f
                val y = -0.3f + 0.6f * rng.nextFloat()
                Beat.Glance(side * (0.2f + 0.4f * rng.nextFloat()), y, logRange(0.4f, 1.1f), "idle")
            }
            "drowsy" -> Beat.Accent(FaceAccent.Drowsy, logRange(1.2f, 2.2f))
            "tilt" -> { lastUsed["tiltI"] = clock; Beat.Gesture(MotionDirector.Gesture.Tilt) }
            "wink" -> Beat.Face("hurt or cute", logRange(0.6f, 0.9f))
            "shyLook" -> Beat.Glance(-(0.3f + 0.3f * rng.nextFloat()), 0.3f + 0.2f * rng.nextFloat(), logRange(0.5f, 1.1f), "idle")
            "hop" -> Beat.Gesture(MotionDirector.Gesture.Hop)
            "worried" -> Beat.Accent(FaceAccent.Worried, logRange(1f, 1.8f))
            "daydream" -> {
                // Gaze drifts up to one side, lids soften, slow blink, back.
                val hold = logRange(1.3f, 2.4f)
                sequence(
                    0.6f to Beat.Accent(FaceAccent.Drowsy, hold * 0.8f),
                    hold * 0.7f to Beat.Blink,
                )
                Beat.Glance((if (rng.nextBoolean()) 1f else -1f) * (0.35f + 0.25f * rng.nextFloat()), -(0.2f + 0.15f * rng.nextFloat()), hold.coerceAtMost(1.5f), "think")
            }
            "survey" -> {
                // Look around the screen: 3 irregular stops, anywhere.
                var t = 0f
                val steps = List(3) {
                    t += logRange(0.7f, 1.5f)
                    val x = -0.6f + 1.2f * rng.nextFloat()
                    val y = -0.35f + 0.7f * rng.nextFloat()
                    t to (Beat.Glance(x, y, logRange(0.35f, 0.8f), "idle") as Beat)
                }
                sequence(*steps.toTypedArray())
                Beat.Blink
            }
            "hum" -> {
                // Bopping along to a tune only it hears: 2–3 small irregular nods.
                val n = 2 + rng.nextInt(2)
                var t = 0f
                sequence(*List(n) { t += logRange(0.55f, 0.95f); t to (Beat.Gesture(MotionDirector.Gesture.Nod) as Beat) }.toTypedArray())
                Beat.Accent(FaceAccent.Glee, logRange(1.6f, 2.6f))
            }
            "peekaboo" -> {
                sequence(logRange(0.9f, 1.4f) to Beat.Accent(FaceAccent.Glee, 1.0f))
                Beat.Gesture(MotionDirector.Gesture.TurnAway)
            }
            "sneeze" -> {
                sequence(0.5f to Beat.Gesture(MotionDirector.Gesture.Recoil), 0.6f to Beat.DoubleBlink)
                Beat.Accent(FaceAccent.WakeSquint, 0.6f)
            }
            "yawn" -> {
                sequence(1.3f to Beat.Blink)
                Beat.Face("Sleeping", logRange(0.9f, 1.4f))
            }
            "twirl" -> {
                // Anticipation: a quick blink, then the spin.
                sequence(0.15f to Beat.Gesture(MotionDirector.Gesture.Spin), 1.9f to Beat.Accent(FaceAccent.Glee, 1.0f))
                Beat.Blink
            }
            else -> null
        }
    }
}
