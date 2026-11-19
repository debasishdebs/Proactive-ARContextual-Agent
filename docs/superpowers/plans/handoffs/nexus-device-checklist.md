# Nexus line device checklist (v1.5 to v2.0), written 2026-11-18 (T9, part 1)

Everything here needs the real glasses + phone. Nothing is installed. Code for T1-T8 exists behind OFF flags (modules `:nexus-port`, `:nexus-bridge`; phone `nexus/`). Run in this order; each block gates one version (see the end of `docs/superpowers/plans/2026-11-16-roadmap-v1.1-v1.3.md`).

Safety rules for every step: phone backup first (`v17-upgrade-recovery.md`), no call active, install the bridge only with `adb -s <phone serial>`, never install `watch-debug.apk` on the phone (same applicationId as the phone app), never uninstall the PCA phone app.

## Setup (once)
1. Install the Nexus phone hub (`com.anezium.rokidbus.phone`, upstream release v2.6.0 or newer) and its glasses app per upstream instructions. Note: Rokid-Coder and Nexus/PCA-on-Nexus do NOT run together on the same glasses (one at a time). Close or disable Rokid-Coder first.
2. Install `nexus-bridge-debug.apk` (no launcher icon: find it under Settings > Apps) and the staged PCA phone APK. The bridge must be signed by the same key as the PCA phone app (PcaSignerTrust): a mismatch shows "untrusted bridge" in Settings > Rokid Nexus.
3. In the Nexus hub, approve the PCA bridge plugin (capabilities: the bridge declares surfaces, stt, tts and assistant together and Nexus pre-selects all four. Approving the plugin in the hub grants voice capabilities at the hub level while PCA's switches keep them unused. For least privilege, in Nexus > Settings > Plugin access untick Speech to text, Text to speech and Replace the glasses assistant BEFORE tapping Allow on glasses; switch them on later, live, in the same screen, only when you reach 1.6/1.7. With them unticked PCA's voice features report Capability denied, which is expected). Settings > Rokid Nexus in PCA should read "approved".

## S2: link coexistence (gates v1.5). If any of 4-6 fails, the Option B transport is needed (about +1 day)
4. With PCA's own glasses link up (normal capture running), start the Nexus hub link: do both hold the session? (BUSSPEC says one CXR session per phone: record exactly what happens: PCA drops, hub drops, or both work.)
5. Repeat with the hub first, then PCA connects.
6. Disconnect/reconnect each side: does the other recover without a manual restart? Watch PCA's hub-recover and watchdog logs (the hub restart fix must not fight the Nexus hub for the link).
7. Gestures/popup layering: a PCA place card and a Nexus notice at the same time; double-tap pause/resume still works; the glasses capture camera is not blocked.
8. HUD host switch: choose 'Nexus' in PCA: PCA glasses popups stop (nudge, answer, place card), cards appear via Nexus once; switch back: everything returns once, nothing doubled.

## v1.5 behaviour checks (cards and pin)
9. Nudge, place card (Place Guide on), mark confirmation, capture-state pin appear on the Nexus HUD with the expected text (<= 1024 chars), Sources footer on answers.
10. Withdraw: Forget a photo whose nudge/answer is showing, Delete all, pause (phone and glasses), enter a mock/real pause zone, switch the host off: each hides the card within about one tick and wipes the Nexus notification history (the hub keeps notices up to 24 h: verify the history screen is empty afterwards). Kill the PCA process while a card shows: the bridge hides it.
11. Untrusted bridge: install a bridge APK signed with a different key under the same package (or pin test): PCA must refuse to bind and show the untrusted state.

## S3: speech, battery, heat (gates v1.6/v1.7)
12. Assist button (1.6): enable 'Nexus voice' (off by default); press the assist button, ask a question in English and in your usual mix of Hindi/Hinglish: transcript quality, answer card, latency; in a zone, not worn, or paused: nothing is delivered and speech stops.
13. TTS (1.7): enable 'Speak answers': audio only to glasses or earbuds (never the phone speaker); stop on trip.
14. Battery/heat: a 35-minute session with HUD cards + assist enabled vs baseline capture only: record glasses temperature and phone battery drop; the thermal gate must still refuse extra work when hot.

## Skills (1.8) and release (2.0)
15. Skills: run each of status, pause_capture, resume_capture (must refuse inside a zone pause), mark_moment (zone/not-worn refusal) from the Nexus Assistant; none returns memory text.
16. Release: build the bridge release signed with the PCA key lineage (after the phone key rotation, planned after Sri Lanka, 1.3.0 lineage; `docs/superpowers/specs/2026-11-16-privacy-and-release-design.md`), check it installs over the debug-signed bridge, publish to GitHub only (signed APK, never debug-signed).

## Known limits to remember
- The coexistence status (hub stopped vs link busy) is a heuristic until S2.
- `authorizeListen` is a blocking binder call with no timeout (a hung phone would stall the plugin thread): watch for it in step 12.
- Already proven on the emulator with the REAL bridge and the real v2.6.0 debug hub (2026-11-19, `docs/superpowers/reviews/2026-11/nexus-bridge-real-hub-smoke-2026-11-19.md`): discovery and Pending, step 3 (untick three, Allow, "Approved"), the skills catalog accepted (no "turned off"), host switch to Nexus enabled and "Waiting for Nexus", the history wipe on host-off answered OK, hub restart re-registers approved with no prompt (the bridge restarts the hub itself), bridge uninstall. You do not need to re-check these on the glasses beyond a glance.
- Step 3 detail: the hub shows FIVE toggles for the bridge (Replace the glasses assistant, Offer skills, Speech to text, Show on your glasses, Text to speech). Untick the three voice ones as written; "Offer skills" is harmless without an Assistant. The PCA screen needs Developer mode (Settings > Diagnostics) to show the Rokid Nexus row.
- NOT yet seen with the real bridge (needs a glasses link, because PCA drops every publish as NOT_WORN without one): the hub's answer to a real publish (NOT_GRANTED text for the unticked rows, CAPABILITY_NOT_AVAILABLE with no link, NOT_REGISTERED in the first ~2 s after a hub restart), any rendering, the per-operation skills consent screen (needs the Assistant plugin), speech.
- Bug fixed after the smoke (183ee884): uninstalling the bound bridge used to show "Bridge not trusted"; it now shows "not installed". The staged phone APK 5bd9fc6f does not have this fix (cosmetic; relevant only if the bridge is removed while bound).
- No Nexus HUD publish has run against a real hub yet.
- Hung-hub residual window (v2 r4, observe at S2): a Binder call already inside a stuck Nexus hub cannot be interrupted by PCA. If the hub stalls (for example a slow link) while a notice is being sent and the user then pauses or enters a zone, that notice may still appear when the hub unblocks, however late, and is hidden by the bridge right after (and wiped by the owed history wipe). PCA only stops sending more. Note on the device whether any stall longer than 2 s happens at all, and how long the hub takes to unblock. This is a known limit, not a bound.
