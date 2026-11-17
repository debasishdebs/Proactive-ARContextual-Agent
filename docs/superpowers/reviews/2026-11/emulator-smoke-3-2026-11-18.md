# Emulator smoke 3 (Nexus line), 2026-11-18

Branch feat/v101-ask-eval at ba9e778f. Fresh debug APKs built at HEAD (`:phone:assembleDebug :nexus-bridge:assembleDebug`, through the gradle lock, BUILD SUCCESSFUL): `phone/build/outputs/apk/debug/phone-debug.apk`, `nexus-bridge/build/outputs/apk/debug/nexus-bridge-debug.apk`. Nexus hub: the existing spike build `~/Desktop/rokid/scratch/nexus-sdk-v0.27.0/phone-hub/build/outputs/apk/debug/phone-hub-debug.apk` (v2.6.0 source, debug-signed, versionCode 20600; no rebuild needed). Emulator `MyEmulator` (API 34 arm64, headless, 1080x2400 @420dpi via `wm`). Every adb call was `adb -s emulator-5554 ...`; the host showed only the emulator in `adb devices` during the run; no real device was addressed. No app code was changed, nothing pushed. Emulator stopped with `emu kill`, `wm` reset, no timers or processes left.

## Verdict

No crash, no ANR, no StrictMode from PCA or the bridge. Two real findings (B1 medium, B2 low/medium), several notes. The main limit: with no glasses, PCA's own wear gate drops every HUD event (`nexus dropped reason=NOT_WORN`), so nothing is ever sent to the bridge or hub and the screen states that depend on a hub answer (Pending approval, Approved, Hub stopped, Link busy) cannot be reached from PCA on an emulator.

| Goal | Result |
|---|---|
| 1 Install v1.0.0, `install -r` upgrade, bridge, hub | PASS (v1.0.0 launched once, upgraded in place, onboarding, Nearby permission dialog) |
| 2 Settings > Rokid Nexus states and switches | PARTIAL: 5 of 10 state strings reached; switches PASS |
| 3 Notice to the hub | NOT REACHABLE (NOT_WORN gate); hub plugin registration and approval observed |
| 4 Trip, wipe debt, bridge killed | PASS with B1/B2 |
| 5 Signer trust | PASS |
| 6 logcat | 0 FATAL, 0 ANR, 0 StrictMode |

## 2. Settings > Rokid Nexus (Developer mode on)

State strings seen (screen title text, host = PCA unless noted):

| State | How reached | Seen |
|---|---|---|
| Off | both apps installed, host PCA | yes |
| Nexus not installed | hub uninstalled (bridge present) | yes |
| Bridge not installed | both uninstalled | yes |
| Waiting for Nexus | host = Nexus, bridge bound, hub running (also with plugin Pending and Approved in the hub) | yes |
| Bridge not trusted | bridge re-signed with another key, installed (section 5) | yes |
| Bridge cannot be used (REFUSED) | not reached; the same-package different-key case maps to "not trusted" first | no |
| Pending approval / Approved / Nexus hub stopped / Glasses link busy / Capability denied | need a HUD result code from the hub; the sink never sends (NOT_WORN) | NOT reached |

Hub side (hub UI "Plugin access"): the bridge appears as "Proactive Context Agent", "UNVERIFIED INSTALLED PLUGIN", state Pending; after "Allow on glasses" it reads Approved; hub log `plugin registered package=io.github.pca.nexusbridge plugin=pca status=approved`, `assistant takeover set=false by plugin=pca`. The PCA screen stayed "Waiting for Nexus" in both hub states (see N1).

Switches: "Show cards in Nexus" PCA to Nexus and back, Nexus voice, Speak answers, Skills: all default OFF (confirmed in `nexus_host.xml`: absent keys). Each of the three flips was followed within 1 s by `bridge features set tts=<bool>` in the bridge process (refreshFeatures takes effect: 3 of 3) and no crash. Host switch Nexus to PCA logged `nexus trip reason=MODE_OFF`, `nexus forget history code=OK`, `bridge hid all`, `nexus wipe acknowledged`, `nexus unbound` within 1 s.

## 3. Notice delivery

Host = Nexus, bridge bound, hub running and the plugin Approved. A typed Ask on Home ("where is the test") returned the offline refusal text on PCA's own screen and produced NO `nexus` line (no show attempt). Every 15 s the status-pin feed logs `nexus dropped reason=NOT_WORN` (about 4 per minute): the gate drops before the bridge. No nudge, answer, place card or mark could be produced on the emulator (no source of nudges, no glasses; no debug injector exists). Hub log shows registration and takeover calls only; nothing was queued. Not verified: any notice reaching the hub, rendering, the 24 h history purge of real notices.

## 4. Trip, wipe debt, bridge killed

- Zone "Charminar" (S 17.3600, N 17.3630, W 78.4730, E 78.4760, 300 m) with host = Nexus: on adding it `zone pause added state=INSIDE reason=NO_FIX` (fail closed). After clearing the memo and restarting (cadence HIGH) plus `emu geo fix 78.4747 17.3616` the memo reads INSIDE with `lastFixWallMs` fresh and the system last location 17.361598,78.474698. Log per tick: `nexus trip reason=ZONE_HOLD left=0` (25 in the first minute: one or two per ~10 s fix) and `nexus trip reason=PAUSE left=0` once. `left=0` means nothing was live on Nexus, so the bridge had nothing to hide ("bridge hid all" only shows on a feature change or host switch). Wipe counters: a zone trip does not owe a wipe (`wipe_owed` absent, `wipe_gen` unchanged), as designed (only Forget, Delete all and MODE_OFF owe). Home showed "Paused / Resume capture".
- Host Nexus to PCA with the bridge bound: `wipe_gen` incremented, hub acknowledged in about 20 ms (`notice history purge queued owner=pca reason=owner_forget`), `wipe_owed` cleared.
- Bridge force-stopped (`am force-stop io.github.pca.nexusbridge`) then host to PCA: `wipe_owed=true` recorded at once (`wipe_gen` 5); at +30 s `nexus wipe retry`, `bind bound`, first `forget history` got `NOT_REGISTERED` (the hub had not re-registered yet), 1 s later `code=OK`, `wipe acknowledged`, `unbound`; `wipe_owed` cleared.
- Bridge disabled (`pm disable-user`, emulator only) with a wipe owed, observed 2+ minutes: bind attempts at 04:22:09, 04:23:09, 04:25:09 (30 s, 60 s, 120 s backoff; two attempts inside the first 2 minutes of the window, each `bind not installed`). PCA CPU over 107 s: 109 ticks (about 1 %), over the next 135 s 86 ticks (about 0.6 %): no spin. After `pm enable`, the package-changed broadcast bound the bridge at once (`wipe retry bound`); the hub did NOT re-register it (disable/enable returned the plugin to Pending in the hub, N3), so `forget history code=NOT_REGISTERED` repeated once per second for the 30 s teardown window (30 sends), then `unbound`; the debt stayed owed and the next retry came after about 190 s (same result). After re-approving in the hub and toggling the host, the wipe was acknowledged and `wipe_owed` cleared.
- PCA kept working throughout (Home, Ask, Settings, zone screen), no crash.

## 5. Signer trust

Throwaway key generated in the scratch dir (deleted afterwards), the bridge copy re-signed with `apksigner` (signer CN=throwaway smoke3 versus Android Debug), same package. `install -r` over the debug-signed bridge failed (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, as expected); after `adb -s emulator-5554 uninstall io.github.pca.nexusbridge` and a fresh install: PCA logged `nexus bridge gone`, `nexus bridge untrusted`, `nexus bind untrusted`; screen "Bridge not trusted" with the detail text; the bridge process was never started (no pid, no service record, no `PCABRIDGE` line) over a 40 s window with switches flipped; with a wipe owed the retries at 30 s and 60 s logged `bind untrusted` and never bound. Installing the genuine bridge over it triggered an immediate bind (`bind untrusted` then `bind bound`) with no restart of PCA.

## 6. Logcat and system sweep

Full logcat sweep (buffer cleared between phases, so counts cover the last phases plus the observations above): `FATAL EXCEPTION` 0, `ANR in` 0, `StrictMode` 0, no `Process: com.rokid.context` or bridge crash; dropbox has no entry for PCA, the bridge or the hub (system strictmode entries at boot only). `nexus` lines in the last window: 30 `forget history code=NOT_REGISTERED`, 4 `trip ZONE_HOLD`, 1 each `wipe retry bound`, `unbound`, `trip LINK_LOST`, `bind untrusted`, `bind bound`. No card text, transcripts, tokens or keys appear in these logs or in this report.

## Findings

**B1 (MEDIUM, edge case) The bridge is never rebound after its process dies while Nexus is the host.** Repro: host = Nexus, bridge bound, `adb -s emulator-5554 shell am force-stop io.github.pca.nexusbridge`. PCA logs `nexus bridge gone` and nothing else; the bridge pid stays empty, bringing PCA to the front does not rebind, and the screen stays "Waiting for Nexus". It recovers only when the host is toggled or PCA restarts. Cause (read, not fixed): `NexusProvider.onGone()` clears the sink and `_bound` but leaves `wanted = true`, so `refresh()` (`if (!wanted) bindLocked()`) is a no-op. Force-stop clears the Android binding; a crash or low-memory kill would normally be restarted by Android for a bound service, so impact is mainly user force-stop or Doze-type kills. During the gap PCA keeps hiding nothing it never showed, but cards stop silently.

**B2 (LOW/MEDIUM, privacy visibility) A wipe owed with an untrusted, absent or Pending bridge is invisible to the user.** With the bridge untrusted (or the hub plugin Pending) after switching Nexus off, `wipe_owed=true` persists with retries up to every 15 min, and nothing on Settings > Rokid Nexus says a history wipe is still owed (the state reads "Bridge not trusted" or "Off"). The hub keeps notices up to 24 h. Not a leak by itself (nothing could be shown on an untrusted bridge), but the TRUST_LINES promise "PCA asks Nexus to wipe" has no user-visible failure state.

N1 (INFO) PCA's state machine has only `NexusResult` codes from HUD calls as evidence, and the sink drops before sending when glasses are not worn, so without worn glasses the screen reads "Waiting for Nexus" forever, even with the plugin Pending or Approved in the hub. Real states can only be checked on real glasses (S2).
N2 (INFO) Screen does not follow package changes while open: after uninstalling the bridge or hub the old state stays until "Check again" (this is by design, a button exists). A disabled bridge package still reads "Waiting for Nexus" rather than "Bridge cannot be used".
N3 (INFO) Approval in the hub is dropped when the bridge is disabled and re-enabled (state returns to Pending); a plain uninstall/reinstall also needs a fresh approval. The first call after a (re)bind returns `NOT_REGISTERED` for about 1 s (hub registration race): the sink handles it (retry at the 1 s tick; `takeover refused code=NOT_REGISTERED` is logged once).
N4 (INFO) The bridge manifest requests `surfaces,stt,tts,assistant` (+ skills catalog) at once, so the hub's single "Allow on glasses" grants speech and assistant too, even though PCA's three switches are OFF. The checklist step 3 ("surfaces first") cannot be followed with this APK; PCA-side gating and `setAssistantTakeover(false)` keep them inert.
N5 (INFO) ZONE_HOLD trips repeat on every fix (about 6 per minute in a zone) with nothing live; harmless, just log volume.
N6 (INFO, known) OnboardingGuard shows Welcome again at each cold start without a usable AI endpoint, which cost one onboarding pass per restart here.

## Could not be verified here (needs real glasses and phone)

Any notice, answer, place card, mark confirmation or status pin reaching the hub and rendering; the hub's notice history and its wipe of real content; the states Pending approval / Approved / Hub stopped / Link busy / Capability denied on PCA's own screen; a hide on trip while a card is showing and "kill PCA while a card shows: the bridge hides it"; assist-button ask, STT, TTS (audio only to glasses/earbuds); skills with the Assistant plugin and the per-operation consent screen; S2 link coexistence (PCA's CXR session beside the hub), gestures, popup layering and host-switch doubling; heat and battery over a 35 minute session; `authorizeListen` blocking; the signed release bridge installing over the debug-signed one (key lineage); the real v2.6.0 release hub (this smoke used a debug-signed build of the v2.6.0 source).
