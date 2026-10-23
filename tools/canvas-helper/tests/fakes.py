"""Fakes for the WP11 tests: clock, phone client, transport, runtime and runner. No real network, no real runtime."""
import json
import threading
from collections import defaultdict, deque

import protocol_loader as L
from pca_helper.client import NetworkError, Response

CONSTS = L.load("constants.json")
PAYLOADS = {s["name"]: s["payload"] for s in L.load("py_exec-payload-samples.json")["valid"]}
RESULTS = {s["name"]: s for s in L.load("py_exec-result-samples.json")["valid"]}


class FakeClock:
    def __init__(self, mono=1_000_000, wall=1_700_000_000_000):
        self.mono = mono
        self.wall = wall
        self.sleeps = []

    def now_ms(self):
        return self.mono

    def wall_ms(self):
        return self.wall

    def advance(self, ms):
        self.mono += ms
        self.wall += ms

    def set_mono(self, ms):
        self.wall += ms - self.mono
        self.mono = ms

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.advance(int(seconds * 1000))


class Block:
    """A scripted item that blocks the caller until released (a hung network call)."""

    def __init__(self, then=None):
        self.event = threading.Event()
        self.entered = threading.Event()
        self.then = then

    def release(self):
        self.event.set()


class FakePhone:
    """Duck-typed PhoneClient. Scripted per method; every call records (name, send_ms, args)."""

    def __init__(self, clock):
        self.clock = clock
        self.calls = []
        self.script = defaultdict(deque)
        self.lock = threading.Lock()

    def push(self, name, *items):
        self.script[name].extend(items)

    def names(self):
        return [c[0] for c in self.calls]

    def of(self, name):
        return [c for c in self.calls if c[0] == name]

    def _do(self, name, *args):
        with self.lock:
            self.calls.append((name, self.clock.now_ms(), args))
            item = self.script[name].popleft() if self.script[name] else self._default(name)
        if isinstance(item, Block):
            item.entered.set()
            item.event.wait(10)
            item = item.then or self._default(name)
        if callable(item):
            item = item()
        if isinstance(item, BaseException):
            raise item
        return item

    def _default(self, name):
        if name == "poll":
            return Response(200, {"sessionGen": 1, "caps": {"py_exec": True}, "serverTimeMs": 0, "available": [], "cancel": [], "cleanup": []}, None)
        if name == "heartbeat":
            return Response(200, {"leaseUntilMs": 0, "serverTimeMs": 0}, None)
        if name == "lease":
            return Response(200, {"job": None}, None)
        if name == "result":
            return Response(200, {"committed": True, "duplicate": False}, None)
        if name == "fail":
            return Response(200, {"ok": True}, None)
        raise AssertionError("no default for " + name)

    def poll(self, body):
        return self._do("poll", json.loads(json.dumps(body)))

    def lease(self, types):
        return self._do("lease", list(types))

    def payload(self, job_id, lease_gen):
        return self._do("payload", job_id, lease_gen)

    def heartbeat(self, job_id, lease_gen):
        return self._do("heartbeat", job_id, lease_gen)

    def result(self, job_id, lease_gen, result_hash, result):
        return self._do("result", job_id, lease_gen, result_hash, result)

    def fail(self, job_id, lease_gen, code):
        return self._do("fail", job_id, lease_gen, code)


def err(status, code):
    return Response(status, {"error": code, "message": "fixed"}, code)


def payload_bytes(name="oneItem"):
    return json.dumps(PAYLOADS[name], separators=(",", ":")).encode("utf-8")


def lease_response(clock, job_id="job1", lease_gen=1, name="oneItem", server_time=5_000_000, deadline_in=130_000, bundle_hash=None, payload_len=None):
    raw = payload_bytes(name)
    return lambda: Response(200, {"job": {"jobId": job_id, "type": "py_exec", "leaseGen": lease_gen, "leaseUntilMs": server_time + 10_000,
                                           "deadlineMs": server_time + deadline_in, "bundleHash": bundle_hash or L.bundle_hash(PAYLOADS[name]),
                                           "payloadBytes": payload_len if payload_len is not None else len(raw)}, "serverTimeMs": server_time}, None)


class FakeHandle:
    def __init__(self, name):
        self.name = name
        self.kills = 0
        self.force_removes = 0
        self.kill_results = deque()
        self.force_results = deque()
        self.finished = None

    def kill(self):
        self.kills += 1
        return self.kill_results.popleft() if self.kill_results else True

    def force_remove(self):
        self.force_removes += 1
        return self.force_results.popleft() if self.force_results else True

    def done(self):
        return self.finished is not None

    def outcome(self):
        return self.finished


class FakeRunner:
    def __init__(self, store=None):
        self.started = []
        self.handles = []
        self.store = store
        self.raises = None
        self.block = None

    def start(self, job, payload):
        self.started.append((job, payload))
        if self.block:
            self.block.entered.set()
            self.block.event.wait(10)
        if self.raises:
            raise self.raises
        if self.store:
            d = self.store.job_stage_dir(job["jobId"]) / "in"
            d.mkdir(parents=True, exist_ok=True)
            (d / "x.bin").write_bytes(b"input")
        h = FakeHandle("pca-job-" + job["jobId"])
        self.handles.append(h)
        return h


class FakeRuntime:
    def __init__(self):
        self.containers = []
        self.volumes = []
        self.events = []
        self.kill_ok = True

    def list_containers(self):
        return list(self.containers)

    def kill(self, name):
        self.events.append(("kill", name))
        return self.kill_ok

    def remove(self, name):
        self.events.append(("remove", name))
        if name in self.containers:
            self.containers.remove(name)
        return True

    def list_volumes(self):
        return list(self.volumes)

    def remove_volume(self, name):
        self.events.append(("rmvol", name))
        if name in self.volumes:
            self.volumes.remove(name)
        return True


class FakeConn:
    def __init__(self, tr, fp):
        self.tr = tr
        self.fp = fp
        self.closed = False

    def peer_fingerprint(self):
        return self.fp

    def request(self, method, path, headers, body, timeout_s):
        self.tr.sent.append({"method": method, "path": path, "headers": dict(headers), "body": body, "timeout": timeout_s})
        item = self.tr.routes[(method, path.split("?")[0])]
        item = item.popleft() if isinstance(item, deque) else item
        if isinstance(item, BaseException):
            raise item
        status, data = item
        return status, (data if isinstance(data, bytes) else json.dumps(data).encode("utf-8"))

    def close(self):
        self.closed = True


class FakeTransport:
    def __init__(self, fp="ab" * 32):
        self.fp = fp
        self.opened = []
        self.sent = []
        self.routes = defaultdict(deque)

    def route(self, method, path, *items):
        self.routes[(method, path)].extend(items)

    def open(self, host, port, timeout_s):
        self.opened.append((host, port, timeout_s))
        return FakeConn(self, self.fp)


__all__ = ["FakeClock", "FakePhone", "FakeHandle", "FakeRunner", "FakeRuntime", "FakeTransport", "Block", "NetworkError", "Response", "err",
           "lease_response", "payload_bytes", "CONSTS", "PAYLOADS", "RESULTS"]
