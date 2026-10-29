package com.rokid.context.phone.context

import com.rokid.context.core.model.context.WatchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the watch link did since the process started. Memory only: a state name, times and counts; no node names, no payloads. */
data class WatchLinkSnapshot(
    val lastAcceptedAtMs: Long? = null,
    val lastState: WatchState? = null,
    val accepted: Int = 0,
    val drops: Map<String, Int> = emptyMap(),
    val lastDropReason: String? = null,
) {
    val rejected: Int get() = drops.values.sum()
}

/**
 * The watch link's in-memory ledger: the intake's last accepted message (receipt time stamped on the phone) and every dropped
 * message by reason. Drives the Context sources status row and the setup step; it never feeds the hub (the hub's own
 * aggregation and its `watch` / `watchRej` counters are untouched). [offSink] counts a drop that never reached an intake
 * (source off, hub not running) in the hub's `watchRej` counter; HubGraph wires it.
 */
class WatchLinkState(private val dropLog: WatchDropLog = WatchDropLog { com.rokid.context.core.log.PcLog.i("PcCtx", it) }) {
    private val _snapshot = MutableStateFlow(WatchLinkSnapshot())
    val snapshot: StateFlow<WatchLinkSnapshot> = _snapshot

    @Volatile var offSink: () -> Unit = {}

    fun onAccepted(state: WatchState, atMs: Long) {
        _snapshot.update { it.copy(lastAcceptedAtMs = atMs, lastState = state, accepted = it.accepted + 1) }
    }

    /** A refusal the intake already counted (its own `watchRej`). */
    fun onDrop(reason: String) {
        dropLog.drop(reason)
        _snapshot.update { it.copy(drops = it.drops + (reason to ((it.drops[reason] ?: 0) + 1)), lastDropReason = reason) }
    }

    /** A drop before any intake: counted here and in the hub counter, never silent. */
    fun dropOff(reason: String) { onDrop(reason); try { offSink() } catch (_: Exception) { } }

    companion object { val shared = WatchLinkState() }
}

/**
 * One log line per rejected watch message, rate-limited: for each fixed reason token at most one line per [windowMs], carrying
 * the count of drops since that reason's last line. Only a token and a count are logged, never a payload, state or node name.
 * Internal reasons map to the fixed set `source_off, hub_down, bad_path, bad_version, stale_seq, rate, malformed, wrong_source`.
 */
class WatchDropLog(private val windowMs: Long = 60_000L, private val clock: () -> Long = System::currentTimeMillis, private val sink: (String) -> Unit) {
    private class Slot { var lastAt = Long.MIN_VALUE; var pending = 0 }
    private val slots = HashMap<String, Slot>()

    fun drop(reason: String) {
        val token = token(reason)
        val line = synchronized(slots) {
            val s = slots.getOrPut(token) { Slot() }
            s.pending++
            val now = clock()
            if (s.lastAt != Long.MIN_VALUE && now - s.lastAt < windowMs) null
            else { val n = s.pending; s.pending = 0; s.lastAt = now; "watch drop reason=$token n=$n" }
        }
        if (line != null) try { sink(line) } catch (_: Exception) { }
    }

    companion object {
        val TOKENS = setOf("source_off", "hub_down", "bad_path", "bad_version", "stale_seq", "rate", "malformed", "wrong_source")

        fun token(reason: String): String = when (reason) {
            "not_enabled", "source_off" -> "source_off"
            "hub_off", "hub_down" -> "hub_down"
            "version", "bad_version" -> "bad_version"
            "bad_source", "wrong_source" -> "wrong_source"
            "too_big", "malformed" -> "malformed"
            "bad_path", "stale_seq", "rate" -> reason
            else -> "malformed"
        }
    }
}

/**
 * The Data Layer receiver's pure core (spec 2026-10-29 §3). The platform service only calls [handle]. Order: path, active source
 * ([sourceOn]: `watch_app`), a running intake, then the intake's own checks (source tag, size, version, seq/epoch, rate). A message
 * dropped because the source is off or the hub is not running is counted with its reason and stores nothing.
 */
object PcaWatchLink {
    const val PATH = "/pca/motion"

    fun handle(
        path: String, data: ByteArray?, sourceOn: Boolean,
        intake: WatchMotionIntake? = WatchMotionIntake.current, link: WatchLinkState = WatchLinkState.shared,
    ): WatchMotionIntake.Result {
        if (path != PATH) return refuse(link, "bad_path")
        if (!sourceOn) return refuse(link, "source_off")
        val i = intake ?: return refuse(link, "hub_off")
        val json = data?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() }
        return i.accept(json)
    }

    private fun refuse(link: WatchLinkState, reason: String): WatchMotionIntake.Result { link.dropOff(reason); return WatchMotionIntake.Result(false, reason) }
}
