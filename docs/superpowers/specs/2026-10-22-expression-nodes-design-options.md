# Expression nodes (`expr`): design options and proposal

**Status:** independent proposal, design only. Written on 2026-10-22 by the Opus planner as input to slice 3c task C0 (the user's brainstorm). Another planner (Codex) is writing a separate proposal, which this one has not read.
**Replaces nothing yet.** If the user accepts it, it becomes the revision of slice 3 §4.3 (S5, S33, S51). Rulings here are numbered **X1–X9** so they don't collide with slice 3's S numbers.

**Builds on:**
- slice 3 r3 (`2026-10-21-canvas-slice3-e2e-configurator-design.md`): §3 invariants (S3, S23, S31, S32), §4.1 node contract, §4.3 starting draft, §5.1–5.2 (`:core:capture`, the 4 KB `CapturePlan`), §5.3 caps and acks, §6 replay, the Re-cut (S50–S56);
- slice 2 (`2026-10-21-canvas-slice2-design.md`): S3 GraalJS harness (`JsHarness`, `phone/src/test/js/`), §4.1 vendoring rules (no `eval`, no `new Function`, flat `vendor-*`);
- A3's `SecondFeatures` (`gyroMax, gyroMean, gyroRms, aboveOnFrac, accVar, stepHz, rotRad`, one row per second) and `ContextState { UNKNOWN, REST, WALKING, VEHICLE_PASSENGER, VEHICLE_DRIVING, FLIGHT }`.

**Battery comes first.** The A3 worn run measured 0.49 %/min, and the target is ≤ 0.42 %/min. Nothing in this proposal adds a sensor, a wakeup, a timer or a camera open. The only extra cost is CPU time on events that already happen (§6).

---

## 1. Recommendation in one paragraph
Write a **small custom language, "pcx"**, whose surface syntax is a **strict subset of CEL syntax** with two additions (namespaced window functions and duration literals). The `CapturePlan` carries it as a **JSON AST**, and two small hand-written evaluators run it: **Kotlin** in `:core:capture` (the authority, used by the compiler, the simulator, the phone and the glasses) and **JS** in `canvas/expr.js` (used for live checking and the "try it" preview in the inspector). Agreement between them is enforced by **shared golden vectors and a seeded differential fuzz test that runs both in one JUnit process through GraalJS**. **cel-java is a test-only oracle** for the CEL-compatible fragment and never ships in an APK. No existing library is shipped, because none of them gives all four of these together: (a) identical results in JVM and JS, (b) windows with O(1) incremental state, (c) a static cost bound the validator can prove, and (d) zero allocation on the glasses at event rate, at a footprint that fits the glasses APK.

---

## 2. Requirements (from the user and slice 3)
| # | Requirement | Source |
|---|---|---|
| R1 | The user writes conditions and computations in a canvas node, for example `headSpeed > 0.8 and ctx in (WALKING, UNKNOWN)`, plus windowed aggregates over IMU and hub signals | user |
| R2 | It runs on the device (glasses, and phone later) inside the compiled plan, under the 4 KB cap | S27, §5.2 |
| R3 | Never arbitrary code: no loops, recursion, IO, clock reads, strings, reflection or `eval` | S4, S5, §9 |
| R4 | Deterministic and bounded cost, provable at compile time | S33, S5 |
| R5 | Battery first: no new wakeups, and no measurable %/min cost | §1 |
| R6 | Testable in the GraalJS harness and in Kotlin, with golden traces where JS and Kotlin agree **exactly** | user |
| R7 | Fails safe: an error can never cost liveness (S31) or the walking guarantee (S32), and it can never keep the camera open longer | S31, S32, S33 |
| R8 | Instant publish: a deterministic compile, no LLM and no APK rebuild | tier 2, §1 |
| R9 | Expression constants can be swept by the tuner | §6.3 |

---

## 3. Candidates evaluated (versions and licences checked on 2026-10-22)

| Candidate | JVM / Android | JS | Licence | Footprint | Determinism across JVM and JS | Bounded cost | Fit |
|---|---|---|---|---|---|---|---|
| **Google CEL**: `dev.cel` (cel-java) **0.14.0** (2026-08-17), with a `runtime-android` artifact | yes. `runtime-android` 0.14.0 depends on Guava 33.6.0-android, protobuf-javalite 4.35.1, RE2/J 1.8, threeten-extra, auto-value annotations; Java 8+ | separate implementations: `@marcbachmann/cel-js` 7.x (MIT, zero dependencies, AST interpreter, `maxAstNodes`/`maxDepth` limits, ints as `BigInt`); `@bufbuild/cel` (cel-es, Apache-2.0, beta per its README, depends on protobuf-es); ChromeGG `cel-js` 0.6.0 (Chevrotain) | Apache-2.0 (Java); MIT / Apache-2.0 (JS) | several MB of dependencies on the glasses APK (Guava and protobuf alone). Unacceptable for a hot path we want allocation-free | **no guarantee**: three independent codebases, CEL `int` is int64 (`BigInt` in JS, `long` in Java), and error, overflow and `uint` rules differ in the corners | non-Turing, but macros (`all`, `exists`, `map`, `filter`) iterate. They can be disabled, but the cost estimator is per implementation | **syntax: adopt (subset). Runtime: test-only oracle** |
| **JsonLogic**: `json-logic-js` 2.0.5, `json-logic-java` (jamsesso) 1.1.0 | yes (Java port) | yes (reference) | MIT / MIT | small | weak: JS truthiness and type coercion (`"1" == 1`) that each port re-implements; string ops; `var` with dotted paths | `map`, `filter`, `reduce`, `all`, `some` over arrays; no static types | AST shape is inspiring, but there is no text syntax (R1), it coerces types and has no windows. **Reject** |
| **expr-eval** (npm) | no | yes | MIT | small | n/a | n/a | **CVE-2025-13204** (prototype pollution → code execution, CVSS 7.3) in every version up to 2.0.2; the fix lives only in a fork. JS only. **Reject** |
| **mathjs** 15.x | no | yes | Apache-2.0 | 712 KB minified / 192 KB gzip | n/a | has function definitions, matrices and unit parsing | JS only, far too large, and it parses user-defined functions. **Reject** |
| **Starlark**: Java (inside Bazel), Go (`starlark-go`, BSD-3), Rust (Meta) | Java yes (not a clean Android artifact) | **no maintained JS implementation** | Apache-2.0 / BSD-3 | large | n/a | has `for` loops over finite values, strings, `def`; bounded only by step counters | a configuration language, not an expression language, and no JS (R6). **Reject** |
| EvalEx, exp4j (Java), filtrex (JS) | one side only each | one side only each | Apache-2.0 / MIT | small | two different libraries means two semantics | no windows | **Reject** (no shared semantics) |
| **Kotlin Multiplatform**: one Kotlin evaluator compiled to JVM and to a JS module | yes | yes (generated) | ours | the Kotlin/JS stdlib is shipped in the canvas, about 100 KB+ after DCE | **one source**. Kotlin/JS `Double` is a JS number, so arithmetic agrees; `Int`/`Long` are emulated | ours | attractive, but it adds the Kotlin/JS toolchain (it wants Node for tests, and the machine has no Node; slice 2 S3). The generated JS is opaque to `VendorLockTest` and the own-code scans. **Fallback** if differential testing keeps finding divergences (X8) |
| **Custom "pcx"** (CEL-subset syntax, our AST, two small evaluators) | ~600 lines of Kotlin | ~450 lines of JS | ours | ~20 KB of dex on the glasses; one canvas module | **by construction**: `Double`-only arithmetic, a fixed op set, fixed evaluation order, no ints, no strings, plus a differential fuzz | static, exact (§6) | **Adopt** |

Ruling X1: **custom language "pcx" with CEL-subset syntax; cel-java 0.14.0 is a test-only oracle in `:core:capture` (`testImplementation`), never a runtime dependency.**
- Why: CEL's syntax is well documented and familiar, so we borrow it and its precedence table. None of CEL's runtimes are shipped: they are heavy on Android, they allocate per evaluation, and the JVM and JS implementations are independent codebases, so the "agree exactly" requirement (R6) would rest on three third-party projects. Our subset uses about 5 % of CEL, and the parts that make CEL big (protobuf types, int64/uint64, strings, regex, timestamps, macros) are exactly the parts we ban.
- Cost if wrong: about 7 agent-h of evaluator code we own, plus the oracle test. If divergences keep appearing, X8 switches to Kotlin Multiplatform without changing the language.

---

## 4. The language

### 4.1 Types
| Type | Values | Representation (Kotlin and JS) |
|---|---|---|
| `num` | finite IEEE-754 doubles | `Double` / JS number |
| `bool` | `true`, `false` | `0.0` / `1.0` on the evaluator stack |
| `enum<E>` | one closed enum per signal (`ContextState`, `MotionState`, `Trigger`) | a fixed **wire code** per value (from the signal catalogue, never a Kotlin `ordinal`) |
| `dur` | duration literals `1500ms`, `30s`, `5m` | a number of ms; a distinct type only as the second argument of `win.*` |

- There are no strings, nulls, lists (except the literal enum list after `in`), maps, ints or user-defined types.
- Every program is statically typed at compile time. A type error is a compile problem, never a runtime error.
- Bare uppercase identifiers are enum literals, resolved against the other operand's type: `ctx == WALKING` is fine, but `motion == WALKING` resolves against `MotionState` (it has a `WALKING` too), and `ctx == HEAD_MOVING` is a type error.

### 4.2 Syntax (a CEL subset with two additions)
```
expr    := or ( '?' expr ':' expr )?
or      := and ( ('||' | 'or') and )*
and     := not ( ('&&' | 'and') not )*
not     := ('!' | 'not') not | cmp
cmp     := sum ( ('<'|'<='|'>'|'>='|'=='|'!=') sum | 'in' enumlist )?     // not chainable
sum     := prod ( ('+'|'-') prod )*
prod    := unary ( ('*'|'/') unary )*
unary   := '-' unary | atom
atom    := NUMBER | DURATION | 'true' | 'false' | NAME | ENUM | call | '(' expr ')'
call    := FUNC '(' expr (',' expr)* ')'
enumlist:= '[' ENUM (',' ENUM)* ']' | '(' ENUM (',' ENUM)* ')'
```
- **Aliases** for the user's example: `and`, `or`, `not`, and `in (A, B)`. On blur the editor rewrites the text to the canonical form, which is valid CEL: `&&`, `||`, `!`, `in [A, B]`. That is what the oracle test parses (§10.4).
- **Precedence of `not`:** it sits just above comparisons, as in Python (`not a > b` means `!(a > b)`). In CEL, `!` binds tighter, but `!a > b` is then a type error (`bool > num`), so no valid program means different things in the two.
- **Not chainable:** `a < b < c` is a parse error, not a surprise.
- **`if(c, a, b)`** is accepted as an alias of `c ? a : b`.
- **Limits:** text ≤ 256 characters, ≤ 32 AST nodes, depth ≤ 10. The parser is recursive descent with an explicit depth counter, so it cannot overflow its stack.

### 4.3 Operators and functions (the whole list)
| Group | Members | Notes |
|---|---|---|
| arithmetic | `+ - * /`, unary `-` | `x / 0` gives `0`, as in S33. The validator warns when a divisor is a bare signal |
| comparison | `< <= > >= == !=`, `in [..]` | `in` only for enum lists of ≤ 6 values |
| logic | `&& \|\| !`, `?:`, `if` | CEL's **commutative** error rules (§4.5) |
| scalar functions | `abs(x)`, `min(a, b, …≤4)`, `max(a, b, …≤4)`, `clamp(x, lo, hi)`, `floor(x)`, `sqrt(x)` | only IEEE-exact operations. `sqrt` is correctly rounded in both Java and JS engines and is covered by goldens. **No** `pow`, `exp`, `log` or trig functions: their results can differ in the last bit between `java.lang.Math` and a JS engine |
| table | `lookup(T, key, default)` | `T` is a `const.table` (≤ 32 rows) with an enum or a `num` key and a `num` value |
| windows | `win.mean(s, d)`, `win.max(s, d)`, `win.min(s, d)`, `win.sum(s, d)`, `win.frac(pred, d)`, `win.count(event, d)` | `d` is a duration literal from 1 s to 300 s (`win.count` up to the last 64 events). See §5.3 |

Ruling X2: **no ints, no strings, no transcendental functions, no `pow`.**
- Why: everything else in the table is either exact IEEE arithmetic or a comparison, so Kotlin and JS give bit-identical results. That is the whole basis of R6.
- Cost if wrong: a user who wants `x²` writes `x * x`; a log-scaled threshold is not expressible.

### 4.4 Evaluation order and the cost model
- **Every node is evaluated, with no short-circuit**, in a fixed post-order. There are no side effects, so this changes no result, and it makes the cost of a program a **constant**, not a worst case.
- **Unit costs** (static, shown in the inspector): literal or signal 1; arithmetic, comparison or logic 1; scalar function 2; `in` 1 + list length; `lookup` 2 + rows; window read 2.
- **Hard limits** (validator and glasses both enforce them; glasses reason `expr_limit`):

  | Limit | Value | Was (S33 draft) |
  |---|---|---|
  | AST nodes per program | 32 | 32 |
  | cost units per program | 64 | — |
  | programs per plan | 6 | 6 |
  | cost units per plan per tick | 256 | ≈ 200 |
  | window terms per plan (distinct `(signal, d, agg)` triples) | 4 | 4 |
  | tables per plan / rows per table | 3 / 32 | 3 / 32 |
  | evaluator state per plan | ≤ 8 KB, allocated at apply | 8 KB |
  | ticks | ≤ 2 per second, coalesced in 500 ms | same |
  | `exprs` + `tables` bytes in `planJson` | reported. The whole plan stays ≤ 4,096 B | — |

### 4.5 Errors (fail-safe, the same in both evaluators)
- **Runtime error value:** the evaluator stack holds `Double`s, and NaN stands for "error". Any operation that produces a non-finite value (Inf or NaN) produces NaN. Outputs are canonicalised (`-0.0` → `+0.0`).
- **Comparisons** with an error operand give an error, never `false`.
- **Logic is commutative, as in CEL:** `false && err = false`, `true || err = true`, otherwise an error. `c ? a : b` with an error in `c` is an error, and an error in the unselected branch is ignored.
- **What an error does** (S33, extended):

  | Role | On error |
  |---|---|
  | guard | the candidate is **kept** (the guard is skipped) |
  | value | the field takes its **row default** (the resolved settings value) |
  | signal (§5.4) | its consumers see an error and apply their own rule |

- **Counting:** `exprErr` per program, in the heartbeat. More than 10 in any minute → auto-revert to `USE_SETTINGS` with `auto:exprErr` (§7 of slice 3), as already drafted.
- **Missing or stale inputs are not errors.** Every signal has a declared default and a staleness limit in the catalogue (§5.1). For example, a `ctx` older than its limit reads as `UNKNOWN`. Defaults are part of the golden vectors.

Ruling X3: **compile-time type errors, runtime NaN-as-error with CEL's commutative logic, and fixed fallbacks by role.**
- Why: a guard error costs at most the cap, never liveness. A value error falls back to a tested row, and NaN behaves the same on both platforms.
- Cost if wrong: a broken guard silently stops filtering until `exprErr` reverts the plan. SHADOW shows the error counts before any LIVE run.

---

## 5. Signals, windows and roles

### 5.1 Signal catalogue (versioned, `sigSchema 1`, served by `/api/node-types`)
Only signals the glasses **already compute** in A3 are in v1 (R5). A signal that is not referenced costs nothing: its ring is not even allocated.

| Signal | Type, unit | Rate | Source | Default (stale after) |
|---|---|---|---|---|
| `headSpeed` (= `gyroMean`) | num, rad/s | 1 Hz | `SecondFeatures` | 0 (3 s) |
| `gyroMax`, `gyroRms` | num, rad/s | 1 Hz | `SecondFeatures` | 0 (3 s) |
| `aboveOnFrac` | num, 0–1 | 1 Hz | `SecondFeatures` | 0 (3 s) |
| `accVar` | num, (m/s²)² | 1 Hz | `SecondFeatures` | 0 (3 s) |
| `stepHz` | num, Hz | 1 Hz | `SecondFeatures` | 0 (3 s) |
| `rotRad` | num, rad | 1 Hz | `SecondFeatures` | 0 (3 s) |
| `motion` | enum `STILL/HEAD_MOVING/WALKING` | 1 Hz | `MotionModel` | `STILL` (3 s) |
| `boutMs`, `boutRad` | num, ms / rad | per glance | `GlanceDetector` | 0 |
| `rotSinceKeepRad`, `msSinceKeep`, `keeps60s` | num | per tick | novelty and `KeyframeGate` | 0 |
| `walkedSinceKeep` | bool | per tick | novelty | false |
| `trigger` | enum `GLANCE/TIMER` | per tick | the scheduler | — |
| `ctx` | enum `ContextState` | on change | glasses `ContextGate` (fed by `pc_motion_ctx`) | `UNKNOWN` (wave B's staleness) |
| `ctxConf` | num, 0–1 | on change | `ContextGate` | 0 |
| `ctxSinceMs` | num, ms | per tick | `ContextGate` | 0 |
| events for `win.count`: `glance`, `open`, `keep` | — | event | the skeleton | — |

- A `speedKmh` bucket is in the catalogue **only if** `pc_motion_ctx` already carries it to the glasses (wave B). Otherwise it is phone-only and waits for the phone domain (Open question 6).
- Location, addresses, device names and intent text are never signals (§9).

### 5.2 Ticks: when programs run
- **Guard programs** run on a glance candidate, after `GlanceDetector` and before novelty.
- **Value programs** run on the scheduler tick that reads the policy row (glance and timer events), coalesced to at most 2 per second (S33).
- **Window rings** are updated once per second, on the `SecondFeatures` row that the classifier already produces.
- Nothing runs while the glasses are paused, not worn, or heat- or battery-stopped, because the skeleton never ticks then.

### 5.3 Windows: deterministic by construction
- **Ring:** each distinct window term gets a ring of 300 per-second slots (or 64 event timestamps for `win.count`), allocated when the plan is applied.
- **Quantised slots:** a slot stores `q = Math.round(x * 1000)` as a 32-bit int after clamping to ±2·10⁶ (Java's `Math.round` and JS's `Math.round` both round halves toward +∞). `win.frac` stores 0/1. **Running sums are exact integers** (≤ 300 × 2·10⁹ < 2⁵³), so the incremental O(1) sum gives the same bits as a full recompute and as JS. `mean = (sum / n) / 1000`, done as two IEEE divisions in both languages.
- **`win.max` / `win.min`:** a monotonic deque over the quantised ints, O(1) amortised.
- **Warm-up:** `n` is the number of filled slots in the window. With `n = 0`, the term returns the signal's default. The inspector shows "warming (n/N s)".
- **`win.frac(pred, d)`:** `pred` must be a **simple predicate** over one 1 Hz signal (`sig op const`, `motion == X`, `ctx in [...]`), evaluated once per second when the slot is written.
- **Reset:** rings are cleared when a plan is applied and when the HUD capture session restarts. The trace's start snapshot stores the ring contents (≤ 4 × 300 × 4 B = 4.8 KB) so that exact replay (S29) stays bit-identical when a trace starts mid-plan.

Ruling X4: **quantised integer rings.**
- Why: a floating-point running sum drifts, and it drifts differently from a recompute, so replay parity and JS/Kotlin agreement would both break on long sessions. Milli-unit quantisation is far below sensor noise.
- Cost if wrong: about 0.0005 rad/s of resolution is lost.

### 5.4 Roles (one canvas node type per role)
| Node type | Domain, category | Returns | What the skeleton does with it |
|---|---|---|---|
| `expr.guard` | RT_GLASSES, THRESHOLDS_FILTERS | bool | true = pass, false = **drop the glance candidate**. Never touches the reserved timer opportunity (S31 G1) |
| `expr.value` | RT_GLASSES, POLICY_FREQUENCY | num | one policy field of one row (for example `VEHICLE_PASSENGER.capPerMin`), clamped to `PlanBounds` and the §3 invariants. Never a WALKING or UNKNOWN row field (S32) |
| `expr.signal` | RT_GLASSES, SIGNALS_HUB | num or bool | a **named derived signal** that other programs can read, in a DAG with no cycles. If it reads only 1 Hz signals, it can also feed a window. **At most 2 per plan**, counted in the 6 programs |

- **Constants as params (R9):** a program can name node params, for example `headSpeed > thr && boutMs >= minBout`, where `thr` and `minBout` are declared in the node's `ParamSchema` (number, min, max, step, sweep hint). The compiler substitutes them as literals, so the frozen plan has no params (S27), and a sweep recompiles per config (cheap and deterministic).
- **No trigger role:** an expression can never cause an extra camera open or a mark. That is what keeps R5 true by design.

Ruling X5 (**a change to S5; it needs the user's yes**): **guards may read `ctx`, `ctxConf` and `ctxSinceMs`**, not only motion. The user's own example needs it.
- Why it stays safe: guards are drop-only, and S31 G1 (the reserved timer opportunity at least once per 60 s) is unchanged, as is S32's lock on the WALKING and UNKNOWN **policy rows**. The validator shows a warning when a guard can drop candidates in WALKING or UNKNOWN: "this rule can skip glances while walking; the 60 s timer still runs".
- Cost if wrong: during a false vehicle reading, a ctx guard can skip glances. The alternative (keeping S5) forces the example into a value program, which cannot express "drop this glance".

---

## 6. Battery and CPU budget
- **Zero new wakeups:** programs run only on glance and timer events and on the existing 1 Hz features row. No sensor rate changes, no new timers, no new BT traffic (the plan arrives once).
- **Zero allocation at runtime:** at apply, the glasses compile each AST into a flat post-order `IntArray` of opcodes and a `DoubleArray` of constants. Evaluation runs on a preallocated `DoubleArray` stack (depth ≤ 16) with no boxing and no allocation, so it causes no GC.
- **The arithmetic:** a worst case of 256 units per tick × 2 ticks/s + 4 ring updates/s is about 520 simple operations per second. On an A53-class core, at roughly 20–50 ns per interpreted op, that is **10–30 µs of CPU per second (< 0.003 % duty)**. The A3 slope (0.0239 %/min per camera-open second per minute) and its floor make this negligible: the estimate is < 0.001 %/min, far below the run-to-run noise.
- **Proof, not trust:** the heartbeat appends `exprUs=<µs in the last minute>`, and the device checklist (§12, E10) runs 6 max-cost programs in SHADOW for one worn hour against the identity plan.
- **The real battery risk is behavioural, not computational:** a value program can raise `capPerMin` (within `PlanBounds`) and so add camera opens. That goes through slice 3's existing battery gate (the drain estimate, TRIAL, S44 for LIVE) like any param change.

Ruling X6: **post-order bytecode, preallocated stack, constant time per program.**
- Why: it gives a measurable "costs nothing" claim, and the cost the validator reports is exact, not an upper bound.
- Cost if wrong: about 1 agent-h more than a tree-walking evaluator.

---

## 7. Plan encoding (inside the 4 KB `CapturePlan`)
```json
"exprs":[
  {"id":"g1","role":"guard",
   "ast":["&&",[">",["$","headSpeed"],0.8],["in",["$","ctx"],["WALKING","UNKNOWN"]]]},
  {"id":"v1","role":"value","field":"VEHICLE_PASSENGER.capPerMin",
   "ast":["clamp",["lookup","t1",["$","ctx"],2],1,4]},
  {"id":"s1","role":"signal","name":"jerky","ast":[">",["win.max",["$","gyroMax"],30000],2.0]}
],
"tables":{"t1":{"key":"ContextState","rows":[["REST",2],["VEHICLE_PASSENGER",3]]}},
"sigSchema":1
```
- **Prefix arrays:** an op name followed by its operands. `["$", name]` is a signal, a bare number is a literal, and enum values are names in the JSON (readable in diffs) that become wire codes at apply. Durations are ms numbers.
- **Size:** the example guard is 82 bytes. A full 32-node program is about 300 B, so 6 programs plus 3 small tables come to about 2 KB. The compiler reports `exprBytes` next to `planBytes`, and over 4,096 it fails, as today (never trims).
- **Canonical JSON and the hash** come only from Kotlin (`PlanCompiler`). Numbers are written by one Kotlin routine (shortest round-trip, no exponent for 1e-6 ≤ |x| < 1e21). JS never produces plan bytes, and the JS goldens compare ASTs by value, so number-printing differences between `Double.toString` and JS's `Number#toString` cannot matter.
- **Decimal literals:** `Double.parseDouble` and JS `Number()` are both correctly rounded, so the same text gives the same double.
- **The glasses re-validate structurally** before applying: known ops, arity, types against `sigSchema`, the limits in §4.4. On any failure they reject the plan with `expr_limit` or `schema`, and nothing partial is applied.
- **Caps:** the glasses advertise `expr` (v1 roles), `windows` and `sig1`. A plan with `exprs` sent to glasses without them is rejected as `unsupported:expr` (S24).

Ruling X7: **the plan carries the AST, never the source text. The glasses have no parser.**
- Why: a smaller attack surface on the glasses, and one parser (Kotlin) is the authority. The text is kept in the pipeline version (the canvas) for editing only.
- Cost if wrong: about 30 % more bytes than text would take.

---

## 8. React Flow inspector UX
- **Palette:** three nodes (`expr.guard` "Custom condition", `expr.value` "Custom rate", `expr.signal` "Custom signal"), shown only once the glasses ack the `expr` cap.
- **Node face:** the role badge, the expression text (one line, truncated), a cost chip (`23/64`), a red border when there are problems.
- **Inspector:**
  - **Editor:** CodeMirror 5.65.21 (already vendored for `py.fn` per slice 3 §13) with a small pcx `defineSimpleMode` (regex tokens only, no `eval`). Autocomplete lists signals with units and defaults from `/api/node-types`, the role's allowed functions, and the node's params.
  - **Live check on each keystroke** (debounced 150 ms) in `expr.js`: parse → type check → limits, with problems underlined at their position, for example "`ctx` is ContextState; `HEAD_MOVING` is a MotionState value".
  - **Meters:** cost units (program and plan), window terms used (`2/4`), bytes (`exprBytes`/budget).
  - **Params block:** `paramform.js` rows for every free name that is not a signal ("declare `thr` as a param?" with one tap), with min, max, step and the sweep mark.
  - **Try it:** a slider per referenced signal (range from the catalogue) and the result, evaluated in JS. A "warming" note appears when windows are involved.
  - **On a recorded trace:** the result timeline (a pass/drop strip for guards, a line for values) over the selected replay. The server's Kotlin evaluator computes it (the authority). The JS evaluation of the same feature stream is shown only in DEV builds as a parity check, and any mismatch is a bug report.
  - **Canonical form:** on blur, the text is reprinted from the AST (aliases become CEL operators, spacing is normalised), so round-trips are stable.
- **Server authority:** a save goes through `/api/expr/check` (Kotlin), and its result is the one the validator uses. The JS check is only a fast hint, as for params (slice 3 §4.2).
- **Promotion (S6a b) later:** the helper's translator emits pcx text and goes through the same check.

---

## 9. Security
- **No code execution anywhere:** the text is parsed into data, and the data is interpreted by a closed `when` over a fixed op set. There is no reflection, `eval`, `new Function`, dynamic dispatch on user input, property lookup by user string, or prototype access (the JS evaluator looks signals up in a `Map` built from the catalogue, never `obj[name]` on a plain object, which rules out the expr-eval class of bugs).
- **Bounded inputs:** the text length, node count, depth (the parser's depth counter), list sizes, table rows and the plan bytes are all capped. The parser is fuzzed for crashes and timeouts (§10.3).
- **Glasses trust nothing:** structural re-validation (§7), and the invariants in §3 of slice 3 apply after every value (clamps, the WALKING/UNKNOWN rows, `capPerMin` ≥ 1, gaps 5–60 s, the reserved timer).
- **No exfiltration path:** outputs are one bool (drop or pass) or one clamped number into a fixed field. Programs cannot read location, text or images, cannot emit events, and cannot reach the network.
- **The canvas scan rules stay:** `expr.js` and the CodeMirror mode pass slice 2's own-code scans (no `innerHTML`, no `eval`).

---

## 10. Testing (shared golden vectors)

### 10.1 Where the vectors live
- **Location:** `android-companion/core/capture/src/test/resources/pcx/`, one JSON file per family. They are read by the Kotlin tests in `:core:capture` and by the differential test in `:phone` (which has GraalJS, slice 2 S3) through a shared test fixture path.
- **Families:**

  | File | Each case |
  |---|---|
  | `parse.json` | text → canonical AST, or `{error: code, at: offset}` |
  | `types.json` | AST + role → OK, or a type problem code |
  | `eval.json` | AST + one input frame (signal values, staleness) → result bits (hex of the double) or `ERR` |
  | `windows.json` | a program + a sequence of 1 Hz rows and ticks (up to 900 s) → the result per tick |
  | `limits.json` | programs at and over each limit → the cost, the bytes, `expr_limit` |
  | `plans.json` | a pipeline fragment → the exact `exprs`/`tables` JSON and the plan hash (Kotlin only) |

- **Hand-written seeds** (about 150 cases): every operator, every error-absorption corner, `-0.0`, `x/0`, NaN inputs, stale signals, warm-up, the 300 s boundary, ring wrap-around, the deque max under ties, enum lists, aliases, and the user's example.

### 10.2 Differential test (the core of R6)
`PcxDifferentialTest` (JUnit in `:phone`, GraalJS 23.1.x interpreter mode):
1. loads `canvas/expr.js` into a GraalJS context;
2. for every golden case, runs the Kotlin evaluator and the JS evaluator **in the same process** and compares `doubleToRawLongBits` after canonicalisation, plus the error flags;
3. **seeded fuzz:** generates 20,000 well-typed random programs within the limits (a fixed seed in CI, plus a nightly random seed whose failures are written back as new golden cases) and random input streams, including NaN/Inf/±0, huge and tiny values, and stale flags, and compares every tick;
4. **parser fuzz:** 5,000 random and mutated strings, which must give the same accept/reject and the same AST on both sides, with no exception other than a parse error, in under 1 ms each.

### 10.3 Kotlin-only tests (`:core:capture`)
- the post-order bytecode evaluator equals a reference tree-walker on all vectors (it guards the X6 optimisation);
- zero allocation: a test runs 10⁵ evaluations and asserts no allocation through a counting allocator hook, or at minimum a heap delta of 0 after warm-up;
- max-cost timing on the JVM as a smoke test (the real number comes from the glasses);
- the validator: limits, roles (a guard returning `num`, a value targeting WALKING), the X5 warning, signal-DAG cycles;
- replay parity (S29) with a guard and a windowed value, including a trace that starts mid-plan (the ring snapshot).

### 10.4 The CEL oracle (`testImplementation("dev.cel:cel:0.14.0")`, JVM only)
For fuzzed programs in the **CEL-compatible fragment** (numbers, bools, comparisons, `&& || !`, `?:`; no enums, windows, lookups, `x/0` or NaN inputs), parse the canonical text with cel-java (double-typed variables) and compare results. This checks that our precedence and our commutative error logic really are CEL's. About 1 agent-h, and it can be dropped if the dependency gives trouble in the test classpath.

### 10.5 On the device (the checklist)
- the max-cost plan on the glasses: `exprUs` per minute and no GC events in logcat during evaluation (use the extended logcat filter; never print AssistServer lines);
- SHADOW would-drop counts within ±5 % of the exact replay of the same trace;
- a forced `exprErr` storm → auto-revert `auto:exprErr`;
- old glasses without the `expr` cap → `unsupported:expr`;
- the 1 h worn A/B (6 max-cost programs in SHADOW vs the identity plan), reported against 0.42 %/min.

---

## 11. What pcx v1 does not do
- No phone-domain (RT_PHONE) programs. Fusion stays param-only, and the walking veto is not editable (S32). See Open question 6.
- No ASYNC programs. Slice 2's interpreter has its own `route.switch`. A later `expr.route` could reuse the language on extraction fields, but it is not in v1.
- No user-defined functions, `let` bindings or macros. `expr.signal` is the only reuse mechanism.
- No per-sample (50 Hz) programs. Per-sample logic is tier 3 → promote (S1).
- No trigger or capture-causing role (§5.4).

---

## 12. Task breakdown (agent-hours; Opus for the spec and reviews, Sonnet for code)
| # | Task | Depends on | Stream | agent-h |
|---|---|---|---|---|
| E0 | C0 brainstorm with the user on this proposal + Codex's; Opus folds the rulings into slice 3 §4.3; Codex review | user session | — | 2 (+ user time) |
| E1 | The language reference + **the hand-written golden vectors** (`pcx/*.json`, ~150 cases) + the signal catalogue `sigSchema 1` | E0 | A (Opus) | 2.5 |
| E2 | Kotlin: lexer, parser (aliases, depth counter), type checker, canonical printer, limits and cost, `/api/expr/check` | E1 | A | 3 |
| E3 | Kotlin: the reference tree-walker, the post-order bytecode evaluator, quantised rings and deques, staleness and defaults, the zero-allocation test | E1 | A′ | 4 |
| E4 | JS `canvas/expr.js`: parser, type checker, printer, evaluator, rings (pure, GraalJS tests) | E1 | B | 3 |
| E5 | `PcxDifferentialTest` (goldens + seeded fuzz + parser fuzz) and the cel-java oracle | E2, E3, E4 | A | 2 |
| E6 | `PlanCompiler`/`PlanValidator`: `exprs`, `tables`, param substitution, `exprBytes`, the role rules, the X5 warning; plan goldens | E2, slice 3 A4 | A | 3 |
| E7 | Glasses: structural re-validate, the bytecode at apply, ticks (guard and value hooks in the existing gate path), rings fed from `SecondFeatures`, `exprErr` and auto-revert, the caps, the heartbeat `exprUs`/`exprErr`, the ring snapshot in the trace manifest | E3, E6, slice 3 A5 | C | 4 |
| E8 | `CaptureSimulator` hooks (guards and values in exact and counterfactual replay), expression constants as sweep params | E3, E6, slice 3 A10/A11 | A | 2 |
| E9 | Inspector UX: the three palette nodes, the CodeMirror pcx mode, live check, meters, params, "try it", the trace timeline | E4, E6, slice 3 A14 | B | 4 |
| E10 | Device checklist §10.5 | E7–E9 | — | 2 (+ 1 h worn) |

**Total: about 31.5 agent-h.** Slice 3's C0–C3 budget is 15 h. The difference is the differential fuzz harness (E5), the second evaluator in JS (E4), the zero-allocation bytecode path (part of E3) and the battery A/B (E10), each of which carries one of R5–R6.
**Critical path:** E0 → E1 → E3 → E5 → E7 → E10, about 16.5 agent-h. E2, E3 and E4 run in parallel after E1 (3 streams), so the work takes **about 18 h of wall-clock**. A **lean cut** (drop the JS evaluator and use server-side "try it" only, no cel-java oracle, a tree-walker without bytecode) is about **20 agent-h**, but then R6 holds only trivially (there is one evaluator) and R5 is unmeasured.

---

## 13. Risks
| Risk | Likelihood | Mitigation |
|---|---|---|
| Kotlin and JS disagree in a corner (rounding, `-0`, NaN, `Math.round` of halves) | medium, early | X2's op set, X4's integer rings, the bit-level differential fuzz, nightly seeds promoted to goldens; **X8** fallback: Kotlin Multiplatform for the evaluator |
| The 4 KB plan cap is too tight once `exprs` are in | medium | `exprBytes` shown live; prefix arrays; at most 6 programs. Open question 4 offers 8 KB if BLE message limits allow |
| Replay parity breaks on traces that start mid-plan | medium | the ring snapshot in the trace start snapshot (§5.3), with a test |
| The user wants more (strings, loops, `pow`, phone rules) | high over time | X1 keeps a CEL-shaped surface, so extensions remain familiar; each addition goes through golden vectors first. Tier 3 (`py.fn` → promotion) covers the rest |
| X5 (ctx in guards) erodes the walking promise in practice | low–medium | the G1 reserved timer, the warning, SHADOW counts per state before LIVE |
| Features on the glasses are not exactly what the catalogue says (units, names, staleness) | medium | E1 reads the A3 code before writing the catalogue; catalogue goldens use real trace rows |
| CPU cost is higher than estimated on the real glasses CPU | low | X6 constant time, `exprUs` in the heartbeat, the A/B in E10; the plan limit can be lowered without a language change |
| cel-java pulls conflicting Guava or protobuf into the JVM test classpath | low | test-only in `:core:capture`; drop §10.4 if it clashes |

Ruling X8: **if the differential fuzz finds more than 2 genuine divergences after E5 is green,** the evaluator moves to a Kotlin Multiplatform module whose JS output is vendored into the canvas and hashed in `vendor-lock.txt`. The language, the goldens and the plan encoding stay the same.

Ruling X9: **the language is versioned** by `sigSchema` (signals) and `pcx` (ops) in `caps`. An op or signal is never redefined, only added, so frozen plans keep their meaning (as S41 does for node types).

---

## 14. Rulings summary
| Ruling | Decision |
|---|---|
| X1 | Custom "pcx" with CEL-subset syntax; cel-java test-only oracle; no shipped library |
| X2 | `num`/`bool`/`enum`/`dur` only; no ints, strings, `pow` or transcendental functions |
| X3 | Static types; NaN-as-error with CEL's commutative logic; guard error keeps, value error defaults |
| X4 | Windows over quantised integer rings (exact O(1) sums), with warm-up defaults and a snapshot |
| X5 | Guards may read `ctx` (**a change to S5; needs a yes**) |
| X6 | Post-order bytecode, preallocated stack, constant time, no allocation |
| X7 | The plan carries the AST; the glasses have no parser and re-validate structurally |
| X8 | Fallback to Kotlin Multiplatform if JS/Kotlin divergences persist |
| X9 | Additive versioning by `sigSchema` and `pcx` caps |
| (unchanged) | S31 G1 reserved timer, S32 locked rows, S33 limits (tightened in §4.4), S27 frozen plans, the 4 KB cap |

---

## 15. Open questions for the user
1. **Ctx in guards (X5):** may a custom condition read the context (`ctx in (WALKING, UNKNOWN)`) and drop glances based on it, given that the 60 s timer still runs? Or should context stay in rate rules only (today's S5)?
2. **Syntax:** keep `and/or/not` as aliases that the editor rewrites to `&& || !` on blur, or keep your words exactly as typed?
3. **JS evaluator:** is instant "try it" in the browser worth the second evaluator and the differential tests (about +5 h), or is a server-side preview (one evaluator, a ~50 ms round trip) enough?
4. **Plan size:** keep the 4 KB cap (about 6 small programs), or raise it to 8 KB if the glasses link allows?
5. **Signals:** is the v1 list in §5.1 (A3's per-second features, glance and keep features, ctx) enough, or do you want a new glasses-side signal (each one is a Kotlin change and a battery check)?
6. **Phone rules:** should v1 also run programs on the phone (for example over speed and hub evidence), or stay on the glasses until the week of data shows a need?
7. **Window length:** is 300 s (5 min) enough, or do you want longer windows such as "walked in the last 15 min" (more memory, coarser slots)?
8. **Division by zero:** keep `x / 0 = 0` (quiet), or make it an error that counts toward auto-revert (loud)?

---

## Sources (checked 2026-10-22)
- cel-java on Maven Central, `dev.cel:cel` 0.14.0 and `runtime-android` 0.14.0 (dependencies from its POM): https://repo1.maven.org/maven2/dev/cel/ , https://repo1.maven.org/maven2/dev/cel/runtime-android/0.14.0/runtime-android-0.14.0.pom , README https://github.com/google/cel-java
- `@marcbachmann/cel-js` (MIT, BigInt ints, structural limits): https://github.com/marcbachmann/cel-js
- cel-es / `@bufbuild/cel` (Apache-2.0, beta): https://github.com/hudlow/cel-es , https://cdn.jsdelivr.net/npm/@bufbuild/cel-spec@0.4.0/README.md
- ChromeGG `cel-js` 0.6.0: https://www.npmjs.com/package/cel-js
- JsonLogic: https://npmjs.com/package/json-logic-js , https://github.com/jamsesso/json-logic-java
- expr-eval CVE-2025-13204: https://nvd.nist.gov/vuln/detail/CVE-2025-13204
- mathjs (Apache-2.0, size): https://mathjs.org/download.html , https://mcp.depscope.dev/pkg/npm/mathjs
- Starlark implementations: https://pkg.go.dev/github.com/google/starlark-go
