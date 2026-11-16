package com.rokid.context.work.frames.place

import com.rokid.context.ai.contract.PlaceMatchOutput
import com.rokid.context.memory.place.CellOutcome
import com.rokid.context.memory.place.PlaceCandidates
import com.rokid.context.memory.place.ZoneFence
import com.rokid.context.memory.place.ZoneTicket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val R4_LAT = 17.361944
private const val R4_LON = 78.474722
private val R4_PLACE = com.rokid.context.memory.place.PlaceEntity("wd:Q1", null, "Charminar", "monument", R4_LAT, R4_LON, 50, "Charminar is a monument.", "wikipedia:en", "page:1", 1_700_000_000_000L, false, false, 0, 100)

/**
 * Codex zones/Place Guide r4: ONE fence. The zone controller holds it (ONLY the fence is flipped here, never the sampler's own `zoneBlocked`, which stays false), at EVERY suspension point of
 * the attempt, and nothing that is a side effect (the fetch, the upload, the memory write, the card) may happen after it, and nothing durable may remain.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSamplerR4Test {
    private enum class Point { NEWEST, TOMBSTONE_1, TOMBSTONE_2, TOMBSTONE_3, TOMBSTONE_4, TOMBSTONE_5, FIX, CANDIDATES, MATCH, SAVE_BEFORE_VALIDATION, SAVE_AFTER_COMMIT }

    private class World(val point: Point?) {
        var now = 10_000_000L
        val fence = ZoneFence()
        val holder = PlaceCardHolder()
        val events = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val outcomes = mutableListOf<PlaceOutcome>()
        var committed = false
        var tombCalls = 0
        val ledger = InMemoryPlaceLedger()
        var flippedAt = -1
        fun flipIf(p: Point) { if (p == point) { flippedAt = events.size; fence.hold(); holder.clear() } }
        val store = object : PlaceFrameStore {
            override suspend fun newestDwell(sinceReceivedMs: Long): PlaceFrame { events += "newest"; delay(1); flipIf(Point.NEWEST); return PlaceFrame("f1", now - 5_000) }
            override suspend fun tombstoned(frameId: String): Boolean {
                events += "tomb"; delay(1)
                flipIf(Point.valueOf("TOMBSTONE_${++tombCalls}".let { if (Point.values().any { p -> p.name == it }) it else "TOMBSTONE_5" }))
                return false
            }
        }

        fun sampler(scope: TestScope) = PlaceSampler(
            scope = scope, store = store,
            fix = { events += "fix"; delay(1); flipIf(Point.FIX); PlaceFix(R4_LAT, R4_LON, 1_000, 10f) },
            candidates = { _, _, _ -> events += "fetch"; delay(1); flipIf(Point.CANDIDATES); PlaceCandidates(listOf(R4_PLACE), CellOutcome.FRESH) },
            match = { _, _ -> events += "upload"; delay(1); flipIf(Point.MATCH); PlaceMatchResult(PlaceMatchOutput("1", 0.9f), true) },
            saveMemory = { r ->
                events += "save"; delay(1); flipIf(Point.SAVE_BEFORE_VALIDATION)
                if (!r.allowed()) false else { committed = true; events += "commit"; delay(1); flipIf(Point.SAVE_AFTER_COMMIT); true }
            },
            onCard = { holder.publish(it) }, clock = { now }, enabled = { true }, zoneBlocked = { false }, networkUp = { true }, flight = PlaceFlight(),
            stats = { outcomes += it }, cards = holder, deleteMemory = { deleted += it; committed = false }, fence = fence, ledger = ledger,
        )
    }

    @Test fun nothingIsDispatchedOrLeftBehindWhenTheFenceClosesAtAnySuspensionPoint() {
        for (point in Point.values()) {
            val w = World(point)
            runTest { w.sampler(this).poke(); advanceUntilIdle() }
            // Nothing after the flip is a side effect: the events list ends where the flip happened (the call that was in flight at the flip is the last one).
            val after = w.events.drop(w.flippedAt.coerceAtLeast(0))
            assertTrue("$point: events after the fence closed: $after", after.none { it in setOf("fetch", "upload", "save", "commit") } || w.flippedAt < 0)
            assertNull("$point: a card is out", w.holder.card.value)
            assertFalse("$point: a photo-derived memory is still durable", w.committed)
            assertTrue("$point: a mark is left in the ledger", w.ledger.pending().isEmpty())
        }
    }

    @Test fun theUntouchedAttemptPublishesAndKeepsItsMemory() = runTest {
        val w = World(null); w.sampler(this).poke(); advanceUntilIdle()
        assertEquals(listOf(PlaceOutcome.MATCHED), w.outcomes); assertTrue(w.committed); assertTrue(w.holder.card.value != null); assertTrue(w.ledger.pending().isEmpty())
    }

    @Test fun theFenceClosingWhileTheMemoryIsBeingWrittenCancelsAndStillTakesTheMemoryBack() = runTest {
        val w = World(null)
        val s = PlaceSampler(
            scope = this, store = w.store, fix = { PlaceFix(R4_LAT, R4_LON, 1_000, 10f) },
            candidates = { _, _, _ -> PlaceCandidates(listOf(R4_PLACE), CellOutcome.FRESH) },
            match = { _, _ -> PlaceMatchResult(PlaceMatchOutput("1", 0.9f), true) },
            saveMemory = { w.committed = true; delay(10_000); true }, onCard = {}, clock = { w.now }, enabled = { true }, zoneBlocked = { false }, networkUp = { true },
            cards = w.holder, deleteMemory = { delay(5); w.deleted += it; w.committed = false }, fence = w.fence, ledger = w.ledger,
        )
        s.poke(); runCurrent(); advanceTimeBy(1_000)
        assertTrue(w.committed)
        w.fence.hold(); advanceUntilIdle() // the job is cancelled mid-write; the cleanup is NonCancellable
        assertEquals(listOf("f1"), w.deleted); assertFalse(w.committed); assertTrue(w.ledger.pending().isEmpty()); assertNull(w.holder.card.value)
    }

    @Test fun aRollbackThatFailsIsRetriedAndThenSweptFromTheDurableMark() = runTest {
        val w = World(Point.SAVE_AFTER_COMMIT)
        var failures = 3
        val s = PlaceSampler(
            scope = this, store = w.store, fix = { PlaceFix(R4_LAT, R4_LON, 1_000, 10f) },
            candidates = { _, _, _ -> PlaceCandidates(listOf(R4_PLACE), CellOutcome.FRESH) },
            match = { _, _ -> PlaceMatchResult(PlaceMatchOutput("1", 0.9f), true) },
            saveMemory = { r -> if (!r.allowed()) false else { w.committed = true; w.flipIf(Point.SAVE_AFTER_COMMIT); true } }, onCard = {}, clock = { w.now }, enabled = { true },
            zoneBlocked = { false }, networkUp = { true }, cards = w.holder,
            deleteMemory = { if (failures-- > 0) throw java.io.IOException("disk") else { w.deleted += it; w.committed = false } }, fence = w.fence, ledger = w.ledger,
        )
        s.poke(); advanceUntilIdle()
        assertTrue("every try failed: still durable, but marked", w.committed && w.ledger.pending() == setOf("f1"))
        s.sweep() // the next poke / the next process start
        assertFalse(w.committed); assertTrue(w.ledger.pending().isEmpty()); assertEquals(listOf("f1"), w.deleted)
    }

    @Test fun theLivenessCheckIsAskedAgainAfterTheInnerReadsSuspend() = runTest {
        val w = World(null)
        val inner = object : com.rokid.context.model.PhotoLiveness {
            override suspend fun gone(ids: Set<String>): com.rokid.context.model.GonePhotos { delay(5); w.fence.hold(); return com.rokid.context.model.GonePhotos(emptySet()) } // "live", but the zone came during the reads
            override suspend fun <T> holdUploadSlot(block: suspend () -> T): T = block()
            override suspend fun goneMemories(ids: Set<String>): Set<String> = emptySet()
            override suspend fun goneMarks(ids: Set<String>): Set<String> = emptySet()
        }
        val live = ZoneFencedLiveness(inner, { false }, w.fence.ticket())
        assertEquals(setOf("a"), live.gone(setOf("a")).ids)
    }

    @Test fun aTicketTakenWhileHeldIsAlreadyClosedAndAFreshOneOpensAfterRelease() {
        val f = ZoneFence(); f.hold()
        assertFalse(f.ticket().isOpen()); assertNull(f.act(f.ticket()) { 1 })
        f.release(); assertTrue(f.ticket().isOpen()); assertEquals(1, f.act(f.ticket()) { 1 })
    }

    @Test fun theSwitchWipeAndForgetTripEndAttemptsInFlightButDoNotHold() {
        val f = ZoneFence(); val old: ZoneTicket = f.ticket(); f.trip()
        assertFalse(old.isOpen()); assertTrue(f.ticket().isOpen()); assertFalse(f.held)
    }
}
