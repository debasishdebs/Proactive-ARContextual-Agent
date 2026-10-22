### Task 26: Publish state machine and recovery table (S25, S62) [CORE, trimmed 2 h; extras LATER LA8]
**Stream B · 3.5 h · depends on: 5, 7, 8, 13. It lands after M1.**

**Implement:**
- round 1 Task 18, with `canvas_group_state` as the persisted transition table:

  | From | Event | To |
  |---|---|---|
  | DESIRED | validated | PREPARED |
  | PREPARED | sent | SENT |
  | SENT | matching EFFECTIVE | EFFECTIVE (then activate the phone halves) |
  | SENT | REJECTED | FAILED |
  | SENT | 10 min | UNKNOWN_REMOTE |
  | UNKNOWN_REMOTE | a later matching EFFECTIVE | EFFECTIVE |
  | UNKNOWN_REMOTE | the user or the next publish | compensate: a new gen with the previous desired state |
  | any | REVERTED(gen) | REVERTED, **terminal** |

- **Matching** needs `gen`, `publishId`, `mode`, `configHash` and `phoneInstall`.
- An EFFECTIVE for a superseded or terminal gen → ignored and counted.

**Tests:**
- round 1's, plus:
  - `lostAckBecomesUnknownRemote`, `lateEffectiveAfterTimeoutActivates`;
  - `staleEffectiveAfterRevertIgnored`;
  - `supersededPublishIgnoresOldAck`;
  - `crashAfterPersistBeforeSend`, `crashAfterSendBeforeAck`, `crashAfterAckBeforeActivate` (each recovers on restart);
  - `compensationUsesHigherGen`.

**Commit:** `feat(canvas): capture-plan publish groups with a persisted transition and recovery table (unknown-remote, compensation, terminal revert)`

