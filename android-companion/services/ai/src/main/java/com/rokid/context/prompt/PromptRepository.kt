package com.rokid.context.prompt

import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.db.PromptDao
import com.rokid.context.memory.db.PromptVersionEntity
import kotlinx.coroutines.flow.Flow

data class PromptDraft(val template: String, val outputSchema: String?)

/**
 * spec §4.3 prompt store. Versioning on: Save appends v(N+1) and activates it. Off: Save rewrites the active version in
 * place and older versions stay read-only. Restore copies an old version through the same rule.
 */
class PromptRepository(
    private val dao: PromptDao,
    private val seeds: PromptSeeds,
    val settings: PromptSettings,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun ensureSeeded() { PromptKey.entries.forEach { seed(it) } }

    private suspend fun seed(key: PromptKey) = dao.seedIfAbsent(key.name, seeds.template(key), DefaultSchemas.text(key), now())

    suspend fun active(key: PromptKey): PromptVersionEntity = dao.active(key.name) ?: run { seed(key); checkNotNull(dao.active(key.name)) }
    fun observeActive(key: PromptKey): Flow<PromptVersionEntity?> = dao.observeActive(key.name)
    fun observeAllActive(): Flow<List<PromptVersionEntity>> = dao.observeAllActive()
    fun history(key: PromptKey): Flow<List<PromptVersionEntity>> = dao.observeVersions(key.name)
    suspend fun version(key: PromptKey, version: Int): PromptVersionEntity? = dao.version(key.name, version)

    suspend fun save(key: PromptKey, draft: PromptDraft, note: String, testedOk: Boolean): Int {
        val template = draft.template.trim()
        require(template.isNotEmpty()) { "The template is empty" }
        val schema = if (key == PromptKey.SYSTEM) null else draft.outputSchema?.trim()?.ifEmpty { null }
        active(key) // v1 is always the seed, never the first user save
        val n = note.replace(Regex("\\s+"), " ").trim().take(80)
        return if (settings.keepVersions.value) dao.appendAndActivate(key.name, template, schema, n, testedOk, now())
        else dao.overwriteActive(key.name, template, schema, n, testedOk, now())
    }

    suspend fun restore(key: PromptKey, version: Int): Int {
        val v = dao.version(key.name, version) ?: throw IllegalArgumentException("That version no longer exists")
        return save(key, PromptDraft(v.template, v.outputSchema), "Restored from v$version", v.testedOk)
    }
}
