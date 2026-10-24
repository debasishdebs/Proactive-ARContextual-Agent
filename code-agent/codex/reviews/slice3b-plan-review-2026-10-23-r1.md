Read-only review; no files changed or builds/container gates run. **The set is not implementation-ready.** Room 2.6.1/schema v7, both migration lists, existing ASYNC projection, incomplete S6 enforcement, browser-only routing, and the unfenced helper DAO were checked against the repo.

File abbreviations below refer to [master](docs/superpowers/plans/2026-10-23-canvas-slice3b/00-master-plan.md), [A](docs/superpowers/plans/2026-10-23-canvas-slice3b/part-A-feasibility-spikes.md), [B](docs/superpowers/plans/2026-10-23-canvas-slice3b/part-B-phone-side.md), and [C](docs/superpowers/plans/2026-10-23-canvas-slice3b/part-C-helper-and-containers.md).

1. **Critical — Polling disk usage does not enforce the stated storage boundary.**  
   **Files:** A S2/N16 and §6; C H7/H26; master R13.  
   A writer can exceed the cap between two-second polls, exhaust inodes, or fill the Mac through the writable bind mount. An ordinary named volume also does not establish the claimed 3 GiB limit.  
   **Exact edit:** Replace “bind + `du` watchdog” as the enforcing boundary with a quota-backed VM filesystem or another demonstrated hard byte/inode limit. Export bounded outputs after termination. Keep the watchdog as a backstop. Require live tests for fast writes, sparse files and inode exhaustion. [Podman mount documentation](https://docs.podman.io/en/latest/markdown/podman-run.1.html#volume-v-source-volume-host-dir-container-dir-options).

2. **Critical — The new insertion fence still permits sending forgotten frames.**  
   **Files:** B P7/P8/P10/P16.  
   `sourcesHold()` checks trace revisions and `labelRev`, not frame tombstones. Ordinary frame Forget need not change either the wipe generation or `labelRev`. Forget can therefore land after `CanvasRunner`’s pre-call check but before helper-job insertion; P9 finds no job to void, and P7 subsequently inserts the forgotten payload.  
   **Exact edit:** Require authoritative frame provenance and transactional frame-tombstone checks at insertion, lease/payload release and result commit. Preserve the fence captured before loading inputs. Add `forgetBetweenRunnerPrecheckAndHelperInsertRefusesPayload`, using a frame with no labels and no trace sources.

3. **Critical — The five-second deletion guarantee does not follow from the timing contract.**  
   **Files:** master R1; B §3.4/P14; C §2/H3/H13.  
   A heartbeat processed before deletion can arrive afterward and restart the four-second timer. The 100 ms watchdog tick also makes “4 s + 1 s” exceed five seconds. Helper death leaves only the much longer container deadline until recovery.  
   **Exact edit:** Anchor liveness to the acknowledged request’s monotonic **send time**, include timer/scheduling slack in the budget, and specify a heartbeat schedule that demonstrably tolerates the promised lost beat. Add delayed-pre-deletion-ack, blocked-poll/result and helper-process-death tests. Either implement an independent VM-side liveness watchdog or explicitly narrow the five-second guarantee.

4. **Important — P16 does not integrate cancellation with the real runner.**  
   **Files:** B P10/P16; C §0.  
   [`CanvasRunner.cancel()`](android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/CanvasRunner.kt:95) sets a flag checked between chunks; it does not cancel the suspended executor. Its actual limit is 50 photos, and `chunkSize(node)` cannot inspect the incoming item count. The service’s deferred is described as completed by result commit, leaving failure/expiry/void completion unspecified.  
   **Exact edit:** Add a separately estimated runner-to-helper cancellation boundary, complete waiters on every terminal state, and bound the wait. Test cancellation through `CanvasRunner`, including A/B runs. Specify batching using the actual API and 50-photo limit. Correct C’s “only caller” claim: `PipelineInterpreter` also consumes `Executors.all()`.

5. **Important — S3 proves less than H26 requires, and misses a major heap.**  
   **Files:** A S3; C H24/H26/H27; master resource/estimate sections.  
   Seeding `assembleDebug` does not seed the unit-test/Robolectric runtime. Moreover, [`android-companion/build.gradle.kts`](android-companion/build.gradle.kts:14) sets every test JVM to **2 GiB**, additional to the planned Gradle/Kotlin heaps. S3 can pass while offline verification fails or OOMs.  
   **Exact edit:** Make S3 seed and execute baseline unit tests and the immutable adapter offline, as well as APK assembly. Add trusted test-JVM heap/fork overrides and measure the complete verification sequence inside the 3 GiB container before setting `G_verify`.

6. **Important — The host/VM semaphore contracts are incompatible.**  
   **Files:** master R12/§6.2; A §5; C H26.  
   The master specifies mkdir-based shell slots, while Python checks an `fcntl.flock` on `host-gradle.lock`. Those mechanisms do not mutually exclude each other. Checking one lock before taking another also leaves a simultaneous-start race.  
   **Exact edit:** Define one cross-process locking protocol used by both entry points. A practical option is a shared Python launcher using flock: host builds hold a shared heavy-exclusion lock; verification/setup hold it exclusively; separate slots enforce host concurrency. Add simultaneous-acquisition and process-death tests.

7. **Important — The generated-file contract cannot reliably produce a compiled tool and test.**  
   **Files:** C H16/H19/H21/H26; B §3.6.  
   H19 rejects new files although a new tool requires them. Its two paths do not distinguish `src/main` from `src/test`; the repo has no source-set override compiling tests beside production code. B also permits a different tool-ID shape from H16.  
   **Exact edit:** Specify the two complete repository paths, package/class derivation and one ID regex in P1’s contract. Permit additions only at those paths. Define template-relative-to-repository patch mapping and add an end-to-end test proving the generated test is discovered by the named Gradle command.

8. **Important — The schema contract is still inconsistent.**  
   **Files:** B §3.6/P1; C H10/H17.  
   B permits `pattern`; H10 omits it and rejects unknown keywords. H10 adds `minItems`, `maxItems` and `maxDepth`, which B excludes. H17 consequently lacks a defined input language for its 200 valid cases.  
   **Exact edit:** Put the supported subset in one shared contract, implement identical acceptance rules in Kotlin/Python, and define which pattern forms case generation supports. Add shared positive/negative fixtures for every keyword and reject unsupported contracts before confirmation.

9. **Important — Held-out examples have no valid minimum-input contract.**  
   **Files:** B §3.6/P12; C H17/H25.  
   B allows up to 50 examples without a minimum; H17 requires at least two held out. Zero, one or two submitted examples cannot satisfy the intended visible/held-out split.  
   **Exact edit:** Require at least three examples, keep at least one visible, define rounding explicitly, and validate examples against both schemas before submission. Add fixtures for counts 0–3 and invalid example outputs.

10. **Important — “Image-owned adapter” does not protect verification from executing generated code.**  
    **Files:** C H24–H27.  
    H26 makes the entire copied source tree writable. Generated tests can modify the adapter or build files before later commands execute; generated code also shares access to report destinations. The patch guard only checks the initial patch.  
    **Exact edit:** Keep baseline/applied sources and verification controls read-only during execution, provide separate writable build/scratch locations, and prevent generated processes from writing trusted reports. Isolate the adapter’s report writer from the tool worker. Add hostile tests that attempt to rewrite the adapter, Gradle configuration and result reports.

11. **Important — Phase 0 contains false-failure conditions and broken handoffs.**  
    **File:** A §0/S2/S1.  
    The preflight requires exactly one connection, although machine initialization creates root and user connections. N15 expects 900 MiB to survive a 512 MiB limit. S2 removes both networks and the proxy, while S1 assumes they still exist.  
    **Exact edit:** Validate the configured rootless connection explicitly rather than total row count; change the surviving allocation to a safely sub-limit value; and deliver reusable topology start/teardown commands invoked by S1. Also make `G_runtime` reference checks actually completed at that gate. [Podman initialization](https://docs.podman.io/en/latest/markdown/podman-machine-init.1.html), [connection examples](https://docs.podman.io/en/latest/markdown/podman-system-connection-list.1.html).

12. **Important — The milestone schedule introduces a dependency deadlock.**  
    **Files:** master §4/§5.1/§6.3; B P16/P20.  
    P16 must merge after H15, but H15 is scheduled in Wave 3 after M1, which requires P16. Cancelling Wave 3 after S1/S3 failure also strands the promised Python-only release. The fallback promising `tool.draft` conflicts with P20’s S1-PASS prerequisite.  
    **Exact edit:** Either move H15 before P16 and include it in M1’s cost, or remove that merge-order dependency through a defined composition strategy. Make stand-ins/export independent of CLI success if retaining that fallback. Recompute milestone membership and review-D timing.

13. **Important — The CLI flags do not establish the stated tool restriction.**  
    **Files:** A S1; C H21/H22.  
    `--allowedTools` preapproves tools; it is not an exhaustive available-tool list. Project settings/hooks and additional tool surfaces are not explicitly disabled. This weakens “generated code is never executed in gen.”  
    **Exact edit:** Use an explicit `--tools` list, disable project/local customization and hooks for the pinned version, prohibit MCP/plugin loading, and load settings from an immutable image path. Add a malicious workspace-settings/hook fixture and assert no command executes. [Official CLI semantics](https://code.claude.com/docs/en/cli-reference).

14. **Important — The per-item Python timeout is cooperative.**  
    **Files:** C H11/H13; A §6.  
    User source runs in the harness process and can replace or ignore its signal handler, or catch the alarm exception. `signal.setitimer` therefore cannot enforce the promised five-second item limit.  
    **Exact edit:** Put item execution behind an external watchdog/process boundary that user code cannot disable, kill the worker/process group on timeout, and test caught alarms, ignored signals and spawned children. Otherwise change the guarantee and result semantics explicitly.

15. **Important — RED/mutation coverage is incomplete, and one named mutation is ineffective.**  
    **Files:** B P7/P19; A S1/S3.  
    P19 has no mutation check; S1/S3 lack named RED-plus-mutation procedures. Removing `state='QUEUED'` from P7’s UPDATE need not fail its concurrent test: Room serializes transactions, and the preceding SELECT still chooses queued rows.  
    **Exact edit:** Add explicit mutation procedures for P19 and both spikes. Replace P7’s mutation with one observable under serialized execution, such as breaking lease-owner/generation enforcement. Add a same-helper concurrent lease test proving `maxActiveJobs=1`; the existing five-job test does not establish that invariant.

16. **Important — Task labels still conceal multiple implementation boundaries.**  
    **Files:** master §4/§5; A S2; B P8/P10/P16; C H26/H28.  
    S2 combines machine provisioning, proxy implementation and seventeen probes in two hours. H26 combines a strict patch applier, registry integration, verification orchestration and scheduling. H28 combines bundle validation with scripted and real-CLI integration. These remain oversized Sonnet dispatches despite compliant labels.  
    **Exact edit:** Split those tasks into independently testable boundaries before dispatch, including separate cancellation and offline-test feasibility work identified above. Re-estimate task/review/fix costs and milestone checkpoints afterward. The **104.0 task-hour sum is correct**, but 140.9 hours and the stop-loss thresholds are not yet supported by the missing work.

Earlier-finding audit: **#2, #3, #7 and #9 are resolved at design level**. **#1, #4–6, #8 and #10–16 remain partially resolved**, for the reasons above. No original concern should be marked empirically proven before its live gate passes.

**NOT READY**