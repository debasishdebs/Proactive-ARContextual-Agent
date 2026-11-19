package com.rokid.context.core.net

/**
 * Roaming data saver, slice 1 (design docs/superpowers/plans/2026-11-20-roaming-data-saver-design.md). Pure Kotlin: the network cost classification and the
 * allow/deny decision are plain functions so a table test can drive every row. Nothing here keeps or logs a name, address, country or position.
 */

/** The transport of the default network. [OTHER] = VPN-only, Bluetooth or anything we cannot place: its cost is unknown. */
enum class Transport { NONE, WIFI, ETHERNET, CELLULAR, OTHER }

/** What the phone's network stack reports for the default network. [known] false = no reading yet, no capabilities, or a SecurityException: never guessed. */
data class NetSnapshot(
    val known: Boolean,
    val transport: Transport,
    val validated: Boolean,
    val notMetered: Boolean,
    val notRoaming: Boolean,
    /** SIM country differs from the network country (a second roaming signal). Only this boolean is kept, never the countries. */
    val countryMismatch: Boolean = false,
    /** Android's own Data Saver is ON and this app is NOT exempt from it (ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED; a whitelisted app reads false): the system itself blocks our background mobile data. */
    val restrictBackground: Boolean = false,
    /** When the network was first seen without validation (ms, 0 = validated or not applicable). Not part of the cost: only for the "stuck without internet" status line. */
    val unvalidatedSinceMs: Long = 0L,
) {
    companion object {
        /** Before the first reading: fail closed. */
        val UNKNOWN = NetSnapshot(known = false, transport = Transport.NONE, validated = false, notMetered = false, notRoaming = false)
    }
}

/**
 * [UNKNOWN] is its own value so the screen can say "Unknown network: treated as roaming", but every rule treats it like [ROAMING].
 * [OFFLINE] = no network at all: nothing can leave, the existing offline handling (queue, no attempt) applies.
 */
enum class NetworkCost { FREE, HOME_MOBILE, ROAMING, UNKNOWN, OFFLINE,
    /** Metered network while Android's Data Saver is on and this app is not exempt: not roaming, but Android blocks background data, so photo uploads wait (small text is still tried). */
    RESTRICTED }

object NetworkCosts {
    /** Fail closed: anything the phone does not say clearly is costly. [abroad] is the user's "I'm abroad" switch: it forces every cellular network (and a metered Wi-Fi, a likely hotspot) to ROAMING. */
    fun classify(s: NetSnapshot, abroad: Boolean): NetworkCost {
        if (!s.known) return NetworkCost.UNKNOWN
        return when (s.transport) {
            Transport.NONE -> NetworkCost.OFFLINE
            Transport.OTHER -> NetworkCost.UNKNOWN
            Transport.CELLULAR ->
                if (abroad || !s.notRoaming || s.countryMismatch) NetworkCost.ROAMING else if (s.restrictBackground) NetworkCost.RESTRICTED else NetworkCost.HOME_MOBILE
            Transport.WIFI, Transport.ETHERNET -> when {
                !s.notRoaming -> NetworkCost.ROAMING
                !s.validated -> NetworkCost.UNKNOWN // a captive portal or a network still being checked is not free
                !s.notMetered -> if (abroad) NetworkCost.ROAMING else if (s.restrictBackground) NetworkCost.RESTRICTED else NetworkCost.HOME_MOBILE
                else -> NetworkCost.FREE
            }
        }
    }

    /** A Wi-Fi or Ethernet network that is connected and unmetered but has not validated for longer than [thresholdMs]: remote photo calls stay blocked (the cost stays UNKNOWN); only the status line says so, and LAN or on-device endpoints keep working. */
    fun stuckUnvalidated(s: NetSnapshot, nowMs: Long, thresholdMs: Long = STUCK_MS): Boolean =
        s.known && (s.transport == Transport.WIFI || s.transport == Transport.ETHERNET) && !s.validated && s.notMetered && s.notRoaming &&
            s.unvalidatedSinceMs > 0 && nowMs - s.unvalidatedSinceMs > thresholdMs

    const val STUCK_MS = 10 * 60_000L

    /**
     * A Wi-Fi or Ethernet network that is connected but has been without validation for less than [debounceMs]: every reconnect delivers its capabilities before validation, so the cost is UNKNOWN for a moment.
     * That blip is not a reason to mark photo uploads "blocked" or to park the backlog; a network still unvalidated after the debounce is.
     */
    fun preValidationBlip(s: NetSnapshot, nowMs: Long, debounceMs: Long = BLIP_MS): Boolean =
        s.known && (s.transport == Transport.WIFI || s.transport == Transport.ETHERNET) && !s.validated && s.notRoaming &&
            s.unvalidatedSinceMs > 0 && nowMs - s.unvalidatedSinceMs < debounceMs

    const val BLIP_MS = 20_000L

    /** True for every cost that is not FREE and not OFFLINE: the cases where a stored place cell is kept instead of fetched again. */
    fun isCostly(c: NetworkCost): Boolean = c == NetworkCost.HOME_MOBILE || c == NetworkCost.ROAMING || c == NetworkCost.UNKNOWN || c == NetworkCost.RESTRICTED

    /** Why a ROAMING classification happened, for the status line: [WHY_ABROAD] (the switch), [WHY_REPORTED] (the phone says roaming), [WHY_COUNTRY] (SIM and network country differ); "" when not roaming. */
    fun roamingWhy(s: NetSnapshot, abroad: Boolean): String = when {
        classify(s, abroad) != NetworkCost.ROAMING -> ""
        !s.notRoaming -> WHY_REPORTED
        s.countryMismatch -> WHY_COUNTRY
        abroad -> WHY_ABROAD
        else -> WHY_REPORTED
    }
    const val WHY_ABROAD = "abroad"
    const val WHY_REPORTED = "reported"
    const val WHY_COUNTRY = "country"

}

/** Which traffic is asked about. PHOTO: a request whose images will be sent. BULK_OTHER: many or large requests without photos (model download, seeding, night re-describe sweeps). SMALL: text. */
enum class DataKind { PHOTO, BULK_OTHER, SMALL }

/** Where the call goes. DEVICE: on this phone, never gated. LAN: a private or .local address (free only on Wi-Fi). REMOTE: anything else. */
enum class EndpointReach { DEVICE, LAN, REMOTE }

/** Settings > Data saver > "Photos and big uploads". */
enum class DataMode {
    WIFI_ONLY, WIFI_AND_HOME_MOBILE, ANYWHERE;

    companion object {
        /** The default: Wi-Fi and home mobile data. Roaming, unknown networks and the "I'm abroad" case stay blocked; "Wi-Fi only" is one tap away. */
        val DEFAULT = WIFI_AND_HOME_MOBILE

        /** An unknown stored value reads as the default. */
        fun parse(s: String?): DataMode = entries.firstOrNull { it.name == s } ?: DEFAULT
    }
}

data class DataSaverSettings(
    val mode: DataMode = DataMode.DEFAULT,
    /** The manual "I'm abroad" switch. */
    val abroad: Boolean = false,
    /** Text requests (questions, place facts) stay allowed on roaming. Slice 1 default ON, no daily limit yet. */
    val smallOnRoaming: Boolean = true,
) {
    /**
     * The mode that is applied. Without a daily limit (slice 2) "Anywhere" cannot be honoured while the user says they are abroad, so it behaves as "Wi-Fi and home mobile data":
     * the abroad switch always means no photo or big upload on mobile data.
     */
    val effectiveMode: DataMode get() = if (abroad && mode == DataMode.ANYWHERE) DataMode.WIFI_AND_HOME_MOBILE else mode
}

/** [reason] is a fixed token (never text from a user or a provider) and is the only thing logged. */
data class DataDecision(val allowed: Boolean, val reason: String) {
    companion object {
        val ALLOW = DataDecision(true, "allowed")
    }
}

object DataPolicy {
    const val R_ON_DEVICE = "on_device"
    const val R_LAN = "lan"
    const val R_FREE = "free"
    const val R_OFFLINE = "offline"
    const val R_WIFI_ONLY = "wifi_only"
    const val R_ROAMING = "roaming"
    const val R_UNKNOWN = "unknown_network"
    const val R_HOME_MOBILE = "home_mobile"
    const val R_SMALL_OK = "small_allowed"
    const val R_ANYWHERE = "anywhere"
    const val R_ANDROID_SAVER = "android_data_saver"

    /**
     * The one decision. [transport] is only used to tell whether a LAN endpoint is reachable for free (Wi-Fi or Ethernet).
     * Offline is allowed here: nothing can leave, and the callers' existing offline path (no attempt used, park, resume) handles it.
     */
    fun decide(kind: DataKind, reach: EndpointReach, cost: NetworkCost, transport: Transport, s: DataSaverSettings): DataDecision {
        if (reach == EndpointReach.DEVICE) return DataDecision(true, R_ON_DEVICE)
        if (reach == EndpointReach.LAN && (transport == Transport.WIFI || transport == Transport.ETHERNET)) return DataDecision(true, R_LAN)
        if (cost == NetworkCost.FREE) return DataDecision(true, R_FREE)
        if (cost == NetworkCost.OFFLINE) return DataDecision(true, R_OFFLINE)
        val blockedToken = when (cost) { NetworkCost.UNKNOWN -> R_UNKNOWN; NetworkCost.ROAMING -> R_ROAMING; NetworkCost.RESTRICTED -> R_ANDROID_SAVER; else -> R_HOME_MOBILE }
        return when (kind) {
            DataKind.SMALL ->
                if (cost == NetworkCost.HOME_MOBILE || cost == NetworkCost.RESTRICTED || s.smallOnRoaming) DataDecision(true, R_SMALL_OK) else DataDecision(false, blockedToken)
            DataKind.PHOTO, DataKind.BULK_OTHER -> if (cost == NetworkCost.RESTRICTED) DataDecision(false, R_ANDROID_SAVER) // Android itself blocks background data: no mode can change that
            else when (s.effectiveMode) {
                DataMode.ANYWHERE -> DataDecision(true, R_ANYWHERE)
                DataMode.WIFI_AND_HOME_MOBILE -> if (cost == NetworkCost.HOME_MOBILE) DataDecision(true, R_HOME_MOBILE) else DataDecision(false, blockedToken)
                DataMode.WIFI_ONLY -> DataDecision(false, if (cost == NetworkCost.HOME_MOBILE) R_WIFI_ONLY else blockedToken)
            }
        }
    }

    /**
     * Whether [host] is a private, link-local, loopback or `.local` address: a box on the user's own network. Never resolves a name (under Offline only the model client's DNS check also requires every resolved address to be on the user's own network). IPv6 literals other than loopback
     * count as remote (we cannot tell a ULA from a public one cheaply, and fail closed).
     */
    fun isLanHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase()?.trimEnd('.') ?: return false
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local") || h == "::1" || h == "[::1]") return true
        val p = h.split('.')
        // ASCII digits only, and no leading zero ("010.0.0.1" may be read as octal by the system resolver, "٣" is not an ASCII digit): anything unusual is remote.
        if (p.size != 4 || p.any { it.isEmpty() || it.length > 3 || !it.all { c -> c in '0'..'9' } || (it.length > 1 && it[0] == '0') }) return false
        val o = p.map { it.toInt() }
        if (o.any { it > 255 }) return false
        return o[0] == 10 || o[0] == 127 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31) || (o[0] == 169 && o[1] == 254)
    }
}

/** What was skipped, for the "today" counts. UPLOAD_QUEUED: a model call refused at the router, its photos stay on the phone for Wi-Fi. DROPPED: an optional photo call that is not made (place match, image intent). BULK: other big work left for Wi-Fi. */
enum class Skipped { UPLOAD_QUEUED, DROPPED, BULK }

/**
 * The run-time gate the call sites ask. The production one is `DataSaverGraph`; [ALLOW_ALL] is for tests and pure modules that never see the network.
 * [decide] has no side effect; a caller that then skips the work calls [skipped] once, which counts it and logs a counts-and-reason-only line.
 */
interface DataGate {
    fun decide(kind: DataKind, reach: EndpointReach = EndpointReach.REMOTE): DataDecision
    fun skipped(kind: DataKind, what: Skipped, d: DataDecision)
    /** True while the network is mobile, roaming or unknown (stored place cells are then kept instead of re-fetched). */
    fun costlyNow(): Boolean
    /** Re-reads the network facts that can change without a capability callback (Android Data Saver status, SIM and network country) and restarts a failed network watch. Cheap; default no-op. */
    fun refresh() {}

    companion object {
        val ALLOW_ALL: DataGate = object : DataGate {
            override fun decide(kind: DataKind, reach: EndpointReach) = DataDecision.ALLOW
            override fun skipped(kind: DataKind, what: Skipped, d: DataDecision) {}
            override fun costlyNow() = false
        }
    }
}

/** Where a counter value lives. Prefs in the app; a map in tests. */
interface CounterStore {
    fun getLong(key: String, default: Long): Long
    fun putLongs(values: Map<String, Long>)
}

/**
 * Today's skipped counts, content-free (numbers only). Keyed by the local date. A later date starts new counters; an EARLIER date than the stored one (the clock was set back)
 * keeps counting into the stored day, so a clock change never gives a free reset.
 */
class SkippedLedger(private val store: CounterStore, private val epochDay: () -> Long) {
    data class Today(val uploadsQueued: Long, val dropped: Long, val bulk: Long) {
        val total: Long get() = uploadsQueued + dropped + bulk
    }

    @Synchronized fun add(what: Skipped, n: Long = 1) {
        val stored = store.getLong(DAY, -1)
        val today = epochDay()
        val day = if (stored < 0 || today > stored) today else stored
        val fresh = day != stored
        val key = key(what)
        val base = if (fresh) 0L else store.getLong(key, 0)
        val reset = if (fresh) Skipped.entries.filter { it != what }.associate { key(it) to 0L } else emptyMap()
        store.putLongs(reset + mapOf(DAY to day, key to base + n))
    }

    @Synchronized fun today(): Today {
        val stored = store.getLong(DAY, -1)
        if (stored < 0 || epochDay() > stored) return Today(0, 0, 0)
        return Today(store.getLong(key(Skipped.UPLOAD_QUEUED), 0), store.getLong(key(Skipped.DROPPED), 0), store.getLong(key(Skipped.BULK), 0))
    }

    private fun key(w: Skipped) = "skipped_" + w.name.lowercase()

    companion object { const val DAY = "skipped_day" }
}
