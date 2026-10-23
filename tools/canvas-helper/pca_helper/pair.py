"""Pairing: the user types the phone's certificate fingerprint; without a usable pin no connection is made and no code is sent."""
from dataclasses import dataclass, field

from .client import MissingPin, PhoneClient, normalize_pin


class PairError(Exception):
    """Fixed texts only; never the code, the token or the phone's message."""


@dataclass
class PairResult:
    helper_id: str
    session_gen: int
    caps: dict
    token: str = field(default="", repr=False)  # memory-only session token: owned by the caller, never written to disk


_TEXT = {401: "the phone refused the pairing code", 409: "helper protocol mismatch", 429: "pairing is locked on the phone, try again later",
         400: "the phone refused the pairing request"}


def pair(store, transport, host, port, code, name, pin, log):
    pin_n = normalize_pin(pin)
    if pin_n is None:
        raise MissingPin("give at least 16 hex digits of the fingerprint shown on the phone")
    if not isinstance(code, str) or not code.strip():
        raise PairError("a pairing code is required")
    log.register_secret(code)
    client = PhoneClient(transport, host, port, pin_n)
    resp = client.pair(code.strip(), (name or "helper")[:40])
    if not resp.ok:
        log.event("pair", status=resp.status)
        raise PairError(_TEXT.get(resp.status, "pairing failed"))
    b = resp.body if isinstance(resp.body, dict) else {}
    token, helper_id, gen = b.get("token"), b.get("helperId"), b.get("sessionGen")
    if not (isinstance(token, str) and token and isinstance(helper_id, str) and isinstance(gen, int) and not isinstance(gen, bool)):
        raise PairError("the phone's pairing answer is malformed")
    log.register_secret(token)
    caps = b.get("caps") if isinstance(b.get("caps"), dict) else {}
    # only non-secret pairing metadata is persisted; the token lives in memory for this process, so a helper restart must re-pair
    store.save_state({"host": host, "port": port, "pin": client.observed_fingerprint, "helperId": helper_id, "sessionGen": gen,
                      "name": (name or "helper")[:40]})
    log.event("pair", status=200)
    return PairResult(helper_id, gen, caps, token)
