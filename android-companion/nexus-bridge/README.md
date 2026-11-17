# PCA Nexus bridge (v2.0, optional, OFF by default)

A separate, headless Android app (`io.github.pca.nexusbridge`, deliberately not `com.rokid.context`) that is a Rokid Nexus **plugin**. It draws what PCA's phone app hands it on the glasses through Nexus
(notices, a status pin, optional speech) and relays notice taps back. It holds no data, persists nothing and has no logic of its own. PCA's own glasses app and link are untouched (architecture "Option A",
which ASSUMES PCA's CXR link and the Nexus hub can coexist; hardware spike S2 has not run). Everything is OFF until the user switches it on inside PCA: Nexus HUD, Nexus speech (TTS), speech to text and the assist button.

Design: `docs/superpowers/specs/2026-11-17-nexus-spike-and-v2-plan.md` (sections 3-6). Rulings: `docs/superpowers/decisions/2026-11-17-codex-proxy-rulings-2.md` section B.

## Pieces

| Where | What |
|---|---|
| `:nexus-port` (library, no Nexus SDK) | `NexusPort` / `NexusPortCallback` AIDL, the PCA-owned types, `HudMapper`, `NexusGates`, and `NexusHudSink`, the ONE choke point (provenance, zone fences, pause, not worn, fail closed, withdraw on close). |
| `:nexus-bridge` (this APK) | `NexusPortService` (signature-guarded server of `NexusPort`), `BridgeRenderer` (validation + draw through PCA's own `NexusBusClient` interface), `SdkNexusBusClient` (the only file family that touches the SDK), `BridgeNexusService` (the plugin), `SettingsActivity` (NexusUi; state, defaults, how to remove). |

The transport is the thin `NexusTransport` / `NexusBusClient` pair. Option B (PCA traffic over the Nexus bus) replaces those, not the sink or the gates.

## Assist-button ask (T5) and skills (T6): what the phone implements (done: `phone/.../nexus/NexusAsk.kt`, `NexusSkills.kt`)

Phone side (implemented): `PcaNexusAsk` runs the transcript through `AppAnswerer` over `AskActions.askFrom` (all its gates) and delivers only through `sink.show(AskAnswer(ref = askId, speak = <TTS switch>))`; an ask in a zone, while paused or not worn is refused before the backend and again before delivery. `PcaNexusSkills` maps the four ops onto the hub pause entry and the phone-origin mark path; `resume_capture` refuses with `pause_zone` during a zone pause. The three switches (`Settings > Rokid Nexus`: "Nexus voice (assist button)", "Speak answers (TTS)", "Skills") are ALL OFF by default, stored as keys `voice`, `tts`, `skills` in the existing `nexus_host` prefs file (excluded from backup, kept by Delete all); any change calls `NexusProvider.refreshFeatures()`.

Both are additive to the port (`NexusPortVersion.CURRENT` stays 1; the new AIDL methods are appended, so old transaction ids do not move) and OFF by default. `NexusConfig` gained `skillsEnabled`; the ask needs
`enabled && sttEnabled && assistTakeover` (`NexusConfig.askEnabled()`); `featureMask()` then pushes `STT|ASSIST`, and `SKILLS` when skills are on. PCA push-to-talk stays the Ask input path (ruling B-Q4).
Nexus owns the microphone and the speech engine: the bridge has no audio permission and no code that records.

| Seam (all in `:nexus-port`) | What the phone does |
|---|---|
| `NexusHudSink.connect(binder, gates, config, actions, log, asks = NexusAskHandler, skills = NexusSkillProvider)` | Pass both when the user's switches exist. Both params are nullable; with `null` the ask and skills stay inert. |
| `NexusAskHandler.ask(askId, text)` | Run `text` through PCA's voice backend (`VoiceGraph`, text in, text out) and gates, then answer with `sink.show(HudEvent.AskAnswer(ref = askId, ..., speak = <the user's TTS switch>))`: the SAME sink and gates; TTS happens only if the TTS switch is on. `text` is the transcript: never log it. No retry. |
| `NexusAskHandler.refused(askId, why: AskRefusal)` | The ask will not be answered on Nexus (`ZONE_PAUSED`, `PAUSED`, `NOT_WORN`, `DISABLED`, `CANCELLED`). NOTHING was delivered to Nexus. `why.text` is a fixed line the phone may show on its own screen. A later `show(AskAnswer(askId))` returns `Dropped(TRIPPED)`. |
| `NexusSkillProvider.invoke(op: SkillOp): SkillResult` | Four ops (`STATUS`, `PAUSE_CAPTURE`, `RESUME_CAPTURE`, `MARK_MOMENT`) returning `Done(state)` with `state` in `SkillState` (`capturing`, `paused`, `zone_paused`, `stopped`), or `Refused(code)` with a `SkillCodes` token (`pause_zone`, `paused`, `not_worn`, ...). `RESUME_CAPTURE` must refuse with `pause_zone` while a zone pause is active; `MARK_MOMENT` must go through the same mark path and zone/pause refusals as the HUD Mark action. Return no memory content. The sink also checks the fences first and applies a 12 s ceiling (Nexus gives 15 s). |
| `NexusHudSink.refreshFeatures()` | Call when any Nexus switch changes (the mask is pushed; turning the ask off stops an open listening). Mode off stays `trip(TripReason.MODE_OFF)`. |
| `trip(...)` | The existing call also ends a listening or pending ask: it stops the Nexus speech session, tells the handler `refused`, and drops a late answer. Call it from the same places as before (zone hold, pause, not worn, Forget...). |

AIDL additions (appended): `NexusPort.stopListening()`, `NexusPort.setSkillHost(NexusSkillHost)`, new `NexusSkillHost.invoke(String): String` (`ok:<state>` or `fail:<code>`, synchronous), and on `NexusPortCallback`
`onListening(boolean)`, `onAskTranscript(String)`, `onAskFailed(int)`. The sink is still the only caller of `NexusPort` and the only implementer of the callback and the skill host (`NexusChokePointTest`); the phone never touches AIDL.

Flow: assist button -> hub opens the plugin with `AI_ASSIST` -> `BridgeRenderer.assistOpened()` opens ONE Nexus speech session (only with the `STT` and `ASSIST` bits) -> final transcript -> `onAskTranscript` -> the sink gates it
(zone, pause, worn) -> `NexusAskHandler.ask`. `DENIED_BUSY` / `NO_SPEECH` / any other end gives a short fixed notice ("Microphone busy. Try again.", "Didn't catch that.", "Voice ask unavailable."). A trip mid-ask stops the
session, delivers nothing and reports `AskRefusal`. The bridge also moves the hub's assist-takeover switch to match (`setAssistantTakeover(false)` unless the ask is on; the hub's own default is ON once the wearer approves
`assistant`). Logs: literal names and counts only, never a transcript.

Skills: `res/raw/nexus_skills.json` (four ops, `data: ["status"]`, output `state` is an enum, no input) is checked by `SkillCatalogTest` with Nexus's own `SkillCatalogParser` and `SkillSchemaValidator`. The wearer approves each
operation per caller in Nexus; PCA additionally keeps the `SKILLS` bit off until the user switches skills on (then every call answers the domain code `disabled`). Declaring the catalog asks Nexus for the `skills_provider`
grant, which returns an installed plugin to Pending once. No operation takes input or returns memory content.

## How the Nexus SDK resolves

Pinned to the upstream tag **`sdk-v0.27.0`** (github.com/Anezium/Rokid-Nexus, Apache-2.0), consumed as the normal Maven artifact
`com.github.Anezium.Rokid-Nexus:bus-client:sdk-v0.27.0` (its `shared` and `ink-engine` come transitively). The pin is the `nexusSdk` value in `build.gradle.kts`. JitPack is added in
`settings.gradle.kts` for that one group only (`content { includeGroup("com.github.Anezium.Rokid-Nexus") }`), so no other dependency can come from it. First build needs the network once
(drop `--offline`); after that the Gradle cache serves it offline. To refresh: change `nexusSdk` to a newer `sdk-v*` tag, read the upstream `docs/PLUGIN_SDK.md` diff first (24 Oct to 14 Nov was six SDK versions),
rebuild online once, run `:nexus-port:testDebugUnitTest :nexus-bridge:testDebugUnitTest`. Fallback if JitPack is down: build the SDK offline from an upstream checkout at the tag (as in
`docs/superpowers/reviews/2026-11/nexus-spike-s0-s1-2026-11-17.md` S1: a module of the upstream worktree depending on `:bus-client`) and publish it to `mavenLocal()`.
Digests of the pinned artifacts (sha256): bus-client AAR `c1d87cae...cb743`, shared AAR `647d92fb...b9d1`, ink-engine JAR `5011949d...e23f`. No secret is vendored; nothing is checked in.
The SDK AAR is compiled with a newer Kotlin than the project's 2.0.21, hence `-Xskip-metadata-version-check` for this module only.

License: the Nexus SDK is Apache License 2.0, (c) 2026 Anezium (it also carries adapted code from alvarosw/Rokid-Nexus, Apache 2.0). Its NOTICE travels with the AAR; keep upstream's notice when redistributing the bridge APK.

## Manifest permissions (the whole list, pinned by `NexusBridgeManifestTest`)

`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` (normal, install-time, as in the SDK sample), plus a signature permission this app DEFINES, `io.github.pca.nexusbridge.permission.BIND_NEXUS_PORT`.
The merged APK also gets `io.github.pca.nexusbridge.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a self-defined signature permission that AndroidX adds. No INTERNET, storage, location, Bluetooth, camera, microphone or notification permission.
T5/T6 add NO permission (no microphone: Nexus owns audio) and one `<meta-data>` (the skills catalog). The PCA phone app declares `<uses-permission BIND_NEXUS_PORT/>` and a `<queries>` entry for `io.github.pca.nexusbridge` (T7); the phone-side ask and skills added NO manifest permission.

## Least privilege: what approving in the hub grants (decision, smoke 3 N4)

The manifest declares `surfaces,stt,tts,assistant` (+ the skills catalog). Findings from the upstream SDK (`docs/PLUGIN_SDK.md` sections 2, 3.3 and 4; `PluginPermissionsActivity`, `PluginGrantStore`, v0.27.0):

- The descriptor is static manifest metadata read by the hub when it scans packages. There is no API to change it at runtime, and a plugin cannot have two states of one ID. Changing the declared set of an installed plugin returns it to Pending (`PluginGrantStore.stateFor` compares requested capabilities), so a "turn voice on" toggle that rewrites the descriptor would force a fresh hub approval each time and would need a second plugin component or a second APK with its own ID.
- BUT the hub already offers a per-capability choice: Plugin access lists one switch per requested capability (Show on your glasses, Speech to text, Text to speech, Replace the glasses assistant), all pre-selected, and "Allow on glasses" approves only the ticked ones; the switches also apply live afterwards. A capability that is not granted answers `CAPABILITY_NOT_GRANTED`, which PCA already maps to "Capability denied".

Decision: option (b), the smallest safe one. The manifest keeps the four capabilities (no descriptor churn, no second APK, no Pending reset, NexusBridgeManifestTest pins it). Instead:

1. PCA's Rokid Nexus screen and the device checklist say plainly that approving the plugin in the hub can grant voice capabilities at the hub level (pre-selected) while PCA's switches keep them unused, and how to grant less: untick Speech to text, Text to speech and Replace the glasses assistant in Nexus > Settings > Plugin access BEFORE tapping Allow on glasses, and tick them later (live) only when switching Nexus voice or Speak answers on in PCA. Unticked, those features read "Capability denied" in PCA, as expected.
2. The bridge guard is already in place and now asserted (`AssistAskBridgeTest.aHubGrantAloneNeverOpensSttOrTts`, `BridgeRendererTest`): with no PCA switch the mask is 0, `speak` answers `DISABLED`, `assistOpened` opens no speech session and the assist takeover is released (`setAssistantTakeover(false)`); STT needs the `STT` and `ASSIST` bits, TTS needs the `TTS` bit, and a trip clears them.
3. Revisit with a separate user-installed voice variant only if a hub release lets a plugin change or narrow its own requested set without a Pending reset.

## Remove it

PCA works without it. Android Settings, Apps, "PCA Nexus bridge", Uninstall. Nothing is stored in the bridge. In Nexus, Settings, Plugin access, you can also deny it first.

## Trip ordering, and the residual window (v2 r3)

Privacy shutdown never depends on a remote call returning, and no content is sent after a trip beyond one small, bounded window.

- `trip()` first closes the phone side (epoch bump; queued shows are discarded), then takes the delivery lease EXCLUSIVE for at most 250 ms (a content send holds it SHARED for its binder call), then delivers `tripAt(epoch)` on its own threads for at most 500 ms. The bridge records the epoch and invalidates the listening generation before returning; `showAt` / `speakAt` with an older epoch are refused BEFORE anything is rendered or sent to Nexus. If a wait times out the trip goes on anyway. `trip()` returns within 750 ms whatever the bridge does; a tick re-sends an unconfirmed epoch.
- Content and privacy operations use separate lanes: a full content lane rejects shows, hides / stops / wipes never share it; a privacy operation the lane still rejects runs on a fresh thread. A withdrawn handle's queued show is cancelled and re-checked at execution.
- A show that was in flight when the wipe was acknowledged re-owes the wipe (on the acknowledgement, and again when the call returns).
- Accepted risk (residual window): a send that is already inside the binder when the trip lands (its epoch check done, the notice about to be drawn) can still draw. The bridge hides it again right after its draw, the phone hides the handle, and a wipe is owed. Maximum duration: the call timeout (2 s) plus, when the exclusive wait times out, the 250 ms wait. A hung call is declared unhealthy at the timeout; nothing further is sent during the backoff.
