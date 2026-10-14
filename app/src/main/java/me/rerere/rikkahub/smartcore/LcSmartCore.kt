package me.rerere.rikkahub.smartcore

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer

/**
 * LC Smart Core — the on-device decision model (v5: mmBERT-small, 14 softmax heads + emo6 + span,
 * int8 ONNX, ~74 MB). Not bundled in the APK: lives in `filesDir/lc_smart_core/` once installed
 * (see [LcSmartCoreInstaller]). Everything here is lazy and off the main thread; if the model is
 * absent or fails, callers get null and fall back to heuristics.
 */
object LcSmartCore {
    private const val TAG = "LcSmartCore"
    const val MODEL_FILE = "decision.int8.onnx"
    const val TOKENIZER_FILE = "decision_tokenizer.bin"
    const val VERSION = "v5"

    fun dir(context: Context) = File(context.filesDir, "lc_smart_core")
    fun isInstalled(context: Context): Boolean =
        File(dir(context), MODEL_FILE).isFile && File(dir(context), TOKENIZER_FILE).isFile

    private val lock = Mutex()
    private var session: OrtSession? = null
    private var tokenizer: LcSmartCoreTokenizer? = null
    @Volatile private var failed = false

    private fun ensure(context: Context): Boolean {
        if (session != null) return true
        if (failed || !isInstalled(context)) return false
        return try {
            val d = dir(context)
            tokenizer = File(d, TOKENIZER_FILE).inputStream().use { LcSmartCoreTokenizer.load(it) }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = OrtEnvironment.getEnvironment().createSession(File(d, MODEL_FILE).absolutePath, opts)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "LC Smart Core unavailable", t); failed = true; false
        }
    }

    /** Classify one message. ~100–600 ms on a phone; returns null when the model isn't available. */
    suspend fun classify(context: Context, text: String): LcSmartCoreHeads.Decision? = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext null
        lock.withLock {
            if (!ensure(context.applicationContext)) return@withLock null
            runCatching { run(session!!, tokenizer!!, LcSmartCoreHeads.msgInput(text)) }
                .onFailure { Log.w(TAG, "classify failed", it) }.getOrNull()
        }
    }

    internal fun run(s: OrtSession, tok: LcSmartCoreTokenizer, input: String): LcSmartCoreHeads.Decision {
        val enc = tok.encodeForModel(input)
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, LcSmartCoreTokenizer.MAX_LEN.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(enc.ids), shape).use { ids ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(enc.mask), shape).use { mask ->
                val wanted = (LcSmartCoreHeads.HEADS.keys + LcSmartCoreHeads.ML_HEADS.keys).toSet()
                s.run(mapOf("input_ids" to ids, "attention_mask" to mask), wanted).use { r ->
                    val logits = wanted.associateWith { name ->
                        @Suppress("UNCHECKED_CAST")
                        (r.get(name).get().value as Array<FloatArray>)[0]
                    }
                    return LcSmartCoreHeads.decode(logits)
                }
            }
        }
    }

    /** Drop the session (e.g. after reinstall). */
    suspend fun reset() = lock.withLock { session?.close(); session = null; tokenizer = null; failed = false }
}
