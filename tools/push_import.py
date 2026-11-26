#!/usr/bin/env python3
"""Feeds a folder pulled by tools/pull_dump.py back to the phone's Developer import API (POST /api/import). Counts-only output.

  python3 tools/push_import.py <pulled-folder> [host:port] [--dry-run] [--dedup] [--batch N] [--since DAY] [--until DAY]
  python3 tools/push_import.py --selftest

Needs: Developer mode ON, the Settings > Diagnostics switch "Developer import API" ON, and a paired session (same as pull_dump: tools/m1-run.sh pair <host:port> <code> <fp8>;
over USB: adb forward tcp:PORT tcp:PORT, host:port = 127.0.0.1:PORT). The token is read into memory only and is never written or printed.
Reads  YYYY-MM-DD/<momentId>/moment.json  and  frames/<frameId>.(jpg|png|webp)  and sends each Moment with its photos as base64 in a `photos` map (at most 50 Moments and ~14 MB per request).
The phone keeps the ORIGINAL ids, times and location, writes memory text as written (attribution "imported", no model call), rebuilds vectors itself.
Round 7b: after the Moments (which carry their marks and place cards) it also sends pins.json (pins, original ids, times and Moment links), marks.json (standalone marks with their real kind; a mark that names its
Moment is restored by that Moment) and scenes/*.json (the phone REBUILDS each Scene from the imported Moments: same id when the members are the same, else mapped to a new one; exported summary text is written with
attribution "imported" only for an identical Scene). Order is fixed: Moments, pins, marks, Scenes, each in its own requests. Without --dedup a Moment that already exists is a per-item conflict (the run reports it and goes on); with --dedup it is skipped. --dry-run writes nothing on the phone.
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


def load_list(path):
    try:
        v = json.load(open(path, encoding="utf-8"))
    except (OSError, ValueError):
        return []
    return [x for x in v if isinstance(x, dict)] if isinstance(v, list) else []


def extras(folder, since=None, until=None):
    """[(object_type, counter key, items)] for pins.json, marks.json and scenes/*.json (each item tagged with its object_type); days filter marks (local day of atMs) and scenes (file day)."""
    def tag(t, items):
        return [dict(x, object_type=t) for x in items]
    pins = tag("pin", load_list(os.path.join(folder, "pins.json")))
    marks = []
    for m in load_list(os.path.join(folder, "marks.json")):
        if isinstance(m.get("atMs"), int):
            day = pd.local_day(m["atMs"])
            if (since and day < since) or (until and day > until):
                continue
        marks.append(m)
    scenes = []
    sd = os.path.join(folder, "scenes")
    if os.path.isdir(sd):
        for f in sorted(os.listdir(sd)):
            day = f[:-5]
            if f.endswith(".json") and DAY.match(day) and not ((since and day < since) or (until and day > until)):
                scenes += load_list(os.path.join(sd, f))
    return [("pin", "pins_sent", pins), ("mark", "marks_sent", tag("mark", marks)), ("scene", "scenes_sent", tag("scene", scenes))]


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
    tot = dict(moments=0, photos=0, photos_missing=0, unreadable=0, requests=0, created=0, would_create=0, conflicts=0, failed=0, skipped=0, rejected_requests=0,
               pins_sent=0, marks_sent=0, scenes_sent=0, pins=0, place_cards=0, scenes=0, scenes_remapped=0, scene_texts=0)
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
                for k in ("pins", "place_cards", "scenes", "scenes_remapped", "scene_texts"):
                    tot[k] += int((j.get("counts") or {}).get(k, 0))
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
    # then the rest, in this order (a pin may name a Moment, a Scene holds Moments): pins, standalone marks, Scenes
    for kind, key, items in extras(folder, since, until):
        tot[key] += len(items)
        for it in items:
            pending.append((it, len(json.dumps(it))))
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
        os.makedirs(os.path.join(t, "scenes"))
        json.dump([{"id": "mk:e:1", "atMs": 1763640000000, "source": "voice", "momentId": None}], open(os.path.join(t, "marks.json"), "w"))
        json.dump([{"id": "user:00000000-0000-0000-0000-00000000000a", "name": "Wall", "kind": "monument", "lat": 1.0, "lon": 2.0, "radiusM": 60}], open(os.path.join(t, "pins.json"), "w"))
        json.dump([{"id": "S65000000", "startMs": 1, "momentIds": ["65000000"], "derived": []}], open(os.path.join(t, "scenes", "2026-11-20.json"), "w"))
        sent = []

        class Fake:
            def post(self, path, body, query=None):
                sent.append((path, json.loads(body), dict(query or {})))
                n = len(sent[-1][1]["items"])
                return 200, {"received": n, "created": n, "would_create": 0, "conflicts": 0, "failed": 0, "skipped": {"scene": 0}}

        lines = []
        tot = push(Fake(), t, False, True, 1, None, None, lines.append)
        check(len(sent) == 5 and tot["moments"] == 2 and tot["photos"] == 2 and tot["created"] == 5, "two Moments (batch 1) then one pin, one mark, one Scene request")
        check([x[1]["items"][0]["object_type"] for x in sent] == ["moment", "moment", "pin", "mark", "scene"], "order: Moments, pins, marks, Scenes")
        check(tot["pins_sent"] == 1 and tot["marks_sent"] == 1 and tot["scenes_sent"] == 1, "extras counted")
        check(sent[0][0] == "/api/import" and sent[0][2] == {"dedup": "true"}, "path and dedup param")
        it = sent[0][1]["items"][0]
        check(it["object_type"] == "moment" and list(it["photos"]) == ["fa"] and base64.b64decode(it["photos"]["fa"]).startswith(b"\xff\xd8"), "photo attached by frame id")
        tot = push(Fake(), t, True, False, 50, "2026-11-21", None, lines.append)
        check(tot["moments"] == 0, "--since filters days")
        sent.clear(); push(Fake(), t, True, False, 50, None, None, lines.append)
        check(len(sent) == 4 and sent[0][2] == {"dry_run": "true"} and len(sent[0][1]["items"]) == 2, "dry run: one request with both Moments, then pins, marks, Scenes")
        sent.clear(); push(Fake(), t, True, False, 50, "2026-11-22", None, lines.append)
        check(len(sent) == 1 and sent[0][1]["items"][0]["object_type"] == "pin", "--since keeps pins (configuration) but drops the older marks and Scenes")
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
