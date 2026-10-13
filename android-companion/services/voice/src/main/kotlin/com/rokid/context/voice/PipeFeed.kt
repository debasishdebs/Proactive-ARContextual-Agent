package com.rokid.context.voice

import com.rokid.context.core.model.audio.Pcm
import kotlin.math.min

/** The write end of the recognizer's audio pipe, written without blocking (Android: `AndroidPipeSink`). */
interface PipeSink {
    /** [WROTE] all of [bytes], [FULL] (nothing written: the pipe is full) or [CLOSED] (EPIPE: the reader went away). */
    fun tryWrite(bytes: ByteArray): Int
    fun close()

    companion object {
        const val WROTE = 1
        const val FULL = 0
        const val CLOSED = -1
    }
}

/**
 * What the feeder knows: bytes offered, bytes the pipe took, whether the newest write was refused (pipe full), the
 * pipe's measured [capacityBytes], how much was fed since [acceptedBytes] last grew ([flatFedBytes]), and whether the
 * reader closed its end or the sink failed ([peerClosed]).
 */
data class PipeMeter(
    val fedBytes: Long,
    val acceptedBytes: Long,
    val lastWriteRefused: Boolean,
    val capacityBytes: Long,
    val flatFedBytes: Long = 0,
    val peerClosed: Boolean = false,
)

/**
 * There is no capacity constant: a real pipe counts page slots, not bytes (3 200-byte writes give 51 200 with 4 KiB pages
 * and 256 000 with 16 KiB pages), so every [PcmFeeder], [PipeMeter] and [ProbeOutcome] carries what [PipeCapacity.measure]
 * found on the device.
 */
object PipeLimits {
    /** Spec §5.1.1: the guard judges only after more than 2 s of fed audio. */
    const val GUARD_AFTER_FED_BYTES = 2L * Pcm.BYTES_PER_SECOND
    /** …and only once the pipe took nothing for 1 s of feed (a reader catching up after a slow start isn't flat). */
    const val GUARD_FLAT_FED_BYTES = 1L * Pcm.BYTES_PER_SECOND
    /** One write ≤ PIPE_BUF (4 096), so a non-blocking pipe write is all or nothing. */
    const val MAX_WRITE_BYTES = Pcm.CHUNK_BYTES
    /** Audio waiting for a slow reader is capped at 3 s; the oldest goes first. */
    const val MAX_PENDING_BYTES = 3 * Pcm.BYTES_PER_SECOND
}

/**
 * Measures a pipe's real capacity: fill a scratch pipe (created like the feed pipe) with [PipeLimits.MAX_WRITE_BYTES] writes.
 *
 * Task 6 MUST first pin both pipes with `F_SETPIPE_SZ(65536)`: `Os.fcntlInt(fd, 1031, 65536)`, then re-measure. The
 * kernel default is 16 page slots, which is 256 000 bytes of 3 200-byte writes on 16 KiB-page phones. If the pinned
 * capacity is still above half the probe feed ([ExternalAudioProbe.decidable] is false), skip the probe: the result
 * is NOT_VERIFIED, never UNSUPPORTED.
 */
object PipeCapacity {
    /** Bytes accepted before the first refusal, at most [limitBytes]. The caller closes [sink]. */
    fun measure(sink: PipeSink, limitBytes: Long = 4L * 1024 * 1024): Long {
        val chunk = ByteArray(PipeLimits.MAX_WRITE_BYTES)
        var total = 0L
        while (total < limitBytes) {
            val r = try { sink.tryWrite(chunk) } catch (e: Exception) { if (e is kotlin.coroutines.cancellation.CancellationException) throw e; PipeSink.CLOSED }
            if (r != PipeSink.WROTE) break
            total += chunk.size
        }
        return total
    }
}

/**
 * Feeds PCM into the recognizer's pipe without ever blocking the caller (spec §5.1.1: a non-reading recognizer can't
 * hang the feeder). A refused write keeps the chunk pending for the next [pump]; [finish] drains, then closes the
 * write end (EOF ends recognition), or closes anyway after `drainMs`. Thread-safe.
 */
class PcmFeeder(
    private val sink: PipeSink,
    private val maxPendingBytes: Int = PipeLimits.MAX_PENDING_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The pipe's measured capacity ([PipeCapacity.measure]). */
    private val capacityBytes: Long,
) {
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var fed = 0L
    private var accepted = 0L
    private var refused = false
    private var fedAtLastAccept = 0L
    private var peerClosed = false
    private var finishAt: Long? = null
    @Volatile var closed = false
        private set

    @Synchronized fun offer(pcm: ByteArray) {
        if (closed || finishAt != null || pcm.isEmpty()) return
        var i = 0
        while (i < pcm.size) {
            val n = min(PipeLimits.MAX_WRITE_BYTES, pcm.size - i)
            pending.addLast(pcm.copyOfRange(i, i + n))
            pendingBytes += n
            i += n
        }
        fed += pcm.size
        while (pendingBytes > maxPendingBytes) pendingBytes -= pending.removeFirst().size
        pump()
    }

    @Synchronized fun pump() {
        if (closed) return
        while (pending.isNotEmpty()) {
            val c = pending.first()
            val r = try { sink.tryWrite(c) } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                PipeSink.CLOSED // EBADF / EIO: the feed is over, as with EPIPE
            }
            when (r) {
                PipeSink.WROTE -> {
                    pending.removeFirst(); pendingBytes -= c.size; accepted += c.size; refused = false
                    fedAtLastAccept = fed
                }
                PipeSink.FULL -> { refused = true; break }
                else -> { peerClosed = true; closeNow(); return }
            }
        }
        val deadline = finishAt
        if (deadline != null && (pending.isEmpty() || clock() >= deadline)) closeNow()
    }

    @Synchronized fun finish(drainMs: Long = 1_000) {
        if (finishAt == null) finishAt = clock() + drainMs
        pump()
    }

    @Synchronized fun close() = closeNow()

    @Synchronized fun meter(): PipeMeter = PipeMeter(fed, accepted, refused, capacityBytes, fed - fedAtLastAccept, peerClosed)

    private fun closeNow() {
        if (closed) return
        closed = true
        pending.clear()
        pendingBytes = 0
        runCatching { sink.close() }
    }
}

object FallbackGuard {
    /**
     * Spec §5.1.1 production guard: the recognizer produced output although it can't have read our audio.
     * - It closed our pipe (EPIPE) within the first 2 s, or
     * - more than 2 s was fed, the pipe is full ([PipeMeter.lastWriteRefused]) and took nothing for 1 s of feed, and
     *   less than one capacity was consumed (consumed ≥ accepted − capacity while full).
     * A recognizer reading at real time never fills the pipe, so it never trips this.
     */
    fun suspects(m: PipeMeter): Boolean =
        (m.peerClosed && m.fedBytes <= PipeLimits.GUARD_AFTER_FED_BYTES) ||
            (m.fedBytes > PipeLimits.GUARD_AFTER_FED_BYTES && m.lastWriteRefused && m.flatFedBytes >= PipeLimits.GUARD_FLAT_FED_BYTES &&
                m.acceptedBytes - m.capacityBytes < m.capacityBytes)
}
