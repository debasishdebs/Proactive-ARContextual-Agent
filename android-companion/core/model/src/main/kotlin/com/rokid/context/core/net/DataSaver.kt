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
    fun classify(s: NetSnapshot, settings: DataSaverSettings): NetworkCost = classify(s, settings.abroad, settings.wifiMeteredAbroad)

    fun classify(s: NetSnapshot, abroad: Boolean, wifiMeteredAbroad: Boolean = false): NetworkCost {
        if (!s.known) return NetworkCost.UNKNOWN
        return when (s.transport) {
            Transport.NONE -> NetworkCost.OFFLINE
            Transport.OTHER -> NetworkCost.UNKNOWN
            Transport.CELLULAR ->
                if (abroad || !s.notRoaming || s.countryMismatch) NetworkCost.ROAMING else if (s.restrictBackground) NetworkCost.RESTRICTED else NetworkCost.HOME_MOBILE
            Transport.WIFI, Transport.ETHERNET -> when {
                !s.notRoaming -> NetworkCost.ROAMING
                !s.validated -> NetworkCost.UNKNOWN // a captive portal or a network still being checked is not free
                // Slice 2 (hotspot): while abroad AND the user asked for it, every Wi-Fi counts as mobile data (a phone hotspot or pocket Wi-Fi looks like free Wi-Fi). Global, nothing about the network is stored.
                abroad && wifiMeteredAbroad && s.transport == Transport.WIFI -> NetworkCost.ROAMING
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
    fun roamingWhy(s: NetSnapshot, abroad: Boolean, wifiMeteredAbroad: Boolean = false): String = when {
        classify(s, abroad, wifiMeteredAbroad) != NetworkCost.ROAMING -> ""
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
    /** Text requests (questions, place facts) stay allowed on roaming. Default ON. */
    val smallOnRoaming: Boolean = true,
    /** Slice 2: the user's own "Daily limit on mobile data". Default OFF: a default user has no limit on home Wi-Fi or home mobile data. */
    val dailyLimitOn: Boolean = false,
    /** The user's own limit, MB per day on mobile data (only read while [dailyLimitOn]). */
    val limitMb: Int = DailyCaps.DEFAULT_MB,
    /** The user's own limit, photo uploads per day on mobile data (only read while [dailyLimitOn]). */
    val limitPhotos: Int = DailyCaps.DEFAULT_PHOTOS,
    /** Slice 2: "Treat Wi-Fi as metered while I'm abroad" (a phone hotspot or pocket Wi-Fi). Default OFF; only has an effect while [abroad] is on. */
    val wifiMeteredAbroad: Boolean = false,
) {
    /** The mode that is applied. Slice 2: "Anywhere" is honoured while abroad (under the daily cap of [DailyCaps]); it was held to "Wi-Fi and home mobile data" in slice 1 for want of a cap. */
    val effectiveMode: DataMode get() = mode
}

/** Mobile data used today (counts only). [bytes]: every kind, both directions, payload only. [photos]: photos in uploaded photo calls. */
data class DayUsage(val bytes: Long, val photos: Long) {
    companion object { val ZERO = DayUsage(0, 0) }
}

/** A day's limit on mobile data. */
data class DailyLimit(val bytes: Long, val photos: Long)

/**
 * Slice 2 caps. NO limit unless (i) the user says they are abroad or the network is roaming/unknown AND the mode lets photos use mobile data ("Anywhere"), or
 * (ii) the user turned on "Daily limit on mobile data". On Wi-Fi (FREE) and offline there is never a limit. A default user (mode "Wi-Fi and home mobile data", not abroad, no limit) never has one.
 */
object DailyCaps {
    const val DEFAULT_MB = 10
    const val DEFAULT_PHOTOS = 30
    val MB_CHOICES = listOf(5, 10, 25, 50)
    val PHOTO_CHOICES = listOf(0, 10, 30, 100)
    private const val MB = 1024L * 1024L

    fun limitFor(s: DataSaverSettings, cost: NetworkCost): DailyLimit? {
        if (!NetworkCosts.isCostly(cost)) return null
        val roamingLike = s.abroad || cost == NetworkCost.ROAMING || cost == NetworkCost.UNKNOWN
        val abroadCap = if (roamingLike && s.effectiveMode == DataMode.ANYWHERE) DailyLimit(DEFAULT_MB * MB, DEFAULT_PHOTOS.toLong()) else null
        val own = if (s.dailyLimitOn) DailyLimit(s.limitMb.coerceAtLeast(0) * MB, s.limitPhotos.coerceAtLeast(0).toLong()) else null
        return when {
            abroadCap != null && own != null -> DailyLimit(minOf(abroadCap.bytes, own.bytes), minOf(abroadCap.photos, own.photos))
            else -> abroadCap ?: own
        }
    }

    /** True when [kind] may not start: the byte limit stops photos and big jobs, the photo limit stops photos. Text (SMALL) is never stopped by a cap. A call that starts under the limit may end a little over it. */
    fun blocks(kind: DataKind, limit: DailyLimit?, usage: DayUsage): Boolean = limit != null && when (kind) {
        DataKind.SMALL -> false
        DataKind.BULK_OTHER -> usage.bytes >= limit.bytes
        DataKind.PHOTO -> usage.bytes >= limit.bytes || usage.photos >= limit.photos
    }

    /** The status-line flag: today's limit (if any applies right now) is used up. */
    fun reached(limit: DailyLimit?, usage: DayUsage): Boolean = blocks(DataKind.PHOTO, limit, usage)
}

/**
 * Marks a USER-class photo call the user said "Send over mobile data once" to. Put on the coroutine with `withContext`; the router honours it only for [com.rokid.context.model.CallClass.USER]
 * (checked there) and for one call chain; it never persists.
 */
object SendOnce : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    object Key : kotlin.coroutines.CoroutineContext.Key<SendOnce>
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
    const val R_DAILY_LIMIT = "daily_limit"
    const val R_SEND_ONCE = "send_once"

    /**
     * The one decision. [transport] is only used to tell whether a LAN endpoint is reachable for free (Wi-Fi or Ethernet).
     * Offline is allowed here: nothing can leave, and the callers' existing offline path (no attempt used, park, resume) handles it.
     */
    fun decide(kind: DataKind, reach: EndpointReach, cost: NetworkCost, transport: Transport, s: DataSaverSettings, usage: DayUsage = DayUsage.ZERO, sendOnce: Boolean = false): DataDecision {
        if (reach == EndpointReach.DEVICE) return DataDecision(true, R_ON_DEVICE)
        if (reach == EndpointReach.LAN && (transport == Transport.WIFI || transport == Transport.ETHERNET)) return DataDecision(true, R_LAN)
        if (cost == NetworkCost.FREE) return DataDecision(true, R_FREE)
        if (cost == NetworkCost.OFFLINE) return DataDecision(true, R_OFFLINE)
        val blockedToken = when (cost) { NetworkCost.UNKNOWN -> R_UNKNOWN; NetworkCost.ROAMING -> R_ROAMING; NetworkCost.RESTRICTED -> R_ANDROID_SAVER; else -> R_HOME_MOBILE }
        return when (kind) {
            DataKind.SMALL ->
                if (cost == NetworkCost.HOME_MOBILE || cost == NetworkCost.RESTRICTED || s.smallOnRoaming) DataDecision(true, R_SMALL_OK) else DataDecision(false, blockedToken)
            DataKind.PHOTO, DataKind.BULK_OTHER -> {
                val byMode = if (cost == NetworkCost.RESTRICTED) DataDecision(false, R_ANDROID_SAVER) // Android itself blocks background data: no mode can change that
                else when (s.effectiveMode) {
                    DataMode.ANYWHERE -> DataDecision(true, R_ANYWHERE)
                    DataMode.WIFI_AND_HOME_MOBILE -> if (cost == NetworkCost.HOME_MOBILE) DataDecision(true, R_HOME_MOBILE) else DataDecision(false, blockedToken)
                    DataMode.WIFI_ONLY -> DataDecision(false, if (cost == NetworkCost.HOME_MOBILE) R_WIFI_ONLY else blockedToken)
                }
                when {
                    // the user's own tap ("Send over mobile data once", USER-class photo calls only: the router checks that): it goes whatever the mode or today's limit says
                    !byMode.allowed && sendOnce && kind == DataKind.PHOTO -> DataDecision(true, R_SEND_ONCE)
                    !byMode.allowed -> byMode
                    sendOnce && kind == DataKind.PHOTO -> if (DailyCaps.blocks(kind, DailyCaps.limitFor(s, cost), usage)) DataDecision(true, R_SEND_ONCE) else byMode
                    DailyCaps.blocks(kind, DailyCaps.limitFor(s, cost), usage) -> DataDecision(false, R_DAILY_LIMIT)
                    else -> byMode
                }
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
    /** The decision for a USER-class photo call the user said "Send over mobile data once" to (see [SendOnce]). Default: the plain decision. */
    fun decideOnce(kind: DataKind, reach: EndpointReach = EndpointReach.REMOTE): DataDecision = decide(kind, reach)
    /**
     * Slice 2 byte counter: a provider call that went out. Bytes are payload (request body and reply text, no TLS or headers), counted only while the network is costly (mobile, roaming, unknown);
     * [photos] = photos the call carried. Counts only, never content. Default no-op.
     */
    fun sent(kind: DataKind, txBytes: Long, rxBytes: Long, photos: Int) {}
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

/**
 * Slice 2: what was sent over costly networks today, as numbers only (bytes per kind, photos), keyed by the LOCAL date. Same day rule as [SkippedLedger]: a later date starts new counters,
 * an EARLIER date than the stored one (the clock was set back) keeps counting into the stored day (never a free reset). Wi-Fi traffic is not counted here.
 */
class MobileDataLedger(private val store: CounterStore, private val epochDay: () -> Long) {
    data class Today(val photoBytes: Long, val bulkBytes: Long, val smallBytes: Long, val photos: Long) {
        val bytes: Long get() = photoBytes + bulkBytes + smallBytes
        val usage: DayUsage get() = DayUsage(bytes, photos)
    }

    private fun key(k: DataKind) = "mobile_bytes_" + k.name.lowercase()

    /** Adds one call. Returns today's totals after it. */
    @Synchronized fun add(kind: DataKind, bytes: Long, photos: Int): Today {
        val stored = store.getLong(DAY, -1)
        val today = epochDay()
        val day = if (stored < 0 || today > stored) today else stored
        val fresh = day != stored
        fun base(k: String) = if (fresh) 0L else store.getLong(k, 0)
        val values = mutableMapOf(DAY to day)
        DataKind.entries.forEach { values[key(it)] = base(key(it)) }
        values[PHOTOS] = base(PHOTOS)
        values[key(kind)] = values.getValue(key(kind)) + bytes.coerceAtLeast(0)
        values[PHOTOS] = values.getValue(PHOTOS) + photos.coerceAtLeast(0)
        store.putLongs(values)
        return read()
    }

    @Synchronized fun today(): Today {
        val stored = store.getLong(DAY, -1)
        if (stored < 0 || epochDay() > stored) return Today(0, 0, 0, 0)
        return read()
    }

    private fun read(): Today = Today(store.getLong(key(DataKind.PHOTO), 0), store.getLong(key(DataKind.BULK_OTHER), 0), store.getLong(key(DataKind.SMALL), 0), store.getLong(PHOTOS, 0))

    /** True the first time it is called on a day (the "limit reached" log happens once a day). Follows the same day rule. */
    @Synchronized fun firstNoticeToday(): Boolean {
        val stored = store.getLong(DAY, -1)
        val today = epochDay()
        val day = if (stored < 0 || today > stored) today else stored
        if (store.getLong(NOTICE_DAY, -1) >= day) return false // given today (or on a later day: the clock was set back)
        store.putLongs(mapOf(NOTICE_DAY to day))
        return true
    }

    companion object { const val DAY = "mobile_day"; const val PHOTOS = "mobile_photos"; const val NOTICE_DAY = "mobile_notice_day" }
}
