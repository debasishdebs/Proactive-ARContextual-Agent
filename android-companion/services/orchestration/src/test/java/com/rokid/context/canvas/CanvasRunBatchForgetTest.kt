package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.model.ModelImageInput
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** S25 through a real experiment run: forgetting one photo removes its own per-photo rows and its extract call's rows, never the rest of the run. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CanvasRunBatchForgetTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tokens = ConfirmTokens({ 1L })

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(app)) }
    @After fun tearDown() { scope.cancel(); AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null) }

    private fun photo(id: String) = ExtractPhoto(id, "ts=1", ModelImageInput("image/jpeg", "AAAA"))
    private fun sent(p: String) = Regex("frame_id: (f\\d)").findAll(p).map { it.groupValues[1] }.toList().distinct()

    private suspend fun runOver(frames: List<String>): String {
        val ai = TestAi.graph(app) { p ->
            if ("Look at the photos" in p) """{"activity":"workout","confidence":0.9,"reason":"x"}"""
            else {
                val ids = sent(p)
                """{"facts":[${ids.joinToString(",") { """{"text":"Mug $it","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$it"]}""" }}],"captions":[${ids.joinToString(",") { """{"frame_id":"$it","caption":"c $it"}""" }}]}"""
            }
        }.also { TestAi.bindAll(it) }
        val dao = MemoryGraph.get(app).db.canvasDao()
        val runner = CanvasRunner(
            dao, { f -> PhotoLoad(f.map(::photo), emptySet()) }, Executors.all(),
            { id -> RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { r -> ai.bindings.get(r) }, liveness = com.rokid.context.orchestration.AllLive) }, tokens, { false }, { 1L }, { "run1" },
        )
        val p = DefaultPipelines.intentDraft("EXTRACT {{recent_context}} {{context_sources}}")
        val est = runner.estimate(p, frames) { null } as Estimate.Ok
        runner.start(p, p.id, null, frames, est.confirmToken, scope)
        withTimeout(30_000) { runner.active.first { it == null } }
        return "run1"
    }

    private suspend fun rows(run: String, frame: String) = MemoryGraph.get(app).db.canvasDao().items(run).filter { it.frameId == frame }

    @Test fun forgettingOnePhotoOfARunLeavesTheOthersPerPhotoRows() = runBlocking<Unit> {
        val run = runOver(listOf("f1", "f2", "f3"))
        val db = MemoryGraph.get(app).db
        fun nodes(f: String) = runBlocking { rows(run, f).map { it.nodeId }.toSet() }
        val before = nodes("f1")
        assertEquals(true, "intent" in before && "extract_workout" in before)
        ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }.forgetMoment("f2")
        assertEquals(0, rows(run, "f2").size)
        // the three photos shared ONE extract call, so its rows go with f2 (S25) AND so do the rows derived from it (the write preview carries the
        // call's text, lineage); only the per-photo rows from before the call (input, intent, route) stay
        for (f in listOf("f1", "f3")) assertEquals(before - setOf("extract_workout", "write"), nodes(f))
        assertEquals(true, db.canvasDao().scores(run).none { it.frameId == "f2" })
    }
}
