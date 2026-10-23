"""Codex round 3 of WP11: journal append atomicity, shutdown drain of everything durable, bounded session-loss drain."""
import os
import threading
import time

import pytest

from fakes import Block, FakeClock, err, Response
from harness import leased, make
from pca_helper import journal as J
from pca_helper.journal import Journal
from pca_helper.store import Store
from test_supervisor import poll_resp


def _fail_nth_open(monkeypatch, n):
    """Failure injection INSIDE Journal._append: the n-th open() of the journal file fails."""
    real, state = os.open, {"i": 0}

    def fake(path, *a, **k):
        if str(path).endswith("journal.jsonl"):
            state["i"] += 1
            if state["i"] == n:
                raise OSError("injected open failure")
        return real(path, *a, **k)
    monkeypatch.setattr(J.os, "open", fake)
    return state


# ------------------------------------------------------------------ 1. memory only after a complete write + fsync
def test_failed_cleanup_append_does_not_leave_the_cleanup_in_memory_so_the_retry_persists_it(tmp_path, monkeypatch):
    s = Store(tmp_path / "home")
    d = s.job_stage_dir("job1")
    (d / "in").mkdir(parents=True)
    j = Journal(s.journal_dir, FakeClock())
    j.add_job("job1", "pca-job-job1", str(d), "")
    _fail_nth_open(monkeypatch, 1)  # the very next append (the cleanup record of job_end) fails
    with pytest.raises(OSError):
        j.job_end("job1")
    assert j.pending_cleanups() == [] and [x["jobId"] for x in j.open_jobs()] == ["job1"], "nothing applied from a failed append"
    monkeypatch.undo()
    j.job_end("job1")  # the retry must persist BOTH records
    j2 = Journal(s.journal_dir, FakeClock())  # crash replay
    assert [c["jobId"] for c in j2.pending_cleanups()] == ["job1"] and j2.open_jobs() == []
    h = make(tmp_path)
    h.sup.recover()
    assert not d.exists()


def test_failure_between_cleanup_and_job_end_records_still_replays_a_pending_cleanup(tmp_path, monkeypatch):
    s = Store(tmp_path / "home")
    j = Journal(s.journal_dir, FakeClock())
    j.add_job("job1", "pca-job-job1", str(s.job_stage_dir("job1")), "")
    _fail_nth_open(monkeypatch, 2)  # cleanup record ok, job_end record fails
    with pytest.raises(OSError):
        j.job_end("job1")
    monkeypatch.undo()
    j2 = Journal(s.journal_dir, FakeClock())
    assert [c["jobId"] for c in j2.pending_cleanups()] == ["job1"]
    j.job_end("job1")  # retry reuses the pending cleanup and writes only the missing job_end
    assert len(Journal(s.journal_dir, FakeClock()).pending_cleanups()) == 1


def test_partial_write_is_completed_or_isolated_and_later_records_survive(tmp_path, monkeypatch):
    s = Store(tmp_path / "home")
    j = Journal(s.journal_dir, FakeClock())
    real = os.write
    state = {"armed": True}

    def half(fd, data):
        if state["armed"] and len(data) > 10:
            state["armed"] = False
            real(fd, bytes(data[: len(data) // 2]))
            raise OSError("disk full")
        return real(fd, data)
    monkeypatch.setattr(J.os, "write", half)
    with pytest.raises(OSError):
        j.add_cleanup("c1", "j1")
    assert j.pending_cleanups() == []
    j.add_cleanup("c1", "j1")  # retry after the torn half line
    j.add_cleanup("c2", "j2")
    assert [c["cleanupId"] for c in Journal(s.journal_dir, FakeClock()).pending_cleanups()] == ["c1", "c2"]


def test_short_writes_are_looped_until_the_whole_record_is_written(tmp_path, monkeypatch):
    s = Store(tmp_path / "home")
    j = Journal(s.journal_dir, FakeClock())
    real = os.write
    monkeypatch.setattr(J.os, "write", lambda fd, data: real(fd, bytes(data[:7])))  # the OS accepts 7 bytes per call
    j.add_cleanup("c1", "j1")
    monkeypatch.undo()
    assert [c["cleanupId"] for c in Journal(s.journal_dir, FakeClock()).pending_cleanups()] == ["c1"]


def test_fsync_failure_applies_nothing_in_memory(tmp_path, monkeypatch):
    s = Store(tmp_path / "home")
    j = Journal(s.journal_dir, FakeClock())
    monkeypatch.setattr(J, "fsync_fd", lambda fd: (_ for _ in ()).throw(OSError("fsync")))
    with pytest.raises(OSError):
        j.add_cleanup("c1", "j1")
    assert j.pending_cleanups() == []


def test_lease_survives_a_journal_failure_on_add_job(tmp_path, monkeypatch):
    from fakes import lease_response, payload_bytes
    h = make(tmp_path)
    h.phone.push("lease", lease_response(h.clock))
    h.phone.push("payload", Response(200, payload_bytes(), None))
    h.journal.add_job = lambda *a: (_ for _ in ()).throw(OSError("disk"))
    h.sup.lease_once()  # must not raise
    assert h.runner.started == [] and h.sup.active_job_id is None


# ------------------------------------------------------------------ 2. shutdown drains pending cleanups and pending kills after _job clears
def test_shutdown_retries_a_transient_copy_deletion_failure_after_the_job_is_gone(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    real, n = h.store.delete_job_copies, {"i": 0}

    def flaky(job_id):
        n["i"] += 1
        return False if n["i"] == 1 else real(job_id)
    h.store.delete_job_copies = flaky
    handle.finished = {"kind": "fail", "code": "timeout"}
    h.sup.job_step()
    assert h.sup.active_job_id is None and h.journal.pending_cleanups() and h.store.job_stage_dir("job1").exists()
    h.sup.shutdown(threading.Event())
    assert h.journal.pending_cleanups() == [] and not h.store.job_stage_dir("job1").exists()


def test_shutdown_retries_a_journaled_pending_kill_after_the_job_is_gone(tmp_path):
    h = make(tmp_path)
    h.runner.block = Block()
    h.runner.kill_fail_first = True
    from fakes import lease_response, payload_bytes
    h.phone.push("lease", lease_response(h.clock))
    h.phone.push("payload", Response(200, payload_bytes(), None))
    t = threading.Thread(target=h.sup.lease_once)
    t.start()
    assert h.runner.block.entered.wait(5)
    h.phone.push("heartbeat", err(403, "capability_revoked"))
    h.sup.heartbeat_tick()
    h.runner.block.release()
    t.join(5)
    h.runtime.containers.append("pca-job-job1")
    assert h.sup.active_job_id is None and h.journal.kill_pending() == ["pca-job-job1"]
    h.sup.shutdown(threading.Event())
    assert h.journal.kill_pending() == [] and h.runtime.containers == []


def test_a_failed_kill_after_a_result_post_error_keeps_the_job_held_not_released(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    handle.kill_results.extend([False, True])
    handle.force_results.extend([False, True])
    from fakes import RESULTS
    handle.finished = {"kind": "result", "result": RESULTS["allOk"]["result"]}
    h.phone.push("result", ValueError("x"))
    h.sup.job_step()
    assert h.sup.active_job_id == "job1", "container not confirmed gone: the job stays held"
    h.clock.advance(1000)
    h.sup.watchdog_check()
    assert h.sup.active_job_id is None and h.journal.kill_pending() == []


# ------------------------------------------------------------------ 3. bounded session-loss drain, durable ownership, nested teardown
def test_session_loss_drain_is_bounded_and_leaves_durable_retry_ownership(tmp_path):
    from pca_helper.clock import SystemClock
    h = make(tmp_path)
    h.sup.clock = SystemClock()
    h.sup.journal._clock = h.sup.clock
    handle = leased(h)
    handle.kill_results.extend([False] * 1000)
    handle.force_results.extend([False] * 1000)  # the kill never succeeds
    h.sup.SESSION_LOST_BUDGET_S = 0.5
    h.phone.push("poll", err(401, "unauthorized"))
    stop = threading.Event()
    out = []
    t0 = time.monotonic()
    t = threading.Thread(target=lambda: out.append(h.sup.run(stop)))
    t.start()
    t.join(10)
    assert not t.is_alive() and out == ["session_lost"] and time.monotonic() - t0 < 6
    assert h.journal.kill_pending() == ["pca-job-job1"], "the next start still owns the retry"


def test_watchdog_and_heartbeat_threads_are_torn_down_even_when_shutdown_raises(tmp_path):
    h = make(tmp_path)
    h.sup.clock = __import__("pca_helper.clock", fromlist=["SystemClock"]).SystemClock()
    before = threading.active_count()

    def boom(*a, **k):
        raise RuntimeError("shutdown failed")
    h.sup.shutdown = boom
    stop = threading.Event()
    stop.set()
    with pytest.raises(RuntimeError):
        h.sup.run(stop)
    time.sleep(0.3)
    assert threading.active_count() == before
