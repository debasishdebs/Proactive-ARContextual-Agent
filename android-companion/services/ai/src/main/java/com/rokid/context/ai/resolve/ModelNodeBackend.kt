package com.rokid.context.ai.resolve

import com.rokid.context.core.net.Backend
import com.rokid.context.core.net.ModelPrivacy

/**
 * Canvas v2.2: the node's own `backend` choice (auto | online | offline) INTERSECTED with the privacy setting. PURE. The result is a fixed refusal token (logged and shown) or null (go on). Layer one runs at
 * estimate, layer two again at call time inside the executor; the router independently refuses a remote endpoint under OFFLINE_ONLY (layer three, unchanged).
 */
enum class NodeBackendPref(val token: String) {
    AUTO("auto"), ONLINE("online"), OFFLINE("offline");
    companion object { fun parse(t: String?): NodeBackendPref = entries.firstOrNull { it.token == t } ?: AUTO }
}

object ModelNodeBackend {
    const val OFFLINE_ONLY = "offline_only"
    const val NO_LOCAL_MODEL = "offline_only_no_local_model"
    const val NOT_BOUND = "not_bound"
    const val MISMATCH = "backend_mismatch"

    /** [bound] is the kind of the endpoint the node's role is bound to now (null = none, or unreadable: fail closed). */
    fun check(pref: NodeBackendPref, privacy: ModelPrivacy, bound: Backend?): String? {
        if (bound == null) return NOT_BOUND
        if (privacy == ModelPrivacy.OFFLINE_ONLY && bound == Backend.ONLINE) return if (pref == NodeBackendPref.OFFLINE) NO_LOCAL_MODEL else OFFLINE_ONLY
        if (pref == NodeBackendPref.ONLINE && bound == Backend.OFFLINE) return MISMATCH
        if (pref == NodeBackendPref.OFFLINE && bound == Backend.ONLINE) return NO_LOCAL_MODEL
        return null
    }
}
