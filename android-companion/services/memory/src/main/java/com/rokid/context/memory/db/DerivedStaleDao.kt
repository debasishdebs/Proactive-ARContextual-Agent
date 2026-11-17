package com.rokid.context.memory.db

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The shared core of the audit append and the stale rule (see `ObjectAudit`), as a base class so that EVERY DAO that changes a source of a Moment (marks, memories) can hide the
 * derived fields of the Moments holding it INSIDE its own transaction, through the same code that writes the audit rows. None of these methods opens a transaction itself: the
 * caller's `@Transaction` method is the one.
 */
abstract class DerivedStaleDao {
    @Query("SELECT COALESCE(MAX(seq), 0) + 1 FROM object_audit WHERE objectKind = :kind AND objectId = :id") abstract suspend fun nextAuditSeq(kind: String, id: String): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertAuditRow(row: ObjectAuditEntity)
    @Query("SELECT field FROM object_derived WHERE objectKind = :kind AND objectId = :id AND staleSince IS NULL ORDER BY field") abstract suspend fun freshFields(kind: String, id: String): List<String>
    @Query("UPDATE object_derived SET staleSince = :now WHERE objectKind = :kind AND objectId = :id AND staleSince IS NULL") abstract suspend fun staleAll(kind: String, id: String, now: Long): Int
    @Query("SELECT DISTINCT momentId FROM moment_part WHERE kind IN (:kinds) AND refId IN (:refIds)") abstract suspend fun momentIdsHoldingParts(kinds: List<String>, refIds: List<String>): List<String>

    @Query("SELECT COUNT(*) FROM moment WHERE momentId = :id") abstract suspend fun momentExists(id: String): Int
    @Query("SELECT COUNT(*) FROM object_audit WHERE objectKind = :kind AND objectId = :id") abstract suspend fun auditRowCount(kind: String, id: String): Int
    /** The revision of an object's INPUTS: the latest audit seq that is not a derived-field row (a build, part, forget or source-invalidation op). 0 = none. */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM object_audit WHERE objectKind = :kind AND objectId = :id AND op NOT IN ('DERIVED_BUILT', 'DERIVED_STALE', 'DERIVED_HIDDEN', 'OUTBOX_QUEUED')")
    abstract suspend fun inputSeq(kind: String, id: String): Int

    // --- Scenes: the part of the shrink rule that every DAO removing a Moment needs ---
    @Query("SELECT DISTINCT sceneId FROM scene_part WHERE kind = 'MOMENT' AND refId IN (:momentIds)") abstract suspend fun sceneIdsHoldingMoments(momentIds: List<String>): List<String>
    @Query("SELECT refId FROM scene_part WHERE sceneId = :sceneId AND kind = 'MOMENT' AND refId IN (:momentIds) ORDER BY refId") abstract suspend fun sceneMomentRefs(sceneId: String, momentIds: List<String>): List<String>
    @Query("DELETE FROM scene_part WHERE sceneId = :sceneId AND kind = 'MOMENT' AND refId IN (:momentIds)") abstract suspend fun deleteSceneMomentParts(sceneId: String, momentIds: List<String>)
    @Query("SELECT COUNT(*) FROM scene_part WHERE sceneId = :sceneId") abstract suspend fun scenePartTotal(sceneId: String): Int
    @Query("SELECT MIN(partTimeMs) FROM scene_part WHERE sceneId = :sceneId") abstract suspend fun scenePartFirstMs(sceneId: String): Long?
    @Query("SELECT MAX(partTimeMs) FROM scene_part WHERE sceneId = :sceneId") abstract suspend fun scenePartLastMs(sceneId: String): Long?
    @Query("UPDATE scene SET startMs = :startMs, endMs = :endMs, membershipRev = membershipRev + 1 WHERE sceneId = :sceneId") abstract suspend fun shrinkSceneRow(sceneId: String, startMs: Long, endMs: Long)
    @Query("DELETE FROM scene WHERE sceneId IN (:ids)") protected abstract suspend fun deleteSceneRows(ids: List<String>)
    @Query("DELETE FROM object_audit WHERE objectKind = 'SCENE' AND objectId IN (:ids)") protected abstract suspend fun deleteSceneAudit(ids: List<String>)
    @Query("DELETE FROM object_derived WHERE objectKind = 'SCENE' AND objectId IN (:ids)") protected abstract suspend fun deleteSceneDerived(ids: List<String>)

    @Query("SELECT COALESCE(MAX(seq), 0) FROM object_audit WHERE objectKind = 'SCENE' AND objectId IN (:ids)") protected abstract suspend fun sceneMaxSeq(ids: List<String>): Int
    @Query("SELECT value FROM canvas_meta WHERE `key` = 'scene_seq_floor'") protected abstract suspend fun sceneSeqFloorValue(): String?
    @Query("INSERT OR REPLACE INTO canvas_meta (`key`, value) VALUES ('scene_seq_floor', :value)") protected abstract suspend fun putSceneSeqFloor(value: String)

    // --- Sensor features per Moment second (Room v12) ---
    @Query("SELECT * FROM moment_sensor WHERE momentId IN (:ids) ORDER BY momentId, phoneSecond") abstract suspend fun sensorOf(ids: List<String>): List<MomentSensorEntity>
    @Query("SELECT * FROM moment_sensor WHERE phoneSecond >= :fromSec AND phoneSecond <= :toSec ORDER BY phoneSecond, momentId") abstract suspend fun sensorBetween(fromSec: Long, toSec: Long): List<MomentSensorEntity>
    @Query("SELECT COUNT(*) FROM moment_sensor") abstract suspend fun sensorCount(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertSensor(rows: List<MomentSensorEntity>)
    @Query("DELETE FROM moment_sensor WHERE momentId IN (:ids)") abstract suspend fun deleteSensorOf(ids: List<String>)
    @Query("SELECT DISTINCT momentId FROM moment_sensor WHERE traceId = :traceId ORDER BY momentId") protected abstract suspend fun sensorMomentsOfTrace(traceId: String): List<String>
    @Query("DELETE FROM moment_sensor WHERE traceId = :traceId") protected abstract suspend fun deleteSensorOfTrace(traceId: String)
    @Query("SELECT DISTINCT momentId FROM moment_sensor WHERE phoneSecond >= :fromSec AND phoneSecond <= :toSec ORDER BY momentId") protected abstract suspend fun sensorMomentsBetween(fromSec: Long, toSec: Long): List<String>
    @Query("DELETE FROM moment_sensor WHERE phoneSecond >= :fromSec AND phoneSecond <= :toSec") protected abstract suspend fun deleteSensorBetween(fromSec: Long, toSec: Long)

    /** Hides the derived fields of each of these Moments that still exists (and, through [markStaleCore], of every Scene holding it); the rows they were built from changed. */
    protected suspend fun hideAfterSensorChange(momentIds: List<String>, now: Long, step: String) {
        momentIds.filter { momentExists(it) > 0 }.forEach { markStaleCore("MOMENT", it, now, step, "SENSOR", null, force = true) }
    }

    /**
     * The trace [traceId] is gone (tombstoned) or holds other content now: every sensor row computed from it is deleted, in ALL Moments (not only those of a forgotten range), and the derived
     * fields of those Moments and of their Scenes are hidden in the caller's transaction. Returns the Moments touched.
     */
    suspend fun purgeSensorOfTrace(traceId: String, now: Long, step: String): Int {
        val moments = sensorMomentsOfTrace(traceId)
        if (moments.isEmpty()) return 0
        deleteSensorOfTrace(traceId)
        hideAfterSensorChange(moments, now, step)
        return moments.size
    }

    /** The Moments whose sensor rows [purgeSensorBetween] / [purgeSensorOfTrace] would delete (read only; Forget lifecycle F6 plans them). */
    suspend fun plannedSensorPurge(fromMs: Long?, toMs: Long?, traceIds: Collection<String>): Set<String> {
        val out = LinkedHashSet<String>()
        traceIds.forEach { out += sensorMomentsOfTrace(it) }
        if (fromMs != null && toMs != null && toMs >= fromMs && toMs >= 0) out += sensorMomentsBetween(Math.floorDiv(maxOf(fromMs, 0L), 1000L), Math.floorDiv(toMs, 1000L))
        return out
    }

    /** Forget of the phone-clock interval `[fromMs, toMs]`: the sensor rows of its seconds go whichever trace they came from (a row whose trace row is missing is found by its second). */
    suspend fun purgeSensorBetween(fromMs: Long, toMs: Long, now: Long, step: String): Int {
        if (toMs < 0 || toMs < fromMs) return 0
        val lo = Math.floorDiv(maxOf(fromMs, 0L), 1000L); val hi = Math.floorDiv(toMs, 1000L)
        val moments = sensorMomentsBetween(lo, hi)
        if (moments.isEmpty()) return 0
        deleteSensorBetween(lo, hi)
        hideAfterSensorChange(moments, now, step)
        return moments.size
    }

    /**
     * Deletes the Scene rows, their audit rows and their derived fields (the parts are the caller's). A Scene id can come back with other members, so the highest audit seq of the
     * deleted Scenes is kept in `canvas_meta` (`scene_seq_floor`, a counter, no user data): [appendAuditRow] never gives a Scene a seq at or below it, so the input revision of a Scene
     * id never goes back and a derive that started before the deletion is refused when it ends.
     */
    protected suspend fun purgeScenes(ids: List<String>) {
        val top = sceneMaxSeq(ids)
        if (top > (sceneSeqFloorValue()?.toIntOrNull() ?: 0)) putSceneSeqFloor(top.toString())
        deleteSceneRows(ids); deleteSceneAudit(ids); deleteSceneDerived(ids)
    }

    /**
     * Moments were removed (Forget, or the builder): every Scene that held one loses that part. In the SAME transaction (the caller's) the Scene shrinks (window and `membershipRev`),
     * its audit gets PART_REMOVED per lost part (and FORGOTTEN when [step] is `forget`) and its derived fields (SUMMARY, CAPTION, EMBEDDING) are hidden at once: they were built from
     * a part that is gone. A Scene left with no part is deleted with its audit and derived rows. Returns the Scenes touched.
     */
    suspend fun shrinkScenesHolding(momentIds: Collection<String>, step: String, now: Long = System.currentTimeMillis()): Int {
        val chunks = momentIds.toList().chunked(500)
        val scenes = chunks.flatMap { sceneIdsHoldingMoments(it) }.distinct().sorted()
        for (sceneId in scenes) {
            val lost = chunks.flatMap { sceneMomentRefs(sceneId, it) }.distinct().sorted()
            chunks.forEach { deleteSceneMomentParts(sceneId, it) }
            if (scenePartTotal(sceneId) == 0) { purgeScenes(listOf(sceneId)); continue }
            lost.forEach { appendAuditRow("SCENE", sceneId, "PART_REMOVED", now, step, "MOMENT", it); if (step == "forget") appendAuditRow("SCENE", sceneId, "FORGOTTEN", now, step, "MOMENT", it) }
            shrinkSceneRow(sceneId, requireNotNull(scenePartFirstMs(sceneId)), requireNotNull(scenePartLastMs(sceneId)) + 999)
            markStaleCore("SCENE", sceneId, now, step, "MOMENT", lost.singleOrNull(), force = true)
        }
        return scenes.size
    }

    suspend fun appendAuditRow(kind: String, id: String, op: String, atMs: Long, step: String, refKind: String? = null, refId: String? = null): Int {
        val seq = if (kind == "SCENE") maxOf(nextAuditSeq(kind, id), (sceneSeqFloorValue()?.toIntOrNull() ?: 0) + 1) else nextAuditSeq(kind, id)
        insertAuditRow(ObjectAuditEntity(kind, id, seq, op, atMs, step, refKind, refId))
        return seq
    }

    /** Hides every fresh derived field of the object NOW and writes INVALIDATED plus DERIVED_STALE and DERIVED_HIDDEN per field. Returns the fields hidden (0 and no row when nothing was fresh). */
    suspend fun markStaleCore(kind: String, id: String, now: Long, step: String, refKind: String?, refId: String?, force: Boolean = false): Int {
        // A Moment changed or was invalidated (a mark undone, corrected or retagged, a memory edited, a rebuild that changed it): every Scene holding it was built from the old Moment.
        if (kind == "MOMENT") sceneIdsHoldingMoments(listOf(id)).sorted().forEach { markStaleCore("SCENE", it, now, step, "MOMENT", id, force = true) }
        val fields = freshFields(kind, id)
        // [force]: a SOURCE changed. The INVALIDATED row is written even when no field is fresh, because it is the revision a derive that started earlier is checked against.
        if (fields.isEmpty()) { if (force) appendAuditRow(kind, id, "INVALIDATED", now, step, refKind, refId); return 0 }
        staleAll(kind, id, now)
        appendAuditRow(kind, id, "INVALIDATED", now, step, refKind, refId)
        fields.forEach { f -> appendAuditRow(kind, id, "DERIVED_STALE", now, step, "FIELD", f); appendAuditRow(kind, id, "DERIVED_HIDDEN", now, step, "FIELD", f) }
        return fields.size
    }

    /** A SOURCE (a mark, a memory) that Moments hold as a part was removed or changed: hides the derived fields of every Moment holding it. Call inside the source's transaction. */
    suspend fun staleMomentsHolding(partKinds: List<String>, refIds: Collection<String>, step: String = "stale", now: Long = System.currentTimeMillis()): Int {
        if (refIds.isEmpty()) return 0
        val ids = refIds.toList().chunked(500).flatMap { momentIdsHoldingParts(partKinds, it) }.distinct()
        val single = refIds.singleOrNull()?.takeIf { partKinds.size == 1 }
        return ids.sumOf { markStaleCore("MOMENT", it, now, step, if (single != null) partKinds.single() else null, single, force = true) }
    }
}
