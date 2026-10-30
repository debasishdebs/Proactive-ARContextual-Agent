# Final fixes X handoff (2026-10-30)

Codex final review findings, all fixed with tests. Frozen: PROTOCOL_VERSION 1, TRACE_SCHEMA 1, plan hash f5782e0d8610, Room v15.

1. Voice pause (HubCaptureControl): always adds an indefinite PHONE record unless one already exists; replies "Paused until you resume" / "Already paused by you".
2. Voice resume: clears only PHONE-origin records. Replies name what still holds capture (glasses, quiet hours, low battery via the `batteryHold` hook, which is not wired to a source yet); "Capture resumed" only when nothing else holds it and the glasses link is up, else "Phone resumed; glasses will follow when connected" (`glassesLinked`, wired in VoiceGraph). New `CaptureResult.Said`.
3. DeliveryRoute: `caller` with the glasses display off is now an explicit phone-only route (fallback glasses_display_off, recorded) for chat, phone voice and glasses voice; never a null route that defaults to the HUD.
4. Watch: MotionSender gates every attempt/retry on the enabled flag; PassiveMotion cancels in-flight sends on disable; register/unregister/alarm are serialised by MonitoringSwitch (last wish wins).
5. GlassesWatchdog: hub restart ladder and last start time persisted (PrefsHubRestartStore, prefs "hub_restart"); independent 60 s minimum between hub start attempts that reset signals cannot bypass.

## Voice reply truthfulness fix (Codex P2)
- `Hub.pausePhoneAcked` / `resumeAcked` (and `PauseHub` equivalents, default false) return whether the `pc_settings` push was accepted by the link; `pushSettings` now returns that Boolean. `HubCaptureControl` words replies from it ("...on the phone and the glasses" vs "...on the phone; the glasses will follow when they reconnect"). Table: docs/voice-commands.md.
- `batteryHold` and `glassesLinked` hooks removed. The phone has NO signal for the glasses' battery capture hold (only `glassesBatteryPct`; the `battery` block reason stays on the glasses), so replies never claim or deny low battery. Not invented; open item if a glasses-to-phone capture-reason message is ever added.
- No-local-pause reply: "No pause is set on the phone" (+ ". The glasses are still paused" when a GLASSES-origin record is synchronised).
