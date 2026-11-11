**NOT READY — 1 remaining P1.**

1. **P1 — Concurrent typed completion can leave a deleted voice answer on the HUD.** [VoiceGraph.kt:71](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/voice/VoiceGraph.kt:71).

   **Repro:** pause voice ask A after its unredacted snapshot at line 56, before returning to Main. Let typed ask B complete `mirrorTyped()`: line 113 overwrites `snapshotAskId=B`, although the pipeline rejects B’s display while Thinking. Complete Delete all, then resume A.

   A’s watcher now receives `existed=false`. Its first `null` read does not withdraw the cached answer, so deleted content remains visible and can be spoken. This leaves the voice variant of r22 incompletely fixed.

   **Evidence:** controlled compiled pipeline/mapper reproduction retained A’s HUD with `speaking=true`. Keeping A’s marker in the control produced Idle, cleared the HUD and stopped speech. Room/Android scheduling was not exercised.

The pairing and delayed-edit repros fail on edb01e10 and pass on cb284323. The isolated HUD deletion repro also closes.

Validation: **372 JS tests**, **56 prebuilt JVM tests** passed; targeted checks found no pre-auth request storm or refresh loop. JS budget **4,705/4,708**; frozen definitions unchanged. No Gradle run or files modified.