# v1.1 design: photo-derived text choke point, release build and signing (2026-11-16)

Roadmap v1.1 items 4 and 5. Branch `feat/v101-ask-eval` at `1b62f263`. Paths are under `android-companion/` unless stated. Line numbers are from today's tree. **UNVERIFIED** marks a claim I could not check against Android documentation or a device.

## A. Photo-derived text choke point

### Invariant and today's state

A forgotten photo, and any text derived from it, is never sent to a model, shown, exported or kept.

- **Sent:** structural since Codex r12. Every `ModelRequest`/`RoleCall` declares `PayloadSources` (`services/ai/.../model/ModelGateway.kt:66`). `ModelRouter.kt:198-204` re-checks those sources under the upload slot. `PromptKeyImageUseTest.everyModelCallSiteDeclaresItsPayloadSources` fails on any undeclared call site.
- **Kept, shown, exported:** still checked caller by caller. Each writer re-implements its own mix of three checks: wipe generation, tombstoned frames and `ForgetFence.allLive`. The mixes differ. Some writers check frames but not memories, and the canvas runs miss member photos (accepted gap at `CanvasRunner.kt:~682`). This is the "per-caller patches" pattern that the handoff lesson (section 14) warns against.

### Design: one declaration, three gates

1. **`Derived<T>(value, sources: PayloadSources, generation: Long)`**: the model reply carries the request's `sources` and the wipe generation that `PhotoLiveness` captured. `RoleRunner` and `ModelRouter` return it, so a caller cannot drop the provenance between the call and the write. Output-cited ids are added with `withCited(ids)`, which can only add ids, never remove them. Provenance is declared once, at the request. Today it is declared at the request and then re-assembled by hand at the write.
2. **`ProvenanceGate.commit(derived, policy) { fenced -> … }`** (`services/orchestration/.../provenance/ProvenanceGate.kt`): one Room transaction that:
   - checks that the wipe generation is unchanged;
   - checks that no frame in `frameIds`, and no `frame:` cite, is tombstoned or gone;
   - checks that `ForgetFence.goneAmong(memoryIds)` is empty and that the marks still exist;
   - runs the block only if all of that holds;
   - writes the dependency rows from `derived.sources` (memory_source, frame_source `CARRIED`, AskSources deps), so the Forget cascade always finds the row later.

   `policy` is `DROP` (facts, nudges, canvas) or `STORE_REDACTED` (asks, which keep today's behaviour). The result is `Committed | Forgotten(ids) | Wiped`.

   **Compile-time capability:** the DAO inserts for derived text take a `Fenced` parameter whose constructor is internal to the gate. This covers derived `MemoryEntity`, `AskEntity`, `NudgeEntity`, canvas run items and saves, and cluster summaries. Code that skips the gate does not compile.
3. **`ProvenanceGate.emit(sources)` / `liveFilter(items, idsOf)`**: the read-side check for text that leaves Room. This covers retrieval results, the HUD answer card, notifications, canvas API responses and the export. Surfaces that hold text in memory (HUD, notification, canvas page) also listen to the removal generation (`ForgetActions.removals`, already served as `GET /api/data-generation`) and clear themselves when it changes. UIs that observe Room need nothing new: the dependency rows from gate 2 let the cascade delete or INVALIDATE their rows.

   The export keeps its predicate in SQL, moved verbatim into one constant, `ProvenanceSql.LIVE_DERIVED`.
4. **Enforcement:** a new source-scan test, `ProvenanceChokePointTest`, modelled on `PromptKeyImageUseTest`. It fails if any of these appear in main code outside the gate, the Forget engine and `FrameLiveness`:
   - `MemoryWipe.generation.get() !=`
   - `ForgetFence.allLive`, `ForgetFence.liveIds`
   - `forgetDao().tombstoned(`
   - `tombstonedAmong(`

   It also fails if a new unfenced insert overload appears for a derived-text table.

### Per-caller checks this replaces

| Leg | Site (file:line) | Today's check |
|---|---|---|
| Kept | `services/orchestration/.../orchestration/PipelineRecords.kt:60`, `:140-153` | generation + tombstoned frames + `allLive` (derived facts) |
| Kept | `.../orchestration/ExtractSave.kt:192-195` | generation + tombstoned `allCarried` |
| Kept | `.../orchestration/ExtractFollowUp.kt:88` | generation only |
| Kept | `.../work/frames/scene/ClusterSave.kt:50`, `:77`, `:123` | generation, stale, tombstoned members |
| Kept | `.../orchestration/forget/ResummarizeWorker.kt:133`, `:148`, `:154` | generation (twice) + tombstoned inherited frames |
| Kept | `.../work/QueueDrainWorker.kt:317-320` | generation + tombstoned union |
| Kept | `.../orchestration/AnswerService.kt:265-268` | generation + `allLive` (stores the ask redacted) |
| Kept | `.../orchestration/nudge/NudgeEngine.kt:122` | `allLive` (drops the nudge) |
| Kept | `.../canvas/LiveExtract.kt:117`, `:155` | generation + `tombstonedAmong` |
| Kept | `services/memory/.../db/CanvasDao.kt:123-124` (`insertRunItemsFenced`), called from `CanvasRunner.kt:694`; closes the `~682` gap | generation + frames + labels |
| Kept | `phone/.../canvas/api/CanvasBackendImpl.kt:770-773` | generation + `allLive` (canvas ask) |
| Shown | `AnswerService.kt:320-330` (`firstCitedPhoto`, HUD label) | `allLive` per cite |
| Shown | `NudgeEngine.kt:146` (trigger photo) | `allLive` |
| Shown | `.../orchestration/RagCore.kt:223`, `:232`, `:235` | `allLive` filters on history and hits |
| Shown | `.../canvas/RetrieveDebug.kt:15-22`, wired at `CanvasBackendImpl.kt:186` | `liveIds` |
| Shown | `CanvasBackendImpl.kt:581` (canvas photo read) | generation + tombstoned + frame present |
| Exported | `services/memory/.../db/ExportDao.kt:28-52` | SQL predicate (moved, not changed) |
| Sent | `services/ai/.../ai/ModelRouter.kt:198-204` | stays; becomes the gate's `send` leg |

Out of scope: `RokidFrameInbox.kt` and `IntentSampler.kt`, which handle photo bytes before anything is derived from them, and the Forget engine itself. The HUD answer-card push and the notification posting were not located in this pass. Task A5 finds them; the rc1 known issue "answer stays on glasses after Forget" belongs to them.

### Tasks (each one Sonnet-sized, one worktree, TDD)

- **A1 `Derived<T>`.**
  - Files: `ModelGateway.kt` (`ModelReply.sources/generation`), `RoleRunner.kt`, `ModelRouter.kt`.
  - Tests: the reply carries the request's sources; `withCited` only adds.
  - Accept: ai module tests green.
- **A2 Gate.**
  - Files: the new `ProvenanceGate.kt`, `Fenced`, `ProvenanceSql`.
  - Tests: a matrix covering a forgotten memory, a tombstoned frame, a gone `frame:` cite, a deleted mark, Delete all, and an **expired photo that stays live** (no JPEG required). It also asserts that the dependency rows are written.
  - Accept: the matrix is green, with real SQL (Robolectric Room).
- **A3 Writers, batch 1.**
  - Files: PipelineRecords, ExtractSave, ExtractFollowUp, ClusterSave, ResummarizeWorker, QueueDrainWorker. Their DAO inserts take `Fenced`.
  - Accept: existing tests green, no change to test expectations, per-caller checks deleted.
- **A4 Writers, batch 2.**
  - Files: AnswerService, NudgeEngine, LiveExtract, CanvasDao/CanvasRunner, CanvasBackendImpl ask.
  - New test: a singleton flush after a scoped Forget cannot restore forgotten-photo text (closes the v1.1-backlog gap).
- **A5 Read side.**
  - Files: RagCore, RetrieveDebug, `firstCitedPhoto`, `CanvasBackendImpl:581`, the HUD answer push and nudge notifications (locate them first), ExportDao (moved to `ProvenanceSql`).
  - Test: a Forget during HUD display clears the card on the next generation tick.
- **A6 Enforcement and review.**
  - `ProvenanceChokePointTest`: it must fail on a planted unfenced write, then pass once it is removed.
  - A Codex review of the whole diff.
  - Accept: all modules green, Codex reports no P1.

## B. Release build and signing

### B1. Every DEBUG / production branch and what to test

`BuildConfig.DEBUG` follows the build type's `isDebuggable`. On the release type it is false, and the app is not debuggable.

| # | Site | Effect when DEBUG=false | Test |
|---|---|---|---|
| 1 | `phone/.../hub/HubGraph.kt:143` `probe = DEBUG` → `services/glasses-link/.../GlassAppWatcher.kt:48`, `:93` | PcLinkProbe rx/tx logs off | Link binds; Ask and HUD work; minimised reopen still works (`MinimisedLink` probes are separate); no PcLinkProbe lines in logcat |
| 2 | `phone/.../ui/preview/PreviewSection.kt:189` | "Copy frame id" hidden | Button absent; "Add to photo test" still works |
| 3 | `phone/.../ui/preview/PreviewRoute.kt:104`, `phone/.../ui/shell/SettingsDestinations.kt:342`, `:371` (`PhotoDimEval.rowVisible`, `PhotoDimEval.kt:209`) | Photo-dim test row, labels and route hidden even in Developer mode | Diagnostics shows no row; the gate refuses the route |
| 4 | `glasses/.../capture/CaptureService.kt:185`, `:710` | `EXTRA_SKIP_DEDUPE` ignored: every capture is deduped | Touchpad capture-now and intent capture both work; any adb script or tool that relied on skip-dedupe now sees dedupe |
| 5 | `services/orchestration/.../MemoryExport.kt:81-82` (`FLAG_DEBUGGABLE`) → `forget/ForgetActions.kt:180` (range), `:235` (others) → `phone/.../ui/memory/ProductionForget.kt:37` | An undeletable cached export turns DONE into `CACHE_CLEANUP_FAILED` (Forget committed, user told) | Device: export, then Forget a range and a photo, expect DONE and an empty cache. The failure path cannot be forced on a non-debuggable device, so it stays JVM-only. The seam is covered in `ForgetOutcomeFixesTest`, `RangeForgetCleanupTest` and `ScopedForgetActionsTest`; confirm the UI message in `MemoryDetailViewModelTest` |
| 6 | Build: no `debugImplementation` tooling; release unit tests disabled (`phone/build.gradle.kts:144`, `glasses/build.gradle.kts:76`); `isMinifyEnabled=false` (phone `:60`, glasses `:24`; watch has no release block) | No R8 risk; no release-variant unit tests; **`run-as` backups stop working** | Covered by the checklist below |

The watch module has no DEBUG branches.

**Approach:** first a release-type build signed with the **current debug key** (B3): it updates in place and rolls back by reinstalling the debug build (**UNVERIFIED** on Rokid OS).

### B2. Signing design

**Facts:**

- An update must be signed by the installed certificate or by a certificate that rotation lineage approves for it. Otherwise it fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- An accepted update keeps the UID, so the Keystore-held SQLCipher key and the database survive. The Keystore part is **UNVERIFIED** on-device, so the checklist verifies it.
- APK Signature Scheme v3 (API 28+) supports rotation. Since build-tools 33, apksigner applies rotation only from API 33 by default (the v3.1 block). **Our devices run below 33** (glasses targetSdk 32; check each device with `getprop ro.build.version.sdk`), so we must pass `--rotation-min-sdk-version 28` (**UNVERIFIED**, check the flag name against the installed build-tools' `apksigner help sign`).

**Lineage, once (done by the user, offline):**

```
apksigner rotate --out ~/<signing dir>/pca.lineage \
  --old-signer --ks ~/.android/debug.keystore --ks-key-alias androiddebugkey \
  --new-signer --ks ~/<signing dir>/release.jks --ks-key-alias pca
```

- The same lineage signs all three APKs.
- The phone and the watch must present the same current certificate for the Data Layer. Google's rule is "same package name and same signing certificate". Whether Play Services compares the current signer or the lineage is **UNVERIFIED**. So the phone and watch rotate together, back to back, which avoids a mixed state.
- Glasses: whether Rokid CXR checks signatures is **UNVERIFIED**; the checklist tests it.

**Sign** (after `assembleRelease`, which today produces unsigned APKs because no `signingConfig` is set):

```
apksigner sign --ks ~/.android/debug.keystore --next-signer --ks ~/<signing dir>/release.jks \
  --lineage ~/<signing dir>/pca.lineage --rotation-min-sdk-version 28 --ks-pass env:PCA_OLD_PASS ...
```

**Gradle:**

- No release `signingConfig`. AGP has no DSL for a lineage (**UNVERIFIED** for the AGP version in use).
- A new script, `tools/sign-release.sh`, reads paths and aliases from `~/<signing dir>/signing.properties` and passwords from the macOS Keychain (`security find-generic-password -w`) into env vars. It then runs `apksigner sign`, `apksigner verify -v --print-certs`, and writes SHA256SUMS.
- Nothing goes through `buildConfigField`. The existing secret regex in `phone/build.gradle.kts:27-46` stays as a backstop.

**Where the keys live:**

- `~/<signing dir>/` (mode 0700): `release.jks`, `pca.lineage`, `signing.properties`.
- Also `~/.android/debug.keystore`. It is now the **root of the lineage** and is irreplaceable: losing it before the switch means the phone app can never be updated without uninstalling it.
- Add `*.lineage` to `.gitignore`. Lines 42-50 already cover `*.jks`, `*.keystore`, `keystore.properties` and `signing/`, but not a lineage file.

**Backup:**

- Copy the debug keystore, the release keystore and the lineage to two places: an encrypted external disk, and a password manager attachment.
- Keep the passwords in the password manager only.
- Do the debug keystore copy **now**, before any other v1.1 work.

**Hardening (Q3):** set the debug signer's lineage capabilities (`--set-installed-data`, `--set-rollback`) explicitly; defaults **UNVERIFIED**.

**Play App Signing later (UNVERIFIED; check Play Console help first):** upload our release key as the app-signing key (PEPK) so sideloaded and Play installs share a signer; whether a lineage rooted in a debug certificate is accepted is unknown. Phone and watch share one listing, so one key. Glasses stay sideloaded.

### Tasks B

- **B1 JVM.**
  - Add `PhotoDimEval.rowVisible(false, true) == false` and a ProductionForget `CacheCleanupFailed` message test if missing.
  - Accept: green.
- **B2 Script.**
  - `tools/sign-release.sh` plus `.gitignore` `*.lineage`, and a dry-run mode that prints the commands with no passwords.
  - Test: a shellcheck run, and a run against throwaway keys in a temporary directory that the test itself creates and deletes.
  - Accept: `apksigner verify` shows v3 with 2 certificates in the lineage.
- **B3 Release-type smoke on device (debug key).** Use the checklist, steps S1-S6.
- **B4 Signing switch.** Use the checklist, steps K1-K9.

### Manual device checklist

**Release-type smoke (debug key)**

- **S1.** Call check (`mCallState=0`). Take a `run-as` phone backup. This is the last one while the app is debuggable.
- **S2.** `adb install -r` the release-type phone, glasses and watch APKs.
- **S3.** Open the phone app. Check that the memory count matches the count before, that no Room migration ran, and that the logcat shows no crash.
- **S4.** Run rows 1-5 of the B1 table. Glasses: capture-now, link, HUD Ask.
- **S5.** Export, Forget a test range, Forget one photo: DONE each time, no export left in the share sheet.
- **S6. Rollback:** reinstall the debug APKs (same versionCode, `-r`).

**Signing switch**

- **K1.** Confirm that each installed APK's certificate equals the local debug key. Pull `base.apk` (`pm path`), run `apksigner verify --print-certs`, and compare the SHA-256 with `keytool -list -v` on `~/.android/debug.keystore`. **A mismatch on any device means STOP.**
- **K2.** Back up the keys (see the backup section). Take a fresh `run-as` backup if the phone is still debuggable.
- **K3.** Sign the APKs with the lineage, then run `apksigner verify -v --print-certs --min-sdk-version 28` and check v3 and the lineage on all three.
- **K4.** Install the glasses first. Check the CXR link, capture and HUD.
- **K5.** Install the phone. Check that the database opens with the same counts, the Keystore key works (memories decrypt), and the hub starts.
- **K6.** Install the watch immediately after. Check that `/pca/motion` messages arrive (motion hint in Diagnostics).
- **K7.** Run `dumpsys package com.rokid.context` and check that the signing info shows the new certificate and the past (debug) certificate. The output format is **UNVERIFIED**.
- **K8.** Run a worn smoke test: capture, mark, Ask, Forget.
- **K9. Rollback:**
  - If an install is refused, nothing changed: stay on the debug builds.
  - If the app installs but misbehaves, roll forward: rebuild the last good commit with a higher versionCode and sign it with the lineage. A downgrade is refused on a non-debuggable app.
  - **Never uninstall the phone app.**
  - Reinstalling a debug-only-signed APK works only if the lineage gives the debug signer the rollback capability.

## Risks

- Losing `~/.android/debug.keystore` before K3 means permanent data loss on update. Mitigation: back it up today.
- After the switch, a non-debuggable phone has no raw backup path. The in-app export holds memories but not the photos or the database, and there is no import (**UNVERIFIED** that none exists).
- The Data Layer could break in a mixed state, or if Play Services checks only the current signer. Mitigation: K5 and K6 back to back.
- Gate 2's `Fenced` capability touches many DAOs. Mitigation: split A3 and A4, and the scan test catches drift.

## Open questions for the user

1. **Debuggable phone release?** Non-debuggable is real hardening and the true production path, but it ends `run-as` backups. Debuggable keeps backups and keeps the developer-only tools on.
2. **Play publishing in plan?** If yes, the release key we generate is the one to upload to Play later, and the Play rules should be checked before K3.
3. **Debug signer after rotation:** keep it trusted for a transition window (easy rollback) or revoke its capabilities (`installed-data`, `rollback` false), so the well-known debug key can no longer update the app?

## Rulings (user, 2026-11-16)
- Release signing uses APK Signature Scheme v3 key rotation so the release-signed build installs OVER the debug-signed apps. Hard requirement: the existing database, marks, Moments, asks and photos are retained (no uninstall, no fresh start). Test the rotation on a spare install first (the `--rotation-min-sdk-version` flag is UNVERIFIED), back up the phone before the real switch.
- Both builds are signed with the release key: the public one non-debuggable, a debuggable variant for the user's own devices so `run-as` backups keep working. A fresh start (needs an import for the export, which does not exist) is only considered at v2/v3 if the user wants it.
- Signed release APKs may be published on GitHub; debug-signed ones never. Rokid-Coder's pattern: PKCS12 release key in GitHub Actions secrets, signer SHA-256 pinned in the build, CI draft release, manual publish.


## Ratchet notes (ProvenanceChokePointTest, 2026-11-18)

The ratchet went from 15 files / 28 raw reads to 6 files / 10. Migrated, behaviour unchanged (decisions and failure modes identical; `RatchetMigrationGoldenTest` holds the golden table):

- Wipe-generation compares now call `ProvenanceGate.isWiped(generation)` (no database in hand) or `gate.wiped(generation)`: OrchestrationScheduler, QueueRuntimeStore, ResummarizeWorker (x2), LiveExtract (x2 wipe parts), ExtractSave, ClusterSave (x2), CanvasBackendImpl (x2 wipe parts).
- Tombstone reads now call `gate.goneFrames(ids, requirePresent = false)` (the same `forgetDao().tombstoned` query, chunked at 500): ExtractSave, QueueDrainWorker, ClusterSave.liveFrames, PipelineRecords.existing, IntentSamplers, PlaceSamplers. `goneFrames` does not catch, so a failing read still throws out of the caller's transaction exactly as before (fail closed by rollback / exception, never "live"). `gate.check()` was deliberately NOT used at these sites: it turns an exception into `UNCHECKABLE` (a refusal) where these sites propagated the exception, and ExtractSave maps a wipe and a tombstone to two different results.

Kept, because the site's own rule differs from the gate's:

- `CanvasDao.tombstonedAmong` (CanvasBackendImpl x2, AskDebug x2, CanvasRunner, LiveExtract): these are counts over the canvas DAO, a seam the canvas tests replace with fakes and that has no database handle; CanvasRunner also fences `labelsMissing` in the same condition. Same query as the gate's, different access path.
- `ForgetFence.liveIds` / `allLive` in CanvasBackendImpl (retrieve debug, ask snapshot): the gate's `liveIds`/`allLive` swallow a database error into "nothing live"/"not live"; these sites let the exception propagate (the whole call fails, a different response than a redacted answer). Migrating would change the failure mode.
- `MomentDao.tombstonedAmong` (DaoMomentSource, MomentBuilder): MOMENT tombstones (second-ids), not photo tombstones; the gate has no moment rule.

Not done: making the raw helpers uncallable for new code (`internal` does not cross the memory/orchestration module boundary, an opt-in annotation would need an `@OptIn` at every test and DAO caller). The ChokePointTest ratchet remains the guard.
