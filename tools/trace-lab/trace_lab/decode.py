"""Decoder for a pulled trace directory (frozen trace schema 1; see README.md for the format).

Verifies, before returning anything: the file set, every chunk's JSON shape, base64 and gzip bounds, the SHA-256 of the RAW payload, the payload
header (PCT1, traceId, seq, eventCount), the event grammar, time order, the epoch (installId, deletionCounter), and, when a manifest is present,
its own SHA-256, its totals and the chunk checksum chain (traceSha256). Anything wrong raises TraceError with a FIXED text; no file content is ever
put into an error. A trace without manifest.json (never closed cleanly) or with `recorded` PARTIAL is decoded and reported as status PARTIAL.
"""
import base64
import binascii
import hashlib
import json
import re
import struct
import zlib
from pathlib import Path

TAGS = ("g", "a", "cx", "rs", "pf", "gp", "pl", "ep", "sc", "gt", "fl", "bo", "bl", "dk", "pr", "cl")

# Wire field names per tag (after the tag and tMs), in wire order (EventCodec.FIELDS in TraceRecords.kt).
FIELDS = {
    "g": ("sMs", "x", "y", "z"), "a": ("sMs", "x", "y", "z"), "cx": ("state",), "rs": ("on",),
    "pf": ("wake", "settledMs", "quietMs", "walkMs", "stillCapMs", "analysisMs", "tier", "lowPower", "heatSlow", "quickLookMs", "ctxCap", "hard"),
    "gp": ("hamming", "bigChange", "ringAll"), "pl": ("gen", "configHash", "mode"),
    "ep": ("open", "cause", "firstFrameAtMs", "openedAtMs", "outcome"),
    "sc": ("seq", "d", "p", "blur", "passedBlur", "motion", "dwellArmed", "force", "cap", "hard", "nowMs"),
    "gt": ("seq", "outcome", "reason", "encoded", "keptAtMs"), "fl": ("reason", "nowMs"), "bo": ("stillStep",), "bl": ("reason",),
    "dk": ("streak",), "pr": (), "cl": ("reason",),
}

# Value kinds per field: i integer, f float32 given as its signed 32-bit IEEE bits, s string, b boolean, h 16 hex chars; a trailing ? allows null.
_SPEC = {
    "g": ("i", "f", "f", "f"), "a": ("i", "f", "f", "f"), "cx": ("s",), "rs": ("b",),
    "pf": ("s", "i", "i", "i?", "i", "i", "s", "b", "b", "i", "i?", "b"), "gp": ("i", "i", "b"), "pl": ("i", "s", "s"),
    "ep": ("b", "s", "i?", "i?", "s?"), "sc": ("i", "h", "h", "f", "b", "s", "b", "b", "i?", "b", "i"),
    "gt": ("i", "s", "s", "b", "i?"), "fl": ("s", "i"), "bo": ("i",), "bl": ("s?",), "dk": ("i",), "pr": (), "cl": ("s",),
}

CHECKSUM_RULE = "sha256 = lower-case hex SHA-256 of the RAW (gunzipped) payload bytes, not of the base64 and not of the gzip"

MAX_B64_CHARS = 90_000
MAX_RAW_BYTES = 512 * 1024
MAX_FILE_CHARS = MAX_B64_CHARS + 1_024
MAX_CHUNKS = 4_096
MAX_EVENTS_PER_CHUNK = 200_000
MAX_MANIFEST_BYTES = 8_192
MAX_LINE_CHARS = 512

ERRORS = {
    "bad_dir": "the path is not a readable trace directory",
    "bad_file": "a trace file is not a well-formed schema-1 file",
    "file_too_long": "a trace file is longer than the schema limit",
    "extra_file": "the directory holds a file that is not manifest.json or chunk-NNNN.json",
    "missing_chunk": "a chunk the manifest lists is missing",
    "b64_too_long": "a chunk's base64 is longer than the schema limit",
    "b64_invalid": "a chunk's base64 is not padded standard base64",
    "gzip_invalid": "a chunk's gzip data is invalid",
    "gunzip_too_big": "a chunk's payload is larger than the schema limit",
    "sha_mismatch": "a chunk's sha256 does not match its raw payload",
    "bad_payload": "a chunk's payload is not well-formed",
    "header_mismatch": "a chunk's payload header does not match its file",
    "bad_event": "an event line is not well-formed",
    "not_monotonic": "event times go backwards",
    "epoch_mismatch": "installId or deletionCounter differs inside the trace",
    "count_mismatch": "a chunk's count differs from the trace's chunk count",
    "bad_manifest": "the manifest is not well-formed",
    "manifest_sha": "the manifest's sha256 does not match its payload",
    "manifest_mismatch": "the manifest's totals do not match the chunks",
    "trace_sha": "the manifest's traceSha256 does not match the chunk checksum chain",
}


class TraceError(Exception):
    """A refusal. The text is fixed per code; nothing from the files is ever included."""

    def __init__(self, code):
        self.code = code
        super().__init__(f"trace-lab: refused ({code}): {ERRORS[code]}")


class Trace:
    def __init__(self, trace_id, install_id, status, partial_reasons, manifest, chunk_count, event_count, raw_bytes, arrays):
        self.trace_id = trace_id
        self.install_id = install_id
        self.status = status  # "COMPLETE" or "PARTIAL"
        self.partial_reasons = partial_reasons
        self.manifest = manifest  # dict or None
        self.chunk_count = chunk_count  # chunk files decoded
        self.event_count = event_count
        self.raw_bytes = raw_bytes
        self.arrays = arrays  # arrays[tag][column] -> list; every tag has "tMs" plus its FIELDS

    @property
    def start_ms(self):
        return self.manifest["startElapsedMs"] if self.manifest else None


_TRACE_ID = re.compile(r"[a-z0-9-]{8,64}")
_SHA = re.compile(r"[0-9a-f]{64}")
_HEX16 = re.compile(r"[0-9a-f]{16}")
_CHUNK_FILE = re.compile(r"chunk-([0-9]{4})\.json")
_KEYS = {"v", "traceId", "kind", "seq", "count", "installId", "deletionCounter", "sha256", "b64", "atMs", "sessionStartMs"}
_MS_MAX = 2**53 - 1


def _is_int(v, lo=0, hi=_MS_MAX):
    return isinstance(v, int) and not isinstance(v, bool) and lo <= v <= hi


def _read_envelope(path):
    try:
        size = path.stat().st_size
    except OSError:
        raise TraceError("bad_file") from None
    if size > MAX_FILE_CHARS * 4:
        raise TraceError("file_too_long")
    try:
        text = path.read_bytes().decode("utf-8")
    except (OSError, UnicodeDecodeError):
        raise TraceError("bad_file") from None
    if len(text) > MAX_FILE_CHARS:
        raise TraceError("file_too_long")
    try:
        j = json.loads(text)
    except ValueError:
        raise TraceError("bad_file") from None
    if not isinstance(j, dict) or set(j) != _KEYS:
        raise TraceError("bad_file")
    if not (_is_int(j["v"], 1, 1) and isinstance(j["traceId"], str) and _TRACE_ID.fullmatch(j["traceId"]) and isinstance(j["kind"], str)
            and _is_int(j["seq"], 0, MAX_CHUNKS) and _is_int(j["count"], 1, MAX_CHUNKS) and isinstance(j["installId"], str)
            and len(j["installId"]) <= 64 and _is_int(j["deletionCounter"]) and isinstance(j["sha256"], str) and _SHA.fullmatch(j["sha256"])
            and isinstance(j["b64"], str) and _is_int(j["atMs"]) and _is_int(j["sessionStartMs"])):
        raise TraceError("bad_file")
    return j


def _b64(s):
    if len(s) % 4 != 0:
        raise TraceError("b64_invalid")
    try:
        return base64.b64decode(s.encode("ascii"), validate=True)
    except (binascii.Error, ValueError, UnicodeEncodeError):
        raise TraceError("b64_invalid") from None


def _gunzip(data):
    d = zlib.decompressobj(wbits=31)
    try:
        raw = d.decompress(data, MAX_RAW_BYTES + 1)
        if len(raw) > MAX_RAW_BYTES:
            raise TraceError("gunzip_too_big")
        if not d.eof or d.unused_data:
            raise TraceError("gzip_invalid")
    except zlib.error:
        raise TraceError("gzip_invalid") from None
    return raw


def _float(bits):
    if not _is_int(bits, -(2**31), 2**31 - 1):
        raise TraceError("bad_event")
    f = struct.unpack("<f", struct.pack("<i", bits))[0]
    if f != f or f in (float("inf"), float("-inf")):
        raise TraceError("bad_event")
    return f


def _value(kind, v):
    nullable = kind.endswith("?")
    k = kind[0]
    if v is None:
        if nullable:
            return None
        raise TraceError("bad_event")
    if k == "i":
        if not _is_int(v, -(2**53), 2**53):
            raise TraceError("bad_event")
        return v
    if k == "f":
        return _float(v)
    if k == "s":
        if not isinstance(v, str) or len(v) > 64:
            raise TraceError("bad_event")
        return v
    if k == "b":
        if not isinstance(v, bool):
            raise TraceError("bad_event")
        return v
    if k == "h":
        if not isinstance(v, str) or not _HEX16.fullmatch(v):
            raise TraceError("bad_event")
        return v
    raise AssertionError(kind)


def _decode_chunk(env, file_seq):
    """Returns (raw_size, events) for one verified chunk; events = list of (tag, tMs, values tuple)."""
    if env["kind"] != "chunk":
        raise TraceError("bad_file")
    if env["seq"] >= env["count"]:
        raise TraceError("bad_file")
    if env["seq"] != file_seq:
        raise TraceError("header_mismatch")
    if len(env["b64"]) > MAX_B64_CHARS:
        raise TraceError("b64_too_long")
    raw = _gunzip(_b64(env["b64"]))
    if hashlib.sha256(raw).hexdigest() != env["sha256"]:
        raise TraceError("sha_mismatch")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        raise TraceError("bad_payload") from None
    if not text.endswith("\n") or "\r" in text:
        raise TraceError("bad_payload")
    lines = text[:-1].split("\n")
    if any(not ln for ln in lines):
        raise TraceError("bad_payload")
    try:
        head = json.loads(lines[0])
    except ValueError:
        raise TraceError("bad_payload") from None
    if not (isinstance(head, list) and len(head) == 4 and head[0] == "PCT1" and isinstance(head[1], str) and _is_int(head[2]) and _is_int(head[3])):
        raise TraceError("bad_payload")
    if head[1] != env["traceId"] or head[2] != env["seq"] or head[3] != len(lines) - 1 or head[3] > MAX_EVENTS_PER_CHUNK or head[3] < 1:
        raise TraceError("header_mismatch")
    events = []
    prev = 0
    for ln in lines[1:]:
        if len(ln) > MAX_LINE_CHARS:
            raise TraceError("bad_event")
        try:
            e = json.loads(ln)
        except ValueError:
            raise TraceError("bad_event") from None
        if not isinstance(e, list) or len(e) < 2 or not isinstance(e[0], str) or e[0] not in _SPEC:
            raise TraceError("bad_event")
        spec = _SPEC[e[0]]
        if len(e) != 2 + len(spec) or not _is_int(e[1]):
            raise TraceError("bad_event")
        if e[1] < prev:
            raise TraceError("not_monotonic")
        prev = e[1]
        events.append((e[0], e[1], tuple(_value(k, v) for k, v in zip(spec, e[2:]))))
    return len(raw), events


def _read_manifest(path, trace_id):
    env = _read_envelope(path)
    if env["kind"] != "manifest" or env["seq"] != 0 or env["count"] != 1:
        raise TraceError("bad_manifest")
    if len(env["b64"]) > MAX_MANIFEST_BYTES * 2:
        raise TraceError("bad_manifest")
    raw = _b64(env["b64"])
    if len(raw) > MAX_MANIFEST_BYTES:
        raise TraceError("bad_manifest")
    if hashlib.sha256(raw).hexdigest() != env["sha256"]:
        raise TraceError("manifest_sha")
    try:
        m = json.loads(raw.decode("utf-8"))
    except ValueError:
        raise TraceError("bad_manifest") from None
    ints = ("deletionCounter", "startElapsedMs", "endElapsedMs", "eventCount", "rawBytes")
    if not (isinstance(m, dict) and m.get("traceSchema") == 1 and isinstance(m.get("traceId"), str) and isinstance(m.get("installId"), str)
            and all(_is_int(m.get(k)) for k in ints) and _is_int(m.get("chunkCount"), 1, MAX_CHUNKS)
            and isinstance(m.get("traceSha256"), str) and _SHA.fullmatch(m["traceSha256"]) and m.get("recorded") in ("COMPLETE", "PARTIAL")):
        raise TraceError("bad_manifest")
    if m["traceId"] != env["traceId"] or m["traceId"] != trace_id:
        raise TraceError("bad_manifest")
    if m["installId"] != env["installId"] or m["deletionCounter"] != env["deletionCounter"]:
        raise TraceError("epoch_mismatch")
    return m


def load_trace(path):
    """Decode and verify the trace directory at path; returns a Trace or raises TraceError."""
    d = Path(path)
    if not d.is_dir():
        raise TraceError("bad_dir")
    try:
        names = sorted(p.name for p in d.iterdir())
    except OSError:
        raise TraceError("bad_dir") from None
    seqs = {}
    for n in names:
        m = _CHUNK_FILE.fullmatch(n)
        if m:
            seqs[int(m.group(1))] = d / n
        elif n != "manifest.json":
            raise TraceError("extra_file")
        elif not (d / n).is_file():
            raise TraceError("bad_dir")
    has_manifest = "manifest.json" in names
    if not seqs and not has_manifest:
        raise TraceError("bad_dir")
    first = None
    if has_manifest:
        # trace id is the manifest's own header id; chunks must agree with it
        trace_id = _read_envelope(d / "manifest.json")["traceId"]
        manifest = _read_manifest(d / "manifest.json", trace_id)
        want = set(range(manifest["chunkCount"]))
        if set(seqs) - want:
            raise TraceError("extra_file")
        if want - set(seqs):
            raise TraceError("missing_chunk")
    else:
        manifest = None
        trace_id = None
    envs = {}
    for seq in sorted(seqs):
        env = _read_envelope(seqs[seq])
        if first is None:
            first = env
            trace_id = trace_id or env["traceId"]
        if env["traceId"] != trace_id:
            raise TraceError("header_mismatch")
        if env["seq"] != seq:
            raise TraceError("header_mismatch")
        if env["installId"] != first["installId"] or env["deletionCounter"] != first["deletionCounter"]:
            raise TraceError("epoch_mismatch")
        if env["count"] != first["count"]:
            raise TraceError("count_mismatch")
        envs[seq] = env
    reasons = []
    if manifest is not None:
        if (first["installId"], first["deletionCounter"]) != (manifest["installId"], manifest["deletionCounter"]):
            raise TraceError("epoch_mismatch")
        if first["count"] != manifest["chunkCount"]:
            raise TraceError("count_mismatch")
        if manifest["recorded"] != "COMPLETE":
            reasons.append("recorded_partial")
    else:
        reasons.append("no_manifest")
        gaps = [s for s in range(first["count"]) if s not in envs] if first else []
        if gaps:
            reasons.append("missing_chunks:" + ",".join(str(s) for s in gaps))
        if envs and max(envs) >= first["count"]:
            raise TraceError("count_mismatch")
    arrays = {t: {"tMs": [], **{f: [] for f in FIELDS[t]}} for t in TAGS}
    raw_total = event_total = 0
    prev = 0
    for seq in sorted(envs):
        raw_size, events = _decode_chunk(envs[seq], seq)
        raw_total += raw_size
        for tag, t_ms, values in events:
            if t_ms < prev:
                raise TraceError("not_monotonic")
            prev = t_ms
            a = arrays[tag]
            a["tMs"].append(t_ms)
            for name, v in zip(FIELDS[tag], values):
                a[name].append(v)
        event_total += len(events)
    if manifest is not None:
        if manifest["eventCount"] != event_total or manifest["rawBytes"] != raw_total:
            raise TraceError("manifest_mismatch")
        chain = hashlib.sha256("".join(envs[s]["sha256"] for s in sorted(envs)).encode("ascii")).hexdigest()
        if chain != manifest["traceSha256"]:
            raise TraceError("trace_sha")
    return Trace(trace_id, first["installId"] if first else manifest["installId"], "PARTIAL" if reasons else "COMPLETE", reasons,
                 manifest, len(envs), event_total, raw_total, arrays)
