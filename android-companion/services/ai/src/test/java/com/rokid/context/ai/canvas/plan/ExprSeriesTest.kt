package com.rokid.context.ai.canvas.plan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.2 row 2: [ExprSeries] runs one expression per recorded second through the SAME lowering and runner as [ExprTry]. */
class ExprSeriesTest {
    private val fast = """{"steps":[{"name":"fast","inputs":[{"name":"speed","sig":"gyroMax"}],"nodes":[{"id":"limit","op":"const","value":1.5},{"id":"over","op":"cmp","f":">"}],"wires":[["speed","over.a"],["limit","over.b"]],"out":"over"}],"out":"fast"}"""
    private val twice = """{"steps":[{"name":"twice","inputs":[{"name":"g","sig":"gyroMax"},{"name":"two","const":2}],"nodes":[{"id":"m","op":"arith","f":"*"}],"wires":[["g","m.a"],["two","m.b"]],"out":"m"}],"out":"twice"}"""
    private val calm = """{"steps":[{"name":"calm","inputs":[],"nodes":[{"id":"w","op":"win","name":"stepHz","agg":"mean","sec":10},{"id":"k","op":"const","value":1.2},{"id":"c","op":"cmp","f":"<"}],"wires":[["w","c.a"],["k","c.b"]],"out":"c"}],"out":"calm"}"""
    private val ctxAge = """{"steps":[{"name":"a","inputs":[{"name":"x","sig":"ctxAgeS"},{"name":"k","const":5}],"nodes":[{"id":"c","op":"cmp","f":">"}],"wires":[["x","c.a"],["k","c.b"]],"out":"c"}],"out":"a"}"""
    private val notNumber = """{"steps":[{"name":"a","inputs":[{"name":"x","sig":"nope"}],"nodes":[],"wires":[],"out":"x"}],"out":"a"}"""
    private val cfg = SignalConfig()
    private fun g(s: String) = Json.parseToJsonElement(s)

    private fun row(s: Long, gyroMax: Double = 0.0, step: Double = 0.0, ctx: String? = null) = ExprSeries.Row(s, doubleArrayOf(gyroMax, 0.0, 0.0, 0.0, 0.0, step, 0.0), ctx)
    private fun ok(r: ExprSeries.Result) = r as ExprSeries.Result.Ok
    private fun series(graph: String, rows: List<ExprSeries.Row>, from: Long, n: Int) = ExprSeries.run(g(graph), rows, from, n, 80, cfg)

    private fun tryItLast(graph: String, values: List<Double?>, signal: String = "gyroMax", extra: String = ""): JsonObject {
        val list = values.joinToString(",", "[", "]") { it?.toString() ?: "null" }
        val o = (ExprTry.run(Json.parseToJsonElement("""{"graph":$graph,"signals":{"$signal":$list}$extra}""").jsonObject, 80, cfg) as ExprTry.Outcome.Ok).body
        return o["output"]!!.jsonObject
    }

    @Test fun agreesWithTryItOnTheLastSecondForConstantRampAndGaps() {
        val ramp = (0 until 20).map { it * 0.2 }
        val rows = ramp.mapIndexed { i, v -> row(100L + i, v) }
        val r = ok(series(twice, rows, 100, 20))
        val expected = tryItLast(twice, ramp, extra = ""","role":"value","row":"REST","field":"capPerMin"""")
        assertEquals(expected["value"]!!.jsonPrimitive.content.toDouble(), r.cells.last().num, 0.0)
        assertEquals("NUM", r.cells.last().kind)
        // a constant series
        val c = ok(series(fast, (0 until 8).map { row(5L + it, 2.0) }, 5, 8))
        assertEquals("BOOL", tryItLast(fast, List(8) { 2.0 })["state"]!!.jsonPrimitive.content)
        assertTrue(c.cells.last().flag)
        // a gap: the last second is missing for both
        val withGap = (0 until 6).map { if (it == 5) null else 3.0 }
        val gapRows = withGap.mapIndexedNotNull { i, v -> v?.let { row(10L + i, it) } }
        val gp = ok(series(fast, gapRows, 10, 6))
        assertEquals("MISSING", gp.cells.last().kind)
        assertEquals("UNAVAILABLE", tryItLast(fast, withGap)["state"]!!.jsonPrimitive.content)
        assertEquals(5, gp.summary.covered); assertEquals(1, gp.summary.missing)
    }

    @Test fun aWindowIsNotReadyWhileItWarmsAndAMissingSecondRespectsTolerance() {
        val rows = (0 until 14).map { row(1_000L + it, step = 0.5) }
        val r = ok(series(calm, rows, 1_000, 14))
        assertEquals("NOT_READY", r.cells.first().kind)
        assertEquals("BOOL", r.cells.last().kind)
        assertTrue(r.summary.states["NOT_READY"]!! > 0)
        // 14 seconds with 6 gaps in the 10 s window: below 80 % valid -> NOT_READY at the end
        val gappy = (0 until 13).filter { it % 2 == 0 && it > 5 || it < 6 }.map { row(1_000L + it, step = 0.5) }
        val gr = ok(series(calm, gappy, 1_000, 13))
        assertTrue(gr.summary.missing >= 3)
        assertEquals("NOT_READY", gr.cells.last().kind)
    }

    @Test fun aGuardReportsTrueFractionOverValidSecondsOnly() {
        val vals = listOf(0.0, 2.0, 2.0, 0.0)
        val r = ok(series(fast, vals.mapIndexed { i, v -> row(50L + i, v) } + emptyList(), 50, 6))   // 2 trailing missing seconds
        assertEquals(ExprRole.GUARD, r.role)
        assertEquals(4, r.summary.valid); assertEquals(2, r.summary.missing); assertEquals(0.5, r.summary.trueFrac!!, 0.0)
        assertNull(r.summary.mean)
    }

    @Test fun aValueGivesRawMinMaxMean() {
        val r = ok(series(twice, listOf(row(1, 1.0), row(2, 2.0), row(3, 3.0)), 1, 3))
        assertEquals(ExprRole.VALUE, r.role)
        assertEquals(2.0, r.summary.min!!, 0.0); assertEquals(6.0, r.summary.max!!, 0.0); assertEquals(4.0, r.summary.mean!!, 1e-9)
        assertNull(r.summary.trueFrac)
    }

    @Test fun pointsAreBucketedTo300AndTheCapIs1800() {
        val rows = (0 until 1800).map { row(10_000L + it, (it % 10).toDouble()) }
        val r = ok(series(twice, rows, 10_000, 1800))
        assertEquals(300, r.points.size); assertTrue(r.truncated)
        assertEquals(10_000L + 1799, r.points.last().second)
        assertEquals(0.0, r.points[0].min!!, 0.0)
        assertTrue(series(twice, rows, 10_000, 1801) is ExprSeries.Result.Bad)
        assertTrue(series(twice, rows, 10_000, 0) is ExprSeries.Result.Bad)
        assertTrue(ok(series(twice, rows.take(10), 10_000, 10)).points.size == 10)
    }

    @Test fun ctxAgeCountsFromTheLastContextChangeInTheWindow() {
        val rows = (0 until 12).map { row(1L + it, ctx = if (it < 8) "REST" else "VEHICLE_DRIVING") }
        val r = ok(series(ctxAge, rows, 1, 12))
        assertTrue(r.cells[7].flag)             // REST since the window start: age 7 > 5
        assertEquals(false, r.cells[9].flag)    // changed at t=8: age 1
        assertTrue(r.cells[6].flag)
        assertEquals(0, r.summary.contextDefaulted)
        assertEquals(12, ok(series(ctxAge, (0 until 12).map { row(1L + it) }, 1, 12)).summary.contextDefaulted)
    }

    @Test fun anInvalidDefinitionAnswersProblemsNotAnException() {
        assertTrue(series(notNumber, emptyList(), 1, 5) is ExprSeries.Result.Problems)
        assertTrue(ExprSeries.run(null, emptyList(), 1, 5, 80, cfg) is ExprSeries.Result.Problems)
    }
}
