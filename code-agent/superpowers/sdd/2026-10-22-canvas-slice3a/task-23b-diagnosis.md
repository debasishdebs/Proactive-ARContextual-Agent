# Task 23b diagnosis: replay disagreement on trace b653442d (desk, wear/still, 2.06 min, configHash f5782e0d8610)

Method: throwaway JVM test (not committed, deleted) loaded the pulled trace by absolute path (TraceFiles.verify + ChunkCodec.decode, 6573 events), ran
CaptureSimulator.replay(base). Reproduced the phone result exactly: passed=3 failed=1 inexact=0, sim opens=4, recorded opens=3.

## The failing window
The first one (opens at the start of the post-pre-roll span). Mismatch: `open_extra @5313521 (TIMER) with no recorded open`.

Recorded `ep` opens (tMs): 5313780, 5353771, 5413759 (all TIMER, all outcome OK; closes 5314226, 5354218, 5414196). No gt keeps (3 gt, all SKIP DUPLICATE). stillStep 1 at start, bo=2 then bo=3.
Simulated TIMER decisions: 5313521, 5314021, 5353632, 5413624.

Arithmetic (CameraPolicy.stillGap 10000<<step capped 60000; lead = p95 of startups = 594 ms, same in sim and live because the snapshot startups window is identical):
| open | dueAt | ideal tick dueAt-594 (= sim) | recorded | recorded minus sim |
|---|---|---|---|---|
| 1 | 5294115(snapshot lastBriefAt)+20000 = 5314115 | 5313521 | 5313780 | +259 ms |
| 2 | close 5314226 + 40000 = 5354226 | 5353632 | 5353771 | +139 ms |
| 3 | close 5354218 + 60000 = 5414218 | 5413624 | 5413759 | +135 ms |

The simulator reproduces the policy (dueAt, lead, cause) exactly. The live loop is consistently LATE by 135-259 ms against the ideal tick that lands on dueAt-lead.
Windows 2 and 3 pass only because they are inside the +-250 ms tolerance. Window 1 is late by 259 ms, 9 ms outside it, so the sim open (5313521) matches nothing.

Knock-on (cosmetic but visible in the report): the unmatched sim open leaves the recorded open waiting in `awaiting`, the tick at 5314021 still sees timerDue
(lastBriefAt only moves at the recorded close) and decides AGAIN; that second decision matches the recorded open (241 ms later, in tolerance). So baseline `opens` = 4 for 3 recorded
opens, and the candidate's camOpenS (1.77 = 4 x mean open 0.443 s) differs from the baseline's (1.33 = the 3 recorded durations) only because of that phantom open.
The identity-plan counterfactual should equal the baseline; it does not for this reason.

## Cause class: (c) approximation noise ReplayRule does not tolerate, with a modelling gap behind it
Not (a): the tick-loop mirror is right (dueAt, lead, hold, cause, close time, back-off, episode outcome all agree; gates 3/3 equal). Not (b): the tick instant is not a decision input;
the decision instant is in fact recorded (the `ep` open tMs is taken at the tick). The gap is that live wake-up latency of the tick (delay(wait) oversleep plus tick body, 135-259 ms on the glasses)
is not modelled: the sim lands exactly on dueAt-lead, the device never does and is always late, never early (kicks are replayed, so early ticks are reproduced).
Not verifiable from the trace which part of the 135-259 is oversleep versus body; both are one-sided (late).

## What the rule needs (not implemented)
Principled: make the tolerance ASYMMETRIC. Replayed-earlier-than-recorded (recorded late) gets a late slack, because latency only delays a tick; keep 250 ms for recorded-earlier-than-replayed.
Evidence supports a late slack of about 500 ms (2 ticks) for this device; 259 is the only data point beyond 250, the max over 3 is 259.
Not done because: `TraceFormatFreezeTest` (lines 175-179) pins `OPEN_TOLERANCE_MS == 250` and symmetric matching, and the same constant bounds the pendingReplayed/awaiting/closing expiry
(CaptureSimulator lines 347, 523, 531, 541, 555-556), so widening needs a ruling and a coordinated change of those expiries and that freeze test. Three data points is thin; one more worn trace
(M1 run with a walking segment, many more TIMER opens) should confirm the +135..+260 ms distribution before widening. No schema change is needed.
A complementary sim fix (adopt a recorded open as the twin of the immediately preceding unmatched sim decision, so no second decision) would remove the phantom open too, but only matters once a decision falls outside tolerance.

## Rulings implemented (controller)
1. 55aaa661: ReplayRule.opensMatch asymmetric (EARLY_SLACK_MS=250 recorded earlier than simulated, LATE_SLACK_MS=500 recorded later); simulator expiries updated (pendingReplayed/closing use LATE, awaiting uses EARLY); TraceFormatFreezeTest changed deliberately (real numbers 5313780 vs 5313521 match, 5314100 does not); 3 simulator tests (late within slack matches, late beyond fails, early beyond 250 still fails). Mutation checks (LATE=250, EARLY=500) are caught. Real trace (208 KB) not committed as a fixture.
2. Glasses: killed trace sealed PARTIAL at start. closeReason `stop` (no reason in the frozen set fits a kill; no format change). A tmp-only start.json is written with the first chunk (writer thread, hot path untouched); recover() seals the contiguous good chunks, only if the start record's epoch equals the persisted epoch and the disk cap allows, else deletes. 5 new tests; mutation checks on epoch and cap are caught. Traces killed before this change have no start.json and are still deleted.
Counts after clean: :core:capture:test 352 tests, 0 failures; :glasses:testDebugUnitTest 748 tests, 0 failures.
