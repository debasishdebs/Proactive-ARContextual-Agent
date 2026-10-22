package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.orchestration.TestAi
import com.rokid.context.prompt.PromptDraft
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InterpreterPublishTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dao: CanvasDao
    private lateinit var repo: CanvasRepository
    private lateinit var ai: AiGraph
    private var compat: (PromptKey, PromptDraft) -> List<String> = { _, _ -> emptyList() }
    private var now = 1_000L
    private var ids = 0
    private var runs = 0

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(app))
        dao = MemoryGraph.get(app).db.canvasDao()
        repo = CanvasRepository(dao, { "seed {{recent_context}}" }, { now++ }, { "p-${++ids}" })
        ai = TestAi.graph(app) { """{"facts":[],"captions":[]}""" }.also { TestAi.bindAll(it) }
        runBlocking { repo.ensureBuiltIns() }
    }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null) }

    private val db get() = MemoryGraph.get(app).db
    private var txCount = 0
    private fun publisher() = CanvasPublisher(dao, ai.prompts, { k, d -> compat(k, d) }, { now++ }, { body -> txCount++; CanvasPublisher.inTransaction(db)(body) })
    private fun schema() = DefaultSchemas.text(PromptKey.VISION_EXTRACT)

    private suspend fun recordRun(hash: String, nodeId: String) {
        val runId = "r${++runs}"
        dao.insertRun(CanvasRunEntity(runId, "x", 1, hash, "{}", "DONE", 1, 1, 1, 0, 1, 2, "[]"))
        dao.upsertItem(CanvasRunItemEntity(runId, "f1", nodeId, "OK", null, null, null, 1, null, null, null, null, null))
    }

    /** A frozen copy of the built-in intent-draft pipeline; [ran] gives every AI node a successful run. */
    private suspend fun intentVersion(ran: Boolean = true, edit: (Pipeline) -> Pipeline = { it }): Pair<String, Int> {
        val id = repo.duplicate("intent-draft", null, "Mine")
        val p = edit(PipelineJson.decode(repo.get(id)!!.draftJson).getOrThrow())
        repo.saveDraft(id, PipelineJson.encode(p), 0)
        val v = repo.freeze(id, "n")
        if (ran) com.rokid.context.ai.canvas.InterpretableShape.preSaveNodes(p)
            .filter { it.type == NodeTypes.PROMPT || it.type == NodeTypes.VISION_EXTRACT }.forEach { recordRun(v.hash, it.id) }
        return id to v.version
    }

    private suspend fun liveShaped(template: String): Pair<String, Int> {
        val id = repo.duplicate("live-extract", null, "Live")
        val base = DefaultPipelines.liveExtract()
        val edited = base.copy(id = id, name = "Live", nodes = base.nodes.map {
            if (it.id == "extract") it.copy(params = buildJsonObject { put("batch", 3); putJsonObject("prompt") { put("source", "draft"); put("template", template); put("schema", schema()) } }) else it
        })
        repo.saveDraft(id, PipelineJson.encode(edited), 0)
        val v = repo.freeze(id, "n"); recordRun(v.hash, "extract")
        return id to v.version
    }

    @Test fun blockedNotInterpretable() = runBlocking<Unit> {
        // freeze refuses invalid pipelines, so a hand-written version row stands for one the validator rejects (an unwired switch branch)
        val p = DefaultPipelines.intentDraft("EXTRACT {{recent_context}}").let { q -> q.copy(id = "bad", edges = q.edges.filterNot { it.`when` == NodeTypes.OTHER }) }
        dao.insertVersion(com.rokid.context.memory.db.CanvasPipelineVersionEntity("bad", 1, PipelineJson.encode(p), "hbad", "n", 1))
        val out = publisher().publishInterpreter("bad", 1, LiveMode.LIVE) as CanvasPublisher.Outcome.Blocked
        assertEquals(listOf("Fix the pipeline's problems first"), out.reasons)
        assertTrue(dao.activePublishes().isEmpty())
    }

    @Test fun blockedUntested() = runBlocking<Unit> {
        val (id, v) = intentVersion(ran = false)
        val out = publisher().publishInterpreter(id, v, LiveMode.LIVE) as CanvasPublisher.Outcome.Blocked
        assertEquals(1, out.reasons.size)
        assertTrue(out.reasons.single().startsWith("Run this version first: intent, ") && out.reasons.single().endsWith(" have no successful run"))
        assertTrue(dao.activePublishes().isEmpty())
    }

    @Test fun blockedCompat() = runBlocking<Unit> {
        val (id, v) = intentVersion()
        compat = { _, _ -> listOf("missing field") }
        val out = publisher().publishInterpreter(id, v, LiveMode.SHADOW) as CanvasPublisher.Outcome.Blocked
        assertTrue(out.reasons.isNotEmpty() && out.reasons.all { it == "Facts from frames: missing field" })
        assertTrue(dao.activePublishes().isEmpty())
    }

    @Test fun versionNotFound() = runBlocking<Unit> {
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("Version not found")), publisher().publishInterpreter("nope", 1, LiveMode.LIVE))
    }

    @Test fun publishShadowThenLive() = runBlocking<Unit> {
        val before = ai.prompts.active(PromptKey.VISION_EXTRACT)
        val (id, v) = intentVersion()
        val s = publisher().publishInterpreter(id, v, LiveMode.SHADOW) as CanvasPublisher.Outcome.Published
        val row = dao.activeInterpreter()!!
        assertEquals(s.publishId, row.id); assertEquals("interpreter", row.kind); assertEquals("SHADOW", row.mode); assertEquals(id, row.pipelineId); assertEquals(v, row.version)
        assertTrue(row.snapshotJson.contains("\"pipelineId\"") && row.snapshotJson.contains("\"hash\""))
        val l = publisher().publishInterpreter(id, v, LiveMode.LIVE) as CanvasPublisher.Outcome.Published
        assertEquals(l.publishId, dao.activeInterpreter()!!.id); assertEquals("LIVE", dao.activeInterpreter()!!.mode)
        assertEquals("an interpreter publish writes no prompts", before, ai.prompts.active(PromptKey.VISION_EXTRACT))
    }

    @Test fun rollbackUndoesNewestEitherKind() = runBlocking<Unit> {
        val (pid, pv) = liveShaped("EDITED {{recent_context}}")
        val promptPub = publisher().publish(pid, pv) as CanvasPublisher.Outcome.Published
        val (iid, iv) = intentVersion()
        val interp = publisher().publishInterpreter(iid, iv, LiveMode.LIVE) as CanvasPublisher.Outcome.Published
        // newest first: the interpreter row; its prompts are untouched
        assertEquals(interp.publishId, (publisher().rollback() as CanvasPublisher.Outcome.Published).publishId)
        assertNull(dao.activeInterpreter())
        assertEquals("EDITED {{recent_context}}", ai.prompts.active(PromptKey.VISION_EXTRACT).template)
        // then the prompts publish
        assertEquals(promptPub.publishId, (publisher().rollback() as CanvasPublisher.Outcome.Published).publishId)
        assertFalse(ai.prompts.active(PromptKey.VISION_EXTRACT).template == "EDITED {{recent_context}}")
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("Nothing to roll back")), publisher().rollback())
        assertEquals(listOf(true, true), dao.publishHistory(10).map { it.rolledBackAt != null })
    }

    @Test fun rollbackOfShadowThenNothingActive() = runBlocking<Unit> {
        val (id, v) = intentVersion()
        publisher().publishInterpreter(id, v, LiveMode.SHADOW)
        publisher().publishInterpreter(id, v, LiveMode.LIVE)
        publisher().rollback()
        assertEquals("SHADOW", dao.activeInterpreter()!!.mode)
        publisher().rollback()
        assertNull(dao.activeInterpreter())
    }

    @Test fun promptPublishStillWorks() = runBlocking<Unit> {
        val (id, v) = liveShaped("EDITED {{recent_context}}")
        val out = publisher().publish(id, v) as CanvasPublisher.Outcome.Published
        assertEquals("EDITED {{recent_context}}", ai.prompts.active(PromptKey.VISION_EXTRACT).template)
        assertEquals("prompts", dao.activePublishes().single().kind)
        assertNull(dao.activeInterpreter())
        assertEquals(out.publishId, (publisher().rollback() as CanvasPublisher.Outcome.Published).publishId)
    }

    @Test fun autoRollbackSetsReason() = runBlocking<Unit> {
        val (id, v) = intentVersion()
        val pub = publisher().publishInterpreter(id, v, LiveMode.LIVE) as CanvasPublisher.Outcome.Published
        assertTrue(publisher().autoRollback(pub.publishId, "auto: 10 of 30 fell back"))
        val row = dao.publishHistory(5).single()
        assertEquals("auto: 10 of 30 fell back", row.rollbackReason); assertTrue(row.rolledBackAt != null)
        assertNull(dao.activeInterpreter())
        assertFalse("already rolled back", publisher().autoRollback(pub.publishId, "again"))
        assertEquals("auto: 10 of 30 fell back", dao.publishHistory(5).single().rollbackReason)
    }

    @Test fun autoRollbackNeverTouchesAPromptsPublish() = runBlocking<Unit> {
        val (id, v) = liveShaped("EDITED {{recent_context}}")
        val pub = publisher().publish(id, v) as CanvasPublisher.Outcome.Published
        assertFalse(publisher().autoRollback(pub.publishId, "auto: x"))
        assertEquals(1, dao.activePublishes().size)
    }

    // ---- Task 9: rollback BY EXPECTED PUBLISH ID (Task 6 review M6) ----

    @Test fun rollbackByExpectedIdUndoesThatPublish() = runBlocking<Unit> {
        val (id, v) = intentVersion()
        publisher().publishInterpreter(id, v, LiveMode.SHADOW)
        val live = publisher().publishInterpreter(id, v, LiveMode.LIVE) as CanvasPublisher.Outcome.Published
        assertEquals(live.publishId, (publisher().rollback(live.publishId) as CanvasPublisher.Outcome.Published).publishId)
        assertEquals("SHADOW", dao.activeInterpreter()!!.mode)
    }

    @Test fun manualClickAfterAnAutoRollbackDoesNotUndoTheOlderPublish() = runBlocking<Unit> {
        val (id, v) = intentVersion()
        val shadow = publisher().publishInterpreter(id, v, LiveMode.SHADOW) as CanvasPublisher.Outcome.Published
        val live = publisher().publishInterpreter(id, v, LiveMode.LIVE) as CanvasPublisher.Outcome.Published
        // the user saw Live as the newest publish; the auto-rollback wins the race
        assertTrue(publisher().autoRollback(live.publishId, "auto: 10 of 30 fell back"))
        val out = publisher().rollback(live.publishId) as CanvasPublisher.Outcome.Blocked
        assertEquals(listOf("That publish was already rolled back."), out.reasons)
        assertEquals("Shadow must stay active", shadow.publishId, dao.activeInterpreter()!!.id)
        assertEquals("auto: 10 of 30 fell back", dao.publishHistory(5).first { it.id == live.publishId }.rollbackReason)
    }

    @Test fun expectedIdThatIsNotTheNewestIsRefusedAndNothingChanges() = runBlocking<Unit> {
        val (id, v) = intentVersion()
        val shadow = publisher().publishInterpreter(id, v, LiveMode.SHADOW) as CanvasPublisher.Outcome.Published
        publisher().publishInterpreter(id, v, LiveMode.LIVE)
        val out = publisher().rollback(shadow.publishId) as CanvasPublisher.Outcome.Blocked
        assertEquals(listOf("A newer publish exists. Reload and try again."), out.reasons)
        assertEquals(2, dao.activePublishes().size)
    }

    @Test fun expectedIdOfAPromptsPublishUnderANewerInterpreterRowIsRefused() = runBlocking<Unit> {
        val (pid, pv) = liveShaped("EDITED {{recent_context}}")
        val promptPub = publisher().publish(pid, pv) as CanvasPublisher.Outcome.Published
        val (iid, iv) = intentVersion()
        publisher().publishInterpreter(iid, iv, LiveMode.SHADOW)
        assertTrue(publisher().rollback(promptPub.publishId) is CanvasPublisher.Outcome.Blocked)
        assertEquals("EDITED {{recent_context}}", ai.prompts.active(PromptKey.VISION_EXTRACT).template)
    }

    @Test fun unknownExpectedIdIsAlreadyRolledBack() = runBlocking<Unit> {
        val out = publisher().rollback(999L) as CanvasPublisher.Outcome.Blocked
        assertEquals(listOf("That publish was already rolled back."), out.reasons)
    }

    @Test fun noExpectedIdKeepsTheOldBehaviour() = runBlocking<Unit> {
        val (id, v) = intentVersion()
        val p = publisher().publishInterpreter(id, v, LiveMode.LIVE) as CanvasPublisher.Outcome.Published
        assertEquals(p.publishId, (publisher().rollback() as CanvasPublisher.Outcome.Published).publishId)
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("Nothing to roll back")), publisher().rollback())
    }
}
