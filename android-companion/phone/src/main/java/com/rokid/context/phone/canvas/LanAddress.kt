package com.rokid.context.phone.canvas

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** The phone's current Wi-Fi IPv4 address, or null when it is not on Wi-Fi. */
object LanAddress {
    fun wifiIpv4(context: Context): String? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.allNetworks.orEmpty().firstNotNullOfOrNull { net ->
            val caps = cm?.getNetworkCapabilities(net) ?: return@firstNotNullOfOrNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstNotNullOfOrNull null
            cm.getLinkProperties(net)?.linkAddresses?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
        }
    } catch (e: Exception) { null }
}
