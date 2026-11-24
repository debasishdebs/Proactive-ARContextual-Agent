VERDICT: NOT READY

Opus reviewer (Codex replacement). This was a static review of `bf733c05` (pin r1 P1 #1-#5 and P3 #6) and `5b7e9946` (place pack) on `feat/v101-ask-eval`. I edited no files and ran no Gradle or adb. Paths are relative to `android-companion/`.

**The five Codex P1s in bf733c05, each traced step by step**
- #1 CLOSED. Photo-scope preview throws → `forgetMoment` → `confirmedPins = emptySet()` → `explicitStore` filters every pair → nothing armed → the sweep clears the link and the pin stays. A successful preview passes `preview.pinIds`. If the preview's pin lookup failed (`pinsUnknown`), it passes an empty set. In every case only pins the dialog named are armed (a), (c).
- #2 CLOSED. The Continue and Delete buttons are disabled until `pinsChecked` is set. A failed lookup becomes `FAILED`, not `NONE`. The wipe receives `pinsGoing.ids` and `beginWipeStore` filters by those ids. A stale lookup from an earlier dialog can land in a reopened one. The ids and names still come from the same `PinsGoing`, so what is shown and what is armed cannot diverge.
- #3 CLOSED. `restoreArming(prev)` runs inside the pin lock (both fresh branches). Because `commit()` updates the in-memory map even when the write fails, memory and disk both hold the older arming afterwards. Lock order is unchanged: DataOpGate, then the pin Mutex, and nothing takes the gate under the pin lock (e).
- #4 CLOSED. Promotion fails → old W is restored → `resumeNotPromoted` → `pinsCommitted = false` → leftovers. `settle(op)` then sees an uncommitted wipe arming that is still pending → `armingOwed` → null → the marker stays and links are kept. A failed deletion after a successful promotion also keeps the marker now.
- #5 CLOSED. `withoutLinks` now returns `[]` only when the stored value is not a JSON array. Such a list already reads as corrupt, so `pins()` returns nothing and no valid pin is dropped. The app has no settings import, so an export of `[]` cannot wipe pins (f).
- #6 is a documentation change only and is accurate.

**5b7e9946 checks that passed**
- The partial backoff runs 5, 10, 20, 40, then 60 min, kept per cell in memory only.
- Costly networks still keep partial cells (`keepWhileCostly`), and offline still returns OFFLINE.
- Delete all and Clear fetched clear the partial streak (`clearFetchedLocked`).
- `SeedResult.complete` has no callers.
- `seedPreset` stops only when cells failed (partial cells do not stop it), so it never loops.
- The 429 detection matches `PlaceHttpException.code`.
- Log lines are literals plus counts and mirror index or class. No pin names, ids or cell keys are logged in either commit.

**Findings**

1. [P1] services/orchestration/src/main/java/com/rokid/context/orchestration/forget/ForgetActions.kt:148 (and :133-134). The multi-select Forget is still not bound to confirmed pins.
   - `forgetSelectionOutcome` calls `run` without `allowedPins`, so it is null and every target pair is armed.
   - Scenario A: in `previewSelection`, `pinsGoing` returns `FAILED` (count 0). The dialog names no pins, the confirm runs, and the pin is deleted unnamed.
   - Scenario B: a pin is linked to a selected Moment between preview and confirm. The `planHash` token does not cover pins, so the pin is deleted unnamed.
   - Names and arming are computed separately (preview versus `selectionTarget` at confirm), so they can diverge.
   - Fix: carry `g.ids` and `failed` in `SelectionPreview`, pass them through `forget(roots, token, pins)`, and use `allowedPins = if (failed) emptySet() else ids`.
2. [P2] services/orchestration/src/main/java/com/rokid/context/work/frames/place/PinMomentLinks.kt:267 with MemoryWipe.kt:188/194. The new `armingOwed` keeps Delete all pending indefinitely when an owed pin deletion can never finish.
   - Trigger: any committed arming whose deletion fails persistently, for example `PlaceGraph.savePins`/`readyPack` throwing or a prefs disk failure. This includes a user Forget's dead set that the wipe carried in.
   - Effect: `wipePending` is never cleared, so every start runs `resumeIfPending` → a full `wipeMemory`. Memories captured after the Delete all are erased again on each start.
   - Fix: count only the pins as a leftover and stop the full resume re-wipe (for example a separate "pins owed" marker retried by `ForgetStartup.settle`), or bound the resume retries.
3. [P2] phone/src/main/java/com/rokid/context/phone/ui/settings/memory/MemorySettings.kt:116,140 and PinMomentLinks.kt:230. Delete all is unavailable while the pin lookup keeps failing.
   - Trigger: at least one linked pin and `photoMomentsOf` throwing (DB unreadable or key unavailable).
   - The button stays disabled. `beginWipe` would also throw in `livePairs` (this was already true before the commit).
   - Fix: on `FAILED`, offer "Delete all, keep pins" with `confirmedPins = emptySet()`, and skip `livePairs`/`alive` when `confirmedPins` is empty.
4. [P3] PinMomentLinks.kt:313. With `reach == null` (Delete all), `going = ids` includes pins whose Moment is already gone. The dialog names them, but `livePairs` never arms them, so they survive as "Needs a Moment". This over-names and is safe.
   - Fix: use `going = live` when `reach == null`.
5. [P3] ForgetActions.kt:60-61. `forgetMomentOutcome`/`forgetMoment` still run with `allowedPins = null`, which means arm everything. They have no production caller today, but a future caller would delete pins unnamed.
   - Fix: pass `allowedPins = emptySet()`, or delete the functions.
6. [P3] MemoryWipe.kt:118. When resume promotion fails but the restored older arming was already committed, `settle` may finish it successfully. `pinsCommitted` is still false, so one extra full resume runs at the next start.
   - Fix: `pinsCommitted = resumePromoted(op) || settle` result non-null with nothing owed.
7. [P3] services/memory/src/main/java/com/rokid/context/memory/place/PlacePack.kt:250-252. A stale partial cell whose refetch fails returns FAILED without bumping `fetchedAt` or the streak. Every glance after 5 min (previously 24 h) re-runs the Wikipedia, Wikidata and Overpass fetch while the sources fail. This only happens on free networks, because the costly guard still holds, and it amplifies 429 rate limits.
   - Fix: on FAILED for a stored PARTIAL cell, `merge` the streak (or stamp a retry time) so failures back off too.
8. [P3] PlacePack.kt:75. `partialStreak` entries are not removed when a cell is evicted. The map is small and in memory only, so this is cosmetic.
   - Fix: remove the cell in `evictToBudget` when it forgets the cell.
