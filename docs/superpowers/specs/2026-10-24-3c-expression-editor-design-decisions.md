# 3c expression nodes: design decisions (2026-10-24)

Status: agreed with the user in the 2026-10-24 brainstorm. Builds on `plans/2026-10-22-canvas-slice3c.md` (section 10 of that plan carries the graph JSON). Where this differs from the plan's "one node with a form table of steps", THIS file wins. **Built** = on branch `v0.9.2`.

## 1. Intent
Let the user adjust how the glasses decide to take a picture, from the canvas editor, without writing code. First release: **guards only**. A guard is a yes/no rule evaluated when the glasses are about to take a picture; it can only **veto** (drop) that picture. It can never add or force one.

Non-goals today: value programs that tune capture rate (later), windowed signals (E2), detectors and recognition nodes, connectors, LIVE acting on the glasses.
Anti-goals: a guard that can open the camera or add a capture; a guard failure that silently drops pictures; any change to the sensing code beyond a plain once-a-second feed.

## 2. How it is edited (final, user-approved mockups)
- One canvas node `expr.guard` (built; palette-visible; **not liveable**: SHADOW/TRIAL only) with a `graph` parameter.
- **Outer level:** named **steps** drawn as boxes (the step name on a coloured pill). Boxes are wired box to box; the red arrows between boxes are the real connections. A step's inputs are signals (the seven motion features, `headSpeed` as an alias of `gyroMax`, `ctx`, `ctxAgeS`), constants, or earlier steps.
- **Inner level:** opening a step (double click, Enter or "Edit step") shows the small graph of catalogue nodes that computes the step (sig, const, cmp, arith, and/or/not, in, clamp, choose, fresh) with its wires; "Back" returns. A cross-step wire always connects boxes, never a node inside another step.
- A palette-added node starts with a valid default (one step `fast` = `headSpeed > 1.5` as the guard output).
- Wiring is by pickers (dropdowns) today, not drag-to-connect; this works with touch and keyboard. Live compile messages and meters (nodes of 32, cost, bytes of the 8 KB plan cap) appear after Compile. A loop is refused at edit time with a clear message; a JSON view with Apply is the fallback.

## 3. Semantics (built)
- Compile lowers the two-level graph to the plan's step list (`PlanConfig.exprs`, post-order, deterministic), `planSchema` 2 and `sigSchema` 1 only when a plan has expressions; schema-1 plans and their hash stay byte-identical; old glasses reject `unsupported:expr`.
- Limits: 32 nodes, 6 programs, cost up to 64 per program, plan up to 8192 bytes with expressions counted; step outputs are number or bool; a guard output must be bool.
- Errors by role: divide by zero is an ERROR; a guard error **keeps** the candidate; stale or missing signals read as UNAVAILABLE and keep it. Any ERROR/UNAVAILABLE input gives ERROR/UNAVAILABLE (strict, not Kleene).
- On the glasses a guard drops only on an available false. The reserved 60 s timer photo attempt and the walking veto run unchanged after the guard. SHADOW counts would-drops and never acts; replay runs the same runner and reports per-guard drops.
- Auto-revert: more than 10 non-divide-by-zero errors in 60 s reverts the running plan (every failing program counts).

## 4. What a guard may read
Only the seven per-second motion features and the context signals (`ctx`, `ctxAgeS`). Not available: time of day, battery, place, phone state, `ctxConf`, per-glance bout and keep signals (the plan allows them only if the motion code already computes them; it does not).

## 5. Later
- **Values (E2):** `expr.value` programs that tune only the capture cap and the gap seconds of non-locked rows (never WALKING/UNKNOWN), clamped; needs the row hook in the plan resolver and about three agent-hours.
- **Windows (C1b/X6):** "over the last N seconds" aggregates (1 to 300 s, at most 4 per program, full-horizon warm-up before an answer, then TOLERANT BY PERCENT, decided 2026-10-25: a window answers when at least `minValidPct` percent of its seconds had sensor data, 50 to 100, 100 = strict; 90% of 60 s is 54 s; max, mean and frac run over the valid seconds only and a gap is never a zero; the percent is per window term in the canvas editor (`mv`, empty = the global Settings value `window_min_valid_pct`, default 90) and part of the term identity and the plan hash). With windows an activity ("walking = mean stepHz over 10 s above 1.2") becomes an expression box, and the Scene boundary logic can use user-defined activities.
- **Try-it:** a phone-evaluated try-it with sliders (the same Kotlin evaluator, no JS evaluator).
- **LIVE:** a guard may act for real only after the battery work the plan names (LA7/LA9) and a worn device gate (X7: 35 minutes, SHADOW against the identity plan).
- **Detectors** (entry gate: 7 days, 20 hours of complete traces, 30 marks, a written list of wanted queries; tier (a) merged into the extract schema by default) and **connectors** (last).

## 6. Verification status
Unit, golden, mutation and replay tests cover the model, runner and editor under GraalVM; Codex phase review fixes are merged. **Not verified:** the editor in a real browser (layout, dark theme, tap sizes, React mounting), the worn device gate, the wiring of the post-veto context in `CaptureService` (no unit test).
