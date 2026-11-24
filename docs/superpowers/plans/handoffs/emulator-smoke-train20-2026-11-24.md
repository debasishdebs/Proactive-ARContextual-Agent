# Emulator rehearsal: train20 6a78899e over the restored phone data (2026-11-24)

Emulator only (emulator-5554, AVD MyEmulator, Android 14, started with -wipe-data). No real device touched, no Gradle. Counts and findings only (no personal text, coordinates or pin names). Same procedure as `emulator-smoke-train13-2026-11-24.md`: old build `apks-v110-cand-18c1b76a` (sha256 e50ef2ba81e2... = SHA256SUMS) installed, launched, force-stopped; context.db (plaintext, user_version 17) and prefs memory_settings, place_pins, pause_zone_state, hub_settings, app_config, persona_settings, place_guide restored via run-as from `phone-dump-2026-11-24`; NOT copied: db_key, endpoint_keys, rokid_link, places.db, photos. Then `install -r` of `apks-v110-train20-6a78899e` (phone-debug.apk sha256 8a74c75219ad... = SHA256SUMS; versionCode 4 both, same cert 61a8318261cb).

| # | Check | Result |
|---|-------|--------|
| 1 | Old build starts on restored context.db | PASS: pid alive, user_version 17, memory 16929 / frame 4661 / moment 1328 / forget_tombstone 10352 / pause_interval 37 (= dump), 0 fatal |
| 2 | install-trip-build.sh --self-test dry run | PASS: fixtures 247 passed 0 failed; "DRY-RUN complete: nothing was changed or written" (versionCode 4 -> 4) |
| 3 | install -r train20 over old build | PASS: Success, installed sha256 = staged sha256 |
| 4 | Counts unchanged after upgrade | PASS: memory 16929 / frame 4661 / moment 1328 / forget_tombstone 10352 / pause_interval 37 identical after upgrade, ~7 min runtime, forced jobs, and at the end of the session |
| 5 | Room user_version 17, no FATAL / ANR / SQLite-Room errors, >= 7 min | PASS: upgrade at 23:01:53, checked 23:09 (7 min 9 s) and again at the end (~13 min): FATAL 0, ANR 0, 0 SQLite/Room/migration lines from the app pid, pid stable. user_version 17 |
| 6 | Pause clean-up ON | PASS by default only: `DeveloperFlags.pauseRetention` defaults to true and the restored prefs hold no `pc_dev` override. Not toggled or viewed in the UI |
| 7 | RetentionWorker "retention paused" line | NOT OBSERVED (same as train13): WorkManager row is ENQUEUED, run_attempt 0; `cmd jobscheduler run -f` on its system id (32) reported "Running job [FORCED]" but no RetentionWorker line reached pca.log or logcat. Counts identical (nothing deleted). Needs a real-device look at pca.log after the first daily run |
| 8 | Retention prefs preserved | PASS (file level): memory_settings.xml restored byte-identical (24 h / 7 days / 500 MB as in the dump) and still present after upgrade. Not re-checked on screen |
| 9 | tools/post-install-check.sh --phone emulator-5554 --apks train20 | PASS=28 WARN=1 FAIL=1 UNKNOWN=2, identical to the train13 smoke. Non-passes expected: FAIL runtime perms 1 granted / 9 denied (never granted on the emulator); WARN battery exemption; UNKNOWN detailed log (7 min, needs 70); UNKNOWN glasses not given. pins total=9, integrity_check ok, forbidden shapes pairs=0 http=0 @=0, PcData "net cost=FREE", no blocked data-saver lines |

## ASK CHECK (data saver touched ModelRouter, EmbeddingClient, FencedCall)

Setup: a local mock OpenAI-compatible server on the Mac (python http.server, port 8099, `/v1/chat/completions` and `/v1/embeddings`, request log of path, byte size and input count only). After the checks above, with the app stopped, the emulator's context.db was edited (counts-neutral): new endpoint row `OPENAI_COMPAT` base `http://10.0.2.2:8099/v1` (cleartext is permitted for 10.0.2.2 by network_security_config), ANSWER bound to it (chat) and EMBED bound to it (embeddings, 256-d). Nothing in the app was changed; the app then ran as installed (train20).

| Check | Result |
|-------|--------|
| Embedding batch on free Wi-Fi (PcData net cost=FREE) | PASS: mock received 1 + 3 + a stream of 16-text batches (about 60 requests in the first 30 s, ~19.6k vectors stored over the session), no data-saver block line. The EmbeddingWorker re-indexed into the new space (memory_vector grew; memory/frame/moment unchanged) |
| Ask on free Wi-Fi | PASS (after fixing my mock): the question reached `/v1/chat/completions` (json_schema request) and the app showed the mock answer on Home plus a "For you" card. My first mock reply lacked the required `sources` field and the app correctly rejected it (RoleRunner "reply rejected stage=SCHEMA", AnswerService INVALID_OUTPUT, "Couldn't answer that - try again" shown); with a schema-valid reply the answer showed |
| Ask on non-roaming mobile data (Wi-Fi switched off, emulated mobile, PcData net cost=HOME_MOBILE) | PASS: the question reached the mock and the answer showed. This is the only run where a call to a LAN-looking address is treated as costly (counted, reservation path) |
| Embedding batches on mobile | NOT CONCLUSIVE: 0 embedding requests reached the mock during the ~15 s on mobile (EmbeddingWorker had just failed with a transient SocketException on the network switch and was in retry backoff, so the data saver block cannot be told apart from backoff). Embedding resumed on Wi-Fi within ~25 s (15 requests in 20 s) |

Limits, stated plainly:
- The URL is a 10.x address, so on Wi-Fi the LAN exemption applies (reason R_LAN: no reservation, nothing counted). The Wi-Fi run proves the model-call path works for free LAN traffic, NOT the reservation path for a public host.
- The reservation path was exercised only by the mobile-data Ask run (LAN host, costly network). No remote (public) host was tested: plain http is allowed only for localhost, 127.0.0.1, 10.0.2.2, *.local, *.lan, *.home.arpa, and a `.lan` name needs DNS, which is not available without editing /etc/hosts or running a DNS server. An https public endpoint would need a trusted certificate. NOT TESTED: Ask / embedding to a public https host (the real Azure case), roaming network, reservation settle / counters in the UI.
- The mock bypasses the real Azure endpoint (no API key on the emulator).

## Observations
- Semantic search showed "Search used keyword matching" and pca.log "SemanticRetriever semantic search unavailable (ProviderConfigException)": the restored ACTIVE index space is the on-device model, whose files are not on the emulator. Emulator-only, not a data saver effect.
- Uncaught finalizer exception from onnxruntime telemetry (HttpClient.finalize, Receiver not registered) once at start: non-fatal, same library as before.
- Onboarding reappears on cold start (known). Home showed 3 existing "Answers" before the test.
- Emulator shut down and mock server killed afterwards. The emulator context.db was modified for the Ask test (mock endpoint and bindings, new embedding space); the emulator was wiped on the next -wipe-data start, the real dump was not touched.
