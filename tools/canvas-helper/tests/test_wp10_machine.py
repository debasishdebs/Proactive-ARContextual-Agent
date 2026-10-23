"""container/vm/machine.sh against the FAKE podman (WP10)."""
import subprocess

import pytest

import pca_lock
from wp10_env import MACHINE_SH, fake  # noqa: F401

STAGE_MOUNT_SUFFIX = ":/stage"


def run_in_owner(fake, *args):
    s = pca_lock.VmSession()
    s.acquire()
    rc = s.run_step(["bash", MACHINE_SH, *args])
    return s, rc


def test_machine_recreate_sequence(fake):
    fake.set_state("running")
    s, rc = run_in_owner(fake, "recreate")
    assert rc == 0, fake.calls()
    calls = fake.calls()
    assert not any(c.startswith("VIOLATION") or c.startswith("UNEXPECTED") for c in calls)
    heads = [" ".join(c.split()[:2]) + (" " + c.split()[2] if c.startswith("machine inspect") else "") for c in calls]
    # strip the inspect polls between the named steps; the order of the non-inspect steps and the stopped confirmation before rm is the contract
    stop = calls.index("machine stop pca")
    rm = next(i for i, c in enumerate(calls) if c.startswith("machine rm"))
    init = next(i for i, c in enumerate(calls) if c.startswith("machine init"))
    start = calls.index("machine start pca")
    assert stop < rm < init < start
    assert any(c == "machine inspect pca => stopped" for c in calls[stop:rm])
    assert not any(c.startswith("machine inspect") and "stopped" not in c for c in calls[stop + 1:rm])
    assert [c.split()[1] for c in calls[start:] if not c.startswith("machine inspect")] == ["start"]
    init_args = calls[init].split()
    vols = [init_args[i + 1] for i, a in enumerate(init_args) if a in ("--volume", "-v")] + [a.split("=", 1)[1] for a in init_args if a.startswith("--volume=")]
    assert len(vols) == 1 and vols[0].endswith(STAGE_MOUNT_SUFFIX) and vols[0].startswith(fake.stage), init_args
    assert init_args[-1] == "pca"
    assert fake.state() == "running"
    # batch end: machine stop confirmed
    rc = s.run_step(["bash", MACHINE_SH, "stop"])
    assert rc == 0
    calls = fake.calls()
    assert fake.state() == "stopped"
    last_stop = max(i for i, c in enumerate(calls) if c == "machine stop pca")
    assert last_stop > start and calls[-1] == "machine inspect pca => stopped"
    assert s.stop_and_release(max_attempts=1) is True


def test_machine_rm_refused_while_previous_not_confirmed_stopped(fake):
    fake.set_state("running")
    fake.flag("stop_fails")
    s, rc = run_in_owner(fake, "recreate")
    assert rc != 0
    calls = fake.calls()
    assert not any(c.startswith("machine rm") or c.startswith("machine init") or c.startswith("machine start") for c in calls)
    assert fake.state() == "running"
    s.release()


def test_machine_recreate_when_no_previous_machine(fake):
    fake.set_state("absent")
    s, rc = run_in_owner(fake, "recreate")
    assert rc == 0
    calls = fake.calls()
    assert not any(c.startswith("machine stop") or c.startswith("machine rm") for c in calls)
    assert [c.split()[1] for c in calls if not c.startswith("machine inspect")] == ["init", "start"]
    s.release()


def test_machine_sh_refuses_outside_vm_session(fake):
    fake.set_state("running")
    r = subprocess.run(["bash", MACHINE_SH, "recreate"], env=fake.env(), capture_output=True, text=True)
    assert r.returncode == 75 and "vm-session" in r.stderr
    assert fake.calls() == []


def test_machine_sh_refuses_unknown_command(fake):
    s, rc = run_in_owner(fake, "nuke")
    assert rc != 0 and fake.calls() == []
    s.release()


def init_args_of(fake):
    return next(c for c in fake.calls() if c.startswith("machine init")).split()


def test_machine_init_passes_cpus_and_disk_cap_with_defaults(fake):
    fake.set_state("absent")
    s, rc = run_in_owner(fake, "recreate")
    a = init_args_of(fake)
    assert rc == 0 and a[a.index("--cpus") + 1] == "2" and a[a.index("--disk-size") + 1] == "10" and a[a.index("--memory") + 1] == "4096"
    assert a[-1] == "pca"
    s.release()


def test_machine_init_disk_cap_is_overridable(fake, monkeypatch):
    monkeypatch.setenv("PCA_MACHINE_DISK_GB", "12")
    fake.set_state("absent")
    s, rc = run_in_owner(fake, "recreate")
    a = init_args_of(fake)
    assert rc == 0 and a[a.index("--disk-size") + 1] == "12"
    s.release()


@pytest.mark.parametrize("cmd", ["recreate", "stop"])
def test_machine_sh_refuses_the_users_default_machine_with_no_podman_call(fake, monkeypatch, cmd):
    monkeypatch.setenv("PCA_MACHINE", "podman-machine-default")
    fake.set_state("running")
    s, rc = run_in_owner(fake, cmd)
    assert rc != 0 and fake.calls() == [] and fake.state() == "running"
    s.release()
