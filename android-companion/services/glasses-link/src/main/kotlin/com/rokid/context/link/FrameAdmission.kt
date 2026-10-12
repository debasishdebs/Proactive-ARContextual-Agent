package com.rokid.context.link

import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.FrameMeta

/** Retained pause history in phone clock (spec §5.5); the hub's Room-backed `HubPauses` in production. */
interface PauseHistory {
    /** True when [phoneMs] falls inside a retained pause interval, open or closed. */
    suspend fun pausedAt(phoneMs: Long): Boolean
}

sealed interface Admission {
    /** Store and ingest; [capturedAtPhoneMs] is the offset-corrected capture time. Ack ok=true after the write. */
    data class Ingest(val capturedAtPhoneMs: Long) : Admission
    /** Ack ok=true with [reason] without ingesting: the glasses delete the frame. */
    data class AckOnly(val reason: String) : Admission
    /** Ack ok=false with [reason]: the glasses resend once, then drop it. */
    data class Reject(val reason: String) : Admission
    /** No ack: the glasses keep the frame and retry on a later flush (fail-closed). */
    data object Hold : Admission
}

/** What the phone does with a complete frame (spec §1.8 dedupe, §5.5 flush filter). */
class FrameAdmission(private val ledger: ReceivedFrameLedger, private val pauses: PauseHistory, private val historyMs: Long = HISTORY_MS) {
    suspend fun decide(meta: FrameMeta, offset: ClockOffset?, nowMs: Long): Admission {
        if (!FrameCodec.isValidFrameId(meta.frameId)) return Admission.Reject(FrameAckReason.DECODE)
        if (ledger.contains(meta.frameId)) return Admission.AckOnly(FrameAckReason.DUPLICATE)
        offset ?: return Admission.Hold
        val captured = offset.toPhone(meta.capturedAtMs)
        // A frame "from the future" means the offset is stale (clock jump, old hello): judging it at "now" would let a
        // frame taken inside a closed pause slip through. Hold it; the link asks for a fresh hello.
        if (captured > nowMs + SKEW_TOLERANCE_MS) return Admission.Hold
        val t = captured.coerceAtMost(nowMs)
        if (t < nowMs - historyMs) return Admission.AckOnly(FrameAckReason.TOO_OLD)
        if (pauses.pausedAt(t)) return Admission.AckOnly(FrameAckReason.PAUSED_DROP)
        return Admission.Ingest(t)
    }

    companion object {
        /** Normal clock jitter between a fresh offset and a frame stamp; beyond it the offset is treated as stale. */
        const val SKEW_TOLERANCE_MS = 5_000L
        /** Pause intervals are kept 7 days (spec §5.5); an older frame cannot be checked, so it is dropped. */
        const val HISTORY_MS = 7 * 24 * 3_600_000L
    }
}
