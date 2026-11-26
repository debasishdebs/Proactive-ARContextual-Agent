# Changelog 1.5.1 to 1.9.1

This is one release, 1.9.1, built on top of 1.9. It carries everything that was built since the 1.5 to 1.9 labels were cut. The sections below list the changes by the version each one belongs to, so you can see which feature came from which step. Later fixes that come out of real-phone testing will ship as 1.9.2 and later, and each will name the version it fixes.

## Read this first

- **Tested on an emulator, not yet on a real phone.** The database upgrades (the private memory format and the places database) were rehearsed on an Android emulator, and key rotation was rehearsed with throwaway keys. Everything else is covered by unit tests only. No part of this release has been verified on a real phone or on the glasses yet.
- **Back up first.** This release changes the format of the private memory database on the phone. Before you install it over an older build, export or back up your data, and keep the backup until you have checked that your memories are still there. A phone that has run an earlier partial build of the new format should not be upgraded in place; start from a backup.
- **Do not install a debug build over a release build or the other way round.** Android refuses a different signature, and the fix is to uninstall, which deletes the app data.
- **Everything experimental is off.** See "Off by default" and "Developer only" below.

## What is OFF by default

These features exist in the app but do nothing until you switch them on:

- Offline place guess ("Maybe: place") and the on-phone photo captioner it needs (three separate switches, and a model download).
- Developer data export (dump) and Developer data import.
- Scene centres, the test-mode place matcher that learns from several photos of a place (log only, cards do not change), and the second image model comparison.
- Pack Moments, retention clean-up that keeps what cards and pins still need, canvas model nodes and the canvas Test run (these last ones were already off in 1.9).

These are ON by default:

- Saving the place of a Moment (optional, local only), place card history, and forgetting several items at once (switch in Settings, Privacy).
- A short stored description on place cards. It only shows when you are offline near an area you have downloaded.

## Developer only

The following need Developer mode to be switched on, and are meant for people who read logs and run scripts: the data export (dump) API and its `pull_dump.py` client, the data import API and its `push_import.py` client, the offline captioner and offline guess switches, the shadow matcher switches, and the canvas switches.

## 1.5.1

- You can enter your own street-map key in Settings. The public build ships without any key, and nothing is sent to a map service until you add one. (This ships in the same install as 1.6.0.)
- Map loading no longer freezes the screen while the key is read, and a loading state is shown.

## 1.6.0

- A newer private memory format (database version 18). The phone now remembers which photos belong to which scene, which model wrote each caption or summary, and which place card came from which place. The places database also gets room for area names and reference descriptions.
- Builds can now be signed with a release key while keeping your existing data (key rotation). A helper script and an APK secrets scan are included.

## 1.6.1

- Fixes to scene grouping: a scene centre now uses only its own photos and handles duplicate chains safely.
- A tested upgrade path from the previous memory format, rehearsed on an emulator.
- The review records for this work are published under `docs/superpowers/reviews`.

## 1.7.0

- New Developer tools to read your data out of the phone (dump) and to put saved places, pins, marks, place cards and scenes back in (import). Both are off by default and need Developer mode.
- An optional second image model (SigLIP 2) can be downloaded. Nothing is downloaded unless you ask.
- Place matching can learn from several photos of one place, in test mode only: it writes to the log and nothing changes on screen.

## 1.7.1

- Safety fixes to the Developer tools: pause zones are respected, forgetting during a read or restore cannot leave pieces behind, files that belong to something else are never replaced, a photo-less re-send of place cards is handled, and a crash on clean builds was fixed.

## 1.8.0

- Activity gets a map of your Moments, a red "no network" banner on the map screens, and a Centre on me button.
- Seed whole areas for offline place guides in the background, with progress, retry on failure, rename, and an estimate of the size. Data credits for map and place sources are added.
- Deleting a place asks first, and can also forget the Moments linked to it.
- Forgetting several items at once is on by default (switch in Settings, Privacy).
- The glasses screen wakes when a card opens by itself.

## 1.8.1

- Clean-up no longer removes photos that a card or pin still needs.
- Maps do not show old online tiles while you are offline.
- Deleting a seeded area removes exactly the places that were seeded.
- Timing values for seeding are kept out of backups.
- Wording and test fixes for the Activity map banner.

## 1.9.1

- Offline place cards can show a short stored description.
- Experimental, off by default: on-phone photo descriptions and a hedged "Maybe: place" card when you are offline. It needs a model download and three switches. The native library behind the captioner has never run on a real phone.
- Canvas model nodes respect the chosen backend on fallbacks too.
- Design notes added under `docs/superpowers/plans`.

## Known limits

- Real-phone and real-glasses behaviour of the new maps, seeding, Centre on me, dump, import, Forget on several items and the offline guess is not yet verified.
- The offline captioner is slow and may be wrong. "Maybe" cards are guesses and say so.
- Seeding needs a network connection and can take a while for large areas.
- Some seeded or downloaded data depends on third-party sources; see `THIRD_PARTY_NOTICES.md`.
- The street map needs your own key; the map key is held in the app's private storage.
- Release signing is prepared but not yet used for public releases.
