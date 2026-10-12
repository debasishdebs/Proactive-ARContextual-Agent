package com.rokid.context.glasses

import android.content.Context

enum class PulseHudEventType(val persistedValue: String) {
    CAPTURED("captured"),
    SENT("sent"),
    FAILED("failed"),
    ;

    companion object {
        fun fromPersisted(raw: String?): PulseHudEventType? {
            return entries.firstOrNull { it.persistedValue == raw?.trim()?.lowercase() }
        }
    }
}

data class PulseHudEvent(
    val type: PulseHudEventType,
    val atMs: Long,
)

data class PulseHudStatus(
    val text: String,
    val atMs: Long,
)

object PulseHudEventStore {
    private const val PREF_NAME = "rokid_pulse_hud_event"
    private const val KEY_TYPE = "event_type"
    private const val KEY_AT_MS = "event_at_ms"
    private const val KEY_STATUS_TEXT = "status_text"
    private const val KEY_STATUS_AT_MS = "status_at_ms"

    fun publish(context: Context, type: PulseHudEventType): Long {
        val atMs = System.currentTimeMillis()
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TYPE, type.persistedValue)
            .putLong(KEY_AT_MS, atMs)
            .apply()
        return atMs
    }

    fun latest(context: Context): PulseHudEvent? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val type = PulseHudEventType.fromPersisted(prefs.getString(KEY_TYPE, null)) ?: return null
        val atMs = prefs.getLong(KEY_AT_MS, -1L)
        if (atMs <= 0L) return null
        return PulseHudEvent(type = type, atMs = atMs)
    }

    fun publishStatus(context: Context, rawText: String): Long {
        val text = rawText.trim().replace("\\s+".toRegex(), " ").take(180)
        val atMs = System.currentTimeMillis()
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS_TEXT, text)
            .putLong(KEY_STATUS_AT_MS, atMs)
            .apply()
        return atMs
    }

    fun latestStatus(context: Context): PulseHudStatus? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY_STATUS_TEXT, null)?.trim().orEmpty()
        if (text.isBlank()) return null
        val atMs = prefs.getLong(KEY_STATUS_AT_MS, -1L)
        if (atMs <= 0L) return null
        return PulseHudStatus(text = text, atMs = atMs)
    }
}
