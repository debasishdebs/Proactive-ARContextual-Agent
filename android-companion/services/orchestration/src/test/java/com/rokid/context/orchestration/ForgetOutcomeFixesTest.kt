package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.forget.ForgetActions
import com.rokid.context.orchestration.forget.ForgetStartup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** v0.9.7 review fixes C (R3: a cached export that cannot be deleted is a typed Forget outcome in production, retried) and D (R6: a Forget blocked by a Canvas Save names the save). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetOutcomeFixesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)
    private val original = DataOpGate.shared

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        DataOpGate.shared = DataOpGate(waitMs = 200)
        MemoryExport.productionBuild = { true }
        MemoryExport.dir(ctx).deleteRecursively()
    }
    @After fun tearDown() {
        MemoryExport.testDelete = null; MemoryExport.productionBuild = MemoryExport.defaultProductionBuild; MemoryExport.dir(ctx).deleteRecursively()
        ctx.getSharedPreferences(MemoryExport.CLEANUP_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        DataOpGate.shared = original; graph.db.close(); MemoryGraph.resetForTests(null)
    }

    private suspend fun mem(id: String) = graph.db.memoryDao().insert(MemoryEntity(id, null, "t", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "t"))
    private fun cachedExport(): File = File(MemoryExport.dir(ctx).apply { mkdirs() }, "proactive-context-memories-1.json").apply { writeText("{\"memories\":[{\"text\":\"t\"}]}") }

    // --- C ---
    @Test fun inProductionAnUndeletableCachedExportMakesTheForgetTypedButTheFactIsGone() = runBlocking<Unit> {
        mem("m1"); val f = cachedExport(); MemoryExport.testDelete = { false }
        assertEquals(ForgetActions.Outcome.CACHE_CLEANUP_FAILED, ForgetActions.forgetFactOutcome(ctx, "m1"))
        assertNull("the Forget itself committed", graph.db.memoryViewDao().byId("m1")); assertTrue("what remains is the cached file", f.exists())
        assertTrue(MemoryExport.cleanupPending(ctx))
    }

    @Test fun aDebugBuildMayLeaveIt() = runBlocking<Unit> {
        mem("m1"); cachedExport(); MemoryExport.testDelete = { false }; MemoryExport.productionBuild = { false }
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetFactOutcome(ctx, "m1"))
    }

    @Test fun theNextStartRetriesAndClearsThePendingMark() = runBlocking<Unit> {
        mem("m1"); val f = cachedExport(); MemoryExport.testDelete = { false }
        ForgetActions.forgetFactOutcome(ctx, "m1")
        MemoryExport.testDelete = null
        ForgetStartup.run(ctx) { }
        assertFalse(f.exists()); assertFalse(MemoryExport.cleanupPending(ctx))
    }

    @Test fun theNextForgetRetriesToo() = runBlocking<Unit> {
        mem("m1"); mem("m2"); val f = cachedExport(); MemoryExport.testDelete = { false }
        ForgetActions.forgetFactOutcome(ctx, "m1")
        MemoryExport.testDelete = null
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetFactOutcome(ctx, "m2"))
        assertFalse(f.exists()); assertFalse(MemoryExport.cleanupPending(ctx))
    }

    @Test fun theStartUpRetryIsBounded() = runBlocking<Unit> {
        mem("m1"); cachedExport(); var calls = 0; MemoryExport.testDelete = { calls++; false }
        ForgetActions.forgetFactOutcome(ctx, "m1"); val afterForget = calls
        repeat(20) { MemoryExport.retryPending(ctx) }
        assertEquals(MemoryExport.MAX_START_RETRIES, calls - afterForget)
        assertTrue("still marked: the next Forget tries again", MemoryExport.cleanupPending(ctx))
    }

    @Test fun aCleanDeleteLeavesNoMark() = runBlocking<Unit> {
        mem("m1"); val f = cachedExport()
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetFactOutcome(ctx, "m1"))
        assertFalse(f.exists()); assertFalse(MemoryExport.cleanupPending(ctx))
    }

    // --- D ---
    @Test fun aForgetBlockedByACanvasSaveSaysSoAndForgetsNothing() = runBlocking<Unit> {
        mem("m1")
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val save = async { DataOpGate.shared.write { inside.complete(Unit); release.await() } }; inside.await()
        assertEquals(ForgetActions.Outcome.SAVE_IN_PROGRESS, ForgetActions.forgetFactOutcome(ctx, "m1"))
        assertNotNull(graph.db.memoryViewDao().byId("m1"))
        release.complete(Unit); save.await()
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetFactOutcome(ctx, "m1"))
    }

    @Test fun anExportStillSaysExport() = runBlocking<Unit> {
        mem("m1")
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val exp = async { DataOpGate.shared.export { inside.complete(Unit); release.await() } }; inside.await()
        assertEquals(ForgetActions.Outcome.EXPORT_IN_PROGRESS, ForgetActions.forgetFactOutcome(ctx, "m1"))
        release.complete(Unit); exp.await()
    }
}
