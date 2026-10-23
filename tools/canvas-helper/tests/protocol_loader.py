"""Loader stub for the shared helper-protocol fixtures (WP1). WP11/WP13 build on it; it only reads and exposes the files.

The fixture folder holds exactly these seven Python-only files (core plan WP1 row, c5 #4 as amended 2026-10-23 evening).
Both languages read the same files: the Kotlin side is HelperProtocolFixturesTest / ConfirmVectorsTest.
"""
import hashlib
import hmac
import json
import re
from pathlib import Path

PROTOCOL_DIR = Path(__file__).resolve().parent.parent / "protocol"

FIXTURE_FILES = (
    "confirm-vectors.json",
    "constants.json",
    "hash-vectors.json",
    "job-id.json",
    "py_exec-payload-samples.json",
    "py_exec-result-samples.json",
    "schema-subset-rows.json",
)


def load(name):
    with open(PROTOCOL_DIR / name, encoding="utf-8") as f:
        return json.load(f)


def canonical_json(obj):
    """Canonical form of the bundle hash: sorted keys (code point order), no whitespace, no ASCII escaping."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def bundle_hash(obj):
    return hashlib.sha256(canonical_json(obj).encode("utf-8")).hexdigest()


def confirm_message(b):
    return "%s|%s|%d|%d" % (b["jobId"], b["bundleHash"], b["sessionGen"], b["expiresAtMs"])


def confirm_tag(key_hex, binding):
    return hmac.new(bytes.fromhex(key_hex), confirm_message(binding).encode("utf-8"), hashlib.sha256).hexdigest()


def job_id_ok(regex, s):
    # full-match semantics: "$" must not match before a trailing newline
    return re.fullmatch(regex, s) is not None
