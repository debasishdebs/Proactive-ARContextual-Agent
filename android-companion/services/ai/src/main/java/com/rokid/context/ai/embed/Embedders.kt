package com.rokid.context.ai.embed

import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.EndpointRepository
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBindingRepository
import com.rokid.context.ai.UrlNormalizer
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.SpaceEmbedderProvider
import com.rokid.context.memory.db.EmbeddingSpaceEntity

/** Turns the EMBED binding (or a stored space's config) into an [EndpointEmbedder]. */
class Embedders(
    private val endpoints: EndpointRepository,
    private val bindings: RoleBindingRepository,
    private val client: EmbeddingClient = EmbeddingClient(),
    /** Gaps G6: the phone's power, for the on-device (LLAMACPP_LOCAL) gate. */
    private val power: () -> PowerState = { PowerState.UNGATED },
) : SpaceEmbedderProvider {
    /** Whether the EMBED binding is the on-device model (no network, no probe): the reindex battery gate. */
    suspend fun bindingIsLocal(): Boolean {
        val b = bindings.get(Role.EMBED) ?: return false
        return endpoints.byId(b.endpointId)?.kind == EndpointKind.LLAMACPP_LOCAL
    }

    /** The EMBED binding as a full spec (one probe call for the dimension). null when unbound; network errors propagate. */
    suspend fun forBinding(): EndpointEmbedder? {
        val b = bindings.get(Role.EMBED) ?: return null
        val e = endpoints.byId(b.endpointId) ?: return null
        // Gaps G6: even the probe is an on-device model run.
        if (e.kind == EndpointKind.LLAMACPP_LOCAL && !LocalModelPower.bulkAllowed(power())) throw LocalModelPausedException()
        val key = endpoints.keyFor(e)
        val revision = client.revision(e, b.model) // a failed lookup is an error, never a silently different fingerprint
        val (doc, query) = PrefixRules.forModel(b.model)
        val probe = client.embed(e, b.model, key, listOf(query + Canaries.TEXTS[0])).single()
        val spec = EmbeddingSpec(e.id, e.kind, e.baseUrl, e.apiVersion, b.model, revision, null, probe.size, true, doc, query, MAX_INPUT_CHARS)
        return EndpointEmbedder(spec, e, key, client)
    }

    /** [e] as bulk work may use it: gated by battery when it is the on-device model (Gaps G6). */
    fun gated(e: EndpointEmbedder): Embedder = if (e.spec.kind == EndpointKind.LLAMACPP_LOCAL) PowerGatedEmbedder(e, power) else e

    /**
     * Serves the space only while the endpoint still points where the space was built (same kind, same normalised
     * address): after a move this returns null, so retrieval falls back to lexical and no text reaches the old host.
     * null too if the endpoint is gone.
     */
    override suspend fun forSpace(space: EmbeddingSpaceEntity): Embedder? {
        val spec = EmbeddingSpec.fromConfig(space.configJson) ?: return null
        val e = endpoints.byId(spec.endpointId) ?: return null
        if (!sameTarget(e.kind, e.baseUrl, spec.kind, spec.baseUrl)) return null
        return gated(EndpointEmbedder(spec, e.copy(apiVersion = spec.apiVersion), endpoints.keyFor(e), client))
    }

    private companion object {
        const val MAX_INPUT_CHARS = 2_000
        fun norm(url: String, kind: EndpointKind) = UrlNormalizer.normalize(url, kind).getOrNull() ?: url.trim().trimEnd('/')
        fun sameTarget(k1: EndpointKind, u1: String, k2: EndpointKind, u2: String) = k1 == k2 && norm(u1, k1) == norm(u2, k2)
    }
}
