import json

import pytest

from fakes import FakeClock, FakeRuntime, Response
from harness import leased, make
from pca_helper.expiry import ArtifactExpiry
from pca_helper.journal import CleanupExecutor, Journal
from pca_helper.log import Logger
from pca_helper.store import Store
from test_supervisor import poll_resp

DAY = 24 * 3600 * 1000


class Crash(BaseException):
    pass


def test_cleanup_journal_survives_crash(tmp_path):
    h = make(tmp_path)
    d = h.store.job_stage_dir("job9")
    (d / "in").mkdir(parents=True)
    (d / "in" / "f").write_bytes(b"1")
    h.runtime.volumes.append("pca-job-job9")
    h.phone.push("poll", poll_resp(cleanup=[{"cleanupId": "c1", "jobId": "job9"}]))

    def crash(self, job_id):
        raise Crash()
    orig = CleanupExecutor.execute
    CleanupExecutor.execute = crash
    try:
        with pytest.raises(Crash):
            h.sup.poll_once()
    finally:
        CleanupExecutor.execute = orig
    assert d.exists(), "crash happened before the files were removed"
    assert "c1" in (h.store.journal_dir / "journal.jsonl").read_text(), "persisted BEFORE anything else (and before any ack)"
    assert h.phone.of("poll")[0][2][0]["cleanupAcks"] == []

    # new process: same directory, new objects
    h2 = make(tmp_path)  # same tmp_path -> same home
    h2.runtime.volumes.append("pca-job-job9")
    h2.sup.recover()
    assert not d.exists() and h2.runtime.volumes == []
    h2.sup.poll_once()
    assert h2.phone.of("poll")[0][2][0]["cleanupAcks"] == ["c1"]
    h2.sup.poll_once()
    assert h2.phone.of("poll")[1][2][0]["cleanupAcks"] == [], "acked after the 2xx, not repeated"


def test_cleanup_not_acked_until_files_are_gone(tmp_path):
    h = make(tmp_path)
    d = h.store.job_stage_dir("job9")
    d.mkdir(parents=True)
    h.runtime.volumes.append("pca-job-job9")
    h.runtime.remove_volume = lambda n: False  # the volume cannot be removed yet
    h.phone.push("poll", poll_resp(cleanup=[{"cleanupId": "c1", "jobId": "job9"}]))
    h.sup.poll_once()
    h.sup.poll_once()
    assert h.phone.of("poll")[1][2][0]["cleanupAcks"] == []
    h.runtime.remove_volume = FakeRuntime.remove_volume.__get__(h.runtime)
    h.sup.poll_once()  # retried at the start of the cycle; done now, so the ack goes out in this very poll
    assert h.phone.of("poll")[2][2][0]["cleanupAcks"] == ["c1"]


def test_cleanup_command_idempotent_on_redelivery_and_lost_ack_redelivered(tmp_path):
    from fakes import NetworkError
    h = make(tmp_path)
    cmd = {"cleanupId": "c1", "jobId": "unknown-job"}  # executed even for unknown job ids
    h.phone.push("poll", poll_resp(cleanup=[cmd]), NetworkError("ack lost"), poll_resp(cleanup=[cmd]), poll_resp())
    for _ in range(4):
        h.sup.poll_once()
    acks = [c[2][0]["cleanupAcks"] for c in h.phone.of("poll")]
    assert acks == [[], ["c1"], ["c1"], ["c1"]], "the ack stays pending until a 2xx; the phone's redelivery after it is executed again without error and acked again"


def test_redelivered_acked_command_is_acked_again(tmp_path):
    h = make(tmp_path)
    cmd = {"cleanupId": "c1", "jobId": "j"}
    h.phone.push("poll", poll_resp(cleanup=[cmd]), poll_resp(), poll_resp(cleanup=[cmd]))
    h.sup.poll_once(); h.sup.poll_once(); h.sup.poll_once(); h.sup.poll_once()
    acks = [c[2][0]["cleanupAcks"] for c in h.phone.of("poll")]
    assert acks == [[], ["c1"], [], ["c1"]]


def test_cleanup_star_removes_every_job_dir_artifact_and_volume(tmp_path):
    h = make(tmp_path)
    for j in ("a1", "b2"):
        (h.store.job_stage_dir(j) / "in").mkdir(parents=True)
        h.store.job_artifact_dir(j).mkdir(parents=True)
        h.runtime.volumes.append("pca-job-" + j)
    h.runtime.volumes.append("other-volume")
    h.phone.push("poll", poll_resp(cleanup=[{"cleanupId": "c1", "jobId": "*"}]))
    h.sup.poll_once()
    assert list(h.store.stage_dir.iterdir()) == [] and list(h.store.artifacts_dir.iterdir()) == []
    assert h.runtime.volumes == ["other-volume"]


def test_orphan_container_killed_on_start_before_first_poll(tmp_path):
    h = make(tmp_path)
    h.runtime.containers.append("pca-job-old")
    h.runtime.volumes.append("pca-job-old")
    d = h.store.job_stage_dir("old")
    d.mkdir(parents=True)
    h.journal.add_job("old", "pca-job-old", str(d), "")
    h.sup.recover()
    assert ("kill", "pca-job-old") in h.runtime.events and ("remove", "pca-job-old") in h.runtime.events
    assert h.runtime.volumes == [] and not d.exists() and h.journal.open_jobs() == []
    assert h.phone.calls == [], "recovery happens before the first poll"


def test_journal_tolerates_a_torn_last_line_and_keeps_earlier_records(tmp_path):
    s = Store(tmp_path / "home")
    j = Journal(s.journal_dir, FakeClock())
    j.add_cleanup("c1", "j1")
    with open(s.journal_dir / "journal.jsonl", "ab") as f:
        f.write(b'{"t":"cleanup","cleanupId":"c2","job')
    j2 = Journal(s.journal_dir, FakeClock())
    assert [c["cleanupId"] for c in j2.pending_cleanups()] == ["c1"]
    j2.add_cleanup("c3", "j3")  # appends cleanly after the torn line
    assert [c["cleanupId"] for c in Journal(s.journal_dir, FakeClock()).pending_cleanups()] == ["c1", "c3"]


def test_journal_file_is_0600_and_holds_no_token(tmp_path):
    s = Store(tmp_path / "home")
    j = Journal(s.journal_dir, FakeClock())
    j.add_job("j1", "pca-job-j1", "/s", "/e")
    import os
    assert oct(os.stat(s.journal_dir / "journal.jsonl").st_mode & 0o777) == "0o600"


# ---------------------------------------------------------------- 7-day retention (retention7d)

def test_retention_7d(tmp_path):
    s = Store(tmp_path / "home")
    clock = FakeClock()
    j = Journal(s.journal_dir, clock)
    log = Logger(sink=[].append)
    ex = ArtifactExpiry(j, clock, log)
    f = s.job_artifact_dir("jobA")
    f.mkdir(parents=True)
    (f / "app.apk").write_bytes(b"synthetic")
    created = clock.wall_ms()
    ex.register("art1", str(f))
    assert j.artifacts()[0]["expiresAtMs"] == created + 7 * DAY
    clock.wall += 7 * DAY - 1
    assert ex.sweep() == 0 and f.exists()
    clock.wall += 1
    assert ex.sweep() == 1 and not f.exists()
    assert j.artifacts() == []


def test_retention_7d_metadata_survives_restart_and_start_sweep_removes(tmp_path):
    h = make(tmp_path)
    f = h.store.job_artifact_dir("jobA")
    f.mkdir(parents=True)
    (f / "a").write_bytes(b"1")
    h.expiry.register("art1", str(f))
    h.clock.wall += 7 * DAY + 5
    h2 = make(tmp_path, clock=h.clock)  # restart: a new process reading the same journal
    assert len(h2.journal.artifacts()) == 1
    h2.sup.recover()  # startup sweep
    assert not f.exists()


def test_retention_7d_periodic_sweep(tmp_path):
    h = make(tmp_path)
    f = h.store.job_artifact_dir("jobA")
    f.mkdir(parents=True)
    h.expiry.register("art1", str(f))
    h.sup.maintenance()
    h.clock.wall += 7 * DAY + 1
    h.sup.maintenance()
    assert f.exists(), "the periodic sweep runs on its own interval, not on every call"
    h.clock.advance(3600 * 1000)
    h.clock.wall += 0
    h.sup.maintenance()
    assert not f.exists()


def test_cleanup_command_removes_artifacts_early(tmp_path):
    h = make(tmp_path)
    f = h.store.job_artifact_dir("jobA")
    f.mkdir(parents=True)
    h.expiry.register("art1", str(f), job_id="jobA")
    h.phone.push("poll", poll_resp(cleanup=[{"cleanupId": "c1", "jobId": "jobA"}]))
    h.sup.poll_once()
    assert not f.exists() and h.journal.artifacts() == []
