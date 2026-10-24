# MORNING (2026-10-24): what is ready, what needs you

Branch `v0.9.1` (integration). Phone debug APK: `android-companion/phone/build/outputs/apk/debug/phone-debug.apk` (built 12:39, includes Moment-lite merge 5bae5e6a; rebuilt after the two Moment fixes land).

## See the canvas in the browser (needs you, ~10 min)
1. Attach the phone (wireless adb is fine). Not during a call. Tell me "go": I take the phone backup first (`~/Desktop/rokid/backups/pca-phone-pre-*.tar`), then `adb install -r` (never uninstall: it deletes the SQLCipher key).
2. Phone: Settings > Diagnostics > Developer mode ON; Canvas screen: "Use from a laptop" ON (same Wi-Fi). The phone shows host:port and a pairing code.
3. Browser on the laptop: open the shown address, enter the code. You get the canvas editor: add steps from the palette (now includes "Python (experiment)" and "Moments" input), pick frames or Moments on the run page.
4. Python experiments additionally need the laptop helper + the Podman VM (stopped now): say "start helper" and I start the `pca` machine inside the lock owner, then `pca-helper run HOST:PORT FINGERPRINT CODE` pairs and supervises. Each run needs a confirm tap on the phone.

## Done since yesterday evening (all merged on v0.9.1, Codex-reviewed unless noted)
WP4-WP13 code, D1 float results (exact-text hash), Python node in the palette, logging chain LGC4P-LGC10 (always-on diagnostic recording, cleanup fence, space policy, hand-over tool `tools/handover.sh`), replay stall model + M1 criterion, Moment-lite (`input.moments`).
WP13 LIVE gate: 16/16 PASS on podman 6.1.4 (new machine `pca`, 10 GB; `podman-machine-default` untouched; both stopped). Evidence: `spikes/wp13-live-gate.md`, `images.lock`.

## NOT done / needs a decision
- Moment-lite Codex fixes (2 Important: memory `type` not in the freshness fingerprint; >2000 photos/second truncation) are in progress; until merged do not rely on Moment freshness.
- Glasses install: NOT done. Needs your OK + phone backup + no call. Glasses code (LGC5-8, 10) has Codex fixes merged but no device validation yet; install after you have seen the canvas.
- Live steps left for WP13: kill-on-deletion timing through the supervisor, B2 frame run in the browser, device run (all need phone + you).
- Decisions waiting: M1 gate rule (I recommend <=2% failing windows, classified, + shadow-vs-admissions parity; today M1 fails on both real traces: live-loop timing blips); heartbeat freshness limit in handover tool (60 min chosen); vision-extract -> Python adapter (skipped); declined vs expired shown as one message.
- Minor follow-ups: doctor prints `ok vm disk (disk_low...)` wording; two files still hold android.util.Log (CanvasPublisher, MemoryWipe); `GlassesRuntime` can still construct the recorder lazily from Main (reads no file now).
- Tests: full run on v0.9.1 passed all 7 modules at 04:33 (before the last merges); the last full run (5bae5e6a) passed its test tasks and stopped only at the APK step (llama.cpp submodule missing in the worktree: fixed by copying sources).

## Usage note
Monthly credits: $349 left until Nov 1. This session ~$280+. Start a fresh chat for the next step; the long context makes every turn expensive.
