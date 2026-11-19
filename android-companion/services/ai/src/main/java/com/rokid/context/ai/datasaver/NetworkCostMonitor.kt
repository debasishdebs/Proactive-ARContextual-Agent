package com.rokid.context.ai.datasaver

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.net.NetSnapshot
import com.rokid.context.core.net.Transport

/**
 * Keeps the latest [NetSnapshot] of the default network (the same callback DiagProbe uses for its log line). Every failure to read is "unknown", never a guess: a missing
 * service, a SecurityException or a null capability set all give [NetSnapshot.UNKNOWN]-like readings that the policy treats as costly.
 * [onChange] runs after every new reading (callback thread); it must be cheap.
 */
class NetworkCostMonitor(private val app: Context, private val onChange: () -> Unit) {
    @Volatile var snapshot: NetSnapshot = NetSnapshot.UNKNOWN
        private set
    private var started = false

    @Synchronized fun start() {
        if (started) return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { update(read(caps)) }
                override fun onLost(network: Network) { update(read(null)) }
            })
            started = true
            update(read(runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()))
        } catch (e: Exception) {
            PcLog.w(TAG, "network watch unavailable: ${e.javaClass.simpleName}") // snapshot stays unknown: fail closed
        }
    }

    private fun update(s: NetSnapshot) {
        val changed = s != snapshot
        snapshot = s
        if (changed) try { onChange() } catch (e: Exception) { PcLog.w(TAG, "network change hook failed: ${e.javaClass.simpleName}") }
    }

    /** [caps] null = no default network (offline), which is a known state. */
    private fun read(caps: NetworkCapabilities?): NetSnapshot {
        if (caps == null) return NetSnapshot(known = true, transport = Transport.NONE, validated = false, notMetered = false, notRoaming = true)
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            else -> Transport.OTHER // VPN only, Bluetooth, anything else
        }
        return NetSnapshot(
            known = true, transport = transport,
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            notRoaming = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
            countryMismatch = countryMismatch(), restrictBackground = restrictBackground(),
        )
    }

    /** SIM country vs network country. Only the boolean leaves this function; both empty (no SIM, airplane mode) is "no mismatch". */
    private fun countryMismatch(): Boolean = try {
        val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sim = tm?.simCountryIso.orEmpty(); val net = tm?.networkCountryIso.orEmpty()
        sim.isNotEmpty() && net.isNotEmpty() && !sim.equals(net, ignoreCase = true)
    } catch (e: Exception) { false }

    private fun restrictBackground(): Boolean = try {
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    } catch (e: Exception) { false }

    private companion object { const val TAG = "PcData" }
}
