package com.rokid.context.memory

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings → Memory (spec §2.4, §P6.1): retention per tier (24 h / 7 d / 30 d defaults), per kind of data (photo 7 d, skipped photo 1 d,
 * caption / scene summary / image vector forever = [FOREVER]) and the soft storage quota (100 MB). [perKindSince] is stamped by the first
 * retention run of the build that has per-kind retention (Ruling 13): photo deadlines count from `max(capture time, perKindSince)`.
 */
data class MemorySettings(
    val shortHours: Int = 24, val mediumDays: Int = 7, val longDays: Int = 30, val quotaMb: Int = 100,
    val photoDays: Int = 7, val skippedPhotoDays: Int = 1, val captionDays: Int = FOREVER, val clusterSummaryDays: Int = FOREVER,
    val imageVectorDays: Int = FOREVER, val perKindSince: Long = 0L,
    /** When the last retention run started: a long gap, or a clock behind it, renews [perKindSince] (fix round 1, I3). */
    val perKindLastRun: Long = 0L,
) {
    val quotaBytes: Long get() = quotaMb.toLong() * 1024 * 1024
    companion object { const val FOREVER = -1 }
}

class MemorySettingsStore(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(read())
    val settings: StateFlow<MemorySettings> = state.asStateFlow()

    private val lock = Any()
    /** True on the thread running a transform: the lock is reentrant, so a nested update would be silently overwritten. */
    private val updating = ThreadLocal<Boolean>()

    /**
     * Atomic: read, transform, one prefs write and publish happen under one lock, so concurrent edits never lose each other.
     * A call from inside its own transform throws IllegalStateException instead of losing the inner write (Phase 5 T20 carry).
     */
    fun update(transform: (MemorySettings) -> MemorySettings) = synchronized(lock) {
        check(updating.get() != true) { "MemorySettingsStore.update called from inside its own transform" }
        updating.set(true)
        try {
            val n = clean(transform(state.value))
            prefs.edit().putInt("short_hours", n.shortHours).putInt("medium_days", n.mediumDays).putInt("long_days", n.longDays).putInt("quota_mb", n.quotaMb)
                .putInt("photo_days", n.photoDays).putInt("skipped_photo_days", n.skippedPhotoDays).putInt("caption_days", n.captionDays)
                .putInt("cluster_summary_days", n.clusterSummaryDays).putInt("image_vector_days", n.imageVectorDays).putLong("per_kind_since", n.perKindSince).putLong("per_kind_last_run", n.perKindLastRun).apply()
            state.value = n
        } finally {
            updating.remove()
        }
    }

    /**
     * Called at the start of each retention run: returns the settings in force. [MemorySettings.perKindSince] is stamped on the first run and
     * renewed (to [nowMs], so deadlines only move later) when the stamp can no longer be trusted: the clock is behind it or behind the last
     * run, the last run was more than 7 days ago (a build switch or restore), or the stamp has no run record. Records the run.
     */
    fun stampPerKindSince(nowMs: Long): MemorySettings {
        update {
            val stale = it.perKindSince == 0L || nowMs < it.perKindSince || it.perKindLastRun == 0L ||
                nowMs < it.perKindLastRun || nowMs - it.perKindLastRun > MAX_RUN_GAP_MS
            // Monotonic: a re-stamp only ever moves the stamp later (a clock set back must not shorten the 7-day protection).
            it.copy(perKindSince = if (stale) maxOf(it.perKindSince, nowMs) else it.perKindSince, perKindLastRun = nowMs)
        }
        return state.value
    }

    private fun read(): MemorySettings {
        val d = MemorySettings()
        return clean(MemorySettings(
            prefs.getInt("short_hours", d.shortHours), prefs.getInt("medium_days", d.mediumDays), prefs.getInt("long_days", d.longDays), prefs.getInt("quota_mb", d.quotaMb),
            prefs.getInt("photo_days", d.photoDays), prefs.getInt("skipped_photo_days", d.skippedPhotoDays), prefs.getInt("caption_days", d.captionDays),
            prefs.getInt("cluster_summary_days", d.clusterSummaryDays), prefs.getInt("image_vector_days", d.imageVectorDays), prefs.getLong("per_kind_since", 0L), prefs.getLong("per_kind_last_run", 0L),
        ))
    }

    private fun clean(s: MemorySettings) = MemorySettings(
        shortHours = s.shortHours.takeIf { it in SHORT_HOURS } ?: 24,
        mediumDays = s.mediumDays.takeIf { it in MEDIUM_DAYS } ?: 7,
        longDays = s.longDays.takeIf { it in LONG_DAYS } ?: 30,
        quotaMb = s.quotaMb.coerceIn(QUOTA_MB.first, QUOTA_MB.last),
        photoDays = s.photoDays.takeIf { it in PHOTO_DAYS } ?: 7,
        skippedPhotoDays = s.skippedPhotoDays.takeIf { it in SKIPPED_DAYS } ?: 1,
        captionDays = s.captionDays.takeIf { it in LONG_KIND_DAYS } ?: MemorySettings.FOREVER,
        clusterSummaryDays = s.clusterSummaryDays.takeIf { it in LONG_KIND_DAYS } ?: MemorySettings.FOREVER,
        imageVectorDays = s.imageVectorDays.takeIf { it in LONG_KIND_DAYS } ?: MemorySettings.FOREVER,
        perKindSince = s.perKindSince.coerceAtLeast(0L),
        perKindLastRun = s.perKindLastRun.coerceAtLeast(0L),
    )

    companion object {
        val SHORT_HOURS = listOf(12, 24, 48)
        val MEDIUM_DAYS = listOf(3, 7, 14)
        val LONG_DAYS = listOf(30, 90, 365)
        val QUOTA_MB = 10..2_000
        private const val MAX_RUN_GAP_MS = 7 * 24 * 3_600_000L
        /** Per-kind choices (spec §P6.1); [MemorySettings.FOREVER] = -1 means never. */
        val PHOTO_DAYS = listOf(1, 3, 7, 30, 90, MemorySettings.FOREVER)
        val SKIPPED_DAYS = listOf(0, 1, 7)
        val LONG_KIND_DAYS = listOf(30, 90, 365, MemorySettings.FOREVER)
        @Volatile private var instance: MemorySettingsStore? = null

        fun get(context: Context): MemorySettingsStore = instance ?: synchronized(this) {
            instance ?: MemorySettingsStore(context.applicationContext.getSharedPreferences("memory_settings", Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
