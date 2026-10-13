package com.rokid.context.voice

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceCapabilityServiceTest {
    private class FakePlatform : VoicePlatform {
        override var sdk = 37
        var onDevice = true
        var mic = true
        var packs: Set<String>? = setOf("en-US")
        var build = "build-1"
        var outcome: ProbeOutcome? = ProbeOutcome(166_142, 166_142)
        var throwOnProbe = false
        var packsThrow: Throwable? = null
        var probes = 0
        override fun onDeviceAvailable() = onDevice
        override fun micGranted() = mic
        override suspend fun installedPacks(): Set<String>? { packsThrow?.let { throw it }; return packs }
        override suspend fun runProbe(localeTag: String): ProbeOutcome? { probes++; if (throwOnProbe) throw IllegalStateException("recognizer died"); return outcome }
        override fun fingerprint() = build
    }

    private val platform = FakePlatform()
    private val records = InMemoryProbeRecords()
    private var language = SpeechLanguage.ENGLISH

    private fun TestScope.service() = VoiceCapabilityService(platform, records, { language }, backgroundScope, StandardTestDispatcher(testScheduler))

    @Test fun withoutAProbeGlassesVoiceIsNotVerifiedAndThePhoneIsReady() = runTest {
        val s = service()
        s.refresh().join()
        assertEquals(VoiceStatus.SUPPORTED, s.capabilities.value.phone)
        assertEquals(VoiceStatus.NOT_VERIFIED, s.capabilities.value.glasses)
        assertEquals("Open Proactive Context on phone to set up voice", s.capabilities.value.askBlockedReason)
        assertEquals(0, platform.probes)
    }

    @Test fun aForegroundRefreshProbesOnceAndRemembersTheVerdict() = runTest {
        val s = service()
        s.refresh(allowProbe = true).join()
        assertEquals(VoiceStatus.SUPPORTED, s.capabilities.value.glasses)
        assertNull(s.capabilities.value.askBlockedReason)
        s.refresh(allowProbe = true).join()
        assertEquals(1, platform.probes)
        assertEquals(ProbeVerdict.SUPPORTED, records.read()!!.verdict)
    }

    @Test fun aNonReadingRecognizerMakesGlassesVoiceUnsupported() = runTest {
        platform.outcome = ProbeOutcome(166_142, 65_536)
        val s = service()
        s.refresh(allowProbe = true).join()
        assertEquals(VoiceStatus.EXTERNAL_AUDIO_UNSUPPORTED, s.capabilities.value.glasses)
        assertEquals("Voice not supported on this phone", s.capabilities.value.askBlockedReason)
        assertEquals(VoiceStatus.SUPPORTED, s.capabilities.value.phone, "phone-mic asks still work")
    }

    @Test fun anOsOrAppUpdateOrANewLanguageProbesAgain() = runTest {
        platform.packs = setOf("en-US", "hi-IN")
        val s = service()
        s.refresh(allowProbe = true).join()
        platform.build = "build-2"
        s.refresh(allowProbe = true).join()
        language = SpeechLanguage.HINDI
        s.refresh(allowProbe = true).join()
        assertEquals(3, platform.probes)
    }

    @Test fun theGuardMarksGlassesVoiceUnsupportedUntilAForegroundProbe() = runTest {
        val s = service()
        s.refresh(allowProbe = true).join()
        s.onFallbackSuspected().join()
        assertEquals(VoiceStatus.EXTERNAL_AUDIO_UNSUPPORTED, s.capabilities.value.glasses)
        s.refresh(allowProbe = false).join()
        assertEquals(VoiceStatus.EXTERNAL_AUDIO_UNSUPPORTED, s.capabilities.value.glasses, "a background refresh keeps it off")
        s.refresh(allowProbe = true).join()
        assertEquals(2, platform.probes)
        assertEquals(VoiceStatus.SUPPORTED, s.capabilities.value.glasses)
    }

    @Test fun aFailedProbeLeavesItUnverifiedAndRecordsNothing() = runTest {
        platform.throwOnProbe = true
        val s = service()
        s.refresh(allowProbe = true).join()
        assertEquals(VoiceStatus.NOT_VERIFIED, s.capabilities.value.glasses)
        assertNull(records.read())
    }

    @Test fun platformLimitsAreReportedWithoutProbing() = runTest {
        platform.sdk = 32
        val s = service()
        s.refresh(allowProbe = true).join()
        assertEquals(VoiceStatus.NEEDS_NEWER_ANDROID, s.capabilities.value.glasses)
        assertEquals(VoiceStatus.SUPPORTED, s.capabilities.value.phone, "API 31-32 can't list packs: assumed installed")
        platform.sdk = 37; platform.mic = false
        s.refresh(allowProbe = true).join()
        assertEquals(VoiceStatus.NEEDS_MIC_PERMISSION, s.capabilities.value.phone)
        platform.mic = true; platform.packs = setOf("hi-IN")
        s.refresh(allowProbe = true).join()
        assertEquals(VoiceStatus.NEEDS_SPEECH_PACK, s.capabilities.value.glasses)
        assertEquals(0, platform.probes)
    }

    /** Task 5 fix 2: a pipe too big to decide (or a skipped probe) is NOT_VERIFIED, never UNSUPPORTED, and is not remembered. */
    @Test fun anUndecidableOrSkippedProbeLeavesItUnverifiedAndRecordsNothing() = runTest {
        platform.outcome = ProbeOutcome(166_142, 166_142, capacityBytes = 256_000)
        val s = service()
        s.refresh(allowProbe = true).join()
        assertEquals(VoiceStatus.NOT_VERIFIED, s.capabilities.value.glasses)
        assertNull(records.read())
        platform.outcome = null
        s.refresh(allowProbe = true).join()
        assertEquals(VoiceStatus.NOT_VERIFIED, s.capabilities.value.glasses)
        assertNull(records.read())
        assertEquals(2, platform.probes, "nothing recorded, so the next foreground refresh tries again")
    }

    /** Review Important 1: a transient recognizer error (busy, disconnected) during the probe is never stored as UNSUPPORTED. */
    @Test fun aTransientRecognizerErrorNeverStoresUnsupported() = runTest {
        platform.outcome = ProbeFeed.judge(SttResult.Failed, ProbeOutcome(166_142, 64_000, 64_000))
        val s = service()
        s.refresh(allowProbe = true).join()
        assertEquals(VoiceStatus.NOT_VERIFIED, s.capabilities.value.glasses)
        assertNull(records.read())
        platform.outcome = ProbeFeed.judge(SttResult.NoSpeech, ProbeOutcome(166_142, 166_142, 64_000))
        s.refresh(allowProbe = true).join()
        assertEquals(VoiceStatus.SUPPORTED, s.capabilities.value.glasses, "the next foreground refresh probes again")
    }

    @Test fun aFailedPackQueryCountsAsInstalledButACancelledOneStopsTheRefresh() = runTest {
        platform.packsThrow = IllegalStateException("query died")
        val s = service()
        s.refresh().join()
        assertEquals(VoiceStatus.SUPPORTED, s.capabilities.value.phone)
        val t = service()
        platform.packsThrow = kotlin.coroutines.cancellation.CancellationException("refresh cancelled")
        t.refresh().join()
        assertEquals(VoiceCapabilities.UNKNOWN, t.capabilities.value, "cancellation is not swallowed")
    }

    /**
     * Fix round 2: we hold the read end (F1), so a closed feed is our own I/O fault (a short write or EIO, which
     * AndroidPipeSink reports as CLOSED, or a throwing sink), never the recognizer's answer: nothing is stored.
     */
    @Test fun aShortWriteOrEioDuringTheProbeStoresNothing() = runTest {
        for (fault in listOf<(Int) -> Int>({ n -> if (n < 20) PipeSink.WROTE else PipeSink.CLOSED }, { n -> if (n < 2) PipeSink.WROTE else throw java.io.IOException("EIO") })) {
            var writes = 0
            val sink = object : PipeSink {
                override fun tryWrite(bytes: ByteArray): Int = fault(writes++)
                override fun close() = Unit
            }
            val outcome = ProbeFeed.stream(PcmFeeder(sink, clock = { testScheduler.currentTime }, capacityBytes = 64_000), ByteArray(134_142)) { kotlinx.coroutines.delay(it) }
            assertEquals(true, outcome.peerClosed)
            platform.outcome = ProbeFeed.judge(SttResult.NoSpeech, outcome)
            val s = service()
            s.refresh(allowProbe = true).join()
            assertEquals(VoiceStatus.NOT_VERIFIED, s.capabilities.value.glasses)
            assertNull(records.read())
        }
    }
}
