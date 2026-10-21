#!/usr/bin/env bash
# Fixture test for tools/battery-run.sh summary (no adb). Run from the repo root.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
fail=0
out="$("$HERE/battery-run.sh" summary "$HERE/testdata/battery-run")" || { echo "FAIL: summary exited non-zero"; exit 1; }
echo "$out"
expect() {
  case "$out" in
    *"$1"*) ;;
    *) echo "FAIL: expected '$1'"; fail=1 ;;
  esac
}
expect "duration_min=20.0 batt_start=90 batt_end=80 pct_per_min=0.50 proj_h=3.3 hb_lines=3 "
expect "camOpenMs1m_mean=6000.0 analysed1m_mean=20.0 kf1m_mean=2.0 upBytes1m_mean=2000.0 batteryC_max=33.0"
expect "wake=MOTION:3"
expect "tier=T0:2,T1:1"
expect "reason=parked:1,sampling:2"
expect "sensors=full:2,low:1"
if grep -q logcat "$HERE/battery-run.sh"; then echo "FAIL: battery-run.sh mentions logcat"; fail=1; fi
[ $fail -eq 0 ] && echo "PASS"
exit $fail
