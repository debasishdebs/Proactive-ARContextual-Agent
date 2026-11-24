package com.rokid.context.ai

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.net.ModelPrivacy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import java.util.WeakHashMap

/**
 * The connection half of "Offline only", beside [OfflineOnlyDns] (which only sees host NAMES). Three things the name check cannot see:
 *  1. a system (Wi-Fi or global) HTTP/SOCKS proxy: OkHttp would send the request to the PROXY. [OfflineOnlySelector] returns NO_PROXY while the mode is OFFLINE_ONLY.
 *  2. a connection that is not what the address said: [Guard] is a NETWORK interceptor, run after the connection is chosen and before the request is written. Under OFFLINE_ONLY it refuses unless the route is
 *     DIRECT and the socket address it connected to is on this phone or the user's own network. That also covers a pooled keep-alive connection opened under AUTO (a name that resolved to a public address),
 *     which is reused without any new DNS lookup.
 *  3. pooled connections themselves: [evictPools] closes the idle ones of every model client when the mode is set.
 * The mode is read at every call; a reader that throws counts as OFFLINE_ONLY. Nothing here logs an address.
 */
object OfflineOnlyNet {
    private fun modeNow(m: () -> ModelPrivacy): ModelPrivacy = try { m() } catch (e: Exception) { ModelPrivacy.OFFLINE_ONLY }

    class OfflineOnlySelector(private val mode: () -> ModelPrivacy = { OfflineOnlyDns.globalMode() }, private val delegate: () -> ProxySelector? = { ProxySelector.getDefault() }) : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> =
            if (modeNow(mode) != ModelPrivacy.AUTO) listOf(Proxy.NO_PROXY) else delegate()?.select(uri) ?: listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) { try { delegate()?.connectFailed(uri, sa, ioe) } catch (_: Exception) { } }
    }

    /** Refuses (typed, content-free) a connection that is not direct to an own-network address while [mode] is not AUTO. */
    class Guard(private val mode: () -> ModelPrivacy = { OfflineOnlyDns.globalMode() }) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            check(modeNow(mode), chain.connection()?.route())
            return chain.proceed(chain.request())
        }
    }

    /**
     * Whether a VPN network is up on this phone (any network with TRANSPORT_VPN, which covers the active one and an always-on VPN that captures the private ranges). A private address behind a VPN can be a REMOTE host
     * (a tunnel to another site), so while this is true Offline only allows only addresses on this phone itself (loopback). Set once by the app ([androidVpnProbe]); false until then (unit tests, the JVM). A probe that
     * throws counts as "VPN up" (fail closed). There is no "allow VPN LAN" option: it is refused and documented (PRIVACY.md); one can be added later as an explicit user choice.
     */
    @Volatile var vpnProbe: () -> Boolean = { false }
    fun vpnNow(): Boolean = try { vpnProbe() } catch (e: Exception) { true }

    /** The production probe: true when any known network has the VPN transport. */
    @Suppress("DEPRECATION")
    fun androidVpnProbe(app: android.content.Context): () -> Boolean = {
        val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    /**
     * Pure rule (testable with a fake route): AUTO passes; otherwise the route must exist, be DIRECT, and its connected address must be [OfflineOnlyDns.isOwnNetwork] AND, while a VPN is up ([vpn]), loopback.
     */
    fun check(m: ModelPrivacy, route: Route?, vpn: Boolean = vpnNow()) {
        if (m == ModelPrivacy.AUTO) return
        val sa: InetSocketAddress? = route?.socketAddress
        val own = route != null && route.proxy.type() == Proxy.Type.DIRECT && sa != null && !sa.isUnresolved && sa.address != null && OfflineOnlyDns.isOwnNetwork(sa.address)
        if (!own) {
            PcLog.i("OfflineOnly", "offline_only_refused_route")
            throw OfflineOnlyRefusedException()
        }
        if (vpn && !sa!!.address.isLoopbackAddress) {
            PcLog.i("OfflineOnly", "offline_only_refused_vpn")
            throw OfflineOnlyRefusedException()
        }
    }

    val shared: Guard = Guard()
    val selector: ProxySelector = OfflineOnlySelector()

    private val clients: MutableSet<OkHttpClient> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    fun register(c: OkHttpClient): OkHttpClient { clients.add(c); return c }

    /** Closes the idle pooled connections of every model client (a connection opened under Automatic must not carry an Offline-only request; the Guard also refuses it if it is still in use). */
    fun evictPools() { val all = synchronized(clients) { clients.toList() }; all.forEach { try { it.connectionPool.evictAll() } catch (_: Exception) { } } }
}
