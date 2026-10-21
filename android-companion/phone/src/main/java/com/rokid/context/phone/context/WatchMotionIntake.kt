package com.rokid.context.phone.context

import com.rokid.context.core.model.Wire
import com.rokid.context.core.model.context.Evidence
import com.rokid.context.core.model.context.WatchMotionCodec
import com.rokid.context.core.model.context.WatchMotionMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The LiveFit watch message gate (spec §8). Pure: the provider passes the caller and a certificate check.
 * Checks, in order: caller, pinned certificate, source on, payload size, decode (malformed / version), `(epoch, seq)`, rate.
 * Documented choice: seq is checked BEFORE rate (spec §8 lists rate first), so a stale message never becomes the pending state
 * and gets a terminal `stale_seq`. A JSON without `"v"` is `malformed` (stricter than the codec; the sender always sends `v`).
 * [accept] is one critical section (check and advance of epoch, seq and the rate window together), so concurrent Binder
 * threads each see a consistent state. Accepted evidence goes to [sink], which only enqueues.
 *
 * Rate coalescing: a message refused only for `rate` is kept as the single pending latest state (a newer one replaces it) and
 * applied when the 1 s window opens, via [schedule] (delay in ms, action). The reply is still `rate`.
 * [clock] stamps the evidence (receipt time; the watch clock is never used); [mono] is the monotonic accept time for the rate window.
 */
class WatchMotionIntake(
    private val enabled: () -> Boolean,
    private val pinnedSha256: () -> String?,
    private val clock: () -> Long,
    private val sink: (Evidence) -> Unit,
    initialEpoch: Long = 0L,
    initialSeq: Long = 0L,
    private val persist: (epoch: Long, seq: Long) -> Unit = { _, _ -> },
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit = { _, _ -> },
    private val onEpochReset: () -> Unit = {},
    private val mono: () -> Long = clock,
    private val onReject: (reason: String) -> Unit = {},
) {
    data class Result(val ok: Boolean, val reason: String?)

    private var lastEpoch = initialEpoch
    private var lastSeq = initialSeq
    private var lastAcceptAt = Long.MIN_VALUE
    private var pending: WatchMotionMessage? = null
    private var timerArmed = false
    private var closed = false

    @Synchronized
    fun accept(callerPackage: String?, certMatches: (String) -> Boolean, json: String?): Result =
        check(callerPackage, certMatches, json).also { if (!it.ok) onReject(it.reason ?: "") }

    private fun check(callerPackage: String?, certMatches: (String) -> Boolean, json: String?): Result {
        if (callerPackage != LIVEFIT_PACKAGE) return no("bad_caller")
        val pin = pinnedSha256()
        if (pin == null || !certMatches(pin)) return no("bad_cert")
        if (closed || !enabled()) return no("not_enabled")
        if (json == null) return no("malformed")
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return no("too_big")
        val v = versionOf(json) ?: return no("malformed")
        if (v != WatchMotionCodec.VERSION) return no("version")
        val m = WatchMotionCodec.decode(json) ?: return no("malformed")
        if (m.epoch == lastEpoch && m.seq <= lastSeq) return no("stale_seq")
        val pend = pending
        if (pend != null && m.epoch == pend.epoch && m.seq < pend.seq) return no("stale_seq")
        val now = mono()
        if (lastAcceptAt != Long.MIN_VALUE && now - lastAcceptAt < MIN_GAP_MS) {
            pending = m
            if (!timerArmed) { timerArmed = true; schedule(MIN_GAP_MS - (now - lastAcceptAt)) { fire() } }
            return no("rate")
        }
        pending = null
        apply(m, now)
        return Result(true, null)
    }

    /** Stops the intake: a pending state is dropped and every later call is `not_enabled`. */
    @Synchronized fun close() { closed = true; pending = null }

    @Synchronized private fun fire() {
        timerArmed = false
        val m = pending ?: return
        if (closed || !enabled()) { pending = null; return }
        val now = mono()
        val wait = MIN_GAP_MS - (now - lastAcceptAt)
        if (wait > 0) { timerArmed = true; schedule(wait) { fire() }; return }
        pending = null
        if (m.epoch == lastEpoch && m.seq <= lastSeq) return
        apply(m, now)
    }

    private fun apply(m: WatchMotionMessage, now: Long) {
        if (lastEpoch != 0L && m.epoch != lastEpoch) onEpochReset()
        lastEpoch = m.epoch; lastSeq = m.seq; lastAcceptAt = now
        persist(m.epoch, m.seq)
        sink(Evidence.WatchMotion(clock(), m.state, m.confidence))
    }

    private fun no(reason: String) = Result(false, reason)

    private fun versionOf(json: String): Int? = try {
        ((Wire.json.parseToJsonElement(json) as? JsonObject)?.get("v") as? JsonPrimitive)?.intOrNull
    } catch (e: Exception) { null }

    companion object {
        const val LIVEFIT_PACKAGE = "com.livear.fit"
        const val MAX_BYTES = 2048
        const val MIN_GAP_MS = 1000L

        /** The process-wide intake the provider reads: set by `WatchSource.start()`, cleared by `stop()`; null = `not_enabled`. */
        @Volatile var current: WatchMotionIntake? = null
    }
}
