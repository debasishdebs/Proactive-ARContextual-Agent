"""HTTPS client to the phone. The certificate fingerprint pin is checked on EVERY call, after the TLS handshake and BEFORE any byte of the request
(in particular the pairing code) is sent. A missing or mismatching pin sends nothing. Every call is bounded by a timeout; none retries.
No `Origin` header. Bearer token on everything except `pair`."""
import hashlib
import hmac
import http.client
import json
import re
import socket
import ssl
import time
from dataclasses import dataclass
from typing import Any, Optional

from .protocol import constants

DEFAULT_TIMEOUT_S = 5.0
MAX_RESPONSE_BYTES = 2 * 1024 * 1024 + 4096


class NetworkError(Exception):
    """Connection failure, timeout, TLS failure or a malformed answer. The message never carries request or response content."""


class BodyTooLarge(ValueError):
    """The serialised request body is over the protocol limit; nothing was sent."""


class PinMismatch(Exception):
    pass


class MissingPin(Exception):
    pass


@dataclass
class Response:
    status: int
    body: Any = None
    code: Optional[str] = None

    @property
    def ok(self):
        return 200 <= self.status < 300


MIN_PIN_HEX = 16  # 64 bits: a shorter prefix can be ground against a CERT_NONE peer, so it is refused before anything is sent


def normalize_pin(pin):
    """The user types the fingerprint shown on the phone (at least MIN_PIN_HEX = 16 hex digits, ':' and spaces allowed, case-insensitive).
    None when absent or unusable. The phone displays 16 or more digits (WP6/WP9)."""
    if not isinstance(pin, str):
        return None
    p = re.sub(r"[\s:]", "", pin).lower()
    if len(p) < MIN_PIN_HEX or len(p) > 64 or re.fullmatch(r"[0-9a-f]+", p) is None:
        return None
    return p


def pin_matches(pin, fingerprint_hex):
    fp = (fingerprint_hex or "").lower()
    return len(fp) >= len(pin) and hmac.compare_digest(fp[:len(pin)].encode(), pin.encode())


class _TlsConn:
    def __init__(self, ssock, host, port, deadline):
        self._s = ssock
        self._host, self._port = host, port
        self._deadline = deadline

    def _remaining(self):
        r = self._deadline - time.monotonic()
        if r <= 0:
            raise NetworkError("timeout")
        return r

    def peer_fingerprint(self):
        der = self._s.getpeercert(binary_form=True)
        if not der:
            raise NetworkError("no certificate")
        return hashlib.sha256(der).hexdigest()

    def request(self, method, path, headers, body, timeout_s):
        try:
            self._s.settimeout(self._remaining())
            c = http.client.HTTPConnection(self._host, self._port)
            c.sock = self._s
            h = dict(headers)
            h["Connection"] = "close"
            c.request(method, path, body=body, headers=h)
            self._s.settimeout(self._remaining())
            resp = c.getresponse()
            chunks, total = [], 0
            while not resp.isclosed():
                self._s.settimeout(self._remaining())  # http.client closes the socket once the body is complete
                chunk = resp.read(65536)
                if not chunk:
                    break
                total += len(chunk)
                if total > MAX_RESPONSE_BYTES:
                    raise NetworkError("response too large")
                chunks.append(chunk)
            return resp.status, b"".join(chunks)
        except NetworkError:
            raise
        except (OSError, ssl.SSLError, http.client.HTTPException):
            raise NetworkError("request failed")

    def close(self):
        try:
            self._s.close()
        except OSError:
            pass


class TlsTransport:
    """Production transport: TLS without CA verification (the pin is the trust anchor, checked by the client), total deadline per call."""

    def open(self, host, port, timeout_s):
        deadline = time.monotonic() + timeout_s
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        try:
            raw = socket.create_connection((host, port), timeout=timeout_s)
        except (OSError, ssl.SSLError):
            raise NetworkError("connect failed")
        try:
            raw.settimeout(max(deadline - time.monotonic(), 0.01))
            s = ctx.wrap_socket(raw, server_hostname=None)
        except (OSError, ssl.SSLError):
            raw.close()
            raise NetworkError("tls failed")
        return _TlsConn(s, host, port, deadline)


class PhoneClient:
    def __init__(self, transport, host, port, pin, token=None):
        self._t = transport
        self.host, self.port = host, port
        self._pin = normalize_pin(pin)
        self._token = token
        self.observed_fingerprint = None
        c = constants()
        self._hb_timeout = c["heartbeatRequestTimeoutMs"] / 1000.0
        self._max_body = c["maxRequestBodyBytes"]
        self._max_result = c["resultBodyMaxBytes"]  # the whole result envelope (phone: CanvasServer.MAX_RESULT_BODY), not just the result

    def _call(self, method, path, obj=None, timeout_s=DEFAULT_TIMEOUT_S, auth=True, raw=False, max_body=None):
        if self._pin is None:
            raise MissingPin("no certificate fingerprint pin")
        data = None
        if obj is not None:
            data = json.dumps(obj, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            if len(data) > (max_body or self._max_body):
                raise BodyTooLarge("request body too large")
        headers = {"Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        if auth and self._token:
            headers["Authorization"] = "Bearer " + self._token
        conn = self._t.open(self.host, self.port, timeout_s)
        try:
            fp = conn.peer_fingerprint()
            self.observed_fingerprint = fp
            if not pin_matches(self._pin, fp):
                raise PinMismatch("certificate fingerprint does not match the pin")
            status, body = conn.request(method, path, headers, data, timeout_s)
        finally:
            conn.close()
        ok = 200 <= status < 300
        if raw and ok:
            return Response(status, body, None)
        try:
            parsed = json.loads(body.decode("utf-8")) if body else {}
        except (ValueError, UnicodeDecodeError):
            if ok:
                raise NetworkError("malformed answer")
            parsed = {}
        code = parsed.get("error") if isinstance(parsed, dict) and isinstance(parsed.get("error"), str) else None
        return Response(status, parsed, code)

    # --- endpoints (core plan section 6)
    def pair(self, code, name):
        return self._call("POST", "/api/helper/pair", {"helperProtocol": 1, "code": code, "name": name}, auth=False)

    def poll(self, body):
        return self._call("POST", "/api/helper/poll", body)

    def lease(self, types):
        return self._call("POST", "/api/helper/lease", {"helperProtocol": 1, "types": list(types)})

    def payload(self, job_id, lease_gen):
        return self._call("GET", "/api/helper/jobs/%s/payload?leaseGen=%d" % (job_id, lease_gen), raw=True)

    def heartbeat(self, job_id, lease_gen):
        return self._call("POST", "/api/helper/jobs/%s/heartbeat" % job_id, {"leaseGen": lease_gen}, timeout_s=self._hb_timeout)

    def result(self, job_id, lease_gen, result_hash, result_canonical):
        """`result_canonical` is the exact TEXT (a str) the hash covers (decision D1); the phone verifies the hash over it as received, then parses it."""
        return self._call("POST", "/api/helper/jobs/%s/result" % job_id, {"leaseGen": lease_gen, "resultHash": result_hash, "resultCanonical": result_canonical},
                          max_body=self._max_result)

    def fail(self, job_id, lease_gen, code):
        return self._call("POST", "/api/helper/jobs/%s/fail" % job_id, {"leaseGen": lease_gen, "code": code})
