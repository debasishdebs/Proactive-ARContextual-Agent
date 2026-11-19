# Review: wipe-refusal backoff (73ef1ce3) + real-hub instrumented test (8ebce006)

Reviewer: Opus (Codex gpt-6.1-sol replacement, low effort), read-only. Branch feat/v101-ask-eval.

## P1
None.

## P2
- P2-1 `android-companion/nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusHudSink.kt:39, :504, :520` - the backoff uses the wall clock. Production builds the sink at `:782` without a `clock`, so it gets the default `System::currentTimeMillis`. If the clock jumps backwards after a refusal (a network or NITZ time fix, or a manual change), `forgetNotBefore` ends up that far in the future, and `tick()` stops asking for the owed wipe until the next trip or bridge connection. That could be hours. The debt is not lost (it stays in the ledger), so this is not a P1. But it fails check (4). A forward jump is harmless: it asks once, then backs off as normal. Fix: keep the backoff on a monotonic clock (`SystemClock.elapsedRealtime()` or `System.nanoTime()`, injectable for tests). Or, at minimum, clamp it in tick: `if (forgetNotBefore - now > 30_000) forgetNotBefore = 0`.

## P3
- P3-1 `NexusHudSink.kt:504` - nothing resets `forgetRefusals` except an OK. `trip()` (`:455`) and `onBridgeConnected()` (`:567`) do ask at once, correctly bypassing the wait. But if that ask is refused again, the next wait jumps straight to 30 s instead of restarting at 1 s. This is harmless, but it does not match the commit text "a new trip ... still asks at once" for the follow-up retries.
- P3-2 Capability ticked again: the hub sends no event, so a granted capability can still wait up to 30 s plus one tick before the wipe is asked again. I accept this for a wipe: the hub keeps history up to 24 h, nothing could have been drawn while "Show on your glasses" was unticked, and the debt is persisted. A hub restart or a bridge reconnect asks at once through `onBridgeConnected` (`:567`).
- P3-3 `Model.kt:55` `isQuietGiveUp` also counts `BUSY` (and `STALE`, `INVALID`, `DISABLED`, `UNSUPPORTED`) as a refusal. So a short-lived BUSY also starts the backoff, delaying the wipe by up to 30 s. Optional: back off only on NOT_GRANTED, NOT_REGISTERED and NOT_AVAILABLE.
- P3-4 `NexusHudSink.kt:495, :499-506` - when the fallback in `enqueueForgetLocked` runs on `runFresh`, two `runForget` calls can overlap. A late refusal can then land after an OK and set the wait again. The cost is only up to 30 s of delay: the debt is settled by `onForgetAccepted` and the ledger, never by these fields. Both fields are read and written under `lock`, so check (3) holds.
- P3-5 `WipeRefusalBackoffTest.kt` - test 1 fails on the old behaviour (21 asks against the allowed 2..7). Test 2 fails if the OK reset is removed (a wait until 3000 would block the tick at 2000). Test 2's trip-bypass assertion passes on both old and new code, and no test covers `onBridgeConnected` bypassing the wait, the wall-clock case, or restart (the in-memory fields start at 0, so the app asks at once after a restart: correct).
- P3-6 `NexusRealHubTest.kt:185, :245` - runs `am force-stop` on the hub with no emulator guard. It only lives in androidTest and is not in any installed APK, and the only production change in these commits is `NexusHudSink.kt` (no hook). But if someone runs it by mistake on a real phone, it force-stops that phone's hub. Optional: `assumeTrue(Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk"))`.

## Checks
(1) The debt is never dropped: `runForget` never touches the ledger, `forgetOwed()` reads the persisted ledger, and a restart asks at once. OK.
(2) Every event that should retry at once bypasses the wait except capability re-grant (P3-2) and repeat refusals (P3-1).
(3) Lock discipline is OK (P3-4).
(4) FAIL: wall clock (P2-1).
(5) OK, with gaps (P3-5).
(6) OK: androidTest only, no production hook, emulator-only run documented (P3-6).
(7) Logs carry codes only (`nexus forget history code=`), and the test logs counts and codes. OK.

READY (no P1; fix P2-1 before the trip build if cheap).
