import ast
import os
from pathlib import Path

import pytest

from pca_helper.log import Logger
from pca_helper.store import Store

PKG = Path(__file__).resolve().parent.parent / "pca_helper"
TOKEN = "tok_" + "Zq9" * 12


def test_log_redacts_registered_secret_token_shapes_and_bundle_bodies():
    lines = []
    log = Logger(sink=lines.append)
    log.register_secret(TOKEN)
    log.event("poll", status=200, count=3, ok=True, code="capability_revoked", token=TOKEN, note="x " + TOKEN, blob={"source": "def run(x): pass", "items": [1, 2]},
              hexish="a" * 40, bearer="Bearer abcdefghijklmnop", raw=b"secret bytes", free="Hello World")
    out = "\n".join(lines)
    assert TOKEN not in out and "def run" not in out and "secret bytes" not in out and "Hello World" not in out and "a" * 40 not in out
    assert "status=200" in out and "count=3" in out and "ok=true" in out and "code=capability_revoked" in out
    assert "blob=<n:2>" in out


def test_log_registered_secret_is_redacted_even_when_enum_shaped():
    lines = []
    log = Logger(sink=lines.append)
    log.register_secret("secretword")
    log.event("x", v="secretword")
    assert "secretword" not in lines[0]


def test_no_raw_print_of_secrets():
    for py in PKG.glob("*.py"):
        tree = ast.parse(py.read_text())
        for n in ast.walk(tree):
            if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "print":
                pytest.fail("raw print in %s:%d" % (py.name, n.lineno))
            if isinstance(n, (ast.Import, ast.ImportFrom)):
                mods = [a.name for a in n.names] if isinstance(n, ast.Import) else [n.module or ""]
                assert "logging" not in mods, py.name
    # the only writers to the process streams are the logger sink and the CLI output object
    for py in PKG.glob("*.py"):
        if py.name in ("log.py", "cli.py"):
            continue
        src = py.read_text()
        assert "sys.stdout" not in src and "sys.stderr" not in src, py.name


def test_store_files_are_0600_and_dirs_0700(tmp_path):
    s = Store(tmp_path / "home")
    s.save_state({"token": "t", "pin": "ab" * 32})
    assert oct(os.stat(s.state_path).st_mode & 0o777) == "0o600"
    for d in (s.home, s.stage_dir, s.journal_dir, s.artifacts_dir):
        assert oct(os.stat(d).st_mode & 0o777) == "0o700", d
    assert s.load_state()["token"] == "t"


def test_atomic_write_leaves_no_partial_file(tmp_path, monkeypatch):
    s = Store(tmp_path / "home")
    s.save_state({"v": 1})

    def boom(a, b):
        raise OSError("disk")
    monkeypatch.setattr(os, "replace", boom)
    with pytest.raises(OSError):
        s.save_state({"v": 2})
    monkeypatch.undo()
    assert s.load_state() == {"v": 1}
    assert [p.name for p in s.home.iterdir() if p.name.endswith(".tmp")] == []


def test_state_never_inside_a_job_dir_and_job_ids_cannot_traverse(tmp_path):
    s = Store(tmp_path / "home")
    assert s.stage_dir not in s.state_path.parents
    for bad in ("../x", "a/b", "", "x" * 65, "a b", "."):
        with pytest.raises(ValueError):
            s.job_stage_dir(bad)


def test_delete_job_copies_removes_stage_artifacts_and_unlinks_symlink(tmp_path):
    s = Store(tmp_path / "home")
    d = s.job_stage_dir("j1")
    (d / "in").mkdir(parents=True)
    (d / "in" / "f").write_bytes(b"1")
    a = s.job_artifact_dir("j1")
    a.mkdir(parents=True)
    victim = tmp_path / "victim"
    victim.mkdir()
    (victim / "keep").write_bytes(b"1")
    os.symlink(victim, s.job_stage_dir("j2"))
    assert s.delete_job_copies("j1") and s.delete_job_copies("j2") and s.delete_job_copies("never")
    assert not d.exists() and not a.exists() and not s.job_stage_dir("j2").is_symlink()
    assert (victim / "keep").exists(), "a symlinked job dir is unlinked, its target is never followed"
