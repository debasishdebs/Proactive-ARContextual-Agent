#!/usr/bin/env python3
"""Mac-only end-to-end measurement of a BULK-WIKIDATA seed for one region (docs/superpowers/plans/2026-11-27-large-area-seeding-design.md, 'Sikkim coverage 27 Nov' and 'Bulk Wikidata end-to-end 27 Nov'). NOT shipped.
Reuses measure_blocks.py helpers. Serial, 1 request / 2 s, app User-Agent, Retry-After honoured, stop on two 429s in a row, hard cap 300 requests.
Stages: trip region (4 sub-boxes, paging demo, whole-region + 200 km probe, class rule app vs looser, intro, 1 km coordinate check, landmarks, per-block geosearch comparison), dense metro box, semi-arid state sample boxes.
Writes full detail (including names) to --out (keep it OUT of the repo); prints aggregates. Usage: measure_region.py --out /path/region.json
"""
import argparse, collections, json, math, pathlib, re, statistics, time, urllib.parse
here = pathlib.Path(__file__).resolve().parent
_src = (here / "measure_blocks.py").read_text().rsplit("\nmain()", 1)[0]  # reuse helpers without running its main()
ns = {"__name__": "mb", "__file__": str(here / "measure_blocks.py")}
exec(compile(_src, "measure_blocks.py", "exec"), ns)
send, js, Stop, state, rec, classes, block_at, geosearch, qualifying_titles = (ns[k] for k in ("send", "js", "Stop", "state", "rec", "classes", "block_at", "geosearch", "qualifying_titles"))
ns["CAP"] = 300  # send() reads the module-level CAP
CELL_SRC = (here.parents[1] / "android-companion/services/connectors/src/main/kotlin/com/rokid/context/connectors/place/PlaceCellSource.kt").read_text()
PERSON = {"Q5", "Q15632617", "Q95074", "Q21070568"}  # PERSON_CLASSES in PlaceCellSource.kt
S, W, N, E = 27.0, 87.9, 28.2, 89.1  # the trip region
LOOSE_RE = re.compile(r"temple|monaster|gompa|vihara|viewpoint|scenic|\blake\b|reservoir|\bpeak\b|summit|mountain|protected area|national park|nature reserve|wildlife|sanctuary|hill station|bridge|museum|\bpark\b|garden|waterfall|pass$", re.I)
LANDMARKS = {
    "Rumtek Monastery": r"rumtek", "Pemayangtse Monastery": r"pemayangtse", "Tsomgo (Changu) Lake": r"tsom?go|tsongmo|changu", "Nathu La": r"nathu",
    "Tiger Hill (Darjeeling)": r"tiger hill", "Batasia Loop": r"batasia", "Himalayan Mountaineering Institute": r"himalayan mountaineering", "Kangchenjunga": r"kangchenjunga|kanchenjunga",
    "Namchi Char Dham (Siddhesvara Dham)": r"siddhesh?vara dham|char dham", "Ravangla Buddha Park (Tathagata Tsal)": r"tathagata|buddha park", "Yuksom": r"^yuksom", "Kalimpong Durpin Dara": r"durpin",
    "Enchey Monastery": r"enchey", "Ghum Monastery": r"ghum", "Darjeeling Peace Pagoda": r"peace pagoda", "Khecheopalri Lake": r"khecheopalri", "Rabdentse ruins": r"rabdentse",
    "Do-drul Chorten": r"do.drul", "Banjhakri Falls": r"banjhakri", "Padmaja Naidu Himalayan Zoological Park": r"padmaja naidu|himalayan zoological", "Pelling": r"^pelling", "Gangtok": r"^gangtok$",
}

def dl(km): return km / 110.574
def dn(lat, km): return km / (111.32 * math.cos(math.radians(lat)))
def centred(lat, lon, km): return (lat - dl(km) / 2, lon - dn(lat, km) / 2, lat + dl(km) / 2, lon + dn(lat, km) / 2)
def hav(a, b, c, d):
    p = math.pi / 180; x = math.sin((c - a) * p / 2) ** 2 + math.cos(a * p) * math.cos(c * p) * math.sin((d - b) * p / 2) ** 2
    return 2 * 6371008.8 * math.asin(math.sqrt(x))

def sparql_all(box, page=5000):
    """Items with an English article and a coordinate in box (+ P31 rows). Pages with ORDER BY."""
    s, w, n, e = box; rows = []; ms_l = []; sizes = []; status = []; off = 0; t0 = time.monotonic(); maxp = 0
    while True:
        q = ('SELECT ?item ?title ?cls ?lat ?lon WHERE { SERVICE wikibase:box { ?item wdt:P625 ?loc . bd:serviceParam wikibase:cornerSouthWest "Point(%.5f %.5f)"^^geo:wktLiteral . '
             'bd:serviceParam wikibase:cornerNorthEast "Point(%.5f %.5f)"^^geo:wktLiteral } '
             '?art schema:about ?item ; schema:isPartOf <https://en.wikipedia.org/> ; schema:name ?title . OPTIONAL { ?item wdt:P31 ?cls } '
             'BIND(geof:latitude(?loc) AS ?lat) BIND(geof:longitude(?loc) AS ?lon) } ORDER BY ?item LIMIT %d OFFSET %d') % (w, s, e, n, page, off)
        st, body, ms = send("https://query.wikidata.org/sparql?format=json&query=" + urllib.parse.quote(q), timeout=70, accept="application/sparql-results+json")
        status.append(st); ms_l.append(round(ms)); sizes.append(len(body))
        if st != 200: break
        b = js(body).get("results", {}).get("bindings", []); rows += b; maxp = max(maxp, len(b))
        if len(b) < page: break
        off += page
    return rows, {"calls": len(status), "status": status, "ms": ms_l, "bytes": sum(sizes), "rows": len(rows), "max_rows_one_page": maxp, "wall_s": round(time.monotonic() - t0, 1), "page": page}

def fold(rows, items):
    for r in rows:
        q = r["item"]["value"].rsplit("/", 1)[1]
        it = items.setdefault(q, {"title": r["title"]["value"], "cls": set(), "lat": float(r["lat"]["value"]), "lon": float(r["lon"]["value"])})
        if "cls" in r: it["cls"].add(r["cls"]["value"].rsplit("/", 1)[1])

def labels(ids):
    lab = {}; ids = sorted(ids)
    for i in range(0, len(ids), 50):
        st, body, _ = send("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=" + "%7C".join(ids[i:i + 50]) + "&props=labels&languages=en&format=json")
        for k, v in js(body).get("entities", {}).items(): lab[k] = v.get("labels", {}).get("en", {}).get("value", "")
    return lab

class Rules:
    def __init__(self):
        self.app_cls = set(classes()); self.curated = set(re.findall(r'"(Q\d+)"\s+to\s+PlaceKinds\.', CELL_SRC.split("CURATED_LANDMARK_QIDS: Map")[1].split("fun ")[0])); self.loose_ids = set()
    def app(self, q, it): return not (it["cls"] & PERSON) and (bool(it["cls"] & self.app_cls) or q in self.curated)
    def loose(self, q, it): return not (it["cls"] & PERSON) and (self.app(q, it) or bool(it["cls"] & self.loose_ids))

def stage_trip(out, R):
    items = {}; st = []
    for b in [(S + i * (N - S) / 2, W + j * (E - W) / 2, S + (i + 1) * (N - S) / 2, W + (j + 1) * (E - W) / 2) for i in range(2) for j in range(2)]:
        rows, stats = sparql_all(b); fold(rows, items); st.append(stats)
    out["trip_subboxes"] = st
    b0 = (S, W, S + (N - S) / 2, W + (E - W) / 2)  # paging demo: same box, 200-row pages
    rows_p, stats_p = sparql_all(b0, page=200); out["trip_paging_demo"] = {**stats_p, "rows_single_page": st[0]["rows"], "equal": len(rows_p) == st[0]["rows"]}
    rows_w, stats_w = sparql_all((S, W, N, E)); out["trip_one_box_133x118km"] = stats_w
    rows_2, stats_2 = sparql_all(centred((S + N) / 2, (W + E) / 2, 200)); out["trip_one_box_200km"] = stats_2
    allc = {c for it in items.values() for c in it["cls"]}; lab = labels(allc)
    R.loose_ids = {c for c in allc if LOOSE_RE.search(lab.get(c, ""))}
    out["trip_items_with_article"] = len(items); out["trip_no_P31"] = sum(1 for it in items.values() if not it["cls"])
    out["trip_pass_app"] = sum(R.app(q, it) for q, it in items.items()); out["trip_pass_loose"] = sum(R.loose(q, it) for q, it in items.items())
    out["trip_item_count_200km_box"] = len({r["item"]["value"] for r in rows_2}); out["trip_item_count_one_box"] = len({r["item"]["value"] for r in rows_w})
    added = {q for q, it in items.items() if not R.app(q, it) and R.loose(q, it)}; out["trip_added_by_loose"] = len(added)
    out["trip_added_by_class"] = [(c, lab.get(c), k) for c, k in collections.Counter(c for q in added for c in (items[q]["cls"] & R.loose_ids)).most_common()]
    out["trip_top_classes"] = [(c, lab.get(c), k) for c, k in collections.Counter(c for it in items.values() for c in it["cls"]).most_common(30)]
    qual = sorted(it["title"] for q, it in items.items() if R.loose(q, it)); t0 = time.monotonic(); ms_l = []; chars = []
    for i in range(0, len(qual), 20):
        s_, body, ms = send("https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&exsentences=3&exlimit=20&titles=" + urllib.parse.quote("|".join(qual[i:i + 20])) + "&format=json&formatversion=2")
        ms_l.append(round(ms)); chars += [min(400, len(p.get("extract", ""))) for p in js(body).get("query", {}).get("pages", []) if p.get("extract")]
    out["trip_intro"] = {"titles": len(qual), "calls": len(ms_l), "median_ms": round(statistics.median(ms_l)) if ms_l else None, "mean_chars_capped_400": round(statistics.mean(chars)) if chars else None, "wall_s": round(time.monotonic() - t0, 1)}
    t0 = time.monotonic(); calls = 0; missing = 0; bytitle = {it["title"]: (q, it) for q, it in items.items() if R.loose(q, it)}; bad = set(); titles = sorted(bytitle)
    for i in range(0, len(titles), 50):
        s_, body, ms = send("https://en.wikipedia.org/w/api.php?action=query&prop=coordinates&coprimary=primary&colimit=500&titles=" + urllib.parse.quote("|".join(titles[i:i + 50])) + "&format=json&formatversion=2"); calls += 1
        for p in js(body).get("query", {}).get("pages", []):
            co = p.get("coordinates")
            if not co: missing += 1; continue
            q, it = bytitle.get(p["title"], (None, None))
            if it and hav(co[0]["lat"], co[0]["lon"], it["lat"], it["lon"]) > 1000: bad.add(q)
    out["trip_coord_check"] = {"calls": calls, "titles": len(titles), "disagree_over_1km": len(bad), "no_wikipedia_coordinate": missing, "wall_s": round(time.monotonic() - t0, 1),
                               "disagree_pass_app": sum(1 for q in bad if R.app(q, items[q])), "disagree_pass_loose_only": sum(1 for q in bad if not R.app(q, items[q]))}
    lm = {}
    for name, rx in LANDMARKS.items():
        lm[name] = [{"qid": q, "title": it["title"], "app": R.app(q, it), "loose": R.loose(q, it), "disagree_1km": q in bad, "classes": [lab.get(c, c) for c in sorted(it["cls"])]} for q, it in items.items() if re.search(rx, it["title"], re.I)]
    out["landmarks"] = lm
    pts = collections.Counter((round(it["lat"] * 5) / 5, round(it["lon"] * 5) / 5) for q, it in items.items() if R.app(q, it))
    centers = [p for p, _ in pts.most_common(6)] + [(27.05, 87.95), (28.1, 88.95)]; cmp = []
    for (clat, clon) in centers:
        blk = block_at(clat - 0.09, clon - 0.1)
        st_, hits, ms = geosearch(blk)
        if hits is None: cmp.append({"status": st_}); continue
        ok, c_ = qualifying_titles(hits, sorted(R.app_cls)) if hits else (set(), 0)
        ins = lambda it: blk[0] <= it["lat"] <= blk[2] and blk[1] <= it["lon"] <= blk[3]
        spa = {it["title"] for q, it in items.items() if ins(it) and R.app(q, it)}
        cmp.append({"geosearch_hits": len(hits), "geosearch_pass_app": len(ok), "sparql_articles": sum(1 for it in items.values() if ins(it)), "sparql_pass_app": len(spa), "both": len(ok & spa), "geo_only": len(ok - spa), "sparql_only": len(spa - ok), "calls": 1 + c_})
    out["block_compare"] = cmp
    return items

def stage_probe(out, key, box, R):
    rows, stats = sparql_all(box); its = {}; fold(rows, its)
    stats["items"] = len(its); stats["app"] = sum(R.app(q, it) for q, it in its.items()); stats["loose"] = sum(R.loose(q, it) for q, it in its.items()); out[key] = stats

def check_out(path):
    """The detail file holds names and coordinates: refuse a path inside this repository."""
    root = here.parents[1].resolve(); o = pathlib.Path(path).resolve()
    if o == root or root in o.parents: raise SystemExit("refusing --out inside the repository: write to a scratch directory")

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--out", required=True); a = ap.parse_args(); check_out(a.out)
    R = Rules(); out = {"app_class_ids": len(R.app_cls), "curated_ids": len(R.curated)}; t0 = time.monotonic()
    try:
        stage_trip(out, R)
        stage_probe(out, "dense_100km_metro", centred(28.61, 77.21, 100), R)
        for key, (la, lo, km) in {"arid_a_200km": (27.0, 72.0, 200), "arid_b_200km": (25.0, 75.0, 200), "arid_c_300km": (26.5, 73.5, 300), "arid_d_400km": (26.5, 74.0, 400)}.items():
            stage_probe(out, key, centred(la, lo, km), R)
    except Stop as s: out["stopped"] = str(s)
    out["loose_class_ids"] = len(R.loose_ids); out["requests_total"] = state["n"]; out["wall_s_total"] = round(time.monotonic() - t0); out["http_status_counts"] = rec["http"]
    pathlib.Path(a.out).write_text(json.dumps(out, indent=1, default=list)); print(json.dumps({k: v for k, v in out.items() if k != "landmarks"}, indent=1, default=list))
    for k, v in out.get("landmarks", {}).items(): print("landmark", "found" if v else "NOT FOUND", sum(1 for x in v if x["app"]), "app")  # counts only: names stay in --out

if __name__ == "__main__": main()
