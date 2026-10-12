package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** spec §4.3: one `prompt` row per key points at exactly one `prompt_version` (the active one). Schema v1 unchanged. */
@Dao
abstract class PromptDao {
    @Query("SELECT * FROM prompt WHERE id = :id") abstract suspend fun prompt(id: String): PromptEntity?
    @Query("SELECT * FROM prompt_version WHERE promptId = :id AND version = :version") abstract suspend fun version(id: String, version: Int): PromptVersionEntity?
    @Query("SELECT COALESCE(MAX(version), 0) FROM prompt_version WHERE promptId = :id") abstract suspend fun maxVersion(id: String): Int
    @Query("SELECT * FROM prompt_version WHERE promptId = :id ORDER BY version DESC") abstract fun observeVersions(id: String): Flow<List<PromptVersionEntity>>

    @Query("SELECT pv.* FROM prompt_version pv JOIN prompt p ON p.id = pv.promptId AND p.activeVersion = pv.version WHERE p.id = :id")
    abstract fun observeActive(id: String): Flow<PromptVersionEntity?>

    @Query("SELECT pv.* FROM prompt_version pv JOIN prompt p ON p.id = pv.promptId AND p.activeVersion = pv.version ORDER BY p.id")
    abstract fun observeAllActive(): Flow<List<PromptVersionEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertVersion(v: PromptVersionEntity)
    @Upsert protected abstract suspend fun upsertPrompt(p: PromptEntity)

    @Query("UPDATE prompt_version SET template = :template, outputSchema = :schema, note = :note, testedOk = :testedOk, createdAt = :now WHERE promptId = :id AND version = :version")
    protected abstract suspend fun rewrite(id: String, version: Int, template: String, schema: String?, note: String, testedOk: Boolean, now: Long): Int

    @Transaction open suspend fun active(id: String): PromptVersionEntity? = prompt(id)?.let { version(id, it.activeVersion) }

    /** Appends v(max+1) and activates it in one transaction: there is never a moment without exactly one active version. */
    @Transaction
    open suspend fun appendAndActivate(id: String, template: String, schema: String?, note: String, testedOk: Boolean, now: Long): Int {
        val next = maxVersion(id) + 1
        insertVersion(PromptVersionEntity(id, next, template, schema, now, note, testedOk))
        upsertPrompt(PromptEntity(id, next))
        return next
    }

    /** Versioning off (spec §4.3): the active version is rewritten in place; older versions stay as they were. */
    @Transaction
    open suspend fun overwriteActive(id: String, template: String, schema: String?, note: String, testedOk: Boolean, now: Long): Int {
        val p = prompt(id) ?: return appendAndActivate(id, template, schema, note, testedOk, now)
        rewrite(id, p.activeVersion, template, schema, note, testedOk, now)
        return p.activeVersion
    }

    @Transaction
    open suspend fun seedIfAbsent(id: String, template: String, schema: String?, now: Long): Boolean {
        if (prompt(id) != null) return false
        appendAndActivate(id, template, schema, "Seeded", false, now)
        return true
    }
}
