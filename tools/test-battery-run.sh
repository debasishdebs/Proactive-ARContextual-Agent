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
expect "camOpenMs1m_mean=6000.0 analysed1m_mean=20.0 kf1m_mean=2.0 upBytes1m_mean=2000.0 batteryC_max=33.0 opens1m_mean=6.0 glances1m_mean=4.0 capped1m_mean=1.0 same1m_mean=1.0 novelSkip1m_mean=2.0"
expect "wake=MOTION:3"
expect "tier=T0:2,T1:1"
expect "reason=parked:1,sampling:2"
expect "sensors=full:2,low:1"
expect "cpu_s_per_min app=3.00 ss=12.00 cam=4.50 camprov=1.20 sens=0.30 bt=0.30 audio=0.00 assist=6.00"
expect "ss_threads=binder:1_2:1.20,SensorService:0.30"
mout="$("$HERE/battery-run.sh" summary "$HERE/testdata/battery-run" minutes)" || { echo "FAIL: minutes exited non-zero"; fail=1; }
echo "$mout"
mexpect() {
  case "$mout" in
    *"$1"*) ;;
    *) echo "FAIL: minutes expected '$1'"; fail=1 ;;
  esac
}
mexpect "10:05 opens1m=12 kf1m=1 glances1m=6 app=na ss=na cam=na camprov=na"
mexpect "10:10 opens1m=6 kf1m=2 glances1m=4 app=3.00 ss=12.00 cam=4.50 camprov=1.20"
# Missing and empty cpu.log must still print the heartbeat table (cpu columns na).
for variant in missing empty; do
  tmpd="$(mktemp -d)"; cp "$HERE"/testdata/battery-run/* "$tmpd"/
  if [ "$variant" = missing ]; then rm -f "$tmpd/cpu.log"; else : > "$tmpd/cpu.log"; fi
  vout="$("$HERE/battery-run.sh" summary "$tmpd" minutes)"; rm -rf "$tmpd"
  case "$vout" in *"no cpu.log"*) ;; *) echo "FAIL: $variant cpu.log: no 'no cpu.log' note"; fail=1 ;; esac
  case "$vout" in *"10:10 opens1m=6 kf1m=2 glances1m=4 app=na ss=na cam=na camprov=na"*) ;; *) echo "FAIL: $variant cpu.log: heartbeat table missing"; fail=1 ;; esac
done
# Without cpu.log and without diag files the minutes mode must never read stdin (awk with no file arguments does).
tmpd="$(mktemp -d)"; cp "$HERE"/testdata/battery-run/{batt.log,batt_run.txt} "$tmpd"/
SECONDS=0
"$HERE/battery-run.sh" summary "$tmpd" minutes >/dev/null < <(sleep 3)
[ "$SECONDS" -lt 3 ] || { echo "FAIL: minutes with no input files waited on stdin (${SECONDS}s)"; fail=1; }
rm -rf "$tmpd"
# A directory path with a space must work (paths are quoted).
tmpd="$(mktemp -d)/with space"; mkdir -p "$tmpd"; cp "$HERE"/testdata/battery-run/* "$tmpd"/
sout="$("$HERE/battery-run.sh" summary "$tmpd" minutes)"; rm -rf "$(dirname "$tmpd")"
case "$sout" in *"10:10 opens1m=6 kf1m=2 glances1m=4 app=3.00 ss=12.00"*) ;; *) echo "FAIL: a path with a space breaks minutes"; fail=1 ;; esac
# A CPU log that crosses midnight: 1000 -> 1600 ticks over 4 min (23:58 -> 00:02) is 6 s / 4 min = 1.50 s/min.
tmpd="$(mktemp -d)"
printf 'label=mid\nstart=10-21_23:50:00\nend=10-22_00:10:00\nclk_tck=100\n' > "$tmpd/batt_run.txt"
printf '10-21_23:50:00 level: 90 temperature: 300 USB powered: false AC powered: false \n10-22_00:10:00 level: 80 temperature: 300 USB powered: false AC powered: false \n' > "$tmpd/batt.log"
printf '10-21_23:58:00 cpu app pid=1 ticks=1000\n10-22_00:02:00 cpu app pid=1 ticks=1600\n' > "$tmpd/cpu.log"
mid="$("$HERE/battery-run.sh" summary "$tmpd")"; rm -rf "$tmpd"
case "$mid" in *"cpu_s_per_min app=1.50 "*) ;; *) echo "FAIL: midnight crossing: $mid"; fail=1 ;; esac
# The CPU logger script that runs on the device must stay count-only too.
if ! sh -n <("$HERE/battery-run.sh" print-cpu-logger); then echo "FAIL: cpu logger has a syntax error"; fail=1; fi
if "$HERE/battery-run.sh" print-cpu-logger | grep -q -i -E "logcat|dumpsys"; then echo "FAIL: cpu logger reads logcat/dumpsys"; fail=1; fi
if "$HERE/battery-run.sh" print-cpu-logger | grep -q awk; then echo "FAIL: cpu logger uses awk on device"; fail=1; fi
if grep -i -E "logcat" "$HERE/battery-run.sh" >/dev/null; then echo "FAIL: logcat"; fail=1; fi
if grep -q logcat "$HERE/battery-run.sh"; then echo "FAIL: battery-run.sh mentions logcat"; fail=1; fi
if ! grep -q "AssistServer" "$HERE/battery-run.sh"; then echo "FAIL: battery-run.sh deny-list lacks AssistServer"; fail=1; fi
[ $fail -eq 0 ] && echo "PASS"
exit $fail
