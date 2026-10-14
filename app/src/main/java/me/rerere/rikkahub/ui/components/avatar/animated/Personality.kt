package me.rerere.rikkahub.ui.components.avatar.animated

/**
 * Continuous character traits, each 0…1 (0.5 = average). They shape timing,
 * amplitude, expression weights, gaze habits, blink rate, idle behaviour and
 * reaction choices in [AlivenessEngine].
 *
 * Designed to be set from outside later (e.g. inferred from a character's system
 * prompt); everything clamps, so any values are safe.
 */
data class Personality(
    val energy: Float = 0.5f,
    val curiosity: Float = 0.5f,
    val warmth: Float = 0.5f,
    val playfulness: Float = 0.5f,
    /** 0 = confident, 1 = shy. */
    val shyness: Float = 0.4f,
    val expressiveness: Float = 0.5f,
    val attentiveness: Float = 0.5f,
    val mischief: Float = 0.3f,
    val calmness: Float = 0.5f,
) {
    fun clamped() = Personality(
        c(energy), c(curiosity), c(warmth), c(playfulness), c(shyness),
        c(expressiveness), c(attentiveness), c(mischief), c(calmness),
    )

    /** Beat frequency multiplier (higher = more frequent). */
    val tempo: Float get() = 0.6f + 0.8f * energy - 0.3f * (calmness - 0.5f)
    /** Expression strength / how readily non-neutral faces appear. */
    val showiness: Float get() = 0.5f + expressiveness
    /** Blink rate multiplier. */
    val blinkRate: Float get() = 0.8f + 0.4f * energy + 0.3f * shyness - 0.2f * calmness

    private fun c(v: Float) = v.coerceIn(0f, 1f)

    companion object {
        /** Generical: warm, curious, a little playful. */
        val GENERICAL = Personality(
            energy = 0.55f, curiosity = 0.75f, warmth = 0.8f, playfulness = 0.6f,
            shyness = 0.3f, expressiveness = 0.65f, attentiveness = 0.7f,
            mischief = 0.3f, calmness = 0.55f,
        )
        /** Grok-eyed marks: more composed, a bit drier and more mischievous. */
        val GROK = Personality(
            energy = 0.5f, curiosity = 0.6f, warmth = 0.5f, playfulness = 0.55f,
            shyness = 0.2f, expressiveness = 0.45f, attentiveness = 0.6f,
            mischief = 0.55f, calmness = 0.65f,
        )

        fun defaultFor(eyeType: String): Personality =
            if (eyeType.equals("grok", ignoreCase = true)) GROK else GENERICAL
    }
}

/**
 * Per-message tone hint (from a classifier/decision model later). Valence −1…1,
 * arousal 0…1, plus an optional named emotion that briefly colours the face.
 */
data class ToneHint(
    val valence: Float,
    val arousal: Float,
    val emotion: Emotion? = null,
    val intensity: Float = 0.6f,
)

enum class Emotion { Joy, Amusement, Interest, Surprise, Concern, Sadness, Confusion, Pride, Calm }
