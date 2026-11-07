package com.rokid.context.orchestration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mutual exclusion itself, with deterministic interleavings (deferreds, no sleeps except the bounded-wait case). */
class DataOpGateTest {
    @Test fun anExportIsRefusedWhileADeleteRunsAndNothingOfItRuns() = runBlocking<Unit> {
        val gate = DataOpGate(); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val del = async { gate.delete { inside.complete(Unit); release.await() } }
        inside.await()
        var ran = false
        val err = runCatching { gate.export { ran = true } }.exceptionOrNull()
        assertTrue(err is DataOpGate.DeleteRunning); assertEquals("busy: a delete is running", err!!.message); assertFalse(ran)
        release.complete(Unit); del.await()
        gate.export { ran = true }; assertTrue("the export runs once the delete is over", ran)
    }

    @Test fun aDeleteWaitsForARunningExportThenRuns() = runBlocking<Unit> {
        val gate = DataOpGate(); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val exp = async { gate.export { inside.complete(Unit); release.await() } }
        inside.await()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val del = async { gate.delete { order += "delete" } }
        // the delete is parked (registered as waiting): a second export is refused, so the first one is the only thing it waits for
        withTimeout(10_000) { while (!runCatching { gate.export { } }.exceptionOrNull().let { it is DataOpGate.DeleteRunning }) kotlinx.coroutines.delay(5) }
        assertTrue("the delete has not run while the export is", order.isEmpty())
        order += "export end"; release.complete(Unit); exp.await(); del.await()
        assertEquals(listOf("export end", "delete"), order)
    }

    @Test fun aDeleteThatWaitedTooLongFailsTypedAndNeverRuns() = runBlocking<Unit> {
        val gate = DataOpGate(waitMs = 150); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val exp = async { gate.export { inside.complete(Unit); release.await() } }
        inside.await()
        var ran = false
        val err = runCatching { gate.delete { ran = true } }.exceptionOrNull()
        assertTrue(err is DataOpGate.ExportInProgress); assertEquals("export in progress, try again", err!!.message); assertFalse(ran)
        release.complete(Unit); exp.await()
        gate.delete { ran = true }; assertTrue("after the export a delete runs", ran)
        gate.export { } // and the waiting count was released: exports start again
    }

    @Test fun deletesDoNotExcludeEachOtherAndAreReentrant() = runBlocking<Unit> {
        val gate = DataOpGate(); var n = 0
        gate.delete { gate.delete { n++ }; n++ }
        assertEquals(2, n)
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val a = async { gate.delete { inside.complete(Unit); release.await() } }
        inside.await(); gate.delete { n++ }; assertEquals(3, n)
        release.complete(Unit); a.await()
    }

    @Test fun exportsDoNotExcludeEachOther() = runBlocking<Unit> {
        val gate = DataOpGate(); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val a = async { gate.export { inside.complete(Unit); release.await() } }
        inside.await(); var ran = false; gate.export { ran = true }; assertTrue(ran)
        release.complete(Unit); a.await()
    }

    private suspend fun assertFree(gate: DataOpGate) { var ran = false; gate.export { ran = true }; assertTrue("the gate is free: an export starts", ran) }

    @Test fun cancellingADeleteBeforeItAcquiresLeavesTheGateFree() = runBlocking<Unit> {
        val gate = DataOpGate()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { gate.delete { } }
        job.cancel(); job.join()
        assertFree(gate)
    }

    @Test fun cancellingADeleteDuringTheWaitLeavesTheGateFree() = runBlocking<Unit> {
        val gate = DataOpGate(); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val exp = async { gate.export { inside.complete(Unit); release.await() } }; inside.await()
        val del = launch { gate.delete { } }
        withTimeout(10_000) { while (!runCatching { gate.export { } }.exceptionOrNull().let { it is DataOpGate.DeleteRunning }) kotlinx.coroutines.delay(5) }
        del.cancel(); del.join()
        release.complete(Unit); exp.await()
        assertFree(gate)
    }

    @Test fun cancellingADeleteAfterItAcquiredLeavesTheGateFree() = runBlocking<Unit> {
        val gate = DataOpGate(); val inside = CompletableDeferred<Unit>()
        val del = launch { gate.delete { inside.complete(Unit); CompletableDeferred<Unit>().await() } }
        inside.await(); del.cancel(); del.join()
        assertFree(gate)
    }

    @Test fun aCancellationLandingRightAfterTheExportEndedDoesNotLeakTheDeleteCount() = runBlocking<Unit> {
        val gate = DataOpGate(); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val exp = async { gate.export { inside.complete(Unit); release.await() } }; inside.await()
        lateinit var del: kotlinx.coroutines.Job
        gate.testInPredicate = { del.cancel() } // the export is over, the wait is about to hand the gate over: the delete is cancelled right there
        del = launch { gate.delete { } }
        withTimeout(10_000) { while (!runCatching { gate.export { } }.exceptionOrNull().let { it is DataOpGate.DeleteRunning }) kotlinx.coroutines.delay(5) }
        release.complete(Unit); exp.await(); del.join()
        assertFree(gate)
    }

    // ---- the write side (a canvas Save): waits, bounded, for a running export or delete ----

    @Test fun aWriteRunsAtOnceWhenNothingElseRuns() = runBlocking<Unit> {
        var ran = false
        DataOpGate().write { ran = true }
        assertTrue(ran)
    }

    @Test fun aWriteWaitsForARunningExportThenRuns() = runBlocking<Unit> {
        val gate = DataOpGate(); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val exp = async { gate.export { inside.complete(Unit); release.await() } }
        inside.await()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val w = async { gate.write { order += "write" } }
        kotlinx.coroutines.delay(100)
        assertTrue("the write has not run while the export does", order.isEmpty())
        order += "export end"; release.complete(Unit); exp.await(); w.await()
        assertEquals(listOf("export end", "write"), order)
    }

    @Test fun aWriteWaitsForARunningDeleteThenRuns() = runBlocking<Unit> {
        val gate = DataOpGate(); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val del = async { gate.delete { inside.complete(Unit); release.await() } }
        inside.await()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val w = async { gate.write { order += "write" } }
        kotlinx.coroutines.delay(100)
        assertTrue("the write has not run while the delete does", order.isEmpty())
        order += "delete end"; release.complete(Unit); del.await(); w.await()
        assertEquals(listOf("delete end", "write"), order)
    }

    @Test fun aWriteThatWaitedTooLongFailsTypedNamingTheReasonAndNeverRuns() = runBlocking<Unit> {
        val gate = DataOpGate(waitMs = 150); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val exp = async { gate.export { inside.complete(Unit); release.await() } }
        inside.await()
        var ran = false
        val e1 = runCatching { gate.write { ran = true } }.exceptionOrNull()
        assertTrue(e1 is DataOpGate.WriteBlocked); assertTrue((e1 as DataOpGate.WriteBlocked).byExport); assertFalse(ran)
        release.complete(Unit); exp.await()
        val inside2 = CompletableDeferred<Unit>(); val release2 = CompletableDeferred<Unit>()
        val del = async { gate.delete { inside2.complete(Unit); release2.await() } }
        inside2.await()
        val e2 = runCatching { gate.write { ran = true } }.exceptionOrNull()
        assertTrue(e2 is DataOpGate.WriteBlocked); assertFalse((e2 as DataOpGate.WriteBlocked).byExport); assertFalse(ran)
        release2.complete(Unit); del.await()
        gate.write { ran = true }; assertTrue("after both, a write runs", ran)
        gate.export { }; gate.delete { } // and nothing was left held
    }

    /** Codex review 2026-10-26: a write must RESERVE its side for its whole run, so an export or a delete cannot enter in the middle of a Save. */
    @Test fun anExportIsRefusedTypedWhileAWriteRunsAndNothingOfItRuns() = runBlocking<Unit> {
        val gate = DataOpGate(); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val w = async { gate.write { inside.complete(Unit); release.await() } }
        inside.await()
        var ran = false
        val err = runCatching { gate.export { ran = true } }.exceptionOrNull()
        assertTrue(err is DataOpGate.WriteRunning); assertTrue("callers that catch DeleteRunning still catch it", err is DataOpGate.DeleteRunning); assertFalse(ran)
        release.complete(Unit); w.await()
        gate.export { ran = true }; assertTrue(ran)
    }

    @Test fun aDeleteWaitsForARunningWriteThenRuns() = runBlocking<Unit> {
        val gate = DataOpGate(); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val w = async { gate.write { inside.complete(Unit); release.await() } }
        inside.await()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val del = async { gate.delete { order += "delete" } }
        kotlinx.coroutines.delay(100)
        assertTrue("the delete has not entered in the middle of the write", order.isEmpty())
        order += "write end"; release.complete(Unit); w.await(); del.await()
        assertEquals(listOf("write end", "delete"), order)
    }

    @Test fun aDeleteThatWaitedTooLongForAWriteFailsTypedAndAWriteNeverStarvesIt() = runBlocking<Unit> {
        val gate = DataOpGate(waitMs = 150); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val w = async { gate.write { inside.complete(Unit); release.await() } }
        inside.await()
        var ran = false
        val err = runCatching { gate.delete { ran = true } }.exceptionOrNull()
        assertTrue(err is DataOpGate.WriteInProgress); assertTrue(err is DataOpGate.ExportInProgress); assertFalse(ran)
        release.complete(Unit); w.await()
        gate.delete { ran = true }; assertTrue("the gate is free again", ran)
    }

    @Test fun aWaitingDeleteStopsNewWritesFromStartingSoItIsNotStarved() = runBlocking<Unit> {
        val gate = DataOpGate(waitMs = 5_000); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val w = async { gate.write { inside.complete(Unit); release.await() } }
        inside.await()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val del = async { gate.delete { order += "delete" } }
        kotlinx.coroutines.delay(100)
        val w2 = async { gate.write { order += "write2" } }
        kotlinx.coroutines.delay(100)
        assertTrue("write2 waits behind the delete", order.isEmpty())
        release.complete(Unit); w.await(); del.await(); w2.await()
        assertEquals(listOf("delete", "write2"), order)
    }

    @Test fun aWriteThatEndsOrIsCancelledReleasesItsReservation() = runBlocking<Unit> {
        val gate = DataOpGate()
        runCatching { gate.write { throw IllegalStateException("x") } }
        assertFree(gate)
        val inside = CompletableDeferred<Unit>()
        val job = launch { gate.write { inside.complete(Unit); kotlinx.coroutines.awaitCancellation() } }
        inside.await(); job.cancel(); job.join()
        assertFree(gate)
    }

    /** Codex recheck: a cancellation (or the timeout) that wins right AFTER the reservation, before the block is entered, must not leak the writer slot. */
    @Test fun aCancellationRightAfterTheReservationDoesNotLeakTheWriterSlot() = runBlocking<Unit> {
        val gate = DataOpGate(); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val del = async { gate.delete { inside.complete(Unit); release.await() } }
        inside.await()
        var ran = false
        lateinit var job: kotlinx.coroutines.Job
        gate.testInReserve = { job.cancel() }
        job = launch { gate.write { ran = true } }
        kotlinx.coroutines.delay(100)
        release.complete(Unit); del.await(); job.join()
        assertFalse("cancelled before the block", ran)
        gate.testInReserve = null
        assertFree(gate)
        var d = false; gate.delete { d = true }; assertTrue("deletes are not held up by a leaked writer either", d)
    }

    @Test fun aTimeoutRightAfterTheReservationEitherRunsTheBlockOrReleasesTheSlot() = runBlocking<Unit> {
        val gate = DataOpGate(waitMs = 50); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val del = async { gate.delete { inside.complete(Unit); release.await() } }
        inside.await()
        gate.testInReserve = { Thread.sleep(200) } // the wait's timeout fires while the slot was just taken
        val r = async { runCatching { gate.write { "ran" } } }
        kotlinx.coroutines.delay(30); release.complete(Unit); del.await()
        r.await()
        gate.testInReserve = null
        assertFree(gate)
        var d = false; gate.delete { d = true }; assertTrue(d)
    }

    // ---- codex r9 P1-1: a photo upload holds the dispatch side for its whole call
    @Test fun aDeleteWaitsForARunningPhotoUploadAndAnUploadWaitsForARunningDelete() = runBlocking<Unit> {
        val gate = DataOpGate(); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val up = async { gate.dispatch { inside.complete(Unit); release.await(); order += "upload end" } }
        inside.await()
        gate.export { order += "export" } // exports are not blocked by uploads
        val del = async { gate.delete { order += "delete" } }
        kotlinx.coroutines.delay(50)
        assertEquals(listOf("export"), order)
        release.complete(Unit); up.await(); del.await()
        assertEquals(listOf("export", "upload end", "delete"), order)

        val dIn = CompletableDeferred<Unit>(); val dRelease = CompletableDeferred<Unit>()
        val del2 = async { gate.delete { dIn.complete(Unit); dRelease.await(); order += "delete2 end" } }
        dIn.await()
        val up2 = async { gate.dispatch { order += "upload2" } }
        kotlinx.coroutines.delay(50)
        assertFalse("no upload starts while a delete runs", "upload2" in order)
        dRelease.complete(Unit); del2.await(); up2.await()
        assertEquals(listOf("delete2 end", "upload2"), order.takeLast(2))
    }

    @Test fun aDeleteThatWaitedTooLongForAnUploadFailsTypedAsBusy() = runBlocking<Unit> {
        val gate = DataOpGate(waitMs = 150); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val up = async { gate.dispatch { inside.complete(Unit); release.await() } }
        inside.await()
        var ran = false
        val err = runCatching { gate.delete { ran = true } }.exceptionOrNull()
        assertTrue(err is DataOpGate.UploadInProgress && err is DataOpGate.WriteInProgress); assertFalse(ran)
        release.complete(Unit); up.await()
        gate.delete { ran = true }; assertTrue(ran)
    }
}
