# Trip day quick reference (Charminar 22 Nov, Ramoji 23 Nov), updated 2026-11-19

**Build (2026-11-20):** installed **18c1b76a** (Card position, Sikkim/Sri Lanka presets). Staged, not installed: `apks-v110-cand-4436d1a5/` (+ street map, trip log, pin-to-Moment; recommended for 22 Nov) and `apks-v110-train-166d3fd4/` (+ data saver, Model privacy; before Sikkim). Install only via `tools/install-trip-build.sh`: dry run, then `--go` on your go (validated backup first, phone first). Steps: `trip-install-checklist-2026-11-18.md` 0c; tests: `device-session-runbook-2026-11-19.md` section 13 (device needed).

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
- 'Pin a place here' adds your own sub-place (needs a fresh location fix; pins stay on the phone). 4436d1a5/train: capture a Moment of the spot first, then 'Attach latest Moment' (or 'Save without a Moment': flagged 'Needs a Moment'). Forgetting that Moment later deletes the pin too (the dialog names it).
- Train only: Settings > Data saver 'Now: ...' line tells why photos wait. On home mobile data photos still upload. Place Guide on mobile + 'I'm abroad' (or roaming) shows the nearby card only. Model privacy stays **Automatic** for the trip unless you decide otherwise (Offline only = nearby cards only, no seeding).
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

## Trip logging (added 2026-11-20)

- Retention: `files/logs/pca.log` (active) and `pca.log.1` ... `pca.log.59` (`.1` newest retained, highest number oldest), 5 MB each, 300 MB in all, oldest dropped first. Same private folder, same backup exclusion, Delete all clears every segment.
- Detailed log: Settings > Diagnostics > Detailed log, ON by default in this build (Developer mode also turns it on). Off keeps only warnings and key events (milestones). Without it most of the new lines below would not reach the file.
- New lines: `PcPlace place attempt ...` (one per Place Guide attempt: outcome, reason, call, cell, ids, buckets, ms, confPct, dedupe and rate-limit state, card result), `PcPlace pack lookup ...`, `PcDiag` (app start milestone, battery, network, clock, timezone, permission, worker, foreground, heartbeat hourly).
- Pull everything oldest first: `tools/link-health-report.sh --phone <serial>` reads all segments; for a manual pull copy `files/logs/` with `run-as` and `cat` the files from the highest `pca.log.N` down to `pca.log`.
- After each day (device needed): `tools/place-attempt-report.sh --phone <serial> --date <MM-DD>` (Place Guide outcomes, reasons, timings; `--pin-map <file>` adds your own labels on the laptop only) and `tools/link-health-report.sh --phone <serial> --date <MM-DD>`. Send both outputs to Claude.
- Privacy: the place ids (`wd:`/`osm:`/`user:`) in the log form a place-level trail of the trip, and info lines also reach logcat; Export diagnostics never includes `pca.log`. See PRIVACY.md section 8.
