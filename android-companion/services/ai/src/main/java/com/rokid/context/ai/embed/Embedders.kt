package com.rokid.context.ai.embed

import com.rokid.context.ai.EndpointRepository
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBindingRepository
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.SpaceEmbedderProvider
import com.rokid.context.memory.db.EmbeddingSpaceEntity

/** Turns the EMBED binding (or a stored space's config) into an [EndpointEmbedder]. */
class Embedders(
    private val endpoints: EndpointRepository,
    private val bindings: RoleBindingRepository,
    private val client: EmbeddingClient = EmbeddingClient(),
) : SpaceEmbedderProvider {
    /** The EMBED binding as a full spec (one probe call for the dimension). null when unbound; network errors propagate. */
    suspend fun forBinding(): EndpointEmbedder? {
        val b = bindings.get(Role.EMBED) ?: return null
        val e = endpoints.byId(b.endpointId) ?: return null
        val key = endpoints.keyFor(e)
        val revision = client.revision(e, b.model) // a failed lookup is an error, never a silently different fingerprint
        val (doc, query) = PrefixRules.forModel(b.model)
        val probe = client.embed(e, b.model, key, listOf(query + Canaries.TEXTS[0])).single()
        val spec = EmbeddingSpec(e.id, e.kind, e.baseUrl, e.apiVersion, b.model, revision, null, probe.size, true, doc, query, MAX_INPUT_CHARS)
        return EndpointEmbedder(spec, e, key, client)
    }

    /** Uses the space's own config (address included), so an edited endpoint never mixes spaces; null if the endpoint is gone. */
    override suspend fun forSpace(space: EmbeddingSpaceEntity): Embedder? {
        val spec = EmbeddingSpec.fromConfig(space.configJson) ?: return null
        val e = endpoints.byId(spec.endpointId) ?: return null
        // The key belongs to the endpoint's current address: never send it to the space's older address.
        val sameTarget = e.kind == spec.kind && e.baseUrl.trimEnd('/') == spec.baseUrl.trimEnd('/')
        val key = if (sameTarget) endpoints.keyFor(e) else null
        return EndpointEmbedder(spec, e.copy(kind = spec.kind, baseUrl = spec.baseUrl, apiVersion = spec.apiVersion), key, client)
    }

    private companion object { const val MAX_INPUT_CHARS = 2_000 }
}
