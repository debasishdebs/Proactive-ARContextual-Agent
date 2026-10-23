"""The ONE runner argv builder (core plan section 2) and the runner that plugs into the supervisor (class `JobRunner`, bottom of the file).

`build_argv` is the only place a `podman run` argument list is assembled, for every row of the profile table; `verify_argv` re-checks the finished
list (flags present, nothing privileged or host-wide, every bind source under /stage/<jobId>/, exactly one named volume pca-job-<jobId>:/out)
before anything is executed. A job id that podman cannot use in a container or volume name is refused.
"""
import importlib.util
import json
import os
import re
import threading
from pathlib import Path

from . import profiles, protocol
from .supervisor import RunnerError

STAGE_ROOT = "/stage"  # the machine's ONE mount (the VM-side path of $HOME/.pca-helper/stage)
NAME_PREFIX = "pca-job-"
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")  # the colon of the protocol id regex is not valid in a podman volume name

# One list of flags every container row gets; the argv test and `verify_argv` both read it.
BASE_FLAGS = ["--rm", "--read-only", "--cap-drop=ALL", "--security-opt", "no-new-privileges", "--pull", "never"]
REQUIRED = ("--rm", "--read-only", "--cap-drop=ALL", "--security-opt", "--user", "--pids-limit", "--memory", "--memory-swap", "--cpus",
            "--tmpfs", "--timeout", "--network", "--name")
FORBIDDEN_FLAGS = frozenset(("--privileged", "--net", "--pid", "--userns", "--ipc", "--uts", "--device", "--cap-add", "--env", "-e", "--env-file",
                             "--group-add", "--mount", "--volume", "--volumes-from", "--add-host", "--cgroupns", "--sysctl", "--entrypoint",
                             "--security-opt=", "--network=", "--user=", "--security-opts"))


class ArgvRefused(Exception):
    pass


def safe_job_id(job_id):
    if not isinstance(job_id, str) or SAFE_ID.fullmatch(job_id) is None:
        raise ArgvRefused("job id cannot name a container")
    return job_id


def volume_name(job_id):
    return NAME_PREFIX + safe_job_id(job_id)


def stage_dir(job_id):
    return "%s/%s" % (STAGE_ROOT, safe_job_id(job_id))


def build_argv(profile, name, *, binds=(), volume=None, timeout_s, labels=None, command=(), image=None):
    """`binds`: (source, target, "ro") tuples (VM paths); `volume`: (name, target) of the one rw named volume or None. Returns the args after `podman`."""
    img = image or profile.image
    if not img:
        raise ArgvRefused("profile has no image")
    a = ["run"] + list(BASE_FLAGS)
    a += ["--name", name]
    for k, v in sorted((labels or {}).items()):
        a += ["--label", "%s=%s" % (k, v)]
    a += ["--user", profile.user, "--pids-limit", str(profile.pids_limit), "--memory", "%dm" % profile.memory_mb,
          "--memory-swap", "%dm" % profile.memory_swap_mb, "--cpus", str(profile.cpus),
          "--tmpfs", "/tmp:size=%dm,nosuid%s" % (profile.tmpfs_mb, ",noexec" if profile.tmpfs_noexec else ""),
          "--network", profile.network, "--log-driver", "k8s-file", "--log-opt", "max-size=%d" % profile.log_max_bytes,
          "--timeout", str(max(int(timeout_s), 1))]
    for src, dst, mode in binds:
        a += ["-v", "%s:%s:%s" % (src, dst, mode)]
    if volume is not None:
        a += ["-v", "%s:%s:rw" % volume]
    a.append(img)
    a += list(command)
    return a


def job_timeout_s(profile, remaining_ms):
    """min(profile deadline, remaining job deadline) + 2 s; the helper's own kill is the primary bound, this one outlives a dead helper."""
    return min(profile.deadline_s, max(int(remaining_ms), 0) // 1000) + 2


def build_job_argv(job_id, *, remaining_ms, table=None):
    t = table or profiles.load_table()
    p = t.job_profile("py_exec")
    name = volume_name(job_id)
    sd = stage_dir(job_id)
    a = build_argv(p, name, binds=[(sd + "/in", "/in", "ro")], volume=(name, "/out"), timeout_s=job_timeout_s(p, remaining_ms),
                   labels={"pca.helper": "1", "pca.job": job_id})
    verify_argv(a, job_id)
    return a


def verify_argv(a, job_id):
    """Refuse any argv that is not exactly a sane job run. Raises ArgvRefused."""
    safe_job_id(job_id)
    if not a or a[0] != "run":
        raise ArgvRefused("not a run")
    for r in REQUIRED:
        if a.count(r) != 1:
            raise ArgvRefused("missing or repeated " + r)
    if "no-new-privileges" not in a or any(t.startswith("seccomp=") or t.startswith("apparmor=") for t in a):
        raise ArgvRefused("security-opt")
    binds, vols, i = [], [], 1
    while i < len(a):
        t = a[i]
        if t == "-v":
            if i + 1 >= len(a):
                raise ArgvRefused("dangling -v")
            parts = a[i + 1].split(":")
            if len(parts) != 3:
                raise ArgvRefused("mount syntax")
            (binds if parts[0].startswith("/") else vols).append(tuple(parts))
            i += 2
            continue
        if t.split("=")[0] in FORBIDDEN_FLAGS or t.startswith(("--network=", "--security-opt=", "--user=")):
            raise ArgvRefused("forbidden " + t.split("=")[0])
        i += 1
    prefix = "%s/%s/" % (STAGE_ROOT, job_id)
    for src, dst, mode in binds:
        parts = src.split("/")
        if not src.startswith(prefix) or ".." in parts or "." in parts or "" in parts[1:] or "ro" not in mode.split(","):
            raise ArgvRefused("bind source outside the job subtree or not read-only")
    if vols != [(NAME_PREFIX + job_id, "/out", "rw")]:
        raise ArgvRefused("the only named volume is pca-job-<jobId>:/out")
    if a[a.index("--network") + 1] in ("host", "bridge", "slirp4netns", "pasta") or a[a.index("--user") + 1] in ("0", "root", ""):
        raise ArgvRefused("network or user")


# ====================================================================== the runner the supervisor calls
GIB = 1 << 30
# podman / container exit codes seen from `podman run` (the live gate confirms them: timeout, oom)
EXIT_CODES = {124: "timeout", 137: "oom"}


def _load_lock_module():
    spec = importlib.util.spec_from_file_location("pca_lock", str(Path(__file__).resolve().parent.parent / "pca_lock.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def default_glue(files):
    """outputs.json -> the result object (WP13 replaces this with the schema-checked job glue)."""
    raw = files.get("outputs.json")
    if raw is None:
        return {"kind": "fail", "code": "bad_output"}
    try:
        obj = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return {"kind": "fail", "code": "bad_output"}
    return {"kind": "result", "result": obj} if isinstance(obj, dict) else {"kind": "fail", "code": "bad_output"}


def preflight(probes, table, stage_dir):
    """None, or the fail code: disk_low (host free disk below the table minimum), resources_busy (host memory pressure)."""
    if probes.free_disk_bytes(str(stage_dir)) < table.host["minFreeDiskGb"] * GIB:
        return "disk_low"
    free = probes.memory_free_percent()
    if free is not None and free < table.host["minMemoryFreePercent"]:
        return "resources_busy"
    return None


class JobHandle:
    """What the supervisor holds: kill / force_remove / done / outcome. A worker thread waits for the container, exports, collects and cleans up the
    host-side outputs; the supervisor never waits on it."""

    def __init__(self, name, job_id, runtime, workspace, proc, lock, glue, log):
        self.name, self.job_id = name, job_id
        self._rt, self._ws, self._proc, self._lock, self._glue, self._log = runtime, workspace, proc, lock, glue, log
        self.cancelled = False
        self._cancel = threading.Event()
        self._outcome = None
        self._done = threading.Event()
        self._t = threading.Thread(target=self._work, daemon=True)

    def begin(self):
        self._t.start()

    def _work(self):
        outcome = None
        try:
            rc = self._proc.wait()
            if self.cancelled:
                outcome = {"kind": "fail", "code": "killed"}
            elif rc != 0:
                outcome = {"kind": "fail", "code": EXIT_CODES.get(rc, "runtime_error")}
            else:
                try:
                    files = self._ws.export_and_collect(self.job_id, self._cancel)
                    outcome = {"kind": "fail", "code": "killed"} if self.cancelled else self._glue(files)
                except Exception as e:
                    outcome = {"kind": "fail", "code": getattr(e, "code", "runtime_error")}
        except Exception:
            outcome = {"kind": "fail", "code": "runtime_error"}
        finally:
            try:
                if outcome is None or outcome.get("kind") != "result":
                    self._ws.abandon(self.job_id)  # no result: the volume and every transient file go now
                else:
                    self._ws.remove_transient(self.job_id)  # the result is in memory; its on-disk copies go before it can be posted
            except Exception:
                pass
            self._outcome = outcome or {"kind": "fail", "code": "runtime_error"}
            try:
                self._lock.release()
            finally:
                self._done.set()

    JOIN_S = 2.0  # bound of the wait for the worker to become quiescent

    def _quiesce(self):
        """Cancel export/collection and wait (bounded) for the worker: True only when it has ended, so no late write can follow a cleanup."""
        self.cancelled = True
        self._cancel.set()
        self._t.join(self.JOIN_S)
        return not self._t.is_alive()

    def kill(self):
        self.cancelled = True
        self._cancel.set()
        self._rt.kill(self.name)
        self._rt.remove(self.name)
        try:
            if self._proc.poll() is None:
                self._proc.terminate()  # the local `podman run` client only
        except OSError:
            pass
        gone = not self._rt.container_exists(self.name)
        return self._quiesce() and gone

    def force_remove(self):
        self.cancelled = True
        self._cancel.set()
        self._rt.remove(self.name)
        gone = not self._rt.container_exists(self.name)
        return self._quiesce() and gone

    def done(self):
        return self._done.is_set()

    def outcome(self):
        return self._outcome


class JobRunner:
    """`start(job, payload) -> JobHandle` (the supervisor's runner interface). Every refusal is a RunnerError with a protocol fail code; nothing here
    can run job code anywhere but in the container the one argv builder describes."""

    def __init__(self, runtime, store, journal, table, clock, log, probes=None, lock_factory=None, glue=None):
        self.runtime, self.store, self.journal, self.table, self.clock, self.log = runtime, store, journal, table, clock, log
        self._probes, self._lock_factory, self._glue = probes, lock_factory, glue or default_glue

    def _make_lock(self):
        if self._lock_factory is not None:
            return self._lock_factory()
        return _load_lock_module().JobLock()

    def start(self, job, payload):
        from .doctor import SystemProbes
        from .export import Workspace, WorkspaceFail
        from .runtime import NeedsRuntime
        if self.runtime is None:
            raise RunnerError("needs_runtime")  # no runtime = no job; never a host process
        job_id = job["jobId"]
        try:
            safe_job_id(job_id)
        except ArgvRefused:
            raise RunnerError("runtime_error")
        try:
            self.runtime.require_running()
        except NeedsRuntime:
            raise RunnerError("needs_runtime")
        probes = self._probes or SystemProbes()
        profile = self.table.job_profile("py_exec")
        code = preflight(probes, self.table, self.store.stage_dir)
        if code:
            raise RunnerError(code)
        lock = self._make_lock()
        try:
            lock.acquire()
        except Exception:
            raise RunnerError("resources_busy")  # another helper job holds job.lock
        ws = Workspace(self.runtime, self.store, self.journal, profile, probes, self.table.host, self.log)
        try:
            try:
                ws.create(job_id, protocol.canonical_json(payload).encode("utf-8"))
                argv = build_job_argv(job_id, remaining_ms=job.get("remainingMs", 0), table=self.table)
                proc = self.runtime.popen_run(argv)
            except (WorkspaceFail, ArgvRefused, OSError) as e:
                ws.abandon(job_id)
                raise RunnerError(getattr(e, "code", "runtime_error") if getattr(e, "code", None) in ("runtime_error", "disk_low") else "runtime_error")
        except BaseException:
            lock.release()
            raise
        glue = self._glue.for_job(payload) if hasattr(self._glue, "for_job") else self._glue  # WP13: a glue that needs the payload (item ids, outSchema)
        h = JobHandle(volume_name(job_id), job_id, self.runtime, ws, proc, lock, glue, self.log)
        h.begin()
        return h
