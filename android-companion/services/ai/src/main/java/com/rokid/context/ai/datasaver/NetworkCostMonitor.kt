package com.rokid.context.ai.datasaver

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.net.NetSnapshot
import com.rokid.context.core.net.Transport

/** The capability bits of one network, as plain values so the mapping is testable without the platform. */
data class RawCaps(
    val wifi: Boolean, val ethernet: Boolean, val cellular: Boolean, val vpn: Boolean,
    val validated: Boolean, val notMetered: Boolean, val notRoaming: Boolean,
)

/**
 * Keeps the latest [NetSnapshot] of the default network (the same callback DiagProbe uses for its log line). Every failure to read is "unknown", never a guess: a missing
 * service, a SecurityException, no first reading yet, or a transport we cannot place all give readings the policy treats as costly.
 * [onChange] runs after every new reading (callback thread); it must be cheap.
 *
 * VPN rule (UNVERIFIED on a device): a VPN's own metered / validated / roaming bits are never trusted for "free". The VPN network's [NetworkCapabilities.getUnderlyingNetworks] (API 31+) says what it
 * really rides on; the costliest of those decides ([resolveVpn]). When they cannot be read (Android 10-11, none declared, any failure) the network is UNKNOWN, which is treated as costly.
 */
class NetworkCostMonitor(private val app: Context, private val clock: () -> Long = System::currentTimeMillis, private val onChange: () -> Unit) {
    @Volatile var snapshot: NetSnapshot = NetSnapshot.UNKNOWN
        private set
    @Volatile private var current: Network? = null
    @Volatile private var currentCaps: NetworkCapabilities? = null
    private var started = false
    private var failedBefore = false

    /**
     * Registers the default-network callback. Safe to call again: a registration that threw (no service, a SecurityException) is retried by every [reread] (each settings or network change and the 30-minute recheck),
     * so UNKNOWN never lasts the whole process; the first success after a failure leaves a milestone line.
     */
    @Synchronized fun start() {
        if (started) return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { current = network; currentCaps = caps; update(read(cm, caps)) }
                override fun onLost(network: Network) {
                    // A lost OLD default network arriving after the new one is already current must not turn the phone "offline".
                    if (network == current) { current = null; currentCaps = null; update(read(cm, null)) }
                }
            })
            started = true
            if (failedBefore) PcLog.milestone(TAG, "network watch started after an earlier failure")
            // No active network at start is "not known yet" (the callback may still deliver one), never "offline": photos stay blocked until a reading says otherwise.
            val active = runCatching { cm.activeNetwork }.getOrNull()
            val caps = active?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
            if (active != null && caps != null) { current = active; currentCaps = caps; update(read(cm, caps)) }
        } catch (e: Exception) {
            failedBefore = true
            PcLog.w(TAG, "network watch unavailable: ${e.javaClass.simpleName}") // snapshot stays unknown: fail closed; retried by the next reread
        }
    }

    /** Retries a failed start, and re-reads the facts that change without a capability callback (Android Data Saver status, SIM and network country) for the current default network. */
    fun reread() {
        if (!started) { start(); return }
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val caps = currentCaps ?: return // offline or not known yet: nothing to re-read
        update(read(cm, caps))
    }

    private fun update(s: NetSnapshot) {
        val old = snapshot
        // Keep the moment an unvalidated reading started; any validated or different-network reading clears it.
        val stamped = if (!s.validated && s.known && s.transport != Transport.NONE) s.copy(unvalidatedSinceMs = if (old.unvalidatedSinceMs > 0 && !old.validated && old.transport == s.transport) old.unvalidatedSinceMs else clock()) else s
        snapshot = stamped
        if (stamped != old) try { onChange() } catch (e: Exception) { PcLog.w(TAG, "network change hook failed: ${e.javaClass.simpleName}") }
    }

    /** [caps] null = the default network was lost (offline), which is a known state once the callback said so. */
    private fun read(cm: ConnectivityManager, caps: NetworkCapabilities?): NetSnapshot {
        if (caps == null) return NetSnapshot(known = true, transport = Transport.NONE, validated = false, notMetered = false, notRoaming = true)
        val raw = raw(caps)
        if (!raw.vpn) return snapshotOf(raw, raw, countryMismatch(), restrictBackground(cm))
        // A VPN's own bits are the VPN's (it may declare itself unmetered and validated whatever it rides on): only the networks it REALLY runs over count. Not provable = unknown = costly.
        val effective = resolveVpn(underlyingOf(cm, caps)) ?: return NetSnapshot(true, Transport.OTHER, raw.validated, notMetered = false, notRoaming = false, countryMismatch = countryMismatch(), restrictBackground = restrictBackground(cm))
        return snapshotOf(effective, raw, countryMismatch(), restrictBackground(cm))
    }

    private fun raw(c: NetworkCapabilities) = RawCaps(
        wifi = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), ethernet = c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        cellular = c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR), vpn = c.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        validated = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), notMetered = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        notRoaming = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
    )

    /**
     * The capabilities of the networks this VPN actually runs over, from the VPN network's own [NetworkCapabilities.getUnderlyingNetworks] (a system API since Android 12, read by reflection). Null = cannot be known: Android 10-11, the platform refusing the hidden call, a VPN that
     * declares none (it then follows the system default, which is not provable), or any failure. We never guess from "some Wi-Fi is connected": that network may not carry the VPN.
     */
    private fun underlyingOf(cm: ConnectivityManager, vpnCaps: NetworkCapabilities): List<RawCaps>? = try {
        // getUnderlyingNetworks() is not in the public SDK (a system API since Android 12): read it by reflection where the platform lets us, and treat any refusal as "not known".
        val nets = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) null
        else NetworkCapabilities::class.java.getMethod("getUnderlyingNetworks").invoke(vpnCaps) as? List<*>
        nets?.map { n -> raw(cm.getNetworkCapabilities(n as Network) ?: return null) }
    } catch (e: Throwable) { null }

    /** SIM country vs network country. Only the boolean leaves this function; both empty (no SIM, airplane mode) is "no mismatch". */
    private fun countryMismatch(): Boolean = try {
        val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sim = tm?.simCountryIso.orEmpty(); val net = tm?.networkCountryIso.orEmpty()
        sim.isNotEmpty() && net.isNotEmpty() && !sim.equals(net, ignoreCase = true)
    } catch (e: Exception) { false }

    private fun restrictBackground(cm: ConnectivityManager): Boolean = try {
        cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    } catch (e: Exception) { false }

    companion object {
        private const val TAG = "PcData"

        /**
         * The reading to trust for a VPN, from the capabilities of its real underlying networks ([underlying]; null or empty = not known). Null = classify UNKNOWN (costly). With several, the COSTLIEST wins
         * (a VPN that may switch between Wi-Fi and mobile data is as expensive as mobile data): mobile data, then anything not provably free, then validated unmetered non-roaming Wi-Fi or Ethernet.
         * A nested VPN, or a network that is neither Wi-Fi, Ethernet nor mobile, cannot be placed: unknown.
         */
        fun resolveVpn(underlying: List<RawCaps>?): RawCaps? {
            if (underlying.isNullOrEmpty()) return null
            if (underlying.any { it.vpn || !(it.wifi || it.ethernet || it.cellular) }) return null
            fun free(c: RawCaps) = (c.wifi || c.ethernet) && !c.cellular && c.validated && c.notMetered && c.notRoaming
            return underlying.firstOrNull { it.cellular } ?: underlying.firstOrNull { !free(it) } ?: underlying.first()
        }

        /**
         * The pure mapping. [effective] carries the validated / metered / roaming bits to trust (the real Wi-Fi under a VPN, else the default network itself); [defaultCaps] decides the transport.
         * A VPN whose default reading names no transport at all is OTHER (unknown).
         */
        fun snapshotOf(effective: RawCaps, defaultCaps: RawCaps, countryMismatch: Boolean, restrictBackground: Boolean): NetSnapshot {
            val transport = when {
                effective.wifi -> Transport.WIFI
                effective.ethernet -> Transport.ETHERNET
                effective.cellular -> Transport.CELLULAR
                defaultCaps.wifi -> Transport.WIFI
                defaultCaps.ethernet -> Transport.ETHERNET
                defaultCaps.cellular -> Transport.CELLULAR
                else -> Transport.OTHER
            }
            return NetSnapshot(true, transport, effective.validated, effective.notMetered, effective.notRoaming, countryMismatch, restrictBackground)
        }
    }
}
