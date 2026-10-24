package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.MomentForget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * The production caller of the builder (Moment building blocks part 2, task A): a frame commit, a recorded mark and a periodic cadence schedule builds that run OFF the calling thread,
 * are coalesced per second bucket and per burst, are idempotent, honour the store switch and never resurrect a forgotten second (the builder re-reads the fences in its transaction).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentBuildTriggerTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private var enabled = true
    private val builder = MomentBuilder(db, enabled = { enabled }, clock = { 5 })
    private val forget = MomentForget(db, ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }) { 1_000 }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val testThread = Thread.currentThread()
    private val buildThreads = Collections.synchronizedList(ArrayList<Thread>())
    private val batches = Collections.synchronizedList(ArrayList<List<String>>())
    private var windowCalls = 0
    private val trigger = MomentBuildTrigger(
        build = { ids -> buildThreads += Thread.currentThread(); batches += ids.toList(); builder.build(ids) },
        rebuild = { a, b -> windowCalls++; builder.rebuildWindow(a, b) },
        enabled = { enabled }, scope = scope, debounceMs = 50,
    )
    @After fun close() { scope.cancel(); db.close() }

    private fun id(i: Int) = MomentId.format(SEC + i)
    private suspend fun moments() = db.momentDao().allMoments().map { it.momentId }

    @Test fun aCommittedFrameProducesItsStoredMomentOffTheCallingThread() = runBlocking<Unit> {
        fx.frame("a", MS + 5)
        trigger.onFrameCommitted(MS + 5, MS + 5); trigger.flush()
        assertEquals(listOf(id(0)), moments()); assertEquals(listOf("a"), db.momentDao().parts(id(0)).map { it.refId })
        assertTrue(buildThreads.isNotEmpty()); buildThreads.forEach { assertNotEquals("no build on the calling (main) thread", testThread, it) }
    }

    @Test fun aBurstIsCoalescedPerSecondAndNeverRebuildsTheWholeWindow() = runBlocking<Unit> {
        repeat(30) { i -> fx.frame("f$i", MS + (i % 3) * 1000L + i); trigger.onFrameCommitted(MS + (i % 3) * 1000L + i, MS + (i % 3) * 1000L + i) }
        trigger.flush()
        assertEquals(setOf(id(0), id(1), id(2)), moments().toSet())
        assertEquals("one build for the burst", 1, trigger.builds); assertEquals(3, trigger.secondsBuilt)
        assertEquals("each second once", listOf(id(0), id(1), id(2)), batches.flatten().sorted()); assertEquals(0, windowCalls)
        assertEquals(10, db.momentDao().parts(id(0)).size)
    }

    @Test fun theSameFrameTriggeredAgainIsIdempotent() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("m", "a")
        trigger.onFrameCommitted(MS + 5, MS + 5); trigger.flush(); val first = fx.snapshot()
        trigger.onFrameCommitted(MS + 5, MS + 5); trigger.onFrameCommitted(MS + 5, MS + 5); trigger.flush()
        assertEquals(first, fx.snapshot()); assertEquals(1, db.objectDao().auditCount())
    }

    @Test fun aFrameWithUnknownCaptureTimeSchedulesNothingAndIsNeverAMoment() = runBlocking<Unit> {
        fx.frame("u", MS + 5, captured = false)
        trigger.onFrameCommitted(MS + 5, null); trigger.flush()
        assertEquals(0, trigger.builds); assertTrue(moments().isEmpty())
        // even if something did ask the builder for that second, the builder refuses it
        assertEquals(1, builder.build(listOf(id(0))).refused); assertTrue(moments().isEmpty())
    }

    @Test fun aMarkRecordedSchedulesItsSecondAndTheMarkAttaches() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.mark("L1", MS + 500)
        trigger.onMarkRecorded(MS + 500); trigger.flush()
        assertEquals(listOf("a" to "FRAME", "L1" to "MARK"), db.momentDao().parts(id(0)).map { it.refId to it.kind }.sortedBy { it.second })
    }

    @Test fun theSwitchOffWritesNothingNotEvenAnAuditRow() = runBlocking<Unit> {
        enabled = false; fx.frame("a", MS + 5)
        trigger.onFrameCommitted(MS + 5, MS + 5); trigger.onMarkRecorded(MS + 5); trigger.flush(); trigger.rebuildRecent(MS + 10)
        assertEquals(0, trigger.builds); assertTrue(moments().isEmpty()); assertEquals(0, db.objectDao().auditCount())
    }

    @Test fun cadenceRebuildsTheCurrentAndThePreviousHourOnly() = runBlocking<Unit> {
        val hour = 3_600_000L; val now = (MS / hour + 4) * hour + 10 * 60_000 // 10 minutes into an hour
        fx.frame("cur", now - 5 * 60_000); fx.frame("prev", now - 40 * 60_000) // previous hour
        fx.frame("old", now - 10 * 60_000 - hour - 5_000) // two hours back: outside the window
        val r = trigger.rebuildRecent(now)
        assertEquals(2, r.stored); assertEquals(1, windowCalls)
        assertEquals(setOf(MomentId.ofMs(now - 5 * 60_000), MomentId.ofMs(now - 40 * 60_000)), moments().toSet())
    }

    /** Forget racing the trigger, both orders: a forgotten second never keeps (or regains) a stored Moment. */
    @Test fun aForgetBeforeTheScheduledBuildRunsLeavesNothing() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("m1", "a"); fx.mark("L1", MS + 500)
        trigger.onFrameCommitted(MS + 5, MS + 5)      // scheduled, not yet drained (debounce)
        forget.forgetMoments(listOf(id(0)))           // Forget commits first
        trigger.flush()
        assertTrue(moments().isEmpty()); assertTrue(db.momentDao().parts(id(0)).isEmpty()); assertTrue(db.frameDao().byIds(listOf("a")).isEmpty())
    }

    @Test fun aForgetAfterTheBuildRemovesTheStoredMoment() = runBlocking<Unit> {
        fx.frame("a", MS + 5); trigger.onFrameCommitted(MS + 5, MS + 5); trigger.flush(); assertEquals(listOf(id(0)), moments())
        forget.forgetMoments(listOf(id(0)))
        assertTrue(moments().isEmpty()); assertEquals(0, db.objectDao().auditCount())
    }

    @Test fun aLateFrameAfterAForgetIsNotBuilt() = runBlocking<Unit> {
        fx.frame("a", MS + 5); forget.forgetMoments(listOf(id(0)))
        fx.frame("late", MS + 7) // slipped in after the Forget (defective admission): the fence still refuses the build
        trigger.onFrameCommitted(MS + 7, MS + 7); trigger.flush()
        assertTrue(moments().isEmpty())
    }

    @Test fun concurrentTriggerAndForgetEndWithoutAForgottenMemberWhateverTheInterleaving() = runBlocking<Unit> {
        repeat(15) { i ->
            fx.frame("c$i", MS + i * 1000L + 5); fx.memory("cm$i", "c$i")
            withContext(Dispatchers.Default) {
                listOf(async { trigger.onFrameCommitted(MS + i * 1000L + 5, MS + i * 1000L + 5); trigger.flush() }, async { forget.forgetMoments(listOf(id(i))) }, async { trigger.onFrameCommitted(MS + i * 1000L + 5, MS + i * 1000L + 5); trigger.flush() }).awaitAll()
            }
            trigger.flush()
            assertNull("second $i", db.momentDao().moment(id(i))); assertTrue(db.momentDao().parts(id(i)).isEmpty()); assertTrue(db.frameDao().byIds(listOf("c$i")).isEmpty())
        }
    }
}
