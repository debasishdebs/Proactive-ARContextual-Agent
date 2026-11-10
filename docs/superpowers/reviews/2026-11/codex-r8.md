**READY — no new P1 found at `1e774832`.**

- **r7 fixed:** failed persistence retains wake completion in memory; ladder reset cannot discard it. [SerialStateMachine.kt:105](android-companion/phone/src/main/java/com/rokid/context/phone/hub/SerialStateMachine.kt#L105)
- **r6 fixed:** retries write the latest snapshot without replaying user actions; watchdog resets retain the last-start timestamp. [SerialStateMachine.kt:119](android-companion/phone/src/main/java/com/rokid/context/phone/hub/SerialStateMachine.kt#L119), [GlassesWatchdog.kt:254](android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesWatchdog.kt#L254)
- **r5 scenarios pass**, including stop revalidation, probe reset, failed commits, wake timeout recovery, and IO execution.

**Verification:** 110 targeted JVM tests passed using existing compiled classes. No fresh build in this read-only environment; Robolectric tests excluded.

P2/P3: none new identified.