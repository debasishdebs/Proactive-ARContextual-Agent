package com.rokid.context.phone.zone

import android.content.SharedPreferences
import com.rokid.context.core.model.Wire
import com.rokid.context.core.model.zone.PauseZone
import kotlinx.serialization.builtins.ListSerializer

/**
 * The user's pause zones (spec 2026-11-16 §B2): a small JSON list in its own prefs file, no Room. User configuration: backed up and included in the settings export
 * (`ConfigExport.PREFS_FILES`); Delete all never touches it. Also holds the per-install counter that makes every `zone:<id>:<n>` record id unique (never reused, so a closed
 * interval's tombstone can never swallow a later pause). Only valid zones are stored or read; an unreadable list reads as NO zones.
 */
class ZoneStore(private val prefs: SharedPreferences) {
    @Synchronized
    fun zones(): List<PauseZone> = prefs.getString(KEY, null)?.let {
        runCatching { Wire.json.decodeFromString(LIST, it) }.getOrNull()?.filter(PauseZone::isValid)?.distinctBy { z -> z.id }
    } ?: emptyList()

    /** Replaces the whole list (committed synchronously: a zone must not be lost to a process death). Invalid zones are dropped. */
    @Synchronized
    fun write(zones: List<PauseZone>) {
        val clean = zones.filter(PauseZone::isValid).distinctBy { it.id }
        prefs.edit().putString(KEY, Wire.json.encodeToString(LIST, clean)).commit()
    }

    /** The next record number, durable before it is used. */
    @Synchronized
    fun nextSeq(): Long {
        val n = prefs.getLong(SEQ, 0L) + 1
        prefs.edit().putLong(SEQ, n).commit()
        return n
    }

    companion object {
        /** The prefs file (user configuration: restorable, exported). */
        const val PREFS = "pause_zones"
        private const val KEY = "zones"
        private const val SEQ = "seq"
        private val LIST = ListSerializer(PauseZone.serializer())
    }
}
