# Session log 2026-10-14: what was done, decisions and why, probes and results

Companion documents:
- `2026-10-14-status-and-backlog.md`: current state and pending work;
- `2026-10-14-decision-log.md`: rulings, probes and test evidence from all phase ledgers and reports, Phases 0–8 included.

This log covers the work done after the earlier compaction, in chronological order. Everything is committed locally on `feat/proactive-mvp-implementation`; nothing has been pushed.

## 1. Phase 7 close-out and the P7 → P8 merge

**Done:**
- `cdd84ce`: a Forget cancels a redacted reminder's armed alarm, and a fired alarm re-reads its proposal and posts nothing unless the proposal is EXECUTED and not redacted.
- `0f8f0dd`: P7 merged into P8.
  - `RokidFrameInbox` was a real semantic conflict and was hand-merged: P8's wipe/cancel handling (`InboxResult`) was kept, and P7's `placeLabel` plus returned frame ids were added. The `appendFrame` lambda stays last so P8 tests' trailing lambdas compile.
  - `ContextDatabase` keeps both sides' DAOs.

**P7×P8 integration fixes:**
- `6fa7d07`: Delete all redacts EXECUTED/EXECUTING/FAILED proposals and cancels their alarms.
- `52cc0f9`: `OriginFencedIntake`. A proposal stored after its ask or nudge was wiped or forgotten is redacted.
- `c74bb76`: phone-capture `ingestSinglePath` honours the wipe fence.

**Decisions:**
- Shopping items and notes are **kept** on Delete all. Why: this matches Phase 7 Forget; they are user-owned outputs.
- Non-open proposals are **redacted, not deleted**. Why: calendar undo still works.
- action_log keeps its transitions but loses its details.
- Spec §6.1.1 ("Delete all = Forget moment for every frame") was the authority for these.

## 2. Probe audio licence

The user decided to remove the macOS `say` clip, because its licence is personal and non-commercial.

**Done (`64de2b0`):** `voice_probe.pcm` is replaced by LJSpeech clip LJ001-0013 ("than in the same operations with ugly ones."), which is **public domain**. It was resampled from 22.05 to 16 kHz mono PCM16 and padded with silence to the **same 134 142 bytes**, and a `voice_probe.SOURCE.txt` note was added.
- Why the same length: the probe's byte math and the pinned tests stay valid. Only the SHA test changed.
- Why not ElevenLabs: the free tier is non-commercial with attribution, and the paid tier is needed for commercial use.
- Why not a Piper voice: voice licences vary. LJSpeech is a real human voice and public domain.
- The old clip was never pushed.

## 3. Phase 8 Stream E + the final whole-branch review

**Done:**
- E1: `AllRoutesSmokeTest` (32 routes) and `HudFixturePagesTest` (15 cases); no crashes found.
- E2: `tools/gate.sh` watchdog. It stalls out after 480 s with a jstack and exits 124.
- E3: `tools/device-checklist.sh` with 35 steps, `docs/screens/{phone,hud}`, `tools/relocate-legacy-screens.sh`, and `/docs/screens/legacy/` git-ignored.

**Final review:** 4 parallel Opus reviewers (data, phone, device, AI/connectors) found 1 Critical and 9 Important issues. All were fixed in 4 parallel streams:
- **C1:** retention expiry was scrubbing EXECUTED reminders. Ruling: retention is storage hygiene; it leaves EXECUTED/EXECUTING proposals, params and alarms alone. A user Forget and Delete all still redact and cancel.
- **Batch attribution:** facts were attributed to the newest frame while the model saw the oldest three. Fix: `VISION_EXTRACT` now runs **once per photo**, up to 3 calls per batch. Why: Forget has to remove exactly the right facts. Cost: up to 3× the AI calls.
- **forget_tombstone:** it was never pruned. A tombstone is now pruned when there's no frame row, no memory referencing it, no JPEG, and it's older than 8 days. Why 8 days: the glasses' 7-day TOO_OLD window plus 1 day for queued work. The sweep is now set-based.
- **ActionEngine:** execution is NonCancellable after the CAS. Why: leaving the Ask screen mid-run left proposals stuck in EXECUTING.
- **Notifications off:** the reminder is kept, and the card shows "Added, but notifications are off" with a **Turn on** button. Why: the alarm still works once notifications are re-enabled.
- **Glasses:** a tap while recording always sends and never votes. A Confirm waits under Listening, and its 15 s window restarts when it becomes visible. A phone toast is shown over Listening.
- **Phone:**
  - a tested onboarding preset replaces only seeder-shaped bindings; edits made in Settings are kept;
  - Capture now uses the hub's QuietWindow occurrence math;
  - Hub pause/resume sends on the hub scope.
- Merged as `b961170`.

## 4. Agent flows (new feature)

**User intent:** show agentic steps as DAGs and make prompts and model tuning intuitive; also a full-screen prompt editor.

**Decisions and why:**
- **Scope A:** only the steps that exist today; new agents come later. Why: stability.
- **A list of flows with multi-select** instead of one canvas. The user's reason: future agents (fitness, work, groceries) would make one canvas too heavy.
- **"AI steps" includes retrieval and future ML models.** This led to facets: Prompt / Model / Retrieval / Custom.
- **Review feedback adopted:**
  - the graph matches real execution (the conditional critic, reprocess, nudge after the summarizer);
  - a side-input alignment pass in the layout;
  - the SYSTEM prompt save exception is preserved;
  - Model and Retrieval are labelled **shared**;
  - placement is Settings → AI only.
- **The Nudge propose edge label** is "if shouldNudge and proposedAction". Why: that's what the code does.
- **Back in the full-screen editor** asks "Discard changes?" when the text changed.
- **Final-review fixes:** skip-layer edges route around nodes, labels sit on their own edge, and prompts shared by several steps get a "shared by N steps" label.
- **Reuse:** the LiveFit generic `ListScreen` was ported, with multi-select added. Neither app had DAG code, so a small layered layout plus Canvas was written.

**Merged as `4cb770c`.** On the device (round 2): "shared by 2 steps", the Retrieval tab and "Discard changes?" were all confirmed.

## 5. Device run 1 (overnight, 01:15–02:33): agent-driven over adb

**Results:**
- 15 PASS (some partial), 2 PARTIAL, 1 FAIL, 17 SKIP (these need the user).
- PASS: link, capture, ask, pause, the back-gesture fix (`f92642a`), confirm with simultaneous phone and HUD response (exactly one outcome), Capture now, nudge, Forget fact/moment.

**Bugs and fixes (`739d55f`, devfix/batch1):**
- **Memory detail drag-on-photo didn't scroll.** Fix: `canPan = scale > MIN`.
- **Timeline "No photo" for earlier-day photos.** Fix: rows look up the frame by id. Ruling: rows show **photo time**, with an "Earlier days" section. This later caused the user's sort confusion, which led to the sort icon (§13).
- **Checklist markers:** a success log `reminder armed; code=NONE` was added.
- **Shopping:** swipe-to-delete already existed but was invisible, so an icon, a hint and Undo were added. A double-delete bug was also found and fixed.

**User feedback during the run:** tap the phone mic before the Mac speaks. Bedtime mode closed the app at about 02:00; it was relaunched and the run continued.

## 6. Device run 2 (15:49–17:04) + the round-2 fixes

**Results:** 14 PASS, 1 PARTIAL, 1 FAIL, 9 SKIP.
- PASS: Agent flows on the device, the memory-scroll fix, Today photos, offline pause, P7-2, S-FLUSH (9 buffered, exact), P7-8, P7-4, D-C1, D-A5 and P7-9.

**Fixes:**
- `571cdaf` (r2a):
  - **encryption:** `rawQuery` got `arrayOf<Any>()` without the spread operator, so it became one bind argument and threw "Too many bind arguments". Log reason codes were added.
  - **quiet hours:** turning the feature off ends the running pause; the Privacy screen dedupes it.
- `4438169` (r2b):
  - **HUD:** shows the answer together with "Added · double-tap undo". Ruling: phone-typed asks show the undo too, because the spec ties it to origin ASK.
  - **Auth timeout:** retries 3× (2/5/10 s); a real decline is never retried.
  - **All-day events:** shown as UTC dates and listed after timed events while timed ones remain today.
  - **Flow view:** opens on the main column.
  - **SDK token logging:** the SDK has no off-switch, so `setLogLevel(ERROR)` is called anyway and the vendor note `docs/rokid-sdk-token-logging.md` was written.
- **Backups** taken before destructive steps: `~/Desktop/rokid/backups/pca-phone-20261014-{1551,1649,1812}.tar`.

## 7. Gate stall diagnosis

**Probe:** the `tools/gate.sh` watchdog caught a stall. The Robolectric worker JVM didn't respond to jstack, which points to GC thrash on Gradle's 512 MB default heap.

**Fix (`d137b17`):** `maxHeapSize = 2g`, `HeapDumpOnOutOfMemoryError`, `ExitOnOutOfMemoryError`. Why: a repeat now fails fast with an explanation instead of hanging. The gate passed afterwards.

## 8. Reboot reminder check (P7-6)

The alarm survived the reboot: the boot receiver re-armed it for 18:00 with `window=+10m`. It fired at about 18:10 and a reminders-channel notification was posted. **Finding:** the inexact alarm can fire up to 10 min late (backlog B2).

## 9. Device run 3 (18:11–18:55) + the round-3 fixes

**Results:**
- D-A4 PASS: 4 buffered frames were dropped as "captured before Delete all".
- P7-12 PASS: everything zeroed.
- Fresh install done. The user allowed losing the keystore and pasted the Azure key in Settings.
- Typed undo with the answer PASS; the flow graph opens on the main column PASS.
- D-D4 FAIL: the encryption rebuild failed at `REBUILD_INDEX`.
- Glasses voice: mostly failed.

**Bugs and fixes:**
- `e77c4f0` (r3a):
  - **BUG-R3-1:** Auto Backup restored onboarding.xml (complete=true). Fix: exclude set-up prefs in all backup sections, plus an `OnboardingGuard` that clears the flag if no usable ANSWER endpoint exists and the DB is empty. Ruling: plain user preferences stay restorable.
  - **BUG-R3-2:** a masked key field for the keyless seeded Azure endpoint, and the manual Azure form prefilled from EnvDefaults.
  - **D-D4:** `sqlcipher_export` doesn't register the FTS4 table in the exporting connection's schema, so the rebuild now runs on a fresh second connection. Not yet confirmed on the device.
  - **OBS-R3-1:** INVALID_OUTPUT on about a third of answers. The spec's single retry already existed. Likely cause: `max_completion_tokens 500` on the gpt-5.4 reasoning deployment, so cut replies were retried with the same cap. Fix: default cap 4096, and a retry with double the cap (max 8192) when `finish_reason=length`. A null-content refusal and blank answers are now handled too.
- `2e49c18` (r3b), **BUG-R3-3, glasses voice:**
  - **VAD:**
    - speech threshold `max(80, 3 × noise floor)`, with the floor measured as the median of the first 300 ms;
    - trailing silence 2.2 s;
    - a tap within 1.5 s after an auto-end counts as the send;
    - every voice outcome is logged with a reason code.
  - **Why:** the old fixed 300 was never reached by soft or Mac-speaker speech, and the 1.5 s silence cut questions after 1–2 words.
- **Environment notes:** Rokid force-stopped the glasses app on a temples-folded event; the Rokid assistant took the AI key once (from an agent keyevent).

## 10. On-device embeddings (new feature)

**User decisions:**
- Use **bge-small-en-v1.5 first**; add multilingual-e5-small later, since the user is unsure how ~120 MB of RAM behaves.
- Add a **model downloader page**.
- Install the NDK and CMake (they're additive and break nothing).

**Probes and facts:**
- The pinned model URL was checked live: HTTP 200, 36 806 944 bytes, the sha256 recorded in the spec, repo commit `d32f8c0…`, MIT licence.
- NDK 27.2 isn't in the SDK channel, so **NDK 28.2.13676358** was pinned. It also gives 16 KB page alignment for Android 15.
- The real-model host check: SHA matches; 384 dims, norm 1.0; our JNI output is identical to llama.cpp's `llama-embedding`; the GGUF says CLS pooling.
- The `.so` is 0x4000-aligned.

**Decisions:**
- llama.cpp b11618 (`4dd6dd4`) as a submodule; `c++_static`; baseline armv8-a.
- Native null returns instead of throws.
- The endpoint id is sanitised to `local_bge-small-en-v1_5-q8`.
- An https-only download client that follows redirects. Why: the Hugging Face URL redirects to its CDN.
- WorkManager input is the model id only.
- The embed model is cached process-wide and released after 60 s idle.
- **Review fixes:**
  - `-O3` in every variant; embeds go 8 texts per call, each with its own timeout;
  - an embed-context OOM counts as a load failure;
  - the metered switch re-enqueues with REPLACE, keeping the `.part`;
  - the stopped-writer race is fixed with call cancel, a per-read stop check and a per-model mutex.
- **APK:** pinning `ndkVersion` in the apps made AGP strip native libraries (llama_jni 67.5 → 4.5 MB). The debug APK is about 97 MB, mostly unminified dex.
- Merged as `7d37580`.

## 11. The drive feedback and the background-capture fix

**User reports:**
1. A double-tap did nothing visible.
2. The user wants an icon instead of the big HUD.
3. "Open on glass" pushed Maps into the background.
4. Notifications came only as voice.
5. Nothing reached the phone during the drive.
6. Nothing was captured after the HUD was minimised over adb.

**Probe:** the glasses logcat said "Foreground service started from background can not have location/camera/microphone access". The CaptureService was restarted per capture from an alarm, so on Android 12 every background start was denied the camera.

**Fix (`1f09708`, merged `a75ae2d`):**
- CaptureService is started once from MainActivity (`ACTION_KEEP_ALIVE`) and kept in the foreground.
- The alarm is a broadcast to `CaptureAlarmReceiver`, which calls the live service in-process.
- The fallback starts the service the old way.
- **Device-verified:** captures while the launcher was in front (DUPLICATE skips prove the camera delivered frames), and later `buffered` while minimised.

**Capture cadence:** the user recalled 1–2 s / 3–5 s. The v1 minimum is 30 s plus burst-on-change. **Decision:** leave it; a motion-aware design comes later (backlog). The user set 30 s for the drive.

## 12. Glasses minimise, icon and pop-ups (new feature)

**User decisions:**
- **Double-tap gate:** a double-tap shows "Double-tap again to exit · 3"; a second double-tap within 3 s minimises; otherwise pause toggles. This applies on all pages; the transient overlays keep their own double-tap.
- **Simpler UX:** the app always opens as the full HUD, and the icon appears **only after minimising**. There's no icon-mode setting.
- **Minimised icon:** a corner ring that counts down to the next photo.
- **Pop-ups:** answers and nudges show as notification-like pop-ups whose duration is set on the phone.
- **Confirms auto-open the app** and then return to where the user was. This is a deliberate, recorded exception to spec §1.7.
- **v2:** glasses asks while minimised, via the Rokid AI key, a wake word, or a phone shortcut.

**Facts:** the glasses run Android 12 (API 32); another app (Alipay) holds SYSTEM_ALERT_WINDOW, so overlays are allowed. **An appops grant has no effect until the permission is declared in the manifest.** That was found on the device and then fixed.

**Done:**
- `0235b1d`: the gate;
- `0cac4d9` and `84c692b`: settings fields and phone rows;
- `a9c2480`: the icon and the manifest permission; this build was installed for the drive;
- `5f9c720`: pop-ups and Confirm auto-open.

**Rulings:**
- tests were written after the code for Task 3 (time pressure);
- the gate arms under plain toasts;
- the ring is driven by `armed_at_ms`;
- nudge pop-ups show unless verbosity is Silent;
- "Open on glasses" detection works within 5 s;
- a 600 ms vote guard after auto-open;
- the icon and pop-ups are Android Views.

**Review fixes:**
- **I1:** a fast 4-tap was swallowed by the detector's 600 ms settle. Fix: while the gate is armed, a same-kind second double-tap passes (`d74e54b`).
- **I2:** moved to the minimised-link work.
- Merged as `d1bf157`.

## 13. Minimised link + "glasses as a nearby device"

**Investigation result:** the phone-side SDK `CxrSessionImpl` closes our session with `GLASSES_EXIT` whenever another glasses app comes to the front. There's no flag to stop it, and the session can't be revived. The glasses kept sending; the phone wasn't listening.

**Decision: Option A.** Keep the session-less `CXRLink` that the watcher already binds and use it as the data link (state `MINIMISED`), with no `appStart`. The probe was built first.

**Probe result (22:42, on the device):** `rx pc_frame`, `tx pc_frame_ack result=0`, `tx pc_settings result=0`, and a `pc_state` heartbeat about every 10 s, all with the launcher in front. **Option A works.**

**Also:**
- `ContextHubService` is reused as the "Connected to glasses" foreground service, which is the user's LiveFit nearby-device idea.
- While MINIMISED, "Open on glasses" sends a pop-up instead of `appStart` (review I2).
- Decision: the phone assumes MINIMISED at once and drops to CLOSED_ON_GLASSES if it hears nothing for 10 s. Why: the SDK reports minimise and exit identically.

**Device-verified:** frames ingested every 30 s with the glasses minimised and the phone on its home screen, and the service `isForeground=true`. Merged as `f82b82e`.

**User drive-2 feedback, explained:**
- The phone had the old build, which caused "open now", settings not syncing, and nothing visible arriving.
- But **87 photos did reach the phone and were processed** (316 TRUSTED summaries).
- The perceived "nothing came" was down to:
  - Activity sorted oldest first;
  - the Home feed showing only answers and nudges;
  - the countdown resetting instantly with no cue.
- **Decisions that followed:** a sort icon (Latest first by default) and a "photo sent" cue (§2 of the backlog, in progress).

## 14. Tooling incidents

- **Submodule breakage:** initialising with `--reference` to a worktree that was later removed broke the main checkout. It was repaired by moving the module aside and re-cloning (`rm -rf` was refused). A memory was saved.
- **A swept commit:** `git commit -am` once swept the device agent's in-progress checklist lines into a docs commit. They were checked: results and paths only, no secrets.
- **Secret handling:** every screenshot set was checked visually for keys, and none showed any. The Rokid token log line is filtered from every log read.

## 15. Decision confidence

**H** = backed by device evidence or a binding spec rule. **M** = sound reasoning, but not yet proven on the device or a judgement call. **L** = a guess that is cheap to change. Each row lists what would change it.

| Decision | Conf. | Why | What would change it |
|---|---|---|---|
| Keep-alive CaptureService + in-process alarm receiver | **H** | The Android 12 while-in-use rule is documented; background captures were verified on the device | Rokid force-stops (B13) kill the service: needs a keep-alive/whitelist answer |
| Minimised link via the session-less CXRLink (Option A) | **H** | The probe passed on the device: frames, acks, settings and heartbeats while minimised | Rokid SDK update changes `CXRLink` routing |
| MINIMISED is assumed at once, with a 10 s silence fallback to CLOSED | **M** | The SDK reports minimise and exit identically; 10 s is a guess | Real exits hold the link too long, or flaky BT causes false closes |
| ContextHubService as the "nearby device" foreground service | **H** | Verified `isForeground=true` and frames with the phone backgrounded | A swipe-from-recents check (pending) |
| Double-tap gate (3 s window, otherwise pause) | **M** | The user chose it; unit tests pass; the fast 4-tap is fixed | Users find a pause delayed by 3 s confusing; the 3 s value is untested on a wearer |
| Icon only after minimising (no icon-mode setting) | **H** | The user's explicit choice ("simpler UX") | The user asks for an icon-only start |
| Confirm auto-open + re-minimise (a spec §1.7 exception) | **M** | The user asked; BAL exemption via SYSTEM_ALERT_WINDOW; not yet verified on the device | Rokid blocks background activity starts anyway |
| Pop-up default 6 s, range 3–20 s | **L** | A reasonable default, adjustable | User feedback |
| VAD: floor 80, ×3 noise, 2.2 s trailing silence, 1.5 s grace tap | **M** | PCM math plus typical speech pauses; not yet tested on the device | The wearer test (soft speech, noisy room) |
| VISION_EXTRACT per photo (up to 3 calls per batch) | **H** for correctness, **M** for cost | Forget must remove the right facts | API cost or latency complaints → batch with per-photo attribution |
| Retention never touches EXECUTED reminders | **H** | The earlier ruling plus user intent (an accepted action) | n/a |
| Tombstone prune rule: 8 days with no frame, memory or JPEG | **M** | Derived from the 7-day TOO_OLD window plus queue slack | The glasses' buffer-age window changes |
| Delete all keeps shopping and notes, and redacts (not deletes) non-open proposals | **M** | Mirrors Forget; keeps undo working | The user expects "everything" gone |
| Backup excludes set-up state; user preferences stay restorable | **M** | It fixes the reinstall bug, but B12 shows the exclusion list is incomplete | The B12 fix extends it |
| Encryption fix: FTS rebuild on a fresh connection | **M** | Explains the failure from the code; reproduced in a JVM fake; not on the device | The device D-D4 run |
| Answer token cap 4096 + retry with double the cap on cut | **M** | The most plausible cause; no device logs yet | The INVALID_OUTPUT count on the device |
| bge-small first, e5-small later | **H** | The user's choice | RAM measurement on the device |
| NDK 28.2 pinned (27.2 unavailable) | **H** | The SDK listing; 16 KB alignment verified | n/a |
| Embeds 8 per call, each with its own 60 s timeout | **M** | Avoids the batch timeout; not measured on the phone | On-device timing |
| Activity default sort Latest first | **H** | The user's request | n/a |
| Rows show photo time, with "Earlier days" (timeline fix) | **L** | Caused user confusion; now combined with the sort | The user prefers extraction time |
| Notifications-off: keep the reminder and warn | **M** | The alarm still works once notifications are back on | The user wants the reminder refused instead |
| Phone-typed asks also show the HUD undo toast | **M** | The spec ties it to origin ASK | The user finds it noisy |
| Auth-timeout retry 3× (2/5/10 s) | **M** | Fixed an observed intermittent; not re-verified | A device cold start |
| All-day events ranked after any timed events left today | **M** | A judgement call | User feedback |
| Public-domain LJSpeech probe clip at the same length | **H** | Licence clean; byte math unchanged; tests pass | n/a |
| Test heap 2 GB + ExitOnOOM | **H** | Explains a JVM that wouldn't answer jstack; gates since then pass | Another stall |
| Agent flows: list + per-flow DAG, facets, shared labels | **H** | The user's design, plus review fixes | n/a |
| The probe flag stays on in debug builds | **L** | Useful diagnostics | Decide (see §16 Decide) |

## 16. Deferred items

### Develop: new capability, needs a spec and a build
1. **Motion-aware fast capture.** Every 1–2 s when moving and 3–5 s when idle, instead of the 30 s minimum. Needs: the glasses IMU or a frame-difference trigger, on-glasses dedupe before upload, a per-frame AI cost cap (sample which frames go to VISION), and battery/heat limits. Open question: does every frame need AI, or only "keyframes"?
2. **Glasses asks while minimised (v2).** Map the Rokid AI key (the phone already sees `onAiKeyDown` / `onGlassAiAssistStart`) to our ask without breaking "Hi Rokid". Alternatives: a wake word, or a phone notification action or QS tile.
3. **Connectors** (the user's next big topic): Outlook, Slack, Gmail, Amazon, Flipkart and more, for personal context and actions. Start with superpowers:brainstorming in a fresh chat; decide read vs act scopes, auth (OAuth on the phone), privacy and Forget semantics.
4. **multilingual-e5-small** as a second on-device model, after the bge-small RAM measurement.
5. **More agent flows:** workout/food reminders, work reminders, groceries and day-to-day tasks. The registry is ready; each needs its own pipeline stages and prompts.
6. **The LiveFit-style HUD Display preview** (a 3:4 frame, live HUD block, Apply), porting `HudDisplayScreen.kt`.
7. **A Settings → Nearby devices page** (LiveFit-style), only if the user wants more than the automatic behaviour already built.
8. **Release build:** minify plus arm64-only APK splits (debug is about 97 MB).

### Improve: existing behaviour, known weakness
1. **B12 (security, first):** exclude `app_config_secret*` from backups and delete the restored `.bak` files after legacy-key retirement. Rotate the key if it's live.
2. **B13:** Rokid AssistServer / temple-fold force-stops kill capture. Investigate a Rokid keep-alive or whitelist; at least resume capture when the app is next started.
3. **B2:** exact-time reminders (exact alarm permission).
4. **B7:** persist the MINIMISED state so a phone process restart never appStarts over Maps.
5. **B6:** the Home countdown appears about 1.5 min after a phone app restart. Request a hello at once on bind.
6. **B4:** ANSWER output formatting (no raw ISO times; resolve "6" with time-of-day context).
7. **Photo-taken feedback** (in progress): verify it answers "I feel nothing came".
8. **Batch VISION** with per-photo attribution, if cost becomes a problem.
9. **The edge-label layout** at large font scales (Agent flows minor M1).
10. **Flaky tests** (B9): stabilise them, or quarantine them with a reason.
11. **Review minors** (B11): triage the minors left in the review files.
12. **Hygiene:** gitignore `.kotlin/`; delete or move the root `window.xml` (B10).

### Decide: needs the user's call
1. **The probe flag:** keep `PcLinkProbe` logging in debug builds, or remove it now that Option A is proven?
2. **Delete all scope:** keep shopping and notes (current), or wipe them too?
3. **Notifications-off reminders:** keep with a warning (current), or refuse?
4. **Timeline row time:** photo time (current) or extraction time, now that a sort exists.
5. **Faster-capture cost model:** acceptable AI cost and battery per hour, which drives Develop #1.
6. **D-A1 test:** the glasses clock was on manual time; what should it be restored to?
7. **The B12 key:** is the key inside the restored `.bak` a live key (rotate it), or a stale one?
8. **Nearby devices page:** wanted or not (Develop #7)?
9. **Fresh-install onboarding device test:** repeat it after the B12 fix?
