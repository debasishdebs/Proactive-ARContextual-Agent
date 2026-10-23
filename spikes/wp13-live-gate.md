# WP13 live gate (batch B2, controller)

Status: INCOMPLETE-GATED. The code half of WP13 is merged and pytest-green against the FAKE podman only: `pca_helper/{schema_lite,jobs_py,gate_stamp}.py`,
`container/pyjob/{Containerfile,pyjob_entry.py,schema_lite.py}`, `tools/canvas-helper/gates/py_gate_live.sh`. Nothing below has been run: no image was built,
no container started, no machine touched. Run it from the controller worktree `pca-3b-gate-b2` (final `v0.9.3` revision) inside the same one
`pca_lock.py vm-session` owner as `spikes/wp12-live-gate.md`; no host Gradle during the batch; the VM is confirmed stopped at exit.
Do the WP12 entry list first (machine recreated in B1, `pca-helper setup` config written); this file adds the WP13 steps.

## Image: base and what the code assumes

- The image is stdlib only: base `docker.io/library/python:3.12-slim`, no pip, no extra package. `container/pyjob/Containerfile` has the form
  `FROM docker.io/library/python:3.12-slim@sha256:<DIGEST>` and the placeholder fails the build on purpose until step 1 fills it.
- `container/pyjob/schema_lite.py` is a byte-identical copy of `tools/canvas-helper/pca_helper/schema_lite.py` (a test enforces it): edit the helper file, then copy.
- The Containerfile creates user 10001 and `/out` owned by 10001 (so a fresh named volume mounted at `/out` is initialised with that owner), `USER 10001`,
  `ENTRYPOINT ["python3","/app/pyjob_entry.py"]` (the runner passes no command and forbids `--entrypoint`).

## Live steps (all TODO)

1. TODO Pin the base and build. `podman --connection pca pull docker.io/library/python:3.12-slim`, then
   `podman --connection pca image inspect --format '{{.Digest}}' docker.io/library/python:3.12-slim`; put the digest into the `FROM` line of
   `container/pyjob/Containerfile` (this is the ONE code edit of the gate; it goes to the gate worktree as evidence-adjacent input, so make it BEFORE the
   tested commit is recorded, not after a stamp) and `pca-helper setup --podman <absolute path> --apply` (builds `localhost/pca-pyjob:1`).
   Record in `images.lock` (create it in the gate worktree; one line per image: `name digest imageId builtFromCommit`): the base digest, the id of
   `localhost/pca-pyjob:1` (`podman image inspect --format '{{.Id}}'`) and the tested commit. Record: `TODO`.
2. TODO `pca-helper doctor`: `images` ok (still FAIL on `python gate stamp`). Record: `TODO`.
3. TODO Run `tools/canvas-helper/gates/py_gate_live.sh` (needs `~/.pca-helper/config.json`; stdout `PASS|FAIL <check>` per check; exit 0 = stamp written,
   1 = a check failed, 2 = not ready). It runs one 7-item probe job (probe, float, sleep, raise, bad output, 3 MB output, allocate-until-OOM) through the
   real `Workspace`, the one argv builder and the real runtime, then a long job that is killed while running. Required checks (all must PASS):
   `image user_10001 root_readonly network_none out_writable round_trip float_result per_item_timeout_kill exception schema_mismatch too_big oom ids_once
   volume_gone stage_gone kill_within_5s`. Record the full output: `TODO`.
4. TODO Stamp. Confirm `~/.pca-helper/gates/python.stamp` exists ONLY after a fully passing run (a failing re-run removes it first), and `pca-helper doctor`
   shows `ok python gate stamp`. Record the stamp content (tested commit, image id): `TODO`.
5. TODO Kill on deletion, <= 5 s, with a live container, through the supervisor (the script's `kill_within_5s` measures `podman kill` + `rm` to gone; this
   step measures the whole chain). Pair a real or scripted phone, start a long job (a source that sleeps), issue the deletion event, measure from the
   event to `podman ps -a` showing no `pca-job-*`, and check `podman volume ls` and `~/.pca-helper/stage` are empty. Record seconds: `TODO`.
6. TODO B2 acceptance on a real frame run (part of the v0.9.3 device run, section 8 of the core plan): pair, grant on, select up to 50 real frames/photos
   in the browser, start a 10-line function, the confirmation card appears on the phone and the browser shows "waiting for your tap", tap, results appear;
   a second run left unconfirmed expires after `confirmTtlMs` with no payload sent; a second run declined ends with `not_confirmed`; the browser cannot
   confirm; Forget of one of the frames mid-run is seen on the device; cancel works. Include one function that returns a float (`number` schema) and
   check the phone accepts it (D1: the result is posted as text + hash). Record: `TODO`.
7. TODO v0.9.3 device run done (S52 checks per the plan). Record: `TODO`.

## Facts the fake cannot prove (verify and record; fix the named place if they differ)

| # | Assumption | Where it lives | Result |
|---|---|---|---|
| 1 | The base image has `groupadd`/`useradd` (Debian slim does) and `RUN` works under the pinned digest | `container/pyjob/Containerfile` | TODO |
| 2 | A named volume mounted at `/out` takes the owner (10001) of the image's `/out` directory (copy-up); else add `:U` in `runner.build_argv` (WP12 fact 3) | Containerfile, `runner.build_argv` | TODO |
| 3 | `fork` + `select` + `SIGKILL` of the per-item child works inside the container under `--pids-limit 128`, `--cap-drop=ALL`, `--read-only`, tmpfs `/tmp` | `pyjob_entry.run_item` | TODO |
| 4 | An item that allocates past 1024 MiB ends as `oom` (child SIGKILLed by the cgroup, or MemoryError) and the harness parent survives to write `outputs.json` | `pyjob_entry.run_item` | TODO |
| 5 | `--network none` makes `socket.connect` fail with OSError (the probe item reports `net_blocked`) | gate script | TODO |
| 6 | The 3 MB item is `too_big` and the whole `outputs.json` stays <= 2 MiB (`maxResultBytes`) | `pyjob_entry.run_job` | TODO |
| 7 | `podman image inspect --format '{{.Id}}'` works over the connection (the stamp records it) | gate script | TODO |
| 8 | `PYTHONHASHSEED=0` and `PYTHONDONTWRITEBYTECODE=1` cause no problem on a read-only root | Containerfile | TODO |

## Result

WP13 live: TODO PASS/FAIL. Machine stopped at exit: TODO. `python.stamp` written by the gate script only: TODO. Evidence (images.lock, outputs of steps 1-7)
is committed from the B2 gate worktree in the one evidence commit; no code change after a stamp.
