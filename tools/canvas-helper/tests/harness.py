from types import SimpleNamespace

from fakes import FakeClock, FakePhone, FakeRunner, FakeRuntime
from pca_helper.expiry import ArtifactExpiry
from pca_helper.journal import CleanupExecutor, Journal
from pca_helper.log import Logger
from pca_helper.store import Store
from pca_helper.supervisor import Supervisor


def make(tmp_path, ready=True, runtime=True, sync=True, consts=None, clock=None, with_runner_store=True):
    clock = clock or FakeClock()
    store = Store(tmp_path / "home")
    lines = []
    log = Logger(sink=lines.append)
    journal = Journal(store.journal_dir, clock)
    phone = FakePhone(clock)
    runner = FakeRunner(store if with_runner_store else None)
    rt = FakeRuntime() if runtime else None
    spawned = []
    spawn = (lambda f: f()) if sync else spawned.append
    expiry = ArtifactExpiry(journal, clock, log)
    sup = Supervisor(phone, runner, journal, store, clock, log, runtime=rt, ready=lambda: ready, spawn=spawn, consts=consts, expiry=expiry)
    return SimpleNamespace(clock=clock, store=store, log=log, lines=lines, journal=journal, phone=phone, runner=runner, runtime=rt, sup=sup,
                           spawned=spawned, expiry=expiry)


def leased(h, job_id="job1", **kw):
    """Run one lease so that a job is active with a running fake handle."""
    from fakes import lease_response, payload_bytes, Response
    h.phone.push("lease", lease_response(h.clock, job_id=job_id, **kw))
    h.phone.push("payload", Response(200, payload_bytes(kw.get("name", "oneItem")), None))
    h.sup.lease_once()
    return h.runner.handles[-1] if h.runner.handles else None
