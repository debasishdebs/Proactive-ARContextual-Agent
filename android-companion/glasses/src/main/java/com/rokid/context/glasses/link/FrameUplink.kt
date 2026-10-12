package com.rokid.context.glasses.link

import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.SAFE_CHUNK_CHARS
import com.rokid.context.glasses.capture.OutboundBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Phase 0 values (spec §1.8). */
data class UplinkConfig(
    val chunkChars: Int = SAFE_CHUNK_CHARS,
    val ackTimeoutMs: Long = 5_000,
    val maxAttempts: Int = 3,
    val retryBackoffMs: Long = 2_000,
    /** `ok=false` acks before a frame is dropped: the first NACK resends once. */
    val maxNacks: Int = 2,
)

/**
 * Stop-and-wait frame flush (spec §1.8): one frame at a time, all its chunks, then wait for `pc_frame_ack`. ok=true →
 * delete; ok=false → resend once, then drop; no ack within 5 s (or a refused send) → retry from chunk 0 after 2 s, at
 * most 3 attempts, then stop and keep the rest for the next flush. [canSend] (session open and not listening) is
 * checked before every chunk, so push-to-talk audio never queues behind frames; an interrupted frame restarts from
 * chunk 0 next time.
 */
class FrameUplink(
    private val scope: CoroutineScope,
    private val buffer: OutboundBuffer,
    private val sendChunk: (FrameChunk) -> Int,
    private val canSend: () -> Boolean,
    private val config: UplinkConfig = UplinkConfig(),
    private val log: (String) -> Unit = {},
) {
    private enum class Outcome { DELIVERED, DROPPED, RETAINED, INTERRUPTED, CLEARED }

    private val acks = ConcurrentHashMap<String, CompletableDeferred<FrameAck>>()
    private var job: Job? = null
    /** Consecutive flush crashes per frame; a frame that crashes [MAX_CRASHES] flushes in a row is dropped. */
    private val crashes = HashMap<String, Int>()
    private val _flushing = MutableStateFlow(false)
    val flushing: StateFlow<Boolean> = _flushing

    /** Start a flush unless one is running: on session open, after a capture, when listening ends. */
    @Synchronized fun kick() {
        if (job?.isActive == true) return
        _flushing.value = true
        job = scope.launch {
            try { flush() } finally { _flushing.value = false }
        }
    }

    /** Called on a bridge thread. A late ack for a frame no longer awaited is ignored. */
    fun onAck(ack: FrameAck) { acks[ack.frameId]?.complete(ack) }

    private suspend fun flush() {
        var current: String? = null
        try {
            flushLoop { current = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val id = current
            log("flush crashed: ${e.javaClass.simpleName}")
            if (id != null) {
                val n = (crashes[id] ?: 0) + 1
                if (n >= MAX_CRASHES) { crashes.remove(id); buffer.remove(id); log("dropped $id after $n crashes") } else crashes[id] = n
            }
        }
    }

    private suspend fun flushLoop(onFrame: (String) -> Unit) {
        var sent = 0
        var dropped = 0
        while (canSend()) {
            val f = buffer.oldest() ?: break
            onFrame(f.meta.frameId)
            val jpeg = try {
                buffer.readJpeg(f)
            } catch (e: IOException) {
                log("unreadable ${f.meta.frameId}: dropped")
                buffer.remove(f.meta.frameId)
                continue
            }
            val chunks = FrameCodec.split(f.meta.frameId, jpeg, f.meta.capturedAtMs, f.meta.luma, f.meta.phash, f.meta.batteryPct, config.chunkChars)
            val outcome = deliver(f.meta.frameId, chunks)
            if (outcome != Outcome.INTERRUPTED && outcome != Outcome.CLEARED) crashes.remove(f.meta.frameId)
            when (outcome) {
                Outcome.DELIVERED -> { buffer.remove(f.meta.frameId); sent++; log("delivered ${f.meta.frameId}") }
                Outcome.DROPPED -> { buffer.remove(f.meta.frameId); dropped++; log("dropped ${f.meta.frameId} after ${config.maxNacks} NACKs") }
                Outcome.RETAINED -> { log("flush stopped after ${config.maxAttempts} timeouts; retained=${buffer.size}"); break }
                Outcome.CLEARED -> log("${f.meta.frameId} cleared while sending; not sent further")
                Outcome.INTERRUPTED -> { log("flush interrupted; retained=${buffer.size}"); break }
            }
        }
        log("flush done sent=$sent dropped=$dropped retained=${buffer.size}")
    }

    private suspend fun deliver(frameId: String, chunks: List<FrameChunk>): Outcome {
        var timeouts = 0
        var nacks = 0
        while (true) {
            if (!buffer.contains(frameId)) return Outcome.CLEARED
            val waiter = CompletableDeferred<FrameAck>()
            acks[frameId] = waiter
            try {
                var refused = false
                for (c in chunks) {
                    if (!buffer.contains(frameId)) return Outcome.CLEARED
                    if (!canSend()) return Outcome.INTERRUPTED
                    if (sendChunk(c) != 0) { refused = true; break }
                }
                val ack = if (refused) null else withTimeoutOrNull(config.ackTimeoutMs) { waiter.await() }
                if (!buffer.contains(frameId)) return Outcome.CLEARED
                when {
                    ack == null -> {
                        timeouts++
                        log("frame $frameId timeout $timeouts/${config.maxAttempts}")
                        if (timeouts >= config.maxAttempts) return Outcome.RETAINED
                        delay(config.retryBackoffMs)
                    }
                    ack.ok -> return Outcome.DELIVERED
                    else -> {
                        nacks++
                        log("frame $frameId NACK ${ack.reason} $nacks/${config.maxNacks}")
                        if (nacks >= config.maxNacks) return Outcome.DROPPED
                    }
                }
            } finally {
                acks.remove(frameId, waiter)
            }
        }
    }

    private companion object { const val MAX_CRASHES = 3 }
}
