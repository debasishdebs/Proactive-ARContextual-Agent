"""WP12 doctor and setup (part-C H9): OK/FAIL per check, never secrets; setup is a dry run unless --apply and never touches the VM lifecycle."""
import io
import json
import os

import pytest

from pca_helper import doctor, setup as setup_mod
from wp12_env import env, fake  # noqa: F401


class Probes:
    def __init__(self, disk=100 << 30, mem=60, opera=False):
        self.disk, self.mem, self.opera = disk, mem, opera

    def free_disk_bytes(self, path):
        return self.disk

    def memory_free_percent(self):
        return self.mem

    def opera_running(self):
        return self.opera


def healthy(env):
    env.write_config()
    env.home.chmod(0o700)
    (env.home / "gates").mkdir(exist_ok=True)
    (env.home / "gates" / "python.stamp").write_text("stamp")


def run(env, probes=None):
    out = io.StringIO()
    code = doctor.run(env.store, out, probes or Probes(), log=env.log)
    return code, out.getvalue()


def test_healthy_machine_passes_with_the_vm_stopped(env):
    healthy(env)
    env.fake.set_state("stopped")  # the VM is stopped whenever idle: doctor must pass then
    code, out = run(env)
    assert code == 0 and "FAIL" not in out, out


def test_healthy_machine_passes_with_the_vm_running(env):
    healthy(env)
    code, out = run(env)
    assert code == 0 and "FAIL" not in out, out
    assert "images" in out


def test_no_runtime_config_fails(env):
    env.home.chmod(0o700)
    code, out = run(env)
    assert code == 1 and "FAIL runtime" in out


@pytest.mark.parametrize("what", ["rootful", "home_share", "machine_absent"])
def test_runtime_problems_fail(env, tmp_path, what):
    healthy(env)
    if what == "rootful":
        env.fake.flag("machine_rootful")
    elif what == "home_share":
        with open(os.path.join(env.fake.dir, "machine_mounts"), "w") as f:
            json.dump([{"Source": str(tmp_path), "Target": str(tmp_path)}], f)
    else:
        env.fake.set_state("absent")
    code, out = run(env)
    assert code == 1 and "FAIL runtime" in out


def test_memory_opera_and_disk_checks(env):
    healthy(env)
    code, out = run(env, Probes(mem=10))
    assert code == 1 and "FAIL memory free" in out and "resources_busy" in out
    code, out = run(env, Probes(opera=True))
    assert code == 1 and "FAIL Opera closed" in out
    code, out = run(env, Probes(disk=1 << 30))
    assert code == 1 and "FAIL host free disk" in out and "disk_low" in out
    code, out = run(env, Probes(mem=None))  # memory_pressure unavailable: reported, not a crash
    assert "memory free" in out


def test_vm_disk_ok_line_carries_no_failure_note(env):
    """Live fact: a healthy running VM printed `ok vm disk (disk_low: ...)`; the note belongs to a failure only."""
    healthy(env)
    with open(os.path.join(env.fake.dir, "vm_df"), "w") as f:
        f.write("/dev/vda 21474836480 %d 100 1%% /var" % (1 << 30))
    code, out = run(env)
    line = [l for l in out.splitlines() if "vm disk" in l][0]
    assert line.startswith("ok") and "disk_low" not in line


def test_vm_disk_checked_only_when_running(env):
    healthy(env)
    with open(os.path.join(env.fake.dir, "vm_df"), "w") as f:
        f.write("/dev/vda 21474836480 %d 100 90%% /var" % (18 << 30))
    code, out = run(env)
    assert code == 1 and "FAIL vm disk" in out and "disk_low" in out
    env.fake.set_state("stopped")
    code, out = run(env)
    assert code == 0, out


def test_missing_image_fails_when_running(env):
    healthy(env)
    with open(os.path.join(env.fake.dir, "images"), "w") as f:
        f.write("localhost/other:1")
    code, out = run(env)
    assert code == 1 and "FAIL images" in out


def test_missing_gate_stamp_fails_and_helper_protocol_ok(env):
    healthy(env)
    os.remove(env.home / "gates" / "python.stamp")
    code, out = run(env)
    assert code == 1 and "FAIL python gate stamp" in out and "ok   helperProtocol" in out


def test_persisted_token_fails_and_output_has_no_secret(env):
    healthy(env)
    env.store.save_state({"host": "h", "port": 1, "pin": "ab" * 32, "token": "SECRET-TOKEN-VALUE"})
    code, out = run(env)
    assert "FAIL no persisted session token" in out and "SECRET-TOKEN-VALUE" not in out and "ab" * 32 not in out


# ------------------------------------------------------------------ setup
def test_setup_plan_is_argv_snapshot_with_the_setup_row_limits(env):
    steps = setup_mod.plan(env.table, env.home, env.fake.bin + "/podman", repo="/repo")
    names = [s.name for s in steps]
    assert names == ["config", "machine", "image_pyjob"]
    build = next(s for s in steps if s.name == "image_pyjob").argv
    assert build[:4] == [env.fake.bin + "/podman", "--connection", "pca", "build"]
    s = env.table.profiles["setup"]
    assert "--memory" in build and build[build.index("--memory") + 1] == "%dm" % s.memory_mb
    assert build[build.index("--memory-swap") + 1] == "%dm" % s.memory_mb and build[build.index("--cpu-quota") + 1] == str(int(s.cpus) * 100000) and "--cpus" not in build  # podman build has no --cpus (live fact, podman 6.1.4)
    assert build[build.index("-t") + 1] == env.table.profiles["py_exec"].image
    assert "--privileged" not in build and "-v" not in build
    assert next(s for s in steps if s.name == "machine").argv is None, "the VM lifecycle is machine.sh through pca_lock.py, never setup"


def test_machine_init_argv_has_exactly_one_stage_mount_and_no_home(env):
    a = setup_mod.machine_init_argv(env.table, "/h/.pca-helper/stage")
    assert a.count("--volume") == 1 and a[a.index("--volume") + 1] == "/h/.pca-helper/stage:/stage"
    assert "--rootful" not in a and "/Users" not in " ".join(a)


def test_setup_is_a_dry_run_by_default(env):
    out = io.StringIO()
    code = setup_mod.main(env.store, out, env.table, podman=env.fake.bin + "/podman", apply=False, repo="/repo")
    assert code == 0 and not (env.home / "config.json").exists() and env.fake.calls() == []
    assert "dry run" in out.getvalue()


def test_setup_apply_writes_config_and_builds_through_the_connection(env):
    out = io.StringIO()
    code = setup_mod.main(env.store, out, env.table, podman=env.fake.bin + "/podman", apply=True, repo="/repo", log=env.log)
    cfg = json.loads((env.home / "config.json").read_text())
    assert code == 0 and cfg["runtime"] == {"podman": env.fake.bin + "/podman", "connection": "pca", "machine": "pca"}
    assert any(c.startswith("build ") and "localhost/pca-pyjob:1" in c for c in env.fake.calls())
    assert not any(c.startswith("machine ") for c in env.fake.calls())


def test_setup_refuses_relative_podman_path(env):
    for bad in ("podman", "./podman", ""):
        assert setup_mod.main(env.store, io.StringIO(), env.table, podman=bad, apply=True, repo="/repo") == 2
    assert not (env.home / "config.json").exists()
