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
# Adaptive windows (v18 build spec section 4): the window depends on how the bracketing media fixes say the user was moving.
SPAN_MAX_S = 120 * 60           # beyond this span between the bracketing fixes an average speed says nothing -> UNKNOWN
STATIONARY_MAX_M, STATIONARY_MAX_MS = 150.0, 0.3
WALKING_MAX_MS = 2.5
WINDOW_S = {"UNKNOWN": 600, "STATIONARY": 3600, "WALKING": 900, "VEHICLE": 300}
CLASSES = ("UNKNOWN", "STATIONARY", "WALKING", "VEHICLE")
IMPORT_HEADER = "momentId\tatMs\tanchorKind\tanchorId\tlat\tlon\taccuracyM\tfixAgeMs\tspeedClass\tmethod"
PROPOSAL_HEADER = "momentId\tlat\tlon\tsource\tgapSeconds\tspeedClass\tmethod"
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


def classify(t, fixes, times):
    """Adaptive proposal for a Moment at t (ms). Returns (cls, proposal) where proposal is
    (lat, lon, method, gapMs, accuracyM) or None (blank). The class is checked top-down:
    UNKNOWN (a bracket is missing, or span > 120 min), STATIONARY (d <= 150 m and v <= 0.3 m/s),
    WALKING (v <= 2.5 m/s), VEHICLE. Gaps are inclusive at the window edge."""
    i = bisect.bisect_right(times, t)
    prev = fixes[i - 1] if i > 0 else None
    nxt = fixes[i] if i < len(fixes) else None
    d = v = 0.0
    if prev is None or nxt is None or (nxt[0] - prev[0]) / 1000.0 > SPAN_MAX_S:
        cls = "UNKNOWN"
    else:
        span = (nxt[0] - prev[0]) / 1000.0     # > 0: prev <= t < next
        d = dist_m(prev[1], prev[2], nxt[1], nxt[2])
        v = d / span
        if d <= STATIONARY_MAX_M and v <= STATIONARY_MAX_MS:
            cls = "STATIONARY"
        elif v <= WALKING_MAX_MS:
            cls = "WALKING"
        else:
            cls = "VEHICLE"
    w_ms = WINDOW_S[cls] * 1000
    dp = t - prev[0] if prev else None
    dn = nxt[0] - t if nxt else None
    if dp is not None and dn is not None and dp <= w_ms and dn <= w_ms:
        wgt = (t - prev[0]) / (nxt[0] - prev[0])
        lat = prev[1] + (nxt[1] - prev[1]) * wgt
        lon = prev[2] + (nxt[2] - prev[2]) * wgt
        method, gap_ms = "INTERPOLATED", min(dp, dn)
    else:
        c = [(g, f) for g, f in ((dp, prev), (dn, nxt)) if f is not None and g <= w_ms]
        if not c:
            return cls, None
        gap_ms, f = min(c, key=lambda x: x[0])
        lat, lon, method = f[1], f[2], "NEAREST"
    gap_s = gap_ms / 1000.0
    if cls == "STATIONARY":
        acc = 15 + d
    elif cls == "WALKING":
        acc = 15 + max(v, 0.5) * gap_s
    elif cls == "VEHICLE":
        acc = 15 + v * gap_s
    else:
        acc = 15 + 1.4 * gap_s
    return cls, (lat, lon, method, int(gap_ms), min(5000.0, acc))


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


def moments_anchored(db_copy):
    """(momentId, momentType, earliest part time, anchorKind, anchorId). The anchor is the earliest FRAME part, else the earliest MARK part."""
    con = sqlite3.connect("file:%s?mode=ro" % db_copy, uri=True)
    try:
        ms = con.execute("SELECT momentId, momentType FROM moment WHERE momentType IN ('FRAME','MARK')").fetchall()
        parts = {}
        for mid, kind, ref, t in con.execute(
                "SELECT momentId, kind, refId, partTimeMs FROM moment_part WHERE kind IN ('FRAME','MARK')"):
            parts.setdefault(mid, []).append((kind, ref, t))
    finally:
        con.close()
    out = []
    for mid, mtype in ms:
        ps = parts.get(mid, [])
        t = min((p[2] for p in ps), default=None)
        anchor = None
        for kind in ("FRAME", "MARK"):
            c = sorted((p[2], p[1]) for p in ps if p[0] == kind)
            if c:
                anchor = (kind, c[0][1])
                break
        out.append((mid, mtype, t, anchor[0] if anchor else None, anchor[1] if anchor else None))
    out.sort(key=lambda r: (r[2] is None, r[2] or 0, r[0]))
    return out


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


def run_adaptive(db_path, csv_path, out, emit_import=False, keep_scratch=False):
    """Adaptive-window dry run: per-class counts only in summary.md, proposals.tsv (coordinates, stays on the Mac).
    With emit_import it also writes moment-loc-import.tsv (0600) for tools/import-moment-locations.sh."""
    make_out(out)
    scratch = os.path.join(out, "scratch")
    os.makedirs(scratch, mode=0o700, exist_ok=True)
    db_copy = os.path.join(scratch, "context.db")
    shutil.copyfile(db_path, db_copy)
    fixes, skipped = load_fixes(csv_path)
    times = [f[0] for f in fixes]
    moments = moments_anchored(db_copy)
    cnt = {c: Counter() for c in CLASSES}
    by_day, by_day_all = Counter(), Counter()
    props, imports, no_time = [], [], 0
    for mid, mtype, t, akind, aid in moments:
        if t is None or akind is None:
            no_time += 1
            continue
        day = datetime.fromtimestamp(t / 1000, IST).strftime("%Y-%m-%d")
        by_day_all[day] += 1
        cls, p = classify(t, fixes, times)
        cnt[cls]["candidates"] += 1
        if p is None:
            cnt[cls]["blank"] += 1
            continue
        lat, lon, method, gap_ms, acc = p
        cnt[cls][method.lower()] += 1
        by_day[day] += 1
        props.append((mid, lat, lon, gap_ms, cls, method))
        imports.append((mid, t, akind, aid, lat, lon, acc, gap_ms, cls, method))
    tsv = os.path.join(out, "proposals.tsv")
    with open(tsv, "w") as f:
        f.write(PROPOSAL_HEADER + "\n")
        for mid, lat, lon, gap_ms, cls, method in props:
            f.write("%s\t%.6f\t%.6f\tMEDIA_BACKFILL\t%d\t%s\t%s\n" % (mid, lat, lon, round(gap_ms / 1000.0), cls, method))
    os.chmod(tsv, 0o600)
    if emit_import:
        itsv = os.path.join(out, "moment-loc-import.tsv")
        with open(itsv, "w") as f:
            f.write(IMPORT_HEADER + "\n")
            for mid, t, akind, aid, lat, lon, acc, gap_ms, cls, method in imports:
                f.write("%s\t%d\t%s\t%s\t%.6f\t%.6f\t%.1f\t%d\t%s\t%s\n" % (mid, t, akind, aid, lat, lon, acc, gap_ms, cls, method))
        os.chmod(itsv, 0o600)
    total = {k: sum(cnt[c][k] for c in CLASSES) for k in ("candidates", "interpolated", "nearest", "blank")}
    lines = ["# GPS backfill dry run, adaptive windows (counts only)", "",
             "Windows: UNKNOWN 10 min, STATIONARY 60 min, WALKING 15 min, VEHICLE 5 min. Media fixes used: %d (skipped, no coords or time: %d)." % (len(fixes), skipped),
             "DRY RUN: nothing was written to any phone.", "",
             "- Moments total: %d (no anchor or part time: %d)" % (len(moments), no_time),
             "- Candidates: %d, with a proposal: %d (interpolated %d, nearest %d), blank: %d" % (
                 total["candidates"], total["interpolated"] + total["nearest"], total["interpolated"], total["nearest"], total["blank"]),
             "", "## By speed class: candidates / interpolated / nearest / blank"]
    lines += ["- %s: %d / %d / %d / %d" % (c, cnt[c]["candidates"], cnt[c]["interpolated"], cnt[c]["nearest"], cnt[c]["blank"]) for c in CLASSES]
    lines += ["", "## By day (IST): proposals / candidates"]
    lines += ["- %s: %d / %d" % (d, by_day.get(d, 0), by_day_all[d]) for d in sorted(by_day_all)]
    lines += [""]
    with open(os.path.join(out, "summary.md"), "w") as f:
        f.write("\n".join(lines))
    if not keep_scratch:
        try:
            shutil.rmtree(scratch)
        except OSError as e:
            print("WARNING: could not delete scratch copy %s: %s" % (scratch, e))
    return dict(total=len(moments), no_time=no_time, classes={c: dict(cnt[c]) for c in CLASSES}, totals=total, by_day=dict(by_day_all))


def adaptive_fixtures():
    """Boundary cases for classify(). Invented coordinates near lat 10 / lon 20. Distances and speeds are probed +-0.1 m / +-0.2 % around each threshold (float safe)."""
    B = 1_000_000_000_000

    def lon(m): return 20.0 + m / (6371000.0 * math.pi / 180 * math.cos(math.radians(10.0)))   # same Earth radius as dist_m

    def pair(d_m, span_s): return [(B, 10.0, lon(0)), (B + span_s * 1000, 10.0, lon(d_m))]

    def cl(fx, dt_ms): return classify(B + dt_ms, fx, [f[0] for f in fx])

    # 150 m and 0.3 m/s (STATIONARY needs both)
    assert cl(pair(149.9, 600), 300_000)[0] == "STATIONARY"
    assert cl(pair(150.1, 3000), 300_000)[0] == "WALKING"       # too far, slow
    assert cl(pair(89.9, 300), 100_000)[0] == "STATIONARY"      # 0.2997 m/s
    assert cl(pair(90.3, 300), 100_000)[0] == "WALKING"         # 0.301 m/s
    # 2.5 m/s
    assert cl(pair(749.9, 300), 100_000)[0] == "WALKING"
    assert cl(pair(750.6, 300), 100_000)[0] == "VEHICLE"
    # a 120 min span still has a speed, one second more is UNKNOWN
    assert cl(pair(0.0, 7200), 100_000)[0] == "STATIONARY"
    assert cl(pair(0.0, 7201), 100_000)[0] == "UNKNOWN"
    # UNKNOWN, one-sided: 10 min window, edge +-1 s, before and after the only fix
    one = [(B, 10.0, lon(0))]
    c, p = cl(one, 600_000); assert c == "UNKNOWN" and p[2] == "NEAREST" and p[3] == 600_000
    assert cl(one, 601_000)[1] is None
    assert cl(one, -600_000)[1] is not None and cl(one, -601_000)[1] is None
    assert cl([], 0) == ("UNKNOWN", None)
    # STATIONARY 60 min: interpolated only when both brackets are inside
    st = pair(0.0, 7000)
    assert cl(st, 3_400_000)[1][2] == "INTERPOLATED"            # 3400 s / 3600 s
    c, p = cl(st, 3_399_000); assert p[2] == "NEAREST" and p[3] == 3_399_000   # next is 3601 s away
    assert cl(pair(0.0, 7200), 3_601_000)[1][2] == "NEAREST"    # prev 3601 s, next 3599 s
    assert cl(st, 3_500_000)[1][2] == "INTERPOLATED"
    # WALKING 15 min (d 300 m > 150 m)
    wk = pair(300.0, 2000)
    c, p = cl(wk, 900_000); assert c == "WALKING" and p[2] == "NEAREST" and p[3] == 900_000
    assert cl(wk, 901_000)[1] is None
    wk2 = pair(300.0, 1800)
    assert cl(wk2, 900_000)[1][2] == "INTERPOLATED"
    c, p = cl(wk2, 901_000); assert p[2] == "NEAREST" and p[3] == 899_000
    # VEHICLE 5 min
    vh = pair(2000.0, 400)                                      # 5 m/s
    assert cl(vh, 100_000)[0] == "VEHICLE" and cl(vh, 100_000)[1][2] == "INTERPOLATED"
    c, p = cl(vh, 99_000); assert p[2] == "NEAREST" and p[3] == 99_000
    vh2 = pair(6000.0, 1200)
    c, p = cl(vh2, 300_000); assert p[2] == "NEAREST" and p[3] == 300_000
    assert cl(vh2, 301_000)[1] is None
    # accuracy: STATIONARY 15 + d, UNKNOWN 15 + 1.4 gap, WALKING 15 + max(v,0.5) gap, VEHICLE 15 + v gap, cap 5000
    c, p = cl(pair(100.0, 3000), 1_000_000); assert c == "STATIONARY" and abs(p[4] - 115.0) < 0.5
    c, p = cl(one, 600_000); assert abs(p[4] - (15 + 1.4 * 600)) < 1e-6
    c, p = cl(wk, 600_000); assert abs(p[4] - (15 + 0.5 * 600)) < 1e-6      # v 0.15 -> floor 0.5
    c, p = cl(vh2, 200_000); assert abs(p[4] - (15 + 5.0 * 200)) < 1.0
    c, p = cl(pair(100_000.0, 1000), 300_000); assert c == "VEHICLE" and p[4] == 5000.0
    return True


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
        adaptive_fixtures()
        out = os.path.join(base, "outA")
        res = run_adaptive(dbp, csvp, out, emit_import=True)
        itsv = os.path.join(out, "moment-loc-import.tsv")
        lines = open(itsv).read().splitlines()
        assert lines[0] == IMPORT_HEADER and oct(os.stat(itsv).st_mode & 0o777) == "0o600"
        rows = {l.split("\t")[0]: l.split("\t") for l in lines[1:]}
        assert all(len(r) == 10 for r in rows.values())
        assert rows["m_interp"][2:4] == ["FRAME", "r"] and rows["m_mark"][2] == "MARK", rows
        assert not os.path.exists(os.path.join(out, "scratch"))
        head = open(os.path.join(out, "proposals.tsv")).read().splitlines()
        assert head[0] == PROPOSAL_HEADER and all(l.split("\t")[3] == "MEDIA_BACKFILL" for l in head[1:])
        assert res["totals"]["candidates"] == 7 and sum(res["classes"][c].get("candidates", 0) for c in CLASSES) == 7, res
        print("fixtures adaptive: %s" % res["totals"])
        print("fixtures OK")
    finally:
        shutil.rmtree(base, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("db", nargs="?", help="path to a copy of the phone's context.db")
    ap.add_argument("--csv", default=os.path.expanduser("~/Desktop/rokid/backups/trip-gps/trip_media_gps.csv"))
    ap.add_argument("--out", help="output folder, outside any git tree (created 0700)")
    ap.add_argument("--window", type=int, choices=(2, 5, 10, 15, 30, 60), default=None,
                    help="fixed window in minutes (the legacy dry run, for comparison). Omit it for the adaptive windows by speed class")
    ap.add_argument("--emit-import", action="store_true", help="adaptive mode only: also write moment-loc-import.tsv (0600) in --out")
    ap.add_argument("--keep-scratch", action="store_true")
    ap.add_argument("--fixtures", action="store_true", help="run the synthetic self-test and exit")
    a = ap.parse_args()
    if a.fixtures:
        fixtures()
        return 0
    if not a.db or not a.out:
        ap.error("db and --out are required")
    if a.window is None:
        res = run_adaptive(os.path.expanduser(a.db), os.path.expanduser(a.csv), os.path.expanduser(a.out), a.emit_import, a.keep_scratch)
        print("adaptive: moments=%d no_anchor_or_time=%d totals=%s" % (res["total"], res["no_time"], res["totals"]))
        for c in CLASSES:
            print("  %-10s %s" % (c, res["classes"][c]))
        return 0
    if a.emit_import:
        ap.error("--emit-import needs the adaptive mode (do not pass --window)")
    res = run(os.path.expanduser(a.db), os.path.expanduser(a.csv), os.path.expanduser(a.out), a.window, a.keep_scratch)
    print("N=%d min: moments=%d proposals=%d blank=%d sources=%s buckets=%s" %
          (a.window, res["total"], res["proposals"], res["blank"], res["by_src"], res["by_bucket"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
