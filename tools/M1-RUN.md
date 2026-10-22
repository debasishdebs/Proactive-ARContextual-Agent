# Slice 3a M1 device run (Task 23b)

Controller runs `tools/m1-run.sh` (needs the glasses on adb as `$G`; the phone is reached over Wi-Fi). Nothing below prints the session token, the pairing code or a device secret.

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
tools/m1-run.sh replay <traceId>                 # traceId from status / pull
```
Notes: the phone has no plan-ack route, so EFFECTIVE is read from the glasses heartbeat (`plan=`, `gen=`, `planMode=`, `planRevert=`, `shOpen=`, `shSkip=` in `files/capture_diag.log`); only heartbeat lines are read, never AssistServer or system logs. The trace reaches the phone over the link when the glasses are charging/linked; `pull` also archives the sealed chunks (`trace-pull.sh`) and heartbeat lines. `shOpen` is summed over the SHADOW heartbeats of the run (the whole log if you did several runs: use a fresh log or `--diag` on a trimmed file). `windows compared` = base passed + base failed.

## Pass criteria (all must hold)
- `plan`: `configHash=f5782e0d8610 problems=0 warnings=0`.
- `shadow`: `mode=SHADOW sent=True`; heartbeat shows `plan=f5782e0d planMode=SHADOW`, `planRevert=none`.
- The trace is `COMPLETE` and `exact=True` in `status`/`pull`, covering the worn run.
- `replay`: `jobState=DONE`, `base_fail=0` with `windows_compared > 0` (inexact windows are excluded and counted; report the count), BatteryLine hours printed.
- `replay`: `PASS shadow vs counterfactual opens` : shadow `shOpen` total within +-5 % of the counterfactual `opens`.
- No `planRevert` other than `none`, no phone or glasses crash.
Task 27 lands only after all of these pass.
