package com.rokid.context.link

import com.rokid.context.core.model.PauseRecord

/**
 * Glasses wall clock minus phone wall clock, measured from `pc_hello` (spec §5.5); one-way latency (30–60 ms in phase 0)
 * is ignored. All values are epoch ms, so time zones and DST never enter this math.
 */
data class ClockOffset(val glassesMinusPhoneMs: Long) {
    fun toPhone(glassesMs: Long): Long = glassesMs - glassesMinusPhoneMs
    fun toGlasses(phoneMs: Long): Long = phoneMs + glassesMinusPhoneMs
    fun recordToPhone(r: PauseRecord): PauseRecord = r.copy(createdAtMs = toPhone(r.createdAtMs), untilMs = r.untilMs?.let(::toPhone))
    fun recordToGlasses(r: PauseRecord): PauseRecord = r.copy(createdAtMs = toGlasses(r.createdAtMs), untilMs = r.untilMs?.let(::toGlasses))

    companion object {
        fun fromHello(glassesNowMs: Long, phoneReceivedAtMs: Long) = ClockOffset(glassesNowMs - phoneReceivedAtMs)

        /** No offset yet: keep the record's duration but start it at the phone's [nowMs] (never shorter: fail-closed). */
        fun anchoredAt(r: PauseRecord, nowMs: Long): PauseRecord =
            r.copy(createdAtMs = nowMs, untilMs = r.untilMs?.let { nowMs + (it - r.createdAtMs).coerceAtLeast(0) })
    }
}
