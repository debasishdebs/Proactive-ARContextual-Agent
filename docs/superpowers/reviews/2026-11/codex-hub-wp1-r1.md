Reviewed both commits against spec §§3–4, excluding subsequent working-tree edits. No P1 findings.

- **P2 — Glasses diagnostics add blocking work to the main thread.** [GlassesRuntime.kt:236](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/main/java/com/rokid/context/glasses/GlassesRuntime.kt:236) adds another synchronous exit-history Binder call and potentially another `/proc` read. Both execute through `onProcessStart()` during runtime construction from `MainActivity.onCreate()`. Slow system-service responses can stall startup. Collect the added diagnostics on IO and test that a blocked collector does not block Main.

- **P3 — Unverified sub-cause can disagree with the classification.** [GlassesRuntime.kt:244](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/main/java/com/rokid/context/glasses/GlassesRuntime.kt:244) reads the caller again after classification already inspected it. If the first lookup fails and the second succeeds, the line reports `forcestop_unverified` without any `sub=`. Other transitions can change the reported cause. Derive classification and sub-cause from one caller snapshot; test changing lookup results.

- **P3 — T2’s foreground-refusal logging remains untested.** [ContextHubServiceTest.kt:226](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/test/java/com/rokid/context/phone/hub/ContextHubServiceTest.kt:226) forces `hubNeededNow=false`, so both added tests exit through `not_needed`. Add a case where the hub is needed and foreground entry fails; assert exactly one `foreground_refused` line, correct `sticky=`, `START_NOT_STICKY`, and no hub construction.

No description-text, secret, or address leak found in the added logging paths; no new crash identified. Tests were inspected but could not be run in the read-only environment.

READY