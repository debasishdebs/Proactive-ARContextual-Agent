#!/usr/bin/env python3
"""Parse an emitted TrainedMotionModel.kt and assert the rules TreeMotionModel.valid enforces."""
import math
import re
import sys

src = open(sys.argv[1]).read()
m = re.search(r'TreeMotionModel\("([^"]+)",\s*intArrayOf\(([^)]*)\),\s*floatArrayOf\(([^)]*)\),\s*intArrayOf\(([^)]*)\),\s*intArrayOf\(([^)]*)\),\s*intArrayOf\(([^)]*)\)\)', src)
assert m, "TreeMotionModel(...) call not parsed"
nums = lambda s, f: [f(x.strip().rstrip("f")) for x in s.split(",") if x.strip()]
feature, thr, left, right, leaf = (nums(m.group(2), int), nums(m.group(3), float), nums(m.group(4), int), nums(m.group(5), int), nums(m.group(6), int))
n = len(feature)
assert n > 0 and all(len(a) == n for a in (thr, left, right, leaf)), "array sizes differ"
for i in range(n):
    if left[i] == -1:
        assert right[i] == -1 and leaf[i] in (0, 1, 2), f"bad leaf {i}"
    else:
        assert 0 <= feature[i] <= 6 and math.isfinite(thr[i]), f"bad split {i}"
        assert i < left[i] < n and i < right[i] < n, f"children not strictly forward at {i}"
print(f"kotlin-valid nodes={n}")
