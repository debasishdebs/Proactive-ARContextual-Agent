"""Supervision of the one leased job (core plan section 6; part-C H3a/H3b/H4). Supervision NEVER executes jobs and never waits on the runner:

* heartbeat: a fixed 1 s timer sends a beat whether or not the previous one finished (at most 2 in flight, 1 s request timeout);
* liveness anchor = monotonic SEND time of the last 2xx-acked lease/heartbeat request (never the arrival time);
* the watchdog reads only monotonic deadlines under a short lock and never waits on a network call: it kills when now > anchor + helperSelfKillMs
  (3.5 s), when the local lease deadline (last ok send + 10 s) passes, or when the job deadline (converted with serverTimeMs) passes;
* a heartbeat/payload/result answer of 401/403/404/409 (any code) kills at once; a poll answer of 401 ends the session;
* a deletion-class denial (including `not_confirmed`) deletes every laptop copy at once and posts no result and no fail.
The runner is an interface (WP12/WP13): `start(job, payload) -> Handle`; Handle: `kill() -> bool`, `force_remove() -> bool`, `done() -> bool`,
`outcome() -> {"kind": "result", "result": {...}} | {"kind": "fail", "code": str}`. The runtime interface (WP12) is: `list_containers()`, `kill(name)`,
`remove(name)`, `list_volumes()`, `remove_volume(name)`. A container is named `pca-job-<jobId>` (Handle.name, else that default): that name is the
kill-pending key in the journal. A payload that fails its integrity checks (hash, length, shape) is reported as fail `bad_output`, the only enum code
that fits; WP8 must not present it to the user as "your function's output was invalid". No subprocess and no host execution of job code exists in this package.
"""
import json
import re
import threading
import time

from dataclasses import dataclass, field
from typing import Any, Optional

from . import protocol
from .client import BodyTooLarge, NetworkError
from .journal import CleanupExecutor, VOLUME_PREFIX

DENIAL_STATUSES = (401, 403, 404, 409)
KILL_RETRY_MS = 1000
RESULT_RETRY_MS = 1000
MAINTENANCE_INTERVAL_MS = 3600 * 1000
WATCHDOG_TICK_S = protocol.constants()["watchdogTickMs"] / 1000.0


def _snake(name):
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()[:24]


def constants():
    return protocol.constants()


class RunnerError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


@dataclass
class ActiveJob:
    job_id: str
    lease_gen: int
    bundle_hash: str
    payload_bytes: int
    anchor: int
    lease_deadline: int
    job_deadline: int
    handle: Any = None
    killed: bool = False
    ended: bool = False
    kill_pending: bool = False
    next_kill_ms: int = 0
    end_pending: bool = False
    kill_reason: Optional[str] = None
    inflight: int = 0
    next_post_ms: int = 0
    posting: bool = False


def _thread_spawn(fn):
    t = threading.Thread(target=fn, daemon=True)
    t.start()


class Supervisor:
    def __init__(self, client, runner, journal, store, clock, log, runtime=None, ready=None, spawn=None, consts=None, expiry=None):
        self.client, self.runner, self.journal, self.store = client, runner, journal, store
        self.clock, self.log, self.runtime = clock, log, runtime
        self.ready = ready or (lambda: False)
        self.spawn = spawn or _thread_spawn
        self.c = consts or constants()
        self.expiry = expiry
        self._executor = CleanupExecutor(store, journal, runtime)
        self._lock = threading.Lock()
        self._job = None
        self.session_lost = False
        self._last_maintenance = None
        self._next_pending_ms = 0

    @property
    def active_job_id(self):
        j = self._job
        return j.job_id if j is not None else None

    # ================================================================= startup recovery (before the first poll)
    def recover(self):
        """Orphans first: every labelled container is killed and removed, every job the journal still calls open is closed (its copies are removed
        by the local cleanup), every pca-job-* volume goes (no job is open at start), pending cleanup commands are resumed, expired artifacts swept."""
        if self.runtime is not None:
            names = self.runtime.list_containers()
            failed = 0
            for name in names:
                ok = self.runtime.kill(name)
                ok = self.runtime.remove(name) and ok
                if not ok:
                    failed += 1
                    self.journal.kill_pending_add(name)
                    self._next_pending_ms = self.clock.now_ms() + KILL_RETRY_MS
            self.log.event("orphan_sweep", containers_checked=len(names), kill_failed=failed)
        for j in self.journal.open_jobs():
            self.journal.job_end(j["jobId"])
        if self.runtime is not None:
            for v in self.runtime.list_volumes():
                if v.startswith(VOLUME_PREFIX):
                    self.runtime.remove_volume(v)
        self._run_pending_cleanups()
        if self.expiry is not None:
            self.expiry.sweep()

    # ================================================================= cleanup commands and local cleanup
    def _run_pending_cleanups(self):
        for c in self.journal.pending_cleanups():
            if self._executor.execute(c["jobId"]):
                self.journal.cleanup_done(c["cleanupId"])
            else:
                self.log.event("cleanup_retry", local=c["local"])

    def _retry_pending_kills_if_due(self):
        now = self.clock.now_ms()
        if now >= self._next_pending_ms:
            self._next_pending_ms = now + KILL_RETRY_MS
            self._retry_pending_kills()

    def _retry_pending_kills(self):
        """Containers whose kill failed outside a held job (orphans at start, a container started after a denial): kill by name, success = gone."""
        if self.runtime is None:
            return
        keys = self.journal.kill_pending()
        if not keys:
            return
        job = self._job
        active = self._cname(job) if job is not None else None
        for key in keys:
            if key == active:
                continue  # the held job's own retry loop owns it
            try:
                self.runtime.kill(key)
                self.runtime.remove(key)
                gone = key not in self.runtime.list_containers()
            except Exception:
                gone = False
            if gone:
                self.journal.kill_done(key)

    def _cname(self, job):
        return getattr(job.handle, "name", None) or VOLUME_PREFIX + job.job_id

    def _process_cleanups(self, cmds):
        valid = [c for c in cmds if isinstance(c, dict) and isinstance(c.get("cleanupId"), str) and isinstance(c.get("jobId"), str)]
        for c in valid:  # persisted BEFORE any execution and before any ack
            self.journal.add_cleanup(c["cleanupId"], c["jobId"])
        job = self._job
        if job is not None and any(c["jobId"] in (job.job_id, "*") for c in valid):
            self._deny(job, "cleanup")  # a running job a cleanup names is killed first; its result (if any) is dropped
        self._run_pending_cleanups()
        if valid:
            self.log.event("cleanup_received", count=len(valid))

    # ================================================================= poll / lease
    def poll_once(self):
        self._run_pending_cleanups()
        self._retry_pending_kills_if_due()
        with self._lock:
            job = self._job
            active = [{"jobId": job.job_id, "leaseGen": job.lease_gen}] if job is not None and not job.ended else []
        acks = self.journal.acks_to_send()
        body = {"helperProtocol": 1, "active": active, "cleanupAcks": acks, "ready": {"py_exec": bool(self.ready())}}
        try:
            resp = self.client.poll(body)
        except NetworkError:
            self.log.event("poll", status=0)
            return "error"
        self.log.event("poll", status=resp.status, acks=len(acks))
        if resp.status == 401:
            self.session_lost = True
            if job is not None:
                self._deny(job, "unauthorized")
            return "session_lost"
        if resp.status == 403 and job is not None:
            self._deny(job, resp.code or "capability_revoked")
        if not resp.ok or not isinstance(resp.body, dict):
            return "error"
        self.journal.acked(acks)
        data = resp.body
        self._process_cleanups(data.get("cleanup") or [])
        job = self._job
        for jid in data.get("cancel") or []:
            if job is not None and job.job_id == jid:
                self._deny(job, "cancelled")
        if ("py_exec" in (data.get("available") or []) and self.ready() and self._job is None and not self.session_lost
                and not self.journal.pending_cleanups() and not self.journal.kill_pending()):
            # never lease while a cleanup (a `*` would hit the new job) or a kill is still pending
            self.lease_once()
        return "ok"

    def lease_once(self):
        with self._lock:
            if self._job is not None:
                return  # maxActiveJobs = 1: never a second lease request while a job is held
        send = self.clock.now_ms()
        try:
            resp = self.client.lease(["py_exec"])
        except NetworkError:
            self.log.event("lease", status=0)
            return
        self.log.event("lease", status=resp.status)
        if resp.status == 401:
            self.session_lost = True
            return
        if not resp.ok or not isinstance(resp.body, dict) or not resp.body.get("job"):
            return
        j, server = resp.body["job"], resp.body.get("serverTimeMs")
        if not self._lease_fields_ok(j, server):
            self.log.event("lease_refused", reason="bad_fields")
            return
        job = ActiveJob(job_id=j["jobId"], lease_gen=j["leaseGen"], bundle_hash=j["bundleHash"], payload_bytes=j["payloadBytes"], anchor=send,
                        lease_deadline=send + self.c["leaseMs"], job_deadline=send + (j["deadlineMs"] - server))
        with self._lock:
            if self._job is not None:
                return
            overdue = self._overdue(job, self.clock.now_ms())
            if not overdue:
                self._job = job
        if overdue:  # the answer came too late: the 3.5 s liveness window (from the SEND time) is already over; the phone's lease lapses by itself
            self.log.event("lease_refused", reason="overdue")
            return
        self.journal.add_job(job.job_id, VOLUME_PREFIX + job.job_id, str(self.store.job_stage_dir(job.job_id)),
                             str(self.store.job_stage_dir(job.job_id) / "export.tar"))
        self._fetch_and_start(job)

    def _overdue(self, job, now):
        return now > job.anchor + self.c["helperSelfKillMs"] or now > job.lease_deadline or now > job.job_deadline

    @staticmethod
    def _is_int(v):
        return isinstance(v, int) and not isinstance(v, bool)

    def _lease_fields_ok(self, j, server):
        return (isinstance(j, dict) and protocol.job_id_ok(j.get("jobId")) and j.get("type") == "py_exec" and self._is_int(j.get("leaseGen"))
                and self._is_int(j.get("deadlineMs")) and self._is_int(server) and self._is_int(j.get("payloadBytes"))
                and isinstance(j.get("bundleHash"), str) and re.fullmatch(r"[0-9a-f]{64}", j["bundleHash"]) is not None)

    def _fetch_and_start(self, job):
        try:
            resp = self.client.payload(job.job_id, job.lease_gen)
        except NetworkError:
            self.log.event("payload", status=0)
            self._abort(job, "payload_unreachable")
            return
        self.log.event("payload", status=resp.status)
        if resp.status in DENIAL_STATUSES:
            self._deny(job, resp.code or "denied")
            return
        if not resp.ok or not isinstance(resp.body, (bytes, bytearray)):
            self._abort(job, "payload_unavailable")
            return
        raw = bytes(resp.body)
        payload = self._verified_payload(job, raw)
        if payload is None:
            self._fail_job(job, "bad_output")
            return
        with self._lock:
            dead = job.killed or job.ended
            overdue = not dead and self._overdue(job, self.clock.now_ms())
        if dead:
            return  # denied, cancelled or killed during the payload fetch: the runner is never started for it
        if overdue:
            self._abort(job, "lease_overdue")
            return
        remaining = max(job.job_deadline - self.clock.now_ms(), 0)
        try:
            handle = self.runner.start({"jobId": job.job_id, "leaseGen": job.lease_gen, "bundleHash": job.bundle_hash, "remainingMs": remaining}, payload)
        except RunnerError as e:
            self._fail_job(job, e.code if e.code in constants()["failCodes"] else "runtime_error")
            return
        except Exception:
            self._fail_job(job, "runtime_error")
            return
        with self._lock:
            late = job.killed or job.ended
            if not late:
                job.handle = handle
        if late:  # killed or ended while the runner was starting: never leave the new container or its inputs behind
            ok = False
            try:
                ok = bool(handle.kill()) or bool(handle.force_remove())
            except Exception:
                ok = False
            if not ok:
                self.journal.kill_pending_add(getattr(handle, "name", None) or VOLUME_PREFIX + job.job_id)
                self._next_pending_ms = self.clock.now_ms() + KILL_RETRY_MS
                self.log.event("kill_failed")
            self.journal.job_end(job.job_id)  # a fresh local cleanup entry, retried every poll until the copies and the volume are gone
            self._run_pending_cleanups()

    def _verified_payload(self, job, raw):
        if len(raw) != job.payload_bytes or len(raw) > self.c["maxPayloadBytes"]:
            return None
        try:
            obj = json.loads(raw.decode("utf-8"))
            if protocol.bundle_hash(obj) != job.bundle_hash:
                return None
        except (ValueError, UnicodeDecodeError):
            return None
        return obj if protocol.validate_payload(obj) == [] else None

    # ================================================================= heartbeat
    def heartbeat_tick(self):
        """One tick of the fixed heartbeat timer; returns True when a beat was started. At most 2 beats in flight."""
        with self._lock:
            job = self._job
            if job is None or job.killed or job.ended or job.inflight >= 2:
                return False
            job.inflight += 1
            send = self.clock.now_ms()
        self.spawn(lambda: self._beat(job, send))
        return True

    def _beat(self, job, send):
        try:
            resp = self.client.heartbeat(job.job_id, job.lease_gen)
        except NetworkError:
            resp = None
        except BaseException:
            with self._lock:
                job.inflight -= 1
            raise
        deny = None
        with self._lock:
            job.inflight -= 1
            if resp is not None and not job.killed and not job.ended:
                if resp.ok:
                    job.anchor = max(job.anchor, send)  # the SEND time of the acked request, never its arrival time
                    job.lease_deadline = max(job.lease_deadline, send + self.c["leaseMs"])
                elif resp.status in DENIAL_STATUSES:
                    deny = resp.code or "denied"
        if deny is not None:
            self._deny(job, deny)

    # ================================================================= watchdog (its own thread; reads deadlines only)
    def watchdog_guard(self):
        """The watchdog thread's body: it must never die. An unexpected exception fails SAFE (every running job is killed and its copies deleted, the
        class name is logged, never the message) and the loop keeps running."""
        try:
            self.watchdog_check()
        except Exception as e:
            self.log.event("watchdog_error", cls=_snake(type(e).__name__))
            self._fail_safe()

    def _fail_safe(self):
        job = self._job
        if job is None:
            return
        try:
            self._deny(job, "watchdog_error")
            return
        except Exception:
            pass
        try:
            h = job.handle
            if h is not None and not h.kill():
                h.force_remove()
        except Exception:
            pass
        try:
            self.store.delete_job_copies(job.job_id)
        except Exception:
            pass
        with self._lock:
            job.killed = job.ended = True
            if self._job is job:
                self._job = None

    def watchdog_check(self):
        self._retry_pending_kills_if_due()
        reason, retry = None, False
        with self._lock:
            job = self._job
            if job is None or job.ended:
                return
            now = self.clock.now_ms()
            if job.killed:
                retry = (job.kill_pending or job.end_pending) and now >= job.next_kill_ms
            elif now > job.anchor + self.c["helperSelfKillMs"]:
                reason = "liveness"
            elif now > job.lease_deadline:
                reason = "lease_deadline"
            elif now > job.job_deadline:
                reason = "job_deadline"
            if reason:
                job.killed, job.kill_reason = True, reason
        if reason:
            self.log.event("kill", reason=reason)
            self._kill_and_end(job, reason)
        elif retry:
            self._kill_and_end(job, job.kill_reason or "retry")

    def _kill(self, job):
        """Kill by name (then rm -f); True when the container is confirmed gone. A failure is journaled and retried every KILL_RETRY_MS."""
        ok = True
        h = job.handle
        if h is not None:
            try:
                ok = bool(h.kill()) or bool(h.force_remove())
            except Exception:
                ok = False
        if ok:
            with self._lock:
                job.kill_pending = False
            self.journal.kill_done(self._cname(job))
            return True
        with self._lock:
            job.kill_pending = True
            job.next_kill_ms = self.clock.now_ms() + KILL_RETRY_MS
        self.journal.kill_pending_add(self._cname(job))
        self.log.event("kill_failed")
        return False

    def _kill_and_end(self, job, outcome):
        if self._kill(job):
            self._end_job(job, outcome)

    def _deny(self, job, code):
        """A denial, a cancel or a cleanup naming this job: kill at once, delete every copy, post nothing."""
        with self._lock:
            if job.ended or job.killed:
                return
            job.killed, job.kill_reason = True, code
        self.log.event("denied", code=code if re.fullmatch(r"[a-z_]{1,24}", code or "") else "other",
                       deletion_class=code in protocol.deletion_class_denials())
        self._kill_and_end(job, "denied")

    def _abort(self, job, reason):
        with self._lock:
            if job.ended or job.killed:
                return
            job.killed, job.kill_reason = True, reason
        self._kill_and_end(job, reason)

    def _end_job(self, job, outcome):
        with self._lock:
            if job.ended:
                return
        try:
            self.journal.job_end(job.job_id)  # the local cleanup entry is durable BEFORE the job is released
        except Exception as e:
            self.log.event("journal_error", cls=_snake(type(e).__name__))
            try:
                self.store.delete_job_copies(job.job_id)  # fallback: the inputs go now; the job stays held and the watchdog retries the journal write
            except Exception:
                pass
            with self._lock:
                job.killed = job.end_pending = True
                job.next_kill_ms = self.clock.now_ms() + KILL_RETRY_MS
            return
        with self._lock:
            if job.ended:
                return
            job.ended, job.end_pending = True, False
            if self._job is job:
                self._job = None
        self._run_pending_cleanups()
        self.log.event("job_end", outcome=outcome if re.fullmatch(r"[a-z_]{1,24}", outcome or "") else "other")

    # ================================================================= job completion (main loop)
    def _fail_job(self, job, code):
        """Post `fail` once (best effort) and end the job; used when the job never ran or the runner reported a failure."""
        self._kill(job)
        try:
            resp = self.client.fail(job.job_id, job.lease_gen, code)
            self.log.event("fail", status=resp.status)
        except NetworkError:
            self.log.event("fail", status=0)
        self._end_job(job, "failed")

    def job_step(self):
        with self._lock:
            job = self._job
            if job is None or job.ended or job.killed or job.handle is None or job.posting:
                return
            if self.clock.now_ms() < job.next_post_ms:
                return
            handle = job.handle
        if not handle.done():
            return
        out = handle.outcome() or {}
        if self.journal.has_pending_cleanup_for(job.job_id):
            self._deny(job, "cleanup")  # a finished result of a job the phone told us to forget is dropped
            return
        with self._lock:
            job.posting = True
        try:
            try:
                if out.get("kind") == "result":
                    self._post_result(job, out.get("result"))
                else:
                    code = out.get("code")
                    self._post_fail(job, code if code in constants()["failCodes"] else "runtime_error")
            except (ValueError, OSError) as e:
                # nothing in the post path may end the helper: fail the job safe, then make sure its copies are gone
                self.log.event("post_error", cls=_snake(type(e).__name__))
                self._fail_job_after_post_error(job)
        finally:
            with self._lock:
                job.posting = False

    def _fail_job_after_post_error(self, job):
        if job.ended:
            return
        try:
            resp = self.client.fail(job.job_id, job.lease_gen, "bad_output")
            self.log.event("fail", status=resp.status)
        except (NetworkError, ValueError, OSError):
            self.log.event("fail", status=0)
        self._kill(job)
        self._end_job(job, "failed")

    def _retry_later(self, job):
        with self._lock:
            job.next_post_ms = self.clock.now_ms() + RESULT_RETRY_MS

    def _post_result(self, job, result):
        try:
            canon = protocol.canonical_json(result)
        except ValueError:
            self._post_fail(job, "bad_output")
            return
        if len(canon.encode("utf-8")) > self.c["maxResultBytes"]:
            self._post_fail(job, "too_large")
            return
        h = protocol.bundle_hash(result)
        try:
            resp = self.client.result(job.job_id, job.lease_gen, h, result)
        except NetworkError:
            self.log.event("result", status=0)
            self._retry_later(job)
            return
        except BodyTooLarge:
            self.log.event("result", status=0, too_large=True)
            self._post_fail(job, "too_large")
            return
        self.log.event("result", status=resp.status)
        if resp.ok:
            self._end_job(job, "done")  # committed or duplicate: inputs and outputs removed at once
        elif resp.status in DENIAL_STATUSES:
            self._deny(job, resp.code or "denied")
        elif resp.status == 413:
            self._post_fail(job, "too_large")
        elif resp.status == 422:
            self._post_fail(job, "bad_output")
        else:
            self._retry_later(job)

    def _post_fail(self, job, code):
        try:
            resp = self.client.fail(job.job_id, job.lease_gen, code)
        except NetworkError:
            self.log.event("fail", status=0)
            self._retry_later(job)
            return
        self.log.event("fail", status=resp.status)
        if resp.ok or resp.status not in DENIAL_STATUSES:
            self._end_job(job, "failed")
        else:
            self._deny(job, resp.code or "denied")

    # ================================================================= main loop
    def maintenance(self):
        now = self.clock.now_ms()
        if self._last_maintenance is None or now - self._last_maintenance >= MAINTENANCE_INTERVAL_MS:
            self._last_maintenance = now
            if self.expiry is not None:
                self.expiry.sweep()

    def shutdown(self, stop=None, unbounded=False):
        """The helper process ends: a running job is killed and its copies deleted (the phone fails the lease and queues its own cleanup). A failed
        kill is retried (watchdog rules, every 1 s) until the job is gone: for 10 s on an ordinary shutdown, until `stop` after a lost session."""
        job = self._job
        if job is not None:
            self._deny(job, "shutdown")
        deadline = time.monotonic() + 10
        while self._job is not None:
            if unbounded:
                if stop is not None and stop.is_set():
                    break
            elif time.monotonic() > deadline:
                break
            self.watchdog_guard()
            threading.Event().wait(0.1)
        if self._job is not None:
            self.log.event("shutdown_job_left", pending=len(self.journal.kill_pending()))  # still journaled: the next start kills it

    def run(self, stop, max_cycles=None):
        """Recover, then poll every pollIntervalIdleMs; heartbeat and watchdog run on their own threads. Returns 'stopped', 'session_lost' or the
        last poll status when max_cycles polls were done."""
        stop_threads = threading.Event()
        hb_s = self.c["heartbeatIntervalMs"] / 1000.0

        def beat_loop():
            while not stop_threads.is_set():
                try:
                    self.heartbeat_tick()
                except Exception as e:
                    self.log.event("heartbeat_error", cls=_snake(type(e).__name__))
                stop_threads.wait(hb_s)

        def watch_loop():
            while not stop_threads.is_set():
                self.watchdog_guard()
                stop_threads.wait(WATCHDOG_TICK_S)

        threads = [threading.Thread(target=beat_loop, daemon=True), threading.Thread(target=watch_loop, daemon=True)]
        status, cycles, next_poll = "stopped", 0, 0
        try:
            self.recover()
            for t in threads:
                t.start()
            while not stop.is_set():
                now = self.clock.now_ms()
                self.maintenance()
                if now >= next_poll:
                    status = self.poll_once()
                    cycles += 1
                    next_poll = self.clock.now_ms() + self.c["pollIntervalIdleMs"]
                    if status == "session_lost" or (max_cycles is not None and cycles >= max_cycles):
                        break
                self.job_step()
                stop.wait(0.1)
        finally:
            self.shutdown(stop, unbounded=(status == "session_lost"))
            stop_threads.set()
            for t in threads:
                if t.is_alive():
                    t.join(5)
        return status
