# Developer and device runbook

How to build, install and test Proactive Context. Current state and pending work: `docs/superpowers/specs/2026-10-14-status-and-backlog.md`.

## Toolchain
- `ANDROID_HOME=/Users/<user>/Desktop/rokid/android-sdk`; `sdkmanager` comes from Homebrew (`/opt/homebrew/share/android-commandlinetools/...`).
- **NDK 28.2.13676358** and **CMake 3.22.1**, installed 2026-10-14 for on-device embeddings. They're pinned in `services/ai`, `phone` and `glasses` (`ndkVersion`). Remove them with `sdkmanager --uninstall "ndk;28.2.13676358" "cmake;3.22.1"`.
- **compileSdk:** 36.
- **Phone:** minSdk 29, targetSdk 35.
- **Glasses app:** targetSdk 32; the Rokid glasses run Android 12 (API 32).
- **Submodule:** `android-companion/services/ai/src/main/cpp/llama.cpp`, llama.cpp b11618 @ 4dd6dd4b. Init it with a plain `git submodule update --init -- <path>`. **Never** use `--reference` to a worktree you'll remove later; that broke the main checkout once.

## Build config (`.env` at the repo root, git-ignored, never printed)
The phone build reads only these **names** from `.env`: `AZURE_KG_ENDPOINT`, `AZURE_KG_DEPLOYMENT`, `OLLAMA_BASE_URL`, `OLLAMA_MODEL`, `LOCAL_RUNTIME_MODE`, `LOCAL_NATIVE_MODEL_PATH`. **No API key is built into the APK** (Phase 8 D1/D3). Keys are entered in the app (Settings → Model endpoints → API key) and stored in the Android Keystore KeyVault. A reinstall loses them, so you paste the key again. Worktrees need `.env` copied in (`cp .env <worktree>/.env`) for the endpoint defaults; do it without printing the file.

## Gradle rules
- From `android-companion/`, always use `./gradlew --no-daemon …`. **Never `gradlew --stop`**: it kills other agents' builds.
- One Gradle run per worktree at a time. Concurrent runs in one worktree corrupt the build.
- Full gate: `tools/gate.sh <log>` (watchdog; defaults to `test :phone:assembleDebug :glasses:assembleDebug`; exit 124 on a stall, with a `<log>.stall` jstack). Or run the same command by hand.
- Test JVMs get a 2 GB heap with ExitOnOutOfMemoryError (root `build.gradle.kts`).
- Known-flaky tests (one re-run allowed): PromptEditorTest, DeleteAllFenceTest, PushToTalkTest, AppShellTest.leavingHome….

## Devices
- **Glasses:** serial `1901092545029120` over USB, package `com.rokid.context.glasses`. They sometimes drop off USB; replug the cable.
- **Phone:** Samsung SM-S931B on **wireless adb**, `adb-RZCY902F76K-nmSM2g._adb-tls-connect._tcp`; package `com.rokid.context`. After a reboot, Wireless debugging may switch off; re-enable it in Developer options (re-pair if asked). **Never toggle Wi-Fi** from adb.
- Always pass `-s <serial>`.
- **Install, keeping data:**
  - phone: `adb -s <phone> install -r android-companion/phone/build/outputs/apk/debug/phone-debug.apk`;
  - glasses: `adb -s <glasses> install -r android-companion/glasses/build/outputs/apk/debug/glasses-debug.apk`.
- **Overlay permission** (minimised icon, pop-ups, Confirm auto-open). Needed again after a glasses reinstall that clears it: `adb -s <glasses> shell appops set com.rokid.context.glasses SYSTEM_ALERT_WINDOW allow`. It works only because the manifest declares it.
- **Launch the HUD:** `adb -s <glasses> shell am start -n com.rokid.context.glasses/.MainActivity`.
- **Phone app data backup** (a debug build): force-stop it, then `adb -s <phone> exec-out run-as com.rokid.context tar -cf - -C /data/data/com.rokid.context databases files shared_prefs no_backup > ~/Desktop/rokid/backups/pca-phone-<stamp>.tar`. It can be restored only onto the **same install**, because the Keystore must survive.
- **Logs:**
  - **Always** filter out lines containing `token` or `AuthorizationHelper`: the Rokid SDK logs the glasses auth token in plain text (see `docs/rokid-sdk-token-logging.md`).
  - Useful tags: phone `PcGlassesLink PcLinkProbe ContextHub PcVoice RokidFrameInbox`; glasses `PcCapture PcUplink PcHud PcGlasses`.
- **Device checklist:** `tools/device-checklist.sh` (`--dry-run`, `--only <id>`); results go in `docs/screens/checklist-<date>.txt`.

## Device etiquette (user rules)
- Don't tap the phone or glasses, or take screenshots, while the user is wearing or using them, unless asked.
- Bluetooth toggling, Delete all, uninstall/reinstall, and permission revokes each need the user's explicit OK, and only for that occasion. Take a backup before anything destructive.
- Before saving any phone screenshot, check that no key or token is on screen.
- Never push. Commit locally only.
- Use Opus/Sonnet agents, never Haiku.

## Known device quirks
- Rokid's phone SDK closes the CXR session (`GLASSES_EXIT`) when another glasses app comes to the front. The app handles this with the `MINIMISED` link state (`f82b82e`).
- Rokid AssistServer, or folding the temples, can force-stop the glasses app (backlog B13).
- Android 12 denies the camera to a foreground service started from the background. Capture uses a keep-alive service started while the HUD is visible (`1f09708`).
- Bedtime mode on the phone closes the app.
- The glasses' Rokid AI key starts Rokid's own assistant and briefly pauses our link.
- Glasses double-tap: wait about 0.6 s between pairs outside the exit gate.
