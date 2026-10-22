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
    private fun plans0(s: PlanSender) = s.capturePlan.value!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun close() { scope.cancel(); prefs.edit().clear().commit() }

    @Test fun deleteAllDropsTheDesiredPlanSoAReconnectResendsNothing() = runBlocking {
        val s = sender(); s.onSessionReady(9_000); s.sendShadow("{\"p\":1}", "abc")
        link.sent.clear()
        prefs.edit().clear().commit() // what the wipe does to the persisted state
        s.resetAfterWipe()
        s.onSessionReady(20_000)
        assertTrue("no plan is re-sent after Delete all", plans().isEmpty())
        assertNull(s.capturePlan.value)
    }

    @Test fun aPlanBuiltBeforeADeleteAllIsNotPersistedAfterIt() = runBlocking {
        var counter = 1L
        val s = PlanSender(link, prefs, { "inst1" }, { now }, epochCounter = { val c = counter; counter++; c }) // the counter moves between build and persist
        val r = runCatching { s.sendShadow("{}", "h") }
        assertTrue(r.isFailure)
        assertNull("nothing was persisted", prefs.getString("msg", null))
    }

    @Test fun aDeleteAllThatClearsThePrefsAfterTheCheckButBeforeTheCommitLeavesNoPlanBehind() = runBlocking {
        var calls = 0
        // the epoch is the same at the check (call 2) and moves by the time the commit has landed (call 3): the wipe ran in between
        val s = PlanSender(link, prefs, { "inst1" }, { now }, epochCounter = { if (++calls <= 2) 0L else 1L })
        val r = runCatching { s.stage(PlanMode.SHADOW, "{}", "abc", "7", null) }
        assertTrue(r.isFailure)
        assertNull("the raced plan is compensated away", prefs.getString("msg", null))
        sender().let { restarted -> restarted.onSessionReady(9_000); assertTrue("a restart retransmits nothing", plans().isEmpty()); assertNull(restarted.capturePlan.value) }
    }

    @Test fun resetAfterWipeAlsoDropsThePersistedDesiredStateButKeepsTheGenCounter() = runBlocking {
        val s = sender(); s.send(PlanMode.SHADOW, "{}", "abc", "7", null)
        s.resetAfterWipe()   // nothing else clears the prefs here: a plan persisted by a send that raced the wipe must go too
        assertNull(prefs.getString("msg", null))
        val restarted = sender(); restarted.onSessionReady(9_000)
        assertTrue(plans().none { it.publishId == "7" && link.sent.size > 1 })
        assertNull(restarted.capturePlan.value)
        assertEquals("the counter survives", 2L, restarted.send(PlanMode.SHADOW, "{}", "abc", "8", null).gen)
    }

    @Test fun afterDeleteAllTheNextPublishIsStillAcceptedByAGlassesReceiverHoldingAnOlderGen() = runBlocking {
        val s = sender()
        var applied: com.rokid.context.capture.plan.ReceiverState? = null
        val rx = com.rokid.context.capture.plan.PlanReceiver(object : com.rokid.context.capture.plan.PlanPersistence {
            override fun load() = applied
            override fun commit(s: com.rokid.context.capture.plan.ReceiverState) { applied = s }
        }, { now }, { "boot" })
        var last = 0L
        repeat(5) { i -> last = s.stage(PlanMode.SETTINGS, null, null, "p$i", null); rx.onMessage(plans0(s)) }
        assertEquals(5L, last)
        com.rokid.context.orchestration.MemoryWipe.clearWipedPrefs(prefs, com.rokid.context.orchestration.MemoryWipe.PLAN_STATE_PREFS)   // what Delete all does to the persisted state
        s.resetAfterWipe()
        val g = sender().stage(PlanMode.SETTINGS, null, null, "p9", null)
        assertEquals(6L, g)
        val ack = rx.onMessage(plans0(sender()).copy(gen = g))
        assertTrue("accepted, not stale", ack.reason != "stale" && ack.state != PlanAckState.REJECTED)
    }

    @Test fun stagingPersistsWithoutTransmittingAndTransmitSendsTheStagedGen() = runBlocking {
        val s = sender(); s.onSessionReady(9_000); link.sent.clear()
        val g = s.stage(PlanMode.SHADOW, "{}", "abc", "7", null)
        assertTrue("staged is not on the link", plans().isEmpty()); assertNotNull(prefs.getString("msg", null))
        assertTrue(s.transmit(g)); assertEquals(g, plans().single().gen)
        assertEquals("a gen that is no longer desired is not sent", false, s.transmit(g + 5))
    }

    @Test fun discardDropsAStagedDesiredStateOfThatPublishOnly() = runBlocking {
        val s = sender(); s.stage(PlanMode.TRIAL, "{}", "abc", "7", 1000L)
        s.discard("other"); assertNotNull(s.capturePlan.value)
        s.discard("7"); assertNull(s.capturePlan.value); assertNull(prefs.getString("msg", null))
        sender().let { it.onSessionReady(9_000) }; assertTrue("never sent", plans().isEmpty())
    }

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

    @Test fun aRevertedGenIsNeverSentAgainOnTheNextSession() = runBlocking {
        val s = sender()
        s.onSessionReady(9_000)
        val g = s.send(PlanMode.TRIAL, "{}", "abc", "7", 3_600_000L).gen
        assertEquals(1, plans().size)
        s.onAck(CapturePlanAck(gen = g, publishId = "7", mode = PlanMode.TRIAL, state = PlanAckState.REVERTED, reason = "auto:trial_end"))
        assertNull("the reverted gen is no longer the desired state", s.capturePlan.value)
        now = 30_000; s.onSessionReady(25_000)
        assertEquals("nothing is re-sent", 1, plans().size)
        val restarted = sender()
        restarted.onSessionReady(40_000)
        assertEquals("nor after a restart of the process", 1, plans().size)
        assertEquals("a new send still takes a fresh gen", g + 1, restarted.send(PlanMode.SHADOW, "{}", "abc", "8", null).gen)
    }

    @Test fun aRevertedAckOfAnOlderGenLeavesTheNewerDesiredState() = runBlocking {
        val s = sender()
        val g1 = s.send(PlanMode.TRIAL, "{}", "abc", "7", 1L).gen
        val g2 = s.send(PlanMode.SHADOW, "{}", "abc", "8", null).gen
        s.onAck(CapturePlanAck(gen = g1, publishId = "7", mode = PlanMode.TRIAL, state = PlanAckState.REVERTED))
        assertEquals(g2, s.capturePlan.value!!.gen)
        s.onSessionReady(9_000)
        assertEquals(g2, plans().last().gen)
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

    @Test fun theGroupTransportAllocatesTheGenAndCarriesThePublishId() = runBlocking {
        val s = sender()
        s.onSessionReady(9_000)
        val out = s.send(PlanMode.SHADOW, "{\"p\":1}", "abc", "77", null)
        assertEquals(1L, out.gen); assertTrue(out.sent)
        val m = plans().single()
        assertEquals("77", m.publishId); assertEquals(PlanMode.SHADOW, m.mode); assertEquals("abc", m.configHash); assertEquals("inst1", m.phoneInstall); assertEquals(9_000L, m.sessionStartMs)
        val settings = s.send(PlanMode.SETTINGS, null, null, "78", null)
        assertEquals(2L, settings.gen); assertNull(plans().last().planJson); assertEquals("78", plans().last().publishId)
    }

    @Test fun aSettingsDesiredStateIsSentOnEverySessionReadyWithTheSessionStamp() = runBlocking {
        val s = sender()
        s.send(PlanMode.SETTINGS, null, null, "5", null)
        link.sent.clear(); now = 20_000
        s.onSessionReady(15_000)
        val m = plans().single()
        assertEquals(PlanMode.SETTINGS, m.mode); assertEquals(1L, m.gen); assertEquals(15_000L, m.sessionStartMs); assertEquals("5", m.publishId)
        link.sent.clear(); s.onSessionReady(16_000)
        assertEquals("again on the next session", 16_000L, plans().single().sessionStartMs)
    }
}
