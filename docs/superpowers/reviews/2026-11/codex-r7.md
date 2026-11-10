**NOT READY** at `5067722e`: one new P1.

**P1 — Retry suppression loses wake completion, stopping live capture.** [SerialStateMachine.kt:97](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/hub/SerialStateMachine.kt:97). Reproduced: `WakeResult` save fails → `HubForeground` does nothing because `wakeStartedAtMs == 0` → connected/worn evaluation successfully resets the ladder → the new sequence check discards the wake-result retry. `autoStoppedAtMs` remains set through subsequent evaluations, making `hubNeeded=false`; the watchdog stops capture. Reproduction: **1 watchdog stop**, versus **0** with the new retry gate bypassed.

Both prior findings are fixed in their reported scenarios: pending intent folding at [AutoStopMachine.kt:101](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopMachine.kt:101), interval retention at [GlassesWatchdog.kt:266](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesWatchdog.kt:266).

**Verification:** 108 targeted JVM tests passed, including all five `AutoStopReview6Test` cases, using existing compiled classes. No fresh build in this read-only environment.

P2/P3: none identified.