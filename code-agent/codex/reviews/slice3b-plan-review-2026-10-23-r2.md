Read-only review; no files changed or live gates run.

Round-1 audit: **#2, #3, #4, #7 and #9 are resolved at design level**. **#1, #5, #6, #8 and #10–16 remain partially resolved**, as detailed below. The narrowed five-second guarantee is documented consistently; it still requires live evidence.

The arithmetic checks: **15.0 + 51.0 + 74.0 = 140.0 task-hours**, **187.15 planned**, **28.07 reserve**, **215.22 cap**. The listed checkpoint allocations also add up, but their task membership needs correction.

File abbreviations: [M: master](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-23-canvas-slice3b/00-master-plan.md), [A: spikes](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-23-canvas-slice3b/part-A-feasibility-spikes.md), [B: phone](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-23-canvas-slice3b/part-B-phone-side.md), [C: helper](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-23-canvas-slice3b/part-C-helper-and-containers.md).

1. **Important — Verification still exposes writable trusted inputs and report staging.**  
   **Files:** A §4; C H24, H26a–c, H27b; M R23.  
   Mounting the baseline read-only does not protect the copy used for execution: H26a copies it into writable scratch, and H26b builds there. Generated tests can modify that copy’s adapter and build controls. Separately, H24 waits for the worker to exit before enumerating its descendants; reparented children can survive and overwrite scratch reports. Java explicitly describes descendants as a snapshot, with terminated processes typically having no children. [JDK documentation](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ProcessHandle.html)  
   **Exact edit:** Materialize in a separate trusted step, then mount the **applied source tree read-only** during execution, with separate writable build/cache directories. Put the untrusted worker behind a container/cgroup boundary; terminate and verify that boundary empty before a report writer outside it writes oracle inputs. Add successful-return/double-fork report-forgery tests.

2. **Important — The quota mechanism has an impossible sparse-file gate and an incomplete rootless setup.**  
   **Files:** A N18–N20, §6.1; C H7b; M R21/Q13.  
   A small ext4 filesystem limits allocated blocks, not sparse files’ logical lengths. `truncate -s 10G` and a small write at a distant offset can succeed; N18 therefore rejects the proposed mechanism. The subsequent `cp -a` also lacks an explicit logical-byte/export-entry budget before writing to the Mac. [truncate semantics](https://man7.org/linux/man-pages/man1/truncate.1.html)  
   The setup additionally assumes `core` can create `/var/lib/pca` and change ownership of a freshly mounted filesystem using rootless `podman unshare`/`:U`. Those operations do not grant VM-root ownership privileges.  
   **Exact edit:** Expect sparse growth to succeed while allocated blocks remain bounded; reject oversized logical files **before export**, using a bounded regular-file exporter. Specify one-time privileged directory provisioning, filesystem ownership via `mkfs.ext4 -E root_owner=…` or explicit privileged ownership setup, required SELinux labels, and privileged unmount cleanup. Prove UID 10001 read/write/export on a fresh VM. [mke2fs ownership option](https://man7.org/linux/man-pages/man8/mke2fs.8.html), [Podman user namespaces](https://docs.podman.io/en/latest/markdown/podman-unshare.1.html)

3. **Important — L1 does not fully define mutual exclusion or crash-safe resource ownership.**  
   **Files:** A L1/§5; M R12/§6.2; C H7/H26b.  
   `vm-light` explicitly holds `light.lock` and slot 2, but no lifetime acquisition of `heavy.lock` is specified. Merely checking whether it is exclusively held races with `vm-heavy`. Also, `killedHolderReleasesLock` treats lock release as sufficient although a Gradle child or runtime-managed container can remain alive after its launcher dies—the latter is explicitly allowed by Q12.  
   **Exact edit:** Require `vm-light` to hold `heavy.lock` shared throughout its job. Define lock ownership for the entire workload lifetime, including detached containers and surviving children. Before admitting work after holder death, reconcile those workloads under serialized admission. Add light/heavy simultaneous-start tests and launcher-SIGKILL tests where the workload remains running.

4. **Important — S3’s cache path is wrong, and its adapter measurement omits the new worker JVM.**  
   **Files:** A §4/S3a/S3b; C H24/H26b; M R13/R23.  
   The cache is stored at `/ro/modules-2`, but `GRADLE_RO_DEP_CACHE` must point to its **parent**, `/ro`. The current value makes Gradle look one level too deep. [Gradle cache layout](https://docs.gradle.org/current/userguide/dependency_caching.html)  
   S3b’s stand-in loads a class itself; H24 adds a separate JVM alongside Gradle, Kotlin and the test JVM. That worker has no explicit heap specification, so S3b does not measure the promised production process shape.  
   **Exact edit:** Replace every `GRADLE_RO_DEP_CACHE=/ro/modules-2` with `/ro`. Make S3b launch the same isolated worker architecture as H24, explicitly bound its heap, and measure the whole process set before recording resources or granting `G_verify`.

5. **Important — The verification sequence cannot follow its own scratch lifecycle and produce the APK report.**  
   **Files:** A S3b/§6; C H26b/H26c; B §3.6; M R10/R23.  
   H26b removes scratch after each step, but H26c’s fourth step needs earlier unit and adapter scratch filesystems. Retaining them instead violates the one-scratch disk budget. The report step also precedes APK assembly while being responsible for copying the APK. Finally, protocol step four is `oracle`, whereas the driver defines `report`.  
   **Exact edit:** Define logical steps as `apply → unit → adapter → oracle → apk`. Immediately after each producing container exits, run its bounded read-only reporter, then delete that scratch before allocating the next. Report/export the APK after assembly. Specify transport/report containers separately from the five protocol steps and show peak simultaneous disk allocation.

6. **Important — The dependency audit omits GREEN dependencies and M0 exit prerequisites.**  
   **Files:** B P1/P10a/P12; M §4.1/§5.3/§6.3.  
   P1 introduces `SchemaSubsetTest` while its implementation belongs to P12, which transitively depends on P1. P1 also tests `BundleHash.of`, whose implementation is assigned to P10a, again downstream of P1. These prevent independent GREEN completion. M0 then requires Python schema/example conformance implemented by H10/H17, assigned to M1/M2.  
   **Exact edit:** Move `BundleHash` into P1. Assign Kotlin schema implementation/conformance to a separately scheduled foundation task, leaving P1’s tests limited to fixtures it can validate independently. Move the Python conformance work required by M0 into M0, or move those exit assertions to their implementing milestones. Re-run the dependency audit using tests and exit conditions as edges.

7. **Important — The promised S2a-failure fallback remains transitively gated by S2a.**  
   **Files:** A §9; B P20; M §5.1/§5.5.  
   P20 depends on P16a, which depends on P13 and the persistence/service chain blocked by G0 until S2a passes. Consequently, “S2a fails: ship phone-only stand-ins/export” and “M2 continues in every case” cannot follow the schedule. The original S1/S3 merge deadlock is fixed; this broader fallback is not.  
   **Exact edit:** Extract experiment-map composition so P20 can use it without P16a or helper plumbing. Give standalone stand-ins/export explicit dependencies on that composition, P15 and H18, and define their own achievable fallback exit.

8. **Important — The phone has no protocol source for the readiness booleans used by the UI.**  
   **Files:** B §3.3/P10b/P12/P18/P20; C B-GRANTS/H3a; M R17.  
   `gen_ready` and `verify_ready` are laptop-local doctor/stamp results. No helper request carries them to the phone; the specified browser status response also omits them. Restricting lease `types` cannot communicate readiness before jobs exist or distinguish the two gates.  
   **Exact edit:** Add a helper-to-phone readiness advertisement to poll, define its session freshness/reset rules, store it in the service, and expose the named booleans through `/api/helper-status`. Add fixtures and tests for missing, stale and invalidated readiness.

9. **Important — The new regex subset is neither safely bounded nor semantically complete across languages.**  
   **Files:** B §3.6a/P12; C H10/H17.  
   “Groups without nesting” still permits `^(a+)+$`, which can make backtracking validation expensive on a failing input. No mandatory string-length bound prevents this before confirmation or during host validation. The subset also leaves `\d`, `\w` and `\s` semantics unspecified; Java and Python defaults differ. [Java patterns](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/regex/Pattern.html), [Python patterns](https://docs.python.org/3/library/re.html)  
   **Exact edit:** Define one bounded matching language, including character classes and full-match semantics. Reject quantified groups containing quantifiers, impose pattern/input/work limits, and use a matching implementation whose bound cannot be defeated by input. Add shared Unicode and pathological-failure fixtures, and make casegen consume the same parsed grammar.

10. **Important — The CLI contract still contains false assumptions about settings and the exact tool set.**  
    **Files:** A §3/S1b; C H21/H22; M R24.  
    `--setting-sources` selects `user/project/local`, not an image-file path. Disabling project settings does not itself establish that `CLAUDE.md` is unread. Current CLI documentation also retains `EndConversation` when other tools remain, contradicting the mandatory “exactly five tools” assertion. [CLI reference](https://code.claude.com/docs/en/cli-reference)  
    **Exact edit:** Record an actual pinned argv using disabled settings sources plus explicit immutable `--settings`; use a verified customization-disabling mode for instruction discovery. Define the permitted file tools separately from harmless mandatory control tools, while refusing every command-capable/MCP surface. Replace the zero-child-process assertion with a check that permits the explicitly configured `apiKeyHelper` subprocess.

11. **Important — Several mandatory RED/mutation procedures cannot produce the specified result.**  
    **Files:** A §0/S1b/S2a/S2c/S3a/S3b; C H22.  
    Adding project settings while retaining `disableAllHooks` need not execute the hook. Removing strict MCP while project discovery remains disabled need not start MCP. Removing `--offline` while retaining `--network none` and an empty cache cannot demonstrate successful downloads. N13 also mistakes an ordinary empty `/mnt` directory for a host share. Finally, S2c’s mutation replaces the quota with a Mac bind and retains an unrestricted fast writer.  
    **Exact edit:** Use deliberately vulnerable, **bounded** positive controls for each defense; distinguish expected build failure from observed downloads; test actual mounts/host canaries rather than directory absence. Stop the storage negative control after a small amount above the test limit. Replace “every line must initially FAIL” with “each assertion must have a demonstrated failing control.”

12. **Important — The external per-item watchdog does not specify cleanup after successful execution.**  
    **Files:** C H11/H13b; M R25.  
    H11 explicitly kills the process group and sweeps escaped children **on timeout**. A function can spawn a background child and return successfully; that child can outlive its item and interfere with subsequent items.  
    **Exact edit:** Require worker-tree cleanup and reaping after **every** outcome, before output acceptance and before starting the next item. Define how escaped/reparented processes are contained without an unbounded `/proc` race. Add normal-return, exception and oversized-output fixtures that spawn escaped children.

13. **Important — Several revised task labels still conceal multiple implementation boundaries.**  
    **Files:** A S2c; B P12; C H24; M §4/§5.4–5.5.  
    S2c still combines a new privileged filesystem lifecycle with network/resource/export probes and live mutations in 2.5 hours. P12 combines a schema-language implementation with browser routing in two hours. H24 combines JVM worker isolation, process supervision and trusted report production in 2.5 hours. These costs are prerequisites for later tasks, rather than incidental fix work.  
    **Exact edit:** Split filesystem prototype from isolation probes, schema implementation from routes, and worker containment from report production. Estimate each independently within the stated dispatch range. Then regenerate task rows, milestone memberships, package totals, checkpoints and stop-loss thresholds from the corrected dependency graph. Keep the existing arithmetic as historical figures.

VERDICT: NOT READY