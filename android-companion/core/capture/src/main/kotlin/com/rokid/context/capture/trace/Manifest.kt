package com.rokid.context.capture.trace

import com.rokid.context.capture.plan.CanonicalJson
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.session.BudgetTier
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class TraceStatus { COMPLETE, PARTIAL }

/**
 * FROZEN (trace schema 1). Manifest-lite (S42 as amended by S73): one canonical JSON document (keys sorted, no whitespace, integers
 * only, at most [TraceBounds.MAX_MANIFEST_BYTES] bytes, depth at most [TraceBounds.MAX_MANIFEST_DEPTH]) shipped as one `TraceChunk` of kind
 * `"manifest"` before the chunks. Strict decode: every key is required, no unknown key, no duplicate key, and the text must equal its own
 * re-encoding.
 *
 * Deviation from the round 1 text, forced by the S73 b64 bound: with up to 4,096 chunks a per-chunk sha list cannot fit one 90,000 char chunk,
 * so the per-chunk sha travels in each `TraceChunk.sha256` and the manifest carries [traceSha256] = SHA-256 over the ASCII concatenation of
 * the chunk sha hex strings in `seq` order ([traceSha]). A chunk that does not match the one the manifest summarised therefore makes the
 * trace PARTIAL (see [TraceAssembler]).
 *
 * Epoch: [installId] and [deletionCounter] are the glasses' persisted deletion epoch at recording time; the phone refuses a trace whose
 * epoch is older than its own. [revision] starts at 1 and is bumped by the phone when it re-ingests the same trace id with different content;
 * [parentTraceId] names the trace a trimmed copy was cut from.
 *
 * Clock (I1, S42): at the trace start AND at its end the recorder samples, at the same instant, the glasses elapsed realtime and the glasses wall clock
 * ([startElapsedMs]/[startWallMs], [endElapsedMs]/[endWallMs]) and, when linked, the phone wall time from the latest phone message
 * ([startPhoneWallMs]/[endPhoneWallMs], null when unlinked, both or neither). [ClockMap] interpolates the glasses wall offset between the two samples and
 * REFUSES (null) when the offset moved by more than [ClockMap.MAX_WALL_JUMP_MS] (the wall clock was corrected mid-trace), instead of smearing it.
 * [phoneOffsetMs] (phone wall minus glasses wall) and [offsetUncertaintyMs] are still filled by the phone at intake (null on the glasses); the phone samples
 * let a later import (Task 19) derive an offset for the trace's own time instead of the intake-time one.
 * [bootId] is the decimal `Settings.Global.BOOT_COUNT` as a string (the same number as `MarkMessage.bootCount`, so the mark-to-trace join matches); a
 * reboot restarts `elapsedRealtime`, a trace never spans one (the recorder closes it with reason `reboot`).
 *
 * [revision] is 1-based: a Room `canvas_trace.revision` of 0 (the column default) maps to manifest revision 1 (`max(1, room)`).
 *
 * Why no per-chunk sha list (M1, deviation from S42's text; the spec and plan Task 18 text carry the same rationale): with up to 4,096 chunks the list cannot
 * fit one 90,000 char chunk. Integrity is equivalent, because [traceSha256] chains every chunk's raw sha in `seq` order, so any changed, missing or swapped
 * chunk breaks it. The only loss is naming WHICH chunk is bad, which does not matter because there is no per-seq resume: a bad trace is PARTIAL as a whole.
 * Task 18's "inventory complete" therefore means: every seq in `[0, chunkCount)` exactly once, and [TraceAssembler] reports COMPLETE.
 *
 * Manifest on the wire (I2): see [ManifestChunk] (one `TraceChunk` of kind `manifest`, seq 0, count 1, PLAIN base64 of the canonical text) and [TraceFiles]
 * (the on-disk layout Task 16 writes and Task 19 imports).
 */
data class Manifest(
    val traceId: String,
    val installId: String,
    val deletionCounter: Long,
    val revision: Int,
    val parentTraceId: String?,
    val bootId: String,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val startWallMs: Long,
    val endWallMs: Long,
    val startPhoneWallMs: Long?,
    val endPhoneWallMs: Long?,
    val phoneOffsetMs: Long?,
    val offsetUncertaintyMs: Long?,
    val chunkCount: Int,
    val eventCount: Long,
    val rawBytes: Long,
    val traceSha256: String,
    /** What the recorder itself knew: PARTIAL if it dropped events (disk cap) or could not close cleanly. */
    val recorded: TraceStatus,
    val closeReason: String,
    val snapshot: StartSnapshot,
) {
    init {
        require(TraceBounds.isTraceId(traceId)) { "traceId" }
        id(installId, "installId", allowEmpty = true)
        ms(deletionCounter, "deletionCounter")
        require(revision in 1..MAX_REVISION) { "revision" }
        parentTraceId?.let { require(TraceBounds.isTraceId(it)) { "parentTraceId" } }
        require(BOOT_ID.matches(bootId)) { "bootId is the decimal boot count" }
        require((startPhoneWallMs == null) == (endPhoneWallMs == null)) { "phone wall samples go together" }
        startPhoneWallMs?.let { ms(it, "startPhoneWallMs") }; endPhoneWallMs?.let { ms(it, "endPhoneWallMs") }
        ms(startElapsedMs, "startElapsedMs"); ms(endElapsedMs, "endElapsedMs"); ms(startWallMs, "startWallMs"); ms(endWallMs, "endWallMs")
        require(endElapsedMs >= startElapsedMs) { "end before start" }
        require((phoneOffsetMs == null) == (offsetUncertaintyMs == null)) { "phone offset and uncertainty go together" }
        phoneOffsetMs?.let { require(it in -TraceBounds.MAX_MS..TraceBounds.MAX_MS) { "phoneOffsetMs" } }
        offsetUncertaintyMs?.let { ms(it, "offsetUncertaintyMs") }
        require(chunkCount in 1..TraceBounds.MAX_CHUNKS) { "chunkCount" }
        require(eventCount in 1..TraceBounds.MAX_TRACE_EVENTS && eventCount >= chunkCount) { "eventCount" }
        require(rawBytes in 1..TraceBounds.MAX_TRACE_RAW_BYTES) { "rawBytes" }
        require(HEX64.matches(traceSha256)) { "traceSha256" }
        require(closeReason in CloseReason.ALL) { "closeReason" }
    }

    /** S66: false for a downsampled (25 Hz) trace. Never parity or publish evidence. */
    val exact: Boolean get() = snapshot.exact

    /** Decisions before this elapsed time are discarded by the simulator (S65). */
    val preRollEndMs: Long get() = startElapsedMs + TraceBounds.PRE_ROLL_MS

    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "traceSchema" to n(TRACE_SCHEMA.toLong()), "traceId" to JsonPrimitive(traceId), "installId" to JsonPrimitive(installId),
            "deletionCounter" to n(deletionCounter), "revision" to n(revision.toLong()),
            "parentTraceId" to (parentTraceId?.let { JsonPrimitive(it) } ?: JsonNull), "bootId" to JsonPrimitive(bootId),
            "startElapsedMs" to n(startElapsedMs), "endElapsedMs" to n(endElapsedMs), "startWallMs" to n(startWallMs), "endWallMs" to n(endWallMs),
            "startPhoneWallMs" to (startPhoneWallMs?.let { n(it) } ?: JsonNull), "endPhoneWallMs" to (endPhoneWallMs?.let { n(it) } ?: JsonNull),
            "phoneOffsetMs" to (phoneOffsetMs?.let { n(it) } ?: JsonNull), "offsetUncertaintyMs" to (offsetUncertaintyMs?.let { n(it) } ?: JsonNull),
            "chunkCount" to n(chunkCount.toLong()), "eventCount" to n(eventCount), "rawBytes" to n(rawBytes), "traceSha256" to JsonPrimitive(traceSha256),
            "recorded" to JsonPrimitive(recorded.name), "closeReason" to JsonPrimitive(closeReason), "snapshot" to snapshotJson(snapshot),
        ),
    )

    /** Canonical text ([CanonicalJson]: sorted keys, no whitespace). Byte-exact and frozen. */
    fun encode(): String = CanonicalJson.encode(toJson())

    companion object {
        const val MAX_REVISION = 1_000_000
        private val BOOT_ID = Regex("[0-9]{1,10}")

        /** The `bootId` for `Settings.Global.BOOT_COUNT` (the number in `MarkMessage.bootCount`). */
        fun bootIdOf(bootCount: Int): String { require(bootCount >= 0) { "bootCount" }; return bootCount.toString() }
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val INT = Regex("-?(0|[1-9][0-9]{0,15})")
        private fun n(v: Long) = JsonPrimitive(v)
        private fun bits(f: Float) = JsonPrimitive(f.toRawBits())
        private fun hex(v: Long) = JsonPrimitive(java.lang.Long.toHexString(v).padStart(16, '0'))

        private fun snapshotJson(s: StartSnapshot) = JsonObject(
            mapOf(
                "settingsHash" to JsonPrimitive(s.settingsHash),
                "ring" to JsonArray(s.ring.map { JsonArray(listOf(hex(it.d), hex(it.p))) }),
                "lastKeyframeAt" to (s.lastKeyframeAt?.let { n(it) } ?: JsonNull),
                "keptAt" to JsonArray(s.keptAt.map { n(it) }),
                "gateLastKeptAt" to (s.gateLastKeptAt?.let { n(it) } ?: JsonNull), "gateKeptAt" to JsonArray(s.gateKeptAt.map { n(it) }),
                "lastBriefAt" to (s.lastBriefAt?.let { n(it) } ?: JsonNull), "stillStep" to n(s.stillStep.toLong()),
                "lastCheckHash" to (s.lastCheckHash?.let { JsonArray(listOf(hex(it.d), hex(it.p))) } ?: JsonNull), "darkStreak" to n(s.darkStreak.toLong()),
                "bindBackoffUntil" to (s.bindBackoffUntil?.let { n(it) } ?: JsonNull), "coldStartGrace" to JsonPrimitive(s.coldStartGrace),
                "startups" to JsonArray(s.startups.map { n(it) }), "lastPhotoAttemptAtMs" to (s.lastPhotoAttemptAtMs?.let { n(it) } ?: JsonNull),
                "pose" to JsonObject(
                    mapOf(
                        "rx" to bits(s.pose.rx), "ry" to bits(s.pose.ry), "rz" to bits(s.pose.rz), "walked" to JsonPrimitive(s.pose.walked),
                        "qx" to bits(s.pose.qx), "qy" to bits(s.pose.qy), "qz" to bits(s.pose.qz), "quietSinceMs" to (s.pose.quietSinceMs?.let { n(it) } ?: JsonNull),
                    ),
                ),
                "planGen" to n(s.planGen), "planHash" to (s.planHash?.let { JsonPrimitive(it) } ?: JsonNull),
                "glance" to JsonObject(
                    mapOf(
                        "onsetRadS" to bits(s.glance.onsetRadS), "releaseRadS" to bits(s.glance.releaseRadS), "bridgeMs" to n(s.glance.bridgeMs),
                        "minBoutMs" to n(s.glance.minBoutMs), "minBoutRad" to bits(s.glance.minBoutRad), "settleMs" to n(s.glance.settleMs),
                        "maxDtMs" to n(s.glance.maxDtMs), "deadbandRadS" to bits(s.glance.deadbandRadS), "noveltyWindowMs" to n(s.glance.noveltyWindowMs),
                        "noveltyMinRad" to bits(s.glance.noveltyMinRad), "bigTurnRad" to bits(s.glance.bigTurnRad),
                        "capPerMin" to n(s.glance.capPerMin.toLong()), "restCapPerMin" to n(s.glance.restCapPerMin.toLong()),
                    ),
                ),
                "ctx" to JsonPrimitive(s.ctx.name), "resting" to JsonPrimitive(s.resting), "tier" to JsonPrimitive(s.tier.name),
                "sampleEvery" to n(s.sampleEvery.toLong()), "exact" to JsonPrimitive(s.exact),
            ),
        )

        /** SHA-256 (lower-case hex) over the ASCII concatenation of the chunk sha hex strings in `seq` order. */
        fun traceSha(chunkShas: List<String>): String =
            MessageDigest.getInstance("SHA-256").digest(chunkShas.joinToString("").toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }

        /** Strict decode; the failure is always a [TraceFormatException] with code `bad_manifest`. */
        fun decodeStrict(s: String): Result<Manifest> = try {
            Result.success(decode(s))
        } catch (e: Exception) {
            Result.failure(TraceFormatException("bad_manifest"))
        }

        private fun decode(s: String): Manifest {
            require(s.length <= TraceBounds.MAX_MANIFEST_BYTES) { "too long" }
            require(depthOk(s)) { "too deep" }
            require(!PlanJson.hasDuplicateKeys(s)) { "duplicate key" }
            val top = Obj(Json.parseToJsonElement(s))
            require(top.int("traceSchema") == TRACE_SCHEMA.toLong()) { "traceSchema" }
            val snap = top.obj("snapshot")
            val m = Manifest(
                traceId = top.str("traceId"), installId = top.str("installId"), deletionCounter = top.int("deletionCounter"), revision = top.int("revision").toInt(),
                parentTraceId = top.strOrNull("parentTraceId"), bootId = top.str("bootId"), startElapsedMs = top.int("startElapsedMs"),
                endElapsedMs = top.int("endElapsedMs"), startWallMs = top.int("startWallMs"), endWallMs = top.int("endWallMs"),
                startPhoneWallMs = top.intOrNull("startPhoneWallMs"), endPhoneWallMs = top.intOrNull("endPhoneWallMs"), phoneOffsetMs = top.intOrNull("phoneOffsetMs"), offsetUncertaintyMs = top.intOrNull("offsetUncertaintyMs"),
                chunkCount = top.int("chunkCount").toInt(), eventCount = top.int("eventCount"), rawBytes = top.int("rawBytes"),
                traceSha256 = top.str("traceSha256"), recorded = top.enum("recorded", TraceStatus.values()), closeReason = top.str("closeReason"),
                snapshot = decodeSnapshot(snap),
            )
            top.done()
            require(m.encode() == s) { "not canonical" }
            return m
        }

        private fun decodeSnapshot(o: Obj): StartSnapshot {
            val g = o.obj("glance")
            val glance = GlanceParams(
                onsetRadS = g.float("onsetRadS"), releaseRadS = g.float("releaseRadS"), bridgeMs = g.int("bridgeMs"), minBoutMs = g.int("minBoutMs"),
                minBoutRad = g.float("minBoutRad"), settleMs = g.int("settleMs"), maxDtMs = g.int("maxDtMs"), deadbandRadS = g.float("deadbandRadS"),
                noveltyWindowMs = g.int("noveltyWindowMs"), noveltyMinRad = g.float("noveltyMinRad"), bigTurnRad = g.float("bigTurnRad"),
                capPerMin = g.int("capPerMin").toInt(), restCapPerMin = g.int("restCapPerMin").toInt(),
            )
            g.done()
            val s = StartSnapshot(
                settingsHash = o.str("settingsHash"),
                ring = o.arr("ring").map { p -> (p as? JsonArray)?.takeIf { it.size == 2 }?.let { RingHash(hexOf(it[0]), hexOf(it[1])) } ?: throw IllegalArgumentException("ring") },
                lastKeyframeAt = o.intOrNull("lastKeyframeAt"), keptAt = o.arr("keptAt").map { intOf(it) },
                gateLastKeptAt = o.intOrNull("gateLastKeptAt"), gateKeptAt = o.arr("gateKeptAt").map { intOf(it) },
                lastBriefAt = o.intOrNull("lastBriefAt"), stillStep = o.int("stillStep").toInt(),
                lastCheckHash = o.take("lastCheckHash").let { if (it is JsonNull) null else (it as? JsonArray)?.takeIf { a -> a.size == 2 }?.let { a -> RingHash(hexOf(a[0]), hexOf(a[1])) } ?: throw IllegalArgumentException("lastCheckHash") },
                darkStreak = o.int("darkStreak").toInt(), bindBackoffUntil = o.intOrNull("bindBackoffUntil"), coldStartGrace = o.bool("coldStartGrace"),
                startups = o.arr("startups").map { intOf(it) }, pose = decodePose(o.obj("pose")), lastPhotoAttemptAtMs = o.intOrNull("lastPhotoAttemptAtMs"),
                planGen = o.int("planGen"),
                planHash = o.strOrNull("planHash"), glance = glance, ctx = o.enum("ctx", ContextState.values()), resting = o.bool("resting"),
                tier = o.enum("tier", BudgetTier.values()), sampleEvery = o.int("sampleEvery").toInt(), exact = o.bool("exact"),
            )
            o.done()
            return s
        }

        private fun decodePose(p: Obj): PoseSnapshot {
            val r = PoseSnapshot(p.float("rx"), p.float("ry"), p.float("rz"), p.bool("walked"), p.float("qx"), p.float("qy"), p.float("qz"), p.intOrNull("quietSinceMs"))
            p.done()
            return r
        }

        private fun intOf(e: JsonElement): Long {
            require(e is JsonPrimitive && e !is JsonNull && !e.isString && INT.matches(e.content)) { "not an integer" }
            return e.content.toLong()
        }
        private fun hexOf(e: JsonElement): Long {
            require(e is JsonPrimitive && e.isString && Regex("[0-9a-f]{16}").matches(e.content)) { "not hex16" }
            return java.lang.Long.parseUnsignedLong(e.content, 16)
        }

        /** Nesting depth of '[' and '{' outside strings, without recursion. */
        private fun depthOk(s: String): Boolean {
            var d = 0; var inStr = false; var esc = false
            for (c in s) {
                if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
                when (c) {
                    '"' -> inStr = true
                    '{', '[' -> { d++; if (d > TraceBounds.MAX_MANIFEST_DEPTH) return false }
                    '}', ']' -> d--
                }
            }
            return true
        }

        private class Obj(e: JsonElement) {
            private val o: JsonObject = e as? JsonObject ?: throw IllegalArgumentException("not an object")
            private val used = HashSet<String>()
            fun take(k: String): JsonElement { used += k; return o[k] ?: throw IllegalArgumentException("$k missing") }
            fun str(k: String): String = take(k).let { require(it is JsonPrimitive && it !is JsonNull && it.isString) { "$k not a string" }; (it as JsonPrimitive).content }
            fun strOrNull(k: String): String? = take(k).let { if (it is JsonNull) null else { require(it is JsonPrimitive && it.isString) { "$k not a string" }; (it as JsonPrimitive).content } }
            fun int(k: String): Long = intOf(take(k))
            fun intOrNull(k: String): Long? = take(k).let { if (it is JsonNull) null else intOf(it) }
            fun float(k: String): Float { val v = int(k); require(v in Int.MIN_VALUE..Int.MAX_VALUE) { "$k bits" }; return Float.fromBits(v.toInt()) }
            fun bool(k: String): Boolean = take(k).let { require(it is JsonPrimitive && it !is JsonNull && !it.isString && (it.content == "true" || it.content == "false")) { "$k not a boolean" }; (it as JsonPrimitive).content == "true" }
            fun obj(k: String) = Obj(take(k))
            fun arr(k: String): JsonArray = take(k) as? JsonArray ?: throw IllegalArgumentException("$k not an array")
            fun <T : Enum<T>> enum(k: String, values: Array<T>): T = str(k).let { s -> values.firstOrNull { it.name == s } ?: throw IllegalArgumentException("$k unknown") }
            fun done() = require(o.keys == used) { "unknown key" }
        }
    }
}

/** The outcome of checking a received trace against its manifest. [reason] is `ok` or the first failed check (a stable token). */
class Verification(val status: TraceStatus, val reason: String)

/**
 * Streams chunks against a [Manifest] without keeping their events (each chunk is inspected once, then dropped). COMPLETE needs: every `seq` in
 * `[0, chunkCount)` present exactly once with a single sha; every chunk from this trace with `count == chunkCount`; time non-decreasing across chunks and
 * inside `[startElapsedMs, endElapsedMs]`; the event and byte totals equal to the manifest; the [Manifest.traceSha] equal to [Manifest.traceSha256]; and
 * the glasses having recorded it COMPLETE. Then the recorder obligations (M3, I4): the initial `cx rs pf gp pl` events at `startElapsedMs`; no `pl` or
 * `gp` after the start and one quickLookMs for the whole trace (a plan or settings change closes the trace); one `pr` at `start + 10 s` (required when the
 * trace is at least that long); exactly one `cl`, the last event, whose reason equals [Manifest.closeReason].
 * Anything else is PARTIAL: viewable and replayable, never publish evidence. Reasons, in the order they are checked: foreign_chunk, count_mismatch,
 * seq_out_of_range, conflict, missing, not_monotonic, range, totals, trace_sha, recorded_partial, start_state, config_change, pre_roll, close.
 */
class TraceAssembler(private val manifest: Manifest) {
    private class Meta(val sha: String, val events: Int, val rawBytes: Int, val first: Long, val last: Long)
    private val seen = HashMap<Int, Meta>()
    private var foreign = false
    private var countMismatch = false
    private var outOfRange = false
    private var conflict = false
    private val initial = HashSet<String>()
    private var configChange = false
    private val quickLooks = HashSet<Long>()
    private val preRolls = ArrayList<Long>()
    private var closes = 0
    private var lastIsClose = false
    private var lastCloseReason: String? = null

    fun accept(c: DecodedChunk) {
        if (c.traceId != manifest.traceId) { foreign = true; return }
        if (c.count != null && c.count != manifest.chunkCount) { countMismatch = true; return }
        if (c.seq !in 0 until manifest.chunkCount) { outOfRange = true; return }
        val prev = seen[c.seq]
        if (prev != null) { if (prev.sha != c.sha256) conflict = true; return }
        seen[c.seq] = Meta(c.sha256, c.events.size, c.rawBytes, c.firstTMs, c.lastTMs)
        for (e in c.events) when (e) {
            is TraceEvent.Ctx, is TraceEvent.Resting, is TraceEvent.Profile -> {
                if (c.seq == 0 && e.tMs == manifest.startElapsedMs) initial.add(EventCodec.tag(e))
                if (e is TraceEvent.Profile) quickLooks.add(e.quickLookMs)
            }
            is TraceEvent.GateParams, is TraceEvent.Plan -> {
                if (e.tMs == manifest.startElapsedMs) { if (c.seq == 0) initial.add(EventCodec.tag(e)) } else configChange = true
            }
            is TraceEvent.PreRollEnd -> preRolls.add(e.tMs)
            is TraceEvent.Close -> closes++
            else -> Unit
        }
        if (c.seq == manifest.chunkCount - 1) (c.events.last() as? TraceEvent.Close).let { lastIsClose = it != null; lastCloseReason = it?.reason }
    }

    fun verify(): Verification {
        fun partial(r: String) = Verification(TraceStatus.PARTIAL, r)
        if (foreign) return partial("foreign_chunk")
        if (countMismatch) return partial("count_mismatch")
        if (outOfRange) return partial("seq_out_of_range")
        if (conflict) return partial("conflict")
        if ((0 until manifest.chunkCount).any { it !in seen }) return partial("missing")
        var prevLast = -1L; var events = 0L; var raw = 0L
        for (i in 0 until manifest.chunkCount) {
            val m = seen.getValue(i)
            if (m.first < prevLast) return partial("not_monotonic")
            prevLast = m.last; events += m.events; raw += m.rawBytes
        }
        if (seen.getValue(0).first < manifest.startElapsedMs || prevLast > manifest.endElapsedMs) return partial("range")
        if (events != manifest.eventCount || raw != manifest.rawBytes) return partial("totals")
        if (Manifest.traceSha((0 until manifest.chunkCount).map { seen.getValue(it).sha }) != manifest.traceSha256) return partial("trace_sha")
        if (manifest.recorded != TraceStatus.COMPLETE) return partial("recorded_partial")
        if (!initial.containsAll(INITIAL_TAGS)) return partial("start_state")
        if (configChange || quickLooks.size > 1) return partial("config_change")
        val longEnough = manifest.endElapsedMs >= manifest.preRollEndMs
        if (preRolls.size > 1 || (preRolls.size == 1 && preRolls[0] != manifest.preRollEndMs) || (preRolls.isEmpty() && longEnough)) return partial("pre_roll")
        if (closes != 1 || !lastIsClose || lastCloseReason != manifest.closeReason) return partial("close")
        return Verification(TraceStatus.COMPLETE, "ok")
    }

    companion object { val INITIAL_TAGS = setOf("cx", "rs", "pf", "gp", "pl") }
}
