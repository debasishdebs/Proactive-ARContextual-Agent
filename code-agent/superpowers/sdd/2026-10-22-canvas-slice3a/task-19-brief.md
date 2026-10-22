### Task 19: Hub trace recorder, Collect for tuning, pulled-trace import (A8 part) [CORE, trimmed 1.5 h; hub recorder LATER LA4]
**Stream A · 2.5 h · depends on: 6, 8, 9.**

**Implement:** round 1 Task 13's recorder, session and import parts. Changed:
- **`CollectSession` publishes `TraceControl`** with the persisted `installId` and `deletionCounter`, and `send = true` (the glasses check their own charging).
- **The hub tap is read-only and changes no hub tick** (S84).
- `tools/trace-pull.sh` is controller-run.

**Tests:** round 1's, plus `controlCarriesPersistedEpoch` and `hubTapDoesNotChangeTickCadence`.

**Commit:** `feat(canvas): hub trace recorder (enum names, speed to 5 km/h), Collect for tuning with the persisted epoch, pulled-trace import`

