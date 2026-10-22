### Task 24: Sweeps, objectives, holdout, Pareto, provenance (A11, S16, S46, S75) [CORE, trimmed 2 h; rest LATER LA6]
**Stream A · 3.5 h · depends on: 21.**

**Implement:** round 1 Task 17, without the calibration import (now Task 25), plus:
- sweepable paths include `policy.<S>.timerPhotoGapsMs.*` (0 or 5–300 s);
- sensing keys are refused (S84);
- `sourcesJson` and `labelRev` are stored;
- **`SweepService`'s constructor has no model dependency.**

**Tests:**
- round 1's (excluding the calibration ones), plus:
  - `sweepGapUpTo300s`, `sweepSensingKeyRefused`;
  - `labelDeleteInvalidatesSweep`;
  - `constructorHasNoModelDependency` (reflection), with the mutation RED "add a `RoleRunner` param" making it fail.

**Commit:** `feat(canvas): RT sweeps (per-state timer photo gaps up to 300 s), objectives, holdout by day, Pareto front with source provenance`

