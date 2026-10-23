import json
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import protocol_loader as L  # noqa: E402


def test_folder_holds_exactly_the_seven_files():
    assert sorted(p.name for p in L.PROTOCOL_DIR.iterdir()) == sorted(L.FIXTURE_FILES)


def test_hash_vectors_match_python_canonical_form():
    vectors = L.load("hash-vectors.json")["vectors"]
    assert len(vectors) >= 6
    for v in vectors:
        assert L.canonical_json(v["payload"]) == v["canonical"], v["name"]
        assert L.bundle_hash(v["payload"]) == v["sha256"], v["name"]


def test_constants_arithmetic_and_single_source():
    c = L.load("constants.json")
    assert c["helperProtocol"] == 1
    assert c["helperSelfKillMs"] + c["watchdogTickMs"] + c["schedulingSlackMs"] + c["killBudgetMs"] == 4900
    assert 4900 <= c["maxKillLatencyMs"] < c["leaseMs"]
    assert c["execWaitMs"] == c["pyExecDeadlineMs"] + 5000
    assert (c["confirmTtlMs"], c["queueWaitMs"], c["execWaitMs"]) == (120000, 30000, 135000)
    assert c["py"]["itemsMax"] == 50 and c["maxActiveJobs"] == 1
    assert c["jobIdRegex"] == L.load("job-id.json")["regex"]
    assert "pattern" not in c["schemaSubsetKeywords"]
    assert "tool_build" not in c["jobTypes"]


def test_job_id_vectors():
    d = L.load("job-id.json")
    for s in d["valid"]:
        assert L.job_id_ok(d["regex"], s), repr(s)
    for s in d["invalid"]:
        assert not L.job_id_ok(d["regex"], s), repr(s)


def test_confirm_vectors_tags_and_transitions():
    d = L.load("confirm-vectors.json")
    cases = {c["name"]: c for c in d["cases"]}
    base = cases["accepted"]
    assert base["accept"] is True
    for c in d["cases"]:
        assert L.confirm_message(c["binding"]) == c["expectedMessage"]
        assert L.confirm_tag(d["testKeyHex"], c["binding"]) == c["expectedTag"]
        if c is not base:
            assert c["accept"] is False
            assert c["expectedTag"] != base["expectedTag"], c["name"]
    # QUEUED is reached from AWAITING_CONFIRM only through CONFIRM
    into_queued = [(f, e) for f, e, t in d["transitions"] if t == "QUEUED"]
    assert into_queued == [("AWAITING_CONFIRM", "CONFIRM")]


def test_schema_rows_and_samples_load():
    rows = L.load("schema-subset-rows.json")
    assert len(rows["instanceRows"]) + len(rows["contractRows"]) >= 30
    ids = [r["id"] for r in rows["instanceRows"]] + [r["id"] for r in rows["contractRows"]]
    assert len(ids) == len(set(ids))
    p = L.load("py_exec-payload-samples.json")
    assert p["valid"] and p["invalid"]
    names = {s["name"] for s in p["valid"]}
    r = L.load("py_exec-result-samples.json")
    assert r["valid"] and r["invalid"]
    for s in r["valid"] + r["invalid"]:
        assert s["payload"] in names


def test_normalized_hash_vectors():
    d = L.load("hash-vectors.json")
    for v in d["normalized"]:
        obj = json.loads(v["rawJson"])
        assert L.canonical_json(obj) == v["canonical"], v["name"]
        assert L.bundle_hash(obj) == v["sha256"], v["name"]
    by = {v["name"]: v for v in d["normalized"]}
    assert by["negativeZero"]["sha256"] == by["zero"]["sha256"]
    assert "openssl" in d["provenance"]


def test_rejected_hash_vectors_raise_a_clear_error():
    d = L.load("hash-vectors.json")
    assert len(d["rejected"]) >= 9
    for v in d["rejected"]:
        obj = json.loads(v["rawJson"])
        with pytest.raises(ValueError) as e:
            L.canonical_json(obj)
        assert str(e.value) in ("non-integer number", "unpaired surrogate"), v["name"]


def test_integer_only_contract_python_side():
    for bad in (1.5, 1.0, -0.0, float("nan"), float("inf"), [1, [2.0]], {"a": {"b": [1.25]}}):
        with pytest.raises(ValueError):
            L.canonical_json(bad)
    assert L.canonical_json({"t": True, "f": False, "n": 0, "m": -3}) == '{"f":false,"m":-3,"n":0,"t":true}'
    assert L.canonical_json(-0) == "0"
    with pytest.raises(ValueError):
        L.canonical_json({1: "int key"})


def test_schema_count_bound_is_frozen_and_enforced():
    c = L.load("constants.json")
    assert c["schemaSubsetMaxCount"] == 2147483647
    rows = L.load("schema-subset-rows.json")
    bound = [r for r in rows["contractRows"] if r["id"].startswith("bound.contract.")]
    assert len(bound) >= 6
    for r in bound:
        assert L.count_bounds_ok(r["schema"], c["schemaSubsetMaxCount"]) is r["supported"], r["id"]
    inst = {r["id"] for r in rows["instanceRows"]}
    assert {"bound.maxLength.atMax", "bound.maxLength.overMaxDoesNotThrow", "bound.maxItems.atMax"} <= inst


def test_depth_rows_hit_the_cap_exactly():
    rows = {r["id"]: r for r in L.load("schema-subset-rows.json")["instanceRows"]}

    def depth(x):
        if isinstance(x, list):
            return 1 + max([depth(i) for i in x] or [0])
        if isinstance(x, dict):
            return 1 + max([depth(i) for i in x.values()] or [0])
        return 0
    cap = L.load("constants.json")["py"]["maxInstanceDepth"]
    assert depth(rows["depth.atCap"]["instance"]) == cap and rows["depth.atCap"]["valid"] is True
    assert depth(rows["depth.overCap"]["instance"]) == cap + 1 and rows["depth.overCap"]["valid"] is False
