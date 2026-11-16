package com.rokid.context.phone.hub

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.WearEstimate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v1.0 wear fallback, phone side: [active] = the glasses report "not worn (estimated)" ([com.rokid.context.core.model.MotionHint.wearEst]) on a live session,
 * the report is fresh (the glasses re-send it every 60 s; one not refreshed within [WearEstimate.FRESH_MS] expires, and a withdrawal `false` ends it at once),
 * AND [WearEstimate.applies]: the link read the system wear check as OFF in the current session ([wearCheck] false; null = unknown = no estimate) and no real
 * wear change was seen in it. The real signal always wins. A lost session drops it (the next session's first hint says it again).
 * Feeds [BackgroundPolicy.away] ([BackgroundPolicy.Away.NOT_WORN_EST]).
 */
class GlassesWearEstimate(
    /** A MONOTONIC clock (Codex wear r2 P1-3): freshness must not follow the wall clock, which a user or the network can move back. The hub passes `SystemClock.elapsedRealtime`. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Called with the delay after which [tick] must run (an estimate was reported or refreshed); the host replaces its previous timer. */
    private val scheduleExpiry: (Long) -> Unit = {},
    private val log: (String) -> Unit = { PcLog.i(Hub.TAG, it) },
) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    private var reported = false
    private var reportedAtMs = 0L
    private var session = false
    private var wearCheck: Boolean? = null
    private var lastWorn: Boolean? = null
    /** A real worn/not-worn CHANGE was seen in this session: the sensor works after all. */
    private var sensorLive = false

    /** A motion hint's estimate (null = an old glasses build: never an estimate; false = withdrawn). */
    @Synchronized fun onHint(wearEst: Boolean?) {
        reported = wearEst == true
        if (reported) { reportedAtMs = clock(); scheduleExpiry(WearEstimate.FRESH_MS) }
        update()
    }
    /** The link carries data (a session) or not. A new session starts with no wear change seen. */
    @Synchronized fun onLink(carriesData: Boolean) {
        session = carriesData
        if (!carriesData) { reported = false; sensorLive = false; lastWorn = null }
        update()
    }
    /** The SDK's wear value (null = unknown). A not-worn value or a change between two known values is real: a first "worn" may be the pinned one. */
    @Synchronized fun onWear(worn: Boolean?) {
        val prev = lastWorn
        if (worn == false || (worn != null && prev != null && worn != prev)) sensorLive = true
        lastWorn = worn
        update()
    }
    /** The glasses' system wear check as read in the CURRENT link session (null = not read yet / unreadable: no estimate). */
    @Synchronized fun onWearCheck(on: Boolean?) { wearCheck = on; update() }
    /** The expiry timer: an estimate not refreshed within [WearEstimate.FRESH_MS] ends. A timer that fires early (the deadline is not due yet) is re-armed for the rest. */
    @Synchronized fun tick() {
        update()
        if (reported) scheduleExpiry(maxOf(1L, WearEstimate.FRESH_MS - (clock() - reportedAtMs)))
    }

    private fun update() {
        if (reported && clock() - reportedAtMs >= WearEstimate.FRESH_MS) { reported = false; log("glasses: estimate not refreshed, expired") }
        val now = reported && session && WearEstimate.applies(wearCheck, sensorLive)
        if (now == _active.value) return
        _active.value = now
        if (now) log("glasses: not worn (estimated)") else log("glasses: worn again (estimate ended)")
    }
}
