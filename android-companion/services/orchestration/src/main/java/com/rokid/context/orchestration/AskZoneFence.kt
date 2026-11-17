package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.place.ZoneFence

/** Thrown when an Ask is stopped by the pause-zone fence (a zone is approached, entered, recorded or unreadable): nothing was sent, nothing is retried. Not a cancellation: callers map it to [AskActions.ZONE_PAUSED]. */
class AskZoneBlockedException : RuntimeException("ask paused by a pause zone")

/** What [AnswerService] asks before it dispatches a model call with stored text, while it waits for one, and before it delivers the answer to the glasses. */
fun interface AskZoneGuard {
    /** True = a zone is active (or the location is uncertain: fail closed). Synchronous, cheap, never throws. */
    fun blocked(): Boolean

    /** Throws [AskZoneBlockedException] while [blocked]. Call as the last step before a dispatch. */
    fun check() { if (blocked()) throw AskZoneBlockedException() }

    companion object { val None = AskZoneGuard { false } }
}

/**
 * Z1 (proxy ruling 2026-11-17): the pause-zone fence of Ask. Stored text can disclose private context just as a fresh caption can, so an Ask model call, and the delivery of its answer to the glasses, are refused while
 * [ZoneFence] is held (the zone controller) or its second opinion says a zone is approached/enforcing/recorded. Local phone browsing of existing data is not touched. Fail closed: any error asking the fence is "blocked".
 * Behind the Developer-visible switch `askZoneFence` (default ON); OFF = today's behaviour.
 */
object AskZoneFence : AskZoneGuard {
    /** Same backup-excluded Developer preference file as `DeveloperFlags` (a phone test pins the name). */
    const val PREFS = "pc_dev"
    const val KEY = "ask_zone_fence"
    private const val TAG = "AskZoneFence"

    /** The Place Guide fence, shared (one fence for every zone-guarded side effect). */
    @Volatile var fence: ZoneFence = com.rokid.context.work.frames.place.PlaceGraph.fence
    /** The zone controller's flags and the pause records, wired by the hub ([approach || enforcing || a zone record exists]). Null before the hub is built: only [fence] answers. */
    @Volatile var extraBlocked: (() -> Boolean)? = null
    /** The switch. Installed by [install]; default ON. */
    @Volatile var enabledProvider: () -> Boolean = { true }
    @Volatile private var app: Context? = null

    fun install(context: Context) {
        val a = context.applicationContext ?: return
        if (app === a) return
        app = a
        enabledProvider = { a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true) }
    }

    fun isEnabled(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true) }.getOrDefault(true)
    fun setEnabled(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply() }

    override fun blocked(): Boolean {
        val on = try { enabledProvider() } catch (_: Exception) { true }
        if (!on) return false
        return try { fence.blocked() || (extraBlocked?.invoke() ?: false) } catch (e: Exception) { PcLog.i(TAG, "zone state unreadable (${e.javaClass.simpleName}); fail closed"); true }
    }
}
