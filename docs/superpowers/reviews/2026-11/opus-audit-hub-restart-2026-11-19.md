# Hub restart: status audit of open Codex findings

Reviewer: Opus, low effort, standing in for Codex gpt-6.1-sol. Read-only static review, with no Gradle, emulator or device runs.
Tree: `feat/v101-ask-eval` @ `0550ee4f` (2026-11-19). Spec: `docs/superpowers/specs/2026-11-16-hub-restart-design.md`.

## Latest round per series

| Series | Latest round | P1 in latest round | Note |
|---|---|---|---|
| hub-wp1 | r1 | none (READY) | P2 is glasses diagnostics running on Main. It is not about privacy, calls, relaunch or battery, so it is out of scope. |
| hub-wp2 | r6 (`c022d601`) | none (READY, no P2/P3) | r1-r5 P1s were verified closed in r6, including the call guard and the 6/h durable reservation. |
| hub-wp3 | r1 (review of `0f71f5b8`) | none | 6 P2s, all in scope (calls, battery, relaunch). Fix commit `bddc0262`, with **no r2 review**. Each fix is audited below. |
| wear-v101 | r6 | none (READY) | - |
| idxbatt | r1 | none (READY) | P2s are not in scope (the brief asks only about open P1s). |
| v100-hud / v100-log / v100-r2 | r1 / r1 / r2 | P1s were raised (pca-v098 tree) | Closed: v100-r3 says "both r2 fixes verified", and v100-nowear says "generation fencing closes the HUD race". No P1 is still open. |
| askeval | r4 | none (READY) | The r3 P1 is marked fixed. |

## WP3 r1 P2s against the current tree

Files touched after `bddc0262`: none for AutoStop*, the receivers, ContextHubService, GlassesRelauncher or CxrGlassesLink. OrchestrationScheduler was touched by 2aa64e39, 88077023 and 0b29a621, none of which changes the cadence code.

| # | Finding | Status | Evidence (current tree) |
|---|---|---|---|
| 1 | A late reduce undoes the cadence restore | FIXED (P3 residual) | `OrchestrationScheduler.kt:278-279`: the reduce runs under `CADENCE_LOCK` and is voided if `cadenceEpoch()` changed. `ContextHubService.kt:330` bumps the epoch on every restore, and `:325` captures the epoch at the stop. Residual: the restore's `cadenceReduced()` check runs outside the lock (`ContextHubService.kt:331`). A restore that lands between the reduce's epoch check and its in-memory commit (a window of microseconds) returns early, leaving the jobs on the 60 min cadence until the next frame calls `HubGraph.kt:114`. That heals itself. Fix: drop the early return, or re-check inside the lock in `restorePeriodicJobs`. |
| 2 | Empty queues do not prove the Moments caught up | FIXED | `OrchestrationScheduler.kt:277,287`: `momentsCaughtUpNow()` flushes the trigger and rebuilds the current and previous hour (60 s timeout). If any build fails, the reduce is refused. |
| 3 | Name-based wake identity, and a stale address after re-pairing | FIXED (accepted residual) | `GlassesAclReceiver.kt:61` forgets the address when there are zero or several matches. `CxrGlassesLink.kt:128` forgets it on a new token, and `:317` removes it when the token is rejected. Identity is still taken from the name (the CXR SDK exposes no session address). A wrong match still goes only through the guarded, quota-bounded probe start. |
| 4 | Receivers call `finish()` before the work is done | FIXED | `AutoStopWake.kt:149-150` `ReceiverWork` runs inside `goAsync`, then awaits the engine's deferred (8 s cap) before `finish()`. `SerialStateMachine.submit` completes only after the effects run, StartWake included. Used at `HubBootReceiver.kt:16,30` and `GlassesAclReceiver.kt:25,42`. |
| 5 | An ACL connect during a call is dropped | FIXED | `AutoStopMachine.kt:265` keeps `nearbyPendingAtMs` and schedules a 15 s WorkManager recheck. `:234` retries it on the process start through the same guards, bounded by `NEARBY_PENDING_MS = 10 min` (`:111`). It is persisted in `AutoStopStore`. |
| 6 | The call check is not repeated after persistence | FIXED | `AutoStopController.kt:174`: a StartWake with `nearby` set re-reads `hubStart.callIdle()` (15 s settle included) right before the start. If a call has begun, it returns `NearbyCallBlocked`, which `AutoStopMachine.kt:131` defers rather than drops. |
| 7 (P3) | ACL prefs read on Main | FIXED | `GlassesAclReceiver.kt:25` moves the match and the prefs read inside the async section. |

## STILL OPEN, ranked

1. P3 only (not a blocker): the residual cadence race in #1. Failure: a hub start races a finishing idle reduce within microseconds, and the running hub keeps 60 min battery-guarded jobs until the next stored frame. Minimal fix: in `restorePeriodicJobsAsync`, always dispatch `restorePeriodicJobs()`, which checks under the lock. It can occur in the trip build (hub restart ON), but it heals at the first frame and nothing is lost.

Caveat: the `bddc0262` fixes have had no Codex r2. This audit is static and covers only the cited code paths.

**HUB: ALL P1 CLOSED**
