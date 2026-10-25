# Window tolerance review

Reviewer: Codex gpt-6.1-sol (read-only, medium). Scope: diff d34058e3..03188146 (percent-based window tolerance).
Outcome: NOT READY (2 Important in the replay lookup); fixed in 1f66be8e, not re-reviewed

- **Important — [ReplayCandidates.kt:22](android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/plan/ReplayCandidates.kt#L22):** A failed compile stops the tolerance search. Concrete case: four explicit 90% terms plus an implicit duplicate compile as four terms at global 90%. Change Settings to 60%: the duplicate becomes a fifth term, compilation fails, and `break` prevents trying 90%. The previously published hash becomes unresolvable. Continue to subsequent tolerances.

- **Important — [ReplayCandidates.kt:20](android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/plan/ReplayCandidates.kt#L20):** Pre-change window hashes cannot resolve. Every attempted recompile now writes explicit `mv` ([ExprProgram.kt:350](android-companion/services/ai/src/main/java/com/rokid/context/ai/canvas/plan/ExprProgram.kt#L350)); even `mv:100` hashes differently from an older strict plan with no `mv`. After upgrading, replay of that published plan is refused. Resolve its frozen compiled config or support legacy lowering.

Static review only; tests were not run in the read-only environment.

VERDICT NOT READY