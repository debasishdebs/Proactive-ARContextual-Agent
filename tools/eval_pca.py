#!/usr/bin/env python3
"""End-to-end question answering eval for the Proactive Context phone app.

Runs every question of a dataset (tools/testdata/qna-eval/*.jsonl) through the phone's real Ask path, records the
answer and what it was built from (memories, photos, Moments, capture times, places), then has one or more judge
LLMs grade each answer against the expected one. Each run writes a timestamped folder; --baseline compares a run
with an earlier one and lists regressions.

Phone side: POST /api/ask/eval (Developer mode, authenticated like every /api route; see "Route contract" below).
Pair first with  tools/m1-run.sh pair <host:port> <code> <fp8>  (this tool reuses its state: host, auth.hdr, cert.der).

Judges: loaded with load_model(<name>) from tools/llm/language_models.py (API-only, no torch; --lm-path).
Credentials come from the .env that module reads (--env-file); nothing secret is printed or written.

Usage:
  tools/eval_pca.py --judge gpt-4o
  tools/eval_pca.py --judge gpt-4o --judge claude-sonnet --ids q01,q05 --thumbs
  tools/eval_pca.py --judge gpt-4o --baseline tools/testdata/qna-eval/runs/20261116-1200
  tools/eval_pca.py --rejudge tools/testdata/qna-eval/runs/20261116-1200 --judge gpt-4o   # judge again, no phone calls

Route contract (POST /api/ask/eval, body {"question": str}; 403 while Developer mode is off, 409 if anything the answer used was forgotten mid-ask):
  {"askId": str, "status": "answered" | "failed", "answer": str, "notice": str?, "mode": str ("UNKNOWN" without a re-run), "fallback": bool,
   "scores": "rerun" | "none", "latencyMs": int, "appVersion": str, "photoFrameId": str?, "photoAtMs": int?,
   "index": {"active": bool, "textEmbedded": int?, "textEligible": int?, "imagesEmbedded": int?, "imagesEligible": int?, "complete": bool},
   "cited": [str] (memory ids and frame:<id> photo cites the answer cited), "dependsOn": [str] (inputs it used without citing),
   "sources": [{"memoryId": str? (null for a cited photo), "rank": int? (null when the re-run did not rank it), "cited": bool, "inPrompt": bool?,
                "score": float?, "lexical": float?, "semantic": float?, "fused": float?, "createdAt": int?, "frameIds": [str],
                "capturedAtMs": int?, "place": {"label": str}?, "momentId": str?, "momentIds": [str], "momentType": str?, "snippet": str (<= 400 chars)}]}
  Ranks and scores come from re-running the retrieval as of the ask ("scores": "rerun"); "cited" is exactly what the answer cited.
  The ask is stored like a Home ask with source canvas_eval: it has no glasses path, takes no proposed action and is never listed in Home or Activity.
"""
import argparse
import datetime as dt
import http.client
import json
import os
import re
import ssl
import sys
import time

ASK_ROUTE = "/api/ask/eval"
FRAME_ID = re.compile(r"^(?!\.)(?!.*\.\.)(?!.*\.$)[A-Za-z0-9:_.-]{1,64}$")
DEFAULT_LM_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "llm")  # API-only language_models.py (no torch)
DEFAULT_DATASET = os.path.join(os.path.dirname(os.path.abspath(__file__)), "testdata/qna-eval/qna-set-v1.jsonl")
VERDICTS = ("correct", "partial", "wrong", "hallucinated", "refused")
SCORE = {"correct": 1.0, "partial": 0.5, "wrong": 0.0, "hallucinated": 0.0, "refused": 0.0}


# ---------------------------------------------------------------- phone client

def state_dir():
    t = os.environ.get("TMPDIR") or "/tmp"
    return t.rstrip("/") + "/m1-run-%d" % os.getuid()


class Phone:
    """Pinned-certificate client. Sends only POST /api/ask/eval and GETs of frame data."""

    def __init__(self, timeout=120):
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

    def call(self, method, path, body=None, raw=False):
        """Returns (status, JSON or bytes). Status 0 = transport error."""
        if not (method == "POST" and path == ASK_ROUTE) and not (method == "GET" and path.startswith("/api/frames/")):
            raise SystemExit("refused: %s %s is not an eval request" % (method, path))
        data = json.dumps(body).encode() if body is not None else None
        hdrs = {"Authorization": self._auth, "Accept": "*/*" if raw else "application/json"}
        if data is not None:
            hdrs["Content-Type"] = "application/json"
        try:
            c = self._conn()
            c.request(method, path, body=data, headers=hdrs)
            r = c.getresponse()
            payload = r.read()
            c.close()
        except SystemExit:
            raise
        except Exception as e:
            return 0, {"error": type(e).__name__}
        if raw:
            return r.status, payload
        try:
            return r.status, json.loads(payload.decode("utf-8")) if payload else None
        except Exception:
            return r.status, None

    def ask(self, question, not_after_ms=None):
        body = {"question": question}
        if not_after_ms:
            body["notAfterMs"] = not_after_ms
        t0 = time.monotonic()
        st, obj = self.call("POST", ASK_ROUTE, body)
        wall = int((time.monotonic() - t0) * 1000)
        if st != 200 or not isinstance(obj, dict):
            err = obj.get("error") if isinstance(obj, dict) else None
            return {"status": "error", "http": st, "error": err, "wallMs": wall}
        obj["wallMs"] = wall
        return obj

    def thumb(self, frame_id):
        if not FRAME_ID.match(frame_id or ""):
            return None
        st, data = self.call("GET", "/api/frames/%s/thumb" % frame_id, raw=True)
        return data if st == 200 else None


# ---------------------------------------------------------------- judges

def load_env(path):
    """Loads KEY=VALUE lines into os.environ without overriding what is already set. Values are never printed."""
    if not path or not os.path.exists(path):
        return
    for line in open(path):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


def load_judges(names, lm_path, workers):
    if lm_path not in sys.path:
        sys.path.insert(0, lm_path)
    try:
        import language_models
    except ImportError as e:
        raise SystemExit("cannot import language_models from %s (%s); pass --lm-path" % (lm_path, e))
    return {n: language_models.load_model(n, desc="judge %s" % n, n_workers=workers) for n in names}


JUDGE_PROMPT = """You grade answers from a personal memory assistant. The assistant answers questions about the user's own day
from photos their smart glasses took. You get the question, the expected answer written by a human who looked at
the photos, the difficulty, and the assistant's answer with the memories it used.

Grade the ANSWER against the EXPECTED answer:
- "correct": it gives the expected facts (wording, extra true detail and approximate times within ~15 minutes are fine).
- "partial": some expected facts are right but some are missing or slightly off.
- "wrong": the key fact is wrong or missing.
- "hallucinated": it states specific facts that are not in the expected answer and not supported by its memories
  (invented names, numbers, places, events), or it answers when the expected answer says it must not.
- "refused": it says it does not know although the expected answer has the facts.
When the EXPECTED answer says the assistant must say it doesn't know / can't tell / must not answer, then a clear
"I don't know" or "I can't tell from your photos" is "correct", and any confident specific answer is "hallucinated".

QUESTION: {question}
DIFFICULTY: {difficulty}
EXPECTED: {expected}
ANSWER: {answer}
MEMORIES USED (rank, capture time, snippet):
{sources}

Reply with JSON only: {{"verdict": "correct|partial|wrong|hallucinated|refused", "reason": "<one sentence>"}}"""


def judge_prompt(q, r):
    lines = []
    for s in (r.get("sources") or [])[:8]:
        t = fmt_ts(s.get("capturedAtMs") or s.get("createdAt"))
        lines.append("- #%s %s %s" % (s.get("rank", "?"), t, (s.get("snippet") or "").replace("\n", " ")[:200]))
    return JUDGE_PROMPT.format(question=q["question"], difficulty=q.get("difficulty", "?"), expected=q["expected"],
                               answer=(r.get("answer") or "(no answer: %s)" % r.get("status")), sources="\n".join(lines) or "(none)")


def parse_verdict(text):
    if not isinstance(text, str):
        return {"verdict": "error", "reason": "judge returned no text"}
    m = re.search(r"\{.*\}", text, re.S)
    try:
        obj = json.loads(m.group(0)) if m else {}
    except Exception:
        obj = {}
    v = str(obj.get("verdict", "")).strip().lower()
    if v not in VERDICTS:
        return {"verdict": "error", "reason": "unparseable judge output"}
    return {"verdict": v, "reason": str(obj.get("reason", ""))[:300]}


def run_judges(judges, questions, results, max_tokens):
    by_id = {q["id"]: q for q in questions}
    convs = [{"prompt": judge_prompt(by_id[r["id"]], r), "hash": r["id"]} for r in results]
    for name, lm in judges.items():
        outs = lm.batched_generate(convs, max_n_tokens=max_tokens, temperature=0.0, with_id=True)
        got = {o.get("id"): parse_verdict(o.get("output")) for o in outs if isinstance(o, dict)}
        for r in results:
            r.setdefault("judges", {})[name] = got.get(r["id"], {"verdict": "error", "reason": "missing"})
    for r in results:
        vs = [j["verdict"] for j in r.get("judges", {}).values() if j["verdict"] in VERDICTS]
        r["verdict"] = max(set(vs), key=lambda v: (vs.count(v), -VERDICTS.index(v))) if vs else "error"
        r["score"] = round(sum(SCORE[v] for v in vs) / len(vs), 3) if vs else None
        r["judgeAgreement"] = len(set(vs)) <= 1 if vs else None


# ---------------------------------------------------------------- report

def fmt_ts(ms):
    if not ms:
        return "?"
    return dt.datetime.fromtimestamp(ms / 1000).strftime("%d %b %H:%M")


def place_of(s):
    p = s.get("place") or {}
    if p.get("label"):
        return p["label"]
    if p.get("lat") is not None and p.get("lon") is not None:
        return "%.4f,%.4f" % (p["lat"], p["lon"])
    return ""


def summarise(results, key):
    rows = {}
    for r in results:
        k = r.get(key, "?")
        s = rows.setdefault(k, {"n": 0, "score": 0.0, "verdicts": {}})
        s["n"] += 1
        s["score"] += r.get("score") or 0.0
        s["verdicts"][r.get("verdict", "?")] = s["verdicts"].get(r.get("verdict", "?"), 0) + 1
    return rows


def write_report(path, results, meta, baseline=None):
    out = []
    w = out.append
    parked = [r for r in results if r.get("needs")]
    results = [r for r in results if not r.get("needs")]
    total = sum(r.get("score") or 0 for r in results)
    w("# PCA QnA eval %s" % meta["started"])
    w("")
    w("Dataset `%s` (%d questions), judges: %s, phone build: %s." % (meta["dataset"], len(results), ", ".join(meta["judges"]) or "none", meta.get("phoneVersion") or "?"))
    w("")
    w("**Score %.1f / %d (%.0f%%)**" % (total, len(results), 100.0 * total / max(1, len(results))))
    if meta.get("indexNote"):
        w("")
        w("> **Unfair run: %s**" % meta["indexNote"])
    for key, title in (("difficulty", "By difficulty"), ("set", "By session")):
        w("")
        w("## %s" % title)
        w("")
        w("| %s | n | score | verdicts |" % key)
        w("|---|---|---|---|")
        for k, s in summarise(results, key).items():
            w("| %s | %d | %.0f%% | %s |" % (k, s["n"], 100.0 * s["score"] / s["n"], ", ".join("%s %d" % kv for kv in sorted(s["verdicts"].items()))))
    if baseline:
        base = {r["id"]: r for r in baseline}
        changed = [(r, base[r["id"]]) for r in results if r["id"] in base and (r.get("score") or 0) != (base[r["id"]].get("score") or 0)]
        w("")
        w("## Changes against the baseline")
        w("")
        if not changed:
            w("No question changed score.")
        for r, b in sorted(changed, key=lambda rb: (rb[0].get("score") or 0) - (rb[1].get("score") or 0)):
            arrow = "REGRESSED" if (r.get("score") or 0) < (b.get("score") or 0) else "improved"
            w("- %s %s: %s -> %s. %s" % (r["id"], arrow, b.get("verdict"), r.get("verdict"), r["question"]))
    if parked:
        w("")
        w("## Not scored yet (need a feature that has not shipped)")
        w("")
        for r in parked:
            w("- %s (needs %s): %s -> %s" % (r["id"], r["needs"], r["question"], r.get("verdict") or "?"))
    w("")
    w("## Questions")
    for r in results + parked:
        w("")
        w("### %s [%s, %s] %s: %s" % (r["id"], r.get("difficulty"), r.get("set"), (r.get("verdict") or "?").upper(), r["question"]))
        w("")
        w("- Expected: %s" % r["expected"])
        w("- Answer: %s" % (r.get("answer") or "(%s %s)" % (r.get("status"), r.get("error") or "")).replace("\n", " "))
        for name, j in (r.get("judges") or {}).items():
            w("- Judge %s: %s. %s" % (name, j["verdict"], j["reason"]))
        w("- Ask %s, mode %s%s, %s ms" % (r.get("askId", "?"), r.get("mode", "?"), " (fallback)" if r.get("fallback") else "", r.get("latencyMs") or r.get("wallMs")))
        srcs = r.get("sources") or []
        if srcs:
            w("")
            w("| rank | cited | taken | place | moment | photo | memory |")
            w("|---|---|---|---|---|---|---|")
            for s in srcs[:8]:
                frames = s.get("frameIds") or []
                photo = frames[0] if frames else ""
                if photo and r.get("thumbs", {}).get(photo):
                    photo = "![](%s)" % r["thumbs"][photo]
                w("| %s | %s | %s | %s | %s | %s | %s |" % (s.get("rank", ""), "yes" if s.get("cited") else "", fmt_ts(s.get("capturedAtMs") or s.get("createdAt")),
                                                         place_of(s), (s.get("momentId") or "") + (" (%s)" % s["momentType"] if s.get("momentType") else ""), photo, s.get("memoryId") or "(photo)"))
    open(path, "w").write("\n".join(out) + "\n")


# ---------------------------------------------------------------- main

def load_dataset(path, ids, sets, levels):
    qs = [json.loads(l) for l in open(path) if l.strip()]
    if ids:
        qs = [q for q in qs if q["id"] in ids]
    if sets:
        qs = [q for q in qs if any(s.lower() in q.get("set", "").lower() for s in sets)]
    if levels:
        qs = [q for q in qs if q.get("difficulty") in levels]
    return qs


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--dataset", default=DEFAULT_DATASET)
    ap.add_argument("--judge", action="append", default=[], help="model name for load_model(); repeat for several judges")
    ap.add_argument("--lm-path", default=DEFAULT_LM_PATH, help="folder that holds language_models.py")
    ap.add_argument("--env-file", action="append", default=None, help=".env with the judges' credentials; repeatable (default: tools/llm/.env, then the repo .env)")
    ap.add_argument("--out-dir", default=None, help="default tools/testdata/qna-eval/runs/<timestamp>")
    ap.add_argument("--ids", default="", help="comma-separated question ids")
    ap.add_argument("--set", action="append", default=[], help="only questions whose set contains this text")
    ap.add_argument("--difficulty", action="append", default=[], help="easy|medium|tough|fail; repeatable")
    ap.add_argument("--baseline", default=None, help="an earlier run folder to compare with")
    ap.add_argument("--rejudge", default=None, help="judge the answers of an earlier run folder again (no phone calls)")
    ap.add_argument("--thumbs", action="store_true", help="download a thumbnail of each source photo into the run folder")
    ap.add_argument("--gap", type=float, default=2.0, help="seconds between asks")
    ap.add_argument("--workers", type=int, default=5)
    ap.add_argument("--max-tokens", type=int, default=400)
    ap.add_argument("--require-complete-index", action="store_true", help="exit with an error (after writing the run) when the phone's search index is not complete")
    a = ap.parse_args()

    ids = set(filter(None, a.ids.split(",")))
    questions = load_dataset(a.dataset, ids, a.set, set(a.difficulty))
    if not questions:
        raise SystemExit("no questions selected")
    started = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = a.out_dir or os.path.join(os.path.dirname(os.path.abspath(a.dataset)), "runs", started)
    os.makedirs(out_dir, exist_ok=True)
    meta = {"started": started, "dataset": os.path.basename(a.dataset), "judges": a.judge}

    if a.rejudge:
        prev = {r["id"]: r for r in (json.loads(l) for l in open(os.path.join(a.rejudge, "results.jsonl")) if l.strip())}
        results = [dict(prev[q["id"]]) for q in questions if q["id"] in prev]
        by_id = {q["id"]: q for q in questions}
        for r in results:
            r.pop("judges", None)
            r["expected"] = by_id[r["id"]]["expected"]  # the dataset may have been corrected since the run
            r.pop("needs", None)
            if by_id[r["id"]].get("needs"):
                r["needs"] = by_id[r["id"]]["needs"]
        meta["phoneVersion"] = json.load(open(os.path.join(a.rejudge, "meta.json"))).get("phoneVersion")
    else:
        phone = Phone()
        results = []
        for i, q in enumerate(questions, 1):
            r = phone.ask(q["question"])
            r.update({k: q[k] for k in ("id", "set", "difficulty", "question", "expected")})
            if q.get("needs"):
                r["needs"] = q["needs"]
            if a.thumbs:
                tdir = os.path.join(out_dir, "thumbs")
                os.makedirs(tdir, exist_ok=True)
                r["thumbs"] = {}
                for s in (r.get("sources") or [])[:8]:
                    for f in (s.get("frameIds") or [])[:1]:
                        data = phone.thumb(f)
                        if data:
                            name = "thumbs/%s.jpg" % f
                            open(os.path.join(out_dir, name), "wb").write(data)
                            r["thumbs"][f] = name
            meta["phoneVersion"] = meta.get("phoneVersion") or r.get("appVersion")
            if isinstance(r.get("index"), dict):
                meta["index"] = r["index"]  # the last answer's view of the search index
            results.append(r)
            print("[%d/%d] %s %s" % (i, len(questions), q["id"], r.get("status")), file=sys.stderr)
            time.sleep(a.gap)

    if a.judge:
        here = os.path.dirname(os.path.abspath(__file__))
        for path in a.env_file or [os.path.join(here, "llm", ".env"), os.path.join(os.path.dirname(here), ".env")]:
            load_env(path)  # the first file wins: load_env never overrides a variable already set
        run_judges(load_judges(a.judge, a.lm_path, a.workers), questions, results, a.max_tokens)

    with open(os.path.join(out_dir, "results.jsonl"), "w") as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    idx = meta.get("index")
    if idx is not None and not idx.get("complete"):
        meta["indexNote"] = ("the search index was not complete (text %s/%s, photos %s/%s): memories not yet embedded are invisible to semantic search; "
                             "put the phone on a charger and rerun" % (idx.get("textEmbedded"), idx.get("textEligible"), idx.get("imagesEmbedded"), idx.get("imagesEligible")))
        print("WARNING: " + meta["indexNote"], file=sys.stderr)
        if a.require_complete_index:
            raise SystemExit("index incomplete and --require-complete-index set: results kept in %s but not scored as a baseline" % out_dir)
    json.dump(meta, open(os.path.join(out_dir, "meta.json"), "w"), indent=1)
    baseline = None
    if a.baseline:
        baseline = [json.loads(l) for l in open(os.path.join(a.baseline, "results.jsonl")) if l.strip()]
    write_report(os.path.join(out_dir, "report.md"), results, meta, baseline)
    scored = [r for r in results if not r.get("needs")]
    total = sum(r.get("score") or 0 for r in scored)
    print("score %.1f/%d (%d not scored yet) -> %s" % (total, len(scored), len(results) - len(scored), os.path.join(out_dir, "report.md")))


if __name__ == "__main__":
    main()
