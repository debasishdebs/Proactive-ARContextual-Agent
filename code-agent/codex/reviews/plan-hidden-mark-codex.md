# Design proposal: foreground idle mode and Mark moment

Read-only proposal; no files changed or device settings altered.
The recommended design retains our task, blanks its UI after inactivity, permits native display sleep, and adds deterministic moment marking over the existing speech path.
Two requirements need device validation: retaining our task across sleep, and recovering reliably through the Rokid D-pad.
Unprompted voice wake cannot work with our microphone closed; default-ON voice commands do not imply always-listening audio.

## 1. Evidence and scope

- Reviewed `ctx/int` glasses runtime, capture service, HUD controller, gesture routing, PTT, VAD, protocol, speech pipeline, phone adapters, and Room definitions.
- The requested slice-3 document exists in the main checkout, not in `ctx/int`: [design, including Re-cut and §16](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/specs/2026-10-21-canvas-slice3-e2e-configurator-design.md:1219).
- §16 supersedes the earlier unbound mark gesture: foreground D-pad tap marks; voice marking defaults ON.
- Current [MainActivity](/Users/debasishkanhar/Desktop/rokid/pca-ctx/int/android-companion/glasses/src/main/java/com/rokid/context/glasses/MainActivity.kt:1) sets `FLAG_KEEP_SCREEN_ON` unconditionally.
- Current `minimise()` persists `ui_mode=icon`, calls `moveTaskToBack(true)`, and displays a noninteractive overlay icon.
- Its black `hudHidden` surface prevents startup flashes; its key handler deliberately bypasses app routing while hidden.
- Reusing that boolean unchanged would leave foreground-hidden mode without dependable controls.
- `CaptureService` owns capture through a camera foreground service; foreground-service status does not make our Activity the foreground UI.
- `HudController` and `GlassesRuntime` already preserve sessions, wear state, pause state, and buffered frame delivery.
- Tap currently starts Ask; long press belongs to Hi Rokid; directional D-pad events implement swipes.
- `ctx/int` has no standalone PCA `PhoneMic.kt`: phone capture uses `AndroidOnDeviceStt.startPhoneMic`, through `PipelinePhoneVoice`.
- LiveFit’s separate `PhoneMic.kt` records PCM itself; copying it would duplicate PCA’s existing capture path.
- Room currently reports v5; v6 belongs to canvas slice 2; these additions belong in the next-free migration, expected v7.
- The A3 baseline is the documented 35-minute worn run at **0.49 %/min**, with persistent logging OFF.

## 2. Decisions and alternatives

| Approach | Benefit | Problem | Decision |
|---|---|---|---|
| Retain task, blank Activity, allow native sleep | Preserves ordinary Android lifecycle and avoids continuous display retention | Firmware must retain task and route wake correctly | Recommend |
| Keep screen awake behind black/dim UI | Predictable app key handling while interactive | Black pixels do not establish display-off power savings | Reject as permanent idle mode |
| Force sleep through privileged or accessibility controls | Can request immediate sleep | Permissions, global effects, and recovery risks | Exclude from normal installation |

“Foreground” means our task remains the selected app when the display returns, unless Rokid or the wearer intentionally selects another surface.
It cannot mean an Activity remains continuously `RESUMED` while the device sleeps.
Home, calls, wear changes, and Hi Rokid retain their existing authority.
No launcher replacement, kiosk mode, lock-task mode, default-assistant takeover, or task-relaunch loop is proposed.

## 3. Display mechanisms

- Replace unconditional keep-screen-on with state-dependent ownership.
- Set `FLAG_KEEP_SCREEN_ON` during active HUD interaction and bounded PTT recording.
- Clear it when idle, stopped, backgrounded, or explicitly exited.
- Android documents clearing this flag as the ordinary way to permit screen timeout. [Android screen guidance](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on)
- At the inactivity deadline, render a static black surface inside our Activity.
- Treat black as visual blanking while waiting for native sleep; never report it as confirmed physical screen-off.
- Do not create a full-screen application overlay: it neither owns ordinary Activity input nor guarantees sleep.
- Do not use `FLAG_DIM_BEHIND` as a power mechanism; it dims content behind a window.
- Do not depend on `screenBrightness=0`: Android defines the minimum brightness override, not guaranteed panel power-off. [Window brightness reference](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)
- Add no partial, dim, bright, or wake-causing wake lock.
- Do not call hidden `PowerManager.goToSleep` or `wakeUp`; the platform sleep mechanism requires privileged authority. [AOSP PowerManager](https://android.googlesource.com/platform/frameworks/base/+/1bb480a/core/java/android/os/PowerManager.java)
- Do not modify global screen timeout, device-admin policy, or secure settings.
- Local CXR-M documentation mentions screen-off APIs, but PCA uses CXR-L; that documentation is insufficient evidence of an available control.
- If native timeout fails while our task is selected, stop rollout and investigate firmware behavior before choosing another mechanism.

## 4. State and lifecycle

Introduce a small pure `ForegroundUiPolicy`; Activity code performs its window and navigation effects.
Keep task placement separate from display presentation and capture state.

| State | Task/UI behavior | Screen policy |
|---|---|---|
| `ACTIVE` | Our HUD visible | Temporary keep-screen-on |
| `IDLE_BLANK` | Our task retained; static black content | Keep-screen-on cleared |
| `SLEEPING` | Native display noninteractive; task retained if firmware permits | No app wake request |
| `BACKGROUND_ICON` | Existing explicit minimised mode | Existing overlay rules |
| `EXITED` | Existing wearer exit and stand-down | No recovery attempt |

- Proposed inactivity default: **15 seconds** after meaningful wearer interaction.
- Use one cancellable Handler/coroutine deadline, never an exact alarm or repeating idle ticker.
- State frames, captures, heartbeats, nudges, and icon refreshes do not reset inactivity.
- PTT suspends idle blanking within its existing recording bounds.
- Confirm/Answer presentation gets a bounded interaction window; it cannot retain the screen indefinitely.
- Separate `idleBlank` from existing startup/minimise `hudHidden`.
- Preserve `START_MINIMISED`, `ui_mode=icon`, automatic-reopen classification, and explicit exit semantics.
- Add an independent presentation field such as `hudPresentation=active|idle`; do not reinterpret existing `icon`.
- Ordinary inactivity never calls `moveTaskToBack`.
- Explicit minimise still does, including the current visible recovery when moving the task fails.
- Launcher opens and explicit Show HUD actions always restore visible UI and clear stale input state.
- Process recreation restores a usable HUD; never restore an unverified invisible state from disk.
- Screen-off `onStop` must not be mistaken for wearer exit or trigger foreground-camera fallback.
- Extend `HudPresence` with a sleep reason so capture failure cannot repeatedly reopen the HUD.
- Keep `CaptureService` ownership, sampling gates, camera cadence, and session recovery unchanged.

## 5. D-pad routing and recovery

- Continue routing ordinary keys through the focused Activity; introduce no global key listener.
- The system can consume the wake key before an Activity receives it. [Android KeyEvent reference](https://developer.android.com/reference/android/view/KeyEvent)
- Therefore distinguish interactive black UI from physical sleep.
- In `IDLE_BLANK`, a delivered short center/Enter tap reveals the HUD and records one moment.
- A delivered directional press reveals the HUD without also navigating.
- If an unseen Confirm is pending, the first press only reveals it; it never votes or marks.
- While physically asleep, the first D-pad press is wake-only when consumed by firmware; the next delivered tap marks.
- Do not infer a mark from `ACTION_SCREEN_ON`, because wear and system actions also wake the display.
- Show “Tap to mark” after a wake whose initiating key was unavailable.
- Reset gesture/down/repeat state across sleep, focus loss, recreation, and mode transitions.
- Preserve notification-key suppression where the current gesture decoder requires it.
- Preserve the existing long-press exclusion and Hi Rokid behavior.
- Recording tap-to-send and double-tap-to-cancel retain priority.
- Visible Confirm controls retain priority; mark tap applies to normal HUD pages.
- Normal-page tap changes from Ask to Mark moment under the negotiated feature.
- Proposed replacement PTT entry: a clearly labelled **Voice command** page, reached by swipe and activated by tap.
- That page explicitly changes tap’s meaning; long press remains untouched.
- Keep the existing phone microphone action as another PTT entry.
- If firmware cannot recover the retained task reliably, disable foreground idle mode and retain existing full/icon behavior.

## 6. Accessibility and Rokid behavior

- Do not add an accessibility service to obtain keys, microphone priority, or screen locking.
- Android accessibility activation requires explicit user enablement; key filtering requires declared capability. [AccessibilityService reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- No reviewed PCA code establishes that this glasses firmware exposes a usable accessibility enablement flow.
- `GLOBAL_ACTION_LOCK_SCREEN` is a global lock action, not an app-local blank-display primitive.
- Do not require ADB grants or secure-setting changes for ordinary use.
- Keep SDK `pc_wear` handling and native wear/home transitions; add no substitute proximity heuristic.
- Do not counteract removal, folding, Home, calls, assistant launch, or intentional scene changes.
- Retain current guarded restart/backoff and persisted wearer-exit stand-down.
- Validate notification/call visibility while our Activity is active and while idle.

## 7. Voice capture and the wake boundary

- **Voice Mark defaults ON as a parser setting.**
- Open no microphone, recognizer, feeder, or watchdog merely because that setting is ON.
- Glasses voice remains explicit PTT using the existing glasses microphone.
- Preserve 16 kHz mono PCM16, 3,200-byte chunks, Base64 `pc_audio`, `ListenStart`, and `pc_listen_end`.
- Preserve `EnergyVad`: 2.2 seconds post-speech silence, 5 seconds without speech, 15 seconds maximum.
- Existing VAD terminates recording; it currently sends chunks before deciding to stop.
- Continuous VAD would still require microphone capture and CPU activity; it is not a free always-listening mechanism.
- Do not add idle pre-roll, periodic microphone probes, or recognizer restart loops.
- Preserve the read watchdog, cancellation, late-send-tap handling, and link-loss cleanup.
- Hi Rokid’s observed microphone ownership does not prove concurrent capture will work.
- Android audio arbitration can return silence even when recording starts successfully. [Audio-input sharing](https://developer.android.com/media/platform/sharing-audio-input)
- Treat recording-state checks as necessary but insufficient; test audible input and silencing behavior on hardware.
- On busy/silenced capture, terminate within existing bounds, release resources, and offer phone PTT.
- Do not disable, kill, or change Hi Rokid’s microphone service; audio focus is not microphone ownership.
- Reuse `AndroidOnDeviceStt`, external-input capability probes, `FallbackGuard`, and `SpeechPacks`.
- Preserve API 33 external-audio requirements and the existing phone-mic capability/permission gates.
- Keep phone and glasses sessions mutually exclusive through the current voice pipeline.

**Voice wake has two distinct meanings:**
- An explicit voice action may reveal an already-interactive black HUD.
- “Mark that” alone cannot wake true sleep when our microphone is closed.
- Phone PTT can recognize and save a mark while glasses sleep; strict A3 mode defers visual feedback until native wake.
- Immediate phone-command display wake would require a separately enabled, user-requested wake path.
- If permitted, investigate one-shot `setTurnScreenOn(true)` during a valid Activity resume, then clear it; never leave it enabled. [Activity wake behavior](https://developer.android.com/reference/android/app/Activity#setTurnScreenOn(boolean))
- That option is a new attributed wake and cannot satisfy a literal prohibition on every new wake event.

## 8. LiveFit command-layer copy

Copy source into PCA’s voice module; retain Apache-2.0 notices and an adaptation header.
Pin the source to reviewed LiveFit commit `45297350cc94cf80d1007e841d68175a9c5feeb3`.

| Source | Adaptation |
|---|---|
| `CommandParser.kt` | Ordered, word-boundary matching rewritten as `MarkCommandParser` |
| `CompositeCommands.kt` | Copy clause splitting; adapt aggregation to one mark event |
| `VoiceCommandGroup.kt` | Copy toggle/group/gate pattern for Mark moment |
| `LanguageRegistry.kt` | Copy locale/parser/splitter structure; register English parsers |
| Corresponding tests | Reuse cases as templates; add PCA semantics |

- Copy no LiveFit connection, permissions, voice service, microphone implementation, or audio protocol.
- Copy no workout/music actions or confirmation parser.
- Retain PCA’s configured language; do not silently replace its default with LiveFit’s `en-IN`.
- Initially support English mark grammar; Hindi speech continues through existing Ask until its grammar is designed.
- Recognize “mark that”, “mark this”, and “mark moment”.
- Proposed intent vocabulary: passenger, driving, aeroplane, working, sitting, talking.
- Prefer explicit phrases: “I’m on a bus”, “I’m driving”, “I’m on a plane”, “I’m working at my desk”.
- Avoid matching incidental nouns in questions such as “What plane is that?”
- Handle negation conservatively; “I’m not driving” must not become driving.
- An explicit mark with an unknown intent still creates MOMENT only.
- Composite clauses such as “mark that and I’m driving” create one MOMENT and one INTENT.
- Conflicting intents produce MOMENT only and request later correction.
- Mixed mark/question utterances save the mark and request a separate Ask; do not silently upload the unmatched clause.

## 9. Voice routing and temporal semantics

- Intercept an accepted final transcript before `VoicePipeline.think` invokes `answerer.answer`.
- A mark match performs no Answerer call, Ask persistence, remote inference, or TTS.
- No match follows today’s Ask path unchanged.
- Turning the voice group OFF restores ordinary Ask routing; touch marking remains available.
- Require a genuine final for labels; do not create training labels from the current timeout-generated partial fallback.
- Add a `MARKED` outcome and bounded 600 ms “Marked · driving” feedback.
- A tap’s anchor is the delivered short-tap event time, converted to elapsed realtime.
- A voice mark’s anchor is PTT initiation, not transcript arrival or phone receipt.
- Phone voice marks use phone PTT initiation.
- Every mark denotes `[anchor − 10,000 ms, anchor]`; preserve missing coverage explicitly.
- Marking never forces capture, retains extra camera frames, or records the previous ten seconds of audio.
- Keep marks even when no photo exists: an uncovered marked window is meaningful tuning evidence.
- MOMENT supplies recall; INTENT supplies human label evaluation.
- Do not overwrite inferred `frame_meta.intentLabel` or feed human labels into live context fusion automatically.

## 10. Additive protocol and time mapping

- Keep `PROTOCOL_VERSION = 1`.
- Keep existing command discriminators, PCM format, audio channels, and gesture enum values.
- Negotiate optional capabilities such as `mark_v1`, `listen_anchor_v1`, and `foreground_idle_v1`.
- Add defaulted fields; old peers ignore them and absent capabilities disable unsupported behavior.
- Use string-based `markGesture="TAP"` rather than adding a `GestureAction` enum value.
- Add optional listen metadata to `CommandEnvelope`: listen ID, initiation elapsed time, boot/session identity, and trace ID.
- Preserve `Command.ListenStart` as its existing discriminator; carry metadata through inbound events and Hub adapters.
- Old receivers ignore metadata; new receivers treat missing anchors as legacy, not accurately timed tuning labels.
- Add `pc_mark`: version, stable event ID, sequence, anchor elapsed time, boot ID, session start, optional trace ID, and wipe generation.
- Add `pc_mark_ack`: event ID and saved/duplicate/rejected status; acknowledge only after durable phone commit.
- A transport send result of zero remains handover, not delivery.
- Voice labels are created directly on the phone using the listen ID; avoid a round trip through a synthetic glasses mark.
- Reuse slice-3 manifest-lite clock samples at trace start/end and interpolate within the same boot.
- Current `ClockOffset` maps wall clocks; it cannot directly convert elapsed timestamps.
- Piggyback elapsed/wall samples on existing hello/session traffic; add no periodic clock-ping timer.
- Preserve raw anchors and mapping quality; unresolved marks stay visible but are excluded from scored recall.
- Keep a bounded glasses event outbox: proposed 128 metadata events, 24-hour expiry, no audio.
- Retry on existing connection/hello opportunities, without a new polling loop.
- Show “Queued” before acknowledgement, “Marked” after commit, and explicit failure when the queue is full.

## 11. Room, deletion, and privacy

- Extend slice-3 **migration A**, expected **v7**, after v5 and reserved v6 merge.
- Coordinate one migration owner with A8; do not create competing v7 migrations or edit historical migrations.
- Recheck the next-free version at merge time.
- Use `canvas_label`, not a second independent mark store.
- Proposed columns: ID, event ID, kind, label, source, source reference, trace ID, raw boot/elapsed anchor, mapped start/end, clock quality, wipe generation, and creation time.
- Enforce uniqueness on `(eventId, kind)`; one event creates one MOMENT and at most one INTENT transactionally.
- Index trace/time and kind/time for timeline and evaluation queries.
- Permit null trace IDs so ordinary marks work outside “Collect for tuning”.
- Preserve deletion tombstones long enough to reject delayed outbox retries.
- Include labels/tombstones in wipe, retention, export, encryption, and replay/sweep invalidation.
- Source-derived marks follow source deletion; independent manual marks are not deleted merely because a nearby photo disappears.
- Store only label names, times, and necessary provenance for voice marks.
- PCM exists transiently in memory and the paired-phone transport; never write or upload audio to a backend.
- Never log PCM, Base64 chunks, transcripts, or recognizer callback contents.
- Matched mark transcripts never enter Ask storage, model requests, analytics, or trace files.
- Preserve on-device-only recognition and reject external-input fallback; retain existing phone-mic opt-out behavior.

## 12. Verification and device release checklist

- Policy tests: inactivity, PTT bounds, screen transitions, recreation, explicit minimise, Home, exit, and fallback suppression.
- Input tests: blank-state recovery, swallowed wake keys, repeats, orphan key-up, hidden Confirm, tap/send priority, and Hi Rokid long press.
- Parser tests: vocabulary, boundaries, negation, composites, conflicts, mixed utterances, toggle OFF, unsupported locale, and genuine-final requirement.
- Pipeline tests: matched marks make zero Answerer/TTS/Ask writes; timestamps survive recognition delay and cancellation races.
- Protocol tests: old/new combinations, missing metadata, unknown fields, deduplication, stale session/boot, malformed times, and lost acknowledgements.
- Storage tests: expected v6→v7 migration, older upgrade chains, encrypted databases, atomic label pairs, deletion fences, and replay invalidation.
- Inspect actual display power state and top task after timeout; black appearance alone is insufficient.
- Verify first/second D-pad behavior after sleep, assistant use, wear removal/re-wear, folding, calls, and notification interruption.
- Verify camera capture continues without a foreground-launch recovery loop.
- Verify glasses PTT with Hi Rokid idle/active, music, calls, BT loss, missing pack, permission denial, and phone mic disabled.
- Run paired unplugged A/B and reversed-order repeats with matched A3 settings, brightness, movement, music, and temperature.
- Use the same persistent-logging-OFF condition as the 0.49 %/min baseline; inspect diagnostics after each run.
- Measure drain, screen-on duration/count, `scrOurs`, wake locks, CPU, camera opens/share, and delivered keyframes.
- Idle acceptance: no additional autonomous screen wakes, alarms, polling loops, microphone sessions, or wake locks.
- Battery acceptance: no reproducible regression against the contemporaneous A3 control; coarse battery percentages require repeat runs.
- Measure a separate fixed number of tap/voice actions to expose interaction cost without contaminating idle comparison.
- Release foreground idle only after recovery and battery gates pass; no device validation was performed for this proposal.

## 13. Task breakdown and estimates

Estimates are agent-hours including focused tests and review; user wear time and merge waits are additional.

| Task | Dependency | Hours |
|---|---|---:|
| Device feasibility: task retention, timeout, key routing, mic ownership | First | 3 |
| UI policy, lifecycle, idle rendering, fallback guard | Feasibility | 5 |
| Tap marking, input recovery, explicit PTT entry | UI policy | 4 |
| Additive capabilities, settings, listen anchors | Contract agreement | 3 |
| Mark transport, acknowledgement, bounded outbox | Protocol | 4 |
| LiveFit command-layer adaptation and parser tests | Vocabulary | 4 |
| VoicePipeline interception, timestamps, feedback | Parser + anchors | 4 |
| Migration-A labels, repository, deduplication, deletion | v6 + A8 coordination | 4 |
| Phone controls, timeline correction, tuning eligibility | Repository | 3 |
| Cross-version, lifecycle, privacy integration tests | Integrated features | 4 |
| Device checklist and battery analysis | Integrated build | 4 |
| Planner reconciliation and final integration review | All above | 2 |
| **Total** | | **44** |

Allow approximately 36–55 agent-hours depending on firmware findings, plus 2–4 hours of worn measurement.
This replaces the narrow A9 mark estimate for this scope; shared A8 trace/migration work must not be counted twice.

## Open questions for the user

1. Does “no new wakeups” permit explicitly requested phone-voice display wakes, while prohibiting autonomous wakes?
2. Is wake-only first press, then tap-to-mark acceptable when firmware consumes the sleeping-display key?
3. Is a swipe-reachable Voice command page acceptable now that ordinary tap becomes Mark moment?
4. Is 15 seconds a suitable inactivity timeout?
5. Confirm the initial intent labels: passenger, driving, aeroplane, working, sitting, talking?
6. Should independent marks persist outside tuning collection? Recommendation: yes.
7. Should conflicting or unrecognized intent phrases save MOMENT only for later correction? Recommendation: yes.
8. If native sleep loses our task or fails battery validation, should foreground idle remain disabled until firmware support is established?