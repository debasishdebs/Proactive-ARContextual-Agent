package com.rokid.context.orchestration.proactive

/** What a ledger line records: a suggestion that reached a surface, or a dismissal ("dismiss" or "never") by the user. */
enum class LedgerKind(val token: String) {
    DELIVERED("d"), DISMISSED("x"),
    /** v3.1 r2: written BEFORE a delivery claims and publishes (budget reserved first); becomes [DELIVERED] when the delivery is confirmed. It counts as consumption exactly like [DELIVERED], also after a process death between the phases. */
    RESERVED("r");
    companion object { fun of(token: String?): LedgerKind? = entries.firstOrNull { it.token == token } }
}

/** One ledger line: a timestamp, the family and the kind. NO content, no key, no place, no ids. */
data class LedgerEntry(val ts: Long, val family: ProactiveFamily, val kind: LedgerKind)

/**
 * The delivery ledger (Codex v3.1 r1 P1-5): an append-only, bounded list that is independent of the retained records, so a dismissal (which deletes the record) does NOT free budget. It feeds the daily cap, the family
 * cap, the gap and the "five dismissals pause" rule, and the LLM nudge gates count its delivered lines too. Pruned after [KEEP_MS].
 */
object DeliveryLedger {
    const val KEEP_MS = 48 * 3_600_000L
    const val MAX_ENTRIES = 400
    /** Five dismissals ("dismiss" or "never") inside [DISMISS_WINDOW_MS] pause delivery for [DISMISS_PAUSE_MS] (counted from the fifth). */
    const val DISMISS_LIMIT = 5
    const val DISMISS_WINDOW_MS = 24 * 3_600_000L
    const val DISMISS_PAUSE_MS = 24 * 3_600_000L

    /** Oldest first; drops what is older than [KEEP_MS] before the newest line and beyond [MAX_ENTRIES]. */
    fun prune(entries: List<LedgerEntry>, nowMs: Long): List<LedgerEntry> {
        val ref = maxOf(nowMs, entries.maxOfOrNull { it.ts } ?: nowMs)
        return entries.filter { ref - it.ts <= KEEP_MS }.sortedBy { it.ts }.takeLast(MAX_ENTRIES)
    }

    /** True for the kinds that consume budget: delivered, and reserved (a delivery that was started; conservative if it never finished). */
    fun consumes(e: LedgerEntry) = e.kind == LedgerKind.DELIVERED || e.kind == LedgerKind.RESERVED

    fun delivered(entries: List<LedgerEntry>) = entries.filter(::consumes)

    /** [e] (a RESERVED line) becomes DELIVERED; null = no such line. */
    fun confirmed(entries: List<LedgerEntry>, e: LedgerEntry): List<LedgerEntry>? {
        val i = entries.indexOfFirst { it == e }
        return if (i < 0) null else entries.toMutableList().also { it[i] = e.copy(kind = LedgerKind.DELIVERED) }
    }

    /** One line equal to [e] removed; null = no such line. */
    fun released(entries: List<LedgerEntry>, e: LedgerEntry): List<LedgerEntry>? {
        val i = entries.indexOfFirst { it == e }
        return if (i < 0) null else entries.toMutableList().also { it.removeAt(i) }
    }

    /** How many suggestions were delivered at or after [sinceMs] (what the nudge gates add to their own counts). */
    fun deliveredSince(entries: List<LedgerEntry>, sinceMs: Long): Int = entries.count { consumes(it) && it.ts >= sinceMs }

    /** The time until which delivery is paused by dismissals (0 = not paused): the latest "fifth dismissal within 24 h" plus 24 h. */
    fun pausedUntil(entries: List<LedgerEntry>): Long {
        val d = entries.filter { it.kind == LedgerKind.DISMISSED }.map { it.ts }.sorted()
        var until = 0L
        for (i in DISMISS_LIMIT - 1 until d.size) if (d[i] - d[i - (DISMISS_LIMIT - 1)] < DISMISS_WINDOW_MS) until = maxOf(until, d[i] + DISMISS_PAUSE_MS)
        return until
    }

    fun encode(entries: List<LedgerEntry>): String = org.json.JSONArray().also { a -> entries.forEach { a.put(org.json.JSONArray().put(it.ts).put(it.family.token).put(it.kind.token)) } }.toString()

    fun decode(json: String?): List<LedgerEntry> = try {
        val a = org.json.JSONArray(json ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val e = a.getJSONArray(i)
            val fam = ProactiveFamily.entries.firstOrNull { it.token == e.getString(1) } ?: return@mapNotNull null
            LedgerEntry(e.getLong(0), fam, LedgerKind.of(e.getString(2)) ?: return@mapNotNull null)
        }
    } catch (e: Exception) { emptyList() }
}
