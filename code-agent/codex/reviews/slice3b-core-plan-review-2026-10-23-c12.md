Read both files in full. **0 Critical, 1 Important, 2 Minor.** No files changed.

1. **Important — WP8’s replacement mutation cannot fail the specified test.**  
   In [00-core-plan.md:70](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-23-canvas-slice3b-core/00-core-plan.md:70), passing empty `frameIds` does not bypass the tombstone predicate: [CanvasDao.kt:114](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/memory/src/main/java/com/rokid/context/memory/db/CanvasDao.kt:114) also checks IDs from the result rows. The current runner already passes `emptySet()`. Skipping `HelperFence` capture likewise leaves this existing write fence intact.

   **Exact edit:** replace that second mutation with: “Temporarily bypass the tombstone predicate in `CanvasDao.fenceHolds`, preserving the generation check; `frameRunWriteAfterForgetStoresNothing` must fail when attempting the post-Forget write of a result row carrying the forgotten frame ID. Restore the predicate afterward.” Test pre-load `HelperFence` capture separately if retaining that mutation claim.

2. **Minor — active backlog decision references removed WP8b.**  
   In [3b-LATER-backlog.md:120](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-23-canvas-slice3b-core/3b-LATER-backlog.md:120), **replace `WP8b` with `WP8`** in the list of CORE confirmation owners. Historical “Was in” references can remain.

3. **Minor — stale spike completion time.**  
   In [00-core-plan.md:253](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-23-canvas-slice3b-core/00-core-plan.md:253), **replace “spike A at 3.0 h” with “spike A at B1 exit, 3.25 h.”**

Both reported c11 fixes are confirmed against the repo:

- WP14 matches the real chunk/manifest encoding, raw-payload checksum rules, manifest checksum chain and all 16 tags.
- WP8 now uses `ForgetService.forgetMoment`, which inserts the frame tombstone consulted by the existing run-write fence. The entry-point defect is closed; finding 1 concerns the mutation only.

The remaining verification passes:

- No active stored-trace canvas-input obligation remains. Residual references describe removals or historical dispositions covered by section 24.5; backlog J records the supersession.
- WP3–WP9 compose over the existing photo/frame path, including deletion fences, confirmation, service/routes/session wiring and `phone → orchestration → memory`.
- WP14 has files, tests, local-data rules, integration exclusions and dependencies specified; stream C schedules it at 13.25–16.25 h, with merge held until 24.25 h.
- Arithmetic checks: **14 WPs; 51.5 WP hours; reviews 4.25; fixes 7.5; planned 67.75; reserve 6.75; cap 74.5**.
- CP triggers check: **8.7 / 50.4 / 81.3**, with cap precedence. Burn **61.75**, escalation **>67.925**, remaining **6.0**.
- Dependency path **24.25**, scheduled path **28.25**, wall-clock **41.75**, **18 worktrees**; section 7 merge order matches section 10.

**Safe to start now:** WP1 and WP10 under PF/admission rules; WP14 independently, retaining its scheduled merge hold. WP2–WP7 and WP9–WP13 remain safe after dependencies merge. Correct finding 1 before WP8’s verification session.

VERDICT: NOT READY