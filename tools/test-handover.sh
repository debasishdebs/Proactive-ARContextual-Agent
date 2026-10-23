#!/usr/bin/env bash
# Fixture test for tools/handover.sh (LGC10-min): a FAKE adb on PATH (testdata/handover/fake-adb.sh) and the m1-run python3 mock for the import. No real device is touched.
# The fake refuses and records install/uninstall/am/pm/force-stop/kill commands; the test asserts it never saw one, and never a logcat except the watch one. Run from anywhere (bash 3.2).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; T="$HERE/testdata/m1-run"; FA="$HERE/testdata/handover/fake-adb.sh"; H="$HERE/handover.sh"
W=$(mktemp -d); fail=0
python3 "$T/mock_server.py" "$W/port" & MOCK=$!
trap 'kill $MOCK 2>/dev/null; rm -rf "$W"' EXIT
for i in 1 2 3 4 5 6 7 8 9 10; do [ -s "$W/port" ] && break; sleep 0.3; done
HP="127.0.0.1:$(cat "$W/port")"
GPKG=com.rokid.context.glasses; PPKG=com.rokid.context
SECRET=deviceSecret=abc123

ok() { echo "ok: $1"; }
bad() { echo "FAIL: $1"; fail=1; }
has() { case "$OUT" in *"$1"*) ;; *) bad "$CASE: output lacks '$1'" ;; esac; }
hasnt() { case "$OUT" in *"$1"*) bad "$CASE: output must not contain '$1'" ;; esac; }
logs() { grep -q -- "$1" "$ADB_LOG" || bad "$CASE: adb log lacks '$1'"; }
logsnt() { if grep -q -- "$1" "$ADB_LOG"; then bad "$CASE: adb saw '$1'"; fi; }

# fresh sandbox: three devices (glasses usb tid 3, phone wireless tid 7 with spaces in the serial, watch tid 9), one sealed trace, one unclosed, logs with a secret line
setup() {
  CASE="$1"; S="$W/$1"; rm -rf "$S"; mkdir -p "$S/bin" "$S/tmp"
  cp "$FA" "$S/bin/adb"
  printf 'List of devices attached\nGSER1 device usb:1-1 product:g transport_id:3\nadb-PHN 123 (2)._adb-tls-connect._tcp device product:p model:P transport_id:7\nWATCH01 device usb:1-2 product:w transport_id:9\n' > "$S/devices"
  local g="$S/data/$GPKG/files" p="$S/data/$PPKG/files/logs"
  mkdir -p "$g/imu/traces/trace-aaaa-0001" "$g/imu/traces/trace-unclosed-0001" "$p"
  echo '{}' > "$g/imu/traces/trace-aaaa-0001/manifest.json"; echo '{"c":1}' > "$g/imu/traces/trace-aaaa-0001/chunk-0001.json"; echo '{"c":1}' > "$g/imu/traces/trace-unclosed-0001/chunk-0001.json"
  printf '10-24 10:10:00.000 I PcCapture status ok\n10-24 10:15:00.000 I x %s\n10-24 10:20:00.000 I PcCapture heartbeat v=2 plan=f5782e0d\n' "$SECRET" > "$g/capture_diag.log"
  printf '10-24 09:00:00.000 I PcCapture heartbeat v=2 old\n' > "$g/capture_diag.log.1"
  for f in pca.log pca.log.1 qna_eval.jsonl qna_eval.jsonl.1; do echo "line of $f" > "$p/$f"; done
  ADB_LOG="$S/adb.log"; : > "$ADB_LOG"; rm -f "$ADB_LOG.bad"
  GD="$g"; OUTD="$S/out"
}
# run_h <args...>: stdin closed (no prompts); the output (stdout+stderr) in $OUT, status in $RC
run_h() {
  OUT=$(PATH="$S/bin:$PATH" FAKE="$S" ADB_LOG="$ADB_LOG" G=GSER1 M1_SCHEME=http TMPDIR="$S/tmp" "$H" "$@" 2>&1 < /dev/null); RC=$?
  echo "--- $CASE rc=$RC"; echo "$OUT" | sed 's/^/    /'
  RUND=$(ls -d "$1"/handover-* 2>/dev/null | tail -1)
  [ -e "$ADB_LOG.bad" ] && bad "$CASE: the fake adb received a forbidden command: $(cat "$ADB_LOG.bad")"
  logsnt ' logcat'; logsnt 'exec-out run-as com.rokid.context.glasses logcat'
  return 0
}
secrets_clean() { # nothing secret on stdout/stderr or in any pulled file
  hasnt abc123; hasnt "deviceSecret"; hasnt mock-token-xyz; hasnt Bearer; hasnt 123456
  if [ -d "$OUTD" ] && grep -r -q -E 'abc123|deviceSecret|mock-token' "$OUTD" 2>/dev/null; then bad "$CASE: a secret reached a pulled file"; fi
}

# 1. TIDs required with glasses + phone + watch attached (nothing is read from any device first)
setup RequiredTids
run_h "$OUTD"; [ "$RC" != 0 ] || bad "$CASE: ran without tids"
has "M1_PHONE_TID"; has "M1_WATCH_TID"; has "2 non-glasses"
logsnt 'exec-out'; [ ! -d "$RUND/trace-aaaa-0001" ] || bad "$CASE: pulled before the check"
setup RequiredTidsPhoneOnly
M1_PHONE_TID=7 run_h "$OUTD"; [ "$RC" != 0 ] || bad "$CASE: ran with only the phone tid"
has "M1_WATCH_TID"; logsnt 'exec-out'
setup RequiredTidsFlags
run_h "$OUTD" --phone-tid 7 --watch-tid 9 --no-import; [ "$RC" = 0 ] || bad "$CASE: --phone-tid/--watch-tid refused"
setup UnknownTid
run_h "$OUTD" --phone-tid 7 --watch-tid 42 --no-import; [ "$RC" != 0 ] || bad "$CASE: a tid that is not attached was accepted"
logsnt 'exec-out'

# 2. a pending cleanup refuses ALL trace export (and reads nothing else); an unreadable pending state fails closed
setup PendingCleanupRefused
echo "epoch=2" > "$GD/trace_cleanup_pending"
run_h "$OUTD" --phone-tid 7 --watch-tid 9 --no-import; [ "$RC" != 0 ] || bad "$CASE: exported while trace_cleanup_pending exists"
has "trace_cleanup_pending"; has "REFUSED"
logsnt 'capture_diag'; logsnt 'tar -c'; logsnt 'files/logs'
[ ! -d "$RUND/trace-aaaa-0001" ] || bad "$CASE: a trace was exported"
setup PendingProbeFailsClosed
FAKE_RUNAS_REFUSED=$GPKG run_h "$OUTD" --phone-tid 7 --watch-tid 9 --no-import; [ "$RC" != 0 ] || bad "$CASE: exported with an unreadable pending state"
[ ! -d "$RUND/trace-aaaa-0001" ] || bad "$CASE: a trace was exported"
setup TracePullRefusesToo
echo "epoch=2" > "$GD/trace_cleanup_pending"
OUT=$(PATH="$S/bin:$PATH" FAKE="$S" ADB_LOG="$ADB_LOG" G=GSER1 "$HERE/trace-pull.sh" "$OUTD" 2>&1 < /dev/null); RC=$?
[ "$RC" != 0 ] || bad "$CASE: trace-pull.sh exported while pending"; has "trace_cleanup_pending"; logsnt 'tar -c'

# 3. --no-import: files only, exit 0, nothing marked, no push, no pairing needed; phone by transport id, never serial; no logcat at all
setup NoImport
run_h "$OUTD" --phone-tid 7 --watch-tid 9 --no-import; [ "$RC" = 0 ] || bad "$CASE: rc $RC"
[ -f "$RUND/trace-aaaa-0001/manifest.json" ] || bad "$CASE: sealed trace not pulled"
[ ! -d "$RUND/trace-unclosed-0001" ] || bad "$CASE: unclosed trace pulled"
for f in capture_diag.log capture_diag.log.1; do [ -f "$RUND/logs/glasses/$f" ] || bad "$CASE: logs/glasses/$f missing"; done
for f in pca.log pca.log.1 qna_eval.jsonl qna_eval.jsonl.1; do [ -f "$RUND/logs/phone/$f" ] || bad "$CASE: logs/phone/$f missing"; done
has "not imported"; has "pulled 1 trace(s)"
[ ! -d "$GD/imu/transferred" ] || bad "$CASE: a marker was written without an import"
logsnt ' push '; logs "^-t 7 exec-out run-as $PPKG"; logsnt '^-s adb-PHN'; logsnt ' logcat'
secrets_clean
setup NoImportNeedsNoPairing
run_h "$OUTD" --phone-tid 7 --watch-tid 9; [ "$RC" = 0 ] || bad "$CASE: files-only run without pairing must exit 0"
has "not imported: needs pairing"; [ ! -d "$GD/imu/transferred" ] || bad "$CASE: marker without import"

setup NoImportSkipsEvenWithPairingArgs
run_h "$OUTD" --phone-tid 7 --watch-tid 9 --no-import --host "$HP" --code 123456 --fp 00000000; [ "$RC" = 0 ] || bad "$CASE: rc $RC"
logsnt ' push '; [ ! -d "$GD/imu/transferred" ] || bad "$CASE: marker with --no-import"; has "not imported: --no-import"; secrets_clean

# 4. phone log rotations: a missing one is skipped with a count; a refused run-as prints the fixed message and exits 0
setup MissingRotation
rm -f "$S/data/$PPKG/files/logs/qna_eval.jsonl.1"
run_h "$OUTD" --phone-tid 7 --watch-tid 9 --no-import; [ "$RC" = 0 ] || bad "$CASE: rc $RC"
has "skipped 1"; [ ! -f "$RUND/logs/phone/qna_eval.jsonl.1" ] || bad "$CASE: ghost rotation"; [ -f "$RUND/logs/phone/qna_eval.jsonl" ] || bad "$CASE: present rotation lost"
setup PhoneRunAsRefused
FAKE_RUNAS_REFUSED=$PPKG run_h "$OUTD" --phone-tid 7 --watch-tid 9 --no-import; [ "$RC" = 0 ] || bad "$CASE: rc $RC"
has "phone logs not pulled: run-as refused (build not debuggable)"; [ -f "$RUND/trace-aaaa-0001/manifest.json" ] || bad "$CASE: glasses traces lost"

# 5. watch logcat only when WATCH_TAGS is set (by tid, the filtered tags, through the deny list); never the glasses logcat
setup WatchTagsUnset
run_h "$OUTD" --phone-tid 7 --watch-tid 9 --no-import; logsnt 'logcat'
setup WatchTagsSet
OUT=$(WATCH_TAGS="PcWatch:I *:S" PATH="$S/bin:$PATH" FAKE="$S" ADB_LOG="$ADB_LOG" G=GSER1 M1_SCHEME=http TMPDIR="$S/tmp" "$H" "$OUTD" --phone-tid 7 --watch-tid 9 --no-import 2>&1 < /dev/null); RC=$?
RUND=$(ls -d "$OUTD"/handover-* | tail -1)
echo "--- $CASE rc=$RC"; echo "$OUT" | sed 's/^/    /'
[ "$RC" = 0 ] || bad "$CASE: rc $RC"; logs '^-t 9 logcat -d -v time -s PcWatch:I \*:S'; logsnt '^-s GSER1 .*logcat'; [ -s "$RUND/logs/watch/logcat.txt" ] || bad "$CASE: no watch log"
grep -q 'zzz999\|deviceSecret' "$RUND/logs/watch/logcat.txt" && bad "$CASE: secret line in the watch log"
hasnt zzz999

# 6. heartbeat evidence: no heartbeat, or none fresh, rejects BEFORE traces or import; the labelled override continues
setup NoHeartbeat
printf '10-24 10:10:00.000 I PcCapture status ok\n' > "$GD/capture_diag.log"; : > "$GD/capture_diag.log.1"
run_h "$OUTD" --phone-tid 7 --watch-tid 9; [ "$RC" != 0 ] || bad "$CASE: accepted a run without heartbeat"
has "NO FRESH HEARTBEAT EVIDENCE"; logsnt 'tar -c'; logsnt ' push '; [ ! -d "$GD/imu/transferred" ] || bad "$CASE: marker"
setup StaleHeartbeat
printf '10-24 06:00:00.000 I PcCapture heartbeat v=2 plan=x\n' > "$GD/capture_diag.log"
run_h "$OUTD" --phone-tid 7 --watch-tid 9 --no-import; [ "$RC" != 0 ] || bad "$CASE: accepted a stale heartbeat (4.5 h old)"
has "NO FRESH HEARTBEAT EVIDENCE"; logsnt 'tar -c'
setup HeartbeatOverride
printf '10-24 10:10:00.000 I PcCapture status ok\n' > "$GD/capture_diag.log"
run_h "$OUTD" --phone-tid 7 --watch-tid 9 --no-import --allow-no-heartbeat; [ "$RC" = 0 ] || bad "$CASE: override refused"
has "NO FRESH HEARTBEAT EVIDENCE"; has "continuing"; [ -f "$RUND/trace-aaaa-0001/manifest.json" ] || bad "$CASE: nothing pulled"

# 7. import: pairing only here (args), then the marker OUTSIDE the trace dir, only for traces whose import succeeded; nothing is stored
setup ImportMarks
mkdir -p "$GD/imu/traces/trace-partial-0001"; echo '{}' > "$GD/imu/traces/trace-partial-0001/manifest.json"
run_h "$OUTD" --phone-tid 7 --watch-tid 9 --host "$HP" --code 123456 --fp 00000000
[ "$RC" != 0 ] || bad "$CASE: a failed (partial) import must make the run non-zero"
has "imported traceId=trace-aaaa-0001"; has "marked 1"
[ -f "$GD/imu/transferred/trace-aaaa-0001" ] || bad "$CASE: marker for the imported trace missing"
[ ! -e "$GD/imu/transferred/trace-partial-0001" ] || bad "$CASE: marker for a trace whose import failed"
[ -z "$(ls "$GD/imu/traces/trace-aaaa-0001" | grep -v -E '^(manifest.json|chunk-0001.json)$')" ] || bad "$CASE: extra file inside the trace dir"
[ ! -s "$GD/imu/transferred/trace-aaaa-0001" ] || bad "$CASE: marker is not zero-byte"
# the marker is written after the push (import) in the adb order
pl=$(grep -n ' push ' "$ADB_LOG" | head -1 | cut -d: -f1); ml=$(grep -n 'transferred' "$ADB_LOG" | head -1 | cut -d: -f1)
[ -n "$pl" ] && [ -n "$ml" ] && [ "$ml" -gt "$pl" ] || bad "$CASE: marker not after the import (push line $pl, marker line $ml)"
secrets_clean
if ls "$S"/tmp/m1-run-* >/dev/null 2>&1; then bad "$CASE: pairing state (token, host) was left on disk"; fi
setup ImportPairFails
run_h "$OUTD" --phone-tid 7 --watch-tid 9 --host "$HP" --code 000000 --fp 00000000
[ "$RC" != 0 ] || bad "$CASE: wrong code must fail the run"; [ ! -d "$GD/imu/transferred" ] || bad "$CASE: marker after a failed pairing"; logsnt ' push '
secrets_clean
setup SecondPullAfterMark
run_h "$OUTD" --phone-tid 7 --watch-tid 9 --host "$HP" --code 123456 --fp 00000000
[ "$RC" = 0 ] || bad "$CASE: first import rc $RC"
run_h "$OUTD.2" --phone-tid 7 --watch-tid 9 --host "$HP" --code 123456 --fp 00000000
[ "$RC" = 0 ] || bad "$CASE: second pull after the mark failed (bad_file_set?)"; [ -f "$RUND/trace-aaaa-0001/manifest.json" ] || bad "$CASE: second pull lost the trace"
[ -f "$GD/imu/transferred/trace-aaaa-0001" ] || bad "$CASE: marker gone"

# 7b. output dir reuse: every run exports into its own fresh subdirectory; a stale trace from an earlier hand-over (since deleted on the glasses) is never imported or marked
setup StaleTraceNeverImported
mkdir -p "$OUTD/trace-stale-0001" "$OUTD/handover-00000000-old/trace-stale-0002"; echo '{}' > "$OUTD/trace-stale-0001/manifest.json"; echo '{}' > "$OUTD/handover-00000000-old/trace-stale-0002/manifest.json"
run_h "$OUTD" --phone-tid 7 --watch-tid 9 --host "$HP" --code 123456 --fp 00000000; [ "$RC" = 0 ] || bad "$CASE: rc $RC"
has "export dir: $RUND"; [ -f "$RUND/trace-aaaa-0001/manifest.json" ] || bad "$CASE: current trace missing from the fresh dir"
[ ! -e "$RUND/trace-stale-0001" ] || bad "$CASE: stale trace in the fresh dir"
[ ! -e "$GD/imu/transferred/trace-stale-0001" ] && [ ! -e "$GD/imu/transferred/trace-stale-0002" ] || bad "$CASE: a stale trace was marked"
logsnt 'trace-stale'; hasnt "traceId=trace-stale"

# 7c. remote shell parsing (the fake joins the arguments and parses them like the real remote shell): the fence must SEE a pending cleanup and a clear one
setup FenceSeesPending
echo "epoch=2" > "$GD/trace_cleanup_pending"
run_h "$OUTD" --phone-tid 7 --watch-tid 9 --no-import; [ "$RC" != 0 ] || bad "$CASE: pending cleanup not seen through remote shell parsing"; has "files/trace_cleanup_pending exists"; has "No trace or log was exported"; logsnt capture_diag
setup FenceSeesClear
run_h "$OUTD" --phone-tid 7 --watch-tid 9 --no-import; [ "$RC" = 0 ] || bad "$CASE: clear state refused (rc $RC)"; hasnt "REFUSED"
setup MarkerAndProbesThroughRemoteShell
run_h "$OUTD" --phone-tid 7 --watch-tid 9 --host "$HP" --code 123456 --fp 00000000
[ -f "$GD/imu/transferred/trace-aaaa-0001" ] || bad "$CASE: marker not created through the remote shell"
[ -f "$RUND/logs/phone/pca.log" ] && [ -f "$RUND/logs/glasses/capture_diag.log" ] || bad "$CASE: log probes lost through the remote shell"

# 8. static: the script never names an install/uninstall/am/pm/force-stop/pkill/killall command, and its only logcat is the watch one
if grep -n -E 'adb[^#]*[ "](install|uninstall|force-stop)|shell +(am|pm) |pkill|killall' "$H" | grep -v '^[0-9]*:#'; then bad "static: forbidden command in handover.sh"; fi
[ "$(grep -v '^ *#' "$H" | grep -c 'logcat')" -le 2 ] || bad "static: more logcat mentions than the watch branch"

[ "$fail" = 0 ] && echo "ALL OK" || { echo "FAILED"; exit 1; }
