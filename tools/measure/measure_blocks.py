#!/usr/bin/env python3
"""Mac-only measurement for the large-area seeding design (docs/superpowers/plans/2026-11-27-large-area-seeding-design.md, section 4 step 1). NOT shipped.

Serial requests only, one every 2 s, Retry-After honoured, stop after two 429s in a row, hard cap 400 requests.
Prints NUMBERS ONLY (no names, coordinates or URLs) and writes a JSON of counts to --out.
Usage: measure_blocks.py --out /path/counts.json [--blocks 60]
"""
import argparse, json, math, pathlib, random, re, statistics, sys, time, urllib.error, urllib.parse, urllib.request, gzip

UA = "PCA-measure/1.0 (+https://github.com/debasishdebs/Proactive-ARContextual-Agent)"
GAP = 2.0
CAP = 400
MIRRORS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter", "https://overpass.private.coffee/api/interpreter"]
# rough outline of the sample state (lat, lon), only used to pick blocks inside it
POLY = [(30.1, 74.0), (29.5, 75.4), (28.8, 76.2), (27.9, 77.0), (27.2, 78.2), (26.2, 77.8), (25.2, 77.3), (24.6, 76.0), (24.0, 75.3), (23.1, 74.5),
        (23.3, 73.0), (24.4, 72.2), (24.7, 71.0), (25.7, 70.0), (27.7, 70.0), (28.3, 69.6), (28.9, 70.7), (30.0, 72.9)]
state = {"n": 0, "last": 0.0, "c429": 0}
rec = {"geo": [], "http": {}}

class Stop(Exception): pass

def inside(lat, lon):
    r = False; j = len(POLY) - 1
    for i in range(len(POLY)):
        yi, xi = POLY[i]; yj, xj = POLY[j]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi: r = not r
        j = i
    return r

def send(url, form=None, timeout=30, accept=None):
    """returns (status, bytes, ms). Raises Stop on cap or two 429s in a row."""
    if state["n"] >= CAP: raise Stop("cap")
    wait = GAP - (time.monotonic() - state["last"])
    if wait > 0: time.sleep(wait)
    state["n"] += 1
    data = urllib.parse.urlencode(form).encode() if form else None
    h = {"User-Agent": UA, "Accept-Encoding": "gzip"}
    if accept: h["Accept"] = accept
    t = time.monotonic(); status = 0; body = b""
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=h), timeout=timeout) as r:
            body = r.read(); status = r.status
            if r.headers.get("Content-Encoding") == "gzip": body = gzip.decompress(body)
    except urllib.error.HTTPError as e:
        status = e.code
        ra = e.headers.get("Retry-After")
        if status == 429:
            state["c429"] += 1
            if state["c429"] >= 2: raise Stop("two 429 in a row")
            try: time.sleep(min(float(ra), 120) if ra else 20)
            except ValueError: time.sleep(20)
    except Exception as e:
        status = -1  # network error / timeout
    ms = (time.monotonic() - t) * 1000
    state["last"] = time.monotonic()
    if status != 429: state["c429"] = 0
    rec["http"][str(status)] = rec["http"].get(str(status), 0) + 1
    return status, body, ms

def js(b):
    try: return json.loads(b)
    except Exception: return {}

def classes():
    src = (pathlib.Path(__file__).resolve().parents[2] / "android-companion/services/connectors/src/main/kotlin/com/rokid/context/connectors/place/PlaceCellSource.kt").read_text()
    return sorted(set(re.findall(r'"(Q\d+)"\s+to\s+PlaceKinds\.', src)))  # MONUMENT_CLASSES + CURATED_LANDMARK_QIDS, as the app admits either

def dlon(lat, km): return km / (111.32 * math.cos(math.radians(lat)))

def block_at(lat, lon, km=19.8):  # 20.0 km is over the 400 km2 limit by rounding: refused as too big
    dl = km / 110.574
    return (lat, lon, lat + dl, lon + dlon(lat + dl / 2, km))  # s, w, n, e

def geosearch(b):
    s, w, n, e = b
    q = "https://en.wikipedia.org/w/api.php?action=query&list=geosearch&gsbbox=%.6f%%7C%.6f%%7C%.6f%%7C%.6f&gslimit=500&format=json&formatversion=2" % (n, w, s, e)
    st, body, ms = send(q)
    j = js(body)
    if st != 200 or "error" in j: return (st if st != 200 else -2), None, ms  # -2 = API error such as "box too big"
    return st, j.get("query", {}).get("geosearch", []), ms

def p31_of(ent):
    out = set()
    for c in ent.get("claims", {}).get("P31", []):
        v = c.get("mainsnak", {}).get("datavalue", {}).get("value")
        if isinstance(v, dict) and "id" in v: out.add(v["id"])
    return out

def qualifying_titles(hits, cls):
    """app rule: English Wikipedia article whose Wikidata P31 is admitted (or curated). Returns (set of titles, calls)."""
    calls = 0; qid_of = {}
    ids = sorted({h["pageid"] for h in hits}); title_of = {h["pageid"]: h["title"] for h in hits}
    for i in range(0, len(ids), 50):
        st, body, _ = send("https://en.wikipedia.org/w/api.php?action=query&prop=pageprops&ppprop=wikibase_item&pageids=" + "%7C".join(map(str, ids[i:i + 50])) + "&format=json&formatversion=2"); calls += 1
        for p in js(body).get("query", {}).get("pages", []):
            q = p.get("pageprops", {}).get("wikibase_item")
            if q: qid_of[p["title"]] = q
    qs = sorted(set(qid_of.values())); ent = {}
    for i in range(0, len(qs), 50):
        st, body, _ = send("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=" + "%7C".join(qs[i:i + 50]) + "&props=claims&format=json"); calls += 1
        ent.update(js(body).get("entities", {}))
    ok = {t for t, q in qid_of.items() if q in ent and (p31_of(ent[q]) & set(cls) or q in cls)}
    return ok, calls

def sparql(box, cls):
    s, w, n, e = box
    vals = " ".join("wd:" + c for c in cls)
    q = ('SELECT DISTINCT ?item ?title WHERE { SERVICE wikibase:box { ?item wdt:P625 ?loc . bd:serviceParam wikibase:cornerSouthWest "Point(%.5f %.5f)"^^geo:wktLiteral . '
         'bd:serviceParam wikibase:cornerNorthEast "Point(%.5f %.5f)"^^geo:wktLiteral } '
         '{ VALUES ?c { %s } ?item wdt:P31 ?c } UNION { VALUES ?item { %s } } '
         '?art schema:about ?item ; schema:isPartOf <https://en.wikipedia.org/> ; schema:name ?title }') % (w, s, e, n, vals, vals)
    st, body, ms = send("https://query.wikidata.org/sparql?format=json&query=" + urllib.parse.quote(q), timeout=70, accept="application/sparql-results+json")
    rows = js(body).get("results", {}).get("bindings", []) if st == 200 else []
    return st, {r["title"]["value"] for r in rows}, ms, len(body), len(rows)

def intro_cost(titles):
    ms_l = []; chars = []; st_l = []
    for i in range(0, len(titles) - 19, 20):
        chunk = titles[i:i + 20]
        st, body, ms = send("https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&exsentences=3&exlimit=20&titles=" + urllib.parse.quote("|".join(chunk)) + "&format=json&formatversion=2")
        st_l.append(st)
        if st == 200:
            ms_l.append(ms)
            chars += [len(p.get("extract", "")) for p in js(body).get("query", {}).get("pages", []) if p.get("extract")]
    return ms_l, chars, st_l

def pct(v, p):
    v = sorted(v); return v[min(len(v) - 1, int(round(p * (len(v) - 1))))] if v else None

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--out", required=True); ap.add_argument("--blocks", type=int, default=60); ap.add_argument("--no-overpass", action="store_true"); ap.add_argument("--intro-only", action="store_true", help="skip steps 1-2; take 20-40 qualifying titles from one 300 km SPARQL box around the state centre")
    a = ap.parse_args()
    _root = pathlib.Path(__file__).resolve().parents[2]; _o = pathlib.Path(a.out).resolve()
    if _o == _root or _root in _o.parents: raise SystemExit("refusing --out inside the repository: write to a scratch directory")
    cls = classes(); out = {"class_count": len(cls)}
    if a.intro_only:
        try:
            dl = 150 / 110.574; dn = 150 / (111.32 * math.cos(math.radians(26.9)))
            st, titles, ms, size, rows = sparql((26.9 - dl, 75.8 - dn, 26.9 + dl, 75.8 + dn), cls)
            out["sparql_300km"] = {"status": st, "ms": round(ms), "rows": rows, "bytes": size}
            pool = sorted(titles); pool = pool[:40] if len(pool) >= 40 else pool[:len(pool) // 20 * 20]
            ms_l, chars, st_l = intro_cost(pool)
            out["intro"] = {"calls": len(st_l), "titles_available": len(titles), "median_ms_per_call": round(statistics.median(ms_l)) if ms_l else None,
                            "median_chars_per_intro": statistics.median(chars) if chars else None, "mean_chars_per_intro": round(statistics.mean(chars)) if chars else None, "intros": len(chars)}
        except Stop as s2: out["stopped"] = str(s2)
        out["requests_total"] = state["n"]; out["http_status_counts"] = rec["http"]
        pathlib.Path(a.out).write_text(json.dumps(out, indent=1)); print(json.dumps(out, indent=1)); return
    try:
        # 1. grid sample of 20x20 km blocks inside the state, deterministic
        cands = []
        lat = 23.2
        while lat < 30.0:
            lon = 69.8
            while lon < 78.2:
                b = block_at(lat, lon)
                if inside((b[0] + b[2]) / 2, (b[1] + b[3]) / 2): cands.append(b)
                lon += 0.25
            lat += 0.25
        random.Random(27112026).shuffle(cands)
        sample = cands[:a.blocks]
        hits_n = []; ms_l = []; fail = 0
        for b in sample:
            st, hits, ms = geosearch(b)
            if hits is None: fail += 1; continue
            hits_n.append(len(hits)); ms_l.append(ms)
        ne = [h for h in hits_n if h > 0]
        out["blocks_requested"] = len(sample); out["blocks_ok"] = len(hits_n); out["blocks_failed"] = fail
        out["empty_share"] = round(sum(1 for h in hits_n if h == 0) / max(1, len(hits_n)), 3)
        out["cap500_share"] = round(sum(1 for h in hits_n if h >= 500) / max(1, len(hits_n)), 3)
        out["nonempty_median_hits"] = statistics.median(ne) if ne else None
        out["nonempty_p90_hits"] = pct(ne, 0.9); out["nonempty_mean_hits"] = round(statistics.mean(ne), 1) if ne else None
        out["mean_hits_all_blocks"] = round(statistics.mean(hits_n), 2) if hits_n else None
        out["geosearch_median_ms"] = round(statistics.median(ms_l)) if ms_l else None
        out["geosearch_p90_ms"] = round(pct(ms_l, 0.9)) if ms_l else None

        # 2. SPARQL, 100 km and 200 km boxes around the densest sampled block's area
        center = max(sample[:len(hits_n)], key=lambda b: hits_n[sample.index(b)] if sample.index(b) < len(hits_n) else 0) if hits_n else sample[0]
        clat = (center[0] + center[2]) / 2; clon = (center[1] + center[3]) / 2
        boxes = {}
        for km in (100, 200):
            dl = km / 2 / 110.574; dn = km / 2 / (111.32 * math.cos(math.radians(clat)))
            boxes[km] = (clat - dl, clon - dn, clat + dl, clon + dn)
        sp = {}
        for km in (100, 200):
            st, titles, ms, size, rows = sparql(boxes[km], cls)
            sp[km] = titles
            out["sparql_%dkm" % km] = {"status": st, "ms": round(ms), "rows": rows, "distinct_titles": len(titles), "bytes": size}
        # overlap on the 100 km box: geosearch over 5 x 5 blocks of 20 km, same class rule
        b100 = boxes[100]; geo_hits = []; dl = 19.8 / 110.574; cap_blocks = 0; gcalls = 0
        lat0 = b100[0]
        for i in range(5):
            lon0 = b100[1]
            for j in range(5):
                bb = (lat0, lon0, lat0 + dl, lon0 + dlon(lat0 + dl / 2, 19.8))
                st, hits, ms = geosearch(bb); gcalls += 1
                if hits is not None:
                    geo_hits += hits
                    if len(hits) >= 500: cap_blocks += 1
                lon0 = bb[3]
            lat0 += dl
        uniq = {h["pageid"]: h for h in geo_hits}
        geo_q, qcalls = qualifying_titles(list(uniq.values()), cls)
        s100 = sp[100]
        out["overlap_100km"] = {"geosearch_blocks": gcalls, "blocks_at_cap": cap_blocks, "geosearch_hits": len(uniq), "geosearch_qualifying": len(geo_q), "class_filter_calls": qcalls,
                                "sparql_titles": len(s100), "both": len(geo_q & s100), "sparql_only": len(s100 - geo_q), "geosearch_only": len(geo_q - s100)}

        # 3. intro text, batches of 20 qualifying titles
        pool = sorted(s100 | geo_q)
        ms_l, chars, st_l = intro_cost(pool[:60] if len(pool) >= 60 else pool[:len(pool) // 20 * 20])
        out["intro"] = {"calls": len(st_l), "titles_available": len(pool), "median_ms_per_call": round(statistics.median(ms_l)) if ms_l else None,
                        "median_chars_per_intro": statistics.median(chars) if chars else None, "mean_chars_per_intro": round(statistics.mean(chars)) if chars else None, "intros": len(chars)}
    except Stop as s:
        out["stopped"] = str(s)
    # 4. Overpass, one block-sized try per mirror
    ov = []
    try:
        if a.no_overpass: raise Stop('overpass skipped')
        b = sample[0] if 'sample' in dir() else block_at(26.0, 73.0)
        bb = "(%.6f,%.6f,%.6f,%.6f)" % b
        q = '[out:json][timeout:25];(nwr["historic"]["name"]%s;nwr["heritage"]["name"]%s;nwr["tourism"~"^(attraction|museum|viewpoint)$"]["name"]%s;);out tags center bb;' % (bb, bb, bb)
        for m in MIRRORS:
            st, body, ms = send(m, {"data": q}, timeout=30)
            ov.append({"status": st, "ms": round(ms), "bytes": len(body), "json_ok": bool(js(body))})
    except Stop as s:
        if not a.no_overpass: out.setdefault("stopped", str(s))
    out["overpass"] = ov
    out["requests_total"] = state["n"]; out["http_status_counts"] = rec["http"]
    pathlib.Path(a.out).write_text(json.dumps(out, indent=1))
    print(json.dumps(out, indent=1))

main()
