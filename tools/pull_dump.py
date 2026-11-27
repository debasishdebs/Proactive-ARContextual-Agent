#!/usr/bin/env python3
"""Pulls the phone's read-only Developer dump (/api/dump/...) into a LOCAL folder for offline experiments.

  python3 tools/pull_dump.py [host:port] [--out DIR] [--since T] [--until T] [--limit N] [--no-photos] [--no-scenes]
  python3 tools/pull_dump.py --selftest

Needs: Developer mode ON in the app and a paired session (tools/m1-run.sh pair <host:port> <code> <fp8>). The state it reads is the same as the
other tools (${TMPDIR:-/tmp}/m1-run-<uid>: host, auth.hdr, cert.der). The session token is read into MEMORY only and is never written, printed or put in a file.
Intended transport is USB:  adb forward tcp:PORT tcp:PORT  then  host:port = 127.0.0.1:PORT  (the phone refuses other Host values unless "Use from a laptop" paired it).

Output (default ~/Desktop/rokid/dumps/<dump_id>/, mode 0700, never inside a git tree), sorted by local date:
  manifest.json  README.md  marks.json  pins.json
  YYYY-MM-DD/<momentId>/moment.json  and  YYYY-MM-DD/<momentId>/frames/<frameId>.jpg
  scenes/YYYY-MM-DD.json
Resumable: a file that exists is skipped (files are written to a temp name and renamed, so a half file is never taken as done). Prints counts only.
The dump contains the owner's saved locations and place names: treat the folder as private (it is created 0700; keep it off cloud sync and out of git).
Stdlib only, Python 3.8+.
"""
import argparse
import datetime
import hashlib
import http.client
import json
import os
import re
import ssl
import sys
import tempfile
import time
import urllib.parse

DEFAULT_ROOT = os.path.join("~", "Desktop", "rokid", "dumps")
PAGE = 200
MAX_RETRY = 6
SAFE = re.compile(r"[^A-Za-z0-9._-]")

README = """# Phone dump (read-only copy of context.db, pulled through the app)

dump_id: {dump_id}
This folder holds YOUR private data (saved locations, place names, photos, notes). Keep it off cloud sync and out of git.

## Layout
- manifest.json            counts, date range, schema/app version, embedding spaces, which fields exist (and which are absent)
- YYYY-MM-DD/<momentId>/moment.json   one Moment (a Moment is one phone-clock second; its id is that second in 8 hex digits)
- YYYY-MM-DD/<momentId>/frames/       the stored photos of that Moment (only photos that still exist on the phone)
- scenes/YYYY-MM-DD.json   the Scenes that start that (local) day
- marks.json               every mark (voice/tap "remember this")
- pins.json                the user's pinned places (coordinates, note, attached Moment)
- place_attempts.json      every place card attempt with its layer record (eval) and your label (Right / Wrong / Not a place); read it with tools/measure/place_eval_report.py

## moment.json fields
id, startMs, endMs (epoch ms, phone clock; endMs = startMs + 999), type (FRAME or MARK Moment), text (timestamp + visible memory text),
transcript, errorReason, noPhotoReason, buildRev/contentRev/contentHash/builtAt (builder bookkeeping),
frames[]: id, ts, capturedAtMs, markRef, clusterId, motion, dwell, blur, salience, width, phash, dhash, nearDupOf, intentLabel, intentConf, imageVectorSpace, placeLabel, hasPhoto
memories[]: id, frameId, text (the caption or note), type, tags, tier, confidence, reviewState (OK | NEEDS_REVIEW | INVALIDATED), reviewFlaggedAt, imageDelivered, derived, createdAt,
  model / modelVersion (always null: the phone does not record which model wrote a text; see manifest.fieldsAbsent)
marks[]: id, source (tap, voice, phone, ...), name, atMs, frameId, photo
derived[]: field (SUMMARY | CAPTION | EMBEDDING), value, embeddingSpace (the space id of an EMBEDDING), builtRev, atMs, stale
reviewState: the worst memory state of the Moment (null when it has no memory)
placeName / placeNames[] {{source: pin | card, placeId, name}}: the resolved place name when one exists (kept apart from the coordinates)
placeCards[]: id, cardId, placeId, title, kind, delivery, at, frameId
sceneId: the Scene that holds this Moment
location: null or {{lat, lon, accuracyM, source (ZONE_STREAM | PLACE_GUIDE | LAST_KNOWN | MEDIA_BACKFILL), speedClass, method, atMs, fixAgeMs}}

## scenes
id, startMs, endMs, hourStartMs, boundaryRule (ACTIVITY_CHANGE | FRAME_CHANGE | CAP_5_MIN | HOUR_END), activity, membershipRev, momentIds[], derived[] (summary, caption),
clusters[]: clusterId, state, openedAt, closedAt, closeReason, motionClass, memberCount, medoidFrameIds[] (the representative photos), summary (the cluster summary text)

## marks.json
id, atMs, source, name, frameId, momentId, photo, createdAt

## pins.json
id, name, kind, lat, lon, radiusM, parentId, note, momentId, pinnedAt

## place_attempts.json
id, atMs, kind, placeId, confPct, delivery, shown, held, layer (L1 | L2 | L3 | PIN | LOC | NONE), eval (the layer record as an object), label (RIGHT | WRONG | NOT_A_PLACE | null), labelAt, frameId, momentId, photoGone

## privacy
Read-only copy. It never contains the database key, pairing tokens, API keys or app settings. Forgotten items are not in it.
"""


class Fatal(Exception):
    pass


def state_dir():
    t = os.environ.get("TMPDIR") or "/tmp"
    return os.path.join(t.rstrip("/") or "/", "m1-run-%d" % os.getuid())


def read_state(sd):
    """host (or None), the Authorization header value (in memory only), the pinned certificate DER hash (or None)."""
    host = None
    try:
        host = open(os.path.join(sd, "host")).read().strip() or None
    except OSError:
        pass
    auth = None
    try:
        for line in open(os.path.join(sd, "auth.hdr")):
            if line.lower().startswith("authorization:"):
                auth = line.split(":", 1)[1].strip()
    except OSError:
        pass
    pin = None
    try:
        pin = hashlib.sha256(open(os.path.join(sd, "cert.der"), "rb").read()).hexdigest()
    except OSError:
        pass
    return host, auth, pin


def inside_git_tree(path):
    p = os.path.realpath(os.path.expanduser(path))
    while True:
        if os.path.exists(os.path.join(p, ".git")):
            return True
        parent = os.path.dirname(p)
        if parent == p:
            return False
        p = parent


class Api:
    """One keep-alive connection; JSON and bytes GETs with a retry on 429. Never logs a URL, a body or the token."""

    def __init__(self, hostport, scheme, auth, pin, sleep=time.sleep):
        self.hostport, self.scheme, self.auth, self.pin, self.sleep = hostport, scheme, auth, pin, sleep
        self.requests = 0
        self.limit = PAGE
        self.conn = None

    def _connect(self):
        if self.scheme == "https":
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE   # the phone's certificate is self-signed: it is PINNED below instead
            self.conn = http.client.HTTPSConnection(self.hostport, timeout=60, context=ctx)
            self.conn.connect()
            der = self.conn.sock.getpeercert(binary_form=True)
            if self.pin is None:
                raise Fatal("no pinned certificate (cert.der) in the pairing state: pair first with tools/m1-run.sh pair")
            if hashlib.sha256(der).hexdigest() != self.pin:
                raise Fatal("CERTIFICATE MISMATCH: not the paired phone")
        else:
            self.conn = http.client.HTTPConnection(self.hostport, timeout=60)

    def get(self, path, query=None):
        """Returns (status, content_type, body_bytes). 429 is retried after Retry-After; a dropped connection is retried once."""
        url = path + ("?" + urllib.parse.urlencode(query) if query else "")
        for attempt in range(MAX_RETRY):
            for second in (False, True):
                try:
                    if self.conn is None:
                        self._connect()
                    self.conn.request("GET", url, headers={"Authorization": self.auth, "Accept": "*/*"})
                    r = self.conn.getresponse()
                    body = r.read()
                    self.requests += 1
                    break
                except (http.client.HTTPException, OSError):
                    self.conn = None
                    if second:
                        raise Fatal("connection to the phone failed")
            if r.status == 429:
                try:
                    wait = max(1, min(30, int(r.getheader("Retry-After", "1"))))
                except ValueError:
                    wait = 1
                self.sleep(wait)
                continue
            return r.status, r.getheader("Content-Type", ""), body
        raise Fatal("rate limited too long")

    def json(self, path, query=None):
        st, _, body = self.get(path, query)
        try:
            j = json.loads(body.decode("utf-8"))
        except ValueError:
            j = {}
        if st != 200:
            raise Fatal("phone refused: http=%d error=%s" % (st, j.get("error", "?") if isinstance(j, dict) else "?"))
        return j

    def pages(self, path, query=None):
        after = None
        while True:
            q = dict(query or {})
            q["limit"] = str(self.limit)
            if after:
                q["after"] = after
            j = self.json(path, q)
            for it in j.get("items", []):
                yield it
            after = j.get("next")
            if not after:
                return


def local_day(ms):
    return datetime.datetime.fromtimestamp(ms / 1000.0).strftime("%Y-%m-%d")


def write_atomic(path, data):
    d = os.path.dirname(path)
    os.makedirs(d, mode=0o700, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=d, prefix=".part-")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def dump_json(path, obj):
    write_atomic(path, (json.dumps(obj, ensure_ascii=False, indent=1, sort_keys=True) + "\n").encode("utf-8"))


def photo_ext(ctype):
    return {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}.get(ctype.split(";")[0].strip(), ".jpg")


def find_photo(mdir, fid):
    d = os.path.join(mdir, "frames")
    for ext in (".jpg", ".png", ".webp"):
        if os.path.exists(os.path.join(d, fid + ext)):
            return True
    return False


def pull(api, out, args, say=print):
    counts = dict(moments=0, moments_skipped=0, photos=0, photos_skipped=0, photos_missing=0, scenes=0, marks=0, pins=0, place_attempts=0)
    man = api.json("/api/dump/manifest")
    c = man.get("counts", {})
    say("manifest: moments=%s scenes=%s marks=%s pins=%s frames=%s locations=%s" % tuple(c.get(k, "?") for k in ("moments", "scenes", "marks", "pins", "frames", "locations")))
    if out is None:
        out = os.path.join(os.path.expanduser(DEFAULT_ROOT), SAFE.sub("_", str(man.get("dump_id", "dump"))))
    if inside_git_tree(out):
        raise Fatal("refusing to write the dump inside a git tree (it holds private data): choose --out outside any repository")
    os.makedirs(out, mode=0o700, exist_ok=True)
    os.chmod(out, 0o700)
    dump_json(os.path.join(out, "manifest.json"), man)
    if not os.path.exists(os.path.join(out, "README.md")):
        write_atomic(os.path.join(out, "README.md"), README.format(dump_id=man.get("dump_id", "")).encode("utf-8"))
    q = {}
    if args.get("from"):
        q["from"] = args["from"]
    if args.get("to"):
        q["to"] = args["to"]

    # Moments (and their photos), one folder per local day
    for it in api.pages("/api/dump/moments", q):
        day = local_day(it["startMs"])
        mdir = os.path.join(out, day, SAFE.sub("_", it["id"]))
        mfile = os.path.join(mdir, "moment.json")
        if os.path.exists(mfile):
            counts["moments_skipped"] += 1
        else:
            dump_json(mfile, it)
            counts["moments"] += 1
        if args.get("photos", True):
            for f in it.get("frames", []):
                if not f.get("hasPhoto"):
                    continue
                fid = SAFE.sub("_", f["id"])
                if find_photo(mdir, fid):
                    counts["photos_skipped"] += 1
                    continue
                st, ctype, body = api.get("/api/dump/frames/%s/photo" % urllib.parse.quote(f["id"], safe=""))
                if st == 200:
                    write_atomic(os.path.join(mdir, "frames", fid + photo_ext(ctype)), body)
                    counts["photos"] += 1
                elif st == 404:
                    counts["photos_missing"] += 1
                else:
                    raise Fatal("phone refused a photo: http=%d" % st)

    # Scenes, grouped by the local day they start
    if not args.get("no_scenes"):
        by_day = {}
        for it in api.pages("/api/dump/scenes", q):
            by_day.setdefault(local_day(it["startMs"]), []).append(it)
            counts["scenes"] += 1
        for day, items in by_day.items():
            p = os.path.join(out, "scenes", day + ".json")
            if not os.path.exists(p):
                dump_json(p, items)

    marks = list(api.pages("/api/dump/marks", q))
    counts["marks"] = len(marks)
    if not os.path.exists(os.path.join(out, "marks.json")):
        dump_json(os.path.join(out, "marks.json"), marks)
    pins = api.json("/api/dump/pins").get("items", [])
    counts["pins"] = len(pins)
    if not os.path.exists(os.path.join(out, "pins.json")):
        dump_json(os.path.join(out, "pins.json"), pins)

    # B3: the place attempts (eval + label); only a phone build with context.db v19 says so in its manifest counts
    if "placeAttempts" in c:
        attempts = list(api.pages("/api/dump/place_attempts", q))
        counts["place_attempts"] = len(attempts)
        if not os.path.exists(os.path.join(out, "place_attempts.json")):
            dump_json(os.path.join(out, "place_attempts.json"), attempts)

    say("done: " + " ".join("%s=%d" % kv for kv in counts.items()))
    return out, counts


# ---------------------------------------------------------------- self test (fake server, no phone)

def selftest():
    import http.server
    import shutil
    import threading

    TOKEN = "TOKEN-DO-NOT-WRITE-123"
    ms1 = int(datetime.datetime(2026, 11, 20, 12, 0, 0).timestamp() * 1000)
    ms2 = int(datetime.datetime(2026, 11, 21, 9, 30, 0).timestamp() * 1000)

    def moment(ms, n):
        mid = "%08x" % (ms // 1000)
        return {"id": mid, "startMs": ms, "endMs": ms + 999, "frames": [{"id": "f%d" % n, "hasPhoto": True}, {"id": "gone%d" % n, "hasPhoto": True}], "memories": [], "location": {"lat": 1.5, "lon": 2.5}, "text": "t%d" % n}

    moments = [moment(ms1, 1), moment(ms2, 2)]
    scenes = [{"id": "s1", "startMs": ms1, "momentIds": [moments[0]["id"]], "clusters": []}, {"id": "s2", "startMs": ms2, "momentIds": [moments[1]["id"]], "clusters": []}]
    state = {"count": {}, "throttle": 1, "bad_token": 0, "q": {}}

    class H(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):
            pass

        def send(self, code, body, ctype="application/json; charset=utf-8", extra=None):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body)

        def js(self, obj, code=200):
            self.send(code, json.dumps(obj).encode())

        def do_GET(self):
            u = urllib.parse.urlparse(self.path)
            q = dict(urllib.parse.parse_qsl(u.query))
            p = u.path
            state["count"][p] = state["count"].get(p, 0) + 1
            state["q"][p] = q
            if self.headers.get("Authorization") != "Bearer " + TOKEN:
                state["bad_token"] += 1
                return self.js({"error": "unauthorized"}, 401)
            if p == "/api/dump/manifest":
                if state["throttle"]:
                    state["throttle"] = 0
                    return self.send(429, b'{"error":"rate_limited"}', extra={"Retry-After": "1"})
                return self.js({"dump_id": "dump-selftest", "counts": {"moments": 2, "scenes": 2, "marks": 1, "pins": 1, "frames": 4, "locations": 2, "placeAttempts": 1}, "fieldsAbsent": ["model_name"]})
            if p in ("/api/dump/moments", "/api/dump/scenes"):
                items = moments if p.endswith("moments") else scenes
                after = q.get("after")
                key = (lambda x: x["id"])
                rest = [x for x in items if after is None or key(x) > after]
                page = rest[:1]   # 1 item a page: exercises the cursor
                return self.js({"items": page, "count": len(page), "next": (key(page[-1]) if len(rest) > 1 else None)})
            if p == "/api/dump/marks":
                return self.js({"items": [{"id": "mk:1:1", "atMs": ms1}], "count": 1, "next": None})
            if p == "/api/dump/place_attempts":
                return self.js({"items": [{"id": "pa1", "atMs": ms1, "kind": "PHOTO", "shown": True, "label": "RIGHT"}], "count": 1, "next": None})
            if p == "/api/dump/pins":
                return self.js({"items": [{"id": "user:1", "name": "Pin", "lat": 9.0, "lon": 8.0}], "count": 1, "next": None})
            m = re.match(r"^/api/dump/frames/([^/]+)/photo$", p)
            if m:
                if m.group(1).startswith("f"):
                    return self.send(200, b"\xff\xd8\xff\xe0JPEG-" + m.group(1).encode(), "image/jpeg")
                return self.js({"error": "not_found"}, 404)
            return self.js({"error": "not_found"}, 404)

    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    root = tempfile.mkdtemp(prefix="pull-dump-selftest-")
    fails = []

    def check(cond, what):
        if not cond:
            fails.append(what)
            print("FAIL: " + what)

    try:
        sd = os.path.join(root, "state")
        os.makedirs(sd, mode=0o700)
        open(os.path.join(sd, "host"), "w").write("127.0.0.1:%d\n" % port)
        open(os.path.join(sd, "auth.hdr"), "w").write("Authorization: Bearer %s\n" % TOKEN)
        out = os.path.join(root, "dump")
        env = dict(os.environ, TMPDIR=root)
        os.rename(sd, os.path.join(root, "m1-run-%d" % os.getuid()))
        os.environ["TMPDIR"] = root
        os.environ["M1_SCHEME"] = "http"
        lines = []
        rc = main(["--out", out], say=lines.append, sleep=lambda s: None)
        check(rc == 0, "first run exit 0")
        d1, d2 = local_day(ms1), local_day(ms2)
        check(os.path.isfile(os.path.join(out, d1, moments[0]["id"], "moment.json")), "moment.json day 1")
        check(os.path.isfile(os.path.join(out, d2, moments[1]["id"], "moment.json")), "moment.json day 2")
        check(os.path.isfile(os.path.join(out, d1, moments[0]["id"], "frames", "f1.jpg")), "photo f1")
        check(not os.path.exists(os.path.join(out, d1, moments[0]["id"], "frames", "gone1.jpg")), "a 404 photo is not written")
        check(os.path.isfile(os.path.join(out, "scenes", d1 + ".json")) and os.path.isfile(os.path.join(out, "scenes", d2 + ".json")), "scene files per day")
        check(json.load(open(os.path.join(out, "pins.json")))[0]["lat"] == 9.0, "pins.json")
        check(json.load(open(os.path.join(out, "marks.json")))[0]["id"] == "mk:1:1", "marks.json")
        check(json.load(open(os.path.join(out, "place_attempts.json")))[0]["label"] == "RIGHT", "place_attempts.json")
        check(json.load(open(os.path.join(out, d1, moments[0]["id"], "moment.json")))["location"]["lat"] == 1.5, "location kept in moment.json")
        check(os.path.isfile(os.path.join(out, "README.md")) and os.path.isfile(os.path.join(out, "manifest.json")), "README and manifest")
        check((os.stat(out).st_mode & 0o777) == 0o700, "folder mode 0700")
        check(any("moments=2" in l and "photos=2" in l and "photos_missing=2" in l for l in lines), "counts line: " + " | ".join(lines))
        # resumable: a second run fetches no photo and rewrites nothing
        photos_before = sum(v for k, v in state["count"].items() if k.endswith("/photo"))
        mt = os.stat(os.path.join(out, d1, moments[0]["id"], "moment.json")).st_mtime_ns
        lines2 = []
        main(["--out", out], say=lines2.append, sleep=lambda s: None)
        photos_after = sum(v for k, v in state["count"].items() if k.endswith("/photo"))
        check(photos_after - photos_before == 2, "second run asks only for the 2 photos the phone no longer has (got %d)" % (photos_after - photos_before))
        check(os.stat(os.path.join(out, d1, moments[0]["id"], "moment.json")).st_mtime_ns == mt, "existing moment.json untouched")
        check(any("moments=0" in l and "moments_skipped=2" in l and "photos_skipped=2" in l for l in lines2), "resume counts: " + " | ".join(lines2))
        # query params: --since/--until/--limit reach the phone; --limit out of range is refused
        main(["--out", os.path.join(root, "dump4"), "--since", "2026-11-20", "--until", "2026-11-25T10:00:00Z", "--limit", "7"], say=lambda l: None, sleep=lambda s: None)
        mq = state["q"].get("/api/dump/moments", {})
        check(mq.get("from") == "2026-11-20" and mq.get("to") == "2026-11-25T10:00:00Z" and mq.get("limit") == "7", "since/until/limit sent: %r" % mq)
        check(main(["--out", os.path.join(root, "dump5"), "--limit", "0"], say=lambda l: None, sleep=lambda s: None) != 0, "limit 0 is an error")
        # the token is on no file the client wrote
        leaked = []
        for dp, _, fs in os.walk(out):
            for f in fs:
                if TOKEN.encode() in open(os.path.join(dp, f), "rb").read():
                    leaked.append(f)
        check(not leaked, "the token never reaches the dump folder")
        # refuses a git tree
        repo = os.path.join(root, "repo")
        os.makedirs(os.path.join(repo, ".git"))
        err = []
        rc = main(["--out", os.path.join(repo, "dumps", "x")], say=err.append, sleep=lambda s: None)
        check(rc != 0 and not os.path.exists(os.path.join(repo, "dumps")), "refuses an output path inside a git tree")
        # a wrong token is a fixed error and exit code
        open(os.path.join(root, "m1-run-%d" % os.getuid(), "auth.hdr"), "w").write("Authorization: Bearer nope\n")
        err = []
        rc = main(["--out", os.path.join(root, "dump2")], say=err.append, sleep=lambda s: None)
        check(rc != 0 and not os.path.exists(os.path.join(root, "dump2", "manifest.json")), "a refused session writes nothing")
        check(state["bad_token"] >= 1, "the fake server saw the bad token")
        # not paired
        shutil.rmtree(os.path.join(root, "m1-run-%d" % os.getuid()))
        check(main(["--out", os.path.join(root, "dump3")], say=lambda l: None, sleep=lambda s: None) != 0, "not paired is an error")
    finally:
        srv.shutdown()
        shutil.rmtree(root, ignore_errors=True)
    print("selftest: %s" % ("FAILED (%d)" % len(fails) if fails else "ok"))
    return 1 if fails else 0


def main(argv=None, say=print, sleep=time.sleep):
    ap = argparse.ArgumentParser(description="Pull the phone's read-only Developer dump into a local folder (outside git).")
    ap.add_argument("hostport", nargs="?", help="host:port (default: the paired host in the pairing state); over USB use 127.0.0.1:PORT after adb forward")
    ap.add_argument("--out", help="output folder (default ~/Desktop/rokid/dumps/<dump_id>/); refused inside a git tree")
    ap.add_argument("--since", "--from", dest="from_", help="start: day YYYY-MM-DD (local), ISO-8601 date-time or epoch ms (--from is the old name)")
    ap.add_argument("--until", "--to", dest="to", help="end: day YYYY-MM-DD (local, inclusive), ISO-8601 date-time or epoch ms (--to is the old name)")
    ap.add_argument("--limit", type=int, default=PAGE, help="items per request, 1-%d (default %d)" % (PAGE, PAGE))
    ap.add_argument("--no-photos", action="store_true", help="metadata only")
    ap.add_argument("--no-scenes", action="store_true", help="skip scenes")
    ap.add_argument("--scheme", choices=("http", "https"), help="default: $M1_SCHEME or https")
    ap.add_argument("--state", help="pairing state dir (default ${TMPDIR:-/tmp}/m1-run-<uid>)")
    ap.add_argument("--selftest", action="store_true", help="run the built-in test against a fake server and exit")
    a = ap.parse_args(argv)
    if a.selftest:
        return selftest()
    try:
        host, auth, pin = read_state(a.state or state_dir())
        hp = a.hostport or host
        if not hp or not auth:
            raise Fatal("not paired: run  tools/m1-run.sh pair <host:port> <code> <fp8>  first")
        if not re.match(r"^[A-Za-z0-9._\[\]:-]+:[0-9]{1,5}$", hp):
            raise Fatal("host:port expected")
        scheme = a.scheme or os.environ.get("M1_SCHEME") or "https"
        api = Api(hp, scheme, auth, pin, sleep)
        if not 1 <= a.limit <= PAGE:
            raise Fatal("--limit must be 1-%d" % PAGE)
        api.limit = a.limit
        args = {"from": a.from_, "to": a.to, "photos": not a.no_photos, "no_scenes": a.no_scenes, "limit": a.limit}
        pull(api, a.out, args, say)
        return 0
    except Fatal as e:
        say("error: %s" % e)
        return 1
    except KeyboardInterrupt:
        say("interrupted: rerun to resume")
        return 130


if __name__ == "__main__":
    sys.exit(main())
