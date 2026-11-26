#!/usr/bin/env python3
"""Mac-only India-wide P31 class survey by SPARQL AGGREGATION (docs/superpowers/plans/2026-11-27-large-area-seeding-design.md, 'Class survey India 27 Nov'). NOT shipped.
No place rows are downloaded: per grid cell one GROUP BY ?cls COUNT(DISTINCT ?item) query over items with an en-Wikipedia article, a coordinate and P17 = India (Q668);
a cell that fails (timeout / 5xx) is split in four. Counts are merged client-side; class labels come from wbgetentities (batches of 50). Class ids and counts only.
Resumable: state is checkpointed to --state after every request; run repeatedly with --resume --max-req 50 until it prints DONE.
Politeness: reuses measure_region helpers (serial, 1 request / 2 s, app User-Agent, Retry-After, stop on two 429 in a row), total cap 250 across runs, stop after 4 failures in a row.
Usage: measure_india_classes.py --state /path/india_state.json [--resume] [--max-req 50]
"""
import argparse, json, pathlib, sys, time, urllib.parse
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import measure_region as mr
send, js = mr.send, mr.js
TOTAL_CAP = 250

def box_clause(s, w, n, e):
    return ('SERVICE wikibase:box { ?item wdt:P625 ?loc . bd:serviceParam wikibase:cornerSouthWest "Point(%.4f %.4f)"^^geo:wktLiteral . bd:serviceParam wikibase:cornerNorthEast "Point(%.4f %.4f)"^^geo:wktLiteral } '
            '?item wdt:P17 wd:Q668 . ?art schema:about ?item ; schema:isPartOf <https://en.wikipedia.org/> . ') % (w, s, e, n)
def q_classes(b): return "SELECT ?cls (COUNT(DISTINCT ?item) AS ?n) WHERE { " + box_clause(*b) + "OPTIONAL { ?item wdt:P31 ?cls } } GROUP BY ?cls"
def q_total(b): return "SELECT (COUNT(DISTINCT ?item) AS ?n) WHERE { " + box_clause(*b) + "}"

def check_path(path):
    """The state file is a scratch artefact: refuse a path inside this repository."""
    root = pathlib.Path(__file__).resolve().parents[2]; o = pathlib.Path(path).resolve()
    if o == root or root in o.parents: raise SystemExit("refusing --state inside the repository: write to a scratch directory")

def save(st, path): p = pathlib.Path(path); tmp = p.with_suffix(".tmp"); tmp.write_text(json.dumps(st)); tmp.replace(p)

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--state", required=True); ap.add_argument("--resume", action="store_true"); ap.add_argument("--max-req", type=int, default=50); a = ap.parse_args(); check_path(a.state)
    if a.resume and pathlib.Path(a.state).exists(): st = json.loads(pathlib.Path(a.state).read_text())
    else:
        st = {"todo": [[float(s), float(w), float(s + 4), float(w + 4), 0] for s in range(6, 38, 4) for w in range(68, 98, 4)], "acc": {}, "nonempty": [], "totals_todo": None, "total_items": 0,
              "labels": {}, "labels_todo": None, "requests": 0, "fails": 0, "splits": 0, "cells_ok": 0, "gave_up": 0, "ms": [], "rows_cells": [], "http": {}, "events": [], "wall_s": 0, "done": False}
    mr.ns["CAP"] = min(a.max_req, TOTAL_CAP - st["requests"]); t0 = time.monotonic(); n0 = mr.state["n"]
    def sync():
        st["requests"] += mr.state["n"] - n0 - sync.done; sync.done = mr.state["n"] - n0; st["http"] = {k: st["http"].get(k, 0) for k in st["http"]}
    sync.done = 0
    def query(q):
        status, body, ms = send("https://query.wikidata.org/sparql?format=json&query=" + urllib.parse.quote(q), timeout=75, accept="application/sparql-results+json")
        sync(); st["http"][str(status)] = st["http"].get(str(status), 0) + 1
        if status == 200: st["fails"] = 0; return js(body)["results"]["bindings"], ms
        st["fails"] += 1; st["events"].append("status %s" % status)
        if st["fails"] >= 4: raise mr.Stop("4 failures in a row (last status %s)" % status)
        return None, ms
    try:
        while st["todo"]:
            b = st["todo"][0]; rows, ms = query(q_classes(b[:4]))
            if rows is None:
                st["todo"].pop(0)
                if b[4] >= 3: st["gave_up"] += 1
                else:
                    s, w, n, e = b[:4]; m1, m2 = (s + n) / 2, (w + e) / 2; st["splits"] += 1
                    st["todo"] = [[s, w, m1, m2, b[4] + 1], [s, m2, m1, e, b[4] + 1], [m1, w, n, m2, b[4] + 1], [m1, m2, n, e, b[4] + 1]] + st["todo"]
            else:
                st["todo"].pop(0); st["cells_ok"] += 1; st["ms"].append(round(ms)); tot = 0
                for r in rows:
                    k = r["cls"]["value"].rsplit("/", 1)[1] if "cls" in r else "(none)"; c = int(r["n"]["value"]); st["acc"][k] = st["acc"].get(k, 0) + c; tot += c
                st["rows_cells"].append(tot)
                if tot: st["nonempty"].append(b[:4])
            save(st, a.state)
        if st["totals_todo"] is None: st["totals_todo"] = [list(b) for b in st["nonempty"]]
        while st["totals_todo"]:
            r, ms = query(q_total(st["totals_todo"][0]))
            if r is not None: st["total_items"] += int(r[0]["n"]["value"]); st["totals_todo"].pop(0)
            save(st, a.state)
        if st["labels_todo"] is None: st["labels_todo"] = sorted(k for k, n in st["acc"].items() if n >= 5 and k != "(none)")
        while st["labels_todo"]:
            batch = st["labels_todo"][:50]
            s_, body, _ = send("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=" + "%7C".join(batch) + "&props=labels&languages=en&format=json"); sync()
            st["http"][str(s_)] = st["http"].get(str(s_), 0) + 1
            if s_ != 200: st["fails"] += 1; st["events"].append("labels status %s" % s_); raise mr.Stop("label call status %s" % s_)
            for k, v in js(body).get("entities", {}).items(): st["labels"][k] = v.get("labels", {}).get("en", {}).get("value", "")
            st["labels_todo"] = st["labels_todo"][50:]; save(st, a.state)
        st["done"] = True
    except mr.Stop as s_:
        st["events"].append("stop: %s" % s_)
    sync(); st["wall_s"] += round(time.monotonic() - t0); save(st, a.state)
    print(json.dumps({"done": st["done"], "requests": st["requests"], "todo_cells": len(st["todo"]), "cells_ok": st["cells_ok"], "splits": st["splits"], "gave_up": st["gave_up"],
                      "totals_todo": None if st["totals_todo"] is None else len(st["totals_todo"]), "labels_todo": None if st["labels_todo"] is None else len(st["labels_todo"]),
                      "http": st["http"], "events": st["events"][-5:], "wall_s": st["wall_s"]}))
    print("DONE" if st["done"] else "MORE")
if __name__ == "__main__": main()
