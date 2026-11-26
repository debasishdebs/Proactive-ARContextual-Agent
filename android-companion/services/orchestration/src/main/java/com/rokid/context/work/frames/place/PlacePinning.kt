package com.rokid.context.work.frames.place

import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.model.zone.ZoneEvaluator
import com.rokid.context.memory.place.Geo
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.UserPlace
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
    /** Slice 1.4.2: the Moment chosen in the form was forgotten while the dialog was open. */
    MOMENT_GONE,
}

sealed interface PinResult {
    data class Saved(val place: UserPlace) : PinResult
    data class Refused(val why: PinRefusal) : PinResult
}

/** A pack place the new pin can hang under (a site). */
data class ParentChoice(val id: String, val name: String, val kind: String)

/** What the user typed. */
data class PinForm(val name: String, val kind: String, val radiusM: Int = UserPlace.DEFAULT_RADIUS_M, val parentId: String? = null, val note: String = "", /** Slice 1.4.2: the reference Moment chosen in the form (null = none). Re-checked against the live Moments at save. */ val momentId: String? = null)

/**
 * User-pinned places (Place Guide v2). "Pin a place here" takes the current position ONCE, from a fresh fix only ([fix] returns null for a stale or coarse one), and only when no pause zone is
 * active, approached or unreadable ([zoneBlocked], checked at the start AND again when the pin is saved), AND the fix's own coordinates are judged against every enabled zone ([zones], the pure
 * [ZoneEvaluator.blocksPoint] geometry: box + buffer + approach margin, accuracy counted against the user, unreadable zones refuse) at draft time and again at save: the controller's earlier OUTSIDE
 * reading is never trusted for a position it did not see. The position lives in this object's memory for at most [DRAFT_TTL_MS] between the
 * two steps (a timer clears it at that time, [cancel] and [expire] clear it at once); it is never logged and never sent anywhere: the pin's own coordinates are stored in the user's pin list, like a pause zone's box.
 *
 * Every read-modify-persist of the pin list (create, edit, delete) runs under ONE process-wide lock, so overlapping UI jobs cannot overwrite each other.
 *
 * Editing or deleting a pin needs no fix. The name and the note are the user's own words and stay on the phone ([log] gets literal text and counts only).
 */
class PlacePinning(
    private val fix: suspend () -> PlaceFix?,
    private val zoneBlocked: suspend () -> Boolean,
    /** The stored zones for the geometric check of the pin's own fix; null = unreadable (refuses). */
    private val zones: suspend () -> List<PauseZone>?,
    private val nearby: suspend (lat: Double, lon: Double) -> List<PlaceEntity>,
    private val load: () -> List<UserPlace>,
    private val save: suspend (List<UserPlace>) -> Boolean,
    private val elapsed: () -> Long,
    private val newId: () -> String = UserPlace::newId,
    private val log: (String) -> Unit = {},
    /** Slice 1.4.2: whether a Moment id still has its photo (not forgotten). A link to a gone Moment is never stored. Tests default to "alive". */
    private val momentAlive: suspend (String) -> Boolean = { true },
    /** Slice 1.4.2: the wall clock for a new pin's `pinnedAt`. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Runs [run] after [delayMs]; returns the cancel. Replaced by a fake in tests. */
    private val schedule: (delayMs: Long, run: () -> Unit) -> () -> Unit = DefaultTimer::schedule,
    /** F4: told the id of a pin right after it was saved (the pin dialog's create only). */
    private val onCreated: (String) -> Unit = {},
) {
    private class Draft(val lat: Double, val lon: Double, val accuracyM: Float, val at: Long) { @Volatile var cancelTimer: () -> Unit = {} }
    @Volatile private var draft: Draft? = null

    private fun drop() { draft?.cancelTimer?.invoke(); draft = null }

    private suspend fun zoneRefuses(lat: Double, lon: Double, acc: Float): Boolean =
        zoneBlocked() || ZoneEvaluator.blocksPoint(try { zones() } catch (e: Exception) { null }, lat, lon, acc)

    /** Step 1: takes the position now. Returns the nearby sites the pin may hang under, or the refusal. */
    suspend fun begin(): Result<List<ParentChoice>> {
        drop()
        if (zoneBlocked()) { log("pin refused zone"); return Result.failure(PinRefused(PinRefusal.ZONE)) }
        val f = fix()
        if (f == null || f.ageMs !in 0..MAX_FIX_AGE_MS || f.accuracyM.isNaN() || f.accuracyM > MAX_FIX_ACCURACY_M || !f.lat.isFinite() || !f.lon.isFinite()) {
            log("pin refused no fresh fix"); return Result.failure(PinRefused(PinRefusal.NO_FRESH_FIX))
        }
        if (zoneRefuses(f.lat, f.lon, f.accuracyM)) { log("pin refused zone"); return Result.failure(PinRefused(PinRefusal.ZONE)) } // the fix itself, against every zone, and the controller once more
        val d = Draft(f.lat, f.lon, f.accuracyM, elapsed() - f.ageMs)
        draft = d
        d.cancelTimer = schedule(DRAFT_TTL_MS) { if (draft === d) draft = null }
        val sites = nearby(f.lat, f.lon).filter { !UserPlace.isUserId(it.id) }
            .map { it to Geo.distanceM(f.lat, f.lon, it.lat, it.lon) }.sortedWith(compareBy({ it.second - it.first.radiusM }, { it.first.id }))
            .take(MAX_PARENTS).map { ParentChoice(it.first.id, it.first.name, it.first.kind) }
        return Result.success(sites)
    }

    /** Step 2: stores the pin at the position of [begin]. The zones are judged again, with the fix's coordinates. The draft is used once. */
    suspend fun create(form: PinForm): PinResult {
        val d = draft ?: return PinResult.Refused(PinRefusal.DRAFT_EXPIRED)
        // slice 1.4.2: a Moment forgotten while the dialog was open is refused, never silently stored as "no link"; the held position is kept so the user can pick another or save without one
        val moment = liveMoment(form.momentId)
        if (form.momentId != null && moment == null) return PinResult.Refused(PinRefusal.MOMENT_GONE)
        drop()
        if (elapsed() - d.at > DRAFT_TTL_MS) return PinResult.Refused(PinRefusal.DRAFT_EXPIRED)
        if (zoneRefuses(d.lat, d.lon, d.accuracyM)) { log("pin refused zone"); return PinResult.Refused(PinRefusal.ZONE) }
        val pin = UserPlace(newId(), UserPlace.cleanName(form.name), form.kind, d.lat, d.lon, form.radiusM, form.parentId?.takeIf { it.isNotBlank() }, UserPlace.cleanNote(form.note), moment, wallClock())
        return store(pin)
    }

    /**
     * WP8 (Developer test place): a pin at TYPED coordinates, with no fix. The same zone refusal (the point itself, accuracy 0, and the controller's state) and the same validation as [create]. Never used by the normal pin dialog.
     */
    suspend fun createAt(lat: Double, lon: Double, form: PinForm): PinResult {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return PinResult.Refused(PinRefusal.INVALID)
        if (zoneRefuses(lat, lon, 0f)) { log("pin refused zone"); return PinResult.Refused(PinRefusal.ZONE) }
        val pin = UserPlace(newId(), UserPlace.cleanName(form.name), form.kind, lat, lon, form.radiusM, null, UserPlace.cleanNote(form.note), null, wallClock())
        return store(pin)
    }

    /** How a pin of the Developer import ended ([importPin]). */
    enum class ImportOutcome { SAVED, SAVED_LINK_DROPPED, EXISTS, ZONE, INVALID, NOT_STORED }

    /**
     * Developer IMPORT: stores a pin exactly as exported (original `user:<uuid>` id, position, radius, parent, note, `pinnedAt`) with NO fix, under the same lock, the same validation ([UserPlace.isValid], the pin list cap) and the
     * same zone refusal as [createAt] (the point itself with accuracy 0 and the controller's state). The reference Moment is kept only when it is still alive ([momentAlive]); otherwise the pin is stored WITHOUT the link
     * ([ImportOutcome.SAVED_LINK_DROPPED]: a pin is never a child of a Moment). An existing id is [ImportOutcome.EXISTS] (nothing changes). Never emits [onCreated] (an import is not a new-pin event).
     */
    suspend fun importPin(pin: UserPlace): ImportOutcome {
        if (!pin.isValid) return ImportOutcome.INVALID
        if (zoneRefuses(pin.lat, pin.lon, 0f)) { log("pin refused zone"); return ImportOutcome.ZONE }
        val live = liveMoment(pin.momentId)
        val stored = if (live == pin.momentId) pin else pin.copy(momentId = null)
        return writeLock.withLock {
            val now = load()
            if (now.any { it.id == pin.id }) return@withLock ImportOutcome.EXISTS
            if (now.size >= com.rokid.context.memory.place.UserPinStore.MAX_PINS) return@withLock ImportOutcome.INVALID
            if (!save(now + stored)) return@withLock ImportOutcome.NOT_STORED
            log("pin imported count=${now.size + 1}")
            if (stored.momentId != null) log("pin moment linked")
            if (stored.momentId == pin.momentId) ImportOutcome.SAVED else ImportOutcome.SAVED_LINK_DROPPED
        }
    }

    private suspend fun store(pin: UserPlace): PinResult {
        return writeLock.withLock {
            val now = load()
            if (!pin.isValid || now.size >= com.rokid.context.memory.place.UserPinStore.MAX_PINS) return@withLock PinResult.Refused(PinRefusal.INVALID)
            if (!save(now + pin)) return@withLock PinResult.Refused(PinRefusal.NOT_STORED)
            log("pin created count=${now.size + 1}")
            if (pin.momentId != null) log("pin moment linked")
            try { onCreated(pin.id) } catch (_: Exception) { }
            PinResult.Saved(pin)
        }
    }

    /** Drops an unused draft (the dialog was closed). */
    fun cancel() { drop() }

    /** Clears the held position now (lifecycle disposal). Same as [cancel]. */
    fun expire() { drop() }

    /** True while a position is held (for tests and the UI). */
    val hasDraft: Boolean get() = draft != null

    /** Edits a pin's words, kind, radius and parent. Its position stays: moving a pin means making a new one. */
    suspend fun edit(id: String, form: PinForm): PinResult = writeLock.withLock {
        val now = load()
        val old = now.firstOrNull { it.id == id } ?: return@withLock PinResult.Refused(PinRefusal.INVALID)
        val pin = old.copy(name = UserPlace.cleanName(form.name), kind = form.kind, radiusM = form.radiusM, parentId = form.parentId?.takeIf { it.isNotBlank() }, note = UserPlace.cleanNote(form.note), momentId = if (form.momentId == null) old.momentId else liveMoment(form.momentId) ?: old.momentId) // an edit never drops the link (Remove link is setMoment); a link to a gone Moment is not taken over
        if (!pin.isValid) return@withLock PinResult.Refused(PinRefusal.INVALID)
        if (!save(now.map { if (it.id == id) pin else it })) return@withLock PinResult.Refused(PinRefusal.NOT_STORED)
        log("pin edited")
        if (pin.momentId != old.momentId) { ShadowCaches.clearAll(); if (pin.momentId != null) log("pin moment linked") else log("pin moment unlinked by user") }
        PinResult.Saved(pin)
    }

    private suspend fun liveMoment(id: String?): String? = id?.takeIf { com.rokid.context.memory.moment.MomentId.parse(it) != null && try { momentAlive(it) } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { false } }

    /** Slice 1.4.2: attaches [momentId] to the pin (null = "Remove link"). Under the pin lock; false = unknown pin, a Moment that is gone, or not stored. Logs the event only (no ids). */
    suspend fun setMoment(id: String, momentId: String?): Boolean = writeLock.withLock {
        val now = load()
        val old = now.firstOrNull { it.id == id } ?: return@withLock false
        val m = if (momentId == null) null else liveMoment(momentId) ?: return@withLock false
        if (old.momentId == m) return@withLock true
        if (!save(now.map { if (it.id == id) it.copy(momentId = m) else it })) return@withLock false
        ShadowCaches.clearAll() // a pin link change: the shadow's in-memory reference vectors go at once
        if (m != null) log("pin moment linked") else log("pin moment unlinked by user")
        true
    }

    suspend fun delete(id: String): Boolean = writeLock.withLock {
        val now = load()
        if (now.none { it.id == id }) return@withLock false
        val ok = save(now.filter { it.id != id })
        if (ok) log("pin deleted count=${now.size - 1}")
        ok
    }

    fun pins(): List<UserPlace> = load()

    class PinRefused(val why: PinRefusal) : Exception("pin refused")

    /** The default draft timer: one daemon thread, started on first use, only holds a task while a draft exists. */
    internal object DefaultTimer {
        private val ex: ScheduledExecutorService by lazy {
            java.util.concurrent.ScheduledThreadPoolExecutor(1) { r -> Thread(r, "pin-draft").also { it.isDaemon = true } }.also { it.removeOnCancelPolicy = true; it.setKeepAliveTime(5, TimeUnit.SECONDS); it.allowCoreThreadTimeOut(true) }
        }
        fun schedule(delayMs: Long, run: () -> Unit): () -> Unit { val f: ScheduledFuture<*> = ex.schedule(run, delayMs, TimeUnit.MILLISECONDS); return { f.cancel(false) } }
    }

    companion object {
        /** The ONE lock of every read-modify-persist of the pin list in this process (several instances may exist: the settings screen builds a control per visit). */
        private val writeLock = Mutex()
        /** Runs [block] under that same lock: the Moment link (set, cleared by the user or by Forget) is a read-modify-persist of the same list and must not interleave with [edit] or [create]. */
        suspend fun <T> underPinLock(block: suspend () -> T): T = writeLock.withLock { block() }
        const val MAX_FIX_AGE_MS = 60_000L
        const val MAX_FIX_ACCURACY_M = 50f
        const val DRAFT_TTL_MS = 3 * 60_000L
        const val MAX_PARENTS = 12
        /** The kinds the pin form offers (the pack's own list; a person is not one). */
        val KINDS: List<String> = listOf(PlaceKinds.MONUMENT, PlaceKinds.TEMPLE, PlaceKinds.STUPA, PlaceKinds.FORT, PlaceKinds.PALACE, PlaceKinds.ARCHAEOLOGICAL_SITE, PlaceKinds.MEMORIAL,
            PlaceKinds.CHURCH, PlaceKinds.MOSQUE, PlaceKinds.HERITAGE_BUILDING, PlaceKinds.HISTORIC_SITE, PlaceKinds.MUSEUM, PlaceKinds.TOMB, PlaceKinds.VIEWPOINT, PlaceKinds.ATTRACTION)
    }
}
