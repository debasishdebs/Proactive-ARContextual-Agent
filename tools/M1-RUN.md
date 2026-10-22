# Slice 3a M1 device run (Task 23b)

Controller runs `tools/m1-run.sh`. The phone is reached over Wi-Fi only (LAN API, plus wireless adb for the trace hand-over after the wear). The glasses are on USB only and are touched only by `pull` and `status`, AFTER the wear (`$G`). Nothing below prints the session token, the pairing code or a device secret.

## Your steps
1. Phone: Settings > Diagnostics > turn **Developer mode** on. Open the Canvas screen, turn **Use from a laptop** on (same Wi-Fi as the laptop). Read the **host:port**, the **pairing code** and the first 8 digits of the **certificate fingerprint**; the code lives 5 minutes (tap **New code** if it lapsed or locked).
2. Tell the controller: host:port, code, fingerprint. It runs `pair`, `plan` (must say `f5782e0d8610`, 0 problems), `shadow` (SHADOW only), then `collect start 25`.
3. Wear the glasses 20-30 minutes and go about normally (walk, sit, look at things). Keep the phone on the same Wi-Fi, screen can be off, Developer mode stays on.
4. Take the glasses off and put them on charge. Tell the controller; it runs `collect stop`, then `pull`.
5. Leave both apps alone while the controller runs `replay <traceId>` (about a minute). Do not tap Delete all or turn Developer mode off meanwhile.
6. Afterwards: Canvas LAN switch off. (Back-up of both apps and the install were done by the controller before step 1.)

## Controller sequence
```
tools/m1-run.sh pair <host:port> <code> <fp8>
tools/m1-run.sh plan && tools/m1-run.sh shadow && tools/m1-run.sh status   # plan=f5782e0d planMode=SHADOW in the heartbeat = EFFECTIVE
tools/m1-run.sh collect start 25
# ... worn ...
tools/m1-run.sh collect stop && tools/m1-run.sh pull ~/m1-pull && tools/m1-run.sh status
tools/m1-run.sh replay                           # the trace `pull` imported (or `replay <traceId>`)
```
Notes: the phone has no plan-ack route, so EFFECTIVE is read from the glasses heartbeat (`plan=`, `gen=`, `planMode=`, `planRevert=`, `shOpen=`, `shSkip=` in `files/capture_diag.log`); only heartbeat lines are read, never AssistServer or system logs. Automatic glasses-to-phone transfer is not built (later), so `pull` is the hand-over: it pulls the sealed trace with `trace-pull.sh` (glasses on USB), then for each trace `adb push`es it to the phone's staging dir `/sdcard/Android/data/com.rokid.context/files/trace-staging/<traceId>/` (the app's own external files dir: adb's shell user can write it on Android 14/15 without root; unverified here, no device access in this task) and calls `POST /api/traces/import` (Developer mode on; checksums, deletion epoch, tombstones and the 500 MB cap are enforced by the phone; the staging copy is deleted on success). The phone is selected by adb transport id (auto when it is the only other device in `adb devices -l`, else `M1_PHONE_TID=<id>`), never by serial (the wireless serial has spaces). A PARTIAL trace is refused (409) unless `pull <dir> --allow-partial`; it is then replayable but never publish evidence. `pull` also saves the heartbeat lines. `shOpen` is summed over the SHADOW heartbeats of the run (the whole log if you did several runs: use a fresh log or `--diag` on a trimmed file). `windows compared` = base passed + base failed.

## Pass criteria (all must hold)
- `plan`: `configHash=f5782e0d8610 problems=0 warnings=0`.
- `shadow`: `mode=SHADOW sent=True`; heartbeat shows `plan=f5782e0d planMode=SHADOW`, `planRevert=none`.
- The trace is `COMPLETE` and `exact=True` in `status`/`pull`, covering the worn run.
- `replay`: `jobState=DONE`, `base_fail=0` with `windows_compared > 0` (inexact windows are excluded and counted; report the count), BatteryLine hours printed.
- `replay`: `PASS shadow vs counterfactual opens` : shadow `shOpen` total within +-5 % of the counterfactual `opens`.
- No `planRevert` other than `none`, no phone or glasses crash.
Task 27 lands only after all of these pass.
