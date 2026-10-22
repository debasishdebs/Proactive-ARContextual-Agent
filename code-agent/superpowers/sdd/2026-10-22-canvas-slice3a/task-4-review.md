# Task 4 review: CapturePlan, bounds, validator, resolver, PlanReceiver (2481dc40)

Reviewer: Opus, read-only, one pass over review-c4d488ce..2481dc40.diff (1,318 lines, read in two pages). Files outside the diff were opened only for the named checks listed at the end.

### Spec Compliance

- ✅ Spec compliant against the Task 4 brief. Every named type, text, bound and test is present. The pinned interfaces match, with one addition: `ReceiverState.effective`, which defaults to false and is needed for S69 staged versus EFFECTIVE.
  - Validator texts: PlanValidator.kt:591, 593, 615, 627/628, 640, 641, 645, 646, 660/663, 681, 683, 685 (en dash U+2013 at :584).
  - Gaps 0 or 5,000–300,000 in every row: PlanValidator.kt:682. capPerMin null or 1–4: :681. fastKmh 20–40 and the others ±50 %: PlanBounds.kt:177-194, which matches spec §8 at design.md:857.
  - Glance and gate bounds equal spec §8 (design.md:853-861): PlanBounds.kt:163-173.
  - configHash is 12 hex over the canonical config only: PlanJson.kt:281-283. Strict decode with non-finite refusal: PlanJson.kt:286-372.
  - Every TRIAL rule of S59 is in PlanReceiver.kt:427, 452-478.
- ⚠️ Cannot verify from this diff:
  - Unrestricted LIVE is refused only on the phone (Task 27). The glasses accept LIVE (see Important #1).
  - The hard ceiling min(2 × cap, 8) is Task 12. No constant for it exists here.
  - Task 11 must keep `PlanReceiver` single-threaded. It is documented "Not thread-safe" (PlanReceiver.kt:413), and `onMessage` (link thread) and `tick`/`activated` (session tick) will race.
  - Task 11 must supply a `bootId` that really changes per boot. With a constant bootId, only the `now < start` check (PlanReceiver.kt:476) detects a reboot. A reboot late in a trial whose start was late in the old boot then lets the trial run up to about start + max on the new boot.
  - Task 11 must run `tick`, or an equivalent startup check, **before** applying a persisted TRIAL.
  - The meaning of `ctxBound` (identity value `true`) is not defined in the spec (no hit in design.md). Confirm that true means today's behaviour.
  - A missing policy row is accepted by `decodeStrict` (an empty `policy` is legal). `ContextPolicies.of` maps a missing key to the UNKNOWN row, which is less restrictive, not the default REST or FLIGHT row. Task 7 and Task 11 must pin what an absent row means.
  - Mutation REDs are claimed in task-4-report.md but **not recorded in the commit body**, as the plan preamble requires. The report also says "Concerns: see handback", but no concerns are written in the file.

### Strengths
- **The closed schema is the real security boundary, and it is tight.**
  - `Obj.done()` (PlanJson.kt:367-371) refuses leftover keys at every level.
  - Only two maps are open: `phoneFusion`, which the validator allowlists from raw (PlanValidator.kt:644-645), and `meta.expectOpensP95PerMin`, which is inert in core.
  - So S84 key smuggling by case, Unicode look-alikes or nesting cannot reach any effect. It ends in strict-decode failure, which the receiver maps to `schema` (PlanReceiver.kt:439). The regex refusal (PlanValidator.kt:585, IGNORE_CASE, recursive :623-633) is defence in depth on top of that.
- **The size gate comes before any parse** on the production path (PlanReceiver.kt:432-434).
- **The hash is recomputed and checked against both the message and the embedded hash** (PlanReceiver.kt:440), so a mislabelled config cannot be staged under another plan's identity.
- **Type strictness:**
  - integers reject "1.0" and "1e3";
  - strings are not accepted as numbers or booleans (PlanJson.kt:333-345, 358-359);
  - out-of-range Long and Int are refused.
- **The identity plan equals `ContextPolicies.DEFAULT` exactly.** I checked it against ContextPolicies.kt:
  - REST 2 / 30,000 / 60,000 / 30,000 / 120,000 with forceSettle;
  - FLIGHT 1 / 120,000 / 300,000 / 120,000 / 300,000 with forceSettle;
  - both vehicle rows are `UNCHANGED` (null, 0, 0, 0, 0, forceSettle false), and DRIVING has nudges false.
  - The glance values equal `GlanceParams()`, and the gate equals `KeyframeGate.HAMMING` 10 / `BIG_CHANGE` 22 with ring `all` (the ring is compared with `ring.all`, KeyframeGate.kt:125).
  - The pinned canonical text (CanonicalJsonParityTest.kt:1315) shows exactly these values.
  - `identityEqualsDefaultPoliciesExactly` compares whole data classes (PlanResolverTest.kt:1024), so a new `ContextCapturePolicy` field cannot slip through.
- **`reservedBoundMs` is identical to the golden recorder's rule.** I checked DecisionGoldenRecorder.kt:31-33 against CapturePlan.kt:146-149, including VEHICLE_DRIVING and S83a (UNKNOWN while resting gives 300 s).
- **TRIAL handling is sound:**
  - the clock starts once at activation (`?:` plus the `!effective` guard, PlanReceiver.kt:457-460);
  - a backwards elapsed clock reverts;
  - a missing `trialMaxMs` reverts (fail-safe, :475);
  - a staged-but-unactivated TRIAL across a reboot reverts (the bootId check runs before the start check, :473).
- **The receiver persists before it acks**, and `duplicateReackedNotReapplied` counts commits (PlanReceiverTest.kt:877). That is a real behavioural assertion.
- **The UTF-8 test is designed well.** The 8,193-byte text is 8,192 chars, so a `length` mutation is caught (PlanValidatorTest.kt:1216-1232).

### Issues

#### Critical (Must Fix)
None.

#### Important (Should Fix)
1. **PlanReceiver.kt:429-449: the glasses stage a LIVE plan in CORE**, although the receiver's own `CAPS` (:500) omit `live` and round 3 says "unrestricted LIVE is refused outright".
   - The only refusal is on the phone (Task 27). Task 11 applies "LIVE and TRIAL" (plan Task 11 line 18).
   - So a buggy phone, a rogue phone, or a future phone build gets an **unbounded** LIVE on the glasses. There is no trial clock, which is exactly what S59 put on the glasses so the phone need not be trusted.
   - Fix: `if (m.mode == PlanMode.LIVE && "live" !in CAPS) return reject(m, "unsupported:live")`, using the pinned reason `unsupported:<cap>`.
   - Test: `liveRefusedWithoutCap`.
   - This is a plan gap: the brief lists LIVE among the accepted modes. It belongs here because the receiver is the enforcement point.

#### Minor (Nice to Have)
1. **Install switching resets the receiver's history** (PlanReceiver.kt:421, 445).
   - A message with a different, self-declared `phoneInstall` skips the stale and terminal checks and drops the terminal map.
   - Flipping A to B and back to A re-applies a gen that A had reverted (for example `auto:ceiling`).
   - This is no worse than a paired phone sending gen+1, because reverts are terminal per gen, not per `configHash` (S62). Pairing is the only trust boundary.
   - Note for Task 27: never republish a `configHash` that was reverted with `auto:ceiling`. Consider refusing an empty `phoneInstall` for plan modes.
2. **Gen exhaustion.** A first message with gen `Long.MAX_VALUE` makes every later gen from that install stale, so the phone can no longer push "Use settings".
   - Camera liveness is unaffected (the plan is bounded, and a TRIAL ends).
   - Consider a sanity cap on gen.
3. **Deep nesting.** The validator walkers `finite`, `sensing` and `wakeMotion` (PlanValidator.kt:610-655) are plain recursion and are not inside `runCatching` in the receiver (:436).
   - With the 8 KB cap, the worst case is about 4,096 levels of `[[[[`. That is probably fine on a 1 MB ART thread stack, but it is untested.
   - kotlinx parsing itself switches to deep recursion past 200 levels.
   - Add a test with `"[".repeat(4000)` as a value in the plan (`deepNestingRejectedNotCrash`), or refuse depth > 6 before walking.
4. **`decodeStrict` is laxer than JSON for numbers** (PlanJson.kt:333-337, 362-366).
   - kotlinx's tree reader accepts unquoted tokens, and Kotlin `toDoubleOrNull`/`toFloat` follow Java's float grammar. So `0.6f`, `1d` and hex-float literals probably decode in float and double fields.
   - The hash stays consistent, because it is over the decoded values, so this is a strictness gap, not a safety gap.
   - Tighten with a JSON-number regex in `num()` and add a test.
5. **Duplicate keys are not refused.** kotlinx keeps the last one silently. Both sides use kotlinx, so the effective config and the hash still agree. Document this or refuse it.
6. **`stillFrac`/`stillFracWithImage` at ±50 % allow 1.275 and 1.05** (PlanBounds.kt:187, 191-194).
   - A fraction above 1 is meaningless: REST becomes unreachable, which costs battery.
   - Cap fractions at 1.0. This is within the letter of spec §8.
7. **The allowlist choice is acceptable.** It leaves out walking (`hintWalkTtlMs`, `stepsWalkPerMin`), flight, image, intent, priors and confidence.
   - Every allowlisted key can only bias toward REST or VEHICLE, which the 300 s reserved bound limits.
   - Note that `fastKmh` 20 (spec-mandated) lets cycling at 20 km/h or more read as vehicle (300 s bound instead of 60 s), because SPEED is the one walking-veto exception.
8. **`PlanResolver.resolve` neither validates nor allowlists** (PlanResolver.kt:561-566).
   - A partial with `capPerMin: 99` or `phoneFusion.walkingVeto` resolves successfully. This is by design ("no clamping"), but the KDoc should say the result must go through `PlanValidator` before it is published.
   - Merge semantics are correct: an absent key comes from base; an explicit null is kept for `capPerMin`; null for a required block fails; an extra row fails.
9. **`identity(base)` takes `ctxBound` and `phoneFusion` from `base`** (PlanResolver.kt:552-555). It is exact identity only with the default `base`. Either rename it, or document that callers needing today's behaviour must pass nothing.
10. **Cross-runtime float formatting.** Canonical numbers come from `Float.toString()` and `Double.toString()` (PlanJson.kt:242-246, 276).
    - Today the phone, the glasses (Android libcore) and the JDK 17 host all use the old FloatingDecimal, so this is deterministic.
    - JDK 19+ changed to shortest-repr, which would move pinned hashes for some values in host tests. Values never seen on device are unaffected.
    - A mismatch fails safe (`schema` rejection).
    - Optional: quantize glance floats, or canonicalize them from `BigDecimal` of the decimal string.
11. **`CanonicalJsonParityTest` is not an independent parity check** (services/ai CanonicalJsonParityTest.kt:1289-1316).
    - `:services:ai` runs the same `:core:capture` code on the same host JVM, so it can only detect a change to a pinned value. It will never catch a phone/glasses divergence (there is none, since the code is shared) or an ART difference.
    - Its KDoc claims that "the same fixtures are asserted in :core:capture". Only the hash is (PlanJsonTest.kt:819-822); the canonical identity text and the escape fixture are not.
    - It is still worth keeping as a change detector. Fix the comment.
12. **Tests that cannot fail, or only nearly:**
    - The `ReservedBoundTest` loop re-implements the formula under test (ReservedBoundTest.kt:1248-1252); only the three explicit asserts count.
    - `wallClockRollbackDoesNotExtend` (PlanReceiverTest.kt:944-952) only proves that `trialEndsAtMs` is ignored. No wall clock is involved, so the name overclaims.
    - `identityVehicleRowsUnchangedNoHardFlag` asserts nothing about a hard flag (PlanRow has none).
    - The pinned hash and canonical text were taken from the implementation's output. I verified their values independently against ContextPolicies.kt, GlanceParams.kt and FusionParams, so they are correct.
13. **Test-order evidence.** Apart from the two mutation REDs and the parity placeholder RED, the report has no RED-before-GREEN evidence. The tests were written after the code; that is consistent with the brief's caller note.
    - The tests that matter most were written against independent literals: the identity values, the gap edges 4 / 5 / 300 / 301 s, the cap values 0 / 5 / −1, and the UTF-8 boundary. That limits the risk that tests merely mirror the code.
14. **The production-path UTF-8 check is not tested with multi-byte input** (P1 says "on the production path").
    - `tooBigAndBoundsAndVersion` (PlanReceiverTest.kt:918) uses ASCII spaces. A receiver mutation to `planJson.length` (:432) would still pass, because the validator's later check never runs: the receiver returns `too_big` first, or `bounds` if the size check is removed.
    - Add the `é` boundary case to `PlanReceiverTest`.
15. **The local wire copies diverge from the pinned Protocol** (PlanReceiver.kt:386-400).
    - The pinned `CapturePlanAck` has `caps = []`; the local copy defaults to `CAPS`. `mode` has no default in the pinned version.
    - Task 5 must delete these four types (`PlanMode`, `PlanAckState`, `CapturePlanMessage`, `CapturePlanAck`) and switch the receiver to `:core:model` types. Otherwise the glasses, which import both packages, get name clashes.
    - Flag this in Task 5's brief.
16. **Ack details.**
    - A terminal re-ack reports the post-revert `mode` (SETTINGS), not the original (:422).
    - A second `revert` overwrites the first reason in `terminal` (:483).
    - `activated`, `tick` and `revert` acks drop `publishId`, which S62 matching needs (:465, 489).
17. **`tick` calls `bootId()` every 250 ms tick** (:473), but the boot id cannot change inside a process. Check it once at construction or load, which also saves the per-tick supplier cost if Task 11 reads Settings or a file.
18. **Default charsets.** `toByteArray()` without a charset at PlanValidatorTest.kt:1220, 1225, 1230 and PlanJsonTest.kt:789. JDK 17 uses the platform default charset. Pass `Charsets.UTF_8`.
19. **Commit trailer.** It is `Co-Authored-By: Claude Sonnet 5.5`; Global Constraints specify the Opus 5.5 line. The mutation REDs are also missing from the body (see ⚠️).

### Checks run (named risk, then what was checked)
- Identity exactness: read core/model/.../ContextPolicies.kt, core/capture/.../GlanceParams.kt, the FusionParams block in ContextFusion.kt, and the KeyframeGate.kt constants (HAMMING=10 :155, BIG_CHANGE=22 :164). All match the pinned canonical text.
- Reserved-bound parity: read glasses/.../golden/DecisionGoldenRecorder.kt:31-33. The rule is identical. The reflection test fails loudly on a rename (NoSuchMethodException), so it is fragile but fail-closed. Making the recorder's function `internal` would remove the reflection.
- §8 bounds: grepped design.md:853-861. All values match.
- LIVE enforcement: grepped the plan. The refusal appears only in Task 27 (line 80) and in the round-3 rationale (line 48). Task 11 applies LIVE.
- Not run: tests or Gradle (read-only review). The focused tests I would run are listed in Minor 3, 4 and 14.

### Assessment

**Task quality:** Needs fixes (one Important, small).

**Reasoning:** The schema is closed and strict, the identity plan is exact, and the TRIAL state machine is sound and fail-safe. The glasses-side receiver still stages unrestricted LIVE in CORE, contrary to its own caps and the round-3 safety argument. A one-line `unsupported:live` refusal with a test closes it.
