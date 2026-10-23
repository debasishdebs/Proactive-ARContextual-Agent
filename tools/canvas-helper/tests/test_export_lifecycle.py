"""WP12 output export and workspace lifecycle (core plan 4.2), against the FAKE podman: create volume, write, exit, bounded export, extract with
refusals, remove; removal also after failure, cancel, void, deletion cleanup and the orphan sweep; volume names journaled before creation; stage dir
and outputs removed at every terminal outcome."""
import json
import os
import time

import pytest

from fakes import FakePhone, Response, lease_response, payload_bytes
from pca_helper import collector, export, profiles
from pca_helper.expiry import ArtifactExpiry
from pca_helper.runner import JobRunner, build_job_argv, volume_name
from pca_helper.supervisor import Supervisor
from wp12_env import env, fake  # noqa: F401


class Probes:
    def __init__(self, disk=100 << 30, mem=60):
        self.disk, self.mem = disk, mem

    def free_disk_bytes(self, path):
        return self.disk

    def memory_free_percent(self):
        return self.mem

    def opera_running(self):
        return False


def ws_of(env, probes=None, table=None):
    t = table or env.table
    return export.Workspace(env.runtime, env.store, env.journal, t.job_profile("py_exec"), probes or Probes(), t.host, env.log)


def run_container(env, job_id, remaining_ms=100_000):
    p = env.runtime.popen_run(build_job_argv(job_id, remaining_ms=remaining_ms, table=env.table))
    return p.wait(30)


# ------------------------------------------------------------------ create: journal BEFORE creation
def test_volume_name_is_journaled_before_the_volume_is_created(env):
    order = []
    orig_exec, orig_add = env.runtime._exec, env.journal.add_volume
    env.journal.add_volume = lambda j, n: (order.append(("journal", n)), orig_add(j, n))[1]
    env.runtime._exec = lambda args, **k: (order.append(("podman", args[0] + " " + args[1] if len(args) > 1 else args[0])), orig_exec(args, **k))[1]
    ws_of(env).create("job1", b"{}")
    assert order.index(("journal", "pca-job-job1")) < order.index(("podman", "volume create"))
    j = env.journal.open_jobs()[0]
    assert j["volume"] == "pca-job-job1" and j["stage"].endswith("/stage/job1") and j["export"].endswith("/stage/job1/export.tar")
    assert env.volumes() == ["pca-job-job1"] and (env.store.stage_dir / "job1" / "in" / "payload.json").read_bytes() == b"{}"


def test_failed_volume_create_still_leaves_the_journal_entry_and_no_volume(env):
    env.fake.flag("volume_create_fails")
    with pytest.raises(export.WorkspaceFail) as e:
        ws_of(env).create("job1", b"{}")
    assert e.value.code == "runtime_error"
    assert env.journal.job_volumes("job1") == ["pca-job-job1"] and env.volumes() == []


# ------------------------------------------------------------------ the happy path
def test_create_write_exit_bounded_export_extract_remove(env):
    ws = ws_of(env)
    ws.create("job1", b"{}")
    env.plan(files={"outputs.json": '{"helperProtocol":1}', "extra/n.txt": "x"})
    assert run_container(env, "job1") == 0 and env.containers() == []  # the container is gone (--rm); the volume survived its exit
    assert env.volumes() == ["pca-job-job1"]
    files = ws.export_and_collect("job1")
    assert files == {"outputs.json": b'{"helperProtocol":1}', "extra/n.txt": b"x"}
    assert env.volumes() == [], "the volume is removed right after the export"
    assert (env.store.stage_dir / "job1" / "export.tar").is_file() and (env.store.stage_dir / "job1" / "extract" / "outputs.json").is_file()
    assert ws.remove_transient("job1") and not (env.store.stage_dir / "job1" / "export.tar").exists()
    assert not (env.store.stage_dir / "job1" / "extract").exists()


def test_container_argv_that_ran_is_the_one_builder_argv(env):
    ws_of(env).create("job1", b"{}")
    run_container(env, "job1")
    ran = env.runs()[0]
    assert ran == build_job_argv("job1", remaining_ms=100_000, table=env.table)
    assert "-v" in ran and "pca-job-job1:/out:rw" in ran


@pytest.mark.parametrize("plan", [{"links": {"outputs.json": "/etc/passwd"}}, {"fifos": ["pipe"], "files": {"outputs.json": "{}"}}])
def test_export_extract_refuses_symlink_and_special_files_and_still_removes_the_volume(env, plan):
    ws = ws_of(env)
    ws.create("job1", b"{}")
    env.plan(**plan)
    run_container(env, "job1")
    with pytest.raises(export.WorkspaceFail) as e:
        ws.export_and_collect("job1")
    assert e.value.code == "bad_output" and env.volumes() == []
    ws.abandon("job1")
    assert not (env.store.stage_dir / "job1" / "export.tar").exists()


def test_export_is_bounded_and_oversize_is_too_large(env, tmp_path):
    raw = json.load(open(profiles.DEFAULT_PATH))
    raw["profiles"]["py_exec"]["outMaxBytes"] = 4096
    raw["profiles"]["py_exec"]["outMaxFiles"] = 4
    raw["profiles"]["py_exec"]["outNameMaxBytes"] = {"outputs.json": 4096}
    path = tmp_path / "small.json"
    path.write_text(json.dumps(raw))
    table = profiles.load_table(path)
    ws = ws_of(env, table=table)
    ws.create("job1", b"{}")
    env.fake.flag("export_endless")  # a volume that never stops producing bytes
    t0 = time.monotonic()
    with pytest.raises(export.WorkspaceFail) as e:
        ws.export_and_collect("job1")
    assert e.value.code == "too_large" and time.monotonic() - t0 < 10
    assert not (env.store.stage_dir / "job1" / "export.tar").exists(), "no partial export is left"
    assert env.volumes() == []
    # a declared-oversize file inside a normal-sized export is refused by the collector
    env.fake.flag("export_endless", on=False)
    ws.create("job2", b"{}")
    env.plan(big={"outputs.json": 5000})
    run_container(env, "job2")
    with pytest.raises(export.WorkspaceFail) as e:
        ws.export_and_collect("job2")
    assert e.value.code == "too_large" and env.volumes() == []


def test_disk_low_before_export_and_export_failure(env):
    ws = ws_of(env, Probes(disk=1024))
    ws.create("job1", b"{}")
    with pytest.raises(export.WorkspaceFail) as e:
        ws.export_and_collect("job1")
    assert e.value.code == "disk_low" and env.volumes() == [] and not (env.store.stage_dir / "job1" / "export.tar").exists()
    ws2 = ws_of(env)
    ws2.create("job2", b"{}")
    env.fake.flag("export_fails")
    with pytest.raises(export.WorkspaceFail) as e:
        ws2.export_and_collect("job2")
    assert e.value.code == "runtime_error" and env.volumes() == []


# ------------------------------------------------------------------ every terminal outcome through the real supervisor seam
class Rig:
    def __init__(self, env, probes=None):
        self.env = env
        self.phone = FakePhone(env.clock)
        self.runner = JobRunner(env.runtime, env.store, env.journal, env.table, env.clock, env.log, probes=probes or Probes())
        self.sup = Supervisor(self.phone, self.runner, env.journal, env.store, env.clock, env.log, runtime=env.runtime, ready=lambda: True,
                              spawn=lambda f: f(), expiry=ArtifactExpiry(env.journal, env.clock, env.log))

    def lease(self, job_id="job1"):
        self.phone.push("lease", lease_response(self.env.clock, job_id=job_id))
        self.phone.push("payload", Response(200, payload_bytes("oneItem"), None))
        self.sup.lease_once()

    def handle(self):
        return self.sup._job.handle

    def wait_done(self, timeout=15):
        h = self.handle()
        end = time.monotonic() + timeout
        while not h.done() and time.monotonic() < end:
            time.sleep(0.02)
        assert h.done()

    def finish(self):
        self.wait_done()
        self.sup.job_step()

    def poll(self, **body):
        data = {"sessionGen": 1, "caps": {"py_exec": True}, "serverTimeMs": 0, "available": [], "cancel": [], "cleanup": []}
        data.update(body)
        self.phone.push("poll", Response(200, data, None))
        return self.sup.poll_once()


def assert_clean(env, job_id="job1"):
    assert env.volumes() == [] and env.containers() == []
    assert not (env.store.stage_dir / job_id).exists(), "stage dir (inputs, export, extraction tree) removed"
    assert env.journal.open_jobs() == [] and env.journal.pending_cleanups() == []


def test_done_result_posted_then_everything_removed(env):
    rig = Rig(env)
    env.plan(files={"outputs.json": json.dumps({"helperProtocol": 1, "jobType": "py_exec", "outputs": [{"id": "i0", "ok": True, "output": {"n": 1}}],
                                                "stats": {"ms": 3}})})
    rig.lease()
    assert rig.sup.active_job_id == "job1" and (env.store.stage_dir / "job1" / "in" / "payload.json").is_file()
    rig.wait_done()
    assert env.volumes() == [] and not (env.store.stage_dir / "job1" / "export.tar").exists(), "outputs are gone before the result is even posted"
    rig.sup.job_step()
    assert rig.phone.of("result") and rig.sup.active_job_id is None
    assert_clean(env)


def test_failed_container_posts_fail_and_removes_everything(env):
    rig = Rig(env)
    env.plan(rc=1, files={"outputs.json": "{}"})
    rig.lease()
    rig.finish()
    assert rig.phone.of("fail")[0][2][2] == "runtime_error" and rig.phone.of("result") == []
    assert_clean(env)


def test_timeout_and_oom_exit_codes_map_to_fail_codes(env):
    for rc, code, jid in ((124, "timeout", "ja"), (137, "oom", "jb")):
        rig = Rig(env)
        env.plan(rc=rc)
        rig.lease(jid)
        rig.finish()
        assert rig.phone.of("fail")[-1][2][2] == code
        assert_clean(env, jid)


def test_collector_refusal_is_bad_output_and_nothing_stays(env):
    rig = Rig(env)
    env.plan(links={"outputs.json": "/etc/passwd"})
    rig.lease()
    rig.finish()
    assert rig.phone.of("fail")[0][2][2] == "bad_output"
    assert_clean(env)


def test_cancel_kills_the_container_and_removes_volume_and_stage(env):
    rig = Rig(env)
    env.plan(hold=True)
    rig.lease()
    deadline = time.monotonic() + 10
    while env.containers() == [] and time.monotonic() < deadline:
        time.sleep(0.02)
    assert env.containers() == ["pca-job-job1"] and env.volumes() == ["pca-job-job1"]
    assert rig.poll(cancel=["job1"]) == "ok"
    assert rig.sup.active_job_id is None
    assert_clean(env)
    rig.phone.push("poll", Response(200, {"sessionGen": 1, "caps": {"py_exec": True}, "serverTimeMs": 0, "available": [], "cancel": [], "cleanup": []}, None))
    # the job lock was released: the next job can start
    env.plan(files={"outputs.json": "{}"})
    rig.lease("job2")
    assert rig.sup.active_job_id == "job2"
    rig.finish()
    assert_clean(env, "job2")


def test_void_by_denied_heartbeat_removes_everything(env):
    rig = Rig(env)
    env.plan(hold=True)
    rig.lease()
    rig.phone.push("heartbeat", Response(409, {"error": "voided", "message": "x"}, "voided"))
    assert rig.sup.heartbeat_tick() is True
    assert rig.sup.active_job_id is None
    assert_clean(env)


def test_deletion_cleanup_command_removes_a_running_job_and_a_finished_one(env):
    rig = Rig(env)
    env.plan(hold=True)
    rig.lease()
    rig.poll(cleanup=[{"cleanupId": "c1", "jobId": "job1"}])
    assert rig.sup.active_job_id is None and env.journal.acks_to_send() == ["c1"]
    assert_clean(env)
    # a finished job whose result was not posted yet when the cleanup arrives: the result is dropped, nothing stays
    rig2 = Rig(env)
    env.plan(files={"outputs.json": "{}"})
    rig2.lease("job3")
    rig2.wait_done()
    rig2.poll(cleanup=[{"cleanupId": "c2", "jobId": "*"}])
    assert rig2.phone.of("result") == []
    assert_clean(env, "job3")


def test_orphan_sweep_at_start_removes_old_volumes_containers_and_stage_dirs(env):
    os.makedirs(os.path.join(env.fake.dir, "volumes", "pca-job-old1"))
    os.makedirs(os.path.join(env.fake.dir, "volumes", "unrelated"))
    os.makedirs(os.path.join(env.fake.dir, "containers"), exist_ok=True)
    open(os.path.join(env.fake.dir, "containers", "pca-job-old1"), "w").write("1")
    (env.store.stage_dir / "old1").mkdir()
    env.journal.add_job("old1", "pca-job-old1", str(env.store.stage_dir / "old1"), str(env.store.stage_dir / "old1" / "export.tar"))
    rig = Rig(env)
    rig.sup.recover()
    assert env.volumes() == ["unrelated"], "only pca-job-* volumes are swept"
    assert env.containers() == []
    assert not (env.store.stage_dir / "old1").exists() and env.journal.open_jobs() == []


def test_start_failures_leave_nothing_and_release_the_lock(env):
    rig = Rig(env)
    env.fake.flag("run_fails_start")
    rig.lease()
    rig.finish()  # run exits 125 at once: a failed container
    assert rig.phone.of("fail")
    assert_clean(env)
    env.fake.flag("run_fails_start", on=False)
    env.fake.flag("volume_create_fails")
    rig.lease("job2")
    assert rig.phone.of("fail")[-1][2][2] == "runtime_error" and rig.sup.active_job_id is None
    assert_clean(env, "job2")


def test_resource_preflight_codes(env):
    for probes, code in ((Probes(disk=1 << 30), "disk_low"), (Probes(mem=5), "resources_busy")):
        rig = Rig(env, probes=probes)
        rig.lease("jp%s" % code[:2])
        assert rig.phone.of("fail")[-1][2][2] == code
        assert env.runs() == [], "no container was started"


def test_machine_not_running_is_needs_runtime(env):
    env.fake.set_state("stopped")
    rig = Rig(env)
    rig.lease()
    assert rig.phone.of("fail")[0][2][2] == "needs_runtime" and not any(c.startswith("machine start") for c in env.fake.calls())


def test_second_job_is_refused_while_job_lock_is_held(env):
    from pca_helper.runner import _load_lock_module
    lock = _load_lock_module().JobLock()
    lock.acquire()
    try:
        rig = Rig(env)
        rig.lease()
        assert rig.phone.of("fail")[0][2][2] == "resources_busy" and env.runs() == []
    finally:
        lock.release()
