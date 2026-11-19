# Glasses link coexistence: PCA and Rokid-Coder (design, 2026-11-20)

Status: design only. No code, Gradle or adb was used; the Rokid-Coder repository was not read. All Rokid-Coder behaviour below comes from the facts the user gave; anything not checked in PCA code or a PCA measurement is marked UNVERIFIED.

**The question.** The user runs PCA (direct CXR-L client through Hi Rokid's CXRLinkService) and Rokid-Coder (a Nexus-hub fork: phone `io.github.debasishdebs.rokidcoder`, glasses `...rokidcoder.glasses`, an accessibility watchdog restarts the glasses app, and a "Monitor sessions" healer reconnects its link) on the same phone and glasses. Do they fight over the glasses link, including over Wi-Fi channels? If they do, what should PCA build?

**Evidence so far: no clash has been shown.** In a 2-hour outing the user took 100-200 photos and still got Rokid-Coder notifications. PCA's own log for 19 Nov shows 387 frames, and every reconnect recovered. Older handoff text ("PCA and Rokid-Coder do NOT run together", `handoffs/needs-user-2026-11-19.md` section F; `handoffs/device-session-runbook-2026-11-19.md` L18/L176) comes from Nexus BUSSPEC's one-session-per-phone rule. Nobody measured it, and the field evidence goes against it as a hard limit. One-CXR-session-per-phone as a Rokid platform limit: UNVERIFIED.

---

## 1. Current state: how PCA owns and recovers the link

Paths are relative to `android-companion/`.

### 1.1 Transport (what actually carries frames)

| What | Where | Notes |
|---|---|---|
| Phone session: CXR-L `CxrSessionManager.create(SessionConfig(CUSTOM_APP, glassesPackageName=com.rokid.context.glasses, aiInterceptMode=ALLOW_WITH_PAUSE, terminatingGracePeriodMs=5000))` then `s.connect(token)` | `services/glasses-link/.../link/CxrGlassesLink.kt:241-313` (create at :271-283, connect at :303) | Each `CxrSession` binds its own CXRLink to Hi Rokid. A CUSTOM_APP connect calls appStart, which brings PCA's HUD to the front (`GlassesSessionPolicy.kt:91`). |
| Glasses to phone: frames as Caps text over CXR-S `sendMessage` (`pc_frame` = meta + Base64 chunk) | `glasses/.../link/GlassesBridge.kt:26-27`; `glasses/.../link/FrameUplink.kt:34-38` (stop-and-wait, ack per frame), :191 (`frame timeout n/3`), :136 (`flush stopped after 3 timeouts; retained=N`) | Chunk size `SAFE_CHUNK_CHARS = 32 768` (`core/model/.../FrameCodec.kt:7`). |
| Phone receives | `CxrGlassesLink.kt:322-326` (`onGlassesMessage`), `FrameReassembler.kt`, then `RokidFrameInbox.kt:319-320` (`append result=true origin=glasses`, the line the report counts) | |
| Measured throughput (phase 0) | `docs/spikes/2026-10-12-phase0-results.md` L43-49 | About 5.2 frames/s, about 106 KB/s of JPEG. With phone BT off, the session paused `BT_DISCONNECTED` within 50 ms and resumed by itself about 2 s after BT came back. After a reconnect, throughput was about half. |

**What this means for the Wi-Fi channel question.** PCA's frames ride CXR custom messages, and turning phone BT off pauses that path. About 106 KB/s fits inside Bluetooth Classic EDR. So PCA's own data path may be BT-only (UNVERIFIED: nobody tested whether the SDK moves custom-message bytes over Wi-Fi P2P). "CXR uses BT for control and Wi-Fi P2P for bulk" is UNVERIFIED. It would most plausibly apply to Hi Rokid's own media sync (the user's photos), not to PCA's frames (UNVERIFIED).

### 1.2 Who decides what

| Layer | Where | Behaviour |
|---|---|---|
| Session lifecycle policy (pure) | `services/glasses-link/.../GlassesSessionPolicy.kt:67-174` | States: Idle, ClosedOnGlasses, Minimised, Connecting, Open, Paused, WaitingRetry, WaitingAuthRetry. Transient failure backoff is 2/5/10/30 s, then at most 5 more 30 s retries (`:132-140`, `Backoff.kt:4`, `MAX_CEILING_RETRIES` `:176`). An automatic open is not retried here: it hands back to the relauncher (`:131`, `:139`). A terminal connect error waits for the user (`:183-187`). Nothing may connect while Minimised (`:156`). |
| `GLASSES_EXIT` / `USER_CLOSED` handling | `CxrGlassesLink.kt:368-375`, then policy `:115` | The SDK reports a minimise and a real exit the same way. PCA goes to MINIMISED and the background link decides between them. |
| Background (session-less) CXRLink | `GlassAppWatcher.kt:40-77`; `onGlassAppResume(ours)` logged at `:54-56` | Bound only while PCA's glasses app is out of the foreground (`CxrGlassesLink.kt:226-233`). It never calls appStart. |
| Minimised liveness | `MinimisedLink.kt:173-178` (`CONFIRM_MS` 10 s, `PROBE_MS` 20 s, `MAX_MISSES` 2, `LAST_CHANCE_MS` 8 s) | If the glasses stay silent, the link goes to CLOSED_ON_GLASSES (policy `:117`). |
| Authorization | `CxrGlassesLink.kt:249-263` (AuthActivity, `AUTH_TIMEOUT_MS`), `:315-320` (token rejected, so the token is forgotten), `:384-394` (`preferGlobalHiRokid` reflection; process-local) | `authorization timed out` is counted by the report. |
| Connect timeout | `CxrGlassesLink.kt:265`, `CONNECT_TIMEOUT_MS = 15 s` (`:401`) | |
| Relauncher (`PcRelaunch`) | `phone/.../hub/GlassesRelauncher.kt:122-130` (doc), `:261-268` (`onClosed`), `:270-296` (loop), `:338` (`attemptNow`), `:385-402` (guards), `:406-423` (constants) | Reopens PCA's glasses app after "Rokid closed it" (AssistServer force-stops it on any scene: call, message, music, removal). Steps are 8 s, 30 s, 2 min, 5 min, 10 min. `MAX_PER_HOUR = 6`, a stable session of 60 s resets the step, and it is blocked by a call, media (full screen only) or not-worn state. It is the only path that opens the app automatically (`:300-304`). The wearer's own exit puts it into stand-down (`:241`). Wired in `Hub.kt:147` (`reopen = link.connectAutomatic()`). |
| Watchdog (`PcWatchdog`) | `phone/.../hub/GlassesWatchdog.kt:127-176` (tick), `:188-194` (`TICK_MS` 150 s, `SILENT_MS` 3 min, `MIN_HUB_START_MS` 60 s) | When the link has been silent for 3 min or more: OFFLINE or PAUSED_BT leads to `reconnectAutomatic` on its own ladder; MINIMISED leads to `probeAlive`; CONNECTED or CLOSED_ON_GLASSES leads to `relaunch()` through the relauncher. It never launches anything itself (`:24-25`). It restarts a needed hub on the persisted ladder (`:137`, `:143-151`, `:297-335`: 60 s minimum, then 5/15/30/60 min). Wired in `HubGraph.kt:292`. |
| Hub service | `phone/.../hub/ContextHubService.kt:47`, `:119` (`START_STICKY`) | Builds the link at `HubGraph.kt:147`. On start: watch-only if the wearer's exit stands (`Hub.kt:212`); otherwise a guarded automatic connect (`Hub.kt:214`). App resume: `Hub.kt:433`. |
| AutoStop (`PcAutoStop`) | `phone/.../hub/AutoStopController.kt:1-30` (doc), `:90`; `AutoStopMachine.kt` | Stops the hub after N minutes while the glasses are off-head or disconnected (`auto_stop_disconnected`). If another app held the link and PCA read that as disconnected, AutoStop would stop PCA's hub. |
| Nexus status heuristic | `phone/.../nexus/NexusStatus.kt:11-12`, `:55-56`, `:69`, `:100` | Shows "Glasses link busy" when the Nexus hub reports `NOT_AVAILABLE` and either PCA's link is up or Rokid-Coder is installed. The code itself calls this a heuristic until S2 has run. |

### 1.3 Logging that the measurement depends on

- `LogPolicy.kt:11-12` (`core/model/.../log/`): the file gets W/E and milestones always, and I/D lines only while diagnostics are on. Link transitions (`link=`), frame appends, watchdog ticks, relauncher lines and DiagProbe lines are all I level, so they reach `pca.log` only with the Detailed log on.
- Detailed log switch: `phone/.../canvas/DeveloperModeStore.kt:49` (default ON), UI at `ui/settings/Diagnostics.kt:409-412`.
- `tools/link-health-report.sh` (`:15-47`) counts, per clock hour: frames (`RokidFrameInbox append result=true origin=glasses`), `link=CONNECTING`, `link=CONNECTED`, `authorization timed out`, `failed SESSION_TERM`, `watchdog tick verdict=HubDown`, `PcRelaunch relaunch:`, and the largest gap between frames.
  - Caveat: the relaunch column counts every `relaunch:` line, including `relaunch: link A->B` (`GlassesRelauncher.kt:198`). It is a measure of activity, not a count of reopens.
  - It does not count MINIMISED, CLOSED_ON_GLASSES, PAUSED_BT, `session closed`, `connect failed`, or `watcher: glasses app resume ours=false`. Those are the fingerprints that separate the hypotheses below, so section 3 adds count-only greps for them.

---

## 2. Contention hypotheses and their log fingerprints

H0, the null hypothesis, is that the apps do not contend in any way that matters. Each hypothesis below lists a fingerprint that would confirm it and a result that would falsify it. "RC event" means a Rokid-Coder event written down by hand with clock time (notification shown, healer reconnect, its glasses app restarted by the watchdog).

| # | Hypothesis | Fingerprint in PCA logs (counts, timestamps) | Falsified if |
|---|---|---|---|
| H1 | **Session exclusivity.** Only one CXR-L session per phone (UNVERIFIED platform limit; BUSSPEC design rule). Rokid-Coder's connect or heal evicts PCA's session, or the other way round. | Within 30 s of an RC heal: `session closed <reason other than GLASSES_EXIT>` or `connect failed <code>`, then `link=CONNECTING`/`OFFLINE`, `sess-term` rising in that hour, PCA's backoff 2/5/10/30 s, and possibly `auto_stop_disconnected`. Alternating "who is up" over time. | PCA stays CONNECTED (no `link=` change) across 5 or more RC heals and RC cold starts, in both start orders. |
| H2 | **Foreground tug-of-war on the glasses.** A CUSTOM_APP connect (appStart) or RC's accessibility watchdog brings RC's glasses app to the front. PCA's app goes to the background, and PCA's relauncher later pulls PCA's app forward again, which RC's watchdog then answers. (A UI fight, not a radio fight.) | `watcher: glasses app resume ours=false`, `link=MINIMISED` then possibly `link=CLOSED_ON_GLASSES`, `relaunch: attempt in 8 s`, `relaunch: reopening the glasses app`, repeating. In the worst case `relaunch: waiting (hourly limit)`. Frames keep flowing while minimised (Option A), so the frame count may stay normal while the HUD flips. | No `ours=false` and no MINIMISED/CLOSED_ON_GLASSES near RC events, or a single transition that does not repeat. |
| H3 | **Hi Rokid service and authorization interference.** Both apps bind CXRLinkService. One app's authorization or token refresh disturbs the other's. | `authorization timed out`, `connect failed TOKEN_EXPIRED/NOT_AUTHENTICATED` (PCA then forgets its token: `CxrGlassesLink.kt:315-320`), `link=NOT_AUTHORIZED`. | Zero auth timeouts and zero token rejections across the session. |
| H4 | **Bluetooth bandwidth contention.** Both apps push data over the same BT link (RC notifications and job state; PCA's roughly 27 KB frames). | Link states stay stable, but frames/hour drop, max-gap grows, and glasses-side `frame timeout n/3` / `flush stopped after 3 timeouts; retained=N` appear in the same windows as RC traffic. | Frames/hour and max-gap with RC active are within the thresholds of section 3 compared with PCA alone. |
| H5 | **Wi-Fi channel contention (the user's "channels").** If any party uses Wi-Fi P2P (UNVERIFIED; most plausibly Hi Rokid's photo or media sync), the P2P group and the phone's Wi-Fi station may share or switch channels. 2.4 GHz Wi-Fi also shares a combo radio with BT (BT/Wi-Fi coexistence), which can starve BT time slots. | Frame throughput or gaps get worse when (a) the user takes or syncs photos, or (b) the phone joins or roams Wi-Fi (DiagProbe `network ... transport=` lines, Detailed log). There may be no link-state change at all. | Frames/hour and max-gap are unchanged across photo bursts and across phone Wi-Fi on/off arms. PCA's path being BT-only (1.1) makes a direct effect less likely; only the coexistence side effect would remain. |
| H6 | **Recovery-loop amplification.** PCA's watchdog (150 s tick, 3 min silent threshold) and relauncher ladder react to RC's healer, and RC's healer reacts to PCA's, in a cycle. | Periodic PCA transitions with a period close to RC's healer interval (UNVERIFIED cadence); `relaunch:` and `connecting` counts high in the same hours; `watchdog tick verdict=` other than Ok repeating every 2.5 min. | Transitions are isolated and do not line up in time with RC heals. |
| H7 | **Scene force-stop from notifications.** If RC's notifications reach the glasses through Hi Rokid's notification mirroring (UNVERIFIED delivery path), AssistServer opens a "message" scene and force-stops PCA's glasses app (`GlassesRelauncher.kt:122-125`). | `session closed ...` or `link=CLOSED_ON_GLASSES` within seconds of a notification time, then `relaunch: attempt in 8 s` and a reopen. The frame gap is about the length of the closed window. | No PCA close within 10 s of 5 or more logged notifications. This is not specific to Rokid-Coder: any notification would do it. |
| H8 | **Glasses resource pressure** (CPU, memory, thermal, battery from two apps). | PCA's glasses app is killed (a fresh `pc_hello`, `retained=N` after a restart), battery bucket falling faster than baseline (DiagProbe `battery bucket=`). | Same rate of glasses-app restarts and the same battery slope as baseline. |

Not every bad hour is contention. Calls, walking out of range, taking the glasses off, and the wearer's own exit (`relaunch: user exit, standing down`) produce the same lines. The manual event sheet (section 3) is what tells these causes apart.

---

## 3. Measurement plan (half-day, real phone and glasses, both apps)

### 3.1 Setup (by the user)

- Detailed log ON (Settings > Diagnostics > Detailed log; default ON, check it). Without it most of the counted lines are not in the file (1.3).
- PCA capture running at its normal cadence. Write down the capture interval, because the expected frames/hour depend on it.
- Rokid-Coder installed and running exactly as in daily use (watchdog and Monitor sessions on).
- One paper or phone note, the **event sheet**, with clock times (HH:MM:SS from the phone clock) for: RC notifications seen, RC heal/reconnect seen, RC glasses app restarted, photos taken (burst start and end), phone Wi-Fi toggled, calls, glasses off-head, and PCA app opened or closed by hand.
- Keep posture and place similar across arms: one location, worn, mostly stationary. Use a short walk only in arm E.

### 3.2 Arms (about 4.5 h in total)

| Arm | Duration | Condition | Purpose |
|---|---|---|---|
| A | 60 min | **PCA only**: Rokid-Coder stopped (phone app force-stopped, glasses app closed, its watchdog off) | Baseline frames/h, gap and transitions |
| B | 60 min | Both apps; RC idle (no remote job) | Passive coexistence (H1/H3 at rest) |
| C | 60 min | Both apps; an RC remote job that sends at least 6 notifications at noted times | H4, H6, H7 |
| D | 30 min | Both apps; 5 forced RC heals (for example toggle RC's link or kill its glasses app so its watchdog and healer act), at least 4 min apart, in both start orders (RC first, then PCA restarted; PCA first, then RC restarted) | H1, H2, H6 |
| E | 30 min | Both apps; 3 photo bursts of about 20 photos each, with Hi Rokid sync as normal; then 10 min with phone Wi-Fi OFF and 10 min with it ON | H5 |

If time is short, keep A, C and D. A is required: without a same-day baseline, no threshold means anything.

### 3.3 What to run afterwards (by the user or a controller with the device; not run here)

1. `tools/link-health-report.sh --phone <PHONE> --date <MM-DD>`. This is the per-hour table: frames, connecting, connected, auth-to, sess-term, hubdown, relaunch, max-gap-s. Split hours by arm using the event sheet; arms that cross hour boundaries need the supplementary greps below.
2. Supplementary count-only greps on the pulled `pca.log*`, run by the user and printing counts per arm window, never the lines themselves: `link=MINIMISED`, `link=CLOSED_ON_GLASSES`, `link=PAUSED_BT`, `link=NOT_AUTHORIZED`, `session closed`, `connect failed`, `glasses app resume ours=false`, `relaunch: reopening the glasses app`, `hourly limit`, `auto_stop_disconnected`, `watchdog late tick`. Also the glasses-side log, if it is pullable: `frame timeout`, `flush stopped after`.
3. `tools/place-attempt-report.sh --phone <PHONE> --since "MM-DD HH:MM" --until "MM-DD HH:MM"` per arm. It adds **`frameAgeS`** for each place attempt: how stale the newest glasses frame was when PCA tried to place you. That is a link-independent measure of what the user actually experiences. It also adds `online=`, `call=` and `totalMs`. It does not measure the link directly, but it shows whether a contention episode cost the user anything.
4. Correlation: for each RC event on the sheet, note whether a PCA link transition (any of the grep tokens) happened within 30 s after it. Tally hits out of trials per arm.

### 3.4 The report must show

- Per arm: frames, frames/h, max-gap-s, sess-term, connecting, auth-to, relaunch-activity, and each supplementary token count.
- Per arm: median and maximum of `frameAgeS` from the place report.
- The RC-event correlation tally (hits out of trials, per event type).
- Which hypotheses were confirmed or falsified, citing the row.

### 3.5 Pass/fail thresholds (B to E compared with A)

| Metric | PASS (no actionable contention) | FAIL (contention measured) | Grey zone |
|---|---|---|---|
| Frames/h | at least 90 % of A | below 75 % of A | 75-90 %: repeat the arm once |
| Max gap while worn, not stood down, no call | at most max(2 × capture interval, 120 s) | any gap over 10 min, or 3 or more gaps over 5 min | otherwise |
| PCA link transition within 30 s of an RC event | at most 1 in 5 per event type | 3 or more in 5 of the same type (in D: in either start order) | 2 in 5: repeat |
| Auth timeouts, token rejections, NOT_AUTHORIZED | 0 | 1 or more not explained by the sheet | none |
| `ours=false` and MINIMISED/CLOSED_ON_GLASSES cycles | none repeating | 2 or more cycles of minimise, reopen, minimise in 10 min, or `hourly limit` hit | one isolated cycle |
| Recovery | every drop back to CONNECTED within 2 min | any drop not recovered within 10 min without a user tap | 2-10 min |
| `frameAgeS` median | within +30 s of A | more than +120 s over A | otherwise |

A FAIL ties to a hypothesis through its fingerprint (section 2). For example, a FAIL on transitions-after-heal combined with `session closed`/`connect failed` points to H1, while the same FAIL combined with `ours=false`/MINIMISED points to H2.

### 3.6 Cheaper first pass

Trip days (22 and 23 Nov) produce logs with both apps running anyway. Running `link-health-report.sh` per day (as `device-session-runbook-2026-11-19.md` L247 already asks), plus the event sheet kept loosely, can screen for H1/H2/H7 at no extra cost. Only a clean A/B comparison can falsify H4 and H5.

---

## 4. If contention is measured: options

### O1. Do nothing in code, and document "one at a time if they fight"

- **What:** keep the current behaviour. PCA's recovery (relauncher, watchdog, backoff) already heals what was seen on 19 Nov. Fix the handoff text that says they cannot run together (now contradicted by field evidence). Optionally add a status hint when PCA sees repeated `ours=false` ("Another glasses app is in front").
- **Cost:** about 0 to 0.25 Sd (hint only).
- **Public app:** acceptable. A store app cannot assume that any other app cooperates.
- **Needs from Rokid-Coder:** nothing.
- **Fits when:** the measurement shows PASS or grey, or contention only during rare RC heals.

### O2. PCA-only courtesy rules (a one-sided half-lease)

- **What:** PCA yields without any protocol.
  - (a) When the background watcher reports `ours=false` (a foreign app in front, not a Rokid scene), the relauncher treats it as "another app is in use". It does not reopen until PCA's app is resumed by the wearer, or until the foreign app has been gone for N min (UNVERIFIED whether PCA can tell that a foreign app left; the SDK reports `ours` only as a boolean, `GlassAppWatcher.kt:54-56`).
  - (b) After a PCA session is evicted with a non-exit close soon after a bind it did not cause, back off on the existing persisted ladder instead of the 2/5/10/30 s loop.
  - (c) AutoStop does not count "evicted by a foreign session" as "disconnected", or shows that cause instead.
- **Cost:** about 0.75 to 1 Sd PCA work (relauncher plus policy changes plus tests), a review pass, and a half-day device re-test.
- **Public app:** good. It is polite to any app, including other CXR apps and Hi Rokid's own.
- **Needs from Rokid-Coder:** nothing, though a mirror-image courtesy on its side would help (request R3).
- **Fits when:** H2 or H6 is confirmed (a UI fight or loop amplification).

### O3. A link lease (a polite arbitration protocol between cooperating apps)

- **Protocol sketch:**
  - A lease record holds holder package, purpose (`capture`, `notify`, `voice`, `heal`), priority, `acquiredAt` and `ttlMs`. Default TTL 120 s; the holder renews every TTL/3; an expired lease is free.
  - **Acquire:** ask, wait up to 2 s for the holder's answer, then take the lease only if it is free or expired.
  - **Yield rules:** never pre-empt a user-initiated action (push-to-talk, a tapped Open). Lower priority yields at a safe point (PCA: after the current frame's ack, at most 5 s). The holder may refuse once per TTL.
  - **Backoff for the loser:** 30 s, then 2 min, then 5 min, with ±20 % jitter, and no heal attempt while a valid foreign lease exists.
  - **User-visible status:** "Glasses link: used by Rokid-Coder (until ~HH:MM)" in PCA's status line and notification; the reverse in RC.
  - **Transport between apps:** a bound service or ContentProvider per app, protected by a permission. There is a catch: the two apps have different signers, so a signature permission cannot work. It needs a normal or dangerous custom permission plus caller verification (`Binder.getCallingUid` and signing-cert pinning of the peer). The payload stays content-free (package, purpose, times).
- **Cost:** about 2 to 3 Sd PCA work (protocol, state machine, UI, security review, tests), similar effort on the RC side, a joint device half-day, and an ongoing compatibility burden (versioning the protocol).
- **Public app:** weak. Only apps that implement the lease benefit. Hi Rokid and other CXR apps will not, so PCA still needs O2's one-sided behaviour as a fallback. An exported endpoint widens the attack surface. Pinning a specific peer means a personal pairing, not a public mechanism.
- **Important limit:** if H1 holds (a true one-session limit), a lease does not create coexistence. It only automates taking turns. That matters only if both apps need the link often and in turn.
- **Needs from Rokid-Coder (requests, not changes):**
  - R1: implement the same lease contract (acquire, renew, yield, backoff).
  - R2: expose its link state and heal events as content-free, timestamped log lines.
  - R3: its healer backs off while a valid foreign lease exists.
  - R4: show the status to the user.

### O4. The Nexus route (PCA as a client on the Nexus bus)

- **What:** PCA stops holding its own CXR session and speaks to the glasses through the Nexus hub, so only one session exists by construction.
- **Status:** shelved (`specs/2026-11-19-nexus-option-b-transport-design.md` section 6). Upstream release glasses hubs refuse third-party glasses apps (BUSSPEC L260-264). Rokid-Coder is a fork with its own hubs. Whether it could admit PCA as a client is UNVERIFIED.
- **Cost:** about +1 day of transport work (per the Option B spec) plus SPP throughput risk (PCA's 27 KB frames over the bus; UNVERIFIED) plus a dependency on the fork.
- **Public app:** poor. It would work only with the user's private fork, not with upstream Nexus or for other users.
- **Needs from Rokid-Coder (requests):**
  - R5: would its glasses hub accept a PCA client id (provisioning)?
  - R6: its bus throughput for about 27 KB payloads.
  - R7: a stable client API version.
- **Fits when:** H1 is confirmed, both apps must run all day, and the user accepts a personal-only build.

---

## 5. Recommendation

1. **Build nothing now.** No clash has been shown: 387 frames with full recovery on 19 Nov, and the photo-plus-notification outing worked. The trip window (22-23 Nov, install freeze 26 Nov) argues against changing link code. Confidence that no serious contention exists: moderate, because the evidence is field logs, not the A/B comparison of section 3.
2. **Measure.** Use the trip-day reports as the screen (3.6), then run the half-day arms (3.2) when a device session is available. Make sure the Detailed log stays ON.
3. **If a FAIL appears**, match it to its hypothesis:
   - H2 or H6 (UI fight or loop amplification): O2, PCA-only courtesy, 1 Sd. This is the most likely useful fix and works for a public app.
   - H7 (notification force-stops): this is generic Rokid behaviour, already handled by the relauncher. At most, tune its first step (8 s); no lease.
   - H4 or H5 (bandwidth or Wi-Fi coexistence): no arbitration protocol fixes airtime. Consider PCA-side deferral of the frame flush during photo sync or RC bursts (UNVERIFIED that these can be detected), or accept the drop.
   - H1 (true exclusivity): O2 first, so that turn-taking is graceful. O3 only if the user wants both apps to run all day and will make the RC changes. O4 only for a personal build.
4. **Update the handoff texts** that say the apps "do NOT run together". They should say coexistence is unmeasured, and that field evidence suggests the apps coexist.

Uncertainty, stated plainly: the transport split (BT vs Wi-Fi P2P), the one-session limit, RC's notification path, and RC's healer cadence are all UNVERIFIED. The thresholds in 3.5 are engineering judgement, not derived from a requirement.

---

## 6. Open questions

1. Were the 100-200 photos taken with Rokid's own camera (Hi Rokid sync, possibly over Wi-Fi P2P) or are they PCA frames? This decides whether the outing exercised H5 at all.
2. How do Rokid-Coder notifications reach the glasses: its own glasses app over its own CXR session or bus, or Hi Rokid notification mirroring (which would cause AssistServer scene force-stops, H7)? (Request to the RC side.)
3. What is Rokid-Coder's healer cadence, and what triggers it? Does its accessibility watchdog bring its glasses app to the foreground (H2)? (Request to the RC side.)
4. Does Rokid-Coder hold a CUSTOM_APP session (appStart) or only a session-less CXRLink like PCA's watcher? A session-less link is much less likely to evict or foreground anything (UNVERIFIED).
5. Does the CXR SDK ever move custom-message bytes over Wi-Fi P2P? A 10-minute phone-Wi-Fi-off arm (E) answers whether PCA cares.
6. Can the glasses-side PCA log (`frame timeout`, `flush stopped`) be pulled on the release glasses build, or only on a debuggable one?
7. On the 19 Nov log: were the recovered reconnects near Rokid-Coder events? The report alone cannot say; the event sheet is needed next time.
8. Should `link-health-report.sh` gain columns for MINIMISED, CLOSED_ON_GLASSES, `ours=false` and true reopen counts (instead of all `relaunch:` lines)? It is a small change and would replace the manual greps in 3.3 step 2. It is a separate task and needs a go.
