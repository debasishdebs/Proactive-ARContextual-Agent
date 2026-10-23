import io
import json
import os
import subprocess
import sys
from pathlib import Path

import protocol_loader as L
from fakes import FakeTransport
from pca_helper import protocol as P
from pca_helper.cli import main

ROOT = Path(__file__).resolve().parent.parent


def test_canonical_hash_matches_shared_vectors_and_loader():
    for v in L.load("hash-vectors.json")["vectors"]:
        assert P.canonical_json(v["payload"]) == v["canonical"]
        assert P.bundle_hash(v["payload"]) == v["sha256"]
    for v in L.load("hash-vectors.json")["normalized"]:
        assert P.bundle_hash(json.loads(v["rawJson"])) == v["sha256"]


def test_validate_payload_matches_shared_samples():
    d = L.load("py_exec-payload-samples.json")
    for s in d["valid"]:
        assert P.validate_payload(s["payload"]) == [], s["name"]
    for s in d["invalid"]:
        assert P.validate_payload(s["payload"]), s["name"]


def test_validate_payload_messages_never_echo_content():
    bad = dict(L.load("py_exec-payload-samples.json")["valid"][0]["payload"], source="SECRET-SOURCE" * 2000)
    msgs = P.validate_payload(bad)
    assert msgs and all("SECRET" not in m for m in msgs)


def test_constants_loaded_from_the_shared_fixture_only():
    assert P.constants() == L.load("constants.json")


def test_job_id_regex_from_fixture():
    d = L.load("job-id.json")
    assert all(P.job_id_ok(s) for s in d["valid"]) and not any(P.job_id_ok(s) for s in d["invalid"])


def test_deletion_class_denials_from_fixture():
    assert set(P.deletion_class_denials()) == set(L.load("constants.json")["deletionClassDenials"])
    assert "not_confirmed" in P.deletion_class_denials()


def test_import_has_no_side_effects(tmp_path):
    env = dict(os.environ, HOME=str(tmp_path), PYTHONDONTWRITEBYTECODE="1")
    r = subprocess.run([sys.executable, "-c", "import pca_helper, pca_helper.cli, pca_helper.supervisor, pca_helper.client, pca_helper.pair, "
                        "pca_helper.journal, pca_helper.expiry, pca_helper.log, pca_helper.store, pca_helper.protocol"],
                       cwd=str(ROOT), env=env, capture_output=True, text=True, timeout=60)
    assert r.returncode == 0 and r.stdout == "" and r.stderr == ""
    assert list(tmp_path.iterdir()) == [], "no ~/.pca-helper created at import"


def run_cli(tmp_path, argv, tr=None):
    out, err = io.StringIO(), io.StringIO()
    code = main(argv, out=out, err=err, home=tmp_path / "home", transport=tr or FakeTransport())
    return code, out.getvalue(), err.getvalue()


def test_cli_pair_without_fingerprint_exits_2_and_sends_nothing(tmp_path):
    tr = FakeTransport()
    code, out, err = run_cli(tmp_path, ["pair", "10.0.0.5:8443", "123456"], tr)
    assert code == 2 and tr.opened == [] and tr.sent == []
    assert "123456" not in out + err


def test_cli_pair_then_run_once_and_never_prints_secrets(tmp_path):
    tr = FakeTransport("ab" * 32)
    tok = "tok_" + "Q7" * 20
    tr.route("POST", "/api/helper/pair", (200, {"token": tok, "helperId": "h1", "sessionGen": 1, "caps": {"py_exec": True}, "constants": {}}))
    code, out, err = run_cli(tmp_path, ["pair", "10.0.0.5:8443", "654321", "abababab"], tr)
    assert code == 0
    tr.route("POST", "/api/helper/poll", (200, {"sessionGen": 1, "caps": {"py_exec": True}, "serverTimeMs": 0, "available": [], "cancel": [], "cleanup": []}))
    code2, out2, err2 = run_cli(tmp_path, ["run", "--once"], tr)
    assert code2 == 0
    everything = out + err + out2 + err2
    for secret in (tok, "654321", "ab" * 32):
        assert secret not in everything
    poll = [s for s in tr.sent if s["path"].endswith("/poll")][0]
    assert poll["headers"]["Authorization"] == "Bearer " + tok
    assert json.loads(poll["body"])["ready"] == {"py_exec": False}, "no runtime in WP11: never advertises ready"


def test_cli_run_without_pairing_fails_cleanly(tmp_path):
    code, out, err = run_cli(tmp_path, ["run", "--once"])
    assert code == 1 and "pair" in (out + err)


def test_cli_run_exits_3_when_session_is_gone(tmp_path):
    tr = FakeTransport("ab" * 32)
    tr.route("POST", "/api/helper/pair", (200, {"token": "t" * 24, "helperId": "h", "sessionGen": 1, "caps": {}, "constants": {}}))
    run_cli(tmp_path, ["pair", "h:1", "1", "abababab"], tr)
    tr.route("POST", "/api/helper/poll", (401, {"error": "unauthorized", "message": "x"}))
    assert run_cli(tmp_path, ["run", "--once"], tr)[0] == 3


def test_cli_doctor_stub_reports_checks_without_secrets(tmp_path):
    code, out, err = run_cli(tmp_path, ["doctor"])
    assert code == 1 and "FAIL" in out and "paired" in out and "runtime" in out


def test_cli_unknown_command_exits_2(tmp_path):
    assert run_cli(tmp_path, ["nope"])[0] == 2
