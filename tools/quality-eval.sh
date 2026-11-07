#!/usr/bin/env bash
# Read-only quality evaluation of the phone app (bash 3.2 wrapper). Pair first: tools/m1-run.sh pair <host:port> <code> <fp8>
#   tools/quality-eval.sh all --out report.md [--samples N] [-n 100] [--retrieve-route /api/...]   (see tools/QUALITY-EVAL.md)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 1; }
exec python3 "$HERE/quality-eval.py" "$@"
