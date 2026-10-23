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


def run_cli(tmp_path, argv, tr=None, prompt=None):
    out, err = io.StringIO(), io.StringIO()
    code = main(argv, out=out, err=err, home=tmp_path / "home", transport=tr or FakeTransport(), getpass=prompt)
    return code, out.getvalue(), err.getvalue()


FP16 = "abababababababab"
PAIR_OK = (200, {"token": "tok_" + "Q7" * 20, "helperId": "h1", "sessionGen": 1, "caps": {"py_exec": True}, "constants": {}})
POLL_OK = (200, {"sessionGen": 1, "caps": {"py_exec": True}, "serverTimeMs": 0, "available": [], "cancel": [], "cleanup": []})


def test_cli_run_without_a_usable_fingerprint_exits_2_and_sends_nothing(tmp_path):
    tr = FakeTransport()
    assert run_cli(tmp_path, ["run", "10.0.0.5:8443"], tr)[0] == 2
    for fp in ("abababab", "a" * 15):
        code, out, err = run_cli(tmp_path, ["run", "10.0.0.5:8443", fp, "123456"], tr)
        assert code == 2 and tr.opened == [] and tr.sent == [] and "123456" not in out + err


def test_cli_run_pairs_then_supervises_in_one_process_and_never_prints_secrets(tmp_path):
    tr = FakeTransport("ab" * 32)
    tok = PAIR_OK[1]["token"]
    tr.route("POST", "/api/helper/pair", PAIR_OK)
    tr.route("POST", "/api/helper/poll", POLL_OK)
    code, out, err = run_cli(tmp_path, ["run", "10.0.0.5:8443", FP16, "654321", "--once"], tr)
    assert code == 0
    for secret in (tok, "654321", "ab" * 32):
        assert secret not in out + err
    poll = [x for x in tr.sent if x["path"].endswith("/poll")][0]
    assert poll["headers"]["Authorization"] == "Bearer " + tok
    assert json.loads(poll["body"])["ready"] == {"py_exec": False}, "no runtime in WP11: never advertises ready"


def test_helper_session_token_is_memory_only_and_a_restart_must_re_pair(tmp_path):
    tok = PAIR_OK[1]["token"]
    tr = FakeTransport("ab" * 32)
    tr.route("POST", "/api/helper/pair", PAIR_OK)
    tr.route("POST", "/api/helper/poll", POLL_OK)
    asked = []
    run_cli(tmp_path, ["run", "h:1", FP16, "--once"], tr, prompt=lambda m: asked.append(m) or "111111")
    home = tmp_path / "home"
    for f in home.rglob("*"):
        if f.is_file():
            assert tok.encode() not in f.read_bytes(), f.name
    st = json.loads((home / "state.json").read_text())
    assert "token" not in st and st["host"] == "h" and st["port"] == 1 and st["pin"] == "ab" * 32
    # a helper restart: the second process has no token and must pair again (a second pairing request with a fresh code)
    tr.route("POST", "/api/helper/pair", PAIR_OK)
    tr.route("POST", "/api/helper/poll", POLL_OK)
    run_cli(tmp_path, ["run", "h:1", FP16, "--once"], tr, prompt=lambda m: asked.append(m) or "222222")
    assert len(asked) == 2 and len([x for x in tr.sent if x["path"].endswith("/pair")]) == 2


def test_legacy_persisted_token_is_deleted_on_start(tmp_path):
    from pca_helper.store import Store
    legacy = "legacy-token-" + "Z" * 30
    Store(tmp_path / "home").save_state({"host": "h", "port": 1, "pin": "ab" * 32, "token": legacy, "helperId": "x"})
    tr = FakeTransport("ab" * 32)
    tr.route("POST", "/api/helper/pair", PAIR_OK)
    tr.route("POST", "/api/helper/poll", POLL_OK)
    run_cli(tmp_path, ["run", "h:1", FP16, "999999", "--once"], tr)
    for f in (tmp_path / "home").rglob("*"):
        if f.is_file():
            assert legacy.encode() not in f.read_bytes(), f.name
    assert "token" not in json.loads((tmp_path / "home" / "state.json").read_text())


def test_legacy_token_is_purged_even_when_pairing_then_fails(tmp_path):
    from pca_helper.store import Store
    Store(tmp_path / "home").save_state({"host": "h", "port": 1, "pin": "ab" * 32, "token": "legacy-" + "Z" * 30})
    tr = FakeTransport("cd" * 32)  # fingerprint mismatch
    assert run_cli(tmp_path, ["run", "h:1", FP16, "1", "--once"], tr)[0] == 1
    assert "token" not in json.loads((tmp_path / "home" / "state.json").read_text())


def test_cli_run_exits_3_when_session_is_gone(tmp_path):
    tr = FakeTransport("ab" * 32)
    tr.route("POST", "/api/helper/pair", PAIR_OK)
    tr.route("POST", "/api/helper/poll", (401, {"error": "unauthorized", "message": "x"}))
    assert run_cli(tmp_path, ["run", "h:1", FP16, "1", "--once"], tr)[0] == 3


def test_cli_doctor_stub_reports_checks_without_secrets(tmp_path):
    code, out, err = run_cli(tmp_path, ["doctor"])
    assert code == 1 and "FAIL" in out and "pairing metadata" in out and "runtime" in out


def test_cli_unknown_command_exits_2(tmp_path):
    assert run_cli(tmp_path, ["nope"])[0] == 2
    assert run_cli(tmp_path, ["pair", "h:1", FP16, "1"])[0] == 2, "no separate pair command: the token cannot outlive the process"


def test_cli_code_prompted_when_omitted_or_dash_and_never_echoed(tmp_path):
    for argv in (["run", "h:1", FP16, "--once"], ["run", "h:1", FP16, "-", "--once"]):
        tr = FakeTransport("ab" * 32)
        tr.route("POST", "/api/helper/pair", PAIR_OK)
        tr.route("POST", "/api/helper/poll", POLL_OK)
        asked = []
        code, out, err = run_cli(tmp_path / str(len(argv)), argv, tr, prompt=lambda msg: asked.append(msg) or "777123")
        assert code == 0 and len(asked) == 1
        assert json.loads(tr.sent[0]["body"])["code"] == "777123" and "777123" not in out + err


def test_cli_missing_pin_never_prompts_for_the_code(tmp_path):
    asked = []
    code, _, _ = run_cli(tmp_path, ["run", "h:1", "abab"], prompt=lambda m: asked.append(m) or "1")
    assert code == 2 and asked == []
