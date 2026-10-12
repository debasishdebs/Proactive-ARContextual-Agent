package com.rokid.context.ai

import com.rokid.context.memory.db.RoleBindingDao
import com.rokid.context.memory.db.RoleBindingEntity

class RoleBindingRepository(private val dao: RoleBindingDao, private val endpoints: EndpointRepository) {
    suspend fun get(role: Role): RoleBinding? = dao.byRole(role.name)?.let {
        RoleBinding(role, it.endpointId, it.model, it.temperature, it.maxTokens, it.fallbackEndpointId, it.fallbackModel)
    }

    suspend fun eligibleEndpoints(role: Role): List<Endpoint> = endpoints.all().filter { role.requiredCapability in it.capabilities }

    suspend fun clear(role: Role) = dao.delete(role.name)

    suspend fun set(binding: RoleBinding): Result<Unit> = runCatching {
        val eligible = eligibleEndpoints(binding.role).map { it.id }.toSet()
        require(binding.endpointId in eligible) { "That endpoint can't do ${binding.role.requiredCapability.name.lowercase()}" }
        binding.fallbackEndpointId?.let { require(it in eligible) { "Fallback endpoint can't do ${binding.role.requiredCapability.name.lowercase()}" } }
        dao.upsert(RoleBindingEntity(binding.role.name, binding.endpointId, binding.model, binding.temperature, binding.maxTokens, binding.fallbackEndpointId, binding.fallbackModel))
    }
}
