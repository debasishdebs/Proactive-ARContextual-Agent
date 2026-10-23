"""Admission lock of core plan section 8.1 (WP10). Everything runs against the FAKE podman of wp10_env."""
import os
import subprocess
import sys
import time

import pytest

import pca_lock
from wp10_env import LOCK_PY, fake  # noqa: F401


def admit(**kw):
    return pca_lock.admit_build(**kw)


def test_third_build_refused(fake):
    a, b = admit(), admit()
    assert {a.slot, b.slot} == {1, 2}
    with pytest.raises(pca_lock.Busy):
        admit()
    a.release()
    c = admit()  # a freed slot is usable again
    assert c.slot in (1, 2)
    b.release(), c.release()


def test_vm_refused_while_any_build_holds(fake):
    b = admit()
    with pytest.raises(pca_lock.Busy):
        pca_lock.VmSession().acquire()
    b.release()
    s = pca_lock.VmSession()
    s.acquire()
    assert s.held
    s.release()


def test_host_build_refused_between_vm_steps(fake):
    fake.set_state("stopped")
    s = pca_lock.VmSession()
    s.acquire()
    assert s.run_step(["podman", "machine", "stop", "pca"]) == 0
    with pytest.raises(pca_lock.Busy):  # machine is stopped, but the owner still holds the lock between steps
        admit()
    assert s.run_step(["podman", "machine", "start", "pca"]) == 0
    with pytest.raises(pca_lock.Busy):
        admit()
    assert s.stop_and_release(max_attempts=2) is True
    admit().release()


def test_failed_stop_keeps_owner_alive_and_exclusion(fake):
    fake.set_state("running")
    fake.flag("stop_fails")
    s = pca_lock.VmSession()
    s.acquire()
    assert s.stop_and_release(max_attempts=2) is False
    assert s.stuck and s.held
    with pytest.raises(pca_lock.Busy):
        admit()
    fake.flag("stop_fails", False)  # recovery: the stop works now
    assert s.stop_and_release(max_attempts=2) is True
    assert not s.held
    admit().release()


def test_failed_stop_owner_process_stays_alive(fake):
    """Real owner process: it must not exit on a failed stop, reports vm_stuck, keeps the exclusion, and recovers when the VM is stopped by hand."""
    fake.set_state("running")
    fake.flag("stop_fails")
    p = subprocess.Popen([sys.executable, LOCK_PY, "vm-session"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                         text=True, env=fake.env())
    try:
        assert p.stdout.readline().strip() == "ready"
        p.stdin.write("stop\n")
        p.stdin.flush()
        assert "vm_stuck" in p.stdout.readline()
        time.sleep(0.3)
        assert p.poll() is None  # alive
        r = subprocess.run([sys.executable, LOCK_PY, "build", "--", "true"], env=fake.env(), capture_output=True, text=True)
        assert r.returncode == 75 and "resources_busy" in r.stderr
        fake.set_state("stopped")  # stopped by hand; the owner re-checks the state
        assert p.wait(timeout=10) == 0
        r = subprocess.run([sys.executable, LOCK_PY, "build", "--", "true"], env=fake.env(), capture_output=True, text=True)
        assert r.returncode == 0, r.stderr
    finally:
        if p.poll() is None:
            p.terminate()
            p.wait(timeout=5)


@pytest.mark.parametrize("state", ["running", "starting", "unknown"])
def test_host_admission_refuses_when_vm_running_or_unknown(fake, state):
    if state == "unknown":
        fake.flag("inspect_garbage")
    else:
        fake.set_state(state)
    with pytest.raises(pca_lock.Busy) as e:
        admit()
    assert "resources_busy" in str(e.value)
    # the refusal must not leave a slot or the shared admission behind
    fake.flag("inspect_garbage", False)
    fake.set_state("stopped")
    a, b = admit(), admit()
    a.release(), b.release()


def test_launcher_crash_with_vm_running_refuses_host_build(fake):
    """The launcher died: the kernel dropped every flock, but the VM is still up. No lock is held, state is running."""
    s = pca_lock.VmSession()
    s.acquire()
    assert s.run_step(["podman", "machine", "start", "pca"]) == 0
    s._fd_close_without_stop()  # simulates process death: lock gone, VM still running
    assert fake.state() == "running"
    with pytest.raises(pca_lock.Busy):
        admit()
    r = subprocess.run([sys.executable, LOCK_PY, "build", "--", "true"], env=fake.env(), capture_output=True, text=True)
    assert r.returncode == 75


def test_stopped_machine_admits_and_absent_machine_admits(fake):
    admit().release()
    fake.set_state("absent")
    admit().release()


def test_helper_jobs_serialized(fake):
    j1 = pca_lock.JobLock()
    j1.acquire()
    with pytest.raises(pca_lock.Busy):
        pca_lock.JobLock().acquire()
    b = admit()  # independent of build slots
    b.release()
    j1.release()
    j2 = pca_lock.JobLock()
    j2.acquire()
    j2.release()


def test_cli_build_runs_command_and_exit_code(fake):
    r = subprocess.run([sys.executable, LOCK_PY, "build", "--", sys.executable, "-c", "import sys; sys.exit(3)"],
                       env=fake.env(), capture_output=True, text=True)
    assert r.returncode == 3


# ------------------------------------------------------------------ owner loop: no buffered-line hang (Codex finding 2)
def _owner(fake, data, idle_s, close):
    import io
    import threading
    r, w = os.pipe()
    os.write(w, data)
    if close:
        os.close(w)
    out = io.StringIO()
    s = pca_lock.VmSession()
    s.acquire()
    t0 = time.monotonic()
    pca_lock._owner_loop(s, os.fdopen(r), out, idle_s=idle_s)
    took = time.monotonic() - t0
    if not close:
        os.close(w)
    return out.getvalue().split("\n"), took, s


def test_owner_two_commands_in_one_write_are_both_processed_at_once(fake):
    fake.set_state("stopped")
    lines, took, s = _owner(fake, b"status\nstatus\nstop\n", idle_s=5, close=False)
    assert lines.count("state stopped") == 2 and "stopped" in lines and took < 3 and not s.held


def test_owner_partial_line_then_eof_stops_without_hanging(fake):
    fake.set_state("stopped")
    lines, took, s = _owner(fake, b"status\nsta", idle_s=30, close=True)
    assert lines.count("state stopped") == 1 and "stopped" in lines and took < 3 and not s.held


def test_owner_partial_line_then_idle_timeout_stops(fake):
    fake.set_state("stopped")
    lines, took, s = _owner(fake, b"sta", idle_s=0.3, close=False)
    assert "stopped" in lines and 0.2 < took < 5 and not s.held


def test_poisoned_parent_environment_never_reaches_a_real_podman(tmp_path, monkeypatch):
    import wp10_env
    monkeypatch.setenv("PCA_PODMAN", "/nonexistent-real-podman")
    monkeypatch.setenv("CONTAINER_HOST", "ssh://real@elsewhere/run/podman.sock")
    monkeypatch.setenv("CONTAINER_CONNECTION", "real")
    monkeypatch.setenv("PODMAN_CONNECTIONS_CONF", "/nonexistent")
    f = wp10_env.Fake(str(tmp_path))
    f.set_state("stopped")
    e = f.env()
    assert e["PCA_PODMAN"] == os.path.join(f.bin, "podman")
    assert not any(k.startswith(("CONTAINER_", "PODMAN_", "DOCKER_")) for k in e)
    r = subprocess.run([sys.executable, wp10_env.LOCK_PY, "state"], env=e, capture_output=True, text=True, timeout=60)
    assert r.stdout.strip() == "stopped" and f.calls() == ["machine inspect pca => stopped"]
    wp10_env.apply_env(monkeypatch, f)  # the in-process fixture path
    assert os.environ["PCA_PODMAN"] == os.path.join(f.bin, "podman") and "CONTAINER_HOST" not in os.environ
    assert pca_lock.machine_state() == "stopped" and len(f.calls()) == 2
