package com.rokid.context.work.frames.intent

import com.rokid.context.canvas.FrameIntentSink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The canvas intent sink goes through the sampler's store and fences: unknown labels, tombstones, the row count and a wipe. */
class FrameIntentWriterTest {
    private class Store(var rows: Int = 1) : IntentFrameStore {
        val writes = mutableListOf<List<Any>>()
        override suspend fun newestUnlabelled(sinceReceivedMs: Long): IntentFrame? = null
        override suspend fun setIntent(frameId: String, label: String, conf: Float, at: Long): Int = error("the canvas must never use the unconditional write")
        override suspend fun setIntentIfUnlabelled(frameId: String, label: String, conf: Float, at: Long): Int { writes += listOf(frameId, label, conf, at); return rows }
        override suspend fun tombstoned(frameId: String): Boolean = false
    }

    @Test fun aKnownLabelIsWrittenThroughTheConditionalUpdate() = runBlocking<Unit> {
        val s = Store(); val w = FrameIntentWriter(s)
        w.record("f1", "DRIVING", 0.8f, 42)
        assertEquals(listOf<List<Any>>(listOf("f1", "DRIVING", 0.8f, 42L)), s.writes); assertEquals(1, w.lastRows)
    }

    @Test fun nothingChangedIsReportedAsZeroRows() = runBlocking<Unit> {
        val s = Store(rows = 0); val w = FrameIntentWriter(s)
        w.record("gone", "OTHER", null, 1)
        assertEquals(0, w.lastRows); assertEquals(0f, s.writes.single()[2] as Float)
    }

    @Test fun anUnknownLabelIsNeverWritten() = runBlocking<Unit> {
        val s = Store(); FrameIntentWriter(s).record("f1", "FLYING_CARPET", 0.9f, 1)
        assertTrue(s.writes.isEmpty())
    }

    @Test fun confidenceIsClampedAndFailuresDoNotThrow() = runBlocking<Unit> {
        val s = Store(); FrameIntentWriter(s).record("f1", "WALKING", 7f, 1)
        assertEquals(1f, s.writes.single()[2] as Float)
        val boom = object : IntentFrameStore by Store() { override suspend fun setIntentIfUnlabelled(frameId: String, label: String, conf: Float, at: Long): Int = throw IllegalStateException("db") }
        FrameIntentWriter(boom).record("f1", "WALKING", 0.5f, 1) // must not throw
    }

    @Test fun theSinkInterfaceIsTheWriter() { val sink: FrameIntentSink = FrameIntentWriter(Store()); assertTrue(sink !== FrameIntentSink.None) }
}
