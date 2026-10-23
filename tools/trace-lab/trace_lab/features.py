"""Per-second IMU features, named and defined like the app's (MotionClassifier.features / SecondFeatures).

A pure-Python approximation of the on-device computation; see README.md "How the features differ from the app" for the exact differences.
"""
import math

FEATURE_NAMES = ("gyroMax", "gyroMean", "gyroRms", "aboveOnFrac", "accVar", "stepHz", "rotRad")

# Defaults of GlanceParams (onsetRadS, maxDtMs) and the classifier's window constants.
ONSET_RAD_S = 0.6
MAX_DT_MS = 100
MIN_ACCEL_SAMPLES = 8
MIN_SPAN_MS = 800
ZERO_BAND = 1e-3


def _accel_window(samples):
    """samples = [(tMs, x, y, z)] of one second. Returns (summed per-axis population variance, step Hz) or (0.0, 0.0) like the app's short window."""
    n = len(samples)
    if n < MIN_ACCEL_SAMPLES or samples[-1][0] - samples[0][0] < MIN_SPAN_MS:
        return 0.0, 0.0
    mean = [sum(s[i] for s in samples) / n for i in (1, 2, 3)]
    var = [sum((s[i + 1] - mean[i]) ** 2 for s in samples) / n for i in range(3)]
    k = max(range(3), key=lambda i: var[i])  # strongest axis (first on ties, like maxByOrNull)
    crossings = 0
    prev_sign = 0
    for s in samples:
        d = s[k + 1] - mean[k]
        sign = 0 if abs(d) < ZERO_BAND else (1 if d > 0 else -1)
        if sign != 0:
            if prev_sign != 0 and sign != prev_sign:
                crossings += 1
            prev_sign = sign
    return sum(var), crossings / 2.0 / ((samples[-1][0] - samples[0][0]) / 1000.0)


def per_second_features(trace, start_ms=None):
    """One row per second from the trace start to the last gyro/accel sample (empty seconds are zero rows, like the app's empty window).

    Bucket = floor((tMs - start) / 1000) on the event time tMs; start = start_ms, else the manifest's startElapsedMs, else the first event.
    """
    a = trace.arrays
    g, ac = a["g"], a["a"]
    if not g["tMs"] and not ac["tMs"]:
        return []
    if start_ms is None:
        start_ms = trace.start_ms
    if start_ms is None:
        start_ms = min(arr["tMs"][0] for arr in a.values() if arr["tMs"])
    last = max((arr["tMs"][-1] for arr in (g, ac) if arr["tMs"]))
    n_rows = max(0, (last - start_ms) // 1000) + 1
    gyro = [[] for _ in range(n_rows)]   # per second: (mag, dt seconds)
    accel = [[] for _ in range(n_rows)]
    prev = None
    for t, x, y, z in zip(g["tMs"], g["x"], g["y"], g["z"]):
        dt = 0 if prev is None else max(0, t - prev)
        prev = t if prev is None else max(prev, t)
        i = (t - start_ms) // 1000
        if 0 <= i < n_rows:
            gyro[i].append((math.sqrt(x * x + y * y + z * z), min(dt, MAX_DT_MS) / 1000.0))
    for t, x, y, z in zip(ac["tMs"], ac["x"], ac["y"], ac["z"]):
        i = (t - start_ms) // 1000
        if 0 <= i < n_rows:
            accel[i].append((t, x, y, z))
    rows = []
    for i in range(n_rows):
        s = gyro[i]
        n = len(s)
        var, hz = _accel_window(accel[i])
        rows.append({
            "second": i, "tStartMs": start_ms + i * 1000, "n": n, "nAccel": len(accel[i]),
            "gyroMax": max((m for m, _ in s), default=0.0),
            "gyroMean": sum(m for m, _ in s) / n if n else 0.0,
            "gyroRms": math.sqrt(sum(m * m for m, _ in s) / n) if n else 0.0,
            "aboveOnFrac": sum(1 for m, _ in s if m > ONSET_RAD_S) / n if n else 0.0,
            "accVar": var, "stepHz": hz,
            "rotRad": sum(m * dt for m, dt in s),
        })
    return rows
