package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.dump.DumpItem
import com.rokid.context.canvas.dump.DumpPhoto
import com.rokid.context.canvas.dump.DumpSource
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.CanvasRouter
import com.rokid.context.phone.canvas.server.AssetSource
import com.rokid.context.phone.canvas.server.HostPolicy
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpRoutesTest {
    private class Fake : DumpSource {
        var moments: List<DumpItem> = (1..5).map { DumpItem("m$it", buildJsonObject { put("id", "m$it"); put("startMs", it * 1000L) }) }
        var calls = 0
        var wipeOnRead: (() -> Unit)? = null
        val lastArgs = mutableListOf<String>()
        override suspend fun manifest(): JsonObject { calls++; wipeOnRead?.invoke(); return buildJsonObject { put("dump_id", "dump-x"); put("counts", buildJsonObject { put("moments", 5) }) } }
        override suspend fun moments(fromMs: Long, toMs: Long, after: String?, limit: Int, ascending: Boolean): List<DumpItem> { calls++; lastArgs += "$fromMs|$toMs|$after|$limit" + (if (ascending) "" else "|desc"); wipeOnRead?.invoke(); return moments.sortedBy { it.cursor }.let { if (ascending) it.filter { x -> after == null || x.cursor > after } else it.reversed().filter { x -> after == null || x.cursor < after } }.filter { ms(it) in fromMs..toMs }.take(limit) }
        fun ms(x: DumpItem) = x.json["startMs"]?.jsonPrimitive?.content?.toLong() ?: 0L
        var sceneList: List<DumpItem> = emptyList()
        var markList: List<DumpItem> = emptyList()
        override suspend fun scenes(fromMs: Long, toMs: Long, after: String?, limit: Int, ascending: Boolean): List<DumpItem> = sceneList.sortedBy { ms(it) }.let { if (ascending) it else it.reversed() }.filter { ms(it) in fromMs..toMs }.take(limit)
        override suspend fun marks(fromMs: Long, toMs: Long, after: String?, limit: Int, ascending: Boolean): List<DumpItem> = markList.sortedBy { it.json["atMs"]!!.jsonPrimitive.content.toLong() }.let { if (ascending) it else it.reversed() }.filter { it.json["atMs"]!!.jsonPrimitive.content.toLong() in fromMs..toMs }.take(limit)
        var attemptList: List<DumpItem> = emptyList()
        override suspend fun placeAttempts(fromMs: Long, toMs: Long, after: String?, limit: Int, ascending: Boolean): List<DumpItem> { lastArgs += "attempts|$fromMs|$toMs|$after|$limit" + (if (ascending) "" else "|desc"); return attemptList.sortedBy { it.json["atMs"]!!.jsonPrimitive.content.toLong() }.let { if (ascending) it else it.reversed() }.filter { it.json["atMs"]!!.jsonPrimitive.content.toLong() in fromMs..toMs }.take(limit) }
        override suspend fun pins(): List<JsonObject> = listOf(buildJsonObject { put("id", "p1"); put("lat", 1.5); put("pinnedAt", 2500) }, buildJsonObject { put("id", "p2"); put("lat", 2.5) })
        override suspend fun photo(frameId: String): DumpPhoto? = if (frameId == "f1") DumpPhoto(byteArrayOf(1, 2, 3), "image/jpeg") else null
    }

    private val src = Fake()
    private var dev = true
    private var gen = 0L
    private var now = 1_000_000L
    private fun routes(max: Int = 60) = DumpRoutes({ dev }, src, { gen }, { java.time.ZoneOffset.UTC }, { now }, max)
    private fun get(path: String, q: Map<String, String> = emptyMap(), m: String = "GET", r: DumpRoutes = routes()) = runBlocking { r.handle(HttpRequest(m, path, q, emptyMap(), ByteArray(0))) }
    private fun json(r: HttpResponse?) = Json.parseToJsonElement(String(r!!.body)).jsonObject
    private fun code(r: HttpResponse?) = json(r)["error"]?.jsonPrimitive?.content

    @Test fun notThisGroupsPathIsNullAndOtherMethodsAre405() {
        assertNull(get("/api/moments/repair")); assertNull(get("/api/dumpx/manifest"))
        for (m in listOf("POST", "PUT", "DELETE", "PATCH")) assertEquals(m, 405, get("/api/dump/manifest", m = m)!!.status)
    }

    @Test fun developerModeOffIs403OnEveryRouteAndReadsNothing() {
        dev = false
        listOf("manifest", "moments", "scenes", "marks", "pins", "frames/f1/photo").forEach { p -> val r = get("/api/dump/$p", mapOf("space" to "nomic")); assertEquals(p, 403, r!!.status); assertEquals("developer_mode_off", code(r)) }
        assertEquals(0, src.calls)
    }

    @Test fun manifestAndPagesAreJsonAndCursorPaged() {
        assertEquals("dump-x", json(get("/api/dump/manifest"))["dump_id"]!!.jsonPrimitive.content)
        val p1 = json(get("/api/dump/moments", mapOf("limit" to "2")))
        assertEquals(2, p1["count"]!!.jsonPrimitive.content.toInt()); assertEquals("m2", p1["next"]!!.jsonPrimitive.content)
        val p3 = json(get("/api/dump/moments", mapOf("after" to "m4", "limit" to "2")))
        assertEquals(1, p3["count"]!!.jsonPrimitive.content.toInt()); assertEquals(JsonNull, p3["next"])
        assertEquals(2, json(get("/api/dump/pins"))["count"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun limitIsCappedAt200AndBadParametersAre400() {
        get("/api/dump/moments", mapOf("limit" to "9999")); assertTrue(src.lastArgs.last().endsWith("|200"))
        listOf(mapOf("limit" to "0"), mapOf("limit" to "x"), mapOf("after" to "a b"), mapOf("after" to "a/../b"), mapOf("from" to "yesterday"), mapOf("to" to "2026-13-40")).forEach { q -> assertEquals(q.toString(), 400, get("/api/dump/moments", q)!!.status) }
    }

    @Test fun fromAndToTakeDaysInTheLocalZoneOrEpochMs() {
        get("/api/dump/moments", mapOf("from" to "2026-11-25", "to" to "2026-11-25"))
        assertEquals("1795564800000|1795651199999|null|200", src.lastArgs.last())
        get("/api/dump/moments", mapOf("from" to "1000", "to" to "2000")); assertEquals("1000|2000|null|200", src.lastArgs.last())
    }

    @Test fun aPageStopsAtTwoMegabytesAndHandsBackACursor() {
        val big = "x".repeat(900_000)
        src.moments = (1..5).map { DumpItem("m$it", buildJsonObject { put("id", "m$it"); put("pad", big) }) }
        val r = get("/api/dump/moments")!!
        assertTrue(r.body.size <= DumpSource.MAX_PAGE_BYTES)
        val j = json(r); assertEquals(2, j["count"]!!.jsonPrimitive.content.toInt()); assertEquals("m2", j["next"]!!.jsonPrimitive.content)
        src.moments = listOf(DumpItem("m1", buildJsonObject { put("pad", "y".repeat(3_000_000)) }))
        assertEquals("one oversize item is still delivered (never an endless empty page)", 1, json(get("/api/dump/moments"))["count"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun photoIsStreamedWithItsTypeAndNoStoreAndAMissingOneIs404() {
        val r = get("/api/dump/frames/f1/photo")!!
        assertEquals(200, r.status); assertEquals("image/jpeg", r.contentType); assertEquals(listOf<Byte>(1, 2, 3), r.body.toList()); assertEquals("no-store", r.headers["Cache-Control"])
        assertEquals(404, get("/api/dump/frames/gone/photo")!!.status)
        assertEquals(400, get("/api/dump/frames/a b/photo")!!.status)
        assertEquals(404, get("/api/dump/frames/f1/other")!!.status); assertEquals(404, get("/api/dump/nothing")!!.status)
    }

    @Test fun theVectorsEndpointIsGone() {
        assertEquals(404, get("/api/dump/vectors", mapOf("space" to "nomic"))!!.status)
    }

    @Test fun sinceUntilCursorAreAliasesAndIsoDateTimesAreAccepted() {
        get("/api/dump/moments", mapOf("since" to "1000", "until" to "2000", "cursor" to "m1")); assertEquals("1000|2000|m1|200", src.lastArgs.last())
        get("/api/dump/moments", mapOf("since" to "2026-11-25T00:00:00Z", "until" to "2026-11-25T00:00:01.5Z")); assertEquals("1795564800000|1795564801500|null|200", src.lastArgs.last())
        get("/api/dump/moments", mapOf("since" to "2026-11-25T05:30:00+05:30")); assertEquals("1795564800000|${Long.MAX_VALUE}|null|200", src.lastArgs.last())
        get("/api/dump/moments", mapOf("since" to "2026-11-25T00:00:00")); assertEquals("1795564800000", src.lastArgs.last().substringBefore('|')) // zone-less = local zone (UTC here)
    }

    @Test fun conflictingAliasesAndAReversedRangeAre400() {
        listOf(mapOf("from" to "1", "since" to "1"), mapOf("to" to "5", "until" to "5"), mapOf("after" to "a", "cursor" to "a"), mapOf("since" to "9", "until" to "3"), mapOf("since" to "2026-11-25T99:00:00Z"), mapOf("until" to "x".repeat(60)))
            .forEach { q -> assertEquals(q.toString(), 400, get("/api/dump/moments", q)!!.status) }
    }

    @Test fun fieldsKeepOnlyTheNamedTopLevelKeysAndId() {
        src.moments = listOf(DumpItem("m1", buildJsonObject { put("id", "m1"); put("a", 1); put("b", 2); put("c", 3) }))
        val it = json(get("/api/dump/moments", mapOf("fields" to "a,c")))["items"]!!.jsonArray[0].jsonObject
        assertEquals(setOf("id", "a", "c"), it.keys)
        assertEquals(setOf("id", "lat"), json(get("/api/dump/pins", mapOf("fields" to "lat")))["items"]!!.jsonArray[0].jsonObject.keys)
        assertEquals(setOf("id", "a", "b", "c"), json(get("/api/dump/moments"))["items"]!!.jsonArray[0].jsonObject.keys)
        listOf("a b", "a,,b", "a.b", "1a", "a/../b", (1..41).joinToString(",") { "f$it" }).forEach { f -> assertEquals(f, 400, get("/api/dump/moments", mapOf("fields" to f))!!.status) }
    }

    @Test fun rateLimitedAfterTheBudgetWithinOneSecondAndFreshAfterIt() {
        val r = routes(max = 3)
        repeat(3) { assertEquals(200, get("/api/dump/pins", r = r)!!.status) }
        val limited = get("/api/dump/pins", r = r)!!
        assertEquals(429, limited.status); assertEquals("rate_limited", code(limited)); assertEquals("1", limited.headers["Retry-After"])
        now += 1_001; assertEquals(200, get("/api/dump/pins", r = r)!!.status)
    }

    @Test fun deleteAllWhileReadingAnswersNothingButStopped() {
        src.wipeOnRead = { gen++ }
        val r = get("/api/dump/moments")!!
        assertEquals(409, r.status); assertEquals("dump_stopped", code(r)); assertFalse(String(r.body).contains("m1"))
        src.wipeOnRead = null; assertEquals(200, get("/api/dump/moments")!!.status)
    }

    /** review2 #4: a Forget / Delete saved locations (any gated delete) cannot COMMIT while a dump read runs: it waits for the read, so the response never discloses rows a committed deletion removed. */
    @Test fun aDeleteCannotCommitDuringADumpReadAndARunningDeleteStopsTheDump() {
        var deleted = false; var deletedDuringRead = false
        val t = Thread { runBlocking { com.rokid.context.orchestration.DataOpGate.shared.delete { deleted = true } } }
        src.wipeOnRead = { if (!t.isAlive && !deleted) { t.start(); Thread.sleep(300); deletedDuringRead = deleted } }
        val r = get("/api/dump/moments")!!
        assertFalse("the delete committed while the read was running", deletedDuringRead)
        assertEquals(200, r.status)
        t.join(5_000); assertTrue("the delete goes ahead once the read ended", deleted)
        // a delete that is RUNNING when the request arrives: nothing is read, nothing answered
        src.wipeOnRead = null; val before = src.calls
        val during = runBlocking { com.rokid.context.orchestration.DataOpGate.shared.delete { routes().handle(HttpRequest("GET", "/api/dump/moments", emptyMap(), emptyMap(), ByteArray(0))) } }!!
        assertEquals(409, during.status); assertEquals("dump_stopped", code(during)); assertEquals(before, src.calls)
    }

    @Test fun developerModeGoingOffWhileReadingAnswersNothing() {
        src.wipeOnRead = { dev = false }
        assertEquals(403, get("/api/dump/manifest")!!.status)
    }

    @Test fun aFailingReaderIsAFixed500WithoutTheExceptionText() {
        val boom = object : DumpSource by src { override suspend fun manifest(): JsonObject = throw IllegalStateException("secret-detail-xyz") }
        val r = runBlocking { DumpRoutes({ true }, boom).handle(HttpRequest("GET", "/api/dump/manifest", emptyMap(), emptyMap(), ByteArray(0))) }!!
        assertEquals(500, r.status); assertFalse(String(r.body).contains("secret-detail-xyz"))
    }

    @Test fun noAnswerCarriesATokenKeyOrSecretFieldName() {
        val all = listOf("manifest", "moments", "pins", "scenes", "marks").map { String(get("/api/dump/$it")!!.body) }
        all.forEach { t -> listOf("token", "apikey", "api_key", "passphrase", "sqlcipher", "maptiler", "authorization").forEach { assertFalse("$it in $t", t.lowercase().contains(it)) } }
    }

    // ---- behind the real router: pairing and the Host policy are the router's, exactly as for the repair and location-import routes ----

    private val auth = CanvasAuth(clock = { 5_000L })
    private val hosts = HostPolicy(port = { 8765 }, lanIp = { null })
    private val routed = ApiHandler { req -> runBlocking { routes().handle(req) } ?: HttpResponse.error(404, "not_found", "Not found.") }
    private val router = CanvasRouter(auth, hosts, routed, AssetSource { null })
    private fun viaRouter(token: String?, host: String = "127.0.0.1:8765", path: String = "/api/dump/manifest") =
        router.handle(HttpRequest("GET", path, emptyMap(), buildMap { put("host", host); if (token != null) put("authorization", "Bearer $token") }, ByteArray(0)))

    @Test fun withoutAPairedSessionTheRouterAnswers401AndTheReaderIsNeverCalled() {
        assertEquals(401, viaRouter(null).status); assertEquals(401, viaRouter("wrong").status); assertEquals(0, src.calls)
    }

    @Test fun aPairedSessionOnLoopbackIsServedAndAnyOtherHostIs421() {
        val t = auth.newWebViewToken()
        assertEquals(200, viaRouter(t).status); assertEquals(200, viaRouter(t, host = "localhost:8765").status)
        for (h in listOf("192.168.1.5:8765", "evil.example:8765", "127.0.0.1:9999", "10.0.0.2")) assertEquals(h, 421, viaRouter(t, host = h).status)
        dev = false; assertEquals(403, viaRouter(t).status)
    }

    @Test fun theLogLineTemplateNeverCarriesAFrameId() {
        val logs = mutableListOf<String>()
        val t = auth.newWebViewToken()
        CanvasRouter(auth, hosts, routed, AssetSource { null }) { logs += it }.handle(HttpRequest("GET", "/api/dump/frames/f1/photo", mapOf("after" to "secret"), mapOf("host" to "127.0.0.1:8765", "authorization" to "Bearer $t"), ByteArray(0)))
        assertEquals(1, logs.size); assertFalse(logs[0].contains("f1")); assertFalse(logs[0].contains("secret"))
    }

    private fun ids(r: HttpResponse?) = json(r)["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test fun orderDescReadsNewestFirstAndPagesWithTheCursor() {
        assertEquals(listOf("m5", "m4"), ids(get("/api/dump/moments", mapOf("order" to "desc", "limit" to "2"))))
        assertEquals(listOf("m3", "m2"), ids(get("/api/dump/moments", mapOf("order" to "DESC", "after" to "m4", "limit" to "2"))))
        assertEquals(listOf("m1", "m2"), ids(get("/api/dump/moments", mapOf("order" to "asc", "limit" to "2"))))
        assertEquals(listOf("p2", "p1"), ids(get("/api/dump/pins", mapOf("order" to "desc"))))
    }

    @Test fun badOrderIs400OnEveryListRoute() {
        listOf("moments", "scenes", "marks", "pins", "items").forEach { p -> listOf("up", "descending", "1").forEach { o -> assertEquals("$p $o", 400, get("/api/dump/$p", mapOf("order" to o))!!.status) } }
    }

    @Test fun batchSizeIsThePageSizeAliasAndABadOrConflictingOneIs400() {
        assertEquals(2, json(get("/api/dump/moments", mapOf("batch_size" to "2")))["count"]!!.jsonPrimitive.content.toInt())
        get("/api/dump/moments", mapOf("batch_size" to "9999")); assertTrue(src.lastArgs.last().endsWith("|200"))
        assertEquals(200, get("/api/dump/moments", mapOf("batch_size" to "3", "limit" to "3"))!!.status)
        listOf(mapOf("batch_size" to "0"), mapOf("batch_size" to "-1"), mapOf("batch_size" to "x"), mapOf("batch_size" to "3", "limit" to "4")).forEach { q -> assertEquals(q.toString(), 400, get("/api/dump/moments", q)!!.status) }
    }

    private fun withOthers() {
        src.sceneList = listOf(DumpItem("s", buildJsonObject { put("id", "s1"); put("startMs", 2500) }))
        src.markList = listOf(DumpItem("k", buildJsonObject { put("id", "k1"); put("atMs", 1500) }))
    }

    @Test fun itemsMergesTypesByTimeAndTagsEachWithItsObjectType() {
        withOthers()
        val r = get("/api/dump/items")!!; assertEquals(200, r.status)
        assertEquals(listOf("m1", "k1", "m2", "m3", "s1", "p1", "m4", "m5", "p2").sorted(), ids(r).sorted())
        assertEquals(listOf("p2", "m1", "k1", "m2", "s1"), ids(get("/api/dump/items", mapOf("limit" to "5", "to" to "3000")))) // p2 has no time: 0
    }

    @Test fun itemsFilterByObjectTypeAcceptsBothSpellingsAndOrderAndPages() {
        withOthers()
        assertEquals(listOf("m1", "m2", "k1").sorted(), ids(get("/api/dump/items", mapOf("object_type" to "moment,mark", "to" to "2000"))).sorted())
        assertEquals(listOf("s1"), ids(get("/api/dump/items", mapOf("object_type[]" to "scene"))))
        val tags = json(get("/api/dump/items", mapOf("object_type" to "mark")))["items"]!!.jsonArray.map { it.jsonObject["object_type"]!!.jsonPrimitive.content }
        assertEquals(listOf("mark"), tags)
        // desc paging walks every item exactly once through the composite cursor
        val seen = ArrayList<String>(); var after: String? = null
        for (n in 0 until 10) { if (n > 0 && after == null) break; val q = mutableMapOf("order" to "desc", "limit" to "2", "object_type" to "moment,scene,mark"); after?.let { q["after"] = it }
            val j = json(get("/api/dump/items", q)); seen += j["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }; after = (j["next"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.content }
        assertEquals(listOf("m5", "m4", "s1", "m3", "k1", "m2", "m1").let { it.sortedBy { x -> x } }, seen.sorted()); assertEquals(7, seen.size)
        assertEquals(seen, seen.distinct())
        // fields keeps id and object_type
        val one = json(get("/api/dump/items", mapOf("object_type" to "mark", "fields" to "atMs")))["items"]!!.jsonArray[0].jsonObject
        assertEquals(setOf("id", "atMs", "object_type"), one.keys)
    }

    @Test fun itemsPagingWalksMarksSharingOneTimestampWithBatchSizeOne() {
        src.markList = (1..3).map { n -> DumpItem("5000:t$n", buildJsonObject { put("id", "t$n"); put("atMs", 5000L) }) }
        val seen = ArrayList<String>(); var after: String? = null
        for (n in 0 until 10) { val q = mutableMapOf("batch_size" to "1", "object_type" to "mark"); after?.let { q["after"] = it }
            val j = json(get("/api/dump/items", q)); seen += j["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }; after = (j["next"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.content; if (after == null) break }
        assertEquals(listOf("t1", "t2", "t3"), seen)
    }

    @Test fun itemsBadObjectTypeCursorOrRangeIs400() {
        listOf(mapOf("object_type" to "frame"), mapOf("object_type" to ""), mapOf("object_type" to "mark,mark"), mapOf("object_type" to "mark,"), mapOf("object_type" to "mark", "object_type[]" to "pin"),
            mapOf("after" to "nonsense"), mapOf("after" to "x:moment:m1"), mapOf("after" to "5:vector:m1"), mapOf("from" to "2000", "to" to "1000"), mapOf("limit" to "0"))
            .forEach { q -> assertEquals(q.toString(), 400, get("/api/dump/items", q)!!.status) }
    }

    @Test fun itemsAreDeveloperOnlyAndGetOnly() {
        dev = false; assertEquals(403, get("/api/dump/items")!!.status); dev = true
        assertEquals(405, get("/api/dump/items", m = "POST")!!.status)
    }

    // ---- B3: place attempts ----
    private fun attempts() { src.attemptList = (1..3).map { n -> DumpItem("${n * 1000}:a$n", buildJsonObject { put("id", "a$n"); put("atMs", n * 1000L); put("kind", "PHOTO"); put("label", if (n == 2) "RIGHT" else null as String?) }) } }

    @Test fun placeAttemptsRouteHasThePageBehaviourOfTheOtherRoutes() {
        attempts()
        val r = get("/api/dump/place_attempts", mapOf("limit" to "2"))!!; assertEquals(200, r.status)
        assertEquals(listOf("a1", "a2"), ids(r)); assertEquals("2000:a2", json(r)["next"]!!.jsonPrimitive.content)
        assertEquals(listOf("a3", "a2"), ids(get("/api/dump/place_attempts", mapOf("order" to "desc", "limit" to "2"))))
        assertEquals("the cursor and the range reach the reader", "attempts|0|2500|1000:a1|200", src.lastArgs.also { get("/api/dump/place_attempts", mapOf("after" to "1000:a1", "to" to "2500")) }.last())
        val one = json(get("/api/dump/place_attempts", mapOf("fields" to "label")))["items"]!!.jsonArray[0].jsonObject
        assertEquals(setOf("id", "label"), one.keys)
        assertEquals(400, get("/api/dump/place_attempts", mapOf("limit" to "0"))!!.status); assertEquals(400, get("/api/dump/place_attempts", mapOf("order" to "sideways"))!!.status); assertEquals(400, get("/api/dump/place_attempts", mapOf("after" to "no way"))!!.status)
    }

    @Test fun placeAttemptIsOptInInTheMergedStreamAndTagged() {
        attempts(); withOthers()
        assertFalse("not in the default set", ids(get("/api/dump/items")).any { it.startsWith("a") })
        val r = get("/api/dump/items", mapOf("object_type" to "place_attempt,mark"))!!; assertEquals(200, r.status)
        assertEquals(listOf("a1", "k1", "a2", "a3"), ids(r))
        assertEquals(setOf("place_attempt", "mark"), json(r)["items"]!!.jsonArray.map { it.jsonObject["object_type"]!!.jsonPrimitive.content }.toSet())
        assertEquals(listOf("a1", "a2", "a3"), ids(get("/api/dump/items", mapOf("object_type[]" to "place_attempt"))))
        assertEquals(setOf("id", "label", "object_type"), json(get("/api/dump/items", mapOf("object_type" to "place_attempt", "fields" to "label")))["items"]!!.jsonArray[0].jsonObject.keys)
    }

    @Test fun placeAttemptsAreDeveloperOnlyGetOnlyAndWipeSafe() {
        attempts()
        dev = false; assertEquals(403, get("/api/dump/place_attempts")!!.status); dev = true
        assertEquals(405, get("/api/dump/place_attempts", m = "POST")!!.status)
        val before = src.lastArgs.size; dev = false; get("/api/dump/place_attempts"); dev = true; assertEquals("a refused call reads nothing", before, src.lastArgs.size)
    }
}
