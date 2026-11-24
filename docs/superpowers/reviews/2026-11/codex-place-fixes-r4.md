VERDICT: NOT READY

Paths below are relative to `android-companion/`; all line numbers refer to `cdef4c7e`.

1. [P1] services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSamplers.kt:117 - Production calls `history.add(e)` without its guard (also :132); a suspended record can insert a forgotten frame’s row AFTER deletion, timestamped beyond the Delete-all cutoff, and survive retries - Pass and recheck frame liveness, generation and fence authority under the history write lock.

2. [P1] services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceCardHistory.kt:114 - A’s pin-history deletion fails; concurrent B clears the shared `scopeFailed`; A’s retry still fails but `scopePending()` returns false, allowing DONE. DataOpGate explicitly permits concurrent deletes (:11–12), and scopes begin before acquiring the pin lock - Give each operation an immutable scope identity and track its obligations independently through reporting.

3. [P1] services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceCardHistory.kt:134 - Coexisting equivalent `a:T` and `r:0:T` tokens each cover the other; snapshot compaction removes BOTH before executing either. Coexistence is possible after a failed compaction removal; private rows then lose every retry obligation - Select a durable survivor for each equivalent scope before removing covered tokens.

4. [P1] services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceCardHistory.kt:121 - `!coversByTime(other, all)` does not prove the Delete-all token covers `other`: `a:100` drops `r:50:200`, plus all frame/pin tokens. After clock rollback, an existing row at 150 survives successful Delete all while its failed Forget obligation disappears - Require actual scope containment; retain frame/pin obligations until deletion succeeds or coverage is proven.

5. [P1] services/orchestration/src/main/java/com/rokid/context/orchestration/MemoryWipe.kt:101 - The typed result considers only history: failed file deletion increments `leftovers` (:179), or pin settlement remains owed (:205), yet clean history produces DONE and MemorySettings.kt:135 says “Everything was deleted” - Include remaining files, pack cleanup and pin obligations in the cleanup-pending outcome.

| Earlier r3 finding | Status | Trace at cdef4c7e |
|---|---|---|
| 1 Forget reports DONE before pin-history cleanup | STILL OPEN | ForgetActions.kt:304–306 now rechecks after `explicit`, but finding 2 can erase that operation’s failure before reporting. |
| 2 Delete all claims success with history remaining | STILL OPEN | SettingsDeps.kt:155 → `deleteAllOutcome` → MemorySettings.kt:134–135 correctly maps pending in isolation; the shared-scope race can still suppress it. History retry remains independent; :213 clears the wipe marker when other leftovers are zero. |
| 3 Unbounded obligations and unrelated-token failures | STILL OPEN | Arming persists the replacement before compaction and isolated unrelated failures are excluded; findings 2–4 break operation isolation and deletion coverage. |

A failed covering Delete-all retains its own token in the ordinary sequential path; that protection does not resolve findings 1, 3 or 4. Startup time compaction preserves frame/pin tokens, but arming compaction drops them.

No concrete DataOpGate/pin/history lock cycle found; production `pinActive` reads prefs without acquiring the pin mutex.

Static review of commit `cdef4c7e`; checkout HEAD is `b3b487a2`. No files edited; no Gradle, adb or tools scripts run.