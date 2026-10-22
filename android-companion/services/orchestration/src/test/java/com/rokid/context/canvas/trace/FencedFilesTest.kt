package com.rokid.context.canvas.trace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.memory.db.DeletionEpochStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FencedFilesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val files = FencedFiles(db, ctx.filesDir)
    @Before fun clean() { FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }; ctx.getSharedPreferences(DeletionEpochStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit() }
    @After fun close() { db.close(); clean() }

    private fun tmp(text: String = "trace") = files.newTmp("t1").also { it.writeText(text) }
    private fun finalFile(name: String = "t1.bin") = File(File(ctx.filesDir, FencedFiles.TRACES_DIR), name)

    @Test fun discardRemovesNonEmptyDirectoriesAfterAFailedCommit() {
        val tmpDir = File(ctx.filesDir, FencedFiles.TMP_DIR + "/imp-1").apply { mkdirs(); File(this, "chunk-0000.json").writeText("x") }
        val finalDir = File(ctx.filesDir, FencedFiles.TRACES_DIR + "/trace-0001").apply { mkdirs(); File(this, "chunk-0000.json").writeText("x") }
        files.discard(tmpDir, finalDir, moved = true)
        assertFalse(tmpDir.exists()); assertFalse("a moved import's chunks do not stay behind", finalDir.exists())
    }

    @Test fun publishRenamesWhenTheFenceHolds() = runBlocking {
        val t = tmp(); val f = finalFile()
        assertTrue(files.publish(t, f) { true })
        assertEquals("trace", f.readText()); assertFalse(t.exists())
    }

    @Test fun fencedPublishSkipsAfterWipe() = runBlocking {
        val store = DeletionEpochStore.of(ctx)
        // The REAL fence: the in-process generation and the persisted epoch, exactly what a recorder captures before it writes the tmp file.
        val fence = DaoFence(MemoryWipe.generation.get(), store.read(), MemoryWipe.generation::get, store::read)
        val t = tmp(); val f = finalFile()
        store.commitWipe(1_000) // a Delete all committed its epoch while the file was being written
        assertFalse(files.publish(t, f) { fence.holds() })
        assertFalse("nothing published", f.exists()); assertFalse("the tmp file is removed", t.exists())
        val again = DaoFence(MemoryWipe.generation.get(), store.read(), MemoryWipe.generation::get, store::read)
        assertTrue("a recorder that captured the new epoch publishes", files.publish(tmp(), finalFile("t2.bin")) { again.holds() })
    }

    @Test fun aFailedMoveRollsBackTheRowTheFenceWroteAndLeavesNoFile() = runBlocking {
        val t = tmp()
        File(ctx.filesDir, FencedFiles.TRACES_DIR).apply { deleteRecursively(); writeText("a file where the folder should be") }
        val blocked = File(File(ctx.filesDir, FencedFiles.TRACES_DIR), "t1.bin")
        val e = com.rokid.context.memory.db.DeletionEpoch("i", 0, 0)
        val published = files.publish(t, blocked) {
            db.canvasSlice3Dao().insertLabelFenced(com.rokid.context.memory.db.CanvasLabelEntity("row", "MOMENT", "tap", "X", 5, createdAt = 1), DaoFence(0, e, { 0 }, { e }))
        }
        assertFalse(published)
        assertEquals("the row the fence wrote did not commit without its file", null, db.canvasSlice3Dao().label("row"))
        assertEquals("labelRev rolled back too", 0L, db.canvasSlice3Dao().labelRev())
        assertFalse(t.exists())
    }

    @Test fun theFenceRunsInsideTheDatabaseTransaction() = runBlocking {
        val t = tmp(); val f = finalFile()
        var inTransaction = false
        files.publish(t, f) { inTransaction = db.openHelper.writableDatabase.inTransaction(); true }
        assertTrue(inTransaction)
    }

    @Test fun aThrowingFenceOrAFailedMoveLeavesNothingBehind() = runBlocking {
        val t = tmp()
        assertFalse(files.publish(t, finalFile()) { error("boom") })
        assertFalse(t.exists()); assertFalse(finalFile().exists())
        val t2 = tmp(); val blocker = File(ctx.filesDir, FencedFiles.TRACES_DIR).apply { deleteRecursively(); writeText("a file where the folder should be") }
        assertFalse(files.publish(t2, File(blocker, "t.bin")) { true })
        assertFalse(t2.exists())
    }

    @Test fun pathsOutsideTheOwnedFoldersAreRefused() {
        val outsideTmp = File(ctx.filesDir, "elsewhere.tmp").apply { writeText("x") }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { files.publish(outsideTmp, finalFile()) { true } } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { files.publish(tmp(), File(ctx.filesDir, "rokid_archive/x.jpg")) { true } } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { files.publish(tmp(), File(File(ctx.filesDir, FencedFiles.TRACES_DIR), "../escape.bin")) { true } } }
        assertTrue(outsideTmp.exists())
    }

    @Test fun tmpSweptAtStartup() = runBlocking {
        val orphan = tmp("orphan"); val nested = File(files.tmpDir, "sub/deep.tmp").apply { parentFile!!.mkdirs(); writeText("x") }
        val published = finalFile("keep.bin").apply { parentFile!!.mkdirs(); writeText("kept") }
        val imported = File(File(ctx.filesDir, FencedFiles.IMPORT_DIR), "i.bin").apply { parentFile!!.mkdirs(); writeText("kept") }
        val unrelated = File(ctx.filesDir, "keep_me.txt").apply { writeText("x") }
        val result = CanvasStartup.run(ctx, db)
        assertFalse(orphan.exists()); assertFalse(nested.exists())
        assertEquals("both files and the sub folder are counted", 3, result.tmpFilesSwept)
        assertTrue(published.exists()); assertTrue(imported.exists()); assertTrue(unrelated.exists())
        assertEquals("a second pass finds nothing", 0, CanvasStartup.run(ctx, db).tmpFilesSwept)
    }

    @Test fun startupAbortsRunningReplays() = runBlocking {
        db.canvasSlice3Dao().insertReplay(com.rokid.context.memory.db.CanvasReplayEntity("r1", null, "{}", "EXACT", createdAt = 1))
        assertEquals(1, CanvasStartup.run(ctx, db, now = 9).jobsAborted)
        assertEquals("ABORTED", db.canvasSlice3Dao().replay("r1")!!.jobState)
    }
}
