package com.rokid.context.phone.ui.shell

import com.rokid.context.phone.ui.activity.Period
import java.net.URLEncoder
import java.time.LocalDate

/** spec §2.1 routes. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ASK = "ask/{id}"
    const val ACTIVITY = "activity?period={period}"
    const val DAY = "day/{date}"
    const val MEMORY = "memory/{id}"
    /** Find a photo (wave 3): `q` is optional; a non-blank one searches once on open. */
    const val PHOTOS = "photos?q={q}"
    const val FRAMES = "frames/{date}?start={start}"
    /** The composite previewer (`ref` is a [com.rokid.context.phone.ui.preview.PreviewRef.raw]) and the common list of an Activity kind (`ids` comma-joined). */
    const val PREVIEW = "preview/{ref}"
    const val LIST = "list/{kind}?ids={ids}"
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
    const val SETTINGS_SOURCES = "settings/sources"
    const val SETTINGS_ACTIONS = "settings/actions"
    const val SETTINGS_SHOPPING = "settings/shopping"
    const val SETTINGS_NOTES = "settings/notes"
    /** Nudge detail (Phase 7 ruling: the spec names a "nudge detail" without a route). */
    const val NUDGE = "nudge/{id}"
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
        SETTINGS_NOTES,
    )

    enum class Tab(val route: String, val label: String) { HOME("home", "Home"), ACTIVITY("activity", "Activity"), SETTINGS("settings", "Settings") }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun ask(id: String) = "ask/${enc(id)}"
    fun activity(period: Period? = null) = if (period == null) "activity" else "activity?period=${period.name.lowercase()}"
    fun day(date: LocalDate) = "day/$date"
    fun memory(id: String) = "memory/${enc(id)}"
    /** Encoded with `android.net.Uri.encode` (a platform API, not hand-written escaping). */
    fun photos(q: String = "") = if (q.isBlank()) "photos" else "photos?q=${android.net.Uri.encode(q)}"
    fun preview(ref: String) = "preview/${enc(ref)}"
    fun list(kind: com.rokid.context.phone.ui.list.ListKind, ids: List<String>) = "list/${kind.name}?ids=${enc(ids.joinToString(","))}"
    fun nudge(id: String) = "nudge/${enc(id)}"
    fun frames(date: LocalDate, start: String? = null) = "frames/$date" + (start?.let { "?start=${enc(it)}" } ?: "")
    fun startRoute(onboardingComplete: Boolean) = if (onboardingComplete) HOME else ONBOARDING

    /** [route] is the destination's pattern (`NavDestination.route`). */
    fun tabFor(route: String?): Tab? = when {
        route == null || route == ONBOARDING -> null
        route == HOME || route.startsWith("ask/") || route.startsWith("nudge/") -> Tab.HOME
        route.startsWith("activity") || route.startsWith("day/") || route.startsWith("memory/") || route.startsWith("frames/") || route.startsWith("photos") || route.startsWith("preview/") || route.startsWith("list/") -> Tab.ACTIVITY
        route.startsWith("settings") -> Tab.SETTINGS
        else -> null
    }

    fun showsNav(route: String?): Boolean = route != null && route != ONBOARDING && !route.startsWith("frames/")
}
