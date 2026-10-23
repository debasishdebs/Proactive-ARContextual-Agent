# trace-lab

Laptop-side experiments over sensor traces pulled from the glasses. Python standard library only (plus pytest for the tests). It decodes a pulled
trace directory, verifies every checksum, computes per-second features, runs a Python function of yours over the data and prints the result.

It is NOT connected to the app, the phone, the canvas, any database, any helper process, a container or the network, and it persists no results.
The data stays in the local files you pulled. **You delete the pulled files yourself when you are done** (a trace is personal data: it is your own
head and body motion). The only file this tool ever writes is the one you name with `features --out`.

## Usage

Run from this directory (`tools/trace-lab`), where `TRACE_DIR` is one pulled trace directory (`manifest.json` plus `chunk-0000.json` ...):

```
python3 -m trace_lab.cli summary  TRACE_DIR                      # verify, then print counts and span
python3 -m trace_lab.cli features TRACE_DIR [--out f.csv]        # per-second features as CSV
python3 -m trace_lab.cli run TRACE_DIR my_script.py [--func analyze] [--on arrays|features]
python3 -m pytest tools/trace-lab -q                             # tests (from the repository root)
```

Exit codes: 0 ok, 2 the trace was refused (a fixed message on stderr, never file content), 3 your script failed (only the exception type is printed).

Your script defines a function (default name `analyze`). With `--on arrays` it gets a `Trace`: `trace.arrays[tag][column]` is a list per column
(`"tMs"` plus the field names below; floats are decoded to real floats), plus `trace.status`, `trace.partial_reasons`, `trace.manifest` (dict or None),
`trace.event_count`. With `--on features` it gets the list of per-second row dicts. Whatever it returns is printed as JSON (or `repr`). Your function
runs in this process with your own rights: there is no sandbox; only run code you wrote.

```python
def analyze(trace):
    g = trace.arrays["g"]
    return {"gyro_samples": len(g["tMs"]), "duration_ms": g["tMs"][-1] - g["tMs"][0]}
```

Smoke check against a real pulled trace (not part of CI; prints counts and timing only):

```
time python3 -m trace_lab.cli summary /path/to/pulled/traces/<traceId>
time python3 -m trace_lab.cli features /path/to/pulled/traces/<traceId> | wc -l
```

## Format (frozen trace schema 1)

Sources: `ChunkCodec.kt`, `TraceFiles.kt`, `Manifest.kt`, `TraceRecords.kt` in `android-companion/core/capture/.../capture/trace/`.

A trace directory holds `manifest.json` and `chunk-0000.json` ... `chunk-NNNN.json` (4 digits, `seq` order). Each file is ONE line of compact JSON with exactly
the keys `v, traceId, kind, seq, count, installId, deletionCounter, sha256, b64, atMs, sessionStartMs`.

- **Chunk file**: `kind` is `chunk`, `seq` in `[0, count)` and equal to the number in the file name, `count` = number of chunks in the trace.
  `b64` = padded standard base64 of the GZIP of the raw payload (at most 90,000 characters; the gunzipped payload at most 512 KiB).
- **Raw payload**: UTF-8 lines each ended by `\n`. Line 1 is the header `["PCT1","<traceId>",<seq>,<eventCount>]`, then `eventCount` event lines
  `["<tag>",tMs,<fields>...]`, `tMs` non-decreasing (also across chunks). Floats are written as the signed 32-bit IEEE bits of a float32 (decoded here).
- **Checksum rule**: sha256 = lower-case hex SHA-256 of the RAW (gunzipped) payload bytes, not of the base64 and not of the gzip.
- **Manifest file**: `kind` is `manifest`, `seq` 0, `count` 1, `b64` = padded base64 of the canonical manifest JSON text with NO gzip, `sha256` = SHA-256 of
  those UTF-8 bytes. Its `traceSha256` = SHA-256 of the ASCII concatenation of the chunk `sha256` hex strings in `seq` order (the checksum chain).
  It also carries `chunkCount`, `eventCount`, `rawBytes`, `installId`, `deletionCounter`, `startElapsedMs`, `recorded` (`COMPLETE` or `PARTIAL`).

Events (the 16 tags; the columns of `trace.arrays[tag]` after `tMs`; `sMs` is the sensor time, `tMs` the record time):

| tag | fields |
| --- | --- |
<!-- tags:begin -->
| `g` | `sMs`, `x`, `y`, `z` |
| `a` | `sMs`, `x`, `y`, `z` |
| `cx` | `state` |
| `rs` | `on` |
| `pf` | `wake`, `settledMs`, `quietMs`, `walkMs`, `stillCapMs`, `analysisMs`, `tier`, `lowPower`, `heatSlow`, `quickLookMs`, `ctxCap`, `hard` |
| `gp` | `hamming`, `bigChange`, `ringAll` |
| `pl` | `gen`, `configHash`, `mode` |
| `ep` | `open`, `cause`, `firstFrameAtMs`, `openedAtMs`, `outcome` |
| `sc` | `seq`, `d`, `p`, `blur`, `passedBlur`, `motion`, `dwellArmed`, `force`, `cap`, `hard`, `nowMs` |
| `gt` | `seq`, `outcome`, `reason`, `encoded`, `keptAtMs` |
| `fl` | `reason`, `nowMs` |
| `bo` | `stillStep` |
| `bl` | `reason` |
| `dk` | `streak` |
| `pr` | (none) |
| `cl` | `reason` |
<!-- tags:end -->

### What is verified, and what is refused

Per chunk: file shape and size, `kind`, `seq` equal to the file name, base64 padding, gzip validity and the size bound, the sha256 over the RAW payload,
the payload header (`PCT1`, traceId, seq, eventCount equal to the line count), every event line (known tag, field count and types), time order.
Per trace: the file set (the manifest plus `chunk-0000 .. chunk-(chunkCount-1)`, nothing else, not even a stray `.DS_Store`), equal `installId` and
`deletionCounter` in every file, the manifest's own sha256, its `eventCount` and `rawBytes` against the chunks, and `traceSha256` against the chain.
Refused with a fixed text per code (`bad_dir, bad_file, file_too_long, extra_file, missing_chunk, b64_too_long, b64_invalid, gzip_invalid, gunzip_too_big,
sha_mismatch, bad_payload, header_mismatch, bad_event, not_monotonic, epoch_mismatch, count_mismatch, bad_manifest, manifest_sha, manifest_mismatch,
trace_sha`); the text never contains file content.

**Partial traces.** A trace never closed cleanly has no `manifest.json`: it is decoded (every chunk present still has to verify) and reported with
`status = PARTIAL`, reason `no_manifest`, plus `missing_chunks:<seqs>` for gaps. A manifest with `recorded = PARTIAL` is decoded with reason
`recorded_partial`. With a manifest present the set must be complete: a missing or extra chunk is refused. A corrupt chunk is always refused.
Deliberately not checked (the app's `TraceAssembler` does, for publish evidence): the recorder obligations (initial state events, one `pr`, one `cl`).

## Per-second features: how they differ from the app

Names and meaning follow `MotionClassifier.features()` / `SecondFeatures` (`gyroMax, gyroMean, gyroRms, aboveOnFrac, accVar, stepHz, rotRad`), with the
`GlanceParams` defaults (onset 0.6 rad/s, max dt 100 ms). One row per second (`second`, `tStartMs`, `n` gyro samples, `nAccel`), seconds without samples
are zero rows. This is a pure-Python APPROXIMATION of the on-device values, differing as follows:

- **Window**: the app computes `gyro*` and `rotRad` over the samples since the previous once-a-second call, and `accVar`/`stepHz` over a 1 s sliding window
  at the moment of that call. Here every feature is computed over the samples whose `tMs` fall into the same fixed bucket `floor((tMs - start) / 1000)`
  (`start` = the manifest's `startElapsedMs`, or the first event without a manifest). Row boundaries therefore differ from the app's tick times.
- **Time base**: `tMs` (record time) is used for bucketing and `dt`; the app's classifier is fed sensor sample times. `dt` of the very first gyro sample of the
  trace is 0 (the app starts from a stored previous time) and every `dt` is capped at 100 ms like the app.
- **Arithmetic**: float64 here, float32 on the device (small rounding differences). `accVar`/`stepHz` use the same rule (population per-axis variance summed,
  mean-crossings of the strongest axis, 1e-3 dead zone, needs 8 samples spanning 800 ms else 0), but on the bucket's samples.
- **Not reproduced**: the classifier state, the glance and micro-bout counts, the pose/`rotationSinceMark` (net rotation; `rotRad` is the travelled angle sum like the app's feature) and any
  gyro-bias handling. Use the `cx` events for the state the glasses recorded.
