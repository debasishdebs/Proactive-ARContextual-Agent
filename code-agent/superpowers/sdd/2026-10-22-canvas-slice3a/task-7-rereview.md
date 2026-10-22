# Task 7 re-review, fix round 1 (dd351976 vs 6010674f)

Reviewer: Opus, read-only. No Gradle, adb, stash or commits. Inputs: task-7-review.md, task-7-fix1-report.md, the full diff and the surrounding PlanCompiler, PlanResolver, PlanValidator and CapturePlan code.

**Verdict: APPROVED.** Both Importants and Minors 3, 4, 5, 6, 8 and 9 are addressed. No Critical or Important defect was introduced. Two test-quality Minors remain, and Minor 7 stays open as ruled.

## Findings verified

### Important 1 (now refused)

- **Second node of a non-threshold chain type.** `topology()` refuses a second node of any non-threshold chain type with "unsupported wrapper order: T before T". The extra nodes are added to `flaggedNodes`, so the unreachable-node text is not reported twice.
- **ctx out-edges.** `signal.in ctx` must now have exactly one target (`targets.size != 1`).
- **Off-chain policy.** A ctx-fed policy that has no chain edge is refused when the chain has other edges.
- **Tests.**
  - `duplicatePolicyRefused` and `policyOffTheChainRefused` both fail on the pre-fix code: the off-chain graph compiled before.
  - The duplicate test asserts the specific text, so other refusals on the same graph cannot mask it.

### Valid graphs are not newly rejected

- The default graph, the §1 graph and the sparse graphs of Minor 7 all have at most one node of each type and one ctx edge to an on-chain policy.
- The off-chain rule only applies when `onChain` is non-empty, so `head` with a dangling `cin -> rate` still compiles. That case belongs to open Minor 7.

### Important 2 (cell semantics)

- An absent cell now takes the identity row's value. An explicit JSON null cap is null (no cap). An explicit null gap is 0.
- An absent or null bool keeps falling back to identity, and the validator still refuses a null bool ("must be set").
- This matches `PlanResolver.resolve`: the merge treats absent as base, and an explicit null `capPerMin` is kept as null. The compiler and the resolver now agree.

### Always emit all four rows

- `rows()` iterates `PlanState.entries`, which is exactly REST, VEHICLE_PASSENGER, VEHICLE_DRIVING and FLIGHT. This equals `PlanValidator.EDITABLE` and the `identity().policy` keys.
- The pre-fix code already behaved this way, so nothing changes for the glasses or for PlanResolver.
- WALKING and UNKNOWN are never emitted, so the "locked" refusal cannot trigger.

### Goldens

- Untouched: the diff changes no resource and no hash literal.
- The §1 golden's partial VEHICLE_PASSENGER row omits its gaps. Under the new rule those take identity gaps, which are 0, the same as the old "blank = 0". The hash `5ca1e1c3c69f` is therefore legitimately stable.
- Identity `f5782e0d8610` is still asserted for both the no-rows graph and the explicit identity-rows graph.

### Minors fixed

- **Minor 3.** The `boutMs` replay at 350 vs 349 ms through the real GlanceDetector, and the `boutRad` replay with exact float steps of 0.125, both pass. The direct assertions on `minBoutMs` and `minBoutRad` would catch a wrong mapping.
- **Minor 4.** The pre-fold text is filtered by an anchored regex. The post-fold check owns the refusal, and both directions are tested. `/validate` itself is unchanged.
- **Minor 5.** The dead branch is removed. Without a head node the release default is 0.3, below the minimum onset of 0.4, so that branch could never fire.
- **Minor 6.**
  - The g2 test now asserts the exact texts.
  - The no-op `let` is removed.
  - The `staticCost` test now asserts the exact value and that the ASYNC half is excluded.
  - The one-leaf test now covers the FLIGHT and VEHICLE rows, the walk and still-cap gaps, and nudges.
- **Minor 8.** The KDoc is reworded.
- **Minor 9.** The ColSpec KDoc is updated and `nullGapCellIsAcceptedAndNullBoolIsNot` is added.

## Remaining findings

- **Critical:** none.
- **Important:** none.
- **Minor A: `ctxMustHaveExactlyOneOutEdge` cannot fail on the old code.**
  - Its graph adds `cin -> cap`, a target that is not a policy. The pre-fix check already refused that.
  - With the duplicate rule in place, a second policy target is refused anyway. That leaves only a duplicate `cin -> rate` edge with a different `when` value for `size != 1` to catch.
  - The check is redundant defence, so this is test hygiene, not a hole. To make the test discriminate, use a duplicate-`when` edge to the same policy, if PipelineValidator allows one.
- **Minor B: `allFourRowsAreAlwaysEmittedFromIdentity` is nearly tautological.**
  - The keys check cannot fail while `rows()` uses `associateWith(PlanState.entries)`.
  - The `json.contains(name)` check is weak.
  - The per-row identity comparisons carry the real value of the test. Acceptable as it is.
- **Minor C: no RED run was recorded.** The implementer wrote the tests and the fix in one pass. The mutation analysis above stands in for the RED runs.
- **Minor 7 (open, as ruled):** sparse graphs compile, and a policy without `signal.in` compiles with `ctxBound = false`. Rule on this when Task 11 defines what `ctxBound = false` does.
