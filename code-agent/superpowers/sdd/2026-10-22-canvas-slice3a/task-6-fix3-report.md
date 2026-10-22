# Task 6 fix round 3 report (FROZEN by controller ruling after this)
Tests after clean: 265 total, 83 trace, 0 failures. Schema 1; goldens regenerated deliberately: goldenPayload (609 bytes; fl+nowMs, gt+keptAtMs), GOLDEN_SHA, GOLDEN_B64, GOLDEN_TRACE_SHA, goldenManifest (+bindFailures, prevGyroMs, startPhoneAgeMs, endPhoneAgeMs; phone samples now raw atMs + age).

## Changes
- Critical 1: fl = ["fl",tMs,reason,nowMs] (the gate's own flush clock).
- Important 4: snapshot + bindFailures (0..100), prevGyroMs; gt = [..,encoded,keptAtMs|null], keptAtMs set exactly for an encoded keep (session lastKeyframeAt/keptAt/markPose instant).
- Important 6: TraceAssembler keeps running totals, stops inspecting once over the manifest (verify -> totals), pr kept as count+first, quickLook as first+changed flag; test with 4,096 tiny chunks.
- Important 3: manifest startPhoneAgeMs/endPhoneAgeMs; ClockMap: phone wall = atMs + age; age > 120 s -> samples unused (phoneSampleStale, intake fallback); phone-offset drift > 2 s -> phoneJump (no phone time); uncertainty adds phone drift and age.

## Text for the Task 20 spec (replay is APPROXIMATE)
Replay compares `gt` exactly (seq, outcome, reason, encoded, keptAtMs) where inputs are fully recorded (sc.nowMs, sc.cap/hard, fl.nowMs, pf ctxCap/hard, snapshot). Opens are compared by cause and within 250 ms. A trace window is marked INEXACT (an `exact` flag in the replay result model, not on the wire) when it contains a captureNow request (ep open cause now or sc.force), a bind failure/back-off (ep close busy|bind|cold|open_failed, or snapshot bindFailures/bindBackoffUntil), or an episode depending on an async collector callback (ep open cause dwell|onset). Inexact windows are excluded from pass/fail and counted. Implemented as ReplayRule / Exactness.classify / ReplayTally. Not recorded by decision: consumed captureNow requests, collector callback times, transient StateFlow states. SchedulerInputs is an inventory, not a completeness proof.
