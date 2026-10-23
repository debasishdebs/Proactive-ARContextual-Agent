#!/usr/bin/env bash
# FAKE adb for tools/test-handover.sh (test only; no real device). $FAKE is the sandbox: $FAKE/devices (adb devices -l text), $FAKE/data/<pkg>/ (run-as cwd).
# Every call is appended to $ADB_LOG. Install/uninstall/am/pm/force-stop/kill commands are recorded in $ADB_LOG.bad and refused.
echo "$*" >> "$ADB_LOG"
all=" $* "
case "$all" in
  *" install "*|*" install-multiple "*|*" uninstall "*|*" am "*|*" pm "*|*force-stop*|*pkill*|*killall*|*" kill-server "*|*" start-server "*|*" reboot "*|*" root "*)
    echo "$*" >> "$ADB_LOG.bad"; echo "FORBIDDEN" >&2; exit 99 ;;
esac
kind=""; sel=""
case "${1:-}" in -s|-t) kind="$1"; sel="$2"; shift 2 ;; esac
case "${1:-}" in
  devices) cat "$FAKE/devices"; exit 0 ;;
  shell)
    shift
    case "${1:-}" in date) echo "${FAKE_NOW:-10-24 10:30:00}"; exit 0 ;; esac
    exit 0 ;;
  push) exit 0 ;;
  logcat) echo "10-24 10:00:00.000 W WatchTag: watch line deviceSecret=zzz999"; echo "10-24 10:00:01.000 I WatchTag: ok"; exit 0 ;;
  exec-out)
    shift
    # Like real adb: the arguments are joined with spaces and parsed by a REMOTE shell (a lost quote changes the command). run-as is a function: it only affects the command it prefixes.
    export FAKE FAKE_RUNAS_REFUSED
    exec bash -c 'run-as() {
      local pkg="$1"; shift
      if [ "${FAKE_RUNAS_REFUSED:-}" = "$pkg" ]; then echo "run-as: package not debuggable: $pkg" >&2; return 1; fi
      [ -d "$FAKE/data/$pkg" ] || { echo "run-as: unknown package: $pkg" >&2; return 1; }
      ( cd "$FAKE/data/$pkg" && exec "$@" )
    }
    cd /; '"$*" ;;
esac
exit 1
