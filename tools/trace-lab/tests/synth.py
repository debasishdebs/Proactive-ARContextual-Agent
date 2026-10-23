"""Builds a SMALL SYNTHETIC trace directory with the frozen schema-1 encoding rules (independent of the decoder under test)."""
import base64
import gzip
import hashlib
import json
import struct
from pathlib import Path

TRACE_ID = "synthetic-trace-0001"
INSTALL = "install-synthetic"


def bits(f):
    return struct.unpack("<i", struct.pack("<f", f))[0]


def _g(t, x, y, z):
    return ["g", t, t, bits(x), bits(y), bits(z)]


def _a(t, x, y, z):
    return ["a", t, t, bits(x), bits(y), bits(z)]


def events_chunk0():
    ev = [
        ["cx", 0, "STILL"], ["rs", 0, False],
        ["pf", 0, "NORMAL", 1000, 2000, None, 3000, 4000, "FULL", False, False, 5000, None, False],
        ["gp", 0, 8, 20, True], ["pl", 0, 1, "abcd1234", "BASIC"],
    ]
    for i in range(10):  # second 0: |g| = 1.0 for all ten samples, accel constant 0
        ev.append(_g(i * 100, 1.0, 0.0, 0.0))
        ev.append(_a(i * 100, 0.0, 0.0, 0.0))
    for i in range(10):  # second 1: |g| alternates 0, 0.75 ; accel x alternates +1, -1 (variance 1.0, 9 crossings over 0.9 s -> 5.0 Hz)
        t = 1000 + i * 100
        ev.append(_g(t, 0.75 if i % 2 else 0.0, 0.0, 0.0))
        ev.append(_a(t, 1.0 if i % 2 else -1.0, 0.0, 0.0))
    ev.append(["ep", 1950, True, "timer", None, None, None])
    ev.append(["sc", 1960, 1, "00000000000000ff", "0123456789abcdef", bits(0.5), True, "STILL", False, False, None, False, 1960])
    return ev


def events_chunk1():
    return [
        _a(2000, 0.0, 0.0, 0.0), _a(2100, 0.0, 0.0, 0.0), _a(2200, 0.0, 0.0, 0.0),  # second 2: three accel samples only (< 8: accVar 0)
        _g(3500, 1.0, 0.0, 0.0),  # second 3: one gyro sample after a 1600 ms gap (dt capped at 100 ms)
        ["gt", 3600, 1, "keep", "ok", True, 3600], ["fl", 3700, "size", 3700], ["bo", 3800, 2], ["bl", 3900, None],
        ["dk", 4000, 3], ["pr", 10000], ["cl", 10000, "stop"],
    ]


def encode_payload(trace_id, seq, events):
    lines = [json.dumps(["PCT1", trace_id, seq, len(events)], separators=(",", ":"))]
    lines += [json.dumps(e, separators=(",", ":")) for e in events]
    return ("\n".join(lines) + "\n").encode("utf-8")


def chunk_file(trace_id, seq, count, raw, install=INSTALL, counter=0, sha=None):
    return json.dumps({
        "v": 1, "traceId": trace_id, "kind": "chunk", "seq": seq, "count": count, "installId": install, "deletionCounter": counter,
        "sha256": sha if sha is not None else hashlib.sha256(raw).hexdigest(),
        "b64": base64.b64encode(gzip.compress(raw, mtime=0)).decode("ascii"), "atMs": 0, "sessionStartMs": 0,
    }, separators=(",", ":"))


def manifest_file(trace_id, shas, event_count, raw_bytes, trace_sha=None, install=INSTALL, counter=0, recorded="COMPLETE", chunk_count=None):
    m = {
        "traceSchema": 1, "traceId": trace_id, "installId": install, "deletionCounter": counter, "revision": 1, "bootId": "1",
        "startElapsedMs": 0, "endElapsedMs": 10000, "chunkCount": chunk_count if chunk_count is not None else len(shas),
        "eventCount": event_count, "rawBytes": raw_bytes,
        "traceSha256": trace_sha if trace_sha is not None else hashlib.sha256("".join(shas).encode("ascii")).hexdigest(),
        "recorded": recorded, "closeReason": "stop", "snapshot": {},
    }
    text = json.dumps(m, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return json.dumps({
        "v": 1, "traceId": trace_id, "kind": "manifest", "seq": 0, "count": 1, "installId": install, "deletionCounter": counter,
        "sha256": hashlib.sha256(text).hexdigest(), "b64": base64.b64encode(text).decode("ascii"), "atMs": 0, "sessionStartMs": 0,
    }, separators=(",", ":"))


def build(directory, with_manifest=True, trace_id=TRACE_ID):
    d = Path(directory)
    d.mkdir(parents=True, exist_ok=True)
    chunks = [events_chunk0(), events_chunk1()]
    raws = [encode_payload(trace_id, i, e) for i, e in enumerate(chunks)]
    shas = [hashlib.sha256(r).hexdigest() for r in raws]
    for i, r in enumerate(raws):
        (d / f"chunk-{i:04d}.json").write_text(chunk_file(trace_id, i, len(raws), r), encoding="utf-8")
    if with_manifest:
        (d / "manifest.json").write_text(manifest_file(trace_id, shas, sum(len(c) for c in chunks), sum(len(r) for r in raws)), encoding="utf-8")
    return d
