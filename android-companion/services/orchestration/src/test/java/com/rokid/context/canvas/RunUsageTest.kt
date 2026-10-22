package com.rokid.context.canvas

import com.rokid.context.memory.db.CanvasRunItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunUsageTest {
    private fun row(frame: String, p: Int?, c: Int?, calls: Int?, latency: Long?, status: String = "OK") =
        CanvasRunItemEntity("r", frame, "n", status, null, null, null, latency, p, c, "gpt", null, "b", calls)

    @Test fun runUsageSumsOwnerRows() {
        val u = RunUsage.of(listOf(
            row("f1", 600, 30, 3, 900), row("f2", null, null, null, 900), row("f3", null, null, null, 900), // one three-photo call
            row("f4", 100, 10, 1, 100), row("f5", 50, 5, 1, 500, status = "ERROR"),
        ))
        assertEquals(5, u.calls)
        assertEquals(750, u.promptTokens); assertEquals(45, u.completionTokens)
        assertEquals(500L, u.medianLatencyMs) // medians of the owner rows only: 100, 500, 900
    }

    @Test fun emptyRunHasNoMedian() {
        val u = RunUsage.of(emptyList())
        assertEquals(0, u.calls); assertNull(u.medianLatencyMs)
    }
}
