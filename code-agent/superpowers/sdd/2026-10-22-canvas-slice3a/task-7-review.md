# Task 7 review: PlanCompiler (6010674f, base 508de925)

Reviewer: Opus, read-only (no Gradle, no adb, no stash). Read in one pass: the diff package, brief, report, plan (preamble, Round 3, Global Constraints, Tasks 3/4/7/22, S8, S27, S68, S78, S83, S84), spec §5.2 (S8, S27, the compiler paragraph), the Task 3 report and the Task 4 review.

## Spec Compliance

- ❌ One S78 topology hole (Important 1): a `signal.in ctx` that fans out to two `policy.capture_rate` nodes compiles, and `PlanCompiler.kt:160` silently takes the first policy node by id. Everything else checks out: the fixed chain and its reorder, branch and join refusals with the exact text, the strict operators with the exact texts, blank cap = null and blank gap = 0, the S68 warning as a warning, `CompileResult(plan, planJson, problems, warnings, staticCost)` with no `clamped`, the `hub.context` refusal ("hub fusion params arrive in slice 3a-LATER", as Round 3 says), the exact identity golden and the §1 golden.
- ⚠️ Cannot verify from the diff:
  - "round 1's tests": the round 1 Task 6 text is not in the repo (the plan is untracked, with no history). The controller should check the round 1 test list against the 27 tests.
  - The route: no `PlanRoutes.kt` compile handler was written. Round 3 says Task 22 registers "Task 7's compile route", but the merge map says "leaf route files (`PlanRoutes.kt`, …) are written by their tasks". Task 22's own text lists only `TraceRoutes`/`ReplayRoutes` and debug-shadow. Make sure Task 22's brief explicitly owns writing `POST /api/plans/compile`, or it falls between the two tasks.
  - What `ctxBound = false` means on the glasses: there is no consumer yet (Task 11). This matters for Minor 7.

## Checks run (named risk, then what I checked)

- **Identity golden.** I recomputed `f5782e0d8610` in Python, independently of Kotlin. I took the golden config, swapped in the ParamSchema defaults (onset 0.6, settle 1500, window 20000, bigTurn 0.8, bigChange 22, ringCompare all, REST 2/30/60/30/120, PASSENGER cap null) and applied the `CanonicalJson` rules: sorted keys, no whitespace, `BigDecimal.stripTrailingZeros` numbers. The hash matches. It is also pinned independently in Task 4's `PlanJsonTest.kt:79` and `CanonicalJsonParityTest.kt:28`. The test can fail:
  - `PlanCompilerTest.kt:116-126` asserts the hard-coded hash, `config == PlanResolver.identity()`, the canonical config and `encode` equality;
  - it does so for both the no-rows graph and the explicit identity-rows graph.
- **The §1 golden.** In Python:
  - the resource file re-encodes canonically to itself byte for byte, with keys sorted at every level and no trailing newline;
  - the test compares the full `planJson` string against the file, so key order is asserted too;
  - `sha256(canonical(config))[:12]` = `5ca1e1c3c69f`, which equals the stored `configHash`.
- **"One param changes one leaf".** Every changed value differs from its default (for example ringCompare "last" vs "all", minBoutRad 0.3 vs 0.26). The assertion is set equality on the changed leaf paths, so either a no-op or a write to the wrong field fails it (`PlanCompilerTest.kt:138-161`). Gaps:
  - it covers only REST rows;
  - nothing covers FLIGHT/VEHICLE rows, walk or still-cap gaps, or `nudges` (Minor 6).
- **Smuggling sensing params or out-of-bounds values.** There is no path. The plan is built only from the fixed `PlanConfig` fields:
  - capture-half types are `strict`, so unknown keys are refused;
  - `hub` is non-strict, but any param is refused by `HUB_PARAMS`;
  - unknown table columns and rows are refused by `ParamCheck`;
  - `PlanValidator.check` re-runs on the encoded output (`PlanCompiler.kt:167-168`) and refuses with no output.

  I compared `PlanBounds` with the `ParamSchema` bounds: they are identical, and the threshold fold bounds equal the head bounds (0.4–1, 100–800, 0.1–0.8). The release ≤ onset check matches `PlanValidator.kt:108` in Float.
- **Duplicates of non-threshold chain types.** `PipelineValidator` requires exactly one `imu.head_motion` root, and every node must be reachable from it or from a `signal.in` seed. On the root path every chain node has one chain in-edge and one chain out-edge (branch check). The only node type a non-chain edge can reach is policy (through `signal.in`), so policy is the only one that can be duplicated. That gives Important 1.
- **The `ParamCheck.cell` change, other callers.** `allowZero` is set only on the four gap columns (`ParamSchema.kt` `gap()`), and `ParamCheck` is reached only through `PipelineValidator` (the CanvasApi validate route and the compiler). Net effect:
  - a null gap now passes `/validate` too, which is consistent with the compiler;
  - no Task 3 test asserted that a null gap is refused (`NodeTypesV2Test.kt:172-178`);
  - BOOL cells are unaffected (null is still "must be set").
- **The compiler replacing 3 validator texts.** The filtering happens only inside `PlanCompiler.compile`, and `PipelineValidator` itself is unchanged, so `/validate` still reports "op not allowed for feature", the port mismatch and "unreachable". The edge-label filter `"edge $from->$to: "` matches `PipelineValidator.kt:48` (ids ≤ 32 chars, below the 40-char `shown()` cut). In every filtered case a compiler text is still present, so nothing is ever let through by the filter.
- **The S68 warning direction.** `withContext` takes `maxOf(profile, row)`, so a row gap below the SETTLE/T0 profile (10/30/10/60 s, the shortest of all tiers) is moot at every tier. The walk fallback `walkCheckMs ?: settledCheckMs` mirrors `CaptureProfile.kt:32`. Gap 0 is excluded. The warning is one de-duplicated text, and it is never emitted alongside problems.
- **Determinism.** Nodes are sorted by id, and edges by (from, to, when). The validator runs on the sorted copy, `own` is a LinkedHashSet, and threshold conflicts are reported against the lower id. Tested for both plan bytes and problem order (`PlanCompilerTest.kt:353-361`).

## Strengths

- **Defence in depth with no clamping.** The output is built only from the fixed schema (it can never carry a sensing key), it is re-validated by the real `PlanValidator`, and a failure emits nothing.
- **Strict operators are tested against the real `GlanceDetector`** (0.6 vs 0.61, and 0.59 lets 0.6 through), not against the compiler's own fold.
- **The goldens are honest.** The §1 golden is byte-compared, including key order. The identity hash is cross-pinned by three independent tests, and the hashes were reproduced outside Kotlin.
- **The branch rule is compact and correct.** It keeps the nearest-later out-edge and the nearest-earlier in-edge, and covers the fork, the join and the threshold → threshold case.
- **Problem texts are fixed and name only ids, type ids and bounds.** A test checks that titles and the pipeline name are never echoed (`PlanCompilerTest.kt:306-313`).
- **Mutation REDs are recorded for both named mutations.**

## Issues

### Critical (Must Fix)
None.

### Important (Should Fix)

1. **`PlanCompiler.kt:160` (with `:207-211`): a duplicate policy node fed only by `ctx` compiles, and the wired one can be silently ignored.**
   - Repro: the default graph, plus a node `a_rate` of type `policy.capture_rate` and an edge `cin -> a_rate`.
   - Why it compiles:
     - the ctx check passes, because all targets are policy nodes;
     - `a_rate` is reachable from the `signal.in` seed;
     - it has no chain edges, so the order and branch checks never see it;
     - `policy` is `mixedIn`, so no port-type rule fires;
     - no validator rule requires an outgoing edge.
   - What the plan gets: `chain.firstOrNull { POLICY }` picks `a_rate` (it sorts first), so the rows of the policy node that is actually on the chain are dropped without a problem.
   - Why it matters: this is an S78 branch (`signal.in` fanning out), and the user-visible result is that the edits made to the wired node do nothing.
   - Fix:
     - refuse a second node of any non-threshold chain type (a fixed text such as `"unsupported wrapper order: policy.capture_rate before policy.capture_rate"`, or "only one <type>");
     - and/or require `signal.in ctx` to have exactly one out-edge;
     - add a `duplicatePolicyRefused` test.
   - Related: a policy node fed by `ctx` but not on the chain (for example `novel -> cap` with `cin -> rate` dangling) also compiles, and its rows apply. Decide whether an off-chain policy is allowed, or whether it must have a chain edge when the chain has other nodes.

2. **`PlanCompiler.kt:219-235`: in a present row, an absent cell means "blank" (cap null, gap 0), while an absent node or row means "identity".** This is the question flagged by the implementer, and my judgement is that it is a footgun. It contradicts the precedent in the same plan: `PlanResolver.resolve` treats absent as "from base", and an explicit null as "unchanged".
   - `{"REST":{"capPerMin":1}}` (tested as the intended behaviour at `PlanCompilerTest.kt:333-334`) drops REST's 30/60/30/120 s gaps to inherit. At the SETTLE/T0 profile that is 10/30/10/60 s, so up to 3x more timer photo attempts at rest, with no warning.
   - `{"REST":{"settledGapS":60}}` drops REST's cap from 2 to null (no hard flag), loosening REST further.
   - `forceSettle` and `nudges` in the same row do fall back to identity (`:225`), so a single row mixes both rules.
   - This is not an invariant breach (it stays within the hard caps and the reserved attempt), but it silently moves battery the wrong way when a user edits one cell, which is the common edit.
   - Recommended rule:
     - **an absent cell takes the identity row's value;**
     - **only an explicit JSON `null` is "blank"** (cap null, gap 0);
     - the editor (Task 35) sends an explicit null for a cleared cell.

     This makes "missing = identity" uniform at the node, row and cell level, matches `PlanResolver.resolve`, and keeps S68's "blank cap → null, blank gap → 0" for real blanks.
   - Tests: update `blankCapIsNull` (`:317-326`) and `blankGapIsZero` (`:328-335`) so that absent = identity and null = blank. Add `partialRowKeepsIdentityCells`.
   - If the controller prefers the current rule, at least emit a warning when a present row drops an identity cap or gap.

### Minor (Nice to Have)

3. **S78's "exact-boundary tests cover every feature" is met only for `gyroPeak`.** `thresholdBoundaryExact` (`PlanCompilerTest.kt:175-194`) only asserts the mapping for `boutMs` (300 → 300), and does not test `boutRad` at all. Add detector replays:
   - a bout of exactly `minBoutMs` counts, and one ms less does not;
   - the same for `minBoutRad`.
4. **The pre-fold release check gives a false refusal.** `ParamSchema.kt:168-170` checks the head's `releaseRadS` against the head onset (default 0.6) before the fold, so head release 0.7 with a gyroPeak threshold at 0.8 is refused with "releaseRadS must not exceed onsetRadS", although the folded plan (onset 0.8) is valid. The compiler's post-fold check (`PlanCompiler.kt:155-158`) already covers this case; drop or filter the pre-fold text when a gyroPeak threshold is present.
5. **`PlanCompiler.kt:157` has a dead branch.** The fallback text with the literal `"head"` cannot be reached: without a head node the release is 0.3, below any onset ≥ 0.4, and with a head node and no threshold `ParamCheck` refuses first. Simplify it.
6. **Some tests are weak.**
   - `asyncNodeInTheRtHalfRefused` g2 asserts only `isNotEmpty` (`PlanCompilerTest.kt:272`).
   - `conflictingThresholdsRefused` has a no-op `.let { it.copy(nodes = it.nodes) }` (`:202`).
   - `staticCostIsTheSumOverTheCaptureHalf` asserts only `rtOps == 1` (`:363-367`).
   - The one-leaf test covers no FLIGHT or VEHICLE rows, walk or still-cap gaps, or `nudges`.
7. **Very sparse graphs compile.**
   - A graph with only `imu.head_motion` (no `capture.image`, no policy, no `signal.in`) compiles to identity with `ctxBound = false` (`optionalNodesMayBeLeftOut`). S78 says optional nodes may be left out but never says which, and `capture.image` is the sink.
   - A policy with rows but no `signal.in` at all compiles with `ctxBound = false`, while a `signal.in` with no edge is refused. That is inconsistent.

   The controller should rule on both, at least once Task 11 defines what `ctxBound = false` does.
8. **The KDoc overstates privacy.** `PlanCompiler.kt:42` says "no pipeline, node or model text is echoed", but the validator texts it forwards echo unknown param, row and column keys (truncated to 40 chars) through `ParamCheck.shown()` (Task 3 behaviour). Titles and names are not echoed (tested). Reword the KDoc.
9. **The `ParamSchema.kt:14` ColSpec KDoc was not updated:** `allowZero` cells now also accept null. There is also no `ParamCheck`-level test for a null gap; it is covered only through the compiler.

## Judgement on the implementer's flagged semantics

- **Missing nodes take identity values:** consistent with S27 ("from the graph, or else from the settings and defaults") and S78 ("optional nodes may be left out"). Agreed.
- **Threshold vs explicit head param must agree, and two thresholds on one feature must agree:** sound. Equality of doubles parsed from JSON is safe. Refusal beats precedence guessing.
- **Release above the folded onset:** correct and tested. See Minor 4 for the pre-fold false refusal.
- **The `hub.context` refusal:** matches Round 3.
- **No route:** acceptable per Round 3, subject to the ⚠️ about who writes the handler.
- **The S68 warning against a fixed SETTLE/T0 profile:** conservative and correct (a gap below T0 is moot at every tier).

## Assessment

**Spec compliant:** No. One S78 hole: the duplicate or off-chain policy node fed by `ctx` (Important 1).

**Task quality:** Needs fixes (Important 1 and 2).

**Reasoning:** The compiler is well defended. The plan is built from the fixed schema and re-validated, both goldens were independently reproduced, and the operator and topology refusals are tested against real code. But a ctx fan-out lets a second policy node silently override the wired one, and the cell-level "absent = blank" rule silently drops REST's identity cap and gaps when a user edits a single cell.
