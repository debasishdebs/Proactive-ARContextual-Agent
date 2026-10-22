package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.CanvasRepository
import com.rokid.context.canvas.Estimate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Everything the canvas API needs, as data. Unknown ids throw [NoSuchElementException] (404); a bad argument throws
 * [IllegalArgumentException] (400, or 422 `invalid_pipeline` from [freeze]); a run already active throws
 * [com.rokid.context.canvas.CanvasRunner.Busy] (409). Nothing here returns a token or a pairing code.
 */
interface CanvasBackend {
    suspend fun session(token: String): JsonObject
    /** Ends the session of [token]. */
    suspend fun logout(token: String)
    fun nodeTypes(): JsonArray
    suspend fun pipelines(): JsonArray
    /** `{json, draftRev, builtIn, problems, layout, versions:[{version,hash,note,frozenAt}], liveShape, live:{version}|null, activePrompts:{"<nodeId>":{template,schema,version}}}` */
    suspend fun pipeline(id: String): JsonObject?
    /** [from] is `<id>` or `<id>@<version>`. */
    suspend fun duplicate(from: String, name: String): String
    suspend fun saveDraft(id: String, json: String, rev: Int): CanvasRepository.SaveResult
    suspend fun deletePipeline(id: String): Boolean
    /** [draftRev] (S38): when given and not the draft's current revision, throws [CanvasRepository.DraftChanged]. */
    suspend fun freeze(id: String, note: String, draftRev: Int?): JsonObject
    /**
     * Validates [json] without saving anything: `{problems:[{text,nodeId?,edge?}], layout, liveShape, interpretable:{problems,warnings,callRange}}`.
     * An undecodable pipeline gives its fixed text as the only problem and a null layout. The API adds the echoed `rev`.
     */
    suspend fun validate(json: String): JsonObject
    /** `PipelineDiff` of version [a] against [b] (a version number or `draft`) as JSON; null when the pipeline or a version is unknown. */
    suspend fun diff(id: String, a: String, b: String): JsonObject?
    suspend fun publish(id: String, version: Int): CanvasPublisher.Outcome
    suspend fun activePublish(): JsonObject
    suspend fun rollback(): CanvasPublisher.Outcome
    suspend fun checkPrompt(nodeType: String, template: String, schema: String?): List<String>
    /** Frames with `afterMs <= ts < beforeMs` (no lower bound when [afterMs] is null), newest first. */
    suspend fun frames(afterMs: Long?, beforeMs: Long, limit: Int, dwellOnly: Boolean): JsonArray
    /** Metadata plus `live:[{id,text,type}]`. */
    suspend fun frame(id: String): JsonObject?
    suspend fun frameImage(id: String, thumb: Boolean): ByteArray?
    suspend fun estimate(pipelineId: String, version: Int?, frameIds: List<String>): Estimate
    suspend fun startRun(pipelineId: String, version: Int?, frameIds: List<String>, token: String): String
    suspend fun runs(): JsonArray
    /** The run row plus `items`, `scores` and `progress {done,total}`. */
    suspend fun run(id: String): JsonObject?
    suspend fun cancelRun(id: String): Boolean
    suspend fun deleteRun(id: String): Boolean
    suspend fun score(runId: String, frameId: String, nodeId: String, score: Int, note: String)
    /** `{frames:[{frameId, nodes:[{nodeId, a, b, diff:[{op,text}]}]}]}`; null when either run is unknown. */
    suspend fun compare(a: String, b: String): JsonObject?
}
