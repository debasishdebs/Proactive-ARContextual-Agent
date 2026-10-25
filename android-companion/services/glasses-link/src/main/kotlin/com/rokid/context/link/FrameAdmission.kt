package com.rokid.context.link

import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.FrameMeta
import com.rokid.context.core.model.MarkRef

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
/** What the phone knows of the mark a photo names (a real stored mark label `mk:<epoch>:<seq>`). */
enum class MarkState { OPEN, UNKNOWN, CLOSED }

/** Looks a mark up: OPEN = stored, no photo yet and still waiting for one; UNKNOWN = not stored (yet); CLOSED = it has its photo, no longer waits, or was undone. */
interface MarkLookup {
    suspend fun state(markRef: String): MarkState
    /** DURABLE claim: true when [frameId] holds (or just took) the one allowance of the mark; false when another frame id does. Survives eviction and restart. */
    suspend fun claim(markRef: String, frameId: String): Boolean
}

/**
 * The allowance behind the ONE phone-side pause exception: a photo passes the pause history only for a REAL stored mark that still waits for its photo, and each mark's allowance goes to ONE frame id
 * (a resend of that frame passes again; a second frame naming the same mark does not). At most [cap] marks are claimed at once (the oldest claim is evicted), so a flood of frames cannot ride it.
 */
class MarkPhotoAllowance(private val lookup: MarkLookup, private val cap: Int = 16) {
    private val claims = LinkedHashMap<String, String>()
    internal val claimed: Int get() = synchronized(claims) { claims.size }

    suspend fun check(markRef: String, frameId: String): MarkState {
        if (!MarkRef.isValid(markRef)) return MarkState.CLOSED
        synchronized(claims) { claims[markRef]?.let { return if (it == frameId) MarkState.OPEN else MarkState.CLOSED } }
        val st = lookup.state(markRef)
        if (st != MarkState.OPEN) return st
        if (!lookup.claim(markRef, frameId)) return MarkState.CLOSED // the durable claim decides; the map below is only a fast path
        synchronized(claims) {
            val other = claims[markRef]
            if (other != null) return if (other == frameId) MarkState.OPEN else MarkState.CLOSED
            claims[markRef] = frameId
            while (claims.size > cap) claims.remove(claims.keys.first())
        }
        return MarkState.OPEN
    }

    companion object { val NONE = MarkPhotoAllowance(object : MarkLookup { override suspend fun state(markRef: String) = MarkState.CLOSED; override suspend fun claim(markRef: String, frameId: String) = false }) }
}

class FrameAdmission(
    private val ledger: ReceivedFrameLedger, private val pauses: PauseHistory, private val historyMs: Long = HISTORY_MS,
    /** Without it (the default) no frame passes a pause. */
    private val marks: MarkPhotoAllowance = MarkPhotoAllowance.NONE,
) {
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
        // v0.9.6 slice C, the ONE phone-side exception of "a mark touches capture": the photo taken for a mark passes the pause history (the user asked for it explicitly) only when its mark is REAL (stored, no
        // photo yet) and this frame holds that mark's single allowance ([MarkPhotoAllowance]). A photo whose mark has not arrived yet is held (no ack: the glasses resend once the mark is stored).
        // Everything else of that moment is still dropped. The wipe fence, the Moment Forget fences and the dedupe above and in the intake still apply.
        if (pauses.pausedAt(t)) {
            val ref = meta.markRef
            if (ref == null || !MarkRef.isValid(ref)) return Admission.AckOnly(FrameAckReason.PAUSED_DROP)
            when (marks.check(ref, meta.frameId)) {
                MarkState.OPEN -> Unit
                MarkState.UNKNOWN -> return Admission.Hold
                MarkState.CLOSED -> return Admission.AckOnly(FrameAckReason.PAUSED_DROP)
            }
        }
        return Admission.Ingest(t)
    }

    companion object {
        /** Normal clock jitter between a fresh offset and a frame stamp; beyond it the offset is treated as stale. */
        const val SKEW_TOLERANCE_MS = 5_000L
        /** Pause intervals are kept 7 days (spec §5.5); an older frame cannot be checked, so it is dropped. */
        const val HISTORY_MS = 7 * 24 * 3_600_000L
    }
}
