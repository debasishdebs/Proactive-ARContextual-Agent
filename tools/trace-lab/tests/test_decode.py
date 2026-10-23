import base64
import hashlib
import json
import pytest
import synth
from trace_lab import decode
from trace_lab.decode import TraceError, load_trace


def test_decode_chunks(tmp_path):
    t = load_trace(synth.build(tmp_path / "t"))
    assert t.trace_id == synth.TRACE_ID and t.status == "COMPLETE" and t.partial_reasons == []
    assert t.chunk_count == 2 and t.event_count == len(synth.events_chunk0()) + len(synth.events_chunk1())
    assert set(t.arrays) == set(decode.TAGS) and len(decode.TAGS) == 16
    g = t.arrays["g"]
    assert len(g["tMs"]) == 21 and g["tMs"][:3] == [0, 100, 200] and g["tMs"][-1] == 3500
    assert g["x"][0] == 1.0 and g["x"][11] == 0.75 and g["y"][0] == 0.0 and g["sMs"][10] == 1000
    assert len(t.arrays["a"]["tMs"]) == 23 and t.arrays["a"]["x"][11] == 1.0 and t.arrays["a"]["x"][10] == -1.0
    assert t.arrays["cx"]["state"] == ["STILL"] and t.arrays["rs"]["on"] == [False]
    assert t.arrays["pf"]["walkMs"] == [None] and t.arrays["pf"]["quickLookMs"] == [5000]
    assert t.arrays["gp"]["ringAll"] == [True] and t.arrays["pl"]["configHash"] == ["abcd1234"]
    assert t.arrays["ep"]["cause"] == ["timer"] and t.arrays["sc"]["d"] == ["00000000000000ff"] and t.arrays["sc"]["blur"] == [0.5]
    assert t.arrays["gt"]["outcome"] == ["keep"] and t.arrays["bl"]["reason"] == [None] and t.arrays["dk"]["streak"] == [3]
    assert t.arrays["pr"]["tMs"] == [10000] and t.arrays["cl"]["reason"] == ["stop"] and t.arrays["fl"]["reason"] == ["size"]
    assert t.arrays["bo"]["stillStep"] == [2]


def _dir(tmp_path):
    return synth.build(tmp_path / "t")


def _expect(d, code):
    with pytest.raises(TraceError) as e:
        load_trace(d)
    assert e.value.code == code
    assert str(e.value) == f"trace-lab: refused ({code}): {decode.ERRORS[code]}"


def _rewrite(d, name, fn):
    p = d / name
    j = json.loads(p.read_text())
    fn(j)
    p.write_text(json.dumps(j, separators=(",", ":")))


def test_chunk_sha_mismatch_refused(tmp_path):
    # one flipped payload byte inside the gzip stream (b64 flip of the stored deflate data keeps valid base64)
    d = _dir(tmp_path)
    import gzip
    p = d / "chunk-0000.json"
    j = json.loads(p.read_text())
    raw = bytearray(gzip.decompress(base64.b64decode(j["b64"])))
    raw[-3] ^= 0x01  # still valid UTF-8 text, wrong content (a digit in the last line)
    j["b64"] = base64.b64encode(gzip.compress(bytes(raw), mtime=0)).decode()
    p.write_text(json.dumps(j, separators=(",", ":")))
    _expect(d, "sha_mismatch")


def test_wrong_sha_refused(tmp_path):
    d = _dir(tmp_path)
    _rewrite(d, "chunk-0001.json", lambda j: j.update(sha256="0" * 64))
    _expect(d, "sha_mismatch")


def test_sha_over_b64_is_not_accepted(tmp_path):
    d = _dir(tmp_path)
    _rewrite(d, "chunk-0001.json", lambda j: j.update(sha256=hashlib.sha256(j["b64"].encode()).hexdigest()))
    _expect(d, "sha_mismatch")


def test_regzipped_altered_payload_with_old_sha_refused(tmp_path):
    d = _dir(tmp_path)
    ev = synth.events_chunk1()
    ev[3] = synth._g(3500, 0.5, 0.0, 0.0)
    raw = synth.encode_payload(synth.TRACE_ID, 1, ev)
    old = json.loads((d / "chunk-0001.json").read_text())["sha256"]
    (d / "chunk-0001.json").write_text(synth.chunk_file(synth.TRACE_ID, 1, 2, raw, sha=old))
    _expect(d, "sha_mismatch")


def test_swapped_chunk_refused(tmp_path):
    d = _dir(tmp_path)
    a, b = (d / "chunk-0000.json"), (d / "chunk-0001.json")
    ta, tb = a.read_text(), b.read_text()
    a.write_text(tb); b.write_text(ta)
    _expect(d, "header_mismatch")


def test_header_wrong_seq_traceid_count_refused(tmp_path):
    d = _dir(tmp_path)
    ev = synth.events_chunk1()
    for label, trace_id, seq, header_events in (("seq", synth.TRACE_ID, 5, ev), ("trace", "other-trace-9999", 1, ev)):
        raw = synth.encode_payload(trace_id, seq, header_events)
        (d / "chunk-0001.json").write_text(synth.chunk_file(synth.TRACE_ID, 1, 2, raw))
        _expect(d, "header_mismatch")
    raw = synth.encode_payload(synth.TRACE_ID, 1, ev).replace(b",11]", b",12]", 1)
    assert b",12]" in raw
    (d / "chunk-0001.json").write_text(synth.chunk_file(synth.TRACE_ID, 1, 2, raw))
    _expect(d, "header_mismatch")


def test_wrong_trace_sha_refused(tmp_path):
    d = _dir(tmp_path)
    j = json.loads((d / "manifest.json").read_text())
    m = json.loads(base64.b64decode(j["b64"]))
    m["traceSha256"] = "1" * 64
    text = json.dumps(m, sort_keys=True, separators=(",", ":")).encode()
    j["b64"] = base64.b64encode(text).decode(); j["sha256"] = hashlib.sha256(text).hexdigest()
    (d / "manifest.json").write_text(json.dumps(j, separators=(",", ":")))
    _expect(d, "trace_sha")


def test_manifest_inconsistent_refused(tmp_path):
    d = _dir(tmp_path)

    def put(**kw):
        j = json.loads((d / "manifest.json").read_text())
        m = json.loads(base64.b64decode(j["b64"]))
        m.update(kw)
        text = json.dumps(m, sort_keys=True, separators=(",", ":")).encode()
        j["b64"] = base64.b64encode(text).decode(); j["sha256"] = hashlib.sha256(text).hexdigest()
        (d / "manifest.json").write_text(json.dumps(j, separators=(",", ":")))
    put(eventCount=999)
    _expect(d, "manifest_mismatch")
    put(eventCount=len(synth.events_chunk0()) + len(synth.events_chunk1()), installId="someone-else")
    _expect(d, "epoch_mismatch")
    put(installId=synth.INSTALL, chunkCount=3)
    _expect(d, "missing_chunk")


def test_manifest_own_sha_refused(tmp_path):
    d = _dir(tmp_path)
    _rewrite(d, "manifest.json", lambda j: j.update(sha256="2" * 64))
    _expect(d, "manifest_sha")


def test_missing_and_extra_chunk_refused(tmp_path):
    d = _dir(tmp_path)
    (d / "chunk-0001.json").unlink()
    _expect(d, "missing_chunk")
    d = synth.build(tmp_path / "t2")
    (d / "chunk-0002.json").write_text((d / "chunk-0001.json").read_text())
    _expect(d, "extra_file")
    d = synth.build(tmp_path / "t3")
    (d / "notes.txt").write_text("x")
    _expect(d, "extra_file")


def test_epoch_mismatch_between_chunks_refused(tmp_path):
    d = _dir(tmp_path)
    _rewrite(d, "chunk-0001.json", lambda j: j.update(deletionCounter=7))
    _expect(d, "epoch_mismatch")


def test_malformed_and_oversize_refused(tmp_path):
    d = _dir(tmp_path)
    secret = "SECRET-CONTENT-XYZ"
    (d / "chunk-0001.json").write_text("{not json " + secret)
    with pytest.raises(TraceError) as e:
        load_trace(d)
    assert e.value.code == "bad_file" and secret not in str(e.value) and secret not in repr(e.value)
    d = synth.build(tmp_path / "t2")
    (d / "chunk-0001.json").write_text(" " * (decode.MAX_FILE_CHARS + 1))
    _expect(d, "file_too_long")
    d = synth.build(tmp_path / "t3")
    _rewrite(d, "chunk-0001.json", lambda j: j.update(b64="A" * 90_004))
    _expect(d, "b64_too_long")
    d = synth.build(tmp_path / "t4")
    _rewrite(d, "chunk-0001.json", lambda j: j.update(b64=j["b64"].replace("=", "")))
    p = json.loads((d / "chunk-0001.json").read_text())
    if len(p["b64"]) % 4 == 0:
        p["b64"] = p["b64"] + "A"  # force an unpadded length
    (d / "chunk-0001.json").write_text(json.dumps(p, separators=(",", ":")))
    _expect(d, "b64_invalid")
    d = synth.build(tmp_path / "t5")
    _rewrite(d, "chunk-0001.json", lambda j: j.update(b64=base64.b64encode(b"not gzip at all").decode()))
    _expect(d, "gzip_invalid")
    d = synth.build(tmp_path / "t6")  # a decompression bomb over the raw bound
    import gzip
    big = base64.b64encode(gzip.compress(b"\n" * (decode.MAX_RAW_BYTES + 10), mtime=0)).decode()
    _rewrite(d, "chunk-0001.json", lambda j: j.update(b64=big))
    _expect(d, "gunzip_too_big")
    d = synth.build(tmp_path / "t7")
    raw = synth.encode_payload(synth.TRACE_ID, 1, synth.events_chunk1()).replace(b'["pr",10000]', b'["zz",10000]')
    (d / "chunk-0001.json").write_text(synth.chunk_file(synth.TRACE_ID, 1, 2, raw))
    _expect(d, "bad_event")
    d = synth.build(tmp_path / "t8")
    ev = synth.events_chunk1(); ev[0], ev[3] = ev[3], ev[0]
    (d / "chunk-0001.json").write_text(synth.chunk_file(synth.TRACE_ID, 1, 2, synth.encode_payload(synth.TRACE_ID, 1, ev)))
    _expect(d, "not_monotonic")
    _expect(tmp_path / "does-not-exist", "bad_dir")


def test_partial_trace_without_manifest_is_decoded(tmp_path):
    d = synth.build(tmp_path / "t", with_manifest=False)
    t = load_trace(d)
    assert t.status == "PARTIAL" and "no_manifest" in t.partial_reasons and t.manifest is None
    assert len(t.arrays["g"]["tMs"]) == 21
    (d / "chunk-0001.json").unlink()  # a gap is tolerated only without a manifest and is reported
    t = load_trace(d)
    assert t.status == "PARTIAL" and "missing_chunks:1" in t.partial_reasons and len(t.arrays["g"]["tMs"]) == 20
    d = synth.build(tmp_path / "t2", with_manifest=False)
    j = json.loads((d / "chunk-0001.json").read_text())
    (d / "chunk-0001.json").write_text(json.dumps({**j, "sha256": "0" * 64}, separators=(",", ":")))
    _expect(d, "sha_mismatch")  # a partial trace still refuses a corrupt chunk


def test_manifest_recorded_partial_is_decoded(tmp_path):
    d = synth.build(tmp_path / "t")
    j = json.loads((d / "manifest.json").read_text())
    m = json.loads(base64.b64decode(j["b64"])); m["recorded"] = "PARTIAL"
    text = json.dumps(m, sort_keys=True, separators=(",", ":")).encode()
    j["b64"] = base64.b64encode(text).decode(); j["sha256"] = hashlib.sha256(text).hexdigest()
    (d / "manifest.json").write_text(json.dumps(j, separators=(",", ":")))
    t = load_trace(d)
    assert t.status == "PARTIAL" and t.partial_reasons == ["recorded_partial"]
