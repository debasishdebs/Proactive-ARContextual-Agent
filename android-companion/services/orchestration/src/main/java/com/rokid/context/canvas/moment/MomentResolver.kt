package com.rokid.context.canvas.moment

import java.security.MessageDigest

/** A photo of a Moment. [rev] is a stable fingerprint of what the frame row says (never the image bytes, never a path). */
data class MomentFrame(val id: String, val ts: Long, val rev: String)

/** A memory (or any other id-bearing part) attached by reference. */
data class MomentRef(val id: String, val rev: String)

data class MomentMark(val id: String, val atMs: Long, val rev: String)

/** Read-only access to the existing tables; nothing here writes. Forgotten (tombstoned) frames are never returned. */
interface MomentSource {
    /** Live frames with `fromMs <= ts <= toMs`, oldest first, at most [limit]. */
    suspend fun frames(fromMs: Long, toMs: Long, limit: Int): List<MomentFrame>
    /** (frameId, memory) pairs for the memories that belong to those frames. */
    /** The newest [limit] live frames in the range, oldest first. */
    suspend fun framesNewest(fromMs: Long, toMs: Long, limit: Int): List<MomentFrame>
    suspend fun memories(frameIds: List<String>): List<Pair<String, MomentRef>>
    suspend fun marks(fromMs: Long, toMs: Long): List<MomentMark>
}

/**
 * A derived read model (nothing persisted): the live frames of one phone-clock second plus the memories of those frames and the marks of that second, by reference.
 * [contentHash] is SHA-256 over the sorted, kind-tagged member ids and their revisions; [contentRev] is the member count (a derived counter, NOT monotonic: only
 * the hash proves freshness).
 */
data class Moment(
    val id: String, val startMs: Long, val frames: List<MomentFrame>, val memories: List<MomentRef>, val marks: List<MomentMark>,
    val contentRev: Int, val contentHash: String,
) {
    /** What an estimate token binds for this Moment. */
    val bindingKey: String get() = "$id:$contentRev:$contentHash"
}

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
        val seconds = frames.map { Math.floorDiv(it.ts, 1000L) }.distinct().sorted().takeLast(limit)
        if (seconds.isEmpty()) return emptyList()
        val first = seconds.first() * 1000
        val last = seconds.last() * 1000 + 999
        val marks = source.marks(first, last)
        val mem = source.memories(frames.map { it.id }).groupBy({ it.first }, { it.second })
        return seconds.map { s ->
            val here = frames.filter { Math.floorDiv(it.ts, 1000L) == s }
            build(s, here, here.flatMap { mem[it.id].orEmpty() }, marks.filter { Math.floorDiv(it.atMs, 1000L) == s })
        }
    }

    /** The requested Moments as they are NOW, in request order; a malformed id, a duplicate, and a second with no live photo are dropped. */
    suspend fun resolve(ids: List<String>): List<Moment> {
        val out = ArrayList<Moment>()
        for (id in ids.distinct()) {
            val s = MomentId.parse(id) ?: continue
            val lo = s * 1000
            val frames = source.frames(lo, lo + 999, FRAME_CAP + 1)
            if (frames.size > FRAME_CAP) throw MomentTooBig()
            if (frames.isEmpty()) continue
            out += build(s, frames, source.memories(frames.map { it.id }).map { it.second }, source.marks(lo, lo + 999))
        }
        return out
    }

    private fun build(second: Long, frames: List<MomentFrame>, memories: List<MomentRef>, marks: List<MomentMark>): Moment {
        val f = frames.sortedWith(compareBy({ it.ts }, { it.id }))
        val m = memories.distinctBy { it.id }.sortedBy { it.id }
        val k = marks.sortedWith(compareBy({ it.atMs }, { it.id }))
        val lines = f.map { "F|${it.id}|${it.ts}|${it.rev}" } + m.map { "M|${it.id}|${it.rev}" } + k.map { "K|${it.id}|${it.atMs}|${it.rev}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(lines.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
        return Moment(MomentId.format(second), second * 1000, f, m, k, lines.size, digest.joinToString("") { "%02x".format(it) })
    }

    companion object { const val FRAME_CAP = 2000 }
}
