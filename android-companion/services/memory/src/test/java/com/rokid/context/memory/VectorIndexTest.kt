package com.rokid.context.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.EmbeddingDao
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.MemoryVectorEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.retrieval.BruteForceVectorIndex
import com.rokid.context.memory.retrieval.Hit
import com.rokid.context.memory.retrieval.VectorCodec
import com.rokid.context.memory.retrieval.VectorMath
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VectorIndexTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val index = BruteForceVectorIndex(db.embeddingDao())
    private val memories = MemoryRepository(db.memoryDao())
    @After fun close() = db.close()

    /** Stores the vector and (unless [memory] is false) an OK memory row for it: the index only scores live memories. */
    private suspend fun put(id: String, space: String, v: FloatArray, memory: Boolean = true, review: ReviewState = ReviewState.OK) {
        if (memory) memories.addIgnore(MemoryEntity(id, null, "m $id", "note", "", MemoryTier.SHORT, 0.8, review, false, 1, false, ""))
        db.embeddingDao().putVector(MemoryVectorEntity(id, space, VectorCodec.encode(v)))
    }

    @Test fun codecIsLittleEndianFloat32() {
        val bytes = VectorCodec.encode(floatArrayOf(1f, -2.5f))
        assertEquals(8, bytes.size); assertEquals(0x3F.toByte(), bytes[3]) // 1.0f = 0x3F800000, little-endian
        assertArrayEquals(floatArrayOf(1f, -2.5f), VectorCodec.decode(bytes), 0f)
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), VectorMath.l2(floatArrayOf(3f, 4f)), 1e-6f)
    }

    @Test fun ranksByCosineWithinOneSpaceOnly() = runBlocking {
        // memory_vector has no foreign key, so a literal space id is enough here (the memory rows must exist)
        put("near", "S", floatArrayOf(1f, 0.1f))
        put("far", "S", floatArrayOf(-1f, 0f))
        put("elsewhere", "OTHER", floatArrayOf(1f, 0f))
        val hits = index.search("S", floatArrayOf(1f, 0f), 5)
        assertEquals(listOf("near", "far"), hits.map { it.id })
        assertTrue(hits[0].score > 0.99 && hits[1].score < -0.99)
        assertEquals(listOf("near"), index.search("S", floatArrayOf(1f, 0f), 1).map { it.id })
    }

    @Test fun zeroAndMismatchedVectorsNeverProduceNaN() = runBlocking {
        put("zero", "S", floatArrayOf(0f, 0f))
        put("wrongDim", "S", floatArrayOf(1f, 0f, 0f))
        put("ok", "S", floatArrayOf(0f, 1f))
        val hits = index.search("S", floatArrayOf(0f, 1f), 5)
        assertEquals(listOf("ok", "zero"), hits.map { it.id })
        assertTrue(hits.none { it.score.isNaN() }); assertEquals(0.0, hits[1].score, 0.0)
        assertTrue(index.search("S", floatArrayOf(0f, 0f), 5).isEmpty())
    }
    @Test fun blobsRoundTripBitExactThroughTheDatabase() = runBlocking {
        val v = floatArrayOf(0f, -0f, Float.MIN_VALUE, Float.MAX_VALUE, -1.1754944E-38f, Float.fromBits(0x7FC00001), 3.1415927f)
        db.embeddingDao().putVector(MemoryVectorEntity("m", "S", VectorCodec.encode(v)))
        val back = VectorCodec.decode(db.embeddingDao().vectors("S").single().blob)
        assertEquals(v.map { it.toRawBits() }, back.map { it.toRawBits() })
    }

    @Test fun decodeRejectsATruncatedBlobAndTheIndexSkipsIt() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { VectorCodec.decode(ByteArray(7)) }
        put("broken", "S", floatArrayOf()); db.embeddingDao().putVector(MemoryVectorEntity("broken", "S", ByteArray(7)))
        put("ok", "S", floatArrayOf(1f, 0f))
        assertEquals(listOf("ok"), index.search("S", floatArrayOf(1f, 0f), 5).map { it.id })
    }

    @Test fun nonFiniteVectorsScoreZeroNotNaN() = runBlocking {
        assertEquals(0.0, VectorMath.cosine(floatArrayOf(Float.POSITIVE_INFINITY, 0f), floatArrayOf(1f, 0f)), 0.0)
        assertEquals(0.0, VectorMath.cosine(floatArrayOf(Float.NaN, 1f), floatArrayOf(1f, 0f)), 0.0)
        put("inf", "S", floatArrayOf(Float.POSITIVE_INFINITY, 0f))
        val hits = index.search("S", floatArrayOf(1f, 0f), 5)
        assertTrue(hits.none { it.score.isNaN() })
        assertTrue(index.search("S", floatArrayOf(Float.POSITIVE_INFINITY, 0f), 5).isEmpty())
    }

    @Test fun invalidatedNeedsReviewForgottenAndDeletedMemoriesAreNeverHits() = runBlocking {
        put("ok", "S", floatArrayOf(1f, 0f))
        put("invalidated", "S", floatArrayOf(1f, 0f), review = ReviewState.INVALIDATED)
        put("review", "S", floatArrayOf(1f, 0f), review = ReviewState.NEEDS_REVIEW)
        put("orphan", "S", floatArrayOf(1f, 0f), memory = false) // vector left behind by a deleted memory
        put("forgotten", "S", floatArrayOf(1f, 0f))
        memories.deleteByIds(listOf("forgotten")) // Forget / delete removes the memory row; its vector may linger
        assertEquals(listOf("ok"), index.search("S", floatArrayOf(1f, 0f), 10).map { it.id })
        db.openHelper.writableDatabase.execSQL("UPDATE memory SET reviewState = 'INVALIDATED' WHERE id = 'ok'")
        assertTrue(index.search("S", floatArrayOf(1f, 0f), 10).isEmpty())
    }

    @Test fun pagedBoundedHeapMatchesTheFullSortAcrossManyPages() = runBlocking {
        val rnd = java.util.Random(42)
        val all = (0 until 1_237).map { i ->
            // Every 7th vector repeats an earlier direction so equal scores straddle page boundaries.
            val v = if (i % 7 == 0 && i > 0) floatArrayOf(1f, 1f, 0f) else floatArrayOf(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f)
            "m${i.toString().padStart(4, '0')}" to v
        }
        all.forEach { (id, v) -> put(id, "S", v) }
        val q = floatArrayOf(0.7f, 0.7f, 0.1f)
        val full = all.map { (id, v) -> Hit(id, VectorMath.cosine(q, v)) }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.id })
        for (pageSize in listOf(1, 3, 500, 5_000)) {
            val paged = BruteForceVectorIndex(db.embeddingDao(), pageSize)
            for (k in listOf(1, 10, 100, 2_000)) assertEquals("page $pageSize k $k", full.take(k), paged.search("S", q, k))
        }
        assertTrue(index.search("S", q, 0).isEmpty())
    }

    @Test fun vectorsArriveInBoundedRowidPages() = runBlocking {
        (0 until 12).forEach { put("m$it", "S", floatArrayOf(1f, it.toFloat())) }
        put("x", "S", floatArrayOf(1f, 0f), review = ReviewState.INVALIDATED)
        val pages = generateSequence(db.embeddingDao().livePage("S", 0L, 0.0, 5)) { prev ->
            prev.takeIf { it.size == 5 }?.let { runBlocking { db.embeddingDao().livePage("S", it.last().rowid, 0.0, 5) } }
        }.toList()
        assertEquals(listOf(5, 5, 2), pages.map { it.size })
        assertEquals((0 until 12).map { "m$it" }, pages.flatten().map { it.memoryId }) // insertion (rowid) order
    }

    @Test fun livePageWalksTheSpaceIndexInRowidOrderWithoutATempSort() {
        val sql = "EXPLAIN QUERY PLAN " + EmbeddingDao.LIVE_PAGE_SQL.replace(":spaceId", "?").replace(":afterRowid", "?")
            .replace(":minConfidence", "?").replace(":limit", "?")
        val plan = db.openHelper.readableDatabase.query(sql, arrayOf<Any?>("S", 0L, 0.0, 5)).use { c -> buildList { while (c.moveToNext()) add(c.getString(3)) } }
        assertTrue(plan.toString(), plan.none { "TEMP B-TREE" in it })
        assertTrue(plan.toString(), plan.first().contains("USING INDEX index_memory_vector_spaceId (spaceId=? AND rowid>?)"))
    }

    /** B3: decoding into a reused buffer yields exactly the floats [VectorCodec.decode] returns. */
    @Test fun decodeIntoMatchesDecode() {
        val rnd = java.util.Random(3)
        val out = FloatArray(64)
        repeat(50) {
            val blob = VectorCodec.encode(FloatArray(64) { (rnd.nextGaussian() * 10).toFloat() })
            assertTrue(VectorCodec.decodeInto(blob, out))
            assertEquals(VectorCodec.decode(blob).map { it.toRawBits() }, out.map { it.toRawBits() })
        }
        assertFalse(VectorCodec.decodeInto(VectorCodec.encode(FloatArray(32)), out)) // wrong dimension
        assertFalse(VectorCodec.decodeInto(ByteArray(255), out)) // not float32-aligned
    }

    /** B3 (pin): a vector of another dimension is never a hit. */
    @Test fun aWrongDimensionBlobIsSkipped() = runBlocking {
        val rnd = java.util.Random(4)
        put("small", "S", FloatArray(32) { 1f })
        put("ok", "S", FloatArray(64) { rnd.nextFloat() })
        assertEquals(listOf("ok"), index.search("S", FloatArray(64) { 1f }, 10).map { it.id })
    }

    /** B3: 50 k x 64-d vectors (the spec's brute-force ceiling) rank exactly as a full sort. */
    @Test fun fiftyThousandVectorsSearchMatchesFullSort() = runBlocking {
        val rnd = java.util.Random(5)
        val vectors = ArrayList<Pair<String, FloatArray>>(50_000)
        val w = db.openHelper.writableDatabase
        w.beginTransaction()
        try {
            val mem = w.compileStatement(
                "INSERT INTO memory (id, frameId, text, type, tags, tier, confidence, reviewState, trusted, createdAt, derived, searchText) " +
                    "VALUES (?, NULL, 't', 'note', '', 'SHORT', 0.8, 'OK', 0, 1, 0, 't')",
            )
            val vec = w.compileStatement("INSERT INTO memory_vector (memoryId, spaceId, blob) VALUES (?, 'S', ?)")
            repeat(50_000) { i ->
                val id = "v${i.toString().padStart(5, '0')}"
                val v = FloatArray(64) { rnd.nextFloat() - 0.5f }
                vectors += id to v
                mem.clearBindings(); mem.bindString(1, id); mem.executeInsert()
                vec.clearBindings(); vec.bindString(1, id); vec.bindBlob(2, VectorCodec.encode(v)); vec.executeInsert()
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        val q = FloatArray(64) { rnd.nextFloat() - 0.5f }
        val reference = vectors.map { (id, v) -> Hit(id, VectorMath.cosine(q, v)) }.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.id }).take(10)
        assertEquals(reference, index.search("S", q, 10))
    }

    @Test fun cosineOfDifferentWidthsIsZeroNeverThrows() {
        assertEquals(0.0, VectorMath.cosine(floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f)), 0.0)
        assertEquals(0.0, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f, 0f)), 0.0)
    }
}
