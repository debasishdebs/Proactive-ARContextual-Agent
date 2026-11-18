#!/usr/bin/env python3
"""Offline tests of tools/eval_proactive.py (metrics, bars, wording scan, file parsing). No phone needed: python3 tools/test_eval_proactive.py"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import eval_proactive as ev


def card(key, at=1, text="Golconda: last visit 12 Nov."):
    return {"atMs": at, "trigger": "tick", "gate": "WOULD_SHOW", "candidates": [{"key": key, "family": "f2", "score": 0.7, "verdict": "WOULD_SHOW", "sourceIds": ["mem:m"], "text": text}]}


def day(cards, labels=None, expect=None, steps_run=96, elapsed=100):
    return {"spec": {"day": "2026-11-18", "stepMin": 15, "events": [], "expect": expect or [], "labels": labels or {}}, "resp": {"steps": cards, "stepsRun": steps_run, "elapsedMs": elapsed}}


class Metrics(unittest.TestCase):
    def test_pass(self):
        m = ev.score([day([card("a")], {"a": "useful"}, ["a"])])
        self.assertTrue(all(m["pass"].values()), m)

    def test_useful_rate_and_recall_bars(self):
        m = ev.score([day([card("a"), card("b")], {"a": "useful", "b": "not_useful"}, ["a", "z"])])
        self.assertEqual(0.5, m["useful_rate"]); self.assertEqual(0.5, m["recall"])
        self.assertFalse(m["pass"]["useful_rate"]); self.assertFalse(m["pass"]["recall"])

    def test_one_harmful_or_wrong_card_fails(self):
        for v in ("harmful", "wrong"):
            m = ev.score([day([card("a")], {"a": v})])
            self.assertEqual(1, m["harmful"]); self.assertFalse(m["pass"]["harmful"])

    def test_false_claim_wording_is_harmful_even_without_a_label(self):
        m = ev.score([day([card("a", text="The stove is off and you left it safe.")], {"a": "useful"})])
        self.assertEqual(1, m["false_claim_wording"]); self.assertFalse(m["pass"]["harmful"])

    def test_cap_per_day(self):
        m = ev.score([day([card("k%d" % i, i) for i in range(4)], {"k%d" % i: "useful" for i in range(4)})])
        self.assertEqual(4, m["max_shown_per_day"]); self.assertFalse(m["pass"]["per_day_cap"])

    def test_step_time(self):
        m = ev.score([day([card("a")], {"a": "useful"}, steps_run=10, elapsed=5000)])
        self.assertEqual(500.0, m["p95_step_ms"]); self.assertFalse(m["pass"]["p95_step_ms"])

    def test_other_verdicts_are_not_cards(self):
        r = day([{"atMs": 1, "trigger": "tick", "gate": "SHOWN_GAP", "candidates": [{"key": "a", "verdict": "SHOWN_GAP", "text": None}]}])["resp"]
        self.assertEqual([], ev.would_show(r))

    def test_report_names_the_result(self):
        self.assertIn("RESULT: PASS", ev.report(ev.score([day([card("a")], {"a": "useful"})])))


class Delivery(unittest.TestCase):
    def test_counters_are_reported_but_never_a_bar(self):
        d = day([card("a")], {"a": "useful"}, ["a"])
        d["resp"]["delivery"] = {"delivered": {"phone": 2, "hud": 1}, "dropped": {"wear": 3}, "feedback": {"dismiss": 1}}
        m = ev.score([d])
        self.assertEqual({"phone": 2, "hud": 1}, m["delivery"]["delivered"]); self.assertTrue(all(m["pass"].values()), m)
        text = ev.report(m)
        self.assertIn("delivered hud=1 phone=2 | dropped wear=3 | feedback dismiss=1", text); self.assertIn("RESULT: PASS", text)

    def test_an_older_phone_without_the_field_reports_none(self):
        m = ev.score([day([card("a")], {"a": "useful"})])
        self.assertEqual({"delivered": {}, "dropped": {}, "feedback": {}}, m["delivery"]); self.assertIn("delivered none | dropped none | feedback none", ev.report(m))


class Files(unittest.TestCase):
    def test_load_day_and_bad_verdict(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "x.jsonl")
            open(p, "w").write('# c\n{"kind":"day","day":"2026-11-18"}\n{"kind":"expect","key":"f2:a"}\n{"kind":"label","key":"f2:a","verdict":"useful"}\n')
            s = ev.load_day(p)
            self.assertEqual(("2026-11-18", 15, ["f2:a"], {"f2:a": "useful"}), (s["day"], s["stepMin"], s["expect"], s["labels"]))
            open(p, "w").write('{"kind":"day","day":"2026-11-18"}\n{"kind":"label","key":"k","verdict":"great"}\n')
            with self.assertRaises(SystemExit):
                ev.load_day(p)

    def test_the_checked_in_days_parse(self):
        folder = ev.DEFAULT_DAYS
        names = sorted(n for n in os.listdir(folder) if n.endswith(".jsonl"))
        self.assertEqual(3, len(names))
        for n in names:
            ev.load_day(os.path.join(folder, n))

    def test_the_tool_only_sends_the_replay_route(self):
        self.assertEqual("/api/proactive/replay", ev.ROUTE)


if __name__ == "__main__":
    unittest.main()
