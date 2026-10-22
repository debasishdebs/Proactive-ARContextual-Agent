package com.rokid.context.capture.trace

import com.rokid.context.core.model.TraceChunk
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.json.Json

/**
 * FROZEN (trace schema 1, I2). The manifest as a `TraceChunk`: `kind = "manifest"`, `seq = 0`, `count = 1`, `traceId`, `installId` and `deletionCounter`
 * equal to the manifest's, `b64` = standard padded base64 of the canonical manifest text ([Manifest.encode], UTF-8) with NO gzip (it is at most 8 KiB, so
 * at most 10,924 b64 characters), `sha256` = lower-case hex SHA-256 of those raw UTF-8 bytes. Checksum coverage: the sha covers exactly the manifest text,
 * and the manifest's own `traceSha256` covers the data chunks, so the pair binds the whole trace. The manifest is sent before the data chunks.
 */
object ManifestChunk {
    const val KIND = "manifest"
    private val SHA = Regex("[0-9a-f]{64}")
    private const val MAX_B64 = (TraceBounds.MAX_MANIFEST_BYTES + 2) / 3 * 4

    fun encode(m: Manifest, atMs: Long = 0, sessionStartMs: Long = 0): TraceChunk {
        val raw = m.encode().toByteArray(Charsets.UTF_8)
        return TraceChunk(
            traceId = m.traceId, kind = KIND, seq = 0, count = 1, installId = m.installId, deletionCounter = m.deletionCounter,
            sha256 = ChunkCodec.sha256Hex(raw), b64 = Base64.getEncoder().encodeToString(raw), atMs = atMs, sessionStartMs = sessionStartMs,
        )
    }

    /** Failure codes (a [TraceFormatException]): kind, seq, chunk_count, sha_invalid, b64_too_long, b64_invalid, sha_mismatch, bad_payload, bad_manifest, header_mismatch. */
    fun decode(c: TraceChunk): Result<Manifest> = try {
        if (c.kind != KIND) throw TraceFormatException("kind")
        if (c.seq != 0) throw TraceFormatException("seq")
        if (c.count != 1) throw TraceFormatException("chunk_count")
        if (!SHA.matches(c.sha256)) throw TraceFormatException("sha_invalid")
        if (c.b64.length > MAX_B64) throw TraceFormatException("b64_too_long")
        if (c.b64.length % 4 != 0) throw TraceFormatException("b64_invalid")
        val raw = try { Base64.getDecoder().decode(c.b64) } catch (e: IllegalArgumentException) { throw TraceFormatException("b64_invalid") }
        if (raw.size > TraceBounds.MAX_MANIFEST_BYTES) throw TraceFormatException("bad_manifest")
        if (ChunkCodec.sha256Hex(raw) != c.sha256) throw TraceFormatException("sha_mismatch")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) { throw TraceFormatException("bad_payload") }
        val m = Manifest.decodeStrict(text).getOrThrow()
        if (m.traceId != c.traceId || m.installId != c.installId || m.deletionCounter != c.deletionCounter) throw TraceFormatException("header_mismatch")
        Result.success(m)
    } catch (e: TraceFormatException) { Result.failure(e) } catch (e: Exception) { Result.failure(TraceFormatException("bad_payload")) }
}

/**
 * FROZEN (trace schema 1, I2). The on-disk layout of one trace: Task 16 (recorder, glasses) writes it, Task 19 (`TraceImport` after `adb pull`) reads it.
 *
 * - One directory per trace, named by the trace id ([dirName]); the recorder root is its own business (`files/traces/`).
 * - `manifest.json` ([MANIFEST_FILE]): the [ManifestChunk] `TraceChunk` as one line of JSON ([encodeFile]).
 * - `chunk-0000.json` ... `chunk-NNNN.json` ([chunkFile], 4 digits, `seq` order): each is the data `TraceChunk` (kind `chunk`, [ChunkCodec]) as one line of
 *   JSON, i.e. exactly what travels on the link, so a pulled import and a live transfer share one decoder and one checksum path.
 * - The set of file names must be exactly the manifest plus `chunk-0000 .. chunk-(chunkCount-1)`; anything else makes the import fail.
 * - A file is the compact JSON of a `TraceChunk` with every field written (defaults included, declaration order) and no trailing newline; a decode accepts
 *   only text that re-encodes to itself, like the other frozen codecs. At most [MAX_FILE_CHARS] characters.
 * - The chunk epoch (`installId`, `deletionCounter`) of every file must equal the manifest's.
 * - A trace that was never closed cleanly has no `manifest.json` and is not importable (the recorder writes the manifest last, after the close).
 */
object TraceFiles {
    const val MANIFEST_FILE = "manifest.json"
    const val MAX_FILE_CHARS = TraceBounds.MAX_B64_CHARS + 1_024
    private val JSON = Json { encodeDefaults = true }
    private val CHUNK_FILE = Regex("chunk-([0-9]{4})\\.json")

    fun dirName(traceId: String): String { require(TraceBounds.isTraceId(traceId)) { "traceId" }; return traceId }
    fun chunkFile(seq: Int): String { require(seq in 0 until TraceBounds.MAX_CHUNKS) { "seq" }; return String.format(java.util.Locale.ROOT, "chunk-%04d.json", seq) }

    fun encodeFile(c: TraceChunk): String = JSON.encodeToString(TraceChunk.serializer(), c)

    fun decodeFile(text: String): Result<TraceChunk> = try {
        if (text.length > MAX_FILE_CHARS) throw TraceFormatException("file_too_long")
        val c = JSON.decodeFromString(TraceChunk.serializer(), text)
        if (encodeFile(c) != text) throw TraceFormatException("not_canonical")
        Result.success(c)
    } catch (e: TraceFormatException) { Result.failure(e) } catch (e: Exception) { Result.failure(TraceFormatException("bad_file")) }

    /**
     * Imports one trace directory without keeping its events: [names] lists the files of the directory, [read] returns a file's text. Returns the manifest
     * and the [Verification] (PARTIAL with a reason, never a throw, for a trace that is incomplete or inconsistent). A structural failure (wrong file set,
     * undecodable file, epoch or seq mismatch) is a failure with a [TraceFormatException] code: bad_file_set, bad_file, file_seq, epoch_mismatch, or the codec's own.
     */
    fun verify(names: Set<String>, read: (String) -> String): Result<Pair<Manifest, Verification>> = try {
        if (MANIFEST_FILE !in names) throw TraceFormatException("bad_file_set")
        val manifest = ManifestChunk.decode(decodeFile(read(MANIFEST_FILE)).getOrThrow()).getOrThrow()
        val expected = (0 until manifest.chunkCount).map { chunkFile(it) }.toSet() + MANIFEST_FILE
        if (names != expected) throw TraceFormatException("bad_file_set")
        val asm = TraceAssembler(manifest)
        for (i in 0 until manifest.chunkCount) {
            val name = chunkFile(i)
            val tc = decodeFile(read(name)).getOrThrow()
            if (CHUNK_FILE.matchEntire(name)!!.groupValues[1].toInt() != tc.seq) throw TraceFormatException("file_seq")
            if (tc.installId != manifest.installId || tc.deletionCounter != manifest.deletionCounter) throw TraceFormatException("epoch_mismatch")
            asm.accept(ChunkCodec.decode(tc).getOrThrow())
        }
        Result.success(manifest to asm.verify())
    } catch (e: TraceFormatException) { Result.failure(e) } catch (e: Exception) { Result.failure(TraceFormatException("bad_file")) }
}

/**
 * Replay equality rule for Task 20 (controller ruling: replay is APPROXIMATE, not bitwise-exact).
 *
 * - The gate is replayed from fully recorded inputs: `sc.nowMs`, `sc.cap/hard`, `fl.nowMs` (the flush decision time), `pf` context cap, the snapshot. Every `gt`
 *   (seq, outcome, reason, `encoded`, `keptAtMs`) must then be EQUAL ([gatesMatch]).
 * - Camera opens depend on the session tick clock (not recorded): a replayed open matches a recorded one when the cause is equal and the times differ by at
 *   at most the slacks of [opensMatch]: the recorded open may be up to [LATE_SLACK_MS] later than the replayed one, or [EARLY_SLACK_MS] earlier.
 * - Not recorded, by decision: consumed `captureNow()` requests, the lifetime of a request waiting behind a bind back-off, and when the asynchronous
 *   onset/dwell collectors ran or which transient StateFlow states a tick observed. A window of the trace that involves any of them is INEXACT
 *   ([Exactness.classify]): the replay result model carries an `exact` flag (it is NOT on the wire), inexact windows are excluded from pass/fail and COUNTED
 *   ([ReplayTally]).
 */
object ReplayRule {
    /**
     * Asymmetric (Task 23b, from a real desk trace): the live tick loop is only ever LATE against the replayed one (wake-up latency; recorded opens were +135..+259 ms
     * after the simulated tick, which lands exactly on dueAt minus the lead), never ahead of it. So a recorded open may follow the replayed one by up to [LATE_SLACK_MS],
     * but precede it by at most [EARLY_SLACK_MS] (one replay tick).
     */
    const val EARLY_SLACK_MS = 250L
    const val LATE_SLACK_MS = 500L
    fun opensMatch(recordedAtMs: Long, recordedCause: EpisodeCause, replayedAtMs: Long, replayedCause: EpisodeCause): Boolean =
        recordedCause == replayedCause && (recordedAtMs - replayedAtMs) in -EARLY_SLACK_MS..LATE_SLACK_MS
    fun gatesMatch(a: TraceEvent.Gate, b: TraceEvent.Gate): Boolean =
        a.seq == b.seq && a.outcome == b.outcome && a.reason == b.reason && a.encoded == b.encoded && a.keptAtMs == b.keptAtMs
}

/** Why a replay window cannot be compared exactly. */
enum class Inexact { CAPTURE_NOW, BIND_FAILURE, ASYNC_CALLBACK }

object Exactness {
    /**
     * The reasons a window (its events, and the snapshot when the window starts the trace) is inexact: a `captureNow` request (an `ep` open with cause `now`, or an
     * `sc` with `force`), a bind failure or back-off (an `ep` close with outcome busy, bind, cold or open_failed, or snapshot `bindBackoffUntil`/`bindFailures`), or an
     * episode that depends on an async collector callback (an `ep` open with cause `dwell` or `onset`). Empty = exact.
     */
    fun classify(events: List<TraceEvent>, snapshot: StartSnapshot? = null): Set<Inexact> {
        val r = LinkedHashSet<Inexact>()
        if (snapshot != null && (snapshot.bindFailures > 0 || snapshot.bindBackoffUntil != null)) r += Inexact.BIND_FAILURE
        for (e in events) when (e) {
            is TraceEvent.Episode -> {
                if (e.open && e.cause == EpisodeCause.NOW) r += Inexact.CAPTURE_NOW
                if (e.open && (e.cause == EpisodeCause.DWELL || e.cause == EpisodeCause.ONSET)) r += Inexact.ASYNC_CALLBACK
                if (!e.open && e.outcome in setOf(EpisodeOutcome.BUSY, EpisodeOutcome.BIND, EpisodeOutcome.COLD, EpisodeOutcome.OPEN_FAILED)) r += Inexact.BIND_FAILURE
            }
            is TraceEvent.Score -> if (e.force) r += Inexact.CAPTURE_NOW
            else -> Unit
        }
        return r
    }
}

/** The replay verdict counts: windows that passed, failed, and were inexact (excluded from pass/fail). */
class ReplayTally {
    var passed = 0; private set
    var failed = 0; private set
    var inexact = 0; private set
    fun record(reasons: Set<Inexact>, matches: Boolean) { if (reasons.isNotEmpty()) inexact++ else if (matches) passed++ else failed++ }
}

/**
 * Every input the glasses scheduler reads (`CameraPolicy.decide`, the `SamplingSession` loop and `KeyframeGate.offerScored`) and where the trace
 * carries it (C2). A value is `tag.field` (an event), `snapshot.key` (the manifest snapshot) or `derived:...` (recomputed by replay from the others).
 * Task 20's replay may rely on exactly these; a new scheduler input means a schema bump.
 */
object SchedulerInputs {
    val ALL: Map<String, String> = linkedMapOf(
        "decide.state" to "derived:g,a,cx(classifier on sMs)", "decide.decelerating" to "derived:g,a(classifier)", "decide.msSinceMotionEnd" to "derived:g,a(classifier)",
        "decide.msSinceKeyframe" to "gt.outcome", "decide.quickLookMs" to "pf.quickLookMs", "decide.lowPower" to "pf.lowPower", "decide.heatSlow" to "pf.heatSlow",
        "decide.nowMs" to "derived:tMs of tick and delivery order", "decide.lastBriefCheckAtMs" to "ep.outcome", "decide.quiet" to "dk.streak",
        "decide.profile" to "pf.wake", "decide.stillStep" to "bo.stillStep",
        "session.lastBriefAt" to "snapshot.lastBriefAt", "session.stillStep" to "snapshot.stillStep", "session.lastCheckHash" to "snapshot.lastCheckHash",
        "session.darkStreak" to "snapshot.darkStreak", "session.bindBackoffUntil" to "snapshot.bindBackoffUntil", "session.coldStartGrace" to "snapshot.coldStartGrace",
        "session.startups" to "snapshot.startups", "session.openedAt" to "ep.openedAtMs", "session.firstFrameAt" to "ep.firstFrameAtMs",
        "session.episodeOutcome" to "ep.outcome", "session.lastKeyframeAt" to "snapshot.lastKeyframeAt", "session.keptAt" to "snapshot.keptAt",
        "session.pose" to "snapshot.pose", "session.lastPhotoAttemptAtMs" to "snapshot.lastPhotoAttemptAtMs", "session.cause" to "ep.cause",
        "gate.flushNow" to "fl.nowMs", "session.keptAtMs" to "gt.keptAtMs", "session.bindFailures" to "snapshot.bindFailures", "classifier.prevGyroMs" to "snapshot.prevGyroMs", "gate.cap" to "sc.cap", "gate.hard" to "sc.hard", "gate.nowMs" to "sc.nowMs", "dwell.ctxCap" to "pf.ctxCap", "dwell.hard" to "pf.hard", "session.keepEncoded" to "gt.encoded", "session.captureNow" to "ep.cause", "gate.force" to "sc.force", "gate.dwellArmed" to "sc.dwellArmed", "gate.motion" to "sc.motion",
        "gate.lastKeptAt" to "snapshot.gateLastKeptAt", "gate.keptTimes" to "snapshot.gateKeptAt", "gate.ring" to "snapshot.ring", "gate.params" to "gp.hamming",
        "gate.scoreHashes" to "sc.d",
    )
}
