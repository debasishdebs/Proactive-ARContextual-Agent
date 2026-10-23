"""The Python mirror of the Kotlin `SchemaSubset` (core plan section 6; WP13). Standard library only, no import from the rest of the package, because
a byte-identical copy of this file ships inside the job image (`container/pyjob/schema_lite.py`, kept equal by a test).

Keywords: type, required, properties, additionalProperties (only `false`), enum, items, minimum, maximum, minLength, maxLength, minItems, maxItems.
No `pattern`. Both languages read the same rows (`protocol/schema-subset-rows.json`) and must give the same verdicts:

* `contract_errors(schema)`: [] when the schema is inside the subset (fixed texts, never an echo);
* `validate(schema, instance)`: True/False; an instance nested deeper than MAX_INSTANCE_DEPTH arrays/objects is invalid;
* keywords apply by the VALUE's type (`minimum` only to numbers, `minLength` only to strings, ...), exactly as the Kotlin code does;
* `integer` means a JSON integer token (Python: int, never bool; 3.0 and 1e2 are not integers); `number` is int or float, never bool;
* `enum` compares numbers by value (1 equals 1.0), everything else strictly (true is not 1), and enum values are data, never schemas;
* string length counts code points (Python str length), counts are compared as big integers.
"""

KEYWORDS = frozenset(("type", "required", "properties", "additionalProperties", "enum", "items", "minimum", "maximum", "minLength", "maxLength",
                      "minItems", "maxItems"))
TYPES = frozenset(("object", "array", "string", "integer", "number", "boolean", "null"))
MAX_COUNT = 2147483647  # constants.json schemaSubsetMaxCount
MAX_SCHEMA_DEPTH = 16
MAX_INSTANCE_DEPTH = 8  # constants.json py.maxInstanceDepth


def _is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def _is_number(v):
    return (isinstance(v, (int, float)) and not isinstance(v, bool)) and v == v and v not in (float("inf"), float("-inf"))


def contract_errors(schema):
    errs = []
    _contract(schema, 0, errs)
    out = []
    for e in errs:
        if e not in out:
            out.append(e)
    return out


def _contract(s, depth, errs):
    if depth > MAX_SCHEMA_DEPTH:
        errs.append("schema nested too deep")
        return
    if not isinstance(s, dict):
        errs.append("schema must be an object")
        return
    for k in s:
        if k not in KEYWORDS:
            errs.append("pattern is unsupported" if k == "pattern" else "unsupported keyword")
    t = s.get("type")
    if "type" in s and not (isinstance(t, str) and t in TYPES):
        errs.append("unknown type")
    if "required" in s:
        r = s["required"]
        if not isinstance(r, list) or any(not isinstance(x, str) for x in r):
            errs.append("required must list names")
    if "properties" in s:
        p = s["properties"]
        if not isinstance(p, dict):
            errs.append("properties must be an object")
        else:
            for v in p.values():
                _contract(v, depth + 1, errs)
    if "items" in s:
        _contract(s["items"], depth + 1, errs)
    if "enum" in s and not (isinstance(s["enum"], list) and s["enum"]):
        errs.append("enum must be a non-empty list")
    for k in ("minimum", "maximum"):
        if k in s and not _is_number(s[k]):
            errs.append("%s must be a number" % k)
    for k in ("minLength", "maxLength", "minItems", "maxItems"):
        if k in s:
            v = s[k]
            if not _is_int(v) or v < 0:
                errs.append("%s must be a non-negative integer" % k)
            elif v > MAX_COUNT:
                errs.append("%s above the supported bound" % k)
    ap = s.get("additionalProperties", None)
    if "additionalProperties" in s and ap is not False:
        errs.append("additionalProperties must be false")
    object_schema = t == "object" or "properties" in s
    if object_schema and "additionalProperties" not in s:
        errs.append("object schema needs additionalProperties false")


def _depth(v):
    if isinstance(v, list):
        return 1 + max((_depth(x) for x in v), default=0)
    if isinstance(v, dict):
        return 1 + max((_depth(x) for x in v.values()), default=0)
    return 0


def validate(schema, instance):
    """True when `instance` satisfies `schema` (a schema that is not inside the subset never validates anything: check `contract_errors` first)."""
    try:
        return _depth(instance) <= MAX_INSTANCE_DEPTH and _check(schema, instance)
    except RecursionError:
        return False


def _type_ok(t, v):
    if t == "object":
        return isinstance(v, dict)
    if t == "array":
        return isinstance(v, list)
    if t == "string":
        return isinstance(v, str)
    if t == "boolean":
        return isinstance(v, bool)
    if t == "null":
        return v is None
    if t == "integer":
        return _is_int(v)
    if t == "number":
        return _is_number(v)
    return False


def _same(a, b):
    if _is_number(a) and _is_number(b):
        return a == b
    if isinstance(a, bool) or isinstance(b, bool) or a is None or b is None:
        return a is b
    if type(a) is not type(b):
        return False
    if isinstance(a, list):
        return len(a) == len(b) and all(_same(x, y) for x, y in zip(a, b))
    if isinstance(a, dict):
        return a.keys() == b.keys() and all(_same(a[k], b[k]) for k in a)
    return a == b


def _check(s, v):
    if not isinstance(s, dict):
        return False
    if "type" in s and not _type_ok(s["type"], v):
        return False
    if "enum" in s and not any(_same(e, v) for e in s["enum"]):
        return False
    if _is_number(v):
        if "minimum" in s and v < s["minimum"]:
            return False
        if "maximum" in s and v > s["maximum"]:
            return False
    if isinstance(v, str):
        if "minLength" in s and len(v) < s["minLength"]:
            return False
        if "maxLength" in s and len(v) > s["maxLength"]:
            return False
    if isinstance(v, list):
        if "minItems" in s and len(v) < s["minItems"]:
            return False
        if "maxItems" in s and len(v) > s["maxItems"]:
            return False
        if "items" in s and not all(_check(s["items"], x) for x in v):
            return False
    if isinstance(v, dict):
        for r in s.get("required") or ():
            if r not in v:
                return False
        props = s.get("properties")
        if isinstance(props, dict):
            for k, sub in props.items():
                if k in v and not _check(sub, v[k]):
                    return False
        if s.get("additionalProperties", True) is False and any(not isinstance(props, dict) or k not in props for k in v):
            return False
    return True
