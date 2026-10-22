# Hidden-foreground glasses app and "Mark moment": design proposal (Claude, independent)

Date: 2026-10-22. Status: PROPOSAL, no code written. Written without reading the Codex proposal (`.superpowers/codex/plan-hidden-mark-codex.md`).
Feeds: slice 3 spec §16 rulings 1–2 and the Re-cut row 5 (S54), §6.4 (S45). This proposal replaces the glasses-gesture part of slice 3a task A9 and is the "new 3a task" from §16.2.

Code read (worktree `../pca-ctx/int`, branch `ctx/int` @ `60e91ddf`): glasses `MainActivity`, `GlassesRuntime`, `AutoStartGate`, `RestartDiagnostics`/`UiModeStore`, `capture/CaptureService` (screen-on receiver), `capture/ForegroundFallback` (`HudPresence`), `capture/diag/ScreenWakeStats`, `hud/KeyGestures`, `hud/InputRouter`, `link/HudController`, `voice/PushToTalk`, manifest; `core/model` `EnergyVad`, `Pcm`, `Protocol` (channels, `Gesture`, `Command`), `Wire` (Json config); `services/voice` `VoicePipeline`, `Answering`, `AndroidOnDeviceStt`, `AndroidVoicePlatform`, `SpeechLanguage`/`VoiceSettings`; `services/glasses-link` `GlassesSessionPolicy`; phone `Hub`, `StateBroadcaster`; `services/memory` `ContextDatabase` (version 5). Live Fit (`../live-fitness-tracker` @ `4529735`): `CommandParser.kt`, `CompositeCommands.kt`, `VoiceCommandGroup.kt`, `LanguageRegistry.kt` and their tests. Docs: handoff §0c, §6, §8; slice 3 Re-cut, §6.4, §12, §15.18, §16.

---

## 0. Decisions at a glance

| # | Question | Decision | Why (short) |
|---|---|---|---|
| D1 | How the screen is "hidden" | **Two stages: blank first, then the real display-off from the system timeout.** After an idle delay, the HUD draws only black and the app **drops `FLAG_KEEP_SCREEN_ON`**. Rokid's own screen timeout then turns the display off. We never turn the screen on or off from code | This is the only option that adds **zero wakeups and zero wake locks**. The A3 baseline already has the display off most of the time (screen on 1–5 min per hour). A black screen that stays on, or brightness 0, keeps the device interactive |
| D2 | Wake | **The wearer's D-pad press (the system wakes the display), the wearer opening the app (launcher, or a "Hi Rokid, open …" voice launch), or a user-started voice flow (Listening/Answer overlay) while the display is already on.** Proactive overlays keep today's A3 rule: nothing pops up while the display is off | No programmatic wake means no new wakeups by construction |
| D3 | First touch | **The first touch after blank or display-off only wakes the HUD.** It is never routed. A 300 ms guard after display-on drops the rest of the waking gesture | Same rule as today's M3 ("nothing invisible may answer a Confirm"). It also absorbs the half-delivered gesture after a wake key |
| D4 | Minimise and exit | **Unchanged.** The double-tap gate still minimises to icon mode (`moveTaskToBack`, Hi Rokid home in front), and the third double-tap still exits. Only *idle* goes to hidden-foreground | This keeps the Rokid home reachable by the same gesture as today |
| D5 | Never stranded | Keys are always consumed while blank, and any key unblanks. `blank ⇒ !KEEP_SCREEN_ON` is an invariant. A check at display-on sees whether we are really on top within 3 s; if not, the app falls back to icon mode and counts the event. System keys (long-press for Hi Rokid, volume, power) are never taken | See §3.6 |
| D6 | Mark gesture | **Tap = mark + listen.** A tap (TAP mapped to ASK) records the mark at once (`pc_mark`, a ✓ cue on the glasses) and opens the normal push-to-talk listen. Silence or a second tap keeps a plain MOMENT. "Mark that, driving" adds an INTENT. A real question goes to Ask unchanged, and the row is kept with source `ASK_TAP`, excluded from recall by default. A double-tap cancels both the listen and the mark | One gesture, as ruled in §16.1 and S54. Every voice mark needs a tap anyway (no hotword). The anchor is the exact tap time |
| D7 | Voice capture | **Push only, as today.** No always-on listening on either device. The glasses mic opens only after a tap, using the existing `PushToTalk` + `EnergyVad` (≤ 5 s with no speech, 2.2 s of trailing silence, 15 s cap) | Idle cost is zero. Always-on listening costs about 0.03–0.1 %/min, clashes with Hi Rokid's hold on the mic, and records bystanders (§4.2) |
| D8 | Parser location | Phone, on the final transcript, **before** `answerer.answer` (S54), through a new `UtteranceInterceptor` port in `VoicePipeline` | The recognizer is on the phone. The glasses stay dumb |
| D9 | What to copy from Live Fit | `CommandParser` pattern (rewritten as `MarkCommandParser`), `EnglishClauses` + `parseUtterance` verbatim, the `VoiceCommandGroup`/`VoiceCommandGate` shape, the `LanguagePack` shape, and their 4 tests as templates. Nothing else (§4.4) | As S54 says |
| D10 | Room | One new table `moment_label`, CREATE-only migration, **number = next free at merge** (expected v7 if slice 2 v6 lands first; if slice 3a's migration A is merged together with it, fold it into migration A) | v5 is merged and v6 is reserved for slice 2 |
| D11 | Wire | New channels `pc_mark` (glasses→phone) and `pc_mark_ack` (phone→glasses). Nullable additive fields on `Hello` and `HudSettings`. **No new `Command` subtype** and no field added to `ListenStart`. `PROTOCOL_VERSION` stays 1 | `Command` is a sealed polymorphic type, so an unknown subtype breaks old decoders. Unknown fields are safe (`ignoreUnknownKeys = true`) |
| D12 | Privacy | Audio is never stored or uploaded; it streams only to the on-device recognizer. Transcripts of marks are never stored or logged. A matched mark never calls a model. Only times, the source enum and the label enum are stored | Same as S54 and spec §9 |

Estimated total: **about 28 agent-h** (core) plus 4 optional, in two parallel streams, gated by a 2.5 h device spike (§11).

---

## 1. Facts from today's code that shape the design

1. **The full HUD keeps the screen on forever.** `MainActivity.onCreate` sets `FLAG_KEEP_SCREEN_ON` and never clears it. Today the only battery-friendly mode is icon mode: `minimise()` calls `moveTaskToBack`, Hi Rokid home comes to the front, and the overlay `MinimisedIcon` shows (needs `SYSTEM_ALERT_WINDOW`). In icon mode D-pad keys go to the Rokid launcher, never to us. That is why a mark tap needs the app in front.
2. **`hudHidden` (the w3g change) exists, but only as a pre-minimise black frame.** While it is set, `dispatchKeyEvent` passes every key to `super`. In particular KEYCODE_NOTIFICATION (83) reaches the system, which "moves our task to the back". A hidden-foreground state therefore cannot reuse `hudHidden` as it is. It needs its own state that **consumes** keys. `minimise()` already undoes `hudHidden` when `moveTaskToBack` fails (M4, "never in front and black"). The same principle is reused in §3.6.
3. **Key model (`KeyGestures`)**: every gesture starts with 83. DPAD 19–22 are swipe steps. ENTER/DPAD_CENTER key-up is a tap unless the key was held for 500 ms or repeated (that is Hi Rokid's long press). Two 83s or one BACK make a double-tap. Gesture map: TAP→ASK, DOUBLE_TAP→PAUSE_RESUME (on a page this goes to the exit gate), swipes→pages. **No gesture is free.** This is why D6 shares the tap with Ask.
4. **Push-to-talk** (`PushToTalk`): `AudioRecord` on MIC at 16 kHz, 100 ms chunks, Base64 on `pc_audio` between `ListenStart` (`pc_cmd`) and `pc_listen_end`. It only starts when the CXR **session** is open (`link.start()` requires `sessionOpen`). The service's foreground type is `camera` only (manifest), so the glasses mic works **only while the activity is visible** (target SDK 32 enforces while-in-use mic access). Consequence: the display must stay on for the whole listen.
5. **Wakes and battery** (handoff §6): screen on 25 times for 5 m 22 s in a 51 min run, woken by `android.policy:KEY`. That is about 13 s per wake, which suggests a Rokid screen timeout near 10–15 s (to be measured). The A3 worn baseline is **0.49 %/min** in icon mode. The glasses app holds **no wake locks** today (grep). `ScreenWakeStats` already counts display-on events, the ones caused by our UI, and nudges skipped while the display was off. `MainActivity.onPhoneOverlay` already holds pop-ups back when `!isInteractive` (A3 `PopupGate`).
6. **Rokid scene behaviour** (handoff §6): AssistServer force-stops the `third_app` scene when another scene opens (call, message, mobile_music, glasses removal). The restart comes 17 s to 5½ min later. In icon mode a real call did **not** kill the app (retest 1). **Being in front makes us the third_app scene again.** This is the main risk (R1).
7. **Restart path**: after a kill, `RestartDiagnostics.takeStartMinimised()` → `AutoStartGate.decide` minimises the first start, so the app never comes up over a call. `UiModeStore` accepts only `full|icon|home`; an unknown value reads as `home`.
8. **Link**: when the activity is in front, the phone holds the CUSTOM_APP session (`CONNECTED`, route SESSION). In icon mode it uses the background link (`MINIMISED`, route BACKGROUND). The phone's `StateBroadcaster` heartbeat is 10 s while linked.
9. **Voice on the phone**: `VoicePipeline.think(…)` → `answerer.answer(question, source)`. The recognizer is on-device only, guarded by `FallbackGuard`. Logs carry outcome codes only. Languages are English (`en-US`) and Hindi (`hi-IN`).
10. **Wire**: `Wire.json` has `ignoreUnknownKeys = true`, `explicitNulls = false`. `Command` is a sealed `@Serializable` type, and `ListenStart` is a `data object`.

---

## 2. Feature A and Feature B in one picture

```
           idle T_blank (no key, no live overlay, not listening)
  FULL ──────────────────────────────────────────────▶ FRONT_BLANK (black, KEEP_SCREEN_ON cleared)
   ▲  ▲                                                   │  system screen timeout
   │  └──── any touchpad key (wake-only) ◀────────────────┤──────────────▶ FRONT_OFF (display off, activity stopped)
   │                                                      │                     │ wearer's key → system wakes display
   │                                                      │                     ▼
   │  display-on + we are top → FULL (HUD drawn, the waking gesture dropped)  ◀─┘
   │  display-on + we are NOT top within 3 s → ICON (fallback, counted)
   │
   └── double-tap gate (2nd) ──▶ ICON (moveTaskToBack, overlay icon)  ── 3rd ──▶ HOME (exit)

  In FULL: tap = mark + listen (Feature B). The listen holds KEEP_SCREEN_ON until it ends.
```

---

## 3. Feature A: stay in front, screen hidden

### 3.1 Mechanisms considered

| Option | What | Wakeups / wake locks | Display power | Interactive state | Verdict |
|---|---|---|---|---|---|
| **A. Clear `FLAG_KEEP_SCREEN_ON`, draw black, let the system timeout turn the display off** | Window flag cleared on idle; Compose draws only a black `Box` (`HudContent` leaves the composition) | none added | off after the timeout | non-interactive after the timeout; SoC may suspend as in icon mode | **Chosen** |
| B. Black overlay with the screen held on | `KEEP_SCREEN_ON` kept, black content | the flag is effectively a screen wake lock held for as long as the app is in front | small on micro-LED, but the panel, compositor and touch stay powered | always interactive: no suspend, and Rokid's system services keep their interactive behaviour | Rejected: open-ended on-time and a regression A3 cannot absorb |
| C. Window brightness 0 (`screenBrightness = 0f` or OFF override) | Brightness override | same as B | panel still driven; some panels still glow at 0 | interactive | Rejected (same as B) |
| D. Force the display off | `DevicePolicyManager.lockNow` (needs device admin), writing `SCREEN_OFF_TIMEOUT` (`WRITE_SETTINGS`, global side effect on Rokid), proximity wake lock | D: needs a wake lock | off | — | Rejected: needs privileges, changes Rokid settings, and proximity is useless for wear (handoff) |
| E. Status quo: icon mode | `moveTaskToBack` + overlay icon | none | off after the timeout | — | Kept as the explicit-minimise path; cannot receive D-pad keys |

Blanking *before* the system timeout (stage 1) is worth it even though the display goes off shortly after. On the waveguide display black means the pixels are off, the HUD does no frame work (no 1 Hz ticker: see 3.8), and nothing is shown to bystanders during the timeout or dim phase.

**Never used anywhere in this feature:** `PowerManager` wake locks of any kind, `ACQUIRE_CAUSES_WAKEUP`, `setTurnScreenOn`, `setShowWhenLocked`, `FLAG_TURN_SCREEN_ON`, and a microphone foreground-service type. A unit test greps the glasses sources for these tokens (§9).

### 3.2 States (new `FrontIdlePolicy`, pure JVM, in `glasses/hud/`)

| State | Window | `KEEP_SCREEN_ON` | Compose | Keys |
|---|---|---|---|---|
| `FULL` | HUD | held **only while** something needs it (see 3.3) | `HudContent` | routed as today |
| `FRONT_BLANK` | black | **never** | black `Box` (`hud_blank` test tag), `HudContent` not composed | all touchpad keys consumed; first = wake only |
| `FRONT_OFF` | (display off; activity stopped) | never | stopped | none arrive (the wake key is the system's) |
| `ICON` | task in back, overlay icon | n/a | stopped | Rokid's |

`ui_mode` persisted values become `full | front | icon | home`. `front` covers BLANK and OFF; an older build reads it as `home`, which is acceptable for a downgrade. `RestartDiagnostics` treats `front` like `full` for "killed while running". The heartbeat diag line prints `ui_mode=front`.

Transitions (all inputs come in as events, so the whole policy is unit-testable):
- `FULL → FRONT_BLANK`: `T_blank` (default **8 s**, setting 5–60 s) since the last key, with **no** live overlay (Listening/Thinking from our listen, a Confirm, an Answer while speaking, the exit-gate countdown) and `!listening`. Only when the setting "Stay in front when idle" is on. With the setting off, FULL behaves exactly as today (flag held forever).
- `FRONT_BLANK → FRONT_OFF`: the system (screen-off broadcast or `onStop` while `!isInteractive`).
- `FRONT_BLANK → FULL`: any touchpad key (consumed, not routed); a new intent from the launcher or a voice launch; a user-started overlay (our own glasses listen, or a phone-mic ask's Answer) **while interactive**.
- `FRONT_OFF → FULL`: `onStart` after display-on with us on top. The HUD shows for `T_blank`, then blanks again.
- `FRONT_OFF → ICON` (fallback): display-on, and `onStart`/`onResume` does not arrive within **3 s** (someone else is on top, e.g. the Rokid home returned on wake). This sets `ui_mode=icon`, shows the icon (when the keep-alive service is live), and adds 1 to `frontFallbacks` (Hello, Diagnostics).
- `FULL/FRONT_* → ICON`: the double-tap gate, unchanged.

### 3.3 `KEEP_SCREEN_ON` policy (in setting-on mode)

The flag is held while **any** of these is true, and cleared otherwise:
1. a key arrived within `T_blank`;
2. `runtime.listening` (the glasses mic needs the activity visible: fact 4);
3. a live Confirm, Listening/Thinking, or Answer overlay is shown, or the exit-gate countdown runs;
4. the 600 ms mark cue.

Invariant asserted in the policy and in Robolectric: **`blank ⇒ flag cleared`**, and **flag held ⇒ a bounded reason** (each reason has a time bound: Listening ≤ 26 s via `VoicePipeline` bounds, Answer hold 30 s, Confirm `CONFIRM_MS`). A stuck reason can therefore never hold the display on for good. A safety cap releases the flag after **60 s** without a key, whatever the reasons, and logs one count.

### 3.4 Key routing

- In `FRONT_BLANK`, `dispatchKeyEvent` consumes 83, 19–23 and 66 (DOWN and UP), unblanks, and resets `KeyGestures`. Nothing is routed. Every other key (volume 24/25, power, camera, anything unknown) goes to `super`, unchanged. BACK while blank only unblanks (it is a double-tap on some firmware).
- **Wake guard.** On display-on or unblank, inputs are dropped until a **fresh KEY_NOTIFICATION down at least 300 ms later**. On AOSP a wake key on a non-interactive display is not passed to the app, but the *rest* of the same gesture (the ENTER of a tap, the DPAD steps of a swipe) can arrive after the display is interactive. Without the guard that remainder could become a tap. `KeyGestures` already refuses a key-up with no recorded down; the guard covers the rest. To verify on the device: D3 in §10.
- Long press (Hi Rokid) is untouched. `KeyGestures` emits no Tap for a held key; Hi Rokid's trigger is intercepted by the system before it reaches the app (as today, to verify again in front mode).
- `FULL` routing is unchanged, except for the tap → mark hook (§4.3).

### 3.5 Lifecycle around display-off

Display-off stops the activity (`onStop`, not finishing); display-on starts it again. Today's `onStart` re-runs the start classification and `AutoStartGate.decide`, which consumes the one-shot pending flag and may minimise. In front mode a **display-cycle start is not a start**:
- `onStop` records `stoppedByScreenOff = !powerManager.isInteractive` (and `uiMode == front`).
- The next `onStart` with that flag set skips `StartSources.classify`, `startGate.decide`, `clearUserExit` and `uiMode.set(FULL)`. It goes to `FULL` (the HUD shows after a wake) through `FrontIdlePolicy`, and `hudPresence.onStarted()` runs as before.
- `HudPresence` sees STOPPED for the display-off time. The camera fallback only relaunches the same top activity, which is harmless. No change.
- `HudController`: `onStop` without finishing does not close the CXR session, so the phone stays `CONNECTED`. **To verify:** whether the CXR SDK reports a pause or exit on display-off (D4). If it reports `GlassesExited`, the phone goes `MINIMISED` and sends on the background link. That is functionally fine, but the phone status line must not say "Minimised" (see the Hello `uiMode` field, §6).
- `CaptureService` registers `ACTION_SCREEN_OFF` next to its existing `ACTION_SCREEN_ON` receiver (a dynamic receiver only, so no new wakeups) and forwards both to the runtime (`FrontIdlePolicy`, `ScreenWakeStats`).

### 3.6 Never stranded: guarantees and their tests

1. While blank, every touchpad key is consumed **and unblanks**. 83 never reaches `super` while blank (it would move us to the back with no icon).
2. `blank ⇒ !KEEP_SCREEN_ON`. A black screen held on is impossible by construction.
3. A fallback to icon at display-on when we are not on top within 3 s. The icon or the launcher then always gets the wearer back.
4. A start path that fails to show (an exception while composing, a recreate) clears blank first, as today's M4 does.
5. The exit and minimise gestures work from `FULL` (one wake touch away from blank). Rokid's long press and system keys are never taken.
6. Diagnostics: the Hello carries `uiMode` and `frontFallbacks`. The phone shows "On glasses · screen asleep · capturing" for `front`, never "Closed".

### 3.7 Rokid wear, home and force-stops

- **Home is unchanged.** It is reached by the minimise gesture (icon mode) as today, and Rokid's own home or back keys are untouched.
- **Wear is unchanged.** `pc_wear` and the wear gate are not touched. Removing the glasses is a Rokid scene: in front mode we expect a force-stop (as in full mode today). The restart then comes back in **icon** mode, exactly as today (never over a call).
- **Back to front after a Rokid restart** (setting, default from Q4). At the next `ACTION_SCREEN_OFF` while in system-caused icon mode, with `AudioManager.mode == MODE_NORMAL` and `!isMusicActive`, the app starts itself with `EXTRA_FRONT_HIDDEN` (allowed by the existing `SYSTEM_ALERT_WINDOW` background-start exemption, which the Confirm path already uses). It comes up blank, with no turn-screen-on flag, so the display stays off. When the wearer next wakes the display they see our HUD instead of home. An explicit wearer minimise is never auto-promoted.
- **R1: force-stop exposure (the main trade-off).** In front mode a message, a music start or a call (scene changes) will probably force-stop us, as in full mode, and each restart has a capture gap of 17 s to 5½ min. Icon mode avoids this. The device spike (§10 D5) measures it **before** the rest is built. If messages or music kill the app often, default to icon mode and offer front mode as an opt-in "D-pad marking" mode (Q5).

### 3.8 Battery: why it should not regress, and how it is proven

What differs from icon mode, and how each part is handled:
- **Display on-time**: no programmatic wakes. The on-time per wearer wake is `max(T_blank, Rokid timeout)`, against the Rokid timeout in icon mode. With `T_blank = 8 s` it matches or is shorter. The 3.3 reasons only hold the flag during user-started flows.
- **Compose work**: blank removes `HudContent` from the composition, so its `LaunchedEffect` 1 s ticker (`delay(1_000)` loop and 30 s battery read) **stops**. While the display is off the activity is stopped as well. Unit test: no ticker job while blank.
- **Link**: front mode keeps the CUSTOM_APP session. The phone sends `pc_state` every 10 s on both routes. Whether the session route costs more Bluetooth than the background link is **unknown** and is measured in the A/B. If it regresses, the optional task F6 adds Hello `uiMode=front`, so the phone's `StateBroadcaster` slows its heartbeat to 30 s while the glasses are blank (the glasses `LivenessMonitor` threshold is raised to match).
- **New receivers**: `SCREEN_OFF` only (event-driven).
- **Mark feature**: zero idle cost. A mark costs one small `pc_mark` message, and a listen only when the wearer taps.

Acceptance (S44 style, same protocol as A3): a 20 min desk A/B (icon vs front, paused capture, persistent logging off) and a 35 min worn run. Pass if worn **≤ 0.51 %/min** (0.49 + 0.02 noise band), `scrOn` per minute ≤ the icon-mode run, `scrOurs` unchanged, and no new wake-lock entries in `dumpsys power`. Fail → do not make front mode the default. Try F6, then re-measure.

---

## 4. Feature B: Mark moment

### 4.1 Gesture semantics

| Option | Behaviour | Pros | Cons |
|---|---|---|---|
| **B1. Tap = mark + listen (chosen)** | The tap records the mark immediately and opens the normal push-to-talk listen. Silence or a second tap keeps the MOMENT; a mark phrase adds an INTENT; a question becomes an Ask (row kept as `ASK_TAP`, excluded from recall by default); double-tap undoes both | One gesture, as §16.1 rules. The voice mark is "after the normal push-to-talk tap" (S54). The anchor is exact. It works even when voice is blocked (no pack, link down) | Every Ask also stores a mark row (flagged). A silent mark keeps the mic open up to 5 s unless the wearer taps again |
| B2. Tap = mark only; Ask moves to another gesture | — | Clean labels | Breaks the Ask muscle memory. No free gesture (fact 3) |
| B3. A separate `markGesture` (S45 original) | Bind a gesture | Spec'd | §16.1 superseded it: the user chose the tap. All gestures are taken |
| B4. A tap opens a listen; mark only if the transcript says so | — | No silent marks | A silent tap gives nothing, which contradicts "D-pad tap labels the previous ~10 s" |

The `ASK_TAP` rows have a reason to exist: asking about something often means "this moment mattered". They are stored, but the tuner counts them only when the user turns on "count asks as moments" (Q1).

### 4.2 Voice capture: the push versus listening trade-off and mic ownership

| Option | Idle battery | Mic contention with Hi Rokid | Privacy | Verdict |
|---|---|---|---|---|
| **Push (tap), glasses mic, existing PTT + EnergyVad** | 0 | Works today: `AudioRecordSource.start()` checks `RECORDSTATE_RECORDING` and reports MIC_UNAVAILABLE, never silence | Mic only after an explicit tap | **Chosen** |
| Always-on glasses mic with an energy-VAD gate, streaming only speech to the phone | Mic path + codec + 10 Hz VAD ≈ 15–40 mA on a 1,000 mAh battery ≈ **0.025–0.07 %/min** before Bluetooth. Every overheard conversation streams to the phone (radio + STT) | Rokid's assistant (uid 1000) already holds the mic for "Hi Rokid". Under Android's concurrent-capture rules a background (display-off, stopped) app gets silenced input, and target 32 needs a visible activity or a microphone FGS | Records bystanders; constant listening | Rejected |
| Always-on phone mic | Phone battery; the phone is often in a pocket (poor audio) | — | Same | Rejected |
| Hi Rokid voice → our app | — | Hi Rokid's grammar is Rokid's; at most "open <app>" launches us | — | Used only as a **wake** (a voice launch unblanks, §3.2) |

So the only voice path is: tap → existing `PushToTalk` → `pc_audio` → phone `AndroidOnDeviceStt.startExternal` (on-device only) → final transcript → parser.

### 4.3 Flow

Glasses (`MainActivity.onAsk`, TAP routed to ASK, not recording, setting "Mark by tap" on):
1. `at = System.currentTimeMillis()`, `atElapsed = SystemClock.elapsedRealtime()`. `MarkQueue.add(seq++, at, atElapsed, src=TAP, listen=<ptt will start>)` is persisted, then `pc_mark` is sent if the session is open (otherwise queued). The local **`MarkCue`** (a corner ✓ like `CaptureCueMark`, 600 ms) shows at once. It never waits for the phone and never forces a capture (S45).
2. Today's checks follow unchanged: `askUnavailable` (offline or voice blocked) → its toast, **but the mark has already been taken**. Then `ptt.start()`.
3. Second tap → `ptt.stop()` (as today). Double-tap → `ptt.cancel()` + `Command.Cancel` (as today) + `pc_mark` `{undo: seq}`. The cue changes to "mark undone".

Phone:
1. `pc_mark` → `MarkRecorder.onMark`. The row is inserted with `startAtMs = phoneTime(at) − 10_000`, `endAtMs = phoneTime(at)`, `source = GLASSES_TAP`, `listenPending = listen`. Idempotent on `(epoch, seq)`. `pc_mark_ack` is sent.
2. Clock mapping: `phoneTime = at + offset`, where `offset` comes from the latest `Hello.glassesNowMs` against phone receipt time (`clockQuality = MAPPED`). With no Hello yet in this session it uses the receipt time (`RECEIPT`). `atElapsed` and `bootCount` are stored for the S42 trace mapping in slice 3.
3. Listen linkage: the GLASSES listen whose `ListenStart` arrives within ±3 s of a `listen=true` mark is bound to that mark (`pc_cmd` and `pc_mark` are separate channels, so their order is not guaranteed).
4. On the final transcript, `VoicePipeline.think` calls `UtteranceInterceptor.intercept(text, source, boundMark)` **before** `answerer.answer`:
   - mark or intent clauses only → `Handled`: attach the intent (if any), set `source = VOICE` (or `PHONE_VOICE` for a phone-mic ask), show the HUD Toast "marked · driving" (or "✓ marked") through the existing `pc_state` overlay and in the phone UI. `outcome=MARKED` (a new `VoiceOutcome`). **No model call.**
   - mark clauses plus other clauses ("mark that and what's this building") → record the mark, then pass **only** the clauses that matched nothing (`ParsedUtterance.notUnderstood`, joined) to `answerer.answer`.
   - no mark clause → `PassThrough`: the bound row becomes `ASK_TAP`, and the ask continues unchanged.
5. `NoSpeech`/`NoFinal` with a bound mark: show "✓ marked" instead of "Couldn't hear"; the row stays `GLASSES_TAP`. `Cancel` or `pc_mark{undo}` → the row is deleted (a hard delete, nothing else references it).
6. A voice mark with no bound mark (an older glasses build, "Mark by tap" off, or a phone-mic ask) is anchored at the listen start as the phone saw it (`clockQuality = RECEIPT`).

Toggles: "Mark by tap" (glasses, synced in `HudSettings`, default **on**); voice group "Mark moment" (phone, Settings → Voice, default **on**, §16.1). With the voice group off the interceptor always passes through, so the ask path is exactly as today.

### 4.4 What is copied from Live Fit (code only)

Destination: `services/voice/src/main/kotlin/com/rokid/context/voice/commands/` (pure JVM, `:services:voice`). Each file starts with `// Adapted from Live Fit <path>@<commit> (Apache-2.0, same owner)`.

| Live Fit file @ commit | Taken | Changed |
|---|---|---|
| `services/voice/.../CommandParser.kt` @ `e504914` | the normalise step (`lowercase`, strip non-letters, collapse spaces), the `has(vararg)` word-boundary matcher, the rule order (most specific first) | rules rewritten as `MarkCommandParser.parse(clause): MarkCommand?` (`MarkCommand.Mark(intent: IntentLabel?)`, `MarkCommand.Intent(label)`) |
| `CompositeCommands.kt` @ `cb0f4fb` | `ParsedUtterance`, `EnglishClauses.split` (separators and the filler set) **verbatim**; `LanguagePack.parseUtterance` | generic over the command type |
| `VoiceCommandGroup.kt` @ `e504914` | the enum-with-label/examples/toggleable shape; `isAllowed`, `withEnabled`, `encode`/`decode`; `VoiceCommandGate` | one group: `MarkMoment("Mark moment", "\"mark that\", \"mark that, I'm driving\"")`. The disabled set is persisted as `VoiceSettings.disabledGroups: String = ""` |
| `LanguageRegistry.kt` @ `cb0f4fb` | `LanguagePack` (locale, name, parser, splitter), `forLocale` | English pack keyed to PCA's `en-US`; **no YesNo**; Hindi (`hi-IN`) has no pack → pass-through (Q6) |
| Tests `CommandParserTest` (e504914), `CompositeCommandTest` (b0fb351), `VoiceCommandGroupTest` (b0fb351), `LanguageRegistryTest` (768309d) | the structure and style of the tests | new goldens |

**Not copied**: permissions, `GlassesVoiceBridge`/`lf_*` channels, `LiveVoiceService`, `FakeVoiceService`, `YesNoParser`, and Live Fit's `PushToTalk`/`EnergyVad`/`AndroidOnDeviceStt`/`SpeechPacks`/`PhoneMic`, of which PCA already has adapted copies.

`MarkCommandParser` rules (after normalising, which turns "I'm" into "i m"):
1. **Mark verb**: the clause starts with (`please`)? `mark|bookmark|flag` followed by `that|this|it|now|here|moment|the moment` or the end of the clause. "Mark called me" and "what did Mark say" do not match: the verb must open the clause and be followed by a determiner or the end.
2. **Intent lexicon** (S13 vocabulary; proposal, Q6): `passenger` ← bus, taxi, cab, uber, ola, auto, rickshaw, metro, train, "in a car", "passenger"; `driving` ← driving, "i m driving", "behind the wheel"; `aeroplane` ← plane, flight, flying, airplane, aeroplane; `working` ← laptop, desk, computer, working, coding, office; `sitting` ← sitting, sofa, couch; `talking` ← talking, meeting, conversation. Rule order puts `driving` before `passenger`, so "driving the car" is driving.
3. **Intent-only clause** (S54 "I'm on a plane"): accepted only when the **whole utterance** is `(i m|i am|we re|now|currently) (on|in|at)? (a|the|my)? <lexicon phrase> (now|right now)?` and contains no question word (`what where when who how why which can could should is are do does am`). "Am I driving too fast?" and "what bus goes to Andheri" go to Ask.
4. A mark verb with an unknown intent clause → MOMENT only (S54). The unknown clause is *not* sent to Ask when it directly follows "mark that,": it was probably a label attempt. A clause after "and" containing a question word is sent to Ask.

### 4.5 Phone entry points

Unchanged from S45 and slice 3 A9: the notification action, the home button and the tile write `moment_label` rows with `source = PHONE_*` through the same `MarkRecorder`. They are not in this proposal's estimate (they stay in A9).

---

## 5. Data model (Room, phone, `services/memory`)

`ContextDatabase` is at version 5 (merged). v6 is reserved for canvas slice 2, and v7/v8 for slice 3. This feature takes **the next free number at merge** with a CREATE-only migration. If it merges together with slice 3a's migration A, it is folded into it.

```sql
CREATE TABLE moment_label (
  id            TEXT NOT NULL PRIMARY KEY,      -- uuid
  dedupeKey     TEXT,                            -- "<epoch>:<seq>" for glasses marks; NULL otherwise
  startAtMs     INTEGER NOT NULL,                -- phone wall clock; anchor − 10 000
  endAtMs       INTEGER NOT NULL,                -- anchor
  source        TEXT NOT NULL,                   -- GLASSES_TAP | VOICE | PHONE_VOICE | ASK_TAP | PHONE_NOTIFICATION | PHONE_TILE | PHONE_HOME | RETRO
  intentLabel   TEXT,                            -- canvas S13 enum name, NULL = MOMENT only
  clockQuality  TEXT NOT NULL,                   -- MAPPED | RECEIPT
  glassesElapsedMs INTEGER, glassesBootCount INTEGER,  -- for S42 trace mapping
  createdAtMs   INTEGER NOT NULL
);
CREATE UNIQUE INDEX index_moment_label_dedupeKey ON moment_label(dedupeKey);
CREATE INDEX index_moment_label_startAtMs ON moment_label(startAtMs);
```

There is **no text column** for transcripts. A retro note (slice 3 timeline) gets its own nullable `note` column when slice 3 adds retro marks, not here. Deletion (S38): "Delete all" wipes the table, a per-row delete from the timeline, and retention follows the trace retention, otherwise 90 days (a proposed default, not a user question: marks hold no sensor data). Glasses side: `MarkQueue` in SharedPreferences (JSON, ≤ 200 entries, entries older than 7 days dropped, a random `epoch` generated once per install, `seq` monotonic and persisted).

---

## 6. Wire protocol (additive, `PROTOCOL_VERSION = 1`)

| Change | Direction | Payload | Old peer |
|---|---|---|---|
| `Channels.MARK = "pc_mark"` | glasses → phone | `Mark(v=1, epoch, seq, atMs, atElapsedMs, bootCount, sessionStartMs, src="tap", listen: Boolean, undo: Long? = null)` (S45's fields plus additions) | an old phone does not subscribe, so the message is dropped and the glasses resend later (harmless) |
| `Channels.MARK_ACK = "pc_mark_ack"` | phone → glasses | `MarkAck(v=1, epoch, seqs: List<Long>)` | an old glasses build ignores it |
| Resend | — | the glasses resend unacked marks on session open and after each Hello (≤ 20 per burst) | — |
| `Hello` + `uiMode: String? = null` (`full|front|icon`), `frontFallbacks: Int? = null`, `marksPending: Int? = null` | glasses → phone | nullable | ignored (`ignoreUnknownKeys`) |
| `HudSettings` + `frontWhenIdle: Boolean? = null`, `blankAfterSec: Int? = null`, `markByTap: Boolean? = null`, `frontAfterRestart: Boolean? = null` | phone → glasses | nullable; null = the defaults in §7 | ignored |
| Mark feedback | phone → glasses | the existing `pc_state` `Overlay.Toast("marked · driving")` | — |
| **Not changed** | — | no new `Command` subtype; `ListenStart` stays a `data object`; `GestureAction` unchanged (S45's reasoning) | — |

Wire tests: an old-shape decoder (a copy of the v1 classes) decodes the new `Hello`/`HudSettings` JSON; the new decoder reads old JSON with defaults.

---

## 7. Settings

- Phone Settings → Glasses: **"Stay in front when idle (screen sleeps)"** (default per Q8; proposed on once the D5 spike passes), "Blank after" 5–60 s (8 s), "Return to front after a Rokid restart" (Q4).
- Phone Settings → Capture: **"Mark moment by tap"** (on).
- Phone Settings → Voice: group **"Mark moment"** (on), with examples.
- Diagnostics: `frontFallbacks`, `marksPending`, marks today (counts).

---

## 8. Privacy

1. Audio: glasses PCM exists only in the 100 ms chunk in flight. It is never written to disk or logged (as today's PTT). The phone feeds it to the **on-device** recognizer only (`createOnDeviceSpeechRecognizer`, `FallbackGuard`). Audio is never stored or uploaded.
2. Transcripts: a matched mark utterance is never stored, logged or sent to a model. Only `intentLabel` (an enum), times and `source` are stored. In a mixed utterance, only the unmatched clauses go to the ask path, as any ask does today.
3. No always-on mic, no hotword, no microphone FGS type: the mic is open only during a listen the wearer started and can see (the display is held on).
4. Logs: `outcome=MARKED source=GLASSES`, `mark src=tap intent=1|0` (counts and enums only). Blank-state logs are counts.
5. Marks during a privacy pause: recorded (an explicit wearer act with no sensor data), unless Q7 says otherwise.
6. Deletion: marks are included in Delete all and in the S38 fences.

---

## 9. Tests

JVM (pure):
- `FrontIdlePolicyTest`: every transition in §3.2; the invariants `blank ⇒ !keepOn`, `listening ⇒ keepOn ∧ ¬blank`, the 60 s cap; first key after blank not routed; the 300 ms wake guard (ENTER/DPAD without a fresh 83 dropped); the not-on-top-in-3 s fallback; a display-cycle start does not consume `AutoStartGate`'s pending flag.
- `NoWakeTokensTest`: the glasses sources contain none of `newWakeLock`, `ACQUIRE_CAUSES_WAKEUP`, `setTurnScreenOn`, `FLAG_TURN_SCREEN_ON`, `screenBrightness`, `FOREGROUND_SERVICE_MICROPHONE`.
- `MarkQueueTest`: monotonic persisted seq; cap and TTL; ack removes; undo; resend bursts.
- `MarkCommandParserTest` goldens: positive ("mark that", "mark this", "mark moment", "please mark that, I'm on a bus" → passenger, "mark that and I'm driving" → driving, "I'm on a plane" → aeroplane, "mark it, laptop" → working); negative ("Mark called me", "what did Mark say", "am I driving too fast", "what bus goes to Andheri", "remind me about the meeting", "is this a plane"); mixed ("mark that and what's this building" → mark + ask "what's this building"); unknown intent → MOMENT only.
- Copied tests: `EnglishClausesTest`, `VoiceCommandGroupTest` (toggle encode/decode), `LanguagePackTest` (en-US present, hi-IN absent → pass-through).
- `VoicePipelineInterceptTest`: a match never calls the answerer (fake counts 0) and logs `outcome=MARKED`; group off → answerer called; NoSpeech with a bound mark → "✓ marked", not "Couldn't hear"; Cancel → row deleted.
- `MarkRecorderTest`: clock mapping MAPPED/RECEIPT; ±3 s listen linkage; idempotent `(epoch, seq)`; `ASK_TAP` on pass-through; the entity has no text column (reflection check).
- Wire compat tests (§6).
- Room: `MigrationTestHelper` N−1 → N; the deletion-graph test includes `moment_label`; `SqlCipherMigrationDeviceTest` updated (run on a disposable DB only).

Robolectric (`MainActivityTest`, extended):
- idle `T_blank` → `hud_blank` shown and `FLAG_KEEP_SCREEN_ON` cleared (`window.attributes.flags`); 83 while blank is consumed (not passed to `super`) and unblanks; BACK while blank unblanks; listening keeps the flag; the minimise gate still calls `moveToBack`; a screen-off `onStop`/`onStart` keeps `ui_mode=front` and does not run `startGate.decide`; a tap sends `pc_mark` and shows `MarkCue` before `ListenStart`; a tap with voice blocked still marks.
- Regression: all existing `KeyGestures`, `InputRouter`, `AutoStartGate`, `ExitGateActivity`, `PushToTalk` tests unchanged and green.

---

## 10. Device checklist

Before any build, the device spike (D1–D5, about 1 h of the user plus a tiny debug build with `frontWhenIdle` on). Logcat rule: use the extended secret filter, **never print AssistServer lines**, and report force-stops as **counts** (`grep -c`).

| # | Check | Pass |
|---|---|---|
| D1 | `settings get system screen_off_timeout` (number only); with the app in front and the flag cleared, the display turns off on its own | it turns off ≤ timeout + 2 s after blank |
| D2 | After a wearer wake, `dumpsys activity activities` (resumed activity package only) shows our activity on top, not Rokid home | ours on top in ≥ 9 of 10 wakes; otherwise the 3 s fallback fires and is counted |
| D3 | The wake key and the rest of its gesture: a tap from display-off never starts a listen; a swipe from display-off never changes page | 0 of 10 |
| D4 | The CXR session across display-off: phone link state stays CONNECTED (or goes MINIMISED and back) and the status line says "screen asleep", never "Closed" | correct text in 10 of 10 |
| D5 | Force-stops while in front: incoming message, music start/stop, a call, glasses removal; count the restarts and the gaps (`restartGap`) | numbers recorded → decision on Q5 |
| D6 | Hi Rokid long press still works in front mode (FULL and from blank) | works |
| D7 | Tap → ✓ cue at once; silence → "✓ marked" on HUD and phone; second tap ends early; double-tap → "mark undone" and no row | all |
| D8 | "Mark that, I'm on a bus" → "marked · passenger", no model call (an outcome log count with no answer call); "what's this?" → normal answer and an `ASK_TAP` row | all |
| D9 | The mic held by Hi Rokid: start a tap listen right after a "Hi Rokid" interaction | MIC_UNAVAILABLE toast or a working listen, never a silent listen |
| D10 | Offline: 3 taps with the phone's Bluetooth off, then reconnect → 3 rows, no duplicates | 3 |
| D11 | Battery A/B (§3.8): desk 20 min icon vs front (paused), worn 35 min front | worn ≤ 0.51 %/min; `scrOn`/min ≤ icon; no wake locks from us in `dumpsys power` |
| D12 | Rokid restart → icon → next display-off → front (if Q4 = yes); never over a call | 0 times over the call UI |

---

## 11. Task breakdown (agent-hours; Sonnet implements, Opus reviews each task, Codex does one final pass)

| # | Task | Depends | Stream | agent-h |
|---|---|---|---|---|
| A0 | **Spike**: debug flag `frontWhenIdle` (clear the flag + blank only, no other logic), a key-count diag line, and the D1–D5 script for the user. **Gate:** if D2 or D5 fails badly, stop and re-plan (icon-mode marks only from the phone) | — | F | 1.5 (+1 h user) |
| F1 | `FrontIdlePolicy` (pure) + `NoWakeTokensTest` + tests | A0 | F | 2.5 |
| F2 | `MainActivity` wiring: flag policy, blank state, key consumption, wake guard, display-cycle start bypass, `SCREEN_OFF` receiver via `CaptureService`, the 3 s fallback; Robolectric | F1 | F | 4 |
| F3 | `UiModeStore` `front`, `RestartDiagnostics`/heartbeat, `Hello.uiMode`/`frontFallbacks`, `HudSettings` fields, phone status line and Settings rows, wire-compat tests | F1 | F | 3 |
| F4 | Return to front after a Rokid restart (behind `frontAfterRestart`) | F2, Q4 | F | 2 |
| M1 | Glasses mark: `MarkQueue`, `pc_mark`/`pc_mark_ack` (core model + both sides of glasses-link), `MarkCue`, the tap hook in `onAsk`, undo on cancel | F2 (shares `MainActivity`; land after F2) | M | 3 |
| M2 | Live Fit copy: `commands/` package, `MarkCommandParser`, `EnglishClauses`, `VoiceCommandGroup`/`Gate`, `LanguagePack`, goldens | — | M | 3 |
| M3 | Phone: Room table + migration (number at merge), DAO, `MarkRecorder`, clock mapping, listen linkage, `UtteranceInterceptor` in `VoicePipeline`, `VoiceOutcome.MARKED`, HUD/phone feedback, Voice/Capture toggles, Delete-all hook | M2, M1 (protocol) | M | 5 |
| I1 | Integration on one branch, full gate, APK pair, phone backup first (one-way DB) | all | — | 1.5 |
| D | Device checklist D6–D12 | I1 | — | 2 (+1.5 h user, worn) |
| | **Core total** | | | **≈ 27.5** |
| F6 | *(optional, only if D11 regresses)* phone heartbeat 30 s while the glasses are blank, glasses liveness threshold | D11 | F | 2 |
| M4 | *(optional, otherwise in slice 3 A9)* phone notification action, tile and home button | M3 | M | 2 |

Wall-clock: A0 first (about 3 h including the user). Then streams F (F1→F2→F3/F4) and M (M2 ∥, then M1 after F2, then M3) in parallel, about 10–12 h. Then I1 + D. Roughly **16–18 h wall-clock**, not counting merges and user time. Merge: one Gradle slot each; M3's migration number is fixed only at merge (with the v6 slice 2 order).

---

## 12. Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Being in front brings back AssistServer force-stops on message/music/call scenes (capture gaps 17 s–5½ min) | high | capture continuity, user annoyance | the A0/D5 gate; default to icon if bad; restarts come back in icon (unchanged); Q5 |
| R2 | Rokid brings its home to front on display wake | medium | front mode impossible | the 3 s fallback to icon + `frontFallbacks` count; A0/D2 decides before building |
| R3 | The waking gesture's remainder is delivered and acts (a phantom ask or mark) | medium | wrong mark or an unwanted mic | the wake guard (fresh 83 ≥ 300 ms) + first-touch-only-wakes; D3 |
| R4 | The display turns off mid-listen and the mic goes silent | low (the flag is held while listening) | lost ask or mark | the 3.3 rule 2; the VAD and `audioIdleMs` end cleanly |
| R5 | The session route costs more than the background link | unknown | battery | D11 A/B; F6 |
| R6 | The parser grabs real questions (false marks) or misses labels | medium | wrong labels | anchored rules, a question-word veto, goldens; labels are editable on the timeline; a MOMENT is still recorded |
| R7 | Every Ask creates an `ASK_TAP` row and skews recall | medium | tuning bias | excluded from recall by default; Q1 |
| R8 | Hi Rokid holds the mic at the tap | low/medium | no voice label | the mark is taken before the mic opens; MIC_UNAVAILABLE toast; D9 |
| R9 | A stuck reason holds the display on | low | battery | every reason bounded + the 60 s cap + tests |
| R10 | Room numbering clash with slice 2/3 | medium | merge pain | CREATE-only migration, the number set at merge, foldable into migration A |
| R11 | `MainActivity` conflicts between F2 and M1 (and slice 3 work) | high | merge time | M1 after F2; the policy and queue live in their own files; MainActivity keeps thin hooks |

---

## 13. Relation to other specs

- **Slice 3 §6.4/S45**: `HudSettings.markGesture` is **dropped** (§16.1: the tap is the gesture). The 600 ms feedback, the 10 s window, "a mark never forces a capture" and the phone entry points are kept. `pc_mark` keeps S45's fields and adds `epoch`, `atMs`, `bootCount`, `listen`, `undo`.
- **S54**: kept, with two refinements: the anchor is the **tap** (the same instant as `ListenStart`, but measured on the glasses), and the "Couldn't hear" case becomes a plain mark.
- **Slice 3a A9 (4 h)**: this proposal's M1–M3 (11 h) replace A9's glasses-gesture and voice parts. A9 keeps the phone entry points and the timeline. The §16.2 "new 3a task" is F1–F4 + A0 (13 h).
- **A3 battery rules**: unchanged (no pop-ups while the display is off, `ScreenWakeStats`).

---

## Open questions for the user

1. **Tap = mark + listen:** is it fine that every tap-Ask also stores a mark row (flagged `ASK_TAP`, *not* counted in recall unless you turn it on)? Or should asks never create a mark?
2. **First touch:** after the screen has blanked or gone dark, should the first touch only wake the HUD (proposed: a mark is then two taps), or should a tap from a dark screen mark at once?
3. **Blank delay:** 8 s by default (5–60 s setting). Should the HUD also go dark after idle when you opened it from the launcher yourself, or only after a reopen or capture start?
4. **After a Rokid force-stop restart** (calls, messages, removal): should the app return to the front by itself at the next screen-off (never during a call), or stay in icon mode until you open it?
5. **Trade-off to decide after the spike:** in front mode, Rokid will probably close the app on messages, music and calls (capture gap 17 s–5½ min each time; icon mode avoids this). If that shows up often, do you prefer icon mode by default with front mode as an opt-in "D-pad marking" mode?
6. **Intent words:** confirm the lexicon in §4.4 (e.g. auto/rickshaw/metro/train → passenger; "walking" is not in the canvas vocabulary). Do you want Hindi phrases too (no Hindi parser pack is planned yet)?
7. **Marks while capture is paused:** record them (proposed: they hold no sensor data), or drop them?
8. **Default:** should "Stay in front when idle" be on for this install as soon as the battery check passes, or stay opt-in?
