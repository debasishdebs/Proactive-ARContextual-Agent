**Three P1 findings remain.**

1. **Zone checks still allow network/photo dispatch after a suspension.**
   - [PlaceSamplers.kt:42](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSamplers.kt:42) checks the zone **before** `inner.gone()`, which suspends for Room reads. If a zone activates during those reads, liveness returns “live” and the router uploads without another zone check.
   - [PlacePack.kt:69](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/memory/src/main/java/com/rokid/context/memory/place/PlacePack.kt:69) can wait for `fetchLock` and database reads, then start fetching without zone authority. Subsequent Wikipedia/Wikidata/Overpass requests also lack checks. This can disclose the coarse location after protection activates. Fence the actual dispatches after their waits.

2. **Zone-triggered memory rollback is not fail-closed.** [PlaceSampler.kt:154](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSampler.kt:154) commits the memory before rechecking zones. If a zone activated during that commit, cleanup depends on `rollback()`, which silently discards deletion failures and has no retry; cancellation can also interrupt cleanup. The photo-derived memory then remains durable despite the blocked attempt. Validate zone authority within the memory transaction, with rollback before commit, or provide durable cleanup.

3. **Minimized place popups survive zone entry and Forget/Delete all.** [MainActivity.kt:229](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/main/java/com/rokid/context/glasses/MainActivity.kt:229) withdraws an existing popup only for `Overlay.Answer`. The place-zone check only rejects a newly arriving card. Consequently, an already-visible place popup remains until its 12-second timer even after zone settings arrive and the phone sends `overlay=null`, including after deletion. Withdraw place popups on card removal and zone activation.

**A — Zones r3: all four statically fixed**, accounting for their placement in `7969288a`: failed pushes reach the retry controller; undurable missed intervals prevent clear-memo advancement; rollback widening covers memory holds; failed configuration recovery preserves protection.

**B — Place Guide r1 disposition:**

| Finding | Result |
|---|---|
| Zone changes during suspended work | Incomplete — P1 #1 |
| Post-model memory/card race | Incomplete — P1 #2; publication checks improved |
| Delete all versus outstanding fetch response | Fixed by epoch invalidation and fetch lock |
| Stale call-spacing timestamp | Fixed with monotonic spacing from call completion |
| Cards surviving Forget/Delete all | Phone holder fixed; glasses popup incomplete — P1 #3 |

**C — PG3:** No protocol compatibility break found. Old glasses ignore `place` and retain ordinary nudge behavior; old-phone nudges decode with `place=null`; unknown commands become `Malformed` without throwing. Card title, description, and source derive from the pack; the model only selects a candidate.

**P2/P3, briefly:**

- **P2:** Dismissal matches only the title, so a delayed dismissal can clear a newer card with the same title ([Hub.kt:655](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/Hub.kt:655)). Use a card-instance ID and conditional clear.
- **P2:** Minimized popups bypass presenter motion dismissal.
- **P2:** `placeCards.drop(1)` skips an existing card on hub restart, leaving it without a newly scheduled phone expiry.

Static review of both commits and regression tests; tests were not executed in this read-only workspace.

NOT READY