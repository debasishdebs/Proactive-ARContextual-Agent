# Task 5 review (739b9793) and Task 4 fix round (7f9b7c6d)

Reviewer: Opus, read-only. One pass over review-8e8bf71c..7f9b7c6d.diff (1,802 lines, three pages). Files outside the diff were opened only for the named checks at the end.

## Task 4 fix round (7f9b7c6d)

### Spec Compliance
- ✅ Spec compliant. Every Task 4 review finding the fix round was asked to close is closed:
  - **LIVE refusal:** LIVE is refused as `unsupported:live` before anything is staged (PlanReceiver.kt:49). `liveRefusedWithoutCap` asserts that nothing is committed and that a later LIVE leaves the staged SHADOW in place.
  - **Still fractions:** capped at 1.0 (PlanBounds.fusionRange). `stillFractionsAreCappedAtOne` checks the exact text at 1.0 and at 1.01.
  - **Depth cap:** 32. `tooDeep` stops after 33 levels, and the walkers run inside `runCatching` (PlanValidator.problems).
  - **Duplicate keys:** refused, compared after unescaping (PlanJson.kt:92).
  - **Strict JSON numbers:** enforced both in `num()` and in the validator's `finite` walker.
  - **Boot id:** read once (PlanReceiver ctor). `bootIdIsReadOnceNotPerTick` asserts exactly one read.
  - **Terminal re-ack:** keeps the original mode and publishId through `TerminalMeta`. A second revert re-acks the first reason. The activated, tick and revert acks carry publishId.
  - **Multibyte UTF-8 boundary through the receiver:** `multibyteUtf8BoundaryThroughTheReceiver` asserts `chars <= 8,192` on the 8,193-byte text, so a `length` mutation is caught.
  - **KDocs:** `resolve`, `identity` and CanonicalJsonParityTest.
  - **Weak tests:** `Charsets.UTF_8`; ReservedBoundTest now uses independent literals covering every state; the vehicle rows are compared with `ContextPolicies.DEFAULT`; `wallClockRollbackDoesNotExtend` now re-sends a duplicate.
- ⚠️ Cannot verify from this diff:
  - `ReceiverState` gained `publishId` and `terminalMeta`. No persisted form exists yet, so Task 11's store must default both fields on load.
  - A missing `terminalMeta` entry already falls back gracefully to the current mode and publishId.

### Strengths
- **The duplicate-key scanner is correct for valid JSON:**
  - it is iterative;
  - it handles escapes (`\"`, `\\`, `\/`, `\uXXXX` including surrogate halves, which kotlinx decodes the same way);
  - braces inside strings do not confuse it;
  - array elements are never treated as keys.
  - For invalid input (for example `\u+041`, which `toIntOrNull(16)` accepts), it can only add a failure to a text that kotlinx refuses anyway.
- **The number regex is exactly the JSON grammar.** `1e400` passes the grammar and is then refused as non-finite. `1e3` passes as a number but is refused for an integer field.
- **The LIVE refusal sits after the terminal, stale and duplicate checks.** So a re-sent terminal gen still gets its REVERTED re-ack, and a LIVE message can never move `appliedGen`.

### Issues
#### Critical
None.
#### Important
None.
#### Minor
1. **The receiver-path deep-nesting test cannot fail under the depth-cap mutation** (PlanReceiverTest.kt:231).
   - It nests only 4,000 levels, which recursion handles on a host JVM, and it asserts only REJECTED. Missing keys reject the plan anyway.
   - The validator test (100,000 levels, exact text) is the real guard.
   - Fix: assert the reason `bounds`, or use 100,000 levels.
2. **The commit trailer is still `Claude Sonnet 5.5`** on both commits. Global Constraints specify the Opus 5.5 line.
3. **Task 4 review Minors 1 and 2 (empty `phoneInstall`, gen exhaustion) are unaddressed.** That is acceptable under FAST MODE; noted for Task 27.

### Assessment
**Task quality:** Approved.
**Reasoning:** Every requested fix is present, correct and tested with tests that can fail. The one weak test is backed by a strong validator-level test.

---

## Task 5 (739b9793)

### Spec Compliance
- ✅ Spec compliant with the brief. All seven named tests exist:
  - `oldGlassesNeverAcksPhoneShowsTooOld`
  - `oldPhoneHudSettingsDecodesPlanAwareFalse`
  - `capsAckOnReadyWithoutPlan`
  - `ackFromOtherInstallIgnored` (glasses-link and Hub)
  - `ackFromOldSessionIgnored` (the Hub version is `...AndCurrentPasses`)
  - `queuedMarkOlderThanSessionAccepted` (both)
  - `oldDecoderIgnoresListenId`
- **Protocol:**
  - `PROTOCOL_VERSION` is still 1, and `channelNamesAndVersion` asserts it.
  - Every new field has a default except the identity fields: `gen`, `epoch`/`seq`/`atMs`/`atElapsedMs` and `traceId`/`ok`. That matches the pinned shapes.
  - `caps` defaults to `[]` as pinned.
- **The Hub passes an ack only on install plus session, and counts the rest** (PlanAck.kt:24, Hub.kt:300, 424).
- **Marks are gated only on `v`** (GlassesInbound, MARK).
- **No `HubGraph`, `CanvasApi`, `MainActivity` or `DatabaseFactory` edits.** `git diff --stat 8e8bf71c..7f9b7c6d` on those paths is empty.
- **Logs are counts only:** the stale line at Hub.kt:424 prints only the count, and marks and acks are never logged.
- ❌ Extra (Minor): the round-3 core table, which is binding and wins over task text, says "**`TRACE_CHUNK`/`TRACE_ACK` and `CommandEnvelope.listenId` are LATER**". This commit adds:
  - `TRACE_CHUNK`/`TRACE_ACK` and their types;
  - `GlassesEvent.TraceChunkReceived` and the inbound decode;
  - `Hub.onTraceChunk`;
  - `CommandEnvelope.listenId` (Protocol.kt:43, 294, 383).

  The brief itself lists `oldDecoderIgnoresListenId`, so the plan contradicts itself. The additions are additive and inert (no-op sinks). The risk is that an unbounded `TraceChunk.b64` is decoded on the phone with no S73 bound check before Task 18 exists. Either keep them and note them as pre-landed for LATER, or drop the inbound `TRACE_CHUNK` decode until Task 18.
- ⚠️ Cannot verify from this diff:
  - Mark idempotency by (epoch, seq) and fencing by `deletionCounter` belong to Task 31. The Hub forwards every mark.
  - Gen matching of MATCH acks belongs to Task 26.
  - `planAware = false` with a persisted plan leading to `auto:old_phone` belongs to Task 11.

### Answers to the scrutiny points
1. **Unknown enums.**
   - `Wire.json` has `coerceInputValues = true` and `ignoreUnknownKeys = true`, both at base 8e8bf71c too.
   - An unknown `PlanMode` decodes to **SETTINGS** (the property default), never to LIVE or TRIAL. The test checks this with `"HYPER"` (WireSlice3aTest).
   - On the glasses, an unknown mode from a newer phone stages "use settings" for that gen, which is fail-safe. It cannot reach TRIAL, because TRIAL also needs `trialMaxMs` and a valid plan.
   - On the phone, an unknown `PlanAckState` from newer glasses decodes to REJECTED, which is conservative.
2. **The fixtures are hand-written, not device captures. They do match the base format.**
   - **`old_hud_settings.json`:** carries exactly base HudSettings' fields minus the nullable `quietHours` and `askBlockedReason`, which `explicitNulls = false` omits. I checked this with `git show 8e8bf71c:.../Protocol.kt`.
   - **`old_glasses_hello.json`:** a valid subset of base `Hello`. Its omitted fields are nullable or defaulted.
   - **`old_command_envelope.json`:** uses the `kind` discriminator and `Confirm(proposalId, yes)` as in base.
   - **New phone with old glasses:** the old glasses subscribe per channel (HudController.kt:94-101), so `pc_capture_plan` is never delivered to them, and `planAware` is dropped by `ignoreUnknownKeys`.
   - **Old phone with new glasses:** base `GlassesInbound` has `else -> emptyList()`, so the caps ack is ignored.
   - Weakness: `OldHudSettingsSubset` has 3 fields. It is a subset, not a frozen copy (Minor 4).
3. **Ack correlation and spoofing.**
   - A CAPS-classified ack only sets `CAPABLE` and calls `onPlanCaps`. It can never reach `onPlanAck`, so a stale or rogue gen-0 ack cannot spoof a MATCH.
   - An ack from another install or session needs both values equal. Since `sessionStartMs` is the phone's clock at `onReady` (Hub.newSession), an earlier session's ack cannot match.
   - On reconnect, `onReady` resets the tracker and `sessionStartMs`. An in-flight ack from the previous session is STALE, and Task 26 must resend.
   - **Who can write these channels:** only the paired glasses app over the CXR bridge. No other phone app can inject into `GlassesInbound`, so pairing is the trust boundary, as in Task 4.
   - **A forged or replayed mark from a rogue paired peer** can only add a time label. It carries no content, and dedup is Task 31's job.
   - **The 10 s TOO_OLD grace** is measured from the phone's receipt of the first hello. The caps ack is sent immediately after the hello, in the same `phoneMessage()` call (HudController.kt:183), so link latency cancels out.
   - **A late caps ack recovers:** `caps` is checked first in `state()`. Only a lost caps ack, or the reconnect case in Important 1b, gives a TOO_OLD that lasts the whole session.
4. **Caps ack chatter.**
   - There is one small message per glasses session open, guarded by `_sessionOpen.compareAndSet` and `!exited`.
   - The test checks that a second phone message sends nothing more and that a new session sends it again.
   - It adds no new wakeup, timer or sensor. The battery impact is negligible.
5. **Defaulted Hub parameters.**
   - The Hub parameters all default to no-ops, and `phoneInstall` defaults to `""`.
   - With nothing wired, the only behaviour change is two trackers and a `when` branch on new events that an old glasses build never sends. No existing path is altered.
   - `planAware = true` is set on the single `Channels.SETTINGS` send path (StateFrameBuilder.kt:69; Hub.kt:354 is the only send). Old glasses ignore it.

### Strengths
- The gate is a small pure class that is easy to test. The Hub wiring is about 10 lines, and the Hub test covers the real `onReady` and `sessionStartMs` path, including "the current session passes".
- Task 4's duplicate local wire types are deleted, so there is one set of types. The receiver passes `caps = CAPS` explicitly on every ack because the pinned default is `[]`.
- `capsAckOnReadyWithoutPlan` asserts the content, once per session, and again after a reconnect, and it has a recorded mutation RED.

### Issues
#### Critical
None.

#### Important
1. **The contract between the glasses' acks and `PlanAckGate` is implicit, and it breaks as built.** Two parts:
   - a. **`PlanReceiver` never sets `sessionStartMs` on any ack** (PlanReceiver.kt:124 builds every ack with `sessionStartMs = 0`).
     - The gate requires `ack.sessionStartMs == current session` (PlanAck.kt:24).
     - So every real receiver ack (STAGED, EFFECTIVE, REVERTED, REJECTED) for gen ≥ 1 is classified **STALE** and dropped unless Task 11's adapter re-stamps it. A publish would then never see EFFECTIVE, and REVERTED (`auto:trial_end`, `auto:ceiling`) would never reach the phone.
     - No test runs a receiver ack through the gate, and no KDoc tells Task 11 to stamp.
     - Fix:
       - `onMessage` acks echo `m.sessionStartMs`;
       - document on `PlanReceiver` and `HudController` that the glasses adapter stamps acks it emits by itself (activated, tick, revert) with the latest phone `sessionStartMs` it has seen (from `pc_phone_ctx` or `pc_motion_ctx`, both sent in `onReady`);
       - add a cross-module test: receiver ack, stamped, then `PlanAckGate.classify`, expecting MATCH.
   - b. **The CAPS rule is defined by the absence of fields** (gen 0, `""`, 0, no reason). But the `capsAck` KDoc says "Task 11 fills in the applied plan" (HudController.kt:73).
     - If Task 11 puts the applied gen, install or hash in it (as the KDoc invites), the gate classifies it MATCH or STALE (session 0 means STALE). The peer then never becomes CAPABLE, and the UI shows "Glasses app too old" for a new build.
     - Related: a phone-side `onReady` without a glasses-side session close (the glasses session stays open, so neither hello nor caps is re-sent on open) gives a false TOO_OLD after the next frame hello. `onHelloRequest` (HudController.kt:159-166) sends a hello but not the caps ack.
     - Fix (cheap):
       - in the Hub, mark the peer capable on **any** decoded `pc_capture_plan_ack`, STALE included (any ack proves the channel);
       - or also send the caps ack on `onHelloRequest`;
       - and correct the KDoc to S74: "gen 0, no plan, no install, no session".
     - Test: the caps ack with `phoneInstall` set still yields CAPABLE.

#### Minor
1. **Extra versus the round-3 table** (see ❌ above). `TRACE_CHUNK` is decoded with no size bound before Task 18 exists.
2. **The glasses-link `queuedMarkOlderThanSessionAccepted` assertion is vacuous** (Slice3aWireTest.kt:29-34). It builds a `PlanAckGate` that is never used and asserts that its `ackStale` is 0, which cannot fail. The test also duplicates `markIsDecodedWhenOlderThanSessionStart`. Drop the gate line.
3. **The return value of the caps-ack `bridge.send` is ignored, and `capsAck()` runs unguarded** (HudController.kt:183).
   - A failed send is never retried in that session, which with Important 1b means TOO_OLD for the whole session.
   - A throwing Task 11 lambda would abort `onSessionOpened()`.
   - Wrap it in `runCatching` and count a failure.
4. **The "frozen copy" tests decode with today's `Wire.json` and a 3-field `OldHudSettingsSubset`.** This is adequate only because `ignoreUnknownKeys` is pinned at base.
   - The fixtures are hand-written, not captured from a device or produced by base code.
   - Optional: generate them once from `git show 8e8bf71c` code, and note their provenance in a comment.
5. **The CAPS rule could misclassify a gen-0 receiver ack** for a phone with an unset install (`""`) and session 0. Gens start at 1 (Task 26), so this is theoretical. A `require(gen >= 1)` in Task 26 or the receiver closes it.
6. **`Log.i` on every stale ack.** It is count only and compliant, but it could be noisy during a reconnect storm. Consider logging every Nth.
7. **The commit trailer is `Claude Sonnet 5.5`** (Global Constraints: Opus 5.5).

### Checks run (named risk, then what was checked)
- **Forbidden files:** `git diff --stat 8e8bf71c..7f9b7c6d` on `*HubGraph* *CanvasApi* *MainActivity* *DatabaseFactory*` is empty.
- **Unknown-enum and old-decoder semantics:** `Wire.json` at base and at head has `ignoreUnknownKeys`, `coerceInputValues`, `explicitNulls = false` and the `kind` discriminator.
- **Fixture provenance:** I compared the fixture fields with base HudSettings, Hello and MotionHint via `git show 8e8bf71c:.../Protocol.kt`.
- **Old glasses ignoring new channels:** HudController subscribes per channel (:94-101).
- **The only settings send path:** a grep of `Channels.SETTINGS` in phone/hub finds only Hub.kt:354.
- **Caps on reconnect and hello requests:** read HudController.onHelloRequest (:159-166), Hub.requestHello (:460-467) and Hub.newSession (:320).
- **Receiver ack fields:** PlanReceiver.ack (:124). There is no `sessionStartMs`.
- **Not run:** Gradle or tests (read-only review). The tests I would add are the stamped receiver-ack-through-gate MATCH test and the caps-ack-with-install CAPABLE test.

### Assessment
**Task quality:** Needs fixes (one Important, small).
**Reasoning:** The wire is additive and correctly defaulted at v1. Unknown values fail safe, and the gate cannot be spoofed into MATCH. However, the receiver's acks carry no `sessionStartMs`, and the caps-ack contract is left implicit and contradicted by its own KDoc. As wired, the correlation would drop every real ack or misreport new glasses as too old once Task 11 lands.
