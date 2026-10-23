"""WP13: the in-container harness `container/pyjob/pyjob_entry.py`, run here as a PLAIN Python process (no container, no podman) with a temp /in and
/out: per-item timeout with a real kill, schema mismatch, exception, too_big, oom and every payload id exactly once in outputs.json."""
import json
import os
import signal
import subprocess
import sys
from pathlib import Path

import pytest

import protocol_loader as L
from wp10_env import ALLOWED_ENV

REPO = Path(__file__).resolve().parents[3]
ENTRY = REPO / "container" / "pyjob" / "pyjob_entry.py"
C = L.load("constants.json")
INT_SCHEMA = {"type": "object", "required": ["n"], "properties": {"n": {"type": "integer", "minimum": 0}}, "additionalProperties": False}


def payload_of(source, inputs, schema=None, per_item_ms=2000, job_ms=60000):
    return {"helperProtocol": 1, "jobType": "py_exec", "source": source, "entry": "run",
            "items": [{"id": "i%d" % n, "input": v} for n, v in enumerate(inputs)], "outSchema": schema or INT_SCHEMA,
            "limits": {"perItemMs": per_item_ms, "jobMs": job_ms}}


def run_harness(tmp_path, payload, timeout=25, write_payload=True):
    d_in, d_out = tmp_path / "in", tmp_path / "out"
    d_in.mkdir(exist_ok=True)
    d_out.mkdir(exist_ok=True)
    if write_payload:
        (d_in / "payload.json").write_text(json.dumps(payload))
    env = {k: os.environ[k] for k in ALLOWED_ENV if k in os.environ}  # allow-list: nothing inherited can select a runtime
    p = subprocess.run([sys.executable, str(ENTRY), "--in", str(d_in), "--out", str(d_out)], env=env, capture_output=True, timeout=timeout)
    return p, d_out


def outputs(d_out):
    return json.loads((d_out / "outputs.json").read_text())


def by_id(res):
    return {o["id"]: o for o in res["outputs"]}


def test_constants_are_the_fixture_numbers():
    ns = {}
    src = ENTRY.read_text()
    for name in ("MAX_RESULT_BYTES", "PER_ITEM_MS_MAX", "JOB_MS_MAX"):
        line = [l for l in src.splitlines() if l.startswith(name + " = ")][0]
        ns[name] = int(line.split("=")[1].split("#")[0])
    assert ns["MAX_RESULT_BYTES"] == C["maxResultBytes"] and ns["PER_ITEM_MS_MAX"] == C["py"]["perItemMs"] and ns["JOB_MS_MAX"] == C["py"]["jobMs"]


def test_happy_path_writes_outputs_json_with_every_id_once_in_order(tmp_path):
    src = "def run(x):\n    return {'n': len(x['text'])}\n"
    pl = payload_of(src, [{"text": "a"}, {"text": "bb"}, {"text": ""}])
    p, d_out = run_harness(tmp_path, pl)
    assert p.returncode == 0 and p.stdout == b"" and p.stderr == b""
    res = outputs(d_out)
    assert set(res) == {"helperProtocol", "jobType", "outputs", "stats"} and res["helperProtocol"] == 1 and res["jobType"] == "py_exec"
    assert [o["id"] for o in res["outputs"]] == ["i0", "i1", "i2"]
    assert [o["output"] for o in res["outputs"]] == [{"n": 1}, {"n": 2}, {"n": 0}] and all(o["ok"] is True for o in res["outputs"])
    assert isinstance(res["stats"]["ms"], int)
    assert sorted(x.name for x in d_out.iterdir()) == ["outputs.json"], "only outputs.json is left in /out (no temp file)"
    assert oct((d_out / "outputs.json").stat().st_mode & 0o777) == "0o600"


def test_per_item_timeout_kills_the_child_and_the_next_item_still_runs(tmp_path):
    pidfile = tmp_path / "child.pid"
    src = ("import os, time\n"
           "def run(x):\n"
           "    if x['k'] == 'sleep':\n"
           "        open(x['pid'], 'w').write(str(os.getpid()))\n"
           "        time.sleep(20)\n"
           "    return {'n': 1}\n")
    pl = payload_of(src, [{"k": "sleep", "pid": str(pidfile)}, {"k": "ok", "pid": ""}], per_item_ms=400)
    try:
        p, d_out = run_harness(tmp_path, pl, timeout=12)  # the harness must NOT wait for the 20 s sleep
    finally:
        if pidfile.exists():  # tidy: never leave the sleeper behind if the kill is missing
            try:
                os.kill(int(pidfile.read_text()), signal.SIGKILL)
            except (OSError, ValueError):
                pass
    assert p.returncode == 0
    o = by_id(outputs(d_out))
    assert o["i0"] == {"id": "i0", "ok": False, "code": "timeout"} and o["i1"]["ok"] is True
    with pytest.raises(ProcessLookupError):
        os.kill(int(pidfile.read_text()), 0)  # the killed child is gone (and reaped by the harness)


def test_job_deadline_ends_the_remaining_items_as_timeout_without_running_them(tmp_path):
    marks = tmp_path / "marks"
    marks.mkdir()
    src = ("import time\n"
           "def run(x):\n"
           "    open(x['m'], 'w').write('1')\n"
           "    time.sleep(10)\n"
           "    return {'n': 1}\n")
    pl = payload_of(src, [{"m": str(marks / ("m%d" % n))} for n in range(3)], per_item_ms=5000, job_ms=700)
    p, d_out = run_harness(tmp_path, pl, timeout=12)
    res = outputs(d_out)
    assert [o.get("code") for o in res["outputs"]] == ["timeout"] * 3
    assert sorted(x.name for x in marks.iterdir()) == ["m0"], "items after the job deadline never started"


def test_schema_mismatch_is_per_item(tmp_path):
    src = ("def run(x):\n    return x['out']\n")
    outs = [{"n": 1}, {"n": "1"}, {"n": 1, "extra": 2}, {"n": -1}, {"n": 1.5}, {}, [1], {"n": 7}]
    p, d_out = run_harness(tmp_path, payload_of(src, [{"out": o} for o in outs]))
    res = outputs(d_out)
    assert [o["ok"] for o in res["outputs"]] == [True, False, False, False, False, False, False, True]
    assert all(o["code"] == "schema_mismatch" for o in res["outputs"] if not o["ok"]) and "output" not in res["outputs"][1]


def test_float_output_passes_number_and_fails_integer(tmp_path):
    schema = {"type": "object", "required": ["s"], "properties": {"s": {"type": "number"}}, "additionalProperties": False}
    p, d_out = run_harness(tmp_path, payload_of("def run(x):\n    return {'s': x['v']}\n", [{"v": 1}, {"v": 1}], schema=schema))
    assert all(o["ok"] for o in outputs(d_out)["outputs"])
    src = "def run(x):\n    return {'n': 0.73}\n"
    (tmp_path / "b").mkdir()
    p, d_out2 = run_harness(tmp_path / "b", payload_of(src, [{}]))
    assert outputs(d_out2)["outputs"][0]["code"] == "schema_mismatch"


@pytest.mark.parametrize("body", [
    "raise ValueError('SECRET-LEAK')",
    "return {1, 2}",
    "return {'n': float('nan')}",
    "return {'n': float('inf')}",
    "import sys; sys.exit(3)",
    "import os; os._exit(0)",
    "print('SECRET-LEAK'); import sys; sys.stderr.write('SECRET-LEAK'); raise KeyError('x')",
    "return run.nope",
])
def test_exception_is_a_per_item_code_and_nothing_leaks(tmp_path, body):
    src = "def run(x):\n    %s\n" % body
    p, d_out = run_harness(tmp_path, payload_of(src, [{}, {}]))
    assert p.returncode == 0
    res = outputs(d_out)
    assert [o["code"] for o in res["outputs"]] == ["exception", "exception"]
    assert b"SECRET-LEAK" not in p.stdout + p.stderr + (d_out / "outputs.json").read_bytes()


def test_missing_entry_and_syntax_error_are_exceptions_not_a_crash(tmp_path):
    for n, src in enumerate(("x = 1\n", "def run(x:\n")):
        sub = tmp_path / ("c%d" % n)
        sub.mkdir()
        p, d_out = run_harness(sub, payload_of(src, [{}]))
        assert p.returncode == 0 and outputs(d_out)["outputs"] == [{"id": "i0", "ok": False, "code": "exception"}]


def test_oom_kill_and_memory_error_are_oom(tmp_path):
    src = ("import os, signal\n"
           "def run(x):\n"
           "    if x['k'] == 'kill':\n"
           "        os.kill(os.getpid(), signal.SIGKILL)\n"
           "    raise MemoryError()\n")
    p, d_out = run_harness(tmp_path, payload_of(src, [{"k": "kill"}, {"k": "mem"}]))
    assert [o["code"] for o in outputs(d_out)["outputs"]] == ["oom", "oom"]


def test_too_big_item_and_the_whole_result_stays_inside_the_limit(tmp_path):
    schema = {"type": "string"}
    src = "def run(x):\n    return 'x' * x['n']\n"
    p, d_out = run_harness(tmp_path, payload_of(src, [{"n": 3_000_000}, {"n": 5}], schema=schema))
    assert [o.get("code") for o in outputs(d_out)["outputs"]] == ["too_big", None]
    sub = tmp_path / "sum"
    sub.mkdir()
    p, d_out = run_harness(sub, payload_of(src, [{"n": 900_000}] * 3, schema=schema))
    codes = [o.get("code") for o in outputs(d_out)["outputs"]]
    assert codes == [None, None, "too_big"], "the first two fit the 2 MiB budget; the third does not"
    assert (d_out / "outputs.json").stat().st_size <= C["maxResultBytes"]


def test_result_ids_exactly_once_for_fifty_mixed_items(tmp_path):
    src = ("import time\n"
           "def run(x):\n"
           "    k = x['k']\n"
           "    if k == 1: raise RuntimeError()\n"
           "    if k == 2: return {'n': 'bad'}\n"
           "    if k == 3: time.sleep(5)\n"
           "    return {'n': k}\n")
    inputs = [{"k": n % 4} for n in range(50)]
    p, d_out = run_harness(tmp_path, payload_of(src, inputs, per_item_ms=150, job_ms=60000), timeout=40)
    ids = [o["id"] for o in outputs(d_out)["outputs"]]
    assert ids == ["i%d" % n for n in range(50)] and len(set(ids)) == 50
    for n, o in enumerate(outputs(d_out)["outputs"]):
        k = n % 4
        assert (o["ok"], o.get("code")) == {0: (True, None), 1: (False, "exception"), 2: (False, "schema_mismatch"), 3: (False, "timeout")}[k]


def test_items_do_not_share_state(tmp_path):
    src = "COUNT = [0]\ndef run(x):\n    COUNT[0] += 1\n    return {'n': COUNT[0]}\n"
    p, d_out = run_harness(tmp_path, payload_of(src, [{}, {}, {}]))
    assert [o["output"]["n"] for o in outputs(d_out)["outputs"]] == [1, 1, 1]


def test_a_bad_payload_writes_nothing_and_exits_nonzero(tmp_path):
    for n, pl in enumerate(({"nope": 1}, {**payload_of("", [{}]), "items": [{"id": "i0", "input": 1}, {"id": "i0", "input": 2}]})):
        sub = tmp_path / ("p%d" % n)
        sub.mkdir()
        p, d_out = run_harness(sub, pl)
        assert p.returncode == 2 and not (d_out / "outputs.json").exists()
    sub = tmp_path / "none"
    sub.mkdir()
    p, d_out = run_harness(sub, None, write_payload=False)
    assert p.returncode == 2 and not (d_out / "outputs.json").exists()
