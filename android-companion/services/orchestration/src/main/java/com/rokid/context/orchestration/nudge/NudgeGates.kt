package com.rokid.context.orchestration.nudge

import com.rokid.context.core.model.QuietWindow
import com.rokid.context.memory.db.NudgeGateDao
import com.rokid.context.orchestration.AskText
import java.security.MessageDigest
import java.time.Instant

enum class Gate { PASS, QUIET_HOURS, CAP_REACHED, DUPLICATE, OFF, SHOWN_GAP }

/**
 * Cap invariant: [check] counts and NudgeEngine inserts in two steps, not one transaction. That is safe only because
 * every engine run goes through the unique work "nudge_engine" (ExistingWorkPolicy.KEEP), so runs never overlap. A
 * second caller of NudgeEngine would have to keep that serialisation, or the cap could be exceeded by one.
 *
 * R-61 gates, in order: a cap of 0 → OFF; now inside a quiet-hours occurrence ([QuietWindow]) → QUIET_HOURS (dropped, not deferred); nudges
 * since local midnight ≥ cap → CAP_REACHED; (in [check]) a nudge shown within the shown-nudge gap → SHOWN_GAP; the same collapsed, lower-cased text in the last 24 h → DUPLICATE.
 */
class NudgeGates(
    private val dao: NudgeGateDao,
    /** Minimum gap between shown nudges (notify throttle, spec P2.1); applies in [check] only, never to evaluation. */
    private val shownGapMs: () -> Long = { DEFAULT_SHOWN_GAP_MS },
    private val cap: () -> Int,
) {
    /** The gates that don't depend on the text (checked before any model call). */
    suspend fun precheck(hud: NudgeHud, nowMs: Long): Gate {
        val limit = cap()
        if (limit <= 0) return Gate.OFF
        val local = Instant.ofEpochMilli(nowMs).atZone(hud.zone)
        // One quiet-hours implementation with the phone pause set (integration carry 9): DST-consistent occurrences.
        if (hud.quietHours?.let { QuietWindow.occurrenceAt(it, nowMs, hud.zone) } != null) return Gate.QUIET_HOURS
        val midnight = local.toLocalDate().atStartOfDay(hud.zone).toInstant().toEpochMilli()
        if (dao.countSince(midnight) >= limit) return Gate.CAP_REACHED
        return Gate.PASS
    }

    suspend fun check(text: String, hud: NudgeHud, nowMs: Long): Gate {
        val pre = precheck(hud, nowMs)
        if (pre != Gate.PASS) return pre
        if (shownGapMs() > 0 && dao.countSince(nowMs - shownGapMs()) > 0) return Gate.SHOWN_GAP
        if (dao.existsHashSince(hash(text), nowMs - DAY_MS)) return Gate.DUPLICATE
        return Gate.PASS
    }

    companion object {
        private const val DAY_MS = 24 * 3_600_000L
        const val DEFAULT_SHOWN_GAP_MS = 30 * 60_000L

        /** SHA-256 hex of the collapsed, lower-cased text. */
        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(AskText.collapse(text).lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
