# Phase 0 spike results (2026-10-12)
Devices: phone Samsung SM-S931B, Android 17 (API 37); glasses Rokid RG-glasses, Android 12 (API 32), firmware SKQ1.240613.001; Hi Rokid `com.rokid.sprite.global.aiapp` G1.14.20.0921 (global variant — `preferGlobalHiRokid` quirk applied).
Probe branch: spike/cxr-probe @ 341c3e2.

## CXR API notes
- sendCustomCmd return type: `SessionResult(code, data, message)` (success = `code=OK`), not Int/Boolean. All other class/param names used by Live Fit (`CxrSessionManager`, `SessionConfig`, `ISessionLifecycleCbk`, `ICustomCmdSessionCallback`, `Caps`, `CXRServiceBridge.sendMessage/subscribe`) compiled unchanged with `client-l:1.1.2` + `cxr-service-bridge:1.5`.
- Glasses-side `CXRServiceBridge.sendMessage` returns 0 even when the phone app's session is closed and the message is dropped (see Background sending) — rc=0 means "handed to the CXR service", not "delivered".
- Session lifecycle seen: `onSessionPaused(BT_DISCONNECTED)` / `onSessionResumed()` on a BT drop (automatic, no reconnect call); `onSessionTerminating(GLASSES_APP_EXIT)` then `onSessionClosed(GLASSES_EXIT)` after the 5 s grace when the glasses activity exits.
- Link round trip (phone → glasses → phone ping, 5×): 127 (warm-up), 34, 41, 61, 60 ms. Glasses wall clock ran ~107–144 ms ahead of the phone.

## Glasses → phone Caps string size
| KB | received/5 | intact/5 | glasses rc |
|---|---|---|---|
| 4 | 5 | 5 | 0 |
| 8 | 5 | 5 | 0 |
| 16 | 5 | 5 | 0 |
| 24 | 5 | 5 | 0 |
| 32 | 5 | 5 | 0 |
| 48 | 5 | 5 | 0 |
| 64 | 5 | 5 | 0 |
| 96 | 5 | 5 | 0 |
| 128 | 5 | 5 | 0 |
| 192 | 5 | 5 | 0 |
| 256 | 5 | 5 | 0 |

No limit reached up to 256 KB per Caps string.
**SAFE_CHUNK_CHARS = 32 768** (plan cap; measured headroom ≥ 8×).

## Chunked frames (20 KB JPEG ≈ 27 KB Base64)
Sample: 360×270 JPEG, 20 396 B → 27 196 Base64 chars.

| chunk | chunk acks | runs | ms per frame (min/median/max) | chunks | attempts |
|---|---|---|---|---|---|
| 8K | no | 3 | 143 / 177 / 183 | 4 | 1 each |
| 16K | no | 3 | 161 / 169 / 262 | 2 | 1 each |
| 32K | no | 3 | 155 / 174 / 229 | 1 | 1 each |
| 16K | yes | 3 | 197 / 239 / 247 | 2 | 1 each |

Per-chunk acks needed for flow control: **no** (evidence: 0 missing / 0 CRC-failed frames across all no-ack runs and the 20-frame backlogs; chunk acks only add ~40–80 ms per frame).
Corrupted frame: NACK observed **yes** (`CRC MISMATCH → NACK`, glasses `NACK reason=crc 1/2`); resend delivered **yes** (312 ms, nacks=1).
Persistent corruption: frame dropped after 2 NACKs **yes**; later frames delivered **yes** (`flush done sent=2 dropped=1 retained=0`, 773 ms).
Lost frame ack: resend after timeout deduped **yes** (phone `duplicate (already ingested) → ack ok, not re-ingested`; glasses `delivered … timeouts=1`, 7 194 ms = 5 s ack timeout + 2 s backoff).
Backlog 20 @16K: total ms 3 835, retries 0, retained at end 0 (per frame 137 / 166 / 306 ms; ≈ 5.2 frames/s ≈ 106 KB/s of JPEG).

## Interrupted flush
Run A — phone Bluetooth off 5 s (`svc bluetooth disable`, ~0.8 s into a 20-frame backlog): session paused `BT_DISCONNECTED` 50 ms after the drop and resumed by itself 2.3 s after BT came back (7.2 s after the drop). In-flight frame: timeout 1/3, 2/3, delivered on attempt 3. Retained N: 0 (recovered within 3 attempts); total 23 889 ms.
Run B — Bluetooth off 20 s: frame timeouts 1/3, 2/3, 3/3 → `flush stopped after 3 timeouts; retained=16`. Session resumed by itself 1.7 s after BT came back.
Retained N: 16 ; reconnect ms: 1 700–2 300 after BT returns (automatic resume, no manual Connect) ; recovered by Flush: **yes** (16/16, 5 495 ms); ingested twice: **0** ; lost: **0** (phone ingested count rose by exactly 20 per backlog, glasses buffer empty).
After a reconnect, per-frame time roughly doubled (median ~330 ms vs ~165 ms) and one 3.7 s stall was seen — do not assume the pre-drop throughput right after a reconnect.

## PTT audio gaps (ms)
Audio: 3 200-byte chunks (Base64 in a Caps string) every 100 ms for 10 s.

| case | n | p50 | p95 | max |
|---|---|---|---|---|
| alone, run 1 | 96 | 99 | 138 | 1 189 (first-run outlier) |
| alone, run 2 | 96 | 101 | 149 | 236 |
| alone, run 3 | 96 | 105 | 164 | 216 |
| + 20-frame flush, run 1 | 97 | 103 | 248 | 322 |
| + 20-frame flush, run 2 | 97 | 101 | 251 | 281 |
| + 20-frame flush, run 3 | 97 | 100 | 210 | 347 |

Decision: suspend flush while listening — **yes** (required by spec §1.8) / numbers: p95 rises 1.3–1.8× (+60–110 ms) and max +100 ms during a flush; below the 2× "must" threshold, but the flush also slows (3.8 s → ~5.0 s) and both compete for the same link. No audio chunk was lost in any run.

## Background sending
Heartbeat every 2 s from a glasses foreground service (`HeartbeatService`).

| state | heartbeats in 30 s | maxGap | rc warnings |
|---|---|---|---|
| (a) probe activity in front | ~15 (18 in first ~36 s) | 2 039 ms | none |
| (b) backed out to Rokid launcher | 3 (only during the 5 s terminating grace), then 0 | ∞ (stopped) | none — rc stayed 0 |
| (c) display asleep, activity was already exited | 0 | ∞ | none |
| (c′) display asleep (`KEYCODE_SLEEP`) with probe activity not exited | ~15 (22 in 44 s; re-run 17 in 34 s) | 2 105 ms (re-run) | none |

In (b) the phone's CXR service still logs `recv Notify: cmd probe_hb`, but the app's `CxrSession` has closed (`GLASSES_EXIT`) and delivers nothing — silent loss. A phone-side `connect()` while the glasses sit on the launcher reopens the glasses activity and delivery resumes.
Physically removing the glasses (wear sensor) was not tested; display sleep is the closest proxy.
Re-run with lifecycle logging (review follow-up): on display sleep the activity goes `onPause` → `onStop` (not resumed, not destroyed) and heartbeats keep flowing — the working condition is **session open / activity not exited**, not RESUMED.
**Decision for CaptureService:** capture may run in the service, but **buffer until the session is open** — send only while the CUSTOM_APP session is open (activity not exited; display may sleep). Otherwise keep frames in `OutboundBuffer`. The phone hub does not auto-reconnect on `GLASSES_EXIT` (it would reopen the activity and steal the foreground); like Live Fit it starts `GlassAppWatcher` and reconnects when the user reopens the app or on an explicit phone action. Frame-level acks make loss in the 5 s grace window safe. rc=0 is also returned during a BT pause — only the frame ack proves delivery.

## TTS
Phone TTS heard on: **glasses** (user confirmed; media routed over the existing BT A2DP link, AAC). Glasses BT output listed: **yes** (Glasses_9120 as BT SCO type 7 and BT A2DP type 8; active media device = glasses A2DP). Glasses TTS engine: init=-1, engines=null, heard: **no**.
**Decision:** phone TTS via BT route. No glasses-side fallback exists (no engine on YodaOS); if the BT audio route is absent, the answer is shown on the HUD only.

## STT
api=37, recognitionAvailable=true, onDeviceAvailable=true.
Injected transcript: "What do I need from the store" (partials; user silent in a quiet room); written progression: 32 000 → 60 800 → 92 800 → 124 800 → 134 142/134 142 (full feed at real-time pace, no plateau) ; **verdict:** injection works.
`onResults` returned `RESULTS_RECOGNITION = null` although partials were complete — the transcript must fall back to the last non-empty partial.
**Detection method for spec §5.1.1:** pipe back-pressure with ≥ 96 KB feed (the 1 s buffer in the spec is below pipe capacity and cannot detect fallback) — **confirmed for the positive case** (a reading recognizer consumed all 134 KB at real-time pace). The negative case (recognizer ignoring the pipe → writes block near 64 KiB) was not observable on this phone because injection works; the method stands on pipe-capacity reasoning for that branch.

## Spec/plan follow-ups
- §1.8: replace "assume 16 KB until measured" with SAFE_CHUNK_CHARS = 32 768 (no limit seen up to 256 KB); frame-level acks only, no per-chunk acks; note post-reconnect throughput ≈ half.
- §1.7 / §3.1 `CaptureService`: frames may only be **sent** while the CUSTOM_APP session is open (activity not exited); otherwise buffer. `CXRServiceBridge.sendMessage` rc=0 does not mean delivered. No auto-reconnect on `GLASSES_EXIT`; `GlassAppWatcher` reconnect as in Live Fit; BT drops pause/resume automatically.
- §5.1.1 production guard: fires only after > 2 s of fed audio with < 64 KiB consumed; non-blocking writes. §7 risk line and R-35: TTS fallback reworded to HUD-only.
- §3.6: TTS route verified (phone TTS plays in the glasses over BT A2DP); remove the glasses-side `TextToSpeech` fallback (no engine on YodaOS); without BT audio the answer is HUD-only.
- §5.1.1: replace the "1 s buffer" probe with a paced ≥ 96 KB (≥ 3 s) feed and back-pressure check; treat a null/empty final result as the last non-empty partial.
- Phase 3/6 plans: use the numbers above (5 s ack timeout and 3 attempts are adequate; reconnect resumes in ~2 s; PTT suspends flush).
