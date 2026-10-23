"""Helper protocol v1 on the Python side: numbers come from the shared WP1 fixtures (`protocol/constants.json`), never restated here; canonical JSON,
bundle hash and the job-id check live in `canonical.py` (one implementation, pinned to the Kotlin BundleHash by the shared vectors)."""
import json
from pathlib import Path

from . import canonical as _canon

ROOT = Path(__file__).resolve().parent.parent
PROTOCOL_DIR = ROOT / "protocol"

_consts = None


def constants():
    global _consts
    if _consts is None:
        with open(PROTOCOL_DIR / "constants.json", encoding="utf-8") as f:
            _consts = json.load(f)
    return _consts


def canonical_json(obj):
    return _canon.canonical_json(obj)


def bundle_hash(obj):
    return _canon.bundle_hash(obj)


def job_id_ok(s):
    return isinstance(s, str) and _canon.job_id_ok(constants()["jobIdRegex"], s)


def deletion_class_denials():
    return tuple(constants()["deletionClassDenials"])


_PAYLOAD_KEYS = {"helperProtocol", "jobType", "source", "entry", "items", "outSchema", "limits"}


def _is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def _schema_errors(schema, errs, c):
    """Contract of the schema subset (keywords only; instance validation is WP13 schema_lite). `pattern` and every unknown keyword is refused."""
    if not isinstance(schema, dict):
        errs.add("schema must be an object")
        return
    allowed = set(c["schemaSubsetKeywords"])
    if not set(schema) <= allowed:
        errs.add("schema uses an unsupported keyword")
    t = schema.get("type")
    if t is not None and t not in c["schemaTypes"]:
        errs.add("schema type unsupported")
    props = schema.get("properties")
    if props is not None:
        if not isinstance(props, dict):
            errs.add("schema properties must be an object")
        else:
            for v in props.values():
                _schema_errors(v, errs, c)
    if "items" in schema:
        _schema_errors(schema["items"], errs, c)
    if "additionalProperties" in schema and schema["additionalProperties"] is not False:
        errs.add("additionalProperties must be false")
    if not _loader_count_ok(schema, c):
        errs.add("schema count bound out of range")


def _loader_count_ok(schema, c):
    return _canon.count_bounds_ok(schema, c["schemaSubsetMaxCount"])


def validate_payload(payload):
    """Shape check of a py_exec payload, mirroring HelperProtocol.validatePayload (Kotlin). Fixed texts, never an echo of the content. [] = valid."""
    c = constants()
    errs = set()
    if not isinstance(payload, dict):
        return ["payload must be an object"]
    if set(payload) != _PAYLOAD_KEYS:
        errs.add("payload fields are not exactly the protocol fields")
    if payload.get("helperProtocol") != c["helperProtocol"] or isinstance(payload.get("helperProtocol"), bool):
        errs.add("helperProtocol must be 1")
    if payload.get("jobType") != "py_exec":
        errs.add("jobType must be py_exec")
    src = payload.get("source")
    if not isinstance(src, str):
        errs.add("source must be a string")
    elif len(src) > c["py"]["sourceMaxChars"]:
        errs.add("source too long")
    if payload.get("entry") != c["py"]["entry"]:
        errs.add("entry must be run")
    import re
    items = payload.get("items")
    if not isinstance(items, list):
        errs.add("items must be a list")
    else:
        if not (c["py"]["itemsMin"] <= len(items) <= c["py"]["itemsMax"]):
            errs.add("item count out of range")
        seen = set()
        for it in items:
            if not isinstance(it, dict) or set(it) != {"id", "input"}:
                errs.add("item must be {id,input}")
                continue
            i = it["id"]
            if not isinstance(i, str) or re.fullmatch(c["itemIdRegex"], i) is None:
                errs.add("item id must be an opaque index")
            elif i in seen:
                errs.add("item ids must be unique")
            else:
                seen.add(i)
    if "outSchema" not in payload:
        errs.add("outSchema missing")
    else:
        _schema_errors(payload["outSchema"], errs, c)
    lim = payload.get("limits")
    if not isinstance(lim, dict) or set(lim) != {"perItemMs", "jobMs"}:
        errs.add("limits must be {perItemMs,jobMs}")
    else:
        a, b = lim["perItemMs"], lim["jobMs"]
        if not (_is_int(a) and _is_int(b) and 1 <= a <= c["py"]["perItemMs"] and 1 <= b <= c["py"]["jobMs"]):
            errs.add("limits out of range")
    if not errs:
        try:
            if len(canonical_json(payload).encode("utf-8")) > c["maxPayloadBytes"]:
                errs.add("payload too large")
        except ValueError:
            errs.add("payload is not canonical")
    return sorted(errs)
