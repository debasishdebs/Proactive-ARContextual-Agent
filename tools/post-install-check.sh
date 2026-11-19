#!/usr/bin/env bash
# READ-ONLY post-install verification for the trip build (bash 3.2 compatible).
#   tools/post-install-check.sh --phone <serial> [--glasses <serial>] --apks <folder>
#   tools/post-install-check.sh --fixtures        (pure-logic fixtures, no device, no adb)
# Each check prints PASS / WARN / FAIL / UNKNOWN with a short literal reason; the run ends with a summary.
# Exit codes: 0 = no FAIL (WARN/UNKNOWN allowed, read them), 1 = at least one FAIL, 2 = refused / usage.
# NEVER installs, uninstalls, clears, force-stops, writes settings or sends input: every adb call goes through adb_safe (allow-list, tested in the fixtures).
# It only reads (dumpsys, getprop, pm path, logcat -d, run-as cat of two prefs files) and pulls the installed base APK into a temp dir it removes.
# Every adb call carries -s. Prints counts, hashes, names and statuses only: never a logcat line, token or pairing code.
# Procedure: tools/INSTALL-TRIP-BUILD.md section "Post-install check".
set -uo pipefail
umask 077

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

# ---------- fixtures ----------
FX_PASS=0 FX_FAIL=0
eq() { # name expected actual
  if [ "$2" = "$3" ]; then FX_PASS=$((FX_PASS + 1)); else FX_FAIL=$((FX_FAIL + 1)); echo "  FIXTURE FAILED: $1 (want '$2', got '$3')"; fi
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
