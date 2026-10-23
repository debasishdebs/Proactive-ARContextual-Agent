package com.rokid.context.phone.canvas.helper

import com.rokid.context.core.log.Level
import com.rokid.context.core.log.Line
import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.log.Route
import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.AssetSource
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.CanvasRouter
import com.rokid.context.phone.canvas.server.HelperAuth
import com.rokid.context.phone.canvas.server.HostPolicy
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import com.rokid.context.phone.log.PhoneLogging
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** WP2 RED group: the 3b phone lines are count-only (no ids, tokens, codes, names or payload fields) and go through the gated port. */
class CanvasLogLineShapeTest {
    private val lines = mutableListOf<Line>()
    private val sink = object : LogSink { override fun emit(line: Line, route: Route) { synchronized(lines) { lines += line } } }

    @Before fun install() { PcLog.testOnlyResetNeverCallFromProduction(); PcLog.install(sink); PcLog.setDiagnostic(true) }
    @After fun reset() { PcLog.testOnlyResetNeverCallFromProduction() }

    private val shape = Regex("^[a-z]+( [a-z_]+){0,4}( [0-9]+)?$")
    private val secretName = "Debasish-MacBook-Secret"
    private val jobId = "job-4f2a9c"
    private val hash = "73449d42e9b6b083761e50cfdf6fbe631946f4c12dd02ac8e5c64eaedeb2850f"

    @Test fun everyHelperLineIsCountOnlyAndGated() {
        val now = 1_000L
        val helper = HelperAuth(clock = { now })                                        // default port = PhoneLogging.canvasPort()
        val grants = HelperGrants(MemoryGrantStore())
        val native = HelperNative(helper, grants, clock = { now })
        val confirm = HelperConfirm()
        native.acceptPairing()
        val code = helper.pairingCode()!!.code
        val wrong = if (code == "000000") "000001" else "000000"
        helper.pair(wrong, secretName, "10.0.0.4")
        val token = (helper.pair(code, secretName, "10.0.0.4") as HelperAuth.PairResult.Ok).token
        helper.pair(code, secretName, "10.0.0.4")                                       // no code now
        native.setGrant(HelperGrants.PY_EXEC, true)
        native.setGrant(HelperGrants.PY_EXEC, false)
        val b = ConfirmBinding(jobId, hash, 3, 9_000L)
        val tag = confirm.tag(b)
        confirm.verify(b, tag, 0L); confirm.verify(b, "bad", 0L); confirm.verify(b, tag, 9_000L); confirm.rotate()
        // the router's request line for a helper route keeps to method + route template + status + ms
        val routerLines = mutableListOf<String>()
        val api = ApiHandler { HttpResponse.json(200, kotlinx.serialization.json.JsonObject(emptyMap())) }
        val router = CanvasRouter(CanvasAuth({ now }), HostPolicy({ 8765 }, { null }), api, AssetSource { null }, helperAuth = helper, helperApi = api, log = { routerLines += it })
        router.handle(HttpRequest("POST", "/api/helper/jobs/$jobId/payload", mapOf("leaseGen" to "3"), mapOf("host" to "127.0.0.1:8765", "authorization" to "Bearer $token"), ByteArray(0)))
        native.disconnectHelper()

        assertTrue("helper lines were logged: ${lines.size}", lines.count { it.msg.startsWith("helper ") } >= 9)
        val secrets = listOf(token, code, wrong, secretName, jobId, hash, tag, "10.0.0.4", "3")
        for (l in lines.filter { it.msg.startsWith("helper ") }) {
            assertEquals("canvas", l.tag)
            assertTrue("'${l.msg}' is not a count-only shape", shape.matches(l.msg))
            for (s in secrets.dropLast(1)) assertFalse("'${l.msg}' carries a secret", l.msg.contains(s))
        }
        assertEquals(1, routerLines.size)
        assertTrue(routerLines[0], Regex("^POST /api/helper/:id/:id/:id 200 [0-9]+ms$").matches(routerLines[0]))
        for (s in secrets.dropLast(1)) assertFalse(routerLines[0].contains(s))
    }

    @Test fun theProductionPortIsTheGatedPcLogAdapter() {
        // a W line through the production port reaches the installed sink, and a line logged before any install would sit in PcLog's ring: it is PcLog, not a sink
        PhoneLogging.canvasPort().log(Level.W, "canvas", "helper probe")
        assertTrue(lines.any { it.msg == "helper probe" && it.level == Level.W })
        val src = java.io.File("src/main/java/com/rokid/context/phone/canvas")
        val raw = src.walkTopDown().filter { it.extension == "kt" && ("/helper/" in it.path || it.name in setOf("HelperAuth.kt", "PairingLimiter.kt", "CanvasRouter.kt", "CanvasGraph.kt", "HelperCards.kt")) }
            .filter { Regex("android\\.util\\.Log|\\bLog\\.[diwev]\\(").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertTrue("raw android.util.Log in 3b files: $raw", raw.isEmpty())
    }
}
