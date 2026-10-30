# Handoff 2026-10-30: watch UI polish and voice pause/resume (feat/v099-watch)

## Watch (:watch)
- Dark theme, per-state accent (`WatchLook.style`: STILL slate blue, WALKING green, RUNNING orange, CYCLING teal, IN_VEHICLE amber, EXERCISE red, ASLEEP indigo, UNKNOWN grey) and a 48dp vector icon per state (`res/drawable/ic_state_*.xml`; material-icons-extended not used).
- Labels via `WatchStatus.stateLabel` (IN_VEHICLE = "Driving"); link line `WatchStatus.linkText` plus one-tap fix (`WatchStatus.fix`: Allow motion / Turn on); "Last sent: STATE HH:mm"; toggle "Send motion to phone". Launcher label "PCA Motion".
- No animations; the screen refreshes on resume and every 15 s only while RESUMED. ScalingLazyColumn with round-screen padding. Not run on a device/emulator (no adb).
- Tests: WatchStatusTest extended (labels, distinct accent/icon, hue ranges, link lines/fixes).

## Voice pause/resume
- `CaptureCommandParser` (`:services:voice`, whole-utterance exact phrases), `CaptureControl`/`CaptureResult` and the reply copy in `Utterance` (new optional ctor param `capture`, default null = not recognised). Runs after the mic-capability check, before the mark parser; no model call.
- `HubCaptureControl` (`:phone`, `voice/`) over `PauseHub.pausePhone/resume`, wired in `VoiceGraph`. Origin is always PHONE (only entry that exists; a glasses-voice pause is also a PHONE record). Glasses app untouched.
- Docs: `docs/voice-commands.md`. Tests: `CaptureCommandsTest`, `HubCaptureControlTest`.
