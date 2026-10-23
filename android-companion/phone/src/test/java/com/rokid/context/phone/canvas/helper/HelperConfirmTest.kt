package com.rokid.context.phone.canvas.helper

import com.rokid.context.core.log.Level
import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.CanvasRouter
import com.rokid.context.phone.canvas.server.HelperAuth
import com.rokid.context.phone.canvas.server.HostPolicy
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.AssetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** WP2 RED group (user decision 2026-10-23 evening): the memory-only HMAC binding of one native tap to one job, hash, session and expiry. */
class HelperConfirmTest {
    private val fixtures: JsonObject = run {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "tools/canvas-helper/protocol").isDirectory) d = d.parentFile
        Json.parseToJsonElement(File(requireNotNull(d), "tools/canvas-helper/protocol/confirm-vectors.json").readText()).jsonObject
    }
    private val keyHex = fixtures["testKeyHex"]!!.jsonPrimitive.content
    private fun hex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun confirm() = HelperConfirm.forTest(hex(keyHex))

    private class Case(val name: String, val binding: ConfirmBinding, val message: String, val tag: String, val accept: Boolean)
    private val cases: List<Case> = fixtures["cases"]!!.jsonArray.map { c ->
        val o = c.jsonObject; val b = o["binding"]!!.jsonObject
        Case(
            o["name"]!!.jsonPrimitive.content,
            ConfirmBinding(b["jobId"]!!.jsonPrimitive.content, b["bundleHash"]!!.jsonPrimitive.content, b["sessionGen"]!!.jsonPrimitive.longOrNull!!, b["expiresAtMs"]!!.jsonPrimitive.longOrNull!!),
            o["expectedMessage"]!!.jsonPrimitive.content, o["expectedTag"]!!.jsonPrimitive.content, o["accept"]!!.jsonPrimitive.booleanOrNull!!,
        )
    }
    private val accepted get() = cases.single { it.accept }

    @Test fun tagMatchesTheSharedHmacVectors() {
        val c = confirm()
        assertEquals(5, cases.size)
        for (k in cases) {
            assertEquals(k.name, k.message, k.binding.message())
            assertEquals(k.name, k.tag, c.tag(k.binding))
        }
    }

    @Test fun tagBoundToJobHashSessionAndExpiry() {
        val c = confirm()
        val tag = c.tag(accepted.binding)
        assertTrue(c.verify(accepted.binding, tag, nowMs = accepted.binding.expiresAtMs - 1))
        for (k in cases.filter { !it.accept }) {
            assertFalse("${k.name}: the accepted tag must not verify another binding", c.verify(k.binding, tag, nowMs = 0L))
            assertTrue("${k.name}: its own tag verifies", c.verify(k.binding, k.tag, nowMs = 0L))
        }
        // a different bundle hash, taken by itself
        val other = accepted.binding.copy(bundleHash = cases.single { it.name == "otherBundleHash" }.binding.bundleHash)
        assertFalse(c.verify(other, tag, 0L))
        // expiry: the tap window ends at expiresAtMs
        assertFalse(c.verify(accepted.binding, tag, nowMs = accepted.binding.expiresAtMs))
        assertFalse(c.verify(accepted.binding, tag, nowMs = accepted.binding.expiresAtMs + 5_000))
        // malformed tags are refused, never thrown on
        for (bad in listOf("", "zz", tag.uppercase(), tag.dropLast(1), tag + "0", tag.dropLast(1) + (if (tag.last() == '0') '1' else '0'), " $tag")) assertFalse("'$bad'", c.verify(accepted.binding, bad, 0L))
    }

    @Test fun restartInvalidatesKey() {
        val first = HelperConfirm()
        val b = accepted.binding
        val tag = first.tag(b)
        assertTrue(first.verify(b, tag, 0L))
        assertFalse("a new process (new instance) has a new key", HelperConfirm().verify(b, tag, 0L))
        assertNotEquals(first.tag(b), HelperConfirm().tag(b))
        // a new dev session on the same instance rotates the key too
        first.rotate()
        assertFalse(first.verify(b, tag, 0L))
        assertTrue(first.verify(b, first.tag(b), 0L))
    }

    @Test fun keyIsMemoryOnlyAndNeverExposed() {
        val c = confirm()
        // no public member hands out the key
        val exposed = HelperConfirm::class.java.methods.filter { it.declaringClass == HelperConfirm::class.java }
            .filter { ByteArray::class.java.isAssignableFrom(it.returnType) || javax.crypto.SecretKey::class.java.isAssignableFrom(it.returnType) || it.returnType == java.security.Key::class.java }
        assertTrue("public members returning key material: $exposed", exposed.isEmpty())
        assertFalse(c.toString().contains(keyHex))
        assertFalse(c.toString().contains(accepted.tag))
        // the class has no Context and no prefs: nothing to persist with
        val src = File("src/main/java/com/rokid/context/phone/canvas/helper/HelperConfirm.kt").readText()
        for (banned in listOf("SharedPreferences", "Context", "android.util.Log", "File(", "putString", "println(")) assertFalse("HelperConfirm.kt mentions $banned", src.contains(banned))
    }

    @Test fun neitherTheKeyNorATagReachesALogLine() {
        val lines = mutableListOf<String>()
        val c = HelperConfirm.forTest(hex(keyHex), log = { _: Level, tag: String, msg: String -> lines += msg })
        val tag = c.tag(accepted.binding)
        c.verify(accepted.binding, tag, 0L)
        c.verify(accepted.binding, "bad", 0L)
        c.verify(accepted.binding, tag, accepted.binding.expiresAtMs)
        c.rotate()
        assertTrue("some count-only line was logged", lines.isNotEmpty())
        for (l in lines) {
            for (secret in listOf(keyHex, tag, accepted.binding.jobId, accepted.binding.bundleHash, accepted.binding.expiresAtMs.toString())) assertFalse("'$l' carries a secret", l.contains(secret))
            assertTrue("'$l' is not a count-only shape", Regex("^[a-z]+( [a-z_]+){0,4}( [0-9]+)?$").matches(l))
        }
    }

    // ---- no HTTP surface, no way to the key for either principal ---------------------------------------------------------------------------------------

    private class Rig {
        var now = 5_000_000L
        val browser = CanvasAuth(clock = { now })
        val helper = HelperAuth(clock = { now }, log = { _, _, _ -> })
        val hits = mutableListOf<HttpRequest>()
        val notFound = ApiHandler { hits += it; HttpResponse.error(404, "not_found", "Not found.") }
        val router = CanvasRouter(browser, HostPolicy({ 8765 }, { null }), notFound, AssetSource { null }, helperAuth = helper, helperApi = notFound)
        fun req(m: String, p: String, tok: String?, body: String = "") =
            HttpRequest(m, p, emptyMap(), buildMap { put("host", "127.0.0.1:8765"); if (tok != null) put("authorization", "Bearer $tok") }, body.toByteArray(), remoteIp = "10.0.0.5")
        fun helperToken(): String { helper.acceptPairing(); val code = helper.pairingCode()!!.code
            val r = (helper.pair(code, "h", "10.0.0.5") as HelperAuth.PairResult.Ok); return r.token }
        fun browserToken(): String = (browser.pair(browser.newCode().code) as CanvasAuth.Pair.Ok).token
    }

    @Test fun confirmHasNoHttpSurface() {
        val rig = Rig()
        val ht = rig.helperToken(); val bt = rig.browserToken()
        val paths = listOf(
            "/api/helper/confirm", "/api/helper/jobs/job-1/confirm", "/api/helper/jobs/job-1/decline", "/api/helper/decline", "/api/helper/pending", "/api/helper/confirmations",
            "/api/helper-jobs/job-1/confirm", "/api/helper-jobs/job-1/decline", "/api/helper-jobs/pending", "/api/helper-status/pending", "/api/helper-status/confirm",
            "/api/runs/1/confirm", "/api/runs/confirm", "/api/confirm", "/api/pending", "/confirm", "/api/helper/jobs/job-1/cancel-confirm",
        )
        val bodies = listOf("", """{"tag":"ab","jobId":"job-1"}""", """{"confirm":true}""")
        for (p in paths) for (m in listOf("GET", "POST", "PUT", "DELETE")) for (tok in listOf(null, ht, bt)) for (b in bodies) {
            val res = rig.router.handle(rig.req(m, p, tok, b))
            assertTrue("$m $p ${if (tok == null) "anon" else "tok"} answered ${res.status}", res.status in 400..499)
        }
    }

    @Test fun helperAndBrowserTokensCannotMintBinding() {
        val keyBytes = hex(keyHex)
        val c = HelperConfirm.forTest(keyBytes)
        val rig = Rig()
        val ht = rig.helperToken(); val bt = rig.browserToken()
        val everyTag = cases.map { c.tag(it.binding) }
        val secrets = listOf(keyHex, java.util.Base64.getEncoder().encodeToString(keyBytes), java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes)) + everyTag
        val paths = listOf("/api/session", "/api/pipelines", "/api/helper/poll", "/api/helper/lease", "/api/helper/jobs/job-1/payload", "/api/helper-status", "/api/helper-jobs", "/api/helper/pair", "/index.html", "/")
        for (p in paths) for (m in listOf("GET", "POST")) for (tok in listOf(null, ht, bt)) {
            val res = rig.router.handle(rig.req(m, p, tok, """{"helperProtocol":1,"code":"000000","name":"x"}"""))
            val text = String(res.body, Charsets.UTF_8) + res.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            for (s in secrets) assertFalse("$m $p leaked key material or a tag", text.contains(s))
        }
        // and the pair response itself (the one body that carries a credential) holds the helper token only
        rig.helper.acceptPairing()
        val pr = rig.router.handle(rig.req("POST", "/api/helper/pair", null, """{"helperProtocol":1,"code":"${rig.helper.pairingCode()!!.code}","name":"x"}"""))
        assertEquals(200, pr.status)
        val body = String(pr.body, Charsets.UTF_8)
        for (s in secrets) assertFalse(body.contains(s))
    }

    @Test fun sourceHasNoRouteThatReachesConfirm() {
        val dir = File("src/main/java/com/rokid/context/phone/canvas")
        val offenders = dir.walkTopDown().filter { it.extension == "kt" && ("/server/" in it.path || "/api/" in it.path) }
            .filter { Regex("HelperConfirm\\b(?!Actions)").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertTrue("HTTP-side files that reference HelperConfirm (the key holder): $offenders", offenders.isEmpty())
    }
}
