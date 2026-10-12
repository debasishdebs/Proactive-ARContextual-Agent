package com.rokid.context.phone.ui.glasses

import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.StateFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stand-in for Phase 3's hub (contract): OFFLINE until [emit] is called. Used by tests and, until the post-merge
 * Task 11 swaps in `HubGraph.glassesStatus(context)`, by the app itself — which truthfully shows "Glasses offline".
 */
class FakeGlassesStatusSource(initial: GlassesStatus = OFFLINE) : GlassesStatusSource {
    private val state = MutableStateFlow(initial)
    override val status: StateFlow<GlassesStatus> = state.asStateFlow()

    var reconnectRequests = 0
        private set

    override fun requestReconnect() { reconnectRequests++ }

    fun emit(status: GlassesStatus) { state.value = status }

    companion object {
        val OFFLINE = GlassesStatus(LinkState.OFFLINE, glassesBatteryPct = null, bufferedFrames = 0, lastFrameAtMs = null, frame = null)

        fun connected(frame: StateFrame, batteryPct: Int? = frame.glassesBatteryPct) =
            GlassesStatus(LinkState.CONNECTED, batteryPct, bufferedFrames = 0, lastFrameAtMs = null, frame = frame)
    }
}
