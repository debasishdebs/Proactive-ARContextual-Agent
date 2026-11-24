package com.rokid.context.memory.retrieval

import com.rokid.context.memory.MemorySettingsStore

/**
 * The Quick recall window (retention v3): a SHORT fact older than it AND without a text vector is "demoted" ([com.rokid.context.memory.db.Demotion]) and leaves the default
 * retrieval pools and Home lists. [sinceMs] is the window's start (`nowMs - window`), null = no window (nothing is demoted).
 */
fun interface RecallWindow {
    fun sinceMs(nowMs: Long): Long?

    companion object {
        val NONE = RecallWindow { null }
        private const val HOUR = 3_600_000L

        /** Fixed hours (tests); a non-positive value is no window. */
        fun hours(h: () -> Int): RecallWindow = RecallWindow { now -> h().takeIf { it > 0 }?.let { now - it * HOUR } }

        /**
         * The user's setting. Today the field is `shortHours`; when the retention branch renames it to `recallHours` this is the ONE line to change.
         */
        fun of(store: MemorySettingsStore): RecallWindow = hours { store.settings.value.shortHours }
    }
}
