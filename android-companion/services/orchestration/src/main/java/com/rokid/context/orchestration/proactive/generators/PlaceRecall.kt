package com.rokid.context.orchestration.proactive.generators

import com.rokid.context.orchestration.proactive.Candidate
import com.rokid.context.orchestration.proactive.PlaceFacts
import com.rokid.context.orchestration.proactive.ProactiveFamily
import com.rokid.context.orchestration.proactive.SourceIds
import com.rokid.context.orchestration.proactive.SuggestionRecord
import com.rokid.context.orchestration.proactive.Templates
import java.time.ZoneId

/** One place memory (`type="place"`): the user looked at [placeName] on [tsMs]. [placeKey] is the `wd:` tag, or `user:<id>` when the name is a pin's. */
data class PlaceVisit(val memoryId: String, val frameId: String?, val placeKey: String, val placeName: String, val parentName: String?, val tsMs: Long, val confidence: Double)
data class PinInfo(val id: String, val name: String, val note: String)
data class MarkInfo(val id: String, val frameId: String?, val atMs: Long)

/** Read-only access to what F2 is built from. Everything it returns is live and as of [untilMs]; an implementation that cannot read answers an empty list (nothing to suggest). */
interface PlaceHistorySource {
    /** Place memories no newer than [untilMs], newest first. */
    suspend fun visits(untilMs: Long, limit: Int): List<PlaceVisit>
    suspend fun pins(): List<PinInfo>
    suspend fun marksOnFrames(frameIds: Collection<String>): List<MarkInfo>
}

/** What a generator found: the candidates, and how many it dropped because their place is inside a pause zone. */
data class Generated(val candidates: List<Candidate>, val zoneDropped: Int = 0)

interface CandidateGenerator {
    /** [anchorBlocked] says whether a place (by key) is inside an enabled zone (true also when it cannot be told: fail closed). */
    suspend fun generate(nowMs: Long, anchorBlocked: suspend (String) -> Boolean): Generated
}

/** What the "why" sheet and the shadow list show for one record: template text and the sources with their times. */
data class RenderedCard(val text: String, val sourceLabel: String, val sources: List<RenderedSource>)
data class RenderedSource(val kind: String, val tsMs: Long?)

/**
 * F2 place recall (design 2.2): on arrival at place P (a place memory of the last [ARRIVAL_WINDOW_MS]), earlier visits to P (or to its parent site) older than a day, P's pin note and the marks made on those
 * visits' photos. Nothing from the visit in progress, nothing inside a zone. Zero provider calls.
 */
class PlaceRecallGenerator(private val source: PlaceHistorySource, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : CandidateGenerator {
    override suspend fun generate(nowMs: Long, anchorBlocked: suspend (String) -> Boolean): Generated {
        val visits = source.visits(nowMs, MAX_VISITS).filter { it.tsMs <= nowMs }
        val arrived = visits.filter { nowMs - it.tsMs in 0..ARRIVAL_WINDOW_MS }.groupBy { it.placeKey }
        if (arrived.isEmpty()) return Generated(emptyList())
        val pins = source.pins().associateBy { it.id }
        val out = ArrayList<Candidate>()
        var dropped = 0
        for ((key, now) in arrived) {
            val here = now.maxByOrNull { it.tsMs }!!
            val blocked = try { anchorBlocked(key) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (e: Exception) { true }
            if (blocked) { dropped++; continue }
            // earlier visits: the same place, or the visit to the site it is a part of; never one of the visits in progress
            val earlier = visits.filter { v -> v.tsMs < nowMs - DAY_MS && (v.placeKey == key || (here.parentName != null && v.placeName.equals(here.parentName, true))) }.sortedByDescending { it.tsMs }
            val last = earlier.firstOrNull() ?: continue
            val pin = pins[key]?.takeIf { it.note.isNotBlank() }
            val recent = earlier.take(RECENT_VISITS)
            val marks = source.marksOnFrames(recent.mapNotNull { it.frameId }).sortedByDescending { it.atMs }
            // EVERY source the text and the counts depend on: all earlier visits (the count and the last date), the arrival memory, the pin (also one with a blank note: its note is what is shown), all marks counted
            val sources = buildList {
                add(SourceIds.mem(last.memoryId)); last.frameId?.let { add(SourceIds.frame(it)) }
                for (v in earlier) { add(SourceIds.mem(v.memoryId)); v.frameId?.let { add(SourceIds.frame(it)) } }
                add(SourceIds.mem(here.memoryId)); here.frameId?.let { add(SourceIds.frame(it)) }
                pins[key]?.let { add(SourceIds.pin(it.id)) }
                marks.forEach { add(SourceIds.mark(it.id)) }
            }.distinct()
            val conf = (0.5 + 0.4 * last.confidence.coerceIn(0.0, 1.0) + (if (pin != null) 0.1 else 0.0) + (if (marks.isNotEmpty()) 0.05 else 0.0)).coerceAtMost(1.0)
            out += Candidate("f2:$key", ProactiveFamily.PLACE_RECALL, key, sources, PlaceFacts(here.placeName, last.tsMs, earlier.size, pin?.note, marks.size), conf, here.tsMs)
        }
        return Generated(out, dropped)
    }

    /** The card of a stored record, rebuilt from the LIVE sources; null when any of them is gone (a Forget, a deleted pin): nothing is shown from a half-gone record. */
    suspend fun render(r: SuggestionRecord, nowMs: Long): RenderedCard? {
        val visits = source.visits(nowMs, MAX_VISITS)
        val mem = SourceIds.memories(r.sourceIds).distinct().map { id -> visits.firstOrNull { it.memoryId == id } ?: return null }
        val last = mem.firstOrNull() ?: return null
        val pin = SourceIds.pins(r.sourceIds).firstOrNull()?.let { id -> source.pins().firstOrNull { it.id == id } ?: return null }
        val markIds = SourceIds.marks(r.sourceIds)
        val marks = if (markIds.isEmpty()) emptyList() else source.marksOnFrames(SourceIds.frames(r.sourceIds)).filter { it.id in markIds }.also { if (it.map { m -> m.id }.toSet().size != markIds.toSet().size) return null }
        val facts = PlaceFacts(last.placeName, last.tsMs, r.factors.firstNotNullOfOrNull { it.removePrefix("visits=").takeIf { s -> s != it }?.toIntOrNull() } ?: 1, pin?.note, r.factors.firstNotNullOfOrNull { it.removePrefix("marks=").takeIf { s -> s != it }?.toIntOrNull() } ?: marks.size)
        val z = zone()
        val srcs = mem.map { RenderedSource("memory", it.tsMs) } + listOfNotNull(pin?.takeIf { it.note.isNotBlank() }?.let { RenderedSource("pin", null) }) + marks.map { RenderedSource("mark", it.atMs) }
        return RenderedCard(Templates.placeRecall(facts, z), Templates.sourceLabel(srcs.size, last.tsMs, z), srcs)
    }

    companion object {
        const val ARRIVAL_WINDOW_MS = 20 * 60_000L
        const val DAY_MS = 24 * 3_600_000L
        const val MAX_VISITS = 200
        const val RECENT_VISITS = 5

        /** The fixed tokens of a candidate (the why sheet's input). */
        fun factors(c: Candidate): List<String> = buildList {
            add("arrived"); add("visits=${c.facts.earlierVisits}")
            if (!c.facts.note.isNullOrBlank()) add("pin_note")
            if (c.facts.markCount > 0) add("marks=${c.facts.markCount}")
        }
    }
}
