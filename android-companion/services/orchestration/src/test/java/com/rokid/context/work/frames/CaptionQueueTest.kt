package com.rokid.context.work.frames

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.model.CallClass
import com.rokid.context.model.CallGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Wave 2 Task 6 (Ruling 9): the caption queue is the lowest priority; aging is done in the pick order. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CaptionQueueTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db
    private var now = 1_793_000_000_000L
    private val calls = mutableListOf<Pair<List<String>, CallClass>>()
    private val gated = mutableListOf<CallClass>()

    /** A fake gate: records the class of every call that asks for a permit (a NUDGE would be the one allowed to take the reserved token). */
    private val gate = object : CallGate {
        override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T { gated += callClass; return block() }
    }

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun seed(id: String, ts: Long, state: String = "PENDING", waitedMs: Long = 0L, dwell: Boolean = false) {
        db.frameDao().insertIgnore(FrameEntity(id, ts, "/x/$id.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", dwell, null, null, null, null, 0.7f, null, null, null, null, ts, routedAt = ts))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, state, 0, null, null, null, now - waitedMs)))
    }

    private fun pipeline() = KeyframePipeline(db, { ids, claim, cls ->
        calls += ids to cls
        gate.withPermit(cls) { ids.forEach { check(db.frameStateDao().completeIfClaimed(it, claim, "EXTRACTED", now) == 1) } }
        ExtractOutcome.Saved(emptyList())
    }, { now }, null, newClaimId = { "c-${calls.size}" }, pollMs = 50)

    @Test fun lowestPriorityByDefault() = runBlocking {
        seed("q1", now - 5_000, "CAPTION_QUEUED", waitedMs = 60_000); seed("p1", now - 1_000)
        val p = pipeline()
        assertEquals(1, p.drainOnce()); assertEquals(listOf("p1") to CallClass.EXTRACT, calls[0])
        assertEquals(1, p.drainOnce()); assertEquals(listOf("q1") to CallClass.CAPTION_QUEUE, calls[1])
        assertEquals("EXTRACTED", db.frameStateDao().get("q1")!!.state)
    }

    @Test fun agesAheadOfNonDwellAfterTenMinutes() = runBlocking {
        seed("q1", now - 700_000, "CAPTION_QUEUED", waitedMs = 11 * 60_000L); seed("p1", now - 1_000)
        seed("d1", now - 2_000, dwell = true)
        val p = pipeline()
        p.drainOnce(); assertEquals("dwell is still first", listOf("d1", "p1"), calls[0].first)
        seed("p2", now - 500)
        p.drainOnce(); assertEquals(listOf("q1") to CallClass.CAPTION_QUEUE, calls[1])
        p.drainOnce(); assertEquals(listOf("p2"), calls[2].first)
    }

    @Test fun becomesExtractAfterThirtyMinutes() = runBlocking {
        seed("q1", now - 3_000_000, "CAPTION_QUEUED", waitedMs = 29 * 60_000L)
        pipeline().drainOnce(); assertEquals(CallClass.CAPTION_QUEUE, calls[0].second)
        seed("q2", now - 3_000_000, "CAPTION_QUEUED", waitedMs = 31 * 60_000L)
        pipeline().drainOnce(); assertEquals(listOf("q2") to CallClass.EXTRACT, calls[1])
    }

    @Test fun neverTakesNudgeToken() = runBlocking {
        seed("q1", now - 3_000_000, "CAPTION_QUEUED", waitedMs = 40 * 60_000L); seed("q2", now - 3_000, "CAPTION_QUEUED", waitedMs = 12 * 60_000L)
        seed("q3", now - 2_000, "CAPTION_QUEUED", waitedMs = 1_000L)
        val p = pipeline()
        repeat(3) { p.drainOnce() }
        assertTrue(gated.isNotEmpty())
        assertTrue("only ordinary classes, never NUDGE: $gated", gated.all { it == CallClass.CAPTION_QUEUE || it == CallClass.EXTRACT })
    }

    @Test fun atMostThreePerCall() = runBlocking {
        for (i in 1..5) seed("q$i", now - i * 1000L, "CAPTION_QUEUED", waitedMs = 60_000L + i)
        val p = pipeline()
        assertEquals(3, p.drainOnce()); assertEquals(2, p.drainOnce())
        assertEquals(listOf(3, 2), calls.map { it.first.size })
    }
}
