#!/usr/bin/env python3
# Tiny stdlib mock of the phone canvas routes used by tools/m1-run.sh (test only). Usage: mock_server.py <port-file>
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
TOKEN = "mock-token-xyz"
class H(BaseHTTPRequestHandler):
    polls = 0
    imports = []
    def log_message(self, *a): pass
    def send(self, code, obj):
        b = json.dumps(obj).encode(); self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def body(self):
        n = int(self.headers.get("Content-Length") or 0); return json.loads(self.rfile.read(n) or b"{}")
    def authed(self): return self.headers.get("Authorization") == "Bearer " + TOKEN
    def do_POST(self):
        p = self.path
        if p == "/api/pair":
            return self.send(200, {"token": TOKEN}) if self.body().get("code") == "123456" else self.send(401, {"error": "unauthorized", "triesLeft": 4})
        if not self.authed(): return self.send(401, {"error": "unauthorized"})
        b = self.body()
        if p == "/api/plans/compile":
            ok = len(b["json"]["nodes"]) == 10
            return self.send(200, {"ok": ok, "configHash": "f5782e0d8610" if ok else None, "problems": [] if ok else ["x"], "warnings": []})
        if p == "/api/plans/debug-shadow":
            if b.get("mode") != "SHADOW": return self.send(400, {"error": "shadow_only"})
            return self.send(200, {"label": "debug SHADOW (no publish gates)", "mode": "SHADOW", "gen": 3, "sent": True, "configHash": "f5782e0d8610", "warnings": []})
        if p == "/api/traces/import":
            if b.get("name") == "trace-partial-0001" and not b.get("allowPartial"): return self.send(409, {"error": "partial_trace"})
            H.imports.append(b["name"])
            return self.send(200, {"traceId": b["name"], "status": "COMPLETE", "events": 11, "chunks": 1, "unchanged": False})
        if p == "/api/collect/start": return self.send(200, {"active": True, "untilMs": 1000 * b["minutes"]})
        if p == "/api/collect/stop": return self.send(200, {"active": False, "untilMs": None})
        if p == "/api/replays":
            assert b["candidate"]["configHash"] == "f5782e0d8610"
            return self.send(202, {"replayId": "r1"})
        self.send(404, {"error": "not_found"})
    def do_GET(self):
        if not self.authed(): return self.send(401, {"error": "unauthorized"})
        p = self.path
        if p == "/api/collect": return self.send(200, {"active": True, "untilMs": 5})
        if p == "/api/traces": return self.send(200, [{"traceId": "t-1", "source": "GLASSES", "status": "COMPLETE", "revision": 1, "exact": True}])
        if p == "/api/replays": return self.send(200, [{"replayId": "r1", "jobState": "DONE"}])
        if p == "/api/replays/r1":
            H.polls += 1
            if H.polls < 2: return self.send(200, {"replayId": "r1", "jobState": "RUNNING"})
            fig = lambda o: {"configHash": "f5782e0d8610", "opens": o, "estimatedHoursFromFull": 7.5, "estimatedPctPerMin": 0.22}
            rep = {"minutes": 25.0, "baseline": fig(100), "candidate": fig(100), "unobservedOpensPct": 3.0, "basePassed": 24, "baseFailed": 0, "inexactWindows": 2, "warnings": []}
            return self.send(200, {"replayId": "r1", "jobState": "DONE", "result": {"estimate": True, "reports": [{"traceId": "t-1", "report": rep}]}})
        self.send(404, {"error": "not_found"})
srv = HTTPServer(("127.0.0.1", 0), H)
open(sys.argv[1], "w").write(str(srv.server_address[1]))
srv.serve_forever()
