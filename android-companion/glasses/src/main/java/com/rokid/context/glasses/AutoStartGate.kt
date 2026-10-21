package com.rokid.context.glasses

/** Who started the activity: the Rokid launcher (the wearer's own open) or anything else (the phone's appStart, an auto-reopen, a service). */
enum class StartSource { LAUNCHER, OTHER }

/** What the referrer was, as a class only: the package name never reaches a log. */
enum class RefClass { LAUNCHER, OTHER, NONE }

/** Why a start did or did not minimise. */
data class StartDecision(
    val minimise: Boolean, val pending: Boolean, val markerRecent: Boolean, val extra: Boolean,
    val fromMinimised: Boolean, val source: StartSource,
) {
    /** Count-only diag line; never the referrer package. */
    fun line(ref: RefClass): String =
        "start src=$source ref=$ref pending=${pending.b} marker=${markerRecent.b} fromMin=${fromMinimised.b} extra=${extra.b} minimise=${minimise.b}"

    private val Boolean.b get() = if (this) 1 else 0
}

object StartSources {
    fun refClass(referrerPackage: String?): RefClass = when {
        referrerPackage.isNullOrBlank() -> RefClass.NONE
        referrerPackage.contains("launcher", ignoreCase = true) -> RefClass.LAUNCHER
        else -> RefClass.OTHER
    }

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
    /** The last start that put the HUD in front and may have been the phone's reopen (a kill restart, a non-launcher start, or a return from icon mode). */
    private var frontAt: Long? = null
    private var markerAt: Long? = null

    /**
     * True = minimise now. [extraMinimised]: the START_MINIMISED extra was present (and is consumed by the caller).
     * [fromMinimised]: the task was in icon mode when this start came (a live process). Fix wave I N2: a non-launcher start
     * (or any start from icon mode) within [MARKER_WINDOW_MS] of the phone's `pc_auto_open` marker is minimised whether or not
     * the process restarted; when the marker comes after the start, [onAutoOpenMarker] does it.
     */
    @Synchronized fun onStart(source: StartSource, extraMinimised: Boolean, fromMinimised: Boolean = false): Boolean =
        decide(source, extraMinimised, fromMinimised).minimise

    /** The body of [onStart], with the reasons. Consumes the one-shot pending flag like [onStart] does. */
    @Synchronized fun decide(source: StartSource, extraMinimised: Boolean, fromMinimised: Boolean = false): StartDecision {
        val pending = takePending()
        val markerRecent = markerAt?.let { uptimeMs() - it <= MARKER_WINDOW_MS } == true
        val minimise = extraMinimised || (pending && source != StartSource.LAUNCHER) || (markerRecent && (source != StartSource.LAUNCHER || fromMinimised))
        if (!minimise && (pending || source != StartSource.LAUNCHER || fromMinimised)) frontAt = uptimeMs()
        return StartDecision(minimise, pending, markerRecent, extraMinimised, fromMinimised, source)
    }

    /** `pc_auto_open` arrived: true = the HUD that came to front a moment ago was the phone's reopen and must go to icon mode. */
    @Synchronized fun onAutoOpenMarker(): Boolean {
        markerAt = uptimeMs()
        val at = frontAt ?: return false
        frontAt = null
        return uptimeMs() - at <= MARKER_WINDOW_MS
    }

    companion object { const val MARKER_WINDOW_MS = 20_000L }
}
