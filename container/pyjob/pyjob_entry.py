"""In-container harness of a `py_exec` job (core plan section 6; WP13). Standard library only (the image has nothing else).

Reads `<in>/payload.json` (the canonical payload the helper staged: `{helperProtocol, jobType, source, entry, items:[{id,input}], outSchema, limits}`)
and writes `<out>/outputs.json`: `{"helperProtocol":1,"jobType":"py_exec","outputs":[{id,ok,output|code}...],"stats":{"ms":N}}` with EVERY payload item
id exactly once, in order, and exits 0 whatever the items did. Nothing is printed: the job's own stdout/stderr go to /dev/null.

One forked child per item runs `entry(input)` from a fresh namespace of `source`. The child is KILLED when its per-item time is up (the lesser of
`limits.perItemMs` and what is left of `limits.jobMs`); the parent never waits for job code. Item codes: `timeout` (killed at its limit or no job time
left), `schema_mismatch` (the output is not valid against `outSchema`), `exception` (the code raised, returned a non-JSON value, or died), `oom` (the
child was SIGKILLed from outside, or raised MemoryError), `too_big` (the serialized output does not fit what is left of the 2 MiB result budget).
Directories default to /in and /out; `--in` and `--out` exist for the host tests, which run this file as a plain process.
"""
import json
import os
import select
import signal
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import schema_lite  # noqa: E402  (a byte-identical copy of tools/canvas-helper/pca_helper/schema_lite.py)

MAX_RESULT_BYTES = 2097152  # constants.json maxResultBytes: the whole envelope must fit what the phone accepts
ENVELOPE_RESERVE = 1024
ITEM_RESERVE = 96  # per item still to run: its worst failed entry
OUT_NAME = "outputs.json"
PER_ITEM_MS_MAX = 5000
JOB_MS_MAX = 120000


def _dumps(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def _child(source, entry, value, wfd, budget):
    """Runs in the forked child. Writes one JSON line {ok,out|code} to the pipe and leaves with os._exit."""
    code, text = "exception", None
    try:
        null = os.open(os.devnull, os.O_RDWR)
        os.dup2(null, 0)
        os.dup2(null, 1)
        os.dup2(null, 2)
        ns = {"__name__": "__job__"}
        exec(compile(source, "<job>", "exec"), ns)
        out = ns[entry](value)
        text = _dumps(out)
        if len(text.encode("utf-8")) > budget:
            code, text = "too_big", None
    except MemoryError:
        code, text = "oom", None
    except BaseException:
        code, text = "exception", None
    try:
        data = json.dumps({"out": text} if text is not None else {"code": code}, ensure_ascii=False).encode("utf-8")
        view = memoryview(data)
        while view:
            n = os.write(wfd, view)
            view = view[n:]
    finally:
        os._exit(0)


def run_item(source, entry, value, limit_ms, budget):
    """-> ("out", text) | ("code", itemCode). The child is killed at `limit_ms`."""
    if limit_ms <= 0:
        return "code", "timeout"
    rfd, wfd = os.pipe()
    pid = os.fork()
    if pid == 0:
        os.close(rfd)
        _child(source, entry, value, wfd, budget)
        os._exit(0)  # not reached
    os.close(wfd)
    deadline = time.monotonic() + limit_ms / 1000.0
    chunks, timed_out = [], False
    try:
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                timed_out = True
                break
            ready, _, _ = select.select([rfd], [], [], left)
            if not ready:
                timed_out = True
                break
            b = os.read(rfd, 65536)
            if not b:
                break
            chunks.append(b)
        if timed_out:
            try:
                os.kill(pid, signal.SIGKILL)  # the per-item kill
            except OSError:
                pass
        _, status = os.waitpid(pid, 0)
    finally:
        os.close(rfd)
    if timed_out:
        return "code", "timeout"
    try:
        msg = json.loads(b"".join(chunks).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        msg = None
    if isinstance(msg, dict) and isinstance(msg.get("out"), str):
        return "out", msg["out"]
    if isinstance(msg, dict) and msg.get("code") in ("too_big", "oom", "exception"):
        return "code", msg["code"]
    if os.WIFSIGNALED(status) and os.WTERMSIG(status) == signal.SIGKILL:
        return "code", "oom"  # killed from outside (the cgroup): the container's memory limit
    return "code", "exception"


def run_job(payload, now=time.monotonic):
    t0 = now()
    source, entry, schema, items = payload["source"], payload["entry"], payload["outSchema"], payload["items"]
    lim = payload["limits"]
    per_item_ms = max(1, min(int(lim["perItemMs"]), PER_ITEM_MS_MAX))
    job_ms = max(1, min(int(lim["jobMs"]), JOB_MS_MAX))
    outputs, used = [], ENVELOPE_RESERVE
    for n, it in enumerate(items):
        left_ms = job_ms - int((now() - t0) * 1000)
        budget = MAX_RESULT_BYTES - used - ITEM_RESERVE * (len(items) - n)
        kind, val = run_item(source, entry, it["input"], min(per_item_ms, left_ms), max(budget, 0))
        entry_obj = None
        if kind == "out":
            try:
                obj = json.loads(val)
            except ValueError:
                obj = None
                entry_obj = {"id": it["id"], "ok": False, "code": "exception"}
            if entry_obj is None:
                if schema_lite.validate(schema, obj):
                    entry_obj = {"id": it["id"], "ok": True, "output": obj}
                else:
                    entry_obj = {"id": it["id"], "ok": False, "code": "schema_mismatch"}
        else:
            entry_obj = {"id": it["id"], "ok": False, "code": val}
        used += len(_dumps(entry_obj).encode("utf-8")) + 1
        outputs.append(entry_obj)
    return {"helperProtocol": 1, "jobType": "py_exec", "outputs": outputs, "stats": {"ms": int((now() - t0) * 1000)}}


def _payload_ok(p):
    if not isinstance(p, dict) or not isinstance(p.get("items"), list) or not isinstance(p.get("limits"), dict):
        return False
    if not isinstance(p.get("source"), str) or not isinstance(p.get("entry"), str) or not isinstance(p.get("outSchema"), dict):
        return False
    ids = [i.get("id") if isinstance(i, dict) else None for i in p["items"]]
    return all(isinstance(i, str) for i in ids) and len(set(ids)) == len(ids) and all("input" in i for i in p["items"])


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    in_dir, out_dir = "/in", "/out"
    while argv:
        a = argv.pop(0)
        if a == "--in" and argv:
            in_dir = argv.pop(0)
        elif a == "--out" and argv:
            out_dir = argv.pop(0)
        else:
            return 2
    try:
        with open(os.path.join(in_dir, "payload.json"), "rb") as f:
            payload = json.loads(f.read().decode("utf-8"))
    except (OSError, ValueError):
        return 2
    if not _payload_ok(payload):
        return 2
    result = run_job(payload)
    data = _dumps(result).encode("utf-8")
    tmp = os.path.join(out_dir, ".outputs.tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        view = memoryview(data)
        while view:
            view = view[os.write(fd, view):]
    finally:
        os.close(fd)
    os.replace(tmp, os.path.join(out_dir, OUT_NAME))
    return 0


if __name__ == "__main__":
    sys.exit(main())
