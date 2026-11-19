# Re-review: wipe-refusal backoff fixes (435e8bf0) + emulator guard (e59fcda5)

Reviewer: Opus (Codex gpt-6.1-sol replacement, low effort), read-only. Branch feat/v101-ask-eval, tip e59fcda5. Path: privacy withdrawal (owed history wipe).

## P1
None.

## P2
None. Prior P2-1 (wall-clock backoff) is fixed.

## P3
- P3-1 `android-companion/nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusHudSink.kt:535` - the check `monotonicMs() >= forgetNotBefore` compares absolute values rather than `(now - mark)`. That is deliberate and correct with the `Long.MIN_VALUE` "no wait" sentinel: a difference against MIN_VALUE would overflow, so do NOT "fix" it to `now - forgetNotBefore >= 0`. It is overflow-safe in practice. `nanoTime/1e6` is at most about ±9.2e12 ms, so adding at most 30 000 (`:512`, `:514`) can never overflow. A negative JVM nanoTime is harmless. A real wrap needs about 292 years of uptime, and Android's nanoTime is CLOCK_MONOTONIC from boot. Optional: say this in a comment so nobody "fixes" it later.
- P3-2 `NexusHudSink.kt:510-515` - `RATE_LIMITED` and `ERROR` match no `when` branch, so they leave the wait as it is. This is the same as before, because `isQuietGiveUp` also excluded them. `ERROR` is covered by the dispatcher's own health backoff (`:276`, `:286`). But a hub that answers `RATE_LIMITED` is asked again every tick (1 s), the opposite of what that code asks for. Optional: map `RATE_LIMITED` to the fixed 1-2 s branch or to the escalating branch. Nothing is lost either way.
- P3-3 Code mapping (check 3): OK. The escalating codes (`:511`) are NOT_GRANTED, NOT_REGISTERED, NOT_AVAILABLE, INVALID, DISABLED and UNSUPPORTED. The fixed 1 s codes (`:514`) are BUSY and STALE. A forget's `callT` has no guard, so STALE can only come from the hub. A transient NOT_REGISTERED right after a hub restart is bounded: the reconnect goes through `onBridgeConnected`, which calls `resetForgetWaitLocked` (`:582`) and asks at once. If registration lands a moment later, the retries come at +1 s, +2 s, +4 s and so on. A 30 s delay only happens after about 5 refusals in a row on one connection. That is acceptable for a wipe whose debt is persisted. A permanent INVALID is capped at one ask per 30 s, so it cannot flood, and it is never given up.
- P3-4 `android-companion/phone/src/androidTest/java/com/rokid/context/phone/nexus/NexusRealHubTest.kt:57-58` - the guard matches on substrings. `"generic"` in FINGERPRINT can also match some AOSP-derived real builds (generic_* product trees). Both force-stops (`:192`, `:252`) run only inside the test methods, which come after `@Before`, so the guard covers them. On the real phone a false positive is unlikely. On the glasses the bridge APK check would also skip. Optional, stricter: `Build.HARDWARE in setOf("ranchu","goldfish")` or the `ro.kernel.qemu` property.

## Checks
(1) Monotonic: `monotonicMs` defaults to `System.nanoTime()/1e6` (`:55`), "no wait" is `Long.MIN_VALUE` (`:73`, `:508`, `:522`), and `clock()` is no longer used for the backoff. OK (P3-1).
(2) The debt is never dropped: the diff does not touch the ledger, a stale refusal (`:509`) only skips re-arming the wait, and `historyDirty` (`:230`) still enqueues with no wait check. OK.
(3) Mapping: OK (P3-3); RATE_LIMITED is a nit (P3-2).
(4) Resets: a trip (`:453`), a connection (`:582`) and an OK (`:508`) clear both the count and the wait. `okBefore` is read under the lock before the call (`:504`), and compared and written under the same lock (`:507-515`). So a late refusal from a run that started before an OK cannot re-arm the wait. Race-free. OK.
(5) The guard skips non-emulators (P3-4). The test KDoc (`:45-48`), the runbook pre-flight (line 13) and the review doc (line 3) all warn that connectedDebugAndroidTest installs on every attached device. OK.
(6) The tests would fail on the old code. The wall-clock test fails because a jump back of 6 h froze the asks. The reconnect/trip test fails because the old code held the 30 s wait. The BUSY test fails because the old code escalated, giving fewer than 11 asks. The overlap test fails because the old code re-armed the wait to `clock()+1000`, not MIN_VALUE. The logs are codes only (`nexus forget history code=`), and the tests assert counts. OK.

READY
