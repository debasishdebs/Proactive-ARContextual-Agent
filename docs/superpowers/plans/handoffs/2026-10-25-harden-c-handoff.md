# Hardening C: the log ratchet migration (branch feat/harden-c, from v0.9.5 tip 47252247)

Self-contained. No wire, trace, Room or plan-hash change; behaviour identical except where a log message lost an id, path or name (below).

## Why
`pca.log` (phone) held only Canvas lines in the 2026-10-25 wear window because ~100 production files, including the hub, the watchdog wiring and the link, logged through `android.util.Log` (logcat only). The glasses logcat also leaks the device secret, so the PcLog file sinks (`capture_diag.log`, `pca.log`) are the only safe logs. All production code now logs through `PcLog`.

## Result
`phone/src/test/resources/raw-log-baseline.txt` is down from 100 files to 2, both the logcat backends that must call the platform: `glasses/.../log/GlassesLogSink.kt` (5) and `phone/.../log/PhoneLogSink.kt` (5). Nothing else may hold a raw reference (the ratchet).

## Batches (one commit each, each green)
1. `5c69fbdf` hub and link: `Hub`, `HubGraph`, `FrameIngestor`, `ContextHubService`, `StateBroadcaster`, `CxrGlassesLink`, `GlassAppWatcher`, `SessionSlot`. Removed from messages: frame id (`frame <id> not stored/ingested/dropped/resend verified` now fixed event names such as `frame not stored: delete_all_fence_unknown`), pause id (`pause added origin=..`, `phone pause added`). Kept: link state names, wearing booleans, counts, error class names, SDK numeric result codes, close reasons (enum).
2. `03cf39a6` glasses: `GlassesRuntime`, `MainActivity`, `CaptureAlarmReceiver`, `CaptureSession`, `ForegroundFallback`, `CameraXSamplingCamera`, `GlassesBridge`, `HudController`, `MinimisedIcon`, `PopupOverlay` (`CaptureService` only had an import). Removed: pause id in `paused locally`. No new per-second line: every converted line was already an event line (stale/ignored phone messages, failures); `Decoded.Malformed.reason` is a class name or a fixed phrase. The 1 Hz paths (heartbeat, status) were not touched, so `log_lines_dropped` pressure is unchanged.
3. `e17e7ac0` phone: `ContextApp`, `MainActivity`, actions, `PlanGraph`, `PhoneCapture`, `PhoneNudges`, settings screens, `PipelineControl`, `VoiceGraph`. All messages were already class-name or `SafeLog.describe` (kind, retryable, http code) only; no text change.
4. `c45e00f0` services (ai, connectors, memory, orchestration, voice, glasses-link already in 1). Removed: endpoint name (`ModelRouter` vision retry, `RoleRunner` reject line), inbox/file absolute path and source name (`RokidFrameInbox`), frame ids (`RokidFrameInbox`, `PipelineRecords`, `ExtractSave`), cluster id (`ClusterSave`), job id (`QueueDrainWorker`). Kept: role key enum names, counts, size, outcome enum names, model catalog id + fixed reason in `LocalModelDownloadWorker`.
   - `services:memory` gained `implementation(project(":core:model"))` (pure Kotlin module, no cycle) so its 4 files can use PcLog.
   - Voice module rules (`VoiceModuleRulesTest`) now allow the `core.log` import and scan `PcLog.` lines as log lines (the old `\bLog\.` regex would have silently stopped seeing them); `PhoneVoiceRulesTest` and `DbSecretRulesTest` scanners likewise match `PcLog.`.
   - Tests that read `ShadowLog` need the lines routed there: test-only `PcLogToShadowRule` (one copy in each of ai, connectors, memory, orchestration test sources) installs a sink that forwards to `android.util.Log` with diagnostics on, applied to the 11 affected test classes (this also keeps their "no secret in the log" assertions from passing vacuously).
5. new `LogContentScanTest` (phone): every `PcLog.d/i/w/e(` statement in all production modules is parsed; a message that interpolates `.message`, `.cause`, a frame/wire/pause/cluster/job id, a path, `.text`, question, prompt, url, token, secret, passphrase, pairing or an endpoint name fails. `theScannerCatchesEveryForm` pins the forms.

## Mutations (all caught)
- Raw `android.util.Log.i(...)` re-added to `Hub.kt`: `RawLogScanTest` fails ("Hub.kt has 1 (allowed 0)").
- `PcLog.i(TAG, "m ${...message}")` added to `Hub.kt`: `LogContentScanTest` fails naming the statement.

## Behaviour notes (human awareness)
- `Log.d` became `PcLog.d`: reaches logcat and file only while diagnostics are on (before: always logcat). Only `SceneIndexer` (2 lines) used D.
- I-level lines reach the FILE only while diagnostics are on (W/E always); this is the existing LogPolicy, so the hub/link/frame lines now show up in `pca.log` in a diagnostics-on window, which is what the last window lacked.
- Lines written through a `log(...)` lambda (e.g. `ContextHub`, `MinimisedLink`, `VoicePipeline`, `PushToTalk`) were reviewed by hand (counts, enum and state names, fixed phrases); the scan test only sees their final `PcLog` call.
- `ActionEngine`/`ReminderExecutor`/receivers still wrap the log call in `try { } catch (RuntimeException)` from the android.util.Log days; harmless, not removed (no logic change requested).

## Verification
Final full run (clean tests) of `:core:model`, `:core:capture`, `:services:*`, `:glasses`, `:phone`: green; the only failure was the known-flaky `PromptEditorTest` (green alone). `WiringEndToEndTest.publishFlowWiredEndToEnd` failed once inside a filtered run and passed alone (not logging related).

## Merge steps
`git merge feat/harden-c` into v0.9.5 (or the current train tip). Conflicts are only possible where another branch adds a raw `Log` call: use PcLog (the ratchet and `LogContentScanTest` will say which file). Build both APKs; no install done here.
