#!/usr/bin/env python3
"""Measures seconds per 5 km cell for the place-pack seed, from a laptop. NOT shipped in the app.

Issues the same requests as PlacePack.seedArea -> PlaceCellSource.fetchCell, one cell after another:
  Wikipedia geosearch (gsbbox, limit 500), Wikipedia extracts (batches of 20), Wikidata wbgetentities (batches of 50),
  Overpass (one POST, first mirror that answers), with the app's User-Agent and its 400 ms minimum gap per host.
Prints only counts and times (no coordinates). Usage: measure_seed_cells.py [--cells 25] [--box S,W,N,E]
The default box is a public tourist area chosen only because it is not the owner's.
"""
import argparse, urllib.error, gzip, json, statistics, sys, time, urllib.parse, urllib.request

UA = "ProactiveContextAgent/1.1 (https://github.com/; contact: see repository)"  # PlaceHttp.DEFAULT_USER_AGENT
LAT_STEP = 180.0 / 4096
LON_STEP = 360.0 / 8192
MIN_GAP = 0.4
MIRRORS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter", "https://overpass.private.coffee/api/interpreter"]
last = {}
COOLDOWN = 180.0  # OverpassMirrorSource.cooldownMs: after every mirror failed, none is asked again for 3 minutes
down_until = 0.0

def pause_for(host):
    gap = time.monotonic() - last.get(host, 0)
    if gap < MIN_GAP: time.sleep(MIN_GAP - gap)

def send(url, form=None, timeout=25):
    host = urllib.parse.urlparse(url).netloc
    pause_for(host)
    data = urllib.parse.urlencode(form).encode() if form else None
    req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, "Accept-Encoding": "gzip"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read()
            if r.headers.get("Content-Encoding") == "gzip": body = gzip.decompress(body)
            return r.status, body.decode("utf-8", "replace")
    finally:
        last[host] = time.monotonic()

def fmt(x): return ("%.6f" % x).rstrip("0").rstrip(".")

def cell(box, li, lo):
    s = -90 + li * LAT_STEP; w = -180 + lo * LON_STEP
    return s, w, s + LAT_STEP, w + LON_STEP

T0 = [0.0]
CORE = []

def monument_classes():
    # the app keeps a Wikipedia article only when its Wikidata P31 is one of PlaceCellSource.MONUMENT_CLASSES (or the QID is curated): read both tables from the source
    import re, pathlib
    src = (pathlib.Path(__file__).resolve().parents[2] / "android-companion/services/connectors/src/main/kotlin/com/rokid/context/connectors/place/PlaceCellSource.kt").read_text()
    return set(re.findall(r'"(Q\d+)"\s+to\s+PlaceKinds\.', src))

MONUMENTS = monument_classes()

def fetch_cell(b):
    T0[0] = time.monotonic()
    s, w, n, e = b
    reqs = 0; places = 0; notes = []
    st, body = send("https://en.wikipedia.org/w/api.php?action=query&list=geosearch&gsbbox=%s%%7C%s%%7C%s%%7C%s&gslimit=500&format=json&formatversion=2" % (fmt(n), fmt(w), fmt(s), fmt(e))); reqs += 1
    hits = json.loads(body).get("query", {}).get("geosearch", [])
    ids = sorted({h["pageid"] for h in hits})
    qids = []
    for i in range(0, len(ids), 20):
        chunk = "%7C".join(str(x) for x in ids[i:i + 20])
        st, body = send("https://en.wikipedia.org/w/api.php?action=query&prop=extracts%7Cpageprops%7Ccoordinates&exintro=1&explaintext=1&exsentences=3&exlimit=20&ppprop=wikibase_item&colimit=max&coprimary=primary&pageids=" + chunk + "&format=json&formatversion=2"); reqs += 1
        for p in json.loads(body).get("query", {}).get("pages", []):
            q = p.get("pageprops", {}).get("wikibase_item")
            if q: qids.append(q)
    ents = {}
    for i in range(0, len(qids), 50):
        st, body = send("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=" + "%7C".join(qids[i:i + 50]) + "&props=claims&format=json"); reqs += 1
        ents.update(json.loads(body).get("entities", {}))
    bb = "(%s,%s,%s,%s)" % (fmt(s), fmt(w), fmt(n), fmt(e))
    q = '[out:json][timeout:25];(nwr["historic"]["name"]%s;nwr["heritage"]["name"]%s;nwr["tourism"~"^(attraction|museum|viewpoint)$"]["name"]%s;);out tags center bb;' % (bb, bb, bb)
    global down_until
    core = time.monotonic() - T0[0]
    ok = False
    if time.monotonic() < down_until:
        notes.append("overpass cooling down")
    else:
        for m in MIRRORS:
            try:
                st, body = send(m, {"data": q}); reqs += 1; json.loads(body); ok = True; break
            except Exception as ex:
                reqs += 1; notes.append("overpass " + type(ex).__name__)
        if not ok: down_until = time.monotonic() + COOLDOWN
    # a "place" in the app needs a monument class; count pages with a Wikidata item as the upper bound
    def p31(e):
        out = set()
        for c in e.get("claims", {}).get("P31", []):
            v = c.get("mainsnak", {}).get("datavalue", {}).get("value")
            if isinstance(v, dict) and "id" in v: out.add(v["id"])
        return out
    places = sum(1 for q in qids if q in ents and p31(ents[q]) & MONUMENTS)  # approximation of the app's filter (no person check, no hierarchy)
    CORE.append(core)
    return reqs, len(hits), places, ok, notes

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cells", type=int, default=25)
    ap.add_argument("--box", default="55.93,-3.22,55.97,-3.16")  # public tourist area (Edinburgh Old Town); only used to pick cells
    a = ap.parse_args()
    s, w, n, e = [float(x) for x in a.box.split(",")]
    li0 = int((s + 90) // LAT_STEP); lo0 = int((w + 180) // LON_STEP)
    side = int(a.cells ** 0.5 + 0.999)
    todo = [(li0 + i, lo0 + j) for i in range(side) for j in range(side)][:a.cells]
    secs = []; active = []; plc = []; hits = []; fails = 0; reqs = 0; op_fail = 0; retried_cells = 0; codes = {}
    for k, (li, lo) in enumerate(todo, 1):
        t = time.monotonic(); waited = 0.0
        for attempt in range(1, 6):  # PlacePack.MAX_ATTEMPTS: first try plus 4 retry rounds with 5/10/20/40 s waits
            try:
                r, h, p, ok, notes = fetch_cell(cell(None, li, lo))
                reqs += r; hits.append(h); plc.append(p)
                if not ok: op_fail += 1
                if attempt > 1: retried_cells += 1
                break
            except urllib.error.HTTPError as ex:
                codes[ex.code] = codes.get(ex.code, 0) + 1
                if attempt == 5: fails += 1; break
                w = min(5 * 2 ** (attempt - 1), 60); waited += w; time.sleep(w)
            except Exception as ex:
                codes[type(ex).__name__] = codes.get(type(ex).__name__, 0) + 1
                if attempt == 5: fails += 1; break
                w = min(5 * 2 ** (attempt - 1), 60); waited += w; time.sleep(w)
        d = time.monotonic() - t; secs.append(d); active.append(d - waited)
        print("cell %d/%d %.1fs (waited %.0fs)" % (k, len(todo), d, waited), file=sys.stderr)
    q = sorted(secs)
    p90 = q[min(len(q) - 1, int(round(0.9 * (len(q) - 1))))]
    print(json.dumps({"cells": len(todo), "failed_after_5_tries": fails, "cells_needing_retry": retried_cells, "error_codes_seen": codes, "overpass_failed": op_fail, "requests": reqs,
        "sec_per_cell_median": round(statistics.median(secs), 2), "sec_per_cell_p90": round(p90, 2), "sec_per_cell_mean": round(statistics.mean(secs), 2), "sec_per_cell_median_excluding_retry_waits": round(statistics.median(active), 2),
        "wiki_only_sec_per_cell_median": round(statistics.median(CORE), 2) if CORE else None, "wiki_only_sec_per_cell_p90": round(sorted(CORE)[min(len(CORE) - 1, int(round(0.9 * (len(CORE) - 1))))], 2) if CORE else None,
        "geosearch_hits_per_cell_mean": round(statistics.mean(hits), 2) if hits else None,
        "places_kept_per_cell_mean": round(statistics.mean(plc), 2) if plc else None,
        "places_kept_per_cell_median": statistics.median(plc) if plc else None}, indent=1))

main()
