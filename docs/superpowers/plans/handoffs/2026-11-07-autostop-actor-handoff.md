# AutoStop / watchdog actor handoff (2026-11-07)

Branch `feat/v099-watch`, worktree `pca-v098`. This answers Codex review 4 (five P2s, no P1). The four code findings shared one cause: AutoStop and watchdog state was changed from several threads and entry points, and saved around start/stop under ad-hoc locks. That locking is gone. The state now lives in one serialised state machine. Frozen values are unchanged: PROTOCOL_VERSION 1, TRACE_SCHEMA 1, plan hash f5782e0d8610, Room v15.

## 1. The engine (`phone/hub/SerialStateMachine.kt`, the one shared file)

`SerialStateMachine<S, E, F>` takes `load`, a pure `reduce(state, event, now) -> Transition(state', effects, recheck?, reply?)`, `persisted(S)`, `save(S)`, `now`, `executor`, `prepare` and `run`.

- `submit(e)` never blocks and works from any thread. It queues the event and starts a drain on `executor`. An atomic flag allows one drainer at a time, so the executor may be multi-threaded. Production uses `Dispatchers.IO.asExecutor()`; tests use `SerialStateMachine.DIRECT`.
- A step runs in this order:
  1. `prepare` fills inputs the event is missing (cheap in-memory reads, on the serial context).
  2. `reduce` computes the next state.
  3. If the persisted part changed, `save` commits the whole snapshot. The new state becomes visible only after that commit.
  4. `run` executes the effects. An effect may return a feedback event, which is reduced next, ahead of any queued event.
  5. A `recheck` goes to the tail of the queue.
- A failed save keeps the old state and runs no effect.
- `load` runs on the first step, on the executor, so the main thread does no disk read or write.
- The submitter's deferred completes with the reply of its whole chain, feedback and recheck included.

## 2. AutoStop (`AutoStopMachine.kt` reducer; `AutoStopEngine` and the effect runner in `AutoStopController.kt`)

**State** (`AutoStopState`):
- `saved: AutoStopSnapshot` is the persisted part. It is one record in prefs file `hub_auto_stop`, using the same keys as before and written in one commit. The fields are manualStop, autoStoppedAtMs, reason, awaySinceMs, wakeStep, nextWakeAtMs and wakeProbe.
- In memory only: the latest `WakeInputs` (exempt, minutes, linkAllowed, serviceRunning), the latest `HubSignals` (hubRunning, link, worn, inFront, lastFrontMs, busy, runActive), `awaySince`, `pendingStop` (reason, since, at, the previous nextWakeAt) and `claim` (via, at, controller?).

**Events** (`AutoStopEvent`):
- `Evaluate`: the controller loop submits it on a poke (link or wear change, app in front or left, a setting change) or when its timer fires. Inputs are sampled in `prepare`.
- `ConfirmStop`: the recheck. It is sampled again.
- `ProcessStarted(via)`: from `Application.onCreate` and the WorkManager worker.
- `WakeResult(issued, serviceRunning)`: feedback from a start.
- `UserStarted`: `ensureRunning(force)`.
- `ManualStopped`: the service's `onDestroy` when it did not expect the stop.

**Effects:** `Note`, `StopHub(reason)` (which logs the `auto_stop_*` token), `StartWake(via, controller)`, `ScheduleWake(ms, controller)`, and `Log(line, via)`.

**Replies:** `AutoStopSleep(ms?)` for the controller and `AutoStopClaimed(Claim)` for a process start.

**Transitions:**
- **Running and away, policy says stop:** the reducer commits the mark (autoStoppedAt, reason, since, next rung), sets `pendingStop`, and queues `ConfirmStop` behind everything that arrived during the commit.
- **`ConfirmStop`:** it reruns `BackgroundPolicy.autoStop` on the latest inputs.
  - Still stop: `StopHub`, then `ScheduleWake(rung)`, and the probe is cleared.
  - Otherwise (in front, busy, run, present, hub not running): the stop is cancelled and the mark is reverted. **This fixes finding 1.** Any `Evaluate` processed while the stop is pending, and whose decision is "no stop", also cancels it.
- **Present:** clears the away window and the probe, and resets the ladder.
- **Hub stopped and auto-stopped:**
  - If the wake is due, the claim runs inside the reducer: it checks the stop, consumes the rung, sets the probe, and emits `StartWake` after that commit.
  - `WakeResult` settles the claim as `STARTED`, `ALREADY_RUNNING` or `REFUSED`. On `REFUSED` the probe is cleared and the next rung is scheduled.
  - The controller and the process start continue as before: sleep or re-arm, or schedule the remaining time.
- **Single wake owner:** the worker, `onCreate` and the controller only submit events to the one engine, `AutoStopWake.engine(app)`. A second claimant finds the stop cleared (`NONE`) or the deadline moved (`NOT_DUE`).
- **`UserStarted`:** a reducer step that clears the manual stop, the auto stop, the ladder, the probe and any pending stop. `WakeResult` only ever clears the probe, so no later step can restore it. **This fixes finding 2.**
- **Cold start:** `ContextApp.onCreate` calls `AutoStopWake.processStarted(app)`, which only submits `ProcessStarted`. The worker uses `onProcessStart(app)`, which submits the same event and waits up to 60 s. **This fixes finding 4.**
- **What other code sees:** `BackgroundInputs.needs` reads `engine.visible()`, where a stop still being committed does not count as stopped. Before the engine's first step it falls back to the stored record.
- **Hub-bound effects:** they go through the attached `HubPort`. The note is posted back to the hub scope.

## 3. Watchdog ladder (`WatchdogLadder` in `GlassesWatchdog.kt`, same engine)

- **State:** `saved: HubRestartSnapshot` (hub back-off plus last start, one record in `hub_restart`: step, next_at and last_start in one commit) and `reconnect` back-off (memory only).
- **Events:**
  - `PositiveSignal` resets both ladders and keeps the last start.
  - `HubWanted` and `HubRunning` reset the hub ladder.
  - `GlassesPresent` resets the reconnect ladder.
  - `TryHubStart(at)` replies true when allowed (back-off due and `MIN_HUB_START_MS`) and records the attempt.
  - `TryReconnect(at)` does the same for reconnects.
- **The tick:** it awaits `TryHubStart` and starts the hub only after the commit returned, on its own thread. The tick mutex now only guards the tick's own log and baseline fields.
- **Finding 3:** every save writes the latest snapshot. A reset submitted during a delayed save is reduced after it and saved after it, so the persisted and in-memory ladders cannot diverge.

## 4. Docs (finding 5)

`code-agent/releases/pca-v0.9.8-beta.md`, Known limitations: "the Home chat has no glasses path" is replaced with what slice E shipped (v098e handoff §2). Typed answers reach the HUD through `DeliveryRoute` when the glasses are connected and the display is on; otherwise they go to the phone with the reason recorded. A chat Query with target phone no longer mirrors. Queued and other sources still record no glasses path.

## 5. Tests

- **Adapted, same assertions unless noted:**
  - `AutoStopControllerTest`: the commit test now asserts that the save happens before the stop or start and that nothing runs on the caller thread.
  - `AutoStopWakeTest`: the race tests now use the shared actor. Its expectations moved in two places:
    - When the worker wins, the controller queued behind it now sees the woken hub and replies with the 5 min probe, instead of `BUSY_RECHECK`.
    - A thread waiting in the queue is detected as parked, no longer as blocked on a lock.
  - `GlassesWatchdogTest`: the stores implement `load` / `save`.
- **New, `AutoStopActorTest`:**
  - 150 seeds × 3 threads × 40 random events (a fake clock, a 4-thread executor). The trace folds to every recorded state and to the final state. Persisted equals in-memory after every step. No `StopHub` runs against the latest inputs, and no stop is left half-committed. The test also asserts that real stops and cancelled stops both happened.
  - Reproductions of finding 1: a foreground event during the mark commit; busy changing with no event; and a control case where nothing changes and the stop runs.
  - Finding 2: a user start during an issued claim and during a refused one.
  - Finding 4: no load or save on the submitting thread.
  - Finding 3: a positive signal during a delayed hub-start save.
  - 100 seeds of watchdog-ladder interleavings with replay and persisted == in-memory.
- **Full `:phone:testDebugUnitTest` run twice:** see the commit message.

## 6. Codex review 5 (2026-11-08): five P2s, each first reproduced by a test, then fixed in the same design

Tests: `AutoStopReview5Test`. With the fixes temporarily reverted, 9 of its 12 tests failed:
- the cold-resume test passed, because that reverted build did not revert the resume path;
- the engine-load test passed, because it tests the engine only;
- the `ContextApp` scan passed, because it reads source the reverts did not touch. Run against HEAD's `ContextApp` it finds 6 eager store loads.

1. **The confirm commits nothing.**
   - The mark now carries the whole persisted outcome of the stop: the reason, the since time, the next rung, and the probe used up. `PendingStop` remembers the previous `nextWakeAt` and probe, so a cancel can revert them.
   - `ConfirmStop` emits `StopHub` only when the pure decision, over inputs sampled for that very event (after everything queued during the mark's commit), still says stop with the marked reason, and nothing persisted changes. So no commit can separate the decision from the stop.
   - A changed reason is re-marked and confirmed again.
   - A `UserStarted` or a foreground return queued during the commit cancels the stop.
2. **`UserStarted` resets `awaySince`** (to unknown, so the next look starts the away window). The full N applies after the front hold.
3. **A failed `commit()` is not durable.**
   - `save` returns Boolean for both stores. In the engine, a failed save drops the transition: its state is not published and its effects never run. The failure becomes an event, `PersistFailed(event, attempt)`, reduced against the unchanged state, which may not change the persisted part.
   - Some events are retried on 250 ms, 1 s, 4 s, 15 s and 60 s (bounded): UserStarted, ManualStopped, HubForeground, WakeResult, ProcessStarted, and ConfirmStop (re-sampled). An evaluation is not retried; the controller's 60 s recheck covers it.
   - `PersistRecovered` follows the next successful save.
   - Safe behaviour while saves fail:
     - a stop needs a durable mark, so the hub is never stopped;
     - `visible()` reports neither stop flag;
     - the watchdog still starts a down hub, unsaved, at most once per `MIN_HUB_START_MS` (tracked in memory);
     - `persist_failed` is logged at most once a minute.
4. **An issued wake is not a started hub.**
   - `AutoStopSnapshot.wakeStartedAtMs` (prefs key `wake_started_at`) keeps the wake in progress. The hub still counts as auto-stopped, but `visible()` says keep running.
   - It ends on `HubForeground`, which `ContextHubService` submits after it enters the foreground, or when the controller sees the hub running.
   - It otherwise ends after `WAKE_START_TIMEOUT_MS` (2 min): no probe, still stopped, and the next rung (already set by the claim) is scheduled.
   - The issued start schedules a recovery wake at the timeout. A cold `ProcessStarted` with a wake in progress schedules recovery for the time left, or times it out at once.
   - Changed test expectations: an issued cold wake now also schedules the 2 min recovery (3 `AutoStopWakeTest` cases).
5. **No main-thread preference loads at startup.**
   - `ContextApp`'s providers read their stores lazily, and a `settings-warm` thread loads them.
   - `BackgroundInputs` uses `engine.visible()`. Before the actor has loaded, it uses the safe defaults (not stopped) and calls `engine.warm()`, which loads on IO. Otherwise there is no behaviour change.

In `ContextApp`, the timing and Developer-mode stores are local `by lazy` delegates, so `ForgetScopeWiringTest`'s source scan still finds its bindings. Final full `:phone:testDebugUnitTest`: 2172/2172 passed.

## 7. Codex review 6 (2026-11-08): 1 P1 and 1 P2 in the retry path, both reproduced first

Tests: `AutoStopReview6Test`. Against `e6fee700` 4 of its 5 tests failed: both P1 orderings, the folded-start case, and the 1 s second start. The two-failed-intents case already passed there.

- **P1: a delayed retry no longer overrides a newer action.**
  - Engine: every step that changes the persisted part takes the next `changeSeq`. A retry job carries the sequence of the change it retries. It is dropped, unapplied, when a newer change has been attempted.
  - Reducer: a user intent (`UserStarted` / `ManualStopped`) whose save failed becomes `pendingIntent`, and a newer intent replaces it. It is folded into the next snapshot written by any event, or by a `RetryPersist` (a no-op event). Retries never replay an intent event.
  - So only the latest snapshot is ever retried or written, and it carries the newest intent.
  - `visible()` shows the folded state. While saves fail, no auto stop is reported, but the manual stop follows the newest intent (the earlier "keep running" manual default is gone).
- **P2: `PersistRecovered` keeps the watchdog's `unsavedStartAt`.** The 60 s minimum counts from max(persisted, unsaved). The unsaved time is cleared only when a start is recorded durably.

## 7. Open points

- The watchdog's reconnect, probe and relaunch hooks still run on the tick's thread (Main), by design: the hub hooks expect it. Only the ladder moved into the actor.
- `ensureRunning(force)` submits `UserStarted` asynchronously. A non-force `hubNeeded` read in the few ms before that step can still see the old stop flags, as it could before with `apply()`.
