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
    const val FRAMES = "frames/{date}?start={start}"
    const val SETTINGS = "settings"
    const val SETTINGS_ENDPOINTS = "settings/endpoints"
    const val SETTINGS_DIAGNOSTICS = "settings/diagnostics"
    const val DIAGNOSTICS_QUEUES = "settings/diagnostics/queues"
    const val DIAGNOSTICS_TRACKER = "settings/diagnostics/tracker"
    const val SETTINGS_GLASSES = "settings/glasses"
    const val SETTINGS_PHONE_INPUTS = "settings/phone-inputs"
    const val SETTINGS_CAPTURE = "settings/capture"
    const val SETTINGS_ENDPOINT = "settings/endpoints/{id}"
    const val SETTINGS_ROLES = "settings/roles"
    const val SETTINGS_PROMPTS = "settings/prompts"
    const val SETTINGS_PROMPT = "settings/prompts/{id}"
    const val SETTINGS_PROMPT_HISTORY = "settings/prompts/{id}/history"
    const val SETTINGS_RETRIEVAL = "settings/retrieval"
    const val SETTINGS_PERSONA = "settings/persona"
    const val SETTINGS_MEMORY = "settings/memory"
    const val SETTINGS_MEMORY_REVIEW = "settings/memory/review"
    const val DIAGNOSTICS_LOGS = "settings/diagnostics/logs"
    /** id "new" opens an empty editor. */
    const val NEW_ENDPOINT = "new"

    fun endpoint(id: String) = "settings/endpoints/${enc(id)}"
    fun prompt(id: String) = "settings/prompts/${enc(id)}"
    fun promptHistory(id: String) = "settings/prompts/${enc(id)}/history"

    /** Settings rows that open a screen in this build; Phase 7 rows (privacy, sources, actions) stay "Coming soon". */
    val LIVE_SETTINGS: Set<String> = setOf(SETTINGS_GLASSES, SETTINGS_PHONE_INPUTS, SETTINGS_CAPTURE, SETTINGS_ENDPOINTS, SETTINGS_ROLES, SETTINGS_PROMPTS, SETTINGS_RETRIEVAL, SETTINGS_PERSONA, SETTINGS_MEMORY, SETTINGS_DIAGNOSTICS)

    enum class Tab(val route: String, val label: String) { HOME("home", "Home"), ACTIVITY("activity", "Activity"), SETTINGS("settings", "Settings") }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun ask(id: String) = "ask/${enc(id)}"
    fun activity(period: Period? = null) = if (period == null) "activity" else "activity?period=${period.name.lowercase()}"
    fun day(date: LocalDate) = "day/$date"
    fun memory(id: String) = "memory/${enc(id)}"
    fun frames(date: LocalDate, start: String? = null) = "frames/$date" + (start?.let { "?start=${enc(it)}" } ?: "")
    fun startRoute(onboardingComplete: Boolean) = if (onboardingComplete) HOME else ONBOARDING

    /** [route] is the destination's pattern (`NavDestination.route`). */
    fun tabFor(route: String?): Tab? = when {
        route == null || route == ONBOARDING -> null
        route == HOME || route.startsWith("ask/") -> Tab.HOME
        route.startsWith("activity") || route.startsWith("day/") || route.startsWith("memory/") || route.startsWith("frames/") -> Tab.ACTIVITY
        route.startsWith("settings") -> Tab.SETTINGS
        else -> null
    }

    fun showsNav(route: String?): Boolean = route != null && route != ONBOARDING && !route.startsWith("frames/")
}
