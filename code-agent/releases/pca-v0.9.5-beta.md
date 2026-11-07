## v0.9.5-beta

Debug-signed; no APKs are attached. Phone database is Room v13 (additive migrations, one-way: back up before every `adb install -r`, never uninstall).

**Hardening**
- The glasses trace recorder is built only on the writer thread; every deletion path writes its cleanup outbox rows in the same transaction through one helper, with a bounded drain; retention writes phone-file rows only; queued trace controls keep the clocks sampled at their arrival.
- Every Moment run and Scene run lists all marks of its Moments as lineage; A/B runs apply the Photo settings of each arm; watch run rows are deleted when the watch source is switched off (retried until a running export has finished).
- All production logging goes through `PcLog` (glasses, hub, link, phone services, settings UI, voice and services modules), with a strict content scan test: ids, paths and endpoint names are dropped from messages.

**Privacy and Forget**
- One `DataOpGate` makes export and delete mutually exclusive; mark undo, watch-off row deletion and the settings and diagnostics exports take it; the Forget dialog names a running export and when to retry.
- Forgetting a fact deletes the cached memory export; the memory export carries only valid, visible memories (no forgotten, hidden, stale or orphaned ones); a residue oracle checks every table, file and lineage token for the app-reachable triggers.

**Moments and catch-up**
- The Moment cadence is scheduled on every start path, whatever the pipeline state; a catch-up marker is written only after marks were found; counts-only cadence and catch-up logs; a developer `POST /api/moments/rebuild?day=` route (rate limited, counts only).

**Settings and canvas**
- Watchdog silence and tick, the photo Forget radius (default 10 s) and Scene rebuilds per day (default 24) are settings; the Moments input is shown in the node palette (not liveable); the `capture_timing` prefs file is excluded from backup.

**Reviews**
- Hardening, forget-gaps and export work went through Codex fix rounds; the road-to-v1 plan was revised and the 2026-10-25 rulings were recorded.

**Known limitations**
- Not yet verified on a worn day; derive stays off; the watch-run route and Scene building flows are not wired.
