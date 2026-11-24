VERDICT: NOT READY

Reviewed `6725c88f`; static review only. No files edited; no Gradle, adb, or tools scripts run. Traces below use filename suffixes within `android-companion/`.

1. **[P1] Privacy check precedes a suspension.** `PlaceHttp.kt:86` checks privacy, then `:88` dispatches to IO. If that dispatch queues and the user enables Offline only, `:91–108` opens/connects without rechecking. This request was not already on wire. Recheck inside IO immediately before connection.
2. **[P2] Restart still defeats the fallback cap.** `FallbackParkCap.kt:15–23` keeps counts only in memory. Repeated process death before park seven permits indefinite parking; cluster propagation fixes do not close this earlier finding.
3. **[P3] First DataSaver refresh can still run on main.** `DataSaverGraph.kt:202–205` installs a synchronous refresh callback and calls `monitor.start()`. `NetworkCostMonitor.kt:56→76` invokes it synchronously for an active network, reaching `refreshState→heldBack→privacy.current()` before the IO launch at `DataSaverGraph.kt:207`.

AUTO/VPN: **no new refusal found**. Place HTTP, embedding factories, DNS and endpoint guards pass AUTO; `OfflineOnlyNet.check():63` returns before VPN refusal. However, its default argument at `:62` eagerly probes even for AUTO. Probe exceptions become `true`, but cause refusal only outside AUTO. Move probing after the AUTO return to preserve the default path without extra work.

OFFLINE: `PlacePack.kt:336–338` returns before cell/place writes or backoff. Seeds do **not** loop indefinitely: `:251–271` retries uncounted outcomes once, then marks partial; production admission stops persistent Offline only via `SeedStopped` and writes partial (`:219–222`). Both seed UIs clear `seeding`; empty OFFLINE glance results remain eligible for a later poke (`PlaceSampler.kt:424–427`).

Tests inspected: AUTO DNS with VPN (`OfflineOnlyDnsTest.kt:61`), AUTO route with `vpn=true` (`OfflineOnlyNetTest.kt:73`), proxy preservation (`:144`), default DataSaver (`ModelPrivacyStoreTest.kt:173`), existing embedding defaults, and Place HTTP AUTO admission. Missing: AUTO through the interceptor with a throwing probe, queued-IO mode flip, synchronous monitor-start refresh, and seed OFFLINE integration. Tests were not executed.

| Earlier finding | Status and trace at tip |
|---|---|
| #1 — every place request | **STILL OPEN**, narrower dispatch gap: finding 1; typed refusal otherwise propagates through mirror/cell catches to `PlacePack.kt:336`. |
| #2 — VPN route | **CLOSED**: `OfflineOnlyGuard.kt:17`, DNS `:17–37`, route guard `OfflineOnlyNet.kt:63–72`; Offline only refuses non-loopback behind VPN. |
| #3 — embedding key read | **CLOSED**: `Embedders.kt:36→39`, `:62→63`; guard precedes both key reads. |
| #4 — cluster flag/cap | **CLOSED**: `KeyframePipeline.kt:547→595→604`; carries flag and caps cluster IDs. |
| #5 — restart cap | **STILL OPEN**: finding 2. |
| #6 — main-thread first load | **STILL OPEN**: finding 3. |
| #7 — `.local` lookup | **CLOSED**: `OfflineOnlyDns.kt:17–21,34–37`; refusal precedes delegate lookup. |
| I-P2-a / I-P2-b | **CLOSED**: unchanged `ModelPrivacyStore.kt:102–113` serializes/version-checks writes; superseded writes return null. |
| I-P2-c | **STILL OPEN**: restart cap, finding 2. |
| I-P2-d | **CLOSED**: `DeletionGraph.kt:114` inventories `last_choice`. |
| I-P2-e | **CLOSED** for listed omissions; `ClusterCallTest.kt:132` now covers cluster cap propagation too. |
| I known window | **STILL OPEN**: `ModelPrivacyStore.kt:90–91` uses asynchronous `apply()` before durable flush. |
| J-P3 — Saving | **STILL OPEN**: final store check `:111` can precede newer tap; unversioned callback at `ModelPrivacySettings.kt:87` clears newer Saving state. |
| J-P3 — no_link | **STILL OPEN**: `Hub.kt:308–309` converts throwing push to deferred; `ZoneController.kt:366` labels it `no_link`. |
| audit P3-2 | **CLOSED**: pre-lookup `.local` refusal above. |
| audit P3-4 | **CLOSED** narrowly: asynchronous flow getters `ModelPrivacyStore.kt:59–65`; separate finding 3 remains. |
| Model download unguarded | **CLOSED** as previously overruled: deliberately disclosed separate download path, `PRIVACY.md:210`. |