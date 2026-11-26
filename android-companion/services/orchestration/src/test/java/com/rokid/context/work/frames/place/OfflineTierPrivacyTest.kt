package com.rokid.context.work.frames.place

import com.rokid.context.memory.Embedder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Codex round 3: the offline guess text never reaches a remote embedder, and a dead process's temp photo copy is removed at the next start. */
class OfflineTierPrivacyTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeEmbedder : Embedder {
        var calls = 0
        override val fingerprint = "fake"
        override suspend fun embedDocuments(texts: List<String>): List<FloatArray> { calls++; return texts.map { floatArrayOf(1f) } }
        override suspend fun embedQuery(text: String): FloatArray = floatArrayOf(1f)
    }

    @Test fun remoteBindingGetsNoText() = runTest {
        val e = FakeEmbedder(); var built = 0
        val r = PlaceSamplers.LocalOnlyTextEmbed({ false }, { built++; e }).embed(listOf("museum gallery"))
        assertNull(r); assertEquals(0, e.calls); assertEquals(0, built)
    }

    @Test fun localBindingEmbeds() = runTest {
        val e = FakeEmbedder()
        assertEquals(1, PlaceSamplers.LocalOnlyTextEmbed({ true }, { e }).embed(listOf("a"))?.size)
    }

    @Test fun leftoverTierPhotoIsRemovedAtStart() {
        val f = tmp.newFile("offline-tier-photo.jpg").also { it.writeText("x") }
        PlaceSamplers.clearTierPhoto(tmp.root)
        assertFalse(f.exists())
    }
}
