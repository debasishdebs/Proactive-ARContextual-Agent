Three P1 findings remain.

1. **P1 — IMU coverage still accepts missing or stale evidence.** [WearDetector.kt:89](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/main/java/com/rokid/context/glasses/capture/motion/WearDetector.kt:89), [CaptureService.kt:348](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/main/java/com/rokid/context/glasses/capture/CaptureService.kt:348).

   Coverage means only “gyro timestamp changed”; acceleration availability and sample age are unchecked. Reproduced: advancing gyro timestamps **600 seconds behind current time** activate the estimate; fresh gyro with unavailable acceleration also activates it. Once active, reducing coverage to **25% for another ten minutes** leaves it active because `quietLong` is latched. Current wearer motion can therefore go unobserved while capture pauses or AutoStop counts away time.

   **Fix:** require fresh, successfully delivered gyro **and acceleration** evidence, with sample counts and timestamps from the feature snapshot. Enforce coverage continuously and reset the run when coverage becomes inadequate.

2. **P1 — A delayed “check off” message overrides a newer enabled check.** [HudController.kt:254](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/main/java/com/rokid/context/glasses/link/HudController.kt:254).

   `onWearMessage()` checks the message against its own session-start field, but does not reject older updates or compare against the current session. Reproduced: establish a quiet run, apply a newer `wearCheck=true`, then deliver an older `wearCheck=false, worn=true`. The estimate immediately returns, falsely pausing a worn user despite enabled detection. This concerns the wear channel; it does not reopen the excluded HUD-answer finding.

   **Fix:** fence wear updates against session closure and reject obsolete session/update stamps atomically. Give check changes a fresh ordering stamp: [Hub.kt:327](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/Hub.kt:327) currently reuses `lastWornAtMs`, so successive check changes can share a timestamp.

3. **P1 — Clock rollback can strand the phone estimate indefinitely.** [GlassesWearEstimate.kt:56](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesWearEstimate.kt:56), [Hub.kt:335](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/Hub.kt:335).

   Freshness uses wall-clock subtraction, while the expiry job waits a duration. Reproduced: receive `true`, move the phone clock backward one hour, then fire the scheduled three-minute expiry. The estimate remains active, and no replacement timer is scheduled. If Smart stops and its withdrawal is lost, the original stranded-estimate problem returns.

   **Fix:** measure receipt freshness with `elapsedRealtime`; expire using that deadline and rearm if a timer fires early.

4. **P2 — Cold foreground sessions never obtain the current wear check.** [CxrGlassesLink.kt:215](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/glasses-link/src/main/kotlin/com/rokid/context/link/CxrGlassesLink.kt:215).

   `openSession()` reads only the background transport before binding the foreground session. After process restart that transport is unbound, so the read returns null; foreground callbacks never refresh it. The persisted-off safety issue is fixed conservatively, but the fallback remains disabled throughout an ordinary foreground session even when detection is actually off.

   **Fix:** read the setting from the live foreground binding after connection, and refresh it on resume through `WearCheckState.onRead`.

5. **P2 — Glasses regression tests and sensing pins were not updated.** [WearEstimateTest.kt:16](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/test/java/com/rokid/context/glasses/capture/motion/WearEstimateTest.kt:16), [PrivacyInvariantsTest.kt:138](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/test/java/com/rokid/context/phone/canvas/helper/PrivacyInvariantsTest.kt:138).

   Seven wear tests fail: their fixture supplies no IMU coverage, and one still expects unknown-check estimation. The `MotionHintSender` and `WearDetector` SHA pins also mismatch.

   **Fix:** supply explicit sensor coverage, correct the unknown-check assertion, add the failure cases above, and deliberately refresh the two reviewed pins.

Round 1 verification: Smart-stop withdrawal is present; two quiet observations 600 seconds apart no longer satisfy the window; persisted settings no longer authorize estimation; check-only messages now transmit; unknown checks remain conservative.

Room **16**, `PROTOCOL_VERSION` **1**, `TRACE_SCHEMA` **1**, and default-plan hash **`f5782e0d8610`** are unchanged. No additional wire-compatibility break or P3 finding identified.

Validation used existing compiled outputs in this read-only workspace: **25 focused JUnit tests, 18 passed and 7 failed**; all seven wire assertion methods passed. No clean Gradle rebuild or device run.

**NOT READY**