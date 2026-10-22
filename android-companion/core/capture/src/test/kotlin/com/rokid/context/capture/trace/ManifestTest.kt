package com.rokid.context.capture.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestTest {
    private fun fail(r: Result<*>): String = (r.exceptionOrNull() as? TraceFormatException)?.code ?: "NOT_A_TRACE_FORMAT_FAILURE:${r.exceptionOrNull()}:${r.getOrNull()}"
    private val id = TraceFixtures.ID

    @Test fun goldenManifestIsByteExact() {
        assertEquals(TraceFixtures.goldenManifest, TraceFixtures.manifest().encode())
        assertEquals(TraceFixtures.manifest(), Manifest.decodeStrict(TraceFixtures.goldenManifest).getOrThrow())
    }

    @Test fun traceShaIsTheDigestOfTheChunkShaList() {
        assertEquals(TraceFixtures.GOLDEN_TRACE_SHA, Manifest.traceSha(listOf(TraceFixtures.GOLDEN_SHA)))
        assertTrue(Manifest.traceSha(listOf("a".repeat(64), "b".repeat(64))) != Manifest.traceSha(listOf("b".repeat(64), "a".repeat(64))))
    }

    @Test fun strictDecode() {
        val g = TraceFixtures.goldenManifest
        val bad = mapOf(
            "unknown key" to g.replace("{\"bootId\"", "{\"aaa\":1,\"bootId\""),
            "missing key" to g.replace("\"closeReason\":\"stop\",", ""),
            "duplicate key" to g.replace("\"revision\":1,", "\"revision\":1,\"revision\":2,"),
            "whitespace" to g.replace("\"revision\":1", "\"revision\": 1"),
            "unsorted" to g.replace("\"bootId\":\"42\",\"chunkCount\":1", "\"chunkCount\":1,\"bootId\":\"42\""),
            "float" to g.replace("\"revision\":1", "\"revision\":1.0"),
            "future schema" to g.replace("\"traceSchema\":1", "\"traceSchema\":2"),
            "nan" to g.replace("\"revision\":1", "\"revision\":NaN"),
            "bad enum" to g.replace("\"COMPLETE\"", "\"DONE\""),
            "snapshot unknown key" to g.replace("\"ctx\":\"REST\"", "\"ctx\":\"REST\",\"zz\":1"),
            "exact mismatch" to g.replace("\"sampleEvery\":1", "\"sampleEvery\":2"),
            "negative offset id" to g.replace("\"deletionCounter\":3", "\"deletionCounter\":-3"),
            "not an object" to "[1]", "empty" to "", "trailing" to "$g ", "trailing json" to "$g{}",
            "bad trace id" to g.replace("trace-0001-golden", "BAD"),
            "chunks over bound" to g.replace("\"chunkCount\":1", "\"chunkCount\":4097"),
            "end before start" to g.replace("\"endElapsedMs\":12000", "\"endElapsedMs\":999"),
        )
        for ((n, s) in bad) assertEquals("bad_manifest", fail(Manifest.decodeStrict(s)), n)
    }

    @Test fun depthAndSizeBounded() {
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        assertEquals("bad_manifest", fail(Manifest.decodeStrict(deep)))
        val nested = TraceFixtures.goldenManifest.replace("\"keptAt\":[100,200]", "\"keptAt\":[[[[[[[[1]]]]]]]]")
        assertEquals("bad_manifest", fail(Manifest.decodeStrict(nested)))
        assertEquals("bad_manifest", fail(Manifest.decodeStrict(TraceFixtures.goldenManifest + " ".repeat(TraceBounds.MAX_MANIFEST_BYTES))))
    }

    @Test fun downsampledFlaggedNotExact() {
        val down = TraceFixtures.snapshot.copy(sampleEvery = 2, exact = false)
        val m = TraceFixtures.manifest(snap = down)
        assertFalse(m.exact)
        assertTrue(TraceFixtures.manifest().exact)
        assertFalse(Manifest.decodeStrict(m.encode()).getOrThrow().exact)
        // 25 Hz recording can never claim to be exact, and an exact claim needs full rate
        assertFailsWith<IllegalArgumentException> { TraceFixtures.snapshot.copy(sampleEvery = 2, exact = true) }
        assertFailsWith<IllegalArgumentException> { TraceFixtures.snapshot.copy(sampleEvery = 1, exact = false) }
        assertFailsWith<IllegalArgumentException> { TraceFixtures.snapshot.copy(sampleEvery = 0, exact = false) }
    }

    @Test fun epochAndRevisionAreCarried() {
        val m = TraceFixtures.manifest().copy(installId = "install-zzz", deletionCounter = 9, revision = 4, parentTraceId = "parent-trace-01")
        val back = Manifest.decodeStrict(m.encode()).getOrThrow()
        assertEquals("install-zzz", back.installId); assertEquals(9, back.deletionCounter); assertEquals(4, back.revision); assertEquals("parent-trace-01", back.parentTraceId)
        assertFailsWith<IllegalArgumentException> { m.copy(revision = 0) }
        assertFailsWith<IllegalArgumentException> { m.copy(parentTraceId = "BAD ID") }
    }

    @Test fun manifestKeysAreAnAllowlistOfNumbersEnumsHashesAndIds() {
        // nothing private: every key is known, and the document is printable ASCII
        val keys = Regex("\"([A-Za-z0-9]+)\":").findAll(TraceFixtures.goldenManifest).map { it.groupValues[1] }.toSet()
        val allowed = setOf(
            "bootId", "chunkCount", "closeReason", "deletionCounter", "endElapsedMs", "endPhoneWallMs", "endWallMs", "eventCount", "installId", "offsetUncertaintyMs",
            "parentTraceId", "phoneOffsetMs", "rawBytes", "recorded", "revision", "snapshot", "startElapsedMs", "startPhoneWallMs", "startWallMs", "traceId", "traceSchema", "traceSha256",
            "ctx", "exact", "glance", "keptAt", "lastKeyframeAt", "planGen", "planHash", "resting", "ring", "sampleEvery", "settingsHash", "tier",
            "bigTurnRad", "bridgeMs", "capPerMin", "deadbandRadS", "maxDtMs", "minBoutMs", "minBoutRad", "noveltyMinRad", "noveltyWindowMs", "onsetRadS", "releaseRadS", "restCapPerMin", "settleMs",
            "gateLastKeptAt", "gateKeptAt", "lastBriefAt", "stillStep", "lastCheckHash", "darkStreak", "bindBackoffUntil", "coldStartGrace", "startups", "pose", "lastPhotoAttemptAtMs",
            "rx", "ry", "rz", "walked", "qx", "qy", "qz", "quietSinceMs",
        )
        assertEquals(emptySet(), keys - allowed)
        assertEquals(emptySet(), allowed - keys)
        assertTrue(TraceFixtures.goldenManifest.all { it.code in 0x20..0x7e })
        assertFailsWith<IllegalArgumentException> { TraceFixtures.manifest().copy(closeReason = "free text with spaces") }
    }

    @Test fun snapshotCarriesTheSchedulerStartState() {
        val back = Manifest.decodeStrict(TraceFixtures.goldenManifest).getOrThrow().snapshot
        val s = TraceFixtures.snapshot
        assertEquals(s.lastBriefAt, back.lastBriefAt); assertEquals(s.stillStep, back.stillStep); assertEquals(s.lastCheckHash, back.lastCheckHash)
        assertEquals(s.darkStreak, back.darkStreak); assertEquals(s.bindBackoffUntil, back.bindBackoffUntil); assertEquals(s.startups, back.startups)
        assertEquals(s.pose, back.pose); assertEquals(s.lastPhotoAttemptAtMs, back.lastPhotoAttemptAtMs)
        assertEquals(s.gateLastKeptAt, back.gateLastKeptAt); assertEquals(s.gateKeptAt, back.gateKeptAt)
        // session and gate values are recorded separately: they differ in the fixture on purpose (after a forced keep or an encode failure)
        assertTrue(back.keptAt != back.gateKeptAt && back.lastKeyframeAt != back.gateLastKeptAt)
        val none = s.copy(lastBriefAt = null, lastCheckHash = null, bindBackoffUntil = null, lastPhotoAttemptAtMs = null, gateLastKeptAt = null, lastKeyframeAt = null, startups = emptyList(), pose = PoseSnapshot.ZERO)
        assertEquals(none, Manifest.decodeStrict(TraceFixtures.manifest(snap = none).encode()).getOrThrow().snapshot)
    }

    @Test fun bootIdIsTheDecimalBootCountAndRevisionIsOneBased() {
        assertEquals("42", Manifest.bootIdOf(42))
        assertEquals(TraceFixtures.manifest().bootId, Manifest.bootIdOf(42))
        assertFailsWith<IllegalArgumentException> { TraceFixtures.manifest().copy(bootId = "boot-42") }
        assertFailsWith<IllegalArgumentException> { TraceFixtures.manifest().copy(bootId = "") }
        assertFailsWith<IllegalArgumentException> { TraceFixtures.manifest().copy(revision = 0) }
    }

    // ---- COMPLETE / PARTIAL ----
    private val ev = TraceFixtures.events
    private fun enc(seq: Int, events: List<TraceEvent>, traceId: String = id): DecodedChunk {
        val e = ChunkCodec.encode(traceId, seq, events)
        return ChunkCodec.decode(traceId, seq, e.sha256, e.b64).getOrThrow()
    }
    /** The golden trace split in three chunks: [0,8) [8,14) [14,17). */
    private fun three(): List<DecodedChunk> = listOf(enc(0, ev.subList(0, 8)), enc(1, ev.subList(8, 14)), enc(2, ev.subList(14, 17)))
    private fun manifestFor(c: List<DecodedChunk>, recorded: TraceStatus = TraceStatus.COMPLETE, start: Long = 1000, end: Long = 12_000, close: String = "stop"): Manifest {
        val sorted = c.sortedBy { it.seq }
        return TraceFixtures.manifest(
            chunkCount = c.size, eventCount = c.sumOf { it.events.size }.toLong(), rawBytes = c.sumOf { it.rawBytes }.toLong(),
            traceSha = Manifest.traceSha(sorted.map { it.sha256 }), recorded = recorded,
        ).copy(startElapsedMs = start, endElapsedMs = end, closeReason = close)
    }
    private fun verdict(events: List<TraceEvent>, mod: (Manifest) -> Manifest = { it }): Verification {
        val c = enc(0, events)
        return TraceAssembler(mod(manifestFor(listOf(c)))).also { it.accept(c) }.verify()
    }

    @Test fun completeWhenEveryChunkIsPresentAndMatching() {
        val (a, b, c) = three()
        val asm = TraceAssembler(manifestFor(three()))
        listOf(c, a, b).forEach { asm.accept(it) }            // arrival order does not matter
        asm.accept(b)                                          // an idempotent re-send is fine
        val v = asm.verify()
        assertEquals(TraceStatus.COMPLETE, v.status, v.reason)
        assertEquals(TraceStatus.COMPLETE, verdict(ev).status)
    }

    @Test fun partialWhenAChunkIsMissing() {
        val (a, _, c) = three()
        val asm = TraceAssembler(manifestFor(three())); asm.accept(a); asm.accept(c)
        assertEquals(TraceStatus.PARTIAL, asm.verify().status)
        assertEquals("missing", asm.verify().reason)
    }

    @Test fun partialWhenAChunkDiffersFromTheOneTheManifestSummarises() {
        val (a, _, c) = three()
        val other = enc(1, ev.subList(8, 14).map { if (it is TraceEvent.Flush) TraceEvent.Flush(it.tMs, "wind0w") else it })
        val asm = TraceAssembler(manifestFor(three())); asm.accept(a); asm.accept(other); asm.accept(c)
        assertEquals("trace_sha", asm.verify().reason)
    }

    @Test fun partialOnConflictingResendAndForeignChunk() {
        val m = manifestFor(three())
        val (a, b, c) = three()
        val dup = TraceAssembler(m); dup.accept(a); dup.accept(b); dup.accept(enc(1, listOf(TraceEvent.Flush(5000, "x")))); dup.accept(c)
        assertEquals("conflict", dup.verify().reason)
        val foreign = TraceAssembler(m); foreign.accept(a); foreign.accept(b); foreign.accept(c); foreign.accept(enc(0, listOf(TraceEvent.Flush(5, "x")), traceId = "trace-9999-other"))
        assertEquals("foreign_chunk", foreign.verify().reason)
        val beyond = TraceAssembler(m); beyond.accept(a); beyond.accept(b); beyond.accept(c); beyond.accept(enc(3, listOf(TraceEvent.Flush(12_001, "x"))))
        assertEquals("seq_out_of_range", beyond.verify().reason)
    }

    @Test fun partialWhenTheChunkCountDisagreesWithTheManifest() {
        val (a, b, c) = three()
        fun withCount(d: DecodedChunk, n: Int) = DecodedChunk(d.traceId, d.seq, d.events, d.rawBytes, d.sha256, n)
        val asm = TraceAssembler(manifestFor(three()))
        asm.accept(withCount(a, 3)); asm.accept(withCount(b, 3)); asm.accept(withCount(c, 4))
        assertEquals("count_mismatch", asm.verify().reason)
        val ok = TraceAssembler(manifestFor(three())); listOf(a, b, c).forEach { ok.accept(withCount(it, 3)) }
        assertEquals(TraceStatus.COMPLETE, ok.verify().status)
        // a wire chunk carries its count through ChunkCodec.decode(TraceChunk)
        val e = ChunkCodec.encode(id, 0, ev.subList(0, 8))
        assertEquals(3, ChunkCodec.decode(com.rokid.context.core.model.TraceChunk(traceId = id, kind = "chunk", seq = 0, count = 3, sha256 = e.sha256, b64 = e.b64)).getOrThrow().count)
    }

    @Test fun partialWhenTheGlassesRecordedItPartial() {
        val v = verdict(ev) { it.copy(recorded = TraceStatus.PARTIAL) }
        assertEquals(TraceStatus.PARTIAL, v.status); assertEquals("recorded_partial", v.reason)
    }

    @Test fun partialWhenTimeRunsBackwardsAcrossChunksOrLeavesTheTraceWindow() {
        val a = enc(0, ev.subList(0, 8)); val b = enc(1, listOf(TraceEvent.Flush(1_500, "x"), TraceEvent.Flush(12_000, "y")))
        val asm = TraceAssembler(manifestFor(listOf(a, b))); asm.accept(a); asm.accept(b)
        assertEquals("not_monotonic", asm.verify().reason)    // chunk 0 ends at 2000
        val early = enc(0, listOf(TraceEvent.Flush(900, "x"), TraceEvent.Flush(12_000, "y")))
        val e = TraceAssembler(manifestFor(listOf(early))); e.accept(early)
        assertEquals("range", e.verify().reason)
    }

    @Test fun partialWhenTotalsDisagree() {
        val c = enc(0, ev)
        val asm = TraceAssembler(manifestFor(listOf(c)).copy(eventCount = 5)); asm.accept(c)
        assertEquals("totals", asm.verify().reason)
    }

    @Test fun noChunksAtAllIsPartial() {
        assertEquals("missing", TraceAssembler(manifestFor(listOf(enc(0, ev)))).verify().reason)
    }

    // ---- recorder obligations (M3) and the plan/settings rule (I4) ----
    private fun without(tag: String) = ev.filter { EventCodec.tag(it) != tag }

    @Test fun recorderMustAppendTheInitialStateAtTheStart() {
        for (t in listOf("cx", "rs", "pf", "gp", "pl")) assertEquals("start_state", verdict(without(t)).reason, t)
        // present but not at the start time
        val late = ev.map { if (it is TraceEvent.Ctx) TraceEvent.Ctx(1001, it.state) else it }.sortedBy { it.tMs }
        assertEquals("start_state", verdict(late).reason)
    }

    @Test fun aPlanOrSettingsChangeInsideATraceIsRefused() {
        val plan = ev.toMutableList().also { it.add(8, TraceEvent.Plan(2200, 8, "abc123def456", com.rokid.context.core.model.PlanMode.TRIAL)) }
        assertEquals("config_change", verdict(plan).reason)
        val gate = ev.toMutableList().also { it.add(8, TraceEvent.GateParams(2200, 11, 24, true)) }
        assertEquals("config_change", verdict(gate).reason)
        val quick = ev.toMutableList().also { it.add(8, (ev[2] as TraceEvent.Profile).copy(tMs = 2200, quickLookMs = 1000)) }
        assertEquals("config_change", verdict(quick).reason)
        // heat/battery changes of the other profile fields are fine
        val heat = ev.toMutableList().also { it.add(8, (ev[2] as TraceEvent.Profile).copy(tMs = 2200, heatSlow = false, lowPower = true)) }
        assertEquals(TraceStatus.COMPLETE, verdict(heat).status)
    }

    @Test fun planAndSettingsClosesAreKnownCloseReasons() {
        assertEquals(setOf("stop", "pause", "unplug", "epoch", "cap", "reboot", "plan", "plan_revert", "settings"), CloseReason.ALL)
        for (r in listOf("plan", "plan_revert", "settings")) {
            val withClose = ev.dropLast(1) + TraceEvent.Close(12_000, r)
            assertEquals(TraceStatus.COMPLETE, verdict(withClose) { it.copy(closeReason = r) }.status, r)
        }
        assertFailsWith<IllegalArgumentException> { TraceFixtures.manifest().copy(closeReason = "whatever") }
    }

    @Test fun preRollEndIsExactlyTenSecondsAfterTheStart() {
        assertEquals("pre_roll", verdict(ev.map { if (it is TraceEvent.PreRollEnd) TraceEvent.PreRollEnd(10_999) else it }).reason)
        assertEquals("pre_roll", verdict(without("pr")).reason)                       // the trace is longer than 10 s
        assertEquals("pre_roll", verdict(ev.toMutableList().also { it.add(16, TraceEvent.PreRollEnd(11_000)) }).reason)
        // a trace shorter than the pre-roll has none
        val short = ev.filter { it !is TraceEvent.PreRollEnd }.dropLast(1) + TraceEvent.Close(5_000, "stop")
        assertEquals(TraceStatus.COMPLETE, verdict(short) { it.copy(endElapsedMs = 5_000) }.status)
    }

    @Test fun closeIsLastAndMatchesTheManifest() {
        assertEquals("close", verdict(without("cl")).reason)
        assertEquals("close", verdict(ev) { it.copy(closeReason = "pause") }.reason)
        assertEquals("close", verdict(ev.dropLast(1) + TraceEvent.Flush(12_000, "x") ).reason)
        assertEquals("close", verdict(ev.dropLast(1) + TraceEvent.Close(11_500, "stop") + TraceEvent.Close(12_000, "stop")).reason)
    }

    @Test fun tooManyChunksRejectedByTheManifest() {
        assertFailsWith<IllegalArgumentException> { TraceFixtures.manifest(chunkCount = 4097) }
        assertFailsWith<IllegalArgumentException> { TraceFixtures.manifest(chunkCount = 0) }
        assertTrue(TraceFixtures.manifest(chunkCount = 4096, eventCount = 5000).chunkCount == 4096)
        assertFailsWith<IllegalArgumentException> { TraceFixtures.manifest(eventCount = TraceBounds.MAX_TRACE_EVENTS + 1) }
        assertFailsWith<IllegalArgumentException> { TraceFixtures.manifest(rawBytes = TraceBounds.MAX_TRACE_RAW_BYTES + 1) }
    }

    @Test fun preRollEndComesFromTheStart() {
        assertEquals(TraceFixtures.manifest().startElapsedMs + 10_000, TraceFixtures.manifest().preRollEndMs)
    }
}
