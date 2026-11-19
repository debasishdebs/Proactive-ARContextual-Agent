# Review: derived-signal try-it (v1.2 row 2), commits 5853764f..496a377b

Reviewer: Opus (Codex gpt-6.1-sol replacement), low effort, read-only. No Gradle run, no device.

## Verified OK
- Gating: `SignalTry.handle` checks Developer mode AND `signalTryEnabled` (default false) on every call (403), and again after evaluation. A combine-watch cancels the try in flight. `GET /api/signals` exposes only `tryIt.available`, and the canvas hides Run. `setSignalTry` has no caller in main code, so the feature cannot be reached in production today.
- Read-only: the route is in `READ_ONLY_POSTS` (30 s deadline). It makes no model, hub, plan or network call and has no `store.put*`. The lease state is in memory only, and the config is only read, so configHash is not touched. `SignalTryWritesNothingTest` and `readingWritesNothing` cover this.
- Limits: body up to 64 KB (`ExprTry.MAX_BYTES`), a parse failure goes through `runCatching`, windows are limited to 300/600/1800, `ExprSeries.MAX_SECONDS` 1800, 300 points, 50 Moments. It refuses on thermal SEVERE, when another try is running and within the 2 s gap. `CancellationException` is rethrown in both `SignalTry` and `SignalRoutes`.
- Privacy: the `signaltry` log lines carry only fixed reasons, counts and the exception class name. Error bodies are fixed strings. Zones: production wires `ZoneHistory.UNAVAILABLE`, so every second is UNKNOWN and is excluded before evaluation. The reply is "no zone history" and `momentsIn` is never reached.
- Same path: `ExprSeries` uses `ExprTry.prepare` (one `ExprLowering` and one `ExprRunner`) and `DerivedSignals.validate` (the same texts as Save). There is no forked evaluator.

## Findings

P2-1 (Forget fence gap): `android-companion/.../canvas/signal/SignalTryReader.kt` (in `read`, the `lease.extend(moments.first...)` line). The lease covers the TRACE, the up-to-50 listed photo Moments and their frames. It does not cover the Moments that own the evaluated `moment_sensor` rows. A Forget of a Moment, or of a time range inside the window, that deletes sensor rows but leaves the trace is not detected during the evaluation, so the numbers are still shown. Delete all (generation) and trace Forget are caught. Fix: extend the lease with every distinct `momentId` of `clear` (in chunks), and add a reader test "a Moment forget during the evaluation shows nothing". Production cannot reach this today (no flag UI, zone history UNAVAILABLE), but the spec promises "a Forget ... at any point ends in Forgotten".

P2-2 (spurious refusal at the cap): `android-companion/.../phone/canvas/api/SignalTry.kt` (in `handle`, the `DerivedSignals.validate(name, ...)` line), together with `exprgraph.js:485` (`realBody` sends no name). The name defaults to "tryit". With 8 saved derived signals (`MAX_DERIVED`), every try, including a try of an existing signal being edited, returns the problem "at most 8 derived signals". Fix: send the edited name, or skip the count check on a try.

P3-1 (spec drift): `ExprSeries.kt` (the `ctxSince = 0` start). The spec section 5 says ctxAgeS is UNAVAILABLE before the first context change. The code counts from the window start, and `ExprSeriesTest.ctxAgeCountsFromTheLastContextChangeInTheWindow` asserts the code's behaviour. Align the spec or the code.

P3-2: `SignalTryReader.momentsIn` lists photo Moments of the whole window from any source, not only Moments of the labelled recording (trace). The labelling is slightly misleading.

P3-3: `ExprSeries.run` is CPU-bound and does not suspend, so cancellation or the deadline lands only after it finishes. This is acceptable because the work is bounded (at most 1800 ticks), but it is worth a comment.

P3-4: The tests cover 403/400/409/429/503, forgotten, busy, cancel and zone-held/unknown. They do not cover a Moment or range Forget (see P2-1), a body over 64 KB on this route, or the MAX_DERIVED cap.

## Verdict
No P1. **READY**. Fix P2-1 before any real zone-history source or flag UI turns the feature on.
