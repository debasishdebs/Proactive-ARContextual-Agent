Reviewed wave B at `6187727`, excluding A3, wave 2, and the earlier review’s findings. **One Important and two Minor additional findings; no confirmed Critical issue.**

1. **Important — An older context callback can undo revocation or session close.**  
   [HudController.kt:109](/Users/debasishkanhar/Desktop/rokid/pca-ctx/int/android-companion/glasses/src/main/java/com/rokid/context/glasses/link/HudController.kt:109) checks ordering under a lock, then invokes `onMotionContext(m)` outside it. An older REST handler can pass the check, stall, and apply after a newer UNKNOWN revoke. The gate then grants REST a fresh 180-second TTL. Session close has the same race, and the handler also accepts messages while the session is closed.

   **Fix:** serialize validation, gate updates, and session reset together; reject messages belonging to a closed session. Add deterministic tests that delay a REST callback until after revocation and disconnect.

2. **Minor — The five-minute stillness window retains samples across long scheduling gaps.**  
   [MotionHintSender.kt:29](/Users/debasishkanhar/Desktop/rokid/pca-ctx/int/android-companion/glasses/src/main/java/com/rokid/context/glasses/capture/motion/MotionHintSender.kt:29) calculates the fraction from the last 300 calls, regardless of elapsed time. After 300 STILL samples followed by ten minutes without calls, the next HEAD_MOVING call sends `stillFrac5m=1`. The phone stamps this as fresh evidence, potentially sustaining an incorrect REST cap. Existing tests always advance in one-second increments.

   **Fix:** expire samples by timestamp, or reset the window after a sampling gap and report `-1` until sufficient recent coverage exists. Test a multi-minute gap explicitly.

3. **Minor — The multi-signer regression test bypasses the production certificate lookup.**  
   [ContextSourcesSettingsTest.kt:115](/Users/debasishkanhar/Desktop/rokid/pca-ctx/int/android-companion/phone/src/test/java/com/rokid/context/phone/ui/settings/capture/ContextSourcesSettingsTest.kt:115) supplies a fake lookup returning `null` and `cannotPin=true`. Removing the production multi-signer guard would leave this test passing.

   **Fix:** retain this UI test, but add tests exercising `PackageLiveFitCertLookup` with single-signer, multi-signer, and signing-history fixtures, asserting the actual digest or refusal.

No concrete exported-provider spoofing/certificate-pin bypass or wave B backup/export leak was identified. Validation was source and diff inspection; no Gradle or device tests ran. The working tree remains clean.