# Task 8 report: force-stop classifier and Diagnostics (S14, S20, user rulings 2 and 5)
- New: glasses ExitClassifier.kt (ExitFacts, CallerInfo, ExitKind, ExitClassifier.classify returns null for non-force-stops and stale exits; ProcCaller reads /proc cmdline + stat start time, untested on JVM, covered by the device checklist), core/model ClosesSummary.kt (shared wording).
- RestartDiagnostics: ctor `lastExit`, `classify`; counts rokid_closes / unknown_closes / other_closes; reason forcestop_rokid | forcestop_unverified | forcestop_other; gap from the exit timestamp when it is in (lastAlive, now]; one count-only line `restart rokid=N unknown=M other=K gap= reason= ui_mode=` (replaces the old restart line; nothing greps the old one in the repo).
- Phone (user ruling 5): Hello gains additive `unverifiedCloses` and `otherCloses` (PROTOCOL_VERSION stays 1); HubState carries them and rokidClosesLine() shows "Rokid closed the app N times (last gap Xs; M unverified; K other force-stops)". Wording uses "unverified" (user ruling 2) not S14's "unattributed".
- Unverified stops are not added to the Rokid count. Only Rokid stops store a gap.
- RED: compile failures in :core:model tests and :glasses tests with the implementation stashed. GREEN: :core:model:test, `*RestartDiagnostics* *ExitClassifier* *GlassesRuntime*`, `:phone *HubTest*`.
- killedWhileRunning is unchanged by the kind (any force-stop while running restarts in icon mode).
- Phone Diagnostics screen reads glassesDiagnostics(), which now includes the new line; no separate UI file edit.

## Fix round 1
M7 dead reason guards removed; M8 KDoc fixed (RestartDiagnostics, HubState). M9: `closes_v2` marker; the first `onProcessStart` of this version removes the legacy `rokid_closes` and `last_gap_sec` once (test `legacyRokidClosesResetOnceOnUpgrade`).
M10 for the Task 10 device checklist (the plan file in the main checkout has uncommitted edits, so recorded here): on the device, Rokid's AssistServer is likely to read as hidden, so Rokid stops will probably show as `unknown=` / "unverified" with `rokid=0`. Do not read `rokid=0` as "Rokid never closed it". An adb force-stop should also read as unverified, not other, unless its pid is readable.
