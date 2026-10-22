# Task 11 re-review, fix round 1 (0f7c2c7a vs 03b23eef)

Reviewer: Opus, read-only. No Gradle, adb, stash or commits. I read the full diff, PlanReceiver.kt, PlanController.kt, PlanAck.kt (the gate and tracker), Hub.kt (onReady, newSession and the ack handling), HudController (the plan freshness filter), S24 in the e2e spec, and the PlanTestKit MemPersistence.

## Verdict: Needs a small fix

One Important finding is left: a phone rollback whose write fails keeps the TRIAL running. It is a one-line fix. Everything else is Minor or fixed.

## Verified fixed

- **Important 1: fixed.**
  - **Caps ack:** HudController still sends the gen-0 caps-only `capsAck` default. GlassesRuntime no longer overrides it.
  - **Status ack:** a separate `statusAck` is sent only when gen != 0. It is wrapped in a try.
  - **Stamping:**
    - `locked{}` stamps every queued ack inside the lock.
    - `onMessage` stamps the ack it returns.
    - `statusAck` carries `session`.
    - So STAGED, EFFECTIVE and self-emitted REVERTED acks are all stamped. `everyAckIsStamped…` would fail if either stamping site were removed.
  - **Real gate test:** `Slice3aWireTest` runs the real PlanAckGate and PlanPeerTracker, but on hand-built acks. It would pass without the fix. The glasses side checks real controller output against a copy of the gate rule. That split is acceptable given the minSdk 28/29 constraint, but the copy can drift (Minor 6).
- **Important 2: fixed.**
  - `forceSettle` defaults to true. `ActiveConfig.of` (TRIAL) gets SETTLE, and `SETTINGS` (also used for SHADOW) keeps the settings wake, so the goldens are unchanged.
  - `trialIsSettleWake…` and the flipped `rowGaps…` assertion both fail if `forceSettle` is ignored.
- **Important 3: fixed for local reverts. The phone-commanded rollback is not covered (see Important R1).**
  - `PlanReceiver.revert` sets `st` before the commit and sets `persistPending`. `tick` retries the write, and the controller fast path includes `persistPending`.
  - `aFailedWriteAtTrialEnd…` fails if the old order (commit, then set) comes back. It also checks the retry.
  - bootstrap, onMessage, tick, onPhoneSettings, revert and statusAck all catch.
  - **Core semantics:** no PlanReceiverTest depends on revert throwing. `grep` finds no fail or throw cases there. On a successful write, the order of state and ack is unchanged, so the Task 4/5 semantics hold.
  - **Coverage gap:** the new receiver behaviour has no `:core:capture` test (only glasses tests).
- **Minor 1 (`attach` under the lock), 2 (`stagedAt` backoff), 3 (own guard) and 4 (`statusAck` ticks): fixed.** Each has a test that fails under its mutation. For Minor 2, without the backoff there are about 20 aborts, against at most 3 allowed.
- **Minor 9: acceptable.** The composed profile, SETTLE and 45 s are now asserted. The heatStop and userPause rigs run on the plan rows.
- **Minor 13: fixed.**
  - The high-water mark is written in the same `edit()` as the state.
  - An unreadable state loads as a placeholder: SETTINGS, effective, `terminal {G: auto:corrupt}`. A re-sent G is re-acked REVERTED, a gen below G is `stale`, and a gen above G is accepted.
  - It cannot lock out every future plan. That would need G = Long.MAX, and the same is true of any accepted gen today.
  - It protects only the latest install, which is correct because gens are counted per install.
  - With no mark (legacy or empty), the state loads as null, which is SETTINGS.
- **Minor 18: fixed.** `git check-ignore` matches `android-companion/.kotlin/` (.gitignore:36).

## The stale stamp at session open (the implementer's concern)

The phone cannot get stuck.

- **Why the status ack is always STALE:** the phone's `newSession()` sets `sessionStartMs = clock()` in `onReady`. The glasses only know the previous session's stamp, or 0. So the session-open status ack is **always** STALE in practice.
- **Why CAPABLE is still reached:** the caps ack is CAPS, so PlanPeerTracker reaches CAPABLE regardless.
- **How the phone recovers the plan state:** S24 requires the phone to send `pc_capture_plan` in `onReady()`. The glasses then re-ack that gen, stamped, as EFFECTIVE, STAGED or REVERTED (a terminal gen is re-acked REVERTED). That ack is a MATCH.
- **Self-emitted acks before the phone's first message are lost but recovered the same way.** These are a revert at bootstrap and a TRIAL end found by `statusAck`.
- **Dependency:** Task 26 must send the current gen on every `onReady`, including when the phone wants SETTINGS. Flag this to the controller.

## Remaining findings

### Important
- **R1. A phone rollback whose write fails keeps the TRIAL applied** (PlanController.onMessage catch).
  - **What happens:**
    - A SETTINGS or SHADOW message from the phone (an S25 rollback) is a revert path.
    - If `receiver.onMessage`'s commit throws, the catch returns REJECTED `persist`, but `active` stays the TRIAL config.
    - The TRIAL then runs until its local limit, up to 1 h.
    - `aFailedWriteInOnMessageDoesNotThrow` only covers the case with no plan held.
  - **Fix:** call `failClosed()` in that catch. Any newer message supersedes the current plan, so falling back to SETTINGS in memory is the safe choice.
  - **Test:** a TRIAL is EFFECTIVE, a SETTINGS gen 2 arrives with `failNextCommit`, and `active` is SETTINGS.

### Minor
1. **The session-open status ack is redundant.** It is always STALE (see above) and adds 1 to `ackStale` on every session, which muddies that diagnostic. Either drop it, or document that it is a best-effort ack and that the `onReady` re-ack is the real one.
2. **`session = m.sessionStartMs` is unconditional.**
   - HudController's freshness filter compares only the message's own two fields. So a delayed copy from an old session still passes and moves the stamp back.
   - Later self-emitted acks are then STALE until the next message.
   - **Fix:** use `session = maxOf(session, m.sessionStartMs)`. Both values are the phone's wall clock.
3. **The new REJECTED reason `persist` is not in the S24 reason list.** Task 26 must treat it as retryable, not as a final verdict on the gen. The gen is not terminal on the glasses and is applied on the next resend.
4. **The heartbeat reports the settings wake while a plan forces SETTLE** (CaptureService.kt:325: `wake = settings.cameraWake.name`). Report the composed wake.
5. **After an in-memory revert whose write failed,** a process death before the retry resumes the TRIAL from disk on the same boot, until its limit. This applies to `auto:old_phone`, the Task 12 heat and ceiling reverts, and others. It is bounded (at most 1 h) and rare, so I accept it. Note it for Task 12.
6. **Some tests are weaker than their names suggest:**
   - `Slice3aWireTest.stampedReceiverAcks…` cannot fail on this fix, because it uses hand-built shapes.
   - `aRevertAtLoadIsStampedWithTheNextSession…` only checks that a message sets the stamp. The revert-at-load ack itself is never sent: bootstrap discards it.
   - `aFailedWriteAtLoad…` and `aFailedWriteInOnPhoneSettings…` no longer reach a throw path, because revert no longer throws. They are fine as regression guards, but `failClosed()` is now almost unreachable.
7. **`persist.load()` runs in the PlanReceiver constructor,** outside bootstrap's try. A `ClassCastException` from a mistyped pref would still crash at load. The likelihood is low. Optionally wrap `load()` in PrefsPlanPersistence with runCatching, returning the placeholder or null.
8. **The commit trailer still says "Claude Sonnet 5.5"** (prior Minor 16, out of scope for this round).
