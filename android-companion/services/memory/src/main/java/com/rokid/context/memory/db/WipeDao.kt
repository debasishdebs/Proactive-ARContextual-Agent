package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Settings → Memory › Delete all (spec §6.1.1: Forget moment for every frame + all derived memories, asks, nudges,
 * vectors and tombstones, plus the frame_* capture tables: meta, state, source, cluster, vector, and the canvas run outputs: canvas_run, canvas_run_item, canvas_score, canvas_live_trace). Endpoints, bindings, prompts, pauses, shopping list and notes are settings/user lists: kept.
 * Proposals (P7xP8 I1): every proposal came from an ask or a nudge, so all of them are memory-derived. Open ones are
 * deleted; the rest (EXECUTING/EXECUTED/FAILED, kept for undo) get Forget's redaction: params replaced with
 * [com.rokid.context.memory.forget.ForgetService.REDACTED_PARAMS] and action_log details emptied. [wipeMemory]
 * returns every proposal id it saw, so the caller cancels their alarms and notifications.
 */
@Dao
abstract class WipeDao {
    @Query("DELETE FROM memory_source") protected abstract suspend fun sources()
    @Query("DELETE FROM memory_vector") protected abstract suspend fun vectors()
    @Query("DELETE FROM embedding_space") protected abstract suspend fun spaces()
    @Query("DELETE FROM memory") protected abstract suspend fun memories()
    @Query("DELETE FROM frame_source") protected abstract suspend fun frameSources()
    @Query("DELETE FROM frame_vector") protected abstract suspend fun frameVectors()
    @Query("DELETE FROM frame_cluster") protected abstract suspend fun frameClusters()
    @Query("DELETE FROM frame_state") protected abstract suspend fun frameStates()
    @Query("DELETE FROM frame_meta") protected abstract suspend fun frameMetas()
    @Query("DELETE FROM frame") protected abstract suspend fun frames()
    @Query("DELETE FROM forget_tombstone") protected abstract suspend fun tombstones()
    @Query("DELETE FROM ask") protected abstract suspend fun asks()
    @Query("DELETE FROM nudge") protected abstract suspend fun nudges()
    @Query("DELETE FROM proposal WHERE state IN ('PENDING','SUPPRESSED','EXPIRED','DECLINED')") protected abstract suspend fun openProposals()
    @Query("SELECT id FROM proposal") protected abstract suspend fun proposalIds(): List<String>
    @Query("UPDATE proposal SET paramsJson = :params") protected abstract suspend fun scrubProposalParams(params: String)
    @Query("UPDATE action_log SET detail = ''") protected abstract suspend fun scrubActionLog()
    @Query("DELETE FROM summary_trace") protected abstract suspend fun traces()
    @Query("DELETE FROM canvas_run_item") protected abstract suspend fun canvasItems()
    @Query("DELETE FROM canvas_score") protected abstract suspend fun canvasScores()
    @Query("DELETE FROM canvas_run") protected abstract suspend fun canvasRuns()
    @Query("DELETE FROM canvas_live_trace") protected abstract suspend fun canvasTraces()
    @Query("SELECT path FROM frame") abstract suspend fun framePaths(): List<String>

    @Transaction
    open suspend fun wipeMemory(): List<String> {
        val proposals = proposalIds()
        sources(); frameSources(); vectors(); frameVectors(); spaces(); memories(); frames(); frameClusters(); frameStates(); frameMetas(); tombstones(); asks(); nudges(); openProposals(); traces()
        canvasItems(); canvasScores(); canvasRuns(); canvasTraces() // photo-derived experiment outputs; pipelines, versions and publishes hold prompts and stay
        scrubProposalParams(com.rokid.context.memory.forget.ForgetService.REDACTED_PARAMS); scrubActionLog()
        return proposals
    }
}
