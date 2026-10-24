"""`doctor` (part-C H9): one OK/FAIL line per check, never a secret. A stopped machine passes (the VM is stopped whenever idle); VM-side checks (images,
VM disk) run only while it is up. The host probes (memory, Opera, free disk) are injectable; the real ones run two fixed absolute system tools."""
import shutil
import subprocess
import re

from . import profiles
from .protocol import constants
from .runtime import NeedsRuntime, load_runtime

GIB = 1 << 30


class SystemProbes:
    def free_disk_bytes(self, path):
        return shutil.disk_usage(str(path)).free

    def memory_free_percent(self):
        try:
            p = subprocess.run(["/usr/bin/memory_pressure"], capture_output=True, text=True, timeout=20)
            m = re.search(r"free percentage:\s*(\d+)%", p.stdout)
            return int(m.group(1)) if m else None
        except (OSError, subprocess.SubprocessError):
            return None

    def opera_running(self):
        try:
            return subprocess.run(["/usr/bin/pgrep", "-x", "Opera"], capture_output=True, timeout=20).returncode == 0
        except (OSError, subprocess.SubprocessError):
            return False


def gate_stamp_path(store, name="python"):
    return store.home / "gates" / ("%s.stamp" % name)


def gate_ready(store):
    return gate_stamp_path(store).is_file()


def run(store, out, probes=None, log=None, table=None):
    table = table or profiles.load_table()
    probes = probes or SystemProbes()
    rows = []

    def add(name, ok, note=""):
        rows.append((name, ok, note))

    st = store.load_state() or {}
    add("home dir mode 0700", (store.home.stat().st_mode & 0o777) == 0o700)
    add("pairing metadata", True, " (informational: every start pairs again; %s)" % ("present" if st.get("host") and st.get("pin") else "none yet"))
    add("no persisted session token", "token" not in st, "" if "token" not in st else " (run once to delete it)")
    rt, info = None, None
    try:
        rt = load_runtime(store, None, log)
        info = rt.check()
        add("runtime", True)
    except NeedsRuntime as e:
        add("runtime", False, " (needs_runtime: %s)" % e)
    state = str((info or {}).get("State", "")).lower()
    running = state == "running"
    if rt is not None:
        add("machine state", state in ("running", "stopped"), " (%s)" % (state or "unknown"))
        py = table.job_profile("py_exec")
        if running:
            missing = [t for t in (py.image,) if not rt.image_exists(t)]
            add("images", not missing, "" if not missing else " (%d missing: run setup)" % len(missing))
            used = rt.vm_var_used_bytes()
            disk_ok = used is not None and used <= table.host["vmVarMaxGiB"] * GIB
            add("vm disk", disk_ok, "" if disk_ok else " (disk_low: VM /var above the limit or unreadable)")
        else:
            add("images", True, " (skipped: machine stopped)")
            add("vm disk", True, " (skipped: machine stopped)")
    add("host free disk", probes.free_disk_bytes(str(store.home)) >= table.host["minFreeDiskGb"] * GIB, " (disk_low)")
    mem = probes.memory_free_percent()
    add("memory free", mem is not None and mem >= table.host["minMemoryFreePercent"], " (resources_busy: below %d %% or unreadable)" % table.host["minMemoryFreePercent"])
    add("Opera closed", not probes.opera_running(), " (resources_busy)")
    add("helperProtocol", constants()["helperProtocol"] == 1)
    add("python gate stamp", gate_ready(store), " (no stamp: the live gate has not passed)")
    bad = False
    for name, ok, note in rows:
        informational = name in ("pairing metadata", "machine state", "images", "vm disk")
        out.write("%s %s%s\n" % ("ok  " if ok else "FAIL", name, note if (not ok or informational) else ""))
        bad = bad or not ok
    return 1 if bad else 0
