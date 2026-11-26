package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.dumpimport.DumpImportParser
import com.rokid.context.canvas.dumpimport.ImportReport
import com.rokid.context.phone.canvas.server.HttpRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ImportRoutesTest {
    private var dev = true
    private var flag = true
    private var now = 1_000_000L
    private var calls = 0
    private var seen: Triple<Int, Boolean, Boolean>? = null
    private var reply: (List<DumpImportParser.Entry>) -> ImportReport = { e -> ImportReport(e.map { "created" to "ok" }, e.size, 0, 0, 0, 0, 0, false) }
    private fun routes(max: Int = 60) = ImportRoutes({ dev }, { flag }, { e, d, r -> calls++; seen = Triple(e.size, d, r); reply(e) }, { now }, max)
    private val jpeg = Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3))
    private val sec = 1_700_000_000L
    private val good = """{"items":[{"object_type":"moment","id":"${"%08x".format(sec)}","startMs":${sec * 1000},"frames":[{"id":"f1","capturedAtMs":${sec * 1000 + 5},"hasPhoto":true}],"photos":{"f1":"$jpeg"}}]}"""
    private fun post(body: String = good, q: Map<String, String> = emptyMap(), m: String = "POST", r: ImportRoutes = routes(), path: String = "/api/import", tooLarge: Boolean = false) =
        runBlocking { r.handle(HttpRequest(m, path, q, emptyMap(), body.toByteArray(), bodyTooLarge = tooLarge)) }
    private fun json(r: com.rokid.context.phone.canvas.server.HttpResponse) = Json.parseToJsonElement(String(r.body)).jsonObject

    @Test fun otherPathsAndMethods() {
        assertNull(post(path = "/api/dump/items"))
        assertEquals(405, post(m = "GET")!!.status)
    }

    @Test fun gates() {
        dev = false; assertEquals("developer_mode_off", json(post()!!)["error"]!!.jsonPrimitive.content); assertEquals(403, post()!!.status)
        dev = true; flag = false; assertEquals(403, post()!!.status); assertEquals("import_off", json(post()!!)["error"]!!.jsonPrimitive.content)
        assertEquals("nothing ran behind a closed gate", 0, calls)
    }

    @Test fun badQueryAndBadBodyAre400NeverMore() {
        listOf(mapOf("dedup" to "yes"), mapOf("dry_run" to "1"), mapOf("x" to "y")).forEach { assertEquals(400, post(q = it)!!.status) }
        listOf("", "{", """{"items":[]}""", """{"items":[{"object_type":"moment","id":"zz"}]}""").forEach { assertEquals(400, post(it)!!.status) }
        val r = post("""{"items":[{"object_type":"moment","id":"zz"}]}""")!!
        assertEquals(0, json(r)["item"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, calls)
        assertEquals(413, post(tooLarge = true)!!.status)
    }

    @Test fun paramsReachTheImporterAndTheAnswerIsCountsOnly() {
        val r = post(q = mapOf("dedup" to "true", "dry_run" to "true"))!!
        assertEquals(200, r.status); assertEquals(Triple(1, true, true), seen)
        val j = json(r); assertEquals(1, j["received"]!!.jsonPrimitive.content.toInt())
        assertTrue(!String(r.body).contains(good.substring(40, 60)))
        assertEquals(Triple(1, false, false), run { post(); seen })
    }

    @Test fun conflictIs409WithPerItemResults() {
        reply = { e -> ImportReport(e.map { "conflict" to "exists" }, 0, 0, 0, 0, 0, 0, false) }
        val r = post()!!
        assertEquals(409, r.status); assertEquals(1, json(r)["conflicts"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun deleteAllMidImportIs409Stopped() {
        reply = { e -> ImportReport(e.map { "failed" to "stopped" }, 0, 0, 0, 0, 0, 0, true) }
        val r = post()!!; assertEquals(409, r.status); assertEquals("import_stopped", json(r)["error"]!!.jsonPrimitive.content)
    }

    @Test fun rateLimited() {
        val r = routes(max = 2)
        assertEquals(200, post(r = r)!!.status); assertEquals(200, post(r = r)!!.status); assertEquals(429, post(r = r)!!.status)
        now += 1_001; assertEquals(200, post(r = r)!!.status)
    }

    @Test fun importerFailureIs500NotACrash() {
        val r = ImportRoutes({ true }, { true }, { _, _, _ -> throw IllegalStateException("x") })
        assertEquals(500, post(r = r)!!.status)
    }
}
