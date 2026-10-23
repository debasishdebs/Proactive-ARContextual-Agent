"""Codex re-review of WP11 (findings 1-5, 7)."""
import threading

import pytest

import protocol_loader as L
from fakes import CONSTS, Block, FakeClock, Response, err, lease_response, payload_bytes
from harness import leased, make
from pca_helper.journal import Journal
from pca_helper.store import Store

T0 = 1_000_000


class Crash(BaseException):
    pass


# ---- finding 1: cleanup durable before the job is closed or released
def test_job_end_persists_the_cleanup_before_closing_the_journal_job(tmp_path):
    s = Store(tmp_path / "home")
    d = s.job_stage_dir("job1")
    (d / "in").mkdir(parents=True)
    j = Journal(s.journal_dir, FakeClock())
    j.add_job("job1", "pca-job-job1", str(d), "")
    orig, calls = j._append, []

    def crashing(rec):
        calls.append(rec["t"])
        if len(calls) == 2:
            raise Crash()  # crash between the two records of job_end
        orig(rec)
    j._append = crashing
    with pytest.raises(Crash):
        j.job_end("job1")
    h = make(tmp_path)  # restart on the same home
    h.sup.recover()
    assert not d.exists(), "the closed-but-not-cleaned job must still be cleaned after the crash"


def test_exception_inside_journal_job_end_keeps_the_job_held_and_retried(tmp_path):
    h = make(tmp_path)
    handle = leased(h)
    real, boom = h.journal.job_end, {"n": 0}

    def flaky(job_id):
        boom["n"] += 1
        if boom["n"] == 1:
            raise OSError("disk")
        return real(job_id)
    h.journal.job_end = flaky
    handle.finished = {"kind": "fail", "code": "timeout"}
    h.sup.job_step()  # must not raise
    assert h.sup.active_job_id == "job1", "retry ownership is kept: the job is not released while its cleanup is not durable"
    assert not h.store.job_stage_dir("job1").exists(), "inputs are removed directly as a fallback"
    h.clock.advance(1000)
    h.sup.watchdog_check()
    assert h.sup.active_job_id is None and h.journal.open_jobs() == []


# ---- finding 2: a poll 401 must not abandon a failed kill
def test_poll_401_keeps_supervising_until_the_container_is_gone(tmp_path):
    from pca_helper.clock import SystemClock
    h = make(tmp_path)
    h.sup.clock = SystemClock()
    h.sup.journal._clock = h.sup.clock
    handle = leased(h)
    handle.kill_results.extend([False, True])
    handle.force_results.extend([False, True])
    h.phone.push("poll", err(401, "unauthorized"))
    stop = threading.Event()
    out = []
    t = threading.Thread(target=lambda: out.append(h.sup.run(stop)))
    t.start()
    t.join(15)
    stop.set()
    assert not t.is_alive() and out == ["session_lost"]
    assert handle.kills >= 2 and h.sup.active_job_id is None, "returned only after the container was confirmed gone"
    assert not h.store.job_stage_dir("job1").exists() and h.journal.kill_pending() == []


# ---- finding 3: an overdue lease is rejected synchronously
def test_slow_lease_older_than_the_liveness_window_is_rejected_before_anything_starts(tmp_path):
    h = make(tmp_path)
    inner = lease_response(h.clock)

    def slow():
        h.clock.advance(4000)  # the answer arrives 4 s after the lease was SENT
        return inner()
    h.phone.push("lease", slow)
    h.phone.push("payload", Response(200, payload_bytes(), None))
    h.sup.lease_once()
    assert h.runner.started == [] and h.sup.active_job_id is None and "payload" not in h.phone.names()
    assert not h.sup.heartbeat_tick()


def test_lease_overdue_while_the_payload_was_fetched_never_starts_the_runner(tmp_path):
    h = make(tmp_path)
    h.phone.push("lease", lease_response(h.clock))

    def slow_payload():
        h.clock.advance(3400)
        h.sup.heartbeat_tick()  # a beat that is never acked cannot refresh the anchor
        h.clock.advance(200)
        return Response(200, payload_bytes(), None)
    h.phone.push("payload", slow_payload)
    h.phone.push("heartbeat", lambda: __import__("fakes").NetworkError("timeout"))
    h.sup.lease_once()
    assert h.runner.started == []


# ---- finding 4: schema bounds are checked on schema nodes only
@pytest.mark.parametrize("rid", ["ok.propertyNamedLikeAKeyword", "ok.enumObjectWithKeywordKey"])
def test_schema_parity_rows_accepted_like_kotlin(rid):
    from pca_helper import protocol as P
    row = {r["id"]: r for r in L.load("schema-subset-rows.json")["contractRows"]}[rid]
    assert row["supported"] is True
    assert L.count_bounds_ok(row["schema"], CONSTS["schemaSubsetMaxCount"]) is True
    errs = set()
    P._schema_errors(row["schema"], errs, CONSTS)
    assert errs == set()


def test_payload_with_a_property_named_minlength_validates():
    from pca_helper import protocol as P
    p = dict(L.load("py_exec-payload-samples.json")["valid"][0]["payload"],
             outSchema={"type": "object", "properties": {"minLength": {"type": "integer"}}, "additionalProperties": False})
    assert P.validate_payload(p) == []


def test_real_count_bounds_are_still_enforced_under_properties_and_items():
    over = CONSTS["schemaSubsetMaxCount"] + 1
    assert L.count_bounds_ok({"type": "object", "properties": {"a": {"type": "string", "maxLength": over}}, "additionalProperties": False}, CONSTS["schemaSubsetMaxCount"]) is False
    assert L.count_bounds_ok({"type": "array", "items": {"type": "array", "maxItems": over}}, CONSTS["schemaSubsetMaxCount"]) is False


# ---- finding 7: result at exactly maxResultBytes plus its envelope
def test_result_exactly_at_the_result_limit_whose_envelope_is_over_the_wire_limit_fails_too_large(tmp_path):
    from fakes import FakeTransport
    from pca_helper import protocol
    from pca_helper.client import PhoneClient
    h = make(tmp_path)
    handle = leased(h)
    tr = FakeTransport("ab" * 32)
    c = PhoneClient(tr, "h", 1, pin="ab" * 8, token="tok")
    c._max_result = CONSTS["maxResultBytes"]  # a phone whose body limit equals the result limit: the envelope can never fit
    h.phone.result = lambda *a: c.result(*a)
    r = {"x": ""}
    r["x"] = "a" * (CONSTS["maxResultBytes"] - len(protocol.canonical_json(r).encode()))
    handle.finished = {"kind": "result", "result": r}
    h.sup.job_step()  # must not raise
    assert tr.sent == [] and h.phone.of("fail")[0][2] == ("job1", 1, "too_large")
    assert h.sup.active_job_id is None and not h.store.job_stage_dir("job1").exists()
