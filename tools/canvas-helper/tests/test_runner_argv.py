"""WP12: the ONE runner argv builder and the ONE profile table (core plan sections 2 and 4.1). Pure argv tests: nothing is executed."""
import json
import os
import shutil

import pytest

from pca_helper import profiles, runner

JOB = "job-7"
FLAGS = ["--read-only", "--cap-drop=ALL", "--rm"]


def argv(profile="py_exec", job=JOB, remaining_ms=130_000, table=None):
    return runner.build_job_argv(job, remaining_ms=remaining_ms, table=table or profiles.load_table()) if profile == "py_exec" else None


def pairs(a):
    """Flag -> list of values for the `--flag value` and `--flag=value` forms."""
    out = {}
    i = 0
    while i < len(a):
        t = a[i]
        if t.startswith("--") and "=" in t:
            k, v = t.split("=", 1)
            out.setdefault(k, []).append(v)
        elif t.startswith("--") and i + 1 < len(a) and not a[i + 1].startswith("-"):
            out.setdefault(t, []).append(a[i + 1])
            i += 1
        elif t.startswith("-"):
            out.setdefault(t, []).append(a[i + 1] if i + 1 < len(a) and t in ("-v", "-e") else None)
            if t in ("-v", "-e"):
                i += 1
        i += 1
    return out


def test_every_run_has_the_base_flags():
    a = argv()
    assert a[0] == "run"
    for f in FLAGS:
        assert f in a, f
    p = pairs(a)
    assert p["--security-opt"] == ["no-new-privileges"] and "seccomp=unconfined" not in " ".join(a)
    assert p["--user"] == ["10001"]
    assert p["--name"] == ["pca-job-" + JOB]
    assert "--timeout" in p and "--pids-limit" in p and "--memory" in p and "--memory-swap" in p and "--cpus" in p and "--tmpfs" in p
    assert p["--network"] == ["none"], "py_exec has no network"
    assert p["--pull"] == ["never"]


def test_limits_come_from_the_table_and_swap_equals_memory(tmp_path):
    t = profiles.load_table()
    py = t.job_profile("py_exec")
    p = pairs(runner.build_job_argv(JOB, remaining_ms=130_000, table=t))
    assert p["--memory"] == p["--memory-swap"] == ["%dm" % py.memory_mb]
    assert p["--pids-limit"] == [str(py.pids_limit)] and p["--cpus"] == [str(py.cpus)]
    assert p["--tmpfs"][0].startswith("/tmp:size=%dm" % py.tmpfs_mb) and "noexec" in p["--tmpfs"][0] and "nosuid" in p["--tmpfs"][0]
    # change the table, the argv changes (one resource config)
    raw = json.load(open(profiles.DEFAULT_PATH))
    raw["profiles"]["py_exec"]["memoryMb"] = 777
    raw["profiles"]["py_exec"]["pidsLimit"] = 33
    raw["profiles"]["py_exec"]["memorySwapMb"] = 777
    path = tmp_path / "r.json"
    path.write_text(json.dumps(raw))
    p2 = pairs(runner.build_job_argv(JOB, remaining_ms=130_000, table=profiles.load_table(path)))
    assert p2["--memory"] == p2["--memory-swap"] == ["777m"] and p2["--pids-limit"] == ["33"]


def test_timeout_is_min_of_profile_deadline_and_remaining_plus_two():
    t = profiles.load_table()
    d = t.job_profile("py_exec").deadline_s
    assert pairs(argv(remaining_ms=500_000))["--timeout"] == [str(d + 2)]
    assert pairs(argv(remaining_ms=30_000))["--timeout"] == ["32"]
    assert pairs(argv(remaining_ms=0))["--timeout"] == ["2"], "never zero (zero means no timeout)"


def test_no_privilege_or_host_namespaces():
    a = " ".join(argv())
    for bad in ("--privileged", "--net=host", "--network host", "--network=host", "--pid=host", "--pid host", "--userns", "--ipc", "--uts",
                "--device", "--cap-add", "--env", "-e ", "--env-file", "--group-add", "--security-opt seccomp", "--security-opt apparmor", "--add-host"):
        assert bad not in a, bad


def parse_mounts(a):
    binds, vols = [], []
    for i, t in enumerate(a):
        assert t != "--mount" and not t.startswith("--mount=") and t not in ("--volume",) and not t.startswith("--volumes-from"), "one mount syntax"
        if t == "-v":
            parts = a[i + 1].split(":")
            src, dst, opts = parts[0], parts[1], (parts[2] if len(parts) > 2 else "")
            (binds if src.startswith("/") else vols).append((src, dst, opts))
    return binds, vols


def test_every_bind_source_is_under_the_job_subtree_and_only_volume_is_pca_job_out():
    a = argv()
    binds, vols = parse_mounts(a)
    assert binds, "inputs are bind mounted"
    for src, dst, opts in binds:
        norm = os.path.normpath(src)
        assert norm.startswith("/stage/%s/" % JOB) and ".." not in src.split("/"), src
        assert "ro" in opts.split(","), "inputs are read-only: " + src
    assert vols == [("pca-job-" + JOB, "/out", "rw")]


def test_no_home_claude_codex_in_argv(monkeypatch, tmp_path):
    monkeypatch.setenv("HOME", str(tmp_path / "somehome"))
    a = " ".join(argv())
    assert str(tmp_path) not in a and "somehome" not in a and "$HOME" not in a and "~" not in a
    assert ".claude" not in a and ".codex" not in a and "/Users" not in a and "/root" not in a


def test_verify_argv_guard_accepts_the_built_argv_and_refuses_outside_mounts():
    a = argv()
    runner.verify_argv(a, JOB)  # no exception
    for extra in (["-v", "/Users/x:/h:ro"], ["-v", "/stage:/s:ro"], ["-v", "/stage/other/in:/in:ro"], ["-v", "/stage/%s/../x:/in:ro" % JOB],
                  ["-v", "pca-job-other:/out"], ["-v", "pca-job-%s:/mnt" % JOB], ["--privileged"], ["--network", "host"], ["--mount", "type=bind,src=/,dst=/h"]):
        with pytest.raises(runner.ArgvRefused):
            runner.verify_argv(a[:-1] + extra + a[-1:], JOB)
    with pytest.raises(runner.ArgvRefused):
        runner.verify_argv([t for t in a if t != "--read-only"], JOB)


def test_one_job_row_and_non_job_rows_cannot_run_jobs():
    t = profiles.load_table()
    assert [n for n, p in t.profiles.items() if p.job] == ["py_exec"]
    assert {"setup", "probe"} <= set(t.profiles) and not t.profiles["setup"].job and not t.profiles["probe"].job
    for name in ("setup", "probe", "gen", "verify", "nope"):
        with pytest.raises(profiles.ProfileError):
            t.job_profile(name)


def test_job_ids_that_podman_cannot_name_are_refused():
    for bad in ("a:b", "a/b", "", "-x", "a" * 65, "..", "a b"):
        with pytest.raises(runner.ArgvRefused):
            runner.build_job_argv(bad, remaining_ms=1000, table=profiles.load_table())


def test_probe_row_uses_the_same_builder_and_base_flags():
    t = profiles.load_table()
    a = runner.build_argv(t.profiles["probe"], name="pca-probe-1", labels={"pca.job": "probe"}, binds=[("/stage/probe", "/probe", "ro")], timeout_s=60,
                          command=["python3", "/probe/s2_probe.py"], image="localhost/pca-probe:1")
    for f in FLAGS:
        assert f in a
    assert pairs(a)["--network"] == ["none"]


def test_base_flags_are_the_single_source():
    assert all(f in runner.BASE_FLAGS for f in ("--read-only", "--cap-drop=ALL", "--rm"))


def test_table_refuses_swap_different_from_memory(tmp_path):
    raw = json.load(open(profiles.DEFAULT_PATH))
    raw["profiles"]["py_exec"]["memorySwapMb"] = 2048
    path = tmp_path / "r.json"
    path.write_text(json.dumps(raw))
    with pytest.raises(profiles.ProfileError):
        profiles.load_table(path)
