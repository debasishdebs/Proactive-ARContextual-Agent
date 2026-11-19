# Nexus bridge: install and uninstall, 2026-11-19 (DRAFT, never run on a real device)

Sources: `nexus-device-checklist.md` (same folder), `android-companion/nexus-bridge/README.md`, `release/README.md`, `PRIVACY.md` section 6. Anything not in those is marked UNVERIFIED.

## Rules that always apply
- Back up the phone first (`v17-upgrade-recovery.md`); no call active.
- Install the bridge ONLY with `adb -s <phone serial>`. Never on the glasses or the watch.
- NEVER uninstall the PCA phone app (`com.rokid.context`): the SQLCipher key lives in the Android Keystore and the data is lost (`release/README.md`).
- NEVER install `watch-debug.apk` on the phone: same applicationId as the phone app (checklist safety rules).
- Install PCA itself via `tools/install-trip-build.sh` (dry run first); do not use the Rokid-Coder installer for PCA packages (`needs-user-2026-11-19.md` F).

## Install
1. Nexus hub v2.6.0 or newer on the phone (`com.anezium.rokidbus.phone`) and its glasses app, per upstream instructions (checklist Setup 1). Confirm which adb serial is the phone before any install.
2. Install the staged PCA phone APK, then `adb -s <phone serial> install -r nexus-bridge-debug.apk`. No launcher icon: it shows under Android Settings > Apps (package `io.github.pca.nexusbridge`).
3. Signer trust: `PcaSignerTrust` binds the bridge only if it is signed like the phone app (same certificate), or one certificate is in the other's rotation lineage; a pin list exists but is empty (`nexus-port/.../SignerTrust.kt`). A mismatch shows "untrusted bridge" in PCA Settings > Rokid Nexus and PCA sends it nothing. Debug bridge + debug phone share the debug key, so it works before rotation. After the phone key rotation the bridge must be signed with the release key or lineage (`release/README.md`); that a release bridge installs over the debug bridge is UNVERIFIED (checklist step 16).
4. In the Nexus hub approve the PCA bridge plugin. It declares `surfaces,stt,tts,assistant` and the hub pre-selects all four. For least privilege, in Nexus > Settings > Plugin access untick Speech to text, Text to speech and Replace the glasses assistant BEFORE tapping Allow on glasses. PCA's voice features then report "Capability denied" (expected). Switch them on later, live, in the same screen, only when you reach 1.6/1.7 (checklist Setup 3).
5. PCA Settings > Rokid Nexus should read "approved". The host stays PCA until you choose Nexus; the three switches (voice, tts, skills) are OFF by default.

## Uninstall the bridge only
1. In PCA Settings > Rokid Nexus set the host back to PCA (switching Nexus off asks the hub to wipe its notice history; `PRIVACY.md` section 6). The hub keeps notices up to 24 hours otherwise.
2. Open the Nexus hub history screen and check it is empty (checklist step 10). PCA keeps owing the wipe until the hub confirms; whether a wipe can complete after the bridge is gone is UNVERIFIED, so do steps 1 and 2 BEFORE uninstalling.
3. Remove it: Android Settings > Apps > PCA Nexus bridge > Uninstall (the bridge's own hint), or `adb -s <phone serial> uninstall io.github.pca.nexusbridge`. The bridge stores nothing, so nothing is left in it (`nexus-bridge/src/main/res/values/strings.xml`, `settings_remove_hint`).
4. Confirm PCA still works: with host = PCA, nudge/answer/place cards come through PCA's own glasses app, capture continues, and Settings > Diagnostics shows the glasses link up. A real-device run of this: UNVERIFIED. Whether the hub drops the plugin entry after uninstall: UNVERIFIED; remove it in the hub if it stays.
5. PCA data is untouched. The `nexus_host` prefs are kept by Delete all and excluded from backup (`PRIVACY.md`).

## What NOT to do
- Do not uninstall the PCA phone app or clear its data to "reset Nexus".
- Do not install the bridge or `watch-debug.apk` on the glasses, watch or wrong serial.
- Do not install a bridge signed with a different key under the same package (it shows untrusted; uninstall it first, then install the one from this PCA release).
- Do not publish or distribute a debug-signed bridge.

## Rokid-Coder coexistence
Rokid-Coder (a Nexus fork) and PCA/Nexus do NOT run together on the same glasses: one at a time, one CXR/bus session per phone (checklist Setup 1). Close or disable the Rokid-Coder hub and its glasses app before a PCA day, check PCA Settings > Diagnostics shows the glasses link up, and note which side drops if both start (that is the S2 question). PCA never installs, starts or takes over either (`PRIVACY.md` section 6). The packages are separate: PCA `com.rokid.context` / `com.rokid.context.glasses`.
