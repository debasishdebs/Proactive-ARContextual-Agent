package com.rokid.context.memory.forget.lifecycle

import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.TraceClock
import com.rokid.context.memory.forget.ExpiredLineage
import com.rokid.context.memory.forget.ExpiryMarks
import com.rokid.context.memory.forget.ForgetRadius
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.MomentForget
import com.rokid.context.memory.forget.PHOTO_CITE_PREFIX
import com.rokid.context.memory.moment.MomentId

/**
 * The planner (design 2.2), in SHADOW mode (Forget lifecycle M2): it READS the database and says which refs each existing Forget path would delete, redact, invalidate or lock. It never writes, never takes the
 * gate and never replaces a delete; the golden delete-set tests assert that its set equals what the real code deletes on a fixed world, and the optional runtime shadow hook compares counts after a real Forget.
 *
 * It mirrors the CURRENT paths (v1 parity), step by step and in the same order, because the old code is order dependent (a photo deleted earlier in the same Forget has no capture time left for the radius, a memory
 * deleted earlier is no longer found). The edges come from the families' [ForgetFamily.dependents] (the existing queries).
 *
 * With `forgetV2` on (rulings 2026-11-17) the plan ALSO names: the survivors of a multi-photo or carried memory (F1, F5: PHOTO REBUILD, never locked), the Scenes rebuilt with their derived text erased (F3) and the
 * replays, sweeps, helper jobs and `moment_sensor` rows a Forget invalidates (F6: RUN_RESULT `replay:` `sweep:` `job:` and MOMENT `sensor:` nodes). With the flag OFF the plan is the v1 plan. The fences and counters a Forget
 * writes stay control data of the executor (tombstones are planned as the photo).
 */
class ForgetPlanner(private val db: ContextDatabase, private val radiusMs: () -> Long = { ForgetRadius.ms() }) {
    private val tx = PlanTx(db)
    private val moments by lazy { MomentForget(db, ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db))) }

    suspend fun plan(roots: Set<Ref>, policy: Policy): ForgetPlan {
        val s = Sim()
        when (policy) {
            Policy.User -> {
                val memories = roots.filter { it.family == Family.MEMORY }.map { it.id }
                if (memories.isNotEmpty()) s.cascade(memories.distinct(), lockFrames = true)
                roots.filter { it.family == Family.PHOTO }.forEach { s.photo(it.id) }
                val marks = roots.filter { it.family == Family.MARK }.map { it.id }
                if (marks.isNotEmpty()) s.marks(marks)
            }
            is Policy.RetentionPhotos -> s.expirePhotos(roots.filter { it.family == Family.PHOTO }.map { it.id }.distinct(), policy.requireLinked, policy.recheckSkipped)
            is Policy.Spans -> s.spans(policy)
        }
        return s.finish()
    }

    /** The range Forget of `[fromMs, toMs]` (rounded outward to whole seconds, as `MomentForget.forgetRange`). */
    suspend fun planRange(fromMs: Long, toMs: Long): ForgetPlan {
        if (toMs < 0 || toMs < fromMs) return ForgetPlan(emptyMap())
        val lo = Math.floorDiv(maxOf(fromMs, 0L), 1000L).coerceAtMost(MomentId.MAX_SECOND)
        val hi = Math.floorDiv(toMs, 1000L).coerceAtMost(MomentId.MAX_SECOND)
        val seconds = if (hi - lo < MomentForget.MAX_TOMBSTONED_SPAN) (lo..hi).toList() else emptyList()
        return plan(emptySet(), Policy.Spans(listOf(lo * 1000..hi * 1000 + 999), seconds))
    }

    /** Forget of Moment ids (`MomentForget.forgetMoments`). */
    suspend fun planMoments(ids: Collection<String>): ForgetPlan {
        val seconds = ids.mapNotNull { MomentId.parse(it) }.distinct().sorted()
        if (seconds.isEmpty()) return ForgetPlan(emptyMap())
        return plan(emptySet(), Policy.Spans(CanvasSlice3Dao.mergeRanges(seconds.map { it * 1000..it * 1000 + 999 }), seconds))
    }

    /** The Moment / Scene scope (`MomentForget.forgetSpans`). */
    suspend fun planScoped(spans: List<LongRange>, sceneIds: Collection<String> = emptyList()): ForgetPlan {
        val merged = moments.wholeSeconds(spans)
        if (merged.isEmpty()) return ForgetPlan(emptyMap())
        return plan(emptySet(), Policy.Spans(merged, moments.secondsOf(merged), sceneIds.toList().distinct(), byKey = true))
    }

    /** The mutable state of one plan: the nodes and what earlier steps of the same Forget already removed. */
    private inner class Sim {
        val nodes = LinkedHashMap<Ref, Fate>()
        val goneMemories = HashSet<String>()
        val goneFrames = HashSet<String>() // photos whose row (and meta) an earlier step of this Forget deleted
        val droppedMoments = LinkedHashSet<String>()
        val touchedScenes = LinkedHashSet<String>()
        val namedScenes = LinkedHashSet<String>()
        val deletedMarks = LinkedHashSet<String>()
        val hiddenFromMemories = LinkedHashSet<String>() // invalidated memories whose holding Moments lose their text (scoped)
        var scoped = false // a Moment / Scene scoped Forget: the Moments that hold a hidden fact elsewhere survive
        val radius = radiusMs()
        val fd = db.forgetDao(); val md = db.momentDao(); val sd = db.sceneDao(); val s3 = db.canvasSlice3Dao()
        /** `forgetV2` (read once per plan): F1 / F5 survivors, F3 Scene rebuild, F6 side effects. OFF: the v1 plan, byte for byte. */
        val v2 = ForgetV2.on
        private var fenceDone = false

        fun put(ref: Ref, fate: Fate) { val old = nodes[ref]; if (old == null || fate.ordinal < old.ordinal) nodes[ref] = fate }

        /** `ForgetService.cascade`. */
        suspend fun cascade(
            ids: List<String>, lockFrames: Boolean, invalidateDescendants: Boolean = true, scrubAccepted: Boolean = true, keepKeyframes: Boolean = false,
            extraCited: Set<String> = emptySet(), provenanceOnly: Boolean = false, bounded: Set<String>? = null, protect: Set<String>? = null, lineageFrames: List<String> = emptyList(),
        ) {
            val found = tx.chunked(ids) { fd.memories(it) }.filter { it.id !in goneMemories }
            if (found.isEmpty() && extraCited.isEmpty() && lineageFrames.isEmpty()) return
            val forgotten = found.map { it.id }.toSet()
            val citedFrames = (found.mapNotNull { it.frameId } + tx.chunked(forgotten) { fd.sourceFramesOf(it) }).distinct()
            // F1 / F5 (`forgetV2`, [protect] = the photos this Forget forgets): a cited photo that is NOT forgotten survives (never locked or deleted) and is rebuilt from its own pixels
            val forgottenFrames = if (protect == null) citedFrames else citedFrames.filter { it in protect }
            val survivors = if (protect == null) emptyList() else citedFrames - forgottenFrames.toSet()
            val frames = if (provenanceOnly) emptyList() else if (keepKeyframes) forgottenFrames - tx.chunked(forgottenFrames) { fd.keyframeIds(it) }.toSet() else forgottenFrames
            // Codex v3 r1 P1-3: the legacy lineage a user Forget reaches (the same read the executor does)
            val lineageSeed = if (lockFrames) (citedFrames + lineageFrames).distinct().filter { bounded == null || it in bounded } else emptyList()
            val lineage = ExpiredLineage.reach(s3, forgotten, lineageSeed)
            val derivedLive = tx.chunked(lineage.derived.toList()) { fd.memories(it) }.map { it.id }.filter { it !in goneMemories }.toSet() - forgotten
            val invalidated = if (!invalidateDescendants) emptySet() else (descendants(forgotten + derivedLive) + derivedLive) - forgotten
            forgotten.forEach { put(Ref(Family.MEMORY, it), Fate.ERASE) }
            invalidated.forEach { put(Ref(Family.MEMORY, it), Fate.REBUILD) }
            goneMemories += forgotten
            hiddenFromMemories += invalidated
            val inside = if (bounded == null) frames else frames.filter { it in bounded }
            dropHolding(inside, (if (bounded == null) forgotten + invalidated else forgotten) + lineage.expired)
            if (lockFrames) {
                frames.forEach { put(Ref(Family.PHOTO, it), Fate.ERASE) }
                dropCanvasOutputs(inside, null, radiusCascade = bounded == null)
                if (bounded != null) { val outside = (frames - inside.toSet()).toSet(); runResults(outside); fence(outside) }
            }
            if (survivors.isNotEmpty()) survivorsRebuilt(survivors)
            val uncited = inside.filter { f -> fd.memoryIdsOfFrame(f).none { it !in goneMemories } }
            val goneCites = if (scrubAccepted) uncited.map { PHOTO_CITE_PREFIX + it } else emptyList()
            val cited = forgotten + invalidated + extraCited + goneCites + lineage.expired + derivedLive
            val v2Rules = ForgetV2.on // the same rule the executor applies (AskRules): preview == delete
            val reached = AskRules.reach(cited, tx, v2Rules, skipAsks = nodes.keys.filter { it.family == Family.ANSWER }.map { it.id }.toSet(), skipNudges = nodes.keys.filter { it.family == Family.NUDGE }.map { it.id }.toSet())
            val asks = reached.asks; val nudges = reached.nudges
            asks.forEach { put(Ref(Family.ANSWER, it), Fate.REBUILD) }
            nudges.forEach { put(Ref(Family.NUDGE, it), if (v2Rules) Fate.ERASE else Fate.REBUILD) }
            proposals(asks + nudges, scrubAccepted)
        }

        /** F1 / F5: the survivors' stored outputs are scrubbed (they may carry the forgotten text) and a survivor with no description left is queued to be rebuilt alone. */
        private suspend fun survivorsRebuilt(survivors: List<String>) {
            runResults(survivors.toSet()); fence(survivors)
            survivors.filter { f -> fd.memoryIdsOfFrame(f).none { it !in goneMemories } }.forEach { put(Ref(Family.PHOTO, it), Fate.REBUILD) }
        }

        /** F6: every Forget moves the frame-Forget fence (the open helper jobs are voided) and scrubs the DONE helper jobs that carried [frameIds]. */
        private suspend fun fence(frameIds: Collection<String>) {
            if (!v2) return
            if (!fenceDone) { fenceDone = true; s3.plannedVoidedHelperJobs().forEach { put(Ref(Family.RUN_RESULT, SideEffectRefs.JOB + it), Fate.DROP) } }
            if (frameIds.isNotEmpty()) s3.plannedScrubbedHelperJobs(frameIds).forEach { put(Ref(Family.RUN_RESULT, SideEffectRefs.JOB + it), Fate.DROP) }
        }

        /** F6: the replays and sweeps `invalidateBySources` would invalidate. */
        private suspend fun invalidations(traceIds: Collection<String>, from: Long?, to: Long?, labelsChanged: Boolean) {
            if (!v2) return
            val (replays, sweeps) = s3.plannedReplaySweepInvalidations(traceIds, from, to, labelsChanged)
            replays.forEach { put(Ref(Family.RUN_RESULT, SideEffectRefs.REPLAY + it), Fate.DROP) }
            sweeps.forEach { put(Ref(Family.RUN_RESULT, SideEffectRefs.SWEEP + it), Fate.DROP) }
        }

        /** The proposals of the redacted origins that change: PENDING ones expire, and params are scrubbed (retention keeps the accepted ones). */
        private suspend fun proposals(origins: List<String>, scrubAccepted: Boolean) {
            if (origins.isEmpty()) return
            val ids = if (scrubAccepted) ProposalFamily.of(origins, tx) else tx.chunked(origins) { db.actionDao().idsForOriginsExcept(it, ACCEPTED) }
            tx.chunked(ids) { c ->
                tx.rows("SELECT id, state, paramsJson FROM proposal WHERE id IN (${c.joinToString(",") { "?" }})", c) { Triple(it.getString(0), it.getString(1), it.getString(2)) }
            }.filter { (_, state, params) -> state == "PENDING" || params != ForgetService.REDACTED_PARAMS }.forEach { put(Ref(Family.PROPOSAL, it.first), Fate.REBUILD) }
        }

        /** The derived memories of [seed] over `memory_source`, minus any that an earlier step of this Forget already deleted (their edges are gone). */
        private suspend fun descendants(seed: Set<String>): Set<String> {
            val seen = HashSet(seed); var frontier = seed.toSet(); val out = LinkedHashSet<String>()
            while (frontier.isNotEmpty()) {
                val next = MemoryFamily.dependents(frontier, tx).filter { it.kind == EdgeKind.DERIVED_FROM }.map { it.to.id }.filter { it !in goneMemories && seen.add(it) }
                out += next; frontier = next.toSet()
            }
            return out
        }

        /** `ForgetService.forgetMomentTx`. */
        suspend fun photo(frameId: String, redactCites: Boolean = true, bounded: Set<String>? = null, protectAll: Set<String>? = null) {
            val protect = if (v2) (protectAll ?: setOf(frameId)) else null
            val radiusCascade = bounded == null
            val cites = if (redactCites) setOf(PHOTO_CITE_PREFIX + frameId) else emptySet()
            val frame = if (frameId in goneFrames) null else fd.frame(frameId)
            val capturedAt = if (frameId in goneFrames) emptyList() else ExpiryMarks.captureTs(fd, s3, listOf(frameId)) // retention v3 (r2-4): the same capture-time fallback as the delete
            val ids = fd.memoryIdsOfFrame(frameId).filter { it !in goneMemories }
            val carried = fd.carriedMemoryIdsOfFrame(frameId).filter { it !in goneMemories } - ids.toSet()
            if (carried.isNotEmpty()) cascade(carried, lockFrames = false, provenanceOnly = true, protect = protect)
            if (frame == null && ids.isEmpty() && (frameId in goneFrames || fd.photoRowCount(frameId) == 0)) {
                dropCanvasOutputs(listOf(frameId), null, radiusCascade)
                cascade(emptyList(), lockFrames = true, extraCited = cites, bounded = bounded, protect = protect, lineageFrames = listOf(frameId))
                return
            }
            cascade(ids, lockFrames = true, extraCited = cites, bounded = bounded, protect = protect, lineageFrames = listOf(frameId))
            put(Ref(Family.PHOTO, frameId), Fate.ERASE)
            dropHolding(listOf(frameId), ids)
            dropCanvasOutputs(listOf(frameId), capturedAt, radiusCascade)
            goneFrames += frameId
        }

        /** `ForgetService.expirePhotosTx`. */
        suspend fun expirePhotos(frameIds: List<String>, requireLinked: Boolean, recheckSkipped: Boolean) {
            val expired = ArrayList<String>(); val times = ArrayList<Long>()
            for (id in frameIds) {
                val frame = (if (id in goneFrames) null else fd.frame(id)) ?: continue
                if (fd.tombstoned(listOf(id)).isNotEmpty()) continue // Codex v3 r1 P1-1: mirrors the executor
                if (requireLinked && fd.describedRefs(id) == 0) continue
                if (recheckSkipped && fd.stillSkippedPhoto(id) == 0) continue
                expired += id; times += frame.ts
                put(Ref(Family.PHOTO, id), Fate.ERASE); goneFrames += id
            }
            // retention r1 (P1-1): photo expiry is storage only: no Moment, Scene, mark or radius cascade is planned; only the photo's own experiment canvas outputs go
            runResults(expired.toSet())
        }

        /** `MomentForget.run`: the range Forget, Forget of Moment ids and the Moment / Scene scope. */
        suspend fun spans(p: Policy.Spans) {
            val found = moments.discover(p.requestedSeconds, p.spans, bounded = p.byKey)
            val effect = if (p.byKey) moments.sceneEffect(found.moments + found.projected, p.sceneIds) else null
            val closure = moments.closureOf(found, effect, p.sceneIds, p.byKey)
            runResults(closure.first)
            val bounded = if (p.byKey) found.frames else null
            val protect = if (v2) found.frames else null
            scoped = p.byKey
            for (f in found.frames) photo(f, bounded = bounded, protectAll = protect)
            if (found.memories.isNotEmpty()) cascade(found.memories.toList(), lockFrames = true, bounded = bounded, protect = protect)
            p.spans.forEach { range(it.first, it.last) }
            marks(found.labels.toList())
            val labelRows = found.labels.toList()
            if (labelRows.isNotEmpty()) MarkFamily.dependents(labelRows.toSet(), tx).filter { it.kind == EdgeKind.LINEAGE }.forEach { put(it.to, Fate.DROP) }
            // 4. the projection
            removeMoments(existingMoments(found.projected))
            dropHolding(found.frames, found.memories, found.labels)
            p.spans.forEach { dropBetween(it.first, it.last) }
            if (p.byKey) {
                p.sceneIds.forEach { namedScenes += it }
                val dropped = droppedMoments.toSet()
                runResults(p.sceneIds.map { "scene:$it" }.toSet() + dropped.map { "moment:$it" })
            }
        }

        /** `CanvasSlice3Dao.forgetRangeTx`: the traces overlapping the range, the marks inside it. (The Moments of the range go through [dropBetween].) */
        private suspend fun range(from: Long, to: Long) {
            val traces = s3.forgettableTraces().filter { Ref(Family.TRACE, it.traceId) !in nodes && TraceClock.overlaps(it, from, to) }
            traces.forEach { put(Ref(Family.TRACE, it.traceId), Fate.ERASE) }
            val labels = md.labelIdsBetween(from, to)
            marks(labels)
            if (v2) { // F6: the replays and sweeps that read the range, the helper jobs the fence voids, the sensor rows of the range
                fence(emptyList()); invalidations(traces.map { it.traceId }, from, to, labels.isNotEmpty())
                s3.plannedSensorPurge(from, to, traces.map { it.traceId }).forEach { put(Ref(Family.MOMENT, SideEffectRefs.SENSOR + it), Fate.DROP) }
            }
        }

        /** The marks that exist among [ids] go, with the run rows that list them in their lineage. */
        suspend fun marks(ids: List<String>) {
            val live = tx.chunked(ids) { md.labelIdsAmong(it) }.filter { it !in deletedMarks }
            if (live.isEmpty()) return
            live.forEach { put(Ref(Family.MARK, it), Fate.ERASE) }
            invalidations(emptyList(), null, null, labelsChanged = true) // F6: `labelRev` moves, the results computed under the old one are invalid
            deletedMarks += live
            MarkFamily.dependents(live.toSet(), tx).filter { it.kind == EdgeKind.LINEAGE }.forEach { put(it.to, Fate.DROP) }
        }

        /** `ForgetService.dropCanvasOutputs`. */
        private suspend fun dropCanvasOutputs(frameIds: List<String>, capturedAtOrNull: List<Long>? = null, radiusCascade: Boolean = true) {
            val capturedAt = capturedAtOrNull ?: ExpiryMarks.captureTs(fd, s3, frameIds.filter { it !in goneFrames })
            val spans = (if (radiusCascade) capturedAt else emptyList()).map { (it - radius)..(it + radius) }.let(CanvasSlice3Dao::mergeRanges)
            spans.forEach { range(it.first, it.last); dropBetween(it.first, it.last) }
            runResults(frameIds.toSet())
            if (frameIds.isNotEmpty()) fence(frameIds)
            marks(tx.chunked(frameIds) { md.labelIdsLinkedToFrames(it) }) // the labels of the photos (`deleteSliceOutputsForFrames`)
        }

        private suspend fun runResults(keys: Set<String>) { RunResultFamily.keysOf(keys, tx).forEach { put(Ref(Family.RUN_RESULT, it), Fate.DROP) } }

        /** `MomentDao.dropHolding`. */
        private suspend fun dropHolding(frameIds: Collection<String>, memoryIds: Collection<String>, labelIds: Collection<String> = emptyList()) {
            val ids = LinkedHashSet<String>()
            ids += tx.chunked(frameIds) { md.momentIdsHolding(listOf(MomentPartKind.FRAME, MomentPartKind.CITE), it + it.map { f -> "frame:$f" }) }
            ids += tx.chunked(memoryIds) { md.momentIdsHolding(listOf(MomentPartKind.MEMORY, MomentPartKind.SUMMARY), it) }
            ids += tx.chunked(labelIds) { md.momentIdsHolding(listOf(MomentPartKind.MARK), it) }
            removeMoments(ids)
        }

        private suspend fun dropBetween(fromMs: Long, toMs: Long) {
            if (toMs < 0) return
            val lo = MomentId.format(Math.floorDiv(maxOf(fromMs, 0L), 1000L).coerceAtMost(MomentId.MAX_SECOND)); val hi = MomentId.format(Math.floorDiv(toMs, 1000L).coerceAtMost(MomentId.MAX_SECOND))
            removeMoments(md.momentIdsBetween(lo, hi))
        }

        private suspend fun existingMoments(ids: Collection<String>): List<String> = tx.chunked(ids) { c -> tx.strings("SELECT momentId FROM moment WHERE momentId IN (${c.joinToString(",") { "?" }})", c) }

        /** `MomentDao.remove`: the Moments, their audit and derived fields; the Scenes that hold them are evaluated at the end. */
        private suspend fun removeMoments(ids: Collection<String>) {
            val fresh = ids.filter { it !in droppedMoments }
            if (fresh.isEmpty()) return
            droppedMoments += fresh
            fresh.forEach { put(Ref(Family.MOMENT, it), Fate.DROP) }
            DerivedFieldFamily.objectsWithRows("MOMENT", fresh, tx).forEach { put(Ref(Family.DERIVED_FIELD, "MOMENT:$it"), Fate.DROP) }
            MomentFamily.dependents(fresh.toSet(), tx).filter { it.kind == EdgeKind.MEMBER && it.to.family == Family.SCENE }.forEach { touchedScenes += it.to.id }
        }

        /** The Scenes a removed Moment touched go when nothing is left of them, else they shrink; the Moments that survive but lose a hidden source lose their text copy. */
        suspend fun finish(): ForgetPlan {
            val scenes = LinkedHashSet<String>(touchedScenes); scenes += namedScenes
            // F3 (`forgetV2`, scoped): a Moment that survives while it held an invalidated fact elsewhere has its derived fields erased and the Scenes holding it are REBUILT (the same set the executor erases)
            if (v2 && scoped && hiddenFromMemories.isNotEmpty()) {
                val remote = (tx.chunked(hiddenFromMemories) { md.momentIdsHolding(listOf(MomentPartKind.MEMORY, MomentPartKind.SUMMARY), it) }.distinct() - droppedMoments)
                DerivedFieldFamily.objectsWithDerived("MOMENT", remote, tx).forEach { put(Ref(Family.DERIVED_FIELD, "MOMENT:$it"), Fate.DROP) }
                scenes += tx.chunked(remote) { sd.sceneIdsHoldingMoments(it) }
            }
            if (scenes.isNotEmpty()) {
                val parts = tx.chunked(scenes) { sd.partsOf(it) }.groupBy { it.sceneId }
                for (sceneId in scenes) {
                    val rest = parts[sceneId].orEmpty().filter { !(it.kind == "MOMENT" && it.refId in droppedMoments) }
                    if (sceneId in namedScenes || (sceneId in touchedScenes && rest.isEmpty())) {
                        put(Ref(Family.SCENE, sceneId), Fate.DROP)
                        DerivedFieldFamily.objectsWithRows("SCENE", listOf(sceneId), tx).forEach { put(Ref(Family.DERIVED_FIELD, "SCENE:$it"), Fate.DROP) }
                    } else {
                        put(Ref(Family.SCENE, sceneId), Fate.REBUILD)
                        // F3 (`forgetV2`): the surviving Scene is rebuilt from its surviving Moments and its old derived text is ERASED; v1 only hides the fresh fields
                        if (v2) DerivedFieldFamily.objectsWithDerived("SCENE", listOf(sceneId), tx).forEach { put(Ref(Family.DERIVED_FIELD, "SCENE:$it"), Fate.DROP) }
                        else DerivedFieldFamily.freshObjects("SCENE", listOf(sceneId), tx).forEach { put(Ref(Family.DERIVED_FIELD, "SCENE:$it"), Fate.REBUILD) }
                    }
                }
            }
            // a Moment that survives but holds a deleted mark (or, in a scoped Forget, a hidden fact) has its derived fields hidden, and in the scoped case its text copy blanked
            val holdingMarks = tx.chunked(deletedMarks) { md.momentIdsHolding(listOf(MomentPartKind.MARK), it) }.filter { it !in droppedMoments }
            DerivedFieldFamily.freshObjects("MOMENT", holdingMarks, tx).forEach { put(Ref(Family.DERIVED_FIELD, "MOMENT:$it"), Fate.REBUILD) }
            return ForgetPlan(nodes, revisionsOf(nodes.keys))
        }
    }

    /**
     * A stable fingerprint of the CONTENT of each affected row (text and revision columns, a photo's tombstone state, a mark's text and photo state, a Moment's / Scene's revisions and derived fields), hashed to hex, never a text.
     * It is what makes the confirmation token cover content: a row updated in place under the same id is a different token. A row that is gone fingerprints as `gone`.
     */
    private suspend fun revisionsOf(refs: Collection<Ref>): Map<Ref, String> {
        val out = HashMap<Ref, String>()
        suspend fun fill(family: Family, sql: (String) -> String, cols: Int, idOf: (Ref) -> String? = { it.id }) {
            val byId = refs.filter { it.family == family }.mapNotNull { r -> idOf(r)?.let { it to r } }.toMap()
            if (byId.isEmpty()) return
            val seen = HashSet<String>()
            tx.chunked(byId.keys) { c ->
                tx.rows(sql(c.joinToString(",") { "?" }), c) { cur -> cur.getString(0) to (1..cols).joinToString("\u0001") { i -> if (cur.isNull(i)) "\u0000" else cur.getString(i) } }
            }.forEach { (id, text) -> seen += id; byId[id]?.let { out[it] = fingerprint(text) } }
            byId.forEach { (id, r) -> if (id !in seen) out[r] = "gone" }
        }
        fill(Family.MEMORY, { "SELECT id, text, type, tags, tier, confidence, reviewState, trusted, derived, searchText, imageDelivered, reviewFlaggedAt, frameId FROM memory WHERE id IN ($it)" }, 12)
        fill(Family.PHOTO, { "SELECT f.id, f.ts, f.path, f.source, f.payload, f.placeLabel, f.phash, (SELECT COUNT(*) FROM forget_tombstone t WHERE t.frameId = f.id), (SELECT markRef FROM frame_meta m WHERE m.frameId = f.id) FROM frame f WHERE f.id IN ($it)" }, 8)
        fill(Family.MARK, { "SELECT labelId, kind, source, name, atMs, frameId, traceId, provenance, deletionCounter, photo FROM canvas_label WHERE labelId IN ($it)" }, 9)
        fill(Family.MOMENT, { "SELECT momentId, buildRev, contentRev, contentHash, text, transcript, errorReason FROM moment WHERE momentId IN ($it)" }, 6) { r -> r.id.takeUnless { it.contains(':') } }
        fill(Family.SCENE, { "SELECT sceneId, buildRev, membershipRev, startMs, endMs, activity FROM scene WHERE sceneId IN ($it)" }, 5)
        fill(Family.ANSWER, { "SELECT id, question, answer, sourcesJson, redacted FROM ask WHERE id IN ($it)" }, 4)
        fill(Family.NUDGE, { "SELECT id, text, why, sourceIdsJson, redacted FROM nudge WHERE id IN ($it)" }, 4)
        fill(Family.PROPOSAL, { "SELECT id, paramsJson, state, originId FROM proposal WHERE id IN ($it)" }, 3)
        // derived fields: ref id is `KIND:objectId`
        refs.filter { it.family == Family.DERIVED_FIELD }.forEach { r ->
            val i = r.id.indexOf(':'); if (i <= 0) return@forEach
            val rows = tx.rows("SELECT field, value, ref, builtRev, staleSince FROM object_derived WHERE objectKind = ? AND objectId = ? ORDER BY field", listOf(r.id.substring(0, i), r.id.substring(i + 1))) { c ->
                (0..4).joinToString("\u0001") { k -> if (c.isNull(k)) "\u0000" else c.getString(k) }
            }
            out[r] = if (rows.isEmpty()) "gone" else fingerprint(rows.joinToString("\u0002"))
        }
        return out
    }

    private fun fingerprint(text: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

    private companion object {
        val ACCEPTED = listOf(com.rokid.context.memory.ProposalState.EXECUTING.name, com.rokid.context.memory.ProposalState.EXECUTED.name)
    }
}
