# 3c E2a windows and value programs review

Reviewer: Codex gpt-6.1-sol (read-only, medium). Scope: diff 4452c1a6..HEAD (WindowRing, window terms, replay parity, expr.value).
Outcome: NOT READY (2 Important: glasses-side program limits, replay value ticks); fixed in ad068120, not re-reviewed

- **Important — device cost limits bypassed:** [PlanValidator.kt:62](/Users/<user>/Desktop/rokid/pca-v094m/android-companion/core/capture/src/main/kotlin/com/rokid/context/capture/plan/PlanValidator.kt:62) checks program count and targets, but never node count or evaluation cost. A correctly hashed sigSchema 2 plan containing one value program with 200 constant steps fits under 8192 bytes, is accepted, and executes every second—exceeding both the 32-node and 64-cost limits without triggering auto-revert.

- **Important — replay freezes value overrides between events:** [CaptureSimulator.kt:368](/Users/<user>/Desktop/rokid/pca-v094m/android-companion/core/capture/src/main/kotlin/com/rokid/context/capture/replay/CaptureSimulator.kt:368) evaluates values only when accepting trace events, after advancing scheduler time. With REST `settledGapS = ctxAgeS` and no events between seconds 10 and 120, replay schedules that interval using the held 10-second gap. The device updates it every second, producing different capture attempts.

VERDICT NOT READY