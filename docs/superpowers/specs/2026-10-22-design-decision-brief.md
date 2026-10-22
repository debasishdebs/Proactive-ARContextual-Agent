# Decision brief: expression nodes, and the hidden-foreground app with Mark moment

Date: 2026-10-22. This brief compares two independent proposals for each feature and recommends one design for each.
- **Pair 1 (expressions):** Opus `2026-10-22-expression-nodes-design-options.md` and Codex `.superpowers/codex/plan-expr-codex.md`.
- **Pair 2 (hidden foreground and Mark moment):** Opus `2026-10-22-hidden-foreground-and-mark-moment-design-options.md` and Codex `.superpowers/codex/plan-hidden-mark-codex.md`.

Code was checked in `../pca-ctx/int` @ `a9059202`. No code, Gradle or device was touched.

**You can answer "yes to all defaults".** §3 lists every open question with a default.

---

## 1. Pair 1: expression nodes (`expr`)

### 1.1 Where they agree (high confidence)
| Topic | Shared position |
|---|---|
| Surface syntax | A CEL-shaped subset: `+ - * /`, comparisons, `&& \|\| !`, `?:`, and `in [ENUM…]`. Numbers are binary64 only. No strings, ints, loops, macros or user functions |
| Runtime | Neither ships a CEL runtime on the glasses. The glasses get a JSON AST and have no parser. An evaluator we own lives in `:core:capture` and is shared by the glasses, phone, simulator and replay |
| Roles | A drop-only guard and a value clamped to one policy field. No role can trigger a capture. The WALKING/UNKNOWN rows stay locked (S32) and the reserved timer stays (S31 G1) |
| Signals | Only signals A3 already computes: the 7 `SecondFeatures`, plus the bout and keep features and `ctx`/`ctxConf`/context age. No new sensor, timer or wakeup |
| Windows | Quantised Int32 one-second buckets, exact integer sums, a monotonic deque for max, horizons of 1–300 s, and at most 4 windows |
| Limits | 32 nodes, 6 programs, 4 windows, 3 tables × 32 rows, ≤ 2 evaluations/s, ≤ 8 KB of state, and the whole plan ≤ 4,096 B (it is never trimmed) |
| Parity | Shared golden vectors, bit-exact Kotlin-vs-JS comparison in GraalJS, and a seeded differential fuzz |
| Failure | A guard error keeps the candidate and a value error uses the row default. More than 10 errors per minute auto-reverts the plan |
| Battery | No measurable cost from computation. The real risk is behavioural (a value can raise `capPerMin`), and the existing S44 gate (≤ 0.42 %/min) covers it |

### 1.2 Where they disagree
| # | Decision | Opus | Codex | **Pick** |
|---|---|---|---|---|
| 1 | Parser | Hand-written parser in Kotlin, plus a second parser in JS. cel-java is a test-only oracle | The cel-java **compiler** on the phone as the frontend. No parser in the browser | **Opus's Kotlin parser, Codex's "no JS parser".** The browser gets ASTs from `/api/expr/check` (see V1) |
| 2 | Context in guards | Allow it (X5, a change to S5) and show a warning | Forbid it (S5 as written) | **Allow it.** It is your own example, and the guard can only drop glances; the 60 s timer still runs |
| 3 | `headSpeed` | `gyroMean` | `gyroMax` of the last second | **`gyroMax`** (see V2) |
| 4 | Aliases (`and/or/not`, `in (A,B)`) | Accept them and rewrite to CEL on blur | Reject them and suggest the CEL spelling | **Accept and rewrite.** About 0.5 h, and it matches how you write |
| 5 | Evaluation order | Evaluate every node (constant cost) with CEL's commutative error rule | Short-circuit, and any error reached aborts | **Opus.** The cost is exact rather than a bound, and the cel-java oracle can check it |
| 6 | `x / 0` | Gives 0 (S33) | ERROR | **Treat it as an error by role** (guard keeps, value takes the default), counted as `exprDiv0`, **not** toward auto-revert. Otherwise a still wearer (`stepHz = 0`) would revert plans every minute |
| 7 | Window warm-up | A partial window answers once `n ≥ 1` | NOT_READY until the full horizon; missing seconds are invalid, not zero | **Codex.** The fallback is safe, and a "300 s mean" over 3 s is misleading |
| 8 | `win.count` | A separate 64-event ring | Per-second counts in the normal ring | **Codex.** One ring type |
| 9 | Versioning | Caps only (`expr`, `sig1`) | Caps plus `planSchema: 2` | **Both.** Cheap, and schema-1 hashes stay unchanged |
| 10 | Editor | CodeMirror 5 mode | Plain textarea | **Textarea first.** CodeMirror arrives with 3b's `py.fn` (B5), so add the mode then |
| 11 | Python promotion | Later | E9 in this feature (3 h) | **Later** |
| 12 | Estimate | 31.5 h (18 h wall-clock) | 44 h core, 56 h with contingency | **34 h** (see §1.4) |

### 1.3 Disputed facts, checked
- **V1. The CEL compiler on Android is heavy.**
  - The POM of `dev.cel:compiler:0.14.0` pulls in full `protobuf-java` 4.35.1, `guava` 33.6.0-**jre**, `antlr4-runtime` 4.13.2, `re2j` and `dev.cel:common`/`protobuf`. The licence is Apache-2.0.
  - `runtime-android` 0.14.0 exists, with Guava-android, protobuf-javalite 4.35.1, re2j and threeten-extra.
  - Today the phone has no protobuf, Guava or ANTLR dependency.
  - So Codex's route adds several MB and a JRE-flavoured Guava to the phone APK, just to parse about 20 grammar rules that we must re-check against our own profile anyway.
  - Verdict: Opus is right about the cost. Keep cel-java as `testImplementation` only.
- **V2. What `headSpeed` should mean.**
  - The `SecondFeatures` field order in `MotionModel.kt` is `gyroMax, gyroMean, gyroRms, aboveOnFrac, accVar, stepHz, rotRad`.
  - Every glance threshold is a peak angular speed: `GlanceParams.onsetRadS = 0.6`, `MotionClassifier.HEAD_MOVING_RAD_S = 0.6`. So `headSpeed > 0.8` only makes sense as a peak.
  - `headSpeed` is not defined anywhere in code. Verdict: Codex (`gyroMax`).
- **V3. S5 really forbids context in guards.** Slice 3 line 355 says "Guards are motion-only and drop-only". Allowing context needs your explicit yes (Q1).
- **V4. The battery figures are correct.** The A3 worn run was 0.49 %/min (handoff, 21:22–21:57 run). The LIVE gate is ≤ 0.42 %/min (slice 3 §1 and line 717). Java and JS `Math.round` both round halves toward +∞, so the quantisation step is bit-identical in both.

### 1.4 Recommended design and estimate
- **The design:**
  - Opus's pcx language, with Codex's warm-up, ring, `count` and `planSchema 2` rules.
  - One Kotlin frontend (parser, checker and canonical printer), which is the authority.
  - A post-order, zero-allocation evaluator in `:core:capture`.
  - A JS **evaluator only** (`canvas/expr.js`) for instant "try it", fed ASTs by the server.
  - Shared goldens and a GraalJS differential fuzz. The cel-java oracle runs in tests only.
- **Merged estimate:** about **34 agent-h**. That is Opus's 31.5 h, minus the JS parser (−1.5), plus Codex's gap/NOT_READY handling and the plan-v2 metadata (+2), plus the alias rewrite (+0.5), plus integration slack (+1.5).
- **First slice (about 15 h), shipped in SHADOW only:**
  - the Kotlin frontend, evaluator, guard and value roles, and scalar signals (**no windows yet**);
  - `/api/expr/check`, the plan encoding and caps, glasses re-validation and apply;
  - `exprErr` and auto-revert, and the textarea inspector with server-side preview.
  - Windows, the JS evaluator and the differential fuzz come in slice 2 (about 12 h). The trace timeline and sweeps come in slice 3 (about 7 h).
- **The device test that gates it** (E10 subset, about 1 h worn):
  - a max-cost plan (6 programs) runs in SHADOW against the identity plan;
  - pass if `exprUs` stays under 50 ms per minute, logcat shows no GC during evaluation, would-drop counts are within ±5 % of exact replay, and old glasses reject the plan with `unsupported:expr`;
  - and worn drain is within noise of the identity run (≤ +0.02 %/min).
  - LIVE still needs S44 at ≤ 0.42 %/min.
- **Prerequisite (unchanged):** slice 3a is merged, and the "week of use" before 3c.

---

## 2. Pair 2: hidden-foreground glasses app, and Mark moment

### 2.1 Where they agree (high confidence)
| Topic | Shared position |
|---|---|
| How the screen hides | Clear `FLAG_KEEP_SCREEN_ON` when idle, draw static black, and let Rokid's own timeout turn the display off |
| What is never used | Wake locks, `setTurnScreenOn`/`FLAG_TURN_SCREEN_ON`, brightness 0, device admin, `WRITE_SETTINGS`, an accessibility service, kiosk or lock-task mode |
| Screen-on during work | Keep-screen-on is held only for interaction and the bounded push-to-talk listen |
| Separate state | Idle-blank gets its own state, not `hudHidden`. Explicit minimise (icon mode) and exit are unchanged |
| Lifecycle | A display-off `onStop`/`onStart` must not count as an exit or a restart, and must not trigger `AutoStartGate` or the camera fallback |
| Voice | Push-to-talk only, with the existing `PushToTalk` and `EnergyVad` (5 s / 2.2 s / 15 s). No always-on mic |
| Parser | Runs on the phone, on the final transcript, before `answerer.answer`. A match makes no model call (`VoiceOutcome.MARKED`) |
| Live Fit copy | `CommandParser` (rewritten), `CompositeCommands`, `VoiceCommandGroup`/`Gate` and `LanguageRegistry`, plus their tests as templates. No mic or audio code. Hindi passes through to Ask |
| Wire | New channels `pc_mark` and `pc_mark_ack`. Only defaulted fields are added. `PROTOCOL_VERSION` stays 1. No new `Command` subtype |
| Privacy | No audio or transcript is stored or logged. Only times, source and label enums are kept |
| Gating | A device spike comes first, and front mode is disabled if the task is lost or the battery A/B fails |

### 2.2 Where they disagree
| # | Decision | Opus | Codex | **Pick** |
|---|---|---|---|---|
| 1 | What a tap means | **Tap = mark + listen.** The mark is recorded at once and Ask opens as today. Silence keeps a MOMENT, "mark that, driving" adds an INTENT, and a question goes to Ask (the row becomes `ASK_TAP`). Double-tap undoes | Tap = mark only. Ask moves to a swipe-reached "Voice command" page | **Opus.** It keeps Ask muscle memory, and S54 already says the voice mark comes "after the normal push-to-talk tap". No gesture is free (V7) |
| 2 | First key on a black but still-interactive screen | It only wakes the HUD (300 ms guard) | A tap reveals the HUD **and** marks | **Opus.** With pick 1 a tap opens the mic, so a phantom tap would also start a phantom listen |
| 3 | Blank delay | 8 s (setting 5–60 s) | 15 s | **8 s.** The observed wake is about 13 s (V6), so 8 s never lengthens screen-on |
| 4 | Mark table | New `moment_label` table | Slice 3's `canvas_label` in migration A | **Codex.** `canvas_label` (MOMENT \| INTENT) is already specced in migration A (V5). One store, one migration |
| 5 | Linking a voice mark to its tap | `pc_mark` with `listen=true`, matched within ±3 s | Listen ID and an elapsed-time anchor on `CommandEnvelope`; voice labels made on the phone | **Merge.** `pc_mark` carries `listenId`, and `CommandEnvelope` gets an optional `listenId`. Exact join, no ±3 s heuristic |
| 6 | Clock | Wall `atMs` mapped through the Hello offset, with elapsed time and boot count stored | Elapsed anchor plus manifest-lite interpolation | **Merge.** Send both. Map with today's `ClockOffset.fromHello` (V8) and keep elapsed time for S42 |
| 7 | Mixed "mark that and what's this building" | Mark, then send only the unmatched clause to Ask | Mark, then ask the wearer to ask separately | **Opus.** You spoke the question on purpose |
| 8 | Return to front after a Rokid force-stop | Optional F4: self-launch blank at the next screen-off | No relaunch of any kind | **Defer.** Off for now; revisit after the spike shows the force-stop rate |
| 9 | Showing a phone-voice mark while the glasses sleep | No wake | Maybe a one-shot `setTurnScreenOn` | **No wake.** Feedback waits for the next wake (A3 rule) |
| 10 | Scope and estimate | 27.5 h (phone entry points stay in A9) | 44 h (includes timeline correction and phone controls) | **About 31 h** (see §2.4) |

### 2.3 Disputed facts, checked
- **V5. `canvas_label` already exists in the spec.** It is in slice 3's migration A (lines 614 and 1035), and migration A is expected at v7 (line 96). A separate `moment_label` table would duplicate it.
- **V6. Screen-on numbers.**
  - "25 wakes / 5 m 22 s, reason `android.policy:KEY`" comes from the **51-min A2-era batterystats** (handoff line 269), not from the A3 worn run. That is about 13 s per wake, so the Rokid timeout is probably 10–15 s; spike D1 measures it.
  - A3 worn: 0.49 %/min, 3.4 camera opens/min, persistent logging off, and our controllable share is about 0.18 %/min.
- **V7. The current key model** (`hud/KeyGestures.kt` and `MainActivity`).
  - Every gesture starts with 83.
  - ENTER or DPAD_CENTER key-up is a tap, unless the key was held for 500 ms or repeated (that is Hi Rokid's long press).
  - Two 83s or one BACK make a double-tap, and DPAD 19–22 are swipes.
  - `dispatchKeyEvent` passes **every** key to `super` while `hudHidden` (line 300), and 83 reaching the system moves our task to the back (line 298).
  - So both proposals are right that idle-blank needs its own key-consuming state.
- **V8. Mic and clock details.**
  - The glasses use `targetSdk = 32`, and the manifest's only foreground-service type is `camera`. So the mic works only while the activity is visible, and the display must stay on for the whole listen (Opus fact 4 holds).
  - `Hello.glassesNowMs` and `ClockOffset.fromHello` exist. `ClockOffset` maps wall clocks only, so Codex is right that elapsed time needs its own mapping. That is why pick 6 sends both.
  - `Command` is a sealed interface and `Wire` uses `ignoreUnknownKeys = true`, so additive fields are safe and a new subtype is not.

### 2.4 Recommended design and estimate
- **The design:**
  - Opus's state machine `FULL → FRONT_BLANK → FRONT_OFF`, with ICON as the explicit-minimise path and the fallback. The flag is held only for bounded reasons, with a 60 s safety cap.
  - The first key only wakes the HUD.
  - Tap = mark + listen, with the merged `listenId`.
  - Labels go in `canvas_label` inside migration A.
  - Codex's NOT-genuine-final rule (no label from a timeout partial) and negation handling ("I'm not driving" gives no INTENT).
  - No self-relaunch.
- **Merged estimate:** about **31 agent-h**.

  | Item | Hours |
  |---|---|
  | Spike A0 | 2 |
  | F1–F3 (policy, wiring, state and wire) | 9.5 |
  | M1 (glasses mark, outbox, listen ID) | 3.5 |
  | M2 (Live Fit parser, with negation and conflicts) | 3.5 |
  | M3 (interceptor and recorder on `canvas_label`, no own migration) | 4.5 |
  | Cross-version tests | 2 |
  | I1 (integration) | 1.5 |
  | Device checklist | 3 |
  | Contingency | 1.5 |

  Phone entry points and timeline correction stay in A9.
- **First slice (about 14 h):**
  - A0, F1–F3 (front-idle behind an opt-in setting), and M1 (tap → `pc_mark` → ✓ cue, queued on the glasses);
  - plus a minimal phone recorder writing MOMENT rows to `canvas_label`, which **lands with or after A8/migration A**. Until then the glasses outbox holds the marks (128 entries, 24 h).
  - The voice parser (M2/M3) is slice 2.
- **The device test that gates it** (spike A0 before F2, then D11):
  - **D1:** with the app in front and the flag cleared, the display turns off within the timeout + 2 s.
  - **D2:** our activity is on top after a wearer wake in ≥ 9 of 10 wakes.
  - **D3:** 0 of 10 phantom taps or swipes from the waking gesture.
  - **D4:** the phone never says "Closed".
  - **D5:** count force-stops on a message, music, a call and glasses removal. Report counts only, never AssistServer lines.
  - Then **D11:** a worn 35 min run at ≤ 0.51 %/min, `scrOn`/min ≤ icon mode, and no wake locks from us in `dumpsys power`.
  - If D2 or D5 fails, front mode stays off and marks come only from the phone and icon mode.

---

## 3. Your open questions (deduplicated, most important first; the default is in bold)

| # | Question | Default |
|---|---|---|
| 1 | May a custom condition read the context (`ctx in [WALKING, UNKNOWN]`) and drop glances? This changes S5; the 60 s timer still runs | **Yes**, with the inspector warning |
| 2 | Does a tap mean mark + listen (Ask stays on tap), rather than mark only with Ask moved to a swipe page? | **Mark + listen** |
| 3 | After the screen blanks or goes dark, does the first touch only wake the HUD (so a mark is two taps from dark)? | **Yes, wake only** |
| 4 | If the spike shows Rokid force-stops in front mode on messages, music or calls, should icon mode stay the default with front mode opt-in? | **Yes.** Front mode is opt-in until D2, D5 and D11 pass, then on by default |
| 5 | Does "no new wakeups" also forbid a phone-voice mark from waking the glasses display? | **Yes, no wake.** Feedback shows at the next wake |
| 6 | Should every tap-Ask also store a mark row (`ASK_TAP`), excluded from recall unless you turn it on? | **Yes, stored and excluded** |
| 7 | Do you confirm the intent words: passenger (bus, taxi, cab, uber, ola, auto, rickshaw, metro, train), driving, aeroplane, working, sitting, talking? English only, Hindi goes to Ask | **Yes** |
| 8 | Does `headSpeed` mean the peak speed of the last second (`gyroMax`)? | **Yes**; a bout-peak signal can come later |
| 9 | Should the editor accept `and/or/not` and `in (A, B)` and rewrite them to CEL? | **Yes** |
| 10 | Is a full-window warm-up acceptable? Windows answer "not ready" (safe fallback) until the whole horizon is filled | **Yes** |
| 11 | Should division by zero be an error by role (safe fallback), counted separately and not toward auto-revert? | **Yes** |
| 12 | Does a 4 KB plan cap (about 6 programs), 300 s maximum windows and v1 glasses-only programs (no phone or hub rules yet) work for you? | **Yes to all three** |
| 13 | Is instant in-browser "try it" (a JS evaluator plus the differential tests) worth it in expression slice 2? | **Yes** (needed for the JS-equals-Kotlin goldens) |
| 14 | Should the screen blank after 8 s (setting 5–60 s)? | **8 s** |
| 15 | Should the app return to front by itself after a Rokid force-stop restart? | **No for now**; revisit after the spike |
| 16 | Should marks be recorded during a privacy pause, and outside "Collect for tuning"? | **Yes to both** (they hold no sensor data) |
| 17 | Should a conflicting or unrecognised intent save MOMENT only? | **Yes** |
| 18 | Does Python-to-expression promotion follow the first expression release? | **Yes, later** |

---

## 4. Order of work
1. Run hidden-foreground spike A0 now (2 h of agent time + 1 h of yours). It does not depend on slice 3.
2. Then front-idle and the tap mark (slice 1). Their storage lands with A8/migration A.
3. Then the voice-mark parser.
4. The expression slice 1 waits for 3a and the week of use. Its spec revision (folding §1.4 into slice 3 §4.3 as X1–X9 plus the picks above) can be written now.
