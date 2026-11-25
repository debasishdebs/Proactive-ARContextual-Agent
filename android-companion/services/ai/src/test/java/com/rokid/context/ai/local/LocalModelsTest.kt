package com.rokid.context.ai.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelsTest {
    @Test fun catalogEntriesArePinnedAndWellFormed() {
        val pinned = Regex("^https://[^/]+/.+/resolve/[0-9a-f]{40}/.+$")
        for (m in LocalModels.all) {
            assertTrue(m.id, m.url.startsWith("https://"))
            assertTrue(m.id, pinned.matches(m.url))
            assertTrue(m.id, Regex("^[0-9a-f]{64}$").matches(m.sha256))
            assertTrue(m.id, m.bytes > 0)
            assertTrue(m.id, m.files.size > 1 || m.url.endsWith("/" + m.file))
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

    @Test fun siglip2IsListedOptInWithSizeAndNeverTheDefault() {
        val m = LocalModels.byId("siglip2-base-p16-224")!!
        assertEquals(false, m.bindable)
        assertEquals(listOf("siglip2-base-p16-224.vision_model_quantized.onnx", "siglip2-base-p16-224.text_model_quantized.onnx", "siglip2-base-p16-224.tokenizer.model"), m.files.map { it.file })
        assertEquals(m.file, m.files.first().file)
        assertEquals(94_553_333L + 283_438_275L + 4_241_003L, m.totalBytes)
        assertEquals("5f2b401c1a4fc095702a5d45348e17ad46c4f87064085365b43c6e8eaa5c0070", m.files[0].sha256)
        assertEquals("3a0603d3a00c05a80a6ded4743c16aaac7b1e62cdcc7e362e7ce418659b96400", m.files[1].sha256)
        assertEquals("61a7b147390c64585d6c3543dd6fc636906c9af3865a5548f27f31aee1d4c8e2", m.files[2].sha256)
        assertEquals("Apache-2.0", m.license)
        // Listed after the existing entries, which keep their order; the others stay bindable and Nomic stays the only "Recommended".
        assertEquals(listOf("bge-small-en-v1.5-q8", "nomic-embed-v1.5", "siglip2-base-p16-224"), LocalModels.all.map { it.id })
        assertEquals(listOf("nomic-embed-v1.5"), LocalModels.all.filter { it.note == "Recommended" }.map { it.id })
        assertTrue(LocalModels.all.filter { it.id != m.id }.all { it.bindable })
    }
}
