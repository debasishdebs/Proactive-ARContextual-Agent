# Follow-ups and decisions log (controller, 2026-10-23 night)

Everything here was found by reviews or by implementers and is NOT yet fixed unless marked DONE. Order: decisions first, then follow-ups by package.

## Decisions taken by the controller (user may veto)
- **D0 Helper pin length: 16 hex digits minimum (DONE in WP11 0285e2cd).** An 8-hex (32-bit) fingerprint prefix can be ground against CERT_NONE + prefix compare, so the pairing code could reach a mismatched peer. The phone helper card must DISPLAY 16 digits (WP6/WP9; message sent to the WP6 implementer). Older canvas LAN pairing keeps 8. m1-run.sh keeps 8 for the canvas API.
- **D1 Float results (DECIDED 2026-10-24 by the user: hash the exact text the helper sent, NO shared float formatter, no tolerance rule; the per-member type check against the output schema, nested objects included, stays). Implement after WP8 merges, before WP13.** Original recommendation follows. Today result hashing is integer-only in both languages, so a Python function that returns 0.73 fails the whole job with `bad_output` (WP11 review finding 12). Recommendation: the helper sends `resultCanonical` (a string: Python `json.dumps(sort_keys=True, separators=(",",":"), ensure_ascii=False)`) plus `resultHash = sha256(that exact string)`; the phone verifies the hash over the received string and then parses it. Phone-built payloads stay integer-only. Touches WP1 fixtures (`py_exec-result-samples.json`, hash vectors), WP5 `commitResult`, WP11 `_post_result`, WP13 harness. About 1.5 h, one coordinated change after WP5's fix merges.
- **D2 Opus stand-in reviews** (user permission 2026-10-23 23:09 for one hour, reviews only; expires 00:09). Reviews so far under the stand-in are labelled `-OPUS-standin` in `code-agent/codex/reviews/`. Codex re-reviews WP11 and WP4 at 00:16 and should be asked to re-review WP5, WP7 and LGC3 as well.

## Open follow-ups by package
- **ANR fix (installed on glasses 2026-10-23 21:59):** Codex round 4 Minor: start-record bytes stay counted after a successful publication until the next recount (conservative). Not yet proven on a real kill during recording: watch the next wear.
- **GlassesRuntime constructor (pre-existing, LGC3 Opus review minor 1):** still does main-thread `filesDir` I/O for `PauseStore`, `OutboundBuffer` and two SharedPreferences loads. Same class of startup risk as the ANR loop. Derive a `filesRoot` from `dataDir` once or build the runtime off the main thread.
- **LGC3 Minors:** `FeatureLogger.kt` is listed in the privacy scan but lives in a directory the scan never walks; the balanced-paren scanner ignores parentheses inside string literals. Neither leaks today.
- **LGC4 hazard:** when the diagnostic flag can be off, I-level `heartbeat v=2` lines stop reaching `capture_diag.log`; `tools/battery-run.sh` (143, 239) and `tools/m1-run.sh` (151, 166, 273) must reject or label runs without fresh heartbeat evidence.
- **WP2 Minors (Codex 3b-wp2-review-2):** test hooks `afterCodeConsumed` ship in production auth classes (replace by pausing through the injected SecureRandom); race tests use `join(300)` (not fully deterministic); parts of the HTTP-surface matrix are tautological until WP6 provides production handlers (WP6 must re-run it against the real `HelperRoutes`).
- **WP3:** `cleanupId` is an internal Long: WP6/WP11 must pin its wire form. SQLCipher migration androidTest was written but never run (emulator only).
- **WP4:** (fixes in flight) endSession must revoke even if grantsOff throws; any Forget voids ALL open jobs (fail closed, user-visible: an unrelated Forget cancels an in-flight experiment); range Forget does not scrub DONE results (jobs carry frame ids without timestamps).
- **WP5:** (Critical race fix in flight) after the fix, merge forward; `purgeOldCleanup` (30 d) is called by nothing; WP6 must call `sweep()` after deletion hooks and at session end.
- **WP7:** (fix in flight) debug-shadow route lacked the S6 guard; `PipelineValidator` now lets a `py.fn` graph pass and be frozen: WP8 must tighten it; the plan's P15/P17 route names (`/api/live`) are wrong, the real ones are pinned in tests.
- **WP11/WP13:** `Failed("killed")` (helper silent 10 s) and `resources_busy`/`timeout` are possible outcomes the WP8 executor must map; the pin guards exist in both pair.py and client.py (defence in depth).
- **Logging LG-MIN remaining:** LGC4 (building), LGC4P (phone publishes diagRecord), LGC5 (trace gate/space), LGC6-min, LGC7-min, LGC8-min, LGC9, LGC10-min; accepted gaps G1-G5 in the plan.
- **Sensor delivery delays (finding, not a bug fix yet):** 10-14 % of the time the app sees sensor data up to 9 s late (sensor timestamps stay continuous). Candidate: sensor FIFO report latency while worn. See `code-agent/superpowers/sdd/2026-10-22-canvas-slice3a/task-23b-diagnosis-2026-10-23.md` addendum.
- **Phone database is unencrypted** (an optional encryption conversion failed on 2026-10-14 and left the old DB, by design); `summ_failed`/`summ_triggered` counters and the QnA failures from today (blue can in the fridge) are not diagnosed: needs the QnA eval record (LG-LATER) and the question times from the user.

## Added 23:35 (WP4 fix report)
- **WP4 done:** 9bee1005 merged (hooks independent, seams internal, scrub tests). Integrated memory/orchestration re-verification pending until the WP5 and WP7 fixes merge.
- **Pre-existing repair-path gap:** `PipelineRecords.deleteFrames` and `RokidFrameInbox.dropRows` delete frame rows WITHOUT moving frame_forget_rev (no fence). Not a regression; fix or document in RV1.
- **Plan wording (section 6):** add "any Forget, including retention, voids every open job (voidReason = frame_forget)"; WP5/WP8 should map `frame_forget` to a distinct user-visible run status.
- **Cleanup '*' rows:** a repeat Delete all adds another '*' row (no dedupe): harmless; left alone deliberately.

## Added 2026-10-24 (user decisions)
- **Podman live step:** create a NEW machine `pca` (disk cap 10 GB via machine.sh/PCA_MACHINE_DISK_GB if the script lacks the knob: add it); keep `podman-machine-default` untouched (stopped, ~2.9 GB real disk, sparse 20 GB image). Ask the user before the live window (RAM; browser use). Both machines stay stopped when idle.
- **D1 floats:** decided, hash the exact text; no shared formatter.
- **Codex budget (user, 2026-10-24 ~01:00, 50% credits for 4 h):** one scoped review per package on gpt-6.1-sol medium (gpt-6.0-sol is rejected on this account). Pending reviews: WP10 non-live, LGC9, then WP8, WP12, LGC8-min.
- **M1 gate decision OPEN:** zero failing windows cannot pass on real traces (live loop timing blips); recommend <=2% failing windows, each classified, plus shadow-vs-admissions parity.
- **Replay follow-up:** Inexact.STALL is now practically unreachable after the overlap fix (kept enum + excuse path).
