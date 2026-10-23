import math
import pytest
import synth
from trace_lab.decode import load_trace
from trace_lab.features import FEATURE_NAMES, per_second_features


def test_per_second_features(tmp_path):
    rows = per_second_features(load_trace(synth.build(tmp_path / "t")))
    assert FEATURE_NAMES == ("gyroMax", "gyroMean", "gyroRms", "aboveOnFrac", "accVar", "stepHz", "rotRad")
    assert [r["second"] for r in rows] == [0, 1, 2, 3]
    assert [r["tStartMs"] for r in rows] == [0, 1000, 2000, 3000]
    r0, r1, r2, r3 = rows
    # second 0: ten gyro samples of magnitude 1.0; first sample has dt 0, the other nine 0.1 s
    assert r0["n"] == 10 and r0["gyroMax"] == 1.0 and r0["gyroMean"] == 1.0 and r0["gyroRms"] == 1.0 and r0["aboveOnFrac"] == 1.0
    assert r0["rotRad"] == pytest.approx(0.9) and r0["accVar"] == 0.0 and r0["stepHz"] == 0.0
    # second 1: magnitudes 0, .75 alternating; sample at tMs 1000 belongs to second 1 (not 0)
    assert r1["n"] == 10 and r1["gyroMax"] == 0.75 and r1["gyroMean"] == pytest.approx(0.375)
    assert r1["gyroRms"] == pytest.approx(math.sqrt(0.5625 / 2)) and r1["aboveOnFrac"] == 0.5
    assert r1["rotRad"] == pytest.approx(0.375) and r1["accVar"] == pytest.approx(1.0) and r1["stepHz"] == pytest.approx(5.0)
    # second 2: no gyro, three accel samples (< 8): zeros like the app's empty second
    assert r2["n"] == 0 and r2["gyroMax"] == 0.0 and r2["gyroRms"] == 0.0 and r2["accVar"] == 0.0 and r2["stepHz"] == 0.0
    # second 3: one sample after a 1.6 s gap, dt capped at maxDtMs = 100 ms
    assert r3["n"] == 1 and r3["rotRad"] == pytest.approx(0.1) and r3["gyroMax"] == 1.0


def test_features_without_sensor_events_is_empty(tmp_path):
    t = load_trace(synth.build(tmp_path / "t"))
    for tag in ("g", "a"):
        for k in t.arrays[tag]:
            t.arrays[tag][k] = []
    assert per_second_features(t) == []
