package com.rokid.context.link

import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.FrameMeta
import com.rokid.context.core.model.JoinResult
import com.rokid.context.core.model.SAFE_CHUNK_CHARS

sealed interface Assembly {
    data object Pending : Assembly
    class Complete(val meta: FrameMeta, val jpeg: ByteArray) : Assembly
    /** Ack `ok=false` with [reason]: the glasses resend once, then drop the frame (spec §1.8). */
    data class Rejected(val frameId: String, val reason: String) : Assembly
    /** Not a usable chunk (bad index/count): no ack, so the glasses' timeout retries it. */
    data object Ignored : Assembly
}

/**
 * Phone-side reassembly per `frameId` (spec §1.8). Chunks may arrive in any order; a second chunk 0 for a frame that
 * already has one is the glasses' retry from chunk 0 and restarts it; a partial frame without a new chunk for
 * [staleMs] is discarded. At most [maxPartials] frames are held and a chunk longer than [MAX_CHUNK_CHARS] is ignored, so
 * memory stays bounded. Thread-safe.
 */
class FrameReassembler(private val staleMs: Long = STALE_MS, private val maxPartials: Int = MAX_PARTIALS) {
    private class Partial(val meta: FrameMeta, val parts: Array<String?>, var lastChunkAtMs: Long)
    private val partials = HashMap<String, Partial>()

    val pendingCount: Int @Synchronized get() = partials.size

    @Synchronized fun accept(chunk: FrameChunk, nowMs: Long): Assembly {
        sweep(nowMs)
        val m = chunk.meta
        if (m.chunkCount !in 1..MAX_CHUNKS || m.chunkIdx !in 0 until m.chunkCount) return Assembly.Ignored
        if (chunk.chunk.length > MAX_CHUNK_CHARS) return Assembly.Ignored
        val existing = partials[m.frameId]
        if (existing == null) evictForNewFrame()
        val restart = existing == null || existing.meta.chunkCount != m.chunkCount || existing.meta.crc32 != m.crc32 ||
            (m.chunkIdx == 0 && existing.parts[0] != null)
        val p = if (restart) Partial(m, arrayOfNulls(m.chunkCount), nowMs).also { partials[m.frameId] = it } else existing!!
        p.parts[m.chunkIdx] = chunk.chunk
        p.lastChunkAtMs = nowMs
        if (p.parts.any { it == null }) return Assembly.Pending
        partials.remove(m.frameId)
        return when (val r = FrameCodec.join(p.meta, p.parts.map { it!! })) {
            is JoinResult.Ok -> Assembly.Complete(p.meta, r.jpeg)
            JoinResult.CrcMismatch -> Assembly.Rejected(m.frameId, FrameAckReason.CRC)
            JoinResult.BadBase64 -> Assembly.Rejected(m.frameId, FrameAckReason.DECODE)
        }
    }

    /** The glasses send one frame at a time, so [maxPartials] partials are ample; the stalest goes first. */
    private fun evictForNewFrame() {
        while (partials.size >= maxPartials) {
            val stalest = partials.minByOrNull { it.value.lastChunkAtMs }?.key ?: return
            partials.remove(stalest)
        }
    }

    /** Drops partial frames idle for [staleMs] or more; returns how many. */
    @Synchronized fun sweep(nowMs: Long): Int {
        val stale = partials.filterValues { nowMs - it.lastChunkAtMs >= staleMs }.keys.toList()
        stale.forEach { partials.remove(it) }
        return stale.size
    }

    companion object {
        const val STALE_MS = 10_000L
        /** 64 × 32 768 chars ≈ 1.5 MB of JPEG: far above a 20–40 KB frame, small enough to bound memory. */
        const val MAX_CHUNKS = 64
        const val MAX_PARTIALS = 4
        const val MAX_CHUNK_CHARS = 2 * SAFE_CHUNK_CHARS
    }
}
