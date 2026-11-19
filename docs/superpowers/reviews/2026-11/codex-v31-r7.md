No new P1/P2/P3 findings in `b4841faa..0c56a29c`.

- **Prior P1 (1) fixed:** [Hub.kt:750](android-companion/phone/src/main/java/com/rokid/context/phone/hub/Hub.kt:750) sets `maxShowMs = 0` for every quiet-bounded suggestion without an offset. Both HUD and popup paths suppress zero-lifetime cards.
- **Prior P1 (2) fixed:** [Hub.kt:743](android-companion/phone/src/main/java/com/rokid/context/phone/hub/Hub.kt:743) keys cache validity by card ID/publication time, session number, and `ClockOffset` instance identity (`===`). Reconnect clears the offset; every hello creates a fresh instance and triggers broadcasting.
- **NPE fixed:** [MainActivity.kt:256](android-companion/glasses/src/main/java/com/rokid/context/glasses/MainActivity.kt:256) removes `o!!`; [PopupOverlay.kt:76](android-companion/glasses/src/main/java/com/rokid/context/glasses/overlay/PopupOverlay.kt:76) accepts `Overlay?`. Null produces no popup text.
- **Place cards unaffected:** [Hub.kt:693](android-companion/phone/src/main/java/com/rokid/context/phone/hub/Hub.kt:693) retains their existing construction and default lifetime.
- **Compatibility preserved:** optional fields retain null defaults, unknown fields are ignored, and protocol version remains 1. Older glasses retain their existing timing limitations.
- **Logs:** the changed production logging at [Delivery.kt:365](android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/Delivery.kt:365) contains only fixed diagnostics, without card content.
- **Accepted residual:** the [r7 addendum:170](docs/superpowers/specs/2026-11-18-v3-proactive-loop-design.md:170) plainly states that restart loses suppression after total storage failure.

Static review only; builds and Android tests were not run in the read-only sandbox.

**READY**