package com.rokid.context.canvas.trace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.capture.trace.TraceFiles
import com.rokid.context.capture.trace.TraceStatus
import com.rokid.context.memory.db.CanvasReplayEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.memory.db.TraceSource
import com.rokid.context.memory.db.TraceSources
import com.rokid.context.orchestration.MemoryWipe
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TraceImportTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val dao = db.canvasSlice3Dao()
    private val files = FencedFiles(db, ctx.filesDir)
    private val store = DeletionEpochStore.of(ctx)
    private val pulled = File(ctx.cacheDir, "pulled")
    private fun realFence() = DaoFence(MemoryWipe.generation.get(), store.read(), MemoryWipe.generation::get, store::read)
    private fun importer(fence: () -> DaoFence = ::realFence) = TraceImport(dao, files, fence) { 5_000L }
    private fun traceDir(id: String) = File(File(ctx.filesDir, FencedFiles.TRACES_DIR), id)
    private fun write(id: String = "trace-aaaa-0001", counter: Long = store.read().counter, install: String = store.read().installId, rec: TraceStatus = TraceStatus.COMPLETE) =
        PulledTraces.write(pulled, id, install, counter, rec)

    @Before fun clean() { wipeAll() }
    @After fun close() { db.close(); wipeAll() }
    private fun wipeAll() {
        ctx.getSharedPreferences(DeletionEpochStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }; pulled.deleteRecursively()
    }

    @Test fun aPulledCompleteTraceIsStoredWithItsRowAndFiles() = runBlocking {
        val dir = write()
        val r = importer().import(dir) as TraceImportResult.Imported
        assertEquals(TraceStatus.COMPLETE, r.status); assertEquals(1, r.revision)
        val row = dao.trace("trace-aaaa-0001")!!
        assertEquals("COMPLETE", row.status); assertEquals("IMU", row.source); assertTrue(row.exact); assertEquals(1, row.revision)
        assertEquals(store.read().installId, row.installId)
        assertEquals(setOf("manifest.json", "chunk-0000.json"), traceDir("trace-aaaa-0001").list()!!.toSet())
        assertEquals("no tmp left", 0, File(ctx.filesDir, FencedFiles.TMP_DIR).listFiles()?.size ?: 0)
        // the stored copy verifies again, byte for byte
        val d = traceDir("trace-aaaa-0001")
        assertTrue(TraceFiles.verify(d.list()!!.toSet()) { File(d, it).readText() }.getOrThrow().second.status == TraceStatus.COMPLETE)
    }

    @Test fun aFlippedChecksumIsRefusedAndNothingIsStored() = runBlocking {
        val dir = write()
        val chunk = File(dir, "chunk-0000.json")
        val t = chunk.readText(); val i = t.indexOf("\"sha256\":\"") + 10
        chunk.writeText(t.substring(0, i) + (if (t[i] == '0') '1' else '0') + t.substring(i + 1))
        val r = importer().import(dir)
        assertEquals("sha_mismatch", (r as TraceImportResult.Rejected).code)
        assertNull(dao.trace("trace-aaaa-0001")); assertFalse(traceDir("trace-aaaa-0001").exists())
    }

    @Test fun aCorruptedPayloadIsRefused() = runBlocking {
        val dir = write()
        val chunk = File(dir, "chunk-0000.json")
        val t = chunk.readText(); val i = t.indexOf("\"b64\":\"") + 7 + 60 // inside the gzip body, not its header
        chunk.writeText(t.substring(0, i) + (if (t[i] == 'A') 'B' else 'A') + t.substring(i + 1))
        assertTrue(importer().import(dir) is TraceImportResult.Rejected)
        assertNull(dao.trace("trace-aaaa-0001")); assertFalse(traceDir("trace-aaaa-0001").exists())
    }

    @Test fun aMissingChunkAndAnExtraFileAreRefused() = runBlocking {
        val a = write("trace-aaaa-0002"); File(a, "chunk-0000.json").delete()
        assertEquals("bad_file_set", (importer().import(a) as TraceImportResult.Rejected).code)
        val b = write("trace-aaaa-0003"); File(b, "notes.txt").writeText("x")
        assertEquals("bad_file_set", (importer().import(b) as TraceImportResult.Rejected).code)
        assertNull(dao.trace("trace-aaaa-0002")); assertNull(dao.trace("trace-aaaa-0003"))
    }

    @Test fun anOversizedFileIsRefusedBeforeItIsRead() = runBlocking {
        val dir = write()
        File(dir, "chunk-0000.json").writeText("x".repeat((TraceImport.MAX_FILE_BYTES + 1).toInt()))
        assertEquals("bad_file", (importer().import(dir) as TraceImportResult.Rejected).code)
    }

    @Test fun aDirectoryNamedAfterAnotherTraceIsRefused() = runBlocking {
        val dir = PulledTraces.write(pulled, "trace-aaaa-0001", store.read().installId, store.read().counter, dirName = "trace-bbbb-9999")
        assertEquals("dir_mismatch", (importer().import(dir) as TraceImportResult.Rejected).code)
    }

    @Test fun aTraceFromAnOlderCounterIsRefused() = runBlocking {
        val old = store.read().counter
        store.commitWipe(1_000) // a Delete all happened after the glasses recorded it
        val r = importer().import(write(counter = old, install = store.read().installId))
        assertEquals("old_epoch", (r as TraceImportResult.Rejected).code)
        assertNull(dao.trace("trace-aaaa-0001")); assertFalse(traceDir("trace-aaaa-0001").exists())
    }

    @Test fun aTraceFromAnotherInstallIsRefused() = runBlocking {
        val r = importer().import(write(install = "someone-else"))
        assertEquals("other_install", (r as TraceImportResult.Rejected).code)
    }

    @Test fun aTombstonedIdIsRefused() = runBlocking {
        dao.tombstoneTrace("trace-aaaa-0001", 1)
        val r = importer().import(write())
        assertEquals("tombstoned", (r as TraceImportResult.Rejected).code)
        assertFalse(traceDir("trace-aaaa-0001").exists())
    }

    @Test fun aDeleteAllDuringTheImportPublishesNoFileAndNoRow() = runBlocking {
        val dir = write()
        val e0 = store.read()
        var reads = 0
        // The first epoch read is the importer's pre-check; every later read (the fence inside the publish transaction) sees the wipe.
        val epochNow = { if (++reads == 1) e0 else DeletionEpoch(e0.installId, e0.counter + 1, 9) }
        val fence = DaoFence(MemoryWipe.generation.get(), e0, MemoryWipe.generation::get, epochNow)
        val r = importer { fence }.import(dir)
        assertTrue(r is TraceImportResult.Rejected)
        assertNull(dao.trace("trace-aaaa-0001")); assertFalse(traceDir("trace-aaaa-0001").exists())
        assertEquals("no tmp left", 0, File(ctx.filesDir, FencedFiles.TMP_DIR).walkTopDown().count { it.isFile })
    }

    @Test fun importingTheSameTraceAgainChangesNothing() = runBlocking {
        val dir = write()
        importer().import(dir)
        val r = importer().import(dir)
        assertEquals(TraceImportResult.Unchanged("trace-aaaa-0001", 1), r)
        assertEquals(1, dao.trace("trace-aaaa-0001")!!.revision)
    }

    @Test fun aChangedReimportBumpsTheRevisionAndInvalidatesResultsThatReadIt() = runBlocking {
        importer().import(write())
        dao.insertReplay(CanvasReplayEntity("r1", null, "{}", "EXACT", "{\"x\":1}", sourcesJson = TraceSources.encode(listOf(TraceSource("trace-aaaa-0001", 1, 0, 10))), createdAt = 1, jobState = "DONE"))
        val r = importer().import(write(rec = TraceStatus.PARTIAL)) as TraceImportResult.Imported
        assertEquals(2, r.revision)
        assertEquals("PARTIAL", dao.trace("trace-aaaa-0001")!!.status)
        assertTrue(dao.replay("r1")!!.invalidated); assertNull(dao.replay("r1")!!.resultJson)
        assertEquals(setOf("manifest.json", "chunk-0000.json"), traceDir("trace-aaaa-0001").list()!!.toSet())
        assertFalse(File(files.tmpDir, "x").exists())
    }

    @Test fun importAllTakesEverySubdirectoryAndReportsEach() = runBlocking {
        write("trace-aaaa-0001"); write("trace-aaaa-0002"); write("trace-aaaa-0003", counter = 99)
        val rs = importer().importAll(pulled)
        assertEquals(listOf(true, true, false), rs.map { it !is TraceImportResult.Rejected })
    }

    @Test fun theImportedTraceCarriesNoLocationOrBluetoothAddress() = runBlocking {
        importer().import(write())
        val text = traceDir("trace-aaaa-0001").listFiles()!!.joinToString { it.readText() } + dao.trace("trace-aaaa-0001")!!.manifestJson
        assertFalse(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").containsMatchIn(text))
        assertFalse(Regex("\"(lat|lon|lng|latitude|longitude|gps|bt|mac)").containsMatchIn(text.lowercase()))
    }
}
