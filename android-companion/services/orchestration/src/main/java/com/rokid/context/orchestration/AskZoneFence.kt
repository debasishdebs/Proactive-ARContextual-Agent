package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.place.ZoneFence
import com.rokid.context.memory.place.ZoneTicket

/** Thrown when an Ask is stopped by the pause-zone fence (a zone is approached, entered, recorded or unreadable): nothing was sent, nothing is retried. Not a cancellation: callers map it to [AskActions.ZONE_PAUSED]. */
class AskZoneBlockedException : RuntimeException("ask paused by a pause zone")

/** What [AnswerService] asks before it dispatches a model call with stored text, while it waits for one, and before it delivers the answer to the glasses. */
fun interface AskZoneGuard {
    /** True = a zone is active (or the location is uncertain: fail closed). Synchronous, cheap, never throws. */
    fun blocked(): Boolean

    /** Throws [AskZoneBlockedException] while [blocked]. Call as the last step before a dispatch. */
    fun check() { if (blocked()) throw AskZoneBlockedException() }

    /** Reads what only a suspending read can tell (the stored zone pause records) before the first check of an Ask. Never throws. */
    suspend fun prepare() {}

    /** The ticket of one Ask (taken BEFORE its first check): closed by the zone fence the moment a zone starts, so a provider dispatch fenced by it is refused or cancelled synchronously. Null = no fence (the poll fallback). */
    fun ticket(): ZoneTicket? = null

    companion object {
        val None = AskZoneGuard { false }
    }
}

/**
 * Z1 (proxy ruling 2026-11-17): the pause-zone fence of Ask. Stored text can disclose private context just as a fresh caption can, so an Ask model call, and the delivery of its answer to the glasses, are refused while
 * the fence is held (the zone controller reports into it through [protect]) or its second opinion says a zone is approached/enforcing/recorded. Local phone browsing of existing data is not touched. Fail closed: any error asking the fence is "blocked".
 * Behind the Developer-visible switch `askZoneFence` (default ON); OFF = today's behaviour.
 *
 * **Startup (Codex z1 r1 P1-1).** It does not depend on the hub or the controller being built. Until the controller has judged the zones once in this process ([protect] with `judged`), the fence is
 * CLOSED whenever the persisted facts say a zone may be in force: the controller's persisted protect flag, any stored zone not disabled (or a zone list that cannot be read), or an active stored `zone:` pause
 * record ([prepare]). With none of those there is nothing to protect and Ask is open.
 */
object AskZoneFence : AskZoneGuard {
    /** Same backup-excluded Developer preference file as `DeveloperFlags` (a phone test pins the name). */
    const val PREFS = "pc_dev"
    const val KEY = "ask_zone_fence"
    /** The zone controller's persisted state file ([com.rokid.context.phone.zone.ZoneStateStore], backup-excluded; a phone test pins both names) and its key: the last protection the controller reported. */
    const val ZONE_STATE_PREFS = "pause_zone_state"
    const val PROTECT_KEY = "protect"
    /** The user's zone list (a JSON array; a phone test pins the name). */
    const val ZONES_PREFS = "pause_zones"
    const val ZONES_KEY = "zones"
    private const val TAG = "AskZoneFence"

    /** Ask's own fence (a Forget or the Place Guide switch must not look like a zone): held by [protect], and by the startup rule until the controller judged. */
    @Volatile var fence: ZoneFence = ZoneFence()
    /** The zone controller's flags, wired by the hub ([approach || enforcing]). Null before the hub is built: [fence] and the startup rule answer. */
    @Volatile var extraBlocked: (() -> Boolean)? = null
    /** The switch. Installed by [install]; default ON. */
    @Volatile var enabledProvider: () -> Boolean = { true }
    @Volatile private var app: Context? = null
    /** True once the controller judged the zones in this process. */
    @Volatile private var judged = false
    /** A stored zone record was found by [prepare] before the controller judged. */
    @Volatile private var recordsBlock = false

    fun install(context: Context) {
        val a = context.applicationContext ?: return
        if (app === a) return
        app = a
        enabledProvider = { a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true) }
        if (startupBlocked()) fence.hold()
    }

    fun isEnabled(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true) }.getOrDefault(true)
    fun setEnabled(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply() }

    /**
     * The zone controller's word, synchronously, from any of its paths ([active] = a zone is approached, entered, recorded or unreadable; [judged] = it has judged the zones since the process started).
     * Active closes the fence and cancels every Ask dispatch in flight at once; clear and judged opens it again.
     */
    fun protect(active: Boolean, judged: Boolean) {
        if (judged) { this.judged = true; recordsBlock = false }
        if (active) { if (!fence.held) fence.hold() } else if (this.judged) fence.release()
    }

    /** True while the persisted facts say a zone may be in force and the controller has not judged yet. Cheap (preference reads), synchronous, fail closed. */
    private fun startupBlocked(): Boolean {
        if (judged) return false
        val a = app ?: return false
        return try {
            recordsBlock ||
                a.getSharedPreferences(ZONE_STATE_PREFS, Context.MODE_PRIVATE).getBoolean(PROTECT_KEY, false) ||
                zonesMayBeInForce(a.getSharedPreferences(ZONES_PREFS, Context.MODE_PRIVATE).getString(ZONES_KEY, null))
        } catch (e: Exception) { true }
    }

    /** Any stored zone that is not switched off, or a list that cannot be parsed. */
    internal fun zonesMayBeInForce(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).any { arr.optJSONObject(it)?.optBoolean("enabled", true) != false }
        } catch (e: Exception) { true }
    }

    override suspend fun prepare() {
        if (judged || recordsBlock) return
        val a = app ?: return
        try {
            val now = System.currentTimeMillis()
            val live = com.rokid.context.memory.MemoryGraph.get(a).db.pauseDao().records().any { com.rokid.context.core.model.zone.ZonePause.isZoneRecord(it.pauseId) && (it.untilMs == null || now < it.untilMs!!) }
            if (live) { recordsBlock = true; fence.hold() }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            PcLog.i(TAG, "zone records unreadable (${e.javaClass.simpleName}); fail closed")
            recordsBlock = true; fence.hold()
        }
    }

    override fun ticket(): ZoneTicket? {
        val on = try { enabledProvider() } catch (_: Exception) { true }
        if (!on) return null
        if (startupBlocked()) fence.hold()
        return fence.ticket()
    }

    override fun blocked(): Boolean {
        val on = try { enabledProvider() } catch (_: Exception) { true }
        if (!on) return false
        return try { fence.blocked() || (extraBlocked?.invoke() ?: false) || startupBlocked() } catch (e: Exception) { PcLog.i(TAG, "zone state unreadable (${e.javaClass.simpleName}); fail closed"); true }
    }

    /** Tests: the process-start state. */
    internal fun resetForTests() {
        fence = ZoneFence(); extraBlocked = null; enabledProvider = { true }; app = null; judged = false; recordsBlock = false
    }
}

/**
 * The liveness of an Ask's model call, wrapped (Codex z1 r1 P1-2): the router calls [holdUploadSlot] inside the call permit, immediately before the provider write, and again for every retry. A zone that started
 * while the call waited for its permit (or for anything before it) ends the dispatch HERE, as a zone fence closing (a cancellation the Ask maps to [AskZoneBlockedException]). Photo-bearing and text-only Asks alike.
 */
internal class AskZoneLiveness(
    private val inner: com.rokid.context.model.PhotoLiveness, private val guard: AskZoneGuard, private val ticket: ZoneTicket?,
) : com.rokid.context.model.PhotoLiveness by inner {
    override suspend fun <T> holdUploadSlot(block: suspend () -> T): T = inner.holdUploadSlot {
        if (ticket?.isOpen() == false || guard.blocked()) throw com.rokid.context.memory.place.ZoneFenced()
        block()
    }
}
