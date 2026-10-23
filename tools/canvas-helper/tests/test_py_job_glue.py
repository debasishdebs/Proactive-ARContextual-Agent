"""WP13: the job glue (`jobs_py.PyJobGlue`) through the real runner and supervisor against the FAKE podman: outputs.json -> D1 result (a float result is
posted as exact text with its hash), schema re-check, ids exactly once, and the stage dir and outputs are gone after the result commit."""
import hashlib
import json
import time

import pytest

import protocol_loader as L
from fakes import FakePhone, PAYLOADS, Response, lease_response, payload_bytes
from pca_helper import cli
from pca_helper.expiry import ArtifactExpiry
from pca_helper.jobs_py import PyJobGlue
from pca_helper.runner import JobRunner, default_glue
from pca_helper.supervisor import Supervisor
from test_export_lifecycle import Probes, assert_clean
from wp12_env import env, fake  # noqa: F401


class Rig:
    def __init__(self, env, glue):
        self.env = env
        self.phone = FakePhone(env.clock)
        self.runner = JobRunner(env.runtime, env.store, env.journal, env.table, env.clock, env.log, probes=Probes(), glue=glue)
        self.sup = Supervisor(self.phone, self.runner, env.journal, env.store, env.clock, env.log, runtime=env.runtime, ready=lambda: True,
                              spawn=lambda f: f(), expiry=ArtifactExpiry(env.journal, env.clock, env.log))

    def run_job(self, plan_outputs, name="oneItem", job_id="job1"):
        self.env.plan(files={"outputs.json": plan_outputs if isinstance(plan_outputs, str) else json.dumps(plan_outputs)})
        self.phone.push("lease", lease_response(self.env.clock, job_id=job_id, name=name))
        self.phone.push("payload", Response(200, payload_bytes(name), None))
        self.sup.lease_once()
        h = self.sup._job.handle
        end = time.monotonic() + 15
        while not h.done() and time.monotonic() < end:
            time.sleep(0.02)
        assert h.done()
        self.sup.job_step()


def env_of(outputs, stats=None):
    return {"helperProtocol": 1, "jobType": "py_exec", "outputs": outputs, "stats": stats or {"ms": 3}}


def ok(i, out):
    return {"id": i, "ok": True, "output": out}


def test_float_result_is_posted_as_exact_text_with_the_matching_hash(env):
    text = ('{"helperProtocol":1,"jobType":"py_exec","outputs":[{"id":"i0","ok":true,"output":{"meta":{"conf":0.5},"score":1e-05,"series":[0.1,-0.0]}},'
            '{"id":"i1","ok":true,"output":{"meta":{"conf":0.5},"score":0.73,"series":[0.1]}}],"stats":{"ms":42}}')
    rig = Rig(env, PyJobGlue())
    rig.run_job(text, name="floatTwo")
    posted = rig.phone.of("result")
    assert len(posted) == 1 and rig.phone.of("fail") == []
    _, _, (job_id, lease_gen, result_hash, canon) = posted[0]
    assert canon == text, "the exact canonical text, floats printed by Python repr"
    assert result_hash == hashlib.sha256(canon.encode("utf-8")).hexdigest() == L.result_hash(canon)
    assert '"score":0.73' in canon and '"score":1e-05' in canon and "-0.0" in canon
    assert json.loads(canon)["outputs"][0]["output"]["series"] == [0.1, -0.0]


def test_outputs_and_stage_are_removed_after_the_result_commit(env):
    rig = Rig(env, PyJobGlue())
    rig.run_job(env_of([ok("i0", {"n": 1})]))
    assert rig.phone.of("result") and rig.sup.active_job_id is None
    assert_clean(env)
    assert not (env.store.stage_dir / "job1" / "export.tar").exists() and not (env.store.stage_dir / "job1" / "extract").exists()


def test_result_not_yet_committed_keeps_the_stage_but_not_the_outputs(env):
    rig = Rig(env, PyJobGlue())
    rig.phone.push("result", Response(500, {"error": "x", "message": "x"}, None))  # the phone has not accepted it: the job stays, outputs already gone
    rig.run_job(env_of([ok("i0", {"n": 1})]))
    assert rig.sup.active_job_id == "job1"
    assert env.volumes() == [] and not (env.store.stage_dir / "job1" / "export.tar").exists() and not (env.store.stage_dir / "job1" / "extract").exists()


def test_an_output_failing_the_schema_becomes_a_per_item_schema_mismatch(env):
    rig = Rig(env, PyJobGlue())
    rig.run_job(env_of([ok("i0", {"n": "not an int"})]))
    canon = rig.phone.of("result")[0][2][3]
    assert json.loads(canon)["outputs"] == [{"code": "schema_mismatch", "id": "i0", "ok": False}]
    assert_clean(env)


@pytest.mark.parametrize("outs", [
    [],  # an id is missing
    [ok("i0", {"n": 1}), ok("i0", {"n": 1})],  # twice
    [ok("i0", {"n": 1}), ok("i7", {"n": 1})],  # unknown id
    [{"id": "i0", "ok": False, "code": "kaboom"}],  # a code outside the shared list
    [{"id": "i0", "ok": True, "output": {"n": 1}, "code": "timeout"}],  # a mixed entry
    ["x"],
])
def test_wrong_ids_or_shapes_are_bad_output_and_post_a_fail(env, outs):
    rig = Rig(env, PyJobGlue())
    rig.run_job(env_of(outs))
    assert rig.phone.of("result") == [] and rig.phone.of("fail")[0][2][2] == "bad_output"
    assert_clean(env)


@pytest.mark.parametrize("text", ["not json", "[]", '{"helperProtocol":1}', '{"helperProtocol":1,"jobType":"py_exec","outputs":[{"id":"i0","ok":true,"output":{"n":NaN}}],"stats":{"ms":1}}',
                                  '{"helperProtocol":1,"helperProtocol":1,"jobType":"py_exec","outputs":[],"stats":{}}',
                                  '{"helperProtocol":2,"jobType":"py_exec","outputs":[],"stats":{"ms":1}}'])
def test_unreadable_or_wrong_envelope_is_bad_output(env, text):
    rig = Rig(env, PyJobGlue())
    rig.run_job(text)
    assert rig.phone.of("fail")[0][2][2] == "bad_output"
    assert_clean(env)


def test_missing_outputs_json_is_bad_output(env):
    rig = Rig(env, PyJobGlue())
    env.plan(files={"other.txt": "x"})
    rig.phone.push("lease", lease_response(env.clock))
    rig.phone.push("payload", Response(200, payload_bytes("oneItem"), None))
    rig.sup.lease_once()
    h = rig.sup._job.handle
    end = time.monotonic() + 15
    while not h.done() and time.monotonic() < end:
        time.sleep(0.02)
    rig.sup.job_step()
    assert rig.phone.of("fail")[0][2][2] == "bad_output"
    assert_clean(env)


def test_a_result_over_the_limit_is_too_large():
    payload = {"items": [{"id": "i0", "input": {}}], "outSchema": {"type": "string"}}
    big = json.dumps(env_of([ok("i0", "x" * 2_200_000)])).encode()
    assert PyJobGlue.build({"outputs.json": big}, payload) == {"kind": "fail", "code": "too_large"}


def test_the_glue_is_bound_per_job_and_the_default_stays_for_other_users(env):
    g = PyJobGlue()
    assert g.build({"outputs.json": b"{}"}, None) == {"kind": "fail", "code": "bad_output"}
    assert g({"outputs.json": b"{}"}) == {"kind": "fail", "code": "bad_output"}, "unbound glue never posts"
    assert default_glue({"outputs.json": b"{}"})["kind"] == "result", "WP12's default glue is untouched"
    bound = g.for_job(PAYLOADS["oneItem"])
    assert bound is not g and bound({"outputs.json": json.dumps(env_of([ok("i0", {"n": 1})])).encode()})["kind"] == "result"


def test_the_cli_runs_jobs_with_this_glue(env, monkeypatch):
    import io
    from fakes import FakeTransport
    from test_protocol_and_cli import FP16, PAIR_OK, POLL_OK
    seen = {}

    class Recorder:
        def __init__(self, *a, **k):
            seen.update(k)

        def start(self, job, payload):
            raise AssertionError("no job in this poll")

    monkeypatch.setattr(cli, "JobRunner", Recorder)
    env.write_config()
    tr = FakeTransport("ab" * 32)
    tr.route("POST", "/api/helper/pair", PAIR_OK)
    tr.route("POST", "/api/helper/poll", POLL_OK)
    assert cli.main(["run", "h:1", FP16, "654321", "--once"], out=io.StringIO(), err=io.StringIO(), home=env.home, transport=tr) == 0
    assert isinstance(seen.get("glue"), PyJobGlue)
