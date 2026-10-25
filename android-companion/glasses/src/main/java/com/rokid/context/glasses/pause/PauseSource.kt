package com.rokid.context.glasses.pause

import com.rokid.context.core.model.PauseOrigin

/**
 * WHO paused capture, as a content-free word for the glasses logs (`status`, `heartbeat`, `trace state:` and the pause lines). It is kept beside the records in the
 * glasses' own pause store, never on the wire: [com.rokid.context.core.model.PauseRecord] and the Hello are unchanged. A record from a store written before this existed
 * reads as [PERSISTED]. The battery policy is not a pause record: it shows as BATTERY_LOW through [forReason].
 */
enum class PauseSource {
    USER_GLASSES, USER_PHONE, QUIET_HOURS, STORE_UNREADABLE, PERSISTED, OTHER;

    companion object {
        fun of(origin: PauseOrigin): PauseSource = when (origin) {
            PauseOrigin.GLASSES -> USER_GLASSES
            PauseOrigin.PHONE -> USER_PHONE
            PauseOrigin.QUIET_HOURS -> QUIET_HOURS
        }

        /** An unknown or missing code is a record an older build stored. */
        fun parse(code: String?): PauseSource = entries.firstOrNull { it.name == code } ?: PERSISTED

        /** The source word for a session status [reason]: the pause records for "paused", the battery policy for the two battery reasons, "-" for everything else. */
        fun forReason(reason: String?, pausesLabel: String): String = when (reason) {
            "paused" -> pausesLabel
            "battery" -> "BATTERY_LOW"
            "battery_budget" -> "BATTERY_BUDGET"
            else -> "-"
        }
    }
}
