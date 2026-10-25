# v0.9.5 hardening A review

Reviewer: Codex gpt-6.1-sol (read-only, medium). Scope: recorder thread guard, outbox rows for all deletion paths, mark lineage.
Outcome: NOT READY (2 Important: queued control clocks, retention backlog); fixed in caf31a2d and 47836d32

- **Important — control timestamps shift while queued.** [GlassesRuntime.kt:300](/Users/<user>/Desktop/rokid/pca-v095m/android-companion/glasses/src/main/java/com/rokid/context/glasses/GlassesRuntime.kt:300) now queues `onControl`, which samples arrival clocks when executed. A 20-second recovery backlog shifts Forget’s translated range by 20 seconds, exceeding its 5-second margin and leaving targeted traces undeleted. It also extends collect windows. Capture arrival clocks before enqueueing and preserve them through application.

- **Important — retention creates an unbounded cleanup backlog.** [ForgetService.kt:132](/Users/<user>/Desktop/rokid/pca-v095m/android-companion/services/memory/src/main/java/com/rokid/context/memory/forget/ForgetService.kt:132) now queues glasses frame/range rows for routine photo expiry. Production binds `NotReachableGlasses`, so these rows never drain; distinct frame IDs and ranges defeat deduplication. Continuous capture therefore grows the table indefinitely. Every 15-minute drain loads and sorts the entire backlog despite its attempt limit, and Delete all preserves those pending rows.

No additional Critical/Important early-Main-access or mark-lineage findings.

VERDICT NOT READY