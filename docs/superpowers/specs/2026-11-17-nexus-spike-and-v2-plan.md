# Rokid Nexus spike and v2.0 plugin plan (2026-11-17)

DESIGN only (Opus). Covers roadmap v1.3 row 2 (`plans/2026-11-16-roadmap-v1.1-v1.3.md`) and version table D in `specs/2026-11-16-place-guide-reuse-and-roadmap.md`: the spike lands in **1.4.0**, PCA as a Nexus plugin in **2.0.0**, the proactive agent loop in **3.0.0**. Nothing here was built, installed or run. Short paths: `NX` = `~/Desktop/rokid/rokid-resources/nexus/Rokid-Nexus` (clone at `15cd98ad`, 24 Oct; the latest release is 2.0.4 from 7 Nov, so this clone is **older than the release we would install**), `RC` = `~/Desktop/rokid/Rokid-Coder` (`release/1.0.0`), `AC` = `android-companion/`.

## 0. Summary

- Nexus is a **plugin host**. One hub runs on the glasses and draws everything. Each plugin is a **headless phone APK** that the user approves one capability at a time. Plugins need no glasses APK.
- PCA is the opposite. It has its own glasses app (capture, marks, popups, pause) and its own CXR-L link (`CxrGlassesLink`, `SessionType.CUSTOM_APP`).
- **The deciding unknown:** Nexus says only **one CXR-L session can exist on the phone**, and the phone hub owns it. Rokid-Coder already found that it and upstream Nexus cannot both hold the glasses link. Whether PCA's own session can run next to Nexus's is UNVERIFIED. Hardware spike S2 settles it, and the answer picks architecture A or B (section 3).
- Recommendation: a **1.5 to 2.5 Sd spike** (about 1 Sd of it needs no glasses), then 2.0 = **Option A, a side-car bridge APK** if the links can coexist. That is about 9 Sd. If they cannot, Option B (PCA's traffic rides the Nexus bus) adds about 6 Sd.

## 1. What Nexus gives (sources)

| Feature | What it is | Source |
|---|---|---|
| Plugin model | One exported service with action `com.anezium.rokidbus.action.PLUGIN`. Metadata: id, API_VERSION 3, CAPABILITIES, RECEIVE_PREFIXES. Installing grants nothing; the user approves each capability in Nexus → Settings → Plugin access. The hub can cold-start the service. | `NX/docs/PLUGIN_SDK.md` §2-3 (L36-147), §4 (L1899) |
| Headless rule | No `MAIN`/`LAUNCHER` filter, settings drawn with the NexusUi kit. Dormant unless a surface is open. Pins and notices may wake the plugin, push once and go dormant again. | `NX/docs/PLUGINS.md` L3, L98, L177-213 |
| HUD surfaces | Four kinds: **notice** (a discrete event: 1024 chars or 16 lines, TTL 2-45 s, up to 3 actions, paged, `hideNotice`), **pin** (one global last-writer-wins slot, 30-min default TTL), **activity** (a state machine; the platform picks its presentation), **surface** (cards, list rows, readers, editable fields; one exclusive slot, `SURFACE_BUSY`), plus Ink pages and images (`ink_surface`) | SDK L149-163, L723, L1064, L1147; `NX/bus-client/.../NexusPluginClient.kt` showPin L131, hidePin L144, showNotice L168, hideNotice L215 |
| STT | `stt` grant, `nexusSpeechSession`, utterance mode only, partials when the engine is realtime. Languages include `auto`/`en` (no Indic listed). Shares one audio lease, so a second user gets `DENIED_BUSY`. Plugins must never log transcripts. The engine is the hub's choice, and upstream includes BYOK cloud engines (RC design L212). | SDK §3.2 L1422-1538 |
| TTS | `tts` grant, `nexusTtsSession.speak` (1024 chars), one voice (a new utterance preempts), 5 commands/s. Plays **only on the glasses or earbuds, never the phone speaker**. Cancelled when the microphone is needed. | SDK §3.3 L1540-1630 |
| Assist button | `assistant` grant: the hub dismisses Rokid's native scene and opens the plugin with `openType = AI_ASSIST`. The wearer can pause the takeover from the phone. Button edges arrive on `onNexusGlassesAiButton`. | SDK §3.5 L1664-1685 |
| Skills v1 | A JSON catalog in `res/raw` (32 ops, 64 KiB) with typed ops, `effect` read/action and a `data` vocabulary. The wearer approves each op for each caller (in practice the Assistant plugin, which runs on the user's ChatGPT or BYOK model). 15 s per call, 16 KiB results, opaque `nexusRef` handles. | SDK §3.6 L1687-1814; `NX/BUSSPEC.md` "Skills v1" (~L2171-2330) |
| Free signals | `GLASSES_WORN` link bit and `/glasses/device-info` (battery, wearing, charging) | SDK L127-136 |
| Transport | JSON ≤3 KiB rides the CXR control plane, else SPP. Binary frames are SPP-only, are never queued, and fail with `NO_DATA_PLANE` while SPP is down; Binder delivery is capped at 512 KiB. | BUSSPEC L8-12, L48-67 |
| Input | Triple tap is the only gesture Nexus claims. With a native app in front and the Nexus session closed, tap, swipe and back go to the app underneath. | BUSSPEC L1611-1630; `NX/README.md` L124 |
| Glasses-side clients | The glasses-client-probe binds into the glasses hub through `bus-client` (`hubTarget`) | BUSSPEC L40-44; `NX/TESTPLAN.md` L98-180 |
| Debug | Developer mode turns on a Bus inspector (traffic and rejections). Debug hubs have adb probes. Normal use needs no adb. | SDK L1925-1945 |

How Nexus differs from PCA's glasses app. PCA's glasses app is a native app on the glasses that owns the camera loop, marks, popups (`AC/glasses/.../overlay/PopupOverlay.kt`, `PlacePopupTracker.kt`) and pause (`glasses/pause/`). It talks to the phone over PCA's own `pc_*` channels (`AC/core/model/.../Protocol.kt`, `PROTOCOL_VERSION = 1`). Nexus is a platform: it owns the CXR link, the SPP socket, the overlay renderer, speech, and an accessibility service on the glasses. Rokid-Coder **embeds** the Nexus cores in its own app and deliberately has **no** plugin host (`RC/docs/coding-agent/claude/design/2026-11-15-v1-product-design.md` L272-275, L293-295; `RC/mobile/README.md` "If Rokid Nexus is installed"). So PCA cannot be a plugin inside Rokid-Coder, and Rokid-Coder and upstream Nexus are mutually exclusive on one pair of glasses. Prior PCA notes: `plans/handoffs/2026-11-10-full-handoff.md` §12 (L172-176), `rokid-resources/learning/nexus-platform.md`, `learning/pca-integration-roadmap.md`.

## 2. Spike (1.4.0, 1.5-2.5 Sd + about half a day of the user wearing the glasses)

All spike code goes in `spikes/nexus/` (outside the Gradle build of `android-companion`). Never uninstall PCA (its SQLCipher key lives in the Keystore). Back up first, as in the 2026-11-10 handoff install plan.

| # | Work | Where | Sd | Output |
|---|---|---|---|---|
| S0 | Update the NX clone to the 2.0.4 tag and current `sdk-v*` (read-only fetch into `rokid-resources`), and diff PLUGIN_SDK/BUSSPEC since `15cd98ad`. Re-check the verified APK digests (`nexus-phone-2.0.4` a4474abc…, signer CN=Anezium). | desk | 0.25 | Changed-API list; pinned SDK version |
| S1 | Throwaway headless probe plugin `io.github.pca.nexusprobe`: `surfaces,stt,tts,assistant` plus a 4-op skills catalog (`status`, `pause_capture`, `resume_capture`, `mark_moment`) with canned answers. It shows a notice, a pin and a list surface, speaks a TTS line, and opens a speech session. | **emulator** (API 34 AVD, as RC's emulator tests did) | 0.75 | Discovery → Pending → approve; catalog accepted by Plugin access; per-op consent screen; Bus inspector journal; with no link: `CAPABILITY_NOT_AVAILABLE`/`DENIED_NO_LINK` paths. Whether the phone hub starts without Hi Rokid or a CXR link is UNVERIFIED (if it refuses, run S1 on the real phone with the glasses off). |
| S1b | Desk check: can a **third-party glasses app** be a glasses-side bus client (approval model, `hubTarget = GLASSES`)? Needed only for Option B. | desk | 0.25 | yes/no with file refs |
| S2 | **Coexistence gate.** Install Nexus phone 2.0.4 and onboard it (glasses hub plus its accessibility service). With PCA's hub running, start the Nexus hub, then try the reverse order. Does PCA's CXR-L CUSTOM_APP session (`CxrGlassesLink.kt` L273-275, `AiInterceptMode.ALLOW_WITH_PAUSE`) survive? Do frames, marks and `pc_state` still flow? Also check PCA double-tap vs Nexus triple-tap, PCA popup vs a Nexus notice band (which one sits on top), the assist button with PCA's intercept vs the Nexus takeover, and PCA capture while a Nexus surface is open. | **hardware** | 0.5 + user | GO-A (both links live), GO-B (exclusive), or NO-GO |
| S3 | Quality: 10 fixed utterances (with Indian place names), Nexus STT vs PCA push-to-talk (`glasses/voice/PushToTalk.kt`), measuring accuracy and latency; TTS loudness on the glasses; 60-min battery and thermal delta, Nexus hub idle vs stopped, with PCA capturing. | **hardware** | 0.25 + user | Table; keep-or-drop for STT and TTS |
| S4 | Spike report `docs/superpowers/reviews/2026-1x/nexus-spike.md` with the A/B decision and updated estimates | desk | 0.25 | report |

Testing without glasses works for S0, S1, S1b and for all the PCA-side logic (JVM tests against a fake port, the same way `FakeGlassesLink` stands in for `GlassesLink`). One limit: upstream has no public fake transport. `NexusPluginClient.createWithTransport` exists only as a Rokid-Coder patch (`RC/vendor/nexus/PATCHES.md`, fcf42a11), so PCA should test its own `NexusPort` interface instead. **These need hardware:** CXR-L coexistence, how anything renders, touchpad and assist button, STT/TTS quality, battery and heat, the accessibility onboarding, and SPP binary throughput (Option B).

## 3. Integration architecture

**What stays on the phone in PCA's main app, for every option:** capture ingest, the RAG core, AnswerService, nudges, Place Guide, Forget, the canvas, and **every privacy gate**. Nexus never gets a database handle. It only ever receives text that has already passed the gates.

**Option A, side-car (if S2 = GO-A; recommended).** PCA's glasses app and link stay as they are: capture, marks and pause are unchanged. A new **headless bridge APK** `:nexus-bridge` (PCA signer, no launcher, about 1 kLoC) is the Nexus plugin. It binds to PCA's phone app over a **signature-level** bound service (`NexusPort`, plain AIDL) and is a dumb renderer and relay, with no data and no logic. It is a separate APK because the headless rule (PLUGINS.md L98) forbids PCA's launcher. That also keeps `bus-client` out of the PCA APK and makes Nexus strictly optional.

| PCA thing | Nexus kind | Notes |
|---|---|---|
| Nudge, place card | notice, actions Dismiss / Mark / More | TTL = `HudSettings.popupMs()` (3-20 s, within Nexus's 2-45 s range); `wakeDisplay` only at MAX verbosity |
| Ask answer | notice `lines` (+ optional TTS) | The answer cut to 1024 chars, citations as a footer |
| Capture state (on, paused, zone-paused) | pin, refreshed before its TTL | Text only, no place name while zone-paused |
| Mark confirm | notice | Reuses the `MarkPhotoReason` tokens (`pause_zone` etc.) |
| Assist-button ask | `AI_ASSIST` open → `nexusSpeechSession` → PCA voice backend (text in, text out, `phone/voice/VoiceGraph.kt`) → answer notice | Off by default (the Nexus takeover switch). Nexus's own Assistant stays one launcher pick away. |
| Skills (provider) | `status`, `pause_capture`, `resume_capture`, `mark_moment` | All `effect: action` or status-only: **no memory content leaves PCA** in 2.0 |

To keep a card from showing twice, add a **"HUD host: PCA glasses app | Nexus"** setting. When it is Nexus, `pc_settings` gains an additive field `hudHost` (default `PCA`; old glasses ignore it), and the glasses app stops drawing popups while capture goes on. `PROTOCOL_VERSION` stays 1.

**Option B, Nexus transport (if S2 = GO-B).** PCA's phone hub drops its own CXR-L session whenever Nexus mode is on. A new `NexusGlassesLink : GlassesLink` (`AC/services/glasses-link/.../GlassesLink.kt`) carries the **same** `pc_*` JSON as plugin messages on `/plugin/pca/<channel>`. Frames go as SPP binary frames (≤512 KiB; PCA already has `pc_frame_ack` and the received-frame ledger for drops, and Nexus never queues). PCA's glasses app becomes a glasses-side bus client (needs S1b = yes). The message schemas do not change, so PROTOCOL_VERSION stays 1. Everything else is as in A. **Option C** (embed the Nexus cores the way Rokid-Coder does) makes PCA a host, not a plugin. It would clash with Nexus and Rokid-Coder and is out of scope for 2.0.

**Privacy: one choke point, `NexusHudSink`, inside PCA's phone app.** Every Nexus-bound notice, pin, surface, TTS line and skill result goes through it, and it is the only caller of `NexusPort`:
1. **ProvenanceGate** (`AC/services/orchestration/.../provenance/ProvenanceGate.kt` L33): every cited memory, frame or mark id must be live (`liveIds`). Any doubt drops the item (fail closed), the same rule as SHOWN today.
2. **ZoneFence / AskZoneFence** (`AC/services/memory/.../place/ZoneFence.kt` L16-24, `AC/services/orchestration/.../AskZoneFence.kt` L21-26): while the fence is held or blocked, nothing is sent and TTS is refused. Sends go through `ZoneFence.act(ticket)` (check, then act, under the lock).
3. **Pause zones and pauses** (`AC/phone/.../zone/ZoneController.kt`, `glasses/pause/GlassesPauses.kt`, quiet hours): no proactive notice or TTS while paused. Skills return the domain code `pause_zone` or `paused`.
4. **Withdraw on close:** a zone hold, Forget, Delete all, pause, or Nexus mode switched off makes the sink call `hideNotice`/`hidePin`/surface `hide`/`tts.stop` for **every** live PCA handle it tracks. A test proves no handle survives a fence trip.
5. Not worn (`GLASSES_WORN` or PCA's wear signal): no notice, no TTS.
6. No transcript, card text or answer text in logs (PcLog rules plus SDK L1497). The bridge persists nothing.

Trust statement for `PRIVACY.md`: the Nexus hubs are third-party code (one maintainer, SDK below 1.0). They see every text PCA sends to them and every transcript. Skills that return memory text would flow on to the Assistant's model provider, which is why those skills stay out of 2.0.

## 4. v2.0 scope, cut line, risks, effort

**In 2.0 (Option A):** the bridge APK; notices for nudges, place cards, answers and marks; the status pin; TTS for answers (opt-in); assist-button ask through Nexus STT (opt-in); the 4 action skills; the sink with withdraw; the HUD-host setting; coexistence UX. **Cut to 2.x:** an Activity list surface; `search_memory`/`ask` skills that return content; PCA as a skills **client** (Agenda, Transit…); Ink pages; the Nexus camera; Store/RokidBrew listing. **If S2 = GO-B:** 2.0 = Option B and TTS/STT move to 2.0.1, or 2.0 slips by about 6 Sd (user decides, Q1). **If NO-GO:** 2.0 is shelved and the stt/tts/HUD ideas go into PCA's own glasses app.

**Risks.** (1) CXR-L exclusivity (the S2 gate). (2) SDK churn: one maintainer, 2.0.0 to 2.0.4 in 8 days, our clone is already behind. Pin the SDK, and add a weekly diff check like RC's upstream sync. (3) The privacy egress surface: the Nexus hubs, the BYOK STT engines, and Assistant's model if content skills are ever added. (4) HUD contention: the one pin slot, the one surface slot (`SURFACE_BUSY`), notices that cannot be queued while the link is down. (5) Gestures and the assist button: PCA's `AiInterceptMode` vs the Nexus takeover, and Hi Rokid AIUI keeping the microphone (RC compat report L138). (6) Battery and heat of two hubs plus capture. (7) Rokid-Coder and Nexus/PCA-on-Nexus cannot run together on one pair of glasses. (8) Firmware changes to the accessibility service or CXR.

**Effort (Sonnet-days).** Spike 1.75 Sd + user hardware time. Option A 2.0: **about 9 Sd** plus 2-3 Codex rounds. Option B adds **about 6 Sd** (B1-B4).

| Task | Sd | Acceptance criteria |
|---|---|---|
| T1 `:nexus-bridge` skeleton: headless manifest, descriptor (`surfaces,stt,tts,assistant`), signature-permission bind to PCA, NexusUi settings with an uninstall row | 0.75 | Builds with the pinned SDK; no LAUNCHER (manifest test); an unrelated app cannot bind `NexusPort` (instrumented test, the RC pattern) |
| T2 `NexusPort` AIDL + `NexusHudSink` with gates 1-3 and 5 | 1.5 | JVM: a forgotten cite, a held fence, a pause or not-worn each drop the item, and a gate error drops it too (fail closed); `ProvenanceChokePointTest`-style test: no `NexusPort` call outside the sink |
| T3 HUD mapping (the table above), TTL clamp, notice actions → PCA actions (Dismiss, Mark, More opens the phone) | 1.0 | Golden tests per kind; text ≤1024; action ids idempotent; `SURFACE_BUSY`/`CAPABILITY_NOT_AVAILABLE` give up quietly (no retry loop) |
| T4 Withdraw on close (gate 4), handle registry | 1.0 | Every live handle is hidden, and TTS stopped, within one tick of a zone hold, Forget, Delete all, pause or mode-off; a fuzz test of interleaved shows and trips leaves zero handles |
| T5 Assist-button ask: `AI_ASSIST` → speech session → voice backend → answer notice and TTS | 1.5 | Fake speech callbacks: final → one ask; `DENIED_BUSY`/`NO_SPEECH` → a short notice; zone held mid-ask → `ZONE_PAUSED`, nothing shown or spoken; no transcript in logs (log-capture test) |
| T6 Skills catalog (4 ops) + provider | 1.0 | Catalog passes a validator for the bounded schema subset (unit test); each op answers within 15 s; pause/zone → domain fail code; `mark_moment` reuses the mark path and its refusals |
| T7 HUD-host setting + additive `pc_settings.hudHost` | 0.75 | Old glasses decode the new `pc_settings` (compat test); the glasses suppress popups when the host is Nexus; `PrivacyInvariantsTest` still finds `PROTOCOL_VERSION = 1` |
| T8 Coexistence UX: detect `com.anezium.rokidbus.phone` and Rokid-Coder, approval-state screen, PRIVACY.md trust text | 0.75 | Each state (not installed, Pending, approved, capability denied, hub stopped) has a screen string; no automatic installs |
| T9 Device checklist + bridge release signing (PCA key, the 1.3.0 lineage) | 0.75 | A checklist of 15 or fewer items covering S2's combinations; signed bridge installs over itself; user-run |
| B1-B4 (Option B only): `NexusGlassesLink`, glasses app as a bus client, frames over SPP binary + ack, tests | 6.0 | The existing `GlassesLink` contract tests pass on the Nexus link; frame drop or retry matches the ledger; schemas unchanged |

## 5. Open questions for the user

1. If S2 shows the links are **exclusive**: Option B in 2.0 (+6 Sd), or ship a smaller 2.0 and move voice to 2.0.1?
2. **Rokid-Coder vs PCA-on-Nexus:** both cannot hold the glasses. Is one at a time acceptable, or should Rokid-Coder later expose its embedded core to PCA (this reverses RC ruling U31)?
3. **Content skills** (`search_memory`, `ask`) send memory text to the Assistant's model provider. Never, 2.x behind an opt-in, or 2.0?
4. **STT engine:** accept Nexus's hub engine (possibly cloud BYOK, no Indic listed) for asks, or keep PCA push-to-talk as the only voice path and use Nexus for TTS and HUD only?
5. **Default HUD host** when both are available: PCA's overlay (recommended until S3) or Nexus?
6. **Distribution:** the bridge APK on GitHub only, or also the RokidBrew registry / Nexus Store?
7. **When can you do the hardware half-day** (S2, S3)? It is after the Sri Lanka build at the earliest, and it means installing Nexus 2.0.4 next to PCA (backup first, never uninstall).

## 6. S0/S1 findings (2026-11-17, no glasses)

Full evidence: `reviews/2026-11/nexus-spike-s0-s1-2026-11-17.md`. Nothing above was edited.

- **Upstream moved.** The clone `15cd98ad` is 680 commits behind. The latest release is **v2.6.0 (14 Nov, SDK `sdk-v0.27.0`)**, not 2.0.4. Re-pick the version to install and its APK digests before S2. The digests in S0 above were not re-checked (no download).
- **Spec lines.** BUSSPEC L8-12 (one CXR-L session, the phone hub owns it) is unchanged, so S2 is still the deciding gate. The manifest and approval model are unchanged, plus an optional `LAUNCHABLE` key, a `specialUse` foreground-service declaration in the sample, and new per-plugin switches in Plugin access. Skills v1 limits and parser are byte-identical, with one new refusal (`SKILL_LEASE_DENIED`). Notices, pins and activities are unchanged; **card immersions are deprecated** and browsable content is now a **page**.
- **Q-section answers from the probe.** The phone hub **starts without Hi Rokid or glasses** (emulator, debug build of v2.6.0). Discovery gives Pending, then Allow gives Approved, with no further prompt after a hub restart or a plugin reinstall. With the link down, approved surface calls return `CAPABILITY_NOT_AVAILABLE` (not `DENIED_NO_LINK`), and `NOT_REGISTERED` before the hub registers the plugin (a push cold-starts the hub in about 1.5 s). The 4-op catalog is accepted once `data` is non-empty (`["status"]`) and string outputs are enums.
- **The SDK resolves offline** only by building `bus-client` from an upstream worktree (JitPack artifacts are not cached). T1 should pin a published tag and fetch it normally.
- **Plan edits to carry into 2.0:** T6 declares `data: ["status"]` and validates with the shared `SkillCatalogParser`; T3/T8 treat `CAPABILITY_NOT_AVAILABLE` and `NOT_REGISTERED` as quiet give-ups; future list UI should be a launcher page.
- **Still open (hardware or other):** S2 coexistence, S3 STT/TTS and battery, how notices and pins render, the per-operation Skills consent screen (needs the Assistant plugin as a caller), the Bus inspector, S1b. Open questions 1-7 above are unchanged; the 2026-11-17 rulings (one at a time, PCA default HUD, no content skills, no Nexus STT/TTS, GitHub only) stand.
