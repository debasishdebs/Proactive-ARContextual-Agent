package com.rokid.context.phone.nexus

import io.github.pca.nexusport.NexusResult

/** The packages PCA looks for (read only, never installed or launched by PCA). Their visibility needs the `<queries>` entries of the manifest. */
object NexusPackages {
    /** PCA's own bridge APK (`:nexus-bridge`). */
    const val BRIDGE = "io.github.pca.nexusbridge"
    /** The Rokid Nexus phone hub (github.com/Anezium/Rokid-Nexus). */
    const val HUB = "com.anezium.rokidbus.phone"
    /** Rokid-Coder's phone app (a Nexus fork that holds the same glasses link). */
    const val ROKID_CODER = "io.github.debasishdebs.rokidcoder"
}

/** What is installed on this phone. */
data class NexusInstalled(
    val bridge: Boolean, val hub: Boolean, val rokidCoder: Boolean,
    /** A package named like the bridge is installed but is not signed like PCA (or a pinned key): PCA never binds it. */
    val bridgeUntrusted: Boolean = false,
)

/** Everything the screen's state is derived from; no text. */
data class NexusInputs(
    val installed: NexusInstalled,
    /** The user chose Nexus as the HUD host. */
    val hostIsNexus: Boolean,
    /** Android refused the bind (the bridge is signed with another key, so it cannot hold PCA's signature permission). */
    val bindRefused: Boolean = false,
    /** PCA is connected to the bridge's port service. */
    val bound: Boolean = false,
    /** The last [NexusResult] code the bridge or the hub answered with; null = nothing was sent yet. */
    val lastCode: Int? = null,
    /** PCA's own glasses link is up (so PCA holds the glasses link itself). */
    val pcaLinkUp: Boolean = false,
    /** The last bind (or a package change) found the bridge's signer is not PCA's. */
    val bridgeUntrusted: Boolean = false,
)

/** The states of the "Rokid Nexus" screen. */
enum class NexusState {
    BRIDGE_NOT_INSTALLED,
    /** A package with the bridge's name is installed, but it is not signed like PCA: it is treated as not installed and never bound. */
    BRIDGE_NOT_INSTALLED_UNTRUSTED,
    BRIDGE_REFUSED, HUB_NOT_INSTALLED,
    /** Installed, but the HUD host is PCA's own glasses app (the default). */
    OFF,
    /** Nexus is the host and the bridge is connecting, or nothing was sent yet to learn the hub's answer. */
    WAITING,
    PENDING_APPROVAL, APPROVED, CAPABILITY_DENIED, HUB_STOPPED, LINK_BUSY,
}

/**
 * The state of the Nexus connection as the user reads it, and its plain-language texts. Derived only from what PCA can observe: which packages exist, whether the bind worked and the last result code of
 * a HUD call. The mapping of codes to states comes from the S0/S1 probe (spec 2026-11-17 section 6): `NOT_REGISTERED` before the hub registers the plugin = Pending approval; `CAPABILITY_NOT_GRANTED` = the
 * wearer denied a capability; `CAPABILITY_NOT_AVAILABLE` = the hub has no glasses link. Whether that is "hub stopped" or "another app holds the link" cannot be read from the hub, so it is told apart by who else
 * could hold it (PCA's own link, or Rokid-Coder installed): a heuristic until hardware spike S2 has run.
 */
object NexusStatus {
    fun of(i: NexusInputs): NexusState = when {
        i.bridgeUntrusted || i.installed.bridgeUntrusted -> NexusState.BRIDGE_NOT_INSTALLED_UNTRUSTED
        !i.installed.bridge -> NexusState.BRIDGE_NOT_INSTALLED
        i.bindRefused -> NexusState.BRIDGE_REFUSED
        !i.installed.hub -> NexusState.HUB_NOT_INSTALLED
        !i.hostIsNexus -> NexusState.OFF
        !i.bound -> NexusState.WAITING
        else -> when (i.lastCode) {
            null, NexusResult.ERROR -> NexusState.WAITING
            NexusResult.NOT_REGISTERED -> NexusState.PENDING_APPROVAL
            NexusResult.NOT_GRANTED -> NexusState.CAPABILITY_DENIED
            NexusResult.NOT_AVAILABLE -> if (i.pcaLinkUp || i.installed.rokidCoder) NexusState.LINK_BUSY else NexusState.HUB_STOPPED
            else -> NexusState.APPROVED // OK, BUSY, RATE_LIMITED, DISABLED, INVALID, UNSUPPORTED: all say the hub registered and approved the plugin
        }
    }

    fun title(s: NexusState): String = when (s) {
        NexusState.BRIDGE_NOT_INSTALLED -> "Bridge not installed"
        NexusState.BRIDGE_NOT_INSTALLED_UNTRUSTED -> "Bridge not trusted"
        NexusState.BRIDGE_REFUSED -> "Bridge cannot be used"
        NexusState.HUB_NOT_INSTALLED -> "Nexus not installed"
        NexusState.OFF -> "Off"
        NexusState.WAITING -> "Waiting for Nexus"
        NexusState.PENDING_APPROVAL -> "Pending approval"
        NexusState.APPROVED -> "Approved"
        NexusState.CAPABILITY_DENIED -> "Capability denied"
        NexusState.HUB_STOPPED -> "Nexus hub stopped"
        NexusState.LINK_BUSY -> "Glasses link busy"
    }

    fun detail(s: NexusState): String = when (s) {
        NexusState.BRIDGE_NOT_INSTALLED -> "The PCA Nexus bridge app is not on this phone. It is a separate, optional app. PCA works fully without it; nothing is installed for you."
        NexusState.BRIDGE_NOT_INSTALLED_UNTRUSTED -> "An app named like the PCA Nexus bridge is installed, but it is not signed with the same key as PCA, so PCA treats the bridge as not installed and sends it nothing. Uninstall it, then install the bridge that came with this PCA release."
        NexusState.BRIDGE_REFUSED -> "Android did not let PCA connect to the bridge. It must be signed with the same key as PCA. Reinstall the bridge that came with this PCA release."
        NexusState.HUB_NOT_INSTALLED -> "The Rokid Nexus app (com.anezium.rokidbus.phone) is not on this phone. The bridge needs it to reach the glasses. PCA does not install it."
        NexusState.OFF -> "PCA's own glasses app draws the cards. Nothing is sent to Nexus."
        NexusState.WAITING -> "Connecting to the bridge, or nothing has been sent to Nexus yet. The first card or status line will show the answer."
        NexusState.PENDING_APPROVAL -> "Open Rokid Nexus, then Settings, then Plugin access, and allow \"Proactive Context Agent\". Until then nothing is drawn."
        NexusState.APPROVED -> "Nexus approved the bridge. Cards, answers and the capture status are drawn by Nexus on the glasses."
        NexusState.CAPABILITY_DENIED -> "Nexus denied something the bridge asked for. Open Rokid Nexus, Settings, Plugin access, and allow it, or switch back to PCA's own glasses app."
        NexusState.HUB_STOPPED -> "The Nexus hub is not running or has no glasses link. Open Rokid Nexus and start it. Nothing is drawn meanwhile."
        NexusState.LINK_BUSY -> "Another app holds the glasses link (PCA's own glasses app, or Rokid-Coder). Nexus and Rokid-Coder run one at a time. Close the other one, then try again. PCA does not take over by itself."
    }

    /** The history cleanup PCA owes Nexus has not been acknowledged yet (B2, smoke 3). No count of notices and no content: only that it is owed. */
    const val WIPE_PENDING = "Cleanup of Nexus notification history is pending: it will run when the bridge is reachable."

    /** Why the owed cleanup cannot run right now, from the state alone (plain words, nothing from the notices). */
    fun wipeWhy(s: NexusState): String = when (s) {
        NexusState.BRIDGE_NOT_INSTALLED_UNTRUSTED -> "The bridge is not trusted, so PCA does not talk to it."
        NexusState.BRIDGE_NOT_INSTALLED -> "The bridge is not installed."
        NexusState.BRIDGE_REFUSED -> "Android did not let PCA connect to the bridge."
        NexusState.HUB_NOT_INSTALLED -> "Rokid Nexus is not installed."
        NexusState.PENDING_APPROVAL -> "The bridge is not approved in Rokid Nexus yet."
        NexusState.CAPABILITY_DENIED -> "Rokid Nexus denied the bridge something it needs."
        NexusState.HUB_STOPPED, NexusState.LINK_BUSY -> "The Rokid Nexus hub is not reachable right now."
        NexusState.OFF, NexusState.WAITING, NexusState.APPROVED -> "The bridge is not connected right now, or Rokid Nexus has not approved it yet."
    }

    /** Both lines of the notice, in the order the screen shows them. */
    fun wipeNotice(s: NexusState): String = WIPE_PENDING + " " + wipeWhy(s)

    /** Shown when Rokid-Coder is installed, whatever the state. */
    const val ROKID_CODER_NOTE = "Rokid-Coder is installed. Rokid-Coder and Nexus use the same glasses link, so run one at a time: close the one you are not using. PCA never takes the link from either by itself."

    /** The trust text (PRIVACY.md carries the same words). Plain language: what the bridge can see and do, what Nexus keeps, what PCA wipes, one at a time. */
    val TRUST_LINES: List<String> = listOf(
        "Rokid Nexus and the bridge are separate apps. Nexus is third-party code.",
        "When Nexus is the HUD host, the bridge sees every card PCA sends to the glasses: nudge, place card, answer and mark texts, and the capture status. It can draw and hide them, and it tells PCA when you tap Dismiss, Mark or More. It cannot read your memories or photos and stores nothing.",
        "Nexus can keep the notices it showed in its notification history for up to 24 hours. PCA asks Nexus to wipe that history when you Forget, Delete all, or switch Nexus off. When you pause, or a pause zone is near, PCA hides what is on the glasses at once.",
        "Nothing is sent while PCA is paused, a pause zone is near, or the glasses are not worn.",
        "Rokid-Coder and Nexus run one at a time on one pair of glasses. PCA never installs, starts or takes over either of them.",
    )

    const val HOST_PCA = "PCA glasses app"
    const val HOST_NEXUS = "Rokid Nexus"
}
