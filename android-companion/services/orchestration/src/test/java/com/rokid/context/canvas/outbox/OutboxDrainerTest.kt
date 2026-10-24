package com.rokid.context.canvas.outbox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drainer is pure logic over ports: store, phone files, glasses and helper. */
class OutboxDrainerTest {
    private var now = 1_000_000L
    private val glasses = FakeGlasses()
    private val helper = FakeHelper()
    private val log = RecordingLog()

    private fun drainer(store: FakeStore, files: FakeFiles, max: Int = 50) = OutboxDrainer(store, files, glasses, helper, clock = { now }, log = log, maxRowsPerRun = max)

    @Test fun aForgottenPhotosJpegIsGoneAfterADrainAndItsRowIsRemoved() = runBlocking<Unit> {
        val files = FakeFiles(listOf("/f/a.jpg", "/f/a.meta.json", "/f/keep.jpg"))
        val store = FakeStore(listOf(phoneRow(1, "/f/a.jpg"), phoneRow(2, "/f/a.meta.json")))
        val r = drainer(store, files).drain()
        assertEquals(setOf("/f/keep.jpg"), files.present); assertTrue(store.rows.isEmpty()); assertEquals(2, r.removed)
    }

    @Test fun aRowForAnAlreadyMissingFileCountsAsDone() = runBlocking<Unit> {
        val files = FakeFiles(); val store = FakeStore(listOf(phoneRow(1, "/f/never.jpg")))
        val r = drainer(store, files).drain()
        assertTrue(store.rows.isEmpty()); assertEquals(1, r.removed); assertEquals(0, r.failed)
    }

    @Test fun aFailedDeletionKeepsTheRowAndCountsTheAttemptWithoutLoggingThePath() = runBlocking<Unit> {
        val files = FakeFiles(listOf("/secret/photo-of-mum.jpg")).also { it.failing += "/secret/photo-of-mum.jpg" }
        val store = FakeStore(listOf(phoneRow(1, "/secret/photo-of-mum.jpg")))
        val r = drainer(store, files).drain()
        assertEquals(1, store.rows.size); assertEquals(1, r.failed); assertEquals(1, store.attempts[1]!!.count); assertEquals(now, store.attempts[1]!!.lastAtMs)
        assertTrue(log.lines.isNotEmpty()); log.lines.forEach { assertFalse(it, it.contains("mum") || it.contains("secret") || it.contains("/")) }
    }

    @Test fun aCrashBetweenTheDeleteAndTheRowRemovalRerunsSafely() = runBlocking<Unit> {
        val files = FakeFiles(listOf("/f/a.jpg")); val store = FakeStore(listOf(phoneRow(1, "/f/a.jpg"))).also { it.failRemoveOnce = true }
        val d = drainer(store, files)
        val first = d.drain()
        assertTrue("the file is gone but the row survived the crash", files.present.isEmpty() && store.rows.size == 1); assertEquals(1, first.failed)
        now += OutboxDrainer.backoffMs(1)
        val second = d.drain()
        assertTrue(store.rows.isEmpty()); assertEquals(1, second.removed); assertTrue(store.attempts.isEmpty())
    }

    @Test fun retryBacksOffExponentiallyUpToACapAndIsBoundedPerRun() {
        assertEquals(OutboxDrainer.BASE_BACKOFF_MS, OutboxDrainer.backoffMs(1))
        assertEquals(OutboxDrainer.BASE_BACKOFF_MS * 2, OutboxDrainer.backoffMs(2))
        assertEquals(OutboxDrainer.BASE_BACKOFF_MS * 4, OutboxDrainer.backoffMs(3))
        assertEquals(OutboxDrainer.MAX_BACKOFF_MS, OutboxDrainer.backoffMs(200))
        assertTrue(OutboxDrainer.backoffMs(40) >= OutboxDrainer.backoffMs(39))
    }

    @Test fun aFailedRowIsNotRetriedBeforeItsBackoffAndIsRetriedAfter() = runBlocking<Unit> {
        val files = FakeFiles(listOf("/f/a.jpg")).also { it.failing += "/f/a.jpg" }; val store = FakeStore(listOf(phoneRow(1, "/f/a.jpg")))
        val d = drainer(store, files)
        d.drain(); assertEquals(1, files.calls.size)
        now += OutboxDrainer.backoffMs(1) - 1
        val early = d.drain(); assertEquals(1, files.calls.size); assertEquals(1, early.waiting)
        now += 1; files.failing.clear()
        d.drain(); assertEquals(2, files.calls.size); assertTrue(store.rows.isEmpty())
    }

    @Test fun atMostNRowsAreAttemptedPerRunAndTheRestWaitForTheNextRun() = runBlocking<Unit> {
        val paths = (1..7).map { "/f/$it.jpg" }; val files = FakeFiles(paths); val store = FakeStore(paths.mapIndexed { i, p -> phoneRow(i + 1L, p) })
        val d = drainer(store, files, max = 3)
        assertEquals(3, d.drain().removed); assertEquals(4, store.rows.size)
        assertEquals(3, d.drain().removed); assertEquals(1, store.rows.size)
        assertEquals(1, d.drain().removed); assertTrue(store.rows.isEmpty())
    }

    @Test fun phoneRowsGoBeforeGlassesRowsWhateverTheirIds() = runBlocking<Unit> {
        val files = FakeFiles(listOf("/f/a.jpg")); val store = FakeStore(listOf(frameRow(1, "w1"), phoneRow(2, "/f/a.jpg")))
        drainer(store, files, max = 1).drain()
        assertTrue("the single slot went to the phone file", files.present.isEmpty()); assertTrue(glasses.calls.isEmpty()); assertEquals(listOf(1L), store.rows.map { it.id })
    }

    @Test fun glassesAndHelperTargetsKeepTheirRowsWhileNotReachableAndChargeNoAttempt() = runBlocking<Unit> {
        glasses.reachable = false; helper.reachable = false
        val files = FakeFiles(listOf("/f/a.jpg"))
        val store = FakeStore(listOf(frameRow(1, "w1"), traceRow(2, "t1"), rangeRow(3, 1, 2), allRow(4), phoneRow(5, "/f/a.jpg")))
        val r = drainer(store, files).drain()
        assertEquals(listOf(1L, 2L, 3L, 4L), store.rows.map { it.id }); assertEquals(4, r.deferred); assertEquals(1, r.removed)
        assertTrue(glasses.calls.isEmpty()); assertEquals(0, helper.calls); assertTrue("no attempt is charged while unreachable", store.attempts.isEmpty())
        assertEquals("one probe per port per run", 1, glasses.probes)
    }

    @Test fun glassesRowsAreRemovedOnceTheGlassesConfirm() = runBlocking<Unit> {
        val store = FakeStore(listOf(frameRow(1, "w1"), traceRow(2, "t1"), rangeRow(3, 1, 2)))
        val r = drainer(store, FakeFiles()).drain()
        assertEquals(listOf("frame", "trace", "range"), glasses.calls); assertTrue(store.rows.isEmpty()); assertEquals(3, r.removed)
    }

    @Test fun theLinkDroppingMidRunDefersTheRestWithoutAnAttempt() = runBlocking<Unit> {
        glasses.next = CleanupResult.NOT_REACHABLE
        val store = FakeStore(listOf(frameRow(1, "w1"), frameRow(2, "w2")))
        val r = drainer(store, FakeFiles()).drain()
        assertEquals(1, glasses.calls.size); assertEquals(2, store.rows.size); assertEquals(2, r.deferred); assertTrue(store.attempts.isEmpty())
    }

    @Test fun deleteAllRowNeedsBothGlassesAndHelperAndSurvivesUntilBothConfirm() = runBlocking<Unit> {
        helper.reachable = false
        val store = FakeStore(listOf(allRow(1)))
        val d = drainer(store, FakeFiles())
        d.drain(); assertEquals("the reachable half ran", listOf("wipe"), glasses.calls); assertEquals(1, store.rows.size)
        helper.reachable = true; d.drain(); assertEquals(1, helper.calls); assertTrue(store.rows.isEmpty())
    }

    @Test fun aMalformedOrUnknownRowIsNeverActedOnKeptAndCounted() = runBlocking<Unit> {
        val files = FakeFiles(listOf("/f/a.jpg"))
        val store = FakeStore(listOf(OutboxRow(1, "PHONE_FILE", "{\"path\":"), OutboxRow(2, "NEW_KIND", "{}"), OutboxRow(3, "GLASSES_RANGE", "{\"fromMs\":9,\"toMs\":1}")))
        val r = drainer(store, files).drain()
        assertEquals(3, store.rows.size); assertEquals(3, r.malformed); assertTrue(files.calls.isEmpty() && glasses.calls.isEmpty())
    }

    @Test fun anExceptionFromAPortIsAFailedAttemptNotACrashOfTheRun() = runBlocking<Unit> {
        val boom = object : PhoneFilePort { override suspend fun deleteConfirmed(path: String): CleanupResult = throw java.io.IOException("/x/y") }
        val store = FakeStore(listOf(phoneRow(1, "/f/a.jpg"), phoneRow(2, "/f/b.jpg")))
        val r = OutboxDrainer(store, boom, glasses, helper, clock = { now }, log = log).drain()
        assertEquals(2, r.failed); assertEquals(2, store.rows.size); log.lines.forEach { assertFalse(it, it.contains("/x/y")) }
    }

    @Test fun attemptsOfRowsThatNoLongerExistArePruned() = runBlocking<Unit> {
        val store = FakeStore(listOf(phoneRow(2, "/f/a.jpg"))).also { it.attempts[99] = OutboxAttempt(4, 1) }
        drainer(store, FakeFiles()).drain()
        assertNull(store.attempts[99])
    }

    @Test fun anEmptyOutboxIsANoOpAndNeverProbesThePorts() = runBlocking<Unit> {
        val r = drainer(FakeStore(), FakeFiles()).drain()
        assertEquals(OutboxDrainer.Report.NONE, r); assertEquals(0, glasses.probes)
    }
}
