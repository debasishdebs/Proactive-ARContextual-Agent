package com.rokid.context.canvas.window

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Canvas v2.1 P1 (design section 1.1 "the join" and 1.8): joins the glasses lane (the KEEP events of a trace) to the phone lane (stored frames) and builds the E2E record. PURE: no database, no clock.
 *
 * Rulings applied (safest defaults, see the decisions file): the per-second probe series are NOT built (R4 is open: counts only); the record holds NO frame id (a frame id would outlive a frame Forget inside a
 * `canvas_replay` row), only `t` offsets and fixed status words, so the trace tombstone is the only lifecycle it needs. The link from a KEEP to its frame is recomputed from the phone run when the page is opened.
 */
object E2eJoin {
    const val TOLERANCE_MS = 1_500L
    const val MAX_LISTED = 600
    const val RECORD_CAP_BYTES = 256 * 1024
    const val CLASS = "E2E"

    data class Keep(val keptAtMs: Long)
    data class Stored(val frameId: String, val capturedAtMs: Long)

    enum class Status(val token: String) { MATCHED("matched"), NOT_STORED("not_stored") }
    data class KeepMatch(val keptAtMs: Long, val status: Status, /** In memory only; never written to the record. */ val frameId: String?)

    data class Result(val keeps: List<KeepMatch>, val matched: Int, val notStored: Int, val unusedFrames: Int, val neverCaptured: Int)

    /**
     * Greedy one-to-one match in time order: each KEEP takes the nearest unused stored frame within [toleranceMs] (ties go to the earlier frame). [counterfactualExtraKeeps] is the number of keeps the draft
     * plan would add that the recorded plan did not make: they have no photo, and are only counted ("never captured"), never matched.
     */
    fun join(keeps: List<Keep>, frames: List<Stored>, counterfactualExtraKeeps: Int = 0, toleranceMs: Long = TOLERANCE_MS): Result {
        val fs = frames.sortedWith(compareBy({ it.capturedAtMs }, { it.frameId }))
        val used = BooleanArray(fs.size)
        val out = keeps.sortedBy { it.keptAtMs }.map { k ->
            var best = -1; var bestD = Long.MAX_VALUE
            for (i in fs.indices) {
                if (used[i]) continue
                val d = kotlin.math.abs(fs[i].capturedAtMs - k.keptAtMs)
                if (d <= toleranceMs && d < bestD) { best = i; bestD = d }
            }
            if (best >= 0) { used[best] = true; KeepMatch(k.keptAtMs, Status.MATCHED, fs[best].frameId) } else KeepMatch(k.keptAtMs, Status.NOT_STORED, null)
        }
        val m = out.count { it.status == Status.MATCHED }
        return Result(out, m, out.size - m, used.count { !it }, counterfactualExtraKeeps.coerceAtLeast(0))
    }

    /** The plain sentence of the counterfactual honesty rule. Null when there is nothing to say. */
    fun counterfactualNote(r: Result): String? =
        if (r.neverCaptured > 0) "the draft would also keep ${r.neverCaptured} frames that were never captured; their phone-lane output cannot be shown" else null

    /** What is known about the run that goes into `paramsJson`. Labels and counts only. */
    data class Params(val sourceKind: String, val fromMs: Long, val toMs: Long, val seed: String, val pickMode: String, val runId: String?, val replayIds: List<String>)

    class Record(val replayClass: String, val paramsJson: String, val resultJson: String, val sourcesJson: String, val fromMs: Long, val toMs: Long)

    /** Builds the one `canvas_replay` row of a Test run: class [CLASS], counts plus a bounded list of KEEP offsets with a status word each (no frame id). Throws when the capped result would still exceed [RECORD_CAP_BYTES]. */
    fun record(p: Params, r: Result, traceIds: List<String>, gateSkipByReason: Map<String, Int>): Record {
        val listed = r.keeps.take(MAX_LISTED)
        val params = buildJsonObject {
            put("source", p.sourceKind); put("fromMs", p.fromMs); put("toMs", p.toMs); put("seed", p.seed); put("pick", p.pickMode)
            p.runId?.let { put("runId", it) }
            put("replayIds", JsonArray(p.replayIds.map { JsonPrimitive(it) }))
        }
        val result = buildJsonObject {
            put("matched", r.matched); put("notStored", r.notStored); put("unusedFrames", r.unusedFrames); put("neverCaptured", r.neverCaptured)
            put("toleranceMs", TOLERANCE_MS)
            put("keeps", JsonArray(listed.map { buildJsonObject { put("t", it.keptAtMs - p.fromMs); put("s", it.status.token) } }))
            put("keepsTruncated", r.keeps.size > MAX_LISTED)
            put("skips", JsonObject(gateSkipByReason.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            counterfactualNote(r)?.let { put("note", it) }
        }.toString()
        require(result.toByteArray(Charsets.UTF_8).size <= RECORD_CAP_BYTES) { "e2e_record_too_big" }
        return Record(CLASS, params.toString(), result, JsonArray(traceIds.sorted().map { JsonPrimitive(it) }).toString(), p.fromMs, p.toMs)
    }
}
