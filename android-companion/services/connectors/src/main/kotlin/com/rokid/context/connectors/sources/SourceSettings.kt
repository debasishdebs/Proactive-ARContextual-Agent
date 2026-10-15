package com.rokid.context.connectors.sources

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Settings › Data sources (R-64): which sources feed `{{context_sources}}` (all off by default) and the nudge daily
 * cap ("Nudges per day" 0/3/6/10, default 6; 0 = off). Build it off Main: the constructor reads prefs.
 */
class SourceSettingsStore(private val prefs: SharedPreferences) {
    private val on = MutableStateFlow(SourceKind.entries.filter { prefs.getBoolean(key(it), false) }.toSet())
    val enabled: StateFlow<Set<SourceKind>> = on.asStateFlow()

    private val cap = MutableStateFlow(prefs.getInt(KEY_CAP, DEFAULT_CAP).takeIf { it in CAPS } ?: DEFAULT_CAP)
    val nudgesPerDay: StateFlow<Int> = cap.asStateFlow()

    private val settle = MutableStateFlow(choice(KEY_SETTLE, SETTLE_SEC, DEFAULT_SETTLE_SEC))
    private val checkGap = MutableStateFlow(choice(KEY_CHECK_GAP, CHECK_GAP_SEC, DEFAULT_CHECK_GAP_SEC))
    private val shownGap = MutableStateFlow(choice(KEY_SHOWN_GAP, SHOWN_GAP_MIN, DEFAULT_SHOWN_GAP_MIN))

    /** Settle window before a live nudge check, seconds (3/5/10/15, default 5). */
    val settleSec: StateFlow<Int> = settle.asStateFlow()
    /** Minimum gap between nudge checks, seconds (30/60/120, default 30). */
    val checkGapSec: StateFlow<Int> = checkGap.asStateFlow()
    /** Minimum gap between shown nudges, minutes (10/30/60, default 30). */
    val shownGapMin: StateFlow<Int> = shownGap.asStateFlow()

    private fun choice(key: String, allowed: List<Int>, default: Int) = prefs.getInt(key, default).takeIf { it in allowed } ?: default

    /** Values outside the allowed lists are ignored. */
    fun setSettleSec(n: Int) { if (n in SETTLE_SEC) { prefs.edit().putInt(KEY_SETTLE, n).apply(); settle.value = n } }
    fun setCheckGapSec(n: Int) { if (n in CHECK_GAP_SEC) { prefs.edit().putInt(KEY_CHECK_GAP, n).apply(); checkGap.value = n } }
    fun setShownGapMin(n: Int) { if (n in SHOWN_GAP_MIN) { prefs.edit().putInt(KEY_SHOWN_GAP, n).apply(); shownGap.value = n } }

    fun set(k: SourceKind, on: Boolean) {
        prefs.edit().putBoolean(key(k), on).apply()
        this.on.update { if (on) it + k else it - k }
    }

    /** Values other than 0/3/6/10 are ignored. */
    fun setNudgesPerDay(n: Int) {
        if (n !in CAPS) return
        prefs.edit().putInt(KEY_CAP, n).apply()
        cap.value = n
    }

    companion object {
        const val PREFS = "context_sources"
        val CAPS = listOf(0, 3, 6, 10)
        const val DEFAULT_CAP = 6
        val SETTLE_SEC = listOf(3, 5, 10, 15)
        const val DEFAULT_SETTLE_SEC = 5
        val CHECK_GAP_SEC = listOf(30, 60, 120)
        const val DEFAULT_CHECK_GAP_SEC = 30
        val SHOWN_GAP_MIN = listOf(10, 30, 60)
        const val DEFAULT_SHOWN_GAP_MIN = 30
        private const val KEY_SETTLE = "nudge_settle_sec"
        private const val KEY_CHECK_GAP = "nudge_check_gap_sec"
        private const val KEY_SHOWN_GAP = "nudge_shown_gap_min"
        private const val KEY_CAP = "nudges_per_day"
        private fun key(k: SourceKind) = "source_" + k.name.lowercase()
    }
}
