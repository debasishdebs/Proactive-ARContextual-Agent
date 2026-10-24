package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.db.CleanupTarget
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.forget.MomentForgetFixture.Companion.MS
import com.rokid.context.memory.forget.MomentForgetFixture.Companion.SEC
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Moment Forget: independent source discovery, fences, one-transaction cascade, tombstones, the durable outbox. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentForgetTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentForgetFixture(db)
    private val s3 = db.canvasSlice3Dao()
    @After fun close() = db.close()

    private suspend fun seedSecond() {
        fx.frame("a", MS + 5, wire = "g1_a"); fx.frame("b", MS + 900, wire = "g1_b"); fx.memory("m1", "a"); fx.mark("L1", MS + 500)
        // neighbours that must survive
        fx.frame("n1", MS - 1); fx.frame("n2", MS + 1000); fx.memory("m2", "n1"); fx.mark("L2", MS + 30_000)
    }

    // --- independent discovery ---
    @Test fun forgetWithEmptyProjectionStillFindsEverythingInTheAuthoritativeStores() = runBlocking<Unit> {
        seedSecond(); fx.memory("o1", null, createdAt = MS + 300); fx.ask("k1", "a")
        assertEquals(0, db.momentDao().allMoments().size)
        fx.forget.forgetMoments(listOf(fx.id))
        assertEquals(listOf("n1", "n2"), fx.frameIds())
        assertEquals(listOf("m2"), fx.memoryIds())
        assertEquals(listOf("L2"), fx.markIds())
        assertTrue("the ask citing a forgotten photo is redacted", db.askDao().byId("k1")!!.redacted)
    }

    @Test fun forgetWithStaleProjectionUnionsWhatTheProjectionNamesWithWhatTheStoresHold() = runBlocking<Unit> {
        seedSecond()
        // The projection is stale in both directions: it misses frame "b" and memory "m1", and names frame "x1", memory "x2" and mark "Lx" that now live far away in time.
        fx.frame("x1", MS + 50_000); fx.memory("x2", "x1"); fx.mark("Lx", MS + 60_000)
        fx.project(fx.id, MomentPartKind.FRAME to "a", MomentPartKind.FRAME to "x1", MomentPartKind.MEMORY to "x2", MomentPartKind.MARK to "Lx")
        fx.forget.forgetMoments(listOf(fx.id))
        assertEquals("both the scanned (b) and the projected (x1) photo go", listOf("n1", "n2"), fx.frameIds())
        assertEquals(listOf("m2"), fx.memoryIds())
        assertEquals("a projected mark outside the second goes too", listOf("L2"), fx.markIds())
        assertTrue(db.momentDao().allMoments().isEmpty())
    }

    @Test fun anExpiredPhotoStillKnownByItsCaptureTimeIsFound() = runBlocking<Unit> {
        fx.frame("e", MS + 5, capturedAt = MS + 5); fx.memory("m1", "e")
        db.frameDao().deleteByIds(listOf("e")) // retention removed the frame row; frame_meta keeps the capture time
        fx.forget.forgetMoments(listOf(fx.id))
        assertTrue(fx.memoryIds().isEmpty())
        assertTrue("its tombstone is written", db.forgetDao().tombstoned(listOf("e")).contains("e"))
    }

    @Test fun aDerivedSummaryOfAForgottenMemoryIsInvalidatedNotKept() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("m1", "a"); fx.memory("s1", null, derived = true); fx.derivedFrom("s1", "m1")
        fx.forget.forgetMoments(listOf(fx.id))
        assertTrue("the summary is hidden", fx.memoryIds().isEmpty())
        assertEquals(com.rokid.context.memory.db.ReviewState.INVALIDATED, db.forgetDao().memories(listOf("s1")).single().reviewState)
    }

    // --- tombstones and fences ---
    @Test fun aTombstoneIsWrittenEvenWhenNoMomentExists() = runBlocking<Unit> {
        fx.forget.forgetMoments(listOf(fx.id))
        assertEquals(listOf(fx.id), db.momentDao().tombstonedAmong(listOf(fx.id)))
        assertTrue("and the durable range fence covers the whole second", s3.momentFenced(MS, MS + 999))
        assertFalse(s3.momentFenced(MS - 1, MS - 1)); assertFalse(s3.momentFenced(MS + 1000, MS + 1000))
    }

    @Test fun malformedIdsAreIgnoredAndWriteNothing() = runBlocking<Unit> {
        fx.frame("a", MS + 5)
        val r = fx.forget.forgetMoments(listOf("zz", "1234567", "ABCDEF01"))
        assertEquals(0, r.seconds); assertEquals(listOf("a"), fx.frameIds()); assertEquals(0, db.momentDao().tombstoneCount())
    }

    @Test fun rangeForgetRoundsOutwardToWholeSeconds() = runBlocking<Unit> {
        fx.frame("in1", MS + 0); fx.frame("in2", MS + 2999); fx.frame("lo", MS - 1); fx.frame("hi", MS + 3000)
        fx.forget.forgetRange(MS + 400, MS + 2100)
        assertEquals(listOf("hi", "lo"), fx.frameIds())
        assertEquals(MS..(MS + 2999), s3.momentForgetRanges().single())
        assertEquals((0..2).map { MomentId.format(SEC + it) }.sorted(), db.momentDao().tombstonedAmong((0..3).map { MomentId.format(SEC + it) }).sorted())
    }

    // --- cascade per part kind ---
    @Test fun everyPartKindOfAMomentIsCascaded() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("m1", "a"); fx.memory("s1", "a", derived = true); fx.mark("L1", MS + 500); fx.ask("k1", "a")
        fx.project(fx.id, MomentPartKind.FRAME to "a", MomentPartKind.MEMORY to "m1", MomentPartKind.SUMMARY to "s1", MomentPartKind.MARK to "L1", MomentPartKind.CITE to "frame:a")
        fx.forget.forgetMoments(listOf(fx.id))
        assertTrue("FRAME", fx.frameIds().isEmpty()); assertTrue("MEMORY and SUMMARY", fx.memoryIds().isEmpty()); assertTrue("MARK", fx.markIds().isEmpty())
        assertTrue("CITE", db.askDao().byId("k1")!!.redacted); assertTrue("projection", db.momentDao().allMoments().isEmpty() && db.momentDao().parts(fx.id).isEmpty())
    }

    @Test fun aProjectedMemoryOfAnotherSecondIsStillForgottenWhenTheProjectionNamesIt() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("far", MS + 90_000); fx.memory("m1", "far")
        fx.project(fx.id, MomentPartKind.FRAME to "a", MomentPartKind.MEMORY to "m1")
        fx.forget.forgetMoments(listOf(fx.id))
        assertTrue(fx.memoryIds().isEmpty())
    }

    /** A part kind that names its source ALONE (the source lives far outside the second, so only the projection can lead Forget to it). */
    private suspend fun lonely(kind: String, ref: String) {
        fx.frame("a", MS + 5); fx.frame("x1", MS + 90_000); fx.memory("x2", "x1"); fx.memory("s9", null, derived = true, createdAt = MS + 90_000); fx.mark("Lx", MS + 95_000); fx.ask("kx", "x1")
        fx.project(fx.id, MomentPartKind.FRAME to "a", kind to ref)
        fx.forget.forgetMoments(listOf(fx.id))
    }
    @Test fun aFramePartAloneIsCascaded() = runBlocking<Unit> { lonely(MomentPartKind.FRAME, "x1"); assertEquals(emptyList<String>(), fx.frameIds()) }
    @Test fun aMemoryPartAloneIsCascaded() = runBlocking<Unit> { lonely(MomentPartKind.MEMORY, "x2"); assertFalse("x2" in fx.memoryIds()) }
    @Test fun aSummaryPartAloneIsCascaded() = runBlocking<Unit> { lonely(MomentPartKind.SUMMARY, "s9"); assertTrue(db.forgetDao().memories(listOf("s9")).isEmpty()) }
    @Test fun aMarkPartAloneIsCascaded() = runBlocking<Unit> { lonely(MomentPartKind.MARK, "Lx"); assertFalse("Lx" in fx.markIds()) }
    @Test fun aCitePartAloneNamesItsPhoto() = runBlocking<Unit> { lonely(MomentPartKind.CITE, "frame:x1"); assertTrue(db.askDao().byId("kx")!!.redacted); assertFalse("x1" in fx.frameIds()) }

    // --- idempotence and atomicity ---
    @Test fun theCascadeIsIdempotent() = runBlocking<Unit> {
        seedSecond(); fx.project(fx.id, MomentPartKind.FRAME to "a")
        fx.forget.forgetMoments(listOf(fx.id))
        val state = listOf(fx.frameIds(), fx.memoryIds(), fx.markIds(), fx.outbox(), db.momentDao().tombstoneCount(), s3.momentForgetRanges())
        val again = fx.forget.forgetMoments(listOf(fx.id))
        assertEquals(state, listOf(fx.frameIds(), fx.memoryIds(), fx.markIds(), fx.outbox(), db.momentDao().tombstoneCount(), s3.momentForgetRanges()))
        assertEquals("nothing new to clean up", 0, again.outboxRows)
    }

    @Test fun aFailureAnywhereRollsBackTheWholeCascadeIncludingTheOutbox() = runBlocking<Unit> {
        seedSecond(); fx.project(fx.id, MomentPartKind.FRAME to "a")
        fx.forget.testFaultBeforeCommit = { throw IllegalStateException("boom") }
        runCatching { fx.forget.forgetMoments(listOf(fx.id)) }
        fx.forget.testFaultBeforeCommit = null
        assertEquals(listOf("a", "b", "n1", "n2"), fx.frameIds()); assertEquals(listOf("L1", "L2"), fx.markIds()); assertEquals(listOf("m1", "m2"), fx.memoryIds())
        assertTrue(fx.outbox().isEmpty()); assertEquals(0, db.momentDao().tombstoneCount()); assertTrue(s3.momentForgetRanges().isEmpty()); assertEquals(1, db.momentDao().allMoments().size)
    }

    // --- durable outbox, inserted in the same transaction ---
    @Test fun theOutboxHoldsPhoneFilesGlassesFramesAndTheGlassesRange() = runBlocking<Unit> {
        seedSecond()
        fx.forget.forgetMoments(listOf(fx.id))
        val box = fx.outbox()
        assertTrue(box.contains(CleanupTarget.PHONE_FILE to "{\"path\":\"/p/a.jpg\"}")); assertTrue(box.contains(CleanupTarget.PHONE_FILE to "{\"path\":\"/p/a.meta.json\"}"))
        assertTrue(box.contains(CleanupTarget.GLASSES_FRAME to "{\"wireFrameId\":\"g1_a\"}")); assertTrue(box.contains(CleanupTarget.GLASSES_FRAME to "{\"wireFrameId\":\"g1_b\"}"))
        assertTrue(box.contains(CleanupTarget.GLASSES_RANGE to "{\"fromMs\":$MS,\"toMs\":${MS + 999}}"))
        assertFalse("a surviving neighbour is not queued", box.any { it.second.contains("n1") || it.second.contains("n2") })
    }

    @Test fun cleanupOutboxRecoveryAfterARestartBeforeAnyAck() = runBlocking<Unit> {
        seedSecond()
        fx.forget.forgetMoments(listOf(fx.id))
        // "restart": nothing was acked, every pending cleanup is still stored and readable.
        val pending = db.momentDao().pendingOutbox()
        assertTrue(pending.size >= 5)
        val first = pending.first()
        assertEquals(1, db.momentDao().ack(first.id, 9)); assertEquals(0, db.momentDao().ack(first.id, 10))
        assertEquals(pending.size - 1, db.momentDao().pendingOutbox().size)
        fx.forget.forgetMoments(listOf(fx.id)) // a repeat does not re-queue the acked work
        assertEquals(pending.size - 1, db.momentDao().pendingOutbox().size)
    }

    @Test fun tracesAreQueuedForTheGlasses() = runBlocking<Unit> {
        val e = com.rokid.context.memory.db.DeletionEpoch("i", 1, 0); val f = com.rokid.context.memory.db.DaoFence(0, e, { 0 }, { e })
        s3.acceptTraceFenced(com.rokid.context.memory.db.CanvasTraceEntity("trace-aaa-01", "HUB", "COMPLETE", "{}", 0, installId = "i", deletionCounter = 1, startedAtMs = MS, endedAtMs = MS + 500, createdAt = 1), f)
        fx.forget.forgetMoments(listOf(fx.id))
        assertTrue(fx.outbox().contains(CleanupTarget.GLASSES_TRACE to "{\"traceId\":\"trace-aaa-01\"}"))
    }
}
