package com.rokid.context.canvas.moment

import androidx.room.withTransaction
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentPartEntity
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.moment.AuditOp
import com.rokid.context.memory.moment.AuditStep
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.memory.moment.NoPhotoReason
import com.rokid.context.memory.moment.ObjectAudit
import org.json.JSONArray
import java.security.MessageDigest

/**
 * The activation switch of STORED Moments (moment-model spec section 10). ON by default since every deletion-protection, fence, race and inventory test is green (it stayed
 * OFF while they were being written); it is now only a kill switch: when off the builder writes nothing, but Forget and Delete all still remove
 * whatever projection exists (deletion protection never depends on this switch).
 */
object MomentStoreSwitch {
    const val DEFAULT_ENABLED = true
}

/**
 * Builds and rebuilds the stored Moment projection (`moment`, `moment_part`) from the AUTHORITATIVE stores through [MomentResolver] (so `contentRev` and `contentHash` are exactly
 * Moment-lite's and a second with neither a live photo nor a mark is not a Moment; a mark alone makes a MARK Moment). Every build runs in ONE transaction that re-reads the sources and the fences, so a Forget that committed
 * first is seen and one that commits afterwards removes what was built: a rebuild can never resurrect a forgotten second. Part revisions are SHA-256 digests of what the
 * resolver reads (never text, labels or paths).
 */
class MomentBuilder(
    private val db: ContextDatabase,
    private val enabled: () -> Boolean = { MomentStoreSwitch.DEFAULT_ENABLED },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where the per-second sensor features come from; null = the builder writes and touches no sensor row. */
    private val sensors: MomentSensors? = null,
) {
    /** [stored] Moments written, [removed] stale ones deleted (no live photo and no mark, forgotten, or too big), [refused] seconds a tombstone or a Moment Forget range fenced. */
    data class Report(val stored: Int, val removed: Int, val refused: Int) { companion object { val NONE = Report(0, 0, 0) } }

    private val resolver = MomentResolver(DaoMomentSource(db.canvasDao(), db.canvasSlice3Dao()))
    private val dao = db.momentDao()
    private val audit = ObjectAudit(db.objectDao(), clock)

    suspend fun build(ids: Collection<String>): Report {
        if (!enabled()) return Report.NONE
        val wanted = ids.distinct().filter { MomentId.parse(it) != null }
        if (wanted.isEmpty()) return Report.NONE
        val loaded = loadSensors(wanted) // trace files are read first, off this thread and outside the transaction
        return db.withTransaction { buildTx(wanted, loaded) }
    }

    /** Rebuilds every second that holds a live photo or a mark in `[fromMs, toMs]` and drops stored Moments of that window that no longer qualify. */
    suspend fun rebuildWindow(fromMs: Long, toMs: Long): Report {
        if (!enabled() || toMs < fromMs) return Report.NONE
        val lo = maxOf(fromMs, 0L); val hi = minOf(toMs, (MomentId.MAX_SECOND + 1) * 1000 - 1)
        if (hi < lo) return Report.NONE
        val loaded = loadSensors(candidateIds(lo, hi)) // read outside the transaction; a second that appears meanwhile is simply not decided (the next build does)
        return db.withTransaction { buildTx(candidateIds(lo, hi), loaded) }
    }

    /**
     * The one-time catch-up after the v13 migration: the marks of the last [CATCHUP_MS] that were recorded before a mark could make a Moment get their MARK Moments now. It builds the seconds
     * that hold a mark (in chunks, each its own transaction, idempotent) and then stores the time it ran in `canvas_meta` ([KEY_CATCHUP], a counter, no user data) so it never repeats;
     * a failure before the end leaves the key unset and the next cadence tick runs it again. Switched off with the store switch (nothing is built and the key stays unset).
     */
    suspend fun catchUpMarks(nowMs: Long): Report {
        if (!enabled()) return Report.NONE
        val meta = db.canvasSlice3Dao()
        if (meta.meta(KEY_CATCHUP) != null) return Report.NONE
        val ids = dao.labelSeconds(maxOf(0L, nowMs - CATCHUP_MS), nowMs).filter { it in 0..MomentId.MAX_SECOND }.map { MomentId.format(it) }
        var stored = 0; var removed = 0; var refused = 0
        for (chunk in ids.chunked(CHUNK)) build(chunk).let { stored += it.stored; removed += it.removed; refused += it.refused }
        meta.putMeta(KEY_CATCHUP, nowMs.toString())
        return Report(stored, removed, refused)
    }

    private suspend fun candidateIds(lo: Long, hi: Long): List<String> {
        val live = (dao.liveFrameSeconds(lo, hi) + dao.labelSeconds(lo, hi)).distinct().filter { it in 0..MomentId.MAX_SECOND }.map { MomentId.format(it) }
        val stored = dao.momentIdsBetween(MomentId.format(lo / 1000), MomentId.format(hi / 1000))
        return (live + stored).distinct()
    }

    private suspend fun loadSensors(ids: List<String>): MomentSensors.Loaded? {
        val s = sensors ?: return null
        val loaded = s.load(ids.mapNotNull { MomentId.parse(it) })
        testAfterSensorLoad?.invoke()
        return loaded
    }

    /** Test seam: runs after the trace files were read and before the build transaction starts, so a test can delete or replace a trace in between. Always null in production. */
    @Volatile internal var testAfterSensorLoad: (suspend () -> Unit)? = null

    private suspend fun buildTx(ids: List<String>, loaded: MomentSensors.Loaded?): Report {
        val valid = if (loaded != null) sensors!!.validTraces(loaded) else emptySet()
        val tomb = ids.chunked(CHUNK).flatMap { dao.tombstonedAmong(it) }.toSet()
        val slice3 = db.canvasSlice3Dao()
        var stored = 0; var removed = 0; var refused = 0
        val resolved = HashMap<String, Moment?>()
        for (id in ids) {
            val s = MomentId.parse(id) ?: continue
            val fenced = id in tomb || slice3.momentFenced(s * 1000, s * 1000 + 999)
            val m = if (fenced) null else try { resolver.resolve(listOf(id)).singleOrNull() } catch (_: MomentTooBig) { null }
            // Unknown capture time = no Moment (spec 3.1): a second holding a photo with only a file or receipt time is never stored.
            val unknown = m != null && dao.framesWithoutCaptureTime(m.frames.map { it.id }).isNotEmpty()
            resolved[id] = if (unknown) null else m
            if (fenced || unknown) refused++
        }
        for ((id, m) in resolved) {
            if (m == null) { if (dao.moment(id) != null || dao.parts(id).isNotEmpty()) { dao.remove(listOf(id), AuditStep.BUILD); removed++ }; continue }
            val before = dao.moment(id); val beforeParts = dao.parts(id)
            val entity = MomentEntity(m.id, BUILD_REV, m.contentRev, m.contentHash, clock(), m.type, noPhotoReasonOf(m.type)); val parts = partsOf(m)
            dao.replace(entity, parts)
            recordAudit(entity, before, beforeParts, parts)
            // the sensor numbers of this second, from the traces as they are NOW; a second that was not part of the read is left alone
            val second = MomentId.parse(id)
            if (loaded != null && second != null && second in loaded.seconds) {
                dao.replaceSensor(id, listOfNotNull(sensors!!.rowFor(loaded, second, valid)), clock(), AuditStep.BUILD, hide = before != null)
            }
            stored++
        }
        return Report(stored, removed, refused)
    }

    /**
     * The audit of one build, in the build's own transaction (names and ids only). A new Moment is BUILT. A rebuild that changed nothing writes nothing (idempotent). A rebuild that
     * changed the part set, a part's revision or the builder version is REBUILT, with one PART_ADDED or PART_REMOVED per part of the set difference (in a fixed order); every such change
     * hides the derived fields (SUMMARY, CAPTION, EMBEDDING) of the Moment until they are rebuilt (the stale rule of [ObjectAudit]).
     */
    private suspend fun recordAudit(now: MomentEntity, before: MomentEntity?, beforeParts: List<MomentPartEntity>, parts: List<MomentPartEntity>) {
        if (before == null) { audit.record(now, AuditOp.BUILT, AuditStep.BUILD); return }
        val old = beforeParts.associateBy { it.kind to it.refId }; val new = parts.associateBy { it.kind to it.refId }
        val added = (new.keys - old.keys).sortedBy { it.first + "\u0000" + it.second }; val removed = (old.keys - new.keys).sortedBy { it.first + "\u0000" + it.second }
        val updated = new.keys.intersect(old.keys).filter { new.getValue(it).partRev != old.getValue(it).partRev }.sortedBy { it.first + "\u0000" + it.second }
        if (added.isEmpty() && removed.isEmpty() && updated.isEmpty() && before.buildRev == now.buildRev && before.contentHash == now.contentHash) return
        audit.record(now, AuditOp.REBUILT, AuditStep.BUILD)
        added.forEach { audit.partAdded(now, AuditStep.BUILD, it.first, it.second) }
        removed.forEach { audit.partRemoved(now, AuditStep.BUILD, it.first, it.second) }
        // a part whose content changed, or a new builder version, with no part added or removed: the derived fields are just as stale
        audit.markStale(now.kind, now.id, AuditStep.BUILD, updated.firstOrNull()?.first, updated.firstOrNull()?.second)
    }

    private suspend fun partsOf(m: Moment): List<MomentPartEntity> {
        val derived = m.memories.map { it.id }.chunked(CHUNK).flatMap { dao.derivedAmong(it) }.toSet()
        val forget = db.forgetDao()
        val out = ArrayList<MomentPartEntity>()
        m.frames.forEach { out += MomentPartEntity(m.id, MomentPartKind.FRAME, it.id, digest(it.rev), it.ts) }
        m.memories.forEach { out += MomentPartEntity(m.id, if (it.id in derived) MomentPartKind.SUMMARY else MomentPartKind.MEMORY, it.id, digest(it.rev), m.startMs) }
        m.marks.forEach { out += MomentPartEntity(m.id, MomentPartKind.MARK, it.id, digest(it.rev), it.atMs, it.code) }
        for (f in m.frames) {
            val like = "%\"${com.rokid.context.memory.MemoryRepository.escapeLike("frame:${f.id}")}\"%"
            val cites = (forget.asksCiting(like).filter { f.id in citedFrames(it.sourcesJson) }.map { "ask:${it.id}" } +
                forget.nudgesCiting(like).filter { f.id in citedFrames(it.sourceIdsJson) }.map { "nudge:${it.id}" }).sorted()
            if (cites.isNotEmpty()) out += MomentPartEntity(m.id, MomentPartKind.CITE, "frame:${f.id}", digest(cites.joinToString(",")), f.ts)
        }
        return out.distinctBy { Triple(it.momentId, it.kind, it.refId) }
    }

    private fun citedFrames(json: String): Set<String> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.trim()?.takeIf { s -> s.startsWith("frame:") }?.removePrefix("frame:") }.toSet()
    }.getOrDefault(emptySet())

    /** Why a MARK Moment has no photo: nothing the phone keeps per second says so (see [NoPhotoReason]), so it is UNKNOWN; a FRAME Moment has a photo and no reason. Exhaustive: a new type must decide. */
    private fun noPhotoReasonOf(type: MomentType): NoPhotoReason? = when (type) {
        MomentType.FRAME -> null
        MomentType.MARK -> NoPhotoReason.UNKNOWN
    }

    private fun digest(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        const val BUILD_REV = 1
        /** `canvas_meta` key set once the catch-up of existing marks has run; the look-back of that catch-up. */
        const val KEY_CATCHUP = "mark_moment_catchup_ms"
        const val CATCHUP_MS = 7 * 24 * 3_600_000L
        private const val CHUNK = 500
    }
}
