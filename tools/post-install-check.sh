#!/usr/bin/env bash
# READ-ONLY post-install verification for the trip build (bash 3.2 compatible).
#   tools/post-install-check.sh --phone <serial> [--glasses <serial>] --apks <folder>
#   tools/post-install-check.sh --fixtures        (pure-logic fixtures, no device, no adb)
# Each check prints PASS / WARN / FAIL / UNKNOWN with a short literal reason; the run ends with a summary.
# Exit codes: 0 = no FAIL (WARN/UNKNOWN allowed, read them), 1 = at least one FAIL, 2 = refused / usage.
# NEVER installs, uninstalls, clears, force-stops, writes settings or sends input: every adb call goes through adb_safe (allow-list, tested in the fixtures).
# It only reads (dumpsys, getprop, pm path, logcat -d, run-as cat of two prefs files) and pulls the installed base APK into a temp dir it removes.
# Trip-build checks (check_trip_build, debuggable builds): files/logs segments and pca.log content, shared_prefs names, pin counts, db user_version on a host COPY: counts and enums only.
# Every adb call carries -s. Prints counts, hashes, names and statuses only: never a logcat line, token or pairing code.
# Procedure: tools/INSTALL-TRIP-BUILD.md section "Post-install check".
set -uo pipefail
umask 077
export LC_ALL=C   # byte-wise awk/sed: hostile or non-UTF-8 log bytes must never abort a parser

PHONE_PKG=com.rokid.context
GLASSES_PKG=com.rokid.context.glasses
PIDWAIT="${PCA_CHECK_PIDWAIT:-10}"
SKEW_WARN=120
BATT_WARN=50
FREE_WARN_KB=2097152   # 2 GB

PHONE="" GLASSES="" APKS="" FIXTURES=0 TMP=""
ADB="" AAPT="" APKSIGNER="" APKANALYZER=""
N_PASS=0 N_WARN=0 N_FAIL=0 N_UNK=0
REMOTE_OUT="" REMOTE_RC=""

say() { echo "$*"; }
die() { echo "REFUSED: $*" >&2; exit 2; }
cleanup() { if [ -n "$TMP" ] && [ -d "$TMP" ]; then rm -rf "$TMP"; fi; TMP=""; return 0; }
trap 'cleanup' EXIT
usage() { sed -n '2,5p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

# ---------- result recording ----------
res() { # STATUS label reason
  case "$1" in
    PASS) N_PASS=$((N_PASS + 1)) ;; WARN) N_WARN=$((N_WARN + 1)) ;;
    FAIL) N_FAIL=$((N_FAIL + 1)) ;; UNKNOWN) N_UNK=$((N_UNK + 1)) ;;
    *) echo "internal: bad status $1" >&2; return 1 ;;
  esac
  printf '%-8s %-28s %s\n' "$1" "$2" "$3"
}
exit_code_for() { # pass warn fail unknown -> 0 or 1 (non-numeric input counts as a failure)
  case "$3" in ''|*[!0-9]*) echo 1; return ;; esac
  [ "$3" -gt 0 ] && echo 1 || echo 0
}

# ---------- the ONLY adb entry point: allow-list ----------
adb_safe() { # serial args... ; refuses anything that could change the device
  local s="$1" sub cmd; shift
  sub="${1:-}"
  case "$sub" in
    exec-out|pull) ;;
    logcat) case " $* " in *" -c "*|*" -G "*|*" -P "*|*" --clear "*|*" -b clear "*) echo "adb_safe: refused logcat mutation" >&2; return 97 ;; esac ;;
    shell)
      cmd="$*"
      case "$cmd" in
        *" install"*|*"uninstall"*|*"pm clear"*|*"force-stop"*|*"pm grant"*|*"pm revoke"*|*" input "*|*"settings put"*|*"settings delete"*|*"svc "*|*"am start"*|*"am broadcast"*|*"am kill"*|*" kill "*|*"rm "*|*" mv "*|*" cp "*|*"reboot"*|*"monkey"*|*"cmd "*" set"*|*"appops set"*|*"dumpsys battery set"*|*"dumpsys battery reset"*|*">"*)
          echo "adb_safe: refused a mutating shell command" >&2; return 97 ;;
      esac ;;
    *) echo "adb_safe: refused adb subcommand '$sub'" >&2; return 97 ;;
  esac
  case "$sub:$*" in
    exec-out:*run-as*) case "$*" in *" cat "*|*" id"*) ;; *) echo "adb_safe: run-as only for cat/id" >&2; return 97 ;; esac ;;
  esac
  "$ADB" -s "$s" "$@"
}
A() { adb_safe "$@"; }

rc_shell() { # serial cmd -> REMOTE_OUT, REMOTE_RC (exit status of cmd). returns 1 on transport failure, 2 on marker failure
  local raw cleaned last re='^__PCA_RC=([0-9]+)$'
  REMOTE_OUT=""; REMOTE_RC=""
  raw="$(A "$1" shell "$2; echo __PCA_RC=\$?" 2>&1)" || return 1
  cleaned="$(printf '%s\n' "$raw" | tr -d '\r')"
  last="$(printf '%s\n' "$cleaned" | tail -n 1)"
  [[ "$last" =~ $re ]] || return 2
  REMOTE_RC="${BASH_REMATCH[1]}"
  REMOTE_OUT="$(printf '%s\n' "$cleaned" | sed '$d')"
  return 0
}

# ---------- pure parsers (fixture-tested) ----------
sha256_of() { local out d; if command -v shasum >/dev/null 2>&1; then out="$(shasum -a 256 "$1")" || return 1; else out="$(sha256sum "$1")" || return 1; fi
  d="${out%% *}"; [[ "$d" =~ ^[0-9a-f]{64}$ ]] || return 1; printf '%s\n' "$d"; }

bad_idval() { local v; v="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  case "$v" in ''|unknown|null|'(null)'|none|undefined|*error*|*'not found'*|*'no such'*|*denied*) return 0 ;; esac
  [[ "$v" =~ ^[0:-]+$ ]] && return 0; return 1; }
id_alias() { local a1 a2 b1 b2; [ "$1" = "$2" ] && return 0
  IFS='|' read -r a1 a2 _ <<<"$1"; IFS='|' read -r b1 b2 _ <<<"$2"
  [ "$a1" = "$b1" ] && return 0; [ "$a2" = "$b2" ] && return 0; return 1; }

# call state class: stdin dumpsys telephony.registry -> idle | active | unknown
call_class() {
  local n=0 line bad=0 re='^[[:space:]]*mCallState=([0-9]+)[[:space:]]*$' empty_re='^[[:space:]]*mCallState[A-Za-z_][A-Za-z0-9_]*=(\{\}|\[\])[[:space:]]*$'
  while IFS= read -r line || [ -n "$line" ]; do
    case "$(printf '%s' "$line" | tr 'A-Z' 'a-z')" in *mcallstate*) ;; *) continue ;; esac
    if [[ "$line" =~ $empty_re ]]; then continue; fi
    if [[ "$line" =~ $re ]]; then
      if [ "${BASH_REMATCH[1]}" != 0 ]; then bad=1; fi
      n=$((n + 1))
    else echo unknown; return 0; fi
  done
  if [ "$n" -eq 0 ]; then echo unknown; elif [ "$bad" = 1 ]; then echo active; else echo idle; fi
}

# runtime permissions: stdin dumpsys package -> lines "name true|false" (unique)
perm_parse() {
  awk '
    /^[[:space:]]*runtime permissions:[[:space:]]*$/ { inb = 1; next }
    inb && /^[[:space:]]+[A-Za-z0-9_.]+: granted=(true|false)/ {
      n = $1; sub(/:$/, "", n); g = $0; sub(/.*granted=/, "", g); sub(/[^a-z].*/, "", g); print n, g; next }
    { inb = 0 }' | sort -u
}
perm_state() { # name ; stdin = perm_parse output -> granted|denied|absent
  local name="$1" line st=absent
  while read -r line; do [ -n "$line" ] || continue
    if [ "${line%% *}" = "$name" ]; then case "${line##* }" in true) st=granted ;; *) st=denied ;; esac; fi
  done
  echo "$st"
}
perm_counts() { # stdin perm_parse -> "granted denied"
  awk '$2=="true"{g++} $2=="false"{d++} END{printf "%d %d\n", g+0, d+0}'
}
perm_denied_names() { awk '$2=="false"{printf "%s ", $1}' | sed 's/android\.permission\.//g; s/ $//'; }

# deviceidle whitelist: stdin, $1 = package -> yes|no
idle_whitelisted() { if grep -Eq "(^|,)${1//./\\.},[0-9]+[[:space:]]*\$" ; then echo yes; else echo no; fi; }

# clock skew: host_before device host_after -> absolute seconds (device vs host midpoint), or "bad"
skew_secs() {
  local a="$1" d="$2" b="$3" mid x
  case "$a$d$b" in *[!0-9]*|'') echo bad; return ;; esac
  [ -n "$a" ] && [ -n "$d" ] && [ -n "$b" ] || { echo bad; return; }
  mid=$(( (a + b) / 2 )); x=$(( d - mid )); [ "$x" -ge 0 ] || x=$(( -x )); echo "$x"
}

# prefs XML boolean: stdin xml, $1 key -> true | false | absent | other
prefs_bool() {
  local key="$1" line v
  line="$(grep -E "<boolean[^>]*name=\"${key}\"" | sed -n 1p)"
  [ -n "$line" ] || { echo absent; return; }
  v="$(printf '%s' "$line" | sed -n 's/.*value="\([^"]*\)".*/\1/p')"
  case "$v" in true|false) echo "$v" ;; *) echo other ;; esac
}
# any other tag with that name (string/int) is not a boolean: treat as other
prefs_state() { # stdin xml, key -> off | ON | other
  local xml; xml="$(cat)"
  local b; b="$(printf '%s\n' "$xml" | prefs_bool "$1")"
  case "$b" in absent) if printf '%s\n' "$xml" | grep -Eq "name=\"$1\""; then echo other; else echo off; fi ;; false) echo off ;; true) echo ON ;; *) echo other ;; esac
}

# battery: stdin dumpsys battery -> "level charging" (charging yes|no|unknown)
battery_parse() {
  awk '/^[[:space:]]*level:/ {l=$2} /^[[:space:]]*(AC|USB|Wireless|Dock) powered:/ {if ($3=="true") c="yes"; else if (c=="") c="no"} /^[[:space:]]*status:/ {st=$2}
       END { if (l ~ /^[0-9]+$/) { if (c=="" && st=="") c="unknown"; if (st=="2"||st=="5") c="yes"; if (c=="") c="no"; print l, c } else print "bad" }'
}
# free KB of /data: stdin df -k /data -> number or bad
df_free_kb() { awk 'NR>=2 && $4 ~ /^[0-9]+$/ {print $4; f=1; exit} END{if(!f) print "bad"}'; }

# aapt badging: stdin -> "versionCode versionName" / name
badging_vc() { sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p" | sed -n 1p; }
badging_vn() { sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" | sed -n 1p; }
dump_vc() { tr -d '\r' | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | sed -n 1p; }
dump_vn() { tr -d '\r' | sed -n 's/.*versionName=//p' | sed -n 1p; }

# pm path: stdin -> first base.apk path
pm_base_path() { tr -d '\r' | sed -n 's/^package://p' | awk '/base\.apk$/ {print; f=1; exit} END{}' ; }

# compare two values: equal -> PASS, else FAIL; empty either side -> UNKNOWN
cmp3() { [ -n "$1" ] && [ -n "$2" ] || { echo UNKNOWN; return; }; [ "$1" = "$2" ] && echo PASS || echo FAIL; }

# log classifier: stdin = logcat -v threadtime text, args: pid, package -> "fatal anr db relevant"
log_counts() {
  local pid="$1" pq="${2//./\\.}"
  awk -v pid="$pid" -v pq="$pq" '
    BEGIN {
      pb = "(^|[^A-Za-z0-9_.])" pq "([^A-Za-z0-9_.]|$)"
      db = "SQLite[A-Za-z]*Exception|file is not a database|SQLITE_NOTADB|SQLITE_CANTOPEN|Could not open database|code 14([^0-9]|$)|Migration didn.t properly handle|A migration from [0-9]+ to [0-9]+ was required but not found|Room cannot verify the data integrity|no such column|no such table|duplicate column|IllegalStateException.*(Room|openHelper|[Dd]atabase)|Cannot open database|Unable to open database|database disk image is malformed"
      anr = "ANR in " pq "([^A-Za-z0-9_.]|$)"
      proc = "Process: " pq "[,: ]"
    }
    $0 ~ /^[0-9][0-9]-[0-9][0-9] / {
      app = ($3 == pid); pk = ($0 ~ pb)
      if (!app && !pk) next
      rel++
      if ($0 ~ /FATAL EXCEPTION/ && app) fe++
      if ($0 ~ proc) pr++
      if ($0 ~ anr || (app && $0 ~ /Application Not Responding/)) an++
      if ($0 ~ db) d++
    }
    END { f = (fe > pr) ? fe : pr; printf "%d %d %d %d\n", f, an + 0, d + 0, rel + 0 }'
}

# ---------- trip-build pure parsers (counts and enums only: nothing read from a log or a prefs file is ever echoed except a whitelisted token) ----------
# shared awk prelude: tmins("MM-DD", "HH:MM:SS") -> minutes since Jan 1 (pca.log has no year); tdiff handles the new-year wrap
TS_AWK='
BEGIN { split("31 28 31 30 31 30 31 31 30 31 30 31", dim, " ") }
function tmins(d, t,   m, n, i) { m = substr(d, 1, 2) + 0; n = substr(d, 4, 2) + 0; for (i = 1; i < m; i++) n += dim[i]; return (n * 24 + substr(t, 1, 2)) * 60 + substr(t, 4, 2) }
function tdiff(a, b,   x) { x = b - a; if (x < 0) x += 365 * 1440; return x }
function isrec() { return ($1 ~ /^[0-9][0-9]-[0-9][0-9]$/ && $2 ~ /^[0-9][0-9]:[0-9][0-9]:[0-9][0-9]/) }
'
# ls -l of files/logs (stdin) -> "segments total_bytes ignored has_main": only exact pca.log / pca.log.N names with 8 ls fields count
seg_scan() {
  awk '
    $1 ~ /^-/ && NF == 8 && $5 ~ /^[0-9]+$/ && length($5) <= 12 && ($8 == "pca.log" || $8 ~ /^pca\.log\.[0-9][0-9]?$/) { n++; t += $5; if ($8 == "pca.log") m = 1; next }
    $1 ~ /^total/ { next }
    NF > 0 { ign++ }
    END { printf "%d %d %d %d\n", n + 0, t + 0, ign + 0, m + 0 }'
}
# detailed-log inference from log text (stdin): "start hb ran_min gap_min" (start Y|N; ran/gap minutes or -), measured to the LAST record
diag_state() {
  awk "$TS_AWK"'
    { if (isrec()) {
        t = tmins($1, $2); last = t; have = 1
        if ($4 == "PcDiag" && $5 == "app" && $6 == "start") { st = t; hb = 0; hbt = -1; s = 1 }
        else if ($4 == "PcDiag" && $5 == "heartbeat" && s) { hb++; hbt = t }
    } }
    END {
      if (!s || !have) { print "N 0 - -"; exit }
      printf "Y %d %d %s\n", hb, tdiff(st, last), (hbt < 0 ? "-" : tdiff(hbt, last))
    }'
}
# forbidden shapes in the message text of a log (stdin): "latlon url at" line counts (url = a real http:// or https:// link; the bare word http, e.g. the class name PlaceHttpException, is not one); the timestamp is not scanned
forbidden_counts() {
  awk '
    { l = $0; sub(/^[0-9][0-9]-[0-9][0-9] [0-9:.]+ /, "", l)
      if (l ~ /-?[0-9][0-9]?[0-9]?\.[0-9][0-9][0-9][0-9]*[ ,;:\/=]+-?[0-9][0-9]?[0-9]?\.[0-9][0-9][0-9][0-9]*/) a++
      if (tolower(l) ~ /https?:\/\//) b++
      if (index(l, "@") > 0) c++ }
    END { printf "%d %d %d\n", a + 0, b + 0, c + 0 }'
}
# PcData "blocked ... reason=<token>" lines (stdin) -> sorted "token n" lines; tokens only from the fixed DataPolicy list, anything else is "other"
pcdata_counts() {
  awk '
    BEGIN { n = split("on_device lan free offline wifi_only roaming unknown_network home_mobile small_allowed anywhere android_data_saver data_saver", k, " "); for (i = 1; i <= n; i++) ok[k[i]] = 1 }
    $4 == "PcData" && $5 == "blocked" { r = "other"; for (i = 6; i <= NF; i++) if (substr($i, 1, 7) == "reason=") { v = substr($i, 8); if (v in ok) r = v; break } c[r]++ }
    END { for (r in c) print r, c[r] }' | sort
}
# last "net cost=<ENUM>" PcData line (stdin) -> FREE|HOME_MOBILE|ROAMING|UNKNOWN|OFFLINE|RESTRICTED|other|none
pcdata_last_cost() {
  awk '$4 == "PcData" && $5 == "net" && $6 ~ /^cost=/ { v = substr($6, 6); if (v == "FREE" || v == "HOME_MOBILE" || v == "ROAMING" || v == "UNKNOWN" || v == "OFFLINE" || v == "RESTRICTED") last = v; else last = "other" } END { print (last == "" ? "none" : last) }'
}
# shared_prefs names (stdin: one name per line, or ls -l) -> "place_pins=Y|N data_saver=.. ai_backend=.. ai_backend_init=.. online_map=.." (exact names only)
prefs_present() {
  awk '
    { n = $NF; sub(/\r$/, "", n); if (NF == 1 || NF == 8) seen[n] = 1 }
    END { split("place_pins data_saver ai_backend ai_backend_init online_map", w, " ")
      for (i = 1; i <= 5; i++) printf "%s=%s%s", w[i], ((w[i] ".xml") in seen ? "Y" : "N"), (i < 5 ? " " : "\n") }'
}
# one <string> value of a prefs XML (stdin) by exact key name: the first match, raw (callers whitelist it before use)
prefs_string() { sed -n "s/.*<string name=\"$1\">\([^<]*\)<\/string>.*/\1/p" | sed -n 1p; }
# Model privacy (mirrors ModelPrivacyStore.load): $1 = ai_backend.xml text, $2 = ai_backend_init.xml text -> "mode=AUTO|OFFLINE_ONLY|missing|garbled reset=yes|no"
privacy_state() {
  local m last initb ib2 mode reset=no
  m="$(printf '%s\n' "$1" | prefs_string mode)"; last="$(printf '%s\n' "$2" | prefs_string last_choice)"
  initb="$(printf '%s\n' "$1" | prefs_bool initialised)"; ib2="$(printf '%s\n' "$2" | prefs_bool initialised)"
  if [ -z "$m" ]; then
    mode=missing; if [ "$initb" = true ] || [ "$ib2" = true ]; then reset=yes; fi
  else
    case "$m" in AUTO) mode=AUTO ;; OFFLINE_ONLY) mode=OFFLINE_ONLY ;; *) mode=garbled; reset=yes ;; esac
    if [ "$m" = AUTO ] && [ "$last" = OFFLINE_ONLY ]; then mode=OFFLINE_ONLY; reset=yes; fi
  fi
  echo "mode=$mode reset=$reset"
}
# place attempts (stdin log, $1 = window hours back from the log's last record) -> "total=N window=H outcomes=A:1,B:2|none garbled=G" (fixed outcome list only)
place_counts() {
  awk -v H="$1" "$TS_AWK"'
    BEGIN { n = split("DISABLED ZONE NO_FRAME STALE_FRAME SEEN NO_FIX STALE_FIX NO_CANDIDATES DUPLICATE RATE_LIMITED BUSY MATCHED NONE CONTRACT LOW_CONFIDENCE NEARBY NOTHING_NEARBY DROPPED FAILED NOT_ADMITTED", ko, " "); for (i = 1; i <= n; i++) known[ko[i]] = 1 }
    { if (isrec()) { t = tmins($1, $2); last = t; p = index($0, "place attempt outcome="); if (p > 0) { m++; at[m] = t; v = substr($0, p + 22); sub(/[^A-Za-z0-9_].*/, "", v); ot[m] = v } } }
    END {
      for (i = 1; i <= m; i++) if (tdiff(at[i], last) <= H * 60) { tot++; if (ot[i] in known) c[ot[i]]++; else g++ }
      s = ""; for (i = 1; i <= n; i++) if (c[ko[i]] > 0) s = s (s == "" ? "" : ",") ko[i] ":" c[ko[i]]
      printf "total=%d window=%d outcomes=%s garbled=%d\n", tot + 0, H, (s == "" ? "none" : s), g + 0 }'
}
# pins (stdin = place_pins.xml) -> "total with_moment without_moment" | bad | nopython. Never prints a name, id or any text.
pins_counts() {
  local o
  command -v python3 >/dev/null 2>&1 || { echo nopython; return; }
  o="$(python3 -c '
import sys, json, xml.etree.ElementTree as E
try:
    r = E.fromstring(sys.stdin.read()); s = None
    for e in r.iter("string"):
        if e.get("name") == "pins": s = e.text
    if s is None:
        print("0 0 0"); sys.exit(0)
    a = json.loads(s)
    if not isinstance(a, list) or any(not isinstance(o, dict) for o in a):
        print("bad"); sys.exit(0)
    m = sum(1 for o in a if isinstance(o.get("moment"), str) and o.get("moment"))
    print(len(a), m, len(a) - m)
except Exception:
    print("bad")
' 2>/dev/null)" || o=bad
  if [[ "$o" =~ ^[0-9]+\ [0-9]+\ [0-9]+$ ]]; then echo "$o"; else echo bad; fi
}
# db mode: $1 = first 15 bytes of context.db, $2 = db_encrypted ('true'|'false'|'') -> plaintext | encrypted | inconsistent (same pairing rule as install-trip-build.sh)
db_mode3() {
  case "$1:$2" in
    "SQLite format 3:false") echo plaintext ;;
    "SQLite format 3:"*) echo inconsistent ;;
    *:false) echo inconsistent ;;
    *) echo encrypted ;;
  esac
}
# segment names (stdin) -> oldest first (highest pca.log.N first, pca.log last); anything else is dropped
seg_order() { awk '$0 == "pca.log" { m = 1; next } $0 ~ /^pca\.log\.[0-9][0-9]?$/ { n = $0; sub(/.*\./, "", n); print n + 0, $0 } END { if (m) print -1, "pca.log" }' | sort -k1,1nr | awk '{print $2}'; }

# ---------- fixtures ----------
FX_PASS=0 FX_FAIL=0
eq() { # name expected actual
  if [ "$2" = "$3" ]; then FX_PASS=$((FX_PASS + 1)); else FX_FAIL=$((FX_FAIL + 1)); echo "  FIXTURE FAILED: $1 (want '$2', got '$3')"; fi
}
fxlogs() { # size_of_pca.log.1_read size_of_pca.log_read rc_seg1 rc_main : mocked adb (no device); prints "TRIP_LOGS=n STATUS:label ..."
  local out
  TMP="$(mktemp -d "${TMPDIR:-/tmp}/pic-fx.XXXXXX")"; PHONE_PKG=com.x; TRIP_LOGS=0; FX_R1="$1"; FX_R2="$2"; FX_C1="$3"; FX_C2="$4"
  rc_shell() { REMOTE_RC=0; REMOTE_OUT="$(printf "$FXL_LS")"; return 0; }
  A() { case "$*" in *files/logs/pca.log.1) head -c "$FX_R1" < /dev/zero | tr '\0' 'x'; return "$FX_C1" ;; *files/logs/pca.log) head -c "$FX_R2" < /dev/zero | tr '\0' 'x'; return "$FX_C2" ;; esac; }
  res() { printf '%s:%s\n' "$1" "$2"; }
  check_trip_logs s > "$TMP/o" 2>&1; out="$(tr '\n' ' ' < "$TMP/o" | sed 's/ *$//')"
  printf 'TRIP_LOGS=%s %s' "$TRIP_LOGS" "$out"
  rm -rf "$TMP"
}
fxlogs_content() { # the content checks after an incomplete read (TRIP_LOGS stays 0)
  fxlogs "$1" "$2" 0 0 >/dev/null
  TMP="$(mktemp -d "${TMPDIR:-/tmp}/pic-fx.XXXXXX")"; res() { printf '%s:%s\n' "$1" "$2"; }
  check_trip_log_content | tr '\n' ' ' | sed 's/ *$//'
  rm -rf "$TMP"
}
run_fixtures() {
  ADB=/bin/echo   # adb_safe allow-list fixtures must never reach a real adb
  local D1 D2 D3 P
  # --- call state (7 + newer formats)
  eq "call: idle slot0" idle "$(printf 'mCallState=0\n' | call_class)"
  eq "call: two slots idle" idle "$(printf '  mCallState=0\n  mCallState=0\n' | call_class)"
  eq "call: empty lists ignored" idle "$(printf '  mCallState=0\n  mCallStateLists={}\n  mCallState=0\n' | call_class)"
  eq "call: only empty lists = unknown" unknown "$(printf '  mCallStateLists={}\n' | call_class)"
  eq "call: ringing" active "$(printf 'mCallState=1\n' | call_class)"
  eq "call: slot 2 offhook" active "$(printf 'mCallState=0\nmCallState=2\n' | call_class)"
  eq "call: junk value unknown" unknown "$(printf 'mCallState=0junk\n' | call_class)"
  eq "call: none at all unknown" unknown "$(printf 'nothing\n' | call_class)"
  eq "call: non-empty list unknown" unknown "$(printf 'mCallState=0\nmCallStateLists={0=2}\n' | call_class)"
  eq "call: CRLF-free empty" unknown "$(printf '' | call_class)"
  # --- permissions
  P='  User 0: ceDataInode=1 installed=true
    install permissions:
      android.permission.INTERNET: granted=true
    runtime permissions:
      android.permission.RECORD_AUDIO: granted=true, flags=[ USER_SET]
      android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED]
      android.permission.BLUETOOTH_CONNECT: granted=true
      android.permission.ACCESS_FINE_LOCATION: granted=true, flags=[ ]
    enabled=0'
  eq "perm: counts" "3 1" "$(printf '%s\n' "$P" | perm_parse | perm_counts)"
  eq "perm: install perms not counted" absent "$(printf '%s\n' "$P" | perm_parse | perm_state android.permission.INTERNET)"
  eq "perm: notifications denied" denied "$(printf '%s\n' "$P" | perm_parse | perm_state android.permission.POST_NOTIFICATIONS)"
  eq "perm: bt connect granted" granted "$(printf '%s\n' "$P" | perm_parse | perm_state android.permission.BLUETOOTH_CONNECT)"
  eq "perm: location granted" granted "$(printf '%s\n' "$P" | perm_parse | perm_state android.permission.ACCESS_FINE_LOCATION)"
  eq "perm: absent permission" absent "$(printf '%s\n' "$P" | perm_parse | perm_state android.permission.CAMERA)"
  eq "perm: denied names" "POST_NOTIFICATIONS" "$(printf '%s\n' "$P" | perm_parse | perm_denied_names)"
  eq "perm: empty input" "0 0" "$(printf '' | perm_parse | perm_counts)"
  eq "perm: no runtime block" "0 0" "$(printf '    install permissions:\n      a.b: granted=true\n' | perm_parse | perm_counts)"
  eq "perm: two users dedupe" "1 0" "$(printf '    runtime permissions:\n      a.b.C: granted=true\n    runtime permissions:\n      a.b.C: granted=true\n' | perm_parse | perm_counts)"
  eq "perm: block ends at blank-indent line" "1 0" "$(printf '    runtime permissions:\n      a.b.C: granted=true\n    other:\n      x.y.Z: granted=false\n' | perm_parse | perm_counts)"
  # --- deviceidle
  eq "idle: user whitelisted" yes "$(printf 'system-excidle,com.android.x,10001\nuser,com.rokid.context,10234\n' | idle_whitelisted com.rokid.context)"
  eq "idle: absent" no "$(printf 'user,com.other,10234\n' | idle_whitelisted com.rokid.context)"
  eq "idle: glasses pkg is not phone pkg" no "$(printf 'user,com.rokid.context.glasses,10234\n' | idle_whitelisted com.rokid.context)"
  eq "idle: glasses match" yes "$(printf 'user,com.rokid.context.glasses,10234\n' | idle_whitelisted com.rokid.context.glasses)"
  eq "idle: dot is literal" no "$(printf 'user,comXrokidXcontext,10234\n' | idle_whitelisted com.rokid.context)"
  eq "idle: empty" no "$(printf '' | idle_whitelisted com.rokid.context)"
  eq "idle: CRLF tolerant" yes "$(printf 'user,com.rokid.context,10234\r\n' | tr -d '\r' | idle_whitelisted com.rokid.context)"
  # --- clock skew
  eq "skew: zero" 0 "$(skew_secs 1000 1000 1000)"
  eq "skew: device ahead 130" 130 "$(skew_secs 1000 1130 1000)"
  eq "skew: device behind 130" 130 "$(skew_secs 1000 870 1000)"
  eq "skew: midpoint" 0 "$(skew_secs 1000 1001 1002)"
  eq "skew: nonnumeric" bad "$(skew_secs 1000 abc 1000)"
  eq "skew: empty device" bad "$(skew_secs 1000 '' 1000)"
  eq "skew: exactly 120 is not above" 120 "$(skew_secs 5000 5120 5000)"
  # --- prefs XML
  eq "prefs: true" ON "$(printf '<map>\n    <boolean name="forget_v2" value="true" />\n</map>\n' | prefs_state forget_v2)"
  eq "prefs: false" off "$(printf '<map><boolean name="forget_v2" value="false" /></map>\n' | prefs_state forget_v2)"
  eq "prefs: absent" off "$(printf '<map><boolean name="other" value="true" /></map>\n' | prefs_state forget_v2)"
  eq "prefs: empty map" off "$(printf "<?xml version='1.0'?>\n<map />\n" | prefs_state forget_v2)"
  eq "prefs: other key true does not leak" off "$(printf '<map><boolean name="forget_v2x" value="true" /></map>\n' | prefs_state forget_v2)"
  eq "prefs: string typed is other" other "$(printf '<map><string name="forget_v2">true</string></map>\n' | prefs_state forget_v2)"
  eq "prefs: garbage value other" other "$(printf '<map><boolean name="k" value="yes" /></map>\n' | prefs_state k)"
  eq "prefs: second flag independent" ON "$(printf '<map><boolean name="a" value="false" />\n<boolean name="multilingual_ask" value="true" /></map>\n' | prefs_state multilingual_ask)"
  eq "prefs: attr order value first" ON "$(printf '<map><boolean value="true" name="k" /></map>\n' | prefs_state k)"
  eq "prefs: deliver_hud true" ON "$(printf '<map><boolean name="deliver_hud" value="true" /></map>\n' | prefs_state deliver_hud)"
  # --- battery / storage
  eq "batt: 80 charging" "80 yes" "$(printf 'Current Battery Service state:\n  AC powered: false\n  USB powered: true\n  status: 2\n  level: 80\n' | battery_parse)"
  eq "batt: 30 not charging" "30 no" "$(printf '  AC powered: false\n  USB powered: false\n  status: 3\n  level: 30\n' | battery_parse)"
  eq "batt: garbage" bad "$(printf 'nothing\n' | battery_parse)"
  eq "df: kb" 4000000 "$(printf 'Filesystem 1K-blocks Used Available Use%% Mounted\n/dev/x 100 50 4000000 2%% /data\n' | df_free_kb)"
  eq "df: garbage" bad "$(printf 'oops\n' | df_free_kb)"
  # --- versions / hashes / badging
  eq "badging: vc" 110 "$(printf "package: name='com.rokid.context' versionCode='110' versionName='1.1.0' platformBuildVersionName='14'\n" | badging_vc)"
  eq "badging: vn" 1.1.0 "$(printf "package: name='com.rokid.context' versionCode='110' versionName='1.1.0' platformBuildVersionName='14'\n" | badging_vn)"
  eq "dump: vc" 110 "$(printf '    versionCode=110 minSdk=29 targetSdk=34\r\n' | dump_vc)"
  eq "dump: vn" 1.1.0 "$(printf '    versionName=1.1.0\r\n' | dump_vn)"
  eq "cmp3: equal" PASS "$(cmp3 abc abc)"
  eq "cmp3: differ" FAIL "$(cmp3 abc abd)"
  eq "cmp3: empty is unknown" UNKNOWN "$(cmp3 '' abc)"
  eq "cmp3: both empty unknown" UNKNOWN "$(cmp3 '' '')"
  eq "pm path: base" /data/app/x/base.apk "$(printf 'package:/data/app/x/split_a.apk\r\npackage:/data/app/x/base.apk\n' | pm_base_path)"
  eq "pm path: none" "" "$(printf 'garbage\n' | pm_base_path)"
  # --- identity
  eq "bad_idval: empty" 0 "$(bad_idval '' && echo 0 || echo 1)"
  eq "bad_idval: zeros" 0 "$(bad_idval 0000000 && echo 0 || echo 1)"
  eq "bad_idval: ok" 1 "$(bad_idval ABC123 && echo 0 || echo 1)"
  D1="S1|aid1|Pixel|fp1"; D2="S1|aid2|Rokid|fp2"; D3="S3|aid3|Rokid|fp2"
  eq "alias: same serial" 0 "$(id_alias "$D1" "$D2" && echo 0 || echo 1)"
  eq "alias: distinct" 1 "$(id_alias "$D1" "$D3" && echo 0 || echo 1)"
  eq "alias: same android_id" 0 "$(id_alias "S9|aid3|a|b" "$D3" && echo 0 || echo 1)"
  # --- log classification (threadtime, counts only)
  L='11-19 10:00:00.000  4242  4242 E AndroidRuntime: FATAL EXCEPTION: main
11-19 10:00:00.001  4242  4242 E AndroidRuntime: Process: com.rokid.context, PID: 4242
11-19 10:00:01.000  4242  4300 E SQLiteLog: SQLiteException: no such table: x
11-19 10:00:02.000   900   950 E ActivityManager: ANR in com.rokid.context (com.rokid.context/.Main)
11-19 10:00:03.000   111   111 I Other: all fine
11-19 10:00:04.000  4242  4242 W Room: Migration didn_t properly handle foo'
  eq "log: mixed" "1 1 2 5" "$(printf '%s\n' "$L" | log_counts 4242 com.rokid.context)"
  eq "log: clean" "0 0 0 1" "$(printf '11-19 10:00:00.000  4242  4242 I App: started\n11-19 10:00:00.000  5  5 I X: hi\n' | log_counts 4242 com.rokid.context)"
  eq "log: other pkg crash ignored" "0 0 0 0" "$(printf '11-19 10:00:00.000  77  77 E AndroidRuntime: Process: com.rokid.context.glasses, PID: 77\n' | log_counts 4242 com.rokid.context)"
  eq "log: glasses crash counted for glasses" "1 0 0 1" "$(printf '11-19 10:00:00.000  77  77 E AndroidRuntime: Process: com.rokid.context.glasses, PID: 77\n' | log_counts 77 com.rokid.context.glasses)"
  eq "log: other-pid SQLite w/o package ignored" "0 0 0 0" "$(printf '11-19 10:00:00.000  9  9 E X: SQLiteException boom\n' | log_counts 4242 com.rokid.context)"
  eq "log: benign sqlcipher_mlock not an error" "0 0 0 1" "$(printf '11-19 10:00:00.000  4242  4242 W sqlcipher_mlock: x\n' | log_counts 4242 com.rokid.context)"
  eq "log: separators ignored" "0 0 0 0" "$(printf -- '--------- beginning of main\n' | log_counts 4242 com.rokid.context)"
  eq "log: ANR app pid text" "0 1 0 1" "$(printf '11-19 10:00:00.000  4242  4242 E ActivityManager: Application Not Responding\n' | log_counts 4242 com.rokid.context)"
  eq "log: not a database" "0 0 1 1" "$(printf '11-19 10:00:00.000  4242  4242 E X: file is not a database (code 26)\n' | log_counts 4242 com.rokid.context)"
  eq "log: code 14" "0 0 1 1" "$(printf '11-19 10:00:00.000  4242  4242 E X: failed (code 14)\n' | log_counts 4242 com.rokid.context)"
  eq "log: empty input" "0 0 0 0" "$(printf '' | log_counts 4242 com.rokid.context)"
  # --- summary / exit code
  eq "exit: no fail" 0 "$(exit_code_for 3 2 0 1)"
  eq "exit: one fail" 1 "$(exit_code_for 3 0 1 0)"
  eq "exit: unknowns only" 0 "$(exit_code_for 0 0 0 9)"
  eq "exit: garbage fails closed" 1 "$(exit_code_for 1 1 x 1)"
  eq "exit: empty fails closed" 1 "$(exit_code_for 1 1 '' 1)"
  # --- adb_safe allow-list (ADB=/bin/echo: an allowed call prints, a refused one returns 97)
  eq "safe: getprop allowed" "-s S shell getprop x" "$(adb_safe S shell getprop x 2>/dev/null)"
  eq "safe: install refused" 97 "$(adb_safe S install -r a.apk >/dev/null 2>&1; echo $?)"
  eq "safe: uninstall refused" 97 "$(adb_safe S shell pm uninstall com.x >/dev/null 2>&1; echo $?)"
  eq "safe: pm clear refused" 97 "$(adb_safe S shell pm clear com.x >/dev/null 2>&1; echo $?)"
  eq "safe: force-stop refused" 97 "$(adb_safe S shell am force-stop com.x >/dev/null 2>&1; echo $?)"
  eq "safe: settings put refused" 97 "$(adb_safe S shell settings put global x 1 >/dev/null 2>&1; echo $?)"
  eq "safe: input refused" 97 "$(adb_safe S shell input keyevent 4 >/dev/null 2>&1; echo $?)"
  eq "safe: pm grant refused" 97 "$(adb_safe S shell pm grant com.x a.b >/dev/null 2>&1; echo $?)"
  eq "safe: logcat -c refused" 97 "$(adb_safe S logcat -c >/dev/null 2>&1; echo $?)"
  eq "safe: logcat -d allowed" "-s S logcat -d -b main" "$(adb_safe S logcat -d -b main 2>/dev/null)"
  eq "safe: push refused" 97 "$(adb_safe S push a /sdcard/ >/dev/null 2>&1; echo $?)"
  eq "safe: redirect refused" 97 "$(adb_safe S shell 'echo x > /sdcard/a' >/dev/null 2>&1; echo $?)"
  eq "safe: run-as ls refused" 97 "$(adb_safe S exec-out run-as com.x ls >/dev/null 2>&1; echo $?)"
  eq "safe: run-as cat allowed" "-s S exec-out run-as com.x cat shared_prefs/a.xml" "$(adb_safe S exec-out run-as com.x cat shared_prefs/a.xml 2>/dev/null)"
  eq "safe: reboot refused" 97 "$(adb_safe S reboot >/dev/null 2>&1; echo $?)"
  eq "safe: monkey refused" 97 "$(adb_safe S shell monkey -p com.x 1 >/dev/null 2>&1; echo $?)"
  # --- trip-build checks (new): log segments, detailed-log inference, forbidden shapes, PcData, prefs names, privacy, place attempts, pins, db mode
  local LS1 LSB L1 L2 L3 L4 L5 PIN PX SEC=Zq9SECRET
  LS1='total 2048
-rw------- 1 u0_a145 u0_a145 3000000 2026-11-20 10:00 pca.log
-rw------- 1 u0_a145 u0_a145 3000000 2026-11-20 09:00 pca.log.1
-rw------- 1 u0_a145 u0_a145 2999999 2026-11-20 08:00 pca.log.2
-rw------- 1 u0_a145 u0_a145 99 2026-11-20 08:00 pca.log.bak
-rw------- 1 u0_a145 u0_a145 99 2026-11-20 08:00 other.txt'
  eq "seg: 3 segments counted, bak/other ignored" "3 8999999 2 1" "$(printf '%s\n' "$LS1" | seg_scan)"
  eq "seg: no pca.log" "1 5 0 0" "$(printf -- '-rw------- 1 u u 5 2026-11-20 10:00 pca.log.1\n' | seg_scan)"
  eq "seg: empty" "0 0 0 0" "$(printf '' | seg_scan)"
  eq "seg: garbled size ignored" "0 0 1 0" "$(printf -- '-rw------- 1 u u 12x 2026-11-20 10:00 pca.log\n' | seg_scan)"
  eq "seg: spaced name ignored" "0 0 1 0" "$(printf -- '-rw------- 1 u u 5 2026-11-20 10:00 pca.log extra\n' | seg_scan)"
  eq "seg: error text ignored" "0 0 1 0" "$(printf 'run-as: package not debuggable\n' | seg_scan)"
  eq "seg: directory not counted" "0 0 1 0" "$(printf 'drwx------ 1 u u 4096 2026-11-20 10:00 pca.log\n' | seg_scan)"
  eq "seg order: oldest first" "pca.log.10 pca.log.2 pca.log.1 pca.log" "$(printf 'pca.log\npca.log.1\npca.log.10\npca.log.2\nzzz\npca.log.x\n' | seg_order | tr '\n' ' ' | sed 's/ $//')"
  eq "seg order: empty" "" "$(printf '' | seg_order)"
  L1='11-20 08:00:00.000 I PcDiag app start cold process sdk=34 uptimeS=3 tzOffsetMin=330
11-20 08:30:00.000 I PcDiag heartbeat uptimeS=1800 heapMb=40
11-20 09:30:00.000 I PcDiag heartbeat uptimeS=5400 heapMb=41
11-20 10:00:00.000 I PcDiag battery bucket=8'
  eq "diag: heartbeats ON" "Y 2 120 30" "$(printf '%s\n' "$L1" | diag_state)"
  eq "diag: no start milestone" "N 0 - -" "$(printf '11-20 08:30:00.000 I PcDiag heartbeat x\n' | diag_state)"
  eq "diag: empty" "N 0 - -" "$(printf '' | diag_state)"
  eq "diag: no heartbeat, short run" "Y 0 30 -" "$(printf '11-20 08:00:00.000 I PcDiag app start cold\n11-20 08:30:00.000 I Other x\n' | diag_state)"
  eq "diag: no heartbeat, long run" "Y 0 150 -" "$(printf '11-20 08:00:00.000 I PcDiag app start cold\n11-20 10:30:00.000 I Other x\n' | diag_state)"
  eq "diag: last start wins, old heartbeat dropped" "Y 0 10 -" "$(printf '11-20 07:00:00.000 I PcDiag app start cold\n11-20 07:30:00.000 I PcDiag heartbeat x\n11-20 08:00:00.000 I PcDiag app start cold\n11-20 08:10:00.000 I X y\n' | diag_state)"
  eq "diag: garbled lines skipped" "Y 1 60 60" "$(printf 'garbage\n\377\376\n11-20 08:00:00.000 I PcDiag app start cold\nxx-yy 99:99 I PcDiag heartbeat\n11-20 08:00:30.000 I PcDiag heartbeat u\n11-20 09:00:30.000 I X y\n' | diag_state)"
  eq "diag: new year wrap" "Y 0 60 -" "$(printf '12-31 23:30:00.000 I PcDiag app start cold\n01-01 00:30:00.000 I X y\n' | diag_state)"
  eq "forbidden: clean" "0 0 0" "$(printf '11-20 08:00:00.000 I PcDiag heapMb=12.5 batt=80\n11-20 08:00:01.000 I PlaceSampler place attempt outcome=NONE distB=12\n' | forbidden_counts)"
  eq "forbidden: lat lon pair" "1 0 0" "$(printf '11-20 08:00:00.000 I X at 17.385044, 78.486671 ok\n' | forbidden_counts)"
  eq "forbidden: negative pair slash" "1 0 0" "$(printf '11-20 08:00:00.000 I X -33.8688/151.2093\n' | forbidden_counts)"
  eq "forbidden: url either case" "0 2 0" "$(printf '11-20 08:00:00.000 I X https://a\n11-20 08:00:00.000 I X HTTP://B\n' | forbidden_counts)"
  eq "forbidden: class name PlaceHttpException is not a url" "0 0 0" "$(printf '11-20 08:00:00.000 I PlaceSeed cell failed (PlaceHttpException)\n11-20 08:00:00.000 I X HTTP/1.1 200\n11-20 08:00:00.000 I X httpcode=429\n' | forbidden_counts)"
  eq "forbidden: at sign" "0 0 1" "$(printf '11-20 08:00:00.000 I X a@b\n' | forbidden_counts)"
  eq "forbidden: timestamp alone is not a pair" "0 0 0" "$(printf '11-20 08:00:00.123 I X 10.500 only\n' | forbidden_counts)"
  eq "forbidden: empty" "0 0 0" "$(printf '' | forbidden_counts)"
  eq "forbidden: output is digits only, never the line" "0 1 0" "$(printf '11-20 08:00:00.000 I X http://%s.example\n' "$SEC" | forbidden_counts | sed "s/$SEC/LEAK/")"
  L2='11-20 08:00:00.000 I PcData blocked kind=PHOTO what=upload reason=roaming
11-20 08:00:01.000 I PcData blocked kind=PHOTO what=upload reason=roaming
11-20 08:00:02.000 I PcData blocked kind=SMALL what=x reason=wifi_only
11-20 08:00:03.000 I PcData net cost=ROAMING
11-20 08:00:04.000 I PcData blocked kind=PHOTO what=x reason=Zq9SECRET_host'
  eq "pcdata: counts by reason, unknown token is other" "other 1 roaming 2 wifi_only 1" "$(printf '%s\n' "$L2" | pcdata_counts | tr '\n' ' ' | sed 's/ $//')"
  eq "pcdata: secret token never echoed" "0" "$(printf '%s\n' "$L2" | pcdata_counts | grep -c "$SEC")"
  eq "pcdata: last cost" ROAMING "$(printf '%s\n' "$L2" | pcdata_last_cost)"
  eq "pcdata: cost garbled is other" other "$(printf '11-20 08:00:03.000 I PcData net cost=%s\n' "$SEC" | pcdata_last_cost)"
  eq "pcdata: no lines" none "$(printf 'x\n' | pcdata_last_cost)"
  eq "pcdata: empty counts" "" "$(printf '' | pcdata_counts)"
  eq "pcdata: reason missing is other" "other 1" "$(printf '11-20 08:00:00.000 I PcData blocked kind=PHOTO\n' | pcdata_counts)"
  eq "prefs names: all present (plain ls)" "place_pins=Y data_saver=Y ai_backend=Y ai_backend_init=Y online_map=Y" "$(printf 'place_pins.xml\ndata_saver.xml\nai_backend.xml\nai_backend_init.xml\nonline_map.xml\n' | prefs_present)"
  eq "prefs names: some absent" "place_pins=Y data_saver=N ai_backend=N ai_backend_init=N online_map=N" "$(printf 'place_pins.xml\nother.xml\n' | prefs_present)"
  eq "prefs names: lookalikes do not count" "place_pins=N data_saver=N ai_backend=N ai_backend_init=N online_map=N" "$(printf 'place_pins.xml.bak\nxplace_pins.xml\nai_backend.xml.tmp\n' | prefs_present)"
  eq "prefs names: ls -l form" "place_pins=Y data_saver=N ai_backend=N ai_backend_init=N online_map=N" "$(printf -- '-rw------- 1 u u 5 2026-11-20 10:00 place_pins.xml\n' | prefs_present)"
  eq "prefs names: error text and empty" "place_pins=N data_saver=N ai_backend=N ai_backend_init=N online_map=N" "$(printf 'ls: shared_prefs: No such file\n' | prefs_present)"
  eq "prefs names: only fixed names are printed" "0" "$(printf 'Zq9SECRET_john.xml\nplace_pins.xml\n' | prefs_present | grep -c "$SEC")"
  PX='<map><string name="mode">OFFLINE_ONLY</string><boolean name="initialised" value="true" /></map>'
  eq "privacy: offline only" "mode=OFFLINE_ONLY reset=no" "$(privacy_state "$PX" '')"
  eq "privacy: auto" "mode=AUTO reset=no" "$(privacy_state '<map><string name="mode">AUTO</string></map>' '')"
  eq "privacy: fresh install (nothing)" "mode=missing reset=no" "$(privacy_state '' '')"
  eq "privacy: mode lost but sentinel initialised = RESET" "mode=missing reset=yes" "$(privacy_state '<map />' '<map><boolean name="initialised" value="true" /></map>')"
  eq "privacy: auto but sentinel last_choice offline = RESET" "mode=OFFLINE_ONLY reset=yes" "$(privacy_state '<map><string name="mode">AUTO</string></map>' '<map><string name="last_choice">OFFLINE_ONLY</string></map>')"
  eq "privacy: unknown value is garbled+RESET, text not echoed" "mode=garbled reset=yes" "$(privacy_state "<map><string name=\"mode\">$SEC</string></map>" '')"
  eq "privacy: lookalike key ignored" "mode=missing reset=no" "$(privacy_state '<map><string name="mode2">AUTO</string></map>' '')"
  eq "privacy: init flag in main file" "mode=missing reset=yes" "$(privacy_state '<map><boolean name="initialised" value="true" /></map>' '')"
  L3='11-20 08:00:00.000 I PlaceSampler place attempt outcome=MATCHED reason=ok chosen=wd:Q1
11-20 09:00:00.000 I PlaceSampler place attempt outcome=NONE reason=x
11-20 09:10:00.000 I PlaceSampler place attempt outcome=NONE reason=x
11-20 09:20:00.000 I PlaceSampler place attempt outcome=Zq9SECRET reason=x
11-20 09:30:00.000 I PlaceSampler place outcome=NONE
11-19 00:00:00.000 I PlaceSampler place attempt outcome=FAILED reason=old
11-20 10:00:00.000 I X y'
  eq "place: window 24h, outcomes in fixed order, garbled counted" "total=4 window=24 outcomes=MATCHED:1,NONE:2 garbled=1" "$(printf '%s\n' "$L3" | place_counts 24)"
  eq "place: window 1h" "total=3 window=1 outcomes=NONE:2 garbled=1" "$(printf '%s\n' "$L3" | place_counts 1)"
  eq "place: window 48h includes older" "total=5 window=48 outcomes=MATCHED:1,NONE:2,FAILED:1 garbled=1" "$(printf '%s\n' "$L3" | place_counts 48)"
  eq "place: none at all" "total=0 window=24 outcomes=none garbled=0" "$(printf '11-20 10:00:00.000 I X y\n' | place_counts 24)"
  eq "place: empty" "total=0 window=24 outcomes=none garbled=0" "$(printf '' | place_counts 24)"
  eq "place: secret outcome never echoed" "0" "$(printf '%s\n' "$L3" | place_counts 24 | grep -c "$SEC")"
  eq "place: hostile non-record line with the phrase ignored" "total=0 window=24 outcomes=none garbled=0" "$(printf 'place attempt outcome=NONE\n' | place_counts 24)"
  PIN='<map><string name="pins">[{&quot;id&quot;:&quot;u1&quot;,&quot;name&quot;:&quot;Zq9SECRET Cafe&quot;,&quot;lat&quot;:1.5,&quot;moment&quot;:&quot;abcd1234&quot;,&quot;at&quot;:5},{&quot;id&quot;:&quot;u2&quot;,&quot;name&quot;:&quot;B&quot;},{&quot;id&quot;:&quot;u3&quot;,&quot;moment&quot;:&quot;ffff0000&quot;}]</string><string name="armed">x</string></map>'
  eq "pins: total, with, without" "3 2 1" "$(printf '%s\n' "$PIN" | pins_counts)"
  eq "pins: names/ids never echoed" "0" "$(printf '%s\n' "$PIN" | pins_counts | grep -c -e "$SEC" -e u1)"
  eq "pins: no pins key" "0 0 0" "$(printf '<map />\n' | pins_counts)"
  eq "pins: empty list" "0 0 0" "$(printf '<map><string name="pins">[]</string></map>\n' | pins_counts)"
  eq "pins: garbled xml" bad "$(printf 'not xml %s <<<\n' "$SEC" | pins_counts)"
  eq "pins: garbled json" bad "$(printf '<map><string name="pins">[{oops %s</string></map>\n' "$SEC" | pins_counts)"
  eq "pins: not a list" bad "$(printf '<map><string name="pins">{"a":1}</string></map>\n' | pins_counts)"
  eq "pins: list of non-objects" bad "$(printf '<map><string name="pins">[1,2]</string></map>\n' | pins_counts)"
  eq "pins: null moment counts as without" "1 0 1" "$(printf '<map><string name="pins">[{"id":"a","moment":null}]</string></map>\n' | pins_counts)"
  eq "pins: empty input" bad "$(printf '' | pins_counts)"
  eq "table count: number" 12 "$(table_count_state 12)"
  eq "table count: zero" 0 "$(table_count_state 0)"
  eq "table count: empty" unreadable "$(table_count_state '')"
  eq "table count: error text" unreadable "$(table_count_state 'Error: no such table')"
  eq "expected db version" 18 "$EXPECT_DB_VERSION"
  eq "dbmode: plaintext" plaintext "$(db_mode3 'SQLite format 3' false)"
  eq "dbmode: plaintext header, flag true" inconsistent "$(db_mode3 'SQLite format 3' true)"
  eq "dbmode: plaintext header, no flag" inconsistent "$(db_mode3 'SQLite format 3' '')"
  eq "dbmode: encrypted header, flag false" inconsistent "$(db_mode3 'abcdefghijklmno' false)"
  eq "dbmode: encrypted header, flag true" encrypted "$(db_mode3 'abcdefghijklmno' true)"
  eq "dbmode: encrypted header, no flag" encrypted "$(db_mode3 'abcdefghijklmno' '')"
  eq "safe: run-as via shell ls -l is read-only and allowed" "-s S shell run-as com.x ls -l files/logs; echo __PCA_RC=\$?" "$(adb_safe S shell "run-as com.x ls -l files/logs; echo __PCA_RC=\$?" 2>/dev/null)"
  eq "safe: shell rm of a log still refused" 97 "$(adb_safe S shell 'run-as com.x rm files/logs/pca.log' >/dev/null 2>&1; echo $?)"
  # --- log segment reads: a failed or truncated read must never turn into PASS
  FXL_LS='total 9\n-rw------- 1 u u 40 2026-11-20 10:00 pca.log.1\n-rw------- 1 u u 40 2026-11-20 10:00 pca.log\n'
  eq "logs: complete read leaves TRIP_LOGS=1" "TRIP_LOGS=1 PASS:log segments" "$(fxlogs 40 40 0 0)"
  eq "logs: truncated oldest segment is UNKNOWN, not read" "TRIP_LOGS=0 PASS:log segments UNKNOWN:log read" "$(fxlogs 10 40 0 0)"
  eq "logs: truncated pca.log is UNKNOWN, not read" "TRIP_LOGS=0 PASS:log segments UNKNOWN:log read" "$(fxlogs 40 3 0 0)"
  eq "logs: failed adb read of a segment is UNKNOWN" "TRIP_LOGS=0 PASS:log segments UNKNOWN:log read" "$(fxlogs 40 40 1 0)"
  eq "logs: failed read of pca.log is UNKNOWN" "TRIP_LOGS=0 PASS:log segments UNKNOWN:log read" "$(fxlogs 40 40 0 1)"
  eq "logs: a read longer than ls (active log grew) is fine" "TRIP_LOGS=1 PASS:log segments" "$(fxlogs 60 50 0 0)"
  eq "logs: incomplete read makes every log-derived check UNKNOWN" "UNKNOWN:detailed log UNKNOWN:log forbidden shapes UNKNOWN:data saver (PcData) UNKNOWN:place attempts" "$(fxlogs_content 10 40)"
  say "fixtures: $FX_PASS passed, $FX_FAIL failed"
  [ "$FX_FAIL" = 0 ]
}

# ---------- device helpers ----------
dev_prop() { local s="$1" v; shift; v="$(A "$s" shell "$@" 2>/dev/null | tr -d '\r' | tr '\n' ' ' | sed 's/^ *//;s/ *$//')" || return 1; printf '%s\n' "$v"; }
dev_ident() {
  local s="$1" ser aid model fp
  ser="$(dev_prop "$s" getprop ro.serialno)" || ser=""
  if bad_idval "$ser"; then ser="$(dev_prop "$s" getprop ro.boot.serialno)" || ser=""; fi
  aid="$(dev_prop "$s" settings get secure android_id)" || aid=""
  model="$(dev_prop "$s" getprop ro.product.model)" || model=""
  fp="$(dev_prop "$s" getprop ro.build.fingerprint)" || fp=""
  if bad_idval "$ser"; then echo "no usable hardware serial"; return 1; fi
  if bad_idval "$aid"; then echo "no usable android_id"; return 1; fi
  if bad_idval "$model" || bad_idval "$fp"; then echo "no usable model/fingerprint"; return 1; fi
  printf '%s|%s|%s|%s\n' "$ser" "$aid" "$model" "$fp"
}
is_watch() { local c f; c="$(dev_prop "$1" getprop ro.build.characteristics)" || c=""; f="$(dev_prop "$1" pm list features)" || f=""
  case "$c" in *watch*) return 0 ;; esac; case "$f" in *android.hardware.type.watch*) return 0 ;; esac; return 1; }
apk_attr() { # which apk -> value
  local w="$1" f="$2"
  case "$w" in
    vc) if [ -n "$AAPT" ]; then "$AAPT" dump badging "$f" 2>/dev/null | badging_vc; elif [ -n "$APKANALYZER" ]; then "$APKANALYZER" manifest version-code "$f" 2>/dev/null | sed -n 1p; fi ;;
    vn) if [ -n "$AAPT" ]; then "$AAPT" dump badging "$f" 2>/dev/null | badging_vn; elif [ -n "$APKANALYZER" ]; then "$APKANALYZER" manifest version-name "$f" 2>/dev/null | sed -n 1p; fi ;;
  esac
}
apk_cert() { local out d; [ -n "$APKSIGNER" ] || return 1
  out="$("$APKSIGNER" verify --print-certs "$1" 2>/dev/null)" || return 1
  d="$(printf '%s\n' "$out" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | sed -n 1p)"
  [[ "$d" =~ ^[0-9a-f]{64}$ ]] || return 1; printf '%s\n' "$d"; }

# ---------- checks ----------
installed_ok=0
check_version_hash() { # serial pkg apk label
  local s="$1" pkg="$2" apk="$3" label="$4" dp ivc ivn avc avn path f want have cw ch st
  dp="$(A "$s" shell dumpsys package "$pkg" 2>/dev/null | tr -d '\r')" || dp=""
  ivc="$(printf '%s\n' "$dp" | dump_vc)"; ivn="$(printf '%s\n' "$dp" | dump_vn)"
  if [ -z "$ivc" ]; then res FAIL "$label installed" "$pkg not installed (no versionCode in dumpsys package)"; return 1; fi
  res PASS "$label installed" "$pkg versionName=$ivn versionCode=$ivc"
  if [ ! -f "$apk" ]; then res UNKNOWN "$label version vs apk" "staged APK missing: $(basename "$apk")"; res UNKNOWN "$label sha256 vs apk" "staged APK missing"; res UNKNOWN "$label signature" "staged APK missing"; return 0; fi
  avc="$(apk_attr vc "$apk")"; avn="$(apk_attr vn "$apk")"
  if [ -z "$avc" ]; then res UNKNOWN "$label version vs apk" "no aapt/apkanalyzer (cannot read the staged APK version)"
  else
    st="$(cmp3 "$ivc" "$avc")"
    res "$st" "$label version vs apk" "installed code=$ivc name=$ivn, staged code=$avc name=${avn:-?}"
    if [ "$st" = PASS ] && [ -n "$avn" ] && [ "$ivn" != "$avn" ]; then res FAIL "$label versionName" "installed '$ivn' != staged '$avn'"; fi
  fi
  path="$(A "$s" shell pm path "$pkg" 2>/dev/null | pm_base_path)" || path=""
  want="$(sha256_of "$apk")" || want=""
  if [ -z "$path" ] || [ -z "$want" ] || [ -z "$TMP" ]; then res UNKNOWN "$label sha256 vs apk" "installed path or staged hash unavailable"; res UNKNOWN "$label signature" "installed path unavailable"; return 0; fi
  f="$TMP/installed-$label.apk"
  if ! A "$s" exec-out cat "$path" > "$f" 2>/dev/null || [ ! -s "$f" ]; then res UNKNOWN "$label sha256 vs apk" "could not read the installed base APK"; res UNKNOWN "$label signature" "could not read the installed base APK"; return 0; fi
  have="$(sha256_of "$f")" || have=""
  st="$(cmp3 "$have" "$want")"
  res "$st" "$label sha256 vs apk" "installed ${have:0:12}.. staged ${want:0:12}.."
  if [ -z "$APKSIGNER" ]; then res UNKNOWN "$label signature" "apksigner not found"
  else cw="$(apk_cert "$apk")" || cw=""; ch="$(apk_cert "$f")" || ch=""
    st="$(cmp3 "$ch" "$cw")"; res "$st" "$label signature" "cert installed ${ch:0:12}.. staged ${cw:0:12}.."; fi
  rm -f "$f"
  return 0
}

check_perms() { # serial pkg label
  local s="$1" pkg="$2" label="$3" dp pp c g d names
  dp="$(A "$s" shell dumpsys package "$pkg" 2>/dev/null | tr -d '\r')" || dp=""
  pp="$(printf '%s\n' "$dp" | perm_parse)"
  if [ -z "$pp" ]; then res UNKNOWN "$label runtime perms" "no 'runtime permissions:' block in dumpsys package"; return; fi
  c="$(printf '%s\n' "$pp" | perm_counts)"; g="${c% *}"; d="${c#* }"
  if [ "$d" = 0 ]; then res PASS "$label runtime perms" "$g granted, 0 denied"
  else names="$(printf '%s\n' "$pp" | perm_denied_names)"; res FAIL "$label runtime perms" "$g granted, $d denied: $names"; fi
  if [ "$label" = phone ]; then
    res PASS "$label state POST_NOTIFICATIONS" "$(printf '%s\n' "$pp" | perm_state android.permission.POST_NOTIFICATIONS) (report only)"
    res PASS "$label state BLUETOOTH_CONNECT" "$(printf '%s\n' "$pp" | perm_state android.permission.BLUETOOTH_CONNECT) (report only)"
    res PASS "$label state location" "fine=$(printf '%s\n' "$pp" | perm_state android.permission.ACCESS_FINE_LOCATION) coarse=$(printf '%s\n' "$pp" | perm_state android.permission.ACCESS_COARSE_LOCATION) (report only)"
  fi
}

check_idle() { # serial pkg label
  local w
  w="$(A "$1" shell dumpsys deviceidle whitelist 2>/dev/null | tr -d '\r')" || w=""
  if [ -z "$w" ]; then res UNKNOWN "$3 battery exemption" "dumpsys deviceidle whitelist returned nothing"; return; fi
  if [ "$(printf '%s\n' "$w" | idle_whitelisted "$2")" = yes ]; then res PASS "$3 battery exemption" "$2 is on the deviceidle whitelist"
  else res WARN "$3 battery exemption" "$2 NOT on the deviceidle whitelist (Settings > battery > unrestricted)"; fi
}

check_process() { # serial pkg label -> sets LAST_PID
  local s="$1" pkg="$2" label="$3" p0 p1
  LAST_PID=""
  p0="$(A "$s" shell pidof "$pkg" 2>/dev/null | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')" || p0=""
  if [ -z "$p0" ]; then res FAIL "$label process" "not running (pidof empty)"; return; fi
  sleep "$PIDWAIT"
  p1="$(A "$s" shell pidof "$pkg" 2>/dev/null | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')" || p1=""
  case "$p0$p1" in *[!0-9]*) res UNKNOWN "$label process" "pidof returned several/odd pids: '$p0' / '$p1'"; return ;; esac
  if [ -z "$p1" ]; then res FAIL "$label process" "pid $p0 gone after ${PIDWAIT}s"
  elif [ "$p0" = "$p1" ]; then res PASS "$label process" "pid $p0 stable over ${PIDWAIT}s"; LAST_PID="$p1"
  else res FAIL "$label process" "pid changed $p0 -> $p1 in ${PIDWAIT}s (restart / crash loop)"; fi
}

check_logs() { # serial pkg pid label
  local s="$1" pkg="$2" pid="$3" label="$4" f="$TMP/log-$4.txt" bf n c
  if [ -z "$pid" ]; then res UNKNOWN "$label log health" "no stable pid to scan"; return; fi
  : > "$f"
  for bf in main system crash; do
    A "$s" logcat -d -b "$bf" -v threadtime >> "$f" 2>/dev/null || { res UNKNOWN "$label log health" "logcat buffer $bf unreadable"; rm -f "$f"; return; }
  done
  n="$(grep -c '^[0-9][0-9]-[0-9][0-9] ' "$f" 2>/dev/null)" || n=0
  if [ "${n:-0}" -lt 5 ]; then res UNKNOWN "$label log health" "only $n log lines retained: cannot judge"; rm -f "$f"; return; fi
  c="$(log_counts "$pid" "$pkg" < "$f")"; rm -f "$f"
  set -- $c
  if [ "$1" = 0 ] && [ "$2" = 0 ] && [ "$3" = 0 ]; then res PASS "$label log health" "fatal=0 anr=0 db_errors=0 (relevant lines=$4)"
  else res FAIL "$label log health" "fatal=$1 anr=$2 db_errors=$3 (relevant lines=$4)"; fi
}

check_safety() { # phone only
  local s="$1" id_rc xml rc k file key st all_ok=1 bad="" unk=0 cnt=0
  rc_shell "$s" "run-as $PHONE_PKG id" || { res UNKNOWN "safety defaults" "run-as probe failed (adb)"; return; }
  if [ "$REMOTE_RC" != 0 ]; then res UNKNOWN "safety defaults" "not debuggable (run-as unavailable): cannot read prefs"; return; fi
  for k in pc_dev:forget_v2 pc_dev:multilingual_ask proactive_settings:deliver_phone proactive_settings:deliver_hud proactive_settings:deliver_nexus; do
    file="${k%%:*}"; key="${k#*:}"; cnt=$((cnt + 1))
    rc_shell "$s" "run-as $PHONE_PKG cat shared_prefs/$file.xml" || { unk=1; continue; }
    if [ "$REMOTE_RC" != 0 ]; then
      case "$REMOTE_OUT" in *"No such file"*|*"o such file"*) st=off ;; *) st=unknown ;; esac
    else st="$(printf '%s\n' "$REMOTE_OUT" | prefs_state "$key")"; fi
    case "$st" in off) ;; ON) all_ok=0; bad="$bad $file:$key=ON" ;; *) unk=1; bad="$bad $file:$key=$st" ;; esac
  done
  if [ "$all_ok" = 0 ]; then res FAIL "safety defaults" "switch(es) ON:$bad"
  elif [ "$unk" = 1 ]; then res UNKNOWN "safety defaults" "could not read every flag:$bad"
  else res PASS "safety defaults" "$cnt flags (forget_v2, multilingual_ask, deliver phone/hud/nexus) absent or false"; fi
}

check_system() { # phone
  local s="$1" t0 t1 dv sk cs b bl ch fr ap bt wf
  cs="$(A "$s" shell dumpsys telephony.registry 2>/dev/null | tr -d '\r' | call_class)" || cs=unknown
  case "$cs" in idle) res PASS "call state" "every mCallState is 0" ;; active) res FAIL "call state" "a call is active (mCallState != 0)" ;; *) res UNKNOWN "call state" "mCallState missing or unparseable" ;; esac
  t0="$(date +%s)"; dv="$(A "$s" shell date +%s 2>/dev/null | tr -d '\r[:space:]')" || dv=""; t1="$(date +%s)"
  sk="$(skew_secs "$t0" "$dv" "$t1")"
  if [ "$sk" = bad ]; then res UNKNOWN "clock skew" "device clock unreadable"
  elif [ "$sk" -gt "$SKEW_WARN" ]; then res WARN "clock skew" "${sk}s vs host (> ${SKEW_WARN}s: quiet-hours deadline would be off)"
  else res PASS "clock skew" "${sk}s vs host"; fi
  ap="$(dev_prop "$s" settings get global airplane_mode_on)" || ap=""; bt="$(dev_prop "$s" settings get global bluetooth_on)" || bt=""; wf="$(dev_prop "$s" settings get global wifi_on)" || wf=""
  case "$ap" in 0) res PASS "airplane mode" "off" ;; 1) res WARN "airplane mode" "ON" ;; *) res UNKNOWN "airplane mode" "unreadable ('$ap')" ;; esac
  case "$bt" in 1) res PASS "bluetooth" "on" ;; 0) res WARN "bluetooth" "OFF (glasses link needs it)" ;; *) res UNKNOWN "bluetooth" "unreadable ('$bt')" ;; esac
  case "$wf" in 1) res PASS "wifi" "on" ;; 0) res WARN "wifi" "off" ;; *) res UNKNOWN "wifi" "unreadable ('$wf')" ;; esac
  b="$(A "$s" shell dumpsys battery 2>/dev/null | tr -d '\r' | battery_parse)" || b=bad
  if [ "$b" = bad ] || [ -z "$b" ]; then res UNKNOWN "battery" "dumpsys battery unparseable"
  else bl="${b% *}"; ch="${b#* }"
    if [ "$bl" -lt "$BATT_WARN" ]; then res WARN "battery" "${bl}% charging=$ch (< ${BATT_WARN}%)"; else res PASS "battery" "${bl}% charging=$ch"; fi; fi
  fr="$(A "$s" shell df -k /data 2>/dev/null | tr -d '\r' | df_free_kb)" || fr=bad
  if [ "$fr" = bad ] || [ -z "$fr" ]; then res UNKNOWN "free storage" "df -k /data unparseable"
  elif [ "$fr" -lt "$FREE_WARN_KB" ]; then res WARN "free storage" "$((fr / 1024)) MB free (< 2 GB)"
  else res PASS "free storage" "$((fr / 1024)) MB free"; fi
}

# ---------- trip-build checks (phone, debuggable builds via run-as; counts and enums only, never content) ----------
LOG_CAP_BYTES=314572800   # 300 MB
HB_MAX_MIN=70
PLACE_HOURS="${PCA_CHECK_PLACE_HOURS:-24}"
PREF_TXT=""
pref_read() { # file-name-without-.xml -> PREF_TXT; 0 = read, 1 = unreadable
  PREF_TXT=""
  rc_shell "$1" "run-as $PHONE_PKG cat shared_prefs/$2.xml" || return 1
  [ "$REMOTE_RC" = 0 ] || return 1
  PREF_TXT="$REMOTE_OUT"; return 0
}

check_trip_logs() { # serial -> builds $TMP/all.log (every segment, oldest first) and $TMP/main.log (pca.log)
  local s="$1" ls_out sc n bytes ign hasmain names f want got bad=""
  rc_shell "$s" "run-as $PHONE_PKG ls -l files/logs" || { res UNKNOWN "log segments" "ls files/logs failed (adb)"; return; }
  if [ "$REMOTE_RC" != 0 ]; then res UNKNOWN "log segments" "files/logs not listable"; return; fi
  sc="$(printf '%s\n' "$REMOTE_OUT" | seg_scan)"; set -- $sc; n="$1"; bytes="$2"; ign="$3"; hasmain="$4"
  ls_out="$REMOTE_OUT"
  if [ "$n" = 0 ] || [ "$hasmain" = 0 ]; then res WARN "log segments" "pca.log missing ($n segment(s), $ign other entries)"; return; fi
  if [ "$bytes" -ge "$LOG_CAP_BYTES" ]; then res FAIL "log segments" "$n segments, $((bytes / 1048576)) MB (>= 300 MB cap)"; return; fi
  res PASS "log segments" "$n segment(s) incl. pca.log, $((bytes / 1048576)) MB total (< 300 MB)"
  [ -n "$TMP" ] || return
  : > "$TMP/all.log"; : > "$TMP/main.log"
  names="$(printf '%s\n' "$ls_out" | awk 'NF == 8 {print $8}' | seg_order)"
  for f in $names; do
    want="$(printf '%s\n' "$ls_out" | awk -v n="$f" 'NF == 8 && $8 == n && $5 ~ /^[0-9]+$/ {print $5; exit}')"
    # no pipe: the adb status must not be hidden by tr. A failed read, or fewer bytes than ls reported (truncated), means the logs are INCOMPLETE:
    # nothing derived from them may be PASS, so TRIP_LOGS stays 0 and every log-derived check reports UNKNOWN.
    if ! A "$s" exec-out run-as "$PHONE_PKG" cat "files/logs/$f" > "$TMP/seg.raw" 2>/dev/null; then bad=failed; break; fi
    got="$(wc -c < "$TMP/seg.raw" | tr -d ' ')"
    case "$got:$want" in *[!0-9:]*|:*|*:) bad=unmeasured; break ;; esac
    if [ "$got" -lt "$want" ]; then bad=truncated; break; fi
    tr -d '\r' < "$TMP/seg.raw" > "$TMP/seg.part"
    cat "$TMP/seg.part" >> "$TMP/all.log"
    if [ "$f" = pca.log ]; then cp "$TMP/seg.part" "$TMP/main.log"; fi
  done
  rm -f "$TMP/seg.part" "$TMP/seg.raw"
  if [ -n "$bad" ]; then res UNKNOWN "log read" "a log segment read $bad: logs incomplete, log-derived checks are UNKNOWN"; return; fi
  TRIP_LOGS=1
}

check_trip_log_content() {
  local st hb ran gap fc lat http at pc tot line cost pl
  if [ "${TRIP_LOGS:-0}" != 1 ]; then
    res UNKNOWN "detailed log" "log not read or incomplete"; res UNKNOWN "log forbidden shapes" "log not read"; res UNKNOWN "data saver (PcData)" "log not read"; res UNKNOWN "place attempts" "log not read"; return
  fi
  set -- $(diag_state < "$TMP/all.log"); st="$1"; hb="$2"; ran="$3"; gap="$4"
  if [ "$st" != Y ]; then res UNKNOWN "detailed log" "no PcDiag app-start milestone in the retained log"
  elif [ "$hb" -ge 1 ]; then
    if [ "$gap" != - ] && [ "$gap" -le "$HB_MAX_MIN" ]; then res PASS "detailed log" "ON inferred: $hb heartbeat(s), last ${gap} min before the log end, app ran ${ran} min"
    else res WARN "detailed log" "heartbeats seen ($hb) but the last is ${gap} min old (> ${HB_MAX_MIN}): logging stalled or switched off"; fi
  elif [ "$ran" -gt "$HB_MAX_MIN" ]; then res PASS "detailed log" "OFF inferred: no heartbeat in ${ran} min of running (milestones only)"
  else res UNKNOWN "detailed log" "app ran only ${ran} min: too short to see a heartbeat (wait > ${HB_MAX_MIN} min)"; fi
  set -- $(forbidden_counts < "$TMP/main.log"); lat="$1"; http="$2"; at="$3"
  if [ "$lat" != 0 ] || [ "$http" != 0 ]; then res FAIL "log forbidden shapes" "pca.log lines with coordinate-like pairs=$lat, 'http(s)://'=$http, '@'=$at (counts only)"
  elif [ "$at" != 0 ]; then res WARN "log forbidden shapes" "pca.log lines with '@'=$at (no coordinates/http)"
  else res PASS "log forbidden shapes" "pca.log: coordinate-like pairs=0 http=0 @=0"; fi
  pc="$(pcdata_counts < "$TMP/main.log")"; cost="$(pcdata_last_cost < "$TMP/main.log")"
  if [ -z "$pc" ]; then res PASS "data saver (PcData)" "no blocked lines in pca.log; last net cost=$cost"
  else pl="$(printf '%s\n' "$pc" | awk '{printf "%s%s=%s", (NR > 1 ? " " : ""), $1, $2}')"; res PASS "data saver (PcData)" "blocked by reason: $pl; last net cost=$cost (report only)"; fi
  pl="$(place_counts "$PLACE_HOURS" < "$TMP/all.log")"
  set -- $pl
  case "$pl" in *" garbled=0") res PASS "place attempts" "$pl" ;; *) res WARN "place attempts" "$pl (garbled outcomes present)" ;; esac
}

check_trip_prefs() {
  local s="$1" pp ls_ok=0 pins ab init pv cnt
  if rc_shell "$s" "run-as $PHONE_PKG ls shared_prefs" && [ "$REMOTE_RC" = 0 ]; then ls_ok=1; fi
  if [ "$ls_ok" = 0 ]; then res UNKNOWN "prefs files" "shared_prefs not listable"; res UNKNOWN "model privacy" "prefs unreadable"; res UNKNOWN "pins" "prefs unreadable"; return; fi
  pp="$(printf '%s\n' "$REMOTE_OUT" | prefs_present)"
  res PASS "prefs files" "$pp (names only; online_map is optional)"
  ab=""; init=""
  case "$pp" in *"ai_backend=Y"*) pref_read "$s" ai_backend && ab="$PREF_TXT" || ab="" ;; esac
  case "$pp" in *"ai_backend_init=Y"*) pref_read "$s" ai_backend_init && init="$PREF_TXT" || init="" ;; esac
  pv="$(privacy_state "$ab" "$init")"
  case "$pv" in
    *"reset=yes"*) res WARN "model privacy" "$pv: the stored choice was lost, the app reads Offline only: confirm in Settings" ;;
    *garbled*) res WARN "model privacy" "$pv" ;;
    *) res PASS "model privacy" "$pv" ;;
  esac
  case "$pp" in
    *"place_pins=Y"*)
      if pref_read "$s" place_pins; then
        cnt="$(printf '%s\n' "$PREF_TXT" | pins_counts)"
        case "$cnt" in
          nopython) res UNKNOWN "pins" "python3 not available on the host" ;;
          bad) res UNKNOWN "pins" "place_pins.xml unreadable or not the expected JSON" ;;
          *) set -- $cnt; res PASS "pins" "total=$1 with Moment link=$2 without=$3 (counts only)" ;;
        esac
      else res UNKNOWN "pins" "place_pins.xml unreadable"; fi ;;
    *) res PASS "pins" "total=0 (no place_pins.xml)" ;;
  esac
}

EXPECT_DB_VERSION=18
# row count of a v18 table: a number only (never a row); empty or non-numeric = UNKNOWN
table_count_state() { case "$1" in ''|*[!0-9]*) echo unreadable ;; *) echo "$1" ;; esac; }
res_table_count() { # table count
  local c; c="$(table_count_state "$2")"
  if [ "$c" = unreadable ]; then res UNKNOWN "db rows $1" "count unreadable (table missing or db not v$EXPECT_DB_VERSION)"; else res PASS "db rows $1" "rows=$c (count only)"; fi
}

check_trip_db() { # serial: only a COPY, only when the app's own flag says plaintext
  local s="$1" d hdr enc mode uv ic d_cnt_ml d_cnt_pc d_cnt_sc d_cnt_ma
  [ -n "$TMP" ] || { res UNKNOWN "db version" "no temp dir"; return; }
  d="$TMP/db"; mkdir -p "$d"
  A "$s" exec-out run-as "$PHONE_PKG" cat databases/context.db > "$d/context.db" 2>/dev/null || true
  if [ ! -s "$d/context.db" ]; then res UNKNOWN "db version" "context.db not readable"; rm -rf "$d"; return; fi
  hdr="$(head -c 15 "$d/context.db" 2>/dev/null | tr -c 'A-Za-z0-9 ' '?')"
  enc=""; if pref_read "$s" memory_graph; then enc="$(printf '%s\n' "$PREF_TXT" | grep -o '<boolean name="db_encrypted" value="[a-z]*"' | sed 's/.*value="//; s/"$//' | sed -n 1p)"; fi
  mode="$(db_mode3 "$hdr" "$enc")"
  case "$mode" in
    encrypted) res UNKNOWN "db version" "database is encrypted: user_version/integrity not readable read-only on the host"; rm -rf "$d"; return ;;
    inconsistent) res WARN "db version" "db header and the app's db_encrypted flag disagree: not inspected"; rm -rf "$d"; return ;;
  esac
  if ! command -v sqlite3 >/dev/null 2>&1; then res UNKNOWN "db version" "sqlite3 not on the host"; rm -rf "$d"; return; fi
  A "$s" exec-out run-as "$PHONE_PKG" cat databases/context.db-wal > "$d/context.db-wal" 2>/dev/null || true
  [ -s "$d/context.db-wal" ] || rm -f "$d/context.db-wal"
  uv="$(sqlite3 "$d/context.db" 'PRAGMA user_version;' 2>/dev/null | tr -d '\r[:space:]')" || uv=""
  ic="$(sqlite3 "$d/context.db" 'PRAGMA integrity_check;' 2>/dev/null | tr -d '\r')" || ic=""
  d_cnt_ml="$(sqlite3 "$d/context.db" 'SELECT COUNT(*) FROM moment_location;' 2>/dev/null | tr -d '\r[:space:]')" || d_cnt_ml=""
  d_cnt_pc="$(sqlite3 "$d/context.db" 'SELECT COUNT(*) FROM place_card_log;' 2>/dev/null | tr -d '\r[:space:]')" || d_cnt_pc=""
  d_cnt_sc="$(sqlite3 "$d/context.db" 'SELECT COUNT(*) FROM scene_centroid;' 2>/dev/null | tr -d '\r[:space:]')" || d_cnt_sc=""
  rm -rf "$d"
  case "$uv" in ''|*[!0-9]*) res UNKNOWN "db version" "user_version unreadable"; return ;; esac
  if [ "$uv" = "$EXPECT_DB_VERSION" ]; then res PASS "db version" "user_version=$uv"; else res FAIL "db version" "user_version=$uv (expected $EXPECT_DB_VERSION)"; fi
  res_table_count moment_location "$d_cnt_ml"
  res_table_count place_card_log "$d_cnt_pc"
  res_table_count scene_centroid "$d_cnt_sc"
  d_cnt_ma="$(sqlite3 "$d/context.db" 'SELECT COUNT(*) FROM model_attribution;' 2>/dev/null | tr -d '\r[:space:]')" || d_cnt_ma=""
  res_table_count model_attribution "$d_cnt_ma"
  if [ "$ic" = ok ]; then res PASS "db integrity" "integrity_check ok (copy)"
  elif [ -z "$ic" ]; then res UNKNOWN "db integrity" "integrity_check gave no answer"
  else res FAIL "db integrity" "integrity_check on the copy did not say ok"; fi
}

check_trip_build() { # phone
  local s="$1"
  TRIP_LOGS=0
  rc_shell "$s" "run-as $PHONE_PKG id" || { res UNKNOWN "trip-build checks" "run-as probe failed (adb)"; return; }
  if [ "$REMOTE_RC" != 0 ]; then res UNKNOWN "trip-build checks" "not debuggable (run-as unavailable): logs, prefs and db not readable"; return; fi
  check_trip_logs "$s"
  check_trip_log_content
  check_trip_prefs "$s"
  check_trip_db "$s"
}

# ---------- main ----------
while [ $# -gt 0 ]; do
  case "$1" in
    --phone) PHONE="${2:-}"; shift 2 ;;
    --glasses) GLASSES="${2:-}"; shift 2 ;;
    --apks) APKS="${2:-}"; shift 2 ;;
    --fixtures) FIXTURES=1; shift ;;
    -h|--help) usage ;;
    *) die "unknown argument: $1" ;;
  esac
done
if [ "$FIXTURES" = 1 ]; then run_fixtures || exit 1; exit 0; fi

[ -n "$PHONE" ] || die "--phone <serial> is required (no auto-detect)"
[ -n "$APKS" ] || die "--apks <folder> is required"
[ -d "$APKS" ] || die "APK folder not found: $APKS"
if [ -n "$GLASSES" ] && [ "$GLASSES" = "$PHONE" ]; then die "phone and glasses serials are equal"; fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-}"
if [ -z "$ADB" ] && [ -x "$SDK/platform-tools/adb" ]; then ADB="$SDK/platform-tools/adb"; fi
if [ -z "$ADB" ]; then ADB="$(command -v adb || true)"; fi
{ [ -n "$ADB" ] && [ -x "$ADB" ]; } || die "adb not found (set ADB or ANDROID_HOME)"
BT=""
if [ -d "$SDK/build-tools" ]; then BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1 || true)"; BT="${BT%/}"; fi
if [ -n "$BT" ] && [ -x "$BT/aapt2" ]; then AAPT="$BT/aapt2"; elif [ -n "$BT" ] && [ -x "$BT/aapt" ]; then AAPT="$BT/aapt"; fi
if [ -z "$AAPT" ]; then AAPT="$(command -v aapt2 || command -v aapt || true)"; fi
if [ -n "$BT" ] && [ -x "$BT/apksigner" ]; then APKSIGNER="$BT/apksigner"; else APKSIGNER="$(command -v apksigner || true)"; fi
APKANALYZER="$(command -v apkanalyzer || true)"

DEVS="$("$ADB" devices 2>/dev/null | tr -d '\r' | awk 'NR>1 && $2=="device"{print $1}')" || DEVS=""
for s in "$PHONE" "$GLASSES"; do
  if [ -n "$s" ] && ! printf '%s\n' "$DEVS" | grep -qx -- "$s"; then die "serial '$s' is not listed (state device) in 'adb devices'"; fi
done
TMP="$(mktemp -d "${TMPDIR:-/tmp}/post-install-check.XXXXXX")" || die "no temp dir"

say "post-install check (READ-ONLY): phone=$PHONE glasses=${GLASSES:-none} apks=$APKS"

# (1) identity
IDP="$(dev_ident "$PHONE")" && IDPOK=1 || IDPOK=0
if [ "$IDPOK" = 0 ]; then res FAIL "identity phone" "$IDP"; else res PASS "identity phone" "usable hardware serial/android_id/model"; fi
if [ "$IDPOK" = 1 ]; then
  if is_watch "$PHONE"; then res FAIL "phone is not a watch" "device reports watch characteristics (applicationId clash)"; else res PASS "phone is not a watch" "no watch characteristics/feature"; fi
fi
if [ -n "$GLASSES" ]; then
  IDG="$(dev_ident "$GLASSES")" && IDGOK=1 || IDGOK=0
  if [ "$IDGOK" = 0 ]; then res FAIL "identity glasses" "$IDG"
  elif [ "$IDPOK" = 1 ] && id_alias "$IDP" "$IDG"; then res FAIL "phone != glasses" "same physical device (serial or android_id equal)"
  else res PASS "phone != glasses" "distinct physical devices"; fi
fi

# (2)-(5) phone
if check_version_hash "$PHONE" "$PHONE_PKG" "$APKS/phone-debug.apk" phone; then
  check_perms "$PHONE" "$PHONE_PKG" phone
  check_idle "$PHONE" "$PHONE_PKG" phone
  check_process "$PHONE" "$PHONE_PKG" phone
  check_logs "$PHONE" "$PHONE_PKG" "$LAST_PID" phone
  check_safety "$PHONE"
fi
check_trip_build "$PHONE"
check_system "$PHONE"

# (8) glasses
if [ -n "$GLASSES" ]; then
  if check_version_hash "$GLASSES" "$GLASSES_PKG" "$APKS/glasses-debug.apk" glasses; then
    check_process "$GLASSES" "$GLASSES_PKG" glasses
  fi
  res UNKNOWN "glasses link state" "not observable read-only from the host: confirm on the phone (Home shows the glasses connected)"
else
  res UNKNOWN "glasses checks" "no --glasses serial given: glasses not checked"
fi

say "summary: PASS=$N_PASS WARN=$N_WARN FAIL=$N_FAIL UNKNOWN=$N_UNK"
RC="$(exit_code_for "$N_PASS" "$N_WARN" "$N_FAIL" "$N_UNK")"
if [ "$RC" = 0 ]; then say "RESULT: no FAIL (read every WARN and UNKNOWN line)"; else say "RESULT: FAIL present: do not trust this install"; fi
exit "$RC"
