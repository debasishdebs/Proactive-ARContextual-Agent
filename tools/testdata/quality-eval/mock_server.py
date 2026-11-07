#!/usr/bin/env python3
# Stdlib mock of the phone canvas read routes used by tools/quality-eval.py (test only). Usage: mock_server.py <port-file> <request-log>
# /api/scenes, /api/sweeps and /api/devices are deliberately missing (404) to exercise the 'unavailable' paths.
import json, sys, time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

TOKEN = "mock-token-xyz"
NOW = int(time.time() * 1000)
DAY = 86400000
TEXTS = [
    "Red bicycle leaning against the bakery window on Elm street",
    "Whiteboard with quarterly budget numbers 2026 and a sales chart",
    "Kitchen counter with a blue kettle, two mugs and a lemon",
    "Kitchen counter with a blue kettle, two mugs and a lemon",
    "Kitchen counter with a blue kettle two mugs and lemons",
    "I cannot identify anything in this image",
    "Dark",
    "Parking garage level 3 near the elevator, spot 42",
    "Laptop screen showing a python traceback in the terminal",
    "Bookshelf with a cookbook about Thai curry and a plant",
    "Train platform 9 departure board showing delays to Leeds",
    "Pharmacy receipt for ibuprofen and vitamin tablets",
    "Dog leash hanging by the front door next to umbrellas",
    "Conference badge for Maria Gonzalez, product summit",
    "Grocery list on the fridge: eggs, oat milk, spinach, rice",
    "The image shows a blurry hallway with a carpet",
]
FRAMES = []
for i, t in enumerate(TEXTS):
    ts = NOW - (i % 3) * DAY - i * 47000
    FRAMES.append({"id": "f%02d" % i, "ts": ts, "motion": 0.1, "dwell": i % 2 == 0, "blur": 0.05 * i, "state": "EXTRACTED", "hasLiveMemories": True})
FRAMES.append({"id": "f90", "ts": NOW - 5000, "motion": 0.3, "dwell": False, "blur": 0.9, "state": "FAILED_FINAL", "hasLiveMemories": False})
FRAMES.append({"id": "f91", "ts": NOW + 3 * DAY, "motion": 0.3, "dwell": False, "blur": 0.2, "state": "NEAR_DUP", "hasLiveMemories": False})
FRAMES.sort(key=lambda f: -f["ts"])
MEM = {"f%02d" % i: [{"id": "m%02d" % i, "text": t, "type": "CAPTION"}] for i, t in enumerate(TEXTS)}
ASKS = [{"id": "a1", "ts": NOW - 1000, "question": "where did I park the car", "answer": "Level 3 near the elevator, spot 42.", "rating": 1, "redacted": False},
        {"id": "a2", "ts": NOW - 2000, "question": "secret", "answer": "", "rating": None, "redacted": True},
        {"id": "a3", "ts": NOW - 3000, "question": "what is on my grocery list", "answer": "I don't know.", "rating": None, "redacted": False},
        {"id": "a4", "ts": NOW - 4000, "question": "train", "answer": "", "rating": -1, "redacted": False}]
VIEWS = {
    "a1": {"askId": "a1", "cited": ["m07"], "mode": "HYBRID", "fallback": False, "composedPrompt": "PROMPT-SHOULDNOTAPPEAR",
           "hits": [{"memoryId": "m07", "frameId": "f07", "lexical": 0.8, "semantic": 0.7, "fused": 0.75, "score": 0.75, "cited": True, "inPrompt": True}]},
    "a3": {"askId": "a3", "cited": ["m99"], "mode": "HYBRID", "fallback": False, "composedPrompt": "x",
           "hits": [{"memoryId": "m14", "frameId": "f14", "lexical": 0.4, "semantic": 0.5, "fused": 0.45, "score": 0.45, "cited": False, "inPrompt": True}]},
    "a4": {"askId": "a4", "cited": [], "mode": "LEXICAL", "fallback": True, "composedPrompt": "x", "hits": []},
}
RUNS = [{"runId": "r1", "pipelineId": "p1", "status": "DONE", "calls": 4, "estCalls": 4, "failures": 0, "startedAt": NOW - 9000, "finishedAt": NOW - 5000},
        {"runId": "r2", "pipelineId": "p1", "status": "FAILED", "calls": 2, "estCalls": 4, "failures": 2, "startedAt": NOW - 20000, "finishedAt": NOW - 18000}]
RUN_DETAIL = {"r1": {"items": [{"nodeId": "n", "status": "OK"}, {"nodeId": "n", "status": "NEEDS_REVIEW"}], "usage": {"medianLatencyMs": 900}},
              "r2": {"items": [{"nodeId": "n", "status": "ERROR", "error": "TRANSIENT"}], "usage": {"medianLatencyMs": 1500}}}


def words(t):
    import re
    return set(re.findall(r"[a-z0-9]+", t.lower()))


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, obj):
        b = json.dumps(obj).encode(); self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def rec(self):
        with open(sys.argv[2], "a") as f:
            f.write("%s %s\n" % (self.command, urlparse(self.path).path))

    def authed(self): return self.headers.get("Authorization") == "Bearer " + TOKEN

    def do_GET(self):
        self.rec()
        if not self.authed(): return self.send(401, {"error": "unauthorized"})
        u = urlparse(self.path); p = u.path; q = parse_qs(u.query)
        if p == "/api/frames":
            before = int(q.get("before", [str(2 ** 62)])[0]); lim = int(q.get("limit", ["60"])[0])
            return self.send(200, [f for f in FRAMES if f["ts"] < before][:min(lim, 5)])  # pages of 5 to exercise paging
        if p.startswith("/api/frames/"):
            fid = p.rsplit("/", 1)[1]
            f = next((x for x in FRAMES if x["id"] == fid), None)
            return self.send(404, {"error": "not_found"}) if f is None else self.send(200, dict(f, live=MEM.get(fid, [])))
        if p == "/api/moments":
            return self.send(200, [{"id": "abcd0001", "startMs": NOW, "type": "FRAME", "photos": 2, "memories": 2, "marks": 0},
                                   {"id": "abcd0002", "startMs": NOW, "type": "MARK", "photos": 0, "memories": 0, "marks": 1, "errorReason": None}])
        if p == "/api/asks": return self.send(200, ASKS)
        if p.startswith("/api/asks/"):
            i = p.rsplit("/", 1)[1]
            if i == "a2": return self.send(409, {"error": "refused"})
            return self.send(200, VIEWS[i]) if i in VIEWS else self.send(404, {"error": "not_found"})
        if p == "/api/pipelines": return self.send(200, [{"id": "p1", "name": "Live", "builtIn": True, "live": {"version": 1}}, {"id": "p2", "name": "Draft", "builtIn": False, "live": None}])
        if p == "/api/pipelines/p1": return self.send(200, {"problems": []})
        if p == "/api/pipelines/p2": return self.send(200, {"problems": ["dangling edge"]})
        if p == "/api/runs": return self.send(200, RUNS)
        if p.startswith("/api/runs/") and p.endswith("/proposal"):
            return self.send(200, {"saveable": True, "saved": p.split("/")[3] == "r1", "memories": []})
        if p.startswith("/api/runs/"):
            i = p.rsplit("/", 1)[1]
            return self.send(200, RUN_DETAIL[i]) if i in RUN_DETAIL else self.send(404, {"error": "not_found"})
        if p == "/api/publish": return self.send(200, {"active": True, "stack": [{"kind": "flow", "mode": "LIVE", "pipelineId": "q1", "version": 2}]})
        if p == "/api/publish/history":
            return self.send(200, [{"id": 3, "kind": "flow", "mode": "LIVE", "pipelineId": "q1", "version": 2, "rolledBackAt": None},
                                   {"id": 2, "kind": "prompts", "mode": None, "pipelineId": "p1", "version": 1, "rolledBackAt": NOW, "rollbackReason": "auto"}])
        if p == "/api/live": return self.send(200, {"active": True, "mode": "SHADOW", "pipelineId": "p1", "version": 1, "interpreted": 10, "fallbacks": 1,
                                                     "frames": [{"frameId": "f00", "batch": "OK", "nodes": []}, {"frameId": "f01", "batch": "ERROR", "nodes": [{"error": "x"}]}]})
        if p == "/api/plans/status": return self.send(200, {"gen": 3, "mode": "SHADOW"})
        if p == "/api/signals": return self.send(200, {"signals": [{"name": "gyroPeak", "enabled": True}], "derived": [{"name": "d", "graph": {"op": "max"}}]})
        if p == "/api/traces": return self.send(200, [{"traceId": "t1", "status": "COMPLETE"}, {"traceId": "t2", "status": "PARTIAL"}])
        if p == "/api/replays": return self.send(403, {"error": "developer_mode_off"})
        return self.send(404, {"error": "not_found"})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0); b = json.loads(self.rfile.read(n) or b"{}")
        if self.path == "/api/pair":  # used only by m1-run.sh pair in the test setup; not recorded as a harness request
            return self.send(200, {"token": TOKEN}) if b.get("code") == "123456" else self.send(401, {"error": "unauthorized"})
        self.rec()
        if not self.authed(): return self.send(401, {"error": "unauthorized"})
        if self.path == "/api/asks/debug-retrieve":
            qw = words(b.get("query", ""))
            hits = sorted(({"memoryId": m[0]["id"], "score": len(qw & words(m[0]["text"])) / (len(qw) or 1)} for m in MEM.values()), key=lambda h: -h["score"])
            return self.send(200, {"hits": [h for h in hits if h["score"] > 0][:b.get("limit", 10)]})
        return self.send(404, {"error": "not_found"})

    def do_DELETE(self):
        self.rec(); self.send(405, {"error": "bad_request"})
    do_PUT = do_PATCH = do_DELETE


s = HTTPServer(("127.0.0.1", 0), H)
open(sys.argv[1], "w").write(str(s.server_address[1]))
s.serve_forever()
