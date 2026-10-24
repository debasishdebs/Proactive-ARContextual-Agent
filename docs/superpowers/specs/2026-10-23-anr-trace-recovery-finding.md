# Finding: ANR loop from trace recovery on the main thread (glasses, 2026-10-23 16:15-16:22)

**Symptom (user):** after about 16:13 the glasses app hung: black screen with the icon on top, D-pad and double-tap dead, then Rokid's home with no icon; recovered by itself after 5-10 min.

**Evidence (glasses dropbox, our package only):** 7 `data_app_anr` entries at 16:16:48, 16:19:08, 16:19:37, 16:20:19, 16:20:46, 16:20:57, 16:22:10; each followed by a process death and a restart (diag log: 8 restarts, 16:15:18-16:22:45, gaps 1-115 s).
- ANR #1 (Subject: broadcast SCREEN_ON): main thread in `TraceStorage.recover` -> `seal` -> `ChunkCodec.decode` -> `EventCodec.parse` using a Kotlin `Regex.matches` per field (`TraceRecords.kt:406-420`).
- ANR #7 (Subject: broadcast CAPTURE_SCHEDULED): main thread blocked in `GlassesRuntime.traceRecorder` (`by lazy`, `GlassesRuntime.kt:131`) called from `CaptureService.startSmart` (`CaptureService.kt:259`), waiting for the lazy lock held by another thread that is still recovering.

**Root cause (proven by the stacks):** `TraceRecorder` construction runs `storage.recover()` which seals a killed trace by fully decoding every chunk (here 397 chunks, 464,266 events, 26 MB raw) with a regex-based parser, inside the `lazy` initialiser reached from the main thread. It takes far longer than the 5-10 s ANR limit; the system kills the process; the restart starts the same recovery again until one pass finally completes. Longer traces (always-on recording) make it worse.

**What is still unknown:** why the app died at about 16:13:08 in the first place (before the recovery loop; no ANR or crash entry covers it, the glasses event buffer no longer reaches back).

**Other kill evidence (events log, 17:10-18:00):** 17:10:06 `am_proc_died` with no preceding kill (uncommanded death); 17:14:04 and 18:00:07 `am_kill ... due to from pid 2001` = `com.rokid.os.sprite.assistserver` (Rokid's own service: the call scene, and removal/dock).

**Fix direction (tests first):** (1) never run recovery in the lazy initialiser or on the main thread: construct the recorder cheaply and run `recover()` on the trace IO thread; (2) seal a killed trace without decoding every event (check chunk checksums and the manifest; at most a streaming count); (3) remove per-field `Regex` from `EventCodec` hot paths; (4) `CaptureService` must never wait on trace initialisation; (5) add a test with a large killed trace (400+ chunks) asserting construction returns immediately and recovery completes off the main thread. This is a v1 blocker and must land before always-on recording (docs/superpowers/specs/2026-10-23-always-on-diagnostic-recording.md), which produces longer traces.
