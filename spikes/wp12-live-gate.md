# WP12 live gate (batch B2, controller)

Status: INCOMPLETE-GATED. The code half of WP12 (runtime, runner, profiles, collector, export, doctor, setup) is merged and pytest-green against the
FAKE podman only. Nothing below has been run on a real machine. Run it from the controller worktree `pca-3b-gate-b2` (final `v0.9.3` revision) inside
one `pca_lock.py vm-session` owner; no host Gradle during the batch; the VM is stopped and confirmed stopped at exit.

## Entry (controller)

- [ ] WP9, WP12, WP13 merged; machine recreated in B1 with exactly one `/stage` mount; `memory_pressure` free >= 25 %; Opera closed
- [ ] `pca-helper setup --podman <absolute path> --apply` run once (writes `~/.pca-helper/config.json`, builds `localhost/pca-pyjob:1` from `container/pyjob`, which WP13 delivers)
- [ ] `pca-helper doctor` output recorded (see step 1)

## Live steps (all TODO)

1. TODO `pca-helper doctor` with the VM stopped, then running: every line `ok` (the `python gate stamp` line is expected FAIL until the stamp is written). Record: `TODO`.
2. TODO Round trip: a py_exec-sized file (about the profile output size) written by the container to `/out`, container exits, host exports through the capped pipe, collector returns it byte-exact, volume gone (`podman volume ls`), stage dir gone. Record sizes and sha256: `TODO`.
3. TODO Deletion event mid-job: leaves no `pca-job-*` volume, no container, no stage dir, no output; kill to gone <= 5 s. Record: `TODO`.
4. TODO Orphan sweep: start the helper with a leftover `pca-job-*` volume and container; both gone before the first poll. Record: `TODO`.

## Facts the fake cannot prove (verify and record; fix the named place if they differ)

| # | Assumption | Where it lives | Result |
|---|---|---|---|
| 1 | `podman run --timeout N` ends the container; its exit status (assumed 124 = `timeout`) | `runner.EXIT_CODES` | TODO |
| 2 | An OOM kill shows as exit 137 (assumed `oom`) | `runner.EXIT_CODES` | TODO |
| 3 | The named volume at `/out:rw` is writable by `--user 10001` (copy-up of an image dir `/out` owned by 10001; else add `:U`) | `runner.build_argv`, WP13 Containerfile | TODO |
| 4 | `podman volume export` works over the remote (ssh) connection and streams a plain tar | `runtime.export_volume` | TODO |
| 5 | `--pull never`, `--log-driver k8s-file --log-opt max-size=` and `--network none` are accepted by the VM's podman | `runner.build_argv` | TODO |
| 6 | `podman kill` / `rm -f -t 0` answer inside the 1 s kill budget over ssh | `runtime.kill`, `runtime.remove` | TODO |
| 7 | `machine inspect` JSON has `State`, `Rootful`, `Mounts[].Source/Target`; `system connection list --format json` has `Name`, `URI`, `Default` | `runtime.check` | TODO |
| 8 | `podman machine ssh <name> df -P -B1 /var` prints used bytes in column 3 | `runtime.vm_var_used_bytes` | TODO |
| 9 | `ps -a --filter label=pca.helper` lists containers named `pca-job-<id>` | `runtime.list_containers` | TODO |
| 10 | `container/vm/machine.sh recreate` passes `--cpus 2 --disk-size 10` (user decision: NEW machine `pca`, 10 GB cap, alongside the user's `podman-machine-default`, which no script touches; the script refuses that name). Check `podman machine inspect pca` shows 2 CPUs, 4096 MiB, 10 GB disk and that `podman-machine-default` is unchanged | `container/vm/machine.sh` | TODO |

## Result

WP12 live: TODO PASS/FAIL. Machine stopped at exit: TODO. Evidence is committed from the B2 gate worktree.
