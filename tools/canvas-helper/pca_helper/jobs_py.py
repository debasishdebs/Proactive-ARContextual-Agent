"""`py_exec` job glue (core plan 4.2 step 4, WP13): the collected `outputs.json` -> the result object the supervisor posts.

`PyJobGlue` replaces `runner.default_glue` through `JobRunner(glue=...)`. The runner binds it to the job's payload (`for_job`), because the checks need
the payload's item ids and `outSchema`. The result is the object `{helperProtocol, jobType, outputs, stats}`; the supervisor turns it into the D1 wire form
(`protocol.result_canonical`: ONE text, hashed over exactly that text; floats are allowed in results, the integer-only rule stays for inputs).

Checks before anything is posted (the phone still re-checks every member):
* outputs.json must be a JSON object of the envelope shape (floats are read as floats; NaN, Infinity and duplicate keys are bad_output);
* every payload item id appears exactly once (a missing, repeated or unknown id is `bad_output`; the harness never does this);
* an item with ok:true whose output fails the schema is rewritten to `{id, ok:false, code:"schema_mismatch"}`; an ok:false item must carry a code of the
  shared item-code list, else the whole file is `bad_output`;
* the result text over the result limit is `too_large`.
Every failure is a fail code of the protocol; no content is logged or echoed.
"""
import json

from . import protocol, schema_lite


def _no_dupes(pairs):
    d = {}
    for k, v in pairs:
        if k in d:
            raise ValueError("duplicate key")
        d[k] = v
    return d


def _no_const(_name):
    raise ValueError("non-finite number")


def _bad():
    return {"kind": "fail", "code": "bad_output"}


class PyJobGlue:
    def __init__(self, payload=None):
        self._payload = payload

    def for_job(self, payload):
        """A glue bound to one job's payload (the runner calls this once per job)."""
        return PyJobGlue(payload)

    def __call__(self, files):
        return self.build(files, self._payload)

    @staticmethod
    def build(files, payload):
        c = protocol.constants()
        raw = files.get("outputs.json")
        if raw is None or not isinstance(payload, dict):
            return _bad()
        try:
            obj = json.loads(raw.decode("utf-8"), object_pairs_hook=_no_dupes, parse_constant=_no_const)
        except (ValueError, UnicodeDecodeError, RecursionError):
            return _bad()
        if not isinstance(obj, dict) or set(obj) != {"helperProtocol", "jobType", "outputs", "stats"}:
            return _bad()
        if obj["helperProtocol"] != c["helperProtocol"] or isinstance(obj["helperProtocol"], bool) or obj["jobType"] != "py_exec":
            return _bad()
        stats, outs = obj["stats"], obj["outputs"]
        if not isinstance(stats, dict) or not isinstance(outs, list):
            return _bad()
        want = [it["id"] for it in payload.get("items", ())]
        got = [o.get("id") if isinstance(o, dict) else None for o in outs]
        if sorted(got, key=str) != sorted(want) or len(set(got)) != len(got):
            return _bad()  # every id exactly once
        schema = payload.get("outSchema")
        if schema_lite.contract_errors(schema):
            return _bad()
        fixed = []
        for o in outs:
            if o.get("ok") is True and set(o) == {"id", "ok", "output"}:
                if schema_lite.validate(schema, o["output"]):
                    fixed.append(o)
                else:
                    fixed.append({"id": o["id"], "ok": False, "code": "schema_mismatch"})
            elif o.get("ok") is False and set(o) == {"id", "ok", "code"} and o["code"] in c["itemCodes"]:
                fixed.append(o)
            else:
                return _bad()
        result = {"helperProtocol": c["helperProtocol"], "jobType": "py_exec", "outputs": fixed, "stats": stats}
        try:
            text = protocol.result_canonical(result)
        except ValueError:
            return _bad()
        if len(text.encode("utf-8")) > c["maxResultBytes"]:
            return {"kind": "fail", "code": "too_large"}
        return {"kind": "result", "result": result}
