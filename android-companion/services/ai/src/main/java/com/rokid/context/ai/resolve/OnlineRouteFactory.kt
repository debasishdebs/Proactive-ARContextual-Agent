package com.rokid.context.ai.resolve

import com.rokid.context.ai.EndpointReaches
import com.rokid.context.ai.EndpointRepository
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBindingRepository

/** The ONLINE instance: a handle on the role whose binding the router will call. Nothing is held open; the router still builds the provider per call. */
data class OnlineRoute(val role: Role)

/**
 * The ONLINE backend of a resolver role: the binding of [role] called through `ModelRouter` (which keeps the image-dispatch guard, the data saver and the Offline-only refusal).
 * [bound] is always true on purpose: an unset or deleted binding is reported by the router at call time as the typed NOT_SET failure, exactly as before the resolver existed.
 * The label is `<reach>:<EndpointKind>` (for example `remote:AZURE_OPENAI`), never the endpoint's name, address or model string.
 */
class OnlineRouteFactory(private val role: Role, private val bindings: RoleBindingRepository, private val endpoints: EndpointRepository) : BackendFactory {
    override suspend fun bound(): Boolean = true

    private suspend fun endpointOf() = bindings.get(role)?.let { b -> endpoints.byId(b.endpointId)?.let { it to b } }

    override suspend fun fingerprint(): String =
        endpointOf()?.let { (e, b) -> "${e.id}|${e.kind.name}|${e.baseUrl}|${b.model}|${b.fallbackEndpointId}|${b.fallbackModel}" } ?: "unbound"

    override suspend fun label(): String = endpointOf()?.first?.let { "${EndpointReaches.of(it).name.lowercase()}:${it.kind.name}" } ?: "unbound"

    override suspend fun create(): Any = OnlineRoute(role)
}
