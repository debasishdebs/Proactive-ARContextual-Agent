package com.rokid.context.glasses.capture.keyframe

import android.content.SharedPreferences

/** [GateStore] over the "capture_guard" prefs (the ring is one string, oldest first). */
class PrefsGateStore(private val prefs: SharedPreferences) : GateStore {
    override fun lastKeyframes(): List<Pair<Long, Long>> =
        (prefs.getString(KEY_RING, null) ?: "").split(',').mapNotNull { e ->
            val parts = e.split(':')
            if (parts.size != 2) null else runCatching { parts[0].toULong(16).toLong() to parts[1].toULong(16).toLong() }.getOrNull()
        }.takeLast(RING)

    override fun push(d: Long, p: Long) {
        val next = (lastKeyframes() + (d to p)).takeLast(RING)
        prefs.edit().putString(KEY_RING, next.joinToString(",") { "${it.first.toULong().toString(16)}:${it.second.toULong().toString(16)}" }).apply()
    }

    override fun lastKeptAt(): Long = prefs.getLong(KEY_LAST_KEPT_AT, 0L)
    override fun setLastKeptAt(t: Long) { prefs.edit().putLong(KEY_LAST_KEPT_AT, t).apply() }

    companion object {
        const val RING = 5
        private const val KEY_RING = "keyframe_ring"
        private const val KEY_LAST_KEPT_AT = "last_kept_at"
    }
}
