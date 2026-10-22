# Task 6 report: frozen trace schema (schema 1)

Files: core/capture/src/main/kotlin/com/rokid/context/capture/trace/{TraceBounds,TraceRecords,ChunkCodec,Manifest,ClockMap}.kt, tests in core/capture/src/test/.../trace/.

## Frozen format
- TRACE_SCHEMA = 1. Chunk payload = header line ["PCT1","<traceId>",seq,eventCount] then one canonical JSON array per event ["tag",tMs,fields...], every line ended by \n (no \r, no blank line).
- Tags: g a cx rs pf gp pl ep sc gt fl bo bl pr cl. Floats = IEEE bits (signed int), 64-bit hashes = hex16, enums by name (ep cause and gt outcome lower case). Decode accepts only a line that re-encodes to itself.
- Chunk sha256 = hex SHA-256 of the raw payload. b64 = base64 (padded) of gzip(payload). TraceChunk mapping: kind "chunk", count = chunks in trace, seq in [0,count).
- Bounds (S73): b64 <= 90,000 chars, raw <= 512 KiB streamed gunzip limit, <= 200,000 events/chunk (header count checked first), <= 4,096 chunks, traceId ^[a-z0-9-]{8,64}$. Plus trace caps: 256 MiB raw, 10M events, line <= 512 chars, manifest <= 8 KiB, depth <= 5.
- Manifest: canonical JSON (sorted keys via CanonicalJson), all keys required, null for optional, strict decode (no unknown/duplicate key, re-encode equality). Fields: traceSchema, traceId, installId, deletionCounter, revision, parentTraceId, bootId, start/endElapsedMs, start/endWallMs, phoneOffsetMs, offsetUncertaintyMs, chunkCount, eventCount, rawBytes, traceSha256, recorded, closeReason, snapshot(StartSnapshot). exact is derived from snapshot (sampleEvery==1, enforced).
- traceSha256 = SHA-256 of the ASCII concatenation of chunk sha hex strings in seq order (per-chunk shas travel in each TraceChunk; a 4,096-entry list cannot fit one 90k chunk).
- TraceAssembler (streaming, keeps no events): COMPLETE only if all seqs present once, same trace, time monotonic and inside [start,end], totals equal, trace sha equal, recorded COMPLETE; else PARTIAL with a reason token.
- ClockMap: linear glasses-wall offset between start/end samples, nearest offset held for 60 s slack, null beyond; null for any other bootId; phone wall null until phoneOffsetMs set; resolve() picks by boot and time.
- Clean start: snapshot + Ctx/Resting/Profile/GateParams/Plan events at start time; PreRollEnd at start + 10 s (PRE_ROLL_MS); manifest.preRollEndMs.
- Privacy: numbers, enum names, hash hex, ids only.

## Tests
trace package 45 tests; :core:capture:test whole module 227 tests, 0 failures. Golden fixtures byte-exact (payload text, sha, manifest JSON, plus a python-made gzip decoded).
Mutation REDs: gunzip limit removed -> gunzipBombRejected, gunzipLimitIsStreamed; boot check removed -> rebootEpochIsNeverMapped; chunk sha check removed -> checksumMismatchRejected; canonical re-encode check removed -> structureIsStrict, strictDecodeRefusesEverythingNonCanonical.

## Concerns for review
1. tMs must be non-decreasing: the recorder (Task 16) needs a single writer that stamps elapsedRealtime at append.
2. Gzip bytes are not byte-exact across JDKs; sha is over raw payload on purpose.
3. Initial-state events are a recorder obligation (documented), not validated by the decoder.
4. Trailing bytes after the gzip member are ignored (bounded by b64 limit; sha covers the raw payload only).
5. Manifest has no per-chunk sha list (see above).
