### Task 4: `CapturePlan` config/meta, bounds, validator, resolver, pure `PlanReceiver` (A3, S59, S60, S68, S83, S84, P1) [CORE 3.5 h]
**Stream A · 4 h · depends on: 2.**

**Files:** `core/capture/src/main/kotlin/com/rokid/context/capture/plan/{CapturePlan,PlanJson,CanonicalJson,PlanBounds,PlanValidator,PlanResolver,PlanReceiver}.kt` and their tests; `services/ai/src/test/.../CanonicalJsonParityTest.kt`.

**Implement:**
- **The pinned types.** `configHash` = 12 hex of SHA-256 over `CanonicalJson.encode(config)`. `PlanJson.decodeStrict` rejects unknown keys and **non-finite numbers**.
- **The UTF-8 byte length** of `encode()` must be ≤ 8,192.
- **`PlanBounds`:**
  - the §8 glance and gate bounds (as in round 1);
  - row `capPerMin` null or 1–4;
  - **each timer photo gap 0 or 5,000–300,000** (S83);
  - `phoneFusion` keys on the allowlist, each within ±50 % of the default; `fastKmh` 20–40.
- **`PlanValidator.problems(raw: JsonObject, p: CapturePlan?, bytes: Int)`**, with exact texts:
  - `"plan is <n> bytes (max 8192)"`;
  - `"<field> out of bounds <lo>–<hi>"`;
  - `"policy row <S>: capPerMin must be 1–4 or unchanged"`;
  - `"policy row <S>: timer photo gaps must be 0 (inherit) or 5–300 s"`;
  - `"VEHICLE_DRIVING nudges are always off"`;
  - `"policy row <S> is locked"` (WALKING, UNKNOWN);
  - `"pause is not allowed"`;
  - `"MOTION wake is settings-only"`;
  - `"fusion.<k> is not tunable"`;
  - **`"<k>: sensing and detection are not plan parameters"`** for any key under any block named `sampleRate*`, `sensor*`, `imu*Hz`, `tick*`, `fusionCadence*`, `evaluate*`, `detector*` or `classifier*` (S84);
  - `"plan schema <n> is not supported"`;
  - `"<field> is not a finite number"`.
- **`PlanResolver` (S27, S68, S83):**
  - `resolve(partial, base)` fills from `base`;
  - `identity(base)`: the rows equal `ContextPolicies.DEFAULT` exactly (REST cap 2 with gaps 30/60/30/120 s and `forceSettle`; FLIGHT cap 1 with 120/300/120/300 s; PASSENGER and DRIVING cap null with gaps 0, DRIVING `nudges = false`), and the glance and gate values equal today's defaults. **No clamping.**
  - `toContextPolicy(row)` maps back to `ContextCapturePolicy` exactly.
- **`PlanReceiver`** implements the pinned API:
  - `gen < appliedGen` → `stale`; `gen == appliedGen` → re-ack without re-applying;
  - a terminal (REVERTED) gen is never re-applied;
  - mode-specific required fields: TRIAL needs `trialMaxMs` in 1..3,600,000, else `bounds`; LIVE, TRIAL and SHADOW need `planJson` and `configHash`;
  - validates, then persists with `commit`, staged;
  - `activated(gen)` acks EFFECTIVE;
  - `tick`: TRIAL elapsed ≥ `trialMaxMs` → revert `auto:trial_end`; `bootId` changed during a TRIAL → revert `auto:reboot`;
  - a duplicate never resets `trialStartElapsed`.

**Tests:**
- `PlanValidatorTest`: round 1's refusal list with the new texts, plus:
  - `acceptsRestGap300s`, `refusesGap301s`, `refusesGap4s`, `acceptsGapZeroInherit`, `acceptsNullCap`;
  - `refusesSensingKey`, `refusesNaN`;
  - `utf8BoundaryMultibyteName` (8,192 accepted, 8,193 refused when a multi-byte character crosses the boundary).
- `PlanJsonTest`: `configHashIgnoresMeta`, `configHashStableAcrossKeyOrder`, `strictDecodeRejectsUnknownKey`, `roundTripExact`.
- `PlanResolverTest`:
  - **`identityEqualsDefaultPoliciesExactly`** (every row field, including REST 120 s and FLIGHT 300 s);
  - `identityVehicleRowsUnchangedNoHardFlag`;
  - `toContextPolicyRoundTrip`.
- `PlanReceiverTest`:
  - `staleRejected`, `duplicateReackedNotReapplied`, `terminalGenNeverReapplied`;
  - `trialNeedsMaxMs`, `trialMaxOver60MinRejected`;
  - `trialEndsOnElapsedNotWallClock`, `wallClockRollbackDoesNotExtend`, `duplicateDoesNotExtendTrial`;
  - `rebootDuringTrialReverts`;
  - `stagedNotEffectiveUntilActivated`.
- `CanonicalJsonParityTest`.
- **Mutation REDs:** remove the WALKING-row refusal → `refusesWalkingRow` fails; let a duplicate reset the trial start → `duplicateDoesNotExtendTrial` fails.

**Commands:** `gw.sh :core:capture:test`, `gw.sh :services:ai:testDebugUnitTest --tests '*CanonicalJsonParityTest'`.

**Commit:** `feat(capture): CapturePlan config/meta with configHash, 8 KB UTF-8 cap, per-state timer photo gaps 5–300 s, sensing-key refusal, exact identity plan, pure PlanReceiver with local TRIAL enforcement`

