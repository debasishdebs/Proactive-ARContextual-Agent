# v0.9.8 slice D: mic and display as editable device capabilities (addendum, 2026-10-28)

Ruling (road-to-v1 revision, 2026-10-25): the canvas knows where a flow can listen (source) and show (target), and the user edits it. Parent: `2026-10-28-v098-query-nudge-design.md` (Slices, D).

## What is hard-coded today
- Targets: `caller|phone|glasses|both` strings in `FlowPlan`/`out.deliver`; `DeliveryRoute.resolve(target, callerOnGlasses, reach)` (core:model) knows only link up/down; `AnswerService.routeOf` (Query) and `NudgeEngine.routeOf` (Nudge, plus the verbosity routing `shownOn`) call it. `DeliveryEnvironment` (orchestration) holds the glasses link probe.
- Sources: `UtteranceSource {GLASSES_VOICE, PHONE_VOICE, PHONE_CHAT, GLASSES_TEXT(reserved)}` in `:services:voice`; `VoicePipeline.glassesListenStart/phoneStart` begin a listen unconditionally (after the platform/permission checks); `Utterance.handle` takes whatever text arrives.
- Protocol: glasses hello carries no mic/display capability fields; nothing is added (PROTOCOL_VERSION 1 stays, the glasses app is untouched). These are USER settings, not what the hardware reports.
- Stores: per-device prefs (`signal_config`, `fusion_settings`) with a StateFlow, API routes (`SignalRoutes`, `/api/...`), a phone Settings page, excluded from backup.

## Model (core:model `DeviceCapabilities`)
- Catalogue of four fixed entries: `phone.mic`, `phone.display`, `glasses.mic`, `glasses.display`. `DeviceCapability(device, capability, enabled, constraint)`; `constraint` is a fixed token, only `hud` on `glasses.display` (the glasses display IS the HUD overlay: a toast or answer overlay, no other surface). An unknown device or capability is never stored.
- `DeviceCapabilities(disabled: Set<String>)`, default empty = everything enabled = today's behaviour. `enabled(device, cap)`, `with(...)`, `list()`, `deliveryWarning(target)`. Pure, no Android.
- Fixed reason tokens (never user text): `phone_display_off`, `glasses_display_off`, `no_display_enabled`, `phone_mic_off`, `glasses_mic_off`.

## Store and editors
- `DeviceCapabilityStore` (phone, prefs `device_capabilities`, StateFlow, in memory without prefs, load drops unknown keys, same style as `SignalConfigStore`). Backup: the pattern for per-device user settings (`signal_config`, `fusion_settings`, `ai_call_pace`) is EXCLUDED, and a restored phone may have different or no glasses, so we exclude it (both XML files and `BackupRulesTest`). Delete all does not touch it (no memory content).
- Phone Settings > Devices > "Mic and display" page: four switches with the HUD note. Canvas: `GET /api/devices` (list), `POST /api/devices/capability {device, capability, enabled}` (400 on an unknown pair), registered like `SignalRoutes`. The canvas JS is not changed.
- A canvas flow editor needs nothing new: delivery targets are already in the palette; the publish check below tells the user when a target is switched off.

## Who consults it
- `DeliveryRoute.resolve(..., caps = ALL)`: a disabled display removes that surface from the route. `phone` off: the glasses if reachable and enabled, else the feed row stays with `phone_display_off`. `glasses` off: the phone, `glasses_display_off`. `both` loses the disabled half. Both off: the phone row (the answer is always stored) with `no_display_enabled`. `caller` is untouched except a glasses-voice ask with the glasses display off, which answers on the phone with `glasses_display_off` (no HUD, no TTS). Every fallback is recorded: Query `ASK_DELIVERY_FALLBACK`, Nudge `NUDGE_DELIVERY_FALLBACK` (existing trace kinds, the token goes in the existing text field; TRACE_SCHEMA 1).
- Nudge: `routeOf` applies the same rules to the `shownOn` result (a glance/toast needs the glasses display; SILENT verbosity is never raised).
- Mic: `VoicePipeline.glassesListenStart` / `phoneStart` refuse a listen when that mic is off with a fixed text (HUD toast for glasses, phone error line for the phone) and outcome BLOCKED; `Utterance.handle` returns `Handled("The <device> mic is off in Settings.")` for a voice utterance whose mic was switched off mid-listen. PHONE_CHAT is typed text, no mic: unaffected (the text path). GLASSES_TEXT (reserved) is text too.
- Publish: `CanvasPublisher.publishFlow` returns a WARNING (not a block) when the flow's only delivery target is disabled (`phone` with the phone display off, `glasses` with the glasses display off, `both` with both off). `Outcome.Published` gains an optional `warnings` list (default empty), returned in the publish JSON.
- Wiring: `ContextApp` sets `DeliveryEnvironment.capabilities` from the store at process start (so workers see it without a hub).

## Not in scope
Capabilities never feed the classifier, capture or the plan hash; transient Listening/Thinking HUD frames are not gated by `glasses.display` (only answer and nudge delivery). No Room change, no protocol field.
