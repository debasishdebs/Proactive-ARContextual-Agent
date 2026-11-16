`24ecdcc5` leaves **two P1s and three P2s**.

1. **P1 — Ordinary AutoStop wakes still bypass the guards.** [AutoStopWake.kt:52](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopWake.kt:52) issues `wake=true, recover=false`. [ContextHubService.kt:324](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/ContextHubService.kt:324) classifies that nonnull intent as an ordinary start, reaching `connectOnce()` without call or quota checks. Both cold and live AutoStop wake callbacks need the guarded classification.

2. **P1 — Watchdog reconnect arms unguarded retries.** [Hub.kt:393](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/Hub.kt:393) guards the initial callback but invokes `connectOnResume()`, which uses `manualConnect(auto=false)`. The JVM policy probe produced `ConnectFailed → ScheduleRetry(2000) → RetryTimer → Connect`. Those subsequent opens consult neither call guards nor quota. A sixth reserved attempt can therefore fail, then retry during a newly started call. Use the automatic connection policy that leaves retries to the relauncher.

3. **P2 — Post-write validation can erase the durable reservation.** [GlassesRelauncher.kt:279](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesRelauncher.kt:279) calls `blocker()`, whose expiry cleanup persists the in-memory attempts before the new reservation has been added there. Holding IO until one old attempt expired reproduced: **one launch, its reservation absent from disk; rebuilding then permitted seven actual attempts within the hour**. Validation must preserve the reservation when pruning.

4. **P2 — Cancellation during persistence skips rollback.** At [GlassesRelauncher.kt:277](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesRelauncher.kt:277), cancellation can be delivered when `withContext` returns, before entering the following cancellation handler. The noncancellable write succeeds but its unused reservation remains. The JVM probe reproduced **six cancelled writes, zero launches, then “hourly limit”**. Cancellation cleanup must surround the write itself.

5. **P2 — BT-paused watchdog ticks consume quota without opening anything.** [Hub.kt:393](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/Hub.kt:393) reserves an attempt for `PAUSED_BT`, but the real `connectOnResume()` rejects that state. With five attempts already used, this no-op consumes the last slot and blocks subsequent OFFLINE recovery. Check connection eligibility before reserving.

The r2 findings stand as follows:

| r2 finding | Result |
|---|---|
| #1: Guard every automatic opening | **Partial** — sticky startup fixed; findings 1–2 remain |
| #2: Revalidate after durable write | **Fixed for the reported call race**; findings 3–4 affect reservation handling |
| #3: One ladder owner, atomic check-and-record | **Fixed** |
| #4: No reopen after failed persistence | **Fixed for a false reservation-write result** |
| #5: Production/persistence coverage | **Partial (P3)** — held-IO and disk-file checks added; production wake/retry and quota edges remain uncovered |

Fresh execution of the existing compiled relauncher/watchdog suites passed **78 tests**. Additional JVM probes reproduced the failures above. No fresh Gradle build or device run was possible in the read-only environment.

I found no concrete production deadlock in the shared ladder and no newly blocked explicit user reconnect path. Existing hub-ladder write-behind behavior, which permits hub starts after save failure, was not classified as a new regression.

NOT READY