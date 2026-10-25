# Re-check of previously unreviewed canvas pieces

Reviewer: Codex gpt-6.1-sol (read-only, medium). Scope: try-it route, Photo scoping, watch ring and run rows, starters, composite card JS.
Outcome: NOT READY (2 Important: A/B bypass of Photo filters, watch samples persisted); fixed in hardening B (842bc226, 67e7986f)

- **Important — Photo filters bypassed in A/B runs:** [CanvasBackendImpl.kt:655](/Users/<user>/Desktop/rokid/pca-v095m/android-companion/phone/src/main/java/com/rokid/context/phone/canvas/api/CanvasBackendImpl.kt:655) passes the original frame IDs without `scoped()`. Comparing a pipeline configured with `markedOnly=true` or `sinceHours=1` processes unmarked or older selected photos, potentially sending them to its model.

- **Important — Watch samples persist beyond the memory ring:** [CanvasRunner.kt:690](/Users/<user>/Desktop/rokid/pca-v095m/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/CanvasRunner.kt:690) serializes raw timestamp/state/confidence/score into run items and flushes them to Room. Calling `startWatch` then switching the watch source off clears the ring but leaves those samples on disk. Currently latent: no HTTP route or page invokes watch runs.

Static review only; tests were not run.

VERDICT NOT READY