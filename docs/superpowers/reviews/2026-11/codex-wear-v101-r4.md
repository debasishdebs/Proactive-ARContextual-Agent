One P1 remains in HEAD `2d78fd26`.

**P1 — Old-session `pc_wear` messages can falsely pause a worn user.** [HudController.kt:263](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/main/java/com/rokid/context/glasses/link/HudController.kt:263) accepts any different session identity and applies both its check and wear value.

Reproduced against compiled code:

- Accept session `200`, seq `20`, check ON, worn true; accumulate 600 fresh quiet seconds with parking disabled; deliver old session `100`, check OFF, worn true. Result: `glassesEstimate=true`, `phoneEstimate=false`, capture blocked as `parked`.
- Deliver an old-session `worn=false` after the current session’s `worn=true`. Capture remains blocked as `not_worn` even after interaction and motion, until another wear update or reset.

The accepted trade-off therefore exceeds “one harmless stale check.” The phone gate in [GlassesWearEstimate.kt:61](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesWearEstimate.kt:61) correctly protects AutoStop while its current check is ON. However, [SamplingSession.kt:523](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/main/java/com/rokid/context/glasses/capture/session/SamplingSession.kt:523) independently pauses on the glasses’ wear value or estimate. Retired-session messages need fencing that preserves same-session silence recovery without relying on wall-clock ordering.

The R3 fixes themselves work: compiled-code probes passed interference from both phone-context and plan handlers, same-session recovery after silence closure, and reconnect with a lower clock stamp. Both new tests pass in the existing HUD report. They cover phone-context interference and disconnect recovery, but omit the plan variant, actual silence recovery, and cross-session replay above.

Validation: **54 focused JUnit tests passed**, seven wire assertion methods passed, and plan-hash/trace checks passed using existing compiled outputs. Room **16**, protocol **1**, trace schema **1**, hash **`f5782e0d8610`**, and optional wire additions remain intact. The known baseline logging failure was ignored. No clean rebuild or device run.

**NOT READY**