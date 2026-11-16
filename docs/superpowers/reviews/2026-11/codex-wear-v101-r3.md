Two P1 findings remain in HEAD `108400c`.

1. **P1 — Other channels bypass wear sequence ordering.** [HudController.kt:262](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/main/java/com/rokid/context/glasses/link/HudController.kt:262).

   `lastWearSeq` resets when `phoneSessionStartMs` changes, but phone-context and plan handlers overwrite that shared identity without fencing (`:232`, `:239`).

   **Reproduced against compiled code:** accept session `200`, seq `20`, check **on**; deliver delayed phone-context session `100`; then deliver session `200`, seq `10`, check **off**. The obsolete update is accepted and immediately reactivates an established quiet estimate. Without the intervening context message, seq `10` is correctly rejected.

   **Fix:** maintain a wear-specific session identity alongside `lastWearSeq` under `ctxLock`, or fence every writer of the shared identity. Add regression tests for both intervening channels.

2. **P1 — Silence recovery locks out subsequent wear updates.** [HudController.kt:354](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/main/java/com/rokid/context/glasses/link/HudController.kt:354), [Hub.kt:495](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/Hub.kt:495).

   After 25 seconds of phone silence, the glasses fence the current phone session as closed. A later state/settings message reopens the HUD and sends Hello. The phone handles that Hello by resending wear with its **existing** session stamp; it does not call `newSession()`. Every subsequent wear update is rejected until an actual transport reopen occurs.

   **Runtime check:** silence closed the session; after simulated same-transport reopening, a higher-sequence wear update produced no callback. A newer transport-session stamp was accepted.

   **Fix:** distinguish local liveness closure from transport-session termination, or negotiate a fresh shared identity during recovery while continuing to reject delayed messages.

3. **P2 — The session fence assumes a monotonic wall clock.** [HudController.kt:261](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/main/java/com/rokid/context/glasses/link/HudController.kt:261), [Hub.kt:409](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/Hub.kt:409).

   A genuine reconnect after phone-clock rollback gets a new `clock()` value below `closedPhoneSessionMs`, locking out wear updates until wall time catches up.

   **Fix:** use an explicitly negotiated session identity or generation that does not depend on wall-clock ordering.

R2 **P1-1 and P1-3 are addressed**: fresh advancing gyro **and** acceleration, sample-age limits, continuous rolling coverage, a read-only acceleration accessor, monotonic phone freshness, and expiry re-arming. Ordinary transport reconnects generate a fresh session stamp; finding 2 concerns recovery without transport reopening.

Validation: **54 focused JUnit tests passed**, seven wire assertion methods passed, the plan-hash golden passed, and all ten sensing pins matched. Room **16**, protocol **1**, trace schema **1**, and hash **`f5782e0d8610`** remain unchanged; `seq` is optional. No clean Gradle rebuild or device run.

The conservative foreground/unknown-check limitation remains accepted. The excluded HUD-answer finding and known baseline logging-test failure were disregarded. No additional P3 findings.

**NOT READY**