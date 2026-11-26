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
    // Slice 3a (Room v7). WIPED: traces, hub events, replays, sweeps, marks and intent labels, calibration artifacts, helper jobs (they may carry photos).
    // KEPT BY DESIGN (no user data): canvas_meta (the monotonic labelRev counter, bumped here so nothing computed earlier can read as current) and
    // canvas_group_state (the S62 publish-group recovery record: hashes and states, needed to recover a deployed plan after a wipe).
    @Query("DELETE FROM canvas_trace_event") protected abstract suspend fun slice3Events()
    @Query("DELETE FROM canvas_trace") protected abstract suspend fun slice3Traces()
    @Query("DELETE FROM canvas_replay") protected abstract suspend fun slice3Replays()
    @Query("DELETE FROM canvas_sweep") protected abstract suspend fun slice3Sweeps()
    @Query("DELETE FROM canvas_label") protected abstract suspend fun slice3Labels()
    @Query("DELETE FROM canvas_calibration") protected abstract suspend fun slice3Calibration()
    @Query("DELETE FROM canvas_helper_job") protected abstract suspend fun slice3HelperJobs()
    @Query("UPDATE canvas_meta SET value = CAST(CAST(value AS INTEGER) + 1 AS TEXT) WHERE `key` = 'labelRev'") protected abstract suspend fun slice3BumpLabelRev(): Int
    /** 3b: the one wildcard cleanup command (`jobId` = "*") that tells every device-side copy of every helper job to discard; the table is KEPT by Delete all. */
    @Query("INSERT INTO canvas_helper_cleanup (jobId, reason, createdAt) VALUES ('*', 'delete_all', :now)") protected abstract suspend fun slice3WildcardCleanup(now: Long)
    @Query("UPDATE canvas_meta SET value = '' WHERE `key` = 'forgetRanges'") protected abstract suspend fun slice3ClearForgetRanges(): Int
    @Query("DELETE FROM canvas_meta WHERE `key` LIKE 'canvas\\_save:%' ESCAPE '\\'") protected abstract suspend fun canvasSaveMarkers()
    @Query("DELETE FROM canvas_meta WHERE `key` LIKE 'mark\\_undone:%' ESCAPE '\\' OR `key` LIKE 'mark\\_claim:%' ESCAPE '\\' OR `key` LIKE 'mark\\_cleanup\\_owed:%' ESCAPE '\\'") protected abstract suspend fun markUndoneMarkers()
    /** Retention lineage of older builds (`lin:`, no longer written) and the expired-photo markers (`rx:`, `ExpiryMarks`): ids and times only, but they name what retention expired, so Delete all removes them. */
    @Query("DELETE FROM canvas_meta WHERE `key` LIKE 'lin:%' OR `key` LIKE 'rx:%'") protected abstract suspend fun retentionLineage()
    @Query("UPDATE canvas_meta SET value = '' WHERE `key` = 'momentForgetRanges'") protected abstract suspend fun momentClearForgetRanges(): Int
    @Query("INSERT OR IGNORE INTO canvas_meta (`key`, value) VALUES ('frame_forget_rev', '0')") protected abstract suspend fun slice3InitForgetRev()
    @Query("UPDATE canvas_meta SET value = CAST(CAST(value AS INTEGER) + 1 AS TEXT) WHERE `key` = 'frame_forget_rev'") protected abstract suspend fun slice3BumpForgetRev(): Int
    // Moment model phase 1 (Room v9). WIPED: the projection and the tombstones (the deletion epoch is the fence after a wipe). KEPT until acknowledged: cleanup_outbox (ids, paths
    // and ranges only): Delete all adds its own device-wide command FIRST, then drops only rows that were already acknowledged.
    @Query("DELETE FROM moment_part") protected abstract suspend fun momentParts()
    @Query("DELETE FROM moment_trace_dep") protected abstract suspend fun momentTraceDeps()
    @Query("DELETE FROM moment") protected abstract suspend fun moments()
    @Query("DELETE FROM moment_tombstone") protected abstract suspend fun momentTombstones()
    @Query("DELETE FROM cleanup_outbox WHERE ackedAt IS NOT NULL") protected abstract suspend fun ackedOutbox()
    @Query("INSERT OR IGNORE INTO cleanup_outbox (target, payloadJson, createdAt) VALUES ('ALL', '{\"reason\":\"delete_all\"}', :now)") protected abstract suspend fun momentWipeCleanup(now: Long)
    // Moment building blocks part 2 (Room v10). WIPED: the audit and the derived fields of every Moment-family object (the audit is content-free, but it is deleted with its objects).
    @Query("DELETE FROM object_audit") protected abstract suspend fun objectAudit()
    @Query("DELETE FROM object_derived") protected abstract suspend fun objectDerived()
    // Scenes (Room v11). WIPED: the Scenes and their membership edges (their audit and derived fields go with the two tables above).
    @Query("DELETE FROM scene_part") protected abstract suspend fun sceneParts()
    @Query("DELETE FROM scene") protected abstract suspend fun scenes()
    // Sensor features per Moment second (Room v12). WIPED: numbers only, but computed from traces that Delete all removes.
    @Query("DELETE FROM moment_sensor") protected abstract suspend fun momentSensor()
    // Moment locations (Room v18, v18 WP2). WIPED: an optional phone fix per Moment.
    @Query("DELETE FROM moment_location") protected abstract suspend fun momentLocation()
    // Place links (Room v18, place object S1). WIPED: pointers from a pin to Moments.
    @Query("DELETE FROM place_link") protected abstract suspend fun placeLinks()
    // Scene centroids (Room v18, WP10). WIPED: a vector derived from the user's photos.
    @Query("DELETE FROM scene_centroid") protected abstract suspend fun sceneCentroids()
    // Model attribution (Room v18, WP11a). WIPED: derived metadata of the texts Delete all removes (names only, never text).
    @Query("DELETE FROM model_attribution") protected abstract suspend fun modelAttribution()
    // Place card history (Room v18, WP3). WIPED: titles and times of the cards the user saw.
    @Query("DELETE FROM place_card_log") protected abstract suspend fun placeHistory()
    @Query("SELECT path FROM frame") abstract suspend fun framePaths(): List<String>

    /** Delete all at the current time (the wildcard cleanup row is stamped with it). */
    open suspend fun wipeMemory(): List<String> = wipeMemory(System.currentTimeMillis())

    @Transaction
    open suspend fun wipeMemory(now: Long): List<String> {
        val proposals = proposalIds()
        ackedOutbox(); momentWipeCleanup(now); objectAudit(); objectDerived(); sceneParts(); scenes(); sceneCentroids(); modelAttribution(); momentSensor(); momentLocation(); placeLinks(); placeHistory(); momentParts(); momentTraceDeps(); moments(); momentTombstones(); momentClearForgetRanges() // the cleanup command is written BEFORE any source reference goes
        sources(); frameSources(); vectors(); frameVectors(); spaces(); memories(); frames(); frameClusters(); frameStates(); frameMetas(); tombstones(); asks(); nudges(); openProposals(); traces()
        canvasItems(); canvasScores(); canvasRuns(); canvasTraces() // photo-derived experiment outputs; pipelines, versions and publishes hold prompts and stay
        slice3Events(); slice3Traces(); slice3Replays(); slice3Sweeps(); slice3Labels(); slice3Calibration(); slice3HelperJobs(); slice3ClearForgetRanges(); canvasSaveMarkers(); markUndoneMarkers(); retentionLineage(); slice3BumpLabelRev()
        slice3WildcardCleanup(now) // 3b: every device-side copy is told to discard; the job rows above are gone, the cleanup table is kept.
        slice3InitForgetRev(); slice3BumpForgetRev() // 3b: frame_forget_rev moves in THIS transaction too: a runner that captured the new generation and epoch (MemoryWipe.deleteAll moves both first) but loaded an old photo must still be refused
        scrubProposalParams(com.rokid.context.memory.forget.ForgetService.REDACTED_PARAMS); scrubActionLog()
        return proposals
    }
}
