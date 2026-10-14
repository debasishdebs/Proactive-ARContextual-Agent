# Proactive Context: settings and persisted preferences inventory (2026-10-14)

Branch `feat/proactive-mvp-implementation` at `c5060c6`. Read from the code: `CM/Protocol.kt` `HudSettings`, the Settings screens under `PH/phone/ui/settings/**`, every `getSharedPreferences` / `EncryptedSharedPreferences` / DataStore call in the `src/main` trees, the Room entities, and `phone/src/test/java/com/rokid/context/BackupRulesTest.kt` with `phone/src/main/res/xml/{backup_rules,data_extraction_rules}.xml`. Keys and files are named only; no values of secrets, tokens or endpoint addresses appear here.

**Paths:** `PH` = `android-companion/phone/src/main/java/com/rokid/context`, `GL` = `android-companion/glasses/src/main/java/com/rokid/context/glasses`, `CM` = `android-companion/core/model/src/main/kotlin/com/rokid/context/core/model`. **DL** = decision log, **SL** = session log, **SB** = status and backlog.

**Backup column.** "Restorable" means the file is on `BackupRulesTest.restorable`, or is a Room row that is restored only if the DB is (it never is). "Excluded" means it is listed in both rule files and pinned by the test. The phone's `context.db` (+ `-wal`/`-shm`) is **excluded**, so every Room-backed setting is excluded. The glasses app has `allowBackup="true"` and **no rules**, so everything on the glasses is backed up by default (DL §4 P8 device M3; devfix/r3a left glasses prefs out of scope).

**Synced column.** "HudSettings.x" means the value travels to the glasses in `pc_settings`, and the glasses cache it in their `hud` prefs.

## 1. Phone: user-visible settings

| Screen / location | Setting | Default | Range / options | Storage (file / key or table) | Synced to glasses? | Backup | Decision / ruling (source) |
|---|---|---|---|---|---|---|---|
| Settings → Rokid Glasses | Verbosity | Medium (or the legacy `app_config/glasses_silency_mode` mapping when there is no stored value) | Silent / Medium / Max | `hub_settings` / `hud_settings` (HudSettings JSON) | HudSettings.verbosity | Restorable | v1 §3.4; legacy mapping `HubSettingsStore.verbosityFor` (P4/T11, DL §1) |
| Settings → Rokid Glasses | HUD size | 0.4 (40 %) | 0.3–1.0 slider (`Hud.MIN_SCALE..MAX_SCALE`) | `hub_settings` / `hud_settings` | HudSettings.scale | Restorable | v1 §3.3; one write per drag (P5/T16, DL §1) |
| Settings → Rokid Glasses | Position | Bottom center | 3×3 grid (`HudPosition`) | `hub_settings` / `hud_settings` | HudSettings.position | Restorable | v1 §3.3; labelled 36 dp grid (P5/T16) |
| Settings → Rokid Glasses → Gestures | Tap / Double-tap / Swipe forward / Swipe back | Ask / Pause-resume / Next page / Prev page | Ask, Pause/Resume, Next page, Prev page, Dismiss, None (Ask greyed out with a reason when voice is unavailable) | `hub_settings` / `hud_settings` | HudSettings.gestures | Restorable | v1 §3.5; unmapped → default (DL §1 P3/T9); default double-tap → exit gate (GM spec, v1 Amendment A4) |
| Settings → Rokid Glasses → Minimised | Icon corner | Top right | Top left / Top right / Bottom left / Bottom right | `hub_settings` / `hud_settings` | HudSettings.iconCorner | Restorable | GM spec §3/§6 (`0cac4d9`, `84c692b`) |
| Settings → Rokid Glasses → Minimised | Pop-up time | 6 s | 3–20 s slider (clamped on use) | `hub_settings` / `hud_settings` | HudSettings.popupSeconds | Restorable | GM spec §4/§6; confidence L (SL §15) |
| Settings → Rokid Glasses | Re-authorize (action) | — | — | Clears `rokid_link` / `token` | — | Excluded | v1 §2.4; token stays in plain prefs (DL §1 P3/T3) |
| Settings → Capture schedule | Interval ("Every") | 5 min (300 s) | 30 s, 1, 2, 5, 10, 15, 30 min, 1 h (clamped 30 s–1 h; doubled at low glasses battery) | `hub_settings` / `hud_settings` | HudSettings.captureIntervalSec | Restorable | DL §1 P3/T11 cadence; v1 Amendment A6; faster cadence deferred (SL §16 Develop 1) |
| Settings → Capture schedule | Burst on change | On | On / Off | `hub_settings` / `hud_settings` | HudSettings.burstOnChange | Restorable | v1 §2.4 |
| Settings → Privacy | Pause capture | — (running) | For 30 minutes / For 1 hour / Until I resume; "Resume all shown" | Room `pause_record`, `pause_interval` (phone); glasses `pauses.preferences_pb` | HudSettings.pauses, resumedPauseIds, pausesExact | Excluded (DB) | v1 §5.5; capture-only (DL §1 P7/A); 90-day tombstones (P7/A I2) |
| Settings → Privacy | Quiet hours on/off | Off | On / Off | `hub_settings` / `hud_settings` (quietHours null = off) | HudSettings.quietHours + zoneId | Restorable | v1 §5.5; off ends the running pause (r2a BUG-R2-6) |
| Settings → Privacy | Quiet hours From / To | 22:00 / 07:00 | 15-min steps, 00:00–23:45; minutes validated 0..1439 | `hub_settings` / `hud_settings` | HudSettings.quietHours | Restorable | P7/A M12 range check; DST gap rule (P7/A1-a) |
| Settings → Privacy | Battery policy (read-only text) | Phone defers ≤ 30 %; glasses stop ≤ 10 % | — | Constants `BatteryPolicy` | — | n/a | Shows the code's thresholds, not the plan's 20 % (DL §1 P7 battery copy; DL §4 P7/A open) |
| Settings → Phone camera & mic | Speech language | English | English / Hindi | `voice_settings` / `language` | No | Restorable | v1 §0.1, §5.1.1; Hindi needs a pack |
| Settings → Phone camera & mic | Use the phone mic for Ask | On | On / Off | `voice_settings` / `phone_mic` | No | Restorable | v1 §2.4; treated as off until prefs load (DL §1 P6/T10) |
| Settings → Phone camera & mic | Voice status (read-only) + Check again / Download pack / Open settings | — | Supported / Needs speech pack / Needs Android 13+ / Not verified | `voice_probe` / `fingerprint`, `verdict`, `from_guard` | HudSettings.askBlockedReason (derived) | Excluded | v1 §5.1.1; NOT_VERIFIED verdict (P6/T5–T6) |
| Settings → Phone camera & mic | Capture now (action) | — | — | Writes a frame (`rokid_phone_<ts>.jpg`) | — | Excluded (frame dirs) | R-66; source `rokid_phone` (P7/C ruling 1) |
| Settings → AI → Agent flows | Flow selection (multi-select) | none | Any subset of Capture / Ask / Nudge | Not persisted (session only) | No | n/a | AF spec §4; not session-scoped across process death (DL §4 AF M5) |
| Settings → AI → On-device models | Allow mobile data | Off | On / Off | `app_config` / `local_models_allow_metered` | No | Excluded (app_config is set-up state) | ODE spec §4/§5; restore brings it back off (BackupRulesTest, DL §1 ODE/review merge) |
| Settings → AI → On-device models | Download / Use for Embeddings / Delete (actions) | — | Catalog: `bge-small-en-v1.5-q8` | `files/models/*`; endpoint `local_bge-small-en-v1_5-q8` in Room `endpoint`; `role_binding` EMBED | No | Excluded | ODE spec §5; id sanitised (DL §1 ODE/U) |
| Settings → AI → Model endpoints | Endpoint: name, type (kind), address, API version, models, capabilities ("Can do"), Test with | Seeded build endpoint (address/deployment from BuildConfig; no key) | Kinds AZURE_OPENAI / OPENAI_COMPAT / OLLAMA / LLAMACPP_LOCAL; caps CHAT, VISION, EMBEDDINGS, JSON_SCHEMA | Room `endpoint` | No | Excluded (DB) | v1 §4.1; no key in APK (DL §1 P3/T2b); bound-endpoint delete blocked (P5/T17) |
| Settings → AI → Model endpoints | API key (write-only field; "Remove key") | none | Masked, password keyboard | `endpoint_keys` (EncryptedSharedPreferences), keyed by the endpoint's `keyRef` | No | Excluded | DL §1 P4/T9, P4/final; P8/D2 key lifecycle |
| Settings → AI → Models by role | Per role: endpoint, model, fallback, temperature, max tokens | Seeded bindings to the build endpoint; temperature/maxTokens empty (provider default; answer cap 4096) | Roles VISION_EXTRACT, EXPLICIT_REMEMBER, SUMMARIZE, CRITIC, ANSWER, NUDGE, EMBED; temperature 0–2 | Room `role_binding` (`endpointId`, `model`, `temperature`, `maxTokens`, `fallbackEndpointId`, `fallbackModel`) | No | Excluded (DB) | v1 §4.2; tested onboarding preset rebinds untouched bindings (P8 final fix-phone I1); 4096/8192 cap (v1 Amendment A10) |
| Settings → AI → Prompts (header) | Keep prompt versions | On | On / Off | `prompt_settings` / `keep_versions` | No | Restorable | v1 §2.4, §4.3 |
| Settings → AI → Prompts → editor | Template, output schema, active version, notes | Seeds from `assets/prompts/` | 64 KB schema cap; 5-gate save | Room `prompt`, `prompt_version` | No | Excluded (DB) | v1 §4.3–4.5; P5/T18 |
| Settings → AI → Retrieval | Search mode | Hybrid | Lexical / Semantic / Hybrid | `retrieval_settings` / `mode` | No | Restorable | v1 §4.6 |
| Settings → AI → Retrieval | Fusion | Weighted score | Weighted score / Reciprocal rank (RRF) / CombMNZ | `retrieval_settings` / `fusion` | No | Restorable | v1 §4.6 |
| Settings → AI → Retrieval | Semantic weight | 0.6 (60/40) | 0–1 | `retrieval_settings` / `weight` (stored as text) | No | Restorable | v1 §4.6 |
| Settings → AI → Retrieval | RRF k | 60 | 1–200 | `retrieval_settings` / `rrf_k` | No | Restorable | v1 §4.6 |
| Settings → AI → Retrieval | Results (top-k) | 8 | 1–20 | `retrieval_settings` / `top_k` | No | Restorable | v1 §4.6 |
| Settings → AI → Retrieval | Prefer recent memories | On | On / Off | `retrieval_settings` / `recency` | No | Restorable | v1 §4.6 |
| Settings → AI → Retrieval | Half-life | 3 days | 0.25–30 days | `retrieval_settings` / `half_life_days` | No | Restorable | v1 §4.6 |
| Settings → AI → Retrieval | Minimum confidence | 0.00 | 0–1 | `retrieval_settings` / `min_confidence` | No | Restorable | v1 §4.6 |
| Settings → AI → Retrieval | Rebuild index (action) | — | — | `index_maintenance` / `rebuild_pending`, `rebuild_pending_token`, `consumed_rebuild` | No | Excluded | Rebuild while paused is persisted (P5/T11) |
| Settings → AI → Persona & language | Persona text | "A concise, friendly assistant." | ≤ 300 chars, whitespace collapsed | `persona_settings` / `text` | No | Restorable | v1 §4.3 `{{persona}}` |
| Settings → AI → Persona & language | Reply language | Same as the question | Same as the question / English / Hindi | `persona_settings` / `language` | No | Restorable | v1 §0.1; Hindi = ReplyLanguage.HINDI (DL §1 P7/E) |
| Settings → Data sources | Calendar / Contacts / Notes / Location | All off | On / Off each (permission asked on enable) | `context_sources` / `source_calendar`, `source_contacts`, `source_notes`, `source_location` | No (never sent to glasses, v1 §5.4) | Restorable | DL §1 P7/C1 |
| Settings → Data sources | Nudges per day | 6 | 0 (off) / 3 / 6 / 10 | `context_sources` / `nudges_per_day` | No | Restorable | v1 §5.2; DL §1 P7/C1 |
| Settings → Actions & tools | Reminder / Shopping list / Calendar event enabled | On / On / Off | On / Off each (calendar asks WRITE_CALENDAR) | `action_settings` / `enabled_reminder`, `enabled_shopping_add`, `enabled_calendar_event` | No | Restorable | v1 §5.3; DL §1 P7/B |
| Settings → Actions & tools | Always confirm (per action) | On | On / Off each | `action_settings` / `confirm_reminder`, `confirm_shopping_add`, `confirm_calendar_event` | No | Restorable | v1 §5.3 matrix (DL §1 P7/B matrix) |
| Settings → Actions & tools | MCP servers | disabled row "Coming in v2" | — | — | — | n/a | R-63 |
| Settings → Shopping list | Items (swipe to remove, Undo) | empty | — | Room `shopping_item` | No | Excluded (DB) | kept on Delete all (DL §1 P7×P8 ruling 2) |
| Settings → Memory | Short-term retention | 24 h | 12 / 24 / 48 h | `memory_settings` / `short_hours` | No | Restorable | v1 §2.4; MVP §10.2; retention ruling (v1 Amendment A16) |
| Settings → Memory | Medium-term retention | 7 days | 3 / 7 / 14 days | `memory_settings` / `medium_days` | No | Restorable | same |
| Settings → Memory | Long-term retention | 30 days | 30 / 90 / 365 days | `memory_settings` / `long_days` | No | Restorable | same |
| Settings → Memory | Soft limit (quota) | 100 MB | 50 / 100 / 250 / 500 / 1 000 MB (stored range 10–2 000) | `memory_settings` / `quota_mb` | No | Restorable | v1 §2.4; banners at 70/90 % (v1 §2.2) |
| Settings → Memory | Encrypt memory | Off (seeded once from legacy `app_config/encryption_enabled`) | On / Off (applies at next app start) | `memory_graph` / `db_encrypted_target` (+ `db_encrypted`, `db_migration_stage`, `db_migration_failed`) | No | Excluded | v1 Amendment A19; D-D4 not yet passing |
| Settings → Memory | Export memories / Delete all (two-step confirm) / Review queue (actions) | — | — | `cache/exports/*.json`; `files/wipe_started_at` fence | No | Excluded | P8/B1 streaming export; Delete all scope (v1 Amendment A17) |
| Settings → Diagnostics | Push settings to glasses; Summarize now; Run capture pipeline now; Reprocess old photos (≤ 90); Export diagnostics (actions) | — | — | none (logs export to `cache/exports`) | Push re-sends HudSettings | n/a | v1 §2.4 "manual config sync" (P5/T21 ruling) |
| Activity tab | Sort | Latest first | Latest first / Oldest first / A–Z / Z–A | `hub_settings` / `activity_sort` | No | Restorable | SB §2; `BackupRulesTest.activitySortIsRestorable` |
| Activity tab | Period Today / Week / Month | Today | — | Route arg `activity?period=` (not persisted) | No | n/a | v1 §2.1 |
| Onboarding | Completed flag | false | — | `onboarding` / `v1_complete` | No | Excluded | BUG-R3-1 ruling + `OnboardingGuard` (DL §1 devfix/r3a) |

## 2. Phone: persisted state that isn't a setting

| Location | What | Storage (file / key or table) | Synced? | Backup | Ruling / source |
|---|---|---|---|---|---|
| Legacy config (read-only now) | `pipeline_enabled`, `paused_at_ms`, `local_mode_enabled`, `encryption_enabled`, `memory_window`, `persona`, `provider_type`, `endpoint`, `deployment_or_model`, `native_model_path`, `glasses_silency_mode`; plus `local_models_allow_metered` | `app_config` | No | Excluded | BUG-R3-1 set-up state; `AppConfigStore.getWithoutSecret` never opens the Keystore (P5/T21) |
| Legacy secret file | the legacy single-provider key entry (read once to seed KeyVault, then retired) | `app_config_secret` (EncryptedSharedPreferences) | No | Excluded (`shared_prefs`). **Not** excluded: `files/app_config_secret.xml.bak.*` (SB B12, HIGH) | P5/T21, P8/D1 |
| Endpoint keys | one entry per endpoint `keyRef` | `endpoint_keys` (EncryptedSharedPreferences) | No | Excluded | v1 §4.1; P8/D2 |
| DB passphrase | `passphrase` | `db_key` (EncryptedSharedPreferences) | No | Excluded | P2/T3, P8/D4 |
| Seed markers | `v1_done`, `key_retired` | `ai_seed` | No | Excluded | P2/T7, P8/D1 |
| Legacy import marker | done flag | `legacy_import` | No | Excluded | P2/T7 |
| Hub pause migration | `pause_migrated_v7`, `pause_migrated_v7_held` | `hub_settings` | No | Restorable (held id released at first start) | BackupRulesTest restorable note; DL §1 P7/A |
| Index maintenance | `last_canary_at`, `rebuild_pending`, `consumed_rebuild`, `rebuild_pending_token`, `discard_fingerprint`, `discard_at`, `discard_streak` | `index_maintenance` | No | Excluded | P5/T11 canary drift policy |
| Pipeline counters | `summ_triggered`, `summ_succeeded`, `summ_failed`, `frame_captured` | `pipeline_stats` | No | Excluded | BUG-R3-1 |
| Nudge run gate | `nudge_last_run_ms` (30-min minimum gap) | `nudge_engine` | No | Restorable | P7/C run gate |
| Glasses pairing token | `token` | `rokid_link` | No | Excluded | P3/T3 |
| Vendor SDK state | Rokid SDK's own prefs | `rokid_bridge` | No | Excluded | BUG-R3-1 |
| Room database | frames, memories, FTS, vectors, spaces, tombstones, asks, nudges, proposals, action_log, pauses, shopping, notes, summary_trace, endpoints, bindings, prompts | `databases/context.db` (+ wal/shm) | Today list, pauses (derived) | Excluded | P2/T6 (no Keystore after restore) |
| Files | `rokid_inbox/`, `rokid_archive/` (JPEGs), `queue_runtime.log`, legacy logs, `models/`, `cache/exports`, `wipe_started_at`, `cxr_received_frames.txt` | `filesDir`, `cacheDir` | No | Excluded (all but `wipe_started_at` and the received-frames ledger, which have no rule) | Final-review B1 (`03376c5`); ODE §4 |

## 3. Glasses: persisted state (no settings screen on the glasses)

| Location | What | Storage | Synced? | Backup | Ruling / source |
|---|---|---|---|---|---|
| HUD settings cache | the last `pc_settings` (every HudSettings field above), so verbosity, gestures, cadence and quiet hours work offline | `hud` / `hudSettings` (JSON) | Receives HudSettings | Backed up (no rules) | v1 §5.5 offline quiet hours; DL §4 devfix/r3a glasses prefs out of scope |
| Capture schedule | `next_trigger_at_ms`, `armed_at_ms` (drives the ring and Glance countdown) | `capture_scheduler` | No | Backed up (no rules) | P3/T11; GM spec §3 ring |
| Capture guard | last pHash and last-kept time (dedupe/throttle) | `capture_guard` | No | Backed up (no rules) | MVP §5.1 / v1 §1.3 guardrails |
| Pause set | glasses-local PauseRecords (fail-closed on corruption) | DataStore `files/pauses.preferences_pb` / `pauses` | Merged with the phone via hello / HudSettings | Backed up (no rules) | v1 §5.5 "glasses: DataStore" |
| Outbound buffer | up to 200 frames / 8 MiB, 72 h uptime age | `files/outbound/` | Flushed to the phone | **Backed up** (camera JPEGs; DL §4 P8 device M3 open) | v1 §1.7; P8/A1 |
| Overlay permission | SYSTEM_ALERT_WINDOW (declared in the manifest; granted by the user or `appops`) | system | No | n/a | GM spec §3; SL §12 |

## 4. Developer, debug and build-time flags

| Flag | Where | Default / when on | Effect | Ruling / source |
|---|---|---|---|---|
| `PcLinkProbe` logging | `CxrGlassesLink(probe = BuildConfig.DEBUG)` in `PH/phone/hub/HubGraph.kt`; `GlassAppWatcher.PROBE_TAG` | On in **debug** builds, off in release | Logs rx/tx counts and result codes on the session-less link, never payloads | ML report; SB B8 / SL §16 Decide 1 (keep or remove), confidence L |
| Capture `skip_dedupe` extra | `GL/capture/CaptureService.kt` `EXTRA_SKIP_DEDUPE` | Honoured only when `BuildConfig.DEBUG` | An adb capture bypasses the pHash/throttle guard | debug-only by construction |
| adb capture intents | `ACTION_CAPTURE_NOW` (MainActivity), `ACTION_NOW`, `ACTION_SCHEDULED`, `ACTION_KEEP_ALIVE` (CaptureService) | always present | Drive capture from adb or alarms; KEEP_ALIVE starts the foreground service | bg-capture (v1 Amendment A1); device checklist |
| Hub notification actions | `ContextHubService.ACTION_PAUSE` / `ACTION_RESUME` | always | Pause/resume from the "Connected to glasses" notification | v1 §5.5 triggers |
| BuildConfig public fields (names only) | `phone/build.gradle.kts`: `AZURE_KG_ENDPOINT`, `AZURE_KG_DEPLOYMENT`, `OLLAMA_BASE_URL`, `OLLAMA_MODEL`, `LOCAL_RUNTIME_MODE`, `LOCAL_NATIVE_MODEL_PATH` | from the git-ignored env file, else built-in defaults | Seed values for the first-launch endpoint; secret-looking names or credential-carrying values fail the build | DL §1 P3/T2b, P8/D3 |
| CXR SDK log level | `CxrGlassesLink` calls `LogUtil.setLogLevel(ERROR)` at link start | every build | Reduces SDK logging; the SDK still logs its auth token (vendor issue) | DL §1 devfix/r2b SEC-1; SB B3 |
| Test JVM heap | Gradle test tasks `maxHeapSize = 2g`, heap dump + exit on OOM | build only | A stall fails fast instead of hanging | SL §7 (`d137b17`) |
| Gate watchdog | `tools/gate.sh` | 480 s stall → jstack, exit 124 | Delivery tooling | P8/E2 |
| Release minify | `phone/build.gradle.kts` `isMinifyEnabled = false` | off | Debug APK ~97 MB | SL §16 Develop 8 |

## 5. BackupRulesTest ruling list (as pinned)

- **Excluded, keys and markers:** `endpoint_keys`, `db_key`, `app_config_secret`, `ai_seed`, `legacy_import`.
- **Excluded, DB:** `context.db`, `context.db-wal`, `context.db-shm`.
- **Excluded, memory copies:** `rokid_inbox`, `rokid_archive`, legacy logs, `queue_runtime.log`, `cache/exports`, `models`.
- **Excluded, set-up state:** `onboarding`, `app_config`, `memory_graph`, `index_maintenance`, `pipeline_stats`, `rokid_link`, `rokid_bridge`, `voice_probe`.
- **Restorable:** `hub_settings` (incl. `activity_sort`), `action_settings`, `context_sources`, `memory_settings`, `retrieval_settings`, `prompt_settings`, `persona_settings`, `voice_settings`, `nudge_engine`.
- The test fails when a phone or services prefs file has no ruling. It doesn't cover the glasses APK, or `files/` names outside the lists (SB B12).

**Count:** 61 rows in total.
- 52 user-visible phone settings and actions (§1).
- 3 glasses persisted values that are user-facing or synced: the HUD cache, the pause set and the overlay permission.
- 6 developer/debug flags that change runtime behaviour: PcLinkProbe, `skip_dedupe`, the adb intents, the hub notification actions, BuildConfig seeds and the SDK log level.

The internal-state rows (§2, other §3 rows and the build-only §4 rows) are listed for completeness but not counted as settings.

## Additions (2026-10-15, feat/small-batch)
| Screen | Setting | Default | Options | Storage | Synced to glasses | Backup | Decision |
|---|---|---|---|---|---|---|---|
| Settings → Glasses → Nearby devices | Keep running when nearby | ON | ON / OFF | `hub_settings` / `keep_running_nearby` | no | restorable | User decision 2026-10-15 (backlog §10). OFF stops ContextHubService when the app is backgrounded |
| Home banner | "Some features are limited" dismissed | not dismissed | — | `hub_settings` / `limited_mode_dismissed` | no | restorable | G3. Reappears only when a new permission goes missing |
| Activity | Sort order | Latest first | Latest / Oldest / A–Z / Z–A | `hub_settings` / `activity_sort` | no | restorable | User request 2026-10-14 |
