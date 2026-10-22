package com.rokid.context.phone.hub.plan

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.CapturePlanMessage
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.Wire
import com.rokid.context.link.FakeGlassesLink
import com.rokid.context.phone.canvas.CanvasCore
import com.rokid.context.phone.canvas.CanvasGraph
import com.rokid.context.phone.canvas.HomeData
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.AssetSource
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PlanSenderTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = ctx.getSharedPreferences("plan_sender_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val link = FakeGlassesLink()
    private var now = 10_000L
    private fun sender(p: android.content.SharedPreferences = prefs) = PlanSender(link, p, { "inst1" }, { now })
    private fun plans() = link.sent.filter { it.first == Channels.CAPTURE_PLAN }.map { (Wire.decode<CapturePlanMessage>(it.second) as Decoded.Ok).value }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun close() { scope.cancel(); prefs.edit().clear().commit() }

    @Test fun sendsAShadowPlanStampedForTheSession() = runBlocking {
        val s = sender()
        s.onSessionReady(9_000)
        val r = s.sendShadow("{\"p\":1}", "abc")
        assertEquals(1L, r.gen); assertTrue(r.sent)
        val m = plans().single()
        assertEquals(PlanMode.SHADOW, m.mode); assertEquals("abc", m.configHash); assertEquals("inst1", m.phoneInstall); assertEquals(9_000L, m.sessionStartMs)
        assertTrue("not a stale copy", m.atMs >= m.sessionStartMs); assertNull(m.trialEndsAtMs); assertNull(m.trialMaxMs)
    }

    @Test fun anAckOfTheOutstandingGenIsKept() = runBlocking {
        val s = sender()
        val g = s.sendShadow("{}", "abc").gen
        s.onAck(CapturePlanAck(gen = g - 1, state = PlanAckState.EFFECTIVE)); assertNull(s.lastAck.value)
        s.onAck(CapturePlanAck(gen = g, state = PlanAckState.EFFECTIVE)); assertEquals(PlanAckState.EFFECTIVE, s.lastAck.value!!.state)
    }

    @Test fun aSendWithNoSessionIsStoredAndSentWhenOneOpens() = runBlocking {
        link.setState(com.rokid.context.core.model.LinkState.OFFLINE)
        val s = sender()
        val r = s.sendShadow("{}", "abc")
        assertEquals(false, r.sent); assertTrue(plans().isEmpty())
        link.setState(com.rokid.context.core.model.LinkState.CONNECTED)
        now = 20_000
        s.onSessionReady(15_000)
        assertEquals(r.gen, plans().single().gen)
    }

    @Test fun reconcileSurvivesCanvasServerStop() = runBlocking {
        val dev = MutableStateFlow(true)
        val core = object : CanvasCore {
            override val api = ApiHandler { HttpResponse.error(404, "not_found", "x") }
            override suspend fun start() {}
            override fun runActive() = false
            override suspend fun cancelActive() {}
            override suspend fun home() = HomeData(emptyList(), emptyList(), false)
            override suspend fun rollback(expectedId: Long): CanvasPublisher.Outcome = CanvasPublisher.Outcome.Blocked(emptyList())
            override suspend fun pipeline(id: String): Pipeline? = null
        }
        val graph = CanvasGraph(CanvasAuth({ now }), core, AssetSource { null }, dev, wifiIp = { null }, scope = scope, clock = { now })
        val s = sender()
        graph.start(false)
        val first = s.sendShadow("{\"p\":1}", "abc")
        // the canvas goes away: Developer mode off stops the server and revokes the sessions
        dev.value = false
        graph.stop()
        assertTrue("server stopped", !graph.status.value.on)
        // the glasses reconnect: the hub re-sends the desired plan, same gen, whatever the canvas is doing
        link.sent.clear(); now = 30_000
        s.onSessionReady(25_000)
        val again = plans().single()
        assertEquals(first.gen, again.gen); assertEquals(PlanMode.SHADOW, again.mode); assertEquals("abc", again.configHash)
        // and a restarted process (a new sender over the same prefs) still holds it and continues the gen
        val s2 = sender()
        assertEquals(first.gen, s2.capturePlan.value!!.gen)
        assertEquals(first.gen + 1, s2.sendShadow("{\"p\":2}", "def").gen)
    }

    @Test fun revertToSettingsIsANewGenAndIsResentLikeAnyDesiredState() = runBlocking {
        val s = sender()
        s.sendShadow("{}", "abc")
        val r = s.revertToSettings()
        assertEquals(2L, r.gen)
        assertEquals(PlanMode.SETTINGS, plans().last().mode); assertNull(plans().last().planJson)
        assertNotNull(s.capturePlan.value)
    }
}
