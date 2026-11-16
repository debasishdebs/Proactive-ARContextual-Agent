package com.rokid.context.phone.ui.shell

import com.rokid.context.phone.ui.activity.Period
import java.net.URLEncoder
import java.time.LocalDate

/** spec §2.1 routes. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ACTIVITY = "activity?period={period}"
    const val DAY = "day/{date}?at={at}"
    /** Find a photo (wave 3): `q` is optional; a non-blank one searches once on open. */
    const val PHOTOS = "photos?q={q}"
    /** The composite previewer of ANY item (`ref` is a [com.rokid.context.phone.ui.preview.PreviewRef.raw]) and the common list of a kind (`ids` comma-joined). */
    const val PREVIEW = "preview/{ref}"
    const val LIST = "list/{kind}?ids={ids}&scope={scope}"
    const val SETTINGS = "settings"
    const val SETTINGS_ENDPOINTS = "settings/endpoints"
    const val SETTINGS_DIAGNOSTICS = "settings/diagnostics"
    const val DIAGNOSTICS_QUEUES = "settings/diagnostics/queues"
    const val DIAGNOSTICS_TRACKER = "settings/diagnostics/tracker"
    /** Debug builds with Developer mode only (not in [LIVE_SETTINGS]; reached from Diagnostics). */
    const val DIAGNOSTICS_PHOTO_DIM = "settings/diagnostics/photo-dim"
    const val SETTINGS_GLASSES = "settings/glasses"
    /** Settings > Rokid Glasses > Nearby devices (user decision 2026-10-15). */
    const val SETTINGS_NEARBY = "settings/glasses/nearby"
    const val SETTINGS_PHONE_INPUTS = "settings/phone-inputs"
    const val SETTINGS_DEVICE_CAPABILITIES = "settings/devices/capabilities"
    const val SETTINGS_CAPTURE = "settings/capture"
    /** Settings > Capture > Phone, car, watch and photo context (Wave B). */
    const val SETTINGS_CONTEXT = "settings/capture/context"
    /** v0.9.7 A: Settings > Capture > Signals and classifier thresholds. */
    const val SETTINGS_SIGNALS = "settings/capture/signals"
    const val SETTINGS_ENDPOINT = "settings/endpoints/{id}"
    const val SETTINGS_ROLES = "settings/roles"
    const val SETTINGS_PROMPTS = "settings/prompts"
    const val SETTINGS_PROMPT = "settings/prompts/{id}"
    const val SETTINGS_PROMPT_HISTORY = "settings/prompts/{id}/history"
    const val SETTINGS_RETRIEVAL = "settings/retrieval"
    /** Settings -> AI -> Agent flows (agent flows spec): the list, then the selected flows' view. */
    const val SETTINGS_FLOWS = "settings/flows"
    const val SETTINGS_FLOWS_VIEW = "settings/flows/view?ids={ids}"
    /** Settings -> AI -> On-device models (on-device embeddings spec section 5). */
    const val SETTINGS_LOCAL_MODELS = "settings/local-models"
    /** Settings -> AI -> Experiment canvas (Developer mode only): home, the in-app editor, and a read-only pipeline DAG. */
    const val SETTINGS_CANVAS = "settings/canvas"
    const val SETTINGS_CANVAS_WEB = "settings/canvas/web"
    const val SETTINGS_CANVAS_PIPELINE = "settings/canvas/pipeline?id={id}"
    const val SETTINGS_PERSONA = "settings/persona"
    const val SETTINGS_MEMORY = "settings/memory"
    const val SETTINGS_MEMORY_REVIEW = "settings/memory/review"
    const val DIAGNOSTICS_LOGS = "settings/diagnostics/logs"
    const val SETTINGS_PRIVACY = "settings/privacy"
    /** Settings -> Privacy -> Pause zones: the map, the zone list and the new-zone form. */
    const val SETTINGS_PRIVACY_ZONES = "settings/privacy/zones"
    /** Settings -> Place Guide (Developer-visible): the switch and Seed this area. */
    const val SETTINGS_PLACE_GUIDE = "settings/place-guide"
    const val SETTINGS_SOURCES = "settings/sources"
    const val SETTINGS_ACTIONS = "settings/actions"
    const val SETTINGS_SHOPPING = "settings/shopping"
    const val SETTINGS_NOTES = "settings/notes"
    /** id "new" opens an empty editor. */
    const val NEW_ENDPOINT = "new"

    fun endpoint(id: String) = "settings/endpoints/${enc(id)}"
    fun prompt(id: String) = "settings/prompts/${enc(id)}"
    fun canvasPipeline(id: String) = "settings/canvas/pipeline?id=${enc(id)}"
    fun promptHistory(id: String) = "settings/prompts/${enc(id)}/history"
    /** Flow ids are registry constants (no commas or reserved characters). */
    fun flowsView(ids: List<String>) = "settings/flows/view?ids=" + ids.joinToString(",")

    /** Settings rows that open a screen in this build. */
    val LIVE_SETTINGS: Set<String> = setOf(
        SETTINGS_GLASSES, SETTINGS_PHONE_INPUTS, SETTINGS_DEVICE_CAPABILITIES, SETTINGS_CAPTURE, SETTINGS_ENDPOINTS, SETTINGS_ROLES, SETTINGS_PROMPTS, SETTINGS_RETRIEVAL,
        SETTINGS_FLOWS, SETTINGS_LOCAL_MODELS, SETTINGS_NEARBY, SETTINGS_CANVAS,
        SETTINGS_PERSONA, SETTINGS_MEMORY, SETTINGS_DIAGNOSTICS, SETTINGS_PRIVACY, SETTINGS_SOURCES, SETTINGS_ACTIONS, SETTINGS_SHOPPING,
        SETTINGS_NOTES, SETTINGS_PLACE_GUIDE,
    )

    enum class Tab(val route: String, val label: String) { HOME("home", "Home"), ACTIVITY("activity", "Activity"), SETTINGS("settings", "Settings") }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun activity(period: Period? = null) = if (period == null) "activity" else "activity?period=${period.name.lowercase()}"
    /** The day view; [atMs] scrolls it to the row nearest that time (a photo-less mark's "Show that time"). */
    fun day(date: LocalDate, atMs: Long? = null) = "day/$date" + (atMs?.let { "?at=$it" } ?: "")
    /** Encoded with `android.net.Uri.encode` (a platform API, not hand-written escaping). */
    fun photos(q: String = "") = if (q.isBlank()) "photos" else "photos?q=${android.net.Uri.encode(q)}"
    fun preview(ref: String) = "preview/${enc(ref)}"
    /** A small set of ids (previewMoment's several items). A period's list never carries its ids: see [listIn]. */
    fun list(kind: com.rokid.context.phone.ui.list.ListKind, ids: List<String>) = "list/${kind.name}?ids=${enc(ids.joinToString(","))}"
    /** The list of [kind] over a period (`MONTH:2026-10-12`, see [com.rokid.context.phone.ui.list.ListScope]): a few bytes however many items it holds; the list resolves the ids itself. */
    fun listIn(kind: com.rokid.context.phone.ui.list.ListKind, scope: String) = "list/${kind.name}?scope=${enc(scope)}"
    fun startRoute(onboardingComplete: Boolean) = if (onboardingComplete) HOME else ONBOARDING

    /** [route] is the destination's pattern (`NavDestination.route`). */
    fun tabFor(route: String?, previewRef: String? = null): Tab? = when {
        route == null || route == ONBOARDING -> null
        // An answer or a nudge is opened from Home; every other item is Activity's.
        route == HOME || (route.startsWith("preview/") && previewRef?.let { it.startsWith("ask:") || it.startsWith("nudge:") } == true) -> Tab.HOME
        route.startsWith("activity") || route.startsWith("day/") || route.startsWith("photos") || route.startsWith("preview/") || route.startsWith("list/") -> Tab.ACTIVITY
        route.startsWith("settings") -> Tab.SETTINGS
        else -> null
    }

    fun showsNav(route: String?): Boolean = route != null && route != ONBOARDING
}
