package com.rokid.context.canvas.dumpimport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.orchestration.MemoryWipe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Round 5 follow-up: the import's photo publish (hard link, never replace/delete a file it did not create) and its Delete all fence. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImportFrameStoreTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private fun frame(id: String) = DumpImportParser.Frame(id, 1_700_000_000_000, "rokid_cxr", "STILL", false, 0.5f, "abcd", null, 360, null, null, jpeg)
    private val archive get() = RokidFrameInbox.archiveDir(app).apply { mkdirs() }
    private val plainWriter: (File, ByteArray, Long) -> Unit = { f, b, t -> f.writeBytes(b); f.setLastModified(t) }
    private fun names() = archive.listFiles().orEmpty().map { it.name }

    @Before fun setUp() {
        com.rokid.context.data.frames.DirSync.testOverride = com.rokid.context.data.frames.DirSync { true }
        MemoryGraph.resetForTests(com.rokid.context.memory.db.DatabaseFactory.inMemory(app))
    }
    @After fun clean() {
        archive.listFiles().orEmpty().forEach { it.delete() }
        com.rokid.context.data.frames.DirSync.testOverride = null; MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null)
    }

    @Test fun anExistingNameIsRefusedAndItsBytesSurvive() = runBlocking<Unit> {
        val live = File(archive, "f1.jpg").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        assertFalse(ImportFrameStore(app, plainWriter).store(frame("f1"), MemoryWipe.generation.get()))
        assertEquals(listOf<Byte>(9, 9, 9), live.readBytes().toList())
        assertEquals(listOf("f1.jpg"), names())
    }

    @Test fun aNameCreatedByALiveCaptureDuringTheWriteIsNeverReplacedOrDeleted() = runBlocking<Unit> {
        val racing: (File, ByteArray, Long) -> Unit = { f, b, t -> plainWriter(f, b, t); File(archive, "f2.jpg").writeBytes(byteArrayOf(7, 7)) }
        assertFalse(ImportFrameStore(app, racing).store(frame("f2"), MemoryWipe.generation.get()))
        assertEquals(listOf<Byte>(7, 7), File(archive, "f2.jpg").readBytes().toList())
        assertEquals(listOf("f2.jpg"), names()) // the temp is gone too
    }

    @Test fun aFailedWriteLeavesNoTempFile() = runBlocking<Unit> {
        val failing: (File, ByteArray, Long) -> Unit = { f, b, _ -> f.writeBytes(b); error("disk") }
        try { ImportFrameStore(app, failing).store(frame("f3"), MemoryWipe.generation.get()) } catch (_: IllegalStateException) { }
        assertTrue(names().isEmpty())
    }

    @Test fun aDeleteAllBetweenTheCheckAndTheCommitLeavesNoRowAndNoFile() = runBlocking<Unit> {
        val gen0 = MemoryWipe.generation.get()
        val wipeDuringWrite: (File, ByteArray, Long) -> Unit = { f, b, t -> plainWriter(f, b, t); MemoryWipe.generation.incrementAndGet() }
        try {
            assertFalse(ImportFrameStore(app, wipeDuringWrite).store(frame("f4"), gen0))
            assertTrue(MemoryGraph.get(app).db.frameDao().byIds(listOf("f4")).isEmpty())
            assertTrue(MemoryGraph.get(app).db.frameMetaDao().byWireId("f4") == null)
            assertTrue(names().isEmpty())
        } finally { MemoryWipe.generation.set(gen0) }
    }

    @Test fun theSweepRemovesOnlyStaleImpTempsOfTheArchive() {
        val now = System.currentTimeMillis()
        val old = File(archive, ".imp-a-${now - 7_200_000}.jpg").apply { writeBytes(byteArrayOf(1)) }
        // a fresh temp whose mtime is the ORIGINAL capture time (writeStamped) must not be swept
        val fresh = File(archive, ".imp-b-$now.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(1_000) }
        val photo = File(archive, "keep.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(1_000) }
        val hidden = File(archive, ".other.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(1_000) }
        val part = File(archive, ".imp-c-$now.jpg.part").apply { writeBytes(byteArrayOf(1)); setLastModified(1_000) } // the atomic writer's temp: old mtime (the ORIGINAL capture time) but being written
        val active = File(archive, ".imp-d-1000000000000.jpg").apply { writeBytes(byteArrayOf(1)) }
        com.rokid.context.data.frames.ImportTempName.begin(active.name)
        assertEquals(1, RokidFrameInbox.sweepOrphanParts(app, nowMs = now)) // the .part sweep leaves import temps alone; only `old` goes (via the import sweep); the active one is skipped
        assertTrue(part.exists()); assertTrue(active.exists())
        com.rokid.context.data.frames.ImportTempName.end(active.name)
        assertEquals(1, RokidFrameInbox.sweepImportTemps(app, nowMs = now)) // `active` is now unregistered and old by name
        assertFalse(active.exists())
        assertFalse(old.exists()); assertTrue(fresh.exists()); assertTrue(photo.exists()); assertTrue(hidden.exists())
    }
}
