# WP5 review 1 (HelperJobService), commit 21b630b4 vs base 65b530ef

Reviewer: Opus, standing in for the Codex reviewer (read only). I read the real files in /Users/debasishkanhar/Desktop/rokid/pca-3b-wp5: the service, the store, readiness, the port and the fake, the WP3 DAO (`CanvasSlice3Dao.kt` 283-562), the four test files, the WP4 branch diff (3b/wp4, 2483746a, which is not an ancestor of 21b630b4: per section 7 it merges after WP5) and the WP11 helper `supervisor.py` result path.

What I checked and found sound:
- The port is fully implemented. The contract really is shared: one abstract `HelperJobPortContract` with 19 cases, the same assertions, and two harnesses (`FakePortHarness`, `RealPortHarness`).
- The three waits are separate:
  - confirmation: `confirmExpiresAtMs`, counted from submit;
  - queue: `confirmedAtMs + QUEUE_WAIT_MS`, in `expireQueuedHelperJobs` and again at the top of `leaseHelperJob`;
  - execution: `deadlineMs`, set at lease.
- The 135 s backstop in `settle` cannot fire, because `sweep` runs `expireLeases` at the 130 s deadline before `settleAll`. I accept this: it is a harmless defence in depth.
- Cancellation runs under `NonCancellable`, and `store.cancel` closes any open state with a cleanup row.
- `waiters.remove(jobId, w)` guarantees at most one completion.
- Payload release, heartbeat and commit denials go through the fenced DAO, and a moved fence voids the job.
- `resultHash` is computed on the phone as `BundleHash.ofCanonical(canonical(result))`. The WP11 helper (`supervisor.py` `_post_result`) sends `protocol.bundle_hash(result)` over the same canonical form, and both sides refuse floats (hash-vectors). So the two sides agree.
- A lease not renewed for 10 s gives `lease_expired`, which the service maps to `Failed("killed")`. "killed" is in the fail enum, so this is acceptable.
- Readiness is stale at >= 15000 ms, which matches "stale after 15 s".
- Module direction: orchestration depends only on ai, capture, memory and connectors. There is no `:phone` import, no `android.util.Log`, and log lines carry counts only.
- No schema or DAO file is touched: the diff contains only the three orchestration main files and the tests.

## Findings

1. **Critical: a concurrent sweep can complete a waiter `Voided` before its job row exists, which leaves a live, confirmable and leasable job whose run has already returned.**
   - **Where:** `HelperJobService.kt` lines 84-88 and 223.
   - **Cause:** `runPy` registers the waiter (`waiters[id] = waiter`, line 85) before `store.insert(...)` (line 88) commits. Any concurrent `sweep()`/`settleAll()` in that window reads `store.job(id) == null`. Concurrent callers include another run's 250 ms poll, `pendingConfirmations()` from WP6's 1 s card refresh, `available()` from the 3 s helper poll, and `lease()`.
   - **Effect:** line 223 maps a null row to `PyOutcome.Voided`, and the waiter is removed and completed. The insert then commits an `AWAITING_CONFIRM` row that has no waiter:
     - `runPy` returns `Voided` at its first `await`, so WP8 fails the items as voided.
     - The native card still lists the job. A tap confirms it, the helper leases it and `releasePayload` hands the payload to the laptop, all for a run that already ended.
     - Because `runPy` has returned, cancelling the caller no longer cancels the job.
   - **Rule broken:** "waiters complete exactly once from the persisted row".
   - **Tests are exposed too:** the tests can hit the same race. `start()` in `HelperJobPortContract.kt` lines 34-43 and `HelperJobServiceTest.kt` lines 39-49 calls `pendingConfirmations()` (which sweeps) on the test thread while `runPy` runs on `Dispatchers.Default`. In-memory Room serialises the insert and the read on its executor in either order, so `confirmedJobCompletesThroughTheHelperAndRunPyReturnsDone` and others can intermittently get `Voided`.
   - **Edit (HelperJobService.kt):**
     - Move `waiters[id] = waiter` to just after a successful `store.insert(job, req.fence)`, then call `settle(id)` as now. A job closed between the insert and the registration is still picked up from its persisted terminal row.
     - Keep the `try`/`catch (CancellationException)` around the insert, so a cancellation that lands after the insert committed still runs `cancel(id)`.
     - On the refused-insert path, drop `waiters.remove(id)`.
   - **Regression test (HelperJobServiceTest.kt):** `aSweepDuringInsertDoesNotCompleteTheRun`. Use a `HelperJobStore` wrapper whose `insert` calls `svc.sweep()` before delegating, then assert that the run is still pending after insert and ends `Done` through confirm, lease and commit. With the current code it ends `Voided`.

2. **Minor: an exception other than cancellation inside `runPy` leaks the waiter and leaves the job open.**
   - **Where:** `HelperJobService.kt` lines 86-99.
   - **Scenario:** a store or SQLite error, or `HelperJobState.valueOf` on a corrupt row. The waiter stays in `waiters` and the row stays open until another sweep closes it.
   - **Edit:** add `catch (e: Throwable) { withContext(NonCancellable) { runCatching { cancel(id) }; waiters.remove(id) }; throw e }` after the cancellation catch, or a `finally { waiters.remove(id) }`.

3. **Minor: the service's session pre-check in `confirm` differs from the fake when the binding is also wrong.**
   - **Where:** `HelperJobService.kt` line 112.
   - **Scenario:** the session changed and the binding's hash or expiry is wrong but its `sessionGen` matches the row. The service voids the job and answers `STALE_FENCE`. The fake (`InMemoryHelperJobPort.kt` line 76) answers `MISMATCH` and leaves the job awaiting.
   - **Risk:** it fails closed, so it is not a security issue. It is only a contract wrinkle.
   - **Edit:** test the full binding first, i.e. use `binding == ConfirmBinding(row.jobId, row.bundleHash.orEmpty(), row.sessionGen, row.confirmExpiresAtMs ?: 0L)` in place of `binding.sessionGen == row.sessionGen`.
   - **Also:** move `aSessionChangeSinceSubmitRefusesTheTapAndVoids` into `HelperJobPortContract`, with a mutable `sessionGen` in both harnesses. Today the contract never exercises the session branch the fake has.

4. **Minor: after WP4 merges, a DONE job scrubbed by a frame Forget maps to `Failed("bad_output")` and not `Voided`.**
   - **Where:** `HelperJobService.kt` lines 236 and 249-250, together with WP4's `scrubDoneJobsForFrames`, which keeps the row DONE with `resultJson = NULL`.
   - **Scenario:** a commit is followed by a Forget before the waiter settles. The run reports `bad_output` for data that was forgotten. Nothing leaks: the WP8 fenced insert refuses it anyway.
   - **Edit:** in `outcomeOf`, map `DONE` with `resultJson == null && voidReason == "frame_forget"` to `PyOutcome.Voided`. Add a case once WP4 is merged.

5. **Minor: the fake does not model lease expiry, so the contract cannot pin `Failed("killed")`.**
   - **Where:** `InMemoryHelperJobPort.kt` line 108; the service maps it at `HelperJobService.kt` line 243.
   - **Edit:** none is required for WP5. Record in the WP8 handoff that `Failed("killed")` (helper silent for 10 s) is a possible outcome alongside `timeout` and `resources_busy`.

6. **Minor: the source-scan part of `confirmFromServiceHasNoAccessToKey` depends on the working directory.**
   - **Where:** `HelperJobServiceTest.kt` line 110, `File("src/main/java/...")`.
   - **Assessment:** Gradle and AGP unit tests run with the module directory as cwd, so it works there. It fails loudly (with an assert message) under another cwd, so it can never pass silently. That is acceptable.
   - **Optional edit:** resolve from `System.getProperty("user.dir")`, with a fallback to `android-companion/services/orchestration/...`.

7. **Minor: handoffs to record for WP6 (no WP5 code change).**
   - (a) `purgeOldCleanup` (30-day retention, `CanvasSlice3Dao`) has no caller. WP6 should call it from the session start or the dev-session tick.
   - (b) There is no ticker beyond `runPy`'s poll. WP6 must call `sweep()` after its deletion hooks and in `terminate`, and `pendingConfirmations()` already sweeps.
   - (c) `commitResult` takes no client `resultHash`. If WP6 wants to refuse a helper-claimed hash that disagrees, it must compare `BundleHash.of(result)` itself before calling. The plan does not require this.
   - (d) Browser submission goes through `POST /api/runs` → WP8 `PythonExecutor` → `runPy`. `/api/helper-jobs` should be read-only status, which resolves the plan conflict in WP6's favour.
   - (e) `PyOutcome.NoHelper` is unused by both the fake and the service. With no helper, a run ends `NotConfirmed` (120 s) or `resources_busy` (30 s after the tap). This is consistent across both, so leave it.

8. **Minor: the tests use real threads and polling.** `Dispatchers.Default` plus `delay(5)` loops with 10 s timeouts, in both test classes. The clock is fake, so time-based assertions are deterministic. The only real nondeterminism found is the race in finding 1, which its fix removes. I did not re-run the "39/99 red against a stub" claim or the nine mutations (no Gradle was allowed). The three plan-named mutations are each killed by the named test, from reading the code:
   - heartbeat renewing a CANCELLED job is killed by `heartbeatAfterCancelDenied`;
   - lease returning an AWAITING_CONFIRM job is killed by `unconfirmedJobNotInAvailableAndNeverLeased`;
   - a single timer from submit, i.e. `execWaitEnd` taken from `createdAt`, is killed by `confirmJustBeforeExpiryThenExecutionGetsFullRuntime` at lines 148-152.

VERDICT: NOT READY
