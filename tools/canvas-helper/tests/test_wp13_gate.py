"""WP13: the `python` stamp is written only from a fully passing results file; the Containerfile and the live gate script are checked STATICALLY
(nothing here builds an image, runs podman or runs the gate script)."""
import io
import json
import re
import subprocess
from pathlib import Path

import pytest

from pca_helper import doctor, gate_stamp
from pca_helper.store import Store

REPO = Path(__file__).resolve().parents[3]
CONTAINERFILE = (REPO / "container" / "pyjob" / "Containerfile").read_text()
GATE = REPO / "tools" / "canvas-helper" / "gates" / "py_gate_live.sh"


def all_pass():
    return {n: True for n in gate_stamp.REQUIRED}


def test_stamp_is_written_only_when_every_required_check_passed(tmp_path):
    store = Store(tmp_path / "home")
    for broken in ({}, {**all_pass(), "kill_within_5s": False}, {**all_pass(), "oom": "yes"}, {k: v for k, v in all_pass().items() if k != "image"}):
        with pytest.raises(gate_stamp.StampRefused):
            gate_stamp.write_stamp(store, broken)
        assert not doctor.gate_ready(store)
    path = gate_stamp.write_stamp(store, all_pass(), commit="abc", image_id="sha256:1")
    assert path == store.home / "gates" / "python.stamp" and doctor.gate_ready(store)
    assert oct(path.stat().st_mode & 0o777) == "0o600" and json.loads(path.read_text())["gate"] == "python"
    gate_stamp.clear(store)
    assert not doctor.gate_ready(store)
    gate_stamp.clear(store)  # clearing a missing stamp is fine


def test_stamp_cli_refuses_a_failing_or_unreadable_results_file(tmp_path):
    home, res = tmp_path / "h", tmp_path / "r.json"
    out, err = io.StringIO(), io.StringIO()
    res.write_text(json.dumps({**all_pass(), "oom": False}))
    assert gate_stamp.main(["write", "--results", str(res)], out, err, home=home) == 1 and "oom" in err.getvalue()
    assert gate_stamp.main(["write", "--results", str(tmp_path / "missing.json")], out, err, home=home) == 1
    assert not doctor.gate_ready(Store(home))
    res.write_text(json.dumps(all_pass()))
    assert gate_stamp.main(["write", "--results", str(res), "--commit", "c"], out, err, home=home) == 0 and doctor.gate_ready(Store(home))
    assert gate_stamp.main(["clear"], out, err, home=home) == 0 and not doctor.gate_ready(Store(home))


def test_stamp_is_written_nowhere_but_the_gate_stamp_module():
    pkg = REPO / "tools" / "canvas-helper" / "pca_helper"
    for py in pkg.glob("*.py"):
        if py.name == "gate_stamp.py":
            continue
        assert "python.stamp" not in py.read_text() and "write_stamp" not in py.read_text(), py.name


def test_containerfile_user_out_dir_and_stdlib_only():
    assert re.search(r"^USER 10001", CONTAINERFILE, re.M)
    assert re.search(r"mkdir -p [^\n]*/out[^\n]*\n?[^\n]*chown 10001:10001 /out|chown 10001:10001 /out", CONTAINERFILE), "/out is owned by 10001 (named-volume copy-up)"
    assert re.search(r"^ENTRYPOINT \[\"python3\", \"/app/pyjob_entry\.py\"\]", CONTAINERFILE, re.M)
    assert "COPY pyjob_entry.py schema_lite.py /app/" in CONTAINERFILE
    assert "pip" not in CONTAINERFILE.replace("NO pip", "") and "apt" not in CONTAINERFILE
    base = [l for l in CONTAINERFILE.splitlines() if l.startswith("FROM ")]
    assert len(base) == 1 and "python:3.12-slim@sha256:" in base[0], "base image pinned by digest (the controller fills the digest)"
    prof = json.loads((REPO / "tools" / "canvas-helper" / "config" / "resources.json").read_text())["profiles"]["py_exec"]
    assert prof["user"] == "10001"


def code_of(path):
    return "\n".join(l for l in path.read_text().splitlines() if not l.lstrip().startswith("#"))


def test_gate_script_is_valid_sh_and_stamps_only_through_the_stamp_writer():
    assert subprocess.run(["/bin/sh", "-n", str(GATE)]).returncode == 0
    src = code_of(GATE)
    assert src.index("gate_stamp clear") < src.index("gate_stamp write"), "the old stamp is removed first"
    assert src.count("gate_stamp write") == 1 and "python.stamp" not in src, "the script never names or writes the stamp file itself"
    for name in gate_stamp.REQUIRED:
        assert re.search(r'line\([^\n]*"%s"' % name, src), "the gate reports check " + name


def test_gate_script_never_touches_the_machine_lifecycle():
    src = code_of(GATE)
    assert not re.search(r"machine\s+(init|start|stop|rm)", src), "the VM lifecycle belongs to machine.sh inside the vm-session owner"
