#!/usr/bin/env bash
# Gate runner with a stall watchdog (bash 3.2 compatible; macOS has no `timeout`).
#   tools/gate.sh <log> [gradle args...]
# Runs ./gradlew in android-companion/ and writes <log>, then appends EXIT=<code>.
# Every CHECK_SECS (60) it checks the log size. After STALL_SECS (480) without growth it
# appends `jps -l` and a jstack of every Gradle JVM to <log>.stall, kills the run, exits 124.
# Env overrides (self-test only): GATE_CMD, CHECK_SECS, STALL_SECS.
set -u

if [ $# -lt 1 ]; then echo "usage: tools/gate.sh <log> [gradle args...]" >&2; exit 2; fi
LOG="$1"; shift
case "$LOG" in /*) ;; *) LOG="$PWD/$LOG" ;; esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHECK_SECS="${CHECK_SECS:-60}"
STALL_SECS="${STALL_SECS:-480}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

if [ $# -eq 0 ]; then
  set -- --no-daemon test :phone:assembleDebug :glasses:assembleDebug
fi

fsize() { stat -f%z "$1" 2>/dev/null || stat -c%s "$1" 2>/dev/null || echo 0; }

kill_tree() {
  local p="$1" c
  for c in $(pgrep -P "$p" 2>/dev/null); do kill_tree "$c"; done
  kill -TERM "$p" 2>/dev/null
}
force_tree() {
  local p="$1" c
  for c in $(pgrep -P "$p" 2>/dev/null); do force_tree "$c"; done
  kill -KILL "$p" 2>/dev/null
}

dump_stall() {
  {
    echo "=== stall at $(date '+%Y-%m-%d %H:%M:%S'): no log growth for ${STALL_SECS}s ==="
    echo "--- jps -l ---"
    jps -l 2>&1
    jps -l 2>/dev/null | awk '/[Gg]radle/ {print $1}' | while read -r pid; do
      echo "--- jstack $pid ---"
      jstack "$pid" 2>&1
    done
  } >> "$LOG.stall"
}

: > "$LOG"
rm -f "$LOG.stall"

if [ -n "${GATE_CMD:-}" ]; then
  ( cd "$ROOT/android-companion" && exec bash -c "$GATE_CMD" ) >> "$LOG" 2>&1 &
else
  ( cd "$ROOT/android-companion" && exec ./gradlew "$@" ) >> "$LOG" 2>&1 &
fi
PID=$!

last=$(fsize "$LOG"); idle=0
while kill -0 "$PID" 2>/dev/null; do
  sleep "$CHECK_SECS"
  kill -0 "$PID" 2>/dev/null || break
  cur=$(fsize "$LOG")
  if [ "$cur" != "$last" ]; then last="$cur"; idle=0; else idle=$((idle + CHECK_SECS)); fi
  if [ "$idle" -ge "$STALL_SECS" ]; then
    dump_stall
    kill_tree "$PID"; sleep 2; force_tree "$PID"
    { wait "$PID"; } 2>/dev/null
    echo "EXIT=124" >> "$LOG"
    exit 124
  fi
done

wait "$PID"; CODE=$?
echo "EXIT=$CODE" >> "$LOG"
exit "$CODE"
