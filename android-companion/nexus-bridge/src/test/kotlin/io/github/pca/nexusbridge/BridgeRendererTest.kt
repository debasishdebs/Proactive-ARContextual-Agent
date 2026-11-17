package io.github.pca.nexusbridge

import io.github.pca.nexusport.NexusAction
import io.github.pca.nexusport.NexusFeatures
import io.github.pca.nexusport.NexusResult
import io.github.pca.nexusport.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PCA's own fake of the Nexus client (upstream has no public fake transport): what the renderer asked Nexus to draw, with scripted results. */
class FakeBus : NexusBusClient {
    val calls = mutableListOf<String>()
    var notice: String? = null
    var pin: String? = null
    var speaking = false
    var result = NexusResult.OK
    var acceptForget: (() -> Unit)? = null
    override fun showNotice(title: String, body: String, ttlMs: Long, wake: Boolean, actions: List<NexusAction>): Int { calls += "notice"; if (result == NexusResult.OK) notice = body; return result }
    override fun hideNotice(): Int { calls += "hideNotice"; notice = null; return NexusResult.OK }
    override fun showPin(title: String, lines: List<String>, ttlMs: Long): Int { calls += "pin"; if (result == NexusResult.OK) pin = lines.joinToString("/"); return result }
    override fun hidePin(): Int { calls += "hidePin"; pin = null; return NexusResult.OK }
    override fun speak(text: String): Int { calls += "speak"; if (result == NexusResult.OK) speaking = true; return result }
    override fun stopSpeech(): Int { calls += "stop"; speaking = false; return NexusResult.OK }
    override fun forgetNotices(onAccepted: () -> Unit): Int { calls += "forget"; acceptForget = onAccepted; return result }
}

class BridgeRendererTest {
    private val bus = FakeBus()
    private val logs = mutableListOf<String>()
    private val r = BridgeRenderer({ bus }, { logs += it })
    private val none = BridgeRenderer({ null })
    private val ids = listOf("dismiss"); private val labels = listOf("Dismiss")

    @Test fun voiceIsOffUntilPcaSetsTheBit() {
        assertEquals(NexusResult.DISABLED, r.speak("hello"))
        assertTrue(bus.calls.isEmpty())
        assertEquals(NexusResult.OK, r.setFeatures(NexusFeatures.TTS))
        assertEquals(NexusResult.OK, r.speak("hello")); assertTrue(bus.speaking)
    }

    @Test fun featuresZeroHidesEverythingAndTtsOffStopsTheVoice() {
        r.setFeatures(NexusFeatures.TTS)
        r.show(Slot.NOTICE.code, "T", "body", 5_000, false, ids, labels); r.show(Slot.PIN.code, "PCA", "Capturing", 60_000, false, emptyList(), emptyList()); r.speak("x")
        r.setFeatures(NexusFeatures.NONE)
        assertEquals(null, bus.notice); assertEquals(null, bus.pin); assertTrue(!bus.speaking)
        r.setFeatures(NexusFeatures.TTS); r.speak("x"); r.setFeatures(NexusFeatures.ASSIST)
        assertTrue(!bus.speaking)
        assertEquals(NexusResult.DISABLED, r.speak("x"))
    }

    @Test fun unknownFeatureBitsAreRejected() {
        assertEquals(NexusResult.INVALID, r.setFeatures(NexusFeatures.KNOWN + 8))
    }

    @Test fun validationIsASecondLineOfDefence() {
        assertEquals(NexusResult.INVALID, r.show(Slot.NOTICE.code, "T", "x".repeat(1025), 5_000, false, ids, labels))
        assertEquals(NexusResult.INVALID, r.show(Slot.NOTICE.code, "T", (1..17).joinToString("\n") { "l" }, 5_000, false, ids, labels))
        assertEquals(NexusResult.INVALID, r.show(Slot.NOTICE.code, "T", "b", 500, false, ids, labels))
        assertEquals(NexusResult.INVALID, r.show(Slot.NOTICE.code, "T", "b", 5_000, false, listOf("a", "b", "c", "d"), listOf("A", "B", "C", "D")))
        assertEquals(NexusResult.INVALID, r.show(Slot.NOTICE.code, "T", "b", 5_000, false, ids, emptyList()))
        assertEquals(NexusResult.INVALID, r.show(Slot.NOTICE.code, null, "b", 5_000, false, ids, labels))
        assertEquals(NexusResult.INVALID, r.show(Slot.PIN.code, "PCA", "a\nb\nc", 60_000, false, emptyList(), emptyList()))
        assertEquals(NexusResult.INVALID, r.show(99, "T", "b", 5_000, false, ids, labels))
        assertEquals(NexusResult.UNSUPPORTED, r.show(Slot.SURFACE.code, "T", "b", 5_000, false, ids, labels))
        assertEquals(NexusResult.UNSUPPORTED, r.show(Slot.ACTIVITY.code, "T", "b", 5_000, false, ids, labels))
        assertTrue(bus.calls.isEmpty())
    }

    @Test fun aValidNoticeAndPinReachTheClient() {
        assertEquals(NexusResult.OK, r.show(Slot.NOTICE.code, "T", "body", 5_000, false, ids, labels))
        assertEquals(NexusResult.OK, r.show(Slot.PIN.code, "PCA", "Capturing", 60_000, false, emptyList(), emptyList()))
        assertEquals("body", bus.notice); assertEquals("Capturing", bus.pin)
        assertEquals(NexusResult.OK, r.hide(Slot.NOTICE.code)); assertEquals(NexusResult.OK, r.hide(Slot.PIN.code))
        assertEquals(null, bus.notice); assertEquals(null, bus.pin)
        assertEquals(NexusResult.INVALID, r.hide(42))
    }

    @Test fun nexusRefusalsComeBackAsQuietCodesWithoutRetry() {
        for (code in listOf(NexusResult.BUSY, NexusResult.NOT_AVAILABLE, NexusResult.NOT_REGISTERED, NexusResult.NOT_GRANTED)) {
            bus.calls.clear(); bus.result = code
            assertEquals(code, r.show(Slot.NOTICE.code, "T", "b", 5_000, false, ids, labels))
            assertEquals(1, bus.calls.size, "no retry for ${NexusResult.name(code)}")
        }
        assertEquals(NexusResult.NOT_REGISTERED, none.show(Slot.NOTICE.code, "T", "b", 5_000, false, ids, labels))   // the plugin service is not up yet (cold start)
        assertEquals(NexusResult.OK, none.hide(Slot.NOTICE.code))                                                    // hiding what cannot be showing is done
    }

    @Test fun tapsSpeechEndAndHistoryAcceptanceAreRelayedAsIdsOnly() {
        val got = mutableListOf<String>()
        r.events = object : BridgeEvents {
            override fun onAction(slot: Int, actionId: String) { got += "a:$slot:$actionId" }
            override fun onSpeechDone() { got += "s" }
            override fun onForgetAccepted() { got += "f" }
        }
        r.noticeAction("mark"); r.speechDone()
        assertEquals(NexusResult.OK, r.forgetHistory()); bus.acceptForget!!.invoke()
        assertEquals(listOf("a:1:mark", "s", "f"), got)
    }

    @Test fun logsHoldCodesAndFlagsNeverText() {
        bus.result = NexusResult.BUSY
        r.show(Slot.NOTICE.code, "SECRET-T", "SECRET-BODY", 5_000, false, ids, labels); r.setFeatures(NexusFeatures.TTS); r.hideAll()
        assertTrue(logs.isNotEmpty()); assertTrue(logs.none { "SECRET" in it })
    }
}
