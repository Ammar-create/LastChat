package me.rerere.rikkahub.smartcore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.ui.components.avatar.animated.Emotion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goldens come from the v5 eval pool + a few tone probes, tokenized with the HF tokenizer and
 * scored with the shipped decision.int8.onnx (tools/smartcore/probe.py). The tokenizer fixture
 * is a subset of decision_tokenizer.bin holding exactly the merges those texts touch.
 */
class LcSmartCoreParityTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("smartcore/$name")!!
    private val tok by lazy { res("tokenizer_subset.bin").use { LcSmartCoreTokenizer.load(it) } }
    private val goldens by lazy { Json.parseToJsonElement(res("goldens.json").readBytes().decodeToString()).jsonArray }

    @Test fun tokenizerMatchesHuggingFace() {
        assertTrue(goldens.size >= 8)
        for (g in goldens) {
            val o = g.jsonObject
            val text = o["text"]!!.jsonPrimitive.content
            val enc = tok.encodeForModel(LcSmartCoreHeads.msgInput(text))
            val raw = o["raw_ids"]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
            assertArrayEquals("raw ids: $text", raw, enc.rawIds)
            val ids = o["ids"]!!.jsonArray.map { it.jsonPrimitive.int.toLong() }.toLongArray()
            assertArrayEquals("trimmed ids: $text", ids, enc.ids.copyOf(ids.size))
            assertEquals(ids.size.toLong(), enc.mask.sum())
            assertTrue(enc.ids.drop(ids.size).all { it == 0L })
        }
    }

    private fun decision(name: String) = goldens.first { it.jsonObject["text"]!!.jsonPrimitive.content == name }
        .jsonObject["logits"]!!.jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.float }.toFloatArray() }
        .let { LcSmartCoreHeads.decode(it) }

    @Test fun headDecodingMatchesReference() {
        for (g in goldens) {
            val logits = g.jsonObject["logits"]!!.jsonObject
            val d = LcSmartCoreHeads.decode(logits.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.float }.toFloatArray() })
            for ((head, labels) in LcSmartCoreHeads.HEADS) {
                val l = logits[head]!!.jsonArray.map { it.jsonPrimitive.float }
                assertEquals(head, labels[l.indices.maxBy { l[it] }], d[head]!!.label)
            }
            assertEquals(6, d.emo6.size)
        }
        assertEquals("happy", decision("OMG I got the job!!!")["emotion"]!!.label)
        assertEquals("sad", decision("I'm really sad today")["emotion"]!!.label)
        assertEquals("neutral", decision("hey")["emotion"]!!.label)
    }

    @Test fun toneHintIsSparseAndMapped() {
        assertNull(LcSmartCoreHeads.toneHint(decision("hey")))
        assertEquals(Emotion.Joy, LcSmartCoreHeads.toneHint(decision("OMG I got the job!!!"))!!.emotion)
        val sad = LcSmartCoreHeads.toneHint(decision("I'm really sad today"))!!
        assertEquals(Emotion.Sadness, sad.emotion); assertTrue(sad.valence < 0)
    }
}
