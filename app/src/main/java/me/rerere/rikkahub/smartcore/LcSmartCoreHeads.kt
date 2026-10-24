package me.rerere.rikkahub.smartcore

import me.rerere.rikkahub.ui.components.avatar.animated.Emotion
import me.rerere.rikkahub.ui.components.avatar.animated.ToneHint
import kotlin.math.exp

/** LC Smart Core v5 output heads (labels.json, run v5). Order of labels = logit index. */
object LcSmartCoreHeads {
    val HEADS: Map<String, List<String>> = linkedMapOf(
        "save" to listOf("0", "1"),
        "kind" to listOf("identity", "preference", "fact", "plan", "relationship", "boundary", "lore", "none"),
        "durability" to listOf("hours", "days", "weeks", "months", "years"),
        "frame" to listOf("real", "fiction", "ooc", "joke"),
        "sensitive" to listOf("none", "health", "sexuality", "beliefs", "politics", "finance", "third_party", "precise_location"),
        "scope" to listOf("USER", "CHARACTER", "PERSONA", "RELATIONSHIP", "WORLD", "CONVERSATION", "NONE"),
        "relation" to listOf("same", "update", "conflict", "unrelated"),
        "supported" to listOf("0", "1"),
        "topic_shift" to listOf("0", "1"),
        "needs_web" to listOf("0", "1"),
        "route" to listOf("fast", "reasoning", "code", "vision"),
        "widget" to listOf("none", "timer", "list", "table", "map", "calendar"),
        "emotion" to listOf("neutral", "happy", "sad", "angry", "confused", "excited"),
        "injection" to listOf("0", "1"),
    )
    /** Multi-label (sigmoid) heads. */
    val ML_HEADS: Map<String, List<String>> = mapOf("emo6" to listOf("anger", "disgust", "fear", "joy", "sadness", "surprise"))

    /** Input template for a single user/assistant message (task "msg", persona/story off). */
    fun msgInput(text: String) = "[T:msg] <persona=off> <story=off> [target] $text"

    data class Head(val label: String, val confidence: Float, val probs: FloatArray)
    data class Decision(val heads: Map<String, Head>, val emo6: Map<String, Float>) {
        operator fun get(head: String) = heads[head]
    }

    fun softmax(l: FloatArray): FloatArray {
        val m = l.max(); val e = FloatArray(l.size) { exp((l[it] - m).toDouble()).toFloat() }; val s = e.sum()
        return FloatArray(l.size) { e[it] / s }
    }
    fun sigmoid(x: Float) = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    fun decode(logits: Map<String, FloatArray>): Decision {
        val heads = HEADS.mapNotNull { (name, labels) ->
            val l = logits[name] ?: return@mapNotNull null
            require(l.size == labels.size) { "head $name: ${l.size} logits for ${labels.size} labels" }
            val p = softmax(l); val i = p.indices.maxBy { p[it] }
            name to Head(labels[i], p[i], p)
        }.toMap()
        val emo6 = ML_HEADS["emo6"]!!.let { labels ->
            logits["emo6"]?.let { l -> labels.indices.associate { labels[it] to sigmoid(l[it]) } } ?: emptyMap()
        }
        return Decision(heads, emo6)
    }

    /**
     * Avatar tone from the emotion + emo6 heads. Returns null for neutral / low-confidence reads, so
     * most messages don't produce a reaction at all; the avatar's own perception gate decides the rest.
     */
    fun toneHint(d: Decision, minConfidence: Float = 0.6f): ToneHint? {
        val e = d["emotion"] ?: return null
        val surprise = d.emo6["surprise"] ?: 0f
        val fear = d.emo6["fear"] ?: 0f
        if (e.label == "neutral" || e.confidence < minConfidence) {
            // A strong multi-label surprise can still register on an otherwise neutral read.
            return if (surprise > 0.8f) ToneHint(0.15f, 0.7f, Emotion.Surprise, 0.4f) else null
        }
        val intensity = (0.3f + 0.6f * (e.confidence - minConfidence) / (1f - minConfidence)).coerceIn(0.3f, 0.85f)
        return when (e.label) {
            "happy" -> ToneHint(0.6f, 0.55f, Emotion.Joy, intensity)
            "excited" -> ToneHint(0.55f, 0.85f, if (surprise > 0.5f) Emotion.Surprise else Emotion.Amusement, intensity)
            "sad" -> ToneHint(-0.6f, 0.3f, Emotion.Sadness, intensity)
            "angry" -> ToneHint(-0.5f, 0.75f, Emotion.Concern, intensity)
            "confused" -> ToneHint(-0.15f, 0.5f, Emotion.Confusion, intensity)
            else -> if (fear > 0.6f) ToneHint(-0.4f, 0.6f, Emotion.Concern, 0.4f) else null
        }
    }
}
