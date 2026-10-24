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
chk "hours_from_full=7.5"; chk "failing_windows=0 total_windows=24 share=0.00 % T=3 %"; chk "PASS failing-window share 0.00 % <= 3 %"; chk "unclassified (0)"
# Task 23c criterion: shadow shOpen against the counterfactual GLANCE ADMISSIONS within max(5 %, 4 opens); the DWELL+ONSET opens are only an INFO line (mock: admissions 100, opens 90)
chk "PASS shadow shOpen=102 vs counterfactual glance_admissions=100, diff +2 (2.0 %), allowed +-5.0"
chk "INFO shadow shOpen=102 vs counterfactual dwell_onset_opens=90, diff +12"
chk "inexact_bounds reasons={}"; chk "opens_range baseline=[100, 100]"
run replay t-1 --diag "$T/diag_bad.log" && { echo "FAIL: 130 vs 100 admissions should fail the criterion"; fail=1; }
chk "FAIL shadow shOpen=130 vs counterfactual glance_admissions=100"
# a report without glanceAdmissions (a phone build before the stall model) fails closed with a clear message, never silently passes
run replay t-noadm --diag "$T/diag_ok.log" && { echo "FAIL: a report without glanceAdmissions must not pass"; fail=1; }
chk "the report has no glanceAdmissions"
# gate: failing windows / compared windows <= T percent (default 3, hard cap 5); mock trace t-f<failing>-<total>
run replay t-f2-116 --diag "$T/diag_ok.log"; chk "failing_windows=2 total_windows=116 share=1.72 % T=3 %"; chk "PASS failing-window share 1.72 % <= 3 %"; chk "failure classification: unclassified (2), informational"
run replay t-f6-100 --diag "$T/diag_ok.log" && { echo "FAIL: 6 of 100 must fail at the default 3"; fail=1; }
chk "FAIL failing-window share 6.00 % <= 3 %"
run replay t-fk2-100 --diag "$T/diag_ok.log"; chk "failure classification (counts by kind): GATE_DIFF=1, OPEN_MISS=1"
run replay t-f5-100 --max-fail-pct 5 --diag "$T/diag_ok.log"; chk "PASS failing-window share 5.00 % <= 5 %"
M1_MAX_FAIL_PCT=5 run replay t-f5-100 --diag "$T/diag_ok.log"; chk "failing-window share 5.00 % <= 5 %"
run replay t-f6-100 --max-fail-pct 5 --diag "$T/diag_ok.log" && { echo "FAIL: 6 of 100 must fail at T=5"; fail=1; }
chk "FAIL failing-window share 6.00 % <= 5 %"
run replay t-f1-100 --max-fail-pct 6 --diag "$T/diag_ok.log" && { echo "FAIL: T=6 must be refused"; fail=1; }
chk "REFUSED: max failing-window share '6'"
o=$("$R" replay t-f1-100 --max-fail-pct 6 2>&1); case "$o" in *"replay started"*|*"jobState"*) echo "FAIL: T=6 reached the replay"; fail=1 ;; esac
run replay t-f1-100 --max-fail-pct abc --diag "$T/diag_ok.log" && { echo "FAIL: T=abc must be refused"; fail=1; }
chk "REFUSED: max failing-window share 'abc'"
M1_MAX_FAIL_PCT=7 run replay t-f1-100 --diag "$T/diag_ok.log" && { echo "FAIL: env T=7 must be refused"; fail=1; }
chk "REFUSED: max failing-window share '7'"
# the parity criterion still applies when the failing share is fine
run replay t-f2-116 --diag "$T/diag_bad.log" && { echo "FAIL: parity must still be enforced"; fail=1; }
chk "PASS failing-window share 1.72 %"; chk "FAIL shadow shOpen=130 vs counterfactual glance_admissions=100"
# the old total-opens criterion, only for SETTLE-wake scripts
run replay t-1 --legacy-total --diag "$T/diag_ok.log"; chk "PASS shadow vs counterfactual opens (legacy total criterion, SETTLE-wake scripts only): 102 vs 100, deviation 2.0%"
run replay t-1 --legacy-total --diag "$T/diag_bad.log" && { echo "FAIL: legacy 130 vs 100 should fail the 5% check"; fail=1; }
chk "FAIL shadow vs counterfactual opens (legacy total criterion, SETTLE-wake scripts only): 130 vs 100"
# pull: a stub adb (glasses on USB serial G, phone on wireless adb whose serial has spaces) records its arguments; the phone is selected by transport id only
mkdir -p "$W/bin"; cat > "$W/bin/adb" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$ADB_LOG"
[ "$1" = devices ] && printf 'List of devices attached\n1901092545029120 device usb:1-1 product:g transport_id:3\nadb-PHN 123 (2)._adb-tls-connect._tcp device product:p model:P transport_id:7\n'
exit 0
STUB
chmod +x "$W/bin/adb"; export ADB_LOG="$W/adb.log"; : > "$ADB_LOG"
rm -rf "$W/pull"; mkdir -p "$W/pull/trace-aaaa-0001" "$W/pull/trace-partial-0001"; echo '{}' > "$W/pull/trace-aaaa-0001/manifest.json"; echo '{}' > "$W/pull/trace-partial-0001/manifest.json"
PATH="$W/bin:$PATH" M1_DIAG="$T/diag_ok.log" run pull "$W/pull" && { echo "FAIL: the PARTIAL trace should fail the pull"; fail=1; }
chk "heartbeat lines saved: 3"; chk "imported traceId=trace-aaaa-0001 status=COMPLETE events=11 chunks=1"; chk "error=partial_trace"; chk "rerun with --allow-partial"
grep -q -- '-t 7 push '"$W/pull/trace-aaaa-0001"' /sdcard/Android/data/com.rokid.context/files/trace-staging/' "$ADB_LOG" || { echo "FAIL: no push by transport id"; fail=1; }
grep -q -- '-s ' "$ADB_LOG" && { echo "FAIL: phone selected by serial"; fail=1; }
rm -rf "$W/pull/trace-partial-0001"
PATH="$W/bin:$PATH" M1_DIAG="$T/diag_ok.log" run pull "$W/pull"; chk "imported 1 trace(s), failed 0"
PATH="$W/bin:$PATH" M1_PHONE_TID=9 M1_DIAG="$T/diag_ok.log" run pull "$W/pull" >/dev/null; grep -q -- '-t 9 push' "$ADB_LOG" || { echo "FAIL: M1_PHONE_TID ignored"; fail=1; }
run replay --diag "$T/diag_ok.log"; chk "replay started"
grep -q SHOULDNOTAPPEAR "$W/pull/heartbeats.log" "$T/diag_ok.log" >/dev/null && grep -q SHOULDNOTAPPEAR "$W/pull/heartbeats.log" && { echo "FAIL: non-heartbeat line leaked into the pull"; fail=1; }
# LGC4 hazard: a pull without heartbeat evidence (diagnostics may be off) is rejected BEFORE any import; M1_ALLOW_NO_HEARTBEAT=1 only labels it; status labels it.
printf '10-21 10:00:00.000 I PcCapture status ok\n' > "$W/nohb.log"
: > "$ADB_LOG"; rm -rf "$W/pull2"; mkdir -p "$W/pull2/trace-aaaa-0001"; echo '{}' > "$W/pull2/trace-aaaa-0001/manifest.json"
PATH="$W/bin:$PATH" M1_DIAG="$W/nohb.log" run pull "$W/pull2" && { echo "FAIL: pull without heartbeats must be rejected"; fail=1; }
chk "NO HEARTBEAT EVIDENCE"; grep -q ' push ' "$ADB_LOG" && { echo "FAIL: imported although there was no heartbeat evidence"; fail=1; }
PATH="$W/bin:$PATH" M1_ALLOW_NO_HEARTBEAT=1 M1_DIAG="$W/nohb.log" run pull "$W/pull2"; chk "imported 1 trace(s), failed 0"; chk "continuing"
M1_DIAG="$W/nohb.log" run status; chk "NO HEARTBEAT EVIDENCE"
no "mock-token-xyz"; no "Bearer"; no "SHOULDNOTAPPEAR"; no "123456"
[ "$fail" = 0 ] && echo "ALL OK" || { echo "FAILED"; exit 1; }
