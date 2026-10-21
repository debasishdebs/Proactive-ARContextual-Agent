# Task 8 report: force-stop classifier and Diagnostics (S14, S20, user rulings 2 and 5)
- New: glasses ExitClassifier.kt (ExitFacts, CallerInfo, ExitKind, ExitClassifier.classify returns null for non-force-stops and stale exits; ProcCaller reads /proc cmdline + stat start time, untested on JVM, covered by the device checklist), core/model ClosesSummary.kt (shared wording).
- RestartDiagnostics: ctor `lastExit`, `classify`; counts rokid_closes / unknown_closes / other_closes; reason forcestop_rokid | forcestop_unverified | forcestop_other; gap from the exit timestamp when it is in (lastAlive, now]; one count-only line `restart rokid=N unknown=M other=K gap= reason= ui_mode=` (replaces the old restart line; nothing greps the old one in the repo).
- Phone (user ruling 5): Hello gains additive `unverifiedCloses` and `otherCloses` (PROTOCOL_VERSION stays 1); HubState carries them and rokidClosesLine() shows "Rokid closed the app N times (last gap Xs; M unverified; K other force-stops)". Wording uses "unverified" (user ruling 2) not S14's "unattributed".
- Unverified stops are not added to the Rokid count. Only Rokid stops store a gap.
- RED: compile failures in :core:model tests and :glasses tests with the implementation stashed. GREEN: :core:model:test, `*RestartDiagnostics* *ExitClassifier* *GlassesRuntime*`, `:phone *HubTest*`.
- killedWhileRunning is unchanged by the kind (any force-stop while running restarts in icon mode).
- Phone Diagnostics screen reads glassesDiagnostics(), which now includes the new line; no separate UI file edit.
