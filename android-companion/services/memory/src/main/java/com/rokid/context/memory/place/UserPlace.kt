package com.rokid.context.memory.place

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A place the user pinned (Place Guide v2, spec 2026-11-16 §B3 / §D 1.2.0): a sub-place Wikidata lacks (Sri Lanka's Mirror Wall, Lion's Paw) or any spot of their own. Everything in it is typed by the
 * user and stays on the phone: the [name] is a candidate name for the match (names only, like any other place), the [note] IS the card text ("Your note"), and neither is ever fetched, logged or sent
 * anywhere else. [id] is `user:<uuid>`; the pack holds it as a row with source `user` and `pinned = true` (never evicted, never overwritten by a fetch).
 */
data class UserPlace(
    val id: String,
    val name: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Int = DEFAULT_RADIUS_M,
    /** The site this is a part of: a pack place id (`wd:…`, `osm:…`), or null. Never another user place. */
    val parentId: String? = null,
    /** One line, at most [NOTE_MAX] characters. */
    val note: String = "",
    /**
     * Slice 1.4.2 "Pin to Moment": the OPTIONAL reference Moment (an 8-hex Moment id) the user attached so they can check, later, that the capture of this spot was clear. A reference only: the
     * matcher never reads it. Cleared (the pin stays) when that Moment is forgotten by any path; never exported, backed up or transferred.
     */
    val momentId: String? = null,
) {
    val isValid: Boolean
        get() = ID.matches(id) && name.isNotBlank() && name.length <= NAME_MAX && '\n' !in name && kind in PlaceKinds.ALL &&
            lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
            radiusM in MIN_RADIUS_M..PlaceKinds.MAX_RADIUS_M && note.length <= NOTE_MAX && '\n' !in note &&
            (parentId == null || (parentId != id && !parentId.startsWith(PREFIX) && parentId.isNotBlank())) &&
            (momentId == null || com.rokid.context.memory.moment.MomentId.parse(momentId) != null)

    /** The pack row: pinned, source `user`, the note as summary. [lastUsedDay] is kept across edits by the pack. */
    fun toEntity(lastUsedDay: Int = 0): PlaceEntity {
        val row = PlaceEntity(id, parentId, name, kind, lat, lon, radiusM, note, SOURCE, SOURCE, 0, seeded = false, pinned = true, lastUsedDay = lastUsedDay, bytes = 0)
        return row.copy(bytes = PlacePack.sizeOf(row))
    }

    companion object {
        const val PREFIX = "user:"
        const val SOURCE = "user"
        const val DEFAULT_RADIUS_M = 60
        const val MIN_RADIUS_M = 10
        const val NOTE_MAX = 140
        const val NAME_MAX = 80
        private val ID = Regex("user:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun newId(): String = PREFIX + UUID.randomUUID().toString()
        fun isUserId(id: String): Boolean = id.startsWith(PREFIX)

        /** Collapses whitespace (one line) and cuts to [NOTE_MAX] characters. */
        fun cleanNote(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(NOTE_MAX)
        fun cleanName(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(NAME_MAX)
    }
}

/**
 * The user's pinned places: a small JSON list in its own prefs file (`place_pins`), the SOURCE OF TRUTH for the pins. User configuration: kept by Delete all, included in the
 * explicit settings export (`ConfigExport.PREFS_FILES`) but EXCLUDED from backup, cloud backup and device transfer (exact positions and notes are private). `places.db` holds a copy of each pin (so the matcher finds it with the rest of the pack); that database is excluded from backup and
 * is rebuilt from this list ([PlacePack.replaceUserPlaces]) at every start; a restore does NOT bring the pins back (only importing the settings export does). A stored list that cannot be read is [corrupt]: it is never overwritten by [write]
 * and the pack keeps its copies.
 */
class UserPinStore(private val prefs: SharedPreferences) {
    @Volatile private var bad = false
    val corrupt: Boolean get() { pins(); return bad }

    @Synchronized
    fun pins(): List<UserPlace> {
        val raw = try { prefs.getString(KEY, null) } catch (e: Exception) { bad = true; return emptyList() }
        if (raw == null) { bad = false; return emptyList() }
        val parsed = try { parse(raw) } catch (e: Exception) { null }
        if (parsed == null || parsed.any { !it.isValid } || parsed.distinctBy { it.id }.size != parsed.size) { bad = true; return emptyList() }
        bad = false
        return parsed
    }

    /** Replaces the list, committed synchronously. False = refused (invalid pin, repeated id, unreadable stored list) or the commit failed. */
    @Synchronized
    fun write(pins: List<UserPlace>): Boolean {
        pins() // refresh [bad]
        if (bad) return false
        if (pins.any { !it.isValid } || pins.distinctBy { it.id }.size != pins.size || pins.size > MAX_PINS) return false
        val before = try { parse(prefs.getString(KEY, null) ?: "[]").associateBy { it.id } } catch (e: Exception) { emptyMap() }
        val ok = try { prefs.edit().putString(KEY, encode(pins)).commit() } catch (e: Exception) { false }
        if (ok) {
            val after = pins.associateBy { it.id }
            // removed or edited pins (ids only): anything derived from them must be withdrawn at once
            // a change of the Moment link alone is no edit of the pin (nothing derived from the pin cites it)
            val changed = before.filter { (id, old) -> after[id]?.copy(momentId = null) != old.copy(momentId = null) }.keys
            if (changed.isNotEmpty()) notifyChanged(changed)
        }
        return ok
    }

    /**
     * Sets (or, with null, removes) the Moment link of one pin. False = unknown pin, invalid id or unreadable/unwritable list. Fires no change listener (the link is not part of what the matcher sees).
     */
    @Synchronized
    fun setMoment(pinId: String, momentId: String?): Boolean {
        val now = pins()
        if (bad) return false
        val pin = now.firstOrNull { it.id == pinId } ?: return false
        if (pin.momentId == momentId) return true
        return write(now.map { if (it.id == pinId) it.copy(momentId = momentId) else it })
    }

    /** Clears every link whose Moment id is not in [alive]; returns the ids of the PINS cleared (never the Moment ids). The pins themselves stay. A corrupt stored list is left alone (null). */
    @Synchronized
    fun clearMomentsExcept(alive: Set<String>): Set<String>? {
        val now = pins()
        if (bad) return null
        val gone = now.filter { it.momentId != null && it.momentId !in alive }
        if (gone.isEmpty()) return emptySet()
        val ids = gone.map { it.id }.toSet()
        return if (write(now.map { if (it.id in ids) it.copy(momentId = null) else it })) ids else null
    }

    /**
     * Slice 1.4.2 (Forget cascade). Before an EXPLICIT user Forget runs, the pins whose reference Moment is about to be at risk are ARMED (ids only, merged with any earlier arming, durable): once the Forget
     * committed, an armed pin whose Moment is gone is deleted with it, also after a crash (the resume reads the arming). While anything is armed, the non-explicit sweep leaves links alone. Not part of
     * the pin list: [pins] and [write] never see it, and the settings export drops it.
     */
    @Synchronized
    fun arm(ids: Set<String>) {
        if (ids.isEmpty()) return
        val all = armed() + ids
        try { prefs.edit().putString(ARMED_KEY, JSONArray(all.sorted()).toString()).commit() } catch (e: Exception) { }
    }

    @Synchronized
    fun armed(): Set<String> = try { prefs.getString(ARMED_KEY, null)?.let { raw -> JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } } ?: emptySet() } catch (e: Exception) { emptySet() }

    @Synchronized
    fun disarm() { try { prefs.edit().remove(ARMED_KEY).commit() } catch (e: Exception) { } }

    /** Clears every link (Delete all). Returns the pins cleared, or null when the list could not be rewritten. */
    fun clearAllMoments(): Set<String>? = clearMomentsExcept(emptySet())

    companion object {
        /** The prefs file: user configuration (exported on request, never in a backup or transfer, never deleted by Delete all). */
        const val PREFS = "place_pins"
        const val MAX_PINS = 200
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Set<String>) -> Unit>()
        /** v3.0: called (on the writer's thread, after a successful commit) with the ids of pins that were deleted or edited. Never throws into the writer. */
        fun addChangeListener(l: (Set<String>) -> Unit) { if (l !in listeners) listeners += l }
        fun removeChangeListener(l: (Set<String>) -> Unit) { listeners -= l }
        private fun notifyChanged(ids: Set<String>) { for (l in listeners) try { l(ids) } catch (_: Exception) { } }
        private const val KEY = "pins"
        /** The arming of a pending Forget cascade (see [arm]): a prefs key beside the list, never exported. */
        const val ARMED_KEY = "armed"

        fun encode(pins: List<UserPlace>): String = JSONArray().also { a ->
            pins.forEach { p -> a.put(JSONObject().put("id", p.id).put("name", p.name).put("kind", p.kind).put("lat", p.lat).put("lon", p.lon).put("r", p.radiusM).put("parent", p.parentId ?: JSONObject.NULL).put("note", p.note).also { o -> if (p.momentId != null) o.put("moment", p.momentId) }) }
        }.toString()

        /** [raw] (the stored list) without any Moment link: what the settings export carries. An unreadable list is returned as it is (the export never invents content). */
        fun withoutLinks(raw: String): String = try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) a.optJSONObject(i)?.remove("moment")
            a.toString()
        } catch (e: Exception) { raw }

        fun parse(raw: String): List<UserPlace> {
            val a = JSONArray(raw)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                UserPlace(o.getString("id"), o.getString("name"), o.getString("kind"), o.getDouble("lat"), o.getDouble("lon"), o.getInt("r"), if (o.isNull("parent")) null else o.getString("parent"), o.optString("note", ""), if (o.isNull("moment")) null else o.optString("moment", "").takeIf { com.rokid.context.memory.moment.MomentId.parse(it) != null }) // an unreadable link is no link: it never makes the list corrupt
            }
        }
    }
}
