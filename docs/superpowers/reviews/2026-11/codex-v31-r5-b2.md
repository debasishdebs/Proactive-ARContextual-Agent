Three P1 findings:

- **P1 — Minimised glasses pop-ups ignore `maxShowMs`.** [MainActivity.kt:256](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/main/java/com/rokid/context/glasses/MainActivity.kt:256) always uses `PlaceCardInfo.SHOW_MS`. A suggestion received at 21:59:58 remains visible past 22:00 if the phone dies. Zero and negative caps also display for 12 seconds. Apply `limitMs()` here and skip drawing when zero. **Prior phone-death P1 remains open.**

- **P1 — The HUD cap is a stale duration, not a quiet-hours deadline.** [Hub.kt:727](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/Hub.kt:727) reuses the publication-time `quietInMs` without subtracting elapsed time; the glasses start that duration when rendering begins. Example: publication at 21:59:50 produces a seven-second cap; first rendering at 21:59:55 lasts until 22:00:02 if the phone dies. The three-second margin cannot guarantee closure after delayed broadcast, delivery, or rendering. Carry and enforce the actual boundary using the clock-offset mechanism.

- **P1 — Failed dismissal persistence still loses suppression after restart.** [SuggestionStore.kt:216](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/SuggestionStore.kt:216) ignores `markPending()`’s result. When marker, fallback, and ledger retries all fail, only memory retains the dismissal; [Delivery.kt:358](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/Delivery.kt:358) continues withdrawal regardless. Four durable dismissals plus a failed fifth still lose the pause after process death. **Prior durability P1 remains open.**

The original publication boundary race is fixed by the fresh check at [Delivery.kt:207](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/Delivery.kt:207), immediate firing when armed inside quiet hours, and the post-publication withdrawal check.

No additional P2/P3 established. The HUD presenter correctly clamps null/zero/negative/over-12-second caps. Additive fields remain decoder-compatible; `PROTOCOL_VERSION` stays 1 and Room is unchanged. Centre placement is preserved; side geometry fits 240 × 320 dp by inspection. No added content logging or diff-introduced zone, wear, or call regression established.

Static review only; tests were not run in the read-only sandbox.

**NOT READY**