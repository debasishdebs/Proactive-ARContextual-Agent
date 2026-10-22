# Design proposal: editable context inference and capture-frequency rules

**Status:** independent proposal; design only; no files changed.
**Scope:** one versioned rule model, editable in the canvas DAG and a simpler phone rules list.
**Recommendation:** retain the existing fusion state machine and capture skeleton; add bounded declarative conditions at explicit extension points.
**Success:** the requested driving, walking/running, and head-motion exceptions work without changing sensing cadence or weakening capture guarantees.

## 1. Evidence and precedence

- The [hub design](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/specs/2026-10-21-wave-b-context-hub-design.md) establishes phone fusion, timestamped evidence, context policies, and local walking protection.
- The [configurator design](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/specs/2026-10-21-canvas-slice3-e2e-configurator-design.md) establishes three domains, frozen plans, invariants, and structured steps.
- The [3a plan](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3a.md) supplies the later 60/300-second ruling and separates CORE from LATER.
- The [expression proposal](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/specs/2026-10-22-expression-nodes-design-options.md) supplies useful bounds; its parser and JS evaluator are superseded by the structured-first ruling.
- In `pca-ctx/int`, current fusion disables image-assisted entry into vehicle/flight and image-assisted lowering of the stillness threshold.
- Current vehicle policy rows preserve capture; DRIVING additionally suppresses nudges.
- Current glasses `ContextGate` applies an unconditional local walking veto, unlike the older spec’s SPEED exception.
- Preserve that stronger local veto; do not silently reintroduce the exception through this feature.
- In `pca-s3/int`, plan types already encode the 8,192-byte cap, locked rows, timer gaps, bounds, and staged receiver.
- These observations do not establish that all 3a-core wiring is complete; this proposal begins after its integration milestone.

## 2. Architecture decision

| Approach | Advantages | Limitation |
|---|---|---|
| Parameterise existing fusion only | Smallest change; excellent baseline parity | Cannot express arbitrary conjunctions or selected-device conditions |
| **Bounded rules over existing fusion facts** | Expresses requested rules; retains temporal behavior and safety | Requires explicit rule-selection and provenance interfaces |
| Replace fusion with a general interpreter | Maximum flexibility | Reimplements sensitive state, expiry, voting, and hysteresis |

Choose the middle approach.
The graph describes predicates, candidate context claims, and capture-policy exceptions.
Existing Kotlin owns source intake, expiry, vehicle holds, driver probability, commitment, and capture scheduling.
There is one authoritative Kotlin evaluator, shared by device runtime and simulation.
There is no text parser, JS evaluator, user Kotlin, Python, or arbitrary device code in the first release.

## 3. Execution domains and graph shape

Retain RT_PHONE, RT_GLASSES, and ASYNC lanes and typed signal bindings.
RT_PHONE evaluates context inference using phone evidence and delayed glasses summaries.
RT_GLASSES evaluates immediate head-motion exceptions and applies capture policies.
ASYNC produces image-intent evidence through the existing path.
Each lane remains acyclic; cross-device bindings represent timestamped messages rather than synchronous calls.
Keep `hub.context` as the phone root, expanding its contents into source, condition, inference, and arbitration nodes.
Add named input/output ports: the current broad SIGNAL type is insufficient to distinguish speed, freshness, and boolean predicates.
Every descriptor declares domain, type, units, availability, freshness, privacy class, and static cost.
A rule cannot directly invoke a source, transmit a message, open the camera, or change sensor registration.

## 4. Signal catalogue

| Signal node | Runtime | Meaning and limits |
|---|---|---|
| Head motion | Glasses | Existing per-second gyro maximum/mean/RMS, rotation, motion classification |
| Head-motion summary | Phone | Fixed preceding-minute aggregates carried in the existing hint |
| Still fraction | Both | Existing five-minute summary; unavailable until sufficient coverage |
| Selected BT connection | Phone | Connection to a locally bound device; no address in rule data |
| Car link / Android Auto | Phone | Existing deterministic connection evidence and driver prior |
| GPS speed | Phone | Adapter-produced speed with freshness and quality; no coordinates exposed |
| GPS updating | Phone | Recent accepted fix while updates are requested; availability rather than proof of movement |
| Phone activity | Phone | Current accepted transition state and age |
| Phone steps | Phone | Completed step bucket and age |
| Watch activity | Phone | LiveFit-forwarded state, confidence, age, and connection availability |
| Airplane mode | Phone | Existing mode and duration |
| Image intent | Phone | Existing bounded label history; restricted information flow |
| Effective context | Glasses | Context after message validity checks and local walking veto |
| Capture features | Glasses | Existing glance, novelty, keep-count, and trigger metadata |

All sources retain their opt-in and permission behavior.
A graph referencing an unavailable source does not enable it automatically.
Freshness is mandatory for restrictive claims; “not connected” is distinct from “connection status unavailable.”
Watch RUNNING maps to capture context WALKING, with an optional running detail badge.
Do not introduce a new capture-state enum merely to distinguish walking from running.

## 5. Head movement and the one-minute link

The existing sender refreshes every minute and additionally reports walking transitions, rate-limited to five seconds.
Preserve that behavior; add no per-rule transmissions.
Extend its payload with a small, fixed set of threshold-independent aggregates, initially gyro maximum and mean over the preceding minute.
Accumulate them from the existing one-second feature callback, with coverage and sequence metadata.
Phone inference therefore means “head movement exceeded x during the last complete minute,” not “head movement exceeds x now.”
Display this latency explicitly in both editors.
Immediate “unless head movement exceeds x” policy exceptions run locally on the glasses.
Arbitrary phone-side windows over unrecovered IMU samples are unsupported; the compiler must reject misleading precision.
Missing summary extensions from older glasses make dependent conditions unavailable, rather than reading zero.

## 6. Structured step catalogue

| Step | Output | Restrictions |
|---|---|---|
| Read signal / constant | Typed value | Catalogue entries only; units required |
| Compare | Boolean | Greater/less/equal comparisons over compatible units |
| Membership | Boolean | Closed enum list |
| All / Any / Not | Boolean | Bounded fan-in; unavailable remains explicit |
| Arithmetic / clamp | Number | Basic arithmetic; finite results; compatible units |
| Window maximum / mean / fraction | Value | Glasses one-second inputs; fixed bounded windows |
| Choose | Typed value | Both branches have the same type |
| Fresh / available | Boolean | Cannot manufacture freshness or permission |
| Infer context | Candidate claim | Fixed ContextState and validated non-image support |
| Apply frequency | Policy proposal | Editable state rows and bounded fields only |
| Unless | Exception | Disables its owning restriction and restores the resolved fallback |
| Pass glance when | Boolean | Drop-only guard; reserved timer path is unreachable |
| Built-in fusion fact | Typed fact | Wrapper for confirmed speed, vehicle hold, stillness, flight, or driver choice |

Forms build the AST directly; the canvas renders steps as nodes or expandable groups.
Start with six glasses programs, 32 AST nodes per program, depth 10, four windows, and 256 total cost units per tick.
Charge repeated evaluations and window-update work to the same budget.
Phone rules have a separate bounded budget: initially 16 claims and 256 shared predicate nodes.
Window warm-up requires full configured coverage; incomplete windows cannot justify restrictions.
Arithmetic errors use fixed role fallbacks: no restrictive claim, pass the glance, or restore the resolved policy value.

## 7. Meaning of the requested examples

**Driving:** head maximum over the previous minute > x → selected BT connected → fresh GPS speed ≥ y → infer DRIVING.
The arrows mean conjunction and data dependency, not a requirement that events occur in that chronological order.
BT connection can independently request existing on-demand speed collection; the speed condition does not depend on its own conclusion.
The resulting non-image claim enters existing vehicle commitment and driver/passenger arbitration.

**Walking/running:** head summary > x → watch WALKING/RUNNING → GPS updating → infer WALKING.
The graph can express this conjunction, while protected walking detection may already establish WALKING from other evidence.
A user cannot make the safety veto depend on all three conditions.

**Frequency:** effective DRIVING → cap two kept photos per rolling minute → unless local head movement > x.
“Two per minute” is a ceiling, not a promise to capture exactly two photos.
An optional timer photo gap controls periodic attempts separately.
The exception restores the resolved normal row; it does not bypass heat, battery, wear, camera ownership, or other hard controls.

## 8. Mapping to ContextFusion

Extract an internal normalized-facts boundary from [ContextFusion.kt](/Users/debasishkanhar/Desktop/rokid/pca-ctx/int/android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/context/ContextFusion.kt).
Keep evidence ingestion, ordering, expiry, held facts, contradiction latches, and driver log-odds in existing implementations.
The structured evaluator consumes these facts plus approved source values and returns candidate claims.
Default graph templates reproduce the current candidate-selection order exactly.
Built-in driver-choice and flight-confirmation steps remain finite catalogue operations, not user-authored mathematical reconstructions.
Parameter-only graphs continue compiling to existing `FusionParams` and policy rows wherever possible.
Only predicates that cannot fold into parameters invoke the bounded evaluator.
Restrictive custom claims require approved support: vehicle evidence for vehicle states, stillness support for REST, and existing corroboration for FLIGHT.
A threshold on head movement alone cannot manufacture a vehicle or flight claim.
This is an explicit extension to S50’s parameter-only contract, not a claim that arbitrary rules already fit it.

## 9. Priority, conflicts, and hysteresis

Protected walking arbitration runs first and cannot be removed or reordered.
Retain default state precedence: walking protection, flight, vehicle, rest, UNKNOWN.
User priority orders competing claims within a state family; stable rule ID breaks remaining ties.
Do not use canvas position or arrival order as priority.
Reject duplicate policy assignments without explicit ordered-choice semantics.
Keep one commitment state machine; individual predicate nodes do not own independent timers.
Default dwell remains immediate WALKING, 30-second vehicle entry, 60-second subtype switching, 120-second REST/FLIGHT entry, and 60-second UNKNOWN entry.
Leaving WALKING retains the existing minimum delay.
Retain confirmed-speed pairing, the 180-second vehicle hold, and its restriction to an existing episode.
Show candidate, committed state, pending dwell, winning claim, and losing claims in explanations.
Rule replacement clears rule-dependent pending state and warms from UNKNOWN; do not inherit an old rule’s accumulated dwell.

## 10. Intent must never throttle capture

Merely blocking an “intent → pause” edge is insufficient.
If driving and passenger rows differ, an image vote changing the subtype could otherwise lower capture.
Maintain two fusion views: the normal decision and a non-image decision using the same non-image evidence and rules.
The second view is the counterfactual capture basis; it excludes all image-derived influence.
Send only these coarse states and revision metadata to the glasses.
Evaluate restrictive policy outputs against both views, then combine them permissively: larger cap, shorter minimum gaps, and pass if either guard passes.
Thus adding image evidence cannot make capture more restrictive than the non-image basis.
Apply this protection to derived conditions, exceptions, and policy selection, not only literal intent nodes.
Image evidence may relax capture and influence presentation; DRIVING still suppresses nudges.
The identity policy retains today’s capture behavior because its two vehicle rows have identical capture fields.
Golden tests must verify REST/workout and BT-contradiction cases, where image evidence currently relaxes capture.
The additional fusion view runs on existing phone events and ticks; it adds CPU work but no sensing or wakeup schedule.

## 11. Non-removable invariants and validation

| Invariant | Enforcement |
|---|---|
| Walking protection | Immutable phone arbitration and unconditional glasses veto |
| WALKING / UNKNOWN policy | Rows absent from writable targets; default full-rate behavior |
| Reserved timer attempt | Scheduler-owned path outside all rule dispatch |
| Hard limits | Existing bounds, rolling ceiling, settle minimum, and power controls |
| Intent cannot throttle | Mandatory non-image comparison and permissive combination |
| Sensing unchanged | No writable sensor, classifier, fusion-cadence, or hub-tick fields |
| Manual capture | Existing exemptions and mandatory safety/privacy gates |
| Driving nudges | Always suppressed after rule evaluation |

Use the later S83 bound already represented in [CapturePlan.kt](/Users/debasishkanhar/Desktop/rokid/pca-s3/int/android-companion/core/capture/src/main/kotlin/com/rokid/context/capture/plan/CapturePlan.kt).
The reserved attempt is every 300 seconds in REST, vehicle, FLIGHT, or WorkRest-resting outside WALKING; otherwise every 60 seconds.
It is a camera-open attempt while eligible, not a guaranteed kept photo.
Guards, novelty, and timer gaps cannot suppress it; dedupe, blur, and the hard ceiling still apply.
Caps remain 1–4, with the existing twice-cap ceiling and absolute eight-keeps ceiling; timer gaps remain inherit or 5–300 seconds.
Validation proves structural non-interference: programs have no outputs capable of modifying protected gates or the reserved path.
Type, domain, support-provenance, target allowlists, interval bounds, resource bounds, and strict schema checks establish the remaining obligations.
Glasses repeat validation independently before activation and enforce runtime bounds afterward.
This proves compliance with implemented invariants, not that a user’s inference accurately describes the physical world.
Context guards may intentionally skip walking glances, as permitted by §17; warn clearly while preserving the protected row and timer path.

## 12. Plan encoding and delivery

Store one canonical RuleSet revision containing stable IDs, typed nodes, connections, priorities, constants, bindings, and display metadata.
Both editors update that revision through the same phone repository and Kotlin validation service.
Compile a phone inference projection and a glasses capture projection; private bindings remain outside both.
Keep the complete resolved executable bundle within 8,192 UTF-8 bytes, including both projections.
Exclude layout, labels, explanations, source addresses, coordinates, and authoring history from executable bytes.
Use shared predicate references and compact enum/operator codes with an explicitly versioned dictionary.
Budget approximately 2 KB for existing configuration, 3 KB for phone predicates, 2 KB for glasses predicates, and 1 KB headroom.
These are allocation targets; production canonical encoding decides acceptance, with no truncation.
Introduce an explicit new plan capability/schema; old strict decoders cannot safely ignore new executable fields.
Reuse durable generations, install/session identity, config hashes, staged activation, acknowledgements, and reconnect reconciliation.
Phone inference stays inactive until the matching glasses projection is effective.
Context messages carry the matching generation/hash; mismatches fall back to UNKNOWN.
During any activation gap, permissive fallback prevents mixed-version throttling; distributed activation is not physically simultaneous.
Persist desired/effective state and compensate failures; terminal reverted generations cannot reactivate.

## 13. Glasses cost and battery

Evaluate only on existing one-second feature callbacks, glance events, and scheduler events.
Use preallocated flat evaluation state; allocate and validate at plan application, outside the capture hot path.
Window buffers and stacks remain within an 8 KB evaluator-state budget, distinct from plan bytes.
No new alarms, wake locks, timers, sensor rates, or per-rule Bluetooth packets.
Do not delay a glance to enforce an evaluation-rate limit; use a current cached result or fail open.
Measure evaluator time, allocations, hint bytes, camera-open seconds, and reserved-attempt counts.
The supplied A3 baseline is approximately 0.5%/minute; the existing unrestricted-LIVE target remains ≤0.42%/minute.
Treat CPU estimates as hypotheses; behavioral changes to camera use remain the larger battery risk.

## 14. Canvas and phone editing

Canvas palette groups are Sources, Conditions, Inference, Frequency, and Exceptions, within the existing domain lanes.
Protected walking arbitration and reserved attempts appear as locked explanatory overlays.
Inspectors show units, freshness, summary latency, warm-up, support provenance, and plan/resource usage.
A phone Rules screen offers “Infer context” and “Adjust photo frequency” sections over the same revision.
Cards read “When … and … infer …” or “While … use … unless …,” with editable structured condition groups.
The phone supports creation, editing, enable/disable, ordering, duplication, simulation, and SHADOW/TRIAL publication.
Advanced graphs remain editable through nested forms; shared predicates are identified explicitly before an edit affects multiple rules.
Use optimistic revision checks so simultaneous canvas and phone edits produce a reviewable conflict.
Show Draft, Desired, and Effective versions separately, with rejection reason and trial countdown.
Specific-device selection happens on the phone; the canvas receives only opaque binding handles and generic labels.

## 15. Privacy and trace replay

Keep coordinates, GPS fixes, BT addresses, device names, and certificate material out of plans, traces, exports, and diagnostics.
Use random local binding handles, never address hashes; their private mapping stays in backup-excluded phone storage.
Current `CarSource` collapses devices into link kinds, so selected-device predicates require a new phone-only fact path.
Use current adapters and trusted LiveFit intake; rules never bypass source generations or revocation.
For traces, record privacy-filtered facts, predicate results, source availability, receipt order, and rule/config versions.
Do not record precise GPS speed; retain plan-specific speed-comparison outcomes and confirmation facts.
Exact replay can reproduce the recorded thresholds; changing a speed threshold requires new collection or explicitly uncertain results.
Synthetic fixtures may exercise arbitrary speeds without retaining personal GPS data.
Record temporal snapshots for holds, contradiction latches, commitments, and window coverage.
Apply existing collection consent, encryption, retention, deletion epochs, tombstones, and backup exclusions.
Inference replay must recompute decisions from sufficient evidence; replaying recorded context states cannot validate changed inference.
Report context agreement, transitions, dwell, false restrictive time, walking vetoes, opens, caps, and timer attempts.
For unobserved photos or changed intent opportunities, show uncertainty rather than exact keep or intent claims.

## 16. Verification, rollout, and rollback

Freeze current fusion and capture outputs before extracting interfaces or adding custom rules.
Golden vectors cover every existing fusion test plus receipt ordering, stale sources, clock rollback, speed spikes, holds, and image restrictions.
Compare candidate, committed state, reasons, probability, suspicion, transition timestamps, and capture decisions.
Identity rules must match today’s implementation using the effective hub parameters, including its watch TTL override.
Property tests cover all-false guards, maximum restrictions, unavailable inputs, forged plans, and intent non-interference.
Begin with SHADOW: separate fusion instances, shared received evidence, and no candidate control over real sensors or capture.
SHADOW cannot claim observations that its candidate would have caused; report those as unavailable.
Permit TRIAL only with complete matching replay evidence and existing entry gates; glasses enforce the 60-minute maximum locally.
Phone trial state ends on restart, expiry, or loss of matching glasses state; restart never extends a trial.
Retain Use settings and Undo latest plan; rollback includes phone inference, glasses policy, and revision bindings.
Unrestricted LIVE stays unavailable until the deferred verified-measurement gate exists and the exact configuration meets it.

## 17. Staging and estimated work

Immediately after 3a-core, ship policy-row and bounded-fusion-parameter editing in both interfaces, with SHADOW/TRIAL only.
This requires pulling forward LA2 phone parameter application and the recovery needed for two runtime components.
Label this as a useful first milestone; it does not yet satisfy the requested arbitrary inference chains.
The next milestone delivers structured predicates, selected-device facts, minute summaries, inference replay, and local exceptions.
Full inference replay requires pulling forward hub evidence recording; CORE’s recorded context events are insufficient.
Defer text syntax, JS preview execution, arbitrary temporal sequences, new sensors, and new per-sample algorithms.

| Task | Dependency | Agent-hours |
|---|---|---:|
| Freeze identity vectors and extension contracts | 3a-core milestone | 3 |
| Shared revision service; basic phone forms and parameter editing | Contracts; LA2 seam | 5 |
| Structured catalogue, Kotlin evaluator, and validator proofs | Contracts | 5 |
| Fusion facts boundary, claims, identity templates, non-image basis | Goldens; evaluator | 6 |
| Selected-device facts and existing-hint summaries | Signal catalogue | 4 |
| Bundle encoding, capabilities, coordinated apply and rollback | Contracts; compiler | 5 |
| Privacy-filtered hub traces, snapshots, deletion integration | Fusion interfaces | 4 |
| Exact inference replay and uncertainty reporting | Traces; evaluator | 4 |
| Canvas nodes, inspectors, explanations, resource meters | Compiler; catalogue | 4 |
| Complete phone rule creation/editing and revision conflicts | Revision service; catalogue | 4 |
| SHADOW/TRIAL integration and failure-boundary tests | Runtime; delivery; replay | 4 |
| Device parity, battery measurement, compatibility and review fixes | Integrated build | 5 |
| **Incremental total** | **Excludes remaining 3a-core work** | **53** |

Allow roughly 20% contingency for replay and distributed activation.
Agent-hours exclude user wear time and do not imply elapsed delivery time.

## 18. Risks and open questions

Principal risks are silent baseline drift, stale summaries presented as live signals, mixed plan revisions, and indirect image-driven throttling.
Other material risks are selected-device information leaking through diagnostics, unsupported speed-threshold replay, and greater camera use despite cheap evaluation.
Mitigate these with identity goldens, explicit latency, revision matching, mandatory non-image comparison, privacy tests, and measured trials.

1. **Arrow semantics:** default to conjunction over fresh evidence; ordered event sequences require a later step type.
2. **Head metric:** default phone inference to preceding-minute gyro maximum; immediate exceptions use local features.
3. **Walking SPEED discrepancy:** preserve the current unconditional glasses veto.
4. **Frequency meaning:** default “2/min” to a keep ceiling, with timer attempt spacing edited separately.
5. **Intent and unequal vehicle rows:** default to mandatory permissive comparison against the non-image basis.
6. **Private speed replay:** default to recorded predicate outcomes; changed thresholds require recollection or uncertain results.
7. **Unavailable source:** default to no restrictive claim and a visible explanation; never enable sensing automatically.
8. **Release scope:** default first to parameter/policy editing, then complete structured rules; unrestricted LIVE remains gated.