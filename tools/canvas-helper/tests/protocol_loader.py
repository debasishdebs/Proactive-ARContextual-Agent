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


def _check_canonical(o):
    """Same domain as the Kotlin BundleHash: null, bool, int, str (no unpaired surrogate), list, dict with str keys. No float of any value."""
    if o is None or isinstance(o, (bool, int)):
        return
    if isinstance(o, float):
        raise ValueError("non-integer number")
    if isinstance(o, str):
        _check_str(o)
        return
    if isinstance(o, list):
        for x in o:
            _check_canonical(x)
        return
    if isinstance(o, dict):
        for k, v in o.items():
            if not isinstance(k, str):
                raise ValueError("object keys must be strings")
            _check_str(k)
            _check_canonical(v)
        return
    raise ValueError("unsupported value")


def _check_str(s):
    if any(0xD800 <= ord(c) <= 0xDFFF for c in s):
        raise ValueError("unpaired surrogate")  # json.loads yields a lone surrogate for "\\ud800"; a real pair is already one code point


def canonical_json(obj):
    """Canonical form of the bundle hash: sorted keys (code point order), no whitespace, no ASCII escaping, integers only (-0 parses to 0)."""
    _check_canonical(obj)
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def count_bounds_ok(schema, max_count):
    """The shared upper bound on minLength/maxLength/minItems/maxItems (constants.json schemaSubsetMaxCount); the Kotlin SchemaSubset enforces the same."""
    if isinstance(schema, dict):
        for k in ("minLength", "maxLength", "minItems", "maxItems"):
            if k in schema and (not isinstance(schema[k], int) or isinstance(schema[k], bool) or schema[k] < 0 or schema[k] > max_count):
                return False
        return all(count_bounds_ok(v, max_count) for v in schema.values())
    if isinstance(schema, list):
        return all(count_bounds_ok(v, max_count) for v in schema)
    return True


def bundle_hash(obj):
    return hashlib.sha256(canonical_json(obj).encode("utf-8")).hexdigest()


def confirm_message(b):
    return "%s|%s|%d|%d" % (b["jobId"], b["bundleHash"], b["sessionGen"], b["expiresAtMs"])


def confirm_tag(key_hex, binding):
    return hmac.new(bytes.fromhex(key_hex), confirm_message(binding).encode("utf-8"), hashlib.sha256).hexdigest()


def job_id_ok(regex, s):
    # full-match semantics: "$" must not match before a trailing newline
    return re.fullmatch(regex, s) is not None
