# Trip day quick reference (Charminar 22 Nov, Ramoji 23 Nov), updated 2026-11-19

**Build:** default `~/Desktop/rokid/backups/apks-v110-f1dcf1fb/`; optional candidate `apks-v110-cand-18c1b76a/` (adds Card position, Sikkim/Sri Lanka presets; not installed yet, install only via `tools/install-trip-build.sh`, dry run first). Steps: `trip-install-checklist-2026-11-18.md`.

## Switches (Settings, Developer mode on)
- **Place Guide** (OFF by default): ON at the first monument = a card appears on the glasses after you look at a place a few seconds (MEDIUM/MAX HUD level). Offline it shows 'name, nearby, not checked'. Seed while it is OFF.
- **Card position** (candidate build; Settings > Place Guide): LEFT / CENTRE / RIGHT, default CENTRE. Try each once. Look for: card clipped at an edge, text cut off (side body ends with '...' after about 12 words is known), readability in sunlight. Note which position reads best.
- **Suggestions delivery switches (3)**: leave OFF. Only in the supervised, stationary, synthetic worn test (device-checklist section G), then switch OFF again.

## Before leaving (internet needed)
1. Settings > Place Guide > 'Seed this area' (Hyderabad), then 'Seed Ramoji Film City (23 Nov)'. Read the result line: **cells ok** = good; **seeded without OSM extras** = usable, fewer extras; **failed** = tap again.
2. A hotel or airport Wi-Fi login page makes a cell fail or come back partial: retry on mobile data.
3. Cell log lines show `rejected=N coordsDisagree=N`: places dropped because Wikipedia and Wikidata coordinates differ by more than 1 km. Birla Mandir will NOT appear, by design.
4. Settings > Privacy > Pause zones: your own zones only. Settings > Pause shows nothing paused. Phone and glasses charged.

## At the monument
- 'Pin a place here' adds your own sub-place (needs a fresh location fix; pins stay on the phone).
- Rokid-Coder: close its hub and glasses app BEFORE a PCA capture (one glasses link at a time). Check Settings > Diagnostics shows the link up. If PCA's link drops when Rokid-Coder starts, write down which side dropped.

## Fallbacks
- Pause capture: double-tap on the glasses, or Settings > Pause on the phone. A zone pause cannot be resumed from inside the zone.
- 'Paused: you're in a pause zone' on Home = a zone is active (no location fix = paused when a zone is enabled).
- No cards: Place Guide ON? Settings > Place Guide counts for the area? HUD level MEDIUM/MAX?
- Phone will not open its data: STOP, do not uninstall, follow `v17-upgrade-recovery.md`.

## DO NOT
- Do not install `watch-debug.apk` on the phone (same applicationId: it replaces the phone app).
- Do not uninstall PCA (never; `install -r` only).
- Do not enable the Suggestions delivery switches outside the supervised worn test.
- Do not use the Rokid-Coder installer for PCA packages.

## After the day
Settings > Diagnostics: note the hub state and any 'restart' lines; tell Claude what you saw (also the card position verdict and any failed seed cells).
