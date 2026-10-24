package com.rokid.context.phone.canvas.api

import com.rokid.context.ai.canvas.plan.ExprLowering
import com.rokid.context.ai.canvas.plan.ExprRole
import com.rokid.context.capture.plan.ExprRunner
import com.rokid.context.capture.plan.ExprSignals
import com.rokid.context.core.model.context.ContextCapturePolicy
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.model.SecondFeatures
import com.rokid.context.phone.canvas.server.HttpRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** X8: the try-it answers what the glasses' own [ExprRunner] answers (keep / tune), over seeded inputs, windows and value programs. */
class TryItMatchesRunnerTest {
    companion object {
        const val FAST = """{"steps":[{"name":"fast","inputs":[{"name":"speed","sig":"headSpeed"}],"nodes":[{"id":"limit","op":"const","value":1.5},{"id":"over","op":"cmp","f":">"}],"wires":[["speed","over.a"],["limit","over.b"]],"out":"over"}],"out":"fast"}"""
        /** mean stepHz over 10 s below 1.2 */
        const val CALM = """{"steps":[{"name":"calm","inputs":[],"nodes":[{"id":"w","op":"win","name":"stepHz","agg":"mean","sec":10},{"id":"k","op":"const","value":1.2},{"id":"c","op":"cmp","f":"<"}],"wires":[["w","c.a"],["k","c.b"]],"out":"c"}],"out":"calm"}"""
        /** gyroMax * 2 (and a second step on top of it) */
        const val TWICE = """{"steps":[{"name":"twice","inputs":[{"name":"g","sig":"gyroMax"},{"name":"two","const":2}],"nodes":[{"id":"m","op":"arith","f":"*"}],"wires":[["g","m.a"],["two","m.b"]],"out":"m"},{"name":"cap","inputs":[{"name":"t","step":"twice"}],"nodes":[{"id":"c","op":"clamp","lo":1,"hi":4}],"wires":[["t","c.a"]],"out":"c"}],"out":"cap"}"""
        const val DIV0 = """{"steps":[{"name":"d","inputs":[{"name":"g","sig":"gyroMax"},{"name":"z","const":0}],"nodes":[{"id":"q","op":"arith","f":"/"},{"id":"k","op":"const","value":1},{"id":"c","op":"cmp","f":">"}],"wires":[["g","q.a"],["z","q.b"],["q","c.a"],["k","c.b"]],"out":"c"}],"out":"d"}"""
    }

    private val routes = ExprRoutes()
    private fun post(body: String) = runBlocking { routes.handle(HttpRequest("POST", "/api/expr/try", emptyMap(), emptyMap(), body.toByteArray()))!! }
    private fun ask(graph: String, signals: String, extra: String = ""): JsonObject {
        val r = post("""{"graph":$graph,"signals":$signals$extra}""")
        assertEquals(String(r.body), 200, r.status)
        return Json.parseToJsonElement(String(r.body)).jsonObject
    }
    private fun lowered(graph: String, role: ExprRole = ExprRole.GUARD, row: String? = null, field: String? = null) =
        ExprRunner.of(ExprLowering.lowerAll(listOf(ExprLowering.Entry("try", Json.parseToJsonElement(graph), role, row, field))).also { assertEquals(emptyList<String>(), it.problems) }.exprs)!!
    private fun features(g: Double = 0.0, step: Double = 0.0) = SecondFeatures(g.toFloat(), 0f, 0f, 0f, 0f, step.toFloat(), 0f)

    @Test fun aGuardVerdictIsTheRunnersKeepOverSeededInputs() {
        val rnd = Random(94)
        repeat(60) {
            val g = Math.round(rnd.nextDouble() * 400) / 100.0
            val runner = lowered(FAST)
            val sig = ExprSignals(); runner.bind(sig); sig.set(features(g), 1_000L); runner.tick(sig)
            val keep = runner.keep(1_000L, sig, ContextState.REST, 0)
            val r = ask(FAST, """{"gyroMax":$g}""")
            assertEquals("gyroMax=$g", if (keep) "keep" else "drop", r["verdict"]!!.jsonPrimitive.content)
            assertEquals(if (keep) "true" else "false", r["output"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        }
    }

    @Test fun aWindowOverAListOfSecondsIsTheRunnersWindowAndAShortListIsNotReady() {
        val rnd = Random(7)
        for (n in listOf(10, 11, 25, 300)) repeat(5) {
            val xs = List(n) { Math.round(rnd.nextDouble() * 300) / 100.0 }
            val runner = lowered(CALM)
            val sig = ExprSignals(); runner.bind(sig)
            xs.forEachIndexed { t, v -> sig.set(features(step = v), 1_000L + t * 1000L); runner.tick(sig) }
            val keep = runner.keep(1_000L + (n - 1) * 1000L, sig, ContextState.REST, 0)
            val r = ask(CALM, """{"stepHz":$xs}""")
            assertEquals("n=$n $xs", if (keep) "keep" else "drop", r["verdict"]!!.jsonPrimitive.content)
        }
        val short = ask(CALM, """{"stepHz":[0,0,0,0,0]}""")
        assertEquals("NOT_READY", short["output"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals("keep", short["verdict"]!!.jsonPrimitive.content)   // a warming window keeps the candidate, as on the device
    }

    @Test fun aConstantFeedsAFullWindow() {
        val r = ask(CALM, """{"stepHz":0.5}""")
        assertEquals("true", r["output"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(10, r["seconds"]!!.jsonPrimitive.content.toInt())
        assertTrue(r["windowStateBytes"]!!.jsonPrimitive.content.toInt() > 0)
    }

    @Test fun aValueProgramIsTheRunnersTunedRowClampedAndEveryStepIsShown() {
        val rnd = Random(3)
        repeat(40) {
            val g = Math.round(rnd.nextDouble() * 500) / 100.0
            val runner = lowered(TWICE, ExprRole.VALUE, "REST", "capPerMin")
            val sig = ExprSignals(); runner.bind(sig); sig.set(features(g), 1_000L); runner.tick(sig)
            runner.tickValues(1_000L, sig, ContextState.REST, 0)
            val cap = runner.tune(ContextState.REST, ContextCapturePolicy(null, 0, 0, 0, 0, false, false, true)).capPerMin!!
            val r = ask(TWICE, """{"gyroMax":$g}""", ""","role":"value","row":"REST","field":"capPerMin"""")
            assertEquals("g=$g", cap.toDouble(), r["applied"]!!.jsonPrimitive.content.toDouble(), 0.0)
            val steps = r["steps"]!!.toString()
            assertTrue(steps, steps.contains("\"twice\"") && steps.contains("\"cap\""))
        }
        val gap = ask(TWICE, """{"gyroMax":2}""", ""","role":"value","row":"REST","field":"settledGapS"""")
        assertEquals(5.0, gap["applied"]!!.jsonPrimitive.content.toDouble(), 0.0)   // 2*2 = 4 s is clamped up to the 5 s floor, like the row
    }

    @Test fun errorsAndLabelsAreNamed() {
        assertEquals("DIV0", ask(DIV0, """{"gyroMax":1}""")["output"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        val bad = ask("""{"steps":[],"out":"x"}""", """{"gyroMax":1}""")
        assertEquals("false", bad["ok"]!!.jsonPrimitive.content)
        assertTrue(bad["problems"]!!.toString().contains("EXPR"))
    }
}
