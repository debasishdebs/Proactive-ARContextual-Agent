package com.rokid.context.ai

import com.rokid.context.memory.db.RoleBindingDao
import com.rokid.context.memory.db.RoleBindingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoleBindingRepository(private val dao: RoleBindingDao, private val endpoints: EndpointRepository) {
    suspend fun get(role: Role): RoleBinding? = dao.byRole(role.name)?.toDomain()

    fun observeAll(): Flow<List<RoleBinding>> = dao.observeAll().map { list -> list.mapNotNull { it.toDomain() } }

    /** Roles whose primary or fallback is [endpointId] (blocks deleting an endpoint that is still in use). */
    suspend fun usages(endpointId: String): List<Role> =
        dao.all().filter { it.endpointId == endpointId || it.fallbackEndpointId == endpointId }.mapNotNull { it.toDomain()?.role }

    private fun RoleBindingEntity.toDomain(): RoleBinding? = runCatching { Role.valueOf(role) }.getOrNull()
        ?.let { RoleBinding(it, endpointId, model, temperature, maxTokens, fallbackEndpointId, fallbackModel) }

    suspend fun eligibleEndpoints(role: Role): List<Endpoint> = endpoints.all().filter { role.requiredCapability in it.capabilities }

    suspend fun clear(role: Role) = dao.delete(role.name)

    suspend fun set(binding: RoleBinding): Result<Unit> = runCatching {
        val eligible = eligibleEndpoints(binding.role).map { it.id }.toSet()
        require(binding.endpointId in eligible) { "That endpoint can't do ${binding.role.requiredCapability.name.lowercase()}" }
        binding.fallbackEndpointId?.let { require(it in eligible) { "Fallback endpoint can't do ${binding.role.requiredCapability.name.lowercase()}" } }
        dao.upsert(RoleBindingEntity(binding.role.name, binding.endpointId, binding.model, binding.temperature, binding.maxTokens, binding.fallbackEndpointId, binding.fallbackModel))
    }
}
