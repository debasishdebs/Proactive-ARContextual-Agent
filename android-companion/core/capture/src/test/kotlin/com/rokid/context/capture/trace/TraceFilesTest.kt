package com.rokid.context.capture.trace

import com.rokid.context.core.model.TraceChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TraceFilesTest {
    private val id = TraceFixtures.ID
    private fun code(r: Result<*>): String = (r.exceptionOrNull() as? TraceFormatException)?.code ?: "NOT_A_TRACE_FORMAT_FAILURE:${r.exceptionOrNull()}:${r.getOrNull()}"
    private val ev = TraceFixtures.events
    private val manifest = TraceFixtures.manifest()
    private val chunk: TraceChunk = ChunkCodec.encode(id, 0, ev).let {
        TraceChunk(traceId = id, kind = "chunk", seq = 0, count = 1, installId = manifest.installId, deletionCounter = manifest.deletionCounter, sha256 = it.sha256, b64 = it.b64)
    }

    private fun dir(m: TraceChunk = ManifestChunk.encode(manifest), c: TraceChunk = chunk) =
        mapOf(TraceFiles.MANIFEST_FILE to TraceFiles.encodeFile(m), TraceFiles.chunkFile(0) to TraceFiles.encodeFile(c))
    private fun verify(files: Map<String, String>) = TraceFiles.verify(files.keys) { files.getValue(it) }

    @Test fun manifestRidesOneChunkPlainBase64OfTheCanonicalText() {
        val c = ManifestChunk.encode(manifest, atMs = 77, sessionStartMs = 5)
        assertEquals("manifest", c.kind); assertEquals(0, c.seq); assertEquals(1, c.count)
        assertEquals(id, c.traceId); assertEquals("install-abc", c.installId); assertEquals(3L, c.deletionCounter)
        assertEquals(77L, c.atMs); assertEquals(5L, c.sessionStartMs)
        // plain: base64 of the canonical text itself, sha over the same raw bytes (no gzip)
        assertEquals(TraceFixtures.goldenManifest, String(java.util.Base64.getDecoder().decode(c.b64), Charsets.UTF_8))
        assertEquals(TraceFixtures.sha(TraceFixtures.goldenManifest.toByteArray()), c.sha256)
        assertEquals(manifest, ManifestChunk.decode(c).getOrThrow())
    }

    @Test fun manifestChunkIsRefusedWhenAnythingIsWrong() {
        val c = ManifestChunk.encode(manifest)
        assertEquals("kind", code(ManifestChunk.decode(c.copy(kind = "chunk"))))
        assertEquals("seq", code(ManifestChunk.decode(c.copy(seq = 1))))
        assertEquals("chunk_count", code(ManifestChunk.decode(c.copy(count = 2))))
        assertEquals("sha_invalid", code(ManifestChunk.decode(c.copy(sha256 = "x"))))
        assertEquals("sha_mismatch", code(ManifestChunk.decode(c.copy(sha256 = "0".repeat(64)))))
        assertEquals("b64_invalid", code(ManifestChunk.decode(c.copy(b64 = c.b64.trimEnd('=')))).let { if (c.b64.endsWith("=")) it else "b64_invalid" })
        assertEquals("b64_invalid", code(ManifestChunk.decode(c.copy(b64 = "!!!!"))))
        assertEquals("b64_too_long", code(ManifestChunk.decode(c.copy(b64 = "A".repeat(20_000)))))
        assertEquals("header_mismatch", code(ManifestChunk.decode(c.copy(installId = "other"))))
        assertEquals("header_mismatch", code(ManifestChunk.decode(c.copy(deletionCounter = 4))))
        assertEquals("header_mismatch", code(ManifestChunk.decode(c.copy(traceId = "trace-9999-other"))))
        // gzip is NOT accepted for the manifest
        val gz = TraceFixtures.gzipB64(TraceFixtures.goldenManifest.toByteArray())
        assertEquals("sha_mismatch", code(ManifestChunk.decode(c.copy(b64 = gz))))
        val notJson = "not a manifest".toByteArray()
        assertEquals("bad_manifest", code(ManifestChunk.decode(c.copy(b64 = java.util.Base64.getEncoder().encodeToString(notJson), sha256 = TraceFixtures.sha(notJson)))))
    }

    @Test fun goldenFileNamesAndContents() {
        assertEquals("manifest.json", TraceFiles.MANIFEST_FILE)
        assertEquals("chunk-0000.json", TraceFiles.chunkFile(0)); assertEquals("chunk-4095.json", TraceFiles.chunkFile(4095))
        assertFailsWith<IllegalArgumentException> { TraceFiles.chunkFile(4096) }
        assertEquals(id, TraceFiles.dirName(id)); assertFailsWith<IllegalArgumentException> { TraceFiles.dirName("../etc") }
        val text = TraceFiles.encodeFile(chunk)
        assertTrue(text.startsWith("""{"v":1,"traceId":"trace-0001-golden","kind":"chunk","seq":0,"count":1,"installId":"install-abc","deletionCounter":3,"sha256":"${TraceFixtures.GOLDEN_SHA}","b64":""""), text.take(200))
        assertTrue(text.endsWith(""","atMs":0,"sessionStartMs":0}"""), text.takeLast(60))
        assertEquals(chunk, TraceFiles.decodeFile(text).getOrThrow())
    }

    @Test fun anImportVerifiesCompleteAndMatchesALiveDecode() {
        val r = verify(dir()).getOrThrow()
        assertEquals(manifest, r.first); assertEquals(TraceStatus.COMPLETE, r.second.status, r.second.reason)
        // the file's chunk is the very chunk the link carries
        assertEquals(chunk, TraceFiles.decodeFile(dir().getValue("chunk-0000.json")).getOrThrow())
    }

    @Test fun anImportRefusesAWrongFileSetOrAnEpochMismatch() {
        val d = dir()
        assertEquals("bad_file_set", code(verify(d - TraceFiles.MANIFEST_FILE)))
        assertEquals("bad_file_set", code(verify(d - "chunk-0000.json")))
        assertEquals("bad_file_set", code(verify(d + ("chunk-0001.json" to d.getValue("chunk-0000.json")))))
        assertEquals("bad_file_set", code(verify(d + ("notes.txt" to "x"))))
        assertEquals("epoch_mismatch", code(verify(dir(c = chunk.copy(deletionCounter = 2)))))
        assertEquals("epoch_mismatch", code(verify(dir(c = chunk.copy(installId = "other")))))
        assertEquals("file_seq", code(verify(mapOf(TraceFiles.MANIFEST_FILE to d.getValue(TraceFiles.MANIFEST_FILE), "chunk-0000.json" to TraceFiles.encodeFile(chunk.copy(seq = 1, count = 2))))))
    }

    @Test fun fileTextIsCanonicalAndBounded() {
        val text = TraceFiles.encodeFile(chunk)
        assertEquals("not_canonical", code(TraceFiles.decodeFile(" $text")))
        assertEquals("not_canonical", code(TraceFiles.decodeFile(text.replace("\"v\":1,", "\"v\": 1,"))))
        assertEquals("bad_file", code(TraceFiles.decodeFile("{}x")))
        assertEquals("bad_file", code(TraceFiles.decodeFile("")))
        assertEquals("file_too_long", code(TraceFiles.decodeFile("x".repeat(TraceFiles.MAX_FILE_CHARS + 1))))
    }

    @Test fun aTamperedChunkInsideAnImportIsPartialOrRefused() {
        val tampered = ChunkCodec.encode(id, 0, ev.map { if (it is TraceEvent.Flush) TraceEvent.Flush(it.tMs, "timer") else it })
        val c = chunk.copy(sha256 = tampered.sha256, b64 = tampered.b64)
        val r = verify(dir(c = c)).getOrThrow()
        assertEquals(TraceStatus.PARTIAL, r.second.status); assertEquals("totals", r.second.reason)   // the raw size differs ("window" vs "timer")
        assertEquals("sha_mismatch", code(verify(dir(c = chunk.copy(sha256 = tampered.sha256)))))
    }
}

class TraceFilesLocaleTest {
    @Test fun chunkFileNamesAreAsciiUnderANonAsciiDigitLocale() {
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-SA-u-nu-arab"))
            assertTrue(String.format("%04d", 7).any { it.code > 127 }, "the test locale must really produce non-ASCII digits")
            assertEquals("chunk-0007.json", TraceFiles.chunkFile(7))
            assertTrue(TraceFiles.chunkFile(4095).matches(Regex("chunk-[0-9]{4}\\.json")))
        } finally { java.util.Locale.setDefault(old) }
    }
}
