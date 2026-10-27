package com.rokid.context.memory.forget

import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.moment.MomentId

/** What a granular Forget was invoked on. [level] is the lowest scope it can run at: a Moment is never forgotten as less than a Moment, a Scene never as less than a Scene. */
sealed interface ForgetTarget {
    val level: ForgetScope
    data class Fact(val memoryId: String) : ForgetTarget { override val level get() = ForgetScope.PHOTO }
    data class Photo(val frameId: String) : ForgetTarget { override val level get() = ForgetScope.PHOTO }
    data class Moment(val momentId: String) : ForgetTarget { override val level get() = ForgetScope.MOMENT }
    data class Scene(val sceneId: String) : ForgetTarget { override val level get() = ForgetScope.SCENE }
}

/**
 * The Forget SCOPE engine (user ruling 2026-10-27). One entry for every granular Forget: [plan] resolves the [ForgetTarget] and the [ForgetScope] into WHAT goes (read only), [preview] counts it from the same
 * discovery the delete uses, [forget] runs it in one transaction through the existing code (never a second copy of the cascade):
 * - PHOTO: a photo = [ForgetService.forgetMoment] exactly as before, NO second fence (frame, file, vectors, memories, run outputs, the radius path); a fact = [ForgetService.forgetFact] exactly as before. Neighbours survive.
 * - MOMENT: [MomentForget.forgetSpans] over the Moment's second(s) extended by [ForgetRadius] (photos, memories, marks, traces, sensor rows, run outputs, projection, outbox, fences).
 * - SCENE: the same over every Moment of the Scene (and the Scene's own time), and the Scene goes whole.
 * Fallbacks (safe, stated in the handoff): a fact with no photo, or a photo with no known time, is forgotten as a PHOTO; a Moment that is in no stored Scene is forgotten as a MOMENT.
 * File deletion, reminders, notifications, the cached export and the listeners are the caller's post-commit job from [Done.cascade] (`ForgetActions`), like every other Forget.
 */
class ScopedForget(
    private val db: ContextDatabase,
    private val service: ForgetService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What a Forget of one target at one scope reaches. [effective] is the scope it really runs at (the setting raised to the entry's level, lowered by a fallback). */
    class Plan internal constructor(
        val target: ForgetTarget, val requested: ForgetScope, val effective: ForgetScope,
        internal val factId: String?, internal val frameId: String?, internal val spans: List<LongRange>, internal val sceneIds: List<String>,
    ) {
        /** Nothing to forget (a malformed or unknown Moment/Scene id). */
        val isEmpty: Boolean get() = factId == null && frameId == null && spans.isEmpty()
    }

    /** The committed Forget: the cascade for the caller's post-commit cleanup, and the spans and traces for the file and glasses listeners. */
    data class Done(val plan: Plan, val cascade: ForgetService.Result, val spans: List<LongRange>, val tombstonedTraceIds: List<String>)

    suspend fun plan(target: ForgetTarget, scope: ForgetScope = ForgetScopeSetting.get()): Plan {
        val eff = scope.atLeast(target.level)
        val dao = db.forgetDao(); val radius = ForgetRadius.ms()
        fun only(factId: String? = null, frameId: String? = null) = Plan(target, scope, ForgetScope.PHOTO, factId, frameId, emptyList(), emptyList())
        // The Moment seconds the target lives in (empty = unknown).
        val seconds: List<Long> = when (target) {
            is ForgetTarget.Fact -> {
                val frames = (dao.memories(listOf(target.memoryId)).mapNotNull { it.frameId } + dao.sourceFramesOf(listOf(target.memoryId))).distinct()
                if (eff == ForgetScope.PHOTO) return only(factId = target.memoryId)
                secondsOfFrames(frames)
            }
            is ForgetTarget.Photo -> {
                if (eff == ForgetScope.PHOTO) return only(frameId = target.frameId)
                secondsOfFrames(listOf(target.frameId))
            }
            is ForgetTarget.Moment -> listOfNotNull(MomentId.parse(target.momentId))
            is ForgetTarget.Scene -> emptyList()
        }
        if (target is ForgetTarget.Fact && seconds.isEmpty()) return only(factId = target.memoryId) // no photo, no Moment: only the fact
        if (target is ForgetTarget.Photo && seconds.isEmpty()) return only(frameId = target.frameId)
        val momentIds = seconds.map { MomentId.format(it) }
        // SCENE: the Scenes holding those Moments (a Scene target names its own).
        val sceneIds: List<String> = when {
            target is ForgetTarget.Scene -> if (db.sceneDao().scene(target.sceneId) != null) listOf(target.sceneId) else emptyList()
            eff == ForgetScope.SCENE -> momentIds.chunked(500).flatMap { db.sceneDao().sceneIdsHoldingMoments(it) }.distinct().sorted()
            else -> emptyList()
        }
        val scenes = if (sceneIds.isEmpty()) emptyList() else db.sceneDao().scenesByIds(sceneIds)
        val sceneMomentSeconds = if (sceneIds.isEmpty()) emptyList() else sceneIds.chunked(500).flatMap { db.sceneDao().partsOf(it) }.filter { it.kind == com.rokid.context.memory.db.ScenePartKind.MOMENT }.mapNotNull { MomentId.parse(it.refId) }
        val allSeconds = (seconds + sceneMomentSeconds).distinct()
        val spans = allSeconds.map { window(it, radius) } + scenes.map { it.startMs..it.endMs }
        if (spans.isEmpty()) return Plan(target, scope, eff, null, null, emptyList(), emptyList())
        val effective = if (sceneIds.isNotEmpty()) ForgetScope.SCENE else ForgetScope.MOMENT
        return Plan(target, scope, effective, null, null, spans, sceneIds)
    }

    /** A Moment's second plus the radius on both sides (whole seconds; never before the epoch). */
    private fun window(second: Long, radius: Long): LongRange = maxOf(0L, second * 1000 - radius)..(second * 1000 + 999 + radius)

    /** Every second a photo is known by (capture time and row time). */
    private suspend fun secondsOfFrames(frameIds: List<String>): List<Long> =
        frameIds.chunked(500).flatMap { db.momentDao().captureTimes(it) }.map { Math.floorDiv(it.ms, 1000L) }.filter { it in 0..MomentId.MAX_SECOND }.distinct().sorted()

    /** The numbers of the confirm dialog: exactly what [forget] removes for spans (the same discovery); for a single photo or fact, what its cascade and the radius path reach. */
    suspend fun preview(plan: Plan): MomentForget.Counts {
        if (plan.isEmpty) return MomentForget.Counts(0, 0, 0, 0, 0, 0)
        val dao = db.forgetDao(); val md = db.momentDao()
        if (plan.spans.isNotEmpty()) return MomentForget(db, service, clock).preview(plan.spans, plan.sceneIds)
        val radius = ForgetRadius.ms()
        val frames: List<String>; val memories: Set<String>
        if (plan.frameId != null) {
            val exists = dao.frame(plan.frameId) != null || dao.photoRowCount(plan.frameId) > 0 || dao.memoryIdsOfFrame(plan.frameId).isNotEmpty()
            frames = if (exists) listOf(plan.frameId) else emptyList(); memories = dao.memoryIdsOfFrame(plan.frameId).toSet()
        } else {
            val id = plan.factId!!; val found = dao.memories(listOf(id))
            val cited = (found.mapNotNull { it.frameId } + dao.sourceFramesOf(listOf(id))).distinct()
            frames = cited.filter { f -> dao.memoryIdsOfFrame(f).all { it == id } } // the photos only this fact held go with it
            memories = found.map { it.id }.toSet()
        }
        val windows = secondsOfFrames(frames).map { window(it, radius) }.let(com.rokid.context.memory.db.CanvasSlice3Dao::mergeRanges)
        val marks = windows.flatMap { md.labelIdsBetween(it.first, it.last) }.distinct()
        val moments = (windows.flatMap { md.momentIdsBetween(MomentId.format(it.first / 1000), MomentId.format(it.last / 1000)) } + frames.chunked(500).flatMap { c -> md.momentIdsHolding(listOf(com.rokid.context.memory.db.MomentPartKind.FRAME, com.rokid.context.memory.db.MomentPartKind.CITE), c + c.map { "frame:$it" }) } + memories.toList().chunked(500).flatMap { md.momentIdsHolding(listOf(com.rokid.context.memory.db.MomentPartKind.MEMORY, com.rokid.context.memory.db.MomentPartKind.SUMMARY), it) }).distinct()
        val (removed, shrunk) = MomentForget(db, service, clock).sceneEffect(moments)
        return MomentForget.Counts(frames.size, memories.size, marks.size, moments.size, removed, shrunk)
    }

    /** Runs the plan in one transaction (the caller holds the delete gate and runs the post-commit cleanup on [Done.cascade]). */
    suspend fun forget(plan: Plan): Done {
        if (plan.isEmpty) return Done(plan, ForgetService.Result.EMPTY, emptyList(), emptyList())
        plan.factId?.let { return Done(plan, service.forgetFact(it), emptyList(), emptyList()) }
        plan.frameId?.let { return Done(plan, service.forgetMoment(it), emptyList(), emptyList()) }
        val r = MomentForget(db, service, clock).forgetSpans(plan.spans, plan.sceneIds)
        // files and glasses follow the committed range (trace folders, the glasses' unsent traces): the same listener call as a range Forget
        for (s in plan.spans) ForgetListeners.rangeForgotten(s.first, s.last, r.tombstonedTraceIds)
        return Done(plan, r.cascade, plan.spans, r.tombstonedTraceIds)
    }
}
