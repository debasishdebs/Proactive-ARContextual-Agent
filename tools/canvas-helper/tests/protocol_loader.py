"""Loader stub for the shared helper-protocol fixtures (WP1). WP11/WP13 build on it; it only reads and exposes the files.

The fixture folder holds exactly these seven Python-only files (core plan WP1 row, c5 #4 as amended 2026-10-23 evening).
Both languages read the same files: the Kotlin side is HelperProtocolFixturesTest / ConfirmVectorsTest.
"""
import hmac
import hashlib
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

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


from pca_helper.canonical import bundle_hash, canonical_json, count_bounds_ok, job_id_ok, result_canonical, result_hash  # noqa: E402,F401  (single implementation, shipped in the package)


def confirm_message(b):
    return "%s|%s|%d|%d" % (b["jobId"], b["bundleHash"], b["sessionGen"], b["expiresAtMs"])


def confirm_tag(key_hex, binding):
    return hmac.new(bytes.fromhex(key_hex), confirm_message(binding).encode("utf-8"), hashlib.sha256).hexdigest()
