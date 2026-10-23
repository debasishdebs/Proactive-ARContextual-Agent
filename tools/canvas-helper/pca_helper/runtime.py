"""The one container runtime (core plan section 2; part-C H5/H6): rootless Podman through ONE named machine connection.

* NO host fallback and NO PATH lookup: the podman executable is the absolute path written to ~/.pca-helper/config.json by `setup`; without it every
  job is `needs_runtime`. Nothing else is ever executed by this module (a source-grep test pins that), and job code never runs on the Mac.
* Every container command is `podman --connection <name> ...`; `check()` validates the connection by NAME, default flag and user-level URI (not by
  counting rows: `machine init` also creates a `-root` connection), the machine name, rootless, and that the machine mounts exactly the staging root
  `/stage` (no `$HOME` share).
* It never starts or stops the machine: the VM lifecycle is `pca_lock.py` / `container/vm/machine.sh` only.
* Implements the runtime interface the supervisor and the cleanup executor call: list_containers, kill, remove, list_volumes, remove_volume.
  `create_volume` journals the volume name BEFORE the volume exists, so a crash leaves an entry that drives its removal.
"""
import json
import os
import re
import subprocess
import threading
import time

from .protocol import constants

NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
CONTAINER_RE = re.compile(r"^pca-job-[A-Za-z0-9_-]{1,64}$")
GONE = ("no such container", "no container with name", "no such volume", "no volume with name", "not found", "is not running",
        "can only kill running")
EXPORT_TIMEOUT_S = 60


class NeedsRuntime(Exception):
    """No usable runtime: the job fails `needs_runtime` (never a host fallback)."""


class RuntimeFail(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


class ExportTooLarge(RuntimeFail):
    def __init__(self):
        super().__init__("too_large")


def load_runtime(store, journal, log, environ=None):
    """Read the runtime from config.json under the helper home. No PATH lookup, no environment variable, no fallback."""
    try:
        with open(store.home / "config.json", encoding="utf-8") as f:
            rt = json.load(f)["runtime"]
        podman, conn, machine = rt["podman"], rt["connection"], rt.get("machine", rt["connection"])
    except (OSError, ValueError, KeyError, TypeError):
        raise NeedsRuntime("no runtime configured")
    if not (isinstance(podman, str) and os.path.isabs(podman) and os.path.isfile(podman) and os.access(podman, os.X_OK)):
        raise NeedsRuntime("configured runtime path is not an absolute executable file")
    if not all(isinstance(x, str) and NAME_RE.match(x) for x in (conn, machine)):
        raise NeedsRuntime("bad connection or machine name")
    return PodmanRuntime(podman, conn, machine, journal, log)


class PodmanRuntime:
    def __init__(self, podman, connection, machine, journal, log, stage_root=None):
        self.podman, self.connection, self.machine = podman, connection, machine
        self.journal, self.log = journal, log
        self._kill_s = constants()["killBudgetMs"] / 1000.0
        self._stage_root = stage_root

    # ---------------------------------------------------------------- the one process choke point
    def _argv(self, args, remote=True):
        return [self.podman] + (["--connection", self.connection] if remote else []) + list(args)

    def _exec(self, args, timeout=30, remote=True):
        """-> (returncode, stdout, stderr); returncode -1 = could not run or timed out. Output is never logged."""
        try:
            p = subprocess.run(self._argv(args, remote), capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)
            return p.returncode, p.stdout, p.stderr
        except (OSError, subprocess.SubprocessError):
            return -1, "", ""

    # ---------------------------------------------------------------- configuration checks
    def machine_info(self):
        rc, out, _ = self._exec(["machine", "inspect", self.machine], remote=False)
        if rc != 0:
            raise NeedsRuntime("machine not found")
        try:
            data = json.loads(out)
            data = data[0] if isinstance(data, list) else data
            if not isinstance(data, dict):
                raise ValueError
            return data
        except (ValueError, IndexError):
            raise NeedsRuntime("machine state unreadable")

    def machine_state(self):
        try:
            return str(self.machine_info().get("State", "")).lower() or "unknown"
        except NeedsRuntime:
            return "unknown"

    def check(self):
        """Validate the configuration; a stopped machine is valid (the VM is stopped whenever idle). Raises NeedsRuntime."""
        rc, out, _ = self._exec(["system", "connection", "list", "--format", "json"], remote=False)
        try:
            rows = json.loads(out) if rc == 0 else None
        except ValueError:
            rows = None
        if not isinstance(rows, list):
            raise NeedsRuntime("connections unreadable")
        row = next((r for r in rows if isinstance(r, dict) and r.get("Name") == self.connection), None)
        uri = str(row.get("URI", "")) if row else ""
        if row is None or row.get("Default") is not True or "/run/user/" not in uri or re.match(r"^[a-z+]+://root@", uri):
            raise NeedsRuntime("connection is not the default user-level connection")
        info = self.machine_info()
        if str(info.get("Name", self.machine)) != self.machine or info.get("Rootful") is not False:
            raise NeedsRuntime("machine name or rootless mismatch")
        mounts = info.get("Mounts")
        if not isinstance(mounts, list) or len(mounts) != 1 or not isinstance(mounts[0], dict) or mounts[0].get("Target") != "/stage" \
                or not str(mounts[0].get("Source", "")).rstrip("/").endswith("/stage"):
            raise NeedsRuntime("machine must mount exactly the staging root")
        if self._stage_root is not None and os.path.realpath(str(mounts[0]["Source"])) != os.path.realpath(str(self._stage_root)):
            raise NeedsRuntime("machine mounts something other than the staging root")
        if str(info.get("State", "")).lower() == "running":
            rc, out, _ = self._exec(["info", "--format", "json"])
            try:
                ok = rc == 0 and json.loads(out)["host"]["security"]["rootless"] is True
            except (ValueError, KeyError, TypeError):
                ok = False
            if not ok:
                raise NeedsRuntime("runtime is not rootless")
        return info

    def require_running(self):
        if self.machine_state() != "running":
            raise NeedsRuntime("machine is not running")

    # ---------------------------------------------------------------- the interface the supervisor and the cleanup executor call
    def list_containers(self):
        rc, out, _ = self._exec(["ps", "-a", "--filter", "label=pca.helper", "--format", "{{.Names}}"])
        if rc != 0:
            self.log.event("runtime_unavailable", op="ps")
            return []
        return [l.strip() for l in out.splitlines() if CONTAINER_RE.match(l.strip())]

    @staticmethod
    def _gone(err):
        low = (err or "").lower()
        return any(g in low for g in GONE)

    def kill(self, name):
        if not isinstance(name, str) or not CONTAINER_RE.match(name):
            return True
        rc, _, err = self._exec(["kill", name], timeout=self._kill_s)
        return rc == 0 or self._gone(err)

    def remove(self, name):
        if not isinstance(name, str) or not CONTAINER_RE.match(name):
            return True
        rc, _, err = self._exec(["rm", "-f", "-t", "0", name], timeout=5)
        return rc == 0 or self._gone(err)

    def container_exists(self, name):
        """False only when podman says the container does not exist; an unreadable answer counts as existing (never claim gone without proof)."""
        rc, _, _ = self._exec(["container", "exists", name], timeout=5)
        return rc != 1

    def list_volumes(self):
        rc, out, _ = self._exec(["volume", "ls", "--format", "{{.Name}}"])
        if rc != 0:
            self.log.event("runtime_unavailable", op="volume_ls")
            return []
        return [l.strip() for l in out.splitlines() if re.match(r"^pca-job-[A-Za-z0-9_-]{1,64}$", l.strip())]

    def remove_volume(self, name):
        if not isinstance(name, str) or not re.match(r"^pca-job-[A-Za-z0-9_-]{1,64}$", name):
            return True  # a name podman cannot hold cannot exist
        rc, _, err = self._exec(["volume", "rm", "-f", name], timeout=30)
        return rc == 0 or self._gone(err)

    def execute(self, args, timeout=3700):
        """Setup only: run one podman command (args after `--connection <name>`) and return its exit code; output is discarded."""
        return self._exec(args, timeout=timeout)[0]

    def image_exists(self, tag):
        rc, _, _ = self._exec(["image", "exists", tag], timeout=10)
        return rc == 0

    def vm_var_used_bytes(self):
        """Bytes used on the VM's /var (doctor, VM running only); None when unreadable."""
        rc, out, _ = self._exec(["machine", "ssh", self.machine, "df", "-P", "-B1", "/var"], timeout=30, remote=False)
        try:
            return int(out.strip().splitlines()[-1].split()[2]) if rc == 0 else None
        except (ValueError, IndexError):
            return None

    # ---------------------------------------------------------------- the job-local volume
    def create_volume(self, job_id, name):
        self.journal.add_volume(job_id, name)  # durable BEFORE the volume exists
        rc, _, _ = self._exec(["volume", "create", name], timeout=30)
        if rc != 0:
            raise RuntimeFail("runtime_error")

    def export_volume(self, name, dest, cap_bytes):
        """`podman volume export` through a pipe capped at cap_bytes + 1 (the head -c of the plan, done in-process): more than cap_bytes = too_large.
        Writes dest (0600, exclusive, never following a link). Returns the byte count. The partial file is removed on any failure."""
        fd = os.open(str(dest), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        total, ok = 0, False
        try:
            try:
                p = subprocess.Popen(self._argv(["volume", "export", name]), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL)
            except OSError:
                raise RuntimeFail("runtime_error")
            timer = threading.Timer(EXPORT_TIMEOUT_S, p.kill)
            timer.start()
            try:
                while True:
                    chunk = p.stdout.read(min(1 << 20, cap_bytes + 1 - total))
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > cap_bytes:
                        raise ExportTooLarge()
                    os.write(fd, chunk)
            finally:
                timer.cancel()
                if p.poll() is None:
                    p.kill()
                p.stdout.close()
                rc = p.wait()
            if rc != 0:
                raise RuntimeFail("runtime_error")
            ok = True
            return total
        finally:
            os.close(fd)
            if not ok:
                try:
                    os.unlink(str(dest))
                except OSError:
                    pass

    def popen_run(self, args):
        """Start `podman run` (args after `podman`); the caller waits. Output goes nowhere (job output reaches the host only through the volume)."""
        return subprocess.Popen(self._argv(args), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
