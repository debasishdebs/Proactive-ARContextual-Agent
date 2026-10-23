import json
import os
import shutil
import socket
import ssl
import subprocess
import threading
import time

import pytest

from fakes import FakeTransport, Response
from pca_helper.client import MissingPin, NetworkError, PhoneClient, PinMismatch, TlsTransport, normalize_pin
from pca_helper.log import Logger
from pca_helper.pair import PairError, pair
from pca_helper.store import Store

FP = "ab" * 32
GOOD_PAIR = (200, {"token": "tok_" + "t" * 40, "helperId": "h1", "sessionGen": 3, "caps": {"py_exec": True}, "constants": {}})
CODE = "482913"


def mk(tmp_path, pin, fp=FP):
    tr = FakeTransport(fp)
    tr.route("POST", "/api/helper/pair", GOOD_PAIR)
    return tr, Store(tmp_path / "home"), Logger(sink=[].append)


def test_fingerprint_mismatch_sends_no_code(tmp_path):
    tr, store, log = mk(tmp_path, "deadbeef" * 2)
    with pytest.raises(PinMismatch):
        pair(store, tr, "10.0.0.5", 8443, CODE, "laptop", "deadbeef" * 2, log)
    assert tr.sent == [], "nothing, in particular not the pairing code, was sent"
    assert store.load_state() is None


def test_missing_pin_sends_no_code(tmp_path):
    for pin in (None, "", "abc", "zz" * 8, "ab" * 4, "a" * 15, "zz" * 16):  # missing, empty, short (8 and 15 digits are too short), not hex
        tr, store, log = mk(tmp_path / str(pin), pin)
        with pytest.raises(MissingPin):
            pair(store, tr, "10.0.0.5", 8443, CODE, "laptop", pin, log)
        assert tr.sent == [] and tr.opened == [], "no connection at all without a pin"


def test_pin_prefix_of_16_hex_digits_matches_and_pairing_stores_full_fingerprint(tmp_path):
    tr, store, log = mk(tmp_path, None)
    res = pair(store, tr, "10.0.0.5", 8443, CODE, "laptop", "AB:AB:AB:AB:AB:AB:AB:AB", log)
    assert res.helper_id == "h1"
    sent = tr.sent[0]
    assert json.loads(sent["body"]) == {"helperProtocol": 1, "code": CODE, "name": "laptop"}
    assert "Origin" not in sent["headers"] and "Authorization" not in sent["headers"]
    st = store.load_state()
    assert st["pin"] == FP and st["token"].startswith("tok_") and st["host"] == "10.0.0.5" and st["port"] == 8443
    assert oct(os.stat(store.state_path).st_mode & 0o777) == "0o600"


def test_peer_matching_only_8_to_15_digits_of_the_pin_is_refused_with_no_bytes_sent(tmp_path):
    pin = "ab" * 8  # 16 hex digits
    for k in range(8, 16):
        peer = pin[:k] + ("c" if pin[k] != "c" else "d") + "0" * (63 - k)  # first k digits equal, digit k+1 differs
        tr = FakeTransport(peer)
        tr.route("POST", "/api/helper/pair", GOOD_PAIR)
        store = Store(tmp_path / str(k))
        with pytest.raises(PinMismatch):
            pair(store, tr, "10.0.0.5", 8443, CODE, "laptop", pin, Logger(sink=[].append))
        assert tr.sent == [] and store.load_state() is None, k
        c = PhoneClient(tr, "h", 1, pin=pin, token="tok")
        with pytest.raises(PinMismatch):
            c.poll({})
        assert tr.sent == []


def test_pinned_fingerprint_enforced_on_every_call(tmp_path):
    tr = FakeTransport("cd" * 32)
    tr.route("POST", "/api/helper/poll", (200, {}))
    c = PhoneClient(tr, "h", 1, pin=FP, token="tok")
    with pytest.raises(PinMismatch):
        c.poll({"helperProtocol": 1})
    assert tr.sent == []
    tr.fp = FP
    assert c.poll({"helperProtocol": 1}).status == 200
    tr.fp = "ef" * 32
    for call in (lambda: c.lease(["py_exec"]), lambda: c.heartbeat("j", 1), lambda: c.payload("j", 1), lambda: c.result("j", 1, "h", {}), lambda: c.fail("j", 1, "oom")):
        with pytest.raises(PinMismatch):
            call()
    assert len(tr.sent) == 1


def test_timeout_bounds_every_call(tmp_path):
    tr = FakeTransport(FP)
    for p in ("poll", "lease"):
        tr.route("POST", "/api/helper/" + p, (200, {"job": None}))
    tr.route("POST", "/api/helper/jobs/j/heartbeat", (200, {}))
    tr.route("POST", "/api/helper/jobs/j/result", (200, {}))
    tr.route("POST", "/api/helper/jobs/j/fail", (200, {}))
    tr.route("GET", "/api/helper/jobs/j/payload", (200, b"{}"))
    tr.route("POST", "/api/helper/pair", GOOD_PAIR)
    c = PhoneClient(tr, "h", 1, pin=FP, token="tok")
    c.poll({}); c.lease(["py_exec"]); c.heartbeat("j", 1); c.result("j", 1, "h", {}); c.fail("j", 1, "oom"); c.payload("j", 1); c.pair("1", "n")
    for s in tr.sent:
        assert 0 < s["timeout"] <= 5.0, s["path"]
    hb = [s for s in tr.sent if s["path"].endswith("/heartbeat")][0]
    assert hb["timeout"] == 1.0
    assert all(t <= 5.0 for _, _, t in tr.opened) and len(tr.opened) == len(tr.sent)


def test_oversize_bodies_refused_locally(tmp_path):
    tr = FakeTransport(FP)
    c = PhoneClient(tr, "h", 1, pin=FP, token="tok")
    with pytest.raises(ValueError):
        c.result("j", 1, "h", {"x": "a" * (2 * 1024 * 1024 + 4096)})
    with pytest.raises(ValueError):
        c.poll({"x": "a" * (1024 * 1024)})
    assert tr.sent == []


def test_revoked_token_returns_401_handled(tmp_path):
    tr = FakeTransport(FP)
    tr.route("POST", "/api/helper/poll", (401, {"error": "unauthorized", "message": "fixed"}))
    c = PhoneClient(tr, "h", 1, pin=FP, token="tok")
    r = c.poll({})
    assert (r.status, r.code, r.ok) == (401, "unauthorized", False)


def test_bad_pair_code_gives_fixed_error_without_echo(tmp_path):
    tr = FakeTransport(FP)
    tr.route("POST", "/api/helper/pair", (401, {"error": "unauthorized", "message": "wrong code"}))
    store = Store(tmp_path / "home")
    with pytest.raises(PairError) as e:
        pair(store, tr, "h", 1, CODE, "laptop", FP, Logger(sink=[].append))
    assert CODE not in str(e.value) and store.load_state() is None


def test_normalize_pin():
    assert normalize_pin("AB:cd ef:01:23:45:67:89") == "abcdef0123456789"
    assert normalize_pin("abc") is None and normalize_pin(None) is None and normalize_pin("xyzxyzxyz") is None
    assert normalize_pin("abcdef01") is None and normalize_pin("a" * 15) is None and normalize_pin("a" * 16) == "a" * 16


# ---------------------------------------------------------------- real sockets (loopback only): the production transport

def _cert(tmp_path):
    if not shutil.which("openssl"):
        pytest.skip("openssl not available")
    key, crt = tmp_path / "k.pem", tmp_path / "c.pem"
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key), "-out", str(crt), "-days", "2", "-subj", "/CN=pca-test"],
                   check=True, capture_output=True)
    import hashlib
    der = ssl.PEM_cert_to_DER_cert(crt.read_text())
    return str(crt), str(key), hashlib.sha256(der).hexdigest()


class _Server:
    def __init__(self, crt, key):
        import http.server
        outer = self
        self.requests = 0

        class H(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                outer.requests += 1
                n = int(self.headers.get("Content-Length", "0"))
                self.rfile.read(n)
                body = json.dumps(GOOD_PAIR[1]).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *a):
                pass

        self.httpd = http.server.HTTPServer(("127.0.0.1", 0), H)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(crt, key)
        self.httpd.socket = ctx.wrap_socket(self.httpd.socket, server_side=True)
        self.port = self.httpd.server_address[1]
        self.t = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.t.start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.t.join(5)


def test_real_tls_pin_match_and_mismatch(tmp_path):
    crt, key, fp = _cert(tmp_path)
    srv = _Server(crt, key)
    try:
        c = PhoneClient(TlsTransport(), "127.0.0.1", srv.port, pin=fp[:16], token=None)
        r = c.pair(CODE, "laptop")
        assert r.status == 200 and srv.requests == 1 and c.observed_fingerprint == fp
        bad = PhoneClient(TlsTransport(), "127.0.0.1", srv.port, pin="0" * 16 if not fp.startswith("0" * 16) else "1" * 16, token=None)
        with pytest.raises(PinMismatch):
            bad.pair(CODE, "laptop")
        assert srv.requests == 1, "the mismatching client never sent a request"
    finally:
        srv.close()


def test_real_socket_hang_is_bounded_by_the_timeout():
    ls = socket.socket()
    ls.bind(("127.0.0.1", 0))
    ls.listen(5)
    held = []
    stop = threading.Event()

    def accept():
        ls.settimeout(0.2)
        while not stop.is_set():
            try:
                held.append(ls.accept()[0])
            except OSError:
                pass

    t = threading.Thread(target=accept, daemon=True)
    t.start()
    try:
        c = PhoneClient(TlsTransport(), "127.0.0.1", ls.getsockname()[1], pin=FP, token="tok")
        t0 = time.monotonic()
        with pytest.raises(NetworkError):
            c.heartbeat("j", 1)  # heartbeat timeout is 1.0 s: the TLS handshake never completes
        assert time.monotonic() - t0 < 2.5
    finally:
        stop.set()
        t.join(5)
        for s in held:
            s.close()
        ls.close()
