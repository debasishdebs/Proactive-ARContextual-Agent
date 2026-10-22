**I would revise the plan before dispatching Sonnet implementers.** The main blockers are durable deletion, trial enforcement, publish identity, verification evidence, and cross-domain recovery.

This was read-only. I reviewed the complete plan, the r3 spec—including Re-cut, §3 and §16–§18—and the decision brief, then checked relevant code in the proposed `pca-s2/int` base. I did not run Gradle or device tests.

1. **Critical — `MemoryWipe.generation` cannot serve as the wire wipe generation.**  
   **Tasks 7, 12, 13, 23; [plan’s collect control](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:916).** It is currently an `AtomicLong(0)` that resets on phone process restart; its documentation explicitly assumes no surviving work from an earlier process. Glasses outboxes and imported files invalidate that assumption. After a wipe and restart, an old trace can pass the lower-generation check; conversely, glasses retaining a higher generation can reject legitimate new collection controls.

   **Fix:** introduce a persisted, pairing-scoped deletion epoch, committed before deletion and carried by all durable trace/outbox work. Keep the process-local generation for in-flight jobs. Test wipe → phone death → reconnect/import, and phone reinstall/re-pairing.

2. **Critical — Forget and Delete all lack a complete, crash-safe implementation contract.**  
   **Tasks 7, 12, 13, 21, 23; [deletion task](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:1242).** T7 deletes rows, but does not assign deletion of trace/import files, pending IO buffers, or the glasses mark outbox. An offline pre-wipe tap has no wipe epoch and can recreate a label after Delete all. A range Forget retains the trace ID after rewriting, but chunk rejection only recognizes a tombstoned trace; the old chunks could overwrite the sanitized revision. A crash can also leave old temporary chunks behind.

   There is an architectural blocker: T23 places `CanvasSlice3Forget` in `:services:orchestration`, then calls it from `:services:memory/ForgetService`; orchestration already depends on memory.

   **Fix:** put transactional invalidation/tombstones in memory, with orchestration coordinating files and devices. Use durable trace revisions or tombstone the original ID when rewriting. Fence filesystem publication as well as Room commits. Clear/fence mark outboxes and pending writer buffers. Land basic wipe handling with each recorder, **before M1 installs a collecting build**.

3. **Critical — the glasses do not independently enforce the 60-minute TRIAL limit.**  
   **Tasks 8–9, 19; [receiver rules](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:694).** The receiver accepts `trialEndsAtMs` without requiring it, bounding it, or defining its clock domain. The phone generates it using phone wall time; comparing it directly against glasses wall time can extend or prematurely end the trial. Wall-clock changes can extend it again.

   **Fix:** validate mode-specific required fields on the glasses; map the deadline correctly and enforce a locally measured maximum duration using a monotonic clock. Persist enough information to fail safely across reboot. Test null, expired and >60-minute deadlines, clock offsets, clock rollback, reboot, and duplicate messages that must not extend a trial.

4. **Critical — publish changes the plan hash after checking evidence for it.**  
   **Tasks 3, 19; [hash definition](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:438), [expectation insertion](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:1129).** `expect` is inside canonical hashed JSON, but T19 populates it from the replay **after** replay/calibration gates. Rehashing produces an untested identity; failing to rehash makes the glasses reject it. It also breaks matching a measured trial to the later LIVE request.

   **Fix:** define immutable configuration identity separately from replay-derived supervision metadata, or finalize the entire payload and rerun the required evidence against that final hash. Add a compile → replay → TRIAL → calibration → LIVE test asserting identical effective configuration identity throughout.

5. **Critical — S44 verification cannot be derived from the existing battery summary.**  
   **Task 17; [calibration import](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:1055).** The current [`battery-run.sh` summary](/Users/<user>/Desktop/rokid/pca-s2/int/tools/battery-run.sh:173) reports aggregate duration/drain and heartbeat averages. It does not establish full plan identity, SHADOW/LIVE phase boundaries, continuous wear, unplugged status, or persistent logging. T17 cannot safely turn that output into `verified=true`.

   **Fix:** add a versioned verification artifact and the necessary telemetry/script changes as an explicit prerequisite. Fail closed on missing data, mixed plans/settings, gaps, plugged/not-worn spans, and phase changes. Add negative fixtures for each condition; never trust an imported caller-supplied `verified` flag.

6. **Critical — lost or late acks can leave a FAILED group running on the glasses.**  
   **Tasks 8, 18; [group failure rules](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:1083).** “No ack within 10 min → FAILED and nothing activates” describes only the phone halves. The glasses may already have applied the plan and lost the ack. A late EFFECTIVE, stale EFFECTIVE after REVERTED, or crash between preferences and Room writes has no defined transition. Matching `gen`, `publishId`, mode and hash before activation is also not explicit.

   **Fix:** specify a persisted transition/recovery table. Timeout becomes an unknown remote outcome requiring reconciliation or a higher-generation compensation. Make REVERTED terminal for that generation. Test ack loss, reordering, superseded publishes, and crashes at every persistence/send/activation boundary.

7. **Critical — `listenId` currently disappears before reaching the voice pipeline.**  
   **Tasks 4, 20–21; [interceptor contract](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:1198).** Existing `GlassesInbound` converts `CommandEnvelope` to `GlassesEvent.Cmd(env.command)`, discarding envelope metadata. `HubVoice.onListenStart()` and `VoicePipeline.glassesListenStart()` take no ID. The plan does not assign changes to these interfaces, `PipelineAdapters`, or `VoiceGraph`.

   Also, current `VoicePipeline.onFinal` passes timeout partials into `think` as ordinary text. Merely adding `genuineFinal` to the interceptor does not preserve that information.

   **Fix:** pin the complete listen metadata path and generation-scoped lifetime. Pass genuine-final status from recognition, intercept before publishing `Thinking`, and forward **only unmatched clauses** to Ask. Handle mark/listen channel reordering without creating duplicate moments. Test the real pipeline with spies on AnswerService/model calls, persistence, logs and HUD state. The row/log-only `noTranscriptStored` test is insufficient.

8. **Critical — “rename migration and schema JSON” is unsafe when another v7 lands first.**  
   **Task 7; [renumbering rule](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:184).** Renaming `6→7` to `6→8` can bypass the other branch’s migration. Renaming `7.json` does not produce the merged v8 schema or identity hash.

   The proposed unit tests also do not exercise SQLCipher. Existing [`DatabaseFactoryUpgradeTest`](/Users/<user>/Desktop/rokid/pca-s2/int/android-companion/services/memory/src/test/java/com/rokid/context/memory/db/DatabaseFactoryUpgradeTest.kt:14) explicitly delegates encrypted coverage to a device suite, which this plan omits.

   **Fix:** rebase onto the allocated latest schema, preserve all existing migrations, implement the actual latest→next migration, regenerate the combined schema, and rerun all upgrade paths. Extend the encrypted device suite through both production builders, including a seeded canonical v6 fixture. Update its fixture-shaping helpers: they currently assume current=v6. A controller-owned encrypted migration rehearsal should precede the production DB upgrade; counts alone are insufficient.

9. **Important — exact replay’s trace contract is incomplete.**  
   **Tasks 5, 12, 14, 16; [snapshot and events](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:539).** The snapshot omits detector phase, classifier windows/pose history, pending gate candidates, timer/backoff/episode state, effective gate/profile parameters and other scheduling inputs. Starting collection mid-bout cannot replay exactly from a fresh classifier. A `Frame` lacks the full inputs needed to reproduce window selection; `lastScored()` is especially problematic for blur failures because current scoring returns before updating the last hashes. T12 also does not explicitly emit the declared Close/Plan events.

   **Fix:** freeze a sufficient snapshot/event schema **before** recorder and simulator streams diverge. Either capture all required state, or constrain trace boundaries and record deterministic pre-roll. Replay must preserve candidate selection and flush behavior, not merely call `finish` or echo recorded Open events.

10. **Important — the 25 Hz fallback invalidates native-rate exact parity.**  
    **Tasks 12, 14, 26; [fallback](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:868).** If capture still runs on native samples while recording drops every other sample, replay cannot reproduce threshold crossings, integration and dwell decisions bit-for-bit.

    **Fix:** mark downsampled traces as approximate and ineligible for exact-parity evidence, or retain sufficient native-rate decision input. Do not silently downsample the live classifier to make replay match: that changes the measured capture algorithm.

11. **Important — SHADOW can change real capture.**  
    **Task 9; [reserved timer activation](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:757).** It enables the real reserved timer in every non-SETTINGS mode, including SHADOW, while SHADOW elsewhere promises settings-driven capture with no camera changes. In FLIGHT/settings gaps of 300 seconds, this adds real camera opens and corrupts both the baseline and overhead measurement.

    **Fix:** real reserved opportunities apply to effective LIVE/TRIAL capture; SHADOW simulates/counts its hypothetical reserved opportunities. Resolve the spec wording explicitly. Test SHADOW versus settings using restrictive FLIGHT and REST settings, not only UNKNOWN/WALKING.

12. **Important — identity resolution does not match the actual base’s policy representation.**  
    **Tasks 3, 8; [resolver](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:470).** The base’s [`ContextPolicies.DEFAULT`](/Users/<user>/Desktop/rokid/pca-s2/int/android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/context/ContextPolicies.kt:18) uses `cap=null` and zero gap minima for both vehicle rows. Plan rows require a non-null cap and gaps ≥5 seconds. Converting “unchanged” to cap=4 also turns on `hardNow(cc)` and removes A3’s dwell bonus. Thus classification alone can introduce throttling.

    Further, `CaptureProfile.withContext` takes maxima; it cannot implement all advertised lower plan gaps over an already restrictive settings/context profile.

    **Fix:** resolve **effective profiles**, distinguishing inherited values from hard context caps. Specify composition order: frozen plan → independent battery/heat restrictions. Add identity cases for all states, WorkRest, budget tiers, and settings edits after publish. Preserve the user’s “intent alone never throttles capture” rule.

13. **Important — EFFECTIVE can precede actual, atomic application.**  
    **Task 8; [parameter application](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:701).** `setParams` waits for QUIET, but other providers immediately read the new `ActivePlan`, and EFFECTIVE follows persistence. That permits an old detector with new novelty/caps while the phone activates new fusion. Current `MotionClassifier` also reads its constructor `params` outside the detector; rebuilding only the detector leaves those reads stale.

    **Fix:** stage one immutable configuration and activate it at a defined safe boundary, then ack EFFECTIVE. Update all classifier parameter reads consistently and define bounded behavior when QUIET does not arrive. Test publication mid-bout, during walking, and during an open camera episode.

14. **Important — liveness needs stronger production-path tests.**  
    **Task 9; [reserved implementation](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:740).** The base has no named `lastOpenAt`; introduce and define it explicitly. Current `forceBrief` labels the episode as onset and interacts with analysis and gate flags. `withContext`, WorkRest and heat-SLOW can produce effective gaps above 60 seconds even when plan row values are bounded.

    **Fix:** use a distinct reserved-opportunity cause and independent elapsed deadline. Test every rolling eligible 60-second window through the actual session/camera owner, including continuous head motion, still backoff, WorkRest, heat-SLOW, bind backoff, failed opens and eligibility transitions. Keep both walking vetoes outside plan control; retain only the spec’s explicit SPEED exception. Guards reading `ctx` are allowed by §17.1 and must not be rejected by obsolete motion-only tests.

15. **Important — several dependencies and ownership boundaries are missing.**  
    **Schedule and Tasks 3, 13–18, 21.** T3’s `:services:ai` canonical parity test needs the `:core:capture` dependency that T6 adds later. T13 also consumes trace types without assigning its module dependency. T16 uses counterfactual replay from T15 but declares only T14. M1’s debug publish/collect/replay path has no implementation owner and bypasses normal ≥30-minute/two-state publish evidence.

    **Fix:** add a small interface/build-wiring prerequisite, make T16 depend on T15, and assign an explicitly SHADOW-only M1 harness. Recompute the schedule after adding deletion and evidence prerequisites.

    The shared-file merge map should be explicit:

    | File/area | Tasks | Required coordination |
    |---|---|---|
    | `HubGraph.kt` | T13, T18, T21 | T18 and T21 overlap in the schedule. One owner integrates constructor/callback wiring after leaf implementations. |
    | `CanvasBackendImpl`, `CanvasBackend`, `CanvasGraph`, `CanvasApi` | T2, T6, T15, T17, T19, T21 | These are mostly missing from file lists. Pin service interfaces and give production composition one owner. |
    | `KeyframeGate.kt` | T8 and T14 | Concurrent edits to the moved class; agree scoring/parameter interfaces before either starts. |
    | `DatabaseFactory`, database/schema files | T7 | Single owner; allocate the schema version before dependent work rebases. |
    | Glasses runtime/session/controller | T8, T9, T12, T20 | Already mostly serialized in C; preserve that ordering and pin callback registration/lifetimes. |
    | `MainActivity` | T20 and future hidden-foreground work | The plan correctly postpones hidden-foreground edits until T20 merges; retain that boundary. |

16. **Important — route registration and full-graph ASYNC activation are underplanned.**  
    **Tasks 2, 6, 15, 17–19, 21.** `/api/node-types` currently delegates to `CanvasBackendImpl.nodeTypes()`, which hardcodes ASYNC and the old param format. Editing `CanvasApi` alone will not expose v2 descriptors. Existing runner/publisher shape checks also expect the ASYNC graph; passing the new three-domain pipeline directly will fail or mis-handle it.

    **Fix:** assign backend/interface/composition changes, define an immutable ASYNC projection that preserves tested identity, and test a full graph through the real API and publisher. Pure compiler tests do not cover this integration.

17. **Important — transfer has no complete progress, timeout or charging contract.**  
    **Tasks 4, 12–13.** “One in flight, paced by link callbacks” does not identify a usable completion callback; current send success means handoff, not delivery. Losing the final chunk can leave both peers waiting forever because the phone checks completeness only “at count.” It is unclear whether `send=charging` means phone charging or glasses charging.

    **Fix:** enforce **local glasses charging** before every send and stop immediately on unplug/disconnect. Define whole-transfer timeout/retry and missing-chunk recovery without reintroducing per-sequence resume. Bound base64 input, gzip expansion, event counts and manifest inventory; validate trace IDs before filesystem use. Test missing final chunk, lost ack, unplug mid-transfer and malformed imports.

18. **Important — compatibility tests cover decoding, not mixed-version operation.**  
    **Tasks 4, 8, 18.** The “old JSON” example is a hypothetical older version of a brand-new message. It does not prove old glasses ignore new channels, or that new glasses behave safely with an old phone after previously persisting a plan. Capability discovery before the first publish is unspecified. The global timestamp rule also contradicts pinned acks: several have no session/timestamp fields. Applying reconnect-time freshness checks to old queued marks can discard the promised 24-hour outbox.

    **Fix:** add frozen old-peer behavioral fixtures in both directions, including persisted plans, disconnect/reconnect, first capability discovery, and queued marks. Separate transport freshness from event occurrence time. Correlate every ack with its epoch/generation and define stale-ack rejection.

19. **Important — source provenance and label invalidation are incomplete.**  
    **Tasks 7, 17, 21, 23.** `canvas_sweep` records days but no explicit trace/label revision set. Deleting a label through `LabelRoutes` does not invalidate recall results or publish evidence. Frame Forget and retention need the same cascade hook; T23’s “frame Forget” wording does not ensure existing `expirePhotos` and fact cascades call it. Automatic MOMENT imports for Capture now/starred frames have no producer task.

    **Fix:** record trace revisions, source intervals and label revision/provenance for each result; invalidate on label delete/correction and every relevant Forget/retention path. Add the automatic import producer and idempotency tests, or explicitly defer it.

20. **Important — several proposed tests can pass without testing the claimed property.**  
    **Tasks 7, 14, 16–17, 21.**

    - An exact replay that consumes recorded Open events cannot independently validate open scheduling.
    - A fixture generated solely by the same moved classes can reproduce the same bug.
    - T16’s permitted JSON stub can acknowledge everything while the real glasses path is broken.
    - A sweep with no runner dependency naturally makes zero calls to a fake runner.
    - A reflection check on label fields cannot prove transcripts never reach Ask, logging or other persistence.

    **Fix:** freeze independent pre-change decision goldens and use the real session/receiver seams. Require demonstrated RED cases: suppress the reserved path, remove walking veto, alter a threshold boundary, bypass a fence, mutate the publish hash, and forward a mark clause to AnswerService. Each relevant test must fail under its corresponding mutation.

21. **Important — recording overhead and replay workload need realistic resource gates.**  
    **Tasks 12, 15, 17, 26.** A 35-minute battery-percentage run has ≈0.029 %/min resolution per percentage point, already larger than the 0.02 recording budget. It cannot reliably establish that budget. It also does not match the specified S44-style duration.

    Replay accepts `List<Ev>` for potentially 12-hour traces and up to 200 configurations. Materializing millions of objects or allowing concurrent sweeps risks memory pressure in the process hosting the glasses link. Existing canvas activity tracking recognizes experiment runs, not these new jobs.

    **Fix:** use longer/repeated paired measurements or a validated higher-resolution measurement source, against A3’s **0.49 %/min** baseline. Budget SHADOW separately. Stream chunks, bound queued recorder IO and replay concurrency, and define cancellation/recovery on thermal stop, service shutdown, Developer-mode changes and process death. Keep deployed plan reconciliation outside the canvas server’s lifetime.

22. **Important — graph edits can promise semantics the compiler does not implement.**  
    **Tasks 2, 6.** Folding both `>` and `>=` into one onset value loses equality semantics: current onset is strict `>`, while bout duration/rotation use `>=`. Acyclic/reachable validation also allows graph arrangements that collapse to identical unordered parameter blocks. `signal.in/out` span multiple domains, but the pinned descriptor has only one domain value and no explicit instance-domain rule.

    **Fix:** specify the supported wrapper topology and signal-domain resolution. Reject unsupported ordering/branching/operators with fixed texts. Add exact-boundary tests for every threshold feature, plus reordered/disconnected policy and binding cases.

23. **Important — tasks are too broad for their allotted reviewable units.**  
    The 71.5-hour addition is arithmetically correct; the confidence and 31-hour wall-clock forecast are not justified by these unresolved contracts.

    | Tasks | Main sizing problem | Concrete split |
    |---|---|---|
    | T7, 2.5 h | Seven tables, fences, migration, wipe and backup | Schema/DAOs; deletion integration; migration verification |
    | T12–13, 6.5 h | Two recorders, transfer, import, clocks, quotas and crash handling | Record format/snapshot; recording; transport/intake |
    | T14–15, 5.5 h | Exact scheduling parity plus counterfactual simulation | Parity first; estimates/API only after parity passes |
    | T18–19, 6 h | Distributed persistence, reconciliation, gates and rollback | State-machine recovery; evidence gates; UI/actions |
    | T20–21, 6 h | Gesture/outbox plus cross-module listen correlation and privacy | Listen metadata contract; tap/outbox; interceptor integration |
    | T25, 3.5 h | Multiple views and complete workflow integration | Replay/sweep views; timeline; publish/rollback |

    **Fix:** estimate again after the interface and privacy prerequisites are explicit. Each task should end with independently testable production behavior, not only leaf classes.

24. **Minor — battery hours are off by a factor of 60.**  
    **Task 15; [formula](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md:984).** `100 / pctPerMin` is minutes.

    **Fix:** hours = `100 / pctPerMin / 60`; pin A3 to approximately **3.40 h** and A2 to **2.78 h** in tests.

25. **Minor — backup work should extend existing tests, not replace their stronger rules.**  
    **Task 7.** All three named new phone prefs files are explicitly ruled out of backup, which is good. However, `BackupRulesTest` has a separate prefs-ruling classification as well as XML expectations. The glasses already have `GlassesBackupRulesTest`, with exact assertions excluding every storage domain.

    **Fix:** add phone names to the appropriate classification and both cloud/device-transfer XML sections. Extend the existing glasses test while preserving blanket exclusions; do not create a duplicate class or weaken those exclusions. Cover temporary/import/rewrite paths too.

26. **Important — P6 removes scope explicitly requested by §16.2.**  
    **P6, T20, final pointer.** Deferring hidden foreground is understandable, and its later merge boundary is sensible, but it is a scope decision, not an implementation detail. In the default icon workflow, glasses taps still cannot mark.

    **Fix:** record the deferral as an explicit accepted scope change and state the usable mark entry points for this release. Otherwise include the spike-gated foreground work and re-estimate it.

**For the 60-second versus 120–300-second question, I recommend allowing ordinary policy gaps up to 300 seconds while keeping an independent, unsuppressible ≤60-second reserved timer in LIVE/TRIAL.** Clamping every gap to 60 seconds needlessly changes ordinary scheduling as well as providing the mandatory opportunity. The timer must bypass policy gaps, novelty and guards, while preserving eligibility, camera ownership and the specified hard ceiling.

That recommendation requires a documented amendment to current S31/§8; it must not be silently implemented. Until amended, the stated 5–60-second bounds remain binding. In either design, call the result a settings-derived baseline rather than claiming battery-identical identity, and measure the added reserved-camera cost against A3.

The **8,192-byte cap and allowing guards to read `ctx` are correct later rulings**. Keep them. Add production-path UTF-8 byte-boundary tests and finite-number validation, and remove obsolete 4 KB/motion-only assertions from the implementation checklist.