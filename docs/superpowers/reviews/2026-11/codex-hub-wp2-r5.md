Commit `5e875c1e` still has **one P1**. No additional P2/P3 findings identified.

**P1 — Late authorization success loses its automatic origin and bypasses the guards.**

The new timeout branch clears `automatic` before handing recovery back to the relauncher ([GlassesSessionPolicy.kt:124](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/glasses-link/src/main/kotlin/com/rokid/context/link/GlassesSessionPolicy.kt:124)). `AuthGate` nevertheless accepts a late success after expiry ([AuthGate.kt:31](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/glasses-link/src/main/kotlin/com/rokid/context/link/AuthGate.kt:31)). Authorization completion then reads the cleared flag and selects `OPEN_AS_USER`, even when `AutoOpenOwner` reports a blocking call ([CxrGlassesLink.kt:149](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/glasses-link/src/main/kotlin/com/rokid/context/link/CxrGlassesLink.kt:149)).

A JVM probe using the compiled production classes reproduced:

```text
automatic open → auth timeout → AutoOpenFailed
late success accepted: true
automatic origin: false
continuation with call guard blocked: OPEN_AS_USER
connect failure → ScheduleRetry(2000)
```

This restores both an unguarded session open during a call and link-owned retries outside the relauncher quota. Preserve authorization origin independently of mutable session policy, and reject expired continuations or return them through guarded recovery.

The caller audit also found that `HubResumeHook` treats **every activity resume**, including internally launched `AuthActivity`, as foreground user activity ([HubActivityHook.kt:44](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/HubActivityHook.kt:44)). After timeout, that route can call `connectOnResume()` and set user origin too; it needs consideration in the same fix.

| r4 finding | Re-review result |
|---|---|
| Authorization guarded through `authContinuation` / `AutoOpenOwner` | **Incomplete:** works before timeout; late completion bypasses it. |
| Serialized reserve/write/commit/rollback | **Fixed:** mutex includes cancellation rollback; replacement waits for cleanup. |
| Automatic auth timeout has a retry owner | **Fixed for timeout without a late result:** emits `OFFLINE` and `AutoOpenFailed`; relauncher schedules guarded backoff. |
| Synchronous failure reporting | **Fixed:** explicit callback avoids StateFlow conflation; `awaitingSince` deduplicates observer/callback reporting. |
| Sticky user `OpenAfterSilence` | **Fixed:** automatic probes preserve the pending user request; response or close clears it. |
| Previous P3 expiry-test timing | **Fixed:** expiry now occurs during the held write. |

The production caller inventory is:

| Opening entry | Callers and disposition |
|---|---|
| `connectOnce → autoConnect` | `Hub.start`: explicit user starts; automatic starts use `connectGuarded`. Unguarded fallback exists only without a relauncher; production supplies one. |
| `connectAutomatic → manualConnect(true)` | Relaucher callback after guarded, durable reservation. |
| `reconnectAutomatic → manualConnect(true)` | `Hub.watchdogReconnect`, through `attemptNow` with eligibility checks. |
| `connect → manualConnect()` | Explicit `Hub.requestReconnect`. |
| `connectOnResume → manualConnect()` | `Hub.onAppResumed`; no-relauncher watchdog fallback. Internal `AuthActivity` resumes weaken this user-origin boundary, as described above. |
| Authorization continuation | User, automatic, or hand-back branches. **Late automatic completion remains unsafe.** |
| Background silence → `manualConnect()` | Only an outstanding user probe request opens immediately; automatic silence goes through relauncher guards. |
| `GlassesAppOpened → Connect` | Watcher reports the wearer reopening the glasses app. |
| `RetryTimer → Connect` | Explicit/user-origin retry chains. The late-auth bug incorrectly admits automatic recovery into these chains. |
| `DevicePresent → Connect` | No production sender found. |
| Actual SDK session creation | All policy `Connect` actions reach `openSession → manager.create → session.connect`. |
| Glasses-side activity launches | Confirm overlay; capture fallback through presence checks, cooldown and consecutive-launch limit. These use separate local policies. |
| `launch-glasses` | No repository definition/reference found; external tooling cannot be certified here. |

I found no new mutex lock cycle: link callbacks run after reservation unlock, and explicit user opens do not acquire the mutex or consume automatic quota. Guarded failures retain bounded backoff. The late-auth path prevents confirming that every automatic opening remains guarded.

Executed existing compiled JVM suites: **145 tests passed**. The Android-runner recovery suite could not initialize with the standalone classpath. No fresh Gradle build or device run was performed in this read-only environment.

NOT READY