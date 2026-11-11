**NOT READY — 2 P1 findings.** Reviewed `f84ab82e..edb01e10`.

1. **P1 — Pairing-code boot never starts canvas invalidation checks.** [app.js:286](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/phone/src/main/assets/canvas/app.js:286).

   **Repro:** use “Open in browser” with a fresh browser session (`#pair=<code>`), pair, then open an Ask or completed run. Forget or retain-delete its source. Boot returned at line 281 before installing polling and visibility handlers, so forgotten content remains indefinitely. Harness reproduction confirmed **zero generation requests**, including after visibility change.

2. **P1 — An ask deleted before the HUD watcher’s first read remains displayed.** [VoiceGraph.kt:69](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/voice/VoiceGraph.kt:69).

   **Repro:** pause `mirrorTyped()` after its unredacted `byId()` snapshot at line 111; complete Delete all; resume. The watcher first reads `null`, but `seen=false` makes withdrawal false. The cached answer remains in HUD/hub state until its normal hold expires. A targeted reproduction exercised the source-matching prebuilt mapping and pipeline and confirmed this behavior.

3. **P2 — A delayed generation response can discard unsaved editor changes.** [app.js:269](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/phone/src/main/assets/canvas/app.js:269).

   **Repro:** hold a generation response while the editor is clean; make an edit; release a changed generation. The guard was checked only before the request. `route(true)` then discards the view without rechecking or prompting. Reproduced in the harness; classified P2 under your stated P1 definition.

Static tracing supports closure of the original list, photo-preview and reminder repros. No additional concrete P1 was established. The new route retains canvas authentication. JS cleanups passed **370 tests** and **81,000 loop-equivalence comparisons**; budget is **4,701/4,708 lines**. Frozen definitions are unchanged.

No Gradle or device tests run; no files modified.