# PCA Nexus publish against the real Nexus hub (S1.5b), emulator only, 2026-11-19

Emulator `emulator-5554` only (AVD MyEmulator, API 34, no glasses link, no Hi Rokid). Counts, codes and states only; no content. No real device was addressed (the emulator was the only attached device).

## How it was done (no production hook)

An INSTRUMENTED test, `android-companion/phone/src/androidTest/java/com/rokid/context/phone/nexus/NexusRealHubTest.kt` (androidTest source set only: nothing is compiled into an APK a user installs). It runs inside the phone app's process and builds what `NexusGraph.attach` builds, with the production classes: `NexusProvider`, the one `NexusHudSink`, `ServiceBridgeLink` (real bind to the real bridge APK `nexus-bridge-debug.apk`, built from this tree and signed with the same debug key as the phone app), `NexusHostStore` as the wipe ledger (on a private prefs file), and `PcaNexusGates` over test lambdas (worn, paused, the two zone fences, provenance) instead of the glasses-driven ones. The only extra is a thin forwarding binder wrapper that counts every transaction (the number of attempts at the hub). The hub is the real upstream v2.6.0 debug build (`backups/nexus-hub-v2.6.0-debug`); its state (Pending, Approved, "Show on your glasses" unticked, force-stopped) was arranged by hand or by `am force-stop` from the test. Run with `adb -s emulator-5554 shell am instrument -w -e expect <CODE> -e class ...NexusRealHubTest com.rokid.context.test/androidx.test.runner.AndroidJUnitRunner` (gradle's `connectedDebugAndroidTest` was not needed: `:phone:assembleDebugAndroidTest` plus manual `adb -s` installs). Results go to logcat tag `PCAREALHUB`.

## Results per item

**1. The hub's answer to each publish (nudge, place card, answer, mark confirmation), through the real bridge.** One binder `showIn` attempt per publish in every state, no exception, no background retry in the 8 s after (counted), the Settings status string derived from the last code:

| Hub state | Code for all four | PCA status string |
|---|---|---|
| Plugin installed, Pending (not approved) | NOT_REGISTERED | "Pending approval" |
| Approved (STT, TTS, assistant unticked), no glasses link | NOT_AVAILABLE (CAPABILITY_NOT_AVAILABLE) | "Nexus hub stopped" (heuristic: nothing else holds a link) |
| Approved, "Show on your glasses" unticked | NOT_GRANTED (CAPABILITY_NOT_GRANTED) | "Capability denied" |

Hub force-stop (20 publishes 0.4 s apart, hub dies at the start): the bridge restarted the hub itself, every publish answered NOT_AVAILABLE, 20 publishes = 20 attempts (no storm, no crash). The expected NOT_REGISTERED first answer was NOT seen for a SHOW (the hub checks the link before the registration); it WAS seen for the history wipe request sent right after a force-stop: NOT_REGISTERED, NOT_REGISTERED, then OK and acknowledged (about 2 s), see item 2. "Quiet give-up" holds for publishes: a refusal drops the item, keeps no handle (`liveSlots` empty) and is never re-sent.

**2. Withdrawal and the history wipe.** No notice can be drawn without a glasses link (every publish is refused), so a live notice being hidden by a close event was NOT observed on the hub; what was observed: after a failed publish, `trip(PAUSE | ZONE_HOLD | NOT_WORN)` leaves no live slot, tells the bridge the trip epoch (`tripIn` transaction seen) and owes no wipe (generation unchanged). `trip(FORGET)` and `trip(DELETE_ALL)`: the debt generation moves up at once (persisted before anything is sent), the hub answers `forgetHistory` OK, `wipe acknowledged`, the debt is cleared. Mode off (`NexusHostStore.set(PCA)` plus `refresh`): debt written with the host switch, MODE_OFF trip, OK, acknowledged, bridge unbound, debt cleared. Wipe owed while the hub was force-stopped: owed at once, NOT_REGISTERED x2, then OK, acknowledged, debt cleared (about 2 s).

**3. Text limits.** The mapper clips before the sink: a 1024-char body goes out as 1024, 1025 and 5000 as 1024, 40 lines as 16, a 100-char title as 32, a surrogate pair at the cut is never split, an empty body is accepted. For every case the hub gave the same answer as for a short text (no INVALID, no ERROR) in the NOT_AVAILABLE state, one attempt each. NOT proven: the hub's own reaction to an over-limit text, because PCA never sends one (and the hub answers NOT_AVAILABLE before it could judge text).

**4. The privacy lane.** For each of paused, Place Guide zone, Ask zone, not worn and provenance (sources not live): all four events published with the gate closed, then one published with the gate flipped while the delivery was in flight (held inside the provenance gate, flipped, released). Zero `showIn` transactions reached the bridge in every case except the design exceptions: a content-free mark confirmation is not paused-gated and is not provenance-gated (it has no sources), so with only that gate closed it goes out once (paused: 1, provenance: 1); everything with content was dropped (PAUSED, ZONE_HELD, NOT_WORN, PROVENANCE). A control publish with every gate reopened did reach the bridge (1 attempt), so the zero counts are the gates, not a dead rig.

## Bug found and fixed

Under NOT_GRANTED the owed history wipe was asked again by every sink tick, once a second, for as long as the refusal lasted (21 `forgetHistory` calls and 21 log lines in 15 s; the debt can never clear while the capability is unticked, and "Show on your glasses" unticked means nothing could have been drawn anyway). Fix (own commit): the sink backs off after a refused wipe request (1, 2, 4 ... up to 30 s; reset on OK); a new trip, a new connection and a successful call still ask at once. After the fix: 5 calls in 15 s. Unit test `WipeRefusalBackoffTest` (nexus-port). The debt stays owed, nothing is dropped. Note for the user: with the capability unticked the settings screen keeps saying the cleanup is pending until it is ticked again.

## Verified

`:phone:testDebugUnitTest --tests 'com.rokid.context.phone.nexus.*'`, `:nexus-port:testDebugUnitTest` and `:nexus-bridge:testDebugUnitTest` pass after the fix. The instrumented class: 6 of 6 pass on the emulator against the approved hub (NOT_AVAILABLE state); the publish test also passed in the Pending and NOT_GRANTED states with the matching `expect`. No `FATAL EXCEPTION` in the logcat of the full run.

## NOT proven (needs the real glasses link, S2)

A successful publish (OK) and anything about rendering, the glasses link and S2 coexistence (start orders, gestures, popup layering, assist intercept), a notice actually hiding on the hub when a close event lands, the hub's history screen being empty after a wipe, the real wear signal (`hub.wornNow`, wear estimate, `link.wearing`) feeding the gates (here replaced by test flags, as intended), the per-operation skills consent screen, speech, battery/heat, the release-signed hub. NOT_REGISTERED for a first SHOW after a hub restart (the hub answered NOT_AVAILABLE first). The exact "Nexus hub stopped" vs "Glasses link busy" reading is still a heuristic until S2.

## Emulator state at the end

Hub (approved, all rows as set), phone debug, bridge and the androidTest APK stay installed on the emulator.
