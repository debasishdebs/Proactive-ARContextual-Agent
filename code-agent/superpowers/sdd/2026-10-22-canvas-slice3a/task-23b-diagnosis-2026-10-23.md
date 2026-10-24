# Task 23b diagnosis, part 2 (worn trace bc1f4b0e, 2026-10-23)

Method: READ-ONLY on the repo. I copied android-companion (no build dirs) to `<scratchpad>/repo`, added a throwaway test + debug counters + experiment switches THERE (never in the repo), ran one `--offline --no-daemon` Gradle per variant on `:core:capture:test` against the pulled real trace (plain JSON chunks, verified by `TraceFiles.verify`), and analysed the chunk format with Python (`<scratchpad>/an/load.py`). No device, no adb, no commit. Repo working tree untouched (git status shows only the pre-existing doc changes).

Reproduced exactly on the unmodified code: baseline opens 154, pass 20, fail 4, inexact 92 (all ASYNC_CALLBACK); identity candidate opens 122, unobserved 11.475 %. Desk trace b653442d: 3 / 3, 4 pass 0 fail (the old phantom is already gone).

## Headline

There is NO settings difference between the stored identity plan and the recorded settings. The 154 vs 122 gap is an artefact of the baseline replay mode, and the root cause of both the gap and all 4 failing windows is one thing the simulator does not model: **the live pipeline is stalled during 38 sensor-delivery gaps (208 s = 13.9 % of the trace), while the replay keeps ticking through them.** Modelling the stall in a throwaway copy moves the numbers to baseline 123 opens (recorded 115), 23 pass / 1 fail, candidate 119, unobserved 1.7 %.

## (a) Why 122 vs 154

PROVEN equal (printed from the candidate and the manifest snapshot in the run):
- `PlanJson.configHash(PlanResolver.identity())` = `f5782e0d8610` = `snapshot.planHash`.
- `ReplayCandidate.of(identity)` glance == `snapshot.glance` field by field (onset 0.6, release 0.3, bridge 250, minBout 300/0.26, settle 1500, noveltyWindow 20000/0.3, bigTurn 0.8, capPerMin 4, restCap 2, deadband 0.05, maxDt 100). Gate = hamming 10, bigChange 22, ringAll true == recorded `gp` event (10, 22, true). Table = ContextPolicies.DEFAULT rows (WALKING/UNKNOWN unchanged, REST cap 2 / 30-60-30-120 s). The trace was recorded with wake SETTLE tier T0 (`pf` 10000/30000/10000/60000), which equals `CaptureProfile.of(SETTLE, T0)`, so the candidate's forced SETTLE profile is the recorded one (REST segment `pf` 30000/60000/30000/120000 ctxCap 2 hard=true also matches the REST row; inferred from the matching numbers, 0.9 min only). So no field, default or tolerance is dropped when building the candidate. (`ReplayCandidates.kt` L14-19 -> `ReplayCandidate.of` CaptureSimulator.kt L54-67.)

PROVEN where the 32 opens come from (cause split of the replayed decisions vs the 115 recorded post-pre-roll opens, 44 timer + 71 dwell):
| | TIMER | DWELL | total |
|---|---|---|---|
| recorded | 44 | 71 | 115 (116 incl. one pre-roll) |
| baseline replay | 80 | 74 | 154 |
| identity candidate | 44 | 78 | 122 |

Baseline TIMER = 80 because of repeated phantom decisions. Mechanism (CaptureSimulator.kt):
1. During a stall the replay ticks every 250 ms of event time (`advanceTo` L313-320) although the live loop did not run. When the timer comes due inside the stall the replay `decide()`s (L402).
2. In the BASE mode the next due time moves only when the recorded close arrives (`if (!ownCadence) lastBriefAt = e.tMs`, L450), and the recorded open does not exist until the stall ends. `suppressUntil = t + DECISION_HOLD_MS` (L404, 1000 ms) only delays the next decision, so the replay decides AGAIN every 1000 ms until the stall ends: runs of 3-6 TIMER decisions at 1 s spacing (e.g. 5894909, 5895909, 5896909; 6292060 ... 6297060 six times; 7134670 ... 7137670 four times). Baseline has 40 `open_extra` mismatches; 35 of 41 total mismatches lie inside a stall, the other 6 sit within 16-600 ms after a stall end.
3. The identity candidate has `ownCadence` (table != null): `decide()` sets `lastBriefAt = t + CANDIDATE_OPEN_MS` (L405), so it decides ONCE per due timer (its 44 timer opens = the 44 recorded ones, but placed 2-8 s EARLY, inside the stall: 16 recorded timers have no candidate twin and 23 candidate opens have no recorded twin, 14 of them inside stalls). Its DWELL surplus (78 vs 71) is dwell callbacks firing on the burst delivered at stall end (e.g. 6297114, 6510122, 6510622) plus the cap/keep bookkeeping of its own gate.
So the baseline 154 is inflated by ~36 repeat decisions; 122 is closer to the truth (recorded 115, +6 %) but is also stall-contaminated.

Stall evidence (PROVEN from the trace): recorded `g` events have 38 delivery gaps > 700 ms, 1.0-8.1 s, sum 207.9 s; delivery lag (`tMs - sMs`) reaches 8103 ms (sensor data are buffered then delivered in a burst of ~270 events); NOT ONE recorded camera open falls inside any gap, although a timer/dwell was due in most of them; the first recorded open after a gap end follows it by 266-1254 ms (typical 280-590 ms). INFERRED (not proven): whether the whole glasses process/looper is suspended (doze/sensor-hub batching with a frozen loop) or only the sensor delivery plus a stale classifier state; the diag log is not periodic enough to tell (`features` lines are change-throttled). Either way the live loop made no decision inside the gaps.

Experiment (throwaway, copy only): in `accept()`, when a sensor event arrives more than STALL_MS (700/1000/1500, all identical) after the previous one, set `nextTickAt = e.tMs` (do not tick through the gap). Result: baseline 154 -> 123, pass/fail 20/4 -> 23/1, inexact unchanged 92, candidate 122 -> 119, unobserved 11.5 % -> 1.7 %. Desk trace unchanged (3/3, 4/0).

Minor secondary asymmetry: the identity candidate is still not equal to the baseline (119 vs 123) because baseline counts unmatched replayed decisions (`opens` L388-413 counts every decision) while the candidate has no recorded to match; the residual 4 are decisions made at stall end whose recorded twin is >500 ms later (see (b)).

## (b) The 4 failing windows (of 24 compared, 20 pass): all `open_extra` caused by a stall, none by gates

(windows are recorded-open to recorded-open; trace-relative time mm:ss and wall clock 22:45:08.589 + offset; ctx from the `cx` events; all gate comparisons equal, gateBad = 0 in every window)
1. start 6004374 (3:04, 22:48:12.8), ctx WALKING, TIMER->TIMER (next recorded open 6017051): 3 extra replay TIMER decisions 6014210/6015210/6016210. Stall 6009102-6016770 (7.7 s). Recorded open is 281 ms after the stall end and 841 ms after the last replay decision (> LATE 500 ms).
2. start 6297694 (7:57.6, 22:53:06.2), WALKING: replay decided TIMER at 6297060/6297108 (stall 6289308-6297108, 7.8 s), the recorded TIMER open came 634 ms (586 ms with the stall model) later: beyond LATE 500, so the recorded open is `open_missed` (booked in the previous, inexact window) and, because it stays in `awaiting` until 6297944, the replay takes a phantom DWELL decision at 6297873 (`awaiting=[6297694]`, `decidable` L382) that is the 1 `extra` of this window. This is the only window that still fails with the stall model.
3. start 6591464 (12:51.3, 22:57:59.9), WALKING (stillStep 1): 1 extra TIMER at 6611276 (stall 6605668-6611892, 6.2 s); recorded open at 6612164 = 272 ms after the stall end, 888 ms after the decision.
4. start 7116993 (21:36.9, 23:06:45.5), ctx UNKNOWN: 4 extra TIMER decisions 7134670..7137670 (stall 7130235-7138091, 7.9 s); recorded open 7138490 = 399 ms after the stall end, 820 ms after the last decision.
Expected (recorded): one TIMER open ~0.3-0.6 s after the stall ended. Simulated: 1-4 TIMER decisions 0.8-3.8 s before it, inside the stall. Cause class: unmodelled stall (not wake-up latency of a running loop, not an early/late asymmetry in the match rule). The late-after-resume latency (266-1254 ms) is wider than the LATE 500 ms slack, which was fitted to a loop that is running.
Hidden by inexactness: of the 40 baseline extras, 12 are in these 4 windows; the rest are in the 92 ASYNC windows that are excluded, so the 20/4 verdict was never measuring the stall problem fully.

## (c) ASYNC_CALLBACK and the parity criterion

What produces it: `Exactness.classify` (TraceFiles.kt L136-148) flags a window when an `ep` open has cause DWELL or ONSET ("depends on an async collector callback", also `Inexact` doc L124-128 of CaptureSimulator). A window runs from one recorded open to the next, both included. The trace has 71 dwell and 44 timer opens, so only windows bounded by two TIMER opens survive: 24 of 116 (21 %), 92 flagged (79 %). Windows are cut at every open, so a single dwell open spoils two windows.

Can any be made exact: YES for DWELL, PROVEN on this trace. The replay re-derives dwell decisions from the same samples: all 71 recorded DWELL opens have a replayed DWELL decision within the existing match rule [-250,+500] ms, offsets min -175 / median +78 / max +483 ms (the async callback latency is small and one-sided), and 43 of 44 TIMER opens match (the 44th is the 586/634 ms stall-resume case). Experiment (copy only): not flagging DWELL as ASYNC_CALLBACK gives 116 windows, 0 inexact; baseline: 99 pass / 17 fail; with the stall model 107 pass / 9 fail. The 9 failures are all `open_extra` at a stall end whose recorded twin is 0.5-1.3 s after the resume decision (windows starting 5898404, 5911139, 5923683, 5940571, 6280353, 6297694, 6671930, 7248499, 7267436), i.e. the stall's resume latency again. Not claiming ONSET: the trace has no onset opens, so ONSET stays flagged.
Still genuinely inexact (unobservable inputs): CAPTURE_NOW and BIND_FAILURE (none in this trace).

Why the old criterion compared different things (PROVEN from code): `shOpen` is `ShadowRunner.fired()` counts of `GlanceAdmission.decide == OPEN` (ShadowRunner.kt L96-104, heartbeat delta via `smart.shadow.take()`, CaptureService.kt L360), i.e. glance (dwell/onset) admissions only, with the shadow's own keep bookkeeping. It contains no timer opens and runs on the sensor thread, not on the loop. Replay `opens` counts timer + dwell + resv decisions. 82 shadow vs 162/122 compared glances with all opens. Heartbeat deltas over the trace span (21 heartbeats, 22:45:08-23:10:00): shOpen 82, glances1m 78, wk dwell 74, wk timer 44 (= the 44 recorded timer opens exactly, so wk deltas cover the span fully). The right counterpart of shOpen is the counterfactual DWELL(+ONSET) opens: 78 (no stall model, identity candidate) / 75 (with stall model) vs recorded dwell 71-74. 82 vs 78 = +5.1 %, 82 vs 75 = +9.3 %.

### Proposed criterion (replaces the +-5 % total-opens rule)
P0 Validity (replay of the recorded config must reproduce the recording): requires the simulator fix below (no ticks through sensor stalls). Over all windows with DWELL no longer flagged: for each cause, recall = recorded opens with a replayed twin within [-250,+500] ms / recorded opens >= 98 % (measured 114/115 = 99.1 % even today), AND replayed opens with no recorded twin ("precision") <= 5 % of recorded opens (today 154 vs 115 = +34 %; with the stall model 123 vs 115 = +7 %, still above; a post-stall slack (below) is needed to get under 5 %).
P1 Shadow vs counterfactual (the milestone): compare `sum(shOpen)` over heartbeats fully inside the trace span with the candidate's DWELL+ONSET opens (cause-filtered) over the same span, same plan hash. Tolerance: |diff| <= max(5 %, 4 opens) of the counterfactual; the boundary uncertainty is the pre-roll (10 s) and the heartbeat edges (<= 1 window). Current data (+4 on 78, +7 on 75) is borderline; confirm on the next worn run before setting m1Passed.
P2 Total opens (timer included) are NOT compared to shadow; they are reported as an estimate with a stall bound: `opens_exact_windows` + `opens_in_flagged_windows` (taken from the recording) with the replay-minus-recorded difference reported separately.
P3 How inexact windows are bounded, not ignored: report for every replay (a) inexact windows by reason, (b) minutes and share of the span they cover, (c) a new reason STALL for any window that contains a sensor delivery gap > 1000 ms (count, total stall seconds; here 38 / 208 s / 13.9 %), (d) the figures as [low, high]: low = replay opens in exact windows + recorded opens in inexact windows; high = low + replayed decisions in inexact windows. Require exact minutes >= 70 % of the span (today 21 %) for M1; below that the gate stays unpassed. Post-stall late slack: treat the first decision at/after a stall end as matching a recorded open up to 1300 ms later (observed 266-1254 ms), instead of widening LATE 500 globally.

## Root cause and minimal fix (it IS a simulator bug/gap, not a settings bug)

Root cause: `CaptureSimulator.Run.advanceTo` (L313-320) simulates a tick every 250 ms of event time even across a gap in sensor delivery in which the live loop made no decisions; with no recorded episode the BASE mode re-decides every `DECISION_HOLD_MS` (L404) because `lastBriefAt` only advances on the recorded close (L450).
Minimal fix (verified by experiment in the copy; not applied to the repo):
1. In `accept()`: when a Gyro/Accel event arrives and `e.tMs - lastSensorT > STALL_MS` (1000), skip the ticks inside the gap (`nextTickAt = e.tMs`), so the first decision happens at the resume instant; widen the match slack for that first post-stall decision (LATE up to ~1300 ms) or flag the window STALL.
2. Stop counting unmatched repeat decisions as figures: the baseline `opens` in `CounterfactualReplay.run` (`base.opens.size`, CounterfactualReplay.kt L125-126) should count recorded opens for the recorded config (as it already does for camera-open seconds), so baseline and identity candidate are comparable; or at least dedupe decisions by recorded twin.
3. Do not flag DWELL as ASYNC_CALLBACK (TraceFiles.kt L141); require the twin match instead (ONSET stays flagged until a trace with onset opens shows it matches).
Failing tests to write first:
- `CaptureSimulatorTest`: a trace with a 6 s hole in `g`/`a` events during which a TIMER becomes due and a recorded TIMER open 300 ms after the resume: expect passed = 1, failed = 0, `opens.size == 1`, no `open_extra`. Today it yields 4-6 decisions at 1 s spacing and `open_extra`.
- `CounterfactualReplayTest`: on that trace the identity candidate (`ReplayCandidate.of(PlanResolver.identity())`) must give `candidate.opens == baseline.opens` (identity-equivalence property: with the recorded wake SETTLE) and `baseline.opens == recorded open count`.
- `Exactness` test: a window with a DWELL open whose replayed twin exists is exact; one with no twin fails.

## Open points / not proven
- Whether the stall is a process suspend or only sensor batching (see above). A discriminating check on the glasses: log tick timestamps inside `SamplingSession` (or compare `SystemClock` vs uptime) during a gap; or the recorder could add a `stall` event.
- The 4 residual candidate-vs-baseline opens after the stall model, and the exact REST-segment equality, are inferred from the matching numbers, not separately instrumented.
- The shadow-vs-counterfactual tolerance is proposed from one trace (n = 1 worn run, 82 vs 75-78); the next worn 20-minute run should confirm it before M1.
- Existing named unit tests (CandidatePolicySimulatorTest, CounterfactualReplayTest, CaptureSimulatorTest, TraceParityTest) were NOT run; I ran only the throwaway real-trace test (which uses the same entry points as `ReplayService`).
- Throwaway artefacts (outside the repo): `<scratchpad>/repo`, `<scratchpad>/an/load.py`, `<scratchpad>/an/out/*` (opens lists, mismatches, per-window debug).

## Addendum (2026-10-23 22:40, controller, measured with tools/trace-lab on v0.9.1)
The open question "whole process suspended or only sensor batching" is answered by the data: the stalls are DELIVERY delays. Sensor timestamps (`sMs`) stay continuous while the record/delivery time (`tMs`) shows bursts.
- Trace bc1f4b0e (24.8 min): delivery gaps >1 s: 38, 208 s total (13.9 %), longest 8.1 s; sensor-time gaps >1 s: 0; max delivery lag 8.1 s.
- Trace 0e9c2f76 (98.8 min, PARTIAL): delivery gaps >1 s: 189, 561 s total (9.5 %), longest 9.4 s; sensor-time gaps >1 s: 1 (4.1 s); max delivery lag 9.4 s.
Consequences: no sensor data is lost and sensor-time features are sound, but live decisions run on data up to ~9 s old for ~10-14 % of the time. The replay stall model (stalls as delivery delays) is the right model. Lead (unproven): the sensor FIFO / max report latency configuration (`sensors fifo gyro=10000 acc=10000` in the diag log, and `sensors=low` in heartbeats): test a lower report latency while worn and measure battery. Backlog item for after v1 blockers.
