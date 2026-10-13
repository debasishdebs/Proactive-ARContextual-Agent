package com.rokid.context.orchestration.nudge

import com.rokid.context.memory.db.NudgeGateDao
import com.rokid.context.orchestration.AskText
import java.security.MessageDigest
import java.time.Instant

enum class Gate { PASS, QUIET_HOURS, CAP_REACHED, DUPLICATE, OFF }

/**
 * R-61 gates, in order: a cap of 0 → OFF; local now inside quiet hours → QUIET_HOURS (dropped, not deferred); nudges
 * since local midnight ≥ cap → CAP_REACHED; the same collapsed, lower-cased text in the last 24 h → DUPLICATE.
 */
class NudgeGates(private val dao: NudgeGateDao, private val cap: () -> Int) {
    /** The gates that don't depend on the text (checked before any model call). */
    suspend fun precheck(hud: NudgeHud, nowMs: Long): Gate {
        val limit = cap()
        if (limit <= 0) return Gate.OFF
        val local = Instant.ofEpochMilli(nowMs).atZone(hud.zone)
        if (hud.quietHours?.contains(local.hour * 60 + local.minute) == true) return Gate.QUIET_HOURS
        val midnight = local.toLocalDate().atStartOfDay(hud.zone).toInstant().toEpochMilli()
        if (dao.countSince(midnight) >= limit) return Gate.CAP_REACHED
        return Gate.PASS
    }

    suspend fun check(text: String, hud: NudgeHud, nowMs: Long): Gate {
        val pre = precheck(hud, nowMs)
        if (pre != Gate.PASS) return pre
        if (dao.existsHashSince(hash(text), nowMs - DAY_MS)) return Gate.DUPLICATE
        return Gate.PASS
    }

    companion object {
        private const val DAY_MS = 24 * 3_600_000L

        /** SHA-256 hex of the collapsed, lower-cased text. */
        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(AskText.collapse(text).lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
