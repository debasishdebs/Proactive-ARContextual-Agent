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
            "unsorted" to g.replace("\"bootId\":\"boot-42\",\"chunkCount\":1", "\"chunkCount\":1,\"bootId\":\"boot-42\""),
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

    @Test fun nothingPrivateInTheManifest() {
        // the document is numbers, enum names, hashes and ids: no free text fields beyond the bounded closeReason token
        assertTrue(TraceFixtures.goldenManifest.all { it.code in 0x20..0x7e })
        assertFailsWith<IllegalArgumentException> { TraceFixtures.manifest().copy(closeReason = "free text with spaces") }
    }

    // ---- COMPLETE / PARTIAL (round 1 cases) ----
    private fun chunk(seq: Int, vararg ts: Long, traceId: String = id): DecodedChunk {
        val enc = ChunkCodec.encode(traceId, seq, ts.map { TraceEvent.PreRollEnd(it) })
        return ChunkCodec.decode(traceId, seq, enc.sha256, enc.b64).getOrThrow()
    }
    private fun manifestFor(vararg c: DecodedChunk, recorded: TraceStatus = TraceStatus.COMPLETE, start: Long = 1000, end: Long = 12_000): Manifest {
        val sorted = c.sortedBy { it.seq }
        return TraceFixtures.manifest(
            chunkCount = c.size, eventCount = c.sumOf { it.events.size }.toLong(), rawBytes = c.sumOf { it.rawBytes }.toLong(),
            traceSha = Manifest.traceSha(sorted.map { it.sha256 }), recorded = recorded,
        ).copy(startElapsedMs = start, endElapsedMs = end)
    }

    @Test fun completeWhenEveryChunkIsPresentAndMatching() {
        val a = chunk(0, 1000, 2000); val b = chunk(1, 3000); val c = chunk(2, 12_000)
        val asm = TraceAssembler(manifestFor(a, b, c))
        listOf(c, a, b).forEach { asm.accept(it) }            // arrival order does not matter
        asm.accept(b)                                          // an idempotent re-send is fine
        val v = asm.verify()
        assertEquals(TraceStatus.COMPLETE, v.status, v.reason)
    }

    @Test fun partialWhenAChunkIsMissing() {
        val a = chunk(0, 1000); val b = chunk(1, 3000); val c = chunk(2, 12_000)
        val asm = TraceAssembler(manifestFor(a, b, c)); asm.accept(a); asm.accept(c)
        assertEquals(TraceStatus.PARTIAL, asm.verify().status)
        assertEquals("missing", asm.verify().reason)
    }

    @Test fun partialWhenAChunkDiffersFromTheOneTheManifestSummarises() {
        val a = chunk(0, 1000); val b = chunk(1, 3000); val c = chunk(2, 12_000)
        val m = manifestFor(a, b, c)
        val asm = TraceAssembler(m); asm.accept(a); asm.accept(chunk(1, 3001)); asm.accept(c)
        assertEquals("trace_sha", asm.verify().reason)
        assertEquals(TraceStatus.PARTIAL, asm.verify().status)
    }

    @Test fun partialOnConflictingResendAndForeignChunk() {
        val a = chunk(0, 1000); val c = chunk(1, 12_000)
        val m = manifestFor(a, c)
        val dup = TraceAssembler(m); dup.accept(a); dup.accept(chunk(0, 1001)); dup.accept(c)
        assertEquals("conflict", dup.verify().reason)
        val foreign = TraceAssembler(m); foreign.accept(a); foreign.accept(c); foreign.accept(chunk(0, 5, traceId = "trace-9999-other"))
        assertEquals("foreign_chunk", foreign.verify().reason)
        val beyond = TraceAssembler(m); beyond.accept(a); beyond.accept(c); beyond.accept(chunk(2, 12_001))
        assertEquals("seq_out_of_range", beyond.verify().reason)
    }

    @Test fun partialWhenTheGlassesRecordedItPartial() {
        val a = chunk(0, 1000, 12_000)
        val asm = TraceAssembler(manifestFor(a, recorded = TraceStatus.PARTIAL)); asm.accept(a)
        assertEquals(TraceStatus.PARTIAL, asm.verify().status); assertEquals("recorded_partial", asm.verify().reason)
    }

    @Test fun partialWhenTimeRunsBackwardsAcrossChunksOrLeavesTheTraceWindow() {
        val a = chunk(0, 1000, 5000); val b = chunk(1, 4000, 12_000)
        val asm = TraceAssembler(manifestFor(a, b)); asm.accept(a); asm.accept(b)
        assertEquals("not_monotonic", asm.verify().reason)
        val early = chunk(0, 900, 12_000)
        val e = TraceAssembler(manifestFor(early)); e.accept(early)
        assertEquals("range", e.verify().reason)
    }

    @Test fun partialWhenTotalsDisagree() {
        val a = chunk(0, 1000, 12_000)
        val m = manifestFor(a).copy(eventCount = 5)
        val asm = TraceAssembler(m); asm.accept(a)
        assertEquals("totals", asm.verify().reason)
    }

    @Test fun noChunksAtAllIsPartial() {
        val a = chunk(0, 1000, 12_000)
        assertEquals("missing", TraceAssembler(manifestFor(a)).verify().reason)
    }

    @Test fun tooManyChunksRejectedByTheManifest() {
        assertFailsWith<IllegalArgumentException> { TraceFixtures.manifest(chunkCount = 4097) }
        assertFailsWith<IllegalArgumentException> { TraceFixtures.manifest(chunkCount = 0) }
        assertTrue(TraceFixtures.manifest(chunkCount = 4096, eventCount = 5000).chunkCount == 4096)
        assertFailsWith<IllegalArgumentException> { TraceFixtures.manifest(eventCount = TraceBounds.MAX_TRACE_EVENTS + 1) }
        assertFailsWith<IllegalArgumentException> { TraceFixtures.manifest(rawBytes = TraceBounds.MAX_TRACE_RAW_BYTES + 1) }
    }

    @Test fun preRollIsTenSecondsAfterTheStart() {
        assertEquals(11_000, TraceFixtures.manifest().preRollEndMs)
        assertEquals(10_000, TraceBounds.PRE_ROLL_MS)
    }
}
