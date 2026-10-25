package com.rokid.context.canvas.moment

import com.rokid.context.memory.db.MomentSensorEntity
import com.rokid.context.memory.moment.MomentType
import java.security.MessageDigest

/** A photo of a Moment. [rev] is a stable fingerprint of what the frame row says (never the image bytes, never a path). */
data class MomentFrame(val id: String, val ts: Long, val rev: String)

/** A memory (or any other id-bearing part) attached by reference. */
data class MomentRef(val id: String, val rev: String)

/** A mark of a Moment. [code] is how it was made (tap, voice, phone_voice, retro, ask_tap): a fixed token, never text. */
data class MomentMark(val id: String, val atMs: Long, val rev: String, val code: String)

/** Read-only access to the existing tables; nothing here writes. Forgotten (tombstoned) frames are never returned. */
interface MomentSource {
    /** Live frames with `fromMs <= ts <= toMs`, oldest first, at most [limit]. */
    suspend fun frames(fromMs: Long, toMs: Long, limit: Int): List<MomentFrame>
    /** (frameId, memory) pairs for the memories that belong to those frames. */
    /** The newest [limit] live frames in the range, oldest first. */
    suspend fun framesNewest(fromMs: Long, toMs: Long, limit: Int): List<MomentFrame>
    suspend fun memories(frameIds: List<String>): List<Pair<String, MomentRef>>
    suspend fun marks(fromMs: Long, toMs: Long): List<MomentMark>
    /** The newest [limit] marks in the range, oldest first (a mark makes a Moment by itself, so a listing must see them next to the photos). */
    suspend fun marksNewest(fromMs: Long, toMs: Long, limit: Int): List<MomentMark> = marks(fromMs, toMs).takeLast(limit)
    /** The stored sensor rows of these Moments (`moment_sensor`); none by default (a source that does not read them). */
    suspend fun sensor(momentIds: List<String>): List<MomentSensorEntity> = emptyList()
}

/**
 * A derived read model (nothing persisted): the live frames of one phone-clock second plus the memories of those frames and the marks of that second, by reference.
 * [contentHash] is SHA-256 over the sorted, kind-tagged member ids and their revisions; [contentRev] is the member count (a derived counter, NOT monotonic: only
 * the hash proves freshness).
 */
data class Moment(
    val id: String, val startMs: Long,
    /** What anchors this Moment, derived from its parts ([MomentType.of]): FRAME when it holds a photo, else MARK. No default: every reader must say what a Moment without a photo means. */
    val type: MomentType,
    val frames: List<MomentFrame>, val memories: List<MomentRef>, val marks: List<MomentMark>,
    val contentRev: Int, val contentHash: String,
    /** The compact sensor summary of this second, only when the source had `moment_sensor` rows for it. NOT part of [contentHash] or [bindingKey]: sensor rows are derived, never evidence of what the Moment holds. */
    val sensor: SensorSummary? = null,
) {
    /** What an estimate token binds for this Moment. */
    val bindingKey: String get() = "$id:$contentRev:$contentHash"
}

/**
 * The mark ids a run over [m] lists as lineage (`mf:<labelId>`), so Forgetting or undoing a mark deletes the run rows. EVERY Moment lists ALL its marks: a MARK Moment has no photo, so its
 * marks carry the lineage; a FRAME Moment keeps its photos as lineage too, and a mark inside it (a mark in a photo Moment) is just as much an input of the run. The commit fence covers the same ids.
 */
fun markLineage(m: Moment): List<String> = m.marks.map { it.id }

/** One second holds more photos than a Moment can fingerprint completely: refused, never silently truncated. */
class MomentTooBig : IllegalStateException("moment_too_big")

class MomentResolver(private val source: MomentSource) {
    /** The NEWEST [limit] Moments. Listing only: a run re-resolves with [resolve]. */
    suspend fun window(fromMs: Long, toMs: Long, limit: Int): List<Moment> {
        if (limit <= 0 || toMs < fromMs) return emptyList()
        val lo = maxOf(fromMs, 0L)
        val hi = minOf(toMs, (MomentId.MAX_SECOND + 1) * 1000 - 1)
        if (hi < lo) return emptyList()
        val frames = source.framesNewest(lo, hi, FRAME_CAP)
        // a mark holds its second by itself; when the frame read was cut short, a mark older than the oldest photo read could belong to a second whose photos were not read, so it is left out
        val markFloor = if (frames.size >= FRAME_CAP) frames.minOf { it.ts } else lo
        val marks = source.marksNewest(maxOf(lo, markFloor), hi, MARK_CAP)
        val seconds = (frames.map { Math.floorDiv(it.ts, 1000L) } + marks.map { Math.floorDiv(it.atMs, 1000L) }).distinct().sorted().takeLast(limit)
        if (seconds.isEmpty()) return emptyList()
        val mem = source.memories(frames.map { it.id }).groupBy({ it.first }, { it.second })
        return withSensor(seconds.map { s ->
            val here = frames.filter { Math.floorDiv(it.ts, 1000L) == s }
            build(s, here, here.flatMap { mem[it.id].orEmpty() }, marks.filter { Math.floorDiv(it.atMs, 1000L) == s })
        })
    }

    /** The requested Moments as they are NOW, in request order; a malformed id, a duplicate, and a second with neither a live photo nor a mark are dropped. */
    suspend fun resolve(ids: List<String>): List<Moment> {
        val out = ArrayList<Moment>()
        for (id in ids.distinct()) {
            val s = MomentId.parse(id) ?: continue
            val lo = s * 1000
            val frames = source.frames(lo, lo + 999, FRAME_CAP + 1)
            if (frames.size > FRAME_CAP) throw MomentTooBig()
            val marks = source.marks(lo, lo + 999)
            if (frames.isEmpty() && marks.isEmpty()) continue
            out += build(s, frames, source.memories(frames.map { it.id }).map { it.second }, marks)
        }
        return withSensor(out)
    }

    private suspend fun withSensor(ms: List<Moment>): List<Moment> {
        if (ms.isEmpty()) return ms
        val rows = source.sensor(ms.map { it.id }).groupBy { it.momentId }
        return if (rows.isEmpty()) ms else ms.map { m -> m.copy(sensor = SensorSummary.of(rows[m.id].orEmpty())) }
    }

    private fun build(second: Long, frames: List<MomentFrame>, memories: List<MomentRef>, marks: List<MomentMark>): Moment {
        val f = frames.sortedWith(compareBy({ it.ts }, { it.id }))
        val m = memories.distinctBy { it.id }.sortedBy { it.id }
        val k = marks.sortedWith(compareBy({ it.atMs }, { it.id }))
        val lines = f.map { "F|${it.id}|${it.ts}|${it.rev}" } + m.map { "M|${it.id}|${it.rev}" } + k.map { "K|${it.id}|${it.atMs}|${it.rev}|${it.code}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(lines.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
        val type = checkNotNull(MomentType.of(f.size, k.size)) { "a second with neither a photo nor a mark is not a Moment" }
        return Moment(MomentId.format(second), second * 1000, type, f, m, k, lines.size, digest.joinToString("") { "%02x".format(it) })
    }

    companion object { const val FRAME_CAP = 2000
        /** The most marks a listing reads (marks are rare; this only bounds a window over years). */
        const val MARK_CAP = 5000 }
}
