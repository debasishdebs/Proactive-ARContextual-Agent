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
5. `LogContentScanTest` (phone), strict BY CONSTRUCTION (Codex fix 1). Every `PcLog.d/i/w/e(tag, msg)` message must be ONE string literal; a template may interpolate only an expression that passes `safeExpr` (counts `.size/.length/.count()`, `javaClass.simpleName`, `SafeLog.describe(x)`, `WearingStatus.describe(x)`, and a short reviewed list of count/enum/state names in the test). Refused: `+` concatenation, `.format(`, `String.format`, a variable message built elsewhere, `.message`, `.localizedMessage`, `.cause`, `stackTraceToString`, `toString()`, any non-listed interpolation. Only reviewed forwarders (`{ PcLog.i(TAG, it) }`, listed per file in `FORWARDERS`) may pass a variable. The text those forward comes from injected one-argument `log(` / `warn(` calls, scanned with the lighter rule (one literal, no ALWAYS_BAD form, no DENY name such as id/text/question/prompt/url/token/secret/pairing; plus five reviewed variable messages in `SINK_VARIABLES`). `theScannerRejectsEveryBypassForm` pins 20 bypass forms; `theScannerAcceptsTheSafeForms` the good ones.
   Offenders found by the stricter scan and migrated: `FrameImages` (`"..." + (e?.let ...)` now two literals), `LocalModelDownloadWorker` (helper `log(id, reason)` renamed `modelEvent(catalogId, reason)`), `GlassAppWatcher` (`wear check` value bound to a name first), `GlassesWatchdog` (`.trim()` builder replaced by one literal).
6. Diagnostic consumers (Codex fix 2). Every consumer of log text was searched (`tools/*.sh`, `tools/trace-lab`, `tools/canvas-helper`, `handover.sh`, `m1-run.sh`, `battery-run.sh`, tests, docs/spikes, specs). Only `tools/device-checklist.sh` greps application log text (handover/m1/battery read heartbeat/status lines, which were not touched; `GlassesPausesTest` asserts `pause added src=...`, untouched). Markers changed in `device-checklist.sh`:
   - `P|frame ingested ` (trailing space) -> `P|frame ingested` (steps S-CAPTURE and others, lines 83, 107, 115, 131).
   - `!P|frame .* not stored` -> `!P|frame not stored`.
   - `P|dropped: captured before Delete all` (D-A4) -> `P|frame dropped: captured_before_delete_all` (this one was ALSO broken by batch 1 and was found by the new test).
   - `P|append result=true frameId=rokid_phone_` (P7-10) -> `P|append result=true origin=phone`; the log line is now `append result=<bool> origin=phone|glasses` (stable, id-free; derived from the frame source `rokid_phone`).
   Unchanged and verified present: `link=CONNECTED`, `hello battery=`, `phone pause added`, `phone resume for `, `paused locally`, `resumed N pause`, `session closed GLASSES_EXIT|USER_CLOSED`, `connect failed`, `undo asked`, `delivered `, `flush done`.
   New `DeviceChecklistMarkersTest` (phone): parses every positive `P|`/`G|` marker (each `|` alternative) and requires some production log literal, with its `$` interpolations filled by plausible values, to match it, so a rewritten line fails the test instead of a device run. `theCheckCatchesARewrittenLine` pins the trailing-space and the id-suffix failures.

## Mutations (all caught)
- Raw `android.util.Log.i(...)` re-added to `Hub.kt`: `RawLogScanTest` fails ("Hub.kt has 1 (allowed 0)").
- Bypass forms re-added to `Hub.kt` (all fail `LogContentScanTest`, each named): `"x" + e.message`, `"%s".format(e.message)`, a variable message `built`, `"${e.message}"`, `"${e.stackTraceToString()}"`, `"${x.toString()}"`, `String.format(...)`.
- Old checklist markers restored in `device-checklist.sh` (`frame ingested ` with the space, `append result=true frameId=rokid_phone_`): `DeviceChecklistMarkersTest` fails and names both.

## Behaviour notes (human awareness)
- `Log.d` became `PcLog.d`: reaches logcat and file only while diagnostics are on (before: always logcat). Only `SceneIndexer` (2 lines) used D.
- I-level lines reach the FILE only while diagnostics are on (W/E always); this is the existing LogPolicy, so the hub/link/frame lines now show up in `pca.log` in a diagnostics-on window, which is what the last window lacked.
- Lines written through a `log(...)` lambda (e.g. `ContextHub`, `MinimisedLink`, `VoicePipeline`, `PushToTalk`) were reviewed by hand (counts, enum and state names, fixed phrases); the scan test only sees their final `PcLog` call.
- `ActionEngine`/`ReminderExecutor`/receivers still wrap the log call in `try { } catch (RuntimeException)` from the android.util.Log days; harmless, not removed (no logic change requested).

## Verification
Final full run (clean tests) of `:core:model`, `:core:capture`, `:services:*`, `:glasses`, `:phone`: green; the only failure was the known-flaky `PromptEditorTest` (green alone). `WiringEndToEndTest.publishFlowWiredEndToEnd` failed once inside a filtered run and passed alone (not logging related).

## Merge steps
`git merge feat/harden-c` into v0.9.5 (or the current train tip). Conflicts are only possible where another branch adds a raw `Log` call: use PcLog (the ratchet and `LogContentScanTest` will say which file). Build both APKs; no install done here.
