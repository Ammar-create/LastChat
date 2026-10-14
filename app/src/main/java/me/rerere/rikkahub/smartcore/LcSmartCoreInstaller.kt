package me.rerere.rikkahub.smartcore

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Downloads LC Smart Core into `filesDir/lc_smart_core/` (kept out of the APK on purpose).
 * Files are verified by SHA-256 and swapped in atomically.
 */
object LcSmartCoreInstaller {
    data class Artifact(val name: String, val bytes: Long, val sha256: String)

    val ARTIFACTS = listOf(
        Artifact(LcSmartCore.MODEL_FILE, 73_712_984, "84db7c21a46ad567281dc651776398dfa91f59f96112a967469365afc1378ee0"),
        Artifact(LcSmartCore.TOKENIZER_FILE, 9_724_976, "6587a93d95972b7e09aeadc160b636512bb4b180f3fd61b9cec013626f7b3fbd"),
    )

    /**
     * Public base URL hosting the artifacts (e.g. a Hugging Face repo `.../resolve/main/`).
     * Empty until a host is chosen; until then the app falls back to the heuristic tone reader.
     */
    const val DEFAULT_BASE_URL = ""

    suspend fun install(
        context: Context,
        client: OkHttpClient,
        baseUrl: String = DEFAULT_BASE_URL,
        onProgress: (Float) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(baseUrl.isNotBlank()) { "No LC Smart Core download location configured" }
            val dir = LcSmartCore.dir(context).apply { mkdirs() }
            val total = ARTIFACTS.sumOf { it.bytes }.toFloat(); var done = 0L
            for (a in ARTIFACTS) {
                val dst = File(dir, a.name)
                if (dst.isFile && sha256(dst) == a.sha256) { done += a.bytes; continue }
                val tmp = File(dir, a.name + ".part")
                client.newCall(Request.Builder().url(baseUrl.trimEnd('/') + "/" + a.name).build()).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code} for ${a.name}" }
                    resp.body.byteStream().use { inp ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n; onProgress(done / total)
                            }
                        }
                    }
                }
                check(sha256(tmp) == a.sha256) { tmp.delete(); "Checksum mismatch for ${a.name}" }
                check(tmp.renameTo(dst)) { "Could not move ${a.name}" }
            }
            LcSmartCore.reset()
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
