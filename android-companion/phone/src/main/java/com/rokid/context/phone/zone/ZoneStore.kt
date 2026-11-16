package com.rokid.context.phone.zone

import android.content.SharedPreferences
import com.rokid.context.core.model.Wire
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.model.zone.ZoneMemo
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The user's pause zones (spec 2026-11-16 §B2): a small JSON list in its own prefs file, no Room. User configuration: backed up and included in the settings export
 * (`ConfigExport.PREFS_FILES`); Delete all never touches it. Also holds the per-install counter that makes every `zone:<id>:<n>` record id unique (never reused, so a closed
 * interval's tombstone can never swallow a later pause). Only valid zones are stored.
 *
 * **Fail closed.** A stored list that cannot be read is NOT an empty list: [corrupt] turns true, [zones] keeps the last good list read or written in this process (empty when none), and the
 * controller holds capture paused ("protected") until [replaceAll] stores a list again. [write] and [nextSeq] return/throw on a failed synchronous commit: a zone change that did not
 * reach the disk is reported, and a record number that did not reach the disk is never used (it could be handed out again after a restart).
 */
class ZoneStore(private val prefs: SharedPreferences) {
    @Volatile private var good: List<PauseZone>? = null
    @Volatile private var bad = false

    /** True when the stored list exists but cannot be read (or the preference read threw): the zones are UNKNOWN. */
    val corrupt: Boolean get() { zones(); return bad }

    @Synchronized
    fun zones(): List<PauseZone> {
        val raw = try { prefs.getString(KEY, null) } catch (e: Exception) { bad = true; return good.orEmpty() }
        if (raw == null) { bad = false; good = emptyList(); return emptyList() }
        val parsed = runCatching { Wire.json.decodeFromString(LIST, raw) }.getOrNull()
        if (parsed == null) { bad = true; return good.orEmpty() }
        bad = false
        return parsed.filter(PauseZone::isValid).distinctBy { it.id }.also { good = it }
    }

    /** Replaces the whole list, committed synchronously. False = the commit failed (the caller reports it; the in-memory list is NOT changed). Invalid zones are dropped. */
    @Synchronized
    fun write(zones: List<PauseZone>): Boolean {
        zones()
        if (bad) return false // never overwrite what cannot be read; [replaceAll] is the explicit way out
        return commit(zones)
    }

    /** The explicit recovery from an unreadable list: stores [zones] as the whole list. */
    @Synchronized
    fun replaceAll(zones: List<PauseZone>): Boolean = commit(zones)

    private fun commit(zones: List<PauseZone>): Boolean {
        val clean = zones.filter(PauseZone::isValid).distinctBy { it.id }
        val ok = try { prefs.edit().putString(KEY, Wire.json.encodeToString(LIST, clean)).commit() } catch (e: Exception) { false }
        if (ok) { good = clean; bad = false }
        return ok
    }

    /** The next record number, durable before it is used. Throws when it cannot be made durable: the number is then never used, so it can never be issued twice. */
    @Synchronized
    fun nextSeq(): Long {
        val n = prefs.getLong(SEQ, 0L) + 1
        check(prefs.edit().putLong(SEQ, n).commit()) { "zone seq not stored" }
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

/**
 * The persisted zone STATE (its own prefs file, NOT exported or restored: it describes this phone's recent past). It is the base of the uncertainty window: after a process death, a hub
 * recovery or a long fix gap, the pause interval of a zone starts at [ZoneMemo.clearWallMs], the last time a fix proved the phone was not inside, so frames taken in the meantime are dropped.
 * An unreadable state is [unreadable]: the controller then backdates by the whole history.
 */
class ZoneStateStore(private val prefs: SharedPreferences) {
    @Synchronized
    fun load(): Map<String, ZoneMemo> = try {
        prefs.getString(KEY, null)?.let { Wire.json.decodeFromString(MAP, it) } ?: emptyMap()
    } catch (e: Exception) { unreadable = true; emptyMap() }

    /** True when the stored state could not be read at [load]. */
    @Volatile var unreadable = false; private set

    /** Synchronous; false = not stored (the in-memory state still holds; a later save retries). */
    @Synchronized
    fun save(memos: Map<String, ZoneMemo>): Boolean = try { prefs.edit().putString(KEY, Wire.json.encodeToString(MAP, memos)).commit() } catch (e: Exception) { false }

    /** The highest wall clock this phone has seen, for clock-rollback detection across a process death; and the rollback total still inside the retained history. */
    @Synchronized fun clock(): Pair<Long, Long> = prefs.getLong(PEAK, 0L) to prefs.getLong(ROLLBACK, 0L)
    @Synchronized fun saveClock(peak: Long, rollbackMs: Long, rollbackAtMs: Long) { prefs.edit().putLong(PEAK, peak).putLong(ROLLBACK, rollbackMs).putLong(ROLLBACK_AT, rollbackAtMs).commit() }
    @Synchronized fun rollbackAt(): Long = prefs.getLong(ROLLBACK_AT, 0L)

    companion object {
        const val PREFS = "pause_zone_state"
        private const val KEY = "memo"
        private const val PEAK = "peakWall"
        private const val ROLLBACK = "rollbackMs"
        private const val ROLLBACK_AT = "rollbackAt"
        private val MAP = MapSerializer(String.serializer(), ZoneMemo.serializer())
    }
}
