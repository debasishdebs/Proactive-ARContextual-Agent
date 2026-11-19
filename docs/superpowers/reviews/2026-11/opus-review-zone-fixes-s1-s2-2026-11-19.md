# Review: S1 (bde734ce) + S2 (5fbe0462), zone fail-closed fixes

Reviewer: Opus 5.5, an independent read-only reviewer standing in for Codex gpt-6.1-sol (low effort). This was a static review of `feat/v101-ask-eval` @ `a1149084`. I did not run Gradle, use devices or run tests.
Paths are relative to `android-companion/`.

## Checks

1. **Coverage.** The sweep's start gate (`SuspectSweepWorker.kt:121`), the per-photo provenance gate (`:108`/`:163`), the drain's `allowed` (`:131`) and `DispatchFence` (`:130` → `KeyframeExtract.kt:65` → `ZoneFencedLiveness.gone`, inside the permit and checked both before and after `inner.gone`) all use the same `zoneSignal`. Nothing suspends between the dispatch check and the upload. S2: `PlaceSampler.kt:197` runs right after the fix is validated and before the second `stopped()`, the pack/cell fetch and PLACE_MATCH. The later rechecks (the ticket, `zoneBlocked`, `ZoneFencedLiveness`) are unchanged. OK.
2. **Fail closed.** In a cold process, `startupBlocked()` (`AskZoneFence.kt:95-104`) blocks on the persisted protect flag, on any enabled zone, or on an unparseable list (`:114`). An exception blocks (`:103`, `:172`). An `install` failure returns `{ true }` (`SuspectSweepWorker.kt:82`). A `prepare()` throw goes to the worker's catch, which retries and uploads nothing. No zones, or only disabled zones, returns false, and `prepare` then sets `startupClear`, which also clears a stale protect flag. No regression. OK.
3. **Inside a zone and the records absent.** In a cold process this still blocks: the enabled-zone rule does not depend on the records. `blockedForBackground` skips the Ask switch. The controller's `onState` calls `protect()` whatever the switch says (`ZoneGraph.kt:42`), so the two are consistent. OK.
   **Deadlock and practical effect.** `judged` lives only in memory. It becomes true only when the hub (ZoneGraph controller) is built in the same process and has judged. A closed gate returns `failed=false`, so the worker returns `Result.success()` with no retry, and the next run is 24 h later. A zone user therefore gets the sweep only on nights when the charger job finds the app process warm with the hub running and judged clear (outside every zone plus approach band). It never runs while charging at home if home is a zone, which is by design. After a reboot or an OEM process kill, it does not run until the hub is started. Steps 1 and 2 (expired-photo removals and already-replaced removals, with no model call) still run every night. This is not a deadlock. It is a slower drain, and that is acceptable under fail-closed.
4. **S2 geometry.** The check uses the fix's own `lat`/`lon`/`accuracyM` against `zonesForCheck()` (all stored zones; corrupt gives null, which blocks; `blocksPoint` skips disabled ones). The approach band and vague-fix widening come from `ZoneEvaluator.blocksPoint` (unit-tested in `ZoneEvaluatorTest.kt:291-303`). The `STALE_FIX` guard at `:195` already handles NaN accuracy and non-finite coordinates, and `blocksPoint` handles them too. A `zones()` throw gives null, which blocks. Cancellation is rethrown. OK.
5. **Tests.** The six S1 tests assert no model call and a closed gate. They pin the premise that the old fence was open, so they would fail on the old `PlaceGraph.fence::blocked` wiring. The four S2 tests cover inside, the approach band (about 1.45 km away against a band of buffer + 2 km), unreadable and far (30 km), and assert no cell fetch and no match. OK.
6. **Logs and schema.** The logs carry only outcome names and counts. Room, places.db and the protocol are untouched. OK.

## Findings

**P2.** `orchestration/forget/NightIndexWorker.kt:145` drains the same caption queue with no zone check. This predates both commits. Suppose the sweep queued photos (outside a zone), then a zone closed mid-drain (`allowed`/`DispatchFence` stop it). The photos stay queued, and the next NightIndex run uploads them from inside the zone. More broadly, the night job uploads every queued photo whatever the user's location is. If threat model A covers "no upload while inside a zone" for background work, NightIndex needs the same `zoneSignal`. Otherwise, record a ruling that it is out of scope.

**P3.** `AskZoneFence.kt:95-98`: in a warm process where the hub judged clear and then stopped receiving fixes or ticks, `judged` stays true. The clear answer then rests on the controller's last snapshot (`ZoneController.kt:105`; staleness is judged only on `tick()`/`onFix`). I did not verify that something keeps ticking the controller overnight while the hub service is down. If nothing does, a warm-process sweep can trust a stale OUTSIDE. This predates both commits and is the same residual as for Ask.

**P3.** `SuspectSweepTest.kt`: no test drives `AskZoneFence.prepare()`. The missing cases are (a) live zone records with no enabled zone, which should block, and (b) a stale protect flag with no zones, which should open via `startupClear`. Both paths are reached in production from `SuspectSweepWorker.kt:46`.

**P3.** `SuspectSweepWorker.kt:94`: the KDoc still says "production: `PlaceGraph.fence::blocked`". It should name `zoneSignal`.

READY
