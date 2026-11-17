package io.github.pca.nexusbridge

import io.github.pca.nexusport.AskFail
import io.github.pca.nexusport.NexusFeatures
import io.github.pca.nexusport.NexusResult
import io.github.pca.nexusport.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T5 on the bridge: the assist-button ask relays Nexus's speech callbacks (fake ones here) and owns no logic, no microphone and no transcript log. */
class AssistAskBridgeTest {
    private class Ev : BridgeEvents {
        val log = mutableListOf<String>()
        override fun onAction(slot: Int, actionId: String) { log += "action" }
        override fun onSpeechDone() { log += "speechDone" }
        override fun onForgetAccepted() { log += "forget" }
        override fun onListening(active: Boolean) { log += "listening:$active" }
        override fun onAskTranscript(text: String) { log += "transcript:$text" }
        override fun onAskFailed(reason: Int) { log += "failed:$reason" }
    }

    private val bus = FakeBus()
    private val ev = Ev()
    private val logs = mutableListOf<String>()
    private var auth: () -> Boolean = { true }
    private val r = BridgeRenderer({ bus }, { logs += it }, { auth() }).also { it.events = ev }
    private val ASK = NexusFeatures.STT or NexusFeatures.ASSIST
    private val SECRET = "where is the red car parked"

    @Test fun offByDefaultTheAssistOpenDoesNothingAndTheTakeoverIsMovedOff() {
        r.assistOpened()
        assertTrue(bus.listen == null && "listen" !in bus.calls); assertTrue(ev.log.isEmpty())
        r.setFeatures(NexusFeatures.TTS)
        assertEquals(false, bus.takeover)
        r.assistOpened(); assertTrue("listen" !in bus.calls)
        r.setFeatures(NexusFeatures.STT)             // speech to text alone is not the ask
        r.assistOpened(); assertTrue("listen" !in bus.calls)
        r.setFeatures(ASK); assertEquals(true, bus.takeover)
        r.setFeatures(NexusFeatures.NONE); assertEquals(false, bus.takeover)
    }

    @Test fun aFinalTranscriptIsRelayedOnceAndThenTheSessionEnds() {
        r.setFeatures(ASK)
        r.assistOpened()
        val l = bus.listen!!
        l.onStarted(); l.onFinal(SECRET); l.onFinal("again"); l.onStopped(ListenStop.COMPLETED)
        assertEquals(listOf("listening:true", "transcript:$SECRET", "listening:false"), ev.log)
    }

    @Test fun deniedBusyAndNoSpeechAreRelayedAsFailureCodes() {
        r.setFeatures(ASK)
        r.assistOpened(); bus.listen!!.onStopped(ListenStop.DENIED_BUSY)
        r.assistOpened(); bus.listen!!.onStarted(); bus.listen!!.onStopped(ListenStop.NO_SPEECH)
        r.assistOpened(); bus.listen!!.onStopped(ListenStop.FAILED)
        assertEquals(listOf("failed:${AskFail.DENIED_BUSY}", "listening:true", "failed:${AskFail.NO_SPEECH}", "failed:${AskFail.OTHER}"), ev.log)
    }

    @Test fun aRefusedStartIsOneFailureAndNoRetry() {
        r.setFeatures(ASK)
        for (code in listOf(NexusResult.NOT_GRANTED, NexusResult.NOT_REGISTERED, NexusResult.NOT_AVAILABLE)) {
            ev.log.clear(); bus.calls.clear(); bus.listenResult = code
            r.assistOpened()
            assertEquals(listOf("failed:${AskFail.OTHER}"), ev.log); assertEquals(1, bus.calls.count { it == "listen" })
        }
    }

    @Test fun aStopFromPcaCutsTheSessionAndWhateverItStillReportsIsIgnored() {
        r.setFeatures(ASK)
        r.assistOpened(); val l = bus.listen!!
        l.onStarted()
        assertEquals(NexusResult.OK, r.hide(Slot.LISTEN.code))
        assertTrue("stopListen" in bus.calls)
        l.onFinal(SECRET); l.onStopped(ListenStop.COMPLETED)                // in flight when the zone tripped
        assertEquals(listOf("listening:true"), ev.log)
    }

    @Test fun switchingTheAskOffOrPcaGoingAwayStopsTheSession() {
        r.setFeatures(ASK); r.assistOpened(); val l = bus.listen!!
        r.setFeatures(NexusFeatures.TTS)
        assertTrue("stopListen" in bus.calls); l.onFinal(SECRET)
        assertFalse(ev.log.any { it.startsWith("transcript") })
        r.setFeatures(ASK); r.assistOpened(); val l2 = bus.listen!!
        r.hideAll(); l2.onFinal(SECRET)
        assertFalse(ev.log.any { it.startsWith("transcript") })
    }

    @Test fun aNewAssistPressReplacesTheOldSession() {
        r.setFeatures(ASK)
        r.assistOpened(); val first = bus.listen!!
        r.assistOpened(); val second = bus.listen!!
        first.onFinal("old")
        second.onFinal("new")
        assertEquals(listOf("transcript:new"), ev.log)
    }

    @Test fun withoutPcaTheAssistOpenIsIgnored() {
        r.setFeatures(ASK); r.events = null
        r.assistOpened()
        assertTrue("listen" !in bus.calls)
    }

    @Test fun theTranscriptNeverReachesTheBridgeLog() {
        r.setFeatures(ASK); r.assistOpened()
        val l = bus.listen!!; l.onStarted(); l.onFinal(SECRET); l.onStopped(ListenStop.COMPLETED)
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { "red car" in it || "parked" in it }, logs.toString())
    }

    // Codex v2 r1 P1-2: the phone's gates answer BEFORE any speech session is opened.
    @Test fun noSpeechSessionIsOpenedUnlessThePhoneAuthorizes() {
        r.setFeatures(ASK)
        auth = { false }; r.assistOpened()
        assertTrue("listen" !in bus.calls && bus.listen == null, "refused (zone, not worn, pause...): nothing opened")
        auth = { throw IllegalStateException("binder") }; r.assistOpened()
        assertTrue("listen" !in bus.calls, "a failing authorization is a no")
        assertTrue(ev.log.isEmpty())
        auth = { true }; r.assistOpened()
        assertEquals(1, bus.calls.count { it == "listen" })
    }

    @Test fun authorizationIsAskedBeforeTheSessionOpens() {
        r.setFeatures(ASK)
        val order = mutableListOf<String>()
        auth = { order += "authorize"; order += "calls=" + bus.calls.count { it == "listen" }; true }
        r.assistOpened()
        assertEquals(listOf("authorize", "calls=0"), order)
        assertEquals(1, bus.calls.count { it == "listen" })
    }

    @Test fun aTripWhileTheSessionIsOpeningClosesIt() {
        r.setFeatures(ASK)
        auth = { r.stopListening(); true }          // the phone tripped after authorizing (or during): the session must not stay open
        bus.calls.clear()
        r.assistOpened()
        assertTrue("listen" !in bus.calls, "a trip before the open ends the attempt")
        val after = bus.calls.count { it == "stopListen" }
        assertTrue(after >= 1)
    }
}
