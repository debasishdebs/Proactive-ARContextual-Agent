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
- Codex combined final re-check of all fixes since their last review: RUNNING (result appended below by the loop).

## NEEDS YOU (nothing else is blocked)
1. When you take the glasses off: plug them in, tell me; I pull traces with `tools/handover.sh` and read the state line. (No trace exists for the hours you wore them after 12:45 and before 14:40.)
2. M1 gate rule decision: I recommend <=2% failing windows (each classified) + shadow-vs-admissions parity; today M1 fails on both real traces (live-loop timing blips).
3. Heartbeat freshness limit in the hand-over tool: 60 min chosen, OK?
4. WP13 live steps 5-7 (kill-on-deletion through the supervisor, B2 frame run in the browser, device run) need your phone, a helper pairing code and your confirm taps: say "start helper".
5. Not built, by design/decision: vision-extract -> Python adapter; declined vs expired shown separately; stored Moment tables/sensors/Scenes (55-87 h later); two older files still hold android.util.Log (CanvasPublisher, MemoryWipe: logging ratchet); GlassesRuntime can still construct the recorder lazily from Main (reads no file).

## Usage note
Monthly credits: ~$349 left on Oct 24 morning (see memory note). Start a fresh chat for the next big step.
