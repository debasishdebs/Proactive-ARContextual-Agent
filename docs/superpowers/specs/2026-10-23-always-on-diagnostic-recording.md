# Always-on diagnostic recording (user ruling 2026-10-23)

**Ruling:** while Developer/diagnostic mode is on, recording must never be blocked by pairing, the laptop, the canvas LAN switch or the phone link. Pairing is needed only to transfer traces. Recording continues until space runs out; it is never stopped for any other reason.

**Why:** the 2026-10-22 night run (00:46-02:41) and the 2026-10-23 morning run (11:00-12:05) were worn but left no trace, because `collect start` is a manual laptop step (and the canvas LAN switch resets on phone restart). Only heartbeats survive, so replay cannot use them.

## Design
0. **Gate pattern (user, 2026-10-23): one predicate per decision, never scattered ifs.** "Recording" here means logging in the broad sense (diag log and sensor/event trace). The glasses trace gate already is a single predicate (`TraceRecorder.collectingNow`, today `collect && now < collectUntil`, set only by the phone's `pc_trace_ctl`): change it to `diagEnabled() || collectWindow`. The text diag log is already funnelled through `DiagLog` (always on); any future condition goes inside `DiagLog`, not at the 48 call sites (the 414 raw `Log.*` calls are ALL migrated onto this one logger, user ruling 2026-10-23: one logging method only, so future work cannot diverge; add a lint/test that fails on a new raw `android.util.Log` call outside the logger). Transfer/pairing gets the same treatment (one `canTransfer()` predicate in the phone API layer, to be located first). Changing when or what to log or pair later is then a one-function edit.
1. **Glasses (first, this is the M1 evidence):** a persisted local flag (`diag_record`, default off) makes `TraceRecorder` start a session whenever capture runs, and seal a new trace per capture session. The phone sets the flag when Developer mode is switched on or off (a `pc_trace_ctl` field or a HudSettings field, additive; PROTOCOL_VERSION stays 1). Once set, the glasses keep recording after a restart or link loss without the phone. `pc_trace_ctl` collect (the laptop session) keeps working and takes precedence for its own window.
2. **Space:** never stop recording. When free space is low, reclaim in this order: sealed traces already transferred (imported on the phone), then the oldest sealed trace. Per-trace cap stays 256 MiB (TraceBounds); a full trace seals and the next one starts.
3. **Deletion:** Delete all, Forget and range deletion apply to these traces exactly as to collected ones (Task 34 fences, deletion epoch). Always-on recording must not bypass them.
4. **Phone:** implement the `HubTraceRecorder` seam (LaterSeams.kt) so phone-side events go into the same trace format; same flag, same space rule.
5. **Watch:** LiveFit repo, separate small task (record motion/events locally, hand over by USB/adb); later.
6. **Hand-over:** one command (extend `tools/trace-pull.sh` / `m1-run.sh pull`) that pulls glasses, phone and watch traces on USB and imports them. Needs the pairing code for the phone import only.
7. **Surface it:** the phone shows "recording diagnostics" with size and the oldest trace, so it is never silent.

## Acceptance
- Developer mode on, glasses worn with no laptop and no pairing: a COMPLETE or PARTIAL trace exists afterwards, including across a Rokid force-stop and restart (killed trace sealed PARTIAL, a new one begins).
- Developer mode off: nothing is recorded; existing traces stay until the user deletes them.
- Delete all removes these traces on both devices.
- Low-space test: oldest transferred trace is reclaimed first; recording never stops.
- No change to capture cadence or battery beyond the recorder's current cost (measure with `NOCPU=1 tools/battery-run.sh`).
