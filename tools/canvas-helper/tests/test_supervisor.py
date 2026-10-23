import threading

import pytest

import protocol_loader as L
from fakes import Block, FakeClock, NetworkError, Response, err, lease_response, payload_bytes, CONSTS, RESULTS
from harness import leased, make

T0 = 1_000_000


# ---------------------------------------------------------------- policy: anchor, liveness, kill matrix (core plan section 6)

def test_supervisor_policy_kill_at_3500ms_without_ack(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    h.clock.set_mono(T0 + 3500)
    h.sup.watchdog_check()
    assert handle.kills == 0, "exactly at the deadline the job is still alive (kill when now > anchor + 3500)"
    h.clock.set_mono(T0 + 3501)
    h.sup.watchdog_check()
    assert handle.kills == 1
    assert h.sup.active_job_id is None


def test_supervisor_policy_anchor_is_send_time_not_arrival_time(tmp_path):
    h = make(tmp_path, sync=False)
    handle = leased(h)
    h.clock.set_mono(T0 + 1000)
    h.sup.heartbeat_tick()
    beat = h.spawned.pop()
    h.clock.set_mono(T0 + 1900)  # the answer arrives 900 ms after the send
    beat()
    h.clock.set_mono(T0 + 4500)
    h.sup.watchdog_check()
    assert handle.kills == 0
    h.clock.set_mono(T0 + 4501)  # sent at +1000, so the deadline is +4500, not arrival (+1900) + 3500
    h.sup.watchdog_check()
    assert handle.kills == 1


def test_supervisor_policy_delayed_pre_deletion_ack_does_not_extend_liveness(tmp_path):
    h = make(tmp_path, sync=False)
    handle = leased(h)
    h.clock.set_mono(T0 + 1000)
    h.sup.heartbeat_tick()  # t0 = +1000; the phone processes it, deletes at t0+100, delivers the 200 at t0+900
    beat = h.spawned.pop()
    h.clock.set_mono(T0 + 1900)
    beat()
    h.clock.set_mono(T0 + 1000 + 3500)
    h.sup.watchdog_check()
    assert handle.kills == 0
    h.clock.set_mono(T0 + 1000 + 3501)
    h.sup.watchdog_check()
    assert handle.kills == 1


def test_supervisor_policy_one_lost_beat_tolerated_two_not(tmp_path):
    # one lost beat: beats at +1000 (lost) and +2000 (answered at +2600): alive until +5500
    h = make(tmp_path, sync=False)
    handle = leased(h)
    h.clock.set_mono(T0 + 1000)
    h.sup.heartbeat_tick()
    lost = h.spawned.pop()
    h.clock.set_mono(T0 + 2000)
    h.sup.heartbeat_tick()
    ok = h.spawned.pop()
    h.phone.push("heartbeat", NetworkError("timeout"), Response(200, {"leaseUntilMs": 0, "serverTimeMs": 0}, None))
    h.clock.set_mono(T0 + 2000)
    lost()
    h.clock.set_mono(T0 + 2600)
    ok()
    h.clock.set_mono(T0 + 5500)
    h.sup.watchdog_check()
    assert handle.kills == 0
    h.clock.set_mono(T0 + 5501)
    h.sup.watchdog_check()
    assert handle.kills == 1

    # two lost beats: the third (sent at +3000) can only be answered after the deadline of +3500
    h2 = make(tmp_path / "b", sync=False)
    handle2 = leased(h2)
    h2.phone.push("heartbeat", NetworkError("timeout"), NetworkError("timeout"))
    for t in (1000, 2000):
        h2.clock.set_mono(T0 + t)
        h2.sup.heartbeat_tick()
        h2.spawned.pop()()
    h2.clock.set_mono(T0 + 3000)
    h2.sup.heartbeat_tick()
    third = h2.spawned.pop()
    h2.clock.set_mono(T0 + 3501)
    h2.sup.watchdog_check()
    assert handle2.kills == 1
    h2.clock.set_mono(T0 + 3600)
    third()  # the late answer cannot revive the dead job
    assert h2.sup.active_job_id is None


@pytest.mark.parametrize("status,code", [(401, "unauthorized"), (403, "capability_revoked"), (404, "not_found"), (409, "voided"),
                                         (409, "lease_expired"), (409, "not_confirmed")])
def test_supervisor_policy_denied_heartbeat_kills_at_once(tmp_path, status, code):
    h = make(tmp_path)
    handle = leased(h)
    h.clock.set_mono(T0 + 1000)
    h.phone.push("heartbeat", err(status, code))
    h.sup.heartbeat_tick()
    assert handle.kills == 1
    assert h.sup.active_job_id is None
    assert not h.store.job_stage_dir("job1").exists()
    assert "result" not in h.phone.names() and "fail" not in h.phone.names()


def test_supervisor_policy_poll_401_kills_at_once_and_ends_session(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    h.phone.push("poll", err(401, "unauthorized"))
    assert h.sup.poll_once() == "session_lost"
    assert handle.kills == 1


def test_supervisor_policy_blocked_poll_does_not_delay_kill(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    blk = Block()
    h.phone.push("poll", blk)
    t = threading.Thread(target=h.sup.poll_once)
    t.start()
    assert blk.entered.wait(5)
    h.clock.set_mono(T0 + 3501)
    h.sup.watchdog_check()  # runs while the poll thread is still inside the network call
    assert handle.kills == 1
    assert t.is_alive(), "the poll is still blocked: the kill did not wait for it"
    blk.release()
    t.join(5)


def test_supervisor_policy_blocked_result_post_does_not_delay_kill(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    handle.finished = {"kind": "result", "result": RESULTS["allOk"]["result"]}
    blk = Block()
    h.phone.push("result", blk)
    t = threading.Thread(target=h.sup.job_step)
    t.start()
    assert blk.entered.wait(5)
    h.clock.set_mono(T0 + 3501)
    h.sup.watchdog_check()
    assert handle.kills == 1 and t.is_alive()
    blk.release()
    t.join(5)


def test_supervisor_policy_blocked_runner_does_not_stop_heartbeats(tmp_path):
    h = make(tmp_path)
    h.runner.block = Block()
    h.phone.push("lease", lease_response(h.clock))
    h.phone.push("payload", Response(200, payload_bytes(), None))
    t = threading.Thread(target=h.sup.lease_once)
    t.start()
    assert h.runner.block.entered.wait(5)
    h.clock.set_mono(T0 + 1000)
    h.sup.heartbeat_tick()
    assert "heartbeat" in h.phone.names()
    h.runner.block.release()
    t.join(5)


def test_supervisor_policy_heartbeat_schedule_does_not_wait_for_previous_beat_max_two_in_flight(tmp_path):
    h = make(tmp_path, sync=False)
    leased(h)
    for t in (1000, 2000, 3000):
        h.clock.set_mono(T0 + t)
        h.sup.heartbeat_tick()
    assert len(h.spawned) == 2, "two beats in flight, the third tick is skipped"
    h.spawned.pop(0)()
    h.clock.set_mono(T0 + 3400)
    h.sup.heartbeat_tick()
    assert len(h.spawned) == 2


def test_supervisor_policy_second_lease_never_requested(tmp_path):
    h = make(tmp_path)
    leased(h)
    h.phone.push("poll", Response(200, {"sessionGen": 1, "caps": {"py_exec": True}, "serverTimeMs": 0, "available": ["py_exec"], "cancel": [], "cleanup": []}, None))
    h.sup.poll_once()
    assert h.phone.names().count("lease") == 1


def test_supervisor_policy_local_lease_deadline_kills_as_backstop(tmp_path):
    consts = dict(CONSTS, helperSelfKillMs=50_000)  # liveness rule out of the way, the 10 s lease deadline must still fire
    h = make(tmp_path, consts=consts)
    handle = leased(h)
    h.clock.set_mono(T0 + 10_000)
    h.sup.watchdog_check()
    assert handle.kills == 0
    h.clock.set_mono(T0 + 10_001)
    h.sup.watchdog_check()
    assert handle.kills == 1


def test_supervisor_policy_job_deadline_converted_with_server_time_kills(tmp_path):
    h = make(tmp_path, sync=False)
    handle = leased(h, deadline_in=4000)  # absolute phone ms - serverTimeMs = 4000 ms after the lease send
    for t in (1000, 2000, 3000, 4000):
        h.clock.set_mono(T0 + t)
        h.sup.heartbeat_tick()
        h.spawned.pop()()  # all acked: liveness never fires
    h.sup.watchdog_check()
    assert handle.kills == 0
    h.clock.set_mono(T0 + 4001)
    h.sup.watchdog_check()
    assert handle.kills == 1


def test_supervisor_policy_kill_failure_forces_remove_journals_and_retries(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    handle.kill_results.extend([False, True])
    handle.force_results.extend([False, True])
    h.clock.set_mono(T0 + 3501)
    h.sup.watchdog_check()
    assert handle.kills == 1 and handle.force_removes == 1
    assert h.sup.active_job_id == "job1", "container not confirmed gone: the job stays held, no new lease"
    assert h.journal.kill_pending() == ["pca-job-job1"]
    h.clock.set_mono(T0 + 3501 + 999)
    h.sup.watchdog_check()
    assert handle.kills == 1, "retry only every 1 s"
    h.clock.set_mono(T0 + 3501 + 1000)
    h.sup.watchdog_check()
    assert handle.kills == 2
    assert h.sup.active_job_id is None
    assert h.journal.kill_pending() == []


def test_supervisor_policy_constants_equal_protocol_fixture(tmp_path):
    from pca_helper import supervisor as S
    c = L.load("constants.json")
    assert S.constants()["helperSelfKillMs"] == c["helperSelfKillMs"] == 3500
    assert S.constants()["heartbeatIntervalMs"] == c["heartbeatIntervalMs"]
    assert S.constants()["watchdogTickMs"] == c["watchdogTickMs"]
    assert S.constants()["leaseMs"] == c["leaseMs"]


def test_supervisor_policy_kill_budget_arithmetic():
    c = L.load("constants.json")
    assert c["helperSelfKillMs"] + c["watchdogTickMs"] + c["schedulingSlackMs"] + c["killBudgetMs"] == 4900 <= 5000 < c["leaseMs"]


def test_supervisor_policy_watchdog_tick_inside_budget(tmp_path):
    h = make(tmp_path)
    leased(h)
    from pca_helper.supervisor import WATCHDOG_TICK_S
    assert WATCHDOG_TICK_S * 1000 == CONSTS["watchdogTickMs"] == 100


def test_supervisor_policy_cancel_from_poll_kills_and_deletes(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    h.phone.push("poll", Response(200, {"sessionGen": 1, "caps": {"py_exec": True}, "serverTimeMs": 0, "available": [], "cancel": ["job1"], "cleanup": []}, None))
    h.sup.poll_once()
    assert handle.kills == 1 and not h.store.job_stage_dir("job1").exists()
    assert "result" not in h.phone.names()


# ---------------------------------------------------------------- poll / lease loop

def poll_resp(available=(), cancel=(), cleanup=()):
    return Response(200, {"sessionGen": 1, "caps": {"py_exec": True}, "serverTimeMs": 0, "available": list(available), "cancel": list(cancel),
                          "cleanup": list(cleanup)}, None)


def test_poll_body_shape_and_ready_flag(tmp_path):
    h = make(tmp_path, ready=True)
    h.sup.poll_once()
    body = h.phone.of("poll")[0][2][0]
    assert body == {"helperProtocol": 1, "active": [], "cleanupAcks": [], "ready": {"py_exec": True}}
    h2 = make(tmp_path / "x", ready=False)
    h2.sup.poll_once()
    assert h2.phone.of("poll")[0][2][0]["ready"] == {"py_exec": False}


def test_poll_lists_active_job_and_leases_only_when_available_and_ready(tmp_path):
    h = make(tmp_path, ready=False)
    h.phone.push("poll", poll_resp(["py_exec"]))
    h.sup.poll_once()
    assert "lease" not in h.phone.names(), "not ready: no lease"
    h2 = make(tmp_path / "y", ready=True)
    h2.phone.push("poll", poll_resp([]))
    h2.sup.poll_once()
    assert "lease" not in h2.phone.names(), "nothing available: no lease"
    h2.phone.push("poll", poll_resp(["py_exec"]))
    h2.phone.push("lease", lease_response(h2.clock))
    h2.phone.push("payload", Response(200, payload_bytes(), None))
    h2.sup.poll_once()
    assert h2.phone.of("lease")[0][2][0] == ["py_exec"]
    h2.sup.poll_once()
    assert h2.phone.of("poll")[-1][2][0]["active"] == [{"jobId": "job1", "leaseGen": 1}]


def test_lease_verifies_bundle_hash_and_starts_runner_with_payload(tmp_path):
    h = make(tmp_path)
    leased(h)
    assert len(h.runner.started) == 1
    job, payload = h.runner.started[0]
    assert job["jobId"] == "job1" and payload["jobType"] == "py_exec"
    assert h.phone.of("payload")[0][2] == ("job1", 1)
    assert h.journal.open_jobs()[0]["jobId"] == "job1"
    assert h.journal.open_jobs()[0]["volume"] == "pca-job-job1"


def test_bundle_hash_mismatch_refused_fails_bad_output_and_runs_nothing(tmp_path):
    h = make(tmp_path)
    h.phone.push("lease", lease_response(h.clock, bundle_hash="0" * 64))
    h.phone.push("payload", Response(200, payload_bytes(), None))
    h.sup.lease_once()
    assert h.runner.started == []
    assert h.phone.of("fail")[0][2] == ("job1", 1, "bad_output")
    assert h.sup.active_job_id is None


def test_payload_length_mismatch_and_invalid_payload_refused(tmp_path):
    h = make(tmp_path)
    h.phone.push("lease", lease_response(h.clock, payload_len=3))
    h.phone.push("payload", Response(200, payload_bytes(), None))
    h.sup.lease_once()
    assert h.runner.started == [] and h.phone.of("fail")[0][2][2] == "bad_output"


def test_job_id_regex_refused_before_any_path_is_built(tmp_path):
    h = make(tmp_path)
    h.phone.push("lease", lease_response(h.clock, job_id="../evil"))
    h.sup.lease_once()
    assert h.runner.started == [] and "payload" not in h.phone.names() and h.sup.active_job_id is None


def test_result_posted_with_canonical_result_hash_and_copies_deleted_on_accept(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    assert h.store.job_stage_dir("job1").exists()
    result = RESULTS["allOk"]["result"]
    handle.finished = {"kind": "result", "result": result}
    h.sup.job_step()
    call = h.phone.of("result")[0][2]
    assert call[:2] == ("job1", 1) and call[2] == L.bundle_hash(result) and call[3] == result
    assert not h.store.job_stage_dir("job1").exists()
    assert h.sup.active_job_id is None and h.journal.open_jobs() == []


def test_result_too_large_posts_fail_too_large(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    handle.finished = {"kind": "result", "result": {"x": "a" * (2 * 1024 * 1024 + 1)}}
    h.sup.job_step()
    assert "result" not in h.phone.names() and h.phone.of("fail")[0][2][2] == "too_large"


def test_runner_failure_code_is_posted_as_fail(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    handle.finished = {"kind": "fail", "code": "oom"}
    h.sup.job_step()
    assert h.phone.of("fail")[0][2] == ("job1", 1, "oom")
    assert not h.store.job_stage_dir("job1").exists()


def test_unknown_fail_code_is_replaced_by_runtime_error(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    handle.finished = {"kind": "fail", "code": "weird"}
    h.sup.job_step()
    assert h.phone.of("fail")[0][2][2] == "runtime_error"


def test_runner_start_error_posts_fail_with_its_code(tmp_path):
    from pca_helper.supervisor import RunnerError
    h = make(tmp_path)
    h.runner.raises = RunnerError("needs_runtime")
    h.phone.push("lease", lease_response(h.clock))
    h.phone.push("payload", Response(200, payload_bytes(), None))
    h.sup.lease_once()
    assert h.phone.of("fail")[0][2][2] == "needs_runtime" and h.sup.active_job_id is None


def test_result_network_failure_is_retried_while_alive(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    handle.finished = {"kind": "result", "result": RESULTS["allOk"]["result"]}
    h.phone.push("result", NetworkError("timeout"))
    h.sup.job_step()
    assert h.sup.active_job_id == "job1"
    h.clock.advance(1000)
    h.sup.job_step()
    assert h.sup.active_job_id is None and len(h.phone.of("result")) == 2


def test_result_422_posts_fail_bad_output_and_deletes(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    handle.finished = {"kind": "result", "result": RESULTS["allOk"]["result"]}
    h.phone.push("result", err(422, "invalid_result"))
    h.sup.job_step()
    assert h.phone.of("fail")[0][2][2] == "bad_output" and not h.store.job_stage_dir("job1").exists()


# ---------------------------------------------------------------- not_confirmed is a deletion-class denial

def test_not_confirmed_is_deletion_class_denial(tmp_path):
    c = L.load("constants.json")
    assert "not_confirmed" in c["deletionClassDenials"]
    # payload route
    h = make(tmp_path / "p")
    h.phone.push("lease", lease_response(h.clock))
    h.phone.push("payload", err(409, "not_confirmed"))
    h.store.job_stage_dir("job1").mkdir(parents=True)
    (h.store.job_stage_dir("job1") / "leftover").write_bytes(b"x")
    h.sup.lease_once()
    assert len(h.phone.of("payload")) == 1, "not retried"
    h.sup.job_step()
    assert h.runner.started == []
    assert not h.store.job_stage_dir("job1").exists(), "every laptop copy deleted at once"
    assert "result" not in h.phone.names() and "fail" not in h.phone.names()
    assert h.sup.active_job_id is None
    # heartbeat route
    h2 = make(tmp_path / "h")
    handle = leased(h2)
    h2.phone.push("heartbeat", err(409, "not_confirmed"))
    h2.sup.heartbeat_tick()
    assert handle.kills == 1 and not h2.store.job_stage_dir("job1").exists() and "fail" not in h2.phone.names()
    # result route
    h3 = make(tmp_path / "r")
    handle3 = leased(h3)
    handle3.finished = {"kind": "result", "result": RESULTS["allOk"]["result"]}
    h3.phone.push("result", err(409, "not_confirmed"))
    h3.sup.job_step()
    assert not h3.store.job_stage_dir("job1").exists() and "fail" not in h3.phone.names() and len(h3.phone.of("result")) == 1
    assert h3.sup.active_job_id is None


# ---------------------------------------------------------------- inputs deleted on every terminal outcome

def _outcome_done(h, handle):
    handle.finished = {"kind": "result", "result": RESULTS["allOk"]["result"]}
    h.sup.job_step()


def _outcome_fail(h, handle):
    handle.finished = {"kind": "fail", "code": "timeout"}
    h.sup.job_step()


def _outcome_422(h, handle):
    handle.finished = {"kind": "result", "result": RESULTS["allOk"]["result"]}
    h.phone.push("result", err(422, "invalid_result"))
    h.sup.job_step()


def _outcome_cancel(h, handle):
    h.phone.push("poll", poll_resp(cancel=["job1"]))
    h.sup.poll_once()


def _outcome_void(h, handle):
    h.phone.push("heartbeat", err(409, "voided"))
    h.sup.heartbeat_tick()


def _outcome_revoked(h, handle):
    h.phone.push("heartbeat", err(403, "capability_revoked"))
    h.sup.heartbeat_tick()


def _outcome_lease_expired(h, handle):
    h.phone.push("heartbeat", err(409, "lease_expired"))
    h.sup.heartbeat_tick()


def _outcome_liveness(h, handle):
    h.clock.advance(3501)
    h.sup.watchdog_check()


def _outcome_cleanup_command(h, handle):
    h.phone.push("poll", poll_resp(cleanup=[{"cleanupId": "c9", "jobId": "job1"}]))
    h.sup.poll_once()


@pytest.mark.parametrize("outcome", [_outcome_done, _outcome_fail, _outcome_422, _outcome_cancel, _outcome_void, _outcome_revoked,
                                     _outcome_lease_expired, _outcome_liveness, _outcome_cleanup_command])
def test_inputs_deleted_on_job_end(tmp_path, outcome):
    h = make(tmp_path)
    handle = leased(h)
    assert (h.store.job_stage_dir("job1") / "in" / "x.bin").exists()
    outcome(h, handle)
    assert h.sup.active_job_id is None
    assert not h.store.job_stage_dir("job1").exists(), outcome.__name__
    assert h.journal.open_jobs() == []
    assert h.runtime.volumes == [] and ("rmvol", "pca-job-job1") in h.runtime.events


def test_inputs_deleted_when_payload_fetch_fails_the_network(tmp_path):
    h = make(tmp_path)
    h.store.job_stage_dir("job1").mkdir(parents=True)
    h.phone.push("lease", lease_response(h.clock))
    h.phone.push("payload", NetworkError("timeout"))
    h.sup.lease_once()
    assert h.sup.active_job_id is None and not h.store.job_stage_dir("job1").exists()
    assert "fail" not in h.phone.names() and "result" not in h.phone.names()


def test_cleanup_command_for_active_job_drops_a_finished_result(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    handle.finished = {"kind": "result", "result": RESULTS["allOk"]["result"]}
    h.phone.push("poll", poll_resp(cleanup=[{"cleanupId": "c1", "jobId": "job1"}]))
    h.sup.poll_once()
    h.sup.job_step()
    assert "result" not in h.phone.names()


def test_threads_start_and_stop_cleanly(tmp_path):
    import time
    from pca_helper.clock import SystemClock
    h = make(tmp_path)
    h.sup.clock = SystemClock()
    stop = threading.Event()
    before = threading.active_count()
    t = threading.Thread(target=h.sup.run, args=(stop,), kwargs={"max_cycles": None})
    t.start()
    time.sleep(0.3)
    stop.set()
    t.join(5)
    assert not t.is_alive() and threading.active_count() == before


# ---------------------------------------------------------------- review 1: fail-safe watchdog, late start, pending kills, pending cleanups

def test_watchdog_exception_fails_safe_kills_every_job_and_deletes_copies(tmp_path):
    h = make(tmp_path)
    handle = leased(h)

    def boom():
        raise RuntimeError("injected")
    h.sup.watchdog_check = boom
    h.sup.watchdog_guard()  # must not raise
    assert handle.kills == 1 and h.sup.active_job_id is None and not h.store.job_stage_dir("job1").exists()
    assert any(l.startswith("watchdog_error") for l in h.lines) and not any("injected" in l for l in h.lines)
    h.sup.watchdog_guard()  # keeps working afterwards


def test_watchdog_thread_survives_an_exception_and_keeps_ticking(tmp_path):
    import time
    from pca_helper.clock import SystemClock
    h = make(tmp_path)
    h.sup.clock = SystemClock()
    calls = []

    def flaky():
        calls.append(1)
        raise RuntimeError("injected")
    h.sup.watchdog_check = flaky
    stop = threading.Event()
    t = threading.Thread(target=h.sup.run, args=(stop,))
    t.start()
    time.sleep(0.6)
    stop.set()
    t.join(5)
    assert len(calls) >= 3, "the watchdog loop outlived the exception"


def test_denial_during_payload_fetch_starts_no_runner(tmp_path):
    h = make(tmp_path)
    blk = Block(then=Response(200, payload_bytes(), None))
    h.phone.push("lease", lease_response(h.clock))
    h.phone.push("payload", blk)
    t = threading.Thread(target=h.sup.lease_once)
    t.start()
    assert blk.entered.wait(5)
    h.phone.push("heartbeat", err(409, "voided"))
    h.sup.heartbeat_tick()
    assert h.sup.active_job_id is None
    blk.release()
    t.join(5)
    assert h.runner.started == [] and not h.store.job_stage_dir("job1").exists()


def test_late_start_kill_failure_is_journaled_retried_and_blocks_leasing(tmp_path):
    h = make(tmp_path)
    h.runner.block = Block()
    h.runner.kill_fail_first = True
    h.phone.push("lease", lease_response(h.clock))
    h.phone.push("payload", Response(200, payload_bytes(), None))
    t = threading.Thread(target=h.sup.lease_once)
    t.start()
    assert h.runner.block.entered.wait(5)
    h.phone.push("heartbeat", err(403, "capability_revoked"))
    h.sup.heartbeat_tick()  # denial while the runner is still starting
    h.runner.block.release()
    t.join(5)
    handle = h.runner.handles[0]
    assert handle.kills >= 1 and handle.force_removes >= 1
    assert h.journal.kill_pending() == ["pca-job-job1"]
    h.runtime.containers.append("pca-job-job1")
    # no second lease while a container may still be running
    h.phone.push("poll", poll_resp(["py_exec"]))
    h.sup.poll_once()
    assert h.phone.names().count("lease") == 1
    # retried every 1 s by the watchdog
    n = handle.kills + h.runtime.events.count(("kill", "pca-job-job1"))
    h.clock.advance(999)
    h.sup.watchdog_check()
    assert handle.kills + h.runtime.events.count(("kill", "pca-job-job1")) == n
    h.clock.advance(1)
    h.sup.watchdog_check()
    assert ("kill", "pca-job-job1") in h.runtime.events and ("remove", "pca-job-job1") in h.runtime.events
    assert h.journal.kill_pending() == [] and not h.store.job_stage_dir("job1").exists()


def test_pending_cleanup_blocks_leasing_so_a_star_cleanup_cannot_hit_a_new_job(tmp_path):
    h = make(tmp_path)
    h.runtime.volumes.append("pca-job-old")
    h.runtime.remove_volume = lambda n: False  # the star cleanup keeps failing
    h.phone.push("poll", poll_resp(cleanup=[{"cleanupId": "c1", "jobId": "*"}]), poll_resp(["py_exec"]))
    h.sup.poll_once()
    h.sup.poll_once()
    assert "lease" not in h.phone.names()


def test_orphan_sweep_logs_the_number_checked_not_the_survivors(tmp_path):
    h = make(tmp_path)
    h.runtime.containers.extend(["pca-job-a", "pca-job-b"])
    h.sup.recover()
    assert any(l.startswith("orphan_sweep") and "containers_checked=2" in l for l in h.lines)


# ---------------------------------------------------------------- WP6 review finding 13: the result envelope limit

def _envelope_len(result):
    import json
    return len(json.dumps({"leaseGen": 1, "resultHash": "0" * 64, "result": result}, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))


def _result_of_envelope_size(n):
    r = {"x": ""}
    r["x"] = "a" * (n - _envelope_len(r))
    assert _envelope_len(r) == n
    return r


def test_result_body_limit_comes_from_constants_and_mirrors_the_phone():
    assert CONSTS["resultBodyMaxBytes"] == 2 * 1024 * 1024 + 4096 == 2101248  # Kotlin CanvasServer.MAX_RESULT_BODY


def test_client_result_envelope_limit_minus_one_limit_and_plus_one(tmp_path):
    from fakes import FakeTransport
    from pca_helper.client import BodyTooLarge, PhoneClient
    lim = CONSTS["resultBodyMaxBytes"]
    for delta, ok in ((-1, True), (0, True), (1, False)):
        tr = FakeTransport("ab" * 32)
        tr.route("POST", "/api/helper/jobs/j/result", (200, {"committed": True, "duplicate": False}))
        c = PhoneClient(tr, "h", 1, pin="ab" * 8, token="tok")
        r = _result_of_envelope_size(lim + delta)
        if ok:
            assert c.result("j", 1, "0" * 64, r).status == 200 and len(tr.sent[0]["body"]) == lim + delta
        else:
            with pytest.raises(BodyTooLarge):
                c.result("j", 1, "0" * 64, r)
            assert tr.sent == []


def _real_result_client(h):
    from fakes import FakeTransport
    from pca_helper.client import PhoneClient
    tr = FakeTransport("ab" * 32)
    tr.route("POST", "/api/helper/jobs/job1/result", (200, {"committed": True, "duplicate": False}))
    c = PhoneClient(tr, "h", 1, pin="ab" * 8, token="tok")
    h.phone.result = lambda *a: (h.phone.calls.append(("result", h.clock.now_ms(), a)), c.result(*a))[1]
    return tr


def test_result_at_the_2mib_result_limit_is_posted_not_a_crash(tmp_path):
    from pca_helper import protocol
    h = make(tmp_path)
    handle = leased(h)
    tr = _real_result_client(h)
    r = {"x": ""}
    r["x"] = "a" * (CONSTS["maxResultBytes"] - len(protocol.canonical_json(r).encode()))
    assert len(protocol.canonical_json(r).encode()) == CONSTS["maxResultBytes"]
    handle.finished = {"kind": "result", "result": r}
    h.sup.job_step()
    assert len(tr.sent) == 1 and h.sup.active_job_id is None and "fail" not in h.phone.names()


def test_oversize_result_fails_the_job_cleans_up_and_never_ends_the_helper(tmp_path):
    from pca_helper import protocol
    h = make(tmp_path)
    handle = leased(h)
    tr = _real_result_client(h)
    r = {"x": "a" * CONSTS["maxResultBytes"]}  # canonical size is over 2 MiB
    handle.finished = {"kind": "result", "result": r}
    h.sup.job_step()  # must not raise
    assert tr.sent == [] and h.phone.of("fail")[0][2] == ("job1", 1, "too_large")
    assert h.sup.active_job_id is None and not h.store.job_stage_dir("job1").exists() and h.journal.open_jobs() == []
    h.sup.watchdog_guard()  # still alive


@pytest.mark.parametrize("exc", [ValueError("x"), OSError("disk")])
def test_any_value_or_os_error_in_the_result_post_path_fails_the_job_safe(tmp_path, exc):
    h = make(tmp_path)
    handle = leased(h)
    handle.finished = {"kind": "result", "result": RESULTS["allOk"]["result"]}
    h.phone.push("result", exc)
    h.sup.job_step()  # must not raise
    assert h.phone.of("fail")[0][2] == ("job1", 1, "bad_output")
    assert h.sup.active_job_id is None and not h.store.job_stage_dir("job1").exists()


def test_result_post_error_and_fail_post_error_still_ends_the_job_without_raising(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    handle.finished = {"kind": "result", "result": RESULTS["allOk"]["result"]}
    h.phone.push("result", ValueError("x"))
    h.phone.push("fail", OSError("disk"))
    h.sup.job_step()
    assert h.sup.active_job_id is None and not h.store.job_stage_dir("job1").exists()
