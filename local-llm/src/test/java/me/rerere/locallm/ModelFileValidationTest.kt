package me.rerere.locallm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFileValidationTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun acceptsLitertlmMagic() {
        assertNull(ModelInstall.litertlmHeaderProblem(bytes("LITERTLM\u0001\u0000\u0000\u0000")))
    }

    @Test
    fun namesCommonBadDownloads() {
        assertEquals("html_page", ModelInstall.litertlmHeaderProblem(bytes("<!DOCTYPE html><html>")))
        assertEquals("lfs_pointer", ModelInstall.litertlmHeaderProblem(bytes("version https://git-lfs.github.com/spec/v1\noid")))
        assertEquals("tflite", ModelInstall.litertlmHeaderProblem(bytes("\u001c\u0000\u0000\u0000TFL3\u0000\u0000")))
        assertEquals("zip_task", ModelInstall.litertlmHeaderProblem(bytes("PK\u0003\u0004abcd")))
        assertEquals("too_small", ModelInstall.litertlmHeaderProblem(ByteArray(0)))
        assertEquals("not_litertlm", ModelInstall.litertlmHeaderProblem(bytes("LTLM\u0000\u0000\u0000\u0000")))
    }

    @Test
    fun cleansNativeEnginePaths() {
        val raw = "INTERNAL: ERROR: [third_party/odml/litert_lm/runtime/executor/llm_litert_compiled_model_executor.cc:1948] └ ERROR: [./third_party/odml/litert/litert/cc/litert_tensor_buffer.h:130]"
        val e = LiteRtRuntime.IncompatibleModelException.from(RuntimeException(raw))
        assertEquals(LiteRtRuntime.IncompatibleModelException.Reason.UNSUPPORTED, e.reason)
        assertFalse(e.detail.contains("third_party"))
        assertTrue(e.detail.startsWith("INTERNAL"))

        val magic = LiteRtRuntime.IncompatibleModelException.from(
            RuntimeException("INVALID_ARGUMENT: Invalid magic number or failed to read: LTLM")
        )
        assertEquals(LiteRtRuntime.IncompatibleModelException.Reason.INVALID_FILE, magic.reason)
    }
}
