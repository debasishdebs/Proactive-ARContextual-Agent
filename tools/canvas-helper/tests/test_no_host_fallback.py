"""WP12: one named rootless connection, no PATH lookup, no host fallback (core plan section 2)."""
import ast
import json
import os
import subprocess
from pathlib import Path

import pytest

from fakes import FakeClock
from pca_helper import runtime as rt_mod
from pca_helper.runner import JobRunner
from pca_helper.supervisor import RunnerError
from wp12_env import env, fake, make_decoy  # noqa: F401

PKG = Path(rt_mod.__file__).parent


def test_no_runtime_config_is_needs_runtime(env):
    with pytest.raises(rt_mod.NeedsRuntime):
        rt_mod.load_runtime(env.store, env.journal, env.log, environ={})


def test_job_runner_without_runtime_fails_needs_runtime_and_runs_nothing_on_the_host(env, monkeypatch):
    seen = []
    real_popen = subprocess.Popen
    monkeypatch.setattr(subprocess, "Popen", lambda *a, **k: seen.append(a) or real_popen(*a, **k))
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: seen.append(a) or (_ for _ in ()).throw(AssertionError("no host process")))
    monkeypatch.setattr(os, "system", lambda *a: seen.append(a) or 1)
    runner = JobRunner(None, env.store, env.journal, env.table, FakeClock(), env.log)
    with pytest.raises(RunnerError) as e:
        runner.start({"jobId": "j1", "leaseGen": 1, "bundleHash": "0" * 64, "remainingMs": 100000}, {"source": "print(1)"})
    assert e.value.code == "needs_runtime"
    assert seen == [], "no process of any kind (no host fallback)"
    assert not (env.store.stage_dir / "j1").exists()


def test_path_lookup_is_ignored_decoys_never_run(env, tmp_path, monkeypatch):
    marker = tmp_path / "decoy-ran"
    d = tmp_path / "decoys"
    d.mkdir()
    for n in ("podman", "docker"):
        make_decoy(d / n, marker)
    monkeypatch.setenv("PATH", "%s:%s:/usr/bin:/bin" % (d, env.fake.bin))
    with pytest.raises(rt_mod.NeedsRuntime):  # no configured path: PATH is NOT searched
        rt_mod.load_runtime(env.store, env.journal, env.log, environ={})
    env.write_config()  # configured absolute path = the fake, although a decoy podman comes first on PATH
    r = rt_mod.load_runtime(env.store, env.journal, env.log, environ={})
    r.check()
    assert not marker.exists()
    assert any(c.startswith("system connection list") for c in env.fake.calls())


def test_relative_or_missing_configured_path_refused(env):
    for bad in ("podman", "./podman", "", "/no/such/podman"):
        env.write_config(podman=bad)
        with pytest.raises(rt_mod.NeedsRuntime):
            rt_mod.load_runtime(env.store, env.journal, env.log, environ={})


def test_rootful_default_connection_refused(env):
    env.write_config()
    with open(os.path.join(env.fake.dir, "connections.json"), "w") as f:
        json.dump([{"Name": "pca", "URI": "ssh://root@127.0.0.1:50000/run/podman/podman.sock", "Default": True}], f)
    with pytest.raises(rt_mod.NeedsRuntime):
        rt_mod.load_runtime(env.store, env.journal, env.log, environ={}).check()


def test_connection_not_default_is_accepted(env):
    """Live fact (podman 6.1.4): the second machine is not the default connection and the user's default must stay untouched."""
    env.write_config()
    with open(os.path.join(env.fake.dir, "connections.json"), "w") as f:
        json.dump([{"Name": "pca", "URI": "ssh://core@h:1/run/user/501/podman/podman.sock", "Default": False}], f)
    rt_mod.load_runtime(env.store, env.journal, env.log, environ={}).check()


def test_mounts_come_from_the_machine_config_when_inspect_has_none(env, tmp_path):
    env.write_config()
    rt = rt_mod.load_runtime(env.store, env.journal, env.log, environ={})
    real = rt.machine_info
    def no_mounts():
        d = dict(real()); d.pop("Mounts", None); return d
    rt.machine_info = no_mounts
    cfg = tmp_path / "m"; cfg.mkdir()
    rt.machine_config_glob = str(cfg / "%s.json")
    with pytest.raises(rt_mod.NeedsRuntime):  # no config file at all: unverifiable = refused
        rt.check()
    (cfg / "pca.json").write_text(json.dumps({"Mounts": [{"Source": str(env.home / "stage"), "Target": "/stage"}]}))
    rt.check()
    (cfg / "pca.json").write_text(json.dumps({"Mounts": [{"Source": "/Users/<user>", "Target": "/Users/<user>"}]}))
    with pytest.raises(rt_mod.NeedsRuntime):
        rt.check()


def test_connection_missing_refused(env):
    env.write_config()
    for rows in ([], [{"Name": "other", "URI": "ssh://core@h:1/run/user/501/podman/podman.sock", "Default": True}]):
        with open(os.path.join(env.fake.dir, "connections.json"), "w") as f:
            json.dump(rows, f)
        with pytest.raises(rt_mod.NeedsRuntime):
            rt_mod.load_runtime(env.store, env.journal, env.log, environ={}).check()


def test_wrong_machine_name_refused(env):
    env.write_config(machine="other")
    with pytest.raises(rt_mod.NeedsRuntime):
        rt_mod.load_runtime(env.store, env.journal, env.log, environ={}).check()


def test_home_share_refused_and_extra_mount_refused(env, tmp_path):
    env.write_config()
    rt_mod.load_runtime(env.store, env.journal, env.log, environ={}).check()  # the default fake machine: one /stage mount
    for mounts in ([{"Source": str(tmp_path), "Target": str(tmp_path)}],
                   [{"Source": str(env.home / "stage"), "Target": "/stage"}, {"Source": "/Users/<user>", "Target": "/Users/<user>"}]):
        with open(os.path.join(env.fake.dir, "machine_mounts"), "w") as f:
            json.dump(mounts, f)
        with pytest.raises(rt_mod.NeedsRuntime):
            rt_mod.load_runtime(env.store, env.journal, env.log, environ={}).check()


def test_rootful_machine_refused(env):
    env.write_config()
    env.fake.flag("machine_rootful")
    with pytest.raises(rt_mod.NeedsRuntime):
        rt_mod.load_runtime(env.store, env.journal, env.log, environ={}).check()


def test_require_running_machine_is_needs_runtime_but_never_starts_it(env):
    env.write_config()
    env.fake.set_state("stopped")
    r = rt_mod.load_runtime(env.store, env.journal, env.log, environ={})
    r.check()  # a stopped machine is a valid configuration (the VM is stopped whenever idle)
    with pytest.raises(rt_mod.NeedsRuntime):
        r.require_running()
    assert not any(c.startswith("machine start") for c in env.fake.calls()), "VM lifecycle only through pca_lock.py"


def test_every_podman_call_uses_the_named_connection(env):
    env.write_config()
    r = rt_mod.load_runtime(env.store, env.journal, env.log, environ={})
    r.list_containers()
    r.list_volumes()
    r.kill("pca-job-a")
    with open(os.path.join(env.fake.dir, "connections.log")) as f:
        assert set(f.read().split()) == {"pca"}


def test_source_has_no_host_execution_path():
    """Source grep (noHostExecutionPath): subprocess only in runtime.py (podman) and doctor.py (two fixed absolute host probes); no os.system/exec/eval."""
    bad = ("os.system", "os.popen", "os.exec", "os.spawn", "pty.", "shell=True", "runpy")
    for py in PKG.glob("*.py"):
        src = py.read_text()
        for b in bad:
            assert b not in src, (py.name, b)
        tree = ast.parse(src)
        for n in ast.walk(tree):
            if isinstance(n, ast.Call) and isinstance(n.func, ast.Name):
                assert n.func.id not in ("eval", "exec", "compile", "__import__"), (py.name, n.func.id)
        mods = [a.name for n in ast.walk(tree) if isinstance(n, ast.Import) for a in n.names] + [n.module for n in ast.walk(tree) if isinstance(n, ast.ImportFrom)]
        if "subprocess" in mods:
            assert py.name in ("runtime.py", "doctor.py"), py.name
    doc = (PKG / "doctor.py").read_text()
    assert "/usr/bin/memory_pressure" in doc and "/usr/bin/pgrep" in doc
    assert "shutil.which" not in (PKG / "runtime.py").read_text() and "shutil.which" not in (PKG / "runner.py").read_text()
