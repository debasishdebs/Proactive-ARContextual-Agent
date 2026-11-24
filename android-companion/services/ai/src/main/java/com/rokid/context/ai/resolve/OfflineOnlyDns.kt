package com.rokid.context.ai

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.net.ModelPrivacy
import okhttp3.Dns
import java.net.InetAddress

/**
 * The name-resolution half of "Offline only". A host that LOOKS local (`box.local`) can still resolve to a public address (a hijacked NXDOMAIN, unicast DNS away from home), so under OFFLINE_ONLY every address a model
 * client resolves must be on this phone or on the user's own network (loopback, site-local, link-local, IPv6 unique-local); otherwise the request is refused with [OfflineOnlyRefusedException] before a connection is
 * made (fail closed: one public address among the answers is enough to refuse). Under AUTO it is the system resolver, unchanged. The mode is read at each lookup; a reader that throws counts as OFFLINE_ONLY.
 * IP literals never reach a [Dns] (OkHttp parses them); their classification is [com.rokid.context.core.net.DataPolicy.isLanHost]'s, checked by the router first.
 */
class OfflineOnlyDns(private val mode: () -> ModelPrivacy, private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val m = try { mode() } catch (e: Exception) { ModelPrivacy.OFFLINE_ONLY }
        if (m != ModelPrivacy.AUTO && refusedBeforeLookup(hostname)) {
            PcLog.i("OfflineOnly", "offline_only_refused_dns")
            throw OfflineOnlyRefusedException()
        }
        val all = delegate.lookup(hostname)
        if (m == ModelPrivacy.AUTO) return all
        if (all.isEmpty() || !all.all(::isOwnNetwork)) {
            PcLog.i("OfflineOnly", "offline_only_refused_dns")
            throw OfflineOnlyRefusedException()
        }
        return all
    }

    /**
     * Names refused WITHOUT being looked up (a lookup would send the NAME to the system's unicast resolver, which may be a remote one): a `.local` name (the system resolver does not do mDNS for it, so the only
     * thing a lookup can do is ask unicast DNS), and any name other than `localhost` while a VPN is up (see [OfflineOnlyNet.vpnNow]). A LAN model is then addressed by its IP address. Fail closed, documented in PRIVACY.md.
     */
    private fun refusedBeforeLookup(hostname: String): Boolean {
        val h = hostname.trim().lowercase().trimEnd('.')
        if (h == "localhost") return false
        return h.endsWith(".local") || OfflineOnlyNet.vpnNow()
    }

    companion object {
        /** The mode every model client's resolver reads (set once by the app graph; AUTO until then, like every other default). */
        @Volatile var globalMode: () -> ModelPrivacy = { ModelPrivacy.AUTO }
        val shared: Dns = OfflineOnlyDns({ globalMode() })

        fun isOwnNetwork(a: InetAddress): Boolean {
            if (a.isAnyLocalAddress || a.isMulticastAddress) return false
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
            val b = a.address
            return b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC // fc00::/7 unique-local
        }
    }
}
