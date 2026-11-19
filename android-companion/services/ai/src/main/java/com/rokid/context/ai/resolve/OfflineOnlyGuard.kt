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
        if (ModelPrivacyPolicy.allows(m, EndpointReaches.of(e))) return
        PcLog.i("OfflineOnly", "offline_only_refused")
        throw OfflineOnlyRefusedException()
    }
}
