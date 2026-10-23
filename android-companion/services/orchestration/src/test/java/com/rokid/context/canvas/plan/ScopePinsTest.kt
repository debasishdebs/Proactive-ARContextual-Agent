package com.rokid.context.canvas.plan

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.plan.PlanCompiler
import com.rokid.context.core.model.PlanMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * WP7 (part B P17): pins only, no new behaviour. Every deferral of the 3b CORE release is a test that fails when a later task lifts it silently: LIVE (3a Task 25 absent), fusion tunables
 * (Task 13 absent), sensing untouched by ASYNC nodes (S84, D12), the pulled-trace import as the only trace source (Tasks 17/18 absent), no Python in sweeps or replays (D10).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScopePinsTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private fun mainSources() = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }.filter { it.extension == "kt" && "/src/main/" in it.path }.toList()
    private fun src(name: String) = mainSources().single { it.name == name }.readText()

    // ---- LIVE ----

    /** Even with a current replay AND the M1 record, an unrestricted LIVE plan is refused in core (the LA9 seam is not built); nothing is sent or recorded. */
    @Test fun liveModeRefusedWithoutTask25() {
        val h = PlanHarness(ApplicationProvider.getApplicationContext())
        try {
            val p = PlanTestFixtures.captureHalf("Quiet")
            val (id, v) = h.frozen(p, ran = false)
            h.addReplay(h.compileHash(p)); runBlocking { h.m1.record(M1Evidence(h.now, "worn run")) }
            val o = runBlocking { h.plans.publish(id, v, PlanMode.LIVE) } as PlanOutcome.Blocked
            assertEquals(listOf(PlanEvidenceGates.G4_LIVE), o.reasons)
            assertTrue(h.sent.isEmpty()); assertTrue(runBlocking { h.canvas.capturePlanPublishes() }.isEmpty())
        } finally { h.close() }
    }

    /** The debug route accepts SHADOW only (grep of its source: the other modes are named only as refusals). */
    @Test fun debugRouteStaysShadowOnly() {
        val s = src("PlanRoutes.kt")
        assertTrue("the SHADOW-only rule is still in the route", Regex("""debug-shadow""").containsMatchIn(s))
        assertFalse("no route text publishes LIVE directly", Regex("""PlanMode\.LIVE""").containsMatchIn(s.substringAfter("debug-shadow")))
    }

    // ---- fusion tunables ----

    @Test fun fusionTunablesRefusedWithoutTask13() {
        val g = PlanTestFixtures.captureHalf("Fusion")
        val withParam = g.copy(nodes = g.nodes.map { if (it.type == NodeTypes.HUB_CONTEXT) it.copy(params = buildJsonObject { put("fastKmh", 30) }) else it })
        val r = PlanCompiler.compile(withParam)
        assertTrue(r.problems.toString(), PlanCompiler.HUB_PARAMS in r.problems)
        assertEquals(null, r.plan)
        assertNotNull("the same graph without the param compiles", PlanCompiler.compile(g).plan)
    }

    // ---- sensing (S84, D12) ----

    /** An ASYNC node of any kind (py.fn included) changes nothing the capture plan carries: same compile, same configHash. */
    @Test fun captureConfigHashUnchangedByAsyncNodes() {
        val full = PlanTestFixtures.full("Full")
        val base = PlanCompiler.compile(full)
        val withPy = PlanCompiler.compile(full.copy(
            nodes = full.nodes + PipelineNode("ask", NodeTypes.PROMPT, "ask", buildJsonObject {
                put("role", "VISION_EXTRACT"); put("name", "ask"); put("images", true); put("template", "ASK {{recent_context}}"); put("schema", """{"type":"object","properties":{"a":{"type":"string"}}}""")
            }) + PipelineNode("fn", "py.fn", "fn", buildJsonObject { put("source", "def run(x): return x"); put("outSchema", "{}") }),
            edges = full.edges + listOf(com.rokid.context.ai.canvas.PipelineEdge("photos", "ask"), com.rokid.context.ai.canvas.PipelineEdge("ask", "fn")),
        ))
        assertNotNull(base.plan)
        assertNotNull(withPy.problems.toString(), withPy.plan)
        assertEquals(base.plan!!.configHash, withPy.plan!!.configHash)
        assertEquals(base.problems, withPy.problems)
    }

    /** The compiler reads RT nodes only: it names no py/tool node type and no ASYNC executor. */
    @Test fun compilerNeverReadsAsyncNodeParams() {
        val s = mainSources().single { it.name == "PlanCompiler.kt" }.readText()
        assertFalse(Regex("""\bpy\.fn\b|PY_FN|"py\.|"tool\.""").containsMatchIn(s))
    }

    // ---- import ----

    /** The pulled-trace import stays the only trace source: no intake class exists, and the import code has no helper or Python reference. */
    @Test fun traceImportStillOnlyTraceSource() {
        val canvasIntake = mainSources().filter { "/canvas/" in it.path && Regex("(?i)intake|collector").containsMatchIn(it.name) }
        assertTrue("no intake or collector class of Tasks 17/18 appeared in the canvas code: $canvasIntake", canvasIntake.isEmpty())
        val s = src("TraceImport.kt")
        assertFalse(Regex("(?i)helper|py\\.fn|python").containsMatchIn(s))
        assertTrue("the byte bounds are still there", s.contains("PHONE_CAP_BYTES") && s.contains("MAX_FILE_BYTES"))
    }

    // ---- no Python in sweeps or replays ----

    @Test fun noPythonInSweepsOrReplays() {
        for (cls in listOf(SweepService::class.java, ReplayService::class.java, SweepRequest::class.java, ReplayRequest::class.java)) {
            val types = cls.declaredConstructors.flatMap { it.parameterTypes.toList() } + cls.declaredFields.map { it.type }
            assertTrue("${cls.simpleName} holds ${types.map { it.name }}", types.none { it.name.contains("helper", ignoreCase = true) || it.name.contains("Python") || it.name.contains("NodeExecutor") || it.name.contains("PipelineInterpreter") })
        }
        for (f in listOf("SweepService.kt", "ReplayService.kt")) assertFalse(f, Regex("""(?i)helper|py\.fn|python|NodeExecutor|Executors\b""").containsMatchIn(src(f)))
        assertTrue("replay and sweep requests have no graph in them", ReplayRequest::class.java.declaredFields.none { it.type.name.contains("Pipeline") } && SweepRequest::class.java.declaredFields.none { it.type.name.contains("Pipeline") })
    }
}
