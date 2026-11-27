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
 * `data_extraction_rules.xml`) and never part of a settings export. Every switch has no UI; the place-fix switches are ON by default (except the place card history: OFF), the rest OFF.
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
    /** Retention P2 (1.8): photo expiry skips the photos a place card, a mark or a pin points to. OFF BY DEFAULT (also OFF when the prefs cannot be read): OFF = the shipped retention, unchanged. The read-only retention preview in Settings does not depend on it. */
    const val RETENTION_P2 = "retentionP2"

    /** Place Guide shadow scoring (1.4.4 slice A): scores the candidates against the dwell photo on-device and adds content-free fields to the `place attempt` line. Never changes a card or a call. OFF by default; no UI. */
    const val PLACE_SHADOW_SCORE = "placeShadowScore"

    fun placeShadowScore(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PLACE_SHADOW_SCORE, false) }.getOrDefault(false)
    fun setPlaceShadowScore(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PLACE_SHADOW_SCORE, on).apply(); if (!on) com.rokid.context.work.frames.place.ShadowCaches.clearAll() } // OFF: the in-memory shadow vectors go at once

    /**
     * Place Guide SigLIP 2 shadow (1.4.4): a SECOND image-text space scored next to Nomic and compared in one counts-only `place shadow2` log line. Needs [PLACE_SHADOW_SCORE] ON as well (it rides on the
     * same hook) and the sideloaded SigLIP files (see SigLipBundle); without them it logs `sig=na`. Never changes a card, a call or anything the user sees. OFF by default (also OFF when the prefs cannot be read); no UI.
     */
    const val PLACE_SHADOW_SIGLIP = "placeShadowSiglip"
    fun placeShadowSiglip(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PLACE_SHADOW_SIGLIP, false) }.getOrDefault(false)
    fun setPlaceShadowSiglip(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PLACE_SHADOW_SIGLIP, on).apply(); if (!on) com.rokid.context.work.frames.place.ShadowCaches.clearAll() }

    /**
     * Pack Moments (1.4.6, design 2026-11-25): the Place Guide shadow matches pack places through the same candidate interface as pin Moments and logs counts only. Needs [PLACE_SHADOW_SCORE] ON as well (it rides on
     * the same hook). Never changes a card, a call or anything the user sees; no network. OFF by default (also OFF when the prefs cannot be read).
     */
    const val PACK_MOMENTS = "packMoments"
    fun packMoments(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PACK_MOMENTS, false) }.getOrDefault(false)
    fun setPackMoments(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PACK_MOMENTS, on).apply(); if (!on) com.rokid.context.work.frames.place.ShadowCaches.clearAll() }

    /**
     * Offline photo captions (V1, owner decision 2026-11-26 round 5): lets the on-device SmolVLM2 captioner (`:services:vision-local`) run. In V1 the only caller is the Developer "Caption a photo" test (nothing stored, nothing sent,
     * no network); V2 will rank captions as text. OFF by default (also OFF when the prefs cannot be read); the model files are a separate catalog download and nothing loads at app start. Turning it OFF releases the model.
     */
    const val OFFLINE_CAPTION = "offlineCaption" // ON by default since 27 Nov (layered confirmation, D13): nothing loads until a caption is asked for and the model files are downloaded
    fun offlineCaption(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(OFFLINE_CAPTION, true) }.getOrDefault(false)
    fun setOfflineCaption(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(OFFLINE_CAPTION, on).apply() }

    /**
     * V2b offline place tier (owner round 6, 26 Nov): with this ON, no accepted place and an offline model path, the photo is captioned on the phone and ranked against the nearby places for ONE "Maybe <place>" card. Needs the
     * user switch "Offline place guess (Maybe)" (Settings > Place Guide, default OFF) AND the `offlineCaption` flag AND the captioner download. OFF by default (also OFF when the prefs cannot be read).
     */
    const val OFFLINE_TIER = "offlineTier" // ON by default since 27 Nov (layered confirmation, D13)
    fun offlineTier(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(OFFLINE_TIER, true) }.getOrDefault(false)
    fun setOfflineTier(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(OFFLINE_TIER, on).apply() }

    /**
     * Developer IMPORT (owner round 7, 26 Nov): `POST /api/import` recreates Moments (photos, captions, marks, location) from a dump. Needs Developer mode and a paired session AND this flag. OFF by default (also OFF when the
     * prefs cannot be read); switch in Settings > Diagnostics. Turning it OFF makes the route answer 403 at once.
     */
    const val DEV_IMPORT = "devImport"
    fun devImport(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DEV_IMPORT, false) }.getOrDefault(false)
    fun setDevImport(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(DEV_IMPORT, on).apply() }

    /**
     * v18 WP12a: the Scene centroid producer (`scene_centroid`, the mean of a Scene's member vectors per embedding space). Runs inside the existing Scene rebuild (the 15-minute cadence worker), derived data only, nothing
     * is shown, sent or prompted. OFF by default (also OFF when the prefs cannot be read); no UI. Turning it OFF stops new rows; existing rows go with their Scene and every Forget as always.
     */
    const val SCENE_CENTROIDS = "sceneCentroids"
    fun sceneCentroids(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SCENE_CENTROIDS, false) }.getOrDefault(false)
    fun setSceneCentroids(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SCENE_CENTROIDS, on).apply() }

    /**
     * v18 WP12b: the Place Guide CENTROID shadow (a captured frame scored against each pin's Scene centroid, one counts-only `place shadow3` log line). Rides on [PLACE_SHADOW_SCORE] (it is called from that shadow) and
     * needs [SCENE_CENTROIDS] rows to exist. No UI effect, no card, no call change. OFF by default (also OFF when the prefs cannot be read); no UI.
     */
    const val PLACE_SHADOW_CENTROID = "placeShadowCentroid"
    fun placeShadowCentroid(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PLACE_SHADOW_CENTROID, false) }.getOrDefault(false)
    fun setPlaceShadowCentroid(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PLACE_SHADOW_CENTROID, on).apply() }

    /**
     * Canvas v2.1 Test run (design 2026-11-24): stored-window and trace sources, the per-node inspector, the E2E record and the bounded live tap. Every Test run is a dry-run (no send, no delivery, no publish, no
     * write to user data; Save only through `CanvasRunSave`). Needs Developer mode as well. OFF by default (also OFF when the prefs cannot be read); no extra UI beyond the Developer row.
     */
    const val CANVAS_TEST_RUN = "canvasTestRun"
    fun canvasTestRun(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CANVAS_TEST_RUN, false) }.getOrDefault(false)
    fun setCanvasTestRun(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CANVAS_TEST_RUN, on).apply() }

    /**
     * Canvas v2.2 model, classifier, router and rule nodes (design 2026-11-24 section 2). OFF by default (also OFF when the prefs cannot be read): the new node types do not appear in the palette and the validator
     * refuses a graph that holds one. Needs Developer mode as well. Offline only is always honoured: no remote model call.
     */
    const val CANVAS_MODEL_NODES = "canvasModelNodes"
    fun canvasModelNodes(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CANVAS_MODEL_NODES, false) }.getOrDefault(false)
    fun setCanvasModelNodes(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CANVAS_MODEL_NODES, on).apply() }

    /** Layered place confirmation (design 2026-11-27 section 5), one Developer switch per layer, all ON by default. [PLACE_L1_PHOTO] is also the kill switch of Layer 1. Layer 3 is [OFFLINE_TIER] + [OFFLINE_CAPTION]. */
    const val PLACE_L1_PHOTO = "placeL1Photo"
    const val PLACE_L2_VISION = "placeL2Vision"
    const val PLACE_MULTI_FRAME = "placeMultiFrame"
    /** B2: fetch (Wi-Fi only) and embed (charging, not hot) the reference photos of seeded places. ON by default; OFF = neither phase starts and a running one stops at its next step. */
    const val PLACE_REF_PHOTOS = "placeRefPhotos"
    fun placeRefPhotos(context: Context) = flag(context, PLACE_REF_PHOTOS)
    fun setPlaceRefPhotos(context: Context, on: Boolean) = setFlag(context, PLACE_REF_PHOTOS, on)
    fun placeL1Photo(context: Context) = flag(context, PLACE_L1_PHOTO)
    fun setPlaceL1Photo(context: Context, on: Boolean) = setFlag(context, PLACE_L1_PHOTO, on)
    fun placeL2Vision(context: Context) = flag(context, PLACE_L2_VISION)
    fun setPlaceL2Vision(context: Context, on: Boolean) = setFlag(context, PLACE_L2_VISION, on)
    fun placeMultiFrame(context: Context) = flag(context, PLACE_MULTI_FRAME)
    fun setPlaceMultiFrame(context: Context, on: Boolean) = setFlag(context, PLACE_MULTI_FRAME, on)

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

    /**
     * Bulk seeding (owner decision 27 Nov): a seed finds its places with a few Wikidata SPARQL box queries plus batched Wikipedia intros instead of Wikipedia geosearch cell by cell; any bulk failure falls back to the per-cell
     * path for the affected cells, and the per-seed cell cap is 30,000 instead of 4,000. ON BY DEFAULT; a prefs read that fails counts as OFF (= the per-cell path, as before). Switch: Settings > Diagnostics > Developer.
     */
    const val BULK_SEED = "bulkSeed"
    fun bulkSeed(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(BULK_SEED, true) }.getOrDefault(false)
    fun setBulkSeed(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(BULK_SEED, on).apply() }

    /** Place card history on the phone (v18): a phone-only list in `context.db` (`place_card_log`). ON BY DEFAULT; a prefs read that fails counts as OFF (no row written, the UI hides the list). Every Forget / Delete all deletes its rows inside its own transaction whatever this says. */
    const val PLACE_CARD_HISTORY = "placeCardHistory"
    fun placeCardHistory(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PLACE_CARD_HISTORY, true) }.getOrDefault(false) // v18: ON by default; a read failure counts as OFF
    fun setPlaceCardHistory(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PLACE_CARD_HISTORY, on).apply() }

    /** Moment location (v18 WP4): save an OPTIONAL location with a new Moment, from a fix the phone already holds. ON BY DEFAULT (owner ruling 2026-11-25); a prefs read that fails counts as OFF. Switching it OFF writes nothing new and keeps the saved rows until the user taps "Delete saved locations". */
    const val MOMENT_LOCATION = "momentLocation"
    fun momentLocation(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MOMENT_LOCATION, true) }.getOrDefault(false)
    fun setMomentLocation(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(MOMENT_LOCATION, on).apply() }

    /** Map zones P1 (design 2026-11-24): the full-height Map zones screen (draw, Pause / Seed sheet, overview). OFF BY DEFAULT (also OFF when the prefs cannot be read): OFF = the shipped Pause zones screen, unchanged. Pause zone semantics are the same either way. */
    const val MAP_ZONES_V2 = "mapZonesV2"
    fun mapZonesV2(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MAP_ZONES_V2, false) }.getOrDefault(false)
    fun setMapZonesV2(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(MAP_ZONES_V2, on).apply() }
    /** Map zones P2 and P3 tools (undo, snap, coordinates, Also seed, Show cells, Moments density). Nested under [MAP_ZONES_V2]: has no effect while that is off. Default OFF, also on a prefs error. */
    const val MAP_ZONES_P23 = "mapZonesP23"
    fun mapZonesP23(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MAP_ZONES_P23, false) }.getOrDefault(false)
    fun setMapZonesP23(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(MAP_ZONES_P23, on).apply() }
    /** "Show my Moments on the map" (P3): default OFF, and a read error means OFF. */
    const val MAP_MOMENTS_DENSITY = "mapMomentsDensity"
    fun mapMomentsDensity(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MAP_MOMENTS_DENSITY, false) }.getOrDefault(false)
    fun setMapMomentsDensity(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(MAP_MOMENTS_DENSITY, on).apply() }

    fun pauseRetention(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PAUSE_RETENTION, true) }.getOrDefault(true)
    fun retentionP2(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(RETENTION_P2, false) }.getOrDefault(false)
    fun setRetentionP2(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(RETENTION_P2, on).apply() }
    fun setPauseRetention(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PAUSE_RETENTION, on).apply() }
    fun multilingualAsk(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MULTILINGUAL_ASK, false) }.getOrDefault(false)
    fun setMultilingualAsk(context: Context, on: Boolean) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(MULTILINGUAL_ASK, on).apply() }
    fun forgetShadow(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(FORGET_SHADOW, false) }.getOrDefault(false)
    /** Forget v2 (multi-select Forget and the fuller Forget rules). ON BY DEFAULT since 26 Nov 2026 (owner 6b); Settings > Privacy has the switch. A prefs read that fails counts as OFF (the earlier behaviour). */
    fun forgetV2(context: Context): Boolean = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(FORGET_V2, true) }.getOrDefault(false)
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
