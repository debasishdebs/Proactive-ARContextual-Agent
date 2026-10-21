# Task 6 report: WorkRest persistence (S13)
- Files: capture/session/WorkRest.kt, capture/session/WorkRestStore.kt (new), GlassesRuntime.kt (prefs `work_rest`, BOOT_COUNT), WorkRestTest.kt.
- RED: compile failure (store/bootCount/WorkRestSnapshot missing). GREEN: `*WorkRest* *GlassesBackupRules* *SamplingSession* *GlimpseOffset*` BUILD SUCCESSFUL.
- Restore is lazy on first onSampling/onPaused while enabled. Boot count compared when both known, else uptime (savedAt > clock). Saves only when the three fields change. Disabled: store cleared once, never saved.
- GlassesBackupRulesTest does not enumerate prefs names: unchanged.
