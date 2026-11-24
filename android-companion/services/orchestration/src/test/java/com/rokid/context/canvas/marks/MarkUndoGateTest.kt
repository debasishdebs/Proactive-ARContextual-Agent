package com.rokid.context.canvas.marks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.WatchRowCleanup
import com.rokid.context.core.model.MarkMessage
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.orchestration.DataOpGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Every delete path takes the gate: with an export running, a mark undo (tap or voice) and the watch-off row deletion wait, then fail typed and delete nothing. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MarkUndoGateTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val epoch = DeletionEpoch("inst", 2, 1_000)
    private val original = DataOpGate.shared
    private lateinit var dao: com.rokid.context.memory.db.CanvasSlice3Dao
    private lateinit var rec: MarkRecorder
    private val t0 = 1_000_000L

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        dao = MemoryGraph.get(ctx).db.canvasSlice3Dao()
        rec = MarkRecorder(dao, { DaoFence(0, epoch, { 0 }, { epoch }) }, { t0 }, { })
        DataOpGate.shared = DataOpGate(waitMs = 150)
    }
    @After fun tearDown() { DataOpGate.shared = original; MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun mark(seq: Long, undo: Long? = null) = MarkMessage(epoch = "e", seq = seq, atMs = 5, atElapsedMs = 5, listen = true, undo = undo, deletionCounter = 2)
    private fun at(ms: Long) = MarkArrival(ms, mapped = true, receivedAtMs = ms)

    private suspend fun <T> duringAnExport(block: suspend () -> T): Result<T> = kotlinx.coroutines.coroutineScope {
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val exp = async { DataOpGate.shared.export { inside.complete(Unit); release.await() } }; inside.await()
        val r = runCatching { block() }
        release.complete(Unit); exp.await(); r
    }

    @Test fun aTapUndoDuringAnExportFailsTypedAndDeletesNothing() = runBlocking<Unit> {
        rec.onMark(mark(1), at(t0))
        val r = duringAnExport { rec.onMark(mark(2, undo = 1), at(t0 + 10)) }
        assertTrue(r.exceptionOrNull() is DataOpGate.ExportInProgress)
        assertEquals("the mark is still there", 1, dao.labels().size)
        assertTrue("after the export the same undo goes through", rec.onMark(mark(2, undo = 1), at(t0 + 20))); assertTrue(dao.labels().isEmpty())
    }

    @Test fun aVoiceUndoDuringAnExportFailsTypedAndDeletesNothing() = runBlocking<Unit> {
        rec.onListenStart(t0); rec.onMark(mark(1), at(t0 + 100))
        val r = duringAnExport { rec.applyVoice(listOf(VoiceMark.Undo), true, t0 + 2_000) }
        assertTrue(r.exceptionOrNull() is DataOpGate.ExportInProgress)
        assertEquals(1, dao.labels().size)
        rec.applyVoice(listOf(VoiceMark.Undo), true, t0 + 3_000); assertTrue(dao.labels().isEmpty())
    }

    @Test fun watchOffRowDeletionDuringAnExportFailsTypedAndKeepsTheRows() = runBlocking<Unit> {
        val c = MemoryGraph.get(ctx).db.canvasDao()
        c.insertRun(CanvasRunEntity("w1", "p", null, "h", "{}", "DONE", 1, 1, 0, 0, 1, null, "[]"))
        c.upsertItem(CanvasRunItemEntity("w1", "watch:1000", "n", "OK", null, "{}", null, null, null, null, null, null, null))
        val r = duringAnExport { WatchRowCleanup.deleteWatchRuns(c) }
        assertTrue(r.exceptionOrNull() is DataOpGate.ExportInProgress)
        assertNotNull(c.run("w1"))
        assertEquals(1, WatchRowCleanup.deleteWatchRuns(c)); assertNull(c.run("w1"))
    }

    @Test fun aWatchOffCleanupBlockedByALongExportRetriesUntilTheRowsAreGone() = runBlocking<Unit> {
        val c = MemoryGraph.get(ctx).db.canvasDao()
        c.insertRun(CanvasRunEntity("w1", "p", null, "h", "{}", "DONE", 1, 1, 0, 0, 1, null, "[]"))
        c.upsertItem(CanvasRunItemEntity("w1", "watch:1000", "n", "OK", null, "{}", null, null, null, null, null, null, null))
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val exp = async { DataOpGate.shared.export { inside.complete(Unit); release.await() } }; inside.await()
        val cleanup = async { WatchRowCleanup.deleteUntilDone(c, retryMs = 20) }
        kotlinx.coroutines.delay(400) // several failed attempts (each waits 150 ms) while the export runs
        assertNotNull("still there while the export runs", c.run("w1"))
        release.complete(Unit); exp.await()
        kotlinx.coroutines.withTimeout(10_000) { cleanup.await() }
        assertNull("deleted once the export finished", c.run("w1"))
    }

    private val CACHE_LEFT = com.rokid.context.orchestration.forget.ForgetActions.Outcome.CACHE_CLEANUP_FAILED

    /** A photo mark `mk:e:1` linked to frame `p1`; the photo Forget (faked) removes the link row like the real one and returns [outcome]. */
    private suspend fun photoRecorder(owed: () -> Boolean, outcome: com.rokid.context.orchestration.forget.ForgetActions.Outcome = CACHE_LEFT): MarkRecorder {
        val r = MarkRecorder(dao, { DaoFence(0, epoch, { 0 }, { epoch }) }, { t0 }, { },
            forgetPhotoOutcome = { dao.deleteLabel("mk:e:1"); outcome }, cleanupOwed = { owed() })
        dao.insertLabelFenced(com.rokid.context.memory.db.CanvasLabelEntity("mk:e:1", "MOMENT", "tap", "MOMENT", t0, frameId = "p1", createdAt = t0, photo = "linked"), DaoFence(0, epoch, { 0 }, { epoch }))
        return r
    }

    /** Codex mark r2 P1-3: the photo Forget committed with cache-left, the second gate step was refused: the retry (no frame any more) keeps returning the cache-left outcome until clean. */
    @Test fun aRetryAfterARefusedSecondGateStepStillReportsTheCleanupOwedUntilItIsClean() = runBlocking<Unit> {
        var owed = true
        val r = photoRecorder({ owed })
        // the photo Forget commits (label gone); an export then blocks the gate acquisition of the second step
        val failed = duringAnExport { r.undoMark("mk:e:1") }
        assertTrue(failed.exceptionOrNull() is DataOpGate.ExportInProgress)
        assertNull("the photo Forget already removed the link", dao.label("mk:e:1"))
        assertEquals("the retry hands the notice back, not a plain DONE", CACHE_LEFT, r.undoMark("mk:e:1")?.outcome)
        // delivered once; a later retry with the cleanup finished says nothing owed
        assertNull(r.undoMark("mk:e:1"))
    }

    @Test fun aRetryWhoseCleanupFinishedInTheMeantimeReportsNoNotice() = runBlocking<Unit> {
        var owed = true
        val r = photoRecorder({ owed })
        duringAnExport { r.undoMark("mk:e:1") }
        owed = false
        assertNull("clean: a plain done", r.undoMark("mk:e:1"))
    }

    @Test fun aRetryWithTheCleanupStillOwedKeepsReportingItWhileTheGateKeepsRefusing() = runBlocking<Unit> {
        val r = photoRecorder({ true })
        duringAnExport { r.undoMark("mk:e:1") }
        val again = duringAnExport { r.undoMark("mk:e:1") }
        assertTrue(again.exceptionOrNull() is DataOpGate.ExportInProgress)
        assertEquals(CACHE_LEFT, r.undoMark("mk:e:1")?.outcome)
    }

    /** Codex mark r2 P1-1: the voice undo reports a committed cleanup notice (true), a plain undo reports none. */
    @Test fun aVoiceUndoWhosePhotoForgetLeftCleanupReportsItToTheCaller() = runBlocking<Unit> {
        val r = photoRecorder({ true })
        r.onListenStart(t0)
        // bind the pre-made photo mark to the listen
        r.onMark(mark(1), at(t0 + 100))
        assertTrue(r.applyVoice(listOf(VoiceMark.Undo), true, t0 + 2_000))
        val plain = photoRecorder({ false }, com.rokid.context.orchestration.forget.ForgetActions.Outcome.DONE)
        plain.onListenStart(t0 + 10_000)
        assertEquals(false, plain.applyVoice(listOf(VoiceMark.Undo), true, t0 + 12_000))
    }
}
