### Task 30: Glasses tap mark (brief M1, P3, S57, S74) [CORE 2.5 h]
**Stream C · 2.5 h · depends on: 5, 29.**

**Implement:**
- round 1 Task 20, plus:
  - each queued mark stores the glasses' known `deletionCounter`;
  - a new epoch or `installId` clears the queue;
  - queued marks are re-sent on `onReady` whatever their age (≤ 24 h).

**Tests:** round 1's, plus `newEpochClearsQueue` and `oldQueuedMarkResentAfterReconnect`.

**Commit:** `feat(glasses): tap marks a moment (persisted epoch-scoped outbox, pc_mark with listenId and both clocks, cue, undo)`

