"""Append-only, fsync'd journal (~/.pca-helper/journal/journal.jsonl, 0600): open jobs with their volumes / stage dirs / export paths,
cleanup commands (persisted BEFORE anything else, acked only after the files are gone), kill-pending entries and retained-artifact expiry metadata.
State is rebuilt by replaying the file; a torn last line (crash mid-write) is ignored and terminated before the next append. Holds no token."""
import json
import os
import re
import threading

from .protocol import constants
from .store import fsync_fd

VOLUME_PREFIX = "pca-job-"


class Journal:
    def __init__(self, directory, clock):
        self._clock = clock
        self._lock = threading.RLock()
        self.path = os.path.join(str(directory), "journal.jsonl")
        self._jobs, self._cleanups, self._kill, self._artifacts = {}, {}, {}, {}
        self._needs_newline = False
        self._load()
        if not os.path.exists(self.path):
            fd = os.open(self.path, os.O_WRONLY | os.O_CREAT, 0o600)
            os.close(fd)
            dfd = os.open(os.path.dirname(self.path), os.O_RDONLY)
            try:
                fsync_fd(dfd)
            finally:
                os.close(dfd)
        os.chmod(self.path, 0o600)

    # ------------------------------------------------------------ persistence
    def _load(self):
        try:
            with open(self.path, "rb") as f:
                data = f.read()
        except FileNotFoundError:
            return
        self._needs_newline = bool(data) and not data.endswith(b"\n")
        for line in data.split(b"\n"):
            if not line.strip():
                continue
            try:
                self._apply(json.loads(line.decode("utf-8")))
            except (ValueError, UnicodeDecodeError, KeyError, TypeError):
                continue  # torn or foreign line

    @staticmethod
    def _write_all(fd, data):
        view = memoryview(data)
        while len(view):
            n = os.write(fd, view)
            view = view[n:]

    def _append(self, rec):
        """Memory is updated ONLY after the whole record was written and fsync'd: a failed append leaves the bookkeeping exactly as it was, so a
        retry persists it again (nothing is skipped because memory already looked done). After any failure the next append starts on a fresh line."""
        line = (json.dumps(rec, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")
        with self._lock:
            fd = os.open(self.path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
            try:
                try:
                    if self._needs_newline:
                        self._write_all(fd, b"\n")
                        self._needs_newline = False
                    self._write_all(fd, line)
                    fsync_fd(fd)
                except BaseException:
                    self._needs_newline = True  # a partial line may be on disk; replay skips it, the next record must not glue onto it
                    raise
            finally:
                os.close(fd)
            self._apply(rec)

    def _apply(self, r):
        t = r["t"]
        if t == "job":
            self._jobs[r["jobId"]] = {"jobId": r["jobId"], "volume": r["volume"], "stage": r["stage"], "export": r["export"], "open": True,
                                      "volumes": [r["volume"]]}
        elif t == "volume":
            j = self._jobs.setdefault(r["jobId"], {"jobId": r["jobId"], "volume": r["name"], "stage": "", "export": "", "open": False, "volumes": []})
            if r["name"] not in j["volumes"]:
                j["volumes"].append(r["name"])
        elif t == "job_end":
            if r["jobId"] in self._jobs:
                self._jobs[r["jobId"]]["open"] = False
        elif t == "job_purged":
            self._jobs.pop(r["jobId"], None)
        elif t == "cleanup":
            self._cleanups.setdefault(r["cleanupId"], {"cleanupId": r["cleanupId"], "jobId": r["jobId"], "local": bool(r.get("local")), "status": "pending"})
        elif t == "cleanup_done":
            if r["cleanupId"] in self._cleanups:
                self._cleanups[r["cleanupId"]]["status"] = "done"
        elif t == "cleanup_acked":
            if r["cleanupId"] in self._cleanups:
                self._cleanups[r["cleanupId"]]["status"] = "acked"
        elif t == "cleanup_redelivered":
            if r["cleanupId"] in self._cleanups and self._cleanups[r["cleanupId"]]["status"] == "acked":
                self._cleanups[r["cleanupId"]]["status"] = "done"
        elif t == "kill_pending":
            self._kill[r["key"]] = True
        elif t == "kill_done":
            self._kill.pop(r["key"], None)
        elif t == "artifact":
            self._artifacts[r["id"]] = {"id": r["id"], "path": r["path"], "jobId": r.get("jobId"), "createdAtMs": r["createdAtMs"],
                                        "expiresAtMs": r["expiresAtMs"]}
        elif t == "artifact_removed":
            self._artifacts.pop(r["id"], None)

    # ------------------------------------------------------------ jobs
    def add_job(self, job_id, volume, stage, export):
        self._append({"t": "job", "jobId": job_id, "volume": volume, "stage": stage, "export": export})

    def add_volume(self, job_id, name):
        """Called by the runtime BEFORE it creates a volume, so a crash leaves a journal entry that drives its removal."""
        self._append({"t": "volume", "jobId": job_id, "name": name})

    def job_end(self, job_id):
        """Terminal outcome: the job is closed and a local cleanup entry (no phone ack) enqueues the removal of every copy."""
        with self._lock:
            cid = "local:" + job_id
            if cid in self._cleanups and self._cleanups[cid]["status"] != "pending":
                cid = "%s:%d" % (cid, len(self._cleanups))
            self.add_cleanup(cid, job_id, local=True)  # the cleanup is durable FIRST: a crash between the two records leaves it pending
            if job_id in self._jobs and self._jobs[job_id]["open"]:
                self._append({"t": "job_end", "jobId": job_id})

    def purge_job(self, job_id):
        if job_id in self._jobs:
            self._append({"t": "job_purged", "jobId": job_id})

    def open_jobs(self):
        with self._lock:
            return [dict(j) for j in self._jobs.values() if j["open"]]

    def known_job_ids(self):
        with self._lock:
            return list(self._jobs)

    def job_volumes(self, job_id):
        with self._lock:
            return list(self._jobs.get(job_id, {}).get("volumes", []))

    def all_volumes(self):
        with self._lock:
            return [v for j in self._jobs.values() for v in j["volumes"]]

    # ------------------------------------------------------------ cleanup commands and acks
    def add_cleanup(self, cleanup_id, job_id, local=False):
        with self._lock:
            c = self._cleanups.get(cleanup_id)
            if c is None:
                self._append({"t": "cleanup", "cleanupId": cleanup_id, "jobId": job_id, "local": local})
            elif c["status"] == "acked":
                self._append({"t": "cleanup_redelivered", "cleanupId": cleanup_id})  # lost ack: the phone resent it, ack it again

    def pending_cleanups(self):
        with self._lock:
            return [dict(c) for c in self._cleanups.values() if c["status"] == "pending"]

    def has_pending_cleanup_for(self, job_id):
        with self._lock:
            return any(c["status"] == "pending" and c["jobId"] in (job_id, "*") for c in self._cleanups.values())

    def cleanup_done(self, cleanup_id):
        with self._lock:
            c = self._cleanups.get(cleanup_id)
            if c is None:
                return
            self._append({"t": "cleanup_done", "cleanupId": cleanup_id})
            if c["local"]:
                self._append({"t": "cleanup_acked", "cleanupId": cleanup_id})  # nothing to tell the phone

    def acks_to_send(self):
        with self._lock:
            return [c["cleanupId"] for c in self._cleanups.values() if c["status"] == "done" and not c["local"]]

    def acked(self, ids):
        with self._lock:
            for i in ids:
                if i in self._cleanups and self._cleanups[i]["status"] == "done":
                    self._append({"t": "cleanup_acked", "cleanupId": i})

    # ------------------------------------------------------------ kill retries
    def kill_pending_add(self, key):
        self._append({"t": "kill_pending", "key": key})

    def kill_done(self, key):
        if key in self._kill:
            self._append({"t": "kill_done", "key": key})

    def kill_pending(self):
        with self._lock:
            return list(self._kill)

    # ------------------------------------------------------------ retained artifacts (expiry.py)
    def add_artifact(self, art_id, path, job_id, created_ms, expires_ms):
        self._append({"t": "artifact", "id": art_id, "path": path, "jobId": job_id, "createdAtMs": created_ms, "expiresAtMs": expires_ms})

    def artifact_removed(self, art_id):
        if art_id in self._artifacts:
            self._append({"t": "artifact_removed", "id": art_id})

    def artifacts(self):
        with self._lock:
            return [dict(a) for a in self._artifacts.values()]


class CleanupExecutor:
    """Executes one cleanup target (a job id, or `*` for all jobs) idempotently: stage dir, artifacts dir, retained artifacts, volumes.
    Returns True only when everything is gone. `runtime` is the runtime interface (WP12); None = no runtime, so no volume can exist."""

    def __init__(self, store, journal, runtime=None):
        self.store, self.journal, self.runtime = store, journal, runtime

    def _remove_volumes(self, names):
        ok = True
        if self.runtime is None:
            return True
        for n in names:
            try:
                ok = bool(self.runtime.remove_volume(n)) and ok
            except Exception:
                ok = False
        return ok

    def _remove_artifacts(self, job_id):
        ok = True
        for a in self.journal.artifacts():
            if job_id == "*" or a["jobId"] == job_id:
                if self.store.remove_path(a["path"]):
                    self.journal.artifact_removed(a["id"])
                else:
                    ok = False
        return ok

    def execute(self, job_id):
        rx = re.compile(constants()["jobIdRegex"])
        if job_id == "*":
            ids = set(self.journal.known_job_ids()) | set(self.store.job_dir_names())
            ok = True
            for i in sorted(ids):
                if rx.fullmatch(i):
                    ok = self.store.delete_job_copies(i) and ok
            names = set(self.journal.all_volumes())
            if self.runtime is not None:
                names |= {v for v in self.runtime.list_volumes() if v.startswith(VOLUME_PREFIX)}
            ok = self._remove_volumes(sorted(names)) and ok
            ok = self._remove_artifacts("*") and ok
            if ok:
                for i in ids:
                    self.journal.purge_job(i)
            return ok
        if not isinstance(job_id, str) or rx.fullmatch(job_id) is None:
            return True  # an id that cannot name a path or a volume has nothing on this machine
        ok = self.store.delete_job_copies(job_id)
        ok = self._remove_volumes(sorted(set(self.journal.job_volumes(job_id)) | {VOLUME_PREFIX + job_id})) and ok
        ok = self._remove_artifacts(job_id) and ok
        if ok:
            self.journal.purge_job(job_id)
        return ok
