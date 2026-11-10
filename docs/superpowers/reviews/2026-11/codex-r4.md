**NOT READY** at `52ad48ae`. Five P2 findings; no P1.

1. **P2 — Stale AutoStop decision.** [AutoStopController.kt:102](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopController.kt:102): foreground, busy, run or glasses-presence inputs can change during the IO commit. The controller then stops unconditionally. Reproduced a stop after foreground return.

2. **P2 — Wake claim overwrites a user start’s probe reset.** [AutoStopWake.kt:90](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopWake.kt:90): concurrent `userStarted()` clears the probe outside `claimLock`; the claimant subsequently restores it, and `ALREADY_RUNNING` preserves it. A user-started hub can consequently stop early.

3. **P2 — Watchdog reset can be lost.** [GlassesWatchdog.kt:113](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesWatchdog.kt:113): delayed `saveStart(b)` can overwrite a newer positive-signal reset. Reproduced divergent persisted/in-memory ladders. Following refusal and process death, recovery can inherit the stale deadline.

4. **P2 — Cold-start disk commits remain on main.** [ContextApp.kt:94](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/ContextApp.kt:94): a due wake synchronously reaches both commits in `claim`. This retains the startup stall/ANR risk; occurring only when due does not resolve it.

5. **P2 — Stale release limitation.** [pca-v0.9.8-beta.md:25](/Users/<user>/Desktop/rokid/pca-v098/code-agent/releases/pca-v0.9.8-beta.md:25): “Home chat has no glasses path” contradicts slice E’s handoff and shipped typed-answer HUD routing.

Worker/controller double-claim protection, refusal classification and unique-work replacement check out; no additional deadlock or lost-wake finding. README privacy wording is corrected. The v0.9.8 correction to **4 P1 / 2 P2** is supported. No personal-data hits in the six release notes.

`VoiceMarksDocTest`: **6/6 passed** via JUnit using existing compiled classes. Fresh Gradle execution was blocked by the read-only cache-lock restriction.