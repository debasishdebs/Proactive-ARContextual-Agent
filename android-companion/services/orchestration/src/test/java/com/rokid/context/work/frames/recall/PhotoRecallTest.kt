package com.rokid.context.work.frames.recall

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.FrameVectorEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.retrieval.BruteForceVectorIndex
import com.rokid.context.memory.retrieval.LexicalRetriever
import com.rokid.context.memory.retrieval.MemoryRetriever
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.memory.retrieval.ScoredMemory
import com.rokid.context.memory.retrieval.SemanticRetriever
import com.rokid.context.memory.retrieval.VectorCodec
import com.rokid.context.work.frames.scene.ImageSpaceTarget
import com.rokid.context.work.frames.scene.ImageSpaces
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhotoRecallTest {
    private val db: ContextDatabase = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    private val hour = 3_600_000L
    private var queryVec: FloatArray = floatArrayOf(1f, 0f)
    private var embedFails = false
    private var targets: List<ImageSpaceTarget>? = null
    private var captionHits: List<ScoredMemory> = emptyList()

    private fun target(space: String, active: Boolean, dims: Int = 2) = ImageSpaceTarget(
        space, "fp", dims, { emptyList() },
        { if (embedFails) throw IOException("offline") else queryVec }, active,
    )

    private fun recall(images: List<ImageSpaceTarget> = listOf(target("s1", true))) =
        PhotoRecall(db, ImageSpaces { targets ?: images }, { _, _, _ -> captionHits }, { 10_000L })

    private suspend fun photo(
        id: String, vec: FloatArray? = null, space: String = "s1", stored: Boolean = true, ts: Long = 5 * hour,
        createdAt: Long = 10 * hour, capturedAt: Long? = null, cluster: String? = null,
    ) {
        if (stored) db.frameDao().insertIgnore(FrameEntity(id, ts, "/$id.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, 640, 0.9f, null, cluster, null, space, createdAt, capturedAtMs = capturedAt))
        if (vec != null) db.frameVectorDao().upsert(listOf(FrameVectorEntity(id, space, VectorCodec.encode(vec))))
    }

    private suspend fun caption(frameId: String, memId: String = "cap_$frameId", text: String = "caption of $frameId", type: String = "caption", at: Long = 1_000) {
        db.memoryDao().insert(MemoryEntity(memId, frameId, text, type, "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, at, false, text))
    }

    private fun scored(m: MemoryEntity, score: Double) = ScoredMemory(m, null, null, score, score)
    private suspend fun scoredOf(memId: String, score: Double) = scored(db.memoryViewDao().byId(memId)!!, score)

    @Test fun imageHitsFromActiveSpaceOnly() = runBlocking {
        photo("a", floatArrayOf(1f, 0f)); photo("b", floatArrayOf(0f, 1f))
        photo("building", floatArrayOf(1f, 0f), space = "s2")
        val r = recall(listOf(target("s2", false), target("s1", true))).search("milk") // the ACTIVE target is not first
        assertEquals(ImageSearchState.RAN, r.imageState)
        assertEquals(listOf("a", "b"), r.image.map { it.frameId })
        assertTrue(r.image.all { it.source == PhotoSource.IMAGE })
        assertEquals(2, r.scanned)
    }

    @Test fun buildingOnlyTargetsIsNoImageSpace() = runBlocking {
        photo("building", floatArrayOf(1f, 0f), space = "s2")
        val r = recall(listOf(target("s2", false))).search("milk")
        assertEquals(ImageSearchState.NO_IMAGE_SPACE, r.imageState); assertTrue(r.image.isEmpty())
    }

    @Test fun noImageSpaceReturnsCaptionsOnly() = runBlocking {
        photo("a"); caption("a"); captionHits = listOf(scoredOf("cap_a", 0.7))
        val r = recall(emptyList()).search("milk")
        assertEquals(ImageSearchState.NO_IMAGE_SPACE, r.imageState)
        assertTrue(r.image.isEmpty()); assertEquals(listOf("a"), r.captions.map { it.frameId })
        assertEquals(PhotoSource.CAPTION, r.captions.single().source)
    }

    @Test fun captionFallbackDedupesByFrameAndSkipsImageFrames() = runBlocking {
        photo("a", floatArrayOf(1f, 0f)); photo("b"); photo("c")
        caption("a"); caption("b"); caption("b", memId = "fact_b", type = "fact", text = "fact"); caption("c")
        db.memoryDao().insert(MemoryEntity("nofr", null, "x", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "x"))
        captionHits = listOf(scoredOf("cap_a", 0.9), scoredOf("cap_b", 0.5), scoredOf("fact_b", 0.8), scoredOf("cap_c", 0.6), scoredOf("nofr", 0.99))
        val r = recall().search("milk")
        assertEquals(listOf("a"), r.image.map { it.frameId })
        assertEquals(listOf("b", "c"), r.captions.map { it.frameId }) // b keeps its best score 0.8; a is in the image list; no frameId dropped
        assertEquals(0.8, r.captions.first().score, 1e-9)
    }

    @Test fun expiredPhotoReturnedWithCaptionAndNotStored() = runBlocking {
        photo("old", floatArrayOf(1f, 0f), stored = false); caption("old")
        val r = recall().search("milk")
        val h = r.image.single()
        assertFalse(h.photoStored); assertEquals("cap_old", h.captionMemoryId); assertEquals("caption of old", h.captionText)
    }

    @Test fun expiredPhotoHasCaptureTsAndReceivedAt() = runBlocking {
        // captured at 5 h, received at 10 h (delivery was late)
        photo("withCapture", floatArrayOf(1f, 0f), stored = false, createdAt = 10 * hour, capturedAt = 5 * hour)
        photo("preV5", floatArrayOf(0.9f, 0.1f), stored = false, createdAt = 10 * hour, capturedAt = null)
        photo("live", floatArrayOf(0.8f, 0.2f), stored = true, ts = 5 * hour, createdAt = 10 * hour)
        val byId = recall().search("milk").image.associateBy { it.frameId }
        assertEquals(5 * hour, byId.getValue("withCapture").ts); assertEquals(10 * hour, byId.getValue("withCapture").receivedAt)
        assertNull(byId.getValue("preV5").ts); assertEquals(10 * hour, byId.getValue("preV5").receivedAt)
        assertEquals(5 * hour, byId.getValue("live").ts)
    }

    @Test fun forgottenFrameNeverReturned() = runBlocking {
        photo("a", floatArrayOf(1f, 0f)); photo("gone", floatArrayOf(1f, 0f)); caption("gone")
        db.frameMetaDao().deleteFor(listOf("gone")) // forgotten mid-search: no meta
        captionHits = listOf(scoredOf("cap_gone", 0.9))
        val r = recall().search("milk")
        assertEquals(listOf("a"), r.image.map { it.frameId }); assertTrue(r.captions.isEmpty())
    }

    @Test fun describedFlagFromMemoryOrFrameSource() = runBlocking {
        photo("viaMem", floatArrayOf(1f, 0f)); caption("viaMem")
        photo("viaSource", floatArrayOf(0.9f, 0.1f))
        db.memoryDao().insert(MemoryEntity("fact", null, "f", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "f"))
        db.frameSourceDao().insertAll(listOf(FrameSourceEntity("fact", "viaSource", null, "FACT")))
        photo("plain", floatArrayOf(0.5f, 0.5f))
        photo("invalidated", floatArrayOf(0.4f, 0.6f)); caption("invalidated")
        db.forgetDao().invalidate(listOf("cap_invalidated"))
        val byId = recall().search("milk").image.associateBy { it.frameId }
        assertTrue(byId.getValue("viaMem").described); assertTrue(byId.getValue("viaSource").described)
        assertFalse(byId.getValue("plain").described); assertFalse(byId.getValue("invalidated").described)
    }

    @Test fun clusterIdCarried() = runBlocking {
        photo("a", floatArrayOf(1f, 0f), cluster = "cl1")
        assertEquals("cl1", recall().search("milk").image.single().clusterId)
    }

    @Test fun embedFailureIsFailedNotThrown() = runBlocking {
        photo("a", floatArrayOf(1f, 0f)); embedFails = true
        val r = recall().search("milk")
        assertEquals(ImageSearchState.FAILED, r.imageState); assertTrue(r.image.isEmpty())
        embedFails = false; queryVec = floatArrayOf(0f, 0f) // unusable query
        assertEquals(ImageSearchState.FAILED, recall().search("milk").imageState)
        queryVec = floatArrayOf(1f) // narrower than the declared width
        assertEquals(ImageSearchState.FAILED, recall().search("milk").imageState)
    }

    @Test fun targetsFailureIsFailed() = runBlocking {
        val r = PhotoRecall(db, ImageSpaces { throw IllegalStateException("config") }, { _, _, _ -> emptyList() }).search("milk")
        assertEquals(ImageSearchState.FAILED, r.imageState)
    }

    @Test fun captionPoolBeyondTwentyWithRealRetriever() = runBlocking {
        val spaces = EmbeddingSpaces(db.embeddingDao())
        val embedder = object : Embedder {
            override val fingerprint = "fp"
            override suspend fun embedDocuments(texts: List<String>) = texts.map { floatArrayOf(1f, 0f) }
            override suspend fun embedQuery(text: String) = floatArrayOf(1f, 0f)
        }
        val retriever = MemoryRetriever(LexicalRetriever(db.retrievalDao()), SemanticRetriever(spaces, BruteForceVectorIndex(db.embeddingDao())) { embedder }, db.memoryViewDao())
        val day = 86_400_000L; val now = 100 * day
        val memories = MemoryRepository(db.memoryDao())
        (0 until 30).forEach { i ->
            val frame = if (i == 24) "f24" else null // the photo-backed hit ranks 25th by recency
            memories.add(MemoryEntity("m$i", frame, "milk $i", "caption", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, now - i * day, false, "milk $i"))
        }
        photo("f24")
        val settings = RetrievalSettings(mode = RetrievalMode.LEXICAL, topK = 8)
        val r = PhotoRecall(db, ImageSpaces { emptyList() }, { q, pool, n -> retriever.retrieve(q, settings, n, poolOverride = pool).hits }, { now }).search("milk")
        assertEquals(listOf("f24"), r.captions.map { it.frameId })
    }

    @Test fun logHasNoQueryText() = runBlocking {
        photo("a", floatArrayOf(1f, 0f))
        recall().search("secret-query-words")
        val lines = ShadowLog.getLogs().filter { it.tag == "PcRecall" }
        assertEquals(1, lines.size)
        assertTrue(lines.single().msg.startsWith("img=1 cap=0 scanned=1"))
        assertTrue(ShadowLog.getLogs().none { it.msg.contains("secret-query-words") })
    }

    @Test fun captionRetrieverFailureIsAStateNotEmpty() = runBlocking {
        photo("a", floatArrayOf(1f, 0f))
        val r = PhotoRecall(db, ImageSpaces { listOf(target("s1", true)) }, { _, _, _ -> throw IllegalStateException("index") }, { 10_000L }).search("milk")
        assertEquals(CaptionSearchState.FAILED, r.captionState); assertEquals(listOf("a"), r.image.map { it.frameId })
        assertEquals(CaptionSearchState.RAN, recall().search("milk").captionState)
    }

    @Test fun captionCutHappensAfterDroppingFramesWithoutMeta() = runBlocking {
        photo("a"); photo("b"); caption("a"); caption("b")
        db.memoryDao().insert(MemoryEntity("legacy", "nometa", "x", "caption", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "x"))
        captionHits = listOf(scoredOf("legacy", 0.99), scoredOf("cap_a", 0.9), scoredOf("cap_b", 0.8))
        val r = recall(emptyList()).search("milk", captionK = 1)
        assertEquals(listOf("a"), r.captions.map { it.frameId })
    }

    @Test fun nonFiniteQueryVectorIsFailed() = runBlocking {
        photo("a", floatArrayOf(1f, 0f)); queryVec = floatArrayOf(Float.NaN, 1f)
        assertEquals(ImageSearchState.FAILED, recall().search("milk").imageState)
    }
}
