package com.rokid.context.phone.hub

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.WearEstimate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v1.0 wear fallback, phone side: [active] = the glasses report "not worn (estimated)" ([com.rokid.context.core.model.MotionHint.wearEst]) on a live session AND
 * [WearEstimate.applies] (the system wear check is not on, by the link's last read, or there is no wear value at all). The real signal always wins: with the
 * check on, or a wear value while its state is unknown, the estimate is ignored. A lost session drops it (the next session's first hint says it again).
 * Feeds [BackgroundPolicy.away] ([BackgroundPolicy.Away.NOT_WORN_EST]).
 */
class GlassesWearEstimate(private val log: (String) -> Unit = { PcLog.i(Hub.TAG, it) }) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    private var reported = false
    private var session = false
    private var wearCheck: Boolean? = null
    private var realKnown = false

    /** A motion hint's estimate (null = an old glasses build: never an estimate). */
    @Synchronized fun onHint(wearEst: Boolean?) { reported = wearEst == true; update() }
    /** The link carries data (a session) or not. */
    @Synchronized fun onLink(carriesData: Boolean) { session = carriesData; if (!carriesData) reported = false; update() }
    /** The SDK's wear value (null = unknown). */
    @Synchronized fun onWear(worn: Boolean?) { realKnown = worn != null; update() }
    /** The glasses' system wear check as last read (null = never). */
    @Synchronized fun onWearCheck(on: Boolean?) { wearCheck = on; update() }

    private fun update() {
        val now = reported && session && WearEstimate.applies(wearCheck, realKnown)
        if (now == _active.value) return
        _active.value = now
        if (now) log("glasses: not worn (estimated)") else log("glasses: worn again (estimate ended)")
    }
}
