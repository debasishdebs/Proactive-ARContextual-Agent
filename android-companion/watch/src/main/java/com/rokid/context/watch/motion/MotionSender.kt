package com.rokid.context.watch.motion

import com.rokid.context.core.model.Wire
import com.rokid.context.core.model.context.WatchMotionCodec
import com.rokid.context.core.model.context.WatchMotionMessage

/** The phone side of the Data Layer, reduced to what the sender needs. False = no node or the send failed. */
fun interface PhoneLink {
    suspend fun send(path: String, body: ByteArray): Boolean
}

object WatchWire {
    const val PATH = "/pca/motion"
    const val SOURCE = "pca_watch"
    const val MAX_BYTES = 2048

    fun encode(msg: WatchMotionMessage): ByteArray = Wire.encode(msg).toByteArray()
}

/**
 * Rate-limited sender with a short backoff. Always sends the latest report (never a queue); a report that still fails
 * after [MAX_TRIES] stays "unsent" in the detector and the next tick (<= 10 min) retries.
 * [epoch] is random per process start; [seq] grows by one per attempt, retries included.
 */
class MotionSender(
    private val link: PhoneLink,
    private val clock: () -> Long,
    private val sleep: suspend (Long) -> Unit,
    val epoch: Long,
) {
    private var seq = 0L
    private var lastAttemptMs: Long? = null

    /** Returns the number of attempts used if delivered to a node, or null when all tries failed. */
    suspend fun send(report: MotionReport): Int? {
        var backoff = MIN_GAP_MS
        for (attempt in 1..MAX_TRIES) {
            lastAttemptMs?.let { last ->
                val wait = MIN_GAP_MS - (clock() - last)
                if (wait > 0) sleep(wait)
            }
            val now = clock()
            lastAttemptMs = now
            seq += 1
            val msg = WatchMotionMessage(
                v = WatchMotionCodec.VERSION, seq = seq, state = report.state, confidence = report.confidence,
                changedAtMs = report.changedAtMs, sentAtMs = now, source = WatchWire.SOURCE, epoch = epoch,
            )
            if (link.send(WatchWire.PATH, WatchWire.encode(msg))) return attempt
            if (attempt < MAX_TRIES) {
                sleep(backoff)
                backoff = minOf(backoff * 2, MAX_BACKOFF_MS)
            }
        }
        return null
    }

    companion object {
        const val MIN_GAP_MS = 1_100L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_TRIES = 6
    }
}
