# v3.0.0 "Proactive agent loop on the phone" — design

Status: DESIGN (Opus, 2026-11-18). Branch `feat/v101-ask-eval`. Roadmap row: `2026-11-16-place-guide-reuse-and-roadmap.md` §D, 3.0.0 ("the place history feeds proactive suggestions").
Paths are relative to `android-companion/`. Today: Room v17 (`ContextDatabase.kt:24`), `PROTOCOL_VERSION = 1`, default plan hash `f5782e0d8610`. This design keeps the protocol version and the plan hash, and asks for one Room migration (v18, question 1).
Depends on: v1.2 Forget lifecycle (`ForgetV2`, read leases, `AskSourceStates`), v1.2 retrieval quality (rule 3a "last state seen"), Place Guide v2 (user pins), v1.5 Nexus withdraw. Effort is in agent-hours (Ah, Sonnet), Codex review per WP not included.

## 1. What "proactive agent loop" means here

**Definition.** A phone-side loop that, at a few cheap moments (a time tick, arriving at a place, a Moment or Scene ending), looks at what PCA has already stored about the user's own day and places, and *may* show one short, sourced, dismissible suggestion. It only reads and reminds. Nothing else happens.

| Family | Example card | Built from (all already on the phone) |
|---|---|---|
| **F1 Open state** | "Last saw the burner lit at 11:36 (20 min ago). Not seen off since." | caption memories about a hazard (stove, iron, tap, door), the place change or Moment end that says "you left" |
| **F2 Place recall** | "Golconda: last visit 12 Nov, your note: *sunset call from Bala Hissar*." | `type="place"` memories (`PlaceMemory`, `PlaceSamplers.kt:100`) with the same `wd:`/`user:` tag, `UserPlace.note` (`UserPlace.kt:23`), mark photos taken there |
| **F3 Routine miss** | "Your 9:00 routine (pill box) not seen today." | a routine the user **confirmed** (mined from the times of matching memories/marks), plus no matching memory today by window end + grace |
| F4 Open loop (v3.x, later) | "You noted 'buy batteries' at the market on Sat." | user notes / marks with intent words, re-surfaced at the same place |

Card wording follows retrieval rule 3a: it states what was **seen and when**, never a fact about the world ("you left the stove on" is not allowed; "not seen off" is). The absence of a card never means "safe"; Settings says so.

**Explicitly NOT in scope**
- No autonomous actions with side effects. A suggestion carries no `proposedAction` (unlike `NudgeEngine.kt:143`, which hands one to `ProposalIntake`); the only taps are view, why, dismiss, snooze, never, thumbs.
- No data leaves the phone. v3.0-v3.3 make **zero** provider calls (no LLM, no vision, no geo fetch); card text is a template over stored snippets ("the model never writes card text", as for `PlaceCard`). An optional phrasing call is v3.4 and OFF (question 7).
- No people recognition, no person-centric families: no "you usually meet X", no face or voice matching; templates never name people; a source memory whose only subject is a person is not a candidate.
- No new capture, sensors or background location. The loop reads what the existing pipeline wrote; place triggers reuse `TripLocation`/`PlaceSampler` while they already run.
- Not a canvas flow: like `PlaceSampler`, it is a side engine (a canvas node would change plan hash `f5782e0d8610` and plan review). The LLM `NudgeEngine` and canvas Nudge flows stay as they are.

## 2. Loop architecture (phone)

```
trigger ─► candidate generators (local SQL + rules) ─► ranker + budget ─► gate pipeline ─► one nudge row (+ meta) ─► surface(s)
   ▲                                                                                                          │
   └──────────────────────────── feedback (dismiss / snooze / never / thumbs) ◄───────────────────────────────┘
```
New package `services/orchestration/.../orchestration/proactive/`. Everything is additive; the nudge pieces it reuses are named per step.

### 2.1 Triggers (no new wake-ups)
| Trigger | Source today | Families |
|---|---|---|
| Time tick | the 15-min `MomentCadenceWorker` (`canvas/moment/MomentCadenceWorker.kt:31` already calls `NudgeTrigger.tickSchedule`): add one `ProactiveTrigger.tick()` call after it | F1, F3 |
| Place arrival | `PlaceSampler` match written as a place memory, or a fresh fix within a `UserPlace.radiusM` while `TripLocation` already runs | F2 (F4) |
| Event | `NudgeTrigger.onEvent` for `TRIGGER_MOMENT` / `TRIGGER_SCENE` (`NodeTypes.kt:124-125`): a Moment end is the "you left" signal | F1 |
All enqueue unique work `proactive_loop_<trigger>` (KEEP, `setRequiresBatteryNotLow`, no expedited work, no wake lock), the `NudgeTrigger.enqueue` pattern (`NudgeTrigger.kt:63-74`). A burst is one run.

### 2.2 Candidate generation (local, no model)
`Candidate(key, family, anchorIds, sourceIds, factsForTemplate, confidence, expiresAt)`. `key` is stable and text-free, e.g. `f2:wd:Q1135340`, `f1:stove:<memoryId>`, `f3:routine:<routineId>:<yyyy-mm-dd>`; it is what feedback and dedupe use.
- **F1 OpenStateGenerator**: a small hazard lexicon over memory `searchText`/tags (FTS, `MemoryEntities.kt`), newest "on" state in the last 3 h with no later "off/closed" state; fires only after a leave signal (Moment end, place change, or 15+ min without a frame of the same Scene). Confidence from the caption's match strength.
- **F2 PlaceRecallGenerator**: on arrival at place P, earlier visits' place memories with P's tag (and P's parent site), the pin note, marks within P's radius; older than 1 day only (no recall of the visit in progress).
- **F3 RoutineMiss**: `RoutineMiner` (nightly, on the cadence worker, charging only) groups memories/marks by a lexicon label and local time of day; ≥ 4 of the last 7 days within ±45 min proposes a routine; it is **inactive until the user confirms it** in Settings. A miss fires at window end + 30 min.

### 2.3 Ranking and budget
`score = familyPrior × confidence × recency × feedbackMultiplier(key, family)`; drop below 0.35; pick the top one per run. Budget, checked before any work and again at commit:
- Proactive cap **3 per day** (question 2), counted inside the existing global nudge cap (`NudgeGates.precheck`, `NudgeGates.kt:26`), so the user's "Nudges per day" stays the ceiling.
- Min gap between shown suggestions: the existing `SHOWN_GAP` (30 min, `NudgeGates.kt:47`); F1 may skip the gap (never the cap).
- Per-key cooldown 24 h (`DUPLICATE` by key, not text) and per-family max 2/day.
- Quiet hours: the same `QuietWindow` occurrence check as nudges; dropped, never deferred (question 3).
- Power: a run is skipped under Battery Saver (`triggers.paused()` already includes it), and generators are bounded SQL (≤ 50 ms, LIMIT 200). The miner runs only while charging. No GPS request is ever made for the loop.

### 2.4 Gate pipeline (every suggestion, in order, fail closed; any throw = drop)
1. **Switch**: Settings > Suggestions master and family switches, OFF by default. Shadow mode = steps 2-8 run, step 9 only writes a trace.
2. **Pause**: any `HubPauses` record (user, quiet hours, auto-stop, `zone:`), Battery Saver → `Paused`.
3. **Zones**: `ZoneFence.blocked()` (`memory/place/ZoneFence.kt`), with a ticket taken before the first suspension and `act(ticket)` around delivery, as `NexusGates.pass()` does (`Gates.kt:28`). Candidates whose anchor place is inside any enabled zone are dropped at generation (`ZoneEvaluator.blocksPoint`).
4. **Budget**: §2.3 (`NudgeGates.precheck` plus the proactive counters).
5. **Feedback**: key on the never list, snoozed, or family off → drop.
6. **Read lease**: `DataOpGate.shared.lease(Leases.Kind.PROACTIVE)` (new enum value, `Leases.kt:17`), `extend(refsOf(sourceIds + anchorIds))`; a Forget holding the gate = no suggestion now.
7. **Provenance**: inside `lease.commit`, `ProvenanceGate.check(all ids, MemoryWipe.generation)` must be `Live` (the `NudgeEngine.kt:131-139` pattern).
8. **Wear / driving**: HUD only if `worn()` is true; during a vehicle episode (the `IntentSampler` episode signal) the HUD is skipped and only F1 goes to the phone (question 10).
9. **Commit**: one `nudge` row (`NudgeEntity`, text = template, `why` = fixed tokens, `sourceIdsJson = AskSources.encodeWithDeps(cited, deps)`) plus one `suggestion_meta` row in the same transaction.

**Per-suggestion Forget linkage**: because every source and anchor is a dependency of the nudge row, the existing cascade erases it (`NudgeEntity.redacted`, the N1 rule). New: the Forget also withdraws the live surface: cancel the notification, clear the HUD overlay if it is showing that id, `NexusHudSink` withdraw by ref. Forgetting the place memory, the pin, or a routine's source memories removes the candidate itself, so it cannot come back.

### 2.5 Delivery surfaces
Routing reuses `NudgeEngine.routeOf` (extracted to a shared `NudgeRouting`): the verbosity ceiling is never raised, and a fallback reason is always traced.
- **Phone**: Activity feed row (it is a nudge row) and, new, a low-importance notification channel "Suggestions" (no sound by default) with Dismiss / Snooze / Never / Why actions.
- **PCA glasses HUD**: `Overlay.Nudge(text, place, suggestion: SuggestionCardInfo? = null)` additive like `place` (`Protocol.kt:135`): title, one line, a source label ("from 2 memories · 12 Nov"). Shown at MEDIUM and MAX (question 4), dismissed by head-motion onset after 2.5 s or 12 s, never during a zone pause. Feedback beyond dismiss happens on the phone.
- **Nexus (v1.5+ line)**: `HudEvent.Nudge(ref, title, body, sources, ttlMs, wake = false)` (`HudEvent.kt:14`) through `NexusHudSink`, whose own gates (`sourcesLive`, fences, `paused`, `worn`) run again.

### 2.6 Feedback and how it changes ranking
`suggestion_feedback(key, family, action, ts)`; ids and tokens only, never text.
- Dismiss: key multiplier ×0.7, recovers linearly over 14 days. Thumbs down: key ×0.4, family ×0.9. Thumbs up: key ×1.2, family ×1.05. Family multiplier bounded to [0.25, 1.5].
- Snooze: 1 h / tonight / tomorrow → a cooldown until then. Never: permanent block of the key; "Never this kind" turns the family off.
- Plain counters, no learned model; Settings > Suggestions lists the never entries and a "Reset learning" button. Auto-pause: 5 dismisses in a row in one family pauses that family and says so.

## 3. Privacy and safety

- **Local only**: generators, miner, ranker and templates run on the phone over the encrypted DB. v3.0-v3.3 add no provider call, no network request and no new upload, so ModelRouter, the vision provider and the upload budget are untouched. The cost is CPU in a 15-min worker that already runs.
- **Optional phrasing (v3.4, OFF)**: `PromptKey.SUGGEST_PHRASE` on the existing NUDGE call class via `RoleRunner`, input = the template facts only, output contract = text ≤ 120 chars + `sourceIds ⊆ input`; counts against the per-day cap (≤ 3 calls/day) and the existing upload rules. A failure shows the template.
- **Explainability**: "Why am I seeing this" (phone sheet from the notification, the feed row, or a long-press in Nexus) shows the family, the trigger ("arrived at Golconda 17:42"), the 1-3 sources with times (opening `CitedPreview`), the score factors in words ("you dismissed this once"), and the Never / Turn off this kind actions. Built from `suggestion_meta.explainJson` (ids + tokens), never stored text.
- **Forget**: §2.4 linkage. Delete all clears suggestion meta and feedback counters; the never list holds only keys and survives like pause zones (question 6). Export includes the suggestion rows like nudges.
- **Sensitive families**: F3 can touch health (meds). It needs a per-routine confirmation, shows a user-chosen label ("9:00 routine") on the HUD, and the detail only on the phone.
- **Zones and places**: a red zone suppresses everything about places inside it; F2 never recalls a visit made inside a zone (those frames were never admitted anyway, `FrameAdmission.kt`).
- **No false comfort**: F1 never says "off" or "safe"; it only reports the last seen ON state and its time. Settings: "Suggestions can miss things. Do not rely on them for safety."

## 4. Evaluation

- **Replay route**: `POST /api/proactive/replay` (Developer mode, authenticated like `/api/ask/eval`), body `{day, stepMin: 15, events?: [...]}`; runs triggers → generators → ranker → gates 2-8 as of each step over the stored data in **dry run** (no rows, no surfaces, a read-only lease like `TRY_IT`), and returns per step the candidates (key, family, score, gate verdicts, source ids, template text).
- **Tool**: `tools/eval_proactive.py` (stdlib + the `m1-run.sh` pairing state, like `eval_pca.py`): replays labelled days from `tools/testdata/proactive-eval/days/*.jsonl` (the user labels each would-show card useful / not useful / wrong / harmful; seeded events such as "stove lit at 11:36, left 11:50"), compares with `--baseline`, and prints a report.
- **Metrics and v3.0 exit bar**: useful-rate of would-show cards ≥ 60 %; harmful or false-claim cards = 0; recall of the seeded F1 events ≥ 80 %; shown/day ≤ cap on every day; zero gate leaks in the leak tests (inside zone, paused, forgotten mid-run, not worn); p95 run time ≤ 300 ms.
- **Shadow on device**: 7 worn days with delivery off (question 8); the Developer screen lists the would-show cards (ids + template text, local only) with thumbs that feed the dataset. Live metrics later: dismiss rate, never rate, thumbs ratio per family (local counters on the Diagnostics screen).

**Failure modes**
| Mode | Mitigation |
|---|---|
| False reassurance (no card, stove was on) | wording + Settings notice; recall target in eval; F1 is a reminder, not a monitor |
| False alarm / nagging | threshold, cap 3/day, per-key cooldown, dismiss decay, auto-pause after 5 dismisses |
| Stale or forgotten sources | lease + ProvenanceGate at commit, Forget withdraws live surfaces |
| Routine drift on trips (time zone change for Sri Lanka, travel days) | routines in home local time; F3 suspended when the phone's time zone differs from the routine's or the user is > 50 km from home (a zone-state-like flag, no fix stored) |
| HUD distraction while driving | vehicle-episode suppression (§2.4 step 8) |
| Duplicate with LLM nudges | shared cap and gap; a suggestion whose sources overlap a nudge shown in the last 2 h is dropped |
| Zone GPS error | `ZoneFence` fails closed (stale fix, revoked location = blocked) |
| Battery / heat | no new wake-ups or GPS; Battery Saver = skip; the miner runs only while charging |
| DST / clock change | `QuietWindow` occurrences; routine windows computed per local date |

## 5. Staged rollout

| Version | Ships | Tasks |
|---|---|---|
| **3.0** | the loop in shadow, then F2 place recall on the phone surface (feed + notification), Why sheet, settings, replay eval | T1-T10 |
| 3.1 | HUD card + Nexus delivery, feedback changes ranking | T11-T14 |
| 3.2 | F1 open state (after its eval bar is met in shadow) | T15-T16 |
| 3.3 | F3 routines with confirmation, trip suspension | T17-T18 |
| 3.4 | optional model phrasing (OFF), F4 open loops spike | T19-T20 |

| T | Task | Files (new unless noted) | Acceptance | Ah |
|---|---|---|---|---|
| T1 | Room v18: `suggestion_meta(nudgeId PK, key, family, score, explainJson, trigger)`, `suggestion_feedback(id, key, family, action, ts)`, DAO, migration | `services/memory/.../db/SuggestionEntities.kt`, `SuggestionDao.kt`, `ContextDatabase.kt` (edit), migration + schema JSON | migration test 17→18 on a v17 fixture; Delete all clears both tables except never rows | 4 |
| T2 | `Leases.Kind.PROACTIVE`; meta row deleted in the nudge Forget cascade | `forget/lifecycle/Leases.kt` (edit), the nudge cascade (edit) | DB test: forgetting any source erases the row + meta; a Forget during a run commits nothing | 3 |
| T3 | `Candidate`, `CandidateKey`, generator interface, `PlaceRecallGenerator` | `orchestration/proactive/Candidate.kt`, `generators/PlaceRecall.kt` | unit tests: same-place history, parent site, pin note, nothing from the visit in progress or inside a zone | 5 |
| T4 | `ProactiveRanker` + `ProactiveBudget` (pure) | `proactive/Ranker.kt`, `Budget.kt` | table tests: cap, gap, family max, per-key cooldown, quiet hours, threshold | 4 |
| T5 | `ProactiveEngine`: gate pipeline 1-9, shadow mode, extract `NudgeRouting` from `NudgeEngine.routeOf` | `proactive/ProactiveEngine.kt`, `nudge/NudgeRouting.kt`, `NudgeEngine.kt` (edit, no behaviour change) | existing nudge tests unchanged; leak tests: paused, zone, not worn, forgotten mid-run, lease blocked = no row | 7 |
| T6 | Triggers: cadence tick, place arrival hook, Moment/Scene events; unique work | `proactive/ProactiveTrigger.kt`, `ProactiveWorker.kt`, `MomentCadenceWorker.kt` + `PlaceSamplers.kt` (one-line hooks) | a burst is one run; battery-not-low constraint; hooks never throw into their callers | 4 |
| T7 | Templates + source labels per family | `proactive/Templates.kt` | golden tests; no person name ever rendered; F1 wording never contains "off/safe" | 3 |
| T8 | Phone notification channel + actions, withdraw on Forget | `phone/.../proactive/SuggestionNotifier.kt` | Forget cancels the notification; actions write feedback rows | 4 |
| T9 | Why sheet + Settings > Suggestions (switches OFF, cap, never list, reset) + Developer shadow list | `phone/.../ui/suggestions/*.kt`, `SettingsRoot.kt` (edit) | UI tests; why shows sources with times from `explainJson` only | 6 |
| T10 | Replay route + `tools/eval_proactive.py` + 3 seeded days | `phone/.../canvas/api/` (edit), `tools/eval_proactive.py`, `tools/testdata/proactive-eval/` | dry run writes nothing (DB diff test); report lists the §4 metrics | 6 |
| T11 | HUD: additive `SuggestionCardInfo`, presenter + composable, MEDIUM/MAX, dismissal | `Protocol.kt`, `core/hud/.../Overlays.kt`, `HudPresenter.kt` (edits) | old glasses build ignores the field (shows text); serialization round-trip; no card during zone pause | 5 |
| T12 | Nexus delivery via `HudEvent.Nudge`, withdraw by ref | `phone/.../nexus/NexusGraph.kt` (edit) | sink gates re-run; Forget withdraws within one delivery | 3 |
| T13 | Feedback → ranking multipliers, decay, auto-pause | `proactive/Feedback.kt` | table tests for each action and the bounds | 4 |
| T14 | Vehicle-episode and not-worn routing | `ProactiveEngine.kt` (edit) | tests: driving → no HUD, F1 phone only | 2 |
| T15 | `OpenStateGenerator` (lexicon, last state, leave signal) | `generators/OpenState.kt` | replay recall ≥ 80 % on seeded days, zero false-claim wording | 6 |
| T16 | F1 safety fixtures + leak tests in the eval set | `tools/testdata/proactive-eval/` | the eval bar runs in CI-less `--check` mode | 3 |
| T17 | `RoutineMiner` + confirm flow | `proactive/RoutineMiner.kt`, settings screen | proposes only at ≥ 4/7 days; inactive until confirmed; runs only while charging | 6 |
| T18 | `RoutineMissGenerator` + trip suspension | `generators/RoutineMiss.kt` | time zone / > 50 km away suspends; DST tests | 4 |
| T19 | Optional phrasing (`SUGGEST_PHRASE`, contract, OFF) | `services/ai/.../contract/` (edits), `ProactiveEngine.kt` | contract rejects foreign ids; failure = template; ≤ 3 calls/day | 5 |
| T20 | F4 open-loop spike (design note + replay numbers) | spike only | a go / no-go with numbers | 3 |

Totals: v3.0 ≈ 46 Ah, v3.1 ≈ 14 Ah, v3.2 ≈ 9 Ah, v3.3 ≈ 10 Ah, v3.4 ≈ 8 Ah (≈ 87 Ah, plus Codex rounds). Parallel worktrees: {T1, T2} · {T3, T4, T7} · {T8, T9} after T5; T10 after T5.

## 6. Open questions (recommended default in bold)

1. Storage: **Room v18 side tables keyed by the nudge id** (suggestions stay nudge rows, so feed, cap and Forget are reused), or no migration by encoding meta in `sourceIdsJson`/`why` (hacky, weaker Why sheet)?
2. Budget: **own cap 3/day inside the global nudge cap**, or a separate cap on top of it?
3. Quiet hours and F1: **quiet hours always win** (F1 only skips the min gap), or let F1 through quiet hours?
4. HUD verbosity: **MEDIUM and MAX like place cards**, or MAX only like LLM nudges?
5. Routines (health): **per-routine confirmation, generic label on the HUD, detail on the phone**, or no F3 at all?
6. Delete all: **never-list keys survive (like pause zones), everything else cleared**, or clear the never list too?
7. Provider phrasing: **templates only, no v3.4 phrasing call**, or build it OFF behind a switch?
8. Shadow: **7 worn days of shadow before delivery is switched on**, or ship F2 delivery straight away?
9. LLM nudges: **coexist with a shared cap and source-overlap dedupe**, or turn the built-in LLM nudge off when Suggestions is on?
10. Driving: **no HUD while in a vehicle episode, F1 on the phone only**, or drop everything while driving?

## v3.1 r6 addendum (Codex r5 P1s)

- Glasses card lifetime: `PlaceCardInfo.hideAtMs` (additive, optional; PROTOCOL_VERSION stays 1) is an absolute quiet-hours deadline in the GLASSES wall clock. The glasses hold no clock offset; the phone converts the boundary with its hello-measured offset (the one it uses for pauses), minus a 3 s slack. The glasses evaluate `remainingMs(now)` at the first draw (pop-up and HUD), clamped to 0..12 s; 0 draws nothing. `maxShowMs` stays as the duration fallback. Offset unknown: fail closed, a boundary within 15 s means the card is not drawn.
- Dismissal durability: when the ledger write, the marker file and the marker fallback ALL fail, the store sets a process-lifetime fail-closed flag (never reset, not even by Delete all): no suggestion is delivered for the rest of the process, the in-memory hold stays, and a full hold trips the flag instead of silently dropping the oldest. A restart after such a total storage failure CANNOT recover the pause: nothing was written (unavoidable without storage).
