#!/usr/bin/env python3
"""Builds the OFFLINE trip-replay bundle (plan: docs/superpowers/plans/2026-11-24-offline-trip-replay-harness.md).

Reads (read-only): a COPY of <dump>/x/databases/context.db (plain SQLite), <dump>/x/shared_prefs/place_pins.xml, the media GPS csv.
Never opens db_key.xml, endpoint_keys.xml, rokid_link.xml or proactive_shadow.xml. Makes no network call. Sends no photo anywhere.
Writes TSV files to --out, which MUST be outside any git work tree (real coordinates, captions and frame ids stay on this Mac).
Prints counts only (never a coordinate, a caption, a name or a frame id).

  python3 tools/replay/build_bundle.py \
      --dump ~/Desktop/rokid/backups/phone-dump-2026-11-24 \
      --gps  ~/Desktop/rokid/backups/trip-gps/trip_media_gps.csv \
      --out  ~/Desktop/rokid/backups/replay-bundle-2026-11-24

Files (tab separated, header line first, times are epoch ms UTC):
  frames.tsv      frameId createdAt capturedAt dwell motion sessionId tombAt photo nearDup text
  fixes.tsv       tMs lat lon src file              (src CAMERA|HIROKID; accuracy is not in the csv: the harness applies a scenario)
  pins.tsv        id name kind lat lon radiusM parentId activeFrom activeSrc   (activeSrc LOG|INFERRED|UNKNOWN)
  historical.tsv  frameId capturedAt name conf      (the trip's real "Looked at X" place records)
  truth.tsv       label target startMs endMs source testable   (template; NOT overwritten when it exists: the user edits it)
  sites.tsv       id name kind lat lon radiusM src  (empty template; ASSUMED pack sites the user may add by hand)
"""
import argparse, csv, datetime as dt, html, json, math, os, pathlib, re, shutil, sqlite3, subprocess, sys, tempfile

IST = dt.timezone(dt.timedelta(hours=5, minutes=30))
DENY = {"db_key.xml", "endpoint_keys.xml", "rokid_link.xml", "proactive_shadow.xml"}
# Antera's pin creation is PROVEN from the phone log (trip-analysis-2026-11-24.md): 23 Nov 22:00:51.6 IST.
LOGGED_PIN_TIMES = {"Antera": "2026-11-23 22:00:51.600"}
INFER_M = 2.0  # a pin within this distance of a media fix is INFERRED to have been made at the earliest such fix


def ist_ms(s):
    fmt = "%Y-%m-%d %H:%M:%S.%f" if "." in s else "%Y-%m-%d %H:%M:%S"
    return int(dt.datetime.strptime(s, fmt).replace(tzinfo=IST).timestamp() * 1000)


def dist_m(a, b, c, d):
    p1, p2 = math.radians(a), math.radians(c)
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(d - b) / 2) ** 2
    return 2 * 6_371_008.8 * math.asin(min(1.0, math.sqrt(h)))


def inside_git(p):
    r = subprocess.run(["git", "-C", str(p), "rev-parse", "--is-inside-work-tree"], capture_output=True, text=True)
    return r.returncode == 0 and r.stdout.strip() == "true"


def clean(s, n):
    return re.sub(r"[\t\r\n]+", " ", s or "").strip()[:n]


def media_time(row):
    if row["exif_time"]:
        return ist_ms(dt.datetime.strptime(row["exif_time"], "%Y:%m:%d %H:%M:%S").strftime("%Y-%m-%d %H:%M:%S"))
    m = re.search(r"(\d{8})[_-](\d{6})", row["file"])  # times from the file name (videos)
    if not m:
        return None
    return ist_ms(dt.datetime.strptime(m[1] + m[2], "%Y%m%d%H%M%S").strftime("%Y-%m-%d %H:%M:%S"))


def write_tsv(path, header, rows):
    with open(path, "w", newline="") as o:
        w = csv.writer(o, delimiter="\t", lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)
    os.chmod(path, 0o600)


TRUTH_TEMPLATE = [  # label, target (pin name or SITE:<name> or -), start IST, end IST, source, testable
    ("SJ_ENTRY", "SalarJung Museum", "2026-11-21 17:09:30", "2026-11-21 17:13:00", "candidates-doc", "yes"),
    ("SJ_FOUNDERS", "Salar jung Founders gallery", "2026-11-21 17:19:00", "2026-11-21 17:31:30", "candidates-doc+user(weak)", "yes"),
    ("SJ_BRONZE", "Bronze Gallery SalarJung", "2026-11-21 17:32:00", "2026-11-21 17:40:00", "candidates-doc", "yes"),
    ("SJ_IVORY", "Ivory Room SJM", "2026-11-21 17:50:00", "2026-11-21 17:54:00", "candidates-doc", "yes"),
    ("SJ_EXTERIOR", "SalarJung Museum", "2026-11-21 18:04:00", "2026-11-21 18:10:00", "user", "yes"),
    ("GOLCONDA_LS", "Golconda Fort Light & sound show", "2026-11-21 19:20:00", "2026-11-21 20:10:00", "user", "yes"),
    ("RAMOJI", "SITE:Ramoji Film City", "2026-11-22 18:08:00", "2026-11-22 21:15:00", "user", "assumed-fix-only"),
    ("ANTERA_FACADE", "Antera", "2026-11-23 22:00:30", "2026-11-23 22:00:50", "log+candidates-doc", "assumed-fix-only"),
    ("ANTERA", "Antera", "2026-11-23 21:50:00", "2026-11-23 23:57:00", "user", "assumed-fix-only"),
    ("ONEGOLD", "-", "2026-11-21 22:41:34", "2026-11-21 23:59:00", "gap-analysis", "no:no-pin,entrance-in-pause"),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", required=True)
    ap.add_argument("--gps", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--from-ist", default="2026-11-21 00:00:00")
    ap.add_argument("--to-ist", default="2026-11-24 01:00:00")
    a = ap.parse_args()
    dump, out = pathlib.Path(a.dump).expanduser(), pathlib.Path(a.out).expanduser()
    probe = out
    while not probe.exists():  # judge the nearest existing ancestor, so a refused run creates nothing
        probe = probe.parent
    if inside_git(probe):
        sys.exit("refused: --out is inside a git work tree (real trip data must stay out of git)")
    out.mkdir(parents=True, exist_ok=True)
    os.chmod(out, 0o700)
    lo, hi = ist_ms(a.from_ist), ist_ms(a.to_ist)

    # 1. context.db: copy first, then open the copy read-only.
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="replay-db-"))
    try:
        src = dump / "x" / "databases" / "context.db"
        for suffix in ("", "-wal", "-shm"):
            if (src.parent / (src.name + suffix)).exists():
                shutil.copy2(src.parent / (src.name + suffix), tmp / ("context.db" + suffix))
        db = sqlite3.connect(f"file:{tmp / 'context.db'}?mode=ro", uri=True)
        text = {}
        for fid, t in db.execute("SELECT frameId, text FROM memory WHERE frameId IS NOT NULL AND type IN ('caption','object') ORDER BY createdAt"):
            text[fid] = (text.get(fid, "") + " | " + clean(t, 200)) if fid in text else clean(t, 200)
        frames = []
        q = ("SELECT fm.frameId, fm.createdAt, COALESCE(fm.capturedAtMs, f.ts), fm.dwell, fm.motion, COALESCE(fm.sessionId,'-'), "
             "COALESCE(t.createdAt, -1), f.id IS NOT NULL, fm.nearDupOf IS NOT NULL FROM frame_meta fm "
             "LEFT JOIN frame f ON f.id = fm.frameId LEFT JOIN forget_tombstone t ON t.frameId = fm.frameId "
             "WHERE fm.createdAt BETWEEN ? AND ? ORDER BY fm.createdAt, fm.frameId")
        for r in db.execute(q, (lo, hi)):
            frames.append(list(r[:7]) + [int(r[7]), int(r[8]), clean(text.get(r[0], ""), 400)])
        hist = [[fid, cap, re.sub(r"^Looked at (.*?) ·.*$", r"\1", t), round(c, 3)] for fid, cap, t, c in db.execute(
            "SELECT m.frameId, fm.capturedAtMs, m.text, m.confidence FROM memory m JOIN frame_meta fm ON fm.frameId = m.frameId "
            "WHERE m.type = 'place' AND m.text LIKE 'Looked at %' AND fm.createdAt BETWEEN ? AND ? ORDER BY fm.createdAt", (lo, hi))]
        db.close()
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    # 2. media fixes (accuracy unknown: the csv has none).
    fixes = []
    for r in csv.DictReader(open(pathlib.Path(a.gps).expanduser())):
        t = media_time(r)
        if r["lat"] and r["lon"] and t is not None:
            fixes.append([t, float(r["lat"]), float(r["lon"]), "HIROKID" if r["folder"] == "Hi Rokid" else "CAMERA", r["file"]])
    fixes.sort()

    # 3. pins (only place_pins.xml is opened from shared_prefs).
    prefs = dump / "x" / "shared_prefs" / "place_pins.xml"
    assert prefs.name not in DENY
    raw = re.search(r'name="pins">(.*?)</string>', prefs.read_text(), re.S)
    pins = []
    for p in json.loads(html.unescape(raw.group(1))) if raw else []:
        act, srcp = -1, "UNKNOWN"
        if p["name"] in LOGGED_PIN_TIMES:
            act, srcp = ist_ms(LOGGED_PIN_TIMES[p["name"]]), "LOG"
        else:
            near = [f[0] for f in fixes if dist_m(p["lat"], p["lon"], f[1], f[2]) <= INFER_M]
            if near:
                act, srcp = min(near), "INFERRED"
        pins.append([p["id"], clean(p["name"], 80), p.get("kind", "monument"), p["lat"], p["lon"], p.get("r", 60), p.get("parent") or "-", act, srcp])

    write_tsv(out / "frames.tsv", ["frameId", "createdAt", "capturedAt", "dwell", "motion", "sessionId", "tombAt", "photo", "nearDup", "text"], frames)
    write_tsv(out / "fixes.tsv", ["tMs", "lat", "lon", "src", "file"], fixes)
    write_tsv(out / "pins.tsv", ["id", "name", "kind", "lat", "lon", "radiusM", "parentId", "activeFrom", "activeSrc"], pins)
    write_tsv(out / "historical.tsv", ["frameId", "capturedAt", "name", "conf"], hist)
    if not (out / "truth.tsv").exists():
        write_tsv(out / "truth.tsv", ["label", "target", "startMs", "endMs", "source", "testable"],
                  [[l, t, ist_ms(s), ist_ms(e), src, ok] for l, t, s, e, src, ok in TRUTH_TEMPLATE])
    if not (out / "sites.tsv").exists():
        write_tsv(out / "sites.tsv", ["id", "name", "kind", "lat", "lon", "radiusM", "src"], [])

    print(f"frames={len(frames)} dwell={sum(f[3] for f in frames)} withPhoto={sum(f[7] for f in frames)} withText={sum(1 for f in frames if f[9])}")
    print(f"fixes={len(fixes)} pins={len(pins)} pinTimes=" + ",".join(f"{s}:{sum(1 for p in pins if p[8] == s)}" for s in ("LOG", "INFERRED", "UNKNOWN")))
    print(f"historical={len(hist)} out=mode700 (outside git)")


if __name__ == "__main__":
    main()
