package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.model.SecondFeatures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 3c X9: golden vectors (`src/test/resources/expr/golden_vectors.json`), hand-computed from the spec: operator boundaries, strictness, error and unavailable rules, `choose`
 * laziness, `fresh` limits, windows (exact sums, gaps, warm-up, staleness). Each vector is a lowered program, the per-second writes (second index + feature values), a read
 * time and the expected value of the program output. A change to any of these semantics is a deliberate decision: edit the vector with it.
 */
class ExprGoldenVectorsTest {
    private val vectors: JsonArray = Json.parseToJsonElement(javaClass.getResource("/expr/golden_vectors.json")!!.readText()).jsonArray
    private val features = SignalCatalog.featureNames

    @Test fun everyVectorIsAnswered() {
        assertTrue(vectors.size >= 30, "${vectors.size} vectors")
        for (v in vectors) {
            v as JsonObject
            val name = v["name"]!!.jsonPrimitive.content
            val prog = v["program"]!!.jsonObject
            val runner = ExprRunner.of(listOf(prog))!!
            val sig = ExprSignals()
            runner.bind(sig)
            var lastMs = 1_000_000L
            for (w in v["writes"]!!.jsonArray) {
                w as JsonObject
                val f = FloatArray(features.size) { i -> (w[features[i]] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 0f }
                lastMs = 1_000_000L + w["sec"]!!.jsonPrimitive.content.toLong() * 1000
                sig.set(SecondFeatures(f[0], f[1], f[2], f[3], f[4], f[5], f[6]), lastMs)
                runner.tick(sig)
            }
            val ctx = ContextState.valueOf(v["ctx"]!!.jsonPrimitive.content)
            val cells = runner.probe(prog["role"]!!.jsonPrimitive.content == "value", 0, lastMs + v["readAfterMs"]!!.jsonPrimitive.content.toLong(), sig, ctx, v["ctxAgeS"]!!.jsonPrimitive.content.toLong())
            val got = cells[prog["out"]!!.jsonPrimitive.content.toInt()]
            val want = v["expect"]!!.jsonObject
            val state = want["state"]!!.jsonPrimitive.content
            when (state) {
                "UNAV" -> assertTrue(got.kind == ExprRunner.Probe.Kind.UNAVAILABLE || got.kind == ExprRunner.Probe.Kind.NOT_READY, "$name: ${got.kind}")
                "NUM" -> { assertEquals(ExprRunner.Probe.Kind.NUM, got.kind, name); assertEquals(want["value"]!!.jsonPrimitive.double(), got.num, name) }
                "BOOL" -> { assertEquals(ExprRunner.Probe.Kind.BOOL, got.kind, name); assertEquals(want["value"]!!.jsonPrimitive.content.toBoolean(), got.flag, name) }
                else -> assertEquals(state, got.kind.name, name)
            }
        }
    }

    private fun JsonPrimitive.double() = doubleOrNull!!
}
