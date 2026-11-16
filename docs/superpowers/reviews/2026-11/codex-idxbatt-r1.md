Reviewed all three commits with `git show`. **Two P2 findings; no confirmed P1.**

1. **P2 — Ordinary saves bypass the battery retry delay and can cancel a queued Rebuild.**  
   [EmbeddingWorker.kt:176](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/EmbeddingWorker.kt:176)

   `ExtractFollowUp.afterSave()` calls `enqueue()` after every save. While battery-paused, each call replaces the delayed work with an immediate run, which checks the unchanged battery and defers again. The effective retry frequency follows memory arrivals instead of the intended 15 minutes.

   The flag also remains true after a Rebuild is queued, until an allowed worker reaches line 95. A normal enqueue during that interval can therefore cancel the Rebuild request. A stale read can also apply replacement after the Rebuild starts; WorkManager’s [`REPLACE` cancels unfinished work](https://developer.android.com/reference/kotlin/androidx/work/ExistingWorkPolicy).

   **Fix:** distinguish battery-wait work from indexing work, preferably with a separate unique work name. Keep ordinary save requests coalesced; replace only an identified wait for an explicit resume trigger. The persisted Rebuild token preserves the intent here, so I do **not** classify this as data loss.

2. **P2 — New preference reads can block Main during startup and screen composition.**  
   [EmbeddingWorker.kt:176](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/EmbeddingWorker.kt:176), [SettingsDestinations.kt:157](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/ui/shell/SettingsDestinations.kt:157)

   `ContextApp.onCreate()` calls `EmbeddingWorker.enqueue()` on Main; the new `batteryPaused()` lookup can wait for the initial `index_maintenance` preference load. Separately, Capture settings constructs `IndexBatteryStore` synchronously during composition, and that store is missing from the background warm-up list. Its constructor immediately calls `getInt()`.

   **Fix:** perform the scheduling decision and initial preference loading on IO, and expose initialized in-memory state to the provider and Compose. Background warming alone does not eliminate the first-access race. Android documents [UI blocking and ANR risks from synchronous preference access](https://developer.android.com/reference/android/content/SharedPreferences).

Other checks:

- `537f9859`: the `Column` correctly fixes the Developer card overlap.
- Provider installation is lazy; I found no direct provider-induced crash or deadlock.
- Remote text embeddings, including Ollama, retain their battery-gate bypass.
- Backup exclusions match the preference filename in legacy backup, cloud backup, and device transfer.
- Lowering the threshold does not enqueue work immediately; indexing and the paused banner can wait until the next recheck.

Static review and XML checks completed. Android tests were not run in the read-only workspace.

**READY (no P1)**