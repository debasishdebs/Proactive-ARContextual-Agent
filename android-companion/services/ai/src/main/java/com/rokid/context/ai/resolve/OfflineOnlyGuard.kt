package com.rokid.context.ai

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.net.ModelPrivacyPolicy

/**
 * The one fail-closed "Offline only" check, shared by every place that can put a request on a remote wire (the router, the embedding client, the endpoint test). [mode] is read at the moment of the
 * call; a reader that throws counts as [ModelPrivacy.OFFLINE_ONLY]. The endpoint's address is only classified ([EndpointReaches]); it is never logged.
 */
object OfflineOnlyGuard {
    /** Throws [OfflineOnlyRefusedException] when [mode] is OFFLINE_ONLY and [e] is a remote endpoint. Call it BEFORE the key is read and before any provider or request is built. */
    fun require(mode: () -> ModelPrivacy, e: Endpoint) {
        val m = try { mode() } catch (t: Exception) { ModelPrivacy.OFFLINE_ONLY }
        // A LAN address behind a VPN may be a remote host: only an endpoint on this phone (loopback or the on-device model) passes while one is up. Checked before the connection (and the key) exist.
        if (ModelPrivacyPolicy.allows(m, EndpointReaches.of(e)) && (m == ModelPrivacy.AUTO || !OfflineOnlyNet.vpnNow() || EndpointReaches.of(e) == com.rokid.context.core.net.EndpointReach.DEVICE || EndpointReaches.isLoopback(e))) return
        PcLog.i("OfflineOnly", "offline_only_refused")
        throw OfflineOnlyRefusedException()
    }
}
