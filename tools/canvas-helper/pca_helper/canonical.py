"""Canonical JSON, bundle hash and shared helpers of helper protocol v1: the ONE Python implementation (WP1 fixtures pin it against the Kotlin
BundleHash). Stdlib only. tests/protocol_loader.py re-exports these so the tests and the helper cannot drift."""
import hashlib
import json
import re


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
        # recurse only through schema positions (property schemas and items); property NAMES and enum values are data, never schemas
        props = schema.get("properties")
        if isinstance(props, dict) and not all(count_bounds_ok(v, max_count) for v in props.values()):
            return False
        return count_bounds_ok(schema["items"], max_count) if "items" in schema else True
    return True


def bundle_hash(obj):
    return hashlib.sha256(canonical_json(obj).encode("utf-8")).hexdigest()


def job_id_ok(regex, s):
    # full-match semantics: "$" must not match before a trailing newline
    return re.fullmatch(regex, s) is not None
