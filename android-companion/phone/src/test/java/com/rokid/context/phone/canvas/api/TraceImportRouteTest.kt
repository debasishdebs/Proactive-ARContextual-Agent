package com.rokid.context.phone.canvas.api

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.plan.ReplayService
import com.rokid.context.canvas.trace.FencedFiles
import com.rokid.context.canvas.trace.PulledTraces
import com.rokid.context.canvas.trace.StoredReplayTraces
import com.rokid.context.canvas.trace.TraceImport
import com.rokid.context.capture.trace.TraceStatus
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.phone.canvas.server.HttpRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Task 23c: POST /api/traces/import through the real TraceRoutes, TraceImport, FencedFiles and (for the replay test) ReplayRoutes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class TraceImportRouteTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val dao = db.canvasSlice3Dao()
    private val store = DeletionEpochStore.of(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dev = MutableStateFlow(true)
    private val staging = File(ctx.cacheDir, "trace-staging")
    private fun fence() = DaoFence(MemoryWipe.generation.get(), store.read(), MemoryWipe.generation::get, store::read)
    private val importer = TraceImport(dao, FencedFiles(db, ctx.filesDir), ::fence) { 5_000L }
    private val routes = TraceRoutes(dao, { null }, dev::value, importer = { importer }, stagingRoot = { staging })
    private val id = "trace-aaaa-0001"

    @Before fun clean() { wipe() }
    @After fun close() { scope.cancel(); db.close(); wipe() }
    private fun wipe() {
        ctx.getSharedPreferences(DeletionEpochStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }; staging.deleteRecursively()
    }
    private fun stage(rec: TraceStatus = TraceStatus.COMPLETE, counter: Long = store.read().counter) = PulledTraces.write(staging, id, store.read().installId, counter, rec)
    private fun post(body: String, path: String = "/api/traces/import") = runBlocking { routes.handle(HttpRequest("POST", path, emptyMap(), emptyMap(), body.toByteArray()))!! }
    private fun text(r: com.rokid.context.phone.canvas.server.HttpResponse) = String(r.body)

    @Test fun importsFromTheStagingDirReportsCountsAndDeletesTheStagingDir() {
        stage()
        val r = post("""{"name":"$id"}""")
        assertEquals(text(r), 200, r.status)
        val t = text(r)
        assertTrue(t, t.contains("\"traceId\":\"$id\"") && t.contains("\"status\":\"COMPLETE\"") && t.contains("\"events\":11") && t.contains("\"chunks\":1"))
        assertNotNull(runBlocking { dao.trace(id) })
        assertTrue(File(File(ctx.filesDir, FencedFiles.TRACES_DIR), id).isDirectory)
        assertFalse("staging dir deleted after success", File(staging, id).exists())
    }

    @Test fun importedTraceIsReplayableThroughTheRealRoutes() {
        stage(); assertEquals(200, post("""{"name":"$id"}""").status)
        val svc = ReplayService(dao, StoredReplayTraces(dao, File(ctx.filesDir, FencedFiles.TRACES_DIR)), ::fence, scope, dev)
        val replays = ReplayRoutes(svc)
        val r = runBlocking { replays.handle(HttpRequest("POST", "/api/replays", emptyMap(), emptyMap(), """{"traceIds":["$id"]}""".toByteArray()))!! }
        assertEquals(text(r), 202, r.status)
        val rid = Regex("\"replayId\":\"([^\"]+)\"").find(text(r))!!.groupValues[1]
        runBlocking { withTimeout(20_000) { while (svc.get(rid)!!.jobState == "RUNNING") delay(10) } }
        val done = text(runBlocking { replays.handle(HttpRequest("GET", "/api/replays/$rid", emptyMap(), emptyMap(), ByteArray(0)))!! })
        assertTrue(done, done.contains("\"jobState\":\"DONE\""))
    }

    @Test fun developerModeOffIs403AndNothingIsImported() {
        stage(); dev.value = false
        assertEquals(403, post("""{"name":"$id"}""").status)
        assertEquals(null, runBlocking { dao.trace(id) }); assertTrue(File(staging, id).isDirectory)
    }

    @Test fun aPartialTraceIsRefusedUnlessAllowed() {
        stage(TraceStatus.PARTIAL)
        val r = post("""{"name":"$id"}""")
        assertEquals(409, r.status); assertTrue(text(r).contains("partial_trace"))
        assertEquals(null, runBlocking { dao.trace(id) }); assertTrue(File(staging, id).isDirectory)
        val ok = post("""{"name":"$id","allowPartial":true}""")
        assertEquals(text(ok), 200, ok.status); assertTrue(text(ok).contains("\"status\":\"PARTIAL\""))
    }

    @Test fun namesThatAreNotPlainTraceIdsAreRefused() {
        stage()
        for (n in listOf("..", "../x", "a/b", "/etc", "trace-aaaa-0001/..", "x", "TRACE-AAAA-0001", "trace-aaaa-0001\n", "", ".", "trace-aaaa-0001/")) {
            val body = kotlinx.serialization.json.JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive(n))).toString()
            assertEquals("name '$n'", 400, post(body).status)
        }
        assertEquals(400, post("""{"name":5}""").status); assertEquals(400, post("not json").status); assertEquals(400, post("").status)
        assertTrue(File(staging, id).isDirectory)
    }

    @Test fun aSymlinkedStagingEntryIsNotFollowed() {
        val outside = File(ctx.cacheDir, "outside-trace").also { it.deleteRecursively() }
        PulledTraces.write(outside.parentFile!!, id, store.read().installId, store.read().counter).renameTo(outside)
        staging.mkdirs(); Files.createSymbolicLink(File(staging, id).toPath(), outside.toPath())
        val r = post("""{"name":"$id"}""")
        assertEquals(text(r), 404, r.status)
        assertEquals(null, runBlocking { dao.trace(id) }); assertTrue(outside.isDirectory)
        outside.deleteRecursively()
    }

    @Test fun missingStagingDirIs404AndAnOldEpochIs409() {
        assertEquals(404, post("""{"name":"$id"}""").status)
        stage(counter = store.read().counter + 5)
        val r = post("""{"name":"$id"}""")
        assertEquals(409, r.status); assertTrue(text(r).contains("old_epoch"))
        assertEquals(null, runBlocking { dao.trace(id) })
    }

    @Test fun aTamperedChunkIsRefusedAndTheStagingDirKept() {
        val d = stage(); val c = File(d, "chunk-0000.json"); val t = c.readText(); val i = t.indexOf("\"sha256\":\"") + 10
        c.writeText(t.substring(0, i) + (if (t[i] == '0') '1' else '0') + t.substring(i + 1))
        val r = post("""{"name":"$id"}""")
        assertEquals(422, r.status); assertTrue(File(staging, id).isDirectory); assertEquals(null, runBlocking { dao.trace(id) })
    }

    @Test fun reImportingTheSameTraceIsIdempotent() {
        stage(); assertEquals(200, post("""{"name":"$id"}""").status)
        stage(); val r = post("""{"name":"$id"}""")
        assertEquals(text(r), 200, r.status); assertTrue(text(r).contains("\"unchanged\":true"))
    }
}
