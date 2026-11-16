No P1 found. I found six P2 issues and one P3.

1. **P2 — A late reduction can undo cadence restoration.**  
   [ContextHubService.kt:324](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/ContextHubService.kt:324): reduction and restoration run on independent threads. A stop can begin checking the inbox, then a hub start/frame arrival sees `reduced=false` and skips restoration, after which the old reduction writes `true`. The running hub remains on 60-minute, battery-constrained jobs. Serialize transitions and revalidate the stop generation before reducing.

2. **P2 — Empty ingest queues do not establish that Moments have caught up.**  
   [OrchestrationScheduler.kt:269](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/work/OrchestrationScheduler.kt:269): reduction checks only the inbox and `QueueRuntimeStore`. Failed Moment builds rely on cadence recovery, which rebuilds only the current and previous clock hours ([MomentBuildTrigger.kt:110](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/MomentBuildTrigger.kt:110)). Extending the interval—or blocking it on low battery—can let an unbuilt second leave that window permanently. Preserve dirty Moment buckets or perform a durable catch-up before reducing.

3. **P2 — Name-based learning neither identifies the session device nor invalidates stale addresses.**  
   [GlassesAclReceiver.kt:50](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesAclReceiver.kt:50): the sole bonded device containing “rokid” need not be the device serving the active CXR session. A different bonded Rokid product can become the wake identity. Also, zero/multiple matches leave the previous address intact; token rejection removes only the token, so subsequent authorization can reactivate an old address. Bind the address to verified session/pairing identity and invalidate it when pairing changes or learning becomes ambiguous.

4. **P2 — Both receivers release their broadcast lifetime before processing completes.**  
   [HubBootReceiver.kt:18](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/HubBootReceiver.kt:18) and [GlassesAclReceiver.kt:27](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesAclReceiver.kt:27): `submit` discards the engine’s deferred result, then immediately calls `finish()`. The actor can still be waiting to load/save state or issue the start when the receiver’s process protection ends. Await the event chain with a bounded timeout before finishing, or durably hand off the work. This follows Android’s [`goAsync()` lifecycle contract](https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()).

5. **P2 — An ACL event during a call is dropped instead of deferred.**  
   [AutoStopMachine.kt:244](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopMachine.kt:244): `!callIdle` returns `NONE` without retaining the nearby signal or scheduling a call recheck. Without the battery exemption, ordinary process-start wakes cannot rescue this auto-stopped hub after the call settles. Retain a bounded pending ACL request and recheck it through the guard.

6. **P2 — T6’s hub-start call check is not revalidated after persistence.**  
   [AutoStopController.kt:158](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopController.kt:158): `GlassesNearby` samples call state before reduction/persistence, then emits `StartWake(recover=false)`. That effect skips the recovery gate’s fresh `hubStart.record()` check. A call beginning during the write can therefore overlap the hub start. Glasses-session opening remains guarded, but the hub-start invariant needs a fresh check immediately before issuing the start.

7. **P3 — ACL matching still reads preferences on Main.**  
   [GlassesAclReceiver.kt:23](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesAclReceiver.kt:23): `isPaired()` and `pairedAddress()` execute before `goAsync()`. Cold SharedPreferences loading can block the receiver’s main thread. Move matching and preference access into the asynchronous section.

The remaining checks passed by inspection:

- **Session choke point:** both triggers use unforced starts, reaching `Hub.start(false)` → `connectGuarded()` → the durable reservation path. I found no new glasses-session bypass of the 15-second settle or persisted 6/hour quota.
- **Spoofing:** both receivers validate actions. ACL_CONNECTED, BOOT_COMPLETED and MY_PACKAGE_REPLACED are [platform-protected broadcasts](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/res/AndroidManifest.xml), so ordinary apps cannot spoof those accepted actions. Sender authentication currently relies on that platform enforcement. Boot can be hardened to `exported=false`; Bluetooth delivery needs care because its privileged sender may have a separate UID ([Android guidance](https://developer.android.com/develop/background-work/background-tasks/broadcasts)).
- **Privacy/logging:** no address/name logging was introduced; the new ACL log is a literal compatible with the content scanner. `rokid_link.xml` and `periodic_cadence.xml` are excluded from cloud backup and device transfer, and neither enters settings export.
- **Lost hubs:** process death does not execute the new reduction hook, so their existing jobs remain scheduled.

This was a static review. Tests and the spec-required device checks were not run in the read-only workspace.

READY