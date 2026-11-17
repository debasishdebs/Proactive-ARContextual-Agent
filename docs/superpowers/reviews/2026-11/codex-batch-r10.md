Two P1s remain in **A**:

- **Zone entry still has a fail-open window.** [ZoneController.kt:124](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/zone/ZoneController.kt:124) synchronously commits preferences before calling `onState`. During that disk wait, Ask’s separate fence remains open and the controller snapshot still reports the previous outside state. Provider dispatch and answer delivery can proceed. Close/cancel Ask’s fence before persistence.

- **A stale startup read can lock Ask outside zones.** [AskZoneFence.kt:109](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/AskZoneFence.kt:109) can resume after `protect(false, true)` and re-hold the fence using an outdated pause-record result. Subsequent `prepare()` calls skip reconciliation because `judged` is true. With no enabled zones, location supervision stops, potentially leaving Ask blocked indefinitely. Apply startup results atomically against controller state.

Otherwise, per-dispatch tickets, delivery rechecks, and recovery force-stop/backup/preservation changes appear closed.

**B:** All three reported Forget P1s appear closed, including FTS and remote Moment/Scene residue. OFF guards are present; no additional qualifying P1 found.

Static review only; tests were not run in the read-only workspace.

NOT READY