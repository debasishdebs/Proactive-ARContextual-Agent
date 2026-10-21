#!/usr/bin/env bash
# Battery run helper for the glasses (bash 3.2). Counts and numbers only; reads no system log.
#   tools/battery-run.sh start <label> | stop | pull <dir> | summary <dir>
# Device serial from $G. `summary` is pure awk over pulled files (no adb).
set -u

G="${G:-1901092545029120}"
PKG=com.rokid.context.glasses
RUNFILE=/data/local/tmp/batt_run.txt
LOGFILE=/data/local/tmp/batt.log

die() { echo "$*" >&2; exit 1; }

cmd_start() {
  local label
  label="$(printf '%s' "${1:-run}" | tr -cd 'A-Za-z0-9_.-')"
  [ "$(adb -s "$G" get-state 2>/dev/null)" = "device" ] || die "device $G not ready"
  adb -s "$G" shell 'pkill -f "[b]att_logger"' >/dev/null 2>&1
  adb -s "$G" shell dumpsys batterystats --reset >/dev/null 2>&1
  adb -s "$G" shell "echo label=$label > $RUNFILE; echo start=\$(date +%m-%d_%H:%M:%S) >> $RUNFILE"
  adb -s "$G" shell 'setsid nohup sh -c "while true; do echo \"\$(date +%m-%d_%H:%M:%S) \$(dumpsys battery | grep -E \"^ +(level|temperature|USB powered|AC powered):\" | tr -s \" \" | tr \"\\n\" \" \")\"; sleep 60; done # batt_logger" > /data/local/tmp/batt.log 2>&1 < /dev/null &'
  echo "started label=$label"
}

cmd_stop() {
  adb -s "$G" shell 'pkill -f "[b]att_logger"' >/dev/null 2>&1
  adb -s "$G" shell "echo end=\$(date +%m-%d_%H:%M:%S) >> $RUNFILE"
  echo "stopped"
}

cmd_pull() {
  local d="${1:-}"
  [ -n "$d" ] || die "usage: pull <dir>"
  mkdir -p "$d"
  adb -s "$G" pull "$LOGFILE" "$d/batt.log" >/dev/null 2>&1
  adb -s "$G" pull "$RUNFILE" "$d/batt_run.txt" >/dev/null 2>&1
  adb -s "$G" exec-out run-as "$PKG" cat files/capture_diag.log > "$d/capture_diag.log" 2>/dev/null
  adb -s "$G" exec-out run-as "$PKG" cat files/capture_diag.log.1 > "$d/capture_diag.log.1" 2>/dev/null
  adb -s "$G" shell dumpsys batterystats --charged "$PKG" 2>/dev/null \
    | grep -v -i -E "token|AuthorizationHelper|deviceSecret|deviceKey|secret|password|DevCmdHelper|PhoneCallManager|TelephoneManager|BluetoothPBAPManager|MobileMsgDialog|AssistServer" \
    | grep -i -E "Estimated power use|camera|cpu|wake|bluetooth|screen" > "$d/batterystats.txt"
  local f
  for f in batt.log batt_run.txt capture_diag.log capture_diag.log.1 batterystats.txt; do
    [ -f "$d/$f" ] && echo "$f $(wc -l < "$d/$f" | tr -d ' ') lines"
  done
}

cmd_summary() {
  local d="${1:-}"
  [ -n "$d" ] && [ -f "$d/batt_run.txt" ] && [ -f "$d/batt.log" ] || die "usage: summary <dir> (needs batt_run.txt and batt.log)"
  local start end
  start="$(sed -n 's/^start=//p' "$d/batt_run.txt" | head -1)"
  end="$(sed -n 's/^end=//p' "$d/batt_run.txt" | tail -1)"
  [ -n "$start" ] && [ -n "$end" ] || die "batt_run.txt needs start= and end="
  # Timestamps are MM-DD_HH:MM:SS (glasses clock); diag lines are "MM-DD HH:MM:SS.mmm".
  local diag=""
  [ -f "$d/capture_diag.log.1" ] && diag="$d/capture_diag.log.1"
  [ -f "$d/capture_diag.log" ] && diag="$diag $d/capture_diag.log"
  awk -v S="$start" -v E="$end" '
    function secs(t,   a) { split(substr(t, 7), a, ":"); return a[1]*3600 + a[2]*60 + a[3] }
    function stamp(t) { sub(/_/, " ", t); return t }
    function inwin(t) { return t >= stamp(S) && t <= stamp(E) }
    FNR == 1 { fileno++ }
    fileno == 1 {
      # batt.log: "MM-DD_HH:MM:SS level: N temperature: N ..."
      t = $1; if (t < S || t > E) next
      for (i = 2; i < NF; i++) if ($i == "level:") { l = $(i+1) + 0; if (bs == "") bs = l; be = l }
      next
    }
    /heartbeat v=2/ {
      t = substr($0, 1, 14)
      if (!inwin(t)) next
      n++
      for (i = 1; i <= NF; i++) {
        k = $i; v = ""; p = index(k, "=")
        if (p == 0) continue
        v = substr(k, p + 1); k = substr(k, 1, p - 1)
        if (k == "camOpenMs1m") cam += v
        else if (k == "analysed1m") an += v
        else if (k == "kf1m") kf += v
        else if (k == "upBytes1m") up += v
        else if (k == "batteryC") { if (v != "null" && v + 0 > bc) bc = v + 0 }
        else if (k == "opens1m") op += v
        else if (k == "wake") w[v]++
        else if (k == "tier") ti[v]++
        else if (k == "reason") re[v]++
        else if (k == "sensors") se[v]++
      }
    }
    function list(arr,   k, s, tmp) {
      s = ""
      for (k in arr) tmp[++c2] = k ":" arr[k]
      # insertion sort for stable output
      for (i = 2; i <= c2; i++) { x = tmp[i]; j = i - 1; while (j > 0 && tmp[j] > x) { tmp[j+1] = tmp[j]; j-- } tmp[j+1] = x }
      for (i = 1; i <= c2; i++) s = s (i > 1 ? "," : "") tmp[i]
      c2 = 0
      return s
    }
    END {
      dur = (secs(E) - secs(S)) / 60; if (dur < 0) dur += 1440
      ppm = (dur > 0 && bs != "") ? (bs - be) / dur : 0
      proj = ppm > 0 ? 100 / ppm / 60 : 0
      printf "duration_min=%.1f batt_start=%s batt_end=%s pct_per_min=%.2f proj_h=%.1f hb_lines=%d", dur, bs, be, ppm, proj, n
      printf " camOpenMs1m_mean=%.1f analysed1m_mean=%.1f kf1m_mean=%.1f upBytes1m_mean=%.1f batteryC_max=%.1f opens1m_mean=%.1f\n", (n ? cam/n : 0), (n ? an/n : 0), (n ? kf/n : 0), (n ? up/n : 0), bc, (n ? op/n : 0)
      print "wake=" list(w)
      print "tier=" list(ti)
      print "reason=" list(re)
      print "sensors=" list(se)
    }
  ' "$d/batt.log" $diag
}

case "${1:-}" in
  start) shift; cmd_start "$@" ;;
  stop) cmd_stop ;;
  pull) shift; cmd_pull "$@" ;;
  summary) shift; cmd_summary "$@" ;;
  *) echo "usage: $0 start <label> | stop | pull <dir> | summary <dir>" >&2; exit 2 ;;
esac
