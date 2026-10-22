# Task 19 report (CORE part): CollectSession, TraceImport, tools/trace-pull.sh

Worktree pca-s3/a, branch s3a/a, base 87bb8fe0. Hub recorder NOT built (LATER LA4), so `hubTapDoesNotChangeTickCadence` is not written.
Gate: `./gradlew --no-daemon --offline clean :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.*'` = 268 tests, 0 failures (20 new: TraceImportTest 14, TraceImportDeleteAllTest 1, CollectSessionTest 5). No adb, no background processes.

## Built
- `canvas/trace/CollectSession.kt`: persisted session (prefs `canvas_collect`: start and until), `control()` builds a `TraceControl` from the PERSISTED epoch (`DeletionEpochStore.read()` each time), `send = true`, bounded 1 min to 12 h (default 20 min), `start/stop/refresh`, `flow` to publish on TRACE_CTL; tombstones and forget range ride one-off controls.
- `canvas/trace/TraceImport.kt`: `import(dir)` / `importAll(pulledRoot)`. Verifies with the frozen `TraceFiles.verify` (checksums, file set, epoch of files), then phone epoch (`other_install`, `old_epoch`), tombstone, dir name = trace id, per-file size bound before read, symlinks refused, 500 MB phone cap. Copies to canvas_tmp and publishes to canvas_traces/<id> through `FencedFiles.publish`; the fence lambda writes the `canvas_trace` row (`acceptTraceFenced`) in the same transaction. Same content = Unchanged; changed content = revision + 1 and `invalidateBySources`. PARTIAL traces are kept (status + reason returned).
- `tools/trace-pull.sh` (controller-run, only `bash -n` checked, never run): run-as tar of files/traces, path-traversal refusal, drops dirs without manifest.json.
- Tests: `PulledTraces` fixture writes the frozen on-disk layout.

## Test-first note
Tests and code were written in the same pass (one first run went red on a bad corruption offset in my own test, fixed). RED was proven afterwards by mutation, each restored:
- epoch fence (fence checks removed in publish lambda): `aDeleteAllDuringTheImportPublishesNoFileAndNoRow` RED.
- checksum refusal (verify failure ignored): 3 tests RED (flipped sha, corrupted payload, missing chunk/extra file).
- Delete all removal (canvas_traces dropped from MemoryWipe's dirs): `DeleteAllEpochTest.deleteAllRemovesFolders` and `TraceImportDeleteAllTest` RED.
- CollectSession epoch (deletionCounter = 0): `controlCarriesPersistedEpoch` RED.

## Concerns
- `ReplayTraces` binding to the stored chunk files is still Task 22.
- Imported traces keep `phoneOffsetMs` null (no phone clock mapping at import).
- Privacy test is a regex over stored files; the real guarantee is that the frozen event schema has no address fields.
