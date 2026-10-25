package com.rokid.context.orchestration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
}
