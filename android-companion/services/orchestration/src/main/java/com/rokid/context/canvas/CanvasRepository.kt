package com.rokid.context.canvas

import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.PipelineHash
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PipelineValidator
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasPipelineEntity
import com.rokid.context.memory.db.CanvasPipelineVersionEntity
import java.security.SecureRandom

private fun randomHex(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

/** Pipelines (one editable draft each) and their frozen versions. Built-ins are read-only and refreshed from code. */
class CanvasRepository(
    private val dao: CanvasDao,
    private val seedTemplate: suspend (PromptKey) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { "p-" + randomHex(8) },
) {
    sealed interface SaveResult {
        data class Saved(val rev: Int, val problems: List<String>) : SaveResult
        data object Conflict : SaveResult
        data object ReadOnly : SaveResult
        data class Unreadable(val reason: String) : SaveResult
    }

    /** The draft was saved by someone else since the caller's save: [freeze] with an expected revision refuses (S38). */
    class DraftChanged : IllegalStateException("draft_changed")

    /** Upserts the built-ins with the current code's JSON (they are read-only, so overwriting is safe). */
    suspend fun ensureBuiltIns() {
        val now = clock()
        val built = listOf(DefaultPipelines.liveExtract(), DefaultPipelines.intentDraft(seedTemplate(PromptKey.VISION_EXTRACT)), DefaultPipelines.defaultCapture(), DefaultPipelines.starter(), DefaultPipelines.starterMomentSummary(), DefaultPipelines.starterWatchMotion())
        built.forEach { p ->
            val old = dao.pipeline(p.id)
            dao.upsertPipeline(CanvasPipelineEntity(p.id, p.name, PipelineJson.encode(p), old?.draftRev ?: 0, true, old?.createdAt ?: now, now))
        }
    }

    suspend fun list(): List<CanvasPipelineEntity> = dao.pipelines()

    suspend fun get(id: String): CanvasPipelineEntity? = dao.pipeline(id)

    /** A new editable draft copied from a frozen [version] of [from], or from its current draft when [version] is null. */
    suspend fun duplicate(from: String, version: Int?, name: String): String {
        val src = if (version != null) dao.version(from, version)?.json else dao.pipeline(from)?.draftJson
        require(src != null) { "source_not_found" }
        val p = PipelineJson.decode(src).getOrElse { throw IllegalArgumentException("source_unreadable") }
        val id = newId()
        val now = clock()
        val copy = p.copy(id = id, name = name)
        dao.upsertPipeline(CanvasPipelineEntity(id, name, PipelineJson.encode(copy), 0, false, now, now))
        return id
    }

    /** Undecodable JSON is not saved; decodable but invalid JSON is saved with its problems listed. */
    suspend fun saveDraft(id: String, json: String, draftRev: Int): SaveResult {
        val entity = dao.pipeline(id) ?: return SaveResult.Unreadable("not found")
        if (entity.builtIn) return SaveResult.ReadOnly
        val p = PipelineJson.decode(json).getOrElse { return SaveResult.Unreadable(it.message ?: "unreadable") }
        val text = if (p.id == id) json else PipelineJson.encode(p.copy(id = id))
        val saved = dao.saveDraft(id, text, draftRev, clock())
        if (saved == 0) return SaveResult.Conflict
        return SaveResult.Saved(draftRev + 1, PipelineValidator.validate(p.copy(id = id)))
    }

    /**
     * Freezes the current draft as the next version. Same hash as the latest version returns that version.
     * [expectedRev] (S38): when given and not the draft's current revision, throws [DraftChanged] and freezes nothing.
     */
    suspend fun freeze(id: String, note: String, expectedRev: Int? = null): CanvasPipelineVersionEntity {
        val entity = dao.pipeline(id) ?: throw IllegalArgumentException("invalid_pipeline")
        if (expectedRev != null && entity.draftRev != expectedRev) throw DraftChanged()
        val p = PipelineJson.decode(entity.draftJson).getOrElse { throw IllegalArgumentException("invalid_pipeline") }
        if (PipelineValidator.validate(p).isNotEmpty()) throw IllegalArgumentException("invalid_pipeline")
        val hash = PipelineHash.of(p)
        val last = dao.versions(id).firstOrNull()
        if (last != null && last.hash == hash) return last
        val v = CanvasPipelineVersionEntity(id, (last?.version ?: 0) + 1, PipelineJson.encode(p), hash, note, clock())
        dao.insertVersion(v)
        return v
    }

    /** False for a built-in, or when an active publish names the pipeline. */
    suspend fun delete(id: String): Boolean {
        val entity = dao.pipeline(id) ?: return false
        if (entity.builtIn) return false
        if (dao.activePublishes().any { it.pipelineId == id }) return false
        return dao.deletePipeline(id) > 0
    }
}
