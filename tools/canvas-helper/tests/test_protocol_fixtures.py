import os
import sys

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
