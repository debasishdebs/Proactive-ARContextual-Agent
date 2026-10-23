import json
import subprocess
import sys
from pathlib import Path
import synth
from trace_lab import cli
from trace_lab.decode import load_trace
from trace_lab.runner import run_user_function

ROOT = Path(__file__).resolve().parent.parent

USER = '''
def analyze(trace):
    g = trace.arrays["g"]
    return {"gyro_samples": len(g["tMs"]), "events": trace.event_count}

def per_sec(rows):
    return {"peak": max(r["gyroMax"] for r in rows)}
'''


def test_run_user_function(tmp_path, capsys):
    d = synth.build(tmp_path / "t")
    script = tmp_path / "u.py"
    script.write_text(USER)
    assert run_user_function(script, "analyze", load_trace(d), "arrays") == {"gyro_samples": 21, "events": 58}
    assert cli.main(["run", str(d), str(script)]) == 0
    assert json.loads(capsys.readouterr().out) == {"gyro_samples": 21, "events": 58}
    assert cli.main(["run", str(d), str(script), "--func", "per_sec", "--on", "features"]) == 0
    assert json.loads(capsys.readouterr().out) == {"peak": 1.0}


def test_user_function_errors_are_reported_without_data(tmp_path, capsys):
    d = synth.build(tmp_path / "t")
    script = tmp_path / "u.py"
    script.write_text("def analyze(trace):\n    raise ValueError('boom')\n")
    assert cli.main(["run", str(d), str(script)]) == 3
    out = capsys.readouterr()
    assert "ValueError" in out.err and out.out == ""
    assert cli.main(["run", str(d), str(script), "--func", "nope"]) == 3


def test_cli_refusal_exit_code_and_summary(tmp_path, capsys):
    d = synth.build(tmp_path / "t")
    assert cli.main(["summary", str(d)]) == 0
    s = capsys.readouterr().out
    assert "COMPLETE" in s and "chunks 2" in s and "g=21" in s
    (d / "chunk-0001.json").unlink()
    assert cli.main(["summary", str(d)]) == 2
    assert "refused (missing_chunk)" in capsys.readouterr().err


def test_features_csv_is_the_only_write(tmp_path, capsys):
    d = synth.build(tmp_path / "t")
    before = sorted(p.name for p in d.iterdir())
    out = tmp_path / "f.csv"
    assert cli.main(["features", str(d), "--out", str(out)]) == 0
    assert sorted(p.name for p in d.iterdir()) == before
    lines = out.read_text().splitlines()
    assert lines[0].split(",")[:2] == ["second", "tStartMs"] and len(lines) == 5
    assert cli.main(["features", str(d)]) == 0 and "gyroMax" in capsys.readouterr().out


def test_module_entry_point(tmp_path):
    d = synth.build(tmp_path / "t")
    r = subprocess.run([sys.executable, "-m", "trace_lab.cli", "summary", str(d)], cwd=ROOT, capture_output=True, text=True)
    assert r.returncode == 0 and "COMPLETE" in r.stdout
