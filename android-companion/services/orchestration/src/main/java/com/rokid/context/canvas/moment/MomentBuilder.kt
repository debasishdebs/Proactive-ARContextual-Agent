package com.rokid.context.canvas.moment

import androidx.room.withTransaction
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentPartEntity
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.moment.AuditOp
import com.rokid.context.memory.moment.AuditStep
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
 * Moment-lite's and a Moment with no live photo is not a Moment). Every build runs in ONE transaction that re-reads the sources and the fences, so a Forget that committed
 * first is seen and one that commits afterwards removes what was built: a rebuild can never resurrect a forgotten second. Part revisions are SHA-256 digests of what the
 * resolver reads (never text, labels or paths).
 */
class MomentBuilder(
    private val db: ContextDatabase,
    private val enabled: () -> Boolean = { MomentStoreSwitch.DEFAULT_ENABLED },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [stored] Moments written, [removed] stale ones deleted (no live photo, forgotten, or too big), [refused] seconds a tombstone or a Moment Forget range fenced. */
    data class Report(val stored: Int, val removed: Int, val refused: Int) { companion object { val NONE = Report(0, 0, 0) } }

    private val resolver = MomentResolver(DaoMomentSource(db.canvasDao(), db.canvasSlice3Dao()))
    private val dao = db.momentDao()
    private val audit = ObjectAudit(db.objectDao(), clock)

    suspend fun build(ids: Collection<String>): Report {
        if (!enabled()) return Report.NONE
        val wanted = ids.distinct().filter { MomentId.parse(it) != null }
        if (wanted.isEmpty()) return Report.NONE
        return db.withTransaction { buildTx(wanted) }
    }

    /** Rebuilds every second that holds a live photo in `[fromMs, toMs]` and drops stored Moments of that window that no longer qualify. */
    suspend fun rebuildWindow(fromMs: Long, toMs: Long): Report {
        if (!enabled() || toMs < fromMs) return Report.NONE
        return db.withTransaction {
            val lo = maxOf(fromMs, 0L); val hi = minOf(toMs, (MomentId.MAX_SECOND + 1) * 1000 - 1)
            if (hi < lo) return@withTransaction Report.NONE
            val live = dao.liveFrameSeconds(lo, hi).filter { it in 0..MomentId.MAX_SECOND }.map { MomentId.format(it) }
            val stored = dao.momentIdsBetween(MomentId.format(lo / 1000), MomentId.format(hi / 1000))
            buildTx((live + stored).distinct())
        }
    }

    private suspend fun buildTx(ids: List<String>): Report {
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
            if (m == null) { if (dao.moment(id) != null || dao.parts(id).isNotEmpty()) { dao.remove(listOf(id)); removed++ }; continue }
            val before = dao.moment(id); val beforeParts = dao.parts(id)
            val entity = MomentEntity(m.id, BUILD_REV, m.contentRev, m.contentHash, clock()); val parts = partsOf(m)
            dao.replace(entity, parts)
            recordAudit(entity, before, beforeParts, parts)
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
        m.marks.forEach { out += MomentPartEntity(m.id, MomentPartKind.MARK, it.id, digest(it.rev), it.atMs) }
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

    private fun digest(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        const val BUILD_REV = 1
        private const val CHUNK = 500
    }
}
