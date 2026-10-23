"""The one redacting logger of the helper (core plan section 12): count-only lines.

A field value is written only when it is an int, a bool, or a short lower-case enum word (a code such as `capability_revoked`). Anything else
(free text, ids, payloads, lists, dicts, bytes) is reduced to its length (`<n:12>`); a registered secret is never written in any form.
No raw print and no `logging` module anywhere in the package: every line goes through `Logger.event`.
"""
import re
import sys
import threading

_ENUM = re.compile(r"^[a-z][a-z0-9_]{0,23}$")
_HEXISH = re.compile(r"^[0-9a-f]{8,}$")


def _stderr_sink(line):
    sys.stderr.write(line + "\n")
    sys.stderr.flush()


class Logger:
    def __init__(self, sink=None):
        self._sink = sink or _stderr_sink
        self._secrets = set()
        self._lock = threading.Lock()

    def register_secret(self, value):
        if isinstance(value, str) and value:
            with self._lock:
                self._secrets.add(value)

    def _value(self, v):
        if isinstance(v, bool):
            return "true" if v else "false"
        if isinstance(v, int):
            return str(v)
        if v is None:
            return "none"
        if isinstance(v, str):
            if any(s in v for s in self._secrets):
                return "<redacted>"
            if _ENUM.match(v) and not _HEXISH.match(v):
                return v
            return "<n:%d>" % len(v)
        try:
            return "<n:%d>" % len(v)
        except TypeError:
            return "<n:0>"

    def event(self, name, **fields):
        if not _ENUM.match(name):
            raise ValueError("event names are fixed enum words")
        parts = [name]
        for k in sorted(fields):
            parts.append("%s=%s" % (k, self._value(fields[k])))
        line = " ".join(parts)
        with self._lock:
            self._sink(line)
