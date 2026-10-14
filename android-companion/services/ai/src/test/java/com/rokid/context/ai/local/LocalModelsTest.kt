package com.rokid.context.ai.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelsTest {
    @Test fun catalogEntriesArePinnedAndWellFormed() {
        val pinned = Regex("^https://[^/]+/.+/resolve/[0-9a-f]{40}/[^/]+$")
        for (m in LocalModels.all) {
            assertTrue(m.id, m.url.startsWith("https://"))
            assertTrue(m.id, pinned.matches(m.url))
            assertTrue(m.id, Regex("^[0-9a-f]{64}$").matches(m.sha256))
            assertTrue(m.id, m.bytes > 0)
            assertTrue(m.id, m.url.endsWith("/" + m.file))
            assertEquals(m, LocalModels.byId(m.id))
        }
        assertEquals(LocalModels.all.size, LocalModels.all.map { it.id }.toSet().size)
        assertNull(LocalModels.byId("nope"))
        for (f in LocalModels.all.flatMap { it.files }) {
            assertTrue(f.file, Regex("^https://[^/]+/.+/resolve/[0-9a-f]{40}/.+$").matches(f.url))
            assertTrue(f.file, Regex("^[0-9a-f]{64}$").matches(f.sha256))
        }
    }

    @Test fun nomicPinnedToCommitAndSha() {
        val m = LocalModels.byId("nomic-embed-v1.5")!!
        assertEquals("Apache-2.0", m.license)
        assertEquals("Recommended", m.note)
        assertEquals(768, m.dims)
        val text = m.files[0]
        assertEquals("https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/0188c9bf409793f810680a5a431e7b899c46104c/nomic-embed-text-v1.5.Q8_0.gguf", text.url)
        assertEquals("3e24342164b3d94991ba9692fdc0dd08e3fd7362e0aacc396a9a5c54a544c3b7", text.sha256)
        assertEquals(146_146_432L, text.bytes)
        val vision = m.files[1]
        assertEquals("https://huggingface.co/nomic-ai/nomic-embed-vision-v1.5/resolve/e3a725bce72db07ca4adb1d83da08903f3ee02f8/onnx/model_quantized.onnx", vision.url)
        assertEquals("ba9107df6e412828dae8c675096209aa39f6536de8ec8d9a872665b54dc750c3", vision.sha256)
        assertEquals(96_745_606L, vision.bytes)
    }

    @Test fun bundleHasTextAndVisionFiles() {
        val m = LocalModels.byId("nomic-embed-v1.5")!!
        assertEquals(2, m.files.size)
        assertTrue(m.files.any { it.file.endsWith(".gguf") })
        assertTrue(m.files.any { it.file.endsWith(".onnx") })
        assertEquals(m.file, m.files.first().file) // the primary file is the text GGUF
        assertEquals(m.files.sumOf { it.bytes }, m.totalBytes)
        assertEquals(2, m.files.map { it.file }.toSet().size)
    }

    @Test fun bgeSmallStaysListedUnchanged() {
        val m = LocalModels.byId("bge-small-en-v1.5-q8")!!
        assertEquals(1, m.files.size)
        assertEquals("bge-small-en-v1.5-q8", m.id)
        assertEquals("bge-small (English)", m.title)
        assertEquals("bge-small-en-v1.5-q8_0.gguf", m.file)
        assertEquals(
            "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/d32f8c040ea3b516330eeb75b72bcc2d3a780ab7/bge-small-en-v1.5-q8_0.gguf",
            m.url,
        )
        assertEquals(36_806_944L, m.bytes)
        assertEquals("ec38e8da142596baa913124ae50550de284b6916bf59577ef2f0cb9660c2f514", m.sha256)
        assertEquals("English", m.languages)
        assertEquals(384, m.dims)
        assertEquals(90, m.ramEstimateMb)
        assertTrue(m.license.startsWith("MIT"))
        assertNull(m.note)
    }
}
