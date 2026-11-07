# Proactive Context

Proactive Context (PCA) is a private, on-device memory and context agent for Rokid glasses. It captures sparse, battery-aware moments on the glasses, builds searchable memory on the phone, and answers voice questions and nudges from that memory.

Three apps, one repo (`android-companion/`):

- **Glasses app** (`:glasses`): camera capture, HUD, voice and marks on the Rokid glasses. The glasses link is the Rokid CXR link.
- **Phone app** (`:phone`): the hub. Memory, retrieval, the canvas web UI, settings, Forget and exports.
- **Watch app** (`:watch`): a Wear OS app that sends passive motion signals to the phone over the Data Layer. LiveFit's motion push to PCA was removed.

## Architecture

- **Hub**: a phone foreground service that owns the glasses link, the watchdog and relauncher (with back-off), and the background policy including AutoStop when the glasses are away.
- **Capture**: activity-driven photo capture with battery guardrails, signals and classifier thresholds, marks (a mark takes a photo), Moments and Scenes built from them.
- **Memory**: an encrypted Room database (SQLCipher) with facts, photos, Moments, marks and a cleanup outbox; one RAG core (`RagCore`) under Ask and Nudge; Forget with scoped deletion and a residue oracle in tests.
- **Canvas**: a node-based pipeline editor served by the phone (Query and Nudge flows, triggers, delivery routes, device capabilities).
- **Previewer**: one `previewMoment` entry and one list view for every item kind.

## Modules

`:phone`, `:glasses`, `:watch`, `:core:model`, `:core:capture`, `:core:hud`, `:core:ui`, `:services:ai` (includes the llama.cpp submodule), `:services:memory`, `:services:orchestration`, `:services:glasses-link`, `:services:voice`, `:services:connectors`.

## Build

Needs JDK 17, the Android SDK (platform 36, NDK 28.2.13676358, CMake 3.22.1), git-lfs (`git lfs install && git lfs pull`) and the llama.cpp submodule (`git submodule update --init`). Set `ANDROID_HOME` or `sdk.dir` in the untracked `android-companion/local.properties`. The first build compiles llama.cpp and takes 10+ minutes.

```bash
cd android-companion
./gradlew --no-daemon --offline test :phone:assembleDebug :glasses:assembleDebug :watch:assembleDebug
```

Full test gate: `tools/gate.sh`. Model endpoints and keys are entered in the app, never in the repo.

## Install rules

- Use `adb install -r` only. **Never uninstall the phone app**: the SQLCipher key lives in the Android Keystore, so an uninstall deletes the database, and Room migrations are one-way. Back up with [docs/runbook.md](docs/runbook.md) before every install.
- The phone and watch apps share the applicationId `com.rokid.context` and must be signed with the **same key**, or the Data Layer will not pair them.
- Builds are debug-signed and sideloaded for now. No APKs are published on GitHub.
- The glasses install over USB adb.

## Versions

The v0.9.5 to v0.9.9 train, and the v1.0.0-rc1 draft, are described in `code-agent/releases/` (one note per version). `code-agent/` also holds the working rules, handoffs and review history for AI coding agents; start at [code-agent/README.md](code-agent/README.md).

## Privacy

Everything stays on the device. The database is encrypted with SQLCipher and its key never leaves the Android Keystore. Voice recognition is on-device and audio is never stored. Forget removes a fact, photo, Moment or time range together with the copies derived from it (exports, run outputs, lineage). Prefs files holding keys, set-up or per-device state are excluded from backup and device transfer (checked by `BackupRulesTest`); the watch and glasses exclude everything.

## Docs

- [docs/voice-commands.md](docs/voice-commands.md): what you can say.
- [docs/voice-marks.md](docs/voice-marks.md): voice marks.
- [docs/runbook.md](docs/runbook.md): install, backup and recovery.
- [tools/QUALITY-EVAL.md](tools/QUALITY-EVAL.md): the read-only quality evaluation harness.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/PHONE_PROCESSING_POLICY.md](docs/PHONE_PROCESSING_POLICY.md): design notes (older; the code is the source of truth).
