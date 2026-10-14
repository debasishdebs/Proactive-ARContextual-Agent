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
    }

    @Test fun onlyBgeSmallIsListedOnDayOne() {
        val m = LocalModels.all.single()
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
