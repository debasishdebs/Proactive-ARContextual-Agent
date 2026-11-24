package com.rokid.context.memory

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings → Memory (spec §2.4, §P6.1; retention v3): the Quick recall window [recallHours] (after it a SHORT fact loses only its text vector; NOTHING deletes text but a user Forget), per kind of data (photo 7 d, skipped photo 1 d,
 * image vector forever = [FOREVER]) and the storage cap. [mediumDays], [longDays], [captionDays] and [clusterSummaryDays] are READ and kept (a downgrade reads sane values) but ignored: retention never deletes a memory row. [perKindSince] is stamped by the first
 * retention run of the build that has per-kind retention (Ruling 13): photo deadlines count from `max(capture time, perKindSince)`.
 */
data class MemorySettings(
    val recallHours: Int = 24, val mediumDays: Int = 7, val longDays: Int = 30, val quotaMb: Int = 250,
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
            // R16: a key is written only when it is already stored or the transform changed it. Retention's stamp (every run) and an edit of another setting
            // therefore never freeze a default as if the user had chosen it: an absent quota_mb keeps reading the current default.
            val old = state.value
            val e = prefs.edit()
            fun put(key: String, new: Int, was: Int) { if (prefs.contains(key) || new != was) e.putInt(key, new) }
            // retention v3: Quick recall lives under its NEW key; `short_hours` is never written again, so a downgrade to the candidate (whose clean() maps 72 / 168 to 24) still reads the last legal value
            put("recall_hours", n.recallHours, old.recallHours); put("medium_days", n.mediumDays, old.mediumDays); put("long_days", n.longDays, old.longDays)
            put("quota_mb", n.quotaMb, old.quotaMb); put("photo_days", n.photoDays, old.photoDays); put("skipped_photo_days", n.skippedPhotoDays, old.skippedPhotoDays)
            put("caption_days", n.captionDays, old.captionDays); put("cluster_summary_days", n.clusterSummaryDays, old.clusterSummaryDays)
            put("image_vector_days", n.imageVectorDays, old.imageVectorDays)
            e.putLong("per_kind_since", n.perKindSince).putLong("per_kind_last_run", n.perKindLastRun).apply()
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
            prefs.getInt("recall_hours", prefs.getInt("short_hours", d.recallHours)), prefs.getInt("medium_days", d.mediumDays), prefs.getInt("long_days", d.longDays), prefs.getInt("quota_mb", d.quotaMb),
            prefs.getInt("photo_days", d.photoDays), prefs.getInt("skipped_photo_days", d.skippedPhotoDays), prefs.getInt("caption_days", d.captionDays),
            prefs.getInt("cluster_summary_days", d.clusterSummaryDays), prefs.getInt("image_vector_days", d.imageVectorDays), prefs.getLong("per_kind_since", 0L), prefs.getLong("per_kind_last_run", 0L),
        ))
    }

    private fun clean(s: MemorySettings) = MemorySettings(
        recallHours = s.recallHours.takeIf { it in RECALL_HOURS } ?: 24,
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
        /** Quick recall choices: 12 / 24 / 48 / 72 h / 7 d. */
        val RECALL_HOURS = listOf(12, 24, 48, 72, 168)
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
