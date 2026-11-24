#!/usr/bin/env python3
"""Dry-run GPS backfill planner for existing Moments. Stdlib only.

Reads a COPY of the phone's context.db (copied to a scratch dir inside --out,
opened read-only) and the media-fix CSV, and writes summary.md (counts only)
and proposals.tsv (coordinates; stays on the Mac). Writes nothing to a phone,
opens no photos, uses no network. See BACKFILL-GPS.md.
"""
import argparse, bisect, csv, math, os, re, shutil, sqlite3, stat, sys, tempfile
from collections import Counter
from datetime import datetime, timezone, timedelta

IST = timezone(timedelta(hours=5, minutes=30))
INTERP_MAX_S = 300      # fix must be <=5 min away on each side
INTERP_MAX_M = 200.0    # the two fixes <=200 m apart
INTERP_MAX_MS = 2.0     # implied speed <=2 m/s
BUCKETS = [(60, "<=1min"), (120, "1-2min"), (300, "2-5min"), (600, "5-10min")]
NAME_RES = [re.compile(r"^(\d{8})_(\d{6})"), re.compile(r"^(?:img|vid)-(\d{8})-(\d{6})")]


def name_time_ms(name):
    """Local IST time from the file name -> epoch ms, or None."""
    for rx in NAME_RES:
        m = rx.match(name.lower())
        if m:
            try:
                dt = datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S")
            except ValueError:
                return None
            return int(dt.replace(tzinfo=IST).timestamp() * 1000)
    return None


def load_fixes(csv_path):
    """Sorted list of (tMs, lat, lon). Rows without coordinates or a parsable name are skipped."""
    out, skipped = [], 0
    with open(csv_path, newline="") as f:
        for r in csv.DictReader(f):
            t = name_time_ms(r["file"])
            try:
                lat, lon = float(r["lat"]), float(r["lon"])
            except (ValueError, TypeError):
                lat = lon = None
            if t is None or lat is None:
                skipped += 1
                continue
            out.append((t, lat, lon))
    out.sort()
    return out, skipped


def dist_m(a_lat, a_lon, b_lat, b_lon):
    p = math.pi / 180
    x = (b_lon - a_lon) * p * math.cos((a_lat + b_lat) / 2 * p)
    y = (b_lat - a_lat) * p
    return 6371000.0 * math.hypot(x, y)


def propose(t, fixes, times, n_s):
    """Return (lat, lon, source, ageSeconds) or None."""
    i = bisect.bisect_right(times, t)
    prev = fixes[i - 1] if i > 0 else None
    nxt = fixes[i] if i < len(fixes) else None
    dp = (t - prev[0]) / 1000.0 if prev else None
    dn = (nxt[0] - t) / 1000.0 if nxt else None
    lim = min(n_s, INTERP_MAX_S)
    if prev and nxt and dp <= lim and dn <= lim:
        span = (nxt[0] - prev[0]) / 1000.0
        d = dist_m(prev[1], prev[2], nxt[1], nxt[2])
        if d <= INTERP_MAX_M and (span == 0 or d / span <= INTERP_MAX_MS):
            w = 0.0 if span == 0 else (t - prev[0]) / (nxt[0] - prev[0])
            return (prev[1] + (nxt[1] - prev[1]) * w, prev[2] + (nxt[2] - prev[2]) * w,
                    "interpolated", int(round(min(dp, dn))))
    cands = [(d, f) for d, f in ((dp, prev), (dn, nxt)) if f is not None and d <= n_s]
    if not cands:
        return None
    d, f = min(cands, key=lambda c: c[0])
    return (f[1], f[2], "media_exact_window", int(round(d)))


def bucket(age_s):
    for lim, label in BUCKETS:
        if age_s <= lim:
            return label
    return ">10min"


def moments_from(db_copy):
    con = sqlite3.connect("file:%s?mode=ro" % db_copy, uri=True)
    try:
        rows = con.execute(
            "SELECT m.momentId, m.momentType, MIN(p.partTimeMs) FROM moment m "
            "LEFT JOIN moment_part p ON p.momentId=m.momentId AND p.kind IN ('FRAME','MARK') "
            "WHERE m.momentType IN ('FRAME','MARK') GROUP BY m.momentId ORDER BY 3").fetchall()
    finally:
        con.close()
    return rows


def in_git_tree(path):
    p = os.path.realpath(path)
    while True:
        if os.path.exists(os.path.join(p, ".git")):
            return True
        parent = os.path.dirname(p)
        if parent == p:
            return False
        p = parent


def make_out(path):
    if in_git_tree(path):
        sys.exit("refusing: --out is inside a git tree: " + path)
    os.makedirs(path, mode=0o700, exist_ok=True)
    os.chmod(path, 0o700)


def run(db_path, csv_path, out, n_min, keep_scratch=False):
    make_out(out)
    scratch = os.path.join(out, "scratch")
    os.makedirs(scratch, mode=0o700, exist_ok=True)
    db_copy = os.path.join(scratch, "context.db")
    shutil.copyfile(db_path, db_copy)  # the dump has no -wal; never open the original
    fixes, skipped = load_fixes(csv_path)
    times = [f[0] for f in fixes]
    moments = moments_from(db_copy)
    n_s = n_min * 60
    props, blank, no_time = [], 0, 0
    by_day, by_day_all, by_bucket, by_src = Counter(), Counter(), Counter(), Counter()
    for mid, mtype, t in moments:
        if t is None:
            no_time += 1
            blank += 1
            continue
        day = datetime.fromtimestamp(t / 1000, IST).strftime("%Y-%m-%d")
        by_day_all[day] += 1
        p = propose(t, fixes, times, n_s)
        if p is None:
            blank += 1
            continue
        props.append((mid,) + p)
        by_day[day] += 1
        by_bucket[bucket(p[3])] += 1
        by_src[p[2]] += 1
    tsv = os.path.join(out, "proposals.tsv")
    with open(tsv, "w") as f:
        f.write("momentId\tlat\tlon\tsource\tageSeconds\n")
        for mid, lat, lon, src, age in props:
            f.write("%s\t%.6f\t%.6f\t%s\t%d\n" % (mid, lat, lon, src, age))
    os.chmod(tsv, 0o600)
    lines = ["# GPS backfill dry run (counts only)", "",
             "Window N = %d min. Media fixes used: %d (skipped, no coords or time: %d)." % (n_min, len(fixes), skipped),
             "DRY RUN: nothing was written to any phone.", "",
             "- Moments total: %d" % len(moments),
             "- With a proposal: %d" % len(props),
             "- Blank (left blank): %d (of which no part time: %d)" % (blank, no_time),
             "", "## By source"]
    lines += ["- %s: %d" % (k, by_src.get(k, 0)) for k in ("media_exact_window", "interpolated")]
    lines += ["", "## By age bucket"]
    lines += ["- %s: %d" % (lab, by_bucket.get(lab, 0)) for _, lab in BUCKETS]
    lines += ["", "## By day (IST): proposals / Moments"]
    lines += ["- %s: %d / %d" % (d, by_day.get(d, 0), by_day_all[d]) for d in sorted(by_day_all)]
    lines += [""]
    with open(os.path.join(out, "summary.md"), "w") as f:
        f.write("\n".join(lines))
    if not keep_scratch:
        try:
            shutil.rmtree(scratch)
        except OSError as e:
            print("WARNING: could not delete scratch copy %s: %s" % (scratch, e))
    return dict(total=len(moments), proposals=len(props), blank=blank, by_src=dict(by_src),
                by_bucket=dict(by_bucket), by_day=dict(by_day_all))


def fixtures():
    """Synthetic self-test. Invented origin 10.0 / 20.0, generic names."""
    base = tempfile.mkdtemp(prefix="backfill_fixtures_")
    try:
        # offsets in metres east of the origin -> degrees of longitude at lat 10
        def lon(m): return 20.0 + m / (111320.0 * math.cos(math.radians(10.0)))
        def ms(h, mi, s): return int(datetime(2026, 1, 1, h, mi, s, tzinfo=IST).timestamp() * 1000)
        csvp = os.path.join(base, "fixes.csv")
        with open(csvp, "w") as f:
            f.write("file,folder,kind,exif_time,lat,lon\n")
            f.write("20260101_100000.jpg,Cam,image,,10.0,%.7f\n" % lon(0))
            f.write("20260101_100200.jpg,Cam,image,,10.0,%.7f\n" % lon(100))   # 100 m / 120 s ok
            f.write("img-20260101-110000-aa-P0-0.jpg,Cam,image,,10.0,%.7f\n" % lon(0))
            f.write("vid-20260101-110400-bb.mp4,Cam,video,,10.0,%.7f\n" % lon(500))  # 500 m apart
            f.write("vid-20260101-120000-cc.mp4,Cam,video,,,\n")                 # no coords
            f.write("20260101_130000.jpg,Cam,image,,10.0,%.7f\n" % lon(0))
        dbp = os.path.join(base, "context.db")
        con = sqlite3.connect(dbp)
        con.execute("CREATE TABLE moment (momentId TEXT PRIMARY KEY, momentType TEXT NOT NULL)")
        con.execute("CREATE TABLE moment_part (momentId TEXT, kind TEXT, refId TEXT, partTimeMs INTEGER)")
        cases = [("m_interp", ms(10, 1, 0)),      # between fixes, 60 s each side -> interpolated
                 ("m_near_2m", ms(10, 4, 0)),     # 120 s after last fix -> nearest at N>=2
                 ("m_far", ms(10, 30, 0)),        # nothing near -> blank
                 ("m_fast", ms(11, 2, 0)),        # pair 500 m / 240 s: speed 2.08 -> not interpolated, nearest 120 s
                 ("m_9min", ms(13, 9, 0)),        # 9 min -> only N=10
                 ("m_nocoord", ms(12, 0, 30))]    # only a coordinate-less file nearby -> blank
        for mid, t in cases:
            con.execute("INSERT INTO moment VALUES (?, 'FRAME')", (mid,))
            con.execute("INSERT INTO moment_part VALUES (?, 'FRAME', 'r', ?)", (mid, t))
        con.execute("INSERT INTO moment VALUES ('m_mark','MARK')")
        con.execute("INSERT INTO moment_part VALUES ('m_mark','MARK','r',?)", (ms(13, 0, 20),))
        con.commit(); con.close()
        exp = {2: (4, "m_interp m_near_2m m_fast m_mark"),
               5: (4, "m_interp m_near_2m m_fast m_mark"),
               10: (5, "m_interp m_near_2m m_fast m_mark m_9min")}
        for n, (count, ids) in exp.items():
            out = os.path.join(base, "out%d" % n)
            res = run(dbp, csvp, out, n)
            got = open(os.path.join(out, "proposals.tsv")).read().splitlines()[1:]
            got_ids = sorted(l.split("\t")[0] for l in got)
            assert got_ids == sorted(ids.split()), (n, got_ids)
            assert res["proposals"] == count and res["total"] == 7, (n, res)
            srcs = {l.split("\t")[0]: l.split("\t")[3] for l in got}
            assert srcs["m_interp"] == "interpolated" and srcs["m_fast"] == "media_exact_window", srcs
            assert oct(os.stat(out).st_mode & 0o777) == "0o700"
            assert not os.path.exists(os.path.join(out, "scratch"))
            print("fixtures N=%d min: moments=%d proposals=%d blank=%d sources=%s" %
                  (n, res["total"], res["proposals"], res["blank"], res["by_src"]))
        assert name_time_ms("20261121_173418.jpg") == name_time_ms("img-20261121-173418-x.jpg")
        assert name_time_ms("20261121_173418.jpg") == int(datetime(2026, 11, 21, 12, 4, 18, tzinfo=timezone.utc).timestamp() * 1000)
        print("fixtures OK")
    finally:
        shutil.rmtree(base, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("db", nargs="?", help="path to a copy of the phone's context.db")
    ap.add_argument("--csv", default=os.path.expanduser("~/Desktop/rokid/backups/trip-gps/trip_media_gps.csv"))
    ap.add_argument("--out", help="output folder, outside any git tree (created 0700)")
    ap.add_argument("--window", type=int, choices=(2, 5, 10), default=5, help="minutes (default 5)")
    ap.add_argument("--keep-scratch", action="store_true")
    ap.add_argument("--fixtures", action="store_true", help="run the synthetic self-test and exit")
    a = ap.parse_args()
    if a.fixtures:
        fixtures()
        return 0
    if not a.db or not a.out:
        ap.error("db and --out are required")
    res = run(os.path.expanduser(a.db), os.path.expanduser(a.csv), os.path.expanduser(a.out), a.window, a.keep_scratch)
    print("N=%d min: moments=%d proposals=%d blank=%d sources=%s buckets=%s" %
          (a.window, res["total"], res["proposals"], res["blank"], res["by_src"], res["by_bucket"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
