package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasPublishEntity
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CanvasRepositoryTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dao: CanvasDao
    private var now = 1_000L
    private var ids = 0
    private lateinit var repo: CanvasRepository

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(app))
        dao = MemoryGraph.get(app).db.canvasDao()
        repo = CanvasRepository(dao, { "SEED for ${it.name} {{recent_context}}" }, { now++ }, { "p-${++ids}" })
    }

    @After fun tearDown() { MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null) }

    private fun editedJson(id: String, name: String = "Mine") = PipelineJson.encode(DefaultPipelines.liveExtract().copy(id = id, name = name))

    @Test fun builtInsSeededAndRefreshed() = runBlocking<Unit> {
        repo.ensureBuiltIns()
        val all = repo.list()
        assertEquals(setOf("live-extract", "intent-draft", "default-capture", "starter-capture-extract", "starter-moment-summary", "starter-watch-motion"), all.map { it.id }.toSet())
        assertTrue(all.all { it.builtIn && it.draftRev == 0 })
        val intent = PipelineJson.decode(repo.get("intent-draft")!!.draftJson).getOrThrow()
        assertTrue(PipelineJson.encode(intent).contains("SEED for VISION_EXTRACT"))
        // overwritten each call: a changed seed shows up, and nothing is duplicated
        val again = CanvasRepository(dao, { "NEW SEED" }, { now++ }, { "x" })
        again.ensureBuiltIns()
        assertEquals(6, again.list().size)
        assertTrue(again.get("intent-draft")!!.draftJson.contains("NEW SEED"))
        assertFalse(again.get("intent-draft")!!.draftJson.contains("SEED for"))
    }

    @Test fun duplicateFromVersionAndDraft() = runBlocking<Unit> {
        repo.ensureBuiltIns()
        val a = repo.duplicate("live-extract", null, "Copy A")
        val e = repo.get(a)!!
        assertEquals("p-1", a); assertFalse(e.builtIn); assertEquals(0, e.draftRev); assertEquals("Copy A", e.name)
        val p = PipelineJson.decode(e.draftJson).getOrThrow()
        assertEquals(a, p.id); assertEquals("Copy A", p.name)
        // a version of the copy, then a duplicate of that exact version after the draft moved on
        val v = repo.freeze(a, "first")
        val moved = DefaultPipelines.liveExtract().copy(id = a, name = "Changed")
        assertTrue(repo.saveDraft(a, PipelineJson.encode(moved), 0) is CanvasRepository.SaveResult.Saved)
        val b = repo.duplicate(a, v.version, "Copy B")
        assertEquals("Copy B", PipelineJson.decode(repo.get(b)!!.draftJson).getOrThrow().name)
        assertEquals(b, PipelineJson.decode(repo.get(b)!!.draftJson).getOrThrow().id)
        try { repo.duplicate("nope", null, "x"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun saveDraftConflictOnStaleRev() = runBlocking<Unit> {
        repo.ensureBuiltIns()
        val id = repo.duplicate("live-extract", null, "A")
        val r1 = repo.saveDraft(id, editedJson(id, "One"), 0)
        assertEquals(1, (r1 as CanvasRepository.SaveResult.Saved).rev)
        assertEquals(CanvasRepository.SaveResult.Conflict, repo.saveDraft(id, editedJson(id, "Two"), 0))
        assertTrue(repo.get(id)!!.draftJson.contains("One"))
        assertEquals(2, (repo.saveDraft(id, editedJson(id, "Two"), 1) as CanvasRepository.SaveResult.Saved).rev)
    }

    @Test fun builtInReadOnly() = runBlocking<Unit> {
        repo.ensureBuiltIns()
        val before = repo.get("live-extract")!!.draftJson
        assertEquals(CanvasRepository.SaveResult.ReadOnly, repo.saveDraft("live-extract", editedJson("live-extract", "Hacked"), 0))
        assertEquals(before, repo.get("live-extract")!!.draftJson)
        assertFalse(repo.delete("live-extract"))
        assertNotNull(repo.get("live-extract"))
    }

    @Test fun defaultCaptureIsABuiltInThatDuplicatesAndNeverDeletes() = runBlocking<Unit> {
        repo.ensureBuiltIns()
        val dc = repo.get("default-capture")!!
        assertTrue(dc.builtIn); assertEquals("Default capture", dc.name)
        assertEquals(CanvasRepository.SaveResult.ReadOnly, repo.saveDraft("default-capture", dc.draftJson, 0))
        assertFalse(repo.delete("default-capture"))
        assertNotNull(repo.get("default-capture"))
        assertTrue(dao.activePublishes().isEmpty()) // existing as a pipeline activates nothing
        val copy = repo.duplicate("default-capture", null, "My capture")
        val c = repo.get(copy)!!
        assertFalse(c.builtIn)
        assertEquals(DefaultPipelines.defaultCapture().copy(id = copy, name = "My capture"), PipelineJson.decode(c.draftJson).getOrThrow())
    }

    @Test fun starterIsAReadOnlyBuiltInThatClonesToAnEditableDraftAndIsNeverActive() = runBlocking<Unit> {
        repo.ensureBuiltIns()
        val st = repo.get("starter-capture-extract")!!
        assertTrue(st.builtIn); assertEquals("Starter: capture and extract", st.name)
        assertEquals(CanvasRepository.SaveResult.ReadOnly, repo.saveDraft("starter-capture-extract", st.draftJson, 0))
        assertFalse(repo.delete("starter-capture-extract"))
        assertNotNull(repo.get("starter-capture-extract"))
        assertTrue("existing as a pipeline activates nothing", dao.activePublishes().isEmpty())
        assertEquals(DefaultPipelines.starter(), PipelineJson.decode(st.draftJson).getOrThrow())
        val copy = repo.duplicate("starter-capture-extract", null, "My starter")
        val c = repo.get(copy)!!
        assertFalse(c.builtIn)
        assertEquals(DefaultPipelines.starter().copy(id = copy, name = "My starter"), PipelineJson.decode(c.draftJson).getOrThrow())
        val edited = PipelineJson.encode(PipelineJson.decode(c.draftJson).getOrThrow().let { p -> p.copy(nodes = p.nodes.map { if (it.id == "extract") it.copy(title = "Mine") else it }) })
        assertTrue("the clone is editable", repo.saveDraft(copy, edited, c.draftRev) is CanvasRepository.SaveResult.Saved)
    }

    @Test fun theWatchStarterIsSeededReadOnlyClonableAndNeverActive() = runBlocking<Unit> {
        repo.ensureBuiltIns()
        val st = repo.get("starter-watch-motion")!!
        assertTrue(st.builtIn); assertEquals("Starter: watch motion to capture rate", st.name)
        assertEquals(CanvasRepository.SaveResult.ReadOnly, repo.saveDraft("starter-watch-motion", st.draftJson, 0))
        assertFalse(repo.delete("starter-watch-motion"))
        assertTrue("existing as a pipeline activates nothing", dao.activePublishes().isEmpty())
        assertEquals(DefaultPipelines.starterWatchMotion(), PipelineJson.decode(st.draftJson).getOrThrow())
        val copy = repo.duplicate("starter-watch-motion", null, "Mine")
        assertFalse(repo.get(copy)!!.builtIn)
        assertEquals(DefaultPipelines.starterWatchMotion().copy(id = copy, name = "Mine"), PipelineJson.decode(repo.get(copy)!!.draftJson).getOrThrow())
        // the earlier built-ins are stored exactly as the code encodes them
        assertEquals(PipelineJson.encode(DefaultPipelines.starterMomentSummary()), repo.get("starter-moment-summary")!!.draftJson)
        assertEquals(PipelineJson.encode(DefaultPipelines.starter()), repo.get("starter-capture-extract")!!.draftJson)
        assertEquals(PipelineJson.encode(DefaultPipelines.defaultCapture()), repo.get("default-capture")!!.draftJson)
        assertEquals(PipelineJson.encode(DefaultPipelines.liveExtract()), repo.get("live-extract")!!.draftJson)
    }

    @Test fun theMomentSummaryStarterIsSeededReadOnlyClonableAndLeavesTheOtherBuiltInsAsTheyWere() = runBlocking<Unit> {
        repo.ensureBuiltIns()
        val st = repo.get("starter-moment-summary")!!
        assertTrue(st.builtIn); assertEquals("Starter: moments to summary", st.name)
        assertEquals(CanvasRepository.SaveResult.ReadOnly, repo.saveDraft("starter-moment-summary", st.draftJson, 0))
        assertFalse(repo.delete("starter-moment-summary"))
        assertTrue("existing as a pipeline activates nothing", dao.activePublishes().isEmpty())
        assertEquals(DefaultPipelines.starterMomentSummary(), PipelineJson.decode(st.draftJson).getOrThrow())
        val copy = repo.duplicate("starter-moment-summary", null, "Mine")
        assertFalse(repo.get(copy)!!.builtIn)
        assertEquals(DefaultPipelines.starterMomentSummary().copy(id = copy, name = "Mine"), PipelineJson.decode(repo.get(copy)!!.draftJson).getOrThrow())
        // the earlier built-ins are stored exactly as before (their encoded JSON is the code's, unchanged by this one existing)
        assertEquals(PipelineJson.encode(DefaultPipelines.starter()), repo.get("starter-capture-extract")!!.draftJson)
        assertEquals(PipelineJson.encode(DefaultPipelines.defaultCapture()), repo.get("default-capture")!!.draftJson)
        assertEquals(PipelineJson.encode(DefaultPipelines.liveExtract()), repo.get("live-extract")!!.draftJson)
    }

    @Test fun unreadableNotSaved() = runBlocking<Unit> {
        repo.ensureBuiltIns()
        val id = repo.duplicate("live-extract", null, "A")
        val before = repo.get(id)!!
        assertTrue(repo.saveDraft(id, "{not json", 0) is CanvasRepository.SaveResult.Unreadable)
        assertTrue(repo.saveDraft(id, "{\"schema\":9}", 0) is CanvasRepository.SaveResult.Unreadable)
        assertEquals(before, repo.get(id))
    }

    @Test fun invalidSavedWithProblems() = runBlocking<Unit> {
        repo.ensureBuiltIns()
        val id = repo.duplicate("live-extract", null, "A")
        val broken = DefaultPipelines.liveExtract().let { it.copy(id = id, edges = it.edges.drop(1)) }
        val r = repo.saveDraft(id, PipelineJson.encode(broken), 0) as CanvasRepository.SaveResult.Saved
        assertTrue(r.problems.isNotEmpty())
        assertEquals(1, repo.get(id)!!.draftRev)
        assertEquals(PipelineJson.encode(broken), repo.get(id)!!.draftJson)
    }

    @Test fun freezeIncrementsAndDedupesSameHash() = runBlocking<Unit> {
        repo.ensureBuiltIns()
        val id = repo.duplicate("live-extract", null, "A")
        val v1 = repo.freeze(id, "one")
        assertEquals(1, v1.version)
        assertEquals(v1, repo.freeze(id, "again"))
        assertEquals(1, dao.versions(id).size)
        val changed = DefaultPipelines.liveExtract().copy(id = id, name = "Renamed")
        repo.saveDraft(id, PipelineJson.encode(changed), 0)
        val v2 = repo.freeze(id, "two")
        assertEquals(2, v2.version); assertEquals("two", v2.note)
        assertTrue(v2.hash != v1.hash)
        assertEquals(listOf(2, 1), dao.versions(id).map { it.version })
    }

    @Test fun freezeRejectsInvalid() = runBlocking<Unit> {
        repo.ensureBuiltIns()
        val id = repo.duplicate("live-extract", null, "A")
        val broken = DefaultPipelines.liveExtract().let { it.copy(id = id, edges = it.edges.drop(1)) }
        repo.saveDraft(id, PipelineJson.encode(broken), 0)
        try { repo.freeze(id, "x"); fail() } catch (e: IllegalArgumentException) { assertEquals("invalid_pipeline", e.message) }
        assertTrue(dao.versions(id).isEmpty())
    }

    @Test fun deleteRefusedWhenPublished() = runBlocking<Unit> {
        repo.ensureBuiltIns()
        val id = repo.duplicate("live-extract", null, "A")
        val other = repo.duplicate("live-extract", null, "B")
        val v = repo.freeze(id, "one")
        val pid = dao.insertPublish(CanvasPublishEntity(pipelineId = id, version = v.version, hash = v.hash, publishedAt = 1, rolledBackAt = null, snapshotJson = "{}"))
        assertFalse(repo.delete(id))
        assertNotNull(repo.get(id))
        assertTrue(repo.delete(other)); assertNull(repo.get(other))
        dao.markRolledBack(pid, 2)
        assertTrue(repo.delete(id))
    }
}
