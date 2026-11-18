#!/usr/bin/env python3
"""Replay eval of the v3.0 Suggestions loop (shadow mode) for the Proactive Context phone app.

Replays labelled days through the phone's dry-run route (nothing is stored, shown or sent; read-only), then scores the
would-show cards against the user's labels. Modelled on tools/eval_pca.py: same pairing state, pinned certificate and
run-folder layout; --baseline compares a run with an earlier one.

Phone side: POST /api/proactive/replay (Developer mode AND the default-OFF "Developer replay" switch in Settings >
Suggestions (shadow); 403 otherwise). Pair first with  tools/m1-run.sh pair <host:port> <code> <fp8>.

Dataset: tools/testdata/proactive-eval/days/*.jsonl, one JSON object per line:
  {"kind": "day",    "day": "2026-11-18", "stepMin": 15, "events": [{"atMs": 1795000000000, "trigger": "place_arrival"}]}
  {"kind": "expect", "key": "f2:wd:Q1234"}                 a card that SHOULD appear that day (the seeded events; recall target)
  {"kind": "label",  "key": "f2:wd:Q1234", "verdict": "useful" | "not_useful" | "wrong" | "harmful"}
Labels are the user's, per would-show card key. Keys are place ids: keep real dataset files OUT of the repo (the
checked-in days are synthetic examples for the --check self test).

Exit bar (design 4, v3.0): useful-rate >= 60 %, harmful or false-claim cards = 0, recall of expected cards >= 80 %,
shown per day <= cap (3), p95 step time <= 300 ms. Zero gate leaks are proven by the unit tests, not by this tool.

Usage:
  tools/eval_proactive.py                                   # every day file, against the paired phone
  tools/eval_proactive.py --days tools/testdata/proactive-eval/days/day-a.jsonl --baseline <run folder>
  tools/eval_proactive.py --check <run folder>              # score a saved run again, offline (no phone)
"""
import argparse
import datetime as dt
import glob
import http.client
import json
import os
import re
import ssl
import sys

ROUTE = "/api/proactive/replay"
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DAYS = os.path.join(HERE, "testdata/proactive-eval/days")
RUNS = os.path.join(HERE, "testdata/proactive-eval/runs")
LABELS = ("useful", "not_useful", "wrong", "harmful")
CAP = 3
BAR = {"useful_rate": 0.60, "harmful": 0, "recall": 0.80, "per_day_cap": CAP, "p95_step_ms": 300}
# wording that would claim a fact about the world (card text states what was seen and when, never that something is safe or off)
FALSE_CLAIM = re.compile(r"\b(safe|turned off|is off|was off|you left|nothing wrong|all clear)\b", re.I)


def state_dir():
    t = os.environ.get("TMPDIR") or "/tmp"
    return t.rstrip("/") + "/m1-run-%d" % os.getuid()


class Phone:
    """Pinned-certificate client. Sends only POST /api/proactive/replay."""

    def __init__(self, timeout=60):
        st = state_dir()
        try:
            self.host = open(os.path.join(st, "host")).read().strip()
            hdr = open(os.path.join(st, "auth.hdr")).read().strip()
        except OSError:
            raise SystemExit("not paired: run  tools/m1-run.sh pair <host:port> <code> <fp8>")
        if not hdr.lower().startswith("authorization:"):
            raise SystemExit("auth header file is malformed: pair again")
        self._auth = hdr.split(":", 1)[1].strip()
        self.scheme = os.environ.get("M1_SCHEME", "https")
        self.cert = None
        if self.scheme == "https":
            try:
                self.cert = open(os.path.join(st, "cert.der"), "rb").read()
            except OSError:
                raise SystemExit("no pinned certificate: pair again with tools/m1-run.sh")
        self.timeout = timeout

    def _conn(self):
        h, _, p = self.host.rpartition(":")
        if self.scheme == "http":
            return http.client.HTTPConnection(h, int(p), timeout=self.timeout)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE  # replaced by the exact-certificate pin below
        c = http.client.HTTPSConnection(h, int(p), timeout=self.timeout, context=ctx)
        c.connect()
        if c.sock.getpeercert(binary_form=True) != self.cert:
            c.close()
            raise SystemExit("CERTIFICATE MISMATCH: the server is not the paired phone; nothing was sent")
        return c

    def replay(self, day, step_min, events):
        body = {"day": day, "stepMin": step_min}
        if events:
            body["events"] = events
        try:
            c = self._conn()
            c.request("POST", ROUTE, body=json.dumps(body).encode(), headers={"Authorization": self._auth, "Content-Type": "application/json", "Accept": "application/json"})
            r = c.getresponse()
            payload = r.read()
            c.close()
            return r.status, (json.loads(payload.decode("utf-8")) if payload else None)
        except SystemExit:
            raise
        except Exception as e:
            return 0, {"error": type(e).__name__}


def load_day(path):
    """-> {"day", "stepMin", "events", "expect": [key], "labels": {key: verdict}} from one JSONL file."""
    d = {"day": None, "stepMin": 15, "events": [], "expect": [], "labels": {}}
    for n, line in enumerate(open(path, encoding="utf-8"), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        o = json.loads(line)
        k = o.get("kind")
        if k == "day":
            d["day"] = o["day"]; d["stepMin"] = int(o.get("stepMin", 15)); d["events"] = list(o.get("events", []))
        elif k == "expect":
            d["expect"].append(o["key"])
        elif k == "label":
            if o.get("verdict") not in LABELS:
                raise SystemExit("%s:%d: bad verdict %r (one of %s)" % (path, n, o.get("verdict"), ", ".join(LABELS)))
            d["labels"][o["key"]] = o["verdict"]
        else:
            raise SystemExit("%s:%d: unknown kind %r" % (path, n, k))
    if not d["day"] or not re.match(r"^\d{4}-\d{2}-\d{2}$", d["day"]):
        raise SystemExit("%s: no valid day line" % path)
    return d


def would_show(resp):
    """The would-show cards of one replay answer: [{atMs, key, score, text, sourceIds}]."""
    out = []
    for s in (resp or {}).get("steps", []):
        for c in s.get("candidates", []):
            if c.get("verdict") == "WOULD_SHOW":
                out.append({"atMs": s.get("atMs"), "key": c.get("key"), "score": c.get("score"), "text": c.get("text") or "", "sourceIds": c.get("sourceIds", [])})
    return out


def p95(xs):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(0.95 * len(xs)))] if xs else 0.0


def score(days):
    """days: [{"spec": load_day(...), "resp": replay answer}] -> metrics dict (pure; the unit test drives it)."""
    shown = labelled = useful = harmful = expect_n = expect_hit = false_claims = 0
    per_day, step_ms, unlabelled = [], [], 0
    for e in days:
        spec, resp = e["spec"], e["resp"]
        cards = would_show(resp)
        per_day.append(len(cards))
        shown += len(cards)
        keys = {c["key"] for c in cards}
        expect_n += len(spec["expect"]); expect_hit += sum(1 for k in spec["expect"] if k in keys)
        for c in cards:
            if FALSE_CLAIM.search(c["text"]):
                false_claims += 1
            v = spec["labels"].get(c["key"])
            if v is None:
                unlabelled += 1
                continue
            labelled += 1
            useful += v == "useful"
            harmful += v in ("harmful", "wrong")
        if resp and resp.get("stepsRun"):
            step_ms.append(resp.get("elapsedMs", 0) / float(resp["stepsRun"]))
    m = {
        "days": len(days), "shown": shown, "labelled": labelled, "unlabelled": unlabelled,
        "useful_rate": (useful / labelled) if labelled else None, "harmful": harmful + false_claims, "false_claim_wording": false_claims,
        "recall": (expect_hit / expect_n) if expect_n else None, "max_shown_per_day": max(per_day) if per_day else 0, "p95_step_ms": p95(step_ms),
    }
    m["pass"] = {
        "useful_rate": m["useful_rate"] is not None and m["useful_rate"] >= BAR["useful_rate"],
        "harmful": m["harmful"] == BAR["harmful"],
        "recall": m["recall"] is None or m["recall"] >= BAR["recall"],
        "per_day_cap": m["max_shown_per_day"] <= BAR["per_day_cap"],
        "p95_step_ms": m["p95_step_ms"] <= BAR["p95_step_ms"],
    }
    return m


def report(m, base=None):
    f = lambda v, pct=False: "n/a" if v is None else ("%.0f %%" % (100 * v) if pct else ("%.1f" % v if isinstance(v, float) else str(v)))
    rows = [("useful-rate (>= 60 %)", f(m["useful_rate"], True), m["pass"]["useful_rate"]), ("harmful / false-claim cards (= 0)", f(m["harmful"]), m["pass"]["harmful"]),
            ("recall of expected cards (>= 80 %)", f(m["recall"], True), m["pass"]["recall"]), ("max shown per day (<= %d)" % CAP, f(m["max_shown_per_day"]), m["pass"]["per_day_cap"]),
            ("p95 step time ms (<= 300, day time / steps)", f(m["p95_step_ms"]), m["pass"]["p95_step_ms"])]
    lines = ["days=%d shown=%d labelled=%d unlabelled=%d" % (m["days"], m["shown"], m["labelled"], m["unlabelled"])]
    lines += ["  %-46s %-8s %s" % (n, v, "ok" if ok else "FAIL") for n, v, ok in rows]
    if base:
        for k in ("useful_rate", "recall", "harmful", "max_shown_per_day", "p95_step_ms"):
            if base.get(k) != m.get(k):
                lines.append("  vs baseline %s: %s -> %s" % (k, f(base.get(k)), f(m.get(k))))
    lines.append("RESULT: " + ("PASS" if all(m["pass"].values()) else "NOT YET (a shadow period of 7 worn days and your labels decide delivery; this tool never enables it)"))
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--days", action="append", help="a day file or a folder of them (default: tools/testdata/proactive-eval/days)")
    ap.add_argument("--baseline", help="an earlier run folder to compare with")
    ap.add_argument("--check", help="score a saved run folder again, offline (no phone)")
    ap.add_argument("--out", default=RUNS, help="where run folders are written")
    a = ap.parse_args(argv)
    paths = []
    for p in a.days or [DEFAULT_DAYS]:
        paths += sorted(glob.glob(os.path.join(p, "*.jsonl"))) if os.path.isdir(p) else [p]
    if not paths and not a.check:
        raise SystemExit("no day files")
    days = []
    if a.check:
        for f in sorted(glob.glob(os.path.join(a.check, "day-*.json"))):
            e = json.load(open(f))
            days.append({"spec": e["spec"], "resp": e["resp"]})
        run_dir = a.check
    else:
        phone = Phone()
        run_dir = os.path.join(a.out, dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
        os.makedirs(run_dir, exist_ok=True)
        for i, p in enumerate(paths):
            spec = load_day(p)
            st, resp = phone.replay(spec["day"], spec["stepMin"], spec["events"])
            if st == 403:
                raise SystemExit("403: turn on Developer mode and Settings > Suggestions (shadow) > Developer replay")
            if st != 200:
                raise SystemExit("replay of %s failed: http %s" % (spec["day"], st))
            days.append({"spec": spec, "resp": resp})
            json.dump({"spec": spec, "resp": resp}, open(os.path.join(run_dir, "day-%02d.json" % i), "w"))
    m = score(days)
    base = None
    if a.baseline:
        base = score([{"spec": json.load(open(f))["spec"], "resp": json.load(open(f))["resp"]} for f in sorted(glob.glob(os.path.join(a.baseline, "day-*.json")))])
    text = report(m, base)
    print(text)
    json.dump(m, open(os.path.join(run_dir, "metrics.json"), "w"), indent=1)
    return 0 if all(m["pass"].values()) else 1


if __name__ == "__main__":
    sys.exit(main())
