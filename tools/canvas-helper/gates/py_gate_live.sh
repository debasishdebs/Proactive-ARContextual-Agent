#!/bin/sh
# LIVE gate of py_exec (core plan WP13, batch B2). NOT run by CI and NOT run by the WP13 code session: the controller runs it, from the gate
# worktree `pca-3b-gate-b2`, INSIDE one `pca_lock.py vm-session` owner (the VM is up; no host Gradle runs), after `pca-helper setup --apply` built
# `localhost/pca-pyjob:1` (spikes/wp13-live-gate.md). It drives the REAL helper code (JobRunner pieces: Workspace, the one argv builder, the real
# podman runtime from ~/.pca-helper/config.json) with one 7-item probe job and one long job, then writes ~/.pca-helper/gates/python.stamp ONLY when
# every required check passed (`python3 -m pca_helper.gate_stamp write`). The old stamp is removed first, so a failed run leaves none.
# Output: one `PASS|FAIL <check>` line per check on stdout (no payload data, no secret). Exit 0 = stamp written; 1 = a check failed; 2 = not ready.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
HELPER_DIR=$(cd "$HERE/.." && pwd)
REPO=$(cd "$HELPER_DIR/../.." && pwd)
export PYTHONPATH="$HELPER_DIR${PYTHONPATH:+:$PYTHONPATH}"
export PYTHONDONTWRITEBYTECODE=1

if [ ! -f "${PCA_HELPER_HOME:-$HOME/.pca-helper}/config.json" ]; then
  echo "not ready: run 'pca-helper setup --podman <absolute path> --apply' first (writes config.json and builds the image)" >&2
  exit 2
fi
python3 -m pca_helper.gate_stamp clear || exit 2
RES=$(mktemp "${TMPDIR:-/tmp}/pca-pygate.XXXXXX") || exit 2
trap 'rm -f "$RES"' EXIT INT TERM

python3 - "$RES" <<'PY'
import json
import os
import sys
import time
from pathlib import Path

from pca_helper import profiles, protocol
from pca_helper.clock import SystemClock
from pca_helper.doctor import SystemProbes
from pca_helper.export import Workspace
from pca_helper.journal import CleanupExecutor, Journal
from pca_helper.jobs_py import PyJobGlue
from pca_helper.log import Logger
from pca_helper.runner import build_job_argv, volume_name
from pca_helper.runtime import NeedsRuntime, load_runtime
from pca_helper.store import Store

res_path = sys.argv[1]
results = {}


def line(ok, name, note=""):
    results[name] = bool(ok)
    sys.stdout.write("%s %s%s\n" % ("PASS" if ok else "FAIL", name, (" (" + note + ")") if note else ""))
    sys.stdout.flush()


home = os.environ.get("PCA_HELPER_HOME") or os.path.join(os.path.expanduser("~"), ".pca-helper")
store = Store(home)
clock = SystemClock()
journal = Journal(store.journal_dir, clock)
log = Logger(sink=lambda s: None)
table = profiles.load_table()
profile = table.job_profile("py_exec")
try:
    rt = load_runtime(store, journal, log)
    rt.require_running()
except NeedsRuntime:
    sys.stdout.write("FAIL runtime (not configured, or the machine is not running: start it inside the vm-session owner)\n")
    json.dump(results, open(res_path, "w"))
    sys.exit(2)
ws = Workspace(rt, store, journal, profile, SystemProbes(), table.host, log)
stamp_id = "gate%d" % os.getpid()

SOURCE = """
import os, socket, time
def run(x):
    k = x["k"]
    if k == "probe":
        try:
            open("/proof", "w").close()
            ro = False
        except OSError:
            ro = True
        s = socket.socket()
        s.settimeout(2)
        try:
            s.connect(("1.1.1.1", 53))
            net = False
        except OSError:
            net = True
        return {"uid": os.getuid(), "root_ro": ro, "net_blocked": net}
    if k == "float":
        return {"score": 0.73, "tiny": 1e-05}
    if k == "sleep":
        time.sleep(60)
    if k == "raise":
        raise ValueError("x")
    if k == "bad":
        return {"score": "text"}
    if k == "big":
        return {"big": "x" * 3000000}
    if k == "oom":
        b = bytearray()
        while True:
            b.extend(b"x" * (1 << 24))
"""
SCHEMA = {"type": "object", "properties": {"uid": {"type": "integer", "enum": [10001]}, "root_ro": {"enum": [True]}, "net_blocked": {"enum": [True]},
                                           "score": {"type": "number"}, "tiny": {"type": "number"}, "big": {"type": "string", "maxLength": 1000}},
          "additionalProperties": False}


def payload_of(kinds, per_item_ms, job_ms):
    return {"helperProtocol": 1, "jobType": "py_exec", "source": SOURCE, "entry": "run",
            "items": [{"id": "i%d" % n, "input": {"k": k}} for n, k in enumerate(kinds)], "outSchema": SCHEMA,
            "limits": {"perItemMs": per_item_ms, "jobMs": job_ms}}


def finish(job_id):
    journal.job_end(job_id)
    CleanupExecutor(store, journal, rt).execute(job_id)


image = profile.image
ok_image = rt.image_exists(image)
line(ok_image, "image", image)
image_id = ""
if not ok_image:
    json.dump(results, open(res_path, "w"))
    sys.exit(1)

# ---------------------------------------------------------------- job 1: the probe job through the real pipeline
job1 = stamp_id + "a"
kinds = ["probe", "float", "sleep", "raise", "bad", "big", "oom"]
payload = payload_of(kinds, 2000, 60000)
assert protocol.validate_payload(dict(payload)) == [], "gate payload is not a valid protocol payload"
t0 = time.monotonic()
ws.create(job1, protocol.canonical_json(payload).encode("utf-8"))
proc = rt.popen_run(build_job_argv(job1, remaining_ms=100_000, table=table))
rc = proc.wait()
elapsed = time.monotonic() - t0
files = {}
try:
    files = ws.export_and_collect(job1)
except Exception as e:  # WorkspaceFail: reported as a failed round trip
    sys.stdout.write("export failed: %s\n" % getattr(e, "code", "error"))
out = PyJobGlue.build(files, payload) if files else {"kind": "fail"}
by_id = {o["id"]: o for o in out.get("result", {}).get("outputs", [])} if out.get("kind") == "result" else {}
line(rc == 0 and set(files) == {"outputs.json"} and out.get("kind") == "result", "round_trip", "rc=%s files=%d" % (rc, len(files)))
line(set(by_id) == {"i%d" % n for n in range(len(kinds))}, "ids_once")
probe = by_id.get("i0", {})
po = probe.get("output", {}) if probe.get("ok") else {}
line(po.get("uid") == 10001, "user_10001")
line(po.get("root_ro") is True, "root_readonly")
line(po.get("net_blocked") is True, "network_none")
line(len(files.get("outputs.json", b"")) > 0, "out_writable", "/out owned by 10001 received outputs.json")
fl = by_id.get("i1", {})
text = protocol.result_canonical(out["result"]) if out.get("kind") == "result" else ""
line(fl.get("ok") is True and fl.get("output") == {"score": 0.73, "tiny": 1e-05} and '"score":0.73' in text and '"tiny":1e-05' in text, "float_result")
line(by_id.get("i2", {}).get("code") == "timeout" and elapsed < 45, "per_item_timeout_kill", "job took %.1fs for a 60 s sleep" % elapsed)
line(by_id.get("i3", {}).get("code") == "exception", "exception")
line(by_id.get("i4", {}).get("code") == "schema_mismatch", "schema_mismatch")
line(by_id.get("i5", {}).get("code") == "too_big", "too_big")
line(by_id.get("i6", {}).get("code") == "oom", "oom", "item i6 allocated until the 1024 MiB limit")
line(volume_name(job1) not in rt.list_volumes(), "volume_gone")
finish(job1)
line(not (store.stage_dir / job1).exists() and not rt.container_exists(volume_name(job1)), "stage_gone")

# ---------------------------------------------------------------- job 2: kill of a running container, measured to gone
job2 = stamp_id + "b"
payload2 = payload_of(["sleep"] * 10, 5000, 60000)
ws.create(job2, protocol.canonical_json(payload2).encode("utf-8"))
proc2 = rt.popen_run(build_job_argv(job2, remaining_ms=100_000, table=table))
name2 = volume_name(job2)
up = False
end = time.monotonic() + 60
while time.monotonic() < end and proc2.poll() is None:
    if rt.container_exists(name2):
        up = True
        break
    time.sleep(0.2)
time.sleep(1.0)  # let it run a moment
k0 = time.monotonic()
rt.kill(name2)
rt.remove(name2)
gone = False
while time.monotonic() - k0 < 10:
    if not rt.container_exists(name2):
        gone = True
        break
    time.sleep(0.05)
kill_s = time.monotonic() - k0
try:
    proc2.wait(10)
except Exception:
    proc2.terminate()
ws.abandon(job2)
finish(job2)
line(up and gone and kill_s <= 5.0 and rt.list_volumes().count(name2) == 0, "kill_within_5s", "kill to gone %.2fs" % kill_s)

json.dump(results, open(res_path, "w"))
sys.exit(0)
PY
rc=$?
[ "$rc" -eq 2 ] && exit 2

IMAGE_ID=$(python3 - <<'PY'
import json, os, subprocess
home = os.environ.get("PCA_HELPER_HOME") or os.path.join(os.path.expanduser("~"), ".pca-helper")
cfg = json.load(open(os.path.join(home, "config.json")))["runtime"]
r = subprocess.run([cfg["podman"], "--connection", cfg["connection"], "image", "inspect", "--format", "{{.Id}}", "localhost/pca-pyjob:1"],
                   capture_output=True, text=True, timeout=60)
print(r.stdout.strip())
PY
)
COMMIT=$(git -C "$REPO" rev-parse HEAD 2>/dev/null || echo unknown)
if python3 -m pca_helper.gate_stamp write --results "$RES" --commit "$COMMIT" --image-id "$IMAGE_ID"; then
  exit 0
fi
echo "FAIL: at least one required check did not pass; no gate stamp was written" >&2
exit 1
