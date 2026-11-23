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
     * matcher never reads it. An explicit Forget (or Delete all) of that Moment ALSO DELETES the pin (the user is asked first and the dialog names it); a removal that is not a Forget (expiry, sweep) only clears the link, the pin stays and shows "Needs a Moment". Never exported, backed up or transferred.
     */
    val momentId: String? = null,
    /** Slice 1.4.2: when a NEW pin was made (epoch ms), so a later suggestion can find the Moment closest to it. Null for pins made before it existed. NOT in the settings export ([withoutLinks] strips it with the Moment link), never backed up or transferred; not read by matching. */
    val pinnedAt: Long? = null,
) {
    val isValid: Boolean
        get() = ID.matches(id) && name.isNotBlank() && name.length <= NAME_MAX && '\n' !in name && kind in PlaceKinds.ALL &&
            lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
            radiusM in MIN_RADIUS_M..PlaceKinds.MAX_RADIUS_M && note.length <= NOTE_MAX && '\n' !in note &&
            (parentId == null || (parentId != id && !parentId.startsWith(PREFIX) && parentId.isNotBlank())) &&
            (momentId == null || com.rokid.context.memory.moment.MomentId.parse(momentId) != null) && (pinnedAt == null || pinnedAt > 0)

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

    /** Clears every link whose Moment id is not in [alive] (except the pins in [keep]: an unfinished arming still needs their link); returns the ids of the PINS cleared (never the Moment ids). The pins themselves stay. A corrupt stored list is left alone (null). */
    @Synchronized
    fun clearMomentsExcept(alive: Set<String>, keep: Set<String> = emptySet()): Set<String>? {
        val now = pins()
        if (bad) return null
        val gone = now.filter { it.momentId != null && it.momentId !in alive && it.id !in keep }
        if (gone.isEmpty()) return emptySet()
        val ids = gone.map { it.id }.toSet()
        return if (write(now.map { if (it.id in ids) it.copy(momentId = null) else it })) ids else null
    }

    /**
     * Slice 1.4.2 (Forget cascade): the durable ARMING of one explicit Forget or Delete all. It belongs to ONE operation ([op]) and names the exact (pin, Moment) pairs that operation may delete; [committed] is
     * set only once the Forget (or the wipe's epoch) really committed. A record that is not committed is never acted on (it is dropped); a committed one is finished at the next start. Not part of the pin
     * list: [pins] and [write] never see it, and the settings export drops it.
     */
    data class Arming(val op: String, val kind: String, val committed: Boolean, val pairs: Map<String, String>,
        /** Once [committed]: exactly the (pin, Moment) pairs whose deletion is now due (stored together with `committed` in ONE write). Before commit: pairs carried over from an older unfinished arming. */
        val dead: Map<String, String> = emptyMap())

    /** The stored arming, or null (none, or unreadable: an unreadable record is treated as none and dropped at the next resolve). */
    @Synchronized
    fun arming(): Arming? = try {
        prefs.getString(ARMED_KEY, null)?.let { raw ->
            val o = JSONObject(raw); val a = o.getJSONArray("pairs")
            Arming(o.getString("op"), o.getString("kind"), o.getBoolean("committed"), pairsOf(a), o.optJSONArray("dead")?.let { pairsOf(it) } ?: emptyMap())
        }
    } catch (e: Exception) { null }

    /** True when something is stored under the arming key (even if unreadable). */
    @Synchronized
    fun hasArmingRecord(): Boolean = try { prefs.contains(ARMED_KEY) } catch (e: Exception) { false }

    /** Stores [a] durably and reads it back; false = not stored or not readable back (the caller must NOT run the Forget). */
    @Synchronized
    fun arm(a: Arming): Boolean {
        val json = JSONObject().put("op", a.op).put("kind", a.kind).put("committed", a.committed).put("pairs", pairsJson(a.pairs)).put("dead", pairsJson(a.dead)).toString()
        val ok = try { prefs.edit().putString(ARMED_KEY, json).commit() } catch (e: Exception) { false }
        return ok && arming() == a
    }

    private fun pairsJson(m: Map<String, String>) = JSONArray().also { arr -> m.toSortedMap().forEach { (p, m2) -> arr.put(JSONArray().put(p).put(m2)) } }
    private fun pairsOf(a: JSONArray): Map<String, String> = (0 until a.length()).associate { i -> a.getJSONArray(i).let { it.getString(0) to it.getString(1) } }

    /** Marks arming [op] committed (the Forget or the wipe epoch committed). False = no such arming or not stored. */
    @Synchronized
    fun markCommitted(op: String): Boolean { val a = arming() ?: return false; return a.op == op && (a.committed || arm(a.copy(committed = true))) }

    @Synchronized
    fun disarm(): Boolean = try { prefs.edit().remove(ARMED_KEY).commit() } catch (e: Exception) { false }

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
            pins.forEach { p -> a.put(JSONObject().put("id", p.id).put("name", p.name).put("kind", p.kind).put("lat", p.lat).put("lon", p.lon).put("r", p.radiusM).put("parent", p.parentId ?: JSONObject.NULL).put("note", p.note).also { o -> if (p.momentId != null) o.put("moment", p.momentId); if (p.pinnedAt != null) o.put("at", p.pinnedAt) }) }
        }.toString()

        /** [raw] (the stored list) without any Moment link: what the settings export carries. An unreadable list is NOT returned (it could still carry a Moment id and a time): it exports as an empty list `[]`, so nothing unsanitised is ever shared. */
        fun withoutLinks(raw: String): String = try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { it.remove("moment"); it.remove("at") }
            a.toString()
        } catch (e: Exception) { "[]" }

        fun parse(raw: String): List<UserPlace> {
            val a = JSONArray(raw)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                UserPlace(o.getString("id"), o.getString("name"), o.getString("kind"), o.getDouble("lat"), o.getDouble("lon"), o.getInt("r"), if (o.isNull("parent")) null else o.getString("parent"), o.optString("note", ""), if (o.isNull("moment")) null else o.optString("moment", "").takeIf { com.rokid.context.memory.moment.MomentId.parse(it) != null }, if (o.isNull("at")) null else o.optLong("at", 0L).takeIf { it > 0 }) // an unreadable link is no link: it never makes the list corrupt
            }
        }
    }
}
