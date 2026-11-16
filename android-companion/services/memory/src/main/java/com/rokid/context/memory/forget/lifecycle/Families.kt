package com.rokid.context.memory.forget.lifecycle

import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.PHOTO_CITE_PREFIX

/**
 * The registry of the Forget lifecycle (M1). Every Room table of `context.db` and `places.db` is owned by exactly one family here, and `FamilyInventoryTest` fails when a table (read from Room's exported schema),
 * a family or a preference file of the privacy surface is not registered, so a later task cannot add storage and forget to decide who deletes it. Names only: this holds no data.
 * The `dependents` of a family are the typed downstream edges of the EXISTING queries (design 2.2); the planner (M2) and, from M4, the executor read them.
 */
object Families {
    val ALL: List<ForgetFamily> = listOf(
        PhotoFamily, MemoryFamily, MomentFamily, SceneFamily, DerivedFieldFamily, MarkFamily, TraceFamily, RunResultFamily,
        AnswerFamily, NudgeFamily, ProposalFamily, AudioFamily, PersonFamily, ObjectFamily, PlacePackFamily, PlaceCardFamily, ConfigFamily, ControlFamily,
    )

    fun of(f: Family): ForgetFamily = ALL.first { it.family == f }

    /** The family that owns [table], or null when none does (the inventory test fails on that). */
    fun ownerOfTable(table: String): Family? = ALL.firstOrNull { table in it.tables }?.family
}

/** Photos: the frame row and everything the capture stored about it. A photo is only ever a root. */
internal object PhotoFamily : ForgetFamily {
    override val family = Family.PHOTO
    override val tables = listOf("frame", "frame_meta", "frame_vector", "frame_state", "frame_source", "frame_cluster", "forget_tombstone")
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
    /** Objects of [kind] among [ids] that have a FRESH derived field (a field a stale mark would hide). */
    suspend fun freshObjects(kind: String, ids: Collection<String>, tx: PlanTx): List<String> = tx.chunked(ids) { c ->
        tx.strings("SELECT DISTINCT objectId FROM object_derived WHERE objectKind = ? AND objectId IN (${c.joinToString(",") { "?" }}) AND staleSince IS NULL", listOf<Any>(kind) + c)
    }
}

/** Marks (`canvas_label`: the tap, its intent row). */
internal object MarkFamily : ForgetFamily {
    override val family = Family.MARK
    override val tables = listOf("canvas_label")
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
}

/** Regenerable run results (the canvas runs, their items, scores and live traces, replays, sweeps, helper jobs). Ref id: the ROW KEY (a photo id, `moment:<id>`, `scene:<id>`). */
internal object RunResultFamily : ForgetFamily {
    override val family = Family.RUN_RESULT
    override val tables = listOf("canvas_run", "canvas_run_item", "canvas_score", "canvas_live_trace", "canvas_replay", "canvas_sweep", "canvas_helper_job")

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
    /** The asks (not yet redacted) whose sources, or `dependsOn` ids, meet [cited]: exactly the match `ForgetService.cascade` redacts by. */
    suspend fun citing(cited: Set<String>, tx: PlanTx): List<String> {
        val dao = tx.db.forgetDao()
        return cited.flatMap { dao.asksCiting(ForgetService.likeFor(it)) }.distinctBy { it.id }.filter { a -> ForgetService.parseIds(a.sourcesJson).any { it in cited } }.map { it.id }
    }
    override suspend fun dependents(ids: Set<String>, tx: PlanTx): List<Edge> = ProposalFamily.of(ids, tx).map { Edge(family, Ref(Family.PROPOSAL, it), EdgeKind.ORIGIN) }
}

/** Nudges. */
internal object NudgeFamily : ForgetFamily {
    override val family = Family.NUDGE
    override val tables = listOf("nudge")
    suspend fun citing(cited: Set<String>, tx: PlanTx): List<String> {
        val dao = tx.db.forgetDao()
        return cited.flatMap { dao.nudgesCiting(ForgetService.likeFor(it)) }.distinctBy { it.id }.filter { n -> ForgetService.parseIds(n.sourceIdsJson).any { it in cited } }.map { it.id }
    }
    override suspend fun dependents(ids: Set<String>, tx: PlanTx): List<Edge> = ProposalFamily.of(ids, tx).map { Edge(family, Ref(Family.PROPOSAL, it), EdgeKind.ORIGIN) }
}

/** Proposals and their action log: they come from an ask or a nudge (their origin), so they follow it. */
internal object ProposalFamily : ForgetFamily {
    override val family = Family.PROPOSAL
    override val tables = listOf("proposal", "action_log")
    /** The proposals of the origins [originIds] (any state). */
    suspend fun of(originIds: Collection<String>, tx: PlanTx): List<String> = tx.chunked(originIds) { tx.db.actionDao().allIdsForOrigins(it) }
}

/** Reserved: no store yet (`moment.transcript` is a null scaffold). The inventory test fails the day one gets a table. */
internal object AudioFamily : ForgetFamily { override val family = Family.AUDIO; override val tables = emptyList<String>(); override val reserved = true }
internal object PersonFamily : ForgetFamily { override val family = Family.PERSON; override val tables = emptyList<String>(); override val reserved = true }
internal object ObjectFamily : ForgetFamily { override val family = Family.OBJECT; override val tables = emptyList<String>(); override val reserved = true }

/**
 * `places.db` (the fetched place pack): public place text (Wikipedia, Wikidata, OpenStreetMap), NOT derived from a photo, never reached by a Forget. Delete all clears the fetched places and the recency and keeps
 * seeded areas, what they seeded and pinned places (`PlacePack.deleteAll`). Its preference file is the Place Guide switch.
 */
internal object PlacePackFamily : ForgetFamily {
    override val family = Family.PLACE_PACK
    override val tables = listOf("place", "cell", "area")
    override val prefs = listOf("place_guide")
}

/** The published Place Guide card: held in memory only (`PlaceCardHolder`), never stored. A Forget of its photo, a range Forget and Delete all WITHDRAW it (a `ForgetListener`). */
internal object PlaceCardFamily : ForgetFamily {
    override val family = Family.PLACE_CARD
    override val tables = emptyList<String>()
    override val holders = listOf("PlaceCardHolder")
}

/** Settings and user lists a Forget never touches: endpoints, role bindings, prompts, pauses, the shopping list, notes, pipelines and publishes. Pause zones (`pause_zones`, `pause_zone_state`) are preferences: never deleted by a Forget or Delete all. */
internal object ConfigFamily : ForgetFamily {
    override val family = Family.CONFIG
    override val tables = listOf(
        "endpoint", "role_binding", "prompt", "prompt_version", "pause_record", "pause_interval", "shopping_item", "note", "canvas_pipeline", "canvas_pipeline_version", "canvas_publish",
    )
    override val prefs = listOf("pause_zones", "pause_zone_state")
}

/** Fences, counters and the cleanup outbox: control data a deletion CREATES, no user content. */
internal object ControlFamily : ForgetFamily {
    override val family = Family.CONTROL
    override val tables = listOf("canvas_meta", "canvas_group_state", "canvas_helper_cleanup", "cleanup_outbox")
}
