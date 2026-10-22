
## Integration merge (s3a/int)
- s3a/a f3808db5, s3a/b dd351976, s3a/c 95e49962 merged with real merge commits; tip 5b28975c.
- Conflicts (s3a/c merge) resolved keeping both sides: PlanReceiver, HudController, PlanReceiverTest, GlassesBackupRulesTest.
- Tests: core:capture 283, ai 535, orchestration 1033, glasses-link 137, glasses 651, all pass. phone 1433 run, 1 flaky (LocalModelsPageTest.rollBackHiddenAfterGraceAndDialogCarriesTheCounts, passes alone). glasses and phone assembleDebug OK (llama.cpp sources borrowed temporarily from the main checkout).

## Round 2 (int)
Merged a(6c825388), b(a3ba69c5), c(ac4e00ab) in order. Conflict: MotionClassifier.kt (a: restoreForReplay, c: poseSnapshot/prevGyroMs) kept both. Privacy fix: MemoryWipe clears canvas_plan_state + canvas_collect prefs (test in TraceImportDeleteAllTest). Gates green; flaky PromptEditorTest.savingCreatesVersionTwoThenOverwritesWhenVersioningIsOff (passes alone). llama.cpp submodule initialised via git submodule update --init.

## Round 3 (2026-10-22)
- merged s3a/a ea77f126 -> fef818c2; s3a/c 4a877ced -> 2497d739 (conflict CaptureSimulator.ReplayCandidate: kept a's table/ctxBound, added c's forceSettle); s3a/b c952c95c -> 5dac463e. s3a/int tip 5dac463e. s3a/d not merged.
- tests (clean, --no-daemon, workers 2): core:capture 345, orchestration 1095, ai 535, glasses 706, glasses-link 137, phone 1461; 0 failures, 0 flaky. assembleDebug glasses+phone OK.

## Round 4 (final)
- s3a/int merges: s3a/b, s3a/a (82a76660), s3a/d (31e60f0f; conflicts in CanvasSlice3Dao, HubGraph, CanvasExtras resolved keeping both sides: putMeta+setMeta/forget ranges, Task33 PlanGraph + S57 wipe resume, StoredReplayTraces with epoch + shared jobGate), s3a/c (already contained), ctx/int (d1689a4e: DiagFile MAX_BYTES 3_000_000, battery-run NOCPU).
- Gate: full clean test, all green except phone PromptEditorTest.savingCreatesVersionTwoThenOverwritesWhenVersioningIsOff (1 of 1522; passed alone: flaky). APKs built.

## Round 5 (final)
- Merged s3a/b (9f478c5e), s3a/a (889d1c51), s3a/c (773f4f0b); s3a/int tip 773f4f0b. No conflicts.
- DiagLog MAX_BYTES=3_000_000L confirmed; battery-run.sh NOCPU present.
- clean test + assembleDebug (phone, glasses): all modules green except phone 1 failure (LocalModelsPageTest.rollBackHiddenAfterGraceAndDialogCarriesTheCounts), passed on solo rerun (known flake). SqlCipher migration tests in :services:memory pass.
- Counts: phone 1548, glasses 753, orchestration 2380, ai 1078, memory 906, voice 392, capture 352, glasses-link 276, connectors 160, model 160, hud 81, ui 26.
- APKs: phone-debug.apk 250 MB, glasses-debug.apk 58 MB. Not installed.
- s3a/a worktree has uncommitted tools/m1-run.sh and tools/trace-pull.sh (left untouched).
