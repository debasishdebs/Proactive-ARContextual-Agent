package com.rokid.context.orchestration.proactive

import com.rokid.context.memory.forget.PHOTO_CITE_PREFIX
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.Ref

/**
 * v3.0 proactive loop, SHADOW MODE (design 2026-11-18, rulings 2026-11-18 "proxy rulings 3"). Nothing here delivers anything: a suggestion that passes every gate is only RECORDED as "would show".
 * Room stays at v17: records live behind [SuggestionStore] (memory, or encrypted app-private prefs excluded from backup).
 */
enum class ProactiveFamily(val token: String, val prior: Double) {
    /** F2: "last time here" from place memories, the pin's note and marks. */
    PLACE_RECALL("f2", 0.9),
}

/** What started a run. All ride existing hooks: the 15 minute cadence worker, a PlaceSampler match, a Moment or Scene end. */
enum class TriggerKind(val token: String) { TICK("tick"), PLACE_ARRIVAL("place_arrival"), MOMENT_END("moment_end");
    companion object { fun of(token: String?): TriggerKind? = entries.firstOrNull { it.token == token } }
}

/**
 * The typed source ids of a suggestion (ruling: pins, marks and photos are tracked explicitly, `Leases.refsOf` reads every non-photo id as a memory):
 * `mem:<id>`, `pin:<id>`, `mark:<id>`, `frame:<id>`. Ids only, never text.
 */
object SourceIds {
    const val MEM = "mem:"
    const val PIN = "pin:"
    const val MARK = "mark:"
    const val FRAME = PHOTO_CITE_PREFIX

    fun mem(id: String) = MEM + id
    fun pin(id: String) = PIN + id
    fun mark(id: String) = MARK + id
    fun frame(id: String) = FRAME + id

    fun memories(src: Collection<String>) = src.filter { it.startsWith(MEM) }.map { it.removePrefix(MEM) }
    fun pins(src: Collection<String>) = src.filter { it.startsWith(PIN) }.map { it.removePrefix(PIN) }
    fun marks(src: Collection<String>) = src.filter { it.startsWith(MARK) }.map { it.removePrefix(MARK) }
    fun frames(src: Collection<String>) = src.filter { it.startsWith(FRAME) }.map { it.removePrefix(FRAME) }

    /** Every id an id the lease and the Forget cascade know about: memories, photos, marks, and pins (a config row of the place pack). */
    fun refs(src: Collection<String>): List<Ref> =
        memories(src).map { Ref(Family.MEMORY, it) } + frames(src).map { Ref(Family.PHOTO, it) } + marks(src).map { Ref(Family.MARK, it) } + pins(src).map { Ref(Family.PLACE_PACK, it) }

    /** What `ProvenanceGate.check(ids, generation)` takes: memory ids and `frame:` cites (marks and pins have their own checks). */
    fun provenance(src: Collection<String>): List<String> = memories(src) + src.filter { it.startsWith(FRAME) }
}

/** What the template needs; derived from stored sources, never stored itself. */
data class PlaceFacts(val placeName: String, val lastVisitMs: Long, val earlierVisits: Int, val note: String?, val markCount: Int)

/** One thing that could be suggested. [key] is stable and text-free; it is what feedback and cooldowns use. */
data class Candidate(
    val key: String,
    val family: ProactiveFamily,
    /** The place this is about (a `wd:` id or a `user:` pin id): the zone check judges it. */
    val anchorKey: String,
    val sourceIds: List<String>,
    val facts: PlaceFacts,
    val confidence: Double,
    val arrivedAtMs: Long,
)

/** A would-show suggestion: ids and fixed tokens only. The card text is a template rendered again from the live sources when shown, so Forget leaves no text behind. */
data class SuggestionRecord(
    val id: String,
    val ts: Long,
    val key: String,
    val family: ProactiveFamily,
    val trigger: TriggerKind,
    val score: Double,
    val anchorKey: String,
    val sourceIds: List<String>,
    /** Fixed tokens the "why am I seeing this" sheet turns into words: `visits=3`, `pin_note`, `marks=1`, `arrived`. */
    val factors: List<String>,
    /** v3.1: the [Surface] tokens this record was already delivered to (at most once per record and surface). Fixed tokens only. */
    val delivered: Set<String> = emptySet(),
)

/** v3.1 delivery surfaces; each has its own default-OFF switch ([ProactiveSettings]) and all need the master switch. */
enum class Surface(val token: String) { PHONE("phone"), HUD("hud"), NEXUS("nexus");
    companion object { fun of(token: String?): Surface? = entries.firstOrNull { it.token == token } }
}

/** Every reason a candidate or a run stops, as a fixed token (counters and replay verdicts). */
enum class Gate {
    SWITCH, PAUSE, ZONE, ZONE_ANCHOR, THRESHOLD, OFF, QUIET_HOURS, CAP_REACHED, GLOBAL_CAP, FAMILY_MAX, SHOWN_GAP, COOLDOWN, FEEDBACK, LEASE, WEAR, DRIVING, PROVENANCE, COMMIT, ERROR, NO_CANDIDATES, NOT_PICKED, WOULD_SHOW,
    /** v3.1 delivery: the surface switch is off, the record was already shown there, or it was withdrawn (dismissed, forgotten) before delivery. */
    SURFACE_OFF, ALREADY_DELIVERED, WITHDRAWN, DELIVERED,
}
