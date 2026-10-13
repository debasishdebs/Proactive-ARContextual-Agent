package com.rokid.context.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceGateTest {
    private val ok = VoiceInputs(sdk = 37, onDeviceAvailable = true, micGranted = true, packInstalled = true, probe = ProbeVerdict.SUPPORTED, probing = false)

    @Test fun everythingInPlaceIsSupportedEverywhere() {
        val c = VoiceGate.decide(ok)
        assertEquals(VoiceStatus.SUPPORTED, c.glasses); assertEquals(VoiceStatus.SUPPORTED, c.phone)
        assertTrue(c.canDownloadPack); assertNull(c.askBlockedReason)
    }

    @Test fun glassesVoiceNeedsAndroidThirteenPhoneVoiceTwelve() {
        val c32 = VoiceGate.decide(ok.copy(sdk = 32))
        assertEquals(VoiceStatus.NEEDS_NEWER_ANDROID, c32.glasses); assertEquals(VoiceStatus.SUPPORTED, c32.phone)
        assertEquals("Needs Android 13+ for glasses voice", VoiceStatusText.glasses(c32.glasses))
        assertEquals("Voice not supported on this phone", c32.askBlockedReason)
        val c30 = VoiceGate.decide(ok.copy(sdk = 30))
        assertEquals(VoiceStatus.NEEDS_NEWER_ANDROID, c30.phone); assertEquals("Needs Android 12+", VoiceStatusText.phone(c30.phone))
        assertFalse(VoiceGate.decide(ok.copy(sdk = 33)).canDownloadPack)
    }

    @Test fun thePhoneConditionsComeFirstInTheirOrder() {
        assertEquals(VoiceStatus.NO_ON_DEVICE_RECOGNIZER, VoiceGate.decide(ok.copy(onDeviceAvailable = false, micGranted = false)).phone)
        val mic = VoiceGate.decide(ok.copy(micGranted = false, packInstalled = false))
        assertEquals(VoiceStatus.NEEDS_MIC_PERMISSION, mic.phone); assertEquals(VoiceStatus.NEEDS_MIC_PERMISSION, mic.glasses)
        assertEquals("Allow the microphone on phone", mic.askBlockedReason)
        val pack = VoiceGate.decide(ok.copy(packInstalled = false))
        assertEquals(VoiceStatus.NEEDS_SPEECH_PACK, pack.glasses); assertEquals("Download speech pack on phone", pack.askBlockedReason)
        assertEquals(VoiceStatus.CHECKING, VoiceGate.decide(ok.copy(packInstalled = null)).phone)
    }

    @Test fun glassesVoiceWaitsForAVerifiedProbe() {
        assertEquals(VoiceStatus.NOT_VERIFIED, VoiceGate.decide(ok.copy(probe = null)).glasses)
        assertEquals("Open Proactive Context on phone to set up voice", VoiceGate.decide(ok.copy(probe = null)).askBlockedReason)
        assertEquals(VoiceStatus.CHECKING, VoiceGate.decide(ok.copy(probe = null, probing = true)).glasses)
        val no = VoiceGate.decide(ok.copy(probe = ProbeVerdict.UNSUPPORTED))
        assertEquals(VoiceStatus.EXTERNAL_AUDIO_UNSUPPORTED, no.glasses); assertEquals(VoiceStatus.SUPPORTED, no.phone)
        assertEquals("Not supported on this phone", VoiceStatusText.glasses(no.glasses))
    }

    @Test fun everyHudReasonFitsTheHud() =
        VoiceStatus.entries.forEach { s -> VoiceStatusText.hudReason(s)?.let { assertTrue(it.length <= 60, it) } }

    /** Preflight F13: T8's VoiceCopy refers to these, so the HUD reasons and the voice toasts can't drift apart. */
    @Test fun theSharedHudReasonsAreTheGateConstants() {
        assertEquals(VoiceStatusText.NOT_SUPPORTED_HUD, VoiceStatusText.hudReason(VoiceStatus.EXTERNAL_AUDIO_UNSUPPORTED))
        assertEquals(VoiceStatusText.NOT_SUPPORTED_HUD, VoiceStatusText.hudReason(VoiceStatus.NO_ON_DEVICE_RECOGNIZER))
        assertEquals(VoiceStatusText.NOT_SUPPORTED_HUD, VoiceStatusText.hudReason(VoiceStatus.NEEDS_NEWER_ANDROID))
        assertEquals(VoiceStatusText.PACK_HUD, VoiceStatusText.hudReason(VoiceStatus.NEEDS_SPEECH_PACK))
        assertEquals(VoiceStatusText.MIC_HUD, VoiceStatusText.hudReason(VoiceStatus.NEEDS_MIC_PERMISSION))
    }

    @Test fun packsMatchByTagThenByLanguage() {
        assertTrue(PackMatch.installed("en-US", setOf("en-us")))
        assertTrue(PackMatch.installed("en-US", setOf("en-GB")))
        assertFalse(PackMatch.installed("hi-IN", setOf("en-US")))
        assertFalse(PackMatch.installed("en-US", emptySet()))
    }
}
