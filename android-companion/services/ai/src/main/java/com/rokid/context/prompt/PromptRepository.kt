package com.rokid.context.prompt

import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.db.CasOutcome
import com.rokid.context.memory.db.CasWrite
import com.rokid.context.memory.db.PromptDao
import com.rokid.context.memory.db.PromptVersionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PromptDraft(val template: String, val outputSchema: String?)

/** One key of [PromptRepository.saveAllIfUnchanged]: [draft] is written only if the active text still equals [expected]. */
data class CasEdit(val key: PromptKey, val expected: PromptDraft, val draft: PromptDraft, val note: String, val testedOk: Boolean)

/**
 * spec §4.3 prompt store. Versioning on: Save appends v(N+1) and activates it. Off: Save rewrites the active version in
 * place and older versions stay read-only. Restore copies an old version through the same rule. A key still on an earlier
 * shipped default ([previousDefaults], never edited) moves to the current one once per process, by the same rule.
 */
class PromptRepository(
    private val dao: PromptDao,
    private val seeds: PromptSeeds,
    val settings: PromptSettings,
    private val previousDefaults: Map<PromptKey, Set<String>> = PromptDefaults.PREVIOUS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val upgradeLock = Mutex()
    private val upgradeChecked = java.util.concurrent.ConcurrentHashMap.newKeySet<PromptKey>()

    suspend fun ensureSeeded() { PromptKey.entries.forEach { seed(it); upgradeOnce(it) } }

    /**
     * An install whose active [key] is byte-identical (template and schema) to an earlier shipped default gets the
     * current default: a new version when versioning is on (the old one stays in history), else in place. Edited
     * prompts are left alone.
     */
    private suspend fun upgradeOnce(key: PromptKey) {
        val known = previousDefaults[key].orEmpty()
        if (known.isEmpty() || key in upgradeChecked) return
        upgradeLock.withLock {
            if (!upgradeChecked.add(key)) return
            val active = dao.active(key.name) ?: return
            if (PromptDefaults.fingerprint(active.template, active.outputSchema) !in known) return
            val template = withContext(Dispatchers.IO) { seeds.template(key) }.trim()
            val schema = DefaultSchemas.text(key)
            if (template == active.template && schema == active.outputSchema) return
            if (settings.keepVersions.value) dao.appendAndActivate(key.name, template, schema, PromptDefaults.UPDATED_NOTE, false, now())
            else dao.overwriteActive(key.name, template, schema, PromptDefaults.UPDATED_NOTE, false, now())
        }
    }

    /** Reads the seed (an asset file for [AssetPromptSeeds]) on IO, and only for a key that has no prompt row yet. */
    private suspend fun seed(key: PromptKey): Boolean {
        if (dao.prompt(key.name) != null) return false
        val text = withContext(Dispatchers.IO) { seeds.template(key) }.trim()
        return dao.seedIfAbsent(key.name, text, DefaultSchemas.text(key), now())
    }

    suspend fun active(key: PromptKey): PromptVersionEntity {
        upgradeOnce(key)
        return dao.active(key.name) ?: run { seed(key); checkNotNull(dao.active(key.name)) }
    }
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

    /**
     * Compare-and-set save of several keys in one transaction: if any key's active text differs from its expected text
     * nothing is written and the mismatching ids come back. Texts are normalised exactly as [save] does; keys are seeded first.
     */
    suspend fun saveAllIfUnchanged(edits: List<CasEdit>): CasOutcome {
        val writes = edits.map { e ->
            val a = active(e.key) // seeds first
            val t = e.draft.template.trim()
            require(t.isNotEmpty()) { "The template is empty" }
            val expT = e.expected.template.trim()
            val expS = normSchema(e.key, e.expected.outputSchema)
            // A seeded schema is stored as shipped (not trimmed), so an expectation that equals the stored text after
            // normalisation is pinned to the exact stored bytes; the DAO then compares those exactly, inside its transaction.
            val same = a.template == expT && normSchema(e.key, a.outputSchema) == expS
            CasWrite(
                e.key.name, if (same) a.template else expT, if (same) a.outputSchema else expS,
                t, normSchema(e.key, e.draft.outputSchema), e.note.replace(Regex("\\s+"), " ").trim().take(80), e.testedOk,
            )
        }
        return dao.casSaveAll(writes, settings.keepVersions.value, now())
    }

    private fun normSchema(key: PromptKey, s: String?): String? = if (key == PromptKey.SYSTEM) null else s?.trim()?.ifEmpty { null }

    suspend fun restore(key: PromptKey, version: Int): Int {
        val v = dao.version(key.name, version) ?: throw IllegalArgumentException("That version no longer exists")
        return save(key, PromptDraft(v.template, v.outputSchema), "Restored from v$version", testedOk = false) // untested until it is run again
    }
}
