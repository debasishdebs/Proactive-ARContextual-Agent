package com.rokid.context.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class GlassesStatusTest {
    @Test fun linkStatesAreTheContractSetInOrder() {
        assertEquals(
            listOf("NOT_AUTHORIZED", "CONNECTING", "CONNECTED", "PAUSED_BT", "CLOSED_ON_GLASSES", "VERSION_MISMATCH", "OFFLINE", "MINIMISED"),
            LinkState.entries.map { it.name },
        )
    }

    @Test fun sourceExposesTheLastBroadcastFrame() {
        val frame = StateFrame(seq = 1, capture = CaptureState.Running, intervalSec = 300, phoneLinked = true, aiHealth = Health.Ok)
        val source = object : GlassesStatusSource {
            override val status: StateFlow<GlassesStatus> = MutableStateFlow(GlassesStatus(LinkState.CONNECTED, 82, 3, 10L, frame))
            override fun requestReconnect() = Unit
        }
        assertEquals(82, source.status.value.glassesBatteryPct)
        assertEquals(3, source.status.value.bufferedFrames)
        assertEquals(frame, source.status.value.frame)
    }
}
