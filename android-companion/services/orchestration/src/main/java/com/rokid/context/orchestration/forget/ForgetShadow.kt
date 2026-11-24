package com.rokid.context.orchestration.forget

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.Fate
import com.rokid.context.memory.forget.lifecycle.ForgetPlan
import com.rokid.context.memory.forget.lifecycle.ForgetPlanner
import com.rokid.context.memory.forget.lifecycle.Policy
import com.rokid.context.memory.forget.lifecycle.Ref

/**
 * Developer-only switches, kept in the Developer mode preference file (`pc_dev`): per-device, off after a restore (the file is excluded from backup and device transfer, `backup_rules.xml` and
 * `data_extraction_rules.xml`) and never part of a settings export. Every switch has no UI; the place-fix switches are ON by default, the rest OFF.
 */
object DeveloperFlags {
    /** The same file as the phone's `DeveloperModeStore` (a test in the phone module pins the two names together). */
    const val PREFS = "pc_dev"
    const val FORGET_SHADOW = "forget_shadow"
    /** Forget lifecycle M4: the destructive switch-over (the `ForgetExecutor` instead of the legacy cascades). OFF by default; flip it only when the shadow line has stayed clean on real data. */
    const val FORGET_V2 = com.rokid.context.memory.forget.lifecycle.ForgetV2.KEY

    /** Multilingual Ask (design 2026-11-19, options A + B): the built-in Ask also runs the query condenser (and, when it is unavailable, the bundled lexicon). OFF by default; no UI. */
    const val MULTILINGUAL_ASK = "multilingual_ask"

    /** P0 retention fix: "Pause clean-up". ON = the nightly [RetentionWorker] returns at once and expires nothing (safety valve for outings). ON BY DEFAULT in the retention-fix candidate (nothing expires until the user turns it OFF); Developer-only row in Diagnostics. */
    const val PAUSE_RETENTION = "pause_retention"

    /** Place Guide shadow scoring (1.4.4 slice A): scores the candidates against the dwell photo on-device and adds content-free fields to the `place attempt` line. Never changes a card or a call. OFF by default; no UI. */
    const val PLACE_SHADOW_SCORE = "placeShadowScore"

    fun placeShadowScore(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PLACE_SHADOW_SCORE, false) }.getOrDefault(false)
    fun setPlaceShadowScore(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PLACE_SHADOW_SCORE, on).apply(); if (!on) com.rokid.context.work.frames.place.ShadowCaches.clearAll() } // OFF: the in-memory shadow vectors go at once

    /** Place fixes (spec 2026-11-24 section 2): each is ON by default and has no UI; OFF restores the older behaviour. */
    const val PLACE_PIN_FIRST = "placePinFirst"
    const val PLACE_FIX_REUSE = "placeFixReuse"
    const val PLACE_DEDUPE_V2 = "placeDedupeV2"
    const val PLACE_RETRY_ON_PIN = "placeRetryOnPin"
    const val PLACE_TEXT_MATCH = "placeTextMatch"
    private fun flag(context: Context, key: String): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, true) }.getOrDefault(true)
    private fun setFlag(context: Context, key: String, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, on).apply() }
    fun placePinFirst(context: Context) = flag(context, PLACE_PIN_FIRST)
    fun setPlacePinFirst(context: Context, on: Boolean) = setFlag(context, PLACE_PIN_FIRST, on)
    fun placeFixReuse(context: Context) = flag(context, PLACE_FIX_REUSE)
    fun setPlaceFixReuse(context: Context, on: Boolean) = setFlag(context, PLACE_FIX_REUSE, on)
    fun placeDedupeV2(context: Context) = flag(context, PLACE_DEDUPE_V2)
    fun setPlaceDedupeV2(context: Context, on: Boolean) = setFlag(context, PLACE_DEDUPE_V2, on)
    fun placeRetryOnPin(context: Context) = flag(context, PLACE_RETRY_ON_PIN)
    fun setPlaceRetryOnPin(context: Context, on: Boolean) = setFlag(context, PLACE_RETRY_ON_PIN, on)
    fun placeTextMatch(context: Context) = flag(context, PLACE_TEXT_MATCH)
    fun setPlaceTextMatch(context: Context, on: Boolean) = setFlag(context, PLACE_TEXT_MATCH, on)

    /** Place card history on the phone (spec 2026-11-24 F6): a phone-only list in `places.db`. ON by default; OFF stops new rows and hides nothing already stored (Forget still deletes them). */
    const val PLACE_CARD_HISTORY = "placeCardHistory"
    fun placeCardHistory(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PLACE_CARD_HISTORY, true) }.getOrDefault(true)
    fun setPlaceCardHistory(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PLACE_CARD_HISTORY, on).apply() }

    fun pauseRetention(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PAUSE_RETENTION, true) }.getOrDefault(true)
    fun setPauseRetention(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PAUSE_RETENTION, on).apply() }
    fun multilingualAsk(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MULTILINGUAL_ASK, false) }.getOrDefault(false)
    fun setMultilingualAsk(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(MULTILINGUAL_ASK, on).apply() }
    fun forgetShadow(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(FORGET_SHADOW, false) }.getOrDefault(false)
    fun forgetV2(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(FORGET_V2, false) }.getOrDefault(false)
    fun setForgetV2(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(FORGET_V2, on).apply() }
    fun setForgetShadow(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(FORGET_SHADOW, on).apply() }
}

/**
 * The runtime SHADOW hook of the Forget lifecycle (M2). When [DeveloperFlags.forgetShadow] is ON, a single-item Forget (fact or photo) first asks the [ForgetPlanner] what it would do, then runs the REAL Forget unchanged,
 * and after the commit compares the planner's sets with what the real code reported and logs ONE milestone line: counts and divergence booleans, never an id or a text. It changes nothing and deletes nothing: the plan only reads,
 * it runs inside the delete gate before the real operation, and every failure is swallowed (the Forget is never affected). OFF by default; the destructive switch-over (M4) stays behind its own flag and is only considered
 * when this line has stayed clean on real data. Range, scoped and retention Forgets are covered by the shadow tests, not by this hook.
 */
internal object ForgetShadow {
    private const val TAG = "ForgetShadow"

    /** The plan taken before the Forget, or null when the flag is off, the roots are empty or planning failed. Never throws. */
    suspend fun before(context: Context, root: Ref): ForgetPlan? {
        if (!DeveloperFlags.forgetShadow(context)) return null
        return try {
            ForgetPlanner(MemoryGraph.get(context).db).plan(setOf(root), Policy.User)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { PcLog.w(TAG, "shadow plan failed"); null }
    }

    /** Compares the plan with the real [ForgetService.Result] and logs the line. Never throws. */
    fun after(plan: ForgetPlan?, kind: ForgetActions.LoggedScope, r: ForgetService.Result) {
        if (plan == null) return
        try {
            val erased = plan.refs(Family.MEMORY, Fate.ERASE); val rebuilt = plan.refs(Family.MEMORY, Fate.REBUILD)
            val answers = plan.refs(Family.ANSWER); val nudges = plan.refs(Family.NUDGE)
            var diverged = false
            if (erased != r.forgotten) { diverged = true; PcLog.milestone(TAG, "shadow forget diverged scope=${kind.name} memories planned=${erased.size} real=${r.forgotten.size}") }
            if (rebuilt != r.invalidated) { diverged = true; PcLog.milestone(TAG, "shadow forget diverged scope=${kind.name} invalidated planned=${rebuilt.size} real=${r.invalidated.size}") }
            if (answers != r.redactedAskIds.toSet()) { diverged = true; PcLog.milestone(TAG, "shadow forget diverged scope=${kind.name} answers planned=${answers.size} real=${r.redactedAskIds.size}") }
            if (nudges.size != r.redactedNudges) { diverged = true; PcLog.milestone(TAG, "shadow forget diverged scope=${kind.name} nudges planned=${nudges.size}") }
            if (!diverged) PcLog.milestone(TAG, "shadow forget matched scope=${kind.name} memories=${erased.size} invalidated=${rebuilt.size} answers=${answers.size} nudges=${nudges.size} nodes=${plan.size}")
        } catch (e: Exception) { PcLog.w(TAG, "shadow compare failed") }
    }
}
