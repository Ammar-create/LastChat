package me.rerere.rikkahub.smartcore

import java.io.DataInputStream
import java.io.InputStream

/**
 * LC Smart Core tokenizer: a byte-exact port of the mmBERT (Gemma-style) HF BPE tokenizer
 * (added-token split → " "→"▁" → Metaspace(prepend always, split) → BPE with byte fallback),
 * followed by the v5 vocab trim remap (old id → trimmed id, unknown → unk).
 *
 * Loaded from the compact `decision_tokenizer.bin` (built by tools/build_tok.py) instead of the
 * 34 MB tokenizer.json: only single-char ids, byte-fallback ids, sorted merge table and remap.
 */
class LcSmartCoreTokenizer private constructor(
    private val unkNew: Int,
    private val byteIds: IntArray,
    private val charIds: HashMap<Int, Int>,
    private val added: List<Pair<String, Int>>, // longest first
    private val mergeKeys: LongArray,
    private val mergeRanks: IntArray,
    private val mergeResults: IntArray,
    private val remap: IntArray, // old id → new id, -1 = not kept
) {
    data class Encoded(val ids: LongArray, val mask: LongArray, val rawIds: IntArray)

    /** Raw (untrimmed) ids for [text], without BOS/EOS. */
    fun encodeRaw(text: String): IntArray {
        val out = ArrayList<Int>(64)
        val seg = StringBuilder()
        var i = 0
        while (i < text.length) {
            val m = added.firstOrNull { text.startsWith(it.first, i) }
            if (m != null) {
                flush(seg, out); seg.setLength(0); out.add(m.second); i += m.first.length
            } else { seg.append(text[i]); i++ }
        }
        flush(seg, out)
        return out.toIntArray()
    }

    /** Model input: `<bos> tokens[:maxLen-2] <eos>`, trimmed vocab, right-padded to [maxLen]. */
    fun encodeForModel(text: String, maxLen: Int = MAX_LEN): Encoded {
        // Cap the input; the model only sees ~254 tokens anyway.
        val raw = encodeRaw(text.take(MAX_CHARS))
        val n = minOf(raw.size, maxLen - 2)
        val full = IntArray(n + 2)
        full[0] = BOS; System.arraycopy(raw, 0, full, 1, n); full[n + 1] = EOS
        val ids = LongArray(maxLen); val mask = LongArray(maxLen)
        for (k in full.indices) {
            val o = full[k]
            val nw = if (o in remap.indices) remap[o] else -1
            ids[k] = (if (nw >= 0) nw else unkNew).toLong(); mask[k] = 1L
        }
        return Encoded(ids, mask, full)
    }

    private fun flush(seg: CharSequence, out: MutableList<Int>) {
        if (seg.isEmpty()) return
        var s = seg.toString().replace(' ', SP)
        if (s[0] != SP) s = SP + s
        var start = 0
        for (k in 1 until s.length) if (s[k] == SP) { bpe(s.substring(start, k), out); start = k }
        bpe(s.substring(start), out)
    }

    private fun bpe(word: String, out: MutableList<Int>) {
        val syms = ArrayList<Int>(word.length + 4)
        var fuse = false
        var k = 0
        while (k < word.length) {
            val cp = word.codePointAt(k); k += Character.charCount(cp)
            val id = charIds[cp]
            if (id != null) { syms.add(id); fuse = false; continue }
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            val bs = IntArray(bytes.size) { byteIds[bytes[it].toInt() and 0xFF] }
            if (bs.any { it < 0 }) {
                if (!(fuse && syms.isNotEmpty() && syms.last() == UNK_OLD)) syms.add(UNK_OLD)
                fuse = true; continue
            }
            bs.forEach { syms.add(it) }; fuse = false
        }
        while (syms.size > 1) {
            var bestRank = Int.MAX_VALUE; var bestAt = -1; var bestRes = 0
            for (j in 0 until syms.size - 1) {
                val idx = mergeKeys.binarySearch((syms[j].toLong() shl 32) or syms[j + 1].toLong())
                if (idx >= 0 && mergeRanks[idx] < bestRank) { bestRank = mergeRanks[idx]; bestAt = j; bestRes = mergeResults[idx] }
            }
            if (bestAt < 0) break
            syms[bestAt] = bestRes; syms.removeAt(bestAt + 1)
        }
        out.addAll(syms)
    }

    companion object {
        const val MAX_LEN = 256
        const val MAX_CHARS = 2000
        const val BOS = 2
        const val EOS = 1
        private const val UNK_OLD = 3
        private const val SP = '\u2581'

        fun load(input: InputStream): LcSmartCoreTokenizer = DataInputStream(input.buffered(1 shl 16)).use { d ->
            val magic = ByteArray(4); d.readFully(magic)
            require(String(magic) == "LCTK") { "Not an LC Smart Core tokenizer file" }
            require(d.readInt() == 1) { "Unsupported tokenizer version" }
            val unk = d.readInt(); val nChars = d.readInt(); val nAdded = d.readInt(); val nMerges = d.readInt()
            val byteIds = IntArray(256) { d.readInt() }
            val chars = HashMap<Int, Int>(nChars * 2)
            repeat(nChars) { chars[d.readInt()] = d.readInt() }
            val added = ArrayList<Pair<String, Int>>(nAdded)
            repeat(nAdded) {
                val id = d.readInt(); val b = ByteArray(d.readInt()); d.readFully(b)
                added.add(String(b, Charsets.UTF_8) to id)
            }
            added.sortByDescending { it.first.length }
            val keys = LongArray(nMerges); val ranks = IntArray(nMerges); val res = IntArray(nMerges)
            for (k in 0 until nMerges) { keys[k] = d.readLong(); ranks[k] = d.readInt(); res[k] = d.readInt() }
            val oldVocab = d.readInt(); val nKept = d.readInt()
            val remap = IntArray(oldVocab) { -1 }
            for (k in 0 until nKept) remap[d.readInt()] = k
            LcSmartCoreTokenizer(unk, byteIds, chars, added, keys, ranks, res, remap)
        }
    }
}
