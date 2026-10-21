package com.rokid.context.phone.context

import com.rokid.context.core.model.context.CarLinkKind
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.Evidence
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Counts only (spec §10): never a name, address, coordinate or caption. Thread-safe. */
class ContextHubStats(private val clock: () -> Long = System::currentTimeMillis) {
    private val startedAtMs = clock()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val stateMs = ConcurrentHashMap<ContextState, AtomicLong>()
    @Volatile var phoneBatteryStart: Int = -1
    @Volatile var phoneBatteryNow: Int = -1

    private fun add(key: String, n: Long = 1) { counters.getOrPut(key) { AtomicLong() }.addAndGet(n) }
    private fun get(key: String): Long = counters[key]?.get() ?: 0L

    fun count(e: Evidence) {
        when (e) {
            is Evidence.ActivityTransition -> add("ar")
            is Evidence.Steps -> add("steps")
            is Evidence.Speed -> add("loc")
            is Evidence.CarLink -> add(if (e.kind == CarLinkKind.ANDROID_AUTO) "aa" else "bt")
            is Evidence.Airplane -> add("air")
            is Evidence.GlassesMotion -> add("hints")
            is Evidence.WatchMotion -> add("watch")
            is Evidence.Intent -> Unit // counted by onIntent
        }
    }

    fun onSend() = add("sends")
    fun onChange() = add("changes")
    fun onIntent(ok: Boolean) = add(if (ok) "intent" else "intentFail")
    fun onIntentSkip() = add("intentSkip")
    fun onDropped() = add("dropped")
    fun onWatchRejected() = add("watchRej")
    fun onWatchEpoch() = add("watchEpoch")
    fun addLocOnSeconds(s: Long) = add("locOnS", s)
    fun addStateTime(state: ContextState, ms: Long) { if (ms > 0) stateMs.getOrPut(state) { AtomicLong() }.addAndGet(ms) }

    private fun batt(v: Int) = if (v < 0) "na" else v.toString()

    /** The one log line (tag `PcCtx`), spec §10. */
    fun line(state: ContextState): String =
        "ctxhub v=1 state=${state.name} ar=${get("ar")} steps=${get("steps")} loc=${get("loc")} locOnS=${get("locOnS")} bt=${get("bt")} " +
            "aa=${get("aa")} air=${get("air")} hints=${get("hints")} watch=${get("watch")} watchRej=${get("watchRej")} " +
            "intent=${get("intent")} intentFail=${get("intentFail")} intentSkip=${get("intentSkip")} sends=${get("sends")} " +
            "changes=${get("changes")} batt=${batt(phoneBatteryStart)}->${batt(phoneBatteryNow)}"

    /** For Diagnostics: every counter, seconds per committed state (`state_<NAME>_s`) and the battery. */
    fun snapshot(): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        for (k in KEYS) m[k] = get(k)
        for (s in ContextState.entries) m["state_${s.name}_s"] = (stateMs[s]?.get() ?: 0L) / 1000
        m["battStart"] = phoneBatteryStart.toLong(); m["battNow"] = phoneBatteryNow.toLong()
        m["uptimeS"] = (clock() - startedAtMs).coerceAtLeast(0) / 1000
        return m
    }

    private companion object {
        val KEYS = listOf("ar", "steps", "loc", "locOnS", "bt", "aa", "air", "hints", "watch", "watchRej", "watchEpoch", "intent", "intentFail", "intentSkip", "sends", "changes", "dropped")
    }
}
