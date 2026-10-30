package com.rokid.context.phone.voice

import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.phone.ui.status.PauseHub
import com.rokid.context.voice.CaptureResult
import com.rokid.context.voice.Utterance
import com.rokid.context.voice.UtteranceResult
import com.rokid.context.voice.UtteranceSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HubCaptureControlTest {
    private class FakeHub(var fail: Boolean = false) : PauseHub {
        val active = MutableStateFlow<List<PauseRecord>>(emptyList())
        var pauses = 0; val resumed = ArrayList<Set<String>>()
        override val activePauses: StateFlow<List<PauseRecord>> get() = active
        override suspend fun pausePhone(untilMs: Long?): PauseRecord {
            if (fail) error("db")
            pauses++; return PauseRecord("p$pauses", PauseOrigin.PHONE, 1L, untilMs).also { active.value = active.value + it }
        }
        override suspend fun resume(ids: Set<String>) { if (fail) error("db"); resumed += ids; active.value = active.value.filterNot { it.pauseId in ids } }
    }

    @Test fun everySurfaceReachesTheHubEntryAndRepliesTruthfully() = runBlocking {
        for (s in UtteranceSource.entries) {
            val hub = FakeHub()
            val u = Utterance(object : com.rokid.context.voice.UtteranceCommands {
                override suspend fun apply(commands: List<com.rokid.context.voice.commands.MarkCommand>, source: UtteranceSource, nowMs: Long) = com.rokid.context.voice.CommandOutcome()
                override fun onAsk(source: UtteranceSource) = Unit
            }, capture = HubCaptureControl({ hub }))
            assertEquals(UtteranceResult.Handled(Utterance.ALREADY_CAPTURING), u.handle("resume capture", s, true))
            assertEquals(UtteranceResult.Handled(Utterance.CAPTURE_PAUSED), u.handle("pause capture", s, true))
            assertEquals(UtteranceResult.Handled(Utterance.ALREADY_PAUSED), u.handle("stop capturing", s, true)) // works while paused, no second record
            assertEquals(1, hub.pauses)
            assertEquals(UtteranceResult.Handled(Utterance.CAPTURE_RESUMED), u.handle("resume capture", s, true))
            assertEquals(listOf(setOf("p1")), hub.resumed)
        }
    }

    @Test fun aFailureIsReportedNeverClaimedAsSuccess() = runBlocking {
        val hub = FakeHub(fail = true)
        val c = HubCaptureControl({ hub })
        assertEquals(CaptureResult.Failed, c.pause(UtteranceSource.PHONE_CHAT))
        hub.active.value = listOf(PauseRecord("x", PauseOrigin.PHONE, 1L))
        assertEquals(CaptureResult.Failed, c.resume(UtteranceSource.GLASSES_VOICE))
    }
}
