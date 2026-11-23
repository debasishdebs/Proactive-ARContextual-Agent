VERDICT: NOT READY

Reviewed HEAD `bb1b7162`, including unit changes since `07e27bcd` (`046bc165`, `295c7892`). Static review only; no files edited, Gradle, adb, or tools scripts run. Findings below remain at HEAD. Paths are relative to `android-companion/`.

1. **[P1] phone/src/main/java/com/rokid/context/phone/ui/memory/MemoryDetailViewModel.kt:115** — A photo has a linked pin → its Forget preview throws → Photo scope deliberately leaves confirmation enabled → confirmation falls through `:141` to `productionMemoryForget.forgetMoment` → `ForgetActions.run` arms current pins without a confirmed-pin restriction and deletes the pin. No dialog named it. **Fix:** require a successful pin preview and pass its confirmed pin pairs through to arming. **affects: cand-4436d1a5 | train7-ccfb0b62**

2. **[P1] phone/src/main/java/com/rokid/context/phone/ui/settings/memory/MemorySettings.kt:107** — Open Delete all → `pinsGoing` initially becomes `NONE` while the lookup runs asynchronously → press Continue and Delete everything before it completes; neither button waits → `MemoryWipe.beginWipe` independently arms every live linked pin. Pins are deleted although neither confirmation named them. A lookup failure also becomes `NONE`. **Fix:** disable confirmation until the lookup succeeds and bind the wipe to the confirmed pin pairs. **affects: cand-4436d1a5 | train7-ccfb0b62**

3. **[P1] services/orchestration/src/main/java/com/rokid/context/work/frames/place/PinMomentLinks.kt:90** — Forget A stores a committed dead set, but its pin deletion remains pending—for example, `PlaceGraph.savePins` throws in `readyPack` before writing preferences → Forget B cannot finish A either → B’s replacement arming write fails → unconditional `store.disarm()` removes A’s recovery record too. Once storage recovers, a sweep merely clears A’s links; the pins previously promised deletion survive. The fresh-wipe branch repeats this at `:218`. **Fix:** preserve or restore the previous committed arming when replacement fails; never unconditionally disarm it. **affects: cand-4436d1a5 | train7-ccfb0b62**

4. **[P1] services/orchestration/src/main/java/com/rokid/context/work/frames/place/PinMomentLinks.kt:213** — Crash after Delete all’s epoch commit, leaving an uncommitted wipe arming → resume’s promotion write fails → the in-memory arming nevertheless becomes committed, while `pinsCommitted` stays `true` on resume (`MemoryWipe.kt:110`) → the pin-list deletion write also fails, updating only memory → `finishQuietly` converts failure to zero, and the sweep sees no in-memory linked pins → `MemoryWipe.kt:194` clears the durable pending marker → crash before the listener at `:195`. On restart, the old on-disk uncommitted arming is dropped because the marker is clear; the original pins survive. **Fix:** propagate promotion/deletion failure to the wipe and retain its pending marker until pin cleanup is durably complete. **affects: cand-4436d1a5 | train7-ccfb0b62**

5. **[P1] services/memory/src/main/java/com/rokid/context/memory/place/UserPlace.kt:189** — Stored pin JSON is malformed but still contains a Moment ID and `at` → Export settings invokes `withoutLinks` → parsing fails and returns the original string → `ConfigExport.kt:78` includes it in the shareable export. Sharing exports the fields explicitly promised to remain excluded. **Fix:** omit unreadable pin data or refuse export instead of returning unsanitized input. **affects: cand-4436d1a5 | train7-ccfb0b62**

6. **[P3] services/orchestration/src/main/java/com/rokid/context/work/frames/place/PinMomentLinks.kt:36** — Start an explicit operation whose target contains no pin pairs → it enters `inflight` but never `armed` → a sweep or settle runs because `busy()` checks only `armed`. This is intentional in H-P2-2’s fix, but contradicts the class specification and invariant 4’s blanket wording. **Fix:** state that the guard protects operations holding an arming. **affects: train7-ccfb0b62**

| id | CONFIRMED-OPEN | FIXED (sha, path:line) | OVERRULED (why) |
|---|---|---|---|
| G-P2-1 | — | `fc3e41a4`, `PinMomentLinks.kt:115,218`; pending wipe pairs carried into a fresh wipe. Separate replacement-failure defect: #3. | — |
| G-P2-2 | — | `fc3e41a4`, `ForgetActions.kt:236`; range arming is inside `DataOpGate`. | — |
| G-P2-3 | — | `fc3e41a4`, `DeletionEpochStore.kt:37`; failed commit restores counter, time and pending value. | — |
| H-P2-1 | — | `c867d709`, `PinMomentLinks.kt:90,115`; user Forget uses `carryWipe=false`. | — |
| H-P2-2 | — | `c867d709`, `PinMomentLinks.kt:67`; busy checks armed operations. Documentation issue: #6. | — |
| J-P2 | — | `295c7892`, `PinMomentLinks.kt:89`; refuses replacement of a pending uncommitted wipe arming. | — |
| audit P3-6 | Rollback to `18c1b76a`: old `UserPlace.kt:103` drops links/times on rewrite; old `ConfigExport.kt:76` exports raw preferences. | — | — |
| audit P3-7 | Existing unlinked pins show “Needs a Moment”: `PlaceGuideSettings.kt:190`. Acceptance remains a ruling. | — | — |
| audit P3-8 | Kept unlinked pins retain `pinnedAt`; clearing it remains a ruling. | KDoc corrected by `af67ff9f`, `UserPlace.kt:29`. | — |

Ancestry checks confirm that **cand lacks `fc3e41a4` and `c867d709`**, while train7 contains both. **Neither build contains J-P2’s `295c7892` fix**, so J-P2 remains open in both staged builds.

| Invariant | Result at HEAD |
|---|---|
| 1. Only a named, confirmed explicit operation deletes pins | **BROKEN (#1, #2)** |
| 2. Armed Moments belong to the confirmed target set | **BROKEN (#1: deletion can proceed without a successful preview)** |
| 3. Committed state stores the exact dead set; deletion uses that set | **HOLDS** |
| 4. Sweeps/settle do nothing throughout every explicit operation | **BROKEN (#6)**; holds while an arming is held |
| 5. New arming never loses an older committed arming | **BROKEN (#3)** |
| 6. Every crash-table state resolves as specified | **BROKEN (#3, #4)** |
| 7. Liveness uses stored photos of the second or its tombstone | **HOLDS** — `MomentDao.kt:102` |
| 8. Range arming is inside `DataOpGate` | **HOLDS** at HEAD/train7; absent in cand |
| 9. Arming failure precedes Forget; cascade failure does not fail committed Forget | **HOLDS** for the checked arming/cascade paths |
| 10. Export strips `moment`, `at`, `armed` | **BROKEN (#5)** |
| 11. No Room schema change | **HOLDS** — added DAO queries, no schema/migration change |
| 12. Pin log messages are literals plus counts | **HOLDS** |

Crash-table rows checked individually:

| Row | Result |
|---|---|
| Mark undo, any point | **HOLDS** |
| Before arming is stored | **HOLDS** |
| Armed, before Forget commits / operation throws | **HOLDS**, except older recovery can already have been lost through #3 |
| Forget committed, before commit write / write fails | **HOLDS** for the stated keep-and-clear-link fallback |
| Commit write done, before pin deletion | **BROKEN (#3)** if a later failed arming discards recovery |
| Pins deleted, before disarm | **HOLDS** |
| Unreadable list / refused save / post-commit exception | **BROKEN (#3)**: later arming can discard the promised retry |
| Delete all before epoch commit | **HOLDS** for the specified no-pending case |
| Epoch committed, before pin commit write / write fails | **BROKEN (#4)** on failed resume promotion/deletion |
| Delete all after pin commit write | **HOLDS** for normal resume |
| User Forget while uncommitted wipe is pending | **HOLDS at HEAD** through `295c7892`; open in both staged builds |