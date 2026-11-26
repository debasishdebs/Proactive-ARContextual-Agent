#!/usr/bin/env python3
"""Feeds a folder pulled by tools/pull_dump.py back to the phone's Developer import API (POST /api/import). Counts-only output.

  python3 tools/push_import.py <pulled-folder> [host:port] [--dry-run] [--dedup] [--batch N] [--since DAY] [--until DAY]
  python3 tools/push_import.py --selftest

Needs: Developer mode ON, the Settings > Diagnostics switch "Developer import API" ON, and a paired session (same as pull_dump: tools/m1-run.sh pair <host:port> <code> <fp8>;
over USB: adb forward tcp:PORT tcp:PORT, host:port = 127.0.0.1:PORT). The token is read into memory only and is never written or printed.
Reads  YYYY-MM-DD/<momentId>/moment.json  and  frames/<frameId>.(jpg|png|webp)  and sends each Moment with its photos as base64 in a `photos` map (at most 50 Moments and ~14 MB per request).
The phone keeps the ORIGINAL ids, times and location, writes memory text as written (attribution "imported", no model call), rebuilds Scenes and vectors itself. Scenes, pins, standalone marks and
place cards in the folder are NOT sent. Without --dedup a Moment that already exists is a per-item conflict (the run reports it and goes on); with --dedup it is skipped. --dry-run writes nothing on the phone.
The folder holds private data: this tool refuses a folder inside a git tree. It never prints an id, a text, a position or a path of a file. Stdlib only.
"""
import argparse
import base64
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pull_dump as pd  # noqa: E402  (same pairing state, certificate pinning and retry rules)

MAX_ITEMS = 50
MAX_BYTES = 14 * 1024 * 1024
DAY = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
PHOTO_EXT = (".jpg", ".png", ".webp")


class Api(pd.Api):
    def post(self, path, body, query=None):
        """Returns (status, parsed_json_or_{}). 429 is retried after Retry-After. Never logs the URL, the body or the token."""
        import urllib.parse
        url = path + ("?" + urllib.parse.urlencode(query) if query else "")
        for _ in range(pd.MAX_RETRY):
            for second in (False, True):
                try:
                    if self.conn is None:
                        self._connect()
                    self.conn.request("POST", url, body=body, headers={"Authorization": self.auth, "Content-Type": "application/json", "Content-Length": str(len(body))})
                    r = self.conn.getresponse()
                    data = r.read()
                    break
                except (pd.http.client.HTTPException, OSError):
                    self.conn = None
                    if second:
                        raise pd.Fatal("connection to the phone failed")
            if r.status == 429:
                self.sleep(1)
                continue
            try:
                j = json.loads(data.decode("utf-8"))
            except ValueError:
                j = {}
            return r.status, j if isinstance(j, dict) else {}
        raise pd.Fatal("rate limited too long")


def moments(folder, since=None, until=None):
    """Yields (day, moment_dir) in time order for the pulled layout."""
    for day in sorted(os.listdir(folder)):
        if not DAY.match(day) or (since and day < since) or (until and day > until):
            continue
        dd = os.path.join(folder, day)
        for mid in sorted(os.listdir(dd)):
            md = os.path.join(dd, mid)
            if os.path.isfile(os.path.join(md, "moment.json")):
                yield day, md


def build_item(md):
    """The Moment object with its photos attached, or None when the file cannot be read. Returns (item, photos_attached, photos_missing)."""
    try:
        item = json.load(open(os.path.join(md, "moment.json"), encoding="utf-8"))
    except (OSError, ValueError):
        return None, 0, 0
    if not isinstance(item, dict):
        return None, 0, 0
    item.setdefault("object_type", "moment")
    photos, missing = {}, 0
    for f in item.get("frames") or []:
        fid = f.get("id") if isinstance(f, dict) else None
        if not fid or f.get("hasPhoto") is False:
            continue
        for ext in PHOTO_EXT:
            p = os.path.join(md, "frames", fid + ext)
            if os.path.isfile(p):
                photos[fid] = base64.b64encode(open(p, "rb").read()).decode("ascii")
                break
        else:
            missing += 1
    item["photos"] = photos
    return item, len(photos), missing


def batches(items, max_items=MAX_ITEMS, max_bytes=MAX_BYTES):
    """Splits (item, size) pairs into request bodies of at most max_items items and max_bytes bytes (one big Moment alone is sent as it is)."""
    cur, size = [], 0
    for it, n in items:
        if cur and (len(cur) >= max_items or size + n > max_bytes):
            yield cur
            cur, size = [], 0
        cur.append(it)
        size += n
    if cur:
        yield cur


def push(api, folder, dry_run, dedup, batch, since, until, say=print):
    tot = dict(moments=0, photos=0, photos_missing=0, unreadable=0, requests=0, created=0, would_create=0, conflicts=0, failed=0, skipped=0, rejected_requests=0)
    pending = []

    def flush():
        for group in batches(pending, batch):
            body = json.dumps({"items": group}).encode("utf-8")
            q = {}
            if dry_run:
                q["dry_run"] = "true"
            if dedup:
                q["dedup"] = "true"
            st, j = api.post("/api/import", body, q)
            tot["requests"] += 1
            if st in (200, 409) and "received" in j:
                for k in ("created", "would_create", "conflicts", "failed"):
                    tot[k] += int(j.get(k, 0))
                tot["skipped"] += sum(int(v) for v in (j.get("skipped") or {}).values())
            elif st == 409 and j.get("error") == "import_stopped":
                raise pd.Fatal("stopped: Delete all ran on the phone during the import")
            elif st in (403, 401):
                raise pd.Fatal("phone refused: http=%d error=%s (Developer mode, the import switch and the pairing are needed)" % (st, j.get("error", "?")))
            else:
                tot["rejected_requests"] += 1   # a 400 names an item index and a token only; nothing is written for that request
                say("request refused: http=%d error=%s item=%s reason=%s" % (st, j.get("error", "?"), j.get("item", "?"), j.get("reason", "?")))
        pending.clear()

    for _day, md in moments(folder, since, until):
        item, n, missing = build_item(md)
        if item is None:
            tot["unreadable"] += 1
            continue
        tot["moments"] += 1; tot["photos"] += n; tot["photos_missing"] += missing
        pending.append((item, len(json.dumps(item))))
        if len(pending) >= batch:
            flush()
    flush()
    say("import %s: %s" % ("DRY RUN" if dry_run else "done", " ".join("%s=%d" % kv for kv in tot.items())))
    return tot


def selftest():
    import tempfile
    ok = 0

    def check(c, what):
        nonlocal ok
        if not c:
            print("SELFTEST FAIL: " + what)
            sys.exit(1)
        ok += 1

    check(pd.inside_git_tree(os.path.dirname(os.path.abspath(__file__))), "repo is a git tree")
    with tempfile.TemporaryDirectory() as t:
        check(not pd.inside_git_tree(t), "temp is not a git tree")
        for mid, fid in (("65000000", "fa"), ("65000001", "fb")):
            md = os.path.join(t, "2026-11-20", mid)
            os.makedirs(os.path.join(md, "frames"))
            json.dump({"id": mid, "startMs": 1, "frames": [{"id": fid, "hasPhoto": True}, {"id": fid + "x", "hasPhoto": False}], "memories": []}, open(os.path.join(md, "moment.json"), "w"))
            open(os.path.join(md, "frames", fid + ".jpg"), "wb").write(b"\xff\xd8\xff\x00")
        os.makedirs(os.path.join(t, "scenes")); open(os.path.join(t, "marks.json"), "w").write("[]")
        sent = []

        class Fake:
            def post(self, path, body, query=None):
                sent.append((path, json.loads(body), dict(query or {})))
                n = len(sent[-1][1]["items"])
                return 200, {"received": n, "created": n, "would_create": 0, "conflicts": 0, "failed": 0, "skipped": {"scene": 0}}

        lines = []
        tot = push(Fake(), t, False, True, 1, None, None, lines.append)
        check(len(sent) == 2 and tot["moments"] == 2 and tot["photos"] == 2 and tot["created"] == 2, "two Moments, batch 1, two requests")
        check(sent[0][0] == "/api/import" and sent[0][2] == {"dedup": "true"}, "path and dedup param")
        it = sent[0][1]["items"][0]
        check(it["object_type"] == "moment" and list(it["photos"]) == ["fa"] and base64.b64decode(it["photos"]["fa"]).startswith(b"\xff\xd8"), "photo attached by frame id")
        tot = push(Fake(), t, True, False, 50, "2026-11-21", None, lines.append)
        check(tot["moments"] == 0, "--since filters days")
        sent.clear(); push(Fake(), t, True, False, 50, None, None, lines.append)
        check(len(sent) == 1 and sent[0][2] == {"dry_run": "true"} and len(sent[0][1]["items"]) == 2, "one dry-run request with both Moments")
        check(len(list(batches([(i, 10) for i in range(5)], 2, 100))) == 3 and len(list(batches([(1, 90), (2, 90)], 50, 100))) == 2, "batching by count and bytes")
        check(not any("65000000" in l or "fa" in l.split() for l in lines), "output holds no id")

        class Refuse:
            def post(self, *a, **k):
                return 403, {"error": "import_off"}
        try:
            push(Refuse(), t, False, False, 50, None, None, lines.append)
            check(False, "403 must stop the run")
        except pd.Fatal:
            ok += 1
    print("selftest ok (%d checks)" % ok)
    return 0


def main(argv=None, say=print, sleep=time.sleep):
    ap = argparse.ArgumentParser(description="Push a folder pulled by pull_dump.py to the phone's Developer import API.")
    ap.add_argument("folder", nargs="?", help="the pulled dump folder (never inside a git tree)")
    ap.add_argument("hostport", nargs="?", help="host:port (default: the paired host); over USB use 127.0.0.1:PORT after adb forward")
    ap.add_argument("--dry-run", action="store_true", help="report what would be created; the phone writes nothing")
    ap.add_argument("--dedup", action="store_true", help="skip Moments that already exist (without it an existing Moment is a per-item conflict)")
    ap.add_argument("--batch", type=int, default=MAX_ITEMS, help="Moments per request, 1-%d" % MAX_ITEMS)
    ap.add_argument("--since", help="first day YYYY-MM-DD (folder days)")
    ap.add_argument("--until", help="last day YYYY-MM-DD")
    ap.add_argument("--scheme", choices=("http", "https"))
    ap.add_argument("--state", help="pairing state dir")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args(argv)
    if a.selftest:
        return selftest()
    try:
        if not a.folder or not os.path.isdir(os.path.expanduser(a.folder)):
            raise pd.Fatal("a pulled dump folder is required")
        folder = os.path.expanduser(a.folder)
        if pd.inside_git_tree(folder):
            raise pd.Fatal("refusing a folder inside a git tree (it holds private locations, places, photos and notes)")
        if not 1 <= a.batch <= MAX_ITEMS or any(d and not DAY.match(d) for d in (a.since, a.until)):
            raise pd.Fatal("--batch 1-%d, --since/--until YYYY-MM-DD" % MAX_ITEMS)
        host, auth, pin = pd.read_state(a.state or pd.state_dir())
        hp = a.hostport or host
        if not hp or not auth:
            raise pd.Fatal("not paired: run  tools/m1-run.sh pair <host:port> <code> <fp8>  first")
        if not re.match(r"^[A-Za-z0-9._\[\]:-]+:[0-9]{1,5}$", hp):
            raise pd.Fatal("host:port expected")
        api = Api(hp, a.scheme or os.environ.get("M1_SCHEME") or "https", auth, pin, sleep)
        push(api, folder, a.dry_run, a.dedup, a.batch, a.since, a.until, say)
        return 0
    except pd.Fatal as e:
        say("error: %s" % e)
        return 1
    except KeyboardInterrupt:
        say("interrupted")
        return 130


if __name__ == "__main__":
    sys.exit(main())
