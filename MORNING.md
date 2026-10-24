# UPDATE 2026-10-24 17:30 (branches changed)
- `v0.9.1` = the build INSTALLED on your devices at 12:45 (6adc222f) plus two bug fixes (WP8 missing-executor, doctor wording); worktree `~/Desktop/rokid/pca-v091`.
- `v0.9.2` (worktree `~/Desktop/rokid/pca-int1`, tip 52463001) = everything new since: 3c E1 guards (graph model, boxed editor, glasses runner, replay parity), Moment phase 1 (Room v9) and the Moment blocks (Room v10: trace clock fix and backfill, MomentObject/audit/derived fields, builder trigger), M1 gate (default 3%, cap 5%) and the wear-window hand-over. Codex-reviewed with fixes merged (the 3c E1 fixes and the last Moment fixes had no final re-check).
- Final full run on v0.9.2: all modules green except the known flaky PromptEditorTest (passes alone). APKs of the tip: phone `android-companion/phone/build/outputs/apk/debug/phone-debug.apk` (17:27); glasses `.../glasses/build/outputs/apk/debug/glasses-debug.apk` (17:05, no glasses change since). NOT installed: needs your OK, a phone backup, no call. The installed build is v0.9.1.
- Design docs for you to approve (docs/superpowers/specs/): `2026-10-24-moment-scene-design-decisions.md`, `2026-10-24-3c-expression-editor-design-decisions.md`.
- Tomorrow: outbox drainer, sensor features, Scene tables + `input.scenes`, 3c E2/values, X7 worn SHADOW gate with you, the three WP13 live steps, release bookkeeping.

---

# MORNING (2026-10-24): what is ready, what needs you

Branch `v0.9.1` (integration). Phone debug APK: `android-companion/phone/build/outputs/apk/debug/phone-debug.apk` (rebuilt 12:43 with the Moment fixes; INSTALLED on the phone 12:45 and the glasses APK too, after backup `backups/pca-phone-pre-v091-20261024-1245.tar`).

## See the canvas in the browser (needs you, ~10 min)
1. Attach the phone (wireless adb is fine). Not during a call. Tell me "go": I take the phone backup first (`~/Desktop/rokid/backups/pca-phone-pre-*.tar`), then `adb install -r` (never uninstall: it deletes the SQLCipher key).
2. Phone: Settings > Diagnostics > Developer mode ON; Canvas screen: "Use from a laptop" ON (same Wi-Fi). The phone shows host:port and a pairing code.
3. Browser on the laptop: open the shown address, enter the code. You get the canvas editor: add steps from the palette (now includes "Python (experiment)" and "Moments" input), pick frames or Moments on the run page.
4. Python experiments additionally need the laptop helper + the Podman VM (stopped now): say "start helper" and I start the `pca` machine inside the lock owner, then `pca-helper run HOST:PORT FINGERPRINT CODE` pairs and supervises. Each run needs a confirm tap on the phone.

## Done since yesterday evening (all merged on v0.9.1, Codex-reviewed unless noted)
WP4-WP13 code, D1 float results (exact-text hash), Python node in the palette, logging chain LGC4P-LGC10 (always-on diagnostic recording, cleanup fence, space policy, hand-over tool `tools/handover.sh`), replay stall model + M1 criterion, Moment-lite (`input.moments`).
WP13 LIVE gate: 16/16 PASS on podman 6.1.4 (new machine `pca`, 10 GB; `podman-machine-default` untouched; both stopped). Evidence: `spikes/wp13-live-gate.md`, `images.lock`.

## STATUS (updated 2026-10-24 ~15:00, loop running)
- INSTALLED (12:45): phone + glasses debug builds of v0.9.1. Glasses app was not running after the install until 14:39; launched both apps then. Glasses recording confirmed 14:40 (`trace state: ... flagState=enabled rec=1 starts=1`). Canvas paired with the laptop at https://192.168.68.100:8765 (fp 46:45:42:0A); browser pairing is yours (new code from the phone).
- Full test run on v0.9.1 tip 6adc222f: all 7 modules green except one known flaky test (LocalModelsPageTest, passes alone). Later small commits: 821581ec (WP8 missing-executor), 2876b163 (doctor wording) with their own tests.
- Codex combined final re-check of all fixes since their last review: VERDICT READY, no Critical/Important. One Minor left open: Moment newest-N can underfill when a recent second holds very many photos (MomentResolver.kt ~47, fetch is capped at 2000 frames before choosing distinct seconds; fix = paginate).

## NEEDS YOU (nothing else is blocked)
1. DONE 15:30-15:40: glasses plugged in, wear window 14:43-15:28 pulled with `tools/handover.sh` (trace 5814127b, PARTIAL, 15:02-15:32, 29.6 min: capture was PAUSED 14:43-15:02, so no earlier trace is expected; state lines name every blocker). Import + M1 replay of it still needs a fresh pairing code each (single use, ~1 min): `m1-run.sh pair ...; m1-run.sh pull <dir> --allow-partial; m1-run.sh replay`. The phone refuses PARTIAL traces without `--allow-partial`.
2. DONE (your decision): M1 gate = failing windows <= T% (default 3, hard cap 5, `--max-fail-pct` / M1_MAX_FAIL_PCT) + shadow-vs-admissions parity. Both real traces pass at 3% (1.7% and 1.5%). Failure classification prints `unclassified` until the replay report carries `baseFailKinds`.
3. DONE (your idea): hand-over pulls only the WEAR WINDOW (last unplug by heartbeat `chg` to the next plug-in/end); no fixed age limit; window with no heartbeat is rejected.
4. WP13 live steps 5-7 (kill-on-deletion through the supervisor, B2 frame run in the browser, device run) need your phone, a helper pairing code and your confirm taps: say "start helper".
5. Not built, by design/decision: vision-extract -> Python adapter; declined vs expired shown separately; stored Moment tables/sensors/Scenes (55-87 h later); two older files still hold android.util.Log (CanvasPublisher, MemoryWipe: logging ratchet); GlassesRuntime can still construct the recorder lazily from Main (reads no file).

## Replay of today's trace (5814127b, imported 15:50)
Failing windows 1/116 (0.86%, gate 3%: PASS). Shadow parity vs glance admissions: 73 vs 68 (+5, limit +-4): FAIL by one open; likely heartbeat-boundary noise (partial heartbeats at the span edges). Parked by decision: optional edge-aware shadow counting (count only heartbeats fully inside the trace span).

## Found and fixed this afternoon
- handover.sh read the trace end time from the wrong place (real manifests are a b64 envelope): fixed 855a7152, tests now use the real format. `--allow-partial` now passes through to the import (4faee104).
- First on-device proof of the always-on logging chain: `trace boot/state` lines name every blocker (gate_off -> enabled, capture_blocked while paused, not_quiet, recovering, already_recording). A killed trace is sealed PARTIAL at the next start (killedTraces=1) and recording resumes in seconds.

## Usage note
Monthly credits: ~$349 left on Oct 24 morning (see memory note). Start a fresh chat for the next big step.
