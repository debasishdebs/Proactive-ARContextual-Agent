package com.rokid.context.glasses

/** Who started the activity: the Rokid launcher (the wearer's own open) or anything else (the phone's appStart, an auto-reopen, a service). */
enum class StartSource { LAUNCHER, OTHER }

object StartSources {
    /**
     * A launcher open is MAIN + LAUNCHER from a referrer that is a launcher app. The CXR appStart carries the same
     * action, so the referrer is the only discriminator the intent offers; an unreadable referrer is [StartSource.OTHER]
     * (the safe side: never full screen over a call). Needs checking on the device (the start log line names the referrer).
     */
    fun classify(action: String?, categories: Set<String>?, referrerPackage: String?): StartSource =
        if (action == "android.intent.action.MAIN" && categories?.contains("android.intent.category.LAUNCHER") == true &&
            referrerPackage?.contains("launcher", ignoreCase = true) == true
        ) StartSource.LAUNCHER else StartSource.OTHER
}

/**
 * Fix wave I (C1, I3): decides when a start of the activity goes straight to icon mode.
 *  - After a restart that follows a kill ([takePending]), the first start minimises unless the wearer opened the app
 *    from the launcher (then the HUD shows: they asked for it).
 *  - [EXTRA] minimised start: once (the caller removes the extra from the intent).
 *  - The phone's `pc_auto_open` marker corrects a launcher-looking start that was really the phone's reopen: within
 *    [MARKER_WINDOW_MS] of that start the HUD goes back to icon mode.
 */
class AutoStartGate(private val takePending: () -> Boolean, private val uptimeMs: () -> Long) {
    private var launcherStartAt: Long? = null

    /** True = minimise now. [extraMinimised]: the START_MINIMISED extra was present (and is consumed by the caller). */
    @Synchronized fun onStart(source: StartSource, extraMinimised: Boolean): Boolean {
        val pending = takePending()
        val minimise = extraMinimised || (pending && source != StartSource.LAUNCHER)
        if (pending && source == StartSource.LAUNCHER) launcherStartAt = uptimeMs()
        return minimise
    }

    /** `pc_auto_open` arrived: true = the HUD showing from a launcher-looking start must go to icon mode. */
    @Synchronized fun onAutoOpenMarker(): Boolean {
        val at = launcherStartAt ?: return false
        launcherStartAt = null
        return uptimeMs() - at <= MARKER_WINDOW_MS
    }

    companion object { const val MARKER_WINDOW_MS = 20_000L }
}
