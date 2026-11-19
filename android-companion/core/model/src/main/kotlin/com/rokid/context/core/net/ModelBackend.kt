package com.rokid.context.core.net

/**
 * Model privacy and the backend choice (slice 1.4.3, design docs/superpowers/plans/2026-11-20-place-guide-resolver-and-semantic-ranking.md section 2). Pure Kotlin so a table test drives every
 * row. Nothing here keeps or logs a name, address or content.
 */

/**
 * Settings > Capture & privacy > Model privacy. [AUTO] is today's behaviour. [OFFLINE_ONLY] never calls a remote endpoint: only this phone and the user's own network (LAN) may be asked, and a call
 * that has no such model fails closed.
 */
enum class ModelPrivacy {
    AUTO, OFFLINE_ONLY;

    companion object {
        val DEFAULT = AUTO

        /** Nothing stored = the default. Anything stored that is not a known value reads as [OFFLINE_ONLY]: an unreadable privacy setting must never open a remote call (fail closed). */
        fun parse(stored: String?): ModelPrivacy = if (stored == null) DEFAULT else entries.firstOrNull { it.name == stored } ?: OFFLINE_ONLY
    }
}

/** Where a call runs. [ONLINE] = the bound remote route (through the router); [OFFLINE] = an on-device model (slot filled by slice 1.4.5). */
enum class Backend { ONLINE, OFFLINE }

/**
 * What the network check reports. [UNKNOWN] = the check itself failed (no ConnectivityManager, an exception): it is never "validated". [BackendDecision] says what each mode does with it.
 */
enum class NetState { VALIDATED, NONE, UNKNOWN }

/** Why no backend was picked (or "ok"). The lower-case name is the fixed token that is logged. */
enum class ResolveReason {
    OK, OFFLINE_ONLY_NO_LOCAL_MODEL, NO_NETWORK_NO_LOCAL_MODEL, NOT_BOUND, LOAD_FAILED;

    val token: String get() = name.lowercase()
}

/** The outcome of the pure decision: [backend] null = unavailable, with [reason] saying why. */
data class BackendPick(val backend: Backend?, val reason: ResolveReason)

object BackendDecision {
    /**
     * Setting first, then network.
     *  - [ModelPrivacy.OFFLINE_ONLY]: the offline backend if one is available, else unavailable. NEVER [Backend.ONLINE], whatever the network says (including [NetState.UNKNOWN]).
     *  - [ModelPrivacy.AUTO]: online when the network is not known to be down and an online route exists; else offline if available; else unavailable.
     *    [NetState.UNKNOWN] counts as "up" in AUTO ONLY: that is today's behaviour (a failing network check never stopped a call; the data saver and the router decide cost and reach). It has no say
     *    in OFFLINE_ONLY.
     */
    fun decide(mode: ModelPrivacy, net: NetState, onlineAvailable: Boolean, offlineAvailable: Boolean): BackendPick = when (mode) {
        ModelPrivacy.OFFLINE_ONLY ->
            if (offlineAvailable) BackendPick(Backend.OFFLINE, ResolveReason.OK) else BackendPick(null, ResolveReason.OFFLINE_ONLY_NO_LOCAL_MODEL)
        ModelPrivacy.AUTO -> {
            val up = net != NetState.NONE
            when {
                up && onlineAvailable -> BackendPick(Backend.ONLINE, ResolveReason.OK)
                offlineAvailable -> BackendPick(Backend.OFFLINE, ResolveReason.OK)
                !up -> BackendPick(null, ResolveReason.NO_NETWORK_NO_LOCAL_MODEL)
                else -> BackendPick(null, ResolveReason.NOT_BOUND)
            }
        }
    }
}

object ModelPrivacyPolicy {
    /** Fixed reason token of a refusal (logged, and carried by the typed exception). */
    const val R_OFFLINE_ONLY = "offline_only"

    /** [OFFLINE_ONLY][ModelPrivacy.OFFLINE_ONLY] allows only this phone and the user's own network. [EndpointReach.REMOTE] is never allowed; there is no other exception. */
    fun allows(mode: ModelPrivacy, reach: EndpointReach): Boolean = mode == ModelPrivacy.AUTO || reach != EndpointReach.REMOTE
}

/**
 * Per-backend floors for the model's own confidence (a 0..1 number the model reports). One place, so a change is one edit and the decision log can name the version ([VERSION]).
 * [ONLINE_MIN] is today's value, unchanged. [OFFLINE_MIN] is the design's starting value for a small on-device model (UNVERIFIED until the 1.4.5 eval); nothing uses it before that slice.
 */
object BackendThresholds {
    const val VERSION = 1
    const val ONLINE_MIN = 0.5f
    const val OFFLINE_MIN = 0.7f

    fun minConfidence(b: Backend): Float = when (b) { Backend.ONLINE -> ONLINE_MIN; Backend.OFFLINE -> OFFLINE_MIN }

    /** The floor as a whole percent, for the log reason token. */
    fun minConfidencePct(b: Backend): Int = (minConfidence(b) * 100).toInt()
}
