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
 * `data_extraction_rules.xml`) and never part of a settings export. Every switch is OFF by default and has no UI.
 */
object DeveloperFlags {
    /** The same file as the phone's `DeveloperModeStore` (a test in the phone module pins the two names together). */
    const val PREFS = "pc_dev"
    const val FORGET_SHADOW = "forget_shadow"
    /** Forget lifecycle M4: the destructive switch-over (the `ForgetExecutor` instead of the legacy cascades). OFF by default; flip it only when the shadow line has stayed clean on real data. */
    const val FORGET_V2 = com.rokid.context.memory.forget.lifecycle.ForgetV2.KEY

    /** Multilingual Ask (design 2026-11-19, options A + B): the built-in Ask also runs the query condenser (and, when it is unavailable, the bundled lexicon). OFF by default; no UI. */
    const val MULTILINGUAL_ASK = "multilingual_ask"

    /** Place Guide shadow scoring (1.4.4 slice A): scores the candidates against the dwell photo on-device and adds content-free fields to the `place attempt` line. Never changes a card or a call. OFF by default; no UI. */
    const val PLACE_SHADOW_SCORE = "placeShadowScore"

    fun placeShadowScore(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PLACE_SHADOW_SCORE, false) }.getOrDefault(false)
    fun setPlaceShadowScore(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PLACE_SHADOW_SCORE, on).apply() }

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
