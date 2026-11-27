#!/usr/bin/env python3
"""Place layer report from a pulled dump folder (docs/superpowers/plans/handoffs/dump-api-2026-11-26.md, "Place attempts and TP/FP/FN").
NOT shipped. Stdlib only. Reads <dump folder>/place_attempts.json (tools/pull_dump.py writes it) or a place_attempts.json path; prints COUNTS and RATES per layer, never an id, a name or a position.

Definitions (a row is one place card attempt; it is "shown" when a card went, or was to go, to the glasses; a label is the owner's tap on the row in Settings > Place cards):
  TP = shown and labelled RIGHT                    FP = shown and labelled WRONG or NOT_A_PLACE
  FN = NOT shown and labelled RIGHT (held / no match / possible: a place was there and no card came)
  TN = NOT shown and labelled WRONG or NOT_A_PLACE (nothing shown was correct)
  precision = TP / (TP + FP)   recall = TP / (TP + FN)   false-positive share = FP / shown-and-labelled
Per layer = the `layer` of the row's eval (L1 photo on the phone, L2 online, L3 offline guess, PIN pin card, LOC location-only card, NONE nothing decided). Unlabelled rows are counted, never guessed.
Usage: place_eval_report.py <dump folder | place_attempts.json> [--out summary.json]   (--out inside a git tree is refused)   |   place_eval_report.py --selftest
"""
import argparse, collections, json, os, sys, tempfile

LABELS = ("RIGHT", "WRONG", "NOT_A_PLACE")
NEG = ("WRONG", "NOT_A_PLACE")
LAYERS = ("L1", "L2", "L3", "PIN", "LOC", "NONE", "unknown")
BIN = 50  # Layer 1 score buckets in milli (cosine x 1000)


def inside_git_tree(path):
    p = os.path.abspath(path)
    while True:
        if os.path.exists(os.path.join(p, ".git")):
            return True
        q = os.path.dirname(p)
        if q == p:
            return False
        p = q


def load(src):
    path = os.path.join(src, "place_attempts.json") if os.path.isdir(src) else src
    with open(path, encoding="utf-8") as f:
        items = json.load(f)
    if not isinstance(items, list):
        raise SystemExit("error: place_attempts.json must be a list")
    return [x for x in items if isinstance(x, dict)]


def layer_of(x):
    v = x.get("layer")
    return v if v in LAYERS else "unknown"


def cell():
    return dict(rows=0, shown=0, labelled=0, tp=0, fp=0, fn=0, tn=0)


def rate(a, b):
    return None if b == 0 else round(a / b, 4)


def report(items):
    layers = collections.OrderedDict((l, cell()) for l in LAYERS)
    total = cell()
    labels = collections.Counter()
    fn_held = collections.Counter()          # FN rows by the reason token (or the Layer 1 state when nothing was held)
    bins = collections.defaultdict(lambda: collections.Counter())  # Layer 1 top score bucket -> label counts
    for x in items:
        shown = bool(x.get("shown"))
        lab = x.get("label") if x.get("label") in LABELS else None
        for c in (layers[layer_of(x)], total):
            c["rows"] += 1
            if shown:
                c["shown"] += 1
            if lab:
                c["labelled"] += 1
                if shown:
                    c["tp" if lab == "RIGHT" else "fp"] += 1
                else:
                    c["fn" if lab == "RIGHT" else "tn"] += 1
        if lab:
            labels[lab] += 1
        if lab == "RIGHT" and not shown:
            ev = x.get("eval") if isinstance(x.get("eval"), dict) else {}
            l1 = ev.get("l1") if isinstance(ev.get("l1"), dict) else {}
            fn_held[str(x.get("held") or ("l1_" + str(l1.get("state", "none"))))] += 1
        ev = x.get("eval") if isinstance(x.get("eval"), dict) else {}
        l1 = ev.get("l1") if isinstance(ev.get("l1"), dict) else {}
        top = l1.get("top")
        if lab and isinstance(top, (int, float)):
            bins[(int(top) // BIN) * BIN][lab] += 1
    out = collections.OrderedDict()
    for name, c in list(layers.items()) + [("ALL", total)]:
        if c["rows"] == 0 and name != "ALL":
            continue
        d = dict(c)
        d["precision"] = rate(c["tp"], c["tp"] + c["fp"])
        d["recall"] = rate(c["tp"], c["tp"] + c["fn"])
        d["fp_share"] = rate(c["fp"], c["tp"] + c["fp"])
        out[name] = d
    return {"layers": out, "labels": dict(labels), "fn_by_reason": dict(fn_held),
            "l1_top_score_by_label": {str(k): dict(v) for k, v in sorted(bins.items())}}


def fmt(v):
    return "-" if v is None else ("%.1f%%" % (v * 100))


def show(r, say=print):
    L = r["layers"]
    say("layer   rows shown label    TP    FP    FN    TN  precision  recall  fp-share")
    for name, d in L.items():
        say("%-6s %5d %5d %5d %5d %5d %5d %5d  %9s %7s %9s" % (name, d["rows"], d["shown"], d["labelled"], d["tp"], d["fp"], d["fn"], d["tn"], fmt(d["precision"]), fmt(d["recall"]), fmt(d["fp_share"])))
    lab = r["labels"]
    say("labels: right=%d wrong=%d not_a_place=%d" % (lab.get("RIGHT", 0), lab.get("WRONG", 0), lab.get("NOT_A_PLACE", 0)))
    if r["fn_by_reason"]:
        say("missed (FN) by reason: " + ", ".join("%s=%d" % kv for kv in sorted(r["fn_by_reason"].items())))
    if r["l1_top_score_by_label"]:
        say("Layer 1 top score (milli, bucket start) -> right / wrong+not a place:")
        for k, v in r["l1_top_score_by_label"].items():
            say("  %s: %d / %d" % (k, v.get("RIGHT", 0), v.get("WRONG", 0) + v.get("NOT_A_PLACE", 0)))


def selftest():
    items = [
        # L1: 3 shown (2 right, 1 wrong), 1 held row labelled right (FN)
        {"id": "a", "layer": "L1", "shown": True, "label": "RIGHT", "eval": {"l1": {"state": "accept", "top": 975}}},
        {"id": "b", "layer": "L1", "shown": True, "label": "RIGHT", "eval": {"l1": {"state": "accept", "top": 960}}},
        {"id": "c", "layer": "L1", "shown": True, "label": "WRONG", "eval": {"l1": {"state": "accept", "top": 951}}},
        {"id": "d", "layer": "L1", "shown": True, "label": None, "eval": {}},
        # L2: 2 shown (1 right, 1 not a place), 1 not shown wrong (TN)
        {"id": "e", "layer": "L2", "shown": True, "label": "RIGHT", "eval": {"l1": {"state": "medium", "top": 880}}},
        {"id": "f", "layer": "L2", "shown": True, "label": "NOT_A_PLACE", "eval": {}},
        {"id": "g", "layer": "L2", "shown": False, "label": "WRONG", "held": "HELD_NOT_CONFIRMED", "eval": {}},
        # NONE: held right (FN data saver), no match right (FN, l1 below), unlabelled
        {"id": "h", "layer": "NONE", "shown": False, "label": "RIGHT", "held": "HELD_DATA_SAVER", "eval": {}},
        {"id": "i", "layer": "NONE", "shown": False, "label": "RIGHT", "held": "HELD_NOT_CONFIRMED", "eval": {"l1": {"state": "below", "top": 700}}},
        {"id": "j", "layer": "NONE", "shown": False, "label": None},
        # an older row without eval, an odd label
        {"id": "k", "shown": True, "label": "MAYBE"},
    ]
    r = report(items)
    L = r["layers"]
    ok = True

    def check(c, what):
        nonlocal ok
        if not c:
            ok = False
            print("FAIL: " + what)
    check((L["L1"]["rows"], L["L1"]["shown"], L["L1"]["labelled"], L["L1"]["tp"], L["L1"]["fp"], L["L1"]["fn"], L["L1"]["tn"]) == (4, 4, 3, 2, 1, 0, 0), "L1 counts %s" % L["L1"])
    check(L["L1"]["precision"] == round(2 / 3, 4) and L["L1"]["recall"] == 1.0 and L["L1"]["fp_share"] == round(1 / 3, 4), "L1 rates")
    check((L["L2"]["tp"], L["L2"]["fp"], L["L2"]["fn"], L["L2"]["tn"]) == (1, 1, 0, 1), "L2 counts %s" % L["L2"])
    check((L["NONE"]["rows"], L["NONE"]["fn"], L["NONE"]["tp"]) == (3, 2, 0) and L["NONE"]["recall"] == 0.0 and L["NONE"]["precision"] is None, "NONE counts %s" % L["NONE"])
    check(L["unknown"]["rows"] == 1 and L["unknown"]["labelled"] == 0, "an odd label is unlabelled")
    A = L["ALL"]
    check((A["rows"], A["tp"], A["fp"], A["fn"], A["tn"]) == (11, 3, 2, 2, 1), "ALL counts %s" % A)
    check(A["recall"] == round(3 / 5, 4) and A["precision"] == round(3 / 5, 4), "ALL rates")
    check(r["labels"] == {"RIGHT": 5, "WRONG": 2, "NOT_A_PLACE": 1}, "labels %s" % r["labels"])
    check(r["fn_by_reason"] == {"HELD_DATA_SAVER": 1, "HELD_NOT_CONFIRMED": 1}, "fn reasons %s" % r["fn_by_reason"])
    check(r["l1_top_score_by_label"].get("950") == {"RIGHT": 2, "WRONG": 1} and r["l1_top_score_by_label"].get("700") == {"RIGHT": 1}, "l1 buckets %s" % r["l1_top_score_by_label"])
    # the output carries counts only: no id of the fixture appears
    txt = json.dumps(r)
    check(not any(('"%s"' % i["id"]) in txt for i in items), "no row id in the summary")
    # a folder and a file both load; an --out inside a git tree is refused
    with tempfile.TemporaryDirectory() as d:
        json.dump(items, open(os.path.join(d, "place_attempts.json"), "w"))
        check(len(load(d)) == 11 and len(load(os.path.join(d, "place_attempts.json"))) == 11, "load folder and file")
        check(not inside_git_tree(d), "a temp folder is outside git")
        repo = os.path.join(d, "repo"); os.makedirs(os.path.join(repo, ".git")); os.makedirs(os.path.join(repo, "sub"))
        check(inside_git_tree(os.path.join(repo, "sub", "x.json")), "inside a git tree detected")
        check(main([d, "--out", os.path.join(repo, "sub", "x.json")], say=lambda s: None) == 2, "an out path inside git is refused")
        check(not os.path.exists(os.path.join(repo, "sub", "x.json")), "nothing was written there")
        o = os.path.join(d, "out", "summary.json")
        check(main([d, "--out", o], say=lambda s: None) == 0 and json.load(open(o))["layers"]["ALL"]["tp"] == 3, "summary written outside git")
    print("selftest: ok" if ok else "selftest: FAILED")
    return 0 if ok else 1


def main(argv=None, say=print):
    ap = argparse.ArgumentParser(description="Counts and rates of the place layers from a pulled dump folder (TP / FP / FN per layer).")
    ap.add_argument("src", nargs="?", help="the pulled dump folder (or its place_attempts.json)")
    ap.add_argument("--out", help="also write the summary JSON here (counts only); refused inside a git tree")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args(argv)
    if a.selftest:
        return selftest()
    if not a.src:
        ap.error("a dump folder is required")
    if a.out and inside_git_tree(a.out):
        say("error: refusing to write inside a git tree: choose --out outside any repository")
        return 2
    r = report(load(a.src))
    show(r, say)
    if a.out:
        os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
        with open(a.out, "w", encoding="utf-8") as f:
            json.dump(r, f, indent=2)
        say("summary written")
    return 0


if __name__ == "__main__":
    sys.exit(main())
