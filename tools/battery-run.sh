#!/usr/bin/env bash
# Battery run helper for the glasses (bash 3.2). Counts and numbers only; reads no system log.
#   tools/battery-run.sh start <label> | stop | pull <dir> | summary <dir> [minutes]
# Device serial from $G. `summary` is pure awk over pulled files (no adb).
set -u

G="${G:-1901092545029120}"
PKG=com.rokid.context.glasses
RUNFILE=/data/local/tmp/batt_run.txt
LOGFILE=/data/local/tmp/batt.log
CPUFILE=/data/local/tmp/cpu.log
CPUSCRIPT=/data/local/tmp/cpu_logger.sh
# Handoff section 2 deny list (never written to a pulled file).
DENY="token|AuthorizationHelper|deviceSecret|deviceKey|secret|password|DevCmdHelper|PhoneCallManager|TelephoneManager|BluetoothPBAPManager|MobileMsgDialog"

die() { echo "$*" >&2; exit 1; }

# The on-device sampler (toybox sh, no awk). Prints only labels, pids, sanitised thread names and tick counts.
cpu_logger_script() {
  cat <<'DEVEOF'
# cpu_logger
sample_stat() { # pid -> prints utime+stime ticks (0 when unreadable)
  rest=$(sed 's/.*) //' "/proc/$1/stat" 2>/dev/null)
  set -- $rest
  echo $(( ${12:-0} + ${13:-0} ))
}
while true; do
  T=$(date +%m-%d_%H:%M:%S)
  sspid=0
  for lp in app:^com.rokid.context.glasses$ ss:^system_server$ cam:^cameraserver$ camprov:camera.provider sens:android.hardware.sensors bt:^com.android.bluetooth$ audio:^audioserver$ assist:assistserver; do
    label=${lp%%:*}
    pat=${lp#*:}
    case "$pat" in
      ^*) re="^[0-9]+ ${pat#^}" ;;
      *) re="^[0-9]+ .*$pat" ;;
    esac
    pid=$(ps -A -o PID,NAME | tr -s ' ' | sed 's/^ //' | grep -E "$re" | head -1 | cut -d' ' -f1)
    if [ -n "$pid" ] && [ -r "/proc/$pid/stat" ]; then
      ticks=$(sample_stat "$pid")
    else
      pid=0; ticks=0
    fi
    [ "$label" = ss ] && sspid=$pid
    echo "$T cpu $label pid=$pid ticks=$ticks"
  done
  if [ "$sspid" != 0 ]; then
    for d in /proc/$sspid/task/*; do
      comm=$(tr -cd 'A-Za-z0-9_.:-' < "$d/comm" 2>/dev/null)
      [ -n "$comm" ] || continue
      echo "$comm ticks=$(sample_stat "${d#/proc/}" | head -1)"
    done 2>/dev/null | sort -t= -k2 -n -r | head -12 | while read -r line; do echo "$T thr ss $line"; done
  fi
  sleep 60
done
DEVEOF
}

cmd_start() {
  local label
  label="$(printf '%s' "${1:-run}" | tr -cd 'A-Za-z0-9_.-')"
  [ "$(adb -s "$G" get-state 2>/dev/null)" = "device" ] || die "device $G not ready"
  adb -s "$G" shell 'pkill -f "[b]att_logger"' >/dev/null 2>&1
  adb -s "$G" shell dumpsys batterystats --reset >/dev/null 2>&1
  local clk
  clk="$(adb -s "$G" shell getconf CLK_TCK 2>/dev/null | tr -d '\r')"
  case "$clk" in ''|*[!0-9]*) clk=100 ;; esac
  adb -s "$G" shell "echo label=$label > $RUNFILE; echo start=\$(date +%m-%d_%H:%M:%S) >> $RUNFILE; echo clk_tck=$clk >> $RUNFILE"
  adb -s "$G" shell 'pkill -f "[c]pu_logger"' >/dev/null 2>&1
  local tmp
  tmp="$(mktemp)"
  cpu_logger_script > "$tmp"
  adb -s "$G" push "$tmp" "$CPUSCRIPT" >/dev/null 2>&1
  rm -f "$tmp"
  adb -s "$G" shell "setsid nohup sh $CPUSCRIPT > $CPUFILE 2>/dev/null < /dev/null &"
  adb -s "$G" shell 'setsid nohup sh -c "while true; do echo \"\$(date +%m-%d_%H:%M:%S) \$(dumpsys battery | grep -E \"^ +(level|temperature|USB powered|AC powered):\" | tr -s \" \" | tr \"\\n\" \" \")\"; sleep 60; done # batt_logger" > /data/local/tmp/batt.log 2>&1 < /dev/null &'
  echo "started label=$label"
}

cmd_stop() {
  adb -s "$G" shell 'pkill -f "[b]att_logger"' >/dev/null 2>&1
  adb -s "$G" shell 'pkill -f "[c]pu_logger"' >/dev/null 2>&1
  adb -s "$G" shell "echo end=\$(date +%m-%d_%H:%M:%S) >> $RUNFILE"
  echo "stopped"
}

cmd_pull() {
  local d="${1:-}"
  [ -n "$d" ] || die "usage: pull <dir>"
  mkdir -p "$d"
  adb -s "$G" pull "$LOGFILE" "$d/batt.log" >/dev/null 2>&1
  adb -s "$G" pull "$RUNFILE" "$d/batt_run.txt" >/dev/null 2>&1
  # cpu.log: only "<ts> cpu|thr ..." lines, through the deny list before it is kept (AssistServer is never written; only the label "assist" is).
  adb -s "$G" pull "$CPUFILE" "$d/cpu.raw" >/dev/null 2>&1
  if [ -f "$d/cpu.raw" ]; then
    grep -v -i -E "$DENY" "$d/cpu.raw" | grep -E '^[0-9][0-9]-[0-9][0-9]_[0-9:]+ (cpu|thr) ' > "$d/cpu.log"
    rm -f "$d/cpu.raw"
  fi
  adb -s "$G" exec-out run-as "$PKG" cat files/capture_diag.log > "$d/capture_diag.log" 2>/dev/null
  adb -s "$G" exec-out run-as "$PKG" cat files/capture_diag.log.1 > "$d/capture_diag.log.1" 2>/dev/null
  adb -s "$G" shell dumpsys batterystats --charged "$PKG" 2>/dev/null \
    | grep -v -i -E "$DENY|AssistServer" \
    | grep -i -E "Estimated power use|camera|cpu|wake|bluetooth|screen" > "$d/batterystats.txt"
  local f
  for f in batt.log batt_run.txt cpu.log capture_diag.log capture_diag.log.1 batterystats.txt; do
    [ -f "$d/$f" ] && echo "$f $(wc -l < "$d/$f" | tr -d ' ') lines"
  done
}

cmd_summary() {
  local d="${1:-}" mode="${2:-}"
  [ -n "$d" ] && [ -f "$d/batt_run.txt" ] && [ -f "$d/batt.log" ] || die "usage: summary <dir> (needs batt_run.txt and batt.log)"
  local start end clk
  clk="$(sed -n 's/^clk_tck=//p' "$d/batt_run.txt" | head -1)"
  case "$clk" in ''|*[!0-9]*|0) clk=100 ;; esac
  start="$(sed -n 's/^start=//p' "$d/batt_run.txt" | head -1)"
  end="$(sed -n 's/^end=//p' "$d/batt_run.txt" | tail -1)"
  [ -n "$start" ] && [ -n "$end" ] || die "batt_run.txt needs start= and end="
  # Timestamps are MM-DD_HH:MM:SS (glasses clock); diag lines are "MM-DD HH:MM:SS.mmm".
  local diag=""
  [ -f "$d/capture_diag.log.1" ] && diag="$d/capture_diag.log.1"
  [ -f "$d/capture_diag.log" ] && diag="$diag $d/capture_diag.log"
  if [ "$mode" = "minutes" ]; then
    summary_minutes "$d" "$start" "$end" "$clk" "$diag"
    return
  fi
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
        else if (k == "glances1m") gl += v
        else if (k == "capped1m") cp += v
        else if (k == "same1m") sm += v
        else if (k == "novelSkip1m") ns += v
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
      printf " camOpenMs1m_mean=%.1f analysed1m_mean=%.1f kf1m_mean=%.1f upBytes1m_mean=%.1f batteryC_max=%.1f opens1m_mean=%.1f glances1m_mean=%.1f capped1m_mean=%.1f same1m_mean=%.1f novelSkip1m_mean=%.1f\n", (n ? cam/n : 0), (n ? an/n : 0), (n ? kf/n : 0), (n ? up/n : 0), bc, (n ? op/n : 0), (n ? gl/n : 0), (n ? cp/n : 0), (n ? sm/n : 0), (n ? ns/n : 0)
      print "wake=" list(w)
      print "tier=" list(ti)
      print "reason=" list(re)
      print "sensors=" list(se)
    }
  ' "$d/batt.log" $diag
  [ -f "$d/cpu.log" ] && summary_cpu "$d/cpu.log" "$start" "$end" "$clk"
}

# Per-process CPU seconds per minute over the window: first and last in-window sample per label; a pid change starts a new segment.
summary_cpu() {
  awk -v S="$2" -v E="$3" -v CLK="$4" '
    function secs(t,   a) { split(substr(t, 7), a, ":"); return a[1]*3600 + a[2]*60 + a[3] }
    $1 < S || $1 > E { next }
    $2 == "cpu" || $2 == "thr" {
      if ($2 == "cpu") { key = $3; split($4, a, "="); pid = a[2]; split($5, b, "="); k = b[2] + 0 }
      else { key = "thr:" $4; pid = "t"; split($5, b, "="); k = b[2] + 0 }
      t = secs($1); if (t < 0) t += 86400
      if (!(key in tf)) { tf[key] = t; if ($2 == "thr") { order[++nthr] = key } }
      tl[key] = t
      if (key in lastp) {
        if (lastp[key] == pid && pid != "0" && k >= lastk[key]) sum[key] += k - lastk[key]
      }
      lastp[key] = pid; lastk[key] = k
    }
    function rate(key,   m) { m = (tl[key] - tf[key]) / 60; return (key in tf && m > 0) ? sum[key] / CLK / m : 0 }
    END {
      printf "cpu_s_per_min app=%.2f ss=%.2f cam=%.2f camprov=%.2f sens=%.2f bt=%.2f audio=%.2f assist=%.2f\n", rate("app"), rate("ss"), rate("cam"), rate("camprov"), rate("sens"), rate("bt"), rate("audio"), rate("assist")
      for (i = 1; i <= nthr; i++) r[i] = rate(order[i])
      for (i = 2; i <= nthr; i++) { x = r[i]; y = order[i]; j = i - 1; while (j > 0 && r[j] < x) { r[j+1] = r[j]; order[j+1] = order[j]; j-- } r[j+1] = x; order[j+1] = y }
      s = ""
      for (i = 1; i <= nthr && i <= 8; i++) { n = order[i]; sub(/^thr:/, "", n); s = s (i > 1 ? "," : "") sprintf("%s:%.2f", n, r[i]) }
      print "ss_threads=" s
    }
  ' "$1"
}

# One line per in-window heartbeat minute: counters plus CPU s/min from the sample in that minute and the one before it (gap <= 5 min, same pid).
summary_minutes() {
  local d="$1" start="$2" end="$3" clk="$4" diag="$5" cpu=""
  [ -f "$d/cpu.log" ] && cpu="$d/cpu.log"
  [ -n "$cpu" ] || cpu=/dev/null
  awk -v S="$start" -v E="$end" -v CLK="$clk" '
    function secs(t,   a) { split(substr(t, 7), a, ":"); return a[1]*3600 + a[2]*60 + a[3] }
    function stamp(t) { sub(/_/, " ", t); return t }
    FNR == 1 { fileno++ }
    fileno == 1 {
      if ($2 != "cpu") next
      split($4, a, "="); split($5, b, "=")
      l = $3; ts = $1
      cnt[l]++; i = cnt[l]
      st[l, i] = ts; sp[l, i] = a[2]; sk[l, i] = b[2] + 0
      next
    }
    /heartbeat v=2/ {
      t = substr($0, 1, 14); if (!(t >= stamp(S) && t <= stamp(E))) next
      mm = substr($0, 7, 5); md = substr($0, 1, 5)
      v["opens1m"] = "na"; v["kf1m"] = "na"; v["glances1m"] = "na"
      for (i = 1; i <= NF; i++) { p = index($i, "="); if (p == 0) continue; k = substr($i, 1, p - 1); if (k in v) v[k] = substr($i, p + 1) }
      line = mm " opens1m=" v["opens1m"] " kf1m=" v["kf1m"] " glances1m=" v["glances1m"]
      nl = split("app ss cam camprov", labs, " ")
      for (q = 1; q <= nl; q++) {
        l = labs[q]; val = "na"; pfx = md "_" mm; idx = 0
        for (i = 1; i <= cnt[l]; i++) if (substr(st[l, i], 1, 11) == md "_" substr(mm, 1, 5)) idx = i
        if (idx > 1 && sp[l, idx] == sp[l, idx-1] && sp[l, idx] != "0") {
          gap = secs(st[l, idx]) - secs(st[l, idx-1])
          if (gap < 0) gap += 86400
          if (gap > 0 && gap <= 300 && sk[l, idx] >= sk[l, idx-1]) val = sprintf("%.2f", (sk[l, idx] - sk[l, idx-1]) / CLK / (gap / 60))
        }
        line = line " " l "=" val
      }
      print line
    }
  ' "$cpu" $diag
}

case "${1:-}" in
  start) shift; cmd_start "$@" ;;
  stop) cmd_stop ;;
  pull) shift; cmd_pull "$@" ;;
  summary) shift; cmd_summary "$@" ;;
  print-cpu-logger) cpu_logger_script ;;
  *) echo "usage: $0 start <label> | stop | pull <dir> | summary <dir> [minutes]" >&2; exit 2 ;;
esac
