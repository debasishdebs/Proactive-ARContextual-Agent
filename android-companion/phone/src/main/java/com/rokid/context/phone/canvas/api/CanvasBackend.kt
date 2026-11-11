package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.AbStarted
import com.rokid.context.canvas.AskDebug
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.CanvasRepository
import com.rokid.context.canvas.CanvasRunner
import com.rokid.context.canvas.Estimate
import com.rokid.context.canvas.LiveMode
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
    /** The MVP prompt publish of a live-shaped version. */
    suspend fun publish(id: String, version: Int): CanvasPublisher.Outcome
    /** An interpreter publish in [mode] (Shadow or Live); the gates' reasons come back as `Blocked`. */
    suspend fun publishInterpreter(id: String, version: Int, mode: LiveMode): CanvasPublisher.Outcome
    /** v0.9.8 slice B: a Query or Nudge pipeline goes live. */
    suspend fun publishFlow(id: String, version: Int): CanvasPublisher.Outcome
    /**
     * The active stack, newest first: `{active, publishId, pipelineId, version, publishedAt}` of the newest prompts publish (as before), plus
     * `stack:[{id,kind,mode,pipelineId,version,publishedAt}]` and `interpreter:{id,kind,mode,pipelineId,version,publishedAt,interpreted,fallbacks}|null`.
     */
    suspend fun activePublish(): JsonObject
    /** Every publish row, newest first (S43): `[{id,kind,mode,pipelineId,version,publishedAt,rolledBackAt,rollbackReason}]`. */
    suspend fun publishHistory(limit: Int): JsonArray
    /**
     * The active interpreter publish's recent traces: `{active, publishId?, pipelineId?, version?, mode?, interpreted, fallbacks, labels:{"<label>":n},
     * frames:[{frameId, at, label, batch:"OK"|"ERROR", nodes:[{nodeId,status,branch,output,error}]}]}`. Debug data of the user's photos: authenticated routes only.
     */
    suspend fun live(limit: Int): JsonObject
    /** Codex r21: a number that moves after every Forget, retention delete and Delete all (the page reads its open view again). */
    fun dataGeneration(): Long = 0L
    /** Rolls back the newest publish of either kind; [expectedId] (the publish the caller saw as the newest) is required: it guards a race with an auto-rollback. */
    suspend fun rollback(expectedId: Long): CanvasPublisher.Outcome
    suspend fun checkPrompt(nodeType: String, template: String, schema: String?): List<String>
    /** Frames with `afterMs <= ts < beforeMs` (no lower bound when [afterMs] is null), newest first. */
    suspend fun frames(afterMs: Long?, beforeMs: Long, limit: Int, dwellOnly: Boolean): JsonArray
    /** Metadata plus `live:[{id,text,type}]`. */
    suspend fun frame(id: String): JsonObject?
    /**
     * The photo of frame [id] (a [thumb]nail JPEG or the full-size image), decrypted in memory and never written anywhere; null (404) when the frame is
     * unknown, forgotten, tombstoned, expired or wiped, or not an image within the size limits. There is no listing: a photo is served only by an id the caller names.
     */
    suspend fun frameImage(id: String, thumb: Boolean): FrameImage?
    suspend fun estimate(pipelineId: String, version: Int?, frameIds: List<String>): Estimate
    /** Moment-lite: Moments (seconds with at least one live photo) with `afterMs <= startMs < beforeMs`, oldest first: `{id, startMs, photos, memories, marks}`. Read-only. */
    suspend fun moments(afterMs: Long, beforeMs: Long, limit: Int): JsonArray = JsonArray(emptyList())
    /** The price of a Moment run; the token binds the sorted ids and each Moment's contentRev + contentHash. */
    suspend fun estimateMoments(pipelineId: String, version: Int?, momentIds: List<String>): Estimate = Estimate.Refused(listOf("moments are not available"))
    /** Starts a Moment run; [CanvasRunner.MomentsMoved] when anything changed since the estimate. */
    suspend fun startMomentRun(pipelineId: String, version: Int?, momentIds: List<String>, token: String): String = throw IllegalArgumentException("moments are not available")
    /** Scenes (read-only) overlapping `[fromMs, toMs]`, oldest first: id, window, cut rule, activity, member Moment ids, sensor summary, and derived fields only while fresh (never a hidden one). */
    suspend fun scenes(fromMs: Long, toMs: Long, limit: Int): JsonArray = JsonArray(emptyList())
    /** Prices a run over Scenes; the token binds what each Scene would hand a node. */
    suspend fun estimateScenes(pipelineId: String, version: Int?, sceneIds: List<String>): Estimate = Estimate.Refused(listOf("scenes are not available"))
    /** Starts a Scene run; [CanvasRunner.ScenesMoved] when anything changed since the estimate. */
    suspend fun startSceneRun(pipelineId: String, version: Int?, sceneIds: List<String>, token: String): String = throw IllegalArgumentException("scenes are not available")
    /** The price of an A/B pair: one token, bound to both pipelines (`hashA|hashB`) and the photos. */
    suspend fun estimateAb(a: PipelineRef, b: PipelineRef, frameIds: List<String>): Estimate
    /** Starts A now and B after it ends DONE; both ids are reserved up front. [CanvasRunner.Busy] while any run or group is active. */
    suspend fun startAb(a: PipelineRef, b: PipelineRef, frameIds: List<String>, token: String): AbStarted
    suspend fun startRun(pipelineId: String, version: Int?, frameIds: List<String>, token: String): String
    suspend fun runs(): JsonArray
    /**
     * The run row plus `items`, `scores`, `progress {done,total}`, `abGroup` (null for a plain run), `usage {calls, est, promptTokens, completionTokens, medianLatencyMs}`
     * and `branches {"<switchNodeId>": {"<label>": count}}`.
     */
    suspend fun run(id: String): JsonObject?
    suspend fun cancelRun(id: String): Boolean
    /**
     * Playground vs Run-and-Save (v0.9.6): what saving run [id] would write, read-only: `{saveable, reason, message, saved, memories:[{frameId,kind,text,type,review}]}`;
     * `saveable:false` with its reason for a run that is not finished, has no Save memory step (a playground run) or is not a photo run. Null for an unknown run.
     */
    suspend fun runProposal(id: String): JsonObject? = null
    /**
     * Saves run [id]'s memories through the real write path, once: `{result, ok, saved, message}` with `result` one of SAVED, ALREADY_SAVED, NOT_SAVEABLE, NOTHING, BUSY (an export or
     * delete is running: try again), WIPED. A failure is thrown (500), never reported as saved. Unknown run: [NoSuchElementException].
     */
    suspend fun saveRun(id: String): JsonObject = throw IllegalArgumentException("save is not available")
    suspend fun deleteRun(id: String): Boolean
    suspend fun score(runId: String, frameId: String, nodeId: String, score: Int, note: String)
    /**
     * `{frames:[{frameId, nodes:[{nodeId, a, b, diff:[{op,text}]}]}], branches:{a:{..},b:{..}}, agreement:{"<switchNodeId>": 0..1}, scoreSums:{"<nodeId>":{a,b}}}`;
     * null when either run is unknown.
     */
    suspend fun compare(a: String, b: String): JsonObject?

    // ---- Ask debug (authenticated routes only: the composed prompt and the replay hold the user's memories; nothing here is ever logged) ----

    /** The newest asks: `[{id, ts, question, answer (short), rating, redacted}]`. */
    suspend fun asks(): JsonArray
    /** The ask's debug view (see [AskDebug.View]); null for an unknown ask; throws [IllegalStateException] ("redacted") for a redacted one. */
    suspend fun ask(id: String): JsonObject?
    /** One ANSWER call with [template] as the EXPERIMENT class; saves nothing. */
    suspend fun replayAsk(id: String, template: String): AskDebug.Replay
    /**
     * The phone's real memory retrieval for [query] (text only: no photo hits, no model call, no write). [mode] null = the user's setting. Throws
     * [IllegalStateException] ("busy") during an export, delete or save. Never logs the query.
     */
    suspend fun retrieveDebug(query: String, mode: String?, topK: Int?, notAfterMs: Long?, snippets: Boolean): JsonObject = throw IllegalArgumentException("retrieve debug is not available")
}

/** A pipeline of an A/B pair: the draft when [version] is null. */
data class PipelineRef(val pipelineId: String, val version: Int?)
