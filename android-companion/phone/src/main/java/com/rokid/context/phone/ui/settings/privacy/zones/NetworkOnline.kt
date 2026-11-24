package com.rokid.context.phone.ui.settings.privacy.zones

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Online = the default network can reach the internet (NET_CAPABILITY_INTERNET). Null capabilities (no default network) = offline. A pure rule so it is testable without the platform. */
internal fun hasInternet(caps: NetworkCapabilities?): Boolean = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

/**
 * The same default-network callback the app's other network watchers use ([com.rokid.context.ai.datasaver.NetworkCostMonitor]), as a flow of "online or not".
 * The first value comes from `activeNetwork`; the callback is unregistered when the collector goes away. If the service is missing or registering fails the flow says offline (fail closed for edits).
 */
fun networkOnlineFlow(app: Context): Flow<Boolean> = callbackFlow {
    val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    if (cm == null) { trySend(false); awaitClose { }; return@callbackFlow }
    fun now(): Boolean = runCatching { hasInternet(cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }) }.getOrDefault(false)
    var current: Network? = null
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { current = network; trySend(hasInternet(caps)) }
        override fun onLost(network: Network) { if (network == current) { current = null; trySend(false) } }
    }
    trySend(now())
    val registered = runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess
    if (!registered) trySend(false)
    awaitClose { if (registered) runCatching { cm.unregisterNetworkCallback(cb) } }
}.distinctUntilChanged()
