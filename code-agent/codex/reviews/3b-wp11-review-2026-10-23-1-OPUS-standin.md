# WP11 review 1 (helper core), commit c328839b, `git diff 65b530ef..HEAD`, worktree /Users/debasishkanhar/Desktop/rokid/pca-3b-wp11

Reviewer: Opus, standing in for Codex (read-only). I ran the suite once: `PYTHONDONTWRITEBYTECODE=1 python3 -m pytest tools/canvas-helper/tests -q -p no:cacheprovider` printed **103 passed in 2.14 s** (93 WP11 tests plus the 10 WP1 tests). I compared the helper with the Kotlin side in pca-3b-wp6 (HelperRoutes.kt, HelperJobService.kt, BundleHash.kt, CanvasSlice3Dao.kt).

## What I checked and found correct

- **Pin checked on every call.** `PhoneClient._call` (client.py:135-155) raises `MissingPin` before `transport.open`. It compares the pin after the handshake and before `conn.request`. The fingerprint is SHA-256 of `getpeercert(binary_form=True)`, which is the leaf DER. The compare uses `hmac.compare_digest` on equal-length strings. `pair()` refuses a missing pin before it builds a client (pair.py:21-23).
- **Transport settings.** CERT_NONE with `check_hostname=False` is acceptable because the pin is the only trust anchor. There is no `Origin` header. `http.client` runs over a socket the client builds itself, so no environment proxy is used and no redirect is followed. Every call has a total deadline, and the heartbeat timeout is 1 s.
- **Stored pin.** After pairing, the helper stores the full 64-hex observed fingerprint, not the typed prefix (pair.py:40).
- **Kill policy.**
  - The anchor is the send time of a 2xx lease or heartbeat (supervisor.py:174, 190, 267, 285).
  - The kill fires when `now > anchor + 3500` (line 302).
  - The local lease deadline and the job deadline are converted with `serverTimeMs`. Both conversions err early, which is the safe direction.
  - 401/403/404/409 from heartbeat, payload or result kill at once. A poll 401 ends the session. A poll 403 kills the job.
  - The watchdog runs on its own thread and never waits on a network call or on the runner.
  - Heartbeats use a fixed tick with at most 2 in flight.
  - The judgement calls are all acceptable: anchoring only on lease and heartbeat 2xx, a slow lease killing at registration, and treating every 409 as deletion-class. The phone already closed those rows, so posting nothing loses nothing.
- **resultHash.** The helper sends `bundle_hash(result)`. The phone checks only `^[0-9a-f]{64}$` and stores `BundleHash.ofCanonical` of its own canonicalisation (HelperRoutes.kt:193-194, HelperJobService.kt:161-169), so there is no disagreement. A float in a result is refused on both sides: the phone answers 422, and the helper fails `bad_output` first. This is consistent.
- **Payload.** `payloadBytes` is the UTF-8 length on both sides (HelperJobDaoTypes.kt:22). The heartbeat body `{leaseGen}` matches the strict key-set parser (HelperRoutes.kt:97-100).
- **Journal.**
  - Each append is fsync'd. A torn last line is skipped and newline-terminated before the next append. The file is 0600 and the directories are 0700.
  - Cleanups are persisted before execution and acked only after `cleanup_done`. A redelivered acked cleanup is acked again.
  - Startup recovery runs before the first poll, in the plan's order: containers, then open jobs, then `pca-job-*` volumes, then pending cleanups, then the expiry sweep.
  - I accept append-only growth for CORE: about 6 short lines per job.
- **Logging.** Values are count-only. Registered secrets are redacted. Hex strings of 8 or more digits are reduced to their length. The AST test bans `print`, `logging` and stream writes outside log.py and cli.py.
- **Token on disk.** `state.json` is 0600 and holds the token and the full pin. The section 21(b) memory-only rule is a phone-side rule (a phone restart invalidates the token, and the helper then gets 401 and exits 3 to re-pair). The old plan line 90 explicitly allows the 0600 helper copy. This is acceptable.
- **No host execution path.** There is no `subprocess`, `os.system`, `exec` or `eval` in `pca_helper/`. `cli run` wires `NullRunner` (always `needs_runtime`) with `ready=False`, so the helper never even leases. Importing the package has no side effects.
- **Tests.** They are behavioural, not tautological. The named mutations are plausible: `helperSelfKillMs` set to 8000 fails the 3500/3501 boundary tests, removing the expiry sweep fails `test_retention_7d*`, and retrying on `not_confirmed` fails the `len(...of("payload")) == 1` assertion. The loopback TLS server and the hanging socket are closed in `finally`.

## Findings

### 1. Important: an 8-hex-digit pin lets a mismatched peer receive the pairing code (client.py:43-50, 53-55; pair.py:21-23; cli.py:78)

`normalize_pin` accepts any prefix from 8 to 64 hex digits, and `pin_matches` accepts the peer when its fingerprint starts with that prefix. An 8-digit prefix is a 32-bit second-preimage target.

**Failure scenario.**
1. The phone's certificate is public: any LAN host can fetch it and its fingerprint.
2. The client uses CERT_NONE, so the certificate's own signature is never verified. A LAN attacker can therefore keep their own key pair and change the certificate's signature bytes or serial until SHA-256 of the DER starts with the same 8 hex digits. That takes about 2^32 SHA-256 operations, which is minutes on a laptop CPU, and it can be done before the pairing starts.
3. During the 120 s pairing window the attacker intercepts the connection (ARP or DNS spoofing on the same Wi-Fi). The helper sends the pairing code to that peer.
4. The attacker relays the code to the real phone, gets the real token, and answers the helper.
5. The helper then stores the attacker's full fingerprint as its pin (pair.py:40). The attacker is now permanently in the middle and receives every confirmed `py_exec` payload (the user's frames).

This is exactly the block condition "pairing code to a mismatched peer". The phone already shows 8 groups of 4 hex digits (32 hex) by default (CanvasHome.kt:296-299, `groups.take(8)`), so a 32-hex minimum costs the user nothing extra.

**Edit.**
- client.py `normalize_pin`: change `len(p) < 8` to `len(p) < 32`, and add a module constant `MIN_PIN_HEX = 32`.
- pair.py:23 and cli.py:78: change the message to "give at least the first 32 hex digits (8 groups) of the fingerprint shown on the phone".
- test_pairing.py:45 (`test_pin_prefix_of_8_hex_digits_matches...`) and test_pairing.py:170 (`pin=fp[:8]`): use `fp[:32]`.
- Add `test_short_pin_under_32_hex_sends_no_code`: an 8-hex and a 31-hex pin both raise `MissingPin` with `transport.opened == []`.
- If the plan's "8 hex" wording is meant literally, the plan text must change to 32 in the same commit.

### 2. Important: the watchdog thread can die silently, after which no liveness or deadline kill happens (supervisor.py:476-479; journal.py:113-175)

**The unguarded loop.** `watch_loop` calls `self.watchdog_check()` with no `try`. `watchdog_check` reaches `_kill_and_end`, then `_end_job`, then `journal.job_end`/`add_cleanup` (fsync'd writes), then `_run_pending_cleanups`, which removes files and volumes. Any exception on that path ends the thread for the rest of the process. Examples are an `OSError` from `os.open`/`os.fsync` (disk full, home dir permissions) or an exception from the runtime.

**The unlocked reads.** The `Journal` readers iterate the shared dicts without `self._lock`: `pending_cleanups`, `has_pending_cleanup_for`, `acks_to_send`, `open_jobs`, `known_job_ids`, `all_volumes`, `artifacts`, and the `len(self._cleanups)` inside `job_end`. Meanwhile `_append` mutates those dicts from other threads:
- the main thread (`_process_cleanups`, `_end_job`)
- heartbeat threads (`_deny`, `_end_job`)
- the watchdog thread (`_end_job`, `kill_pending_add`)

**Failure scenario.** A cleanup command for the active job arrives in a poll at the same moment the watchdog's liveness kill fires.
1. The main thread is in `add_cleanup`, inserting into `_cleanups`.
2. The watchdog thread is in `_end_job` → `_run_pending_cleanups` → `pending_cleanups()`, iterating `_cleanups.values()`.
3. The iteration raises `RuntimeError: dictionary changed size during iteration`, and the watchdog thread exits.
4. From then on, every later job relies only on heartbeat denials. A phone that becomes unreachable (Wi-Fi off, the deletion case the 3.5 s rule exists for) never triggers a kill. The container and its inputs live until the runtime `--timeout` backstop.

The same race in the main thread crashes `run()`. That path is less severe because `finally` calls `shutdown()`.

**Edit.**
1. supervisor.py `watch_loop`: wrap the call in `try: self.watchdog_check() except Exception: self.log.event("watchdog_error")`. Do the same in `beat_loop`. The loop must survive any exception.
2. journal.py: take `self._lock` in every reader and return copies built under the lock, for example `with self._lock: return [dict(c) for c in self._cleanups.values() if ...]`. Make `job_end` and `add_cleanup` check and append under one lock. Change `_lock` to `threading.RLock()` so `_append` can be called while it is held.
3. Test `test_watchdog_survives_a_journal_error`: make `journal.job_end` raise `OSError` once, call the real `watch_loop` through `run()` with a SystemClock, and assert that a second liveness overrun still kills.

### 3. Important: a container started after a kill or denial is not retried if its kill fails, and the runner is started for a job already killed (supervisor.py:228-246)

**No re-check before start.** `_fetch_and_start` calls `self.runner.start(...)` without re-checking `job.killed`/`job.ended`. If a heartbeat denial (403/409 `voided` after a Forget), a cancel or a liveness kill lands during the payload fetch, the job has already ended and its local cleanup has already run. The runner still creates a new container, which re-stages the inputs (FakeRunner writes `stage/<id>/in/x.bin`).

**The late path does not retry.** The late path at lines 241-246 runs `if not handle.kill(): handle.force_remove()`. It ignores the result of `force_remove()`, journals no kill-pending entry, and ignores the return value of `self._executor.execute(job.job_id)`. If both kill calls fail (a transient podman or VM error, which the plan's retry rule exists for), nothing retries. The container keeps running with the job's inputs, and its `pca-job-<id>` volume stays (removal fails while the volume is in use). This lasts until the runtime `--timeout` or the next helper start, after a deletion-class denial.

This is the block condition "the helper keeps a container or inputs after a denial".

**Edit.**
1. Before `self.runner.start` (line 229), add: `with self._lock: dead = job.killed or job.ended` and `if dead: self._executor.execute(job.job_id); return`.
2. In the late branch, replace the `try/finally` with:
   - `ok = bool(handle.kill()) or bool(handle.force_remove())`;
   - if not `ok`, call `self.journal.kill_pending_add(<container name>)`;
   - always call `self.journal.job_end(job.job_id)`, which enqueues a fresh `local:<id>:N` cleanup that is retried every poll, instead of the bare `execute`.
3. `_retry_pending_kills` must then kill by the same key; see finding 4.
4. Test `test_denial_during_payload_fetch_starts_no_runner`: block the payload, push heartbeat 409 `voided`, release, and assert `runner.started == []` and that the stage dir is gone.
5. Test `test_late_start_kill_failure_is_journaled_and_retried`: block `runner.start`, deny, make the handle's kill and force_remove return False once, release, and assert the kill-pending entry exists and a later poll retries it.

### 4. Minor: kill-pending keys mix container names and job ids (supervisor.py:93, 119-122, 333)

`recover` journals container names (`kill_pending_add(name)`), but `_kill` journals `job.job_id`. `_retry_pending_kills` passes both to `runtime.kill(key)`. If WP12 names containers `pca-job-<id>` (as FakeHandle does, fakes.py:173), a job-id key never names a real container. After a crash, that entry is retried with `runtime.kill("job1")` forever, and it is never cleared when podman reports that no such container exists.

**Edit.** Journal the container name (`getattr(h, "name", None)`, or `"pca-job-" + job_id` per the WP12 contract). Record the naming in the runtime interface docstring at supervisor.py:9-11. Treat "no such container" as success.

### 5. Minor: a pending `*` cleanup damages every later job (journal.py:136-137, 206-222; supervisor.py:137, 391-393)

While a `*` cleanup is still pending (for example a volume removal that keeps failing), `poll_once` still leases new jobs. Each poll's `_run_pending_cleanups` then runs `execute("*")`, which deletes the new active job's stage dir and tries to remove its volume mid-run. `has_pending_cleanup_for` also drops the new job's finished result (`_deny(job, "cleanup")`), so the phone sees only a lease expiry.

**Edit.** In `poll_once`, do not lease while any cleanup is pending: `if self.journal.pending_cleanups(): skip lease_once`. Alternatively, store the set of job ids that exist when the `*` command arrives and apply it to those ids only.

### 6. Minor: production code imports a file under tests/ (protocol.py:25-32)

`protocol.py` loads `tests/protocol_loader.py` by path. That works from a repo checkout, but it breaks as soon as WP12/WP13 package or copy `pca_helper` without `tests/` (for example a `pip install`, or the container image if it ever imports protocol). This is not blocking for WP11.

**Edit.** Before WP12 merges, move `canonical_json`, `bundle_hash`, `job_id_ok` and `count_bounds_ok` into `pca_helper/canonical.py`. Make `tests/protocol_loader.py` import from it, keeping one implementation.

### 7. Minor: expiry trusts a rewound wall clock (expiry.py:17-24)

If the wall clock is set back (for example a dead RTC, a manual change, or NTP correcting a fast clock), `expiresAtMs <= now` stays false for up to the size of the rewind. A retained artifact can then outlive 7 days. A forward jump only expires early, which is the safe direction.

**Edit.** In `sweep`, also remove when `now < a["createdAtMs"]` (the clock went back past the creation time). Add a fake-clock test that moves the wall clock back by 30 days.

### 8. Minor: fsync on macOS does not reach stable storage (journal.py:53; store.py:30, 37)

On macOS, `os.fsync` does not flush the drive cache. A power loss (not a process crash) can lose the last journal records, including a just-persisted cleanup. The phone redelivers unacked cleanups, so this is not a lost-cleanup hole.

**Edit.** Use `fcntl.fcntl(fd, fcntl.F_FULLFSYNC)` when `hasattr(fcntl, "F_FULLFSYNC")`, falling back to `os.fsync`. Also fsync the journal directory once after the file is created (journal.py:22-24).

### 9. Minor: the pairing code goes on the command line (cli.py:48-51)

`pca-helper pair HOST:PORT CODE FINGERPRINT` puts the code into shell history and `ps` output. It is single-use with a 120 s TTL, so the risk is small under threat model A.

**Edit.** When `code` is `-` or omitted, read it with `getpass.getpass("pairing code: ")`, and document that form as the default.

### 10. Minor: the orphan-sweep count is misleading (supervisor.py:94)

`containers_checked=len(self.runtime.list_containers())` lists the containers again after they were removed, so the line reports the survivors, not the containers checked.

**Edit.** Compute `names = self.runtime.list_containers()` once, and log `containers_checked=len(names)` and `kill_failed=<n>`.

### 11. Minor: a payload that fails integrity checks is reported as `bad_output` (supervisor.py:224-227)

A hash, length or shape mismatch of the fetched payload is posted as fail `bad_output`, a code whose meaning is about output. The fail enum has no better code and the phone accepts it, so this is acceptable. Document it in the docstring at supervisor.py:1-11 so WP8's status mapping does not show "your function's output was invalid" for a transport fault.

### 12. Minor, for WP13 awareness and not a WP11 change: a float in any output makes the whole job fail

`canonical_json` refuses every float, matching `BundleHash.kt:13`. A function returning `0.5` therefore fails the whole job with `bad_output` on the helper (and with 422 on the phone). Experiments over frames commonly return floats. WP13's `pyjob_entry.py` or schema must encode numbers to match, for example as scaled integers or strings, or WP1 must widen the canonical form. This needs a decision before WP13.

## Summary

- 3 Important findings:
  - #1: the 8-hex pin is grindable, so the pairing code can reach a mismatched peer.
  - #2: the watchdog thread can die silently.
  - #3: a container started after a denial is not journaled or retried when its kill fails.
- 9 Minor findings.
- No Critical findings: no unpinned send path, no host execution path, no secret in logs, and no lost cleanup across a process crash. The contract matches the Kotlin side.

VERDICT: NOT READY
