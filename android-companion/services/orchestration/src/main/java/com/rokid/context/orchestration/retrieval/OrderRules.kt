package com.rokid.context.orchestration.retrieval

import com.rokid.context.ai.canvas.OrderSpec
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.retrieval.ScoredMemory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Retrieval-quality spec section 5: which memories of a retrieval reach the prompt, and in which order, when the plan has a `rag.order` node.
 * Pure Kotlin, no I/O: the caller (RagCore) reads the memories and hands in the candidates. The question's words pick a row (night extent, previous,
 * first, latest, count, state; the phrases are the node's own params); a question without a row keeps the node's default order and selection, a
 * multi-day window gets one hit per day, a one-day window its top hits in time order.
 */
object OrderRules {
    enum class Row { NIGHT, PREVIOUS, FIRST, LATEST, COUNT, STATE }

    /** [row] null = no row matched. [order]: RELEVANCE, NEWEST, OLDEST or TIME. [selection]: TOP_K or PER_DAY. [offset]: episodes to skip (1 = the one before the newest). [limit]: top-k raised for a one-day window. */
    data class Decision(val row: Row?, val order: String, val selection: String, val offset: Int = 0, val ignoreRelevance: Boolean = false, val limit: Int? = null) {
        /** Nothing to do: today's relevance order and top-k. */
        val isNoop: Boolean get() = row == null && order == "RELEVANCE" && selection == "TOP_K" && limit == null
    }

    /** A candidate memory with its event time and its fused retrieval score (0 when it did not come from a search). */
    class Cand(val memory: MemoryEntity, val at: Long, val fused: Double)

    /** The chosen memories in prompt order; [compact] = the short per-day line form; [note] the sentence for the note line. */
    class Picked(val items: List<Cand>, val compact: Boolean, val note: String)

    const val EPISODE_GAP_MS = 30 * 60_000L
    const val RELEVANT_POOL = 40
    const val ONE_DAY_LIMIT = 12
    const val NEWEST_LIMIT = 12
    const val NIGHT_START_HOUR = 6 // a capture before 06:00 belongs to the night that began the evening before
    private const val MULTI_DAY_MS = 36 * 3_600_000L
    private val LAST_IS_WINDOW = "(?!\\s+(?:night|week|weekend|month|year|few|couple|\\d+|monday|tuesday|wednesday|thursday|friday|saturday|sunday))"

    private fun matches(text: String, phrases: List<String>): Boolean = phrases.any { p ->
        val body = p.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        // "last" is a window word before night/week/month/a weekday/a count (TimeWindowRules reads those), an ordering word everywhere else.
        Regex("\\b" + body + "\\b" + if (p.trim() == "last") LAST_IS_WINDOW else "").containsMatchIn(text)
    }

    fun decide(question: String, spec: OrderSpec, window: TimeWindow?): Decision {
        val q = question.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        fun on(row: String) = matches(q, spec.words[row].orEmpty())
        return when {
            on("night") -> Decision(Row.NIGHT, "NEWEST", "PER_DAY", ignoreRelevance = true)
            on("previous") -> Decision(Row.PREVIOUS, "NEWEST", "TOP_K", offset = 1)
            on("first") -> Decision(Row.FIRST, "OLDEST", "TOP_K")
            on("latest") -> Decision(Row.LATEST, "NEWEST", "TOP_K")
            // one hit per day needs days: a one-day (or tonight) window, or no window, keeps its top hits in time order (a count there needs every line)
            on("count") -> if (multiDay(window)) Decision(Row.COUNT, "TIME", "PER_DAY") else Decision(Row.COUNT, "TIME", "TOP_K", limit = ONE_DAY_LIMIT)
            on("state") -> Decision(Row.STATE, "NEWEST", "TOP_K")
            multiDay(window) -> Decision(null, "TIME", "PER_DAY")
            window != null -> Decision(null, "TIME", "TOP_K", limit = ONE_DAY_LIMIT)
            else -> Decision(null, spec.defaultOrder, spec.selection)
        }
    }

    private val DEVICES = Regex("\\b(stove|oven|burner|hob|gas|kettle|iron|heater|geyser|fan|ac|tap|door|window|gate|lock|lights?|lamp|switch|plug|appliance|cooker|induction)\\b")
    private val STATES = Regex("\\b(on|off|open|opened|closed?|locked|unlocked|lit|left|leave|turn(ed)?|switch(ed)?|shut|forget|forgot)\\b")

    /** The default Ask plan (no `rag.order` node): a state / safety question ("did I turn off the stove", "are the lights on", "is the door closed"): the [STATE_WORDS] phrases, or a device word together with a state word. */
    fun isStateQuestion(question: String, phrases: List<String> = com.rokid.context.ai.canvas.OrderParams.STATE_WORDS.split(',').map { it.trim() }): Boolean {
        val q = question.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        return matches(q, phrases) || (DEVICES.containsMatchIn(q) && STATES.containsMatchIn(q))
    }

    private fun multiDay(w: TimeWindow?) = w != null && !w.nightly && w.toMs - w.fromMs >= MULTI_DAY_MS

    /** The candidates that count as relevant: the top [RELEVANT_POOL] by fused score with `fused >= floor * top`; a semantic-only score is flat, so there the floor applies to the min-max range of that pool. */
    fun relevant(hits: List<ScoredMemory>, floor: Double, semanticOnly: Boolean): List<ScoredMemory> {
        val top = hits.sortedByDescending { it.fused }.take(RELEVANT_POOL)
        if (top.isEmpty()) return top
        val hi = top.first().fused
        if (!semanticOnly) return top.filter { it.fused >= floor * hi }
        val lo = top.last().fused
        val span = hi - lo
        return if (span <= 1e-9) top else top.filter { (it.fused - lo) / span >= floor }
    }

    /** Groups [sorted] (already in the order of interest) into episodes: a member joins the open episode when it is within 30 minutes of the one before it. */
    fun episodes(sorted: List<Cand>): List<List<Cand>> {
        val out = mutableListOf<MutableList<Cand>>()
        for (c in sorted) {
            val last = out.lastOrNull()
            if (last != null && kotlin.math.abs(last.last().at - c.at) <= EPISODE_GAP_MS) last += c else out += mutableListOf(c)
        }
        return out
    }

    /**
     * Applies [d] to [relevant] (null when it has nothing to say: the caller keeps its order). [lineLen] is the cost of one prompt line of a candidate,
     * [budget] the characters left for the memory lines; [zone] decides the calendar day of a candidate.
     */
    fun pick(d: Decision, relevant: List<Cand>, spec: OrderSpec, topK: Int, zone: ZoneId, budget: Int, lineLen: (Cand) -> Int): Picked? {
        if (relevant.isEmpty()) return null
        return when (d.selection) {
            "PER_DAY" -> perDay(d, relevant, spec, zone, budget, lineLen)
            else -> topK(d, relevant, topK)
        }
    }

    private fun topK(d: Decision, relevant: List<Cand>, topK: Int): Picked? = when (d.order) {
        "NEWEST" -> {
            val eps = episodes(relevant.sortedByDescending { it.at })
            if (eps.size <= d.offset) null else {
                val items = eps.drop(d.offset).take(3).flatten().take(NEWEST_LIMIT)
                val skipped = if (d.offset > 0) "the latest occurrence was left out because the question asks for the one before it; " else ""
                Picked(items, false, "${skipped}memories are listed newest first, so the first line is the latest relevant one")
            }
        }
        "OLDEST" -> {
            val eps = episodes(relevant.sortedBy { it.at })
            Picked(eps.take(3).flatten().take(NEWEST_LIMIT), false, "memories are listed oldest first, so the first line is the earliest relevant one")
        }
        "TIME" -> Picked(relevant.sortedByDescending { it.fused }.take(maxOf(topK, d.limit ?: topK)).sortedBy { it.at }, false, "memories are in time order")
        else -> null
    }

    private fun perDay(d: Decision, relevant: List<Cand>, spec: OrderSpec, zone: ZoneId, budget: Int, lineLen: (Cand) -> Int): Picked {
        val night = d.row == Row.NIGHT
        val shiftMs = if (night) NIGHT_START_HOUR * 3_600_000L else 0L
        fun dayOf(c: Cand): LocalDate = Instant.ofEpochMilli(c.at - shiftMs).atZone(zone).toLocalDate()
        val perDay = if (night) 1 else spec.perDay
        // days newest first, at most maxDays of them; inside a day the best score first (a night keeps its LAST capture), a second line at least 30 minutes from the first
        val days = relevant.groupBy(::dayOf).toList().sortedByDescending { it.first }.take(spec.maxDays)
        class Slot(val c: Cand, val rank: Int)
        val slots = days.flatMap { (_, cs) ->
            val ordered = if (night) cs.sortedByDescending { it.at } else cs.sortedByDescending { it.fused }
            val picked = mutableListOf<Cand>()
            for (c in ordered) if (picked.size < perDay && picked.none { kotlin.math.abs(it.at - c.at) < EPISODE_GAP_MS }) picked += c
            picked.mapIndexed { i, c -> Slot(c, i) }
        }.toMutableList()
        // over budget: the second line of the lowest-scoring days goes first, then whole days (the lowest-scoring one); a night has one line each, so the oldest nights go
        var cost = slots.sumOf { lineLen(it.c) + 1 }
        while (cost > budget && slots.size > 1) {
            val victim = if (night) slots.minByOrNull { it.c.at }
            else slots.filter { it.rank > 0 }.minByOrNull { it.c.fused } ?: slots.minByOrNull { s -> slots.filter { dayOf(it.c) == dayOf(s.c) }.maxOf { it.c.fused } }
            slots.remove(victim ?: break)
            cost = slots.sumOf { lineLen(it.c) + 1 }
        }
        val items = slots.map { it.c }.sortedBy { it.at }
        val nDays = items.map(::dayOf).toSet().size
        val note = if (night) "one line per night: the last capture of each night (before ${NIGHT_START_HOUR.toString().padStart(2, '0')}:00 it belongs to the evening before), in time order; $nDays nights"
        else "up to $perDay memories per day, in time order, $nDays days"
        return Picked(items, true, note)
    }
}
