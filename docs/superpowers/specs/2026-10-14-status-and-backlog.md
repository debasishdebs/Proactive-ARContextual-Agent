# Status and backlog: everything decided and pending (2026-10-14, 22:50)

This is the single place to resume from after the conversation is compacted. Branch: `feat/proactive-mvp-implementation` @ `f82b82e`. Everything is local; **nothing has been pushed**. The last full gate passed on the combined build: 3 281+ tests, 0 failures. Both devices run the `f82b82e` build (phone + glasses, installed 22:40 with data kept; glasses overlay permission granted).

**Completeness:** this doc was cross-checked on 2026-10-14 against the conversation since the last compaction and against the device-run, devfix and review reports. Items from before that compaction (Phases 0–8) live in the phase ledgers under `.superpowers/sdd/archive/**/progress.md` (Ruling lines and deferred minors), not here. Treat those ledgers plus this doc as the full record.

## 1. What is built and merged

- **v1, Phases 0–8:** see `plans/2026-10-12-v1-master-plan.md` and the phase plans.
- **Agent flows:** Settings → AI → Agent flows. A flow list, a DAG view for each flow, and a step sheet with shared Prompt/Model/Retrieval tabs; the full-screen prompt editor asks "Discard changes?" on Back. Spec/plan `*-agent-flows*`.
- **On-device embeddings:**
  - llama.cpp JNI for arm64, b11618 @ 4dd6dd4, 16 KB-aligned, 4.5 MB;
  - the bge-small-en-v1.5 catalog;
  - a verified, resumable downloader;
  - Settings → AI → On-device models.
  - Spec/plan `*-on-device-embeddings*`.
- **Round 1–3 device fixes:**
  - memory scroll; timeline photos from earlier days; shopping delete + undo; checklist markers;
  - SQLCipher bind-args and FTS rebuild on a fresh connection;
  - quiet hours off ends tonight's pause;
  - HUD undo shown with the answer; auth-timeout retry; all-day calendar; flow view opens on the main column; SDK token-log mitigation;
  - backup never restores set-up state, plus OnboardingGuard; wizard key field; answer retry on a token-cap cut;
  - adaptive glasses VAD, late-tap grace, voice outcome logging;
  - test heap 2 GB with OOM exit.
- **Background capture:** a keep-alive CaptureService plus an in-process alarm receiver. Captures keep the camera while the HUD isn't in front (Android 12 while-in-use rule).
- **Glasses minimise:**
  - double-tap → 3 s "Double-tap again to exit" → minimise, otherwise pause; a fast 4-tap works;
  - a 48 dp countdown-ring icon in a corner;
  - pop-ups while minimised;
  - Confirm auto-open and re-minimise;
  - phone settings: icon corner, pop-up time (3–20 s).
  - Spec/plan `*-glasses-minimise-icon*`.
- **Minimised link:**
  - after the SDK's GLASSES_EXIT, the session-less CXRLink keeps frames, acks, settings and heartbeats flowing (state `MINIMISED`);
  - no appStart while minimised;
  - ContextHubService is the "Connected to glasses" foreground service.
  - **Device-verified 22:42–22:46:** frames every 30 s with the glasses minimised and the phone backgrounded.
  - Report: `.superpowers/sdd/minimised-link-report.md`.

## 2. In progress right now

- `feat/activity-sort-cue`, in worktree `../pca-sortcue`:
  - a sort icon on Activity: Latest first (the new default), Oldest first, A–Z, Z–A; remembered;
  - a "photo sent" cue: a brief ✓/pulse on the glasses timer and the minimised icon, plus "N photos today" on phone Home.
  - Report on finishing: `.superpowers/sdd/activity-sort-cue-report.md`. Then gate, merge into feat, and install.

## 3. Pending device checks (no code needed unless they fail)

**Minimised link** (script in `minimised-link-report.md`):
- (5) Reopen the app on the glasses: the link hands back to CONNECTED, and a resent frame is acked as duplicate, never ingested twice.
- (6) A real exit closes the link within about 10 s, with the CLOSED_ON_GLASSES banner.
- (7) Swipe the phone app from recents: "Connected to glasses" stays and frames keep arriving.
- (4) I2: while minimised, the phone never relaunches the HUD; anything that would open it sends a pop-up. The phone shows no "Open on glasses" button while minimised, which is expected.

**Minimise and icon** (spec §8):
- a phone ask's pop-up over Maps auto-hides after the pop-up time;
- a Confirm while minimised opens the HUD, and Yes/No/15 s returns to Maps with the icon;
- opening by hand during a Confirm keeps the HUD in front;
- a 2-tap then a wait toggles pause;
- changing the icon corner applies;
- §5: notifications and calls over the full HUD. A call did show on the drive, but notifications are not yet confirmed.

**Round 3 fixes:**
- glasses voice: soft speech, a 2 s pause mid-question, slow speech plus a tap, silence, a noisy room;
- encryption ON/OFF with data, plus `SqlCipherMigrationDeviceTest`;
- reinstall shows onboarding; the wizard key field;
- about 10 asks, counting INVALID_OUTPUT (with the stage and cut values in the "reply rejected" log).

**On-device models:** download bge-small, Use for Embeddings, the reindex completes, Retrieval "Try it" shows semantic scores, a meaning-only query matches, force-stop mid-download then resume, and `dumpsys meminfo` while embedding.

**Older leftovers** (`device-run-*-report.md`):
- D-A1 (glasses clock +2 days; the glasses clock was already manual, so decide what to restore it to);
- D-A2 (fill glasses storage);
- D-C2;
- D-C3 (Hi Rokid long-press);
- D-C4 (reinstall the glasses app 10×);
- S-ASK TTS via A2DP;
- P7-3 (auto-resume at +30 min);
- P7-4 (the next night gets a new pause id);
- P7-7 glasses-ask undo;
- D-E2 (gate 3× for stalls).

## 4. Open bugs and observations (not fixed)

| # | Item | Notes |
|---|---|---|
| B1 | **A phone call closed the minimised glasses app** (drive, about 22:1x) | Rokid OS behaviour during calls; logs rolled over. Reproduce with a call while minimised and capture glasses logcat. |
| B2 | Reminders fire up to 10 min late | `window=+10m` inexact alarm. Use an exact alarm (`SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`) for reminders. |
| B3 | The Rokid SDK logs the glasses auth token in plain text | Vendor issue. Note at `docs/rokid-sdk-token-logging.md`, ready to send to Rokid. Our log views and exports filter it. |
| B4 | An answer showed a raw ISO time ("2026-10-14T18:00"); "AM or PM?" was asked for "6" | Prompt and format polish for ANSWER. |
| B5 | 9 memories from 13 Oct hold raw JSON text | Probably an old build; one-off cleanup or leave. |
| B6 | After the phone app restarts, the Home countdown takes about 1.5 min to appear | The glasses' hello only arrives with the next frame. |
| B7 | If the phone process dies while the glasses are minimised, the restart may appStart once | Fixing it needs persisted MINIMISED state (minimised-link report "known gap"). |
| B8 | Debug-only link probe logging (`PcLinkProbe`) is on in debug builds | Decide: keep it as diagnostics, or remove it now that Option A is proven. |
| B9 | Flaky tests on the watch list | PromptEditorTest, DeleteAllFenceTest, PushToTalkTest, AppShellTest.leavingHome… |
| B10 | `android-companion/.kotlin/` and the root `window.xml` are untracked | Add `.kotlin/` to .gitignore; move or delete `window.xml`. |
| B12 | **HIGH, security: legacy secret-config backups are restored from Auto Backup** | Two files are on the phone after today's fresh install: `files/app_config_secret.xml.bak.1780057256` and `files/app_config_secret.xml.bak.manual_reset_1780057830`, about 1.3 KB each, each with 2 key/token/secret-like lines; contents never printed. The `files/` dir isn't excluded for these names, so an old secret config (possibly an API key) goes to cloud backup and comes back. Fix: (a) exclude `file:app_config_secret*` in every backup section; (b) delete these .bak files on startup, after the legacy-key retirement (P8 D1) has read them; (c) check the old Phase 8 D1 code path that created them. Treat the key inside as exposed: rotate it if it's a live key. |
| B13 | **Rokid AssistServer force-stops our glasses app** | "Force stopping com.rokid.context.glasses … from pid 2000" at 19:41:33; pid 2000 is Rokid's AssistServer. Folding the temples (`glassLegStatusChange spread[false]`) also force-stopped it in round 3. Likely the same mechanism as B1, the call. The keep-alive service doesn't survive a force-stop. Investigate whether Rokid offers a keep-alive or whitelist. |
| B14 | Delete all's armed-reminder cancel wasn't exercised on the device | The "Call Mom" reminder had already fired before round 3's Delete all. |
| B11 | Review minors deferred | In `.superpowers/sdd/archive/**/final-review*.md`, `.superpowers/sdd/ode/final-review.md`, `.superpowers/sdd/glasses-minimise/final-review.md`, `.superpowers/sdd/agent-flows/final-review.md`. |

## 5. Decided features not yet specced or built

1. **Faster, motion-aware capture.** The user recalled "every 3–5 s when idle, 1–2 s when moving". Today the minimum is 30 s, plus burst-on-change. This needs its own design: cost (an AI call per frame), battery and heat on the glasses, dedupe, and possibly a motion sensor on the glasses.
2. **Glasses asks while minimised (v2).** Taps go to the front app. Options:
   - map the Rokid AI key (the phone already receives `onAiKeyDown` / `onGlassAiAssistStart`);
   - a wake word;
   - a phone-side shortcut (notification action or QS tile).
   Spec: `glasses-minimise-icon-design.md` §7.
3. **multilingual-e5-small** as a second on-device model, about 120 MB. Add it after RAM is measured with bge-small.
4. **Connectors brainstorm** (the user's next big topic): Outlook, Slack, Gmail, Amazon, Flipkart, and more, to make the agent personal. Start with superpowers:brainstorming in a fresh chat.
5. **Release build:** minify and arm64-only to cut the APK size (debug is about 97 MB).
7. **The LiveFit-style HUD Display screen.** The user asked for "HUD settings like LiveFit". Scale and position already exist in Settings → Glasses, but LiveFit's **live preview** (the 3:4 glasses frame showing the HUD block at its size and position, with an Apply button) wasn't ported. Source: `live-fitness-tracker/phone/.../ui/settings/HudDisplayScreen.kt`.
8. **"Glasses as a nearby device" UI.** The behaviour is built: the phone foreground service runs while the glasses are connected. LiveFit also has a **Settings → Nearby devices** page ("start when nearby" per paired device). Decide whether the user wants that page too, or whether the behaviour is enough.
9. **Brief "photo taken" feedback on the glasses** is in progress (§2); confirm on the device that it answers "I feel nothing came".
6. **Future agent flows:** workout/food, work reminders, groceries and day-to-day tasks. The Agent flows registry is ready for more flows (one file each).

## 6. Rules to keep (from the user)

- **Secrets:** never print secrets. Checks print counts and names only, and logcat greps filter out "token" and "AuthorizationHelper".
- **Git:** never push; commit locally.
- **Speed:** fast, parallel execution. Use lean plans and worktree streams, and Opus/Sonnet only, never Haiku.
- **Devices:**
  - don't touch them while the user is wearing or using them, unless asked;
  - Bluetooth toggling and destructive device steps need an explicit OK each time;
  - never toggle Wi-Fi, since the phone is on wireless adb.
- **llama.cpp submodule:** never init it with `--reference` to a worktree that will be removed. Use a plain `git submodule update --init`.

## 7. Specs and plans index

- **v1:**
  - spec `specs/2026-10-12-v1-redesign-design.md`;
  - plans `plans/2026-10-12-v1-master-plan.md`, plus phase0 … phase8.
- **Agent flows:** spec `specs/2026-10-14-agent-flows-design.md`; plan `plans/2026-10-14-agent-flows.md`.
- **On-device embeddings:** spec `specs/2026-10-14-on-device-embeddings-design.md`; plan `plans/2026-10-14-on-device-embeddings.md`.
- **Glasses minimise/icon:** spec `specs/2026-10-14-glasses-minimise-icon-design.md`; plan `plans/2026-10-14-glasses-minimise-icon.md`.
- **Device runs and fixes** (git-ignored): `.superpowers/sdd/device-run-report.md` (round 1), `device-run-2-report.md`, `device-run-3-report.md`, `devfix-*-report.md`, `minimised-link-investigation.md`, `minimised-link-report.md`, `glasses-minimise-report.md`.
- **Checklist:** `docs/screens/checklist-20261014.txt`; script `tools/device-checklist.sh`; gate watchdog `tools/gate.sh`.
- **Backups:** `~/Desktop/rokid/backups/pca-phone-20261014-{1551,1649,1812}.tar`. They predate the reinstall and can't be restored onto the current install.
