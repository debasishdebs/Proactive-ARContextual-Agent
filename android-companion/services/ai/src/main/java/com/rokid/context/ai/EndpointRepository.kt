package com.rokid.context.ai

import com.rokid.context.memory.db.EndpointDao
import com.rokid.context.memory.db.EndpointEntity
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Endpoints live in Room; their keys in [vault] (the Keystore in the app), which is only ever reached on [io] (F18). */
class EndpointRepository(private val dao: EndpointDao, private val vault: KeyVault, private val io: CoroutineDispatcher = Dispatchers.IO) {
    /** Held by [save]'s key + row writes and by the whole of [sweepOrphanKeys] (fix round 1, I1). */
    private val writes = Mutex()

    /**
     * [apiKey] null = keep the stored key; blank = remove it (e.g. Ollama without auth).
     * A new key is written before the row (P8 D2): if the row write fails, the vault is put back as it was (the earlier
     * key restored, or the new one removed), best effort, so no key is left without its row. A removal runs after the
     * row, as before. [CancellationException] is rethrown.
     */
    suspend fun save(endpoint: Endpoint, apiKey: String?): Result<Endpoint> = try {
        val key = apiKey?.takeIf { it.isNotBlank() }?.let { UrlNormalizer.cleanKey(it).getOrThrow() }
        val previous = if (key != null || apiKey == null) withContext(io) { vault.get(keyRef(endpoint.id)) } else null
        val willHaveKey = key != null || (apiKey == null && previous != null)
        val clean = EndpointRules.clean(endpoint, willHaveKey).getOrThrow()
        // Fix round 1: [writes] keeps the orphan sweep out while a key is stored before its row; NonCancellable makes
        // put + upsert (+ rollback) one unit, so a cancellation can't leave a key without its row or a row without its key.
        writes.withLock {
            withContext(NonCancellable) {
                if (key != null) {
                    withContext(io) { vault.put(keyRef(clean.id), key) }
                    try {
                        dao.upsert(clean.toEntity())
                    } catch (e: Throwable) {
                        withContext(io) { restoreKey(clean.id, previous) }
                        throw e
                    }
                } else {
                    dao.upsert(clean.toEntity())
                    if (apiKey != null) withContext(io) { vault.remove(keyRef(clean.id)) }
                }
            }
        }
        Result.success(clean)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun restoreKey(id: String, previous: String?) {
        try {
            if (previous != null) vault.put(keyRef(id), previous) else vault.remove(keyRef(id))
        } catch (e: Exception) {
            Log.w(TAG, "Key rollback failed: ${e.javaClass.simpleName}")
        }
    }

    suspend fun all(): List<Endpoint> = dao.all().map { it.toDomain() }
    fun observeAll(): Flow<List<Endpoint>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    suspend fun byId(id: String): Endpoint? = dao.byId(id)?.toDomain()
    suspend fun keyFor(endpoint: Endpoint): String? = withContext(io) { vault.get(keyRef(endpoint.id)) }
    /**
     * Removes the key first, then the row (P8 D2): a crash in between leaves a keyless endpoint, never an orphan key.
     * A vault failure is logged and never thrown, so the row still goes (a left-over key is swept at the next start).
     */
    suspend fun delete(id: String) {
        try {
            withContext(io) { vault.remove(keyRef(id)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Key remove failed: ${e.javaClass.simpleName}")
        }
        dao.delete(id)
    }

    /** Removes every `key:<id>` with no endpoint row (a crash mid-save or mid-delete), off Main; returns how many went. */
    suspend fun sweepOrphanKeys(): Int = writes.withLock { withContext(io) {
        val live = dao.all().map { keyRef(it.id) }.toSet()
        vault.refs().filter { it.startsWith(KEY_PREFIX) && it !in live }.count { ref ->
            try {
                vault.remove(ref); true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Orphan key remove failed: ${e.javaClass.simpleName}"); false
            }
        }
    } }

    companion object {
        private const val TAG = "EndpointRepository"
        private const val KEY_PREFIX = "key:"
        fun keyRef(id: String) = "$KEY_PREFIX$id"

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
