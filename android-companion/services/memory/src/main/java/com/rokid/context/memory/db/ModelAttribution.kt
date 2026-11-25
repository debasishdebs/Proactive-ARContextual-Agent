package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Which model wrote a stored text (v18 build spec WP11a; owner decision 2026-11-26: the model name and version are recorded per caption, summary, description, extract and answer, for the owner's offline
 * experiments and the Developer dump). A side table like `moment_location` and `scene_centroid`: no foreign key, no trigger. One row per `(kind, refId)`:
 *  - [AttributionKind.MEMORY]: refId is the `memory.id` (a photo caption, a description, an extract fact, a cluster or Scene summary memory, a text summary);
 *  - [AttributionKind.DERIVED]: refId is `<objectKind>:<objectId>:<field>` of an `object_derived` row (a Moment or Scene SUMMARY or CAPTION);
 *  - [AttributionKind.ANSWER]: refId is the `ask.id` of an Ask answer.
 * It is written in the SAME transaction as the item it describes, deleted with the item (every delete path, user Forget of the item and Delete all) and follows its expiry. [model] is the name the router
 * answered with ([com.rokid.context.ai.RoutedReply.model]); [modelVersion] only where the provider reports one (null today: none does); [promptRev] the prompt version of a role call (null for a fixed prompt).
 * It never holds text, a photo, a prompt, a coordinate or a place name: only names and a number.
 */
@Entity(tableName = "model_attribution", primaryKeys = ["kind", "refId"])
data class ModelAttributionEntity(
    val kind: String,
    val refId: String,
    val model: String,
    val modelVersion: String? = null,
    val promptRev: String? = null,
    val recordedAt: Long,
)

object AttributionKind {
    const val MEMORY = "MEMORY"
    const val DERIVED = "DERIVED"
    const val ANSWER = "ANSWER"
    /** The refId of a derived field: `<objectKind>:<objectId>:<field>`. */
    fun derivedRef(objectKind: String, objectId: String, field: String) = "$objectKind:$objectId:$field"
}

/** The model facts a write site carries to its saver: in memory only, then one `model_attribution` row. A blank [model] is no attribution (never recorded as "unknown"). */
data class ModelAttribution(val model: String, val modelVersion: String? = null, val promptRev: String? = null) {
    fun row(kind: String, refId: String, at: Long) = ModelAttributionEntity(kind, refId, model.take(MAX), modelVersion?.take(MAX), promptRev?.take(MAX), at)
    companion object {
        private const val MAX = 120
        /** Null for a null or blank [model]: the write site then records nothing (and clears an older row of the same item). */
        fun of(model: String?, promptRev: Int? = null, modelVersion: String? = null): ModelAttribution? = model?.trim()?.takeIf { it.isNotEmpty() }?.let { ModelAttribution(it, modelVersion, promptRev?.toString()) }
    }
}

@Dao
abstract class ModelAttributionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsert(row: ModelAttributionEntity)
    @Query("SELECT * FROM model_attribution WHERE kind = :kind AND refId = :refId") abstract suspend fun get(kind: String, refId: String): ModelAttributionEntity?
    @Query("SELECT * FROM model_attribution WHERE kind = :kind AND refId IN (:refIds)") abstract suspend fun ofRefs(kind: String, refIds: List<String>): List<ModelAttributionEntity>
    @Query("SELECT * FROM model_attribution WHERE kind = :kind AND refId LIKE :prefix || '%' ESCAPE '\\'") abstract suspend fun ofPrefix(kind: String, prefix: String): List<ModelAttributionEntity>
    @Query("SELECT COUNT(*) FROM model_attribution") abstract suspend fun count(): Int
    @Query("SELECT COUNT(*) FROM model_attribution WHERE kind = :kind") abstract suspend fun countOf(kind: String): Int

    @Query("DELETE FROM model_attribution WHERE kind = :kind AND refId IN (:refIds)") protected abstract suspend fun deleteChunk(kind: String, refIds: List<String>): Int
    @Query("DELETE FROM model_attribution") abstract suspend fun deleteAll(): Int

    /** Replaces the row of the item, or removes an older one when [attribution] is null (a text a later write produced without a known model must not keep the earlier model's name). */
    @Transaction open suspend fun record(kind: String, refId: String, attribution: ModelAttribution?, at: Long) {
        if (attribution != null) upsert(attribution.row(kind, refId, at)) else deleteChunk(kind, listOf(refId))
    }
    @Transaction open suspend fun deleteFor(kind: String, refIds: Collection<String>): Int = refIds.distinct().chunked(CHUNK).sumOf { deleteChunk(kind, it) }

    /**
     * Deletes every row whose item is gone: the memory, the derived field, or the Ask (a redacted answer counts as gone: its text was erased). Order independent; every path that deletes or redacts items calls it
     * in the same transaction (the cheap id-based [deleteFor] where the ids are at hand).
     */
    @Query(
        "DELETE FROM model_attribution WHERE (kind = 'MEMORY' AND refId NOT IN (SELECT id FROM memory)) " +
            "OR (kind = 'DERIVED' AND refId NOT IN (SELECT objectKind || ':' || objectId || ':' || field FROM object_derived)) " +
            "OR (kind = 'ANSWER' AND refId NOT IN (SELECT id FROM ask WHERE redacted = 0))",
    )
    abstract suspend fun deleteGone(): Int

    private companion object { const val CHUNK = 500 }
}
