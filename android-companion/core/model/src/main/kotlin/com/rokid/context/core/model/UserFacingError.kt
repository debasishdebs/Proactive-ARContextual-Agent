package com.rokid.context.core.model

/** What the UI offers next to an error (spec §6.3). */
enum class ErrorAction { NONE, RECONNECT, OPEN_ON_GLASSES, AUTHORIZE, UPDATE_GLASSES_APP, FIX_ENDPOINT, FREE_STORAGE, RETRY_LATER }

/**
 * A failure as people see it (spec §6.3): a short [title] (also the HUD string), a one-line [hint] and an [action].
 * Never built from exception or socket text; full detail goes to Diagnostics logs only.
 */
data class UserFacingError(val title: String, val hint: String, val action: ErrorAction) {
    /** Title and hint on one line, for trace notes and queue rows. */
    val line: String get() = "$title · $hint"
}

object UserFacingErrors {
    val GlassesOffline = UserFacingError("Glasses offline", "Bring your glasses near the phone.", ErrorAction.RECONNECT)
    val ClosedOnGlasses = UserFacingError("Closed on glasses", "Capture stops until you open Proactive Context on your glasses.", ErrorAction.OPEN_ON_GLASSES)
    val NotAuthorized = UserFacingError("Glasses not authorized", "Authorize Proactive Context in Hi Rokid.", ErrorAction.AUTHORIZE)
    val VersionMismatch = UserFacingError("Update glasses app", "Phone and glasses apps are on different versions.", ErrorAction.UPDATE_GLASSES_APP)
    val StorageFull = UserFacingError("Phone storage full", "Free up space so new frames can be saved.", ErrorAction.FREE_STORAGE)
    val AiKeyRejected = UserFacingError("AI key rejected", "Check the API key for this endpoint.", ErrorAction.FIX_ENDPOINT)
    val AiNotSet = UserFacingError("AI model not set up", "Finish the endpoint and model in Settings.", ErrorAction.FIX_ENDPOINT)
    val AiRequestRejected = UserFacingError("AI request rejected", "Check the endpoint and model in Settings.", ErrorAction.FIX_ENDPOINT)
    val AiUnavailable = UserFacingError("AI unavailable", "Will retry automatically.", ErrorAction.RETRY_LATER)
    /** Model privacy "Offline only" refused a call to a remote model (slice 1.4.3): the typed, user-visible "needs an on-device model" state. */
    val AiNeedsOnDevice = UserFacingError("Needs an on-device model", "Offline only is on. Use an on-device or home model, or switch it off.", ErrorAction.FIX_ENDPOINT)
    val AiGaveUp = UserFacingError("AI kept failing", "Stopped retrying; check the endpoint in Settings.", ErrorAction.FIX_ENDPOINT)
    val Unknown = UserFacingError("Something went wrong", "Details are in Diagnostics.", ErrorAction.NONE)

    val all: List<UserFacingError> = listOf(
        GlassesOffline, ClosedOnGlasses, NotAuthorized, VersionMismatch, StorageFull,
        AiKeyRejected, AiNotSet, AiRequestRejected, AiUnavailable, AiNeedsOnDevice, AiGaveUp, Unknown,
    )

    /** Banner for a link state (spec §2.2); null for states that need no action. */
    fun forLink(link: LinkState): UserFacingError? = when (link) {
        LinkState.OFFLINE -> GlassesOffline
        LinkState.CLOSED_ON_GLASSES -> ClosedOnGlasses
        LinkState.NOT_AUTHORIZED -> NotAuthorized
        LinkState.VERSION_MISMATCH -> VersionMismatch
        LinkState.CONNECTING, LinkState.CONNECTED, LinkState.PAUSED_BT, LinkState.MINIMISED -> null
    }
}
