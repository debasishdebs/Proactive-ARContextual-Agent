VERDICT: NOT READY

1. **[P1] android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/CanvasRunner.kt:200** — Keep 20 previous runs; correct the phone clock backwards; start another run while an export holds the gate. The new run can finish before prune proceeds. Prune then selects it because `CanvasDao.kt:227` orders solely by `startedAt DESC, runId DESC`, deleting its row, results and scores. A/B arms are vulnerable too. **Fix:** explicitly protect newly registered and active run IDs in the transactional prune selection. **affects: train7-ccfb0b62**, also HEAD; introduced by `ccfb0b62`.

2. **[P1] android-companion/phone/src/main/java/com/rokid/context/phone/canvas/MarkGraph.kt:28** — Open “Forget this mark” while its photo is pending; let the photo arrive, produce memories and enter a cached export; confirm Forget when cached-export deletion fails. `ForgetActions` returns `CACHE_CLEANUP_FAILED`, but `.committed` converts it to `true`. `removeMark` completes and `markOutcome` returns `DONE`, suppressing the cache-left notice while exported text survives. **Fix:** preserve the cleanup outcome through the recorder and return the existing cache-left notice to the phone. **affects: cand-4436d1a5 | train7-ccfb0b62**, also HEAD; pre-existing in the candidate’s equivalent callback.

3. **[P3] android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/forget/ForgetActions.kt:101** — Undo a photo-less mark while a photo upload holds the gate beyond its timeout. `UploadInProgress` subclasses `WriteInProgress`, so `markOutcome` returns `SAVE_IN_PROGRESS`; the phone displays “A save is running” (`MemoryForget.kt:86`) although an upload blocked it. **Fix:** preserve the upload refusal separately or use accurate combined busy copy. **affects: cand-4436d1a5 | train7-ccfb0b62**, also HEAD.

| Follow-up id | Status |
|---|---|
| P side note: MARK_BODY claims no photo | FIXED (`ccfb0b62`, `android-companion/phone/src/main/java/com/rokid/context/phone/ui/memory/MemoryForget.kt:75`) |
| P P2: prune waits under runner lock | FIXED (`ccfb0b62`, `android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/CanvasRunner.kt:197`); replacement has finding #1 |
| P P2: prune log / Canvas 409 omit upload | FIXED (`ccfb0b62`, `CanvasRunner.kt:203`, `android-companion/phone/src/main/java/com/rokid/context/phone/canvas/api/CanvasApi.kt:99`); separate mark message remains wrong (#3) |
| J O6: mark undo de-links pins | FIXED (`fea96193`, `android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/forget/ForgetActions.kt:69`) |

| Invariant at HEAD | Result |
|---|---|
| Glasses, voice and phone undo arm nothing and never authorize pin deletion now or later | HOLDS — empty target creates no arming; subsequent sweeps clear links |
| Run delete and prune take DataOpGate and appear in DeleterGateInventoryTest | HOLDS |
| Prune cannot delete the run being started | BROKEN (finding #1) |
| Prune does not overlap gated export, save or upload | HOLDS — save also rechecks run existence inside its transaction |
| No deadlock between runner lock and gate | HOLDS — prune does not acquire the runner lock; gate holders do not wait for it |
| Every user-facing 409 and dialog text is true | BROKEN (findings #2, #3) |

Reviewed HEAD `a7bb466e`, including the requested post-`07e27bcd` file history and `295c7892` pin-arming change. Ancestry checks confirm neither listed commit is in the candidate; both are in train7. Static review only; no files changed and no Gradle, adb or tools scripts run.