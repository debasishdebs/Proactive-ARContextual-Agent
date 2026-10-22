### Task 5: Wire, `listenId`, `planAware`, ack correlation, old-peer fixtures (A6, S24, S74) [CORE, trimmed 1.5 h]
**Stream C · 3 h · depends on: base.**

**Files:** as round 1 Task 4, plus:
- the pinned `+` fields;
- `HudSettings.planAware`;
- frozen fixture JSON under `core/model/src/test/resources/compat/` (old `CommandEnvelope`, old `HudSettings`, an old glasses peer that subscribes to none of the new channels);
- a glasses `onReady` hook that sends an unsolicited caps ack (the plumbing only; the content comes in Task 11).

**Implement:** round 1, plus:
- **Ack correlation** on the phone side: `Hub` passes acks through only when `phoneInstall` matches this install and `sessionStartMs` matches the current session. Others are counted `ackStale`.
- **`MarkMessage` is accepted regardless of `atMs < sessionStartMs`** (occurrence time). Only `v` is gated.

**Tests:**
- round 1's tests;
- `oldGlassesNeverAcksPhoneShowsTooOld` (with the fixture peer);
- `oldPhoneHudSettingsDecodesPlanAwareFalse`;
- `capsAckOnReadyWithoutPlan`;
- `ackFromOtherInstallIgnored`, `ackFromOldSessionIgnored`;
- `queuedMarkOlderThanSessionAccepted`;
- `oldDecoderIgnoresListenId` (frozen copy).

**Commands:** as round 1 Task 4.

**Commit:** `feat(link): additive capture-plan, mark and trace channels, listenId, planAware and ack correlation with frozen old-peer fixtures (protocol v1)`

