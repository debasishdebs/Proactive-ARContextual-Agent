**NOT READY** — reviewed through `3e9febad`, including `63b6585d` hygiene.

Prior findings:

| Item | Verdict | Evidence |
|---|---|---|
| 1. Already-OFFLINE AutoStop never wakes | **FIXED** | `AutoStopController.kt:86` returns and schedules the next rung. |
| 2. Early process restart loses wake | **FIXED** | `AutoStopWake.kt:54` schedules the remaining delay. |
| 3. Cold wake misses five-minute probe | **FIXED** | `AutoStopWake.kt:56` persists the flag; `AutoStopController.kt:77` consumes it. |
| 4. Forgotten retrieval hits escape | **FIXED** | `RetrieveDebug.kt:43` filters through `ForgetFence.liveIds`, then rechecks gate/generation. |
| 5. `"OK"` proposals count as needing review | **FIXED** | `tools/quality-eval.py:641` uses explicit review-state matching. |
| 6. Harness reads delete trace history | **FIXED** | `tools/quality-eval.py:655` skips maintenance reads by default. |
| Opus: watchdog saves after starting | **FIXED** | `GlassesWatchdog.kt:96` saves before starting; `:214` uses `commit()`. |

New findings:

1. **P2 — Worker and controller can claim the same wake concurrently.** [`AutoStopWake.kt:54`](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopWake.kt:54), [`AutoStopController.kt:58`](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopController.kt:58). The worker runs off-main; both paths perform an unsynchronized check/update/start sequence. Both can pass the deadline check, consume two ladder steps, and issue duplicate starts. A delayed loser can then see the service running, treat that as refusal, and clear the successful wake’s probe flag—leaving an away hub running for configured N instead of five minutes. Serialize wake admission and ownership.

2. **P2 — Durable preference writes block the main looper.** [`GlassesWatchdog.kt:214`](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesWatchdog.kt:214), [`AutoStopStore.kt:58`](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopStore.kt:58). Both controllers run on `Dispatchers.Main.immediate` (`HubGraph.kt:132`). The new synchronous commits therefore perform disk waits on main, creating stall/ANR risk under slow I/O. Preserve save-before-start ordering on an IO dispatcher.

3. **P2 — New README gives a false privacy guarantee.** [`README.md:47`](/Users/<user>/Desktop/rokid/pca-v098/README.md:47) says everything stays on-device. Supported remote providers send prompts and optional images over HTTP (`ProviderFactory.kt:23`, `OpenAiCompatProvider.kt:74`). Describe local storage separately from configured remote inference.

Additional accuracy issue: [`pca-v1.0.0-rc1.md:12`](/Users/<user>/Desktop/rokid/pca-v098/code-agent/releases/pca-v1.0.0-rc1.md:12) retains the removed battery-hook limitation; `HubCaptureControl` and the final-fixes-X handoff document its removal. No personal data found in the requested release notes.

No additional verified P1/P2 in fence cost/deadlock, clock guards, active ask/run holds, harness defaults, ignore rules or backup changes. Watch backup exclusions do not alter live Data Layer traffic or existing settings.

In-memory harness checks and backup exclusion source checks passed. Android verification was static; Gradle/device tests were not run in the read-only sandbox.