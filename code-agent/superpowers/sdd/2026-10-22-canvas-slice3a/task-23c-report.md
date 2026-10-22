# Task 23c report: trace import route + m1-run hand-over

Worktree pca-s3/a, branch s3a/a. Merge of s3a/b: clean, no conflicts (merge commit = parent of the task commit; see git log).

## Built
- `TraceRoutes`: `POST /api/traces/import {name, allowPartial?}` (Developer mode gate first: 403). `name` must match the trace id regex (`^[a-z0-9-]{8,64}$`), resolved strictly under `getExternalFilesDir(null)/trace-staging` (NOFOLLOW directory check + canonical parent == canonical root; TraceImport also refuses symlinked files). Result `{traceId,status,events,chunks,unchanged}`; staging dir deleted only after success. Codes: 400 bad name/body, 404 no staging dir/trace, 409 partial_trace/old_epoch/other_install/tombstoned/fence, 413 phone_cap, 422 bad/tampered files, 503 no importer.
- `TraceImport.import(dir, allowPartial = true)`: new `partial_trace` refusal before anything is stored.
- `CanvasExtras`: binds TraceImport (real FencedFiles + persisted-epoch fence) and the staging root. StoredReplayTraces was already bound to canvas_traces; proven by test.
- Tests moved `PulledTraces` to `services/orchestration/src/pulledfixtures` (added to both modules' test source sets).
- tools: `m1-run.sh pull <dir> [--allow-partial]` (trace-pull, adb push by transport id, import, summary), `replay` with no id uses the last imported trace; `M1_PHONE_TID`/`M1_PHONE_PKG`; mock server route + stub-adb tests; `M1-RUN.md`.

## Counts
- New: TraceImportRouteTest 9 (phone). Orchestration canvas.* 370 tests, 0 failures; phone canvas.* 351, 0 failures. `tools/test-m1-run.sh`: ALL OK.
- Mutations (each RED, restored): name+canonical check removed -> namesThatAreNotPlainTraceIds RED; dev gate removed -> developerModeOff RED; epoch check removed -> oldEpoch RED; allowPartial check removed -> aPartialTrace RED. With the route's own symlink/canonical check removed the symlink test stays green because TraceImport independently refuses a symlinked directory (defence in depth).

## Rulings and concerns
- adb writability: `/sdcard/Android/data/com.rokid.context/files/trace-staging` is the app's external files dir; the adb shell user can write app-specific dirs on Android 14/15 (known behaviour; NOT verified, no device). The app mkdirs it on each import call, and the script runs `mkdir -p`. Fallback if the push is refused: `adb push` to /data/local/tmp then `run-as` is not possible for a release build; report back.
- Package id assumed `com.rokid.context` (no debug suffix in phone/build.gradle.kts).
- Test-first: tests written before the route was compiled; RED shown by mutation (route did not exist when tests were authored).
