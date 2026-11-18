# Trip day quick reference (Charminar 22 Nov, Ramoji 23 Nov)

Build: `~/Desktop/rokid/backups/apks-v110-f1dcf1fb/` (phone + glasses). Full steps: `trip-install-checklist-2026-11-18.md`.

## Before leaving the house (internet needed)
1. Phone: Settings > Place Guide (Developer mode on): leave switch OFF. Tap 'Seed this area' (Hyderabad preset), wait for the result line (cells ok / without OSM extras / failed). Tap 'Seed Ramoji Film City (23 Nov)'. If a cell failed: tap again.
2. Settings > Privacy > Pause zones: confirm your own zones (none required for Charminar/Golconda).
3. Phone and glasses charged; glasses worn-test done; Settings > Pause shows nothing paused.

## At the first monument
- Switch Place Guide ON. Look at a monument for a few seconds (dwell): a card shows on the glasses at MEDIUM/MAX level: name + one sentence (+ 'Wikipedia - fetched <date>'). Offline you get 'name . nearby . not checked'.
- 'Pin a place here' to add your own sub-places (e.g. a gate or a hall): needs a fresh location fix; pins stay on the phone.

## If something looks wrong
- Pause capture: double-tap on the glasses, or Settings > Pause on the phone. A pause zone can never be resumed from inside it.
- Home shows 'Paused: you're in a pause zone' = a zone is active (fail closed: no location = paused when a zone is enabled).
- Place cards missing: check Place Guide is ON, the pack has places for the area (Settings > Place Guide shows counts), the glasses HUD level is MEDIUM or MAX.
- Phone will not open its data: STOP. Do not uninstall. Follow `v17-upgrade-recovery.md` (restore the backup tar made before the install).

## After the day
- Settings > Diagnostics: note the hub state and any 'restart' lines; tell Claude what you saw.
