**Design proposal: adopt a restricted CEL frontend and compile expressions into a small, versioned on-device AST.**
Reuse CEL’s parser and type checker; keep the deployed evaluator specific to the canvas’s bounded operations.
This avoids inventing expression syntax while preserving explicit control over memory, battery cost and JS/Kotlin agreement.

This is a design proposal only; no files were edited.
It follows the [slice 3 r3 spec](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/specs/2026-10-21-canvas-slice3-e2e-configurator-design.md:314) and [slice 2’s canvas constraints](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/specs/2026-10-21-canvas-slice2-design.md:64).
Expression implementation remains in 3c, after the language discussion and the required week of use.

**The important boundary is where an expression may affect capture.**
Keep the existing two deployed roles: a motion-only Boolean guard, and a numeric value bound to one editable policy field.
Intermediate computations are allowed in the editor, then inlined into those programs.
They do not create another scheduler, mutable state store or unrestricted signal channel.

The requested example becomes `headSpeed > 0.8 && ctx in [WALKING, UNKNOWN]`.
It is valid as a computation to inspect, but invalid as a capture guard because it reads context.
The inspector should explain that restriction directly.
Using context to drop glance candidates would require an explicit change to S5, beyond adopting a language.

**Library selection should distinguish authoring support from deployed execution.**
These are real candidates, with different integration costs:

| Candidate | JVM/Android and JS availability | Licence and footprint considerations | Assessment |
|---|---|---|---|
| CEL | Official Java implementation, an Android lite runtime, and Buf’s ECMAScript implementation | Apache-2.0; Java compiler has dependencies; JS package includes CEL definitions, RE2 and a Protobuf peer dependency | Best syntax and checking foundation |
| JsonLogic | `json-logic-java` and `json-logic-js` | MIT; JS has no production dependencies; Java implementation uses Gson | Attractive JSON representation, weaker fit for typed infix authoring |
| Apache JEXL | JVM engine; similarly named JS projects are separate implementations | Apache-2.0 for Apache JEXL; JS projects have their own licences and semantics | No reliable shared language contract |
| EvalEx | Java evaluator; no corresponding JS implementation established here | Apache-2.0; no external dependencies, but uses BigDecimal | Decimal semantics add JS parity work |
| Fully custom language | Both implementations would be ours | Small dependencies, highest maintenance responsibility | Unnecessary parser and language-design burden |

CEL supports parse/check/evaluate separation and optional macros; disabling iteration features gives a useful restricted foundation. Its Android lite runtime is a genuine option, not an assumed port. [CEL Java](https://github.com/cel-expr/cel-java), [Android runtime release](https://github.com/cel-expr/cel-java/releases/tag/v0.10.1)

Buf’s CEL-ES is explicitly beta, and its dependency graph needs packaging and GraalJS verification. [CEL-ES README](https://github.com/bufbuild/cel-es), [package manifest](https://github.com/bufbuild/cel-es/blob/main/packages/cel/package.json)

JsonLogic supplies portable JSON rules, but includes coercion, operand-returning Boolean operators and collection operations.
Supporting the desired editor would still require an infix frontend and a strict profile. [JsonLogic operations](https://jsonlogic.com/operations.html), [Java implementation](https://github.com/jamsesso/json-logic-java), [JS manifest](https://github.com/jwadhams/json-logic-js/blob/master/package.json)

JEXL’s current expression restrictions improve safety, but do not establish compatibility with a JS implementation.
EvalEx’s BigDecimal arithmetic would require a matching numerical implementation in JS. [JEXL documentation](https://commons.apache.org/proper/commons-jexl/), [EvalEx documentation](https://github.com/ezylang/EvalEx)

**My recommendation adopts CEL’s frontend, rather than two complete CEL runtimes.**
Use the separately published Java compiler artifact on the phone, initially evaluating version 0.14.0.
Configure an explicit declaration allowlist, disable macros, then validate and lower its checked AST.
Do not ship the compiler, CEL runtime, GraalJS or a JS engine on the glasses. [Compiler packaging](https://github.com/cel-expr/cel-java/blob/v0.14.0/publish/BUILD.bazel)

This still leaves two small application evaluators: Kotlin for deployment/replay and JS for canvas preview/testing.
That is a deliberate maintenance cost.
Full CEL runtimes do not automatically satisfy the draft’s state-memory limit, intermediate-error rules or exact cross-runtime behavior.
The alternative worth retaining is CEL lite plus CEL-ES, if a measured spike demonstrates those requirements with less implementation work.

No APK or bundle-size measurements were performed for this proposal.
Dependency counts, source size and compressed APK growth are different measurements.
The first task must report actual phone APK delta, glasses APK delta, retained heap and cold compilation time.
Proposed review thresholds are +3 MiB compressed on the phone and +100 KiB on the glasses; these are budgets, not library-size claims.

**Use CEL spelling consistently.**
Accept decimal literals, Boolean literals, declared identifiers, parentheses and these operators:
`+ - * /`, `< <= > >= == !=`, `&& || !`, and `condition ? a : b`.
Permit `in` only against a literal list of enum constants.

Provide only named, reviewed functions: `min`, `max`, `abs`, `clamp`, `lookup`, `mean`, `maxOver`, `frac` and `count`.
Window functions are compile-time declarations of state requirements; they are not arbitrary runtime calls.
Do not add aliases such as `and`, tuple membership or `if(...)` in v1.
Offer a suggested CEL spelling when these appear.

Examples include:

| Purpose | Expression |
|---|---|
| Motion guard | `headSpeed > 0.8 && mean(gyroMean, 10) > 0.3` |
| REST cap computation | `ctxConf >= 0.8 ? 2.0 : 4.0` |
| Inspect recent context | `frac(ctx == REST, 300) > 0.8` |
| Count detector events | `count(glance, 60) >= 2.0` |
| Bounded table lookup | `lookup(caps, ctx, 4.0)` |

Exclude assignments, local bindings, recursion, macros, comprehensions, dynamic collections and receiver-method calls.
Also exclude strings, regex, timestamps, duration objects, randomness, clock access and arbitrary property/index access.
A declared input identifier is a read-only signal, not a user variable.
This is a named CEL-based profile, not a promise to accept every CEL expression.

**Make types strict and visible in the inspector.**
Use `Bool`, finite `Number` represented as binary64, and nominal enums such as `ContextState` and `MotionState`.
Enums compare only within their declared type.
Lists and table names are compile-time structures, never general runtime values.

Arithmetic accepts Numbers; Boolean operators require Bools.
Both ternary branches must have the same type.
There is no truthiness, string conversion, enum-to-number conversion or implicit Boolean conversion.
Initially require decimal spelling for runtime numbers, such as `4.0`; window lengths remain integer literal metadata.

Attach units to signal metadata and diagnostics: rad/s, radians, milliseconds, Hz and fractions.
Reject clearly incompatible comparisons and additions where unit information is available.
Do not attempt a general symbolic units system in v1.
Policy targets specify their output unit and deterministic conversion rule.

For integer fields, apply floor and then `PlanBounds`; show the converted result in preview.
Static out-of-range constants fail compilation.
Dynamic values may be clamped, with a diagnostic counter.
WALKING and UNKNOWN remain immutable, and all existing non-removable invariants run afterward.

**Freeze a signal catalogue before implementing evaluation.**
The A3 worktree already defines seven `SecondFeatures` fields:
`gyroMax`, `gyroMean`, `gyroRms`, `aboveOnFrac`, `accVar`, `stepHz` and `rotRad`.
Expose their existing computed values; do not introduce expression work into the IMU sample loop.

Define `headSpeed` explicitly as an alias for the last completed second’s `gyroMax`, in rad/s.
It must not ambiguously mean instantaneous gyro speed or the peak of the preceding bout.
If bout peak speed is desired, add a separately named feature later.

Expose the draft’s candidate/gate features when the shared skeleton actually supplies them:
`boutMs`, `boutRad`, `rotSinceKeepRad`, `msSinceKeep`, `walkedSinceKeep` and `keeps60s`.
Mark each feature’s availability by event type.
Do not fabricate missing candidate metadata for timer evaluations.

For value programs, expose `ctx`, `ctxConf` and a local monotonic context-age feature.
Use the effective context after `ContextGate`, including expiry and the local walking veto.
Do not subtract a phone wall-clock timestamp from a glasses monotonic timestamp.
UNKNOWN remains the existing explicit context fallback, distinct from an unavailable numeric input.

The first live consumer is RT_GLASSES capture policy.
The phone runs the same Kotlin evaluator for compilation checks, preview and replay.
Hub-derived windows operate over the context values already available through the `ctx` binding.
Raw GPS, Bluetooth, watch and intent evidence are not added to the glasses expression environment.

RT_PHONE fusion-parameter expressions and ASYNC expressions should follow under separate consumer contracts.
Grey out those placements initially.
This preserves S34’s three bindings and avoids silently introducing a new distributed signal protocol.

**Window semantics must be part of the language version.**
Support integer horizons from 1 to 300 seconds.
`mean` and `maxOver` accept one allowlisted numeric feature.
`frac` accepts a Boolean feature or a single feature-to-constant comparison.
`count` accepts an allowlisted event source and sums its per-second event counts.

Reject nested windows, dynamic horizons and arbitrary expressions inside aggregates.
Intern identical window declarations across programs.
Count different horizons or predicates separately against the four-window limit.
Initially omit the draft’s separate 64-event-slot window mode.

Evaluate windows over completed one-second buckets, `[second − horizon, second)`.
Specify boundary ordering in the trace format: complete the previous bucket, apply current inputs, then evaluate decisions.
For context predicates, sample the effective context at each bucket boundary.
This makes `frac` a fraction of seconds, rather than a fraction of received messages.

A new window requires its entire horizon of valid buckets before becoming ready.
Missing sensor intervals are invalid buckets, not zero-valued observations.
Context expiry still produces the defined UNKNOWN state.
Preview shows warm-up and missing-data coverage.

Use quantized Int32 bucket values with a versioned scale for each numeric feature.
Convert once per completed second using a documented rounding rule; out-of-range values become unavailable.
Do not change the underlying A3 feature computation.
Store quantized values in traces so replay receives exactly the same expression inputs.

Maintain integer sums and counts, with bounds below JavaScript’s exact-integer limit.
Compute mean in the fixed order `(sum / count) / scale`.
Use a monotonic deque for maximums.
Queries are O(1); deque updates are amortized O(1), with a bounded worst-case expiration burst.

**Retain the draft’s structural caps and strengthen admission checks.**
Keep six deployed programs, 32 lowered AST nodes per program, four windows, three tables and 32 rows per table.
Add maximum AST depth 16, membership-list length 16 and source size 1,024 UTF-8 bytes per editor node.
Enforce limits before and after lowering; constant folding must not bypass source-resource checks.

The entire resolved canonical plan remains at most 4,096 UTF-8 bytes.
These maxima are independent ceilings, not a promise that every maximum fits simultaneously.
Reject excess size or state requirements; never trim programs or shorten windows.

Keep evaluator working state at or below 8 KiB, including arrays, object headers, deques, stacks and counters.
Account for immutable decoded plan storage separately and report total retained heap as well.
Use packed primitive storage rather than per-bucket objects.

A conservative initial admission formula is `2,048 + 8 × totalWindowSlots ≤ 8,192`.
Thus four 300-second windows would be rejected even though each is individually legal.
Replace the formula only after the Android allocation layout is measured.
Show the limiting resource and suggested shorter horizons in the inspector.

The draft’s approximately 400 operations/second is insufficient as a complete bound.
It excludes table scans and worst-case deque maintenance.
Use weighted costs: one unit per scalar node, additional units per membership element or lookup row, and explicit window-update bounds.

Propose at most 512 weighted units per evaluation batch and 8,192 units per second including window maintenance.
Count both ternary branches conservatively during admission.
Maintain a deterministic runtime instruction budget as a second defence.
These units establish bounded work; device measurements must establish elapsed CPU and battery cost.

**Expressions must not create wakeups or raise sensor rates.**
Evaluate a batch only for existing glance/timer opportunities, with at most two batches per second.
Coalesce additional expression requests and preserve the skeleton’s camera-busy and dedupe behavior.
Record the selected candidate and evaluation order so replay reproduces coalescing.

Update windows on existing feature callbacks, using bounded lazy expiration after gaps.
Do not start a one-second alarm, acquire another wake lock, poll the hub or increase message cadence.
When capture’s existing sensor/session lifecycle stops, expression processing stops with it.
The reserved opportunity bypasses guards and uses safe policy fallback if evaluation cannot run immediately.

**Use explicit fail-safe outcomes.**
Compilation errors prevent freeze/publish and identify the node and source span.
On-device validation errors reject the new plan while preserving the previous effective configuration.
Unknown versions, features, operations or capability requirements are rejection errors.

Runtime outcomes are `OK`, `NOT_READY` and `ERROR`.
Warm-up, stale numeric inputs and missing trace coverage produce NOT_READY.
Division by zero, non-finite intermediate results, invalid aggregate state and exhausted instruction budgets produce ERROR.

For either unsuccessful outcome, a guard allows the candidate through and a value uses its frozen tier-1 row value.
Other privacy, thermal, battery, ownership and capture-cap checks still apply.
This preserves capture availability while bounding the battery consequence through the existing skeleton.

Change the draft’s division-by-zero result from zero to ERROR.
Normalize negative zero after every numeric operation.
Specify binary64 evaluation order, prohibit reassociation and fused arithmetic, and reject non-finite intermediates.
Do not constant-fold a dynamic calculation into a numerically different ordering.

Use short-circuit `&&`, `||` and ternary evaluation.
Any error actually reached aborts that program; an unvisited branch cannot fail.
This intentionally differs from CEL’s partial-error suppression and must be documented as profile behavior. [CEL error semantics](https://github.com/cel-expr/cel-java#errors)

Increment `exprErr` once per failed program per batch; NOT_READY has a separate counter.
More than ten errors in a rolling minute triggers the existing local plan auto-revert and group reconciliation.
Retain only the eleven timestamps needed to detect that threshold.
Rate-limit diagnostics and avoid logging source signal values by default.

**Compile to data with explicit compatibility metadata.**
The pipeline stores original expression text, node type/version, constants and bindings.
The phone parses and checks with CEL, validates the profile, extracts windows, then lowers to a typed JSON AST.
The glasses receive only the resolved plan data.

Use compact tagged AST nodes for constants, feature reads, arithmetic, comparisons, conditionals, membership, window reads and table lookups.
Resolve names to stable feature IDs and enums to stable catalogue IDs.
No executable bytecode, class names, function pointers or source evaluation is required.

I recommend `planSchema: 2` for expression-bearing plans, plus explicit expression-profile and signal-catalogue versions.
Keep existing schema-1 plans and stored hashes unchanged.
This is a proposed amendment to §5.2, allowing old devices to reject the extension unambiguously.

Include programs, constants, tables, window descriptors, target bindings and semantic versions in the plan hash.
Follow the established canonicalization and hash-field exclusion convention.
Keep source spans and original text in the frozen pipeline, outside the size-capped delivery plan.
Show both pipeline identity and compiled-plan identity in diagnostics.

Require versioned expression/window capabilities on both endpoints before activation.
Validate again on the glasses: types, bounds, IDs, acyclicity, role permissions, size, cost and memory.
Allocate state during PREPARED, then swap plan and state atomically.
Restarting or replacing a plan resets windows unless an exact replay start snapshot restores them.

**Keep the React Flow inspector small.**
Use the existing textarea, with role/target selectors, a signal catalogue, function help and insertable examples.
Show units, result type, domain, required capabilities and estimated window warm-up.
Do not add CodeMirror for these short expressions.

Debounce authoritative phone validation and attach the draft revision to each request.
Discard stale responses using slice 2’s existing revision rule.
The browser does not need another production parser.
Pure JS modules handle diagnostics, AST printing, evaluation and inspector state.

Preview shows the input timestamp, expression result, effective clamped value and fallback reason.
Offer “test this snapshot” and “replay selected interval.”
Display plan bytes, weighted cost and working-state usage before freeze.
Keep the existing SHADOW/TRIAL/LIVE gates and rollback controls.

**Make agreement an executable contract, rather than a tolerance check.**
Place Kotlin evaluation in `:core:capture`, shared by glasses and phone replay.
Put the JS evaluator in a pure canvas ES module exercised by the existing GraalJS harness.
React Flow remains covered by the browser/device checklist.

Share versioned golden fixtures containing source, lowered AST, input events, window state and expected outputs.
Compare Boolean/enumerated outputs exactly and numeric outputs by binary64 bits after zero normalization.
Compare readiness, error codes, policy conversions, instruction counts and decision order too.

Run fixtures through JVM Kotlin, GraalJS and Android instrumentation on the glasses.
Use integer or bit-pattern trace encodings to avoid accidental Float/string round-trip differences.
Golden expectations must include hand-computed cases, not only one evaluator blessing the other.

Cover arithmetic boundaries, division errors, skipped branches, missing inputs, enum mismatches and policy clamping.
Cover window warm-up, exact boundaries, gaps, expiry, deque ties, plan replacement and reboot.
Add seeded differential generation of legal bounded ASTs and malformed-plan fuzzing.

Test the text/AST round trip by printing in JS, parsing through the authoritative JVM frontend and comparing normalized ASTs.
This works without granting GraalJS host access or writing a second parser.
Include exact-plan byte goldens and boundary cases at 4,096 and 4,097 bytes.

End-to-end tests must retain all-false-guard liveness, WALKING/UNKNOWN protection, SPEED precedence and Capture-now exemptions.
Exercise stale generations, missing capabilities, rollback after the eleventh error and group compensation.
Replay complete traces for exact open/keep/skip sequences; retain the spec’s counterfactual limitations.

**Security follows from a small authority surface.**
Treat expression source and received plans as untrusted, even when generated by another tool.
Bound parser input, parser nodes, nesting, validation concurrency and decoded allocation sizes.
Allow only declared feature IDs and literal table keys; never resolve host-object paths.

No evaluator operation can access files, networking, sensors, storage, camera controls, reflection or process APIs.
Render source and diagnostics as text under the existing CSP.
Pin adopted dependencies, retain licence notices and re-run compatibility fixtures before upgrades.
Forget/wipe fences invalidate expression previews and replay state alongside their source traces.

**Battery acceptance needs measured evidence.**
The A3 baseline remains 0.49 %/min; unrestricted LIVE still requires the existing measured ≤0.42 %/min gate.
A small interpreter does not itself qualify a plan.
Compare identical capture policy with expressions disabled and enabled to isolate evaluator overhead.

Propose evaluator acceptance targets of zero added scheduled wakeups, allocation-free steady-state evaluation and no sustained sensor-rate change.
Use ≤1 ms p99 evaluation batches and ≤0.005 percentage-points/min incremental drain as engineering targets.
The drain target may be below short-run measurement resolution, so report uncertainty and extend paired worn runs when needed.
Keep the existing verification duration, publish modes and automatic rollback rules.

**The work is larger than the current 13-hour C1–C3 allowance.**
The following estimates cover expressions only and assume the 3a compiler, protocol, replay and publish infrastructure is merged.
They exclude detectors, user wear time and unrelated branch integration.

| Task | Concrete deliverable | Depends on | Agent-hours |
|---|---|---|---:|
| E0 | Agree profile, roles, catalogue and spec amendments | Language discussion | 3 |
| E1 | CEL compiler Android probe; dependency, APK, heap and runtime comparison | E0 | 4 |
| E2 | Authoritative frontend, profile checker, lowering and diagnostics | E1 | 6 |
| E3 | Packed Kotlin evaluator, numerical rules and resource validator | E2 | 6 |
| E4 | Windows, quantization, gap handling and replay snapshots | E3 | 6 |
| E5 | JS evaluator, AST printer and cross-runtime golden suite | E2–E4 | 6 |
| E6 | Plan version/capabilities, atomic apply, counters and auto-revert | E3–E4 | 4 |
| E7 | Inspector, snapshot preview and replay integration | E2, E5 | 4 |
| E8 | Device parity, resource stress and battery verification analysis | E5–E7 | 5 |
| E9 | Restricted Python-promotion translation through the same frontend | Core acceptance | 3 |
| Total | Expression feature including promotion | | 47 |

Allow approximately 20% integration contingency: about 56 agent-hours.
Core expressions without promotion are approximately 44 hours before contingency.
These estimates include meaningful parity and device validation; they should be revised after E1.
Planning may happen now, while implementation remains behind the 3c prerequisite.

**Open questions for the user**

1. Is CEL spelling (`&&`, `!`, `[WALKING, UNKNOWN]`) acceptable instead of the example’s word operators and tuple syntax?
2. Should context remain forbidden in capture guards, as S5 currently requires?
3. Should `headSpeed` mean the last second’s maximum speed, or should we introduce a separately named bout-peak signal?
4. Is full-window warm-up acceptable, with capture-safe defaults until the requested history exists?
5. Which additional hub inputs are essential beyond effective context, confidence and age?
6. Is the CEL frontend plus a dedicated bounded evaluator acceptable, subject to the library/runtime spike?
7. Are the proposed resource budgets and division-by-zero error behavior acceptable?
8. Should Python-to-expression promotion ship with the first expression release or follow it?