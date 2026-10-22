### Task 7: `PlanCompiler` (A4, S8, S27, S68, S78) [CORE, trimmed 2 h]
**Stream B · 3.5 h · depends on: 3, 4.**

**Files:** as round 1 Task 6, with `/api/plans/compile` in `PlanRoutes.kt`, registered in `CanvasApi.kt` (owned here).

**Implement:** round 1, changed as follows:
- the **S78 topology** (a fixed chain, so a branch or reorder → `"unsupported wrapper order: <a> before <b>"`);
- **operators:** `gyroPeak` allows only `>` (`">= is not supported for gyroPeak: onset is strict >"`), and `boutMs` and `boutRad` allow only `>=`;
- **policy rows:** blank cap → null, blank gap → 0;
- **the S68 warning:** a gap below the settings profile has no effect, and the compiler adds the warning `"timer photo gap below the settings profile has no effect"` (a warning, not a problem);
- **output:** `CompileResult(plan, planJson, problems, warnings, staticCost)`. There is **no `clamped` field**.

**Tests:**
- round 1's, plus:
  - `goldenCompilesExactly` (the resource updated to the config/meta layout);
  - `identityGraphEqualsIdentityPlanBitForBit`;
  - `gyroPeakGteRefused`, `boutMsGtRefused`;
  - `thresholdBoundaryExact` (onset 0.6 > vs a 0.6 sample in the replay fixture);
  - `reorderedPolicyBeforeNoveltyRefused`, `branchRefused`, `disconnectedBindingRefused`;
  - `gapBelowProfileWarns`, `blankCapIsNull`.
- **Mutation RED:** fold `>=` into onset → `gyroPeakGteRefused` fails.

**Commands:** as round 1 Task 6.

**Commit:** `feat(ai): PlanCompiler over the fixed wrapper chain (strict operators, per-state timer photo gaps, fusion subset) with the exact identity and §1 goldens`

