package com.rokid.context.phone.hub

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.carriesData
import com.rokid.context.core.model.MemoryLine
import com.rokid.context.core.model.NudgeLine
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.UserFacingError
import com.rokid.context.core.model.UserFacingErrors
import com.rokid.context.link.ClockOffset
import com.rokid.context.memory.db.SummaryTraceEntity

/** Everything one [StateFrame] is built from. Instants are phone clock; [activePauses] are phone-clock records. */
data class HubInputs(
    val seq: Long,
    val nowMs: Long,
    val link: LinkState,
    val activePauses: List<PauseRecord>,
    val offset: ClockOffset?,
    val settings: HudSettings,
    val glassesBatteryPct: Int?,
    val nextCaptureAtMs: Long?,
    val queueDepth: Int,
    val lastFrameAtMs: Long?,
    val today: List<MemoryLine>,
    val aiHealth: Health,
    val overlay: Overlay?,
    val error: UserFacingError?,
    /** Phone clock; converted to glasses clock in the frame (Phase 7). */
    val latestNudge: NudgeLine? = null,
    /** rc1 fix: asks a Forget just redacted; the glasses drop an Answer they hold for one of them. */
    val withdrawnAskIds: List<String> = emptyList(),
)

object StateFrameBuilder {
    /**
     * Pause records go out in glasses clock (the glasses enforce them on their own clock, spec §5.5); HUD countdown times (nextCaptureAtMs, lastSentAtMs) too, or null without an offset. Without an offset the records go unchanged (a pause is never dropped).
     */
    fun build(i: HubInputs): StateFrame {
        val pauses = toGlasses(i.activePauses, i.offset)
        return StateFrame(
            seq = i.seq,
            capture = if (pauses.isEmpty()) CaptureState.Running else CaptureState.Paused(pauses),
            nextCaptureAtMs = i.nextCaptureAtMs?.let { m -> i.offset?.toGlasses(m) }, // HUD countdown: glasses clock, omitted without an offset
            intervalSec = i.settings.captureIntervalSec,
            glassesBatteryPct = i.glassesBatteryPct,
            phoneLinked = i.link.carriesData, // minimised: still linked on the background link
            aiHealth = i.aiHealth,
            queueDepth = i.queueDepth,
            lastSentAtMs = i.lastFrameAtMs?.let { m -> i.offset?.toGlasses(m) },
            latestNudge = i.latestNudge?.let { n -> i.offset?.let { o -> n.copy(atMs = o.toGlasses(n.atMs)) } ?: n },
            today = i.today.take(8),
            overlay = i.overlay,
            error = i.error?.title,
            withdrawnAskIds = i.withdrawnAskIds,
        )
    }

    /** `pc_settings` carries the merged pause set (spec §5.5), converted like [build], and why Ask can't start (§5.1.1). */
    /** Phase 7: [resumed] = ids the phone resumed; `pausesExact` only when [offset] measured the conversion; [zoneId] for quiet hours. */
    fun settingsFor(
        settings: HudSettings, activePauses: List<PauseRecord>, offset: ClockOffset?, askBlockedReason: String? = null,
        resumed: List<String> = emptyList(), zoneId: String? = null,
    ): HudSettings = settings.copy(
        pauses = toGlasses(activePauses, offset), askBlockedReason = askBlockedReason,
        resumedPauseIds = resumed, pausesExact = offset != null, zoneId = zoneId,
        planAware = true, // slice 3a: this phone speaks pc_capture_plan (an old phone leaves it false)
    )

    private fun toGlasses(records: List<PauseRecord>, offset: ClockOffset?) = records.map { r -> offset?.recordToGlasses(r) ?: r }
}

/** HUD AI health from the newest model outcome in the last 30 min (spec §3.2 Status page; short text only). */
object AiHealthRule {
    private const val WINDOW_MS = 30 * 60_000L
    private val OK_STATES = setOf("TRUSTED", "NEEDS_REVIEW", "REPROCESSED", "WRITE_CONTEXT_OK", "READ_CONTEXT_OK")
    private val TITLES = UserFacingErrors.all.map { it.title }.toSet()

    fun from(traces: List<SummaryTraceEntity>, nowMs: Long): Health {
        val latest = traces
            .filter { nowMs - it.updatedAt <= WINDOW_MS && (it.state in OK_STATES || it.state == "RETRY" || it.state == "FAILED") }
            .maxByOrNull { it.updatedAt } ?: return Health.Ok
        // Only a known UserFacingError title may reach the glasses; anything else (pre-Task-2 notes) is replaced.
        val title = latest.note.substringBefore(" · ").takeIf { it in TITLES } ?: UserFacingErrors.AiUnavailable.title
        return when (latest.state) {
            "FAILED" -> Health.Blocked(title)
            "RETRY" -> Health.Degraded(title)
            else -> Health.Ok
        }
    }
}
