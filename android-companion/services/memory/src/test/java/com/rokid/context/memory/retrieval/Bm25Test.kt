package com.rokid.context.memory.retrieval

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Bm25Test {
    @Test fun oneTermOneColumnMatchesTheFormula() {
        // p=1 phrase, c=1 column, n=10 rows, avg 5 tokens, this row 5 tokens, x = (hits here 2, hits all 9, docs with hits 3)
        val mi = intArrayOf(1, 1, 10, 5, 5, 2, 9, 3)
        assertEquals(1.574556, Bm25.score(mi), 1e-5)
    }

    @Test fun termsWithoutHitsInThisRowAddNothingAndLongerRowsScoreLower() {
        val short = intArrayOf(2, 1, 10, 5, 3, 1, 4, 4, 0, 2, 2)
        val long = intArrayOf(2, 1, 10, 5, 20, 1, 4, 4, 0, 2, 2)
        assertEquals(true, Bm25.score(short) > Bm25.score(long))
        assertEquals(Bm25.score(intArrayOf(1, 1, 10, 5, 3, 1, 4, 4)), Bm25.score(short), 1e-12)
    }

    @Test fun blobIsNativeOrderUint32s() {
        val bb = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putInt(7).putInt(9)
        assertArrayEquals(intArrayOf(7, 9), Bm25.ints(bb.array()))
    }
}
