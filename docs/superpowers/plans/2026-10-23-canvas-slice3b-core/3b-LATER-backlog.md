# Slice 3b-LATER backlog (deferred from 3b-CORE: v2 NL tools, 3d hardening slice, convenience)

Companion to `00-core-plan.md`. Old ids refer to `../2026-10-23-canvas-slice3b/{00-master-plan,part-A,part-B,part-C}` (the 187.2 h set; left unchanged). "Old h" is the old task estimate of the deferred part (rounded, not re-estimated). Nothing here weakens a privacy invariant of CORE (pairing, confirmation, grants, deletion fences, Room v8, S6, `PROTOCOL_VERSION`, single logger).

**Four kinds of deferral (review c2 #11: do not mix them).**
1. **v2: NL tools (removed product behaviour, user ruling 2026-10-23).** NL-based pipeline/tool development is an advanced feature; v1 ships from v0.9.0 first. Section V below. Not a threat-model consequence.
2. **3d hardening slice (security depth).** Defences against deliberately hostile code (sections A). CORE builds with safety in mind and keeps the seams: **one runner argv builder, one profile table, one resource config (`config/resources.json`)**, so 3d can sandbox and secure later without redesign. Threat model A stays for CORE: the user's own laptop, sane container defaults, no hostile-code proofs. **Re-open trigger:** any of (a) the helper runs jobs from another person or an unreviewed source, (b) the machine is shared, (c) generated or third-party code is auto-installed or auto-merged, (d) a review or an incident shows user code escaping its container.
3. **Verification coverage and convenience (sections B, C).**
4. **Superseded by the Moment slice (section J, user decision 2026-10-23, Option 1: frames-only plus trace-lab).** The stored-trace canvas input path was specified in CORE through review c9 and is removed from CORE, not built in 3b.

## V. v2: NL tools (moved out of CORE wholesale)

The whole feature is one unit; do not ship pieces of it into CORE. Estimates are the c2 CORE figures (agent-h, not re-estimated); the v2 plan must re-plan from the dependencies and re-run its own review.

| v2 item | Content | c2 WP / old ids | h (c2) |
|---|---|---|---|
| V1 NL build path | CLI generation in a container (`gen` profile), `gen`/`verify` profiles in the one profile table, template and two-path diff guard, **Codex read-only diff review gate** (fixed 6-item checklist, one pass, PASS required; rules in the c2 plan section 4.2, preserved in `00-core-plan.md` git history and `../2026-10-23-canvas-slice3b/part-C`), offline APK build (`assembleDebug --offline`, SHA-256), snapshot/verify driver, result bundle with review verdict; APK stays on the laptop, never installed; **c5 #4: also the generated-file templates, repository path rules and the generated tool/class-name keyword denylist (the "id/paths" part of the old P1 fixtures) with their tests, removed from CORE WP1 (CORE keeps only the helper job-id regex vectors)** | c2 WP13, WP14; H16, H17 (no casegen), H19, H21, H22, H23, H25 (frozen compare), H26a-c (simple), H27a, H28a-c | 7.0 |
| V2 `tool_build` grant and form (**the per-job confirmation contract was here from review c3 #3 and is BACK IN CORE for `py_exec` by the user decision of 2026-10-23 evening, see below**) | grant `tool_build` (default off); browser form, verdict + diffstat panel; **`tool_build` jobs reuse CORE's per-run confirmation unchanged (`HelperConfirmActions` with `onConfirm(jobId)`, `onCancel(jobId)`, `pending()`, `HelperConfirmAdapter`, the confirmation operations on `HelperJobPort`, the memory-only HMAC `HelperConfirm.kt`, `AWAITING_CONFIRM`, 409 `not_confirmed`; all built in CORE WP1/WP2/WP3/WP5/WP6 for `py_exec`)**: v2 only adds the `tool_build` job type to that path and its tests. **Retained design text, now implemented in CORE (do not re-invent): interface + adapter + port operation + HMAC contract + tests (`HelperConfirmAdapterTest`, card-calls-only-bound-callbacks, HMAC vectors).** | c2 WP2/WP4/WP6 parts; P2-P5, P18 parts | the confirmation work is sized in CORE (about +4.75 h of WP hours across WP1-WP9 and WP11, section 21 of the core plan); the rest of V2 is not separately sized; additional to the sized subtotal |
| V3 Trusted example tool | one trusted example tool, `Executors.all()` line, frozen-example adapter test (`examples.json`), `NodeTypes.kt` entry `tool.generated`; **c5 #4: the tool-id/class-name denylist fixture and its test** | c2 WP7; H15, H24 (trimmed) | 3.0 |
| V4 Spike B | S1 (pinned CLI generates one patch under an explicit `--tools` list, key canary scan), S3 (offline ARM64 APK build in the 4 GB VM, 1.5 h unattended), recorded CLI flags, measured numbers | c2 WP12; S1a, S1b (basic), S3a, S3b | 3.5 |
| V5 API-key setup and question | dedicated API key with a spend limit placed by the user at `~/.pca-helper/gen.key` (mode 0600), key file mounted read-only into `gen` only | c2 Q1 | in V4 |
| V6 Allow-list proxy | `container/proxy/{proxy.py,Containerfile.proxy,allow.txt}`, network profile for `gen` only (CORE `py_exec` has no network and CORE has no proxy), proxy isolation probe | c2 WP11 part; S2b, S2c (network) | 1.0 |
| V7 Intermediate export and verify workspace | review c2 #4 and #5 for v2: generation output exported and reviewed **before** verification containers start, accepted patch staged read-only into verification, final report/APK export separate, explicit volume retention/removal between phases; a **job-local disk-backed workspace volume** (separate from exported outputs) for sources, Gradle home and build outputs, with one lifecycle (create, size/free-space check, removal) in the runtime WP and measured by spike B; lifecycle test generate -> export -> review -> verify -> export | review c2 #4, #5 | to be sized in v2 |
| V8 Stand-ins and export | `tool.draft` node, deterministic stand-in function (unknown input fails `no_example`), client-side `tool-<id>/` export, no-CLI fallback; restores the master's draft/freeze/stand-in workflow | P20, H18 (review c2 #8) | 4.0 |
| V9 200-case schema oracle | deterministic case generator, `schema_lite` comparator with fail-closed checks, held-out/visible sampling beyond frozen examples (restores the specified 200-input conformance claim) | H17 (casegen), H25 (full), H26c oracle step, H28 oracle assertions (review c2 #8; LB10 before) | 4.0 |
| V10 Retained APK artifacts | APK kept under `~/.pca-helper/artifacts/<job>/` with the 7-day expiry mechanism already built in CORE (WP11), earlier cleanup on job deletion | c2 WP14; S39, H4 | in V1 |

v2 re-open conditions: CORE v0.9.3 COMPLETE (Python experiments shipped). v2 release text must state NL tool development is a separate release and that CORE's `v1.0.0-alpha` has none of it.

## A. 3d hardening slice (hostile-code defences; CORE is correct without them under threat model A)

| LB | Item | Old ids | Old h | Is CORE correct without it? (review c2 #11) | Re-open when |
|---|---|---|---|---|---|
| LB1 | Kernel-limited per-job loop-mount filesystem (`pca-jobfs.sh`, sparse images, `mkfs.ext4 -N`, `sudo mount`, kernel-quota export, N16/N18/N19/N20 live quota cases, Q13) | R21, S2c quota part, H7b, A section 6.1, Codex r2 #2 | 4.0 | Yes under A: CORE exports from a job-local volume with a byte cap, a refusing collector and a free-disk check (core plan section 4.2) | (a) or (b) |
| LB2 | Process-tree forensics and report forgery tests: worker JVM isolation with descendant sweep, read-only baseline/patch/adapter mounts, fresh re-materialized tree per step, post-step report containers, double-fork forgery tests. Applies when v2 verification exists | R23, H24 (worker part), H26b, H26c (report part), H27b, Codex r2 #1 and #5 | 7.0 | Yes under A (no verifier in CORE) | (a), (c), or v2 with untrusted generated code |
| LB3 | External per-item watchdog hardening: `/proc` sweep of escaped children, escaped-child fixtures | R25, H11 (sweep), H13b (watchdog and helper-death live tests), Codex r2 #12 | 1.5 | Yes: CORE retains the ordinary per-item timeout kill by the PID-1 harness and process-group cleanup | (a) |
| LB4 | Bounded regex language and ReDoS machinery: the `pattern` keyword, anchored mini-grammar, shared pathological fixtures | part B 3.6a item 1, P12/H10 pattern parts, Codex r2 #9 | 1.5 | Yes: `pattern` is refused consistently before the job is created | when schemas with patterns are wanted |
| LB5 | VM-side liveness watchdog (second supervised process inside the VM) | Q12, R1 narrowed guarantee | 2.5 | Yes with the explicitly narrowed helper-alive guarantee (about 5 s while the helper lives) | (a), or a user request for a hard guarantee |
| LB6 | Heavy/light lock classes and crash reconciliation: lifetime ownership of detached containers, launcher-SIGKILL tests, simultaneous-start barrier tests, full admission reconciliation. **The small part is in CORE (WP10, c1 #4 and c2 #6): shared host / exclusive VM session held by a living owner from machine start to confirmed stop (failed stop keeps the owner alive), host admission checks the VM is stopped, two build slots, a separate job lock and their tests** | L1 (full), R12, H7 lock parts, Codex r2 #3 | 1.0 | Yes after the failed-stop handling now in CORE | more than one concurrent job |
| LB7 | Settings-isolation proofs for any in-container CLI: immutable settings path, instruction-discovery disabling proofs, exact-tool-set assertion with control tools, malicious-workspace fixture, vulnerable positive controls | R24, S1b (full), H22 re-run, Codex r2 #10 and #11 | 2.5 | Yes under A (no CLI in CORE; v2 keeps the explicit tool list, no `~/.claude`/`~/.codex` mount, canary scan) | (a), (c), or v2 hardening |
| LB8 | Per-step crash and race exhaustiveness: full kill matrix and lost-ack suite, H3b independence tests, timing arithmetic tests, H4 crash-boundary journal matrix | P14 (most), H3b (most), H4 (matrix), R1 test names | 3.5 | Yes: CORE keeps representative privacy and lifecycle tests (one deletion table, one heartbeat/lease/kill group, retention tests) | after a field incident |
| LB9 | Hostile collector cases beyond refusal of symlinks and oversize (hard links, depth bombs, special files catalogue) | H8 (extra) | 1.0 | Yes under A with the retained refusing collector | (a) |
| LB20 | **3d seams to tighten (named so the slice can start)**: replace cells of `config/resources.json` with measured values, add profile columns (seccomp, user namespace, read-only mounts), a stricter argv test over the whole table, optionally a non-default Podman machine isolation. No redesign: the single runner argv builder, profile table and resource config stay | CORE WP12 seams | not estimated | n/a (CORE builds with safety in mind; sandboxing is later) | the 3d slice starts |

## B. Verification depth

| LB | Item | Old ids | Old h | Is CORE correct without it? | Re-open when |
|---|---|---|---|---|---|
| LB10 | 200-case schema-conformance oracle (**moved into v2 item V9**, kept here as a pointer) | H17, H25, H26c, H28 | 4.0 | Python experiments do not use it; the specified claim belongs to NL tools (v2) | with v2 |
| LB11 | RT lockstep with Python and Python in sweeps | P17 (pin kept), A/B out-of-scope list | not estimated | ASYNC experiments work; original RT behaviour remains explicitly deferred; `ReplayService` has no node-suspension bridge; CORE refuses and pins it | a replay design exists |
| LB12 | Promotion machinery and any path from a verified tool to the installed app; the `promote` grant name | master out-of-scope list | not estimated | Yes: no installed-tool claim | promotion slice (after v2) |
| LB13 | CodeMirror or any rich editor; patch syntax-highlighting viewer (`patchview.js`) | master out-of-scope list | not estimated | Yes: plain textarea and `<pre>` | UI polish slice |

## C. Convenience and scope

| LB | Item | Old ids | Old h | Is CORE correct without it? | Re-open when |
|---|---|---|---|---|---|
| LB14 | `tool.draft` node, stand-in function, client-side export (**moved into v2 item V8**, pointer) | P20, H18 | 4.0 | Python experiments do not need it | with v2 |
| LB15 | Persisted helper tokens across phone restarts (adds a persisted secret to the deletion inventory) | Q7 alternative | 2.0 | Yes: **DECIDED by the user 2026-10-23 evening: helper tokens stay memory-only** (a phone app restart invalidates them; re-pair; consistent with "if exposed we can recreate all"); restart revocation and re-pairing are coherent. Not planned | only if the user reverses the decision (frequent re-pairing becomes a burden) |
| LB16 | Per-session confirmation for `py_exec` | Q6 alternative | 1.5 | **SUPERSEDED 2026-10-23 evening: the user chose a stricter control, a per-RUN native confirmation in addition to the grant, and it is in CORE (core plan section 21)**; no per-session variant is planned | n/a |
| LB17 | S3 retry on a 5 GiB VM; amd64 emulation retry (v2 spike B) | A Q2, S3 fallback ladder | 1.0 | Yes: failed feasibility leaves NL disabled (v2) | v2 scope review |
| LB18 | Multi-group review rigor (10 review points), 15-25 % fix rounds, 15 % reserve machinery | master 5.4, 6.4 | not counted | Yes conditionally: CORE uses RV1, RV2, RV-final and about 14.6 % fixes (7.5 h of 51.5 h WP hours, section 24 of the core plan; 8.75 h of 60.25 h at c8; 8.5 h of 58.0 h after the evening decisions; 7.75 h of 53.0 h at c5), and final fixes are verified and reviewed (fix-before-final-build rule) | after a missed defect |
| LB19 | Carried over unchanged from the master: Docker as second runtime; second CLI; AI-assisted stand-ins; fusion tunables (3a Task 13); LIVE (3a Task 25); repair loop; mixed-domain projection; watch app; any sensing change | master section 1 out of scope | not estimated | Yes for the expressly narrowed CORE deliverable | their own slices |

**Deferred task hours (approximate sum of the "Old h" column, task work only): the 3d hardening slice is 24.5 h (LB1-LB9: 4.0 + 7.0 + 1.5 + 1.5 + 2.5 + 1.0 + 2.5 + 3.5 + 1.0 = 24.5 h; LB3 and LB6 shares included once). v2 NL tools: a sized subtotal of 24.0 h of the c2 figures (V1 7.0, V3 3.0, V4 3.5, V6 1.0, V8 4.0, V9 4.0 = 22.5 h, plus the NL shares of the phone and helper WPs, about 1.5 h); V7 (unsized) and the unallocated part of V2 (`tool_build` grant and form; the per-job confirmation contract is no longer deferred, it is in CORE) are additional.** The remaining difference to 187.2 h is explained in the `What changed` tables of `00-core-plan.md`.

## D. Findings of Codex review round 2 (the older r2 review) and where they went

| r2 # | Topic | Disposition |
|---|---|---|
| 1 | verification trusted inputs and report staging | LB2 (v2 verification) |
| 2 | sparse-file quota gate, rootless filesystem setup | LB1 (CORE has no loop-mount) |
| 3 | lock mutual exclusion and crash ownership | LB6 (CORE: minimal admission lock with failed-stop owner and VM-stopped check, core plan section 8.1; the rest stays LB6) |
| 4 | S3 cache path (`GRADLE_RO_DEP_CACHE` = parent `/ro`) and worker JVM measurement | v2 spike B (V4) |
| 5 | verify scratch lifecycle and step names | v2 (V1, V7); heavier lifecycle LB2 |
| 6 | dependency audit (GREEN dependencies, M0 exit prerequisites) | **kept**: `BundleHash` and `SchemaSubset` live in WP1; Python conformance sits in WP13 and is not a v0.9.2 exit condition |
| 7 | S2a-failure fallback gated by S2a | moot: stand-ins/export are v2 (V8); if spike A fails, nothing helper-bound ships |
| 8 | readiness booleans have no protocol source | **kept**: `ready` field in `poll`, shown by `/api/helper-status` (WP6) |
| 9 | regex subset | LB4 |
| 10 | CLI settings and exact tool set | LB7 (v2) |
| 11 | RED/mutation procedures cannot produce the result | CORE has one RED group and one named mutation per WP, each chosen so it can fail |
| 12 | per-item watchdog cleanup | LB3 |
| 13 | labels conceal multiple boundaries | addressed by WP splits with explicit file lists and the 6 h session cap |

## E. Codex review c2 (2026-10-23) findings that live here

| c2 # | Disposition |
|---|---|
| 4 (export lifecycle vs review-before-verify) | closed by v2 deferral; requirement carried to item V7 |
| 5 (verification workspace) | closed by v2 deferral; requirement carried to item V7 |
| 8 (LB14 and LB10 remove NL behaviour and coverage) | closed by v2 deferral; V8 and V9 restore them with the NL feature; the reversal of S55's NL-first order is the user's ruling |
| 11 (item-by-item dispositions) | applied: the "Is CORE correct without it?" columns above |

## F. Findings of Codex review c3 (2026-10-23) that live here

| c3 # | Disposition |
|---|---|
| 3 (job-confirmation contract) | moved to v2 item V2 (interface, adapter, HMAC contract, tests); CORE used native pairing/grant actions only. **Reversed for `py_exec` by the user decision of 2026-10-23 evening: the per-run confirmation is back in CORE (section I below)** |
| 10 (deferred hours) | applied: LB1-LB9 = 24.5 h; v2 sized subtotal 24.0 h, V7 and unallocated V2 additional |

## G. Findings of Codex review c4 (2026-10-23) that touch this file

| c4 # | Disposition |
|---|---|
| 1, 2 (stored-trace experiment path and trace-derived output deletion fence) | **were in CORE (WP8a, WP8b, WP3, WP4, WP9) by the earlier user decision; SUPERSEDED 2026-10-23 by Option 1: moved to section J below**; RT lockstep over traces stays LB11 |
| 4 (session termination) | CORE (WP6); no backlog item |
| 7 (fix ratio, stale figures) | applied: LB18 stated about 14.3 % fixes (14.6 % after c5); the c3 sized v2 and 3d subtotals above are unchanged (no deferred item changed) |

## H. Findings of Codex review c5 (2026-10-23) that touch this file

| c5 # | Disposition |
|---|---|
| 1, 2, 3 (trace provenance contract, result store read/lifecycle, WP8a integration checklist) | **were in CORE (WP1, WP3, WP4, WP8a, WP8b, WP9, section 20); SUPERSEDED 2026-10-23 by Option 1: moved to section J below**; RT lockstep over traces stays LB11 |
| 4 (generation-only fixture scope) | applied: the generated-file/path templates and tool/class-name denylist moved to V1 and V3 above; `not_confirmed` moved to V2 and is **back in CORE since 2026-10-23 evening (section I)**; no hour change to the v2 or 3d subtotals (not separately sized) |

## I. User decisions 2026-10-23 evening that touch this file

| Decision | Disposition here |
|---|---|
| (a) `py_exec` needs a per-run native confirmation (grant on AND confirm) | The confirmation contract of V2 (interface, adapter, port operation, HMAC contract, `AWAITING_CONFIRM`, `not_confirmed`, tests) is **removed from the backlog and built in CORE for `py_exec`** (WP1, WP2, WP3, WP4, WP5, WP6, WP8, WP9, WP11, core plan section 21; +4.75 h of WP hours plus review, fix and device-run effects, planned total 78.25 h after review c8 and 67.75 h after Option 1; the confirmation work grew by +2.25 h of WP hours in c8 for production wiring, three separate waits and the browser-to-native test). V2 keeps only the `tool_build` grant, form and panels, which reuse the CORE path. LB16 is superseded |
| (b) Helper tokens stay memory-only | LB15 marked decided (not planned); no hour change |
| (c) Podman machine recreation approved, order rule: stop the previous machine first, then remove and recreate | In CORE WP10/PF/B1 (core plan section 21); nothing deferred. The hardening item LB20 (optional non-default machine isolation) is unaffected |

## J. Superseded by the Moment slice (user decision 2026-10-23, Option 1: frames-only plus trace-lab)

**Decision.** In-app Python experiments in 3b run over photos/frames only; stored sensor traces are NOT a canvas input. Sensor experiments run on the laptop with `tools/trace-lab` (core plan WP14, no app integration). The later **Moment model slice** (`../../specs/2026-10-23-moment-model-design.md`, phases 1-4 after 3b core, Room v9/v10) gives in-app sensor inputs with range-based deletion and re-plans this work against its own design. The items below were fully specified in `00-core-plan.md` through review c9 (see its git history, sections 6 and 17-23, and the c4/c5/c6 reviews); they are not built in 3b. Hours are the c8/c9 CORE sizing, not a Moment estimate.

| J | Item | Was in | h (c8/c9 sizing) |
|---|---|---|---|
| J1 | Trace-item adapter and input contract: `GET /api/traces/{traceId}/items` inside `TraceRoutes.handle`, `traceItems:[{traceId, revision, itemId}]` on estimate/start with a token bound to the sorted tuple set, `input.traceItems` node and validator rules, `TraceCanvasItem`, `TraceNodeExecutor`, `CanvasRunner.runTraceItems`, trace-graph node whitelist | WP8a | WP8a 5.5 |
| J2 | `TraceItemSource` and `StoredTraceItemSource` over the chunk reader (`canvas_trace` row, strict manifest decode, `ChunkCodec` verified chunk), real chunk fixture test | WP8a | in J1 |
| J3 | Trace provenance DTO `TraceItemProvenance(traceId, traceRevision, installId, deletionCounter, chunkSeq, eventOrdinal, chunkSha256, contentHash, sealState)`, `itemId` codec `c<chunkSeq>.e<eventOrdinal>`, `PyRequest.provenance`, `TraceItemProvenanceTest` | WP1, WP3, WP5 | 0.25 |
| J4 | Retained result store (additive Room tables `canvas_experiment_result` and `canvas_experiment_result_source`), `insertResultFenced` with source-trace re-check, read API `resultsForRun`, `deleteResultsBySource`, `deleteResultsForRun`, the `CanvasDao.deleteRun`/`pruneRuns` cascade | WP3 | 1.5 |
| J5 | Source-based invalidation on every trace deletion cause (trace delete, `tombstoneTrace` incl. always-on reclaim, Forget range, Delete all via `WipeDao`), job voiding by trace, legacy `canvas_run_item`/`canvas_score` payload scan | WP4 | 0.75 |
| J6 | `py.fn` trace executor, fenced result-store write, `CanvasBackendImpl.run()` read path for trace runs, `compare` refusal, payload-free `canvas_run_item` rows | WP8b | WP8b 5.25 less the 3.25 kept as core WP8 |
| J7 | Stored-trace device gate in B2 (real trace, up to 50 items, no photos, Forget mid-run) | B2 | inside the device run |
| J8 | Addendum A1: no scores or notes on trace runs; `traceRunScoreRefusedAndNoCanvasScoreRowBeforeAndAfterForget` | WP8b, WP9 | inside WP8b/WP9 |
| J9 | Trace item selection UI and 50-item message, `StoredTraceToPyFnIntegrationTest`, result-store/run delete/prune/trace-deletion end-to-end tests, trace variant of `BrowserRunToNativeConfirmIntegrationTest` | WP9 | 1.75 |
| J10 | Review and fix share of the above | RV2, fixes | 0.5 + 1.25 |

Total removed 13.5 h (WP hours 11.75); core plan net -10.5 h after adding WP14 (78.25 to 67.75 h).

**Design lessons the Moment slice should reuse (notes, not obligations):** define item identity against the real storage (trace revision + chunk sequence + event ordinal; there are no per-item Room rows); persist every derived value only in a fenced store with source rows and delete it in the same transaction as every deletion cause that names a source; give the store a read API and a run-delete/prune cascade; refuse scores and notes on derived runs unless they are fenced the same way; place a selection route inside `TraceRoutes` (the `extras` chain answers first and 404s unknown shapes); bind estimate tokens to the exact tuple set.

**Room version map.** v8 is 3b's (jobs, cleanup, `frame_forget_rev`). The Moment slice takes v9 and v10. The 3c migration, formerly planned as v9, moves after them (v11 or later).
