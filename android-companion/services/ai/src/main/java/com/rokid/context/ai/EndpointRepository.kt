package com.rokid.context.ai

import com.rokid.context.memory.db.EndpointDao
import com.rokid.context.memory.db.EndpointEntity

class EndpointRepository(private val dao: EndpointDao, private val vault: KeyVault) {
    /** [apiKey] null = keep the stored key; blank = remove it (e.g. Ollama without auth). */
    suspend fun save(endpoint: Endpoint, apiKey: String?): Result<Endpoint> = runCatching {
        val key = apiKey?.takeIf { it.isNotBlank() }?.let { UrlNormalizer.cleanKey(it).getOrThrow() }
        val willHaveKey = key != null || (apiKey == null && vault.get(keyRef(endpoint.id)) != null)
        val clean = EndpointRules.clean(endpoint, willHaveKey).getOrThrow()
        dao.upsert(clean.toEntity())
        when {
            key != null -> vault.put(keyRef(clean.id), key)
            apiKey != null -> vault.remove(keyRef(clean.id))
        }
        clean
    }

    suspend fun all(): List<Endpoint> = dao.all().map { it.toDomain() }
    suspend fun byId(id: String): Endpoint? = dao.byId(id)?.toDomain()
    fun keyFor(endpoint: Endpoint): String? = vault.get(keyRef(endpoint.id))
    suspend fun delete(id: String) { dao.delete(id); vault.remove(keyRef(id)) }

    companion object {
        fun keyRef(id: String) = "key:$id"

        private fun Endpoint.toEntity() = EndpointEntity(
            id, name, kind.name, baseUrl, apiVersion, models.joinToString(","), keyRef(id), capabilities.joinToString(",") { it.name }, createdAt,
        )

        private fun EndpointEntity.toDomain() = Endpoint(
            id = id, name = name, kind = EndpointKind.valueOf(kind), baseUrl = baseUrl, apiVersion = apiVersion,
            models = modelsCsv.split(',').filter { it.isNotBlank() },
            capabilities = capabilitiesCsv.split(',').filter { it.isNotBlank() }.mapNotNull { runCatching { Capability.valueOf(it) }.getOrNull() }.toSet(),
            createdAt = createdAt,
        )
    }
}
