#!/bin/bash
# Link health report for PCA (READ-ONLY, bash 3.2 / macOS awk compatible).
#   tools/link-health-report.sh --phone <serial> [--date MM-DD]      read every pca.log segment from the phone (run-as; debuggable builds only)
#   tools/link-health-report.sh --file <oldest> ... [--file <pca.log>] [--date MM-DD]   read saved log files, OLDEST FIRST (highest pca.log.N first, pca.log last; no adb)
#   tools/link-health-report.sh --fixtures                            parsing fixtures, no adb
# Counts per clock hour of the chosen day: glasses frames received, link CONNECTING/CONNECTED, authorization timeouts,
# SESSION_TERMINATED send failures, watchdog HubDown ticks, relaunch lines, and the longest quiet gap between glasses frames.
# It prints counts and clock hours only: never a log line, a path, a photo id or any content. It never writes to the device.
# Why: the glasses link is shared with other apps (Rokid-Coder, Hi Rokid); this shows what the link did, so a clash is measured, not guessed.
set -u
export LC_ALL=C

usage() { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

# awk program: stdin = log lines "MM-DD HH:MM:SS.mmm L Tag message"; -v day=MM-DD
REPORT_AWK='
function secs(t,   a) { split(t, a, ":"); return a[1] * 3600 + a[2] * 60 + a[3] }
$1 != day { next }
{
  h = substr($2, 1, 2) + 0
  if (!(h in seen)) { seen[h] = 1; hours[++nh] = h }
  line = $0
}
index(line, "RokidFrameInbox append result=true origin=glasses") > 0 {
  frames[h]++
  s = secs($2)
  if (hasprev[h] && s - prev[h] > gap[h]) gap[h] = s - prev[h]
  prev[h] = s; hasprev[h] = 1
}
index(line, "link=CONNECTING") > 0 { connecting[h]++ }
index(line, "link=CONNECTED") > 0 { connected[h]++ }
index(line, "authorization timed out") > 0 { authto[h]++ }
index(line, "failed SESSION_TERM") > 0 { sessterm[h]++ }
index(line, "watchdog tick verdict=HubDown") > 0 { hubdown[h]++ }
index(line, "PcRelaunch relaunch:") > 0 { relaunch[h]++ }
END {
  if (nh == 0) { print "no log lines for " day; exit 0 }
  # hours in ascending order
  for (i = 1; i <= nh; i++) for (j = i + 1; j <= nh; j++) if (hours[j] < hours[i]) { t = hours[i]; hours[i] = hours[j]; hours[j] = t }
  printf "%-5s %7s %10s %9s %8s %9s %8s %9s %10s\n", "hour", "frames", "connecting", "connected", "auth-to", "sess-term", "hubdown", "relaunch", "max-gap-s"
  for (i = 1; i <= nh; i++) {
    h = hours[i]
    printf "%02d:xx %7d %10d %9d %8d %9d %8d %9d %10d\n", h, frames[h] + 0, connecting[h] + 0, connected[h] + 0, authto[h] + 0, sessterm[h] + 0, hubdown[h] + 0, relaunch[h] + 0, gap[h] + 0
    tf += frames[h]; tc += connecting[h]; tn += connected[h]; ta += authto[h]; ts += sessterm[h]; th += hubdown[h]; tr += relaunch[h]
  }
  printf "%-5s %7d %10d %9d %8d %9d %8d %9d\n", "total", tf, tc, tn, ta, ts, th, tr
}'

report_stdin() { awk -v day="$1" "$REPORT_AWK"; }

# stdin = file names in files/logs; prints the pca.log segments OLDEST first (highest pca.log.N ... pca.log.1, then pca.log); anything else is ignored
segment_order_all() {
  awk '$0 == "pca.log" { print "0 " $0; next } $0 ~ /^pca\.log\.[0-9]+$/ { print (substr($0, 9) + 0) " " $0 }' | sort -rn | awk '{ print $2 }'
}

fixtures() {
  local pass=0 fail=0 out want
  check() { # name want got
    if [ "$2" = "$3" ]; then pass=$((pass + 1)); echo "  fixture ok      : $1"; else fail=$((fail + 1)); echo "  FIXTURE FAILED  : $1"; echo "    want: $2"; echo "    got : $3"; fi
  }
  local L1='11-19 21:00:01.000 I RokidFrameInbox append result=true origin=glasses
11-19 21:00:11.000 I RokidFrameInbox append result=true origin=glasses
11-19 21:05:11.500 I RokidFrameInbox append result=true origin=glasses
11-19 21:06:00.000 I PcGlassesLink link=CONNECTING
11-19 21:06:02.000 I PcGlassesLink link=CONNECTED
11-19 21:06:03.000 W PcGlassesLink send pc_state failed SESSION_TERMINATED
11-19 22:10:00.000 W PcGlassesLink authorization timed out
11-19 22:10:01.000 I PcWatchdog watchdog tick verdict=HubDown why=hub
11-19 22:10:02.000 I PcRelaunch relaunch: link OFFLINE->CONNECTING
11-18 21:00:00.000 I RokidFrameInbox append result=true origin=glasses'
  out="$(printf '%s\n' "$L1" | report_stdin 11-19)"
  check "hour rows and total for one day (other days ignored)" "hour   frames connecting connected  auth-to sess-term  hubdown  relaunch  max-gap-s
21:xx       3          1         1        0         1        0         0        300
22:xx       0          0         0        1         0        1         1          0
total       3          1         1        1         1        1         1" "$out"
  check "an empty day says so" "no log lines for 11-20" "$(printf '%s\n' "$L1" | report_stdin 11-20)"
  check "the max gap is per hour and in seconds" "300" "$(printf '%s\n' "$L1" | report_stdin 11-19 | awk '$1=="21:xx"{print $NF}')"
  check "a hostile line (no fields) is ignored" "no log lines for 11-19" "$(printf '%s\n' 'x' '' '   ' | report_stdin 11-19)"
  check "the report never prints a log line (counts only)" "0" "$(printf '%s\n' "$L1" | report_stdin 11-19 | grep -c 'SESSION_TERMINATED\|origin=glasses\|pc_state')"
  check "hours come out ascending even if the log is not" "21:xx 22:xx" "$(printf '%s\n%s\n' '11-19 22:00:00.000 I PcRelaunch relaunch: x' '11-19 21:00:00.000 I PcRelaunch relaunch: y' | report_stdin 11-19 | awk 'NR>1 && $1 ~ /xx/ {printf "%s ", $1}' | sed 's/ $//')"
  check "segments come out oldest first, the active file last, junk ignored" "pca.log.12 pca.log.2 pca.log.1 pca.log" "$(printf '%s\n' pca.log pca.log.1 pca.log.2 notes.txt pca.log.12 pca.log.x pca.log.bak | segment_order_all | tr '\n' ' ' | sed 's/ $//')"
  echo "fixtures: $pass passed, $fail failed"
  [ "$fail" = 0 ]
}

PHONE="" DAY="" FILES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --phone) PHONE="${2:-}"; shift 2 ;;
    --date) DAY="${2:-}"; shift 2 ;;
    --file) FILES+=("${2:-}"); shift 2 ;;
    --fixtures) fixtures; exit $? ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done
[ -n "$DAY" ] || DAY="$(date +%m-%d)"
case "$DAY" in [0-1][0-9]-[0-3][0-9]) ;; *) echo "REFUSED: --date must be MM-DD" >&2; exit 2 ;; esac

if [ -n "$PHONE" ] && [ ${#FILES[@]} -gt 0 ]; then echo "REFUSED: use --phone OR --file, not both" >&2; exit 2; fi
if [ -z "$PHONE" ] && [ ${#FILES[@]} -eq 0 ]; then usage; fi

echo "link health report: day $DAY (counts per clock hour; read-only)"
if [ -n "$PHONE" ]; then
  # adb with stdin closed (adb would otherwise swallow a pipe); older log first
  SEGS="$(adb -s "$PHONE" exec-out run-as com.rokid.context ls files/logs </dev/null 2>/dev/null | tr -d '\r' | segment_order_all)"
  [ -n "$SEGS" ] || SEGS="$(printf 'pca.log.1\npca.log\n')"      # ls refused: the two names a pre-trip build used
  { for f in $SEGS; do adb -s "$PHONE" exec-out run-as com.rokid.context cat "files/logs/$f" </dev/null 2>/dev/null; done; } | tr -d '\r' | report_stdin "$DAY"
else
  for f in "${FILES[@]}"; do [ -r "$f" ] || { echo "REFUSED: cannot read $f" >&2; exit 2; }; done
  cat "${FILES[@]}" | tr -d '\r' | report_stdin "$DAY"
fi
