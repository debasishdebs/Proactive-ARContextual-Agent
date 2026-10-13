package com.rokid.context.voice

import com.rokid.context.core.model.audio.Pcm
import kotlin.math.min

/** The write end of the recognizer's audio pipe, written without blocking (Android: `AndroidPipeSink`). */
interface PipeSink {
    /** [WROTE] all of [bytes], [FULL] (nothing written: the pipe holds 64 KiB unread) or [CLOSED]. */
    fun tryWrite(bytes: ByteArray): Int
    fun close()

    companion object {
        const val WROTE = 1
        const val FULL = 0
        const val CLOSED = -1
    }
}

/** What the feeder knows: bytes offered, bytes the pipe took, and whether the newest write was refused (pipe full). */
data class PipeMeter(val fedBytes: Long, val acceptedBytes: Long, val lastWriteRefused: Boolean)

object PipeLimits {
    /** Linux pipe capacity: a non-blocking write is refused while this much is unread. */
    const val PIPE_CAPACITY_BYTES = 65_536L
    /** Spec §5.1.1: the guard judges only after more than 2 s of fed audio. */
    const val GUARD_AFTER_FED_BYTES = 2L * Pcm.BYTES_PER_SECOND
    /** …and accuses only a recognizer that consumed less than 64 KiB. */
    const val GUARD_MIN_CONSUMED_BYTES = 65_536L
    /** One write ≤ PIPE_BUF (4 096), so a non-blocking pipe write is all or nothing. */
    const val MAX_WRITE_BYTES = Pcm.CHUNK_BYTES
    /** Audio waiting for a slow reader is capped at 3 s; the oldest goes first. */
    const val MAX_PENDING_BYTES = 3 * Pcm.BYTES_PER_SECOND
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
) {
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var fed = 0L
    private var accepted = 0L
    private var refused = false
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
            when (sink.tryWrite(c)) {
                PipeSink.WROTE -> { pending.removeFirst(); pendingBytes -= c.size; accepted += c.size; refused = false }
                PipeSink.FULL -> { refused = true; break }
                else -> { closeNow(); return }
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

    @Synchronized fun meter(): PipeMeter = PipeMeter(fed, accepted, refused)

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
     * Spec §5.1.1 production guard: the recognizer produced output although it can't have read our audio — more than
     * 2 s fed, the pipe full ([PipeMeter.lastWriteRefused]) and less than 64 KiB consumed (consumed = accepted − capacity
     * while full). A recognizer reading at real time never fills the pipe, so it never trips this.
     */
    fun suspects(m: PipeMeter): Boolean =
        m.fedBytes > PipeLimits.GUARD_AFTER_FED_BYTES && m.lastWriteRefused &&
            m.acceptedBytes - PipeLimits.PIPE_CAPACITY_BYTES < PipeLimits.GUARD_MIN_CONSUMED_BYTES
}
