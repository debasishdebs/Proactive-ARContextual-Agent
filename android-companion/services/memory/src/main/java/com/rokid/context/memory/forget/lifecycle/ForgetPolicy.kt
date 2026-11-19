package com.rokid.context.memory.forget.lifecycle

/** What a Forget does to a family of children, per source. [CASCADE_BY_EXISTING_RULES] = "the planner / executor / DAO rules that exist today decide, row by row": the table does not restate them. */
enum class PolicyAction {
    /** The children are deleted (and, if the user is asked, named or counted in the confirmation). */
    FORGET,
    /** Only the LINK to the removed object is cleared; the child itself survives (a pin shows "Needs a Moment"). */
    DE_LINK,
    /** Decided by the existing Fate rules of [ForgetPlanner] / [ForgetExecutor] (ERASE / REBUILD / DROP per row). */
    CASCADE_BY_EXISTING_RULES,
    /** Untouched by a Forget of this source. */
    KEEP,
}

/**
 * A family of things a Forget can reach or leave alone: every lifecycle [Family] plus the families that today live INSIDE another one or outside the database (embeddings, files, pins, glasses state, logs).
 * [lifecycle] is the registered [Family] (null for the extra ones). [fewItems]: the plan names the items (pins, marks) instead of only counting them.
 */
enum class ForgetChild(val lifecycle: Family?, val fewItems: Boolean = false) {
    PHOTO(Family.PHOTO), MEMORY(Family.MEMORY), MOMENT(Family.MOMENT), SCENE(Family.SCENE), DERIVED_FIELD(Family.DERIVED_FIELD),
    MARK(Family.MARK, fewItems = true), TRACE(Family.TRACE), RUN_RESULT(Family.RUN_RESULT),
    ANSWER(Family.ANSWER), NUDGE(Family.NUDGE), PROPOSAL(Family.PROPOSAL),
    AUDIO(Family.AUDIO), PERSON(Family.PERSON), OBJECT(Family.OBJECT),
    PLACE_PACK(Family.PLACE_PACK), PLACE_CARD(Family.PLACE_CARD), CONFIG(Family.CONFIG), CONTROL(Family.CONTROL),
    /** memory_vector, frame_vector, embedding_space: today inside PHOTO / MEMORY ([MemoryFamily], [PhotoFamily] tables). */
    EMBEDDING(null),
    /** Frame JPEGs, `.meta.json` sidecars, trace folders and the cached export. There is no separate thumbnail store: thumbnails are rendered from the JPEG (`FrameImages.kt:134`), so they go with it. */
    FILES(null),
    /** User pins (`place_pins` prefs, [UserPinStore]); a pin is user-authored, so only an explicit source deletes it. */
    PIN(null, fewItems = true),
    /** Glasses-side state: the cleanup outbox, `pc_trace_ctl`, the deletion epoch. */
    GLASSES(null),
    /** `pca.log` files (counts only in a log line; the files themselves are wiped by Delete all only). */
    LOGS(null);

    companion object {
        /** The policy child of a registered lifecycle family. Throws for a family with no row: a new [Family] must add its [ForgetChild] (and its policy row) first. */
        fun of(family: Family): ForgetChild = entries.firstOrNull { it.lifecycle == family } ?: error("no ForgetChild / policy row for Family.$family: add one in ForgetPolicy.kt")
    }
}

/** One cell: the [action] and the code that implements it TODAY ([at], `file:line`, see [ForgetPolicy] for the short names). [verified] = false when the cell comes from the coverage map and was not re-read at that line. */
data class PolicyRule(val action: PolicyAction, val at: String, val verified: Boolean = true)

/** One row: a child family under the five source groups. [user] covers SINGLE, MULTI, RANGE and SCOPE (the same rules today: all go through the `Policy.User` / `Policy.Spans` cascade). */
class PolicyRow(val child: ForgetChild, val user: PolicyRule, val deleteAll: PolicyRule, val expiry: PolicyRule, val sweep: PolicyRule, val purge: PolicyRule) {
    fun rule(kind: ForgetSourceKind): PolicyRule = when (kind) {
        ForgetSourceKind.SINGLE, ForgetSourceKind.MULTI, ForgetSourceKind.RANGE, ForgetSourceKind.SCOPE -> user
        ForgetSourceKind.DELETE_ALL -> deleteAll
        ForgetSourceKind.SYSTEM_EXPIRY -> expiry
        ForgetSourceKind.SYSTEM_SWEEP -> sweep
        ForgetSourceKind.SYSTEM_PURGE -> purge
    }
}

/**
 * THE policy table of the Forget redesign (step 1): (child family x source kind) -> [PolicyAction], encoding TODAY's behaviour exactly. Nothing reads it in production yet (step 2 wires it in), so it changes no
 * behaviour. A new family adds ONE row here and one test; `ForgetPolicyTest` fails when a [Family] or a [ForgetChild] has no row, and when a row has no evidence.
 *
 * Short names in the `at` evidence (paths from `android-companion/services/`): FP = `memory/.../forget/lifecycle/ForgetPlanner.kt`, FE = `.../lifecycle/ForgetExecutor.kt`, FAM = `.../lifecycle/Families.kt`,
 * PML = `orchestration/.../work/frames/place/PinMomentLinks.kt`, MW = `orchestration/.../orchestration/MemoryWipe.kt`, WD = `memory/.../db/WipeDao.kt`, RW = `orchestration/.../forget/RetentionWorker.kt`,
 * IMP = `orchestration/.../canvas/moment/InvalidMomentPurge.kt`, DG = `memory/.../forget/DeletionGraph.kt`. Line numbers are of the tree at 2026-11-20 (1d91f33a).
 * A cell marked `unverified` was taken from `docs/superpowers/plans/2026-11-20-forget-coverage-map.md` and not re-read; step 2 must confirm those before relying on them.
 */
object ForgetPolicy {
    private fun r(a: PolicyAction, at: String, verified: Boolean = true) = PolicyRule(a, at, verified)
    private val F = PolicyAction.FORGET; private val L = PolicyAction.DE_LINK; private val C = PolicyAction.CASCADE_BY_EXISTING_RULES; private val K = PolicyAction.KEEP

    val rows: List<PolicyRow> = listOf(
        // ---- the objects a Forget targets ----
        PolicyRow(ForgetChild.PHOTO,
            user = r(F, "FP:166-184 photo(): frame row ERASE; FP:109 cited photos ERASE"), deleteAll = r(F, "WD:21-27 frame_source, vector, cluster, state, meta, frame, tombstones deleted"),
            expiry = r(C, "FP:187-198 expirePhotos: frame row + tombstone only, meta/vector/memories stay; FAM:48 expireRows"), sweep = r(K, "FE:153-157 removeDescriptions: photos kept (lockFrames=false)"),
            purge = r(K, "IMP:20 doc: a photo is NOT touched")),
        PolicyRow(ForgetChild.MEMORY,
            user = r(F, "FP:32-35 Policy.User, FP:102 forgotten memories ERASE"), deleteAll = r(F, "WD:20 DELETE FROM memory"),
            expiry = r(F, "FP:39 Policy.Retention (memories by tier ERASE, descendants not rebuilt); FE:146-152"), sweep = r(F, "FE:153-158 removeDescriptions (Policy.User, memory ERASE)"),
            purge = r(F, "IMP:69 orphan memories via service.forgetFacts (user policy)")),
        PolicyRow(ForgetChild.MOMENT,
            user = r(C, "FP:107,181,216 dropHolding / removeMoments (DROP or REBUILD per row)"), deleteAll = r(F, "WD:60-63 DELETE FROM moment_part, moment_trace_dep, moment, moment_tombstone"),
            expiry = r(C, "FP:196 dropHolding(expired photos)"), sweep = r(C, "FP:107 dropHolding(inside, forgotten + invalidated)", verified = false),
            purge = r(F, "IMP:60-65 md.insertTombstones + md.remove(invalid)")),
        PolicyRow(ForgetChild.SCENE,
            user = r(C, "FP:219-223 scoped scenes DROP / REBUILD (FE:68 rebuildScenes)"), deleteAll = r(F, "WD:70-71 DELETE FROM scene_part, scene"),
            expiry = r(C, "FP:196 dropHolding shrinks holding Scenes", verified = false), sweep = r(C, "FP:107 dropHolding shrinks holding Scenes", verified = false),
            purge = r(C, "IMP:17 doc: every Scene holding an invalid Moment shrinks and hides its derived fields")),
        PolicyRow(ForgetChild.DERIVED_FIELD,
            user = r(C, "FAM:145 DerivedFieldFamily; FP:216-217 hidden with the holding Moment / Scene"), deleteAll = r(F, "WD:68 DELETE FROM object_derived"),
            expiry = r(C, "FP:196 with the holding Moment", verified = false), sweep = r(C, "FP:107 with the holding Moment", verified = false), purge = r(C, "IMP:17 md.remove deletes derived fields")),
        PolicyRow(ForgetChild.MARK,
            user = r(F, "FP:239-246 marks(): explicit mark ERASE; FP:255 marks linked to a forgotten photo ERASE"), deleteAll = r(F, "WD:46 DELETE FROM canvas_label"),
            expiry = r(C, "FP:197 -> FP:249-255 photo expiry ERASEs marks in the +/- radius (coverage map G9: needs a ruling)"), sweep = r(K, "FE:153-157 lockFrames=false: no mark step in cascade()"),
            purge = r(K, "IMP:20 doc: a bare mark stays, only its Moment goes")),
        PolicyRow(ForgetChild.TRACE,
            user = r(C, "FP:228-229 range(): overlapping traces ERASE; FP:249-252 radius cascade"), deleteAll = r(F, "DG:126 DIRS canvas_traces WIPED; WD:42-43 canvas_trace_event, canvas_trace"),
            expiry = r(C, "FP:197 radius traces ERASE"), sweep = r(K, "FE:153-157 lockFrames=false: no radius step", verified = false), purge = r(C, "IMP:58-66 canvas items, scores and traces keyed by the Moment", verified = false)),
        PolicyRow(ForgetChild.RUN_RESULT,
            user = r(C, "FP:258 runResults DROP; FP:243-245 lineage DROP"), deleteAll = r(F, "WD:35-38 DELETE FROM canvas_run_item, canvas_score, canvas_run, canvas_live_trace"),
            expiry = r(C, "FP:197 -> FP:253 runResults DROP"), sweep = r(K, "FE:153-157 no photo step, no run-result keys", verified = false), purge = r(C, "IMP:18 run rows keyed by the Moment")),
        PolicyRow(ForgetChild.ANSWER,
            user = r(C, "FP:118-120 AskRules.reach -> REBUILD (redact)"), deleteAll = r(F, "WD:28 DELETE FROM ask"),
            expiry = r(C, "FP:39 + FP:118-120 retention cascade reaches cited asks"), sweep = r(C, "FE:153-158 cascade reaches cited asks"), purge = r(C, "IMP:19 forgetFacts: cited asks")),
        PolicyRow(ForgetChild.NUDGE,
            user = r(C, "FP:121 nudge REBUILD (v1) / ERASE (v2)"), deleteAll = r(F, "WD:29 DELETE FROM nudge"),
            expiry = r(C, "FP:121 via the retention cascade"), sweep = r(C, "FP:121 via the cascade"), purge = r(C, "IMP:19 forgetFacts: cited nudges")),
        PolicyRow(ForgetChild.PROPOSAL,
            user = r(C, "FP:122,147-152 PENDING expire, params scrubbed"), deleteAll = r(F, "WD:30,32 open proposals deleted, params scrubbed"),
            expiry = r(C, "FP:149 scrubAccepted=false: accepted proposals kept"), sweep = r(C, "FP:122 via the cascade"), purge = r(C, "IMP:19 forgetFacts")),
        reserved(ForgetChild.AUDIO, "FAM:314 reserved family, no store"),
        reserved(ForgetChild.PERSON, "FAM:315 reserved family, no store"),
        reserved(ForgetChild.OBJECT, "FAM:316 reserved family, no store"),
        PolicyRow(ForgetChild.PLACE_PACK,
            user = r(K, "FAM:322-325 'never a deletion target'"), deleteAll = r(C, "DG:28-86 TABLES: place/cell SCRUBBED, area KEPT (FamilyInventoryTest.thePlacePackIsNotPhotoDerivedAndDeleteAllKeepsItsSeededAreas)"),
            expiry = r(K, "FAM:322-325"), sweep = r(K, "FAM:322-325"), purge = r(K, "FAM:322-325")),
        PolicyRow(ForgetChild.PLACE_CARD,
            user = r(K, "FAM:329 in-memory card, never a deletion target"), deleteAll = r(C, "FAM:329 PlaceCardHolder cleared with the pack", verified = false),
            expiry = r(K, "FAM:329"), sweep = r(K, "FAM:329"), purge = r(K, "FAM:329")),
        PolicyRow(ForgetChild.CONFIG,
            user = r(K, "FAM:336 settings and user lists, never a deletion target"), deleteAll = r(C, "MW:59,179 WIPED_PREFS cleared; DG PREFS decide kept vs wiped"),
            expiry = r(K, "FAM:336"), sweep = r(K, "FAM:336"), purge = r(K, "FAM:336")),
        PolicyRow(ForgetChild.CONTROL,
            user = r(C, "FP:134 fence; tombstones written by FAM:44 PhotoFamily.lock"), deleteAll = r(C, "WD:27 tombstones deleted; DG counters / fences"),
            expiry = r(C, "FAM:48 expireRows leaves a tombstone"), sweep = r(C, "FE:153 fence moves with every Forget", verified = false), purge = r(C, "IMP:60 moment_tombstone per second")),
        // ---- families that live inside another one or outside the database ----
        PolicyRow(ForgetChild.EMBEDDING,
            user = r(F, "FAM:77-78 deleteVectorsFor with the memory; FAM:46 PhotoFamily.deleteVectors, frame vector with the photo"), deleteAll = r(F, "WD:22 DELETE FROM frame_vector; memory vectors with memory"),
            expiry = r(C, "FAM:77 memory vector goes with the memory; photo expiry keeps the frame vector (FAM:48); RW:141 vector expiry is its own raw delete"), sweep = r(F, "FAM:78 REBUILD deletes the memory vectors"),
            purge = r(F, "IMP:69 forgetFacts deletes vectors")),
        PolicyRow(ForgetChild.FILES,
            user = r(F, "FE:250-255 queueCleanup: JPEG + sidecar via the outbox; FA:167-198 post-commit sweep, export cache (FA = ForgetActions.kt, unverified)", verified = false), deleteAll = r(F, "MW:99-184 every frame JPEG, inbox, exports, trace folders", verified = false),
            expiry = r(C, "FE:250 queueCleanup (phone files only for memory expiry)", verified = false), sweep = r(K, "FE:153-157 photos kept (lockFrames=false), so FE:250 queues no file cleanup"), purge = r(C, "IMP:19 forgetFacts queues the outbox rows for files")),
        PolicyRow(ForgetChild.PIN,
            user = r(F, "PML:27-29 explicit forget: pin forgotten with its Moment (armed pairs, PML:70)"), deleteAll = r(F, "PML:190 beginWipe arms every linked pin (PML:205 livePairs); unlinked pins are kept (MemorySettings.kt:292, unverified)"),
            expiry = r(L, "PML:31 + PML:252-259 sweepStore clears the link only"), sweep = r(L, "PML:31 + PML:252-259"), purge = r(L, "PML:31 + PML:252-259")),
        PolicyRow(ForgetChild.GLASSES,
            user = r(C, "FA:273-285 post-commit glasses notify; CanvasForgetCoordinator.kt:30-38", verified = false), deleteAll = r(F, "MW:179 ForgetListeners.deleteAll: glasses epoch (CanvasForgetCoordinator.onDeleteAll)", verified = false),
            expiry = r(C, "FE:250 outbox rows", verified = false), sweep = r(K, "FE:153-158 no glasses notify, FE:56 notifyAfterCommit is for erased frames", verified = false), purge = r(K, "IMP:58-69 no glasses notify, FE:56 notifyAfterCommit is for erased frames", verified = false)),
        PolicyRow(ForgetChild.LOGS,
            user = r(K, "FA:155-158 one counts-only milestone line; log files wiped only by Delete all", verified = false), deleteAll = r(F, "PhoneLogging.kt:139 LogWipeListener via ForgetListeners.deleteAll", verified = false),
            expiry = r(K, "RW:100-148 calls no log wipe"), sweep = r(K, "FE:153-158 calls no log wipe"), purge = r(K, "IMP:58-69 calls no log wipe")),
    )

    /** A reserved family owns no store, so every source keeps it. */
    private fun reserved(child: ForgetChild, at: String) = PolicyRow(child, r(K, at), r(K, at), r(K, at), r(K, at), r(K, at))

    private val byChild: Map<ForgetChild, PolicyRow> = rows.associateBy { it.child }

    fun row(child: ForgetChild): PolicyRow = byChild[child] ?: error("no policy row for $child")
    fun rule(child: ForgetChild, kind: ForgetSourceKind): PolicyRule = row(child).rule(kind)
    fun action(child: ForgetChild, kind: ForgetSourceKind): PolicyAction = rule(child, kind).action
    /** The action for a [source]; [ForgetSource] already guarantees `explicit` agrees with the kind. */
    fun action(child: ForgetChild, source: ForgetSource): PolicyAction = action(child, source.kind)
    fun action(family: Family, source: ForgetSource): PolicyAction = action(ForgetChild.of(family), source.kind)
}
