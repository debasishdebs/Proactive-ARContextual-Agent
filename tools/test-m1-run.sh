#!/usr/bin/env bash
# Fixture test for tools/m1-run.sh against a local python3 mock (http, no TLS, no adb). Run from anywhere.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; T="$HERE/testdata/m1-run"
W=$(mktemp -d); export TMPDIR="$W" M1_SCHEME=http M1_POLL_S=0 M1_POLLS=5
python3 "$T/mock_server.py" "$W/port" & MOCK=$!
trap 'kill $MOCK 2>/dev/null; rm -rf "$W"' EXIT
for i in 1 2 3 4 5 6 7 8 9 10; do [ -s "$W/port" ] && break; sleep 0.3; done
HP="127.0.0.1:$(cat "$W/port")"; R="$HERE/m1-run.sh"; fail=0; all=""
chk() { case "$all" in *"$1"*) ;; *) echo "FAIL: expected '$1'"; fail=1 ;; esac; }
no() { case "$all" in *"$1"*) echo "FAIL: must not contain '$1'"; fail=1 ;; esac; }
run() { local o; o=$("$R" "$@" 2>&1); local rc=$?; echo "\$ m1-run $1 -> rc=$rc"; echo "$o"; all="$all$o"$'\n'; return $rc; }

run plan >/dev/null 2>&1 && { echo "FAIL: plan before pair should fail"; fail=1; }
"$R" pair "$HP" 000000 >/dev/null 2>&1 && { echo "FAIL: wrong code accepted"; fail=1; }
run pair "$HP" 123456; chk "paired"
stat_mode=$(ls -ln "$W"/m1-run-*/auth.hdr | cut -c1-10); [ "$stat_mode" = "-rw-------" ] || { echo "FAIL: token file mode $stat_mode"; fail=1; }
run plan; chk "configHash=f5782e0d8610 problems=0 warnings=0"; chk "PASS identity plan hash"
run shadow; chk "mode=SHADOW gen=3 sent=True configHash=f5782e0d8610"
run collect start 25; chk "collect active=True untilMs=25000"
run collect stop; chk "collect active=False"
M1_DIAG="$T/diag_ok.log" run status; chk "traces=1 COMPLETE=1"; chk "shadow_hb=2"; chk "planMode=SHADOW"
run replay t-1 --diag "$T/diag_ok.log"; chk "windows_compared=24 inexact_windows_excluded=2 base_pass=24 base_fail=0"
chk "hours_from_full=7.5"; chk "PASS exact parity"; chk "PASS shadow vs counterfactual opens: 102 vs 100, deviation 2.0%"
run replay t-1 --diag "$T/diag_bad.log" && { echo "FAIL: 130 vs 100 should fail the 5% check"; fail=1; }
chk "FAIL shadow vs counterfactual opens: 130 vs 100"
rm -rf "$W/pull"; M1_DIAG="$T/diag_ok.log" run pull "$W/pull"; chk "heartbeat lines saved: 3"
grep -q SHOULDNOTAPPEAR "$W/pull/heartbeats.log" "$T/diag_ok.log" >/dev/null && grep -q SHOULDNOTAPPEAR "$W/pull/heartbeats.log" && { echo "FAIL: non-heartbeat line leaked into the pull"; fail=1; }
no "mock-token-xyz"; no "Bearer"; no "SHOULDNOTAPPEAR"; no "123456"
[ "$fail" = 0 ] && echo "ALL OK" || { echo "FAILED"; exit 1; }
