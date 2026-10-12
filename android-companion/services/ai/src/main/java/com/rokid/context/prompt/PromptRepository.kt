package com.rokid.context.prompt

import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.db.PromptDao
import com.rokid.context.memory.db.PromptVersionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

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

    /** Reads the seed (an asset file for [AssetPromptSeeds]) on IO, and only for a key that has no prompt row yet. */
    private suspend fun seed(key: PromptKey): Boolean {
        if (dao.prompt(key.name) != null) return false
        val text = withContext(Dispatchers.IO) { seeds.template(key) }.trim()
        return dao.seedIfAbsent(key.name, text, DefaultSchemas.text(key), now())
    }

    suspend fun active(key: PromptKey): PromptVersionEntity = dao.active(key.name) ?: run { seed(key); checkNotNull(dao.active(key.name)) }
    fun observeActive(key: PromptKey): Flow<PromptVersionEntity?> = dao.observeActive(key.name)
    fun observeAllActive(): Flow<List<PromptVersionEntity>> = dao.observeAllActive()
    fun history(key: PromptKey): Flow<List<PromptVersionEntity>> = dao.observeVersions(key.name)
    suspend fun version(key: PromptKey, version: Int): PromptVersionEntity? = dao.version(key.name, version)

    suspend fun save(key: PromptKey, draft: PromptDraft, note: String, testedOk: Boolean): Int {
        val template = draft.template.trim()
        require(template.isNotEmpty()) { "The template is empty" }
        val schema = if (key == PromptKey.SYSTEM) null else draft.outputSchema?.trim()?.ifEmpty { null }
        active(key) // seed first; with versioning off the first save then overwrites that seeded v1 in place (spec §4.3)
        val n = note.replace(Regex("\\s+"), " ").trim().take(80)
        return if (settings.keepVersions.value) dao.appendAndActivate(key.name, template, schema, n, testedOk, now())
        else dao.overwriteActive(key.name, template, schema, n, testedOk, now())
    }

    suspend fun restore(key: PromptKey, version: Int): Int {
        val v = dao.version(key.name, version) ?: throw IllegalArgumentException("That version no longer exists")
        return save(key, PromptDraft(v.template, v.outputSchema), "Restored from v$version", v.testedOk)
    }
}
