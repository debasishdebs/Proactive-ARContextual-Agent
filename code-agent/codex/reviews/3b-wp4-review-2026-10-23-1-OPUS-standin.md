# Review: 3b WP4, deletion integration and fences for helper jobs (commit 2483746a, diff df732807..HEAD, worktree pca-3b-wp4)

Scope: py_exec only, threat model A. I read the real files (CanvasSlice3Dao.kt, WipeDao.kt, HelperDeletion.kt, HelperJobState.kt, ForgetService.kt, CanvasSlice3Forget.kt, CanvasForgetCoordinator.kt, MemoryWipe.kt, HelperJobFixture.kt, all new and changed tests) as well as the diff. I modified nothing and ran nothing that writes.

## Checks against the questions asked

**Schema is frozen.** The diff touches 9 files. None of them is an entity, a migration, `DatabaseFactory`/`ContextDatabase` or a `schemas/**/8.json` file (`git diff --stat` on those paths is empty). No new column or table. Confirmed.

**(1) Transactionality.**
- `forgetRangeTx` is `@Transaction`. It calls `moveFrameForgetFence`, which does `bumpFrameForgetRev` (itself `@Transaction`) and then `voidAllHelperJobs` (`@Transaction`, through `closeById`, `closeJob`, `closeRow` and `insertCleanup`). All of this runs in the caller's transaction.
- `deleteSliceOutputsForFrames` (`@Transaction`) runs the same step and then `scrubDoneJobsForFrames`.
- The production callers are `CanvasSlice3Forget.forgetRange` (`db.withTransaction`) and `ForgetService.dropCanvasOutputs`, which is reached only from `forgetMomentTx`, `forgetFactsTx`, `expireTx` and `expirePhotosTx`, all inside `db.withTransaction`. Room's nested suspending transactions join the outer transaction on the same connection, so the inner calls cannot deadlock against it. Other DAO transactions (commit, heartbeat, lease, confirm) are serialised by SQLite's single writer.
- An exception in any inner step propagates and rolls everything back. `forgetBumpsRevisionInvalidatesJobAndInsertsCleanupInOneTransaction` and `faultInTheFrameDeletionStepAlsoRollsBack` prove this for the DAO-level transaction: revision, void, cleanup row and range fence all roll back together.
- `testFaultAfterFenceMove` has no production reference (grep: only CanvasSlice3Dao.kt:55/81 and HelperDeletionHooksTest). It is a `public` var, though (Minor 2).

**(2) Void ALL on any Forget.** This is consistent with the plan and with WP3, and is in fact the only consistent choice:
- Section 6 says "any Forget bumps `frame_forget_rev`".
- WP3's `rowFenceHolds` requires `frameForgetRev() == r.frameForgetRev`. So every open job that carries no forgotten frame is already dead at its next confirm, release, heartbeat or commit (voided with reason `fence`).
- Voiding them inside the Forget transaction only moves that death earlier and adds the cleanup row in the same transaction. This is what the WP4 GREEN criterion "every deletion cause voids open jobs and enqueues a cleanup row inside the deletion transaction" asks for.
- No contradiction with WP3, WP5 or WP6. The user impact is real (see Minor 3) but it is a product consequence of the global fence, not a WP4 defect.

**(3) DONE scrub.**
- `scrubDoneRow` nulls `resultJson`, `payloadJson` and `frameIdsJson` and keeps `resultHash`, `leaseOwner` and `leaseGen`.
- A retried identical commit therefore takes the DONE/duplicate branch. `rowFenceHolds` is false because the revision moved, so the answer is `voided` and never an ack. A retried commit with a different hash falls to `denialOf(DONE)`, which is `stale_generation` (409, the helper kills the job), and nothing is stored.
- A pure range Forget does not scrub DONE results. This is acceptable and not a leak:
  - `forgetRangeTx` tombstones traces only and never deletes a frame. Under Option 1 a helper job never names a trace.
  - `CanvasForgetCoordinator.forgetRange` deletes no photo either.
  - Every path that deletes a frame from a user or retention action (forgetMoment, forgetFacts with lockFrames, expire, expirePhotos) goes through `dropCanvasOutputs`, and so through `deleteSliceOutputsForFrames`, which scrubs.
  - The DONE result of a frame that still exists is not forgotten data.

**(4) HelperDeletion.**
- The hooks run first, then `voidAllHelperJobs`. A hook failure is rethrown after the void, and that path is tested.
- It is idempotent for jobs: a second call returns 0 and writes no new cleanup row.
- `HelperSessionHooks` (`grantsOff`, `revokeHelper`, both non-suspending, no DB access) is enough for WP6 to bind `HelperGrants.setGrant(py_exec,false)` and the `HelperAuth` revoke plus confirm-key drop.
- One fail-open on credentials: see Minor 1.

**(5) WipeDao.**
- The `'*'` row is written inside the `wipeMemory(now)` transaction, and the cleanup table is not deleted (WipeDaoTest and HelperJobDaoTest were updated).
- Repeat `'*'` rows are harmless: each one is an idempotent "discard everything" command, it is acked by id and it is purged after 30 days.
- No `frame_forget_rev` bump is needed. `MemoryWipe.deleteAll` commits the persisted epoch (`commitWipe`) and increments the in-process `generation` before it calls `wipeMemory`. So:
  - A job inserted before the wipe is deleted by the wipe; a later commit, heartbeat or release finds `null` and answers `not_found`, never ok.
  - A runner whose fence was captured before the wipe is refused at insert by the epoch or generation, and at confirm, release, heartbeat and commit by `rowFenceHolds`.
  - `canvas_meta` is kept, so the revision stays monotonic.

**(6) `voidHelperJobsForFrames` through `closeJob`.**
- The state machine allows VOID from AWAITING_CONFIRM, QUEUED and LEASED, and each void writes a cleanup row.
- The four changed assertions are justified, because each now reflects the global fence plus the wildcard cleanup row:
  - CanvasSlice3DaoTest j2 is VOIDED plus cleanup.
  - HelperJobDaoTest gains `"*"`.
  - WipeDaoTest has 2 cleanup rows.
  - ForgetCanvasTest: j-f2 is VOIDED, and the label of f2 is kept.
- The narrow function now has no production caller (Minor 5).

**(7) Test quality.**
- The tests use real entry points: `forgetRangeTx`, `ForgetService.forgetMoment`, `forgetFacts`, `expirePhotos`, `WipeDao.wipeMemory`, and a file DB reopen through `DatabaseFactory.build` in `cleanupSurvivesRestart`.
- Both plan mutations are killed:
  - Dropping the bump makes `forgetRacingResultCommit` (b) and (c) go green-to-red.
  - Excluding AWAITING_CONFIRM from `openHelperJobIds` makes `staleConfirmAfterForgetRefused` fail on its immediate VOIDED assertion.
- Count: HelperDeletionTest has 14 tests (not 15) and HelperDeletionHooksTest has 8, which gives the stated total of 22. By reading the pre-WP4 code, about 10 of the 14 would fail. `staleConfirmAfterForgetMomentRefused`, `staleConfirmAfterDeleteAllRefused`, `doneJobOfAnotherFrameKeepsItsResult` and `restartAbortsAnAwaitingJobAndEnqueuesCleanup` already pass on WP3 code. That is plausible and acceptable.
- There are no real-thread race tests. The races are covered as serialized interleavings, which is sound given SQLite's single writer (Minor 7).

## Findings

1. **Minor**: `HelperDeletion.endSession` fails open on the token when the grant hook throws.
   - **Where:** `android-companion/services/memory/src/main/java/com/rokid/context/memory/canvas/HelperDeletion.kt:60` and `:65-71`.
   - **What goes wrong:** `run(cause) { hooks.grantsOff(); hooks.revokeHelper() }` runs both hooks in one lambda.
   - **Scenario:** WP6's `terminate(LAN loss)`. The prefs write in `grantsOff` throws, so `revokeHelper` never runs. Jobs are voided, but the helper token and the in-memory confirm key stay valid. If the grant also stayed on, a new run can be confirmed and leased after the session "ended".
   - **Impact:** no forgotten data is affected, so this is not a blocker. It does contradict the WP6 contract that terminate always revokes the token.
   - Also, if `voidAllHelperJobs` throws, the hook failure is dropped silently.
   - **Edit:**
     ```kotlin
     suspend fun endSession(cause: HelperDeletionCause): Int = run(cause, { hooks.grantsOff() }, { hooks.revokeHelper() })
     private suspend fun run(cause: HelperDeletionCause, vararg steps: () -> Unit): Int {
         var failure: Throwable? = null
         for (s in steps) try { s() } catch (e: Throwable) { if (failure == null) failure = e else failure.addSuppressed(e) }
         val ended = try { dao.voidAllHelperJobs(cause.code, clock()) } catch (e: Throwable) { failure?.let(e::addSuppressed); throw e }
         failure?.let { throw it }
         return ended
     }
     ```
   - **Test:** add `endSessionRevokesEvenWhenGrantHookFails` in HelperDeletionHooksTest. Use `Recorder(failGrant = true)` and assert `calls == ["grantsOff","revokeHelper"]` and that all jobs are VOIDED.

2. **Minor**: The test seam is publicly settable from any module.
   - **Where:** `CanvasSlice3Dao.kt:55`, `@Volatile var testFaultAfterFenceMove`.
   - **Risk:** it is `public`, so any production code holding the DAO could install a hook that runs inside every Forget transaction. Nobody does today.
   - **Edit:** make it `@Volatile internal var testFaultAfterFenceMove` (the tests are in the same `:services:memory` module; this matches `MemoryWipe.afterEpochCommit`, which is `internal`). Optionally add a pin test that greps `src/main` for the name.

3. **Minor**: Any unrelated Forget or retention run cancels an in-flight experiment. This is by design and correct, but the user is not told.
   - **Where:** `CanvasSlice3Dao.kt:78-83`, `moveFrameForgetFence`.
   - **Scenario:** periodic retention (`expire`, `expirePhotos`) or forgetting one fact with a frame lock voids every AWAITING_CONFIRM, QUEUED and LEASED job, including a run over unrelated photos. The user sees the run end.
   - **Plan alignment:** this matches section 6 and is forced by WP3's equality fence (see (2)), so no code change in WP4.
   - **Edit for later WPs:** add one sentence to plan section 6, "Frame-Forget fence": "any Forget, including retention, voids every open job (`voidReason = frame_forget`)". WP5/WP8 should map `frame_forget` to a distinct user-visible run status ("stopped: memory was deleted") instead of a generic failure.

4. **Minor**: The DONE-scrub tests are weaker than they look.
   - **Where:** `android-companion/services/memory/src/test/java/com/rokid/context/memory/forget/HelperDeletionTest.kt:149`, `doneJobOfAnotherFrameKeepsItsResult`.
   - **Gap 1:** the test never asserts that job `a` (which carries `f-a`) was scrubbed. If `scrubDoneJobsForFrames` were a no-op the test would still pass. The real-entry-point test covers the scrub, but only for one row.
   - **Gap 2:** no test retries the identical commit on a scrubbed DONE row. The kept-`resultHash` behaviour is the claim from question (3).
   - **Edit:** after `service.forgetMoment("f-a")`, add:
     ```kotlin
     assertNull(dao.helperJob("a")!!.resultJson)
     assertEquals("[]", dao.helperJob("a")!!.frameIdsJson)
     assertEquals("voided", dao.commitHelperResultFenced("a", "h1", a.leaseGen, "rha", "{\"a\":1}", f.now, f.live).denial)
     assertTrue(dao.pendingCleanup(100).any { it.jobId == "a" && it.reason == "frame_forget" })
     ```

5. **Minor**: `voidHelperJobsForFrames` is now a production-dead, narrower void that does not move the fence.
   - **Where:** `CanvasSlice3Dao.kt:324-329`.
   - **Risk:** its only callers are tests. A later WP could call it as a "Forget hook" and skip the revision bump, which would break the invariant WP4 just established.
   - **Edit:** either delete it and its test use in HelperJobDaoTest, or rename it to `voidOpenJobsCarryingFramesForTest`, make it `internal`, and add to its KDoc: "never a deletion entry point: it does not bump `frame_forget_rev`; Forget paths call `moveFrameForgetFence`".

6. **Minor**: No fault-injection test goes through the outer `db.withTransaction` of a real ForgetService entry point.
   - **Where:** `HelperDeletionHooksTest.kt`, the fault tests.
   - **Gap:** the existing fault tests call the DAO `@Transaction` methods directly. Room nesting makes the outer rollback correct by construction, but the user-facing path (frame row, tombstone, memory cascade plus fence) is unproven.
   - **Edit:** add `faultInForgetMomentRollsBackFrameTombstoneAndFence`:
     - Setup: `frame("f-j1"); mem("m1","f-j1"); f.insert("j1")`.
     - Set the seam, call `service.forgetMoment("f-j1")` and expect it to throw.
     - Assert that the frame row is still present, `tombstonedAmong(["f-j1"]) == 0`, the revision is unchanged, j1 is still AWAITING_CONFIRM and no cleanup row was written.

7. **Minor**: There are no real-thread race tests (Forget concurrent with commit, heartbeat or confirm on two threads).
   - **Why it is not urgent:** SQLite's single writer serialises these, and the interleavings are tested sequentially, so it is not blocking.
   - **Edit (optional, RV1):** one Robolectric test on a file DB with two coroutines on `Dispatchers.IO`, looping N=50 times: `forgetRangeTx` against `commitHelperResultFenced`. Assert that no iteration ends with an ok commit whose row is VOIDED, or with a DONE row whose `frameForgetRev` is stale and that was acked after the bump.

8. **Minor (pre-existing, outside WP4's section-6 causes)**: two maintenance paths delete frame rows without moving the fence or scrubbing DONE results.
   - **Where:** `services/orchestration/.../PipelineRecords.kt:207` `deleteFrames` (purge of non-rokid frames) and `services/orchestration/.../data/frames/RokidFrameInbox.kt:181` `dropRows` (cancelled ingest).
   - **Risk:** a DONE helper result over such a frame would survive its deletion.
   - **Assessment:** these are developer and repair paths, not Forget, retention or Delete all, and the slice-2/3a canvas outputs already have the same gap. Not a WP4 blocker.
   - **Edit for RV1:** route `PipelineRecords.deleteFrames` through `db.canvasSlice3Dao().deleteSliceOutputsForFrames(ids, now)` inside a transaction, or record the gap in the backlog.

9. **Minor**: Repeated Delete all adds one more `'*'` cleanup row each time.
   - **Where:** `WipeDao.kt:51`.
   - **Assessment:** harmless (idempotent command, acked by id, 30-day purge).
   - **Optional edit:** `INSERT INTO canvas_helper_cleanup (jobId, reason, createdAt) SELECT '*', 'delete_all', :now WHERE NOT EXISTS (SELECT 1 FROM canvas_helper_cleanup WHERE jobId = '*')`.
   - **Caution:** keep the current behaviour if the helper uses the newest `'*'` timestamp. Deduping would keep an older timestamp, which is safe only because `'*'` means "everything".

No Critical or Important findings:
- No forgotten frame's payload, result or job survives, or can be acked, through a section-6 deletion cause.
- The revision bump is atomic with the void and the cleanup rows.
- There is no disagreement with the WP3, WP5 or WP6 contracts.
- The schema is unchanged.

VERDICT: READY
