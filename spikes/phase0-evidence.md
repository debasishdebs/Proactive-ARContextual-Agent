# Phase 0 evidence (spike A, WP10)

Status: INCOMPLETE-GATED. The code half of WP10 is merged and pytest-green; every LIVE step below is a TODO for the controller, to be run in batch B1
from the gate worktree `pca-3b-gate-b1` (from the `v0.9.1` tip after WP10 merged). Nothing in this file has been run on a real machine.

## Code half (done, no VM)

| Item | Evidence |
|---|---|
| `tools/canvas-helper/pca_lock.py` admission lock (section 8.1) | pytest `test_wp10_lock.py`, run against a FAKE podman |
| `container/vm/machine.sh` exact sequence | pytest `test_wp10_machine.py` (`test_machine_recreate_sequence`) |
| `spikes/s2_probe.py` assertions | pytest `test_s2_probe.py` (injected data); the probe itself has NOT run in a container |

## References (fill in by the controller)

- PF result (host memory settings, two-build admission): `TODO: path/commit of the PF file`
- Tested host settings: `TODO: by reference to the PF file`
- Tested commit: `TODO`; image/profile hashes: `TODO`

## B1 entry conditions (controller verifies before starting)

- [ ] WP10 code merged, pytest green
- [ ] PF passed
- [ ] machine recreation approved (decided 2026-10-23 evening)
- [ ] previous machine confirmed stopped (nothing running, RAM free); `memory_pressure` free >= 25 %; Opera closed
- [ ] `podman images`, `podman volume ls`, `podman ps -a` listed and recorded below BEFORE the remove

Inventory before remove: `TODO (paste the three listings)`

## B1 live steps (TODO, controller; all inside one `pca_lock.py vm-session` owner; no host Gradle during the batch)

1. TODO Start the owner: `python3 tools/canvas-helper/pca_lock.py vm-session` (it holds `host.lock` exclusive from before the first step).
2. TODO `exec bash container/vm/machine.sh recreate` (stop previous, confirm stopped, rm, init with ONE `/stage` mount, start). Record the order that ran: `TODO`.
3. TODO Record that the machine has exactly one mount (`podman machine inspect`): `TODO`. Check `ls /Users` fails inside the VM (S2c): `TODO`.
4. TODO Isolation probe (S2a): run `spikes/s2_probe.py` in a probe container (`--network none`, non-root `--user`, `--read-only`, only the `/stage` mount); record the JSON: `TODO`. Assertions: no route to the LAN or the Mac, rootless, read-only root, no `/Users`.
5. TODO H20 (Python profile only): `TODO`.
6. TODO Lock check on the live VM: a host build via `pca_lock.py build -- true` is refused with exit 75 while the owner is up and between steps: `TODO`.
7. TODO End of batch: `exec bash container/vm/machine.sh stop`, then `stop` on the owner; confirm `podman machine inspect` shows stopped before the lock is released: `TODO`.

## Result

L1: TODO PASS/FAIL. S2a: TODO. S2c: TODO. H20: TODO.
Machine has exactly one mount: TODO yes/no. Machine stopped at exit: TODO.
Spike failure rule (spike A fail = no containers, phone half only, INCOMPLETE-GATED, scope review): TODO applied? 
Evidence is committed from the B1 gate worktree.
