package com.rokid.context.phone.ui.shell

import com.rokid.context.phone.ui.activity.Period
import java.net.URLEncoder
import java.time.LocalDate

/** spec §2.1 routes owned by Phase 4 (Phase 5 adds the rest of `settings/…`). */
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
