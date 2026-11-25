package com.rokid.context.memory.forget.lifecycle

import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.forget.AskSourceStates
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.PHOTO_CITE_PREFIX

/**
 * The registry of the Forget lifecycle (M1). Every Room table of `context.db` and `places.db` is owned by exactly one family here, and `FamilyInventoryTest` fails when a table (read from Room's exported schema),
 * a family or a preference file of the privacy surface is not registered, so a later task cannot add storage and forget to decide who deletes it. Names only: this holds no data.
 * The `dependents` of a family are the typed downstream edges of the EXISTING queries (design 2.2); the planner (M2) and, from M4, the executor read them.
 */
private const val CHUNK = 500

/** The ids of the F6 side-effect nodes of a plan (`forgetV2`): regenerable results and sensor rows a Forget invalidates, named by prefix inside their family. */
internal object SideEffectRefs {
    const val REPLAY = "replay:"; const val SWEEP = "sweep:"; const val JOB = "job:"; const val SENSOR = "sensor:"
    fun isSideEffect(r: Ref) = (r.family == Family.RUN_RESULT && listOf(REPLAY, SWEEP, JOB).any { r.id.startsWith(it) }) || (r.family == Family.MOMENT && r.id.startsWith(SENSOR))
}

object Families {
    val ALL: List<ForgetFamily> = listOf(
        PhotoFamily, MemoryFamily, MomentFamily, SceneFamily, DerivedFieldFamily, MarkFamily, TraceFamily, RunResultFamily,
        AnswerFamily, NudgeFamily, ProposalFamily, AudioFamily, PersonFamily, ObjectFamily, MomentLocationFamily, SceneCentroidFamily, PlaceHistoryFamily, PlacePackFamily, PlaceCardFamily, ConfigFamily, ControlFamily,
    )

    fun of(f: Family): ForgetFamily = ALL.first { it.family == f }

    /** The family that owns [table], or null when none does (the inventory test fails on that). */
    fun ownerOfTable(table: String): Family? = ALL.firstOrNull { table in it.tables }?.family
}

/** Photos: the frame row and everything the capture stored about it. A photo is only ever a root. */
internal object PhotoFamily : ForgetFamily {
    override val family = Family.PHOTO
    override val tables = listOf("frame", "frame_meta", "frame_vector", "frame_state", "frame_source", "frame_cluster", "forget_tombstone")
    override val selfDeletes = true
    /**
     * F1 / F5 (`forgetV2`): the surviving photos whose memory was erased are described again, ONE AT A TIME, by the ordinary extraction pipeline ([com.rokid.context.memory.db.ForgetDao.requeueSolo]). Returns how many were queued.
     * Call it AFTER the memories are deleted (a photo that another memory still cites is not queued).
     */
    suspend fun requeueSolo(ids: Collection<String>, ctx: DeleteCtx): Int = ids.toList().distinct().chunked(CHUNK).sumOf { ctx.db.forgetDao().requeueSolo(it, ctx.now) }
    /** The extraction lock of a USER Forget: a tombstoned photo is never extracted again. Call [unmark] after the radius cleanup that reads the photo's capture time. */
    suspend fun lock(ids: Collection<String>, ctx: DeleteCtx) { ids.forEach { ctx.db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity(it, ctx.now)) } }
    /** Retention v3 (r2-3): a user Forget clears the expired-photo marker in the same transaction as its tombstone, so a forgotten photo is never read as expired. */
    suspend fun unmark(ids: Collection<String>, ctx: DeleteCtx) = com.rokid.context.memory.forget.ExpiryMarks.clear(ctx.db.canvasSlice3Dao(), ids)
    /** The image vectors of [ids] (a photo no surviving memory cites any more). */
    suspend fun deleteVectors(ids: Collection<String>, ctx: DeleteCtx) { ids.toList().chunked(CHUNK).forEach { ctx.db.forgetDao().deleteFrameVectorsFor(it) } }
    /**
     * Retention expiry of one photo: the extraction lock (tombstone), the durable expired-photo marker ([com.rokid.context.memory.forget.ExpiryMarks], carrying the capture time) and the `frame` row, in the caller's ONE transaction. Its
     * metadata, vector, state and sources stay (the photo is a described moment).
     */
    suspend fun expire(id: String, captureTs: Long?, ctx: DeleteCtx) {
        val dao = ctx.db.forgetDao()
        dao.insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity(id, ctx.now))
        com.rokid.context.memory.forget.ExpiryMarks.mark(ctx.db.canvasSlice3Dao(), id, ctx.now, captureTs)
        dao.deleteFrame(id)
    }
    /** ERASE: the frame row with its sources, vector, metadata and state (the tombstone is written by [lock]). Never a REBUILD or DROP. */
    override suspend fun selfDelete(ids: Set<String>, fate: Fate, ctx: DeleteCtx) {
        require(fate == Fate.ERASE) { "a photo is only erased" }
        val dao = ctx.db.forgetDao()
        for (id in ids) { dao.deleteFrame(id); listOf(id).let { dao.deleteFrameSourcesOfFrames(it); dao.deleteFrameVectorsFor(it); dao.deleteFrameMetaFor(it); dao.deleteFrameStateFor(it) } }
    }
    override suspend fun dependents(ids: Set<String>, tx: PlanTx): List<Edge> {
        val fd = tx.db.forgetDao(); val md = tx.db.momentDao(); val out = ArrayList<Edge>()
        tx.chunked(ids) { md.memoryIdsOfFrames(it) }.forEach { out += Edge(family, Ref(Family.MEMORY, it), EdgeKind.ORIGIN) }
        ids.forEach { f -> fd.carriedMemoryIdsOfFrame(f).forEach { out += Edge(family, Ref(Family.MEMORY, it), EdgeKind.CARRIED) } }
        tx.chunked(ids) { md.momentIdsHolding(listOf(MomentPartKind.FRAME, MomentPartKind.CITE), it + it.map { f -> "frame:$f" }) }.forEach { out += Edge(family, Ref(Family.MOMENT, it), EdgeKind.MEMBER) }
        tx.chunked(ids) { md.labelIdsLinkedToFrames(it) }.forEach { out += Edge(family, Ref(Family.MARK, it), EdgeKind.MEMBER) }
        RunResultFamily.keysOf(ids, tx).forEach { out += Edge(family, Ref(Family.RUN_RESULT, it), EdgeKind.LINEAGE) }
        AnswerFamily.citing(ids.map { PHOTO_CITE_PREFIX + it }.toSet(), tx).forEach { out += Edge(family, Ref(Family.ANSWER, it), EdgeKind.CITES) }
        NudgeFamily.citing(ids.map { PHOTO_CITE_PREFIX + it }.toSet(), tx).forEach { out += Edge(family, Ref(Family.NUDGE, it), EdgeKind.CITES) }
        return out
    }
}

/** Memories (a fact, a caption, a summary, a Place Guide memory of type `place` with its `frame_source` FACT row) with their vectors, FTS rows, edges and trace notes. */
internal object MemoryFamily : ForgetFamily {
    override val family = Family.MEMORY
    override val tables = listOf("memory", "memory_fts", "memory_source", "memory_vector", "embedding_space", "summary_trace")
    override val selfDeletes = true
    /** ERASE: rows, vectors in every space, every edge touching them (the FTS triggers follow). REBUILD: INVALIDATED (hidden from feeds and retrieval), without vectors, and (`forgetV2`) without text, tags and search text; the re-summarise worker rebuilds them from the surviving sources. */
    override suspend fun selfDelete(ids: Set<String>, fate: Fate, ctx: DeleteCtx) {
        val dao = ctx.db.forgetDao()
        when (fate) {
            Fate.ERASE -> ids.toList().chunked(CHUNK).forEach { c -> dao.deleteFrameSourcesFor(c); dao.deleteEdgesFrom(c); dao.deleteEdgesTo(c); dao.deleteVectorsFor(c); dao.deleteMemories(c) }
            Fate.REBUILD -> ids.toList().chunked(CHUNK).forEach { c -> dao.invalidate(c); dao.deleteVectorsFor(c); if (ForgetV2.on) dao.blankText(c) } // forgetV2: the text, tags, search text and FTS terms of the placeholder are erased too
            Fate.DROP -> throw UnsupportedOperationException("a memory is erased or rebuilt")
        }
    }
    override suspend fun dependents(ids: Set<String>, tx: PlanTx): List<Edge> {
        val fd = tx.db.forgetDao(); val md = tx.db.momentDao(); val out = ArrayList<Edge>()
        tx.chunked(ids) { fd.childrenOf(it) }.forEach { out += Edge(family, Ref(Family.MEMORY, it), EdgeKind.DERIVED_FROM) }
        tx.chunked(ids) { md.momentIdsHolding(listOf(MomentPartKind.MEMORY, MomentPartKind.SUMMARY), it) }.forEach { out += Edge(family, Ref(Family.MOMENT, it), EdgeKind.MEMBER) }
        AnswerFamily.citing(ids, tx).forEach { out += Edge(family, Ref(Family.ANSWER, it), EdgeKind.CITES) }
        NudgeFamily.citing(ids, tx).forEach { out += Edge(family, Ref(Family.NUDGE, it), EdgeKind.CITES) }
        return out
    }
}

/** The stored Moment projection: a rebuildable read model. */
internal object MomentFamily : ForgetFamily {
    override val family = Family.MOMENT
    override val tables = listOf("moment", "moment_part", "moment_sensor", "moment_tombstone", "moment_trace_dep")
    override val selfDeletes = true
    /** DROP: the stored Moments with their parts, trace deps, sensor rows, audit and derived fields; the Scenes that held them shrink (their derived fields hidden). */
    override suspend fun selfDelete(ids: Set<String>, fate: Fate, ctx: DeleteCtx) { require(fate == Fate.DROP) { "a Moment is a rebuildable projection" }; ctx.db.momentDao().remove(ids.toList()) }
    /** Source-to-projection cleanup: every stored Moment holding one of these deleted sources goes. Returns the Moment ids removed. */
    suspend fun dropHolding(ctx: DeleteCtx, frames: Collection<String>, memories: Collection<String>, labels: Collection<String> = emptyList()): List<String> = ctx.db.momentDao().dropHolding(frames, memories, labels)
    suspend fun dropBetween(ctx: DeleteCtx, fromMs: Long, toMs: Long): List<String> = ctx.db.momentDao().dropBetween(fromMs, toMs)
    /** The Moments that hold a hidden fact elsewhere survive: their text copy is blanked and their derived fields are marked stale. */
    suspend fun blankAndStale(ctx: DeleteCtx, momentIds: Collection<String>, hiddenMemories: Set<String>) {
        momentIds.toList().chunked(CHUNK).forEach { ctx.db.momentDao().blankText(it) }
        ctx.db.canvasSlice3Dao().staleMomentsHolding(listOf(MomentPartKind.MEMORY, MomentPartKind.SUMMARY), hiddenMemories, step = "forget")
    }
    /** F3 (`forgetV2`): the derived fields of the surviving Moments that held a hidden fact are ERASED (v1 only stales them), and so are those of the Scenes that hold them (they are rebuilt). */
    suspend fun eraseDerivedHolding(ctx: DeleteCtx, momentIds: Collection<String>) {
        if (momentIds.isEmpty()) return
        ctx.db.momentDao().eraseDerived(momentIds)
        val scenes = momentIds.toList().chunked(CHUNK).flatMap { ctx.db.sceneDao().sceneIdsHoldingMoments(it) }.distinct()
        if (scenes.isNotEmpty()) SceneFamily.selfDelete(scenes.toSet(), Fate.REBUILD, ctx)
    }
    override suspend fun dependents(ids: Set<String>, tx: PlanTx): List<Edge> {
        val out = ArrayList<Edge>()
        tx.chunked(ids) { tx.db.sceneDao().sceneIdsHoldingMoments(it) }.forEach { out += Edge(family, Ref(Family.SCENE, it), EdgeKind.MEMBER) }
        DerivedFieldFamily.objectsWithRows("MOMENT", ids, tx).forEach { out += Edge(family, Ref(Family.DERIVED_FIELD, "MOMENT:$it"), EdgeKind.ORIGIN) }
        RunResultFamily.keysOf(ids.map { "moment:$it" }.toSet(), tx).forEach { out += Edge(family, Ref(Family.RUN_RESULT, it), EdgeKind.LINEAGE) }
        return out
    }
}

/** Scenes: a rebuildable grouping of Moments. */
internal object SceneFamily : ForgetFamily {
    override val family = Family.SCENE
    override val tables = listOf("scene", "scene_part")
    override val selfDeletes = true
    /** DROP: the Scenes with their parts, audit and derived fields (a Scene a Forget names goes whole). */
    override suspend fun selfDelete(ids: Set<String>, fate: Fate, ctx: DeleteCtx) {
        when (fate) {
            Fate.DROP -> ctx.db.sceneDao().remove(ids.toList())
            Fate.REBUILD -> ctx.db.sceneDao().eraseDerived(ids) // F3 (`forgetV2`): the surviving Scene keeps its Moments, its old derived text is erased
            Fate.ERASE -> throw UnsupportedOperationException("a Scene is rebuilt or dropped")
        }
    }
    override suspend fun dependents(ids: Set<String>, tx: PlanTx): List<Edge> {
        val out = ArrayList<Edge>()
        DerivedFieldFamily.objectsWithRows("SCENE", ids, tx).forEach { out += Edge(family, Ref(Family.DERIVED_FIELD, "SCENE:$it"), EdgeKind.ORIGIN) }
        RunResultFamily.keysOf(ids.map { "scene:$it" }.toSet(), tx).forEach { out += Edge(family, Ref(Family.RUN_RESULT, it), EdgeKind.LINEAGE) }
        return out
    }
}

/** Derived SUMMARY / CAPTION / EMBEDDING fields of Moment-family objects, and their audit. Ref id: `<objectKind>:<objectId>`. */
internal object DerivedFieldFamily : ForgetFamily {
    override val family = Family.DERIVED_FIELD
    override val tables = listOf("object_derived", "object_audit")
    /** Object ids of [kind] among [ids] that have a derived or audit row. */
    suspend fun objectsWithRows(kind: String, ids: Collection<String>, tx: PlanTx): List<String> = tx.chunked(ids) { c ->
        val q = c.joinToString(",") { "?" }
        tx.strings("SELECT objectId FROM object_derived WHERE objectKind = ? AND objectId IN ($q) UNION SELECT objectId FROM object_audit WHERE objectKind = ? AND objectId IN ($q)", listOf<Any>(kind) + c + listOf(kind) + c)
    }
    /** Objects of [kind] among [ids] that have ANY derived field row, fresh or hidden (the text of a hidden field is still stored). */
    suspend fun objectsWithDerived(kind: String, ids: Collection<String>, tx: PlanTx): List<String> = tx.chunked(ids) { c ->
        tx.strings("SELECT DISTINCT objectId FROM object_derived WHERE objectKind = ? AND objectId IN (${c.joinToString(",") { "?" }})", listOf<Any>(kind) + c)
    }
    /** Objects of [kind] among [ids] that have a FRESH derived field (a field a stale mark would hide). */
    suspend fun freshObjects(kind: String, ids: Collection<String>, tx: PlanTx): List<String> = tx.chunked(ids) { c ->
        tx.strings("SELECT DISTINCT objectId FROM object_derived WHERE objectKind = ? AND objectId IN (${c.joinToString(",") { "?" }}) AND staleSince IS NULL", listOf<Any>(kind) + c)
    }
}

/**
 * The optional saved location of a Moment (v18 WP2): a leaf, never a [MomentFamily] table, because `MomentDao.remove` also runs for builder removals (`step = "build"`) and a rebuild must KEEP the row. It is not
 * enumerated by the planner ([DesignDivergence] MOMENT_LOCATION_NOT_PLANNED): the user-Forget step code deletes it by photo or mark anchor, by second and by time, inside the transaction that already deletes the source.
 * Expiry, the sweep, the invalid-Moment purge and the rebuilds keep it.
 */
internal object MomentLocationFamily : ForgetFamily {
    override val family = Family.MOMENT_LOCATION
    override val tables = listOf("moment_location")
    override val selfDeletes = false
}

/**
 * The centroid of a Scene's member embeddings per space (v18 WP10): derived data, a leaf (no [SceneFamily] table: a Scene rebuild must not touch it, the producer replaces it). Not enumerated by the planner
 * ([DesignDivergence] SCENE_CENTROID_NOT_PLANNED). Every Scene deletion deletes it with the Scene (`DerivedStaleDao.purgeScenes`); a USER Forget also deletes every centroid whose Scene changed or is gone
 * ([com.rokid.context.memory.db.SceneCentroidDao.deleteStale], called at the user-Forget choke points); retention, the sweep and the purge keep it while its Scene row (and revision) lives.
 */
internal object SceneCentroidFamily : ForgetFamily {
    override val family = Family.SCENE_CENTROID
    override val tables = listOf("scene_centroid")
    override val selfDeletes = false
}

/** Marks (`canvas_label`: the tap, its intent row). */
internal object MarkFamily : ForgetFamily {
    override val family = Family.MARK
    override val tables = listOf("canvas_label")
    override val selfDeletes = true
    /** ERASE: each mark (and the results computed under it, `labelRev`, the stale Moments holding it, the pruned claims), one by one as the mark undo always did. */
    override suspend fun selfDelete(ids: Set<String>, fate: Fate, ctx: DeleteCtx) { require(fate == Fate.ERASE) { "a mark is erased" }; val s3 = ctx.db.canvasSlice3Dao(); ids.forEach { s3.deleteLabel(it) } }
    /** The marks a range or scope Forget found that no earlier step deleted: rows and claims, then one `labelRev` bump, and the run rows that list them in their lineage. */
    suspend fun deleteFound(ctx: DeleteCtx, found: Collection<String>, lineageOf: Collection<String>) {
        val md = ctx.db.momentDao(); val s3 = ctx.db.canvasSlice3Dao()
        val stray = found.toList().chunked(CHUNK).flatMap { md.labelIdsAmong(it) }
        stray.chunked(CHUNK).forEach { md.deleteLabels(it) }
        if (stray.isNotEmpty()) s3.bumpLabelRev()
        if (lineageOf.isNotEmpty()) s3.deleteRunRowsOfLabels(lineageOf)
    }
    override suspend fun dependents(ids: Set<String>, tx: PlanTx): List<Edge> {
        val out = ArrayList<Edge>()
        tx.chunked(ids) { tx.db.momentDao().momentIdsHolding(listOf(MomentPartKind.MARK), it) }.forEach { out += Edge(family, Ref(Family.MOMENT, it), EdgeKind.MEMBER) }
        // the rows of a run over a MARK Moment list the mark as the lineage token `mf:<labelId>`: the same discovery the delete uses
        tx.db.canvasDao().batchPeersOf(ids.toList()).forEach { out += Edge(family, Ref(Family.RUN_RESULT, it), EdgeKind.LINEAGE) }
        return out
    }
}

/** IMU and hub traces with their events, and the calibration artifacts (sensor history, tied to time). */
internal object TraceFamily : ForgetFamily {
    override val family = Family.TRACE
    override val tables = listOf("canvas_trace", "canvas_trace_event", "canvas_calibration")
    override val selfDeletes = true
    /** ERASE of time ranges: a trace is reached by its time, so each id is a range `"<fromMs>..<toMs>"`. */
    override suspend fun selfDelete(ids: Set<String>, fate: Fate, ctx: DeleteCtx) {
        require(fate == Fate.ERASE) { "a trace is erased" }
        ids.forEach { r -> val (a, b) = r.split("..").map { it.toLong() }; eraseRange(ctx, a, b) }
    }
    /** The durable range fence, the traces overlapping it tombstoned, the sensor rows, the marks inside it, `labelRev`, the helper jobs voided, the results that read any of it invalidated. */
    suspend fun eraseRange(ctx: DeleteCtx, fromMs: Long, toMs: Long) = ctx.db.canvasSlice3Dao().forgetRangeTx(fromMs, toMs, ctx.now)
}

/** Regenerable run results (the canvas runs, their items, scores and live traces, replays, sweeps, helper jobs). Ref id: the ROW KEY (a photo id, `moment:<id>`, `scene:<id>`). */
internal object RunResultFamily : ForgetFamily {
    override val family = Family.RUN_RESULT
    override val tables = listOf("canvas_run", "canvas_run_item", "canvas_score", "canvas_live_trace", "canvas_replay", "canvas_sweep", "canvas_helper_job")
    override val selfDeletes = true
    /** DROP: the run rows (items, scores, live traces) of [ids] as row keys; the next run recreates them. */
    override suspend fun selfDelete(ids: Set<String>, fate: Fate, ctx: DeleteCtx) { require(fate == Fate.DROP) { "a run result is regenerable" }; if (ids.isNotEmpty()) ctx.db.canvasDao().deleteCanvasOutputsForFrames(ids.toList()) }
    /** The marks of the photos, the results that read them and the helper jobs that carry them (slice 3a). */
    suspend fun dropSliceOutputs(ctx: DeleteCtx, frameIds: List<String>) = ctx.db.canvasSlice3Dao().deleteSliceOutputsForFrames(frameIds, ctx.now)
    /** A photo that is only LOCKED (outside the planned spans) keeps itself, but the stored outputs and DONE helper results of it may carry the forgotten fact's text. */
    suspend fun scrubLocked(ctx: DeleteCtx, frames: List<String>) { ctx.db.canvasDao().deleteCanvasOutputsForFrames(frames); ctx.db.canvasSlice3Dao().scrubOutputsOfLockedFrames(frames, ctx.now) }

    /** The row keys with a stored result among [keys], plus the batch peers that share a provider call with them (the cascade is batch-wide on purpose). */
    suspend fun keysOf(keys: Set<String>, tx: PlanTx): Set<String> {
        if (keys.isEmpty()) return emptySet()
        val own = tx.chunked(keys) { c ->
            val q = c.joinToString(",") { "?" }
            tx.strings("SELECT frameId FROM canvas_run_item WHERE frameId IN ($q) UNION SELECT frameId FROM canvas_score WHERE frameId IN ($q) UNION SELECT frameId FROM canvas_live_trace WHERE frameId IN ($q)", c + c + c)
        }
        return own.toSet() + tx.db.canvasDao().batchPeersOf(keys.toList())
    }
}

/** Saved answers (`ask`). */
internal object AnswerFamily : ForgetFamily {
    override val family = Family.ANSWER
    override val tables = listOf("ask")
    override val selfDeletes = true
    /**
     * REBUILD. v1 (flag off): redacted in place, the question stays, the answer and its sources are cleared. `forgetV2` on (M5, A1): the same erasure, and the source state becomes SOURCE_FORGOTTEN ("Answered from a
     * forgotten fact", the question stays, the row offers "Ask again"; there is NO automatic re-answer). A developer eval ask keeps the v1 redaction.
     */
    override suspend fun selfDelete(ids: Set<String>, fate: Fate, ctx: DeleteCtx) {
        require(fate == Fate.REBUILD) { "an answer is rebuilt (redacted in place), never erased as a dependent" }
        val dao = ctx.db.forgetDao()
        if (!ForgetV2.on) { ids.forEach { dao.redactAsk(it, ForgetService.REDACTED) }; return }
        val eval = ids.chunked(500).flatMap { ctx.db.askDao().byIds(it) }.filter { it.source == AskSourceStates.EVAL_SOURCE }.map { it.id }.toSet()
        ids.forEach { if (it in eval) dao.redactAsk(it, ForgetService.REDACTED) else dao.redactAskForgotten(it, ForgetService.REDACTED, AskSourceStates.forgotten()) }
    }
    /** The asks (not yet redacted) hit by [cited]. v1: whose sources, or `dependsOn` ids, meet it (exactly the match `ForgetService.cascade` redacts by). `forgetV2` on (A1, A2, ruling F4): whose stored ids (cited and `dependsOn`), cited ids or history items meet it (conservative). */
    suspend fun citing(cited: Set<String>, tx: PlanTx, v2: Boolean = false): List<String> {
        val dao = tx.db.forgetDao()
        val byJson = cited.flatMap { dao.asksCiting(ForgetService.likeFor(it)) }.distinctBy { it.id }
        if (!v2) return byJson.filter { a -> ForgetService.parseIds(a.sourcesJson).any { it in cited } }.map { it.id }
        val byStates = cited.flatMap { dao.asksCitingStates(ForgetService.likeFor(it)) }
        return (byJson + byStates).distinctBy { it.id }.filter { a -> AskSourceStates.dependenciesV2(a).any { it in cited } }.map { it.id }
    }
    override suspend fun dependents(ids: Set<String>, tx: PlanTx): List<Edge> = ProposalFamily.of(ids, tx).map { Edge(family, Ref(Family.PROPOSAL, it), EdgeKind.ORIGIN) }
}

/** Nudges. */
internal object NudgeFamily : ForgetFamily {
    override val family = Family.NUDGE
    override val tables = listOf("nudge")
    override val selfDeletes = true
    /** REBUILD (v1): redacted in place. ERASE (`forgetV2`, N1): the row is deleted (a nudge is time-bound; regenerating it would show a stale one). */
    override suspend fun selfDelete(ids: Set<String>, fate: Fate, ctx: DeleteCtx) {
        require(fate == Fate.REBUILD || fate == Fate.ERASE) { "a nudge is redacted (v1) or erased (N1)" }
        val dao = ctx.db.forgetDao()
        ids.forEach { if (fate == Fate.ERASE) dao.deleteNudge(it) else dao.redactNudge(it, ForgetService.REDACTED) }
    }
    suspend fun citing(cited: Set<String>, tx: PlanTx): List<String> {
        val dao = tx.db.forgetDao()
        return cited.flatMap { dao.nudgesCiting(ForgetService.likeFor(it)) }.distinctBy { it.id }.filter { n -> ForgetService.parseIds(n.sourceIdsJson).any { it in cited } }.map { it.id }
    }
    override suspend fun dependents(ids: Set<String>, tx: PlanTx): List<Edge> = ProposalFamily.of(ids, tx).map { Edge(family, Ref(Family.PROPOSAL, it), EdgeKind.ORIGIN) }
}

/**
 * The answers and nudges one Forget reaches from the ids it removed ([cited]). The ONE rule shared by the planner (preview, plan hash) and the executor (delete), so preview == delete.
 * v1 (flag off): one pass, every ask or nudge whose stored ids meet [cited]. `forgetV2` on: answers by ALL their stored dependencies (conservative, ruling F4) and history items (A1, A2), nudges by any dependency (N1: ERASE), and the items
 * reached are themselves history, so the answers that used a reached answer or nudge follow (A2), to a fixpoint.
 */
internal object AskRules {
    class Reach(val asks: List<String>, val nudges: List<String>)

    suspend fun reach(cited: Set<String>, tx: PlanTx, v2: Boolean, skipAsks: Set<String> = emptySet(), skipNudges: Set<String> = emptySet()): Reach {
        if (!v2) return Reach(AnswerFamily.citing(cited, tx).filter { it !in skipAsks }, NudgeFamily.citing(cited, tx).filter { it !in skipNudges })
        val seenAsks = LinkedHashSet<String>(); val seenNudges = LinkedHashSet<String>()
        var frontier = cited
        while (frontier.isNotEmpty()) {
            val a = AnswerFamily.citing(frontier, tx, v2 = true).filter { it !in skipAsks && seenAsks.add(it) }
            val n = NudgeFamily.citing(frontier, tx).filter { it !in skipNudges && seenNudges.add(it) }
            frontier = (a.map { AskSourceStates.ASK_PREFIX + it } + n.map { AskSourceStates.NUDGE_PREFIX + it }).toSet()
        }
        return Reach(seenAsks.toList(), seenNudges.toList())
    }
}

/** Proposals and their action log: they come from an ask or a nudge (their origin), so they follow it. */
internal object ProposalFamily : ForgetFamily {
    override val family = Family.PROPOSAL
    override val tables = listOf("proposal", "action_log")
    override val selfDeletes = true
    /** REBUILD of the proposals of the origins [ids] (asks and nudges that were redacted): PENDING expires, params and log details are scrubbed. */
    override suspend fun selfDelete(ids: Set<String>, fate: Fate, ctx: DeleteCtx) { require(fate == Fate.REBUILD); expireAndScrub(ctx, ids.toList(), scrubAccepted = true) }
    /** Returns the ids of the proposals whose notifications and alarms must go. Retention ([scrubAccepted] false) leaves the accepted ones (EXECUTING, EXECUTED) as they are. */
    suspend fun expireAndScrub(ctx: DeleteCtx, origins: List<String>, scrubAccepted: Boolean): List<String> {
        if (origins.isEmpty()) return emptyList()
        ctx.proposals.expireForOrigins(origins, ctx.now)
        val actions = ctx.db.actionDao()
        return if (scrubAccepted) {
            origins.chunked(CHUNK).flatMap { actions.allIdsForOrigins(it) }.also { _ -> origins.chunked(CHUNK).forEach { c -> actions.scrubLogsForOrigins(c); actions.scrubParamsForOrigins(c, ForgetService.REDACTED_PARAMS) } }
        } else {
            origins.chunked(CHUNK).flatMap { actions.idsForOriginsExcept(it, ACCEPTED) }.also { ids -> ids.chunked(CHUNK).forEach { c -> actions.scrubLogs(c); actions.scrubParams(c, ForgetService.REDACTED_PARAMS) } }
        }
    }
    private val ACCEPTED = listOf(com.rokid.context.memory.ProposalState.EXECUTING.name, com.rokid.context.memory.ProposalState.EXECUTED.name)
    /** The proposals of the origins [originIds] (any state). */
    suspend fun of(originIds: Collection<String>, tx: PlanTx): List<String> = tx.chunked(originIds) { tx.db.actionDao().allIdsForOrigins(it) }
}

/** Reserved: no store yet (`moment.transcript` is a null scaffold). The inventory test fails the day one gets a table. */
internal object AudioFamily : ForgetFamily { override val family = Family.AUDIO; override val tables = emptyList<String>(); override val reserved = true }
internal object PersonFamily : ForgetFamily { override val family = Family.PERSON; override val tables = emptyList<String>(); override val reserved = true }
internal object ObjectFamily : ForgetFamily { override val family = Family.OBJECT; override val tables = emptyList<String>(); override val reserved = true }

/**
 * The phone-only place card history (`place_card_log`, context.db v18, WP3): a leaf. A user Forget of the photo, any user span and Delete all delete its rows INSIDE the transaction that already deletes the source
 * (`dropCanvasOutputs`, `forgetRangeTx`, `wipeMemory`); photo retention only sets `photoGone` and keeps the `frameId`; pin removal deletes the rows of the pin before the pin store is written (`PlaceCardHistory`). The
 * planner does not enumerate it ([DesignDivergence] PLACE_HISTORY_NOT_PLANNED).
 */
internal object PlaceHistoryFamily : ForgetFamily {
    override val family = Family.PLACE_HISTORY
    override val tables = listOf("place_card_log")
    // not a self-deleting family: the planner never plans it, the step code deletes it by photo and by time, and "Delete from history" is PlaceCardHistory.delete (a side delete)

    /** A user Forget of these photos: their rows go (same transaction as the photo). */
    suspend fun eraseFrames(db: com.rokid.context.memory.db.ContextDatabase, frameIds: Collection<String>): Int =
        frameIds.distinct().chunked(CHUNK).sumOf { db.placeCardLogDao().deleteCardLogForFrames(it) }

    /** Photo retention: `photoGone = 1`, the `frameId` stays as private lineage (a later user Forget of the photo still deletes the row). */
    suspend fun unlinkExpired(db: com.rokid.context.memory.db.ContextDatabase, frameIds: Collection<String>): Int =
        frameIds.distinct().chunked(CHUNK).sumOf { db.placeCardLogDao().markCardLogPhotoGone(it) }
}

/**
 * `places.db` (the fetched place pack): public place text (Wikipedia, Wikidata, OpenStreetMap), NOT derived from a photo, never reached by a Forget. Delete all clears the fetched places and the recency and keeps
 * seeded areas, what they seeded and pinned places (`PlacePack.deleteAll`). Its preference file is the Place Guide switch.
 */
internal object PlacePackFamily : ForgetFamily {
    override val family = Family.PLACE_PACK
    override val tables = listOf("place", "cell", "area")
    override val prefs = listOf("place_guide", "place_pins")
}

/**
 * The published Place Guide card: held in memory only (`PlaceCardHolder`), never stored. A Forget of its photo, a range Forget and Delete all WITHDRAW it (a `ForgetListener`).
 * Also the in-memory DERIVED holders of the Place Guide shadow (1.4.4 B1): the image vectors of a pin's reference photo (`PinReferenceVectors`) and the text vectors of pack places (`PlaceShadow`). Never stored; emptied at once
 * by Delete all, every Forget listener event and every pin link change through the `ShadowCaches` registry (listed in [com.rokid.context.memory.forget.DeletionGraph.IN_MEMORY]), and revalidated on every use.
 */
internal object PlaceCardFamily : ForgetFamily {
    override val family = Family.PLACE_CARD
    override val tables = emptyList<String>()
    override val holders = listOf("PlaceCardHolder", "PinReferenceVectors", "PlaceShadow", "PackMomentStats")
}

/** Settings and user lists a Forget never touches: endpoints, role bindings, prompts, pauses, the shopping list, notes, pipelines and publishes. Pause zones (`pause_zones`, `pause_zone_state`) are preferences: never deleted by a Forget or Delete all. */
internal object ConfigFamily : ForgetFamily {
    override val family = Family.CONFIG
    override val tables = listOf(
        "endpoint", "role_binding", "prompt", "prompt_version", "pause_record", "pause_interval", "shopping_item", "note", "canvas_pipeline", "canvas_pipeline_version", "canvas_publish",
    )
    override val prefs = listOf("pause_zones", "pause_zone_state", "nexus_host")
}

/** Fences, counters and the cleanup outbox: control data a deletion CREATES, no user content. */
internal object ControlFamily : ForgetFamily {
    override val family = Family.CONTROL
    override val tables = listOf("canvas_meta", "canvas_group_state", "canvas_helper_cleanup", "cleanup_outbox")
}
