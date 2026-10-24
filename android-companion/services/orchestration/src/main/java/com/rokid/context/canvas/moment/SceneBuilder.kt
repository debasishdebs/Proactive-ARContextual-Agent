package com.rokid.context.canvas.moment

import androidx.room.withTransaction
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.ScenePartEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.moment.AuditOp
import com.rokid.context.memory.moment.AuditStep
import com.rokid.context.memory.moment.ObjectAudit
import com.rokid.context.memory.retrieval.VectorCodec

/** Where the Scene builder reads the stored Moments of a time range (a port, so the cutter and the builder are tested without a database). */
interface SceneSource {
    /** The stored Moments whose second lies in `[fromMs, toMs]`, in any order. */
    suspend fun moments(fromMs: Long, toMs: Long): List<SceneMoment>
}

/**
 * The production [SceneSource]: the stored `moment` projection (so a forgotten Moment is never seen) plus, per Moment, the hash, the motion state and, when the photo has an image
 * embedding, the vector of its first and its last photo. Nothing here writes.
 */
class DaoSceneSource(private val db: ContextDatabase, private val config: SceneConfig = SceneConfig()) : SceneSource {
    override suspend fun moments(fromMs: Long, toMs: Long): List<SceneMoment> {
        val md = db.momentDao()
        val lo = Math.floorDiv(maxOf(fromMs, 0L), 1000L); val hi = Math.floorDiv(toMs, 1000L).coerceAtMost(MomentId.MAX_SECOND)
        if (hi < lo) return emptyList()
        val moments = md.momentsBetween(MomentId.format(lo), MomentId.format(hi))
        val out = ArrayList<SceneMoment>(moments.size)
        for (chunk in moments.chunked(CHUNK)) {
            val parts = md.partsOf(chunk.map { it.momentId }).filter { it.kind == MomentPartKind.FRAME }.groupBy { it.momentId }
            val meta = db.frameMetaDao().byIds(parts.values.flatten().map { it.refId }.distinct().chunked(CHUNK).flatten()).associateBy { it.frameId }
            for (m in chunk) {
                val frames = parts[m.momentId].orEmpty().sortedWith(compareBy({ it.partTimeMs }, { it.refId }))
                val first = frames.firstOrNull()?.let { meta[it.refId] }; val last = frames.lastOrNull()?.let { meta[it.refId] }
                val activity = frames.mapNotNull { meta[it.refId]?.motion }.filter { it in config.activityStates }
                    .groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).firstOrNull()?.key
                out += SceneMoment(m.momentId, (MomentId.parse(m.momentId) ?: continue) * 1000, activity, hash(first?.phash), hash(last?.phash), vector(first), vector(last), m.contentHash)
            }
        }
        return out
    }

    /** A 16-hex-digit perceptual hash; anything else (null, short, not hex) is "not available". */
    private fun hash(hex: String?): Long? = hex?.takeIf { it.length == 16 }?.let { runCatching { java.lang.Long.parseUnsignedLong(it, 16) }.getOrNull() }

    private suspend fun vector(meta: com.rokid.context.memory.db.FrameMetaEntity?): FloatArray? {
        val space = meta?.imageVectorSpace ?: return null
        val blob = db.frameVectorDao().get(meta.frameId, space)?.blob ?: return null
        return runCatching { VectorCodec.decode(blob) }.getOrNull()
    }

    private companion object { const val CHUNK = 500 }
}

/**
 * Builds and rebuilds the stored Scenes from the stored Moments through [SceneCutter]. A rebuild covers WHOLE clock hours (a Scene never crosses one) and runs in ONE transaction that
 * re-reads the Moments, so a Moment Forget that committed first is seen and one that commits afterwards shrinks the Scene (see `MomentDao.remove`): a rebuild can never resurrect a
 * forgotten Moment. Deterministic and idempotent: the same Moments give the same ids, parts and revisions, and a rebuild that changed nothing writes nothing. The audit is content-free
 * (BUILT, REBUILT, PART_ADDED, PART_REMOVED, INVALIDATED); a changed member set or member content hides the Scene's derived fields until they are rebuilt (stale rule of [ObjectAudit]).
 */
class SceneBuilder(
    private val db: ContextDatabase,
    private val source: SceneSource = DaoSceneSource(db),
    private val enabled: () -> Boolean = { MomentStoreSwitch.DEFAULT_ENABLED },
    private val clock: () -> Long = System::currentTimeMillis,
    config: SceneConfig = SceneConfig(),
) {
    /** [stored] Scenes written (new or changed), [removed] Scenes that no longer exist, [unchanged] Scenes a rebuild left alone. */
    data class Report(val stored: Int, val removed: Int, val unchanged: Int) { companion object { val NONE = Report(0, 0, 0) } }

    private val cutter = SceneCutter(config)
    private val dao = db.sceneDao()
    private val audit = ObjectAudit(db.objectDao(), clock)

    /** Rebuilds every clock hour that overlaps `[fromMs, toMs]`. */
    suspend fun rebuildWindow(fromMs: Long, toMs: Long): Report {
        if (!enabled() || toMs < fromMs) return Report.NONE
        val hourLo = Math.floorDiv(maxOf(fromMs, 0L), SceneConfig.HOUR_MS) * SceneConfig.HOUR_MS
        val hourHi = Math.floorDiv(maxOf(toMs, 0L), SceneConfig.HOUR_MS) * SceneConfig.HOUR_MS
        return db.withTransaction {
            val plans = cutter.cut(source.moments(hourLo, hourHi + SceneConfig.HOUR_MS - 1))
            val existing = dao.scenesInHours(hourLo, hourHi).associateBy { it.sceneId }
            var stored = 0; var unchanged = 0
            for (plan in plans) if (write(plan, existing[plan.sceneId])) stored++ else unchanged++
            val gone = (existing.keys - plans.map { it.sceneId }.toSet()).sorted()
            dao.remove(gone)
            Report(stored, gone.size, unchanged)
        }
    }

    /** True when the Scene was written (new or changed), false when it was already as planned. */
    private suspend fun write(plan: ScenePlan, old: SceneEntity?): Boolean {
        val parts = plan.members.map { ScenePartEntity(plan.sceneId, ScenePartKind.MOMENT, it.momentId, it.contentHash, it.startMs) }
        if (old == null) {
            val entity = SceneEntity(plan.sceneId, BUILD_REV, plan.hourStartMs, plan.startMs, plan.endMs, plan.rule, null, null, plan.activity, 1, clock())
            dao.replace(entity, parts)
            audit.record(entity, AuditOp.BUILT, AuditStep.BUILD)
            return true
        }
        val before = dao.parts(plan.sceneId).associateBy { it.kind to it.refId }; val now = parts.associateBy { it.kind to it.refId }
        fun order(k: Pair<String, String>) = k.first + "\u0000" + k.second
        val added = (now.keys - before.keys).sortedBy(::order); val removed = (before.keys - now.keys).sortedBy(::order)
        val updated = now.keys.intersect(before.keys).filter { now.getValue(it).partRev != before.getValue(it).partRev }.sortedBy(::order)
        val sameFields = old.buildRev == BUILD_REV && old.hourStartMs == plan.hourStartMs && old.startMs == plan.startMs && old.endMs == plan.endMs &&
            old.boundaryRule == plan.rule && old.activity == plan.activity
        if (added.isEmpty() && removed.isEmpty() && updated.isEmpty() && sameFields) return false
        val memberChange = added.isNotEmpty() || removed.isNotEmpty()
        val entity = old.copy(buildRev = BUILD_REV, hourStartMs = plan.hourStartMs, startMs = plan.startMs, endMs = plan.endMs, boundaryRule = plan.rule, activity = plan.activity,
            membershipRev = old.membershipRev + if (memberChange) 1 else 0, builtAt = clock())
        dao.replace(entity, parts)
        audit.record(entity, AuditOp.REBUILT, AuditStep.BUILD)
        added.forEach { audit.partAdded(entity, AuditStep.BUILD, it.first, it.second) }
        removed.forEach { audit.partRemoved(entity, AuditStep.BUILD, it.first, it.second) }
        // a member whose content changed, or a new builder version, with no member added or removed: the derived fields are just as stale
        if (updated.isNotEmpty() || old.buildRev != BUILD_REV) audit.markStale(entity.kind, entity.id, AuditStep.BUILD, updated.firstOrNull()?.first, updated.firstOrNull()?.second)
        return true
    }

    companion object { const val BUILD_REV = 1 }
}

/**
 * How often Scenes are rebuilt: a STORED value (`canvas_meta` key [KEY_PER_DAY], "X times a day"), default [DEFAULT_PER_DAY], clamped to `1..`[MAX_PER_DAY] (the periodic worker runs every
 * 15 minutes, so more than 96 a day is not possible). There is no settings screen yet. [runIfDue] is called by the Moment cadence worker and only rebuilds when the interval has passed.
 */
class SceneCadence(private val db: ContextDatabase, private val builder: SceneBuilder, private val clock: () -> Long = System::currentTimeMillis) {
    private val meta = db.canvasSlice3Dao()

    suspend fun perDay(): Int = (meta.meta(KEY_PER_DAY)?.toIntOrNull() ?: DEFAULT_PER_DAY).coerceIn(1, MAX_PER_DAY)
    suspend fun setPerDay(n: Int) = meta.putMeta(KEY_PER_DAY, n.coerceIn(1, MAX_PER_DAY).toString())

    /** Rebuilds the previous and the current hour when the interval since the last run has passed; null when it is not due. */
    suspend fun runIfDue(nowMs: Long = clock()): SceneBuilder.Report? {
        val interval = DAY_MS / perDay()
        val last = meta.meta(KEY_LAST)?.toLongOrNull()
        if (last != null && nowMs >= last && nowMs - last < interval - TOLERANCE_MS) return null
        val report = builder.rebuildWindow(Math.floorDiv(nowMs, SceneConfig.HOUR_MS) * SceneConfig.HOUR_MS - SceneConfig.HOUR_MS, nowMs)
        meta.putMeta(KEY_LAST, nowMs.toString())
        return report
    }

    companion object {
        const val KEY_PER_DAY = "scene_rebuilds_per_day"
        const val KEY_LAST = "scene_last_rebuild_ms"
        const val DEFAULT_PER_DAY = 96
        const val MAX_PER_DAY = 96
        const val DAY_MS = 86_400_000L
        /** The periodic worker has jitter: a run a little early still counts as due. */
        const val TOLERANCE_MS = 120_000L
    }
}
