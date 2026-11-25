package com.rokid.context.ai.embed.siglip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class SigLipBundleTest {
    private fun sparse(dir: File, name: String, bytes: Long) { RandomAccessFile(File(dir, name), "rw").use { it.setLength(bytes) } }
    private fun tmp() = File(System.getProperty("java.io.tmpdir"), "siglip-bundle-" + System.nanoTime()).also { it.mkdirs() }

    @Test fun `nothing present means no embedder and no throw`() {
        val d = tmp()
        try {
            val p = SigLipBundle.presence(d)
            assertFalse(p.usable); assertFalse(p.textUsable)
            assertNull(SigLipBundle.open(d))
            assertNull(SigLipBundle.open(File(d, "missing")))
        } finally { d.deleteRecursively() }
    }

    @Test fun `a wrong sized vision file is refused and the right size opens a lazy embedder`() {
        val d = tmp()
        try {
            sparse(d, SigLipBundle.VISION_FILE, 1234L)
            assertNull("a truncated download must not open", SigLipBundle.open(d))
            sparse(d, SigLipBundle.VISION_FILE, SigLipImageEmbedder.MODEL_BYTES)
            val p = SigLipBundle.presence(d)
            assertTrue(p.usable); assertFalse(p.textUsable)
            val e = SigLipBundle.open(d)
            assertNotNull(e); assertEquals(768, e!!.dim)
            assertTrue(e.spaceId.startsWith("siglip2")) // never mistaken for Nomic by MatchThresholds.spaceOf
            sparse(d, SigLipBundle.TEXT_FILE, SigLipTextEmbedder.MODEL_BYTES); sparse(d, SigLipBundle.TOKENIZER_FILE, SigLipBundle.TOKENIZER_BYTES)
            assertTrue(SigLipBundle.presence(d).textUsable)
        } finally { d.deleteRecursively() }
    }

    @Test fun `the bundle has no network dependency and the three names are distinct ascii`() {
        val names = listOf(SigLipBundle.VISION_FILE, SigLipBundle.TEXT_FILE, SigLipBundle.TOKENIZER_FILE)
        assertEquals(3, names.toSet().size); assertTrue(names.all { n -> n.all { it.code in 32..126 } })
        val src = File("src/main/java/com/rokid/context/ai/embed/siglip/SigLipBundle.kt")
        if (src.isFile) assertFalse(src.readText().let { t -> Regex("java\\.net|okhttp|HttpURLConnection|URL\\(").containsMatchIn(t.lines().filter { !it.trimStart().startsWith("*") && !it.trimStart().startsWith("/") }.joinToString("\n")) })
    }
}
