package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.ModelImageInput
import com.rokid.context.orchestration.TestAi
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.prompt.PromptRepository
import com.rokid.context.prompt.PromptVars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CanvasPublisherTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dao: CanvasDao
    private lateinit var repo: CanvasRepository
    private lateinit var ai: AiGraph
    private val sent = mutableListOf<String>()
    private var compat: (PromptKey, PromptDraft) -> List<String> = { _, _ -> emptyList() }
    private var now = 1_000L
    private var ids = 0
    private var runs = 0

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(app))
        dao = MemoryGraph.get(app).db.canvasDao()
        repo = CanvasRepository(dao, { "seed {{recent_context}}" }, { now++ }, { "p-${++ids}" })
        ai = TestAi.graph(app) { p -> sent += p; """{"facts":[],"captions":[]}""" }.also { TestAi.bindAll(it) }
        runBlocking { repo.ensureBuiltIns() }
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null) }

    private val prompts: PromptRepository get() = ai.prompts
    private var inTx = false
    private val db get() = MemoryGraph.get(app).db
    private val txRunner: suspend (suspend () -> Unit) -> Unit = { body -> CanvasPublisher.inTransaction(db)({ inTx = true; try { body() } finally { inTx = false } }) }
    private fun publisher() = CanvasPublisher(dao, prompts, { k, d -> compat(k, d) }, { now++ }, txRunner)
    private fun schema() = DefaultSchemas.text(PromptKey.VISION_EXTRACT)

    private fun draftParams(template: String) = buildJsonObject {
        put("batch", 3)
        putJsonObject("prompt") { put("source", "draft"); put("template", template); put("schema", schema()) }
    }

    /** A frozen copy of Live extraction whose "extract" prompt is [template] (null: every prompt stays Live). */
    private suspend fun frozen(template: String?, ran: Boolean = true): Pair<String, Int> {
        val id = repo.duplicate("live-extract", null, "Mine")
        val base = DefaultPipelines.liveExtract()
        val edited = if (template == null) base else base.copy(nodes = base.nodes.map { if (it.id == "extract") it.copy(params = draftParams(template)) else it })
        repo.saveDraft(id, PipelineJson.encode(edited.copy(id = id, name = "Mine")), 0)
        val v = repo.freeze(id, "n")
        if (ran) recordRun(v.hash, "extract")
        return id to v.version
    }

    private suspend fun recordRun(hash: String, nodeId: String) {
        val runId = "r${++runs}"
        dao.insertRun(CanvasRunEntity(runId, "x", 1, hash, "{}", "DONE", 1, 1, 1, 0, 1, 2, "[]"))
        dao.upsertItem(CanvasRunItemEntity(runId, "f1", nodeId, "OK", null, null, null, 1, null, null, null, null, null))
    }

    private fun both(body: suspend (keep: Boolean) -> Unit) = runBlocking { listOf(true, false).forEach { keep ->
        ai.settings.prompts.setKeepVersions(keep); body(keep)
        tearDown(); setUp()
    } }

    private suspend fun activeText() = prompts.active(PromptKey.VISION_EXTRACT).template

    @Test fun publishBlockedNotLiveShaped() = both {
        val id = repo.duplicate("intent-draft", null, "I")
        val v = repo.freeze(id, "n")
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("Only pipelines shaped like Live extraction can go live in this version")), publisher().publish(id, v.version))
        assertTrue(dao.activePublishes().isEmpty())
    }

    @Test fun publishBlockedNothingDraft() = both {
        val (id, v) = frozen(null)
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("Nothing to publish: every prompt is Live")), publisher().publish(id, v))
    }

    @Test fun publishBlockedUntested() = both {
        val (id, v) = frozen("EDITED {{recent_context}}", ran = false)
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("Run this version first: extract have no successful run")), publisher().publish(id, v))
        assertTrue(dao.activePublishes().isEmpty())
    }

    @Test fun publishBlockedCompat() = both {
        val (id, v) = frozen("EDITED {{recent_context}}")
        compat = { _, _ -> listOf("missing field") }
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("Facts from frames: missing field")), publisher().publish(id, v))
        assertTrue(dao.activePublishes().isEmpty())
    }

    @Test fun publishSavesDraftPromptAndSnapshot() = both { keep ->
        val before = prompts.active(PromptKey.VISION_EXTRACT)
        val (id, v) = frozen("EDITED {{recent_context}}")
        val out = publisher().publish(id, v) as CanvasPublisher.Outcome.Published
        assertEquals(listOf(PromptKey.VISION_EXTRACT), out.keys)
        val now = prompts.active(PromptKey.VISION_EXTRACT)
        assertEquals("EDITED {{recent_context}}", now.template)
        assertEquals(schema(), now.outputSchema)
        assertTrue(now.testedOk); assertEquals("Canvas: Mine v$v", now.note)
        assertEquals(if (keep) before.version + 1 else before.version, now.version)
        val pub = dao.activePublishes().single()
        assertEquals(out.publishId, pub.id); assertEquals(id, pub.pipelineId); assertEquals(v, pub.version)
        assertTrue(pub.snapshotJson.contains("\"prev\"") && pub.snapshotJson.contains("\"fp\""))
    }

    @Test fun liveRunnerSeesPublishedPrompt() = both {
        val (id, v) = frozen("PUBLISHED-TEXT {{recent_context}}")
        publisher().publish(id, v)
        sent.clear()
        runCatching { ai.runner.run(RoleCall(PromptKey.VISION_EXTRACT, PromptVars(recentContext = "ctx"), images = listOf(ModelImageInput("image/jpeg", "AAAA")))) }
        assertTrue(sent.isNotEmpty() && sent.all { it.contains("PUBLISHED-TEXT") })
    }

    @Test fun rollbackRestoresPrevious() = both { keep ->
        val before = prompts.active(PromptKey.VISION_EXTRACT)
        val (id, v) = frozen("EDITED {{recent_context}}")
        val pub = publisher().publish(id, v) as CanvasPublisher.Outcome.Published
        val undone = publisher().rollback()
        assertEquals(CanvasPublisher.Outcome.Published(pub.publishId, listOf(PromptKey.VISION_EXTRACT)), undone)
        val a = prompts.active(PromptKey.VISION_EXTRACT)
        assertEquals(before.template, a.template); assertEquals(before.outputSchema, a.outputSchema)
        assertEquals("Canvas rollback", a.note)
        assertTrue(dao.activePublishes().isEmpty())
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("Nothing to roll back")), publisher().rollback())
        if (keep) assertEquals(before.version + 2, a.version)
    }

    @Test fun rollbackRefusedWhenPromptChangedSincePublish() = both {
        val (id, v) = frozen("EDITED {{recent_context}}")
        publisher().publish(id, v)
        prompts.save(PromptKey.VISION_EXTRACT, PromptDraft("MANUAL {{recent_context}}", schema()), "by hand", true)
        val out = publisher().rollback()
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("Facts from frames was changed after the publish. Use Settings → Prompts → History instead.")), out)
        assertEquals("MANUAL {{recent_context}}", activeText())
        assertEquals(1, dao.activePublishes().size)
    }

    @Test fun stackedPublishesRollBackNewestFirst() = both {
        val original = activeText()
        val (id1, v1) = frozen("FIRST {{recent_context}}")
        val (id2, v2) = frozen("SECOND {{recent_context}}")
        val p1 = publisher().publish(id1, v1) as CanvasPublisher.Outcome.Published
        val p2 = publisher().publish(id2, v2) as CanvasPublisher.Outcome.Published
        assertEquals("SECOND {{recent_context}}", activeText())
        assertEquals(p2.publishId, (publisher().rollback() as CanvasPublisher.Outcome.Published).publishId)
        assertEquals("FIRST {{recent_context}}", activeText())
        assertEquals(p1.publishId, (publisher().rollback() as CanvasPublisher.Outcome.Published).publishId)
        assertEquals(original, activeText())
    }

    @Test fun versioningOffRollbackStillRestoresText() = runBlocking<Unit> {
        ai.settings.prompts.setKeepVersions(false)
        val before = prompts.active(PromptKey.VISION_EXTRACT)
        val (id, v) = frozen("EDITED {{recent_context}}")
        publisher().publish(id, v)
        assertEquals(before.version, prompts.active(PromptKey.VISION_EXTRACT).version) // rewritten in place
        assertTrue(publisher().rollback() is CanvasPublisher.Outcome.Published)
        assertEquals(before.template, activeText())
        assertEquals(before.version, prompts.active(PromptKey.VISION_EXTRACT).version)
    }

    @Test fun concurrentPublishesKeepOneRollbackChain() = both { coroutineScope {
        val original = activeText()
        val (id1, v1) = frozen("FIRST {{recent_context}}")
        val (id2, v2) = frozen("SECOND {{recent_context}}")
        val p = publisher()
        val outs = listOf(async(Dispatchers.Default) { p.publish(id1, v1) }, async(Dispatchers.Default) { p.publish(id2, v2) }).awaitAll()
        assertTrue(outs.all { it is CanvasPublisher.Outcome.Published })
        assertEquals(2, dao.activePublishes().size)
        assertTrue(p.rollback() is CanvasPublisher.Outcome.Published)
        assertTrue(p.rollback() is CanvasPublisher.Outcome.Published)
        assertEquals(original, activeText())
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("Nothing to roll back")), p.rollback())
    } }

    @Test fun rollbackAfterConcurrentOutsideEditNeverClobbers() = both { coroutineScope {
        val (id, v) = frozen("EDITED {{recent_context}}")
        val p = publisher()
        p.publish(id, v)
        val edit = async(Dispatchers.Default) { prompts.save(PromptKey.VISION_EXTRACT, PromptDraft("MANUAL {{recent_context}}", schema()), "by hand", true) }
        val back = async(Dispatchers.Default) { p.rollback() }
        edit.await(); val out = back.await()
        // the manual edit is either made before the rollback (refused) or after it (applied last): it is never lost
        assertEquals("MANUAL {{recent_context}}", activeText())
        if (out is CanvasPublisher.Outcome.Blocked) assertEquals(1, dao.activePublishes().size)
    } }

    @Test fun rollbackMultiKeyAllOrNothing() = both {
        val criticSchema = DefaultSchemas.text(PromptKey.CRITIC)
        val id = repo.duplicate("live-extract", null, "Two")
        val base = DefaultPipelines.liveExtract()
        val edited = base.copy(id = id, name = "Two", nodes = base.nodes.map {
            when (it.id) {
                "extract" -> it.copy(params = draftParams("EDITED {{recent_context}}"))
                "critic" -> it.copy(params = buildJsonObject { putJsonObject("prompt") { put("source", "draft"); put("template", "CRITIC-EDIT {{recent_context}}"); put("schema", criticSchema) } })
                else -> it
            }
        })
        repo.saveDraft(id, PipelineJson.encode(edited), 0)
        val v = repo.freeze(id, "n")
        recordRun(v.hash, "extract"); recordRun(v.hash, "critic")
        val out = publisher().publish(id, v.version) as CanvasPublisher.Outcome.Published
        assertEquals(2, out.keys.size)
        // the SECOND key (critic) is edited by hand after the publish
        val second = PromptKey.CRITIC
        prompts.save(second, PromptDraft("MANUAL {{recent_context}}", prompts.active(second).outputSchema), "by hand", true)
        val firstKey = PromptKey.VISION_EXTRACT
        assertEquals(listOf(firstKey, second), out.keys)
        val firstBefore = prompts.active(firstKey)
        val firstHistory = prompts.history(firstKey).first().size
        val res = publisher().rollback()
        assertTrue(res is CanvasPublisher.Outcome.Blocked)
        assertEquals("MANUAL {{recent_context}}", prompts.active(second).template)
        assertEquals(firstBefore, prompts.active(firstKey)) // the first key was not rolled back
        assertEquals(firstHistory, prompts.history(firstKey).first().size)
        assertEquals(1, dao.activePublishes().size)
    }

    @Test fun publishBlockedWhenEditedDuringPublish() = both {
        val (id, v) = frozen("EDITED {{recent_context}}")
        compat = { k, d ->
            runBlocking { prompts.save(k, PromptDraft("RACE {{recent_context}}", d.outputSchema), "race", true) }
            emptyList()
        }
        val out = publisher().publish(id, v)
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("Facts from frames was changed during publish. Try again.")), out)
        assertEquals("RACE {{recent_context}}", activeText())
        assertTrue(dao.activePublishes().isEmpty())
    }

    @Test fun rollbackRefusesEditLandingBetweenCheckAndCas() = both {
        val criticSchema = DefaultSchemas.text(PromptKey.CRITIC)
        val id = repo.duplicate("live-extract", null, "Two")
        val base = DefaultPipelines.liveExtract()
        val edited = base.copy(id = id, name = "Two", nodes = base.nodes.map {
            when (it.id) {
                "extract" -> it.copy(params = draftParams("EDITED {{recent_context}}"))
                "critic" -> it.copy(params = buildJsonObject { putJsonObject("prompt") { put("source", "draft"); put("template", "CRITIC-EDIT {{recent_context}}"); put("schema", criticSchema) } })
                else -> it
            }
        })
        repo.saveDraft(id, PipelineJson.encode(edited), 0)
        val v = repo.freeze(id, "n")
        recordRun(v.hash, "extract"); recordRun(v.hash, "critic")
        publisher().publish(id, v.version)
        var armed = false; var criticReads = 0
        // PromptRepository.active() consults previousDefaults[key] on every read: counting those reads lets an outside
        // edit land exactly after the rollback's fingerprint-check read (read 1) and before whatever reads next (read 2)
        val reads = object : AbstractMap<PromptKey, Set<String>>() {
            override val entries: Set<Map.Entry<PromptKey, Set<String>>> = emptySet()
            override fun get(key: PromptKey): Set<String>? {
                if (armed && key == PromptKey.CRITIC && ++criticReads == 2)
                    runBlocking { prompts.save(PromptKey.CRITIC, PromptDraft("Y {{recent_context}}", prompts.active(PromptKey.CRITIC).outputSchema), "outside", true) }
                return null
            }
        }
        val hooked = PromptRepository(MemoryGraph.get(app).db.promptDao(), com.rokid.context.prompt.BuiltInPromptSeeds, ai.settings.prompts, reads) { now++ }
        val firstBefore = prompts.active(PromptKey.VISION_EXTRACT)
        val firstHistory = prompts.history(PromptKey.VISION_EXTRACT).first().size
        armed = true
        val out = // no transaction here on purpose: inside one an outside writer waits for the commit, so this test would deadlock.
        // It checks the CAS alone; the transaction closes the window anyway (legacyFingerprintCheckedInTransaction).
        CanvasPublisher(dao, hooked, { _, _ -> emptyList() }, { now++ }, { body -> body() }).rollback()
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("Critic was changed after the publish. Use Settings \u2192 Prompts \u2192 History instead.")), out)
        assertEquals("Y {{recent_context}}", prompts.active(PromptKey.CRITIC).template) // the outside edit survives
        assertEquals(firstBefore, prompts.active(PromptKey.VISION_EXTRACT)) // all-or-nothing: first key untouched
        assertEquals(firstHistory, prompts.history(PromptKey.VISION_EXTRACT).first().size)
        assertEquals(1, dao.activePublishes().size)
    }

    private fun failOn(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    @Test fun insertPublishFailureRollsBackPrompts() = both {
        val before = prompts.active(PromptKey.VISION_EXTRACT)
        val historyBefore = prompts.history(PromptKey.VISION_EXTRACT).first().size
        val (id, v) = frozen("EDITED {{recent_context}}")
        failOn("CREATE TRIGGER no_publish BEFORE INSERT ON canvas_publish BEGIN SELECT RAISE(ABORT, 'boom'); END")
        val out = publisher().publish(id, v)
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("Publishing failed. Nothing was changed.")), out)
        assertEquals(before, prompts.active(PromptKey.VISION_EXTRACT))
        assertEquals(historyBefore, prompts.history(PromptKey.VISION_EXTRACT).first().size)
        assertTrue(dao.activePublishes().isEmpty())
    }

    @Test fun markRolledBackFailureRestoresNothing() = both {
        val (id, v) = frozen("EDITED {{recent_context}}")
        publisher().publish(id, v)
        val published = prompts.active(PromptKey.VISION_EXTRACT)
        val historyBefore = prompts.history(PromptKey.VISION_EXTRACT).first().size
        failOn("CREATE TRIGGER no_mark BEFORE UPDATE ON canvas_publish BEGIN SELECT RAISE(ABORT, 'boom'); END")
        val out = publisher().rollback()
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("Rolling back failed. Nothing was changed.")), out)
        assertEquals(published, prompts.active(PromptKey.VISION_EXTRACT))
        assertEquals(historyBefore, prompts.history(PromptKey.VISION_EXTRACT).first().size)
        assertEquals(1, dao.activePublishes().size)
    }

    @Test fun legacyFingerprintCheckedInTransaction() = both {
        val (id, v) = frozen("EDITED {{recent_context}}")
        publisher().publish(id, v)
        var armed = false; val reads = mutableListOf<Boolean>()
        val spy = object : AbstractMap<PromptKey, Set<String>>() {
            override val entries: Set<Map.Entry<PromptKey, Set<String>>> = emptySet()
            override fun get(key: PromptKey): Set<String>? { if (armed) reads += inTx; return null }
        }
        val hooked = PromptRepository(MemoryGraph.get(app).db.promptDao(), com.rokid.context.prompt.BuiltInPromptSeeds, ai.settings.prompts, spy) { now++ }
        armed = true
        assertTrue(CanvasPublisher(dao, hooked, { _, _ -> emptyList() }, { now++ }, txRunner).rollback() is CanvasPublisher.Outcome.Published)
        assertTrue(reads.isNotEmpty() && reads.all { it })
    }
}
