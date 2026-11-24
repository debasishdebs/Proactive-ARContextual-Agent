package com.rokid.context.work.frames.place.replay

import com.rokid.context.work.frames.place.BackendChoice
import com.rokid.context.work.frames.place.PlaceFlight
import com.rokid.context.work.frames.place.PlaceOutcome
import com.rokid.context.work.frames.place.PlaceSampler
import com.rokid.context.work.frames.place.RetryReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** One replay run: what is simulated around the real sampler. */
data class RunSpec(
    val stage: MatchStage, val scenario: FixScenario, val activation: Activation = Activation.AS_CREATED, val overlays: Overlays = Overlays.HEAD,
    /** Track search window N (plan 4.1). */
    val nMs: Long = ReplayTrack.FIVE_MIN, val sites: Boolean = false,
    /** Calibration only: the Historical stage answers NONE for every frame the trip did not record, which is not what the real model did; the calibration therefore runs the dedupe of the build that made the trip. */
    val legacyDedupe: Boolean = false,
) { val label: String get() = "${stage.label} x ${scenario.label} x ${activation.name}${if (sites) " +sites" else ""} x ${overlays.label}" }

/** One poke of the replay. [extra] is "" for a frame poke, "+F4" for the extra poke at a pin's creation. */
data class ReplayRow(
    val pokeMs: Long, val extra: String, val frameId: String?, val frameCapMs: Long?, val motion: String, val pos: String, val assumed: Boolean, val f2: Boolean,
    val outcome: PlaceOutcome?, val reason: String, val chosenId: String, val chosenName: String, val f1: String, val note: String, val attemptLine: String,
    /** Parsed from the attempt line (H2): `card=`, `cardWhy=`, `call=`, `pinFirst=`, `fixSrc=`. */
    val card: String = "-", val cardWhy: String = "-", val call: String = "-", val pinFirst: String = "-", val fixSrc: String = "-",
    /** The names of every place the card names (one for a single card, the members for a cluster card; `chosen=` then holds comma-separated ids). */
    val names: List<String> = if (chosenName.isEmpty()) emptyList() else listOf(chosenName),
) {
    /** H2: a card is `card=shown` on the attempt line, not an outcome name (a MATCHED may be `card=suppressed`). */
    val isCard get() = card == "shown"
    val isPinCard get() = isCard && outcome?.name == "PIN_FIRST"
    val isClusterCard get() = isCard && pinFirst.startsWith("cluster(")
    val isSuppressed get() = card == "suppressed"
    val isCall get() = call == "made"
    val verdict get() = "${outcome?.name ?: "-"} · $reason"
}

data class ReplayRequest(val frameId: String, val lines: List<String>)
data class ReplayResult(val spec: RunSpec, val rows: List<ReplayRow>, val requests: List<ReplayRequest>)

/**
 * Drives the REAL [PlaceSampler] through its constructor seams (the same ones PlaceSamplerTest uses) over the whole bundle (plan 7): for every frame_meta row, in createdAt order, it sets the replay
 * clock to createdAt and calls poke(). No gate is copied: the order of the gates, the reason tokens, RECENT/SKEW, fix limits, Geo.candidates, DEDUPE, the call gap and the thresholds are the sampler's own.
 * Not simulated: BUSY (the fakes return at once), real call latency.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripReplayRunner(private val bundle: ReplayBundle) {
    private val track = ReplayTrack(bundle.fixes)

    private data class Event(val t: Long, val frame: ReplayFrame?, val extra: String, val pinId: String? = null)

    fun run(scope: TestScope, spec: RunSpec): ReplayResult {
        var now = 0L
        val store = ReplayFrameStore(bundle.frames, { now })
        val pack = ReplayPinPack(bundle, spec.activation, spec.sites) { now }
        val fixSource = ReplayFixSource(bundle, track, spec.scenario, spec.nMs, spec.overlays.f2Ms)
        val matcher = spec.stage.create(bundle)
        val flight = PlaceFlight()
        val logs = ArrayList<String>()
        val requests = ArrayList<ReplayRequest>()
        var lastFix: FixResult? = null
        val f1 = if (spec.overlays.f1) PinFirstRule() else null

        fun sampler() = PlaceSampler(
            scope = scope, store = store,
            fix = { lastFix = fixSource.fixAt(now, store.lastReturned); lastFix!!.fix },
            candidates = { lat, lon, up -> pack.candidates(lat, lon, up) },
            match = { id, lines -> requests += ReplayRequest(id, lines); matcher.match(id, lines) },
            saveMemory = { true }, onCard = {}, clock = { now }, enabled = { true }, zoneBlocked = { false }, networkUp = { true },
            backend = { BackendChoice.ONLINE }, admit = { true }, photoAllowed = { true }, zones = { emptyList() }, flight = flight,
            log = { logs += it }, stats = {}, elapsed = { now },
            dedupeV2On = { !spec.legacyDedupe }, retryOn = { !spec.legacyDedupe }, // S2 is built: the replay runs the real dedupe v2
        )

        val main = sampler()
        val events = ArrayList<Event>()
        for (f in bundle.frames) events += Event(f.createdAt, f, "")
        if (spec.activation == Activation.AS_CREATED && !spec.legacyDedupe) for (p in bundle.pins) if (p.activeFrom > 0) events += Event(p.activeFrom + 1_000, null, "+F4", p.id) // the pin dialog's save: the real retryRecent(PIN_CREATED)
        events.sortWith(compareBy({ it.t }, { if (it.extra.isEmpty()) 0 else 1 }))

        val rows = ArrayList<ReplayRow>(events.size)
        for (ev in events) {
            now = ev.t
            store.lastReturned = null; lastFix = null; matcher.note = null
            val f1Decision = f1?.let { rule ->
                val frame = store.peek(now - PlaceSampler.RECENT_MS)
                rule.decide(now, fixSource.fixAt(now, frame).fix, pack.activePins()).text
            } ?: "-"
            val mark = logs.size
            if (ev.extra == "+F4") main.retryRecent(RetryReason.PIN_CREATED, ev.pinId) else main.poke()
            scope.runCurrent()
            val line = logs.drop(mark).lastOrNull { it.startsWith("place attempt ") } ?: ""
            val kv = KV.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
            val outcome = kv["outcome"]?.let { runCatching { PlaceOutcome.valueOf(it) }.getOrNull() }
            val fr = store.lastReturned
            val fx = lastFix
            val chosen = kv["chosen"]?.takeIf { it != "-" }.orEmpty()
            rows += ReplayRow(
                now, ev.extra, fr?.frameId, fr?.capturedAt, fr?.motion ?: ev.frame?.motion ?: "-",
                if (fx == null) "-" else if (fx.pos == null) "UNKNOWN" else fx.pos.label() + " acc=${fx.accM.toInt()}", fx?.assumed == true, fx?.f2 == true,
                outcome, kv["reason"] ?: "-", chosen, chosen.split(',').mapNotNull { id -> pack.nameOf(id) }.joinToString(" + "), f1Decision, matcher.note.orEmpty(), line,
                kv["card"] ?: "-", kv["cardWhy"] ?: "-", kv["call"] ?: "-", kv["pinFirst"] ?: "-", kv["fixSrc"] ?: "-",
                chosen.split(',').mapNotNull { id -> pack.nameOf(id) },
            )
        }
        return ReplayResult(spec, rows, requests)
    }

    companion object { private val KV = Regex("""(\w+)=(\S+)""") }
}
