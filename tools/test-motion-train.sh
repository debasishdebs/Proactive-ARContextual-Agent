#!/usr/bin/env bash
# Test for tools/motion-train/train.py on the synthetic sample. Run from the repo root.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
if ! python3 -c 'import sklearn' >/dev/null 2>&1; then echo "skip: no sklearn"; exit 0; fi
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail=0
out="$(python3 "$HERE/motion-train/train.py" "$HERE/motion-train/testdata/sample.csv" --out "$tmp/TrainedMotionModel.kt")" || { echo "FAIL: train.py exited non-zero"; exit 1; }
echo "$out"
grep -q "TreeMotionModel(" "$tmp/TrainedMotionModel.kt" || { echo "FAIL: no TreeMotionModel( in output"; fail=1; }
grep -q "intArrayOf" "$tmp/TrainedMotionModel.kt" || { echo "FAIL: no arrays"; fail=1; }
case "$out" in *"leave-one-out accuracy="*) ;; *) echo "FAIL: no accuracy line"; fail=1 ;; esac
[ $fail -eq 0 ] && echo "PASS"
exit $fail
