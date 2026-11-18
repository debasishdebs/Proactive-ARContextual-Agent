#!/usr/bin/env bash
# Semver-ordered tag helper (fails closed).
#   prev-tag.sh cmp <tagA> <tagB>      prints -1, 0 or 1; non-zero exit if either tag is invalid
#   prev-tag.sh prev <tag> < tag-list  prints the greatest listed tag strictly below <tag> (empty if none);
#                                      non-zero exit if <tag> is invalid. Invalid lines in the list are ignored.
# Order: MAJOR.MINOR.PATCH numeric; a prerelease sorts BELOW its release; prerelease identifiers are compared
# per semver (dot-separated; numeric ones numerically; alphanumerics lexically; numeric < alphanumeric; a shorter
# identifier list sorts below a longer one with an equal prefix).
set -euo pipefail
py=$(cat <<'PY'
import re, sys
RE = re.compile(r'^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([A-Za-z0-9]+(?:\.[A-Za-z0-9]+)*))?$')
def parse(t):
    m = RE.match(t)
    if not m: raise ValueError("invalid tag: %r" % t)
    core = tuple(int(m.group(i)) for i in (1, 2, 3))
    pre = m.group(4)
    if pre is None: return (core, 1, ())
    ids = []
    for p in pre.split('.'):
        if p.isdigit():
            if len(p) > 1 and p[0] == '0': raise ValueError("invalid tag (leading zero): %r" % t)
            ids.append((0, int(p), ''))
        else: ids.append((1, 0, p))
    return (core, 0, tuple(ids))
def main(a):
    if len(a) == 3 and a[0] == 'cmp':
        x, y = parse(a[1]), parse(a[2]); print((x > y) - (x < y)); return 0
    if len(a) == 2 and a[0] == 'prev':
        cur = parse(a[1]); best = None
        for line in sys.stdin:
            t = line.strip()
            if not t or t == a[1]: continue
            try: k = parse(t)
            except ValueError: continue
            if k < cur and (best is None or k > best[0]): best = (k, t)
        if best: print(best[1])
        return 0
    sys.stderr.write("usage: prev-tag.sh cmp A B | prev TAG < list\n"); return 2
try: sys.exit(main(sys.argv[1:]))
except ValueError as e:
    sys.stderr.write("prev-tag: %s\n" % e); sys.exit(1)
PY
)
exec python3 -c "$py" "$@"
