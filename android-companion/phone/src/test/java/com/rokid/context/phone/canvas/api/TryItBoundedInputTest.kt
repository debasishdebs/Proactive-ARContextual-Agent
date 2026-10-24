package com.rokid.context.phone.canvas.api

import com.rokid.context.phone.canvas.server.HttpRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** X8: the try-it input is bounded (signals, values per signal, body size) and every violation is a typed 400 with a fixed text that echoes nothing. */
class TryItBoundedInputTest {
    private val routes = ExprRoutes()
    private fun post(body: ByteArray, method: String = "POST", path: String = "/api/expr/try") = runBlocking { routes.handle(HttpRequest(method, path, emptyMap(), emptyMap(), body))!! }
    private fun post(body: String) = post(body.toByteArray())
    private fun code(r: com.rokid.context.phone.canvas.server.HttpResponse) = Json.parseToJsonElement(String(r.body)).jsonObject["error"]!!.jsonPrimitive.content
    private val g = TryItMatchesRunnerTest.FAST
    private fun req(signals: String) = """{"graph":$g,"signals":$signals}"""

    @Test fun nineSignalsAreRefused() {
        val nine = (1..9).joinToString(",") { "\"s$it\":1" }
        val r = post(req("{$nine}"))
        assertEquals(400, r.status); assertEquals("too_many_signals", code(r))
    }

    @Test fun moreThan300ValuesAreRefusedAndExactly300AreAccepted() {
        val r301 = post(req("""{"gyroMax":[${List(301) { 1 }.joinToString(",")}]}"""))
        assertEquals(400, r301.status); assertEquals("too_many_values", code(r301))
        val r300 = post(req("""{"gyroMax":[${List(300) { 1 }.joinToString(",")}]}"""))
        assertEquals(String(r300.body), 200, r300.status)
        assertEquals(400, post(req("""{"gyroMax":[]}""")).status)
    }

    @Test fun aBodyOverTheSizeCapIsRefusedBeforeItIsParsed() {
        val r = post(ByteArray(65 * 1024) { ' '.code.toByte() })
        assertEquals(400, r.status); assertEquals("too_large", code(r))
    }

    @Test fun raggedListsNonNumbersAndHugeValuesAreRefused() {
        assertEquals("ragged_signals", code(post(req("""{"gyroMax":[1,2],"stepHz":[1,2,3]}"""))))
        assertEquals("bad_value", code(post(req("""{"gyroMax":"fast"}"""))))
        assertEquals("bad_value", code(post(req("""{"gyroMax":true}"""))))
        assertEquals("bad_value", code(post(req("""{"gyroMax":1e7}"""))))
        assertEquals("bad_value", code(post(req("""{"gyroMax":[1,"x"]}"""))))
    }

    @Test fun anUnknownSignalAMissingSignalAndATwiceGivenSignalAreRefusedWithoutEchoingInput() {
        val unknown = post(req("""{"<script>":1}"""))
        assertEquals("unknown_signal", code(unknown)); assertFalse(String(unknown.body).contains("script"))
        val ctx = post(req("""{"ctx":1}"""))
        assertEquals("unknown_signal", code(ctx))
        val missing = post(req("""{"stepHz":1}"""))
        assertEquals("missing_signal", code(missing)); assertTrue(String(missing.body).contains("gyroMax"))
        assertEquals(400, post(req("""{"gyroMax":1,"headSpeed":2}""")).status)
    }

    @Test fun malformedBodiesAreBadRequestsAndOtherPathsAndMethodsAreNotTheirs() {
        assertEquals("bad_request", code(post("not json")))
        assertEquals("bad_request", code(post("[1]")))
        assertEquals("bad_request", code(post("""{"graph":$g}""")))
        assertEquals("bad_request", code(post("""{"graph":$g,"signals":{"gyroMax":1},"ctx":"MOON"}""")))
        assertEquals(405, post(ByteArray(0), method = "GET").status)
        assertEquals(null, runBlocking { routes.handle(HttpRequest("POST", "/api/expr", emptyMap(), emptyMap(), ByteArray(0))) })
    }
}
