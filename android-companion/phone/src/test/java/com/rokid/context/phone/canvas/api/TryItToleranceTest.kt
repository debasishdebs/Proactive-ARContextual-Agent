package com.rokid.context.phone.canvas.api

import com.rokid.context.phone.canvas.server.HttpRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** 3d: the try-it takes per-second validity (null = a missing second) and the windows answer by the tolerance percent, like the glasses' runner. */
class TryItToleranceTest {
    private val CALM = TryItMatchesRunnerTest.CALM
    private fun calmWith(mv: Int) = CALM.replace(""""sec":10}""", """"sec":10,"mv":$mv}""")
    private fun post(body: String, pct: Int? = null) = runBlocking { (if (pct == null) ExprRoutes() else ExprRoutes { pct }).handle(HttpRequest("POST", "/api/expr/try", emptyMap(), emptyMap(), body.toByteArray()))!! }
    private fun ask(graph: String, signals: String, pct: Int? = null): JsonObject {
        val r = post("""{"graph":$graph,"signals":$signals}""", pct)
        assertEquals(String(r.body), 200, r.status)
        return Json.parseToJsonElement(String(r.body)).jsonObject
    }
    private fun state(r: JsonObject) = r["output"]!!.jsonObject["state"]!!.jsonPrimitive.content
    private fun hz(vararg missing: Int) = (0 until 10).joinToString(",", "[", "]") { if (it in missing) "null" else "0" }

    @Test fun aMissingSecondIsToleratedUpToThePercent() {
        assertEquals("BOOL", state(ask(calmWith(90), """{"stepHz":${hz(3)}}""")))          // 9 of 10 at 90 %: exactly at the threshold
        assertEquals("NOT_READY", state(ask(calmWith(90), """{"stepHz":${hz(3, 4)}}""")))  // 8 of 10: one below
        assertEquals("BOOL", state(ask(calmWith(80), """{"stepHz":${hz(3, 4)}}""")))
        assertEquals("NOT_READY", state(ask(calmWith(100), """{"stepHz":${hz(0)}}""")))
        assertEquals("BOOL", state(ask(calmWith(50), """{"stepHz":${hz(0, 1, 2, 3, 4)}}""")))
        assertEquals("NOT_READY", state(ask(calmWith(50), """{"stepHz":${hz(0, 1, 2, 3, 4, 5)}}""")))
    }

    @Test fun theAnswerIsOverTheValidSecondsOnlyAndAGapIsNotAZero() {
        // mean of the 8 valid seconds is 2.0 (a zero for the 2 gaps would make it 1.6, which is < 1.7)
        val g = TryItMatchesRunnerTest.CALM.replace(""""sec":10}""", """"sec":10,"mv":80}""").replace(""""value":1.2""", """"value":1.7""").replace(""""f":"<"""", """"f":">"""")
        assertEquals("true", ask(g, """{"stepHz":[2,2,null,2,2,null,2,2,2,2]}""")["output"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test fun aGraphWithoutAToleranceUsesTheSettingAndNamingOneOverridesIt() {
        val two = """{"stepHz":${hz(3, 4)}}"""
        assertEquals("NOT_READY", state(ask(CALM, two)))            // the default setting is 90
        assertEquals("BOOL", state(ask(CALM, two, pct = 70)))
        assertEquals("NOT_READY", state(ask(calmWith(90), two, pct = 50)))
    }

    @Test fun aSignalWithNoDataDoesNotSpoilAnotherSignalsWindow() {
        assertEquals("BOOL", state(ask(CALM, """{"stepHz":${hz()},"gyroMax":[${List(10) { "null" }.joinToString(",")}]}""")))
    }

    @Test fun aNullIsOnlyValidInsideAList() {
        for (bad in listOf("""{"stepHz":null}""", """{"stepHz":[0,true,0]}""", """{"stepHz":[0,"x",null]}""")) {
            val r = post("""{"graph":$CALM,"signals":$bad}""")
            assertEquals(bad, 400, r.status)
            assertEquals("bad_value", Json.parseToJsonElement(String(r.body)).jsonObject["error"]!!.jsonPrimitive.content)
        }
        assertEquals("ragged_signals", Json.parseToJsonElement(String(post("""{"graph":$CALM,"signals":{"stepHz":[null,1],"gyroMax":[1,2,3]}}""").body)).jsonObject["error"]!!.jsonPrimitive.content)
    }
}
