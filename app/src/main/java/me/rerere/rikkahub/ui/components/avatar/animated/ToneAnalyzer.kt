package me.rerere.rikkahub.ui.components.avatar.animated

/**
 * Tiny rule-based tone read of a finished reply (a stand-in until a learned
 * classifier feeds [ToneHint]s). Looks at emoji, punctuation and a short
 * multilingual word list; returns null when nothing stands out.
 */
object ToneAnalyzer {
    private val joy = listOf(
        "great", "awesome", "glad", "happy", "congrat", "love", "wonderful", "amazing", "yay", "excited",
        "super", "genial", "minunat", "bucuros", "felicit", "toll", "freue", "großartig", "wunderbar",
        "genial", "génial", "content", "开心", "太好", "恭喜",
    )
    private val amuse = listOf("haha", "lol", "funny", "joke", "😂", "😄", "😆", "hehe", "amuzant", "lustig", "哈哈")
    private val concern = listOf(
        "sorry", "unfortunately", "can't", "cannot", "error", "failed", "problem", "careful", "warning", "risk",
        "îmi pare rău", "din păcate", "leider", "entschuldig", "fehler", "抱歉", "遗憾", "错误",
    )
    private val sad = listOf("sad", "loss", "passed away", "grief", "trist", "traurig", "难过", "😢", "😞")
    private val interest = listOf("interesting", "curious", "fascinating", "did you know", "interesant", "spannend", "interessant", "有趣")
    private val surprise = listOf("wow", "whoa", "surpris", "incredible", "unbelievable", "!?", "😮", "😲", "uau", "krass", "哇")
    private val confusion = listOf("not sure", "unclear", "hmm", "i don't know", "nu sunt sigur", "unklar", "不确定")

    fun read(text: String): ToneHint? {
        if (text.isBlank()) return null
        val t = text.lowercase().take(4000)
        fun score(words: List<String>) = words.sumOf { w -> countOf(t, w) }.toFloat()
        val scores = mapOf(
            Emotion.Joy to score(joy) + 0.3f * countOf(t, "!"),
            Emotion.Amusement to score(amuse) * 1.4f,
            Emotion.Concern to score(concern),
            Emotion.Sadness to score(sad) * 1.3f,
            Emotion.Interest to score(interest),
            Emotion.Surprise to score(surprise),
            Emotion.Confusion to score(confusion),
        )
        val (emo, s) = scores.maxBy { it.value }
        if (s < 1f) return null
        val intensity = (0.35f + 0.15f * s).coerceAtMost(0.9f)
        val valence = when (emo) {
            Emotion.Joy, Emotion.Amusement, Emotion.Pride -> 0.6f
            Emotion.Interest, Emotion.Surprise -> 0.25f
            Emotion.Concern, Emotion.Confusion -> -0.25f
            Emotion.Sadness -> -0.6f
            Emotion.Calm -> 0.1f
        }
        val arousal = when (emo) {
            Emotion.Surprise, Emotion.Amusement, Emotion.Joy -> 0.75f
            Emotion.Sadness, Emotion.Calm -> 0.3f
            else -> 0.55f
        }
        return ToneHint(valence, arousal, emo, intensity)
    }

    private fun countOf(t: String, w: String): Int {
        var n = 0; var i = t.indexOf(w)
        while (i >= 0 && n < 5) { n++; i = t.indexOf(w, i + w.length) }
        return n
    }
}
