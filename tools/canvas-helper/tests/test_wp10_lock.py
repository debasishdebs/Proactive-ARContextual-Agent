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
