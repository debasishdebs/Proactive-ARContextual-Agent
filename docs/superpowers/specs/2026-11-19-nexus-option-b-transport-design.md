# Nexus Option B: PCA traffic through the Nexus hub link (design, 2026-11-19)

DESIGN only. Nothing here was built, installed or run. Nexus is P2 (user ruling); this note exists so that a failed S2 does not leave the line without a plan. It extends `2026-11-17-nexus-spike-and-v2-plan.md` (section 3, Option B) and `plans/handoffs/nexus-device-checklist.md` (S2, steps 4-8). Code facts below come from `android-companion/` at commit 5e288bb3: `services/glasses-link` (`GlassesLink.kt`, `CxrGlassesLink.kt`, `ClockOffset.kt`), `nexus-port` (`NexusHudSink`, `NexusDispatcher`, `HudEvent`), `nexus-bridge` (`NexusBusClient`, `NexusPortService`), `core/model/.../Protocol.kt`, glasses `link/HudController.kt`.

## 0. What exists today, and why it matters here

- The v1.5 line (Option A, built behind OFF flags) already sends **cards, pin, answers and TTS through the Nexus hub**: the phone app calls `NexusHudSink`, which calls a signature-bound AIDL service in the headless `:nexus-bridge` APK, which calls the Nexus hub via `bus-client`. No glasses-side Nexus code exists.
- Everything else still rides **PCA's own CXR-L session** (`CxrGlassesLink`, `SessionType.CUSTOM_APP`, `AiInterceptMode.ALLOW_WITH_PAUSE`): `pc_hello` (clock offset), `pc_settings`, `pc_state`, `pc_frame` and `pc_frame_ack` (capture upload), `pc_wear`, pause and resume records, mark requests. The glasses app is a native app with its own camera loop.
- So "cards go through Nexus" is not the hard part. The hard part is that **the glasses app cannot work without its own link**: with no PCA session there is no capture upload, no pause sync, no clock offset.

## 1. The S2 question and its three outcomes

**Exact question.** With PCA's CXR-L CUSTOM_APP session open and capturing, does the Nexus phone hub (v2.6.0 or newer) also hold its CXR-L session to the same glasses at the same time, in either start order, and does each side survive the other disconnecting and reconnecting? Nexus BUSSPEC L8-12 says one CXR-L session per phone, owned by the hub. Whether that is a hard SDK limit or a Nexus design rule is UNVERIFIED.

| Outcome | Meaning | v1.5 consequence |
|---|---|---|
| 1. Both hold the session | Coexistence. Option A (what is built) is the answer. | v1.5 proceeds as planned. Only the layering and recovery checks (steps 6-8) remain. |
| 2. PCA's session drops when the hub connects | The hub wins. Cards could still reach the glasses, but the PCA glasses app loses capture, pause sync and `pc_state`. PCA is effectively off while Nexus mode is on. | Option A is **not shippable as built**. Choices: (a) Option B full transport (section 2), (b) shelve v1.5 and keep PCA's own HUD (zero loss of 1.x), (c) a "Nexus mode" that is knowingly capture-off (not recommended: it defeats the product). |
| 3. The hub's session drops when PCA connects | PCA wins. Nexus gets no link, so every bridge call returns `CAPABILITY_NOT_AVAILABLE` (the quiet give-up path is already built). | v1.5 is **dead on arrival**; nothing short of Option B saves it, and Option B would need PCA to release its own link, which is outcome 2 by design. Shelve; PCA's own HUD is unaffected. |

Also record, whichever outcome: "flapping" (each side taking the link back from the other in a loop). That is worse than a clean drop, because PCA's existing recovery code (`GlassesRelauncher`, `reconnectAutomatic`, the watchdog) would fight the hub. If flapping is seen, treat it as outcome 2 or 3 and stop; do not try to arbitrate.

Only outcome 2, and only if the user wants Nexus anyway, justifies Option B. Outcome 3 and a flapping result argue for shelving.

## 2. Option B: PCA's channel rides the Nexus hub link

Principle: PCA stops opening its own CXR-L session in Nexus mode. The `pc_*` JSON messages and frames travel as Nexus plugin traffic. The privacy gates stay on the phone, unchanged.

### 2.1 Component diagram (text)

```
 GLASSES                                              PHONE
 +---------------------------+                        +--------------------------------------------+
 | PCA glasses app (native)  |                        | PCA phone app                              |
 |  capture / pause / marks  |                        |  hub, RAG, ask, gates, ZoneFence, pauses   |
 |  GlassesRuntime           |                        |   |                                        |
 |   |  GlassesSide transport|                        |   |  GlassesLink  (interface, unchanged)   |
 |   v  (new: Nexus impl)    |                        |   v                                        |
 |   |                       |                        |  NexusGlassesLink (new, replaces Cxr...)   |
 | glasses bus-client        |                        |   |  pc_* JSON + frame bytes               |
 |  (hubTarget = GLASSES)    |                        |   v  NexusHudSink / NexusDispatcher        |
 +------------|--------------+                        |  NexusPort AIDL (signature-bound)          |
              |                                       +---|----------------------------------------+
              |                                           v
 +------------v--------------+   CXR-L control plane   +------------------------------+
 | Nexus glasses hub         |<=======================>| :nexus-bridge APK (plugin)   |
 |  (draws notices/pins)     |   SPP binary (frames)   |  bus-client, plugin id       |
 +---------------------------+                         +--------------|---------------+
                                                                      v
                                                          Nexus phone hub (owns the one CXR-L session)
```

Cards and the pin stay on the existing HudEvent path (the hub draws them). The new part is the **second path**, the PCA protocol bus, from the glasses app to the phone through the same hub.

### 2.2 What the glasses side needs

- The PCA glasses app becomes a **glasses-side bus client**: `bus-client` with `hubTarget = GLASSES`, sending to `/plugin/pca/<channel>`. S1b (desk check: may a third-party glasses app do this, and what is its approval model) is **unanswered**; section 3 makes it the first go/no-go.
- A `GlassesSide` transport interface in the glasses `link/` package with two implementations: the current CXR custom-command one and the Nexus one. Today `GlassesRuntime` talks to CXR directly, so this extraction is real work and must keep non-Nexus behaviour byte-identical.
- Frame upload as SPP **binary frames** (Nexus: not queued, fail with `NO_DATA_PLANE` while SPP is down, Binder cap 512 KiB). PCA already has `pc_frame_ack` and the received-frame ledger, so a failed send is a retry from the glasses buffer, not a silent loss. JSON over 3 KiB also goes to SPP, so `pc_settings` and `pc_hello` with big pause lists must be size-checked.
- Wear signal and open/close lifecycle: PCA gets `wearingStatus` and session callbacks from CXR today (`CxrGlassesLink` around line 290). Through Nexus the free signals are `GLASSES_WORN` and `/glasses/device-info`. Mapping them to PCA's `pc_wear` is new and UNVERIFIED.
- Input: PCA double-tap vs the Nexus triple-tap claim, and the assist button (`AiInterceptMode.ALLOW_WITH_PAUSE` means nothing without PCA's own session): both re-tested.

### 2.3 What the phone side needs

- `NexusGlassesLink : GlassesLink` (the interface in `GlassesLink.kt` stays; its contract tests must pass). It maps `state`, `events`, `ready`, `send`, `wearing` onto the bridge's inbound and outbound streams. `connect*` and `reconnect*` then mean "ask the hub", not "open a CXR session"; the relauncher guards and hourly cap stay but must not fight the hub (Nexus owns link recovery).
- `NexusPort` AIDL grows from "render a HUD command" to a **bidirectional message pipe**: `sendPc(channel, bytes)` out and an inbound callback. This widens the bridge from "dumb renderer, no data" to "carries raw frames and the capture stream". The signature-level bind and `SignerTrust` stay, but the PRIVACY.md statement changes: the Nexus hubs would then see **camera frames**, not only text. That is a user decision (section 4).
- `ClockOffset` and the `pc_hello_request` rate limiter must work over the new link (2.7).
- `HudHost`: in Option B the setting is moot while Nexus mode is on; keep the field for wire compatibility.

### 2.4 Pause and zone withdrawal, and the delivery lease

- **Card path (unchanged).** The sink gates (ProvenanceGate, ZoneFence/AskZoneFence, pauses, not-worn), the shared/exclusive **delivery lease** in `NexusDispatcher` (content sends hold shared, a trip takes exclusive with a bounded wait), withdraw-on-trip of every live handle, the notice-history wipe and the hung-bridge residual window are as built.
- **Capture path (new).** Today a zone trip stops capture by the phone telling the glasses and by the phone refusing frames (`FrameAdmission`). In Option B a trip must still reach the glasses when the hub is slow. The lease idea extends: a pause/zone/Forget **command** to the glasses is a privacy call that goes out under the exclusive lease and is retried until acknowledged; a frame arriving after a trip is dropped by phone admission exactly as now (the phone stays authoritative). The glasses' local pause (double tap) still applies on the glasses first (spec 5.5).
- **New failure mode.** If the hub is hung or SPP is down, the glasses cannot upload and cannot receive a pause. They keep their local buffer and local pauses; the fail-closed rule is: no link means no upload, and buffering continues only within the existing buffer limits. The "hung hub" residual (a call already inside the hub cannot be cancelled) now also covers inbound pause commands. No bound can be promised; it is only observable on hardware.

### 2.5 Privacy lane

- Content gates stay on the phone and in the one choke point (`NexusHudSink`). The new pipe is a second egress and ingress; it must pass the same choke point (extend `NexusChokePointTest`: no `NexusPort` call outside the sink and the new link).
- Frames and text now transit a third-party hub (single maintainer, SDK below 1.0). PCA sends frames nowhere in the cloud itself, but the Nexus hubs' own logging and the Bus inspector (developer mode) could record payloads: UNVERIFIED, to be read in Nexus source and checked with the inspector on, before any frame is sent.
- No frame bytes, text or transcripts in logs (PcLog rules and `NexusLogContentScanTest` extend to the new link).

### 2.6 Capture and camera ownership

PCA's glasses app keeps owning the camera (the Nexus camera stays out of scope). The risk is a second camera user: if the Nexus glasses hub or an app it launches takes the camera while PCA captures, frames stop. S2 step 7 already asks this. In Option B nothing tells Nexus about PCA's camera use, so this is observed, not enforced.

### 2.7 Who owns the hello and the clock offset

The glasses own the wall clock and send `pc_hello` with `glassesNowMs`; the phone computes `ClockOffset.fromHello(glassesNowMs, phoneReceivedAtMs)`, ignoring one-way latency (30-60 ms measured on CXR). Over the hub the latency is unmeasured (extra hops, possible SPP detour, queueing), so the offset could be off by seconds under load, which matters for **pause record times** (`recordToPhone`, `recordToGlasses`). Proposal: the **phone stays the owner** (it issues `pc_hello_request`, computes the offset, keeps `anchoredAt` as the fail-closed fallback), and the glasses stamp `glassesNowMs` as late as possible before send. Add an RTT sample (request out, hello back), use `phoneReceivedAtMs - rtt/2`, and refuse offsets whose RTT exceeds a bound (suggest 2 s, to be tuned) so a pause is never shortened. This is a suggestion; it is untested.

## 3. Minimal spike plan (emulator, fake hub, no hardware)

Goal: prove the PCA side of Option B against a **fake hub**, and find out what the real SDK forces, before spending a hardware half-day. It cannot prove coexistence, rendering, SPP throughput, timing or battery.

| # | Work | Where | Sd |
|---|---|---|---|
| B0 | Desk S1b: read upstream glasses-client code and BUSSPEC (`hubTarget`, approval model): can a third-party glasses app be a bus client at all? If no, Option B is impossible; stop. | desk | 0.25 |
| B1 | Extract the `GlassesSide` transport in the glasses app behind the current CXR implementation; behaviour unchanged. | JVM | 0.75 |
| B2 | `NexusGlassesLink : GlassesLink` and the bidirectional `NexusPort` pipe, against a `FakeHub` (in-memory; configurable latency, drop, queue limit, `NO_DATA_PLANE`, 3 KiB and 512 KiB caps). | JVM | 1.0 |
| B3 | Glasses-side Nexus transport and the capture path through FakeHub on an emulator (frame, ack, retry, buffer). | emulator | 0.75 |
| B4 | Clock offset and pause over FakeHub (RTT-aware) plus fault injection. | JVM | 0.5 |
| B5 | Debug-only spike APKs (not the release build), notes, estimate update. | desk | 0.25 |

**Total about 3.5 Sd** (about 2.5 Sd if B0 gives a partial yes and B3 is skipped). **Test list**, all against FakeHub:
1. The existing `GlassesLink` contract tests pass on `NexusGlassesLink`.
2. `pc_*` round trip for every channel in `Protocol.kt`; JSON over 3 KiB takes the SPP path; a frame over 512 KiB is refused cleanly.
3. `NO_DATA_PLANE` and a full-queue hub: the frame stays in the glasses buffer, is retried, is never duplicated (ledger) and never silently lost.
4. A trip during a send: the exclusive lease closes, the in-flight frame is dropped by phone admission, the pause reaches the glasses and is acknowledged; a fuzz of interleaved shows and trips leaves zero handles and no admitted frame after a trip.
5. Hung hub (blocking call): the timeout fires, content stays blocked until a privacy call returns, and the late-delivery residual is recorded, not hidden.
6. Clock: hello with RTT 20 ms, 800 ms and 5 s: offset used, used, refused (fail-closed `anchoredAt`); pause records never shortened.
7. Hello re-request rate limit and reconnect storm: the relauncher does not loop against a hub reporting `CAPABILITY_NOT_AVAILABLE`.
8. Choke point test: no `NexusPort` call outside the sink and the new link; the log scan finds no frame or text bytes.
9. Old glasses with a new phone, and new glasses with an old phone, still decode (`PROTOCOL_VERSION` stays 1).

**Does the earlier "+1 day" note hold? No, not for full transport.** The device checklist heading says the Option B transport is "about +1 day". The 2026-11-17 spec already priced B1-B4 at **6 Sd**, and that was before the glasses-side extraction, the bidirectional pipe and the clock-over-hub work were itemized. "+1 day" holds only for a cards-only variant, and that variant is Option A (cards ride the bridge today), so it saves nothing when PCA's own session drops. Honest numbers: spike about 3.5 Sd; full Option B after it about 5-7 Sd (consistent with 6 Sd; the glasses-side bus client is the big unknown and could push it higher); plus a hardware half-day. The checklist line should be corrected (not done here: this task commits only this file).

## 4. Risks and what the user must decide

Risks:
1. S1b may be a no: a third-party glasses app might not be allowed as a bus client. Then Option B does not exist.
2. Trust widening: raw frames transit third-party hubs; the bridge turns from a renderer into a data pipe.
3. SPP binary frames are never queued and fail while SPP is down; throughput and battery under continuous capture are unmeasured.
4. Clock and pause timing over an unmeasured path (2.7).
5. Two recovery owners fighting (Nexus hub, PCA relauncher and watchdog).
6. SDK churn (one maintainer; the local clone is 680 commits behind) and the offline SDK build issue (JitPack artifacts are not cached).
7. Rokid-Coder and Nexus cannot share the glasses (already ruled: one at a time).
8. Schedule: competes with the trip builds; the hardware half-day is after Sri Lanka at the earliest.

Decisions for the user:
1. If S2 returns outcome 2: pay for Option B (spike about 3.5 Sd, then about 6 Sd) or shelve v1.5? Recommendation: shelve unless Nexus voice is wanted badly, because PCA's own HUD works and Nexus is P2.
2. Is it acceptable for third-party Nexus hubs to carry camera frames (a privacy statement change)? If not, Option B is out by definition.
3. Run the B0 desk check (0.25 Sd) now, so outcome 2 is not discovered first on hardware?
4. Correct the checklist's "+1 day" line?

## 5. What is NOT verified

- No hardware run and no Nexus hub run for S2: all three outcomes are hypotheses. BUSSPEC's one-session statement was read, not tested; flapping is a guess.
- S1b is unanswered: that a third-party glasses app can be a glasses-side bus client is an assumption.
- Nothing in section 2 is built or tested; the diagram is a design.
- Latency, RTT, SPP throughput, `NO_DATA_PLANE` frequency, battery and heat over the hub are unmeasured; the 2 s RTT bound is a guess.
- Whether the Nexus hubs or the Bus inspector log payloads is unread.
- The mapping of `GLASSES_WORN` and device-info to PCA's wear signal, the double-tap vs triple-tap behaviour and the assist button are unverified.
- The estimates (3.5 Sd, 5-7 Sd) are judgement from the code read, not measured; the glasses `GlassesRuntime` was only skimmed, not read in full.
- Source was read at commit 5e288bb3 and the 2026-11-17 notes (Nexus clone 680 commits behind v2.6.0); the current upstream SDK was not re-read.

## 6. B0 desk check result (2026-11-19): can a third-party glasses app be a bus client of the Nexus hub?

Desk only; no device, no build. Sources: the local Nexus clone `~/Desktop/rokid/rokid-resources/nexus/Rokid-Nexus` (HEAD 15cd98ad, 24 Oct) and the scratch worktree at `sdk-v0.27.0` (`~/Desktop/rokid/scratch/nexus-sdk-v0.27.0`), plus this repo.

**Evidence against (decisive on paper).**
- Nexus `BUSSPEC.md` L260-264 (identical text and lines at the clone and at `sdk-v0.27.0`): "Phone approval does not authorize an arbitrary glasses-side companion; release glasses hubs remain closed to those clients until companion provisioning has its own identity design." Release glasses hubs refuse unknown external glasses-side clients; no glasses-side plugin approval model like the phone's was found (`docs/PLUGIN_SDK.md` at `sdk-v0.27.0` was only grepped for "companion" and "glasses-side", not read in full).
- `BUSSPEC.md` L260-262: legacy `register(clientId, prefixes, callback)` stays only for "same-UID hub internals and explicit debug-probe compatibility"; release hubs reject unknown external legacy callers.
- `android-companion/nexus-bridge/README.md` L1-5, L16: PCA's only Nexus integration is a phone-side plugin; no glasses-side Nexus code exists (section 0, L7 of this doc says the same).

**Evidence for (weak).**
- `BUSSPEC.md` L44: `:glasses-client-probe` (`com.anezium.rokidbus.clientprobe`) is a sample glasses-side client of `:bus-client`, and `2026-11-17-nexus-spike-and-v2-plan.md` L26 cites `hubTarget` for it. That shows the client library supports a glasses target for a probe, not that a release glasses hub accepts a third-party app.
- Rokid-Coder runs glasses-side code with Nexus, but it is a Nexus fork with its own hubs, so it does not show a third-party app working against the upstream release glasses hub. How it provisions is UNVERIFIED.

**Unknown, needs the glasses (all UNVERIFIED).**
1. Whether a debug-signed or probe-style PCA glasses client is accepted by the upstream v2.6.0 release glasses hub, or only by a debug-built hub.
2. Whether a "companion provisioning" mechanism shipped after `sdk-v0.27.0` (not re-read past that tag; the clone is 680 commits behind upstream, per `reviews/2026-11/nexus-spike-s0-s1-2026-11-17.md`).
3. Whether the wearer could provision PCA by hand (an identity or allow-list step on the glasses hub). Nothing in the sources read describes one.

**Recommendation: shelve Option B; do not run the B1 spike.**
Reason: the spec's own gate (B0: "if no, Option B is impossible; stop") is answered "no, as documented" by BUSSPEC L263-264, in the pinned SDK as well as the old clone. B1 (0.75 Sd of glasses-side transport extraction) only pays off if a glasses-side client is permitted, and no release hub we can read permits it. This matches the earlier recommendation (section 4, decision 1): shelve unless S2 fails and Nexus voice is wanted badly. Reopen only if S2 gives outcome 2 AND upstream ships companion provisioning (check BUSSPEC for changed L260-264 or a glasses-side plugin section) or the maintainer confirms an approval path. One question to upstream could settle it faster than any spike; that is the user's call. Decision 3 in section 4 is thereby answered.
