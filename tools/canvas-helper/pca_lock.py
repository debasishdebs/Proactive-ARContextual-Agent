#!/usr/bin/env python3
"""Admission lock of core plan section 8.1 (WP10). Python stdlib only; every lock is a non-blocking flock under PCA_LOCK_DIR
(default ~/.pca-helper/locks); a refusal is exit 75 `resources_busy`; nothing ever waits, so nothing deadlocks.

* host.lock: a host Gradle build takes it SHARED, the VM session takes it EXCLUSIVE. While holding the shared admission the build also requires the
  named Podman machine to be stopped (`podman machine inspect`): running, starting or unknown = refused (covers a VM left up by a crashed launcher,
  whose flock the kernel already dropped). A machine that explicitly does not exist cannot be running and is admitted.
* slot-1.lock / slot-2.lock: a build also holds one slot exclusively for its lifetime; a third build is refused.
* vm-session: a long-lived OWNER process; it takes host.lock exclusive before the first machine step, holds it across every step, every gap and the
  idle timeout, and releases it only after `podman machine stop` is confirmed (state stopped). A failed stop keeps the owner alive and the lock held,
  reports `vm_stuck` on its control channel (stdout) and stderr and retries until the state is confirmed stopped (also when stopped by hand).
  Control channel: stdin lines `exec <argv...>` (runs a step, prints `rc <n>`), `status`, `stop`; EOF or idle timeout acts as `stop`.
* job.lock: one exclusive lock serializes helper jobs; independent of the build slots.
Environment: PCA_LOCK_DIR, PCA_PODMAN (default podman), PCA_MACHINE (default pca), PCA_STOP_RETRY_S, PCA_VM_IDLE_S (default 600).
"""
import fcntl
import json
import os
import shlex
import subprocess
import sys
import time

EXIT_BUSY = 75
PODMAN_TIMEOUT_S = 60


class Busy(Exception):
    def __init__(self, reason):
        super().__init__("resources_busy: " + reason)
        self.reason = reason


def lock_dir():
    d = os.environ.get("PCA_LOCK_DIR") or os.path.join(os.path.expanduser("~"), ".pca-helper", "locks")
    os.makedirs(d, mode=0o700, exist_ok=True)
    return d


def machine_name():
    return os.environ.get("PCA_MACHINE") or "pca"


def _podman():
    return os.environ.get("PCA_PODMAN") or "podman"


class FileLock:
    def __init__(self, name):
        self.path = os.path.join(lock_dir(), name)
        self.fd = None

    def try_acquire(self, exclusive):
        fd = os.open(self.path, os.O_RDWR | os.O_CREAT, 0o600)
        try:
            fcntl.flock(fd, (fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH) | fcntl.LOCK_NB)
        except OSError:
            os.close(fd)
            return False
        self.fd = fd
        return True

    @property
    def held(self):
        return self.fd is not None

    def release(self):
        if self.fd is not None:
            try:
                fcntl.flock(self.fd, fcntl.LOCK_UN)
            finally:
                os.close(self.fd)
                self.fd = None


def machine_state(name=None):
    """stopped | running | starting | absent (machine explicitly does not exist) | unknown (anything else)."""
    try:
        p = subprocess.run([_podman(), "machine", "inspect", name or machine_name()], capture_output=True, text=True, timeout=PODMAN_TIMEOUT_S)
    except (OSError, subprocess.SubprocessError):
        return "unknown"
    if p.returncode != 0:
        low = (p.stderr or "").lower()
        return "absent" if ("does not exist" in low or "no such" in low or "not found" in low) else "unknown"
    try:
        data = json.loads(p.stdout)
        if isinstance(data, list):
            data = data[0]
        state = str(data["State"]).strip().lower()
    except (ValueError, KeyError, IndexError, TypeError):
        return "unknown"
    return state if state in ("stopped", "running", "starting") else "unknown"


class BuildAdmission:
    def __init__(self, host, slot_lock, slot):
        self._host, self._slot, self.slot = host, slot_lock, slot

    def release(self):
        self._slot.release()
        self._host.release()


def admit_build():
    host = FileLock("host.lock")
    if not host.try_acquire(exclusive=False):
        raise Busy("vm session holds host.lock")
    slot_lock, slot = None, None
    for n in (1, 2):
        cand = FileLock("slot-%d.lock" % n)
        if cand.try_acquire(exclusive=True):
            slot_lock, slot = cand, n
            break
    if slot_lock is None:
        host.release()
        raise Busy("both build slots are taken")
    state = machine_state()
    if state not in ("stopped", "absent"):
        slot_lock.release()
        host.release()
        raise Busy("podman machine state is %s" % state)
    return BuildAdmission(host, slot_lock, slot)


class JobLock:
    def __init__(self):
        self._lock = FileLock("job.lock")

    def acquire(self):
        if not self._lock.try_acquire(exclusive=True):
            raise Busy("another helper job holds job.lock")

    def release(self):
        self._lock.release()


class VmSession:
    def __init__(self):
        self._host = FileLock("host.lock")
        self.stuck = False

    @property
    def held(self):
        return self._host.held

    def acquire(self):
        if not self._host.try_acquire(exclusive=True):
            raise Busy("a host build or another vm session holds host.lock")

    def release(self):
        self._host.release()

    def _fd_close_without_stop(self):
        """Test hook: what process death does (the kernel drops the flock, the VM is left as it is)."""
        self._host.release()

    def run_step(self, argv):
        if not self.held:
            raise RuntimeError("vm-session does not hold host.lock")
        env = dict(os.environ)
        env["PCA_VM_SESSION"] = "1"
        return subprocess.run(argv, env=env).returncode

    def stop_and_release(self, max_attempts=None):
        """Stop the machine and release host.lock only after the state is confirmed not running. On failure keep the lock, report vm_stuck and
        retry (max_attempts None = until recovered). Returns True when released."""
        attempts = 0
        while True:
            if machine_state() in ("stopped", "absent"):  # also covers a stop done by hand
                self.stuck = False
                self.release()
                return True
            if max_attempts is not None and attempts >= max_attempts:
                return False
            attempts += 1
            try:
                subprocess.run([_podman(), "machine", "stop", machine_name()], capture_output=True, text=True, timeout=PODMAN_TIMEOUT_S)
            except (OSError, subprocess.SubprocessError):
                pass
            if machine_state() in ("stopped", "absent"):
                self.stuck = False
                self.release()
                return True
            self.stuck = True
            sys.stderr.write("vm_stuck: podman machine %s is not confirmed stopped; holding host.lock\n" % machine_name())
            sys.stderr.flush()
            if max_attempts is None or attempts < max_attempts:
                time.sleep(float(os.environ.get("PCA_STOP_RETRY_S", "5")))


def check_owner():
    """0 when some process holds host.lock exclusively (a vm-session owner), 75 otherwise."""
    probe = FileLock("host.lock")
    if probe.try_acquire(exclusive=False):
        probe.release()
        return EXIT_BUSY
    return 0


def _busy_exit(e):
    sys.stderr.write(str(e) + "\n")
    return EXIT_BUSY


def _owner_loop(s, stdin, out):
    import select
    idle = float(os.environ.get("PCA_VM_IDLE_S", "600"))
    out.write("ready\n")
    out.flush()
    while True:
        ready, _, _ = select.select([stdin], [], [], idle)
        line = stdin.readline() if ready else ""
        line = line.strip()
        if not line or line == "stop":
            break
        if line == "status":
            out.write("state %s\n" % machine_state())
        elif line.startswith("exec "):
            out.write("rc %d\n" % s.run_step(shlex.split(line[5:])))
        else:
            out.write("error unknown command\n")
        out.flush()
    stuck_reported = False
    while not s.stop_and_release(max_attempts=1):
        if not stuck_reported:
            out.write("vm_stuck\n")
            out.flush()
            stuck_reported = True
        time.sleep(float(os.environ.get("PCA_STOP_RETRY_S", "5")))
    out.write("stopped\n")
    out.flush()
    return 0


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        sys.stderr.write("usage: pca_lock.py build|job -- cmd... | vm-session | check-owner | state\n")
        return 2
    cmd, rest = argv[0], argv[1:]
    if cmd in ("build", "job"):
        if rest[:1] == ["--"]:
            rest = rest[1:]
        if not rest:
            sys.stderr.write("no command\n")
            return 2
        try:
            if cmd == "build":
                held = admit_build()
            else:
                held = JobLock()
                held.acquire()
        except Busy as e:
            return _busy_exit(e)
        try:
            return subprocess.run(rest).returncode
        finally:
            held.release()
    if cmd == "vm-session":
        s = VmSession()
        try:
            s.acquire()
        except Busy as e:
            return _busy_exit(e)
        return _owner_loop(s, sys.stdin, sys.stdout)
    if cmd == "check-owner":
        rc = check_owner()
        if rc:
            sys.stderr.write("resources_busy: no vm-session owner holds host.lock\n")
        return rc
    if cmd == "state":
        print(machine_state())
        return 0
    sys.stderr.write("unknown command\n")
    return 2


if __name__ == "__main__":
    sys.exit(main())
