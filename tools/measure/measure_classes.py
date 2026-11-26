#!/usr/bin/env python3
"""Mac-only class survey (docs/superpowers/plans/2026-11-27-large-area-seeding-design.md, 'Class survey Rajasthan 27 Nov'). NOT shipped.
Reuses measure_region.py (SPARQL box + paging + politeness: serial, 1 request / 2 s, app User-Agent, Retry-After, stop on two 429s, hard cap 120 requests).
For two regions (Rajasthan sample boxes, Sikkim/Darjeeling trip box) tallies P31 classes of items with an en-Wikipedia article and a coordinate,
splits strict / strict+loose / owner additions, and counts classes among the NOT admitted items. Full detail incl. names goes to --out (keep OUT of the repo); stdout is aggregates only.
Usage: measure_classes.py --out /path/classes.json
"""
import argparse, collections, json, pathlib, re, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import measure_region as mr
mr.ns["CAP"] = 120
CELL = mr.CELL_SRC
mono = CELL.split("val MONUMENT_CLASSES")[1].split("val MOUNTAIN_CLASSES")[0]
STRICT_IDS = set(re.findall(r'"(Q\d+)"\s+to\s+PlaceKinds\.', mono))
MOUNT_IDS = set(re.findall(r'"(Q\d+)"\s+to\s+PlaceKinds\.', CELL.split("val MOUNTAIN_CLASSES")[1].split("Large areas")[0]))
# planned (design doc section 10, TODO-verify ids) + owner 27 Nov additions: matched by English label because ids are not in the repo
LOOSE_LABELS = {"proglacial lake", "wildlife sanctuary", "bird sanctuary", "sanctuary", "mountain", "summit", "sacred mountain"}
OWNER_LABELS = {"mountain", "tea garden", "stream", "glacier", "hill", "railway station", "bus stop", "hydroelectric power station", "run-of-river hydroelectric power station", "stadium", "mountain ridge", "mountain range", "mountain railway"}
RAJ = {"Jaipur-Ajmer-Pushkar": (26.6, 75.2, 250), "Udaipur-MountAbu-Chittorgarh": (24.8, 73.4, 250), "Jodhpur": (26.3, 73.0, 250), "Jaisalmer": (26.9, 70.9, 250),
       "Bikaner": (28.0, 73.3, 250), "Ranthambore-Sawai-Madhopur": (26.0, 76.6, 250), "Desert-empty-NW": (27.9, 71.0, 250), "Shekhawati-north": (28.4, 75.3, 220)}

def survey(boxes, tag, out):
    items = {}; per = {}
    for name, b in boxes.items():
        rows, st = mr.sparql_all(b); before = len(items); mr.fold(rows, items); per[name] = {"status": st["status"], "calls": st["calls"], "rows": st["rows"], "ms": st["ms"]}
    out[tag + "_boxes"] = per; return items

def analyse(items, tag, out, lab_cache):
    allc = {c for it in items.values() for c in it["cls"]}; need = [c for c in allc if c not in lab_cache]
    lab_cache.update(mr.labels(need)); lab = lab_cache
    strict_all = STRICT_IDS; names_ok = lambda c, S: lab.get(c, "").lower() in S
    loose_ids = {c for c in allc if names_ok(c, LOOSE_LABELS)} | MOUNT_IDS
    owner_ids = {c for c in allc if names_ok(c, OWNER_LABELS)}
    curated = mr.Rules().curated; person = mr.PERSON
    def tier(q, it):
        if it["cls"] & person: return "person"
        if it["cls"] & strict_all or q in curated: return "strict"
        if it["cls"] & loose_ids: return "loose"
        if it["cls"] & owner_ids: return "owner"
        return "none"
    t = {q: tier(q, it) for q, it in items.items()}; c = collections.Counter(t.values())
    rest = collections.Counter(); nop31 = 0
    for q, it in items.items():
        if t[q] in ("none",):
            if not it["cls"]: nop31 += 1
            for k in it["cls"]: rest[k] += 1
    out[tag] = {"items": len(items), "no_P31": sum(1 for it in items.values() if not it["cls"]), "persons": c["person"], "strict": c["strict"], "strict_plus_loose": c["strict"] + c["loose"],
                "strict_loose_owner": c["strict"] + c["loose"] + c["owner"], "not_admitted": c["none"], "not_admitted_no_P31": nop31, "loose_extra": c["loose"], "owner_extra": c["owner"],
                "distinct_classes": len(allc), "owner_ids_found": sorted((k, lab.get(k)) for k in owner_ids), "loose_ids_found": sorted((k, lab.get(k)) for k in loose_ids)}
    out[tag + "_rest_top100"] = [(k, lab.get(k, ""), n) for k, n in rest.most_common(100)]
    out[tag + "_admitted_class_counts"] = [(k, lab.get(k, ""), n) for k, n in collections.Counter(k for q, it in items.items() if t[q] != "none" for k in it["cls"]).most_common(60)]

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--out", required=True); a = ap.parse_args()
    out = {"strict_class_ids": len(STRICT_IDS), "mount_ids": len(MOUNT_IDS)}; lab = {}
    try:
        for tag, boxes in (("raj", {k: mr.centred(*v) for k, v in RAJ.items()}), ("sikkim", {"trip": (27.0, 87.9, 28.2, 89.1)})):
            items = survey(boxes, tag, out); out[tag + "_items_raw"] = {q: {**it, "cls": sorted(it["cls"])} for q, it in items.items()}
            analyse(items, tag, out, lab)
    except mr.Stop as s: out["stopped"] = str(s)
    out["requests_total"] = mr.state["n"]; out["http"] = mr.rec["http"]
    pathlib.Path(a.out).write_text(json.dumps(out, indent=1, default=list))
    print(json.dumps({k: v for k, v in out.items() if not k.endswith("_raw")}, indent=1, default=list))
if __name__ == "__main__": main()
