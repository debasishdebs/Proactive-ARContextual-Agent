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
    /** How often the ack wait checks that the frame is still buffered (a privacy pause clears it). */
    val ackPollMs: Long = 250,
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
    private val onBufferChanged: () -> Unit = {},
    /** A frame was acked ok and left the buffer (the glasses' "photo sent" cue). Never breaks the flush. */
    private val onDelivered: (String) -> Unit = {},
) {
    private enum class Outcome { DELIVERED, DROPPED, RETAINED, INTERRUPTED, CLEARED }

    private val acks = ConcurrentHashMap<String, CompletableDeferred<FrameAck>>()
    private var job: Job? = null
    private var running = false
    /** A kick that arrived while a flush ran: that flush runs once more when it ends (a capture can't be lost). */
    @Volatile private var pendingKick = false
    /**
     * Consecutive flush crashes per frame; a frame that crashes [MAX_CRASHES] flushes in a row is dropped. A count goes
     * when its frame leaves the buffer by any path, or when the frame gets through an attempt without crashing.
     */
    private val crashes = HashMap<String, Int>()
    private val _flushing = MutableStateFlow(false)
    val flushing: StateFlow<Boolean> = _flushing

    /** Start a flush unless one is running (then that one runs again): on session open, after a capture, when listening ends. */
    @Synchronized fun kick() {
        if (running) { pendingKick = true; return }
        running = true
        _flushing.value = true
        job = scope.launch {
            var more = true
            try {
                while (more) { flush(); more = takePendingKick() }
            } finally {
                if (more) endFlush()
            }
        }
    }

    @Synchronized private fun takePendingKick(): Boolean {
        if (pendingKick) { pendingKick = false; return true }
        endFlush()
        return false
    }

    @Synchronized private fun endFlush() { running = false; _flushing.value = false }

    /** Consecutive crashes charged to [frameId]; null when none. */
    internal fun crashCount(frameId: String): Int? = synchronized(crashes) { crashes[frameId] }

    /** Called on a bridge thread. A late ack for a frame no longer awaited is ignored. */
    fun onAck(ack: FrameAck) { acks[ack.frameId]?.complete(ack) }

    /**
     * One pass over the backlog, oldest first. A crash is charged to the frame being handled (never to the previous
     * one); a frame dropped for crashing lets the flush go on, any other crash ends it. Every iteration either removes
     * a frame or ends the flush, so the loop is bounded by the buffer size.
     */
    private suspend fun flush() {
        var sent = 0
        var dropped = 0
        synchronized(crashes) { crashes.keys.retainAll { buffer.contains(it) } }
        while (canSend()) {
            var current: String? = null
            try {
                val before = buffer.size
                val f = buffer.oldest()
                val aged = before - buffer.size
                if (aged > 0) { dropped += aged; bufferChanged() }
                if (f == null) break
                val id = f.meta.frameId
                current = id
                val jpeg = try {
                    buffer.readJpeg(f)
                } catch (e: IOException) {
                    if (f.jpeg.exists()) throw e
                    log("unreadable $id: dropped")
                    forget(id); buffer.remove(id); dropped++; bufferChanged()
                    continue
                }
                val chunks = FrameCodec.split(id, jpeg, buffer.sendTimeMs(f), f.meta.luma, f.meta.phash, f.meta.batteryPct, config.chunkChars)
                val outcome = deliver(id, chunks)
                if (outcome != Outcome.INTERRUPTED) forget(id)
                when (outcome) {
                    Outcome.DELIVERED -> {
                        buffer.remove(id); sent++; log("delivered $id")
                        try { onDelivered(id) } catch (e: Exception) { log("delivered notice failed: ${e.javaClass.simpleName}") }
                    }
                    Outcome.DROPPED -> { buffer.remove(id); dropped++; log("dropped $id after ${config.maxNacks} NACKs"); bufferChanged() }
                    Outcome.RETAINED -> { log("flush stopped after ${config.maxAttempts} timeouts; retained=${buffer.size}"); break }
                    Outcome.CLEARED -> log("$id cleared while sending; not sent further")
                    Outcome.INTERRUPTED -> { log("flush interrupted; retained=${buffer.size}"); break }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("flush crashed: ${e.javaClass.simpleName}")
                val id = current ?: break
                if (!chargeCrash(id)) break
                dropped++
                bufferChanged()
            }
        }
        log("flush done sent=$sent dropped=$dropped retained=${buffer.size}")
        if (sent + dropped > 0) bufferChanged()
    }

    /** True when [id] reached [MAX_CRASHES] and was dropped. */
    private fun chargeCrash(id: String): Boolean {
        val n = synchronized(crashes) { (crashes[id] ?: 0) + 1 }
        if (n < MAX_CRASHES) { synchronized(crashes) { crashes[id] = n }; return false }
        forget(id)
        buffer.remove(id)
        log("dropped $id after $n crashes")
        return true
    }

    private fun forget(id: String) { synchronized(crashes) { crashes.remove(id) } }

    /** The hello that follows corrects the phone's buffered count (no protocol change). Never breaks the flush. */
    private fun bufferChanged() {
        try { onBufferChanged() } catch (e: Exception) { log("buffer change notice failed: ${e.javaClass.simpleName}") }
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
                val ack = if (refused) null else awaitAck(frameId, waiter)
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

    /** The ack, or null on timeout or once the frame left the buffer (checked every [UplinkConfig.ackPollMs]). */
    private suspend fun awaitAck(frameId: String, waiter: CompletableDeferred<FrameAck>): FrameAck? =
        withTimeoutOrNull(config.ackTimeoutMs) {
            var ack: FrameAck? = null
            while (ack == null && buffer.contains(frameId)) ack = withTimeoutOrNull(config.ackPollMs) { waiter.await() }
            ack
        }

    private companion object { const val MAX_CRASHES = 3 }
}
