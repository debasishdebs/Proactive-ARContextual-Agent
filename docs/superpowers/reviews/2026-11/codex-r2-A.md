**NOT READY** — code-verified findings through PCA `dee7690d` and LiveFit `c006831`:

1. **P2 — Disconnected AutoStop can leave the hub permanently off.** [AutoStopController.kt:78](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopController.kt:78) returns `null`, leaving the loop waiting for a poke. Closing an already-OFFLINE link emits no change, so the wake timer never gets armed. Refused wake attempts have the same scheduling hole.

2. **P2 — An early process restart loses the future wake.** [AutoStopWake.kt:26](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopWake.kt:26) exits before the deadline without scheduling anything. If that WorkManager-started process stays alive, subsequent jobs do not rerun `Application.onCreate`; the hub remains off past its deadline.

3. **P2 — Cold wakes ignore the five-minute probe.** [AutoStopWake.kt:32](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopWake.kt:32) starts without setting the controller’s `wakeProbe`. Away glasses therefore leave the hub running for the full configured N—potentially two hours.

4. **P2 — Retrieval can return forgotten text.** [RetrieveDebug.kt:35](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/RetrieveDebug.kt:35) returns unfenced scored hits. A fact/photo Forget completed between the two `busy()` checks leaves both false and does not change the wipe generation. Deleted IDs—and text with `snippets:true`—can escape.

5. **P2 — Harness scoring treats trusted proposals as needing review.** [quality-eval.py:630](/Users/<user>/Desktop/rokid/pca-v098/tools/quality-eval.py:630) tests string truthiness; production returns `"OK"` or `"NEEDS_REVIEW"`. Both count, incorrectly reducing the Canvas score. Confirmed with an in-memory fixture.

6. **P2 — Harness GETs can delete trace history.** [quality-eval.py:644](/Users/<user>/Desktop/rokid/pca-v098/tools/quality-eval.py:644) calls publish/history/live reads that invoke retention DELETEs through [CanvasBackendImpl.kt:457](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/canvas/api/CanvasBackendImpl.kt:457). Although existing maintenance behavior, this violates the harness’s “never mutates” promise.

7. **P2 — LiveFit cannot recover from off-wrist gating while the watch stays connected.** [ServiceGraph.kt:172](/Users/<user>/Desktop/rokid/lf-remove-pca-push/phone/src/main/java/com/debasish/livefit/phone/ServiceGraph.kt:172) disables worn polling when the gate closes. The watch measures worn state only on request, so putting it back on cannot reopen the hub/listener without reconnecting, opening the app, or starting a workout.

8. **P2 — Partial transport loss erases definite not-worn state.** [CompanionPresenceService.kt:41](/Users/<user>/Desktop/rokid/lf-remove-pca-push/phone/src/main/java/com/debasish/livefit/phone/CompanionPresenceService.kt:41) calls `onConnected` whenever merged presence remains true—even BLE disappearance with BT still connected. This replaces `false` with unknown, grants another 20 minutes, and can restart the unworn hub.

Exemption requests are tap-only; backup exclusions are present. No additional verified auth/Origin/body-limit, query/token leakage, UI-thread DoS, workout-stop, or MediaListener race finding. Static review and in-memory checks; no device testing.