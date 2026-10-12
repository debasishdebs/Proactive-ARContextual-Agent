package com.rokid.context.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryVectorEntity
import com.rokid.context.memory.retrieval.BruteForceVectorIndex
import com.rokid.context.memory.retrieval.VectorCodec
import com.rokid.context.memory.retrieval.VectorMath
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    @After fun close() = db.close()

    @Test fun codecIsLittleEndianFloat32() {
        val bytes = VectorCodec.encode(floatArrayOf(1f, -2.5f))
        assertEquals(8, bytes.size); assertEquals(0x3F.toByte(), bytes[3]) // 1.0f = 0x3F800000, little-endian
        assertArrayEquals(floatArrayOf(1f, -2.5f), VectorCodec.decode(bytes), 0f)
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), VectorMath.l2(floatArrayOf(3f, 4f)), 1e-6f)
    }

    @Test fun ranksByCosineWithinOneSpaceOnly() = runBlocking {
        // memory_vector has no foreign key, so a literal space id is enough here
        db.embeddingDao().putVector(MemoryVectorEntity("near", "S", VectorCodec.encode(floatArrayOf(1f, 0.1f))))
        db.embeddingDao().putVector(MemoryVectorEntity("far", "S", VectorCodec.encode(floatArrayOf(-1f, 0f))))
        db.embeddingDao().putVector(MemoryVectorEntity("elsewhere", "OTHER", VectorCodec.encode(floatArrayOf(1f, 0f))))
        val hits = index.search("S", floatArrayOf(1f, 0f), 5)
        assertEquals(listOf("near", "far"), hits.map { it.id })
        assertTrue(hits[0].score > 0.99 && hits[1].score < -0.99)
        assertEquals(listOf("near"), index.search("S", floatArrayOf(1f, 0f), 1).map { it.id })
    }

    @Test fun zeroAndMismatchedVectorsNeverProduceNaN() = runBlocking {
        db.embeddingDao().putVector(MemoryVectorEntity("zero", "S", VectorCodec.encode(floatArrayOf(0f, 0f))))
        db.embeddingDao().putVector(MemoryVectorEntity("wrongDim", "S", VectorCodec.encode(floatArrayOf(1f, 0f, 0f))))
        db.embeddingDao().putVector(MemoryVectorEntity("ok", "S", VectorCodec.encode(floatArrayOf(0f, 1f))))
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
        db.embeddingDao().putVector(MemoryVectorEntity("broken", "S", ByteArray(7)))
        db.embeddingDao().putVector(MemoryVectorEntity("ok", "S", VectorCodec.encode(floatArrayOf(1f, 0f))))
        assertEquals(listOf("ok"), index.search("S", floatArrayOf(1f, 0f), 5).map { it.id })
    }

    @Test fun nonFiniteVectorsScoreZeroNotNaN() = runBlocking {
        assertEquals(0.0, VectorMath.cosine(floatArrayOf(Float.POSITIVE_INFINITY, 0f), floatArrayOf(1f, 0f)), 0.0)
        assertEquals(0.0, VectorMath.cosine(floatArrayOf(Float.NaN, 1f), floatArrayOf(1f, 0f)), 0.0)
        db.embeddingDao().putVector(MemoryVectorEntity("inf", "S", VectorCodec.encode(floatArrayOf(Float.POSITIVE_INFINITY, 0f))))
        val hits = index.search("S", floatArrayOf(1f, 0f), 5)
        assertTrue(hits.none { it.score.isNaN() })
        assertTrue(index.search("S", floatArrayOf(Float.POSITIVE_INFINITY, 0f), 5).isEmpty())
    }
}
