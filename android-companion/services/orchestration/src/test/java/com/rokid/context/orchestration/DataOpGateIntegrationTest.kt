package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.orchestration.forget.ForgetActions
import com.rokid.context.orchestration.forget.RetentionWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Every delete entry point takes the exclusive side, and the export takes the shared one (the gate ruling of 2026-10-25), through the REAL entry points. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DataOpGateIntegrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)
    private val original = DataOpGate.shared

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        DataOpGate.shared = DataOpGate(waitMs = 200) // the 30 s wait, shortened
    }
    @After fun tearDown() { DataOpGate.shared = original; graph.db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun mem(id: String) = graph.db.memoryDao().insert(MemoryEntity(id, null, "t", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "t"))

    /** Holds the shared side the way a running export does, until [release]. */
    private fun kotlinx.coroutines.CoroutineScope.holdExport(inside: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) =
        async { DataOpGate.shared.export { inside.complete(Unit); release.await() } }

    @Test fun aForgetDuringAnExportFailsTypedAndForgetsNothing() = runBlocking<Unit> {
        mem("m1")
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val exp = holdExport(inside, release); inside.await()
        assertEquals(ForgetActions.Outcome.EXPORT_IN_PROGRESS, ForgetActions.forgetFactOutcome(ctx, "m1"))
        assertFalse("the Boolean form says no too", ForgetActions.forgetFact(ctx, "m1"))
        assertNotNull("nothing was forgotten", graph.db.memoryViewDao().byId("m1"))
        release.complete(Unit); exp.await()
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetFactOutcome(ctx, "m1")) // the same Forget now goes through
        assertTrue(graph.db.memoryViewDao().byId("m1") == null)
    }

    @Test fun aForgetThatArrivesDuringAnExportWaitsAndThenRuns() = runBlocking<Unit> {
        DataOpGate.shared = DataOpGate(waitMs = 20_000)
        mem("m1")
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val exp = holdExport(inside, release); inside.await()
        val forget = async { ForgetActions.forgetFactOutcome(ctx, "m1") }
        // the Forget is parked behind the export: still there, and a new export is refused (the waiting delete must not starve)
        kotlinx.coroutines.withTimeout(10_000) { while (!runCatching { DataOpGate.shared.export { } }.exceptionOrNull().let { it is DataOpGate.DeleteRunning }) kotlinx.coroutines.delay(5) }
        assertNotNull(graph.db.memoryViewDao().byId("m1"))
        release.complete(Unit); exp.await()
        assertEquals(ForgetActions.Outcome.DONE, forget.await())
        assertTrue(graph.db.memoryViewDao().byId("m1") == null)
    }

    @Test fun anExportDuringAForgetIsRefusedTypedAndWritesNothing() = runBlocking<Unit> {
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val del = async { DataOpGate.shared.delete { inside.complete(Unit); release.await() } }; inside.await()
        val err = runCatching { MemoryExport.write(ctx, nowMs = 7) }.exceptionOrNull()
        assertTrue(err is DataOpGate.DeleteRunning)
        assertTrue("no file, no .part", MemoryExport.dir(ctx).listFiles().orEmpty().isEmpty())
        release.complete(Unit); del.await()
        assertTrue(MemoryExport.write(ctx, nowMs = 8).exists())
    }

    @Test fun deleteAllDuringAnExportFailsTypedAndDeletesNothing() = runBlocking<Unit> {
        mem("m1")
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val exp = holdExport(inside, release); inside.await()
        val err = runCatching { MemoryWipe.deleteAll(ctx) }.exceptionOrNull()
        assertTrue(err is DataOpGate.ExportInProgress)
        assertNotNull(graph.db.memoryViewDao().byId("m1"))
        release.complete(Unit); exp.await()
        MemoryWipe.deleteAll(ctx)
        assertTrue(graph.db.memoryViewDao().byId("m1") == null)
    }

    @Test fun aRetentionPassDuringAnExportIsRetriedNotSkipped() = runBlocking<Unit> {
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val exp = holdExport(inside, release); inside.await()
        val r = TestListenableWorkerBuilder<RetentionWorker>(ctx).build().doWork()
        assertEquals(ListenableWorker.Result.retry(), r)
        release.complete(Unit); exp.await()
        assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<RetentionWorker>(ctx).build().doWork())
    }
}
