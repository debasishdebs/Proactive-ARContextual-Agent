Found **3 important defects and 1 minor defect; no critical finding**.

1. **Important — staged handovers disable protection for the plan still running.** [PlanController.kt:114](/Users/debasishkanhar/Desktop/rokid/pca-s3/c/android-companion/glasses/src/main/java/com/rokid/context/glasses/capture/plan/PlanController.kt:114) reads `receiver.current`, which already describes the staged replacement. While TRIAL A continues capturing, staging SETTINGS/B makes `planLive()` false and reports SETTINGS/new gen. The supervisor consequently clears A’s ceiling hits. Reproduced against compiled classes; activation-write failures can prolong this beyond the normal 10-second handover.

2. **Important — ceiling hits cross plan generations.** [AutoRevert.kt:12](/Users/debasishkanhar/Desktop/rokid/pca-s3/c/android-companion/glasses/src/main/java/com/rokid/context/glasses/capture/plan/AutoRevert.kt:12) stores timestamps without plan identity; clearing depends on observing `planLive=false` during a supervisor beat. A→B activation between beats preserves A’s hits. Reproduced: two A hits plus one B hit produce `auto:ceiling` against B. SETTINGS hits immediately before TRIAL activation can likewise contaminate it.

3. **Important — actual gate ceiling refusals can go unreported.** [SamplingSession.kt:576](/Users/debasishkanhar/Desktop/rokid/pca-s3/c/android-companion/glasses/src/main/java/com/rokid/context/glasses/capture/session/SamplingSession.kt:576) infers the ceiling from session `keptAt`, populated only after successful encoding; the gate maintains its own keep history earlier. With cap=1, two forced gate keeps whose encoding fails fill the gate’s ceiling while session history stays empty. Subsequent fresh glances receive `CAPPED` without any ceiling callback, so repeated refusals never trigger auto-revert. Additionally, `flushWindow()` refusals at line 464 have no callback.

4. **Minor — effective-wake heartbeat misses WorkRest composition.** [CaptureService.kt:339](/Users/debasishkanhar/Desktop/rokid/pca-s3/c/android-companion/glasses/src/main/java/com/rokid/context/glasses/capture/CaptureService.kt:339) reports the base composed profile without `forRest()`. SETTINGS/T0/MOTION with UNKNOWN context therefore reports `wake=MOTION` during WorkRest, although the session runs SETTLE. `theHeartbeatWakeIsTheEffectiveOne` only compares the helper with that same base profile.

Tests that cannot expose these failures:

- `AutoRevertTest` tests synthetic hits and controller reverts separately; it never exercises generation handovers or the session→supervisor wiring.
- The ceiling session test checks one successful-encoding glance callback, covering neither failed encoding nor window flush.
- `SensingUnchangedTest` never registers sensors: its scripted 50 Hz feed ignores observed rate decisions. Changing actual `SENSOR_DELAY`/registration latency would remain green. The recorder also bypasses `ActiveConfig.profile()`’s forced SETTLE behavior.
- Several `within*` tests compare completed open-to-open gaps without checking the final gap to test end; starvation after an early successful attempt can pass.

Golden resources are unchanged. No new scheduler, walking-veto bypass, intent throttle, or persisted-TRIAL deadline extension found. Read-only review; Gradle was not rerun.