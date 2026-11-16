`55d4470a` still has **one P1 and four P2s**. The single guarded choke-point claim does not hold.

1. **P1 — Authorization completion bypasses the guards and restores unguarded retries.**  
   An automatic cold recovery reaches authorization because `AuthGate.authorized` starts false. [CxrGlassesLink.kt:144](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/glasses-link/src/main/kotlin/com/rokid/context/link/CxrGlassesLink.kt:144) handles success with `manualConnect()` without preserving `auto=true` or rechecking call state/settle. A call starting during authorization therefore does not prevent the eventual session open. This also restores link-owned retries: the JVM policy probe produced **automatic connect → authorization’s manual connect → ConnectFailed → ScheduleRetry(2000) → Connect**. Those additional opens bypass both guard and quota.

2. **P2 — Cancellation rollback can erase a replacement attempt’s reservation.**  
   [connectGuarded](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesRelauncher.kt:317) cancels the previous job without waiting for its write/rollback to finish. The old [rollback](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesRelauncher.kt:296) can run after the replacement’s durable write but before its in-memory commit, overwriting the new reservation. A held-IO stop/start probe reproduced **six actual attempts, five persisted; rebuild then permitted attempt seven**. Reservation and cancellation cleanup need serialization across replacement jobs.

3. **P2 — Automatic authorization timeout strands recovery.**  
   [GlassesSessionPolicy.kt:118](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/glasses-link/src/main/kotlin/com/rokid/context/link/GlassesSessionPolicy.kt:118) now suppresses automatic auth retries and emits `NOT_AUTHORIZED`. The relauncher only schedules failed-attempt retries for `OFFLINE`; [the watchdog](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesWatchdog.kt:165) also does nothing for `NOT_AUTHORIZED`. A background authorization that produces no result therefore has no retry owner. The probe remained at **one attempt after two simulated hours**, despite retaining a token.

4. **P2 — Synchronous ConnectFailed can lose the relauncher retry.**  
   [observe()](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesRelauncher.kt:187) infers failure from `StateFlow` changes. If [openSession()](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/glasses-link/src/main/kotlin/com/rokid/context/link/CxrGlassesLink.kt:289) throws synchronously, `OFFLINE → CONNECTING → OFFLINE` can be conflated into the unchanged starting value. The observer receives no failure transition. An immediate-dispatch probe reproduced **one attempt and no relauncher retry after an hour**. Ordinary asynchronous failure **does** retry after five minutes; synchronous failure depends on watchdog fallback.

5. **P2 — A watchdog probe can revoke an explicit user open.**  
   [probeAlive()](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/glasses-link/src/main/kotlin/com/rokid/context/link/CxrGlassesLink.kt:154) now assigns `openAfterSilence = openIfGone`. Sequence: user taps Reconnect while minimised (`true`), watchdog probes before the response timeout (`false`), glasses remain silent. The explicit open is lost, leaving the guarded automatic reopen—which may be blocked by setting, call, or quota. Preserve an outstanding user request when an automatic probe overlaps it.

The independent caller inventory is:

| Entry | Production callers and disposition |
|---|---|
| `connectOnce` | `Hub.start` only: explicit user start, plus the no-relauncher fallback. Production `HubGraph` supplies guards. |
| `connectOnResume` | `Hub.onAppResumed` from activity resume; `Hub.watchdogReconnect` only when relauncher is absent. |
| `manualConnect` | `connect`, `connectAutomatic`, `connectOnResume`, `reconnectAutomatic`, user-requested background-silence handling, **authorization completion**. The last is the unresolved automatic bypass. |
| `reconnectAutomatic` | `Hub.watchdogReconnect`, through `attemptNow` with eligibility checks. |
| `connectAutomatic` | Relaucher callback through `doReopen`; interface fallback for `reconnectAutomatic`. Authorization continuation subsequently escapes this protection. |
| Other session-opening actions | `GlassesAppOpened` follows the wearer reopening the app; `RetryTimer` opens directly. No production sender of `DevicePresent` was found. |
| `launch-glasses` | No definition or reference exists in the target tree; an external command cannot be certified from this commit. |

The r3 fixes stand as follows:

| r3 issue | Result |
|---|---|
| AutoStop wake classification | Fixed: unforced starts use `connectGuarded`. |
| Watchdog’s initial reconnect | Fixed: uses automatic policy. Authorization continuation remains unsafe. |
| Pruning erases reservation | Fixed for the reported race: pruning no longer persists. |
| Cancellation skips rollback | Fixed in isolation; overlapping replacement jobs remain unsafe, finding 2. |
| `PAUSED_BT` consumes quota | Fixed: reconnect eligibility rejects it before reservation. |

**P3 — The new expiry test misses its intended race:** old entries expire seven seconds into the test, while the first reservation starts after eight seconds. It does not exercise expiry *during* the held write.

Fresh execution of existing compiled relauncher/watchdog suites passed **87 tests**. Additional in-memory JVM probes reproduced findings 1–4; finding 5 follows from the production probe assignments. No fresh Gradle build or device run was performed in this read-only environment.

NOT READY