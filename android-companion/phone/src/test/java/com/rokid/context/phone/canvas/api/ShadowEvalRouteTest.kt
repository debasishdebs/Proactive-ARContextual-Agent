package com.rokid.context.phone.canvas.api

import com.rokid.context.orchestration.proactive.FeedbackAction
import com.rokid.context.orchestration.proactive.FeedbackState
import com.rokid.context.orchestration.proactive.ProactiveFamily
import com.rokid.context.orchestration.proactive.ShadowEvalInput
import com.rokid.context.orchestration.proactive.SuggestionRecord
import com.rokid.context.orchestration.proactive.TriggerKind
import com.rokid.context.phone.canvas.server.HttpRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** `GET /api/proactive/shadow-eval`: Developer-gated like the replay route, text/plain, counts and fixed labels only. */
class ShadowEvalRouteTest {
    private val dev = MutableStateFlow(true)
    private var calls = 0
    private val secret = "GolcondaFortSECRET"
    private val f2 = ProactiveFamily.PLACE_RECALL
    private val rec = SuggestionRecord("id1", 1_800_000_000_000L, "f2:$secret", f2, TriggerKind.TICK, 0.8, "wd:$secret", listOf("mem:$secret", "frame:$secret"), listOf(secret))
    private fun input() = ShadowEvalInput(
        listOf(rec), mapOf("runs" to 5, "would_show" to 1, "drop_$secret" to 3, secret to 2), FeedbackState().after(FeedbackAction.DISMISS, "f2:$secret", f2, 1L),
        neverCount = 1, dailyCounters = mapOf(secret to mapOf("runs" to 1)), zone = ZoneId.of("UTC"), quietAt = { false }, zoneBlocked = { false },
    )
    private fun route() = ShadowEvalRoute(dev) { calls++; input() }
    private fun req(method: String = "GET", path: String = "/api/proactive/shadow-eval") = HttpRequest(method, path, emptyMap(), emptyMap(), ByteArray(0))
    private fun get(r: ShadowEvalRoute = route()) = runBlocking { r.handle(req())!! }

    @Test fun developerModeOffRefusesAndReadsNothing() {
        dev.value = false
        val r = get()
        assertEquals(403, r.status); assertTrue(String(r.body).contains("developer_mode_off")); assertEquals(0, calls)
        dev.value = true
        assertEquals(200, get().status); assertEquals(1, calls)
    }

    @Test fun otherPathsAndMethodsAreNotThisRoute() = runBlocking {
        assertNull(route().handle(req(path = "/api/proactive/other")))
        assertEquals(405, route().handle(req(method = "POST"))!!.status)
    }

    @Test fun theBodyIsTheTextReportWithFixedNotesAndNoRecordText() {
        val r = get()
        assertEquals(200, r.status); assertTrue(r.contentType.startsWith("text/plain"))
        val body = String(r.body)
        assertTrue(body.startsWith("shadow-eval verdict=")); assertTrue(body.contains("CUMULATIVE")); assertTrue(body.contains("invalid day keys: 1"))
        assertFalse(body.contains("SECRET")); assertFalse(body.contains("Golconda"))
    }

    @Test fun aFailureIsAFixedMessageWithNothingEchoed() {
        val r = runBlocking { ShadowEvalRoute(dev) { throw IllegalStateException(secret) }.handle(req())!! }
        assertEquals(500, r.status); assertFalse(String(r.body).contains("SECRET"))
    }

    @Test fun theRouteIsRegisteredAndGoesThroughTheApiWhenGiven() {
        val main = File("src/main/java/com/rokid/context/phone/canvas")
        assertTrue(File(main, "CanvasExtras.kt").readText().contains("shadowEval?.let"))
        assertTrue(File(main, "CanvasExtras.kt").readText().contains("ShadowEvalRoute(dev.enabled)"))
    }
}
