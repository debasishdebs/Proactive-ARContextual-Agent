# Task 7 report: PlanCompiler

Status: DONE (route /api/plans/compile NOT added: CanvasApi/PlanRoutes belong to Task 22 per the Round 3 table and the task rules).

Files: services/ai/.../ai/canvas/plan/PlanCompiler.kt, test PlanCompilerTest (27 tests), resource test/resources/canvas/section1-capture-plan.golden.json (hash 5ca1e1c3c69f computed with python hashlib, independent of Kotlin; identity hash f5782e0d8610 re-derived the same way). One small edit in ParamSchema.kt: a null gap cell (blank = inherit) is accepted.

API: PlanCompiler.compile(pipeline, settingsRev = 0, profile = SETTLE/T0): CompileResult(plan, planJson, problems, warnings, staticCost). plan/planJson null iff problems. Output re-checked with PlanValidator.check.

Semantics chosen (flag for review):
- Missing optional nodes take identity values; a state with no row = identity row; in a PRESENT row an absent or null cap = null and an absent or null gap = 0 (S68 blank rule), forceSettle/nudges absent = identity row.
- Thresholds fold into glance (gyroPeak -> onsetRadS, boutMs -> minBoutMs, boutRad -> minBoutRad), with node-specific bounds; two thresholds on a feature, or a threshold vs an explicit head param, must agree.
- Chain rank head < threshold* < settle < novelty < policy < rate_cap < capture; branch = extra edge out of / into a chain node, reported as "unsupported wrapper order: a before b". ctx binding: signal.in ctx must feed policy only ("ctx binding must feed policy.capture_rate"), signal.out ctx must come from hub.context. ctxBound = signal.in ctx feeds policy.
- Compiler texts replace the validator's overlapping ones (operator, port mismatch on a refused chain edge, unreachable unbound signal).
- S68 warning compared with the SETTLE/T0 settings profile (10/30/10/60 s), overridable parameter.
- hub.context any param -> "hub fusion params arrive in slice 3a-LATER".

Mutation REDs: fold >= into onset -> gyroPeakGteRefused fails; warning `<` to `<=` -> gapBelowProfileWarns fails. Initial RED: stub compiler, 26 of 27 tests failed on assertions.

Tests: PlanCompilerTest 27/27; :services:ai canvas.* + CanonicalJsonParityTest green; :services:orchestration canvas.* green; :core:capture:test green.
