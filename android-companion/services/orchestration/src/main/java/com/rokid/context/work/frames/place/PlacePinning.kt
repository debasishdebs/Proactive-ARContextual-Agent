package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.Geo
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.UserPlace

/** Why a pin was not made. */
enum class PinRefusal {
    /** A pause zone is active, approached or unreadable: nothing is created inside or near one, and an unknown zone state counts as a zone. */
    ZONE,
    /** No fix, or not a fresh and accurate one. The pin never uses an old position. */
    NO_FRESH_FIX,
    /** The draft's fix is older than [PlacePinning.DRAFT_TTL_MS]: start again. */
    DRAFT_EXPIRED,
    /** The form is not valid (name, kind, radius, note, parent) or the list is full. */
    INVALID,
    /** The pins could not be stored. */
    NOT_STORED,
}

sealed interface PinResult {
    data class Saved(val place: UserPlace) : PinResult
    data class Refused(val why: PinRefusal) : PinResult
}

/** A pack place the new pin can hang under (a site). */
data class ParentChoice(val id: String, val name: String, val kind: String)

/** What the user typed. */
data class PinForm(val name: String, val kind: String, val radiusM: Int = UserPlace.DEFAULT_RADIUS_M, val parentId: String? = null, val note: String = "")

/**
 * User-pinned places (Place Guide v2). "Pin a place here" takes the current position ONCE, from a fresh fix only ([fix] returns null for a stale or coarse one), and only when no pause zone is
 * active, approached or unreadable ([zoneBlocked], checked at the start AND again when the pin is saved). The position lives in this object's memory for at most [DRAFT_TTL_MS] between the
 * two steps; it is never logged and never sent anywhere: the pin's own coordinates are stored in the user's pin list, like a pause zone's box.
 *
 * Editing or deleting a pin needs no fix. The name and the note are the user's own words and stay on the phone ([log] gets literal text and counts only).
 */
class PlacePinning(
    private val fix: suspend () -> PlaceFix?,
    private val zoneBlocked: suspend () -> Boolean,
    private val nearby: suspend (lat: Double, lon: Double) -> List<PlaceEntity>,
    private val load: () -> List<UserPlace>,
    private val save: suspend (List<UserPlace>) -> Boolean,
    private val elapsed: () -> Long,
    private val newId: () -> String = UserPlace::newId,
    private val log: (String) -> Unit = {},
) {
    private class Draft(val lat: Double, val lon: Double, val at: Long)
    @Volatile private var draft: Draft? = null

    /** Step 1: takes the position now. Returns the nearby sites the pin may hang under, or the refusal. */
    suspend fun begin(): Result<List<ParentChoice>> {
        draft = null
        if (zoneBlocked()) { log("pin refused zone"); return Result.failure(PinRefused(PinRefusal.ZONE)) }
        val f = fix()
        if (f == null || f.ageMs !in 0..MAX_FIX_AGE_MS || f.accuracyM.isNaN() || f.accuracyM > MAX_FIX_ACCURACY_M || !f.lat.isFinite() || !f.lon.isFinite()) {
            log("pin refused no fresh fix"); return Result.failure(PinRefused(PinRefusal.NO_FRESH_FIX))
        }
        if (zoneBlocked()) { log("pin refused zone"); return Result.failure(PinRefused(PinRefusal.ZONE)) } // a zone that closed while the fix was taken
        draft = Draft(f.lat, f.lon, elapsed() - f.ageMs)
        val sites = nearby(f.lat, f.lon).filter { !UserPlace.isUserId(it.id) }
            .map { it to Geo.distanceM(f.lat, f.lon, it.lat, it.lon) }.sortedWith(compareBy({ it.second - it.first.radiusM }, { it.first.id }))
            .take(MAX_PARENTS).map { ParentChoice(it.first.id, it.first.name, it.first.kind) }
        return Result.success(sites)
    }

    /** Step 2: stores the pin at the position of [begin]. The zone is asked again. The draft is used once. */
    suspend fun create(form: PinForm): PinResult {
        val d = draft ?: return PinResult.Refused(PinRefusal.DRAFT_EXPIRED)
        draft = null
        if (elapsed() - d.at > DRAFT_TTL_MS) return PinResult.Refused(PinRefusal.DRAFT_EXPIRED)
        if (zoneBlocked()) { log("pin refused zone"); return PinResult.Refused(PinRefusal.ZONE) }
        val pin = UserPlace(newId(), UserPlace.cleanName(form.name), form.kind, d.lat, d.lon, form.radiusM, form.parentId?.takeIf { it.isNotBlank() }, UserPlace.cleanNote(form.note))
        val now = load()
        if (!pin.isValid || now.size >= com.rokid.context.memory.place.UserPinStore.MAX_PINS) return PinResult.Refused(PinRefusal.INVALID)
        if (!save(now + pin)) return PinResult.Refused(PinRefusal.NOT_STORED)
        log("pin created count=${now.size + 1}")
        return PinResult.Saved(pin)
    }

    /** Drops an unused draft (the dialog was closed). */
    fun cancel() { draft = null }

    /** Edits a pin's words, kind, radius and parent. Its position stays: moving a pin means making a new one. */
    suspend fun edit(id: String, form: PinForm): PinResult {
        val now = load()
        val old = now.firstOrNull { it.id == id } ?: return PinResult.Refused(PinRefusal.INVALID)
        val pin = old.copy(name = UserPlace.cleanName(form.name), kind = form.kind, radiusM = form.radiusM, parentId = form.parentId?.takeIf { it.isNotBlank() }, note = UserPlace.cleanNote(form.note))
        if (!pin.isValid) return PinResult.Refused(PinRefusal.INVALID)
        if (!save(now.map { if (it.id == id) pin else it })) return PinResult.Refused(PinRefusal.NOT_STORED)
        log("pin edited")
        return PinResult.Saved(pin)
    }

    suspend fun delete(id: String): Boolean {
        val now = load()
        if (now.none { it.id == id }) return false
        val ok = save(now.filter { it.id != id })
        if (ok) log("pin deleted count=${now.size - 1}")
        return ok
    }

    fun pins(): List<UserPlace> = load()

    class PinRefused(val why: PinRefusal) : Exception("pin refused")

    companion object {
        const val MAX_FIX_AGE_MS = 60_000L
        const val MAX_FIX_ACCURACY_M = 50f
        const val DRAFT_TTL_MS = 3 * 60_000L
        const val MAX_PARENTS = 12
        /** The kinds the pin form offers (the pack's own list; a person is not one). */
        val KINDS: List<String> = listOf(PlaceKinds.MONUMENT, PlaceKinds.TEMPLE, PlaceKinds.STUPA, PlaceKinds.FORT, PlaceKinds.PALACE, PlaceKinds.ARCHAEOLOGICAL_SITE, PlaceKinds.MEMORIAL,
            PlaceKinds.CHURCH, PlaceKinds.MOSQUE, PlaceKinds.HERITAGE_BUILDING, PlaceKinds.HISTORIC_SITE, PlaceKinds.MUSEUM, PlaceKinds.TOMB, PlaceKinds.VIEWPOINT, PlaceKinds.ATTRACTION)
    }
}
