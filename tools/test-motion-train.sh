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
# Round trip: every rule the Kotlin loader (TreeMotionModel.valid) enforces holds for the emitted file.
python3 "$HERE/motion-train/check_kotlin.py" "$tmp/TrainedMotionModel.kt" || { echo "FAIL: emitted Kotlin violates loader rules"; fail=1; }
python3 "$HERE/motion-train/check_kotlin.py" "$HERE/testdata/motion-train/TrainedMotionModel.kt" || { echo "FAIL: checked-in sample tree invalid"; fail=1; }
# Scale: 50k synthetic rows must validate in seconds (no quadratic fold split).
python3 - "$tmp/big.csv" <<'PY'
import random, sys
random.seed(1)
h = "t_ms,gyro_max,gyro_mean,gyro_rms,above_on_frac,acc_var,step_hz,rot_rad,glances,micro,state,model,fused,label,rate"
lab = [("SITTING", 0.1), ("CHORES", 1.5), ("WALKING", 0.4)]
with open(sys.argv[1], "w") as f:
    f.write(h + "\n")
    t = 0
    for i in range(50000):
        l, g = lab[(i // 500) % 3]
        t += 1000
        wk = l == "WALKING"
        f.write(f"{t},{g*random.uniform(.5,1.5):.3f},{g/2:.3f},{g/2:.3f},{0.6 if l=='CHORES' else 0.0:.3f},{0.4 if wk else 0.02:.3f},{1.8 if wk else 0.0:.3f},{0.5 if l=='CHORES' else 0.05:.3f},0,0,STILL,h,STILL,{l},1\n")
PY
start=$SECONDS
python3 "$HERE/motion-train/train.py" "$tmp/big.csv" --out "$tmp/Big.kt" >/dev/null || { echo "FAIL: 50k-row train failed"; fail=1; }
[ $((SECONDS - start)) -le 30 ] || { echo "FAIL: 50k rows took $((SECONDS - start)) s"; fail=1; }
[ $fail -eq 0 ] && echo "PASS"
exit $fail
