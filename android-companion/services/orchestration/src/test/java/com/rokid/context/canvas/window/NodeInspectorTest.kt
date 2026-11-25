package com.rokid.context.canvas.window

import com.rokid.context.memory.db.CanvasRunItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeInspectorTest {
    private fun it(frame: String, node: String, status: String = "OK", branch: String? = null, out: String? = "{}", lat: Long? = null) =
        CanvasRunItemEntity("r", frame, node, status, branch, out, if (status == "OK") null else "boom", lat, null, null, null, null, null)

    private val items = listOf(
        it("f1", "a", lat = 10), it("f2", "a", lat = 30), it("f3", "a", "ERROR"),
        it("f1", "b", branch = "pass", out = "{\"x\":1}"), it("f2", "b", branch = "fail"),
    )

    @Test fun badgesCountOkErrorSkippedBranches() {
        val b = NodeInspector.badges(items, listOf("a", "b"), 3).associateBy { it.nodeId }
        assertEquals(2, b.getValue("a").ok); assertEquals(1, b.getValue("a").error); assertEquals(0, b.getValue("a").skipped)
        assertEquals(20L, b.getValue("a").medianLatencyMs)
        assertEquals(1, b.getValue("b").skipped)
        assertEquals(mapOf("fail" to 1, "pass" to 1), b.getValue("b").branches)
        assertNull(b.getValue("b").medianLatencyMs)
    }

    @Test fun pageCarriesUpstreamInputAndPages() {
        val p = NodeInspector.page(items, "b", "a", null, 1)
        assertEquals("f1", p.rows[0].frameId); assertEquals("{}", p.rows[0].input); assertEquals("f1", p.next)
        val q = NodeInspector.page(items, "b", "a", p.next, 10)
        assertEquals(listOf("f2"), q.rows.map { it.frameId }); assertNull(q.next)
    }

    @Test fun outputIsCappedOnACharacterBoundary() {
        val big = "é".repeat(3000)
        val (cut, flag) = NodeInspector.cap(big)
        assertTrue(flag)
        assertTrue(cut!!.toByteArray(Charsets.UTF_8).size <= NodeInspector.OUTPUT_CAP_BYTES)
        assertTrue(cut.all { c -> c == 'é' })
        assertFalse(NodeInspector.cap("short").second)
        assertNull(NodeInspector.cap(null).first)
    }

    @Test fun pageSizeIsBounded() {
        val many = (0 until 500).map { i -> it("f%04d".format(i), "n") }
        assertEquals(NodeInspector.MAX_PAGE, NodeInspector.page(many, "n", null, null, 10_000).rows.size)
        assertEquals(1, NodeInspector.page(many, "n", null, null, 0).rows.size)
    }
}
