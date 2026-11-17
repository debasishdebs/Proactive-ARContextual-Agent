package com.rokid.context.phone.nexus

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.HudHost
import io.github.pca.nexusport.DropReason
import io.github.pca.nexusport.HudEvent
import io.github.pca.nexusport.NexusActionHandler
import io.github.pca.nexusport.NexusPort
import io.github.pca.nexusport.NexusPortCallback
import io.github.pca.nexusport.NexusResult
import io.github.pca.nexusport.Outcome
import io.github.pca.nexusport.Slot
import io.github.pca.nexusport.TripReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A fake bridge: an in-process [NexusPort] that records what PCA sent. */
private class FakePort : NexusPort.Stub() {
    val shown = mutableListOf<Int>()
    val hidden = mutableListOf<Int>()
    var forgets = 0
    var forgetGen = 0L
    var features = -1
    var registered: NexusPortCallback? = null
    var showCode = NexusResult.OK
    override fun apiVersion() = 1
    override fun setCallback(callback: NexusPortCallback?) { registered = callback }
    override fun setFeatures(mask: Int): Int { features = mask; return NexusResult.OK }
    override fun show(slot: Int, title: String?, text: String?, ttlMs: Long, wake: Boolean, actionIds: Array<out String>?, actionLabels: Array<out String>?): Int { if (showCode == NexusResult.OK) shown += slot; return showCode }
    override fun hide(slot: Int): Int { hidden += slot; return NexusResult.OK }
    override fun speak(text: String?) = NexusResult.OK
    override fun stopSpeech() = NexusResult.OK
    override fun forgetHistory(): Int { forgets++; return NexusResult.OK }
    override fun showAt(epoch: Long, slot: Int, title: String?, text: String?, ttlMs: Long, wake: Boolean, actionIds: Array<out String>?, actionLabels: Array<out String>?) = show(slot, title, text, ttlMs, wake, actionIds, actionLabels)
    override fun speakAt(epoch: Long, text: String?) = NexusResult.OK
    override fun tripAt(epoch: Long) = NexusResult.OK
    override fun forgetHistoryAt(generation: Long): Int { forgets++; forgetGen = generation; return NexusResult.OK }
    /** The hub accepts the newest wipe request. */
    fun ack() { registered?.onForgetAcceptedAt(forgetGen) }
    override fun stopListening() = NexusResult.OK
    override fun setSkillHost(host: io.github.pca.nexusport.NexusSkillHost?) = NexusResult.OK
}

private class FakeLink(val port: FakePort, private val result: BridgeLink.Bind = BridgeLink.Bind.BOUND) : BridgeLink {
    var binds = 0; var unbinds = 0
    var trustedNow = true
    var deferConnect = false
    var connected: ((IBinder) -> Unit)? = null; var gone: (() -> Unit)? = null
    override fun bind(onConnected: (IBinder) -> Unit, onGone: () -> Unit): BridgeLink.Bind {
        binds++; connected = onConnected; gone = onGone
        if (result == BridgeLink.Bind.BOUND && !deferConnect) onConnected(port)
        return result
    }
    override fun unbind() { unbinds++ }
    override fun trusted() = trustedNow
}

/** v2.0: the flag-OFF provider, the fail-closed gates through the real sink, and the trip fan-out. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NexusProviderTest {
    private var host = HudHost.PCA
    private var live = true; private var zone = false; private var ask = false; private var paused = false; private var worn = true
    private var liveThrows = false; private var zoneThrows = false
    private val port = FakePort()
    private val link = FakeLink(port)
    private val actions = object : NexusActionHandler { override fun mark(ref: String) = Unit; override fun more(ref: String) = Unit }

    private val debt = MemoryWipeDebt()
    private fun TestScope.provider(d: WipeDebt = debt, l: BridgeLink = link): NexusProvider {
        val gates = PcaNexusGates(
            sourcesLive = { if (liveThrows) throw IllegalStateException("db") else live },
            zoneFence = { if (zoneThrows) throw IllegalStateException("fence") else zone },
            askFence = { ask }, paused = { paused }, worn = { worn },
        )
        return NexusProvider(l, { host }, gates, actions, backgroundScope, clock = { testScheduler.currentTime }, debt = d, directDispatch = true)
    }

    @Test fun aSettingChangeCallsRefreshFeaturesAndPushesTheMask() = runTest {
        host = HudHost.NEXUS
        var sw = NexusSwitches()
        val gates = PcaNexusGates({ true }, { false }, { false }, { false }, { true })
        val p = NexusProvider(link, { host }, gates, actions, backgroundScope, clock = { testScheduler.currentTime }, switches = { sw }, directDispatch = true)
        p.refresh()
        assertEquals(0, port.features) // everything off by default: no TTS, no STT, no assist, no skills
        sw = NexusSwitches(voice = true, tts = true, skills = true)
        p.refreshFeatures()
        val f = io.github.pca.nexusport.NexusConfig(enabled = true, ttsEnabled = true, sttEnabled = true, assistTakeover = true, skillsEnabled = true).featureMask()
        assertTrue(f != 0); assertEquals(f, port.features)
        sw = NexusSwitches(); p.refreshFeatures(); assertEquals(0, port.features)
    }

    @Test fun refreshFeaturesBeforeAnythingIsConnectedIsANoOp() = runTest {
        provider().refreshFeatures()
        assertEquals(-1, port.features)
    }

    private val nudge = HudEvent.Nudge("n1", "Heads up", "Keys are on the shelf", setOf("m1"), ttlMs = 6_000)
    private val pin = HudEvent.CaptureState(HudEvent.CaptureState.State.CAPTURING)

    @Test fun everythingIsOffByDefaultNothingBindsNothingIsSent() = runTest {
        val p = provider()
        p.refresh()
        assertEquals(0, link.binds)
        assertEquals(null, p.show(nudge))
        assertTrue(port.shown.isEmpty() && port.hidden.isEmpty())
        p.trip(TripReason.FORGET) // no sink: a no-op, never an error
        assertEquals(0, port.forgets)
        assertFalse(p.bound.value)
    }

    @Test fun chosenNexusBindsAndAnOpenGateShowsTheItem() = runTest {
        host = HudHost.NEXUS
        val p = provider(); p.refresh()
        assertEquals(1, link.binds); assertTrue(p.bound.value)
        assertEquals(Outcome.Shown(false), p.show(nudge))
        assertTrue(Slot.NOTICE.code in port.shown)
        assertEquals(NexusResult.OK, p.lastCode.value)
    }

    @Test fun aThrowingGateDropsTheItemAndNothingReachesTheBridge() = runTest {
        host = HudHost.NEXUS
        val p = provider(); p.refresh(); port.shown.clear()
        liveThrows = true
        assertEquals(Outcome.Dropped(DropReason.PROVENANCE), p.show(nudge))
        liveThrows = false; zoneThrows = true
        assertEquals(Outcome.Dropped(DropReason.ZONE_HELD), p.show(nudge))
        assertEquals(Outcome.Dropped(DropReason.ZONE_HELD), p.show(pin))
        zoneThrows = false
        assertTrue("nothing was drawn", port.shown.isEmpty())
    }

    @Test fun eachFenceAndStateClosesTheGateToo() = runTest {
        host = HudHost.NEXUS
        val p = provider(); p.refresh(); port.shown.clear()
        live = false; assertEquals(Outcome.Dropped(DropReason.PROVENANCE), p.show(nudge)); live = true
        zone = true; assertEquals(Outcome.Dropped(DropReason.ZONE_HELD), p.show(nudge)); zone = false
        ask = true; assertEquals(Outcome.Dropped(DropReason.ZONE_HELD), p.show(nudge)); ask = false
        paused = true; assertEquals(Outcome.Dropped(DropReason.PAUSED), p.show(nudge)); paused = false
        worn = false; assertEquals(Outcome.Dropped(DropReason.NOT_WORN), p.show(nudge)); worn = true
        assertTrue(port.shown.isEmpty())
        assertEquals(Outcome.Shown(false), p.show(nudge))
    }

    @Test fun everyTripReasonHidesEveryLiveHandleAndOnlyTheOwingOnesAskForTheHistoryWipe() = runTest {
        host = HudHost.NEXUS
        val p = provider(); p.refresh()
        val wipes = setOf(TripReason.FORGET, TripReason.DELETE_ALL, TripReason.MODE_OFF)
        for (reason in listOf(TripReason.ZONE_HOLD, TripReason.FORGET, TripReason.DELETE_ALL, TripReason.PAUSE, TripReason.MODE_OFF, TripReason.NOT_WORN, TripReason.LINK_LOST)) {
            port.hidden.clear(); val before = port.forgets
            assertEquals(Outcome.Shown(false), p.show(nudge)); assertEquals(Outcome.Shown(false), p.show(pin))
            assertEquals(setOf(Slot.NOTICE, Slot.PIN), p.liveSlots())
            p.trip(reason)
            assertEquals("$reason", emptySet<Slot>(), p.liveSlots())
            assertTrue("$reason hid the notice and the pin", port.hidden.containsAll(listOf(Slot.NOTICE.code, Slot.PIN.code)))
            assertEquals("$reason history wipe", if (reason in wipes) before + 1 else before, port.forgets)
        }
    }

    @Test fun aTripBetweenTheGateCheckAndTheDrawWins() = runTest {
        host = HudHost.NEXUS
        lateinit var p: NexusProvider
        // the provenance read is where the show is "in flight": a Forget lands there, after the first gate check and before the draw
        val tripping = PcaNexusGates(sourcesLive = { p.trip(TripReason.FORGET); true }, zoneFence = { false }, askFence = { false }, paused = { false }, worn = { true })
        p = NexusProvider(link, { host }, tripping, actions, backgroundScope, directDispatch = true)
        p.refresh(); port.shown.clear()
        assertEquals(Outcome.Dropped(DropReason.TRIPPED), p.show(nudge))
        assertTrue("nothing was drawn", port.shown.isEmpty())
        assertEquals(emptySet<Slot>(), p.liveSlots())
    }

    @Test fun switchingTheHostAwayWithdrawsAndUnbindsOnceNexusAcceptedTheWipe() = runTest {
        host = HudHost.NEXUS
        val p = provider(); p.refresh()
        p.show(nudge); p.show(pin)
        host = HudHost.PCA
        p.refresh()
        assertEquals(emptySet<Slot>(), p.liveSlots())
        assertEquals("a MODE_OFF trip asks for the wipe", true, port.forgets >= 1)
        assertEquals(0, port.features)
        assertEquals("still bound: the wipe is not accepted yet", 0, link.unbinds)
        port.ack()
        advanceTimeBy(1_500)
        assertEquals(1, link.unbinds)
        assertFalse(p.bound.value)
        assertEquals("after the switch nothing is sent", null, p.show(nudge))
    }

    @Test fun anUnansweredWipeDoesNotKeepTheBridgeBoundForever() = runTest {
        host = HudHost.NEXUS
        val p = provider(); p.refresh()
        host = HudHost.PCA; p.refresh()
        advanceTimeBy(20_000); assertEquals(0, link.unbinds)
        advanceTimeBy(15_000); assertEquals(1, link.unbinds)
    }

    @Test fun anUninstalledOrForeignBridgeIsReportedNotRetried() = runTest {
        host = HudHost.NEXUS
        val refusing = FakeLink(port, BridgeLink.Bind.REFUSED)
        val p = NexusProvider(refusing, { host }, PcaNexusGates({ true }, { false }, { false }, { false }, { true }), actions, backgroundScope, directDispatch = true)
        p.refresh(); p.refresh()
        assertEquals(BridgeLink.Bind.REFUSED, p.bind.value)
        assertEquals(2, refusing.binds) // asked again only on the next refresh (a start or a switch), never in a loop
        assertEquals(null, p.show(nudge))
    }

    @Test fun aQuietGiveUpFromTheHubIsRecordedAsTheStateEvidence() = runTest {
        host = HudHost.NEXUS
        val p = provider(); p.refresh()
        port.showCode = NexusResult.NOT_REGISTERED
        assertEquals(Outcome.Dropped(DropReason.TRANSPORT, NexusResult.NOT_REGISTERED), p.show(nudge))
        assertEquals(NexusResult.NOT_REGISTERED, p.lastCode.value)
    }

    // ---- Codex v2 r1 P1-3: a spoofed bridge ----

    @Test fun anUntrustedBridgeIsNeverBoundAndNeverGetsABinder() = runTest {
        host = HudHost.NEXUS; link.trustedNow = false
        val p = provider(); p.refresh()
        assertEquals(0, link.binds)
        assertEquals(BridgeLink.Bind.UNTRUSTED, p.bind.value)
        assertEquals(null, port.registered)       // no callback, no skill host, nothing handed over
        assertFalse(p.bound.value)
        assertEquals(null, p.show(nudge))
    }

    @Test fun aConnectionFromAPackageThatTurnedUntrustedIsDroppedBeforeAnythingIsHandedOver() = runTest {
        host = HudHost.NEXUS; link.deferConnect = true
        val p = provider(); p.refresh()
        assertEquals(1, link.binds)
        link.trustedNow = false                    // the package was replaced between the bind and the connection
        link.connected!!(port)
        assertEquals(null, port.registered); assertFalse(p.bound.value)
        assertEquals(BridgeLink.Bind.UNTRUSTED, p.bind.value); assertEquals(1, link.unbinds)
    }

    @Test fun aReplacedBridgeIsVerifiedAgainOnThePackageBroadcastAndRecoversWhenTrustedAgain() = runTest {
        host = HudHost.NEXUS
        val p = provider(); p.refresh()
        assertTrue(p.bound.value); assertEquals(Outcome.Shown(false), p.show(nudge))
        link.trustedNow = false; p.onPackageChanged()
        assertFalse(p.bound.value); assertEquals(BridgeLink.Bind.UNTRUSTED, p.bind.value); assertEquals(1, link.unbinds)
        assertEquals(null, p.show(nudge))
        link.trustedNow = true; p.onPackageChanged()
        assertTrue(p.bound.value); assertEquals(BridgeLink.Bind.BOUND, p.bind.value)
    }

    // ---- Codex v2 r1 P1-5: the history wipe is owed until the hub acknowledges ----

    @Test fun aForgetWhileTheBridgeIsDisconnectedIsOwedAndSentOnTheNextConnection() = runTest {
        host = HudHost.NEXUS
        val p = provider(); p.refresh(); p.show(nudge)
        link.gone!!()                              // the bridge process died
        assertFalse(p.bound.value)
        p.trip(TripReason.FORGET)                  // a disconnected trip used to return at once
        assertTrue("the debt is recorded", debt.wipeOwed()); assertEquals(0, port.forgets)
        link.connected!!(port)                     // the service restarted: the same binding reconnects
        assertEquals("the owed wipe goes out on connect", 1, port.forgets)
        port.ack()
        assertFalse("settled by the acknowledgement", debt.wipeOwed())
    }

    @Test fun anUnacknowledgedWipeIsNotDroppedAfterTheTeardownTimeoutAndIsRetriedWithBackoff() = runTest {
        host = HudHost.NEXUS
        val p = provider(); p.refresh(); p.show(nudge)
        host = HudHost.PCA; p.refresh()
        val first = port.forgets
        advanceTimeBy(35_000)
        assertEquals("the stop let go of the bridge...", 1, link.unbinds)
        assertTrue("...but the debt stays", debt.wipeOwed())
        advanceTimeBy(30_000); testScheduler.runCurrent()
        assertEquals("a retry bound it again, only for the wipe", 2, link.binds)
        assertTrue(port.forgets > first)
        assertEquals("nothing but the wipe", null, p.show(nudge))
        port.ack()
        advanceTimeBy(2_000)
        assertFalse(debt.wipeOwed())
        assertEquals("unbound after the acknowledgement", 2, link.unbinds)
        advanceTimeBy(1_000_000); assertEquals("no further attempt once paid", 2, link.binds)
    }

    @Test fun theDebtSurvivesAProcessRestartAndIsPaidByTheNextProvider() = runTest {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("nexus_debt_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        host = HudHost.NEXUS
        val store1 = NexusHostStore(prefs)
        val p1 = provider(store1); p1.refresh()
        link.gone!!()
        p1.trip(TripReason.DELETE_ALL)             // the process is killed right after
        assertTrue(prefs.getBoolean("wipe_owed", false))
        // ---- a new process: a new store over the same file, a new provider; Nexus is no longer the host
        host = HudHost.PCA
        val port2 = FakePort(); val link2 = FakeLink(port2)
        val store2 = NexusHostStore(prefs)
        assertTrue(store2.wipeOwed())
        val p2 = provider(store2, link2); p2.refresh()
        assertEquals(1, link2.binds); assertEquals("the wipe is sent on provider start", 1, port2.forgets)
        port2.ack()
        assertFalse(store2.wipeOwed()); assertFalse(NexusHostStore(prefs).wipeOwed())
        assertFalse("no stored debt, nothing else bound at start", prefs.all.containsKey("wipe_owed"))
    }

    @Test fun aFailedBindDoesNotEndTheAutomaticDebtRetries() = runTest {
        debt.owe()
        val refusing = FakeLink(port, BridgeLink.Bind.REFUSED)
        val p = provider(debt, refusing); p.refresh()
        assertEquals(1, refusing.binds)
        advanceTimeBy(31_000); testScheduler.runCurrent()
        assertEquals("retried after the first wait", 2, refusing.binds)
        advanceTimeBy(61_000); testScheduler.runCurrent()
        assertEquals("and again after the doubled wait", 3, refusing.binds)
    }

    @Test fun aLateAcknowledgementOfAnOlderWipeLeavesTheNewerDebtStanding() = runTest {
        host = HudHost.NEXUS
        val p = provider(); p.refresh(); p.show(nudge)
        p.trip(TripReason.FORGET); val a = port.forgetGen
        p.show(nudge); p.trip(TripReason.FORGET)
        port.registered?.onForgetAcceptedAt(a)
        assertTrue(debt.wipeOwed())
        port.ack()
        assertFalse(debt.wipeOwed())
    }

    @Test fun nothingIsOwedAndNothingBindsWhenNexusWasNeverUsed() = runTest {
        val p = provider(); p.refresh(); p.trip(TripReason.FORGET); p.trip(TripReason.DELETE_ALL)
        assertFalse(debt.wipeOwed()); assertEquals(0, link.binds)
    }
}
