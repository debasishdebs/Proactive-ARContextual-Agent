#!/usr/bin/env bash
# SAFE install helper for the trip build (bash 3.2 compatible). DRY-RUN BY DEFAULT: real actions only with --go.
#   tools/install-trip-build.sh --phone <serial> --glasses <serial> [--watch <serial>] --apks <dir> [--tag <tag>] [--go]
#        [--allow-emulator] [--allow-downgrade] [--accept-unverified-signature [--i-understand-no-signature-check]]
#   tools/install-trip-build.sh --self-test --phone emulator-5554 --apks <dir> [--go]    (emulator only, phone only; runs the fixtures first)
#   tools/install-trip-build.sh --fixtures                                                (refusal fixtures only, no device, no adb)
# Exit codes: 2 = REFUSED, 3 = installed but NOT verified, other nonzero = failure, 0 = success.
# Serial aliases: every serial is resolved to a device identity (hardware serial + android_id + model + fingerprint); all must differ, re-checked before every install.
# FAIL CLOSED: any uncertainty refuses (exit 2) unless a clearly named override flag is passed (and it is printed).
# Serials are never auto-detected and every adb call carries -s (only `adb devices` has none). Never uninstalls, never `pm clear`,
# never kills any process on the host. Prints counts, hashes and statuses only: no logcat line, token or pairing code.
# DRY-RUN writes nothing (no temp dir, no file) and pulls nothing; it skips the signature pull ("signature check will run with --go").
# Procedure source: docs/superpowers/plans/handoffs/v17-upgrade-recovery.md. See tools/INSTALL-TRIP-BUILD.md.
set -Eeuo pipefail
umask 077

PHONE_PKG=com.rokid.context
GLASSES_PKG=com.rokid.context.glasses
WATCH_PKG=com.rokid.context        # same applicationId as the phone app: hazard, see device-checklist A0
BACKUP_DIR="${PCA_BACKUP_DIR:-$HOME/Desktop/rokid/backups}"
SCAN_SECS="${PCA_SCAN_SECS:-60}"
RETRY_SLEEP="${PCA_RETRY_SLEEP:-1}"

PHONE="" GLASSES="" WATCH="" APKS="" TAG="" GO=0 ALLOW_EMU=0 ALLOW_DOWN=0 SELFTEST=0 PLAIN_OK=0
ACCEPT_UNSIG=0 UNDERSTAND_NOSIG=0 FIXTURES=0
TMP="" FXDIR="" WATCH_VC="" wvc=""
BACKUP_TAR="" PREV_APK="" BACKUP_VALID=0
PHASE=preflight    # preflight | backup | installing | installed
APK_VC="" REMOTE_OUT="" REMOTE_RC=""
ADB="" APKSIGNER="" AAPT="" APKANALYZER="" DEVS=""

die()  { echo "REFUSED: $*" >&2; exit 2; }
fail() { echo "FAILED: $*" >&2; exit 1; }
say()  { echo "$*"; }
cleanup() {
  if [ -n "$TMP" ] && [ -d "$TMP" ]; then rm -rf "$TMP"; fi; TMP=""
  if [ -n "$FXDIR" ] && [ -d "$FXDIR" ]; then rm -rf "$FXDIR"; fi; FXDIR=""
  return 0
}
usage() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

is_emu() { case "$1" in emulator-*) return 0 ;; *) return 1 ;; esac; }
# A <serial> args... : the ONLY way adb is called with a device
A() { local s="$1"; shift; "$ADB" -s "$s" "$@"; }

print_guidance() {
  if [ "$BACKUP_VALID" = 1 ] && { [ "$PHASE" = installing ] || [ "$PHASE" = installed ]; }; then
    cat >&2 <<RB
--- FAILED AFTER THE INSTALL STEP STARTED (phase=$PHASE). The validated backup exists. Restore steps (docs/superpowers/plans/handoffs/v17-upgrade-recovery.md section 4) ---
 NON-DESTRUCTIVE FIRST: every step below copies or moves, nothing is deleted; quarantine the current state BEFORE replacing anything.
 Never uninstall, never pm clear. Not during a call. Only restore if the app fails the section 3 checks.
 1. adb -s $PHONE shell am force-stop $PHONE_PKG   (confirm: adb -s $PHONE shell pidof $PHONE_PKG prints nothing)
 2. Preserve the broken state first: adb -s $PHONE exec-out run-as $PHONE_PKG tar c . > $BACKUP_DIR/pca-phone-broken-$TAG.tar  (then tar tf it)
    and on-device quarantine copy: adb -s $PHONE shell run-as $PHONE_PKG sh -c 'mkdir -p quarantine-$TAG && cp -p databases/context.db* quarantine-$TAG/ && cp -p shared_prefs/db_key.xml quarantine-$TAG/'
 3. Move (never delete) the live databases/context.db* into quarantine-$TAG/replaced/, then:
    cat $BACKUP_TAR | adb -s $PHONE shell run-as $PHONE_PKG tar x
    adb -s $PHONE install -r ${PREV_APK:-<previous phone apk, same signature>}
 4. Open the app and run the section 3 checks. If it still fails: stop, delete nothing, ask for help.
 Glasses: adb -s <glasses> install -r <old 1.0.0 glasses apk> (staged in $BACKUP_DIR/apks-v100-*).
RB
  elif [ "$BACKUP_VALID" = 1 ]; then
    echo "No change was made to the phone: leave phone data untouched (phase=$PHASE). The backup $BACKUP_TAR is complete and validated but is NOT needed. The app may have been force-stopped: just reopen it." >&2
  else
    echo "No change was made to the phone: leave phone data untouched; the backup (if any) is incomplete/unvalidated, do not restore from it (phase=$PHASE). The app may have been force-stopped: just reopen it." >&2
  fi
}
on_exit() {
  local rc=$?
  trap - EXIT
  if [ "$rc" -ne 0 ] && [ "$FIXTURES" = 0 ]; then echo "exit status $rc, phase=$PHASE" >&2; print_guidance; fi
  cleanup
  exit "$rc"
}
trap 'echo "ERROR: unexpected failure at line $LINENO (phase=$PHASE)" >&2' ERR
trap on_exit EXIT

# ---------- checked helpers ----------
hash_cmd() { # file -> raw "digest  name" line (overridable by fixtures)
  if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1"
  elif command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"
  else return 1; fi
}
sha256_of() { # file -> prints a validated 64-hex digest; returns 1 on any failure
  local out d re='^[0-9a-f]{64}$'
  out="$(hash_cmd "$1")" || return 1
  d="${out%% *}"
  [[ "$d" =~ $re ]] || return 1
  printf '%s\n' "$d"
}
count_matches() { # grep-flags file regex -> prints a count; grep status 1 (no match) = 0, status >=2 (error) = return 2
  local n gst=0
  n="$(trap - ERR; grep -c"${1#-}" -e "$3" "$2" 2>/dev/null)" || gst=$?
  if [ "$gst" -gt 1 ]; then return 2; fi
  case "$n" in ''|*[!0-9]*) return 2 ;; esac
  printf '%s\n' "$n"
}
fsize() { local n; n="$(stat -f%z "$1" 2>/dev/null || stat -c%s "$1" 2>/dev/null)" || return 1; case "$n" in ''|*[!0-9]*) return 1 ;; esac; printf '%s\n' "$n"; }

remote_rc() { # serial cmd -> sets REMOTE_OUT, REMOTE_RC from EXACTLY ONE well-formed trailing marker.
  # returns 0 ok | 1 adb/transport failure | 2 parse failure or absent/duplicated/malformed marker. Every step is checked (callers run us in || contexts, where set -e is off).
  local raw cleaned last body cnt gst re='^__PCA_RC=([0-9]+)$'
  REMOTE_OUT=""; REMOTE_RC=""
  raw="$(A "$1" shell "$2; echo __PCA_RC=\$?" 2>&1)" || return 1
  cleaned="$(printf '%s\n' "$raw" | tr -d '\r')" || return 2
  gst=0; cnt="$(printf '%s\n' "$cleaned" | grep -c '__PCA_RC=')" || gst=$?
  if [ "$gst" -gt 1 ]; then return 2; fi
  case "$cnt" in ''|*[!0-9]*) return 2 ;; esac
  [ "$cnt" = 1 ] || return 2
  last="$(printf '%s\n' "$cleaned" | tail -n 1)" || return 2
  [[ "$last" =~ $re ]] || return 2
  body="$(printf '%s\n' "$cleaned" | sed '$d')" || return 2
  REMOTE_RC="${BASH_REMATCH[1]}"
  REMOTE_OUT="$body"
  return 0
}

# stop_app statuses: 0 = process CONFIRMED gone (single well-formed marker, pidof remote status 1, empty pid list)
#                    1 = still running after retries | 2 = uncertain (adb/parse/marker/unexpected result). Callers must refuse on anything but 0.
stop_app() {
  local i st re='^[0-9]+([[:space:]]+[0-9]+)*$'
  for i in 1 2 3 4 5; do
    st=0; remote_rc "$PHONE" "am force-stop $PHONE_PKG" || st=$?
    if [ "$st" != 0 ]; then echo "REFUSED: force-stop could not be confirmed (remote_rc status $st)" >&2; return 2; fi
    if [ "$REMOTE_RC" != 0 ]; then echo "REFUSED: am force-stop exited $REMOTE_RC" >&2; return 2; fi
    sleep "$RETRY_SLEEP" || return 2
    st=0; remote_rc "$PHONE" "pidof $PHONE_PKG" || st=$?
    if [ "$st" != 0 ]; then echo "REFUSED: cannot query the process state (remote_rc status $st)" >&2; return 2; fi
    if [ "$REMOTE_RC" = 1 ] && [ -z "$REMOTE_OUT" ]; then say "app process confirmed gone (pidof: no process)"; return 0; fi
    if [ "$REMOTE_RC" = 0 ] && [[ "$REMOTE_OUT" =~ $re ]]; then continue; fi
    echo "REFUSED: unexpected pidof result (exit $REMOTE_RC)" >&2; return 2
  done
  echo "REFUSED: $PHONE_PKG still running after force-stop retries" >&2; return 1
}

parse_call_states() { # stdin: dumpsys text. 0 only if >=1 mCallState lines, EVERY one strictly 'mCallState=0'
  local n=0 line re='^[[:space:]]*mCallState=([0-9]+)[[:space:]]*$'
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in *mCallState*) ;; *) continue ;; esac
    if [[ "$line" =~ $re ]]; then
      [ "${BASH_REMATCH[1]}" = 0 ] || return 1
      n=$((n + 1))
    else return 1; fi
  done
  [ "$n" -ge 1 ]
}
call_guard() { # $1 = label
  local raw=""
  if [ "$SELFTEST" = 1 ] && is_emu "$PHONE" && [ -n "${PCA_SELFTEST_CALLSTATE:-}" ]; then
    raw="mCallState=$PCA_SELFTEST_CALLSTATE"
  else
    raw="$(A "$PHONE" shell dumpsys telephony.registry 2>/dev/null)" || { echo "REFUSED: 'dumpsys telephony.registry' failed on the phone ($1)" >&2; return 1; }
  fi
  raw="$(printf '%s\n' "$raw" | tr -d '\r')" || { echo "REFUSED: cannot normalise the call state output ($1)" >&2; return 1; }
  if parse_call_states <<<"$raw"; then say "call guard ($1): every mCallState is 0"
  else echo "REFUSED: a call is active, or mCallState is missing/unparseable/inconsistent on the phone ($1)" >&2; return 1; fi
}

free_kb() { # dir -> free KB of the filesystem holding dir (nearest existing ancestor)
  local d="$1" n
  while [ ! -d "$d" ] && [ "$d" != / ] && [ "$d" != . ]; do d="$(dirname "$d")" || return 1; done
  n="$(df -Pk "$d" | awk 'NR==2{print $4}')" || return 1
  case "$n" in ''|*[!0-9]*) return 1 ;; esac
  printf '%s\n' "$n"
}
disk_check() { # app data size via run-as du, host free space for BACKUP_DIR and TMPDIR must be >= 3x
  local line kb need f d re='^([0-9]+)[[:space:]]+\.$'
  remote_rc "$PHONE" "run-as $PHONE_PKG du -sk ." || { echo "REFUSED: cannot estimate the app data size (adb/run-as failure)" >&2; return 1; }
  [ "$REMOTE_RC" = 0 ] || { echo "REFUSED: run-as du exited $REMOTE_RC (is the installed build debuggable?)" >&2; return 1; }
  line="$(printf '%s\n' "$REMOTE_OUT" | tail -n 1)" || { echo "REFUSED: cannot read the du output" >&2; return 1; }
  [[ "$line" =~ $re ]] || { echo "REFUSED: unparseable du output" >&2; return 1; }
  kb="${BASH_REMATCH[1]}"; need=$((kb * 3))
  for d in "$BACKUP_DIR" "${TMPDIR:-/tmp}"; do
    f="$(free_kb "$d")" || { echo "REFUSED: cannot read free disk space for $d" >&2; return 1; }
    [ "$f" -ge "$need" ] || { echo "REFUSED: only ${f} KB free for $d, need >= 3x app data (${need} KB)" >&2; return 1; }
  done
  say "disk preflight: app data ~${kb} KB, host free >= ${need} KB (3x) for backup dir and temp dir"
}

# ---------- APK helpers ----------
apk_pkg() { # package name
  if [ -n "$AAPT" ]; then "$AAPT" dump badging "$1" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | sed -n 1p
  else "$APKANALYZER" manifest application-id "$1" 2>/dev/null | sed -n 1p; fi
}
apk_vc() { # versionCode
  if [ -n "$AAPT" ]; then "$AAPT" dump badging "$1" 2>/dev/null | sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p" | sed -n 1p
  else "$APKANALYZER" manifest version-code "$1" 2>/dev/null | sed -n 1p; fi
}
apk_cert() { # apk -> validated cert SHA-256, or return 1
  local out d re='^[0-9a-f]{64}$'
  [ -n "$APKSIGNER" ] || return 1
  out="$("$APKSIGNER" verify --print-certs "$1" 2>/dev/null)" || return 1
  d="$(printf '%s\n' "$out" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | sed -n 1p)" || return 1
  [[ "$d" =~ $re ]] || return 1
  printf '%s\n' "$d"
}
inst_vc() { A "$1" shell dumpsys package "$2" 2>/dev/null | tr -d '\r' | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | sed -n 1p; }
inst_vn() { A "$1" shell dumpsys package "$2" 2>/dev/null | tr -d '\r' | sed -n 's/.*versionName=//p' | sed -n 1p; }

# ---------- device identity (serial aliases: USB serial vs wireless adb-<serial>-<suffix>._adb-tls-connect._tcp or ip:port) ----------
ID_PHONE="" ID_GLASSES="" ID_WATCH="" N_PHONE="" N_GLASSES="" N_WATCH=""
bad_idval() { # value -> 0 if empty/unknown/placeholder (unusable as identity)
  local v; v="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" || return 0
  case "$v" in ''|unknown|null|'(null)'|none|undefined|*error*|*'not found'*|*'no such'*|*denied*) return 0 ;; esac
  [[ "$v" =~ ^[0:-]+$ ]] && return 0
  return 1
}
dev_prop() { # serial cmd... -> trimmed single-line output, return 1 on adb failure
  local s="$1" v; shift
  v="$(A "$s" shell "$@" 2>/dev/null | tr -d '\r' | tr '\n' ' ' | sed 's/^ *//;s/ *$//')" || return 1
  printf '%s\n' "$v"
}
dev_ident() { # serial -> prints "serialno|android_id|model|fingerprint"; returns 1 (reason on stderr) if it cannot be determined. FAIL CLOSED.
  local s="$1" ser aid model fp
  ser="$(dev_prop "$s" getprop ro.serialno)" || ser=""
  if bad_idval "$ser"; then ser="$(dev_prop "$s" getprop ro.boot.serialno)" || ser=""; fi
  aid="$(dev_prop "$s" settings get secure android_id)" || aid=""
  model="$(dev_prop "$s" getprop ro.product.model)" || model=""
  fp="$(dev_prop "$s" getprop ro.build.fingerprint)" || fp=""
  if bad_idval "$ser"; then echo "no usable hardware serial (ro.serialno / ro.boot.serialno) from '$s'" >&2; return 1; fi
  if bad_idval "$aid"; then echo "no usable android_id from '$s'" >&2; return 1; fi
  if bad_idval "$model" || bad_idval "$fp"; then echo "no usable model/fingerprint from '$s'" >&2; return 1; fi
  case "$ser$aid$model$fp" in *'|'*) echo "identity of '$s' contains the separator" >&2; return 1 ;; esac
  printf '%s|%s|%s|%s\n' "$ser" "$aid" "$model" "$fp"
}
id_alias() { # idA idB -> 0 if they are the same physical device (equal hardware serial OR equal android_id OR equal whole identity)
  local a1 a2 b1 b2
  [ "$1" = "$2" ] && return 0
  IFS='|' read -r a1 a2 _ <<<"$1"; IFS='|' read -r b1 b2 _ <<<"$2"
  [ "$a1" = "$b1" ] && return 0
  [ "$a2" = "$b2" ] && return 0
  return 1
}
pair_check() { # labelA serialA idA labelB serialB idB
  if id_alias "$3" "$6"; then
    die "serials '$2' ($1) and '$5' ($4) are the SAME physical device (adb aliases: USB serial vs wireless endpoint). watch-debug.apk has the SAME applicationId ($PHONE_PKG) as the phone app and would REPLACE it. Use ONE serial per physical device"
  fi
}
isolation_check() { # label : resolve phone/glasses/watch identities into N_*, require them pairwise different. Refuses on any doubt.
  local why
  N_PHONE="" N_GLASSES="" N_WATCH=""
  N_PHONE="$(dev_ident "$PHONE" 2>&1)" || die "cannot determine the device identity of the phone serial '$PHONE' ($1): $N_PHONE. Refusing (fail closed)"
  if [ -n "$GLASSES" ]; then N_GLASSES="$(dev_ident "$GLASSES" 2>&1)" || die "cannot determine the device identity of the glasses serial '$GLASSES' ($1): $N_GLASSES. Refusing (fail closed)"; fi
  if [ -n "$WATCH" ]; then N_WATCH="$(dev_ident "$WATCH" 2>&1)" || die "cannot determine the device identity of the watch serial '$WATCH' ($1): $N_WATCH. Refusing (fail closed)"; fi
  [ -z "$GLASSES" ] || pair_check phone "$PHONE" "$N_PHONE" glasses "$GLASSES" "$N_GLASSES"
  [ -z "$WATCH" ] || pair_check phone "$PHONE" "$N_PHONE" watch "$WATCH" "$N_WATCH"
  { [ -z "$WATCH" ] || [ -z "$GLASSES" ]; } || pair_check glasses "$GLASSES" "$N_GLASSES" watch "$WATCH" "$N_WATCH"
  why="$1"; say "device identity ($why): all supplied serials are distinct physical devices"
}
watch_check() { # serial : the target must be a watch (and, via isolation_check, not the phone)
  local s="$1" chars feat
  chars="$(dev_prop "$s" getprop ro.build.characteristics)" || chars=""
  feat="$(dev_prop "$s" pm list features)" || feat=""
  case "$chars" in *watch*) return 0 ;; esac
  case "$feat" in *android.hardware.type.watch*) return 0 ;; esac
  die "watch serial '$s' is not a watch (ro.build.characteristics='$chars', no android.hardware.type.watch). watch-debug.apk shares applicationId $PHONE_PKG with the phone app and would REPLACE the phone app on a phone. Refusing"
}
pre_install_guard() { # role serial : re-resolve ALL identities right before an install step, compare with the preflight baseline
  local role="$1" s="$2"
  isolation_check "before $role install"
  [ "$N_PHONE" = "$ID_PHONE" ] || die "identity of the phone serial changed since preflight: refusing before the $role install"
  [ -z "$GLASSES" ] || [ "$N_GLASSES" = "$ID_GLASSES" ] || die "identity of the glasses serial changed since preflight: refusing before the $role install"
  [ -z "$WATCH" ] || [ "$N_WATCH" = "$ID_WATCH" ] || die "identity of the watch serial changed since preflight: refusing before the $role install"
  if [ "$role" = watch ]; then watch_check "$s"; fi
}
note_endpoint() { # serial : notice for wireless-style serials
  case "$1" in *:*|*_adb-tls*) echo "NOTICE: '$1' is a wireless/endpoint-style serial; one physical device can appear under several serials, so device identities are resolved and compared before every install." ;; esac
  return 0
}

# ---------- argument rules ----------
validate_args() {
  local s
  [ "$PLAIN_OK" = 0 ] || [ "$SELFTEST" = 1 ] || die "--allow-plaintext-db is only valid with --self-test"
  if [ -n "${PCA_SELFTEST_CALLSTATE:-}" ] && { [ "$SELFTEST" = 0 ] || ! is_emu "$PHONE"; }; then die "PCA_SELFTEST_CALLSTATE is only honoured with --self-test on an emulator-* serial"; fi
  [ -n "$PHONE" ] || die "--phone <serial> is required (no auto-detect)"
  [ -n "$APKS" ] || die "--apks <dir> is required"
  [ -d "$APKS" ] || die "APK folder not found: $APKS"
  if [ "$SELFTEST" = 1 ]; then
    [ -z "$WATCH" ] || die "--self-test is phone-only: no --watch"
    is_emu "$PHONE" || die "--self-test accepts only an emulator-* serial (hard refuse on any other serial)"
    if [ -n "$GLASSES" ] && ! is_emu "$GLASSES"; then die "--self-test accepts only emulator-* serials"; fi
    ALLOW_EMU=1
  else
    [ -n "$GLASSES" ] || die "--glasses <serial> is required (no auto-detect)"
  fi
  if [ -n "$GLASSES" ] && [ "$PHONE" = "$GLASSES" ]; then die "phone and glasses serials are equal"; fi
  if [ -n "$WATCH" ] && { [ "$WATCH" = "$PHONE" ] || [ "$WATCH" = "$GLASSES" ]; }; then die "watch serial equals the phone or glasses serial (watch-debug.apk would REPLACE that app)"; fi
  if [ "$GO" = 1 ] && [ "$ALLOW_EMU" = 0 ]; then
    for s in "$PHONE" "$GLASSES" "$WATCH"; do
      if [ -n "$s" ] && is_emu "$s"; then die "serial $s looks like an emulator: add --allow-emulator to run --go on it"; fi
    done
  fi
  if [ "$UNDERSTAND_NOSIG" = 1 ] && [ "$ACCEPT_UNSIG" = 0 ]; then die "--i-understand-no-signature-check only makes sense with --accept-unverified-signature"; fi
  if [ "$ACCEPT_UNSIG" = 1 ] && [ "$GO" = 1 ] && [ "$UNDERSTAND_NOSIG" = 0 ]; then
    for s in "$PHONE" "$GLASSES" "$WATCH"; do
      if [ -n "$s" ] && ! is_emu "$s"; then die "--accept-unverified-signature with --go on a non-emulator serial ($s) also needs --i-understand-no-signature-check"; fi
    done
  fi
  for s in "$PHONE" "$GLASSES" "$WATCH"; do [ -z "$s" ] || note_endpoint "$s"; done
  [ -n "$TAG" ] || TAG="trip-$(date +%Y%m%d-%H%M)"
  case "$TAG" in *[!A-Za-z0-9._-]*) die "tag may only contain letters, digits . _ -" ;; esac
  return 0
}

# ---------- signature ----------
unverified() { # label reason : refuses unless the explicit override was given (then prints it prominently)
  if [ "$ACCEPT_UNSIG" = 1 ]; then
    echo "!!!!! WARNING: $1: SIGNATURE NOT VERIFIED ($2). Continuing ONLY because --accept-unverified-signature was passed. A different signature makes the encrypted DB unreadable. !!!!!"
    return 0
  fi
  die "$1: cannot verify the signature ($2). Refusing. (Override, NOT recommended: --accept-unverified-signature)"
}
sig_check() { # serial pkg newapk label
  local s="$1" pkg="$2" apk="$3" label="$4" path want have
  if [ -z "$APKSIGNER" ]; then unverified "$label" "apksigner not found"; return 0; fi
  want="$(apk_cert "$apk")" || want=""
  if [ -z "$want" ]; then unverified "$label" "cannot read the signing certificate of the new APK"; return 0; fi
  if [ "$GO" = 0 ]; then say "$label: signature check will run with --go (dry-run pulls nothing)"; return 0; fi
  path="$(A "$s" shell pm path "$pkg" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | sed -n 1p)" || path=""
  if [ -z "$path" ]; then unverified "$label" "installed APK path unknown"; return 0; fi
  if ! A "$s" pull "$path" "$TMP/installed-$label.apk" >/dev/null 2>&1; then unverified "$label" "pull of the installed APK failed"; return 0; fi
  have="$(apk_cert "$TMP/installed-$label.apk")" || have=""
  if [ -z "$have" ]; then unverified "$label" "cannot read the installed APK certificate"; return 0; fi
  [ "$have" = "$want" ] || die "$label: signing certificate differs (installed ${have:0:8}... vs new ${want:0:8}...); a different signature makes the encrypted DB unreadable (no override)"
  say "$label: signature matches installed (SHA-256 ${want:0:8}...)"
  return 0
}

# ---------- preflight ----------
check_apk() { # file pkg label -> sets APK_VC
  local f="$1" pkg="$2" label="$3" p h
  [ -f "$f" ] || die "$label APK missing: $f"
  p="$(apk_pkg "$f")" || p=""
  [ "$p" = "$pkg" ] || die "$label APK package is '$p', expected '$pkg'"
  APK_VC="$(apk_vc "$f")" || APK_VC=""
  case "$APK_VC" in ''|*[!0-9]*) die "cannot read versionCode of $f" ;; esac
  h="$(sha256_of "$f")" || die "cannot compute a valid SHA-256 of $f"
  say "$label: $(basename "$f") package=$p versionCode=$APK_VC sha256=$h"
}
check_installed() { # serial pkg apk label : version (no downgrade) + signature
  local s="$1" pkg="$2" apk="$3" label="$4" ivc ivn
  ivc="$(inst_vc "$s" "$pkg")" || ivc=""
  [ -n "$ivc" ] || die "$label: $pkg is not installed on $s (this helper only upgrades; never first-installs)"
  ivn="$(inst_vn "$s" "$pkg")" || ivn=""
  say "$label: installed versionName=$ivn versionCode=$ivc -> new versionCode=$APK_VC"
  if [ "$ivc" -gt "$APK_VC" ] && [ "$ALLOW_DOWN" = 0 ]; then die "$label: downgrade ($ivc > $APK_VC); --allow-downgrade is NOT recommended (Room refuses older schemas)"; fi
  sig_check "$s" "$pkg" "$apk" "$label"
  if [ "$GO" = 1 ] && [ "$label" = phone ] && [ -f "$TMP/installed-phone.apk" ]; then cp "$TMP/installed-phone.apk" "$TMP/prev-phone.apk" || fail "cannot copy the installed phone APK"; fi
  return 0
}

# ---------- steps ----------
do_backup() {
  local t n sz db bytes h ph apksha vn vc stop_st hdr
  BACKUP_TAR="$BACKUP_DIR/pca-phone-pre-$TAG.tar"; PREV_APK="$BACKUP_DIR/pca-phone-pre-$TAG.apk"
  say "STEP backup: force-stop $PHONE_PKG (verified gone), tar c . via run-as -> $BACKUP_TAR (0600; validated with tar -t, listing, size, header, sha256)"
  [ "$GO" = 1 ] || return 0
  PHASE=backup
  if [ "$PLAIN_OK" = 1 ]; then is_emu "$PHONE" || die "--allow-plaintext-db on a non-emulator serial"; fi
  [ ! -e "$BACKUP_TAR" ] || fail "backup file already exists: $BACKUP_TAR (pick another --tag)"
  mkdir -p "$BACKUP_DIR" || fail "cannot create $BACKUP_DIR"
  call_guard backup || die "call active or state unknown"
  stop_st=0; stop_app || stop_st=$?
  [ "$stop_st" = 0 ] || die "cannot confirm the app is stopped (stop_app status $stop_st); backup not taken"
  A "$PHONE" exec-out run-as "$PHONE_PKG" tar c . > "$BACKUP_TAR" 2>"$TMP/tar.err" || fail "run-as tar failed (is the installed build debuggable?)"
  chmod 600 "$BACKUP_TAR" || fail "cannot chmod 600 $BACKUP_TAR"
  t="$TMP/bk"; mkdir -p "$t" || fail "cannot create temp dir"
  tar tf "$BACKUP_TAR" > "$TMP/list.txt" 2>/dev/null || fail "backup validation: tar -t could not read the archive to its end"
  grep -Eq '(^|/)databases/context\.db$' "$TMP/list.txt" || fail "backup validation: databases/context.db missing from the tar"
  grep -Eq '(^|/)shared_prefs/db_key\.xml$' "$TMP/list.txt" || [ "$PLAIN_OK" = 1 ] || fail "backup validation: shared_prefs/db_key.xml missing from the tar"
  n="$(count_matches -E "$TMP/list.txt" 'context\.db-(wal|shm)$')" || fail "backup validation: cannot count wal/shm entries"
  bytes="$(fsize "$BACKUP_TAR")" || fail "cannot stat the backup tar"
  [ "$bytes" -ge 8192 ] || fail "backup validation: tar is only a few KB (failed snapshot)"
  tar xf "$BACKUP_TAR" -C "$t" ./databases/context.db 2>/dev/null || tar xf "$BACKUP_TAR" -C "$t" databases/context.db 2>/dev/null || fail "backup validation: cannot extract context.db"
  db="$t/databases/context.db"
  sz="$(fsize "$db")" || fail "backup validation: cannot stat context.db"
  if [ "$sz" -le 0 ]; then fail "backup validation: context.db is empty"; fi
  if [ $((sz % 4096)) -ne 0 ] && [ "$PLAIN_OK" = 0 ]; then fail "backup validation: context.db size $sz is not a multiple of 4096"; fi
  hdr="$(head -c 15 "$db")" || fail "backup validation: cannot read the context.db header"
  if [ "$hdr" = "SQLite format 3" ] && [ "$PLAIN_OK" = 0 ]; then fail "backup validation: context.db has a plaintext header (bad snapshot or unencrypted install)"; fi
  h="$(sha256_of "$BACKUP_TAR")" || fail "backup validation: cannot compute a valid SHA-256 of the tar"
  say "backup OK: $BACKUP_TAR bytes=$bytes entries=$(wc -l < "$TMP/list.txt" | tr -d ' ') wal/shm=$n context.db=$sz sha256=$h"
  if [ -f "$TMP/prev-phone.apk" ]; then
    cp "$TMP/prev-phone.apk" "$PREV_APK" || fail "cannot write $PREV_APK"
    chmod 600 "$PREV_APK" || fail "cannot chmod 600 $PREV_APK"
    ph="$(sha256_of "$PREV_APK")" || fail "cannot hash $PREV_APK"
    say "previous phone APK kept: $PREV_APK sha256=$ph"
  fi
  apksha="$(sha256_of "$APKS/phone-debug.apk")" || fail "cannot hash the new phone APK"
  vn="$(inst_vn "$PHONE" "$PHONE_PKG")" || vn=""
  vc="$(inst_vc "$PHONE" "$PHONE_PKG")" || vc=""
  [ -n "$vc" ] || fail "cannot read the installed versionCode for the metadata"
  { echo "tag=$TAG"; echo "tar_sha256=$h"; echo "installed_before: versionName=$vn versionCode=$vc"; echo "new_apk_sha256=$apksha"; } > "$BACKUP_DIR/pca-phone-pre-$TAG.notes.txt" || fail "cannot write the backup metadata"
  chmod 600 "$BACKUP_DIR/pca-phone-pre-$TAG.notes.txt" || fail "cannot chmod 600 the metadata"
  BACKUP_VALID=1
}

install_apk() { # serial apk role (phone|glasses|watch): the role must match the serial
  local s="$1" apk="$2" role="$3" flags="-r" out
  case "$role" in
    phone) [ "$s" = "$PHONE" ] && [ "$(basename "$apk")" = phone-debug.apk ] || fail "internal check: wrong serial/apk pairing for phone" ;;
    glasses) [ "$s" = "$GLASSES" ] && [ "$s" != "$PHONE" ] && [ "$(basename "$apk")" = glasses-debug.apk ] || fail "internal check: wrong serial/apk pairing for glasses" ;;
    watch) [ "$s" = "$WATCH" ] && [ "$s" != "$PHONE" ] && [ "$s" != "$GLASSES" ] && [ "$(basename "$apk")" = watch-debug.apk ] || fail "internal check: watch APK may only go to the watch serial" ;;
  esac
  if [ "$ALLOW_DOWN" = 1 ]; then flags="-r -d"; fi
  say "STEP install $role: adb -s $s install $flags $(basename "$apk")"
  [ "$GO" = 1 ] || return 0
  pre_install_guard "$role" "$s"
  call_guard "before $role install" || die "call active or state unknown"
  [ "$BACKUP_VALID" = 1 ] || fail "internal check: no validated backup, refusing to install"
  PHASE=installing
  # shellcheck disable=SC2086
  out="$(A "$s" install $flags "$apk" 2>&1 | tr -d '\r' | tail -n 1)" || out="adb failure"
  [ "$out" = Success ] || fail "$role install result: $out"
  PHASE=installed
  say "$role install: Success"
}

unverified_exit() { # label reason : the install HAPPENED but the log verification is incomplete. Never declares success (exit 3).
  echo "INSTALL DONE BUT NOT VERIFIED ($1: $2). Do NOT treat this as a success: verification is incomplete and the remaining steps were not run." >&2
  echo "Manual check (read the output yourself, nothing is printed here):" >&2
  echo "  adb -s $3 shell pidof $4" >&2
  echo "  adb -s $3 logcat -d -b all | grep -E 'FATAL EXCEPTION|Process: $4'" >&2
  echo "  adb -s $3 logcat -d | grep -iE \"Migration didn.t|SQLiteException|not a database|SQLCipher|Room\"" >&2
  echo "  then open the app and do the v17-upgrade-recovery.md section 3 checks." >&2
  exit 3
}

scan_logs() { # serial pkg label
  local s="$1" pkg="$2" label="$3" pid comp fatal mig lines
  local allf="$TMP/logcat-all-$label.txt" pidf="$TMP/logcat-pid-$label.txt"
  local fre="FATAL EXCEPTION|Process: ${pkg}[,: ]"
  local mre="Migration didn.t|SQLiteException|not a database|SQLCipher|IllegalStateException.*room|room.*(error|exception)"
  say "STEP verify $label: clear logcat, launch $pkg, wait ${SCAN_SECS}s, report counts of FATAL / Migration / Room / SQLCipher errors"
  [ "$GO" = 1 ] || return 0
  A "$s" logcat -c >/dev/null 2>&1 || fail "$label: cannot clear logcat"
  comp="$(A "$s" shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$pkg" 2>/dev/null | tr -d '\r' | tail -n 1)" || comp=""
  case "$comp" in "$pkg"/*) A "$s" shell am start -n "$comp" >/dev/null 2>&1 || fail "$label: could not launch $comp" ;;
    *) A "$s" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || fail "$label: no launcher activity for $pkg" ;; esac
  sleep "$SCAN_SECS" || fail "$label: sleep failed"
  pid="$(A "$s" shell pidof "$pkg" 2>/dev/null | tr -d '\r' | awk '{print $1}')" || pid=""
  say "$label: running=$([ -n "$pid" ] && echo yes || echo NO)"
  [ -n "$pid" ] || fail "$label: app is not running after ${SCAN_SECS}s"
  case "$pid" in *[!0-9]*) unverified_exit "$label" "unparseable pid" "$s" "$pkg" ;; esac
  # 1) retrieve (explicit status), 2) sanity (non-empty / long enough), 3) count (grep status 1 = no match, status 2 = error)
  A "$s" logcat -d -b all > "$allf" 2>/dev/null || unverified_exit "$label" "logcat retrieval failed" "$s" "$pkg"
  lines="$(wc -l < "$allf")" || unverified_exit "$label" "cannot measure the log" "$s" "$pkg"
  lines="${lines//[[:space:]]/}"
  case "$lines" in ''|*[!0-9]*) unverified_exit "$label" "cannot measure the log" "$s" "$pkg" ;; esac
  [ "$lines" -ge 5 ] || unverified_exit "$label" "logcat returned empty/short output ($lines lines) while the app is running" "$s" "$pkg"
  A "$s" logcat -d --pid="$pid" > "$pidf" 2>/dev/null || unverified_exit "$label" "per-process logcat retrieval failed" "$s" "$pkg"
  lines="$(wc -l < "$pidf")" || unverified_exit "$label" "cannot measure the per-process log" "$s" "$pkg"
  lines="${lines//[[:space:]]/}"
  case "$lines" in ''|*[!0-9]*) unverified_exit "$label" "cannot measure the per-process log" "$s" "$pkg" ;; esac
  [ "$lines" -ge 1 ] || unverified_exit "$label" "per-process logcat is empty while the app is running" "$s" "$pkg"
  fatal="$(count_matches -E "$allf" "$fre")" || unverified_exit "$label" "grep error while counting FATAL lines" "$s" "$pkg"
  mig="$(count_matches -iE "$pidf" "$mre")" || unverified_exit "$label" "grep error while counting Migration/Room lines" "$s" "$pkg"
  say "$label log scan: running=yes fatal_count=$fatal migration_room_sqlcipher_error_count=$mig"
  [ "$fatal" = 0 ] || fail "$label: $fatal FATAL lines"
  [ "$mig" = 0 ] || fail "$label: $mig Migration/Room/SQLCipher error lines"
}

# ---------- refusal fixtures (function-level overrides, no adb, no device) ----------
FX_PASS=0 FX_FAIL=0
fx() { # name want(ok|refuse) fn : runs fn in a subshell
  local name="$1" want="$2" fn="$3" rc=0
  ( "$fn" ) >/dev/null 2>&1 || rc=$?
  if { [ "$want" = ok ] && [ "$rc" = 0 ]; } || { [ "$want" = refuse ] && [ "$rc" != 0 ]; }; then FX_PASS=$((FX_PASS + 1)); echo "  fixture ok      : $name"
  else FX_FAIL=$((FX_FAIL + 1)); echo "  FIXTURE FAILED  : $name (wanted $want, rc=$rc)"; fi
}
cg() { PHONE=emulator-5554; SELFTEST=0; A() { printf '%b' "$FX_OUT"; return "${FX_RC:-0}"; }; call_guard fx; }
cgo() { PHONE=emulator-5554; SELFTEST=1; A() { printf 'mCallState=0\n'; }; PCA_SELFTEST_CALLSTATE="$FX_OVR"; call_guard fx; }
cgo_real() { PHONE=R58XXXX; SELFTEST=0; A() { printf 'mCallState=2\n'; }; PCA_SELFTEST_CALLSTATE=0; call_guard fx; }
stp() { PHONE=emulator-5554; RETRY_SLEEP=0; A() { case "$*" in *force-stop*) printf '%b' "$FX_FS";; *pidof*) printf '%b' "$FX_PID"; return "${FX_RC:-0}";; esac; }; stop_app; }
sl() { # post-install log verification fixture: FX_LA/FX_LAR = all-buffer log + status, FX_LP/FX_LPR = per-pid log + status, FX_PIDOUT = pidof output
  PHONE=emulator-5554; GO=1; SCAN_SECS=0; TMP="$FXDIR"
  A() { shift; case "$*" in
      "logcat -c") return 0 ;;
      *resolve-activity*) printf 'com.x/.Main\n' ;;
      *"am start"*|*monkey*) return 0 ;;
      *pidof*) printf '%b' "$FX_PIDOUT" ;;
      *"logcat -d -b all"*) printf '%b' "$FX_LA"; return "${FX_LAR:-0}" ;;
      *"logcat -d --pid"*) printf '%b' "$FX_LP"; return "${FX_LPR:-0}" ;;
      *) return 0 ;; esac; }
  scan_logs emulator-5554 com.x phone
}
fxdev() { # name ser aid model fp chars
  eval "FXI_$1_ser=\"\$2\" FXI_$1_aid=\"\$3\" FXI_$1_model=\"\$4\" FXI_$1_fp=\"\$5\" FXI_$1_chars=\"\$6\""
}
fx_adb() { # mock adb: A <name> shell ...
  local k="$1"; shift
  eval "case \"\$*\" in
    'shell getprop ro.serialno') printf '%s\\n' \"\${FXI_${k}_ser-}\" ;;
    'shell getprop ro.boot.serialno') printf '%s\\n' \"\${FXI_${k}_bser-}\" ;;
    'shell settings get secure android_id') printf '%s\\n' \"\${FXI_${k}_aid-}\" ;;
    'shell getprop ro.product.model') printf '%s\\n' \"\${FXI_${k}_model-}\" ;;
    'shell getprop ro.build.fingerprint') printf '%s\\n' \"\${FXI_${k}_fp-}\" ;;
    'shell getprop ro.build.characteristics') printf '%s\\n' \"\${FXI_${k}_chars-}\" ;;
    'shell pm list features') printf '%s\\n' \"\${FXI_${k}_feat-}\" ;;
    *) return 1 ;; esac"
}
iso() { A() { fx_adb "$@"; }; PHONE=phn GLASSES=gls WATCH="${FX_W-}"; isolation_check fx; }
iso_base() { iso; ID_PHONE="$N_PHONE" ID_GLASSES="$N_GLASSES" ID_WATCH="$N_WATCH"; }
iso_changed() { iso_base; FXI_gls_aid=changedid0001; pre_install_guard glasses gls; }
iso_unchanged() { iso_base; pre_install_guard glasses gls; }
iso_watch_install() { FX_W=wch; iso_base; pre_install_guard watch wch; }
iso_alias_late() { iso_base; FXI_gls_ser="${FXI_phn_ser}"; FXI_gls_aid="${FXI_phn_aid}"; pre_install_guard glasses gls; }
sl_sedfail() { sed() { return 1; }; stp; }
sl_awkfail() { awk() { return 1; }; stp; }
sl_grepfail() { grep() { return 2; }; sl; }
sha_fx() { hash_cmd() { printf '%b' "$FX_OUT"; return "${FX_RC:-0}"; }; sha256_of x; }
dsk() { PHONE=emulator-5554; BACKUP_DIR="$FXDIR/b"; A() { printf '%b' "$FX_OUT"; return "${FX_RC:-0}"; }; free_kb() { printf '%s\n' "$FX_FREE"; }; disk_check; }
sg() { # signature fixture
  PHONE=emulator-5554; TMP="$FXDIR"; GO="${FX_GO:-1}"; APKSIGNER="${FX_SIGNER-/x/apksigner}"; ACCEPT_UNSIG="${FX_ACC:-0}"
  apk_cert() { case "$1" in *installed*) printf '%s' "$FX_HAVE";; *) printf '%s' "$FX_WANT";; esac; [ -n "$FX_WANT" ] || return 1; }
  A() { echo called >> "$FXDIR/adbcalls"; case "$*" in *"shell pm path"*) printf 'package:/data/app/x/base.apk\n' ;; *pull*) return "${FX_PULL:-0}" ;; esac; }
  sig_check emulator-5554 com.x /new.apk phone
}
va() { # arg-rule fixture
  PHONE="${FX_PHONE-emulator-5554}"; GLASSES="${FX_GL-}"; WATCH=""; APKS="$FXDIR"; TAG=t; GO="${FX_GO:-0}"; SELFTEST="${FX_ST:-0}"; PLAIN_OK="${FX_PL:-0}"
  ACCEPT_UNSIG="${FX_ACC:-0}"; UNDERSTAND_NOSIG="${FX_UND:-0}"; ALLOW_EMU="${FX_AE:-0}"
  if [ -n "${FX_ENV-}" ]; then PCA_SELFTEST_CALLSTATE="$FX_ENV"; else unset PCA_SELFTEST_CALLSTATE; fi
  validate_args
}
run_fixtures() {
  FXDIR="$(mktemp -d "${TMPDIR:-/tmp}/install-trip-fx.XXXXXX")" || die "no temp dir for fixtures"
  say "fixtures (no adb, no device):"
  local ok='mCallState=0\n'
  export FX_LA FX_LAR FX_LP FX_LP FX_LPR FX_PIDOUT FX_OUT FX_RC FX_FS FX_PID FX_FREE FX_OVR FX_GO FX_SIGNER FX_ACC FX_HAVE FX_WANT FX_PULL FX_PHONE FX_GL FX_ST FX_PL FX_UND FX_AE FX_ENV
  FX_RC=0
  FX_OUT="  mCallState=0\n  mCallState=0\n";            fx "call guard: all slots 0 passes" ok cg
  FX_OUT='mCallState=0junk\n';                          fx "call guard: mCallState=0junk" refuse cg
  FX_OUT='  mCallState=0 mCallState=2\n';               fx "call guard: '0 mCallState=2' on one line" refuse cg
  FX_OUT='mCallState=0\nmCallState=2\n';                fx "call guard: differing values" refuse cg
  FX_OUT='mCallState=1\n';                              fx "call guard: ringing" refuse cg
  FX_OUT='';                                            fx "call guard: empty output" refuse cg
  FX_OUT='nothing here\n';                              fx "call guard: no mCallState at all" refuse cg
  FX_OUT=$ok; FX_RC=1;                                  fx "call guard: adb failure with idle-looking output" refuse cg
  FX_RC=0
  FX_OVR=2; fx "call guard: self-test override 2 refuses" refuse cgo
  FX_OVR=0; fx "call guard: self-test override 0 passes" ok cgo
  FX_OVR='0junk'; fx "call guard: self-test override 0junk refuses" refuse cgo
  fx "call guard: override ignored on a non-emulator" refuse cgo_real
  FX_FS='__PCA_RC=0\n'; FX_PID='__PCA_RC=1\n'; FX_RC=0;       fx "stop: pidof exit 1 + empty = gone" ok stp
  FX_PID='1234\n__PCA_RC=0\n';                                 fx "stop: process still running after retries" refuse stp
  FX_PID='';  FX_RC=1;                                         fx "stop: pidof adb/transport failure" refuse stp
  FX_PID='error: device offline\n'; FX_RC=1;                   fx "stop: pidof transport error text, no marker" refuse stp
  FX_PID='error: closed\n__PCA_RC=1\n'; FX_RC=0;               fx "stop: pidof exit 1 but non-empty output" refuse stp
  FX_PID='__PCA_RC=127\n';                                     fx "stop: pidof exit 127" refuse stp
  FX_FS='__PCA_RC=1\n'; FX_PID='__PCA_RC=1\n';                 fx "stop: force-stop failed" refuse stp
  FX_FS='';                                                    fx "stop: force-stop no marker" refuse stp
  FX_FS='__PCA_RC=0\n'; FX_PID='diag: warning text\n__PCA_RC=1\n'; FX_RC=0; fx "stop: diagnostic text + marker, status 1 (not provably empty)" refuse stp
  FX_PID='__PCA_RC=1\n__PCA_RC=1\n';                          fx "stop: duplicate markers" refuse stp
  FX_PID='__PCA_RC=0\n__PCA_RC=1\n';                          fx "stop: conflicting duplicate markers" refuse stp
  FX_PID='__PCA_RC=1\r\n';                                    fx "stop: CRLF + single marker = gone" ok stp
  FX_PID='1234\r\n__PCA_RC=0\r\n';                           fx "stop: CRLF, rc=0 with a pid" refuse stp
  FX_PID='5678\n__PCA_RC=0\n';                                fx "stop: rc=0 with a pid" refuse stp
  FX_PID='no marker at all\n';                                fx "stop: no marker" refuse stp
  FX_PID='__PCA_RC=\n';                                        fx "stop: empty marker value" refuse stp
  FX_PID='__PCA_RC=1 trailing\n';                              fx "stop: marker with trailing junk" refuse stp
  FX_PID='__PCA_RC=1\nafter\n';                                fx "stop: marker not last" refuse stp
  FX_PID='diag\n__PCA_RC=1\n';                                 fx "stop: sed mocked to fail (diag + marker 1)" refuse sl_sedfail
  FX_PID='__PCA_RC=1\n';                                       fx "stop: sed mocked to fail (clean marker 1)" refuse sl_sedfail
  FX_PID='diag\n__PCA_RC=1\n';                                 fx "stop: awk mocked to fail (diag + marker 1)" refuse sl_awkfail
  FX_PID='__PCA_RC=1\n';                                       fx "stop: awk mocked to fail does not break a clean proof" ok sl_awkfail
  FX_PID='__PCA_RC=1\n'; FX_RC=0
  FX_OUT="$(printf '0123456789abcdef%.0s' 1 2 3 4)  x\n";      fx "sha256: valid digest" ok sha_fx
  FX_OUT='garbage  x\n';                                       fx "sha256: junk digest" refuse sha_fx
  FX_OUT='abc123  x\n';                                        fx "sha256: short digest" refuse sha_fx
  FX_OUT='';                                                   fx "sha256: empty output" refuse sha_fx
  FX_OUT="$(printf '0123456789abcdef%.0s' 1 2 3 4)  x\n"; FX_RC=1; fx "sha256: tool failure with a valid-looking digest" refuse sha_fx
  FX_RC=0; FX_PIDOUT='4242\n'; FX_LA='l1\nl2\nl3\nl4\nl5\nl6\n'; FX_LAR=0; FX_LP='p1\np2\n'; FX_LPR=0
  fx "logs: clean text passes" ok sl
  FX_LAR=7; fx "logs: all-buffer logcat fails (rc 7)" refuse sl
  FX_LAR=0; FX_LPR=7; fx "logs: per-pid logcat fails (rc 7)" refuse sl
  FX_LPR=0; FX_LA=''; fx "logs: all-buffer logcat returns nothing" refuse sl
  FX_LA='l1\nl2\n'; fx "logs: all-buffer logcat too short" refuse sl
  FX_LA='l1\nl2\nl3\nl4\nl5\nl6\n'; FX_LP=''; fx "logs: per-pid logcat returns nothing" refuse sl
  FX_LP='p1\n'; FX_LA='l1\nAndroidRuntime: FATAL EXCEPTION: main\nl3\nl4\nl5\nl6\n'; fx "logs: FATAL EXCEPTION match refuses" refuse sl
  FX_LA='l1\nl2\nl3\nl4\nl5\nl6\n'; FX_LP='p1\nMigration didn t work\n'; fx "logs: Migration match refuses" refuse sl
  FX_LP='p1\nnet.zetetic SQLCipher failure\n'; fx "logs: SQLCipher match refuses" refuse sl
  FX_LP='p1\nroom error here\n'; fx "logs: Room error match refuses" refuse sl
  FX_LP='p1\np2\n'; fx "logs: grep error (status 2) is not 'no matches'" refuse sl_grepfail
  FX_PIDOUT=''; fx "logs: app not running" refuse sl
  FX_PIDOUT='4242\n'; fx "logs: clean again after the refusals" ok sl
  FX_RC=0
  FX_OUT='4096\t.\n__PCA_RC=0\n'; FX_FREE=100000;              fx "disk: enough space" ok dsk
  FX_FREE=12000;                                               fx "disk: free < 3x data" refuse dsk
  FX_FREE=12287;                                               fx "disk: free just under 3x" refuse dsk
  FX_OUT='junk\n__PCA_RC=0\n'; FX_FREE=100000;                 fx "disk: unparseable du" refuse dsk
  FX_OUT='4096\t.\n__PCA_RC=1\n';                              fx "disk: du exit 1" refuse dsk
  FX_OUT='';                                                   fx "disk: du no output" refuse dsk
  FX_OUT='4096\t.\n__PCA_RC=0\n'; FX_RC=1;                     fx "disk: adb failure" refuse dsk
  FX_RC=0
  FX_GO=1; FX_ACC=0; FX_SIGNER=/x/apksigner; FX_WANT=aa; FX_HAVE=aa; FX_PULL=0
  fx "signature: match passes" ok sg
  FX_SIGNER='';                                                fx "signature: apksigner missing" refuse sg
  FX_SIGNER=/x/apksigner; FX_PULL=1;                           fx "signature: installed APK pull fails" refuse sg
  FX_PULL=0; FX_HAVE='';                                       fx "signature: installed cert unreadable" refuse sg
  FX_HAVE=aa; FX_WANT='';                                      fx "signature: new cert unreadable" refuse sg
  FX_WANT=aa; FX_HAVE=bb;                                      fx "signature: certificate mismatch" refuse sg
  FX_ACC=1; FX_HAVE=bb;                                        fx "signature: mismatch is NOT overridable" refuse sg
  FX_HAVE=''; FX_PULL=1; FX_SIGNER='';                         fx "signature: override accepts missing apksigner" ok sg
  FX_GO=0; FX_ACC=0; FX_SIGNER=/x/apksigner; FX_HAVE=aa; FX_WANT=aa; FX_PULL=0; rm -f "$FXDIR/adbcalls"
  fx "signature: dry-run passes" ok sg
  if [ -e "$FXDIR/adbcalls" ]; then FX_FAIL=$((FX_FAIL + 1)); echo "  FIXTURE FAILED  : dry-run made adb calls"; else FX_PASS=$((FX_PASS + 1)); echo "  fixture ok      : dry-run pulls nothing, no adb call"; fi
  FX_GO=1; FX_ACC=0; FX_PHONE=R58XXXX; FX_GL=R59YYYY; FX_ST=0; FX_PL=0; FX_UND=0; FX_AE=0; FX_ENV=
  fx "args: real serials without --go ok" ok va
  FX_ST=1; fx "args: --self-test on a real serial hard-refused" refuse va
  FX_ST=0; FX_PL=1; fx "args: --allow-plaintext-db without --self-test" refuse va
  FX_PL=0; FX_PHONE=emulator-5554; FX_ST=1; FX_PL=1; FX_GL=; fx "args: self-test + plaintext on emulator ok" ok va
  FX_ST=0; FX_PL=0; FX_PHONE=R58XXXX; FX_GL=R59YYYY; FX_ENV=0; fx "args: PCA_SELFTEST_CALLSTATE on a real serial" refuse va
  FX_ENV=; FX_ACC=1; FX_UND=0; fx "args: --accept-unverified-signature + --go on real devices needs the second flag" refuse va
  FX_UND=1; fx "args: both override flags + --go on real devices ok" ok va
  FX_ACC=0; FX_UND=1; fx "args: second flag alone refused" refuse va
  FX_UND=0; FX_GO=1; FX_PHONE=emulator-5554; FX_GL=R59YYYY; FX_AE=0; fx "args: emulator --go without --allow-emulator" refuse va
  fxdev phn PHSER1234 aid0000phone0001 PixelX fp/phone/1 phone; fxdev gls GLSER5678 aid0000glass0002 Glasses fp/glasses/1 ''
  fxdev wch WCSER9999 aid0000watch0003 Watch1 fp/watch/1 watch; FXI_wch_feat=android.hardware.type.watch; FX_W=
  fx "identity: distinct phone and glasses pass" ok iso
  FX_W=wch; fx "identity: distinct phone, glasses and watch pass" ok iso
  fxdev wal PHSER1234 aid0000phone0001 PixelX fp/phone/1 phone
  FX_W=wal; fx "identity: watch serial is an alias of the phone (same identity) refuses" refuse iso
  fxdev wal PHSER1234 aid0000other9999 PixelX fp/phone/1 phone; fx "identity: alias via equal hardware serial refuses" refuse iso
  fxdev wal OTHERSER12 aid0000phone0001 PixelX fp/phone/1 phone; fx "identity: alias via equal android_id refuses" refuse iso
  FX_W=
  FXI_gls_ser=PHSER1234; FXI_gls_aid=aid0000phone0001; fx "identity: glasses aliases the phone refuses" refuse iso
  FXI_gls_ser=GLSER5678; FXI_gls_aid=aid0000glass0002
  FXI_phn_ser=''; fx "identity: empty hardware serial refuses" refuse iso
  FXI_phn_ser=unknown; fx "identity: 'unknown' serial refuses" refuse iso
  FXI_phn_ser=PHSER1234; FXI_phn_aid=null; fx "identity: android_id 'null' refuses" refuse iso
  FXI_phn_aid=''; fx "identity: empty android_id refuses" refuse iso
  FXI_phn_aid=aid0000phone0001; FXI_phn_fp=''; fx "identity: empty fingerprint refuses" refuse iso
  FXI_phn_fp=fp/phone/1; FXI_phn_ser=''; FXI_phn_bser=BOOTSER77; fx "identity: ro.boot.serialno fallback passes" ok iso
  FXI_phn_ser=PHSER1234; FXI_phn_bser=
  fx "identity: unchanged between preflight and install passes" ok iso_unchanged
  fx "identity: changes between preflight and install refuses" refuse iso_changed
  fx "identity: becomes an alias of the phone before install refuses" refuse iso_alias_late
  fx "watch: real watch distinct from the phone installs" ok iso_watch_install
  FXI_wch_chars=phone; FXI_wch_feat=; fx "watch: target is not a watch refuses" refuse iso_watch_install
  FXI_wch_chars=nosdcard,watch; fx "watch: ro.build.characteristics with watch passes" ok iso_watch_install
  FXI_wch_chars=; FXI_wch_feat=android.hardware.type.watch; fx "watch: pm features with watch passes" ok iso_watch_install
  FXI_wch_chars=phone; FXI_wch_feat=; fx "watch: no watch markers refuses" refuse iso_watch_install
  FXI_wch_chars=watch
  say "fixtures: $FX_PASS passed, $FX_FAIL failed"
  [ "$FX_FAIL" = 0 ]
}

# ---------- main ----------
while [ $# -gt 0 ]; do
  case "$1" in
    --phone) PHONE="${2:-}"; shift 2 ;;
    --glasses) GLASSES="${2:-}"; shift 2 ;;
    --watch) WATCH="${2:-}"; shift 2 ;;
    --apks) APKS="${2:-}"; shift 2 ;;
    --tag) TAG="${2:-}"; shift 2 ;;
    --go) GO=1; shift ;;
    --allow-emulator) ALLOW_EMU=1; shift ;;
    --allow-downgrade) ALLOW_DOWN=1; shift ;;
    --self-test) SELFTEST=1; shift ;;
    --allow-plaintext-db) PLAIN_OK=1; shift ;;
    --accept-unverified-signature) ACCEPT_UNSIG=1; shift ;;
    --i-understand-no-signature-check) UNDERSTAND_NOSIG=1; shift ;;
    --fixtures) FIXTURES=1; shift ;;
    -h|--help) usage ;;
    *) die "unknown argument: $1" ;;
  esac
done

if [ "$FIXTURES" = 1 ]; then
  run_fixtures || exit 1
  exit 0
fi

validate_args
if [ "$ACCEPT_UNSIG" = 1 ]; then echo "!!!!! --accept-unverified-signature IS ACTIVE: signature problems will NOT stop this run !!!!!"; fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-}"
if [ -z "$ADB" ] && [ -x "$SDK/platform-tools/adb" ]; then ADB="$SDK/platform-tools/adb"; fi
if [ -z "$ADB" ]; then ADB="$(command -v adb || true)"; fi
if [ -z "$ADB" ] || [ ! -x "$ADB" ]; then die "adb not found (set ADB or ANDROID_HOME)"; fi
BT=""
if [ -d "$SDK/build-tools" ]; then BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1 || true)"; BT="${BT%/}"; fi
if [ -n "$BT" ] && [ -x "$BT/aapt2" ]; then AAPT="$BT/aapt2"; elif [ -n "$BT" ] && [ -x "$BT/aapt" ]; then AAPT="$BT/aapt"; fi
if [ -z "$AAPT" ]; then AAPT="$(command -v aapt2 || command -v aapt || true)"; fi
if [ -n "$BT" ] && [ -x "$BT/apksigner" ]; then APKSIGNER="$BT/apksigner"; else APKSIGNER="$(command -v apksigner || true)"; fi
APKANALYZER="$(command -v apkanalyzer || true)"
if [ -z "$AAPT" ] && [ -z "$APKANALYZER" ]; then die "neither aapt/aapt2 nor apkanalyzer found: cannot verify package names"; fi

if [ "$SELFTEST" = 1 ]; then run_fixtures || fail "refusal fixtures failed"; cleanup; fi
if [ "$GO" = 1 ]; then TMP="$(mktemp -d "${TMPDIR:-/tmp}/install-trip.XXXXXX")" || die "no temp dir"; fi

DEVS="$("$ADB" devices 2>/dev/null | tr -d '\r' | awk 'NR>1 && $2=="device"{print $1}')" || DEVS=""
for s in "$PHONE" "$GLASSES" "$WATCH"; do
  if [ -n "$s" ] && ! printf '%s\n' "$DEVS" | grep -qx -- "$s"; then die "serial '$s' is not listed (state device) in 'adb devices'"; fi
done

isolation_check preflight
ID_PHONE="$N_PHONE" ID_GLASSES="$N_GLASSES" ID_WATCH="$N_WATCH"
if [ -n "$WATCH" ]; then watch_check "$WATCH"; fi
say "mode: $([ "$GO" = 1 ] && echo GO || echo DRY-RUN) tag=$TAG self-test=$SELFTEST"
say "phone=$PHONE glasses=${GLASSES:-none} watch=${WATCH:-none}"
check_apk "$APKS/phone-debug.apk" "$PHONE_PKG" phone; PHONE_VC="$APK_VC"
check_installed "$PHONE" "$PHONE_PKG" "$APKS/phone-debug.apk" phone
if [ -n "$GLASSES" ]; then
  check_apk "$APKS/glasses-debug.apk" "$GLASSES_PKG" glasses
  check_installed "$GLASSES" "$GLASSES_PKG" "$APKS/glasses-debug.apk" glasses
fi
if [ -n "$WATCH" ]; then
  check_apk "$APKS/watch-debug.apk" "$WATCH_PKG" watch; WATCH_VC="$APK_VC"
  check_installed "$WATCH" "$WATCH_PKG" "$APKS/watch-debug.apk" watch
  say "watch: watch-debug.apk shares applicationId $WATCH_PKG with the phone app; it will be installed ONLY to $WATCH"
fi
call_guard preflight || die "call guard"
disk_check || die "disk preflight"

do_backup
install_apk "$PHONE" "$APKS/phone-debug.apk" phone
scan_logs "$PHONE" "$PHONE_PKG" phone
if [ "$GO" = 1 ]; then
  nvc="$(inst_vc "$PHONE" "$PHONE_PKG")" || nvc=""
  [ "$nvc" = "$PHONE_VC" ] || fail "phone installed versionCode is '$nvc', expected $PHONE_VC"
  say "phone verified: installed versionCode=$nvc"
fi
if [ -n "$GLASSES" ]; then
  install_apk "$GLASSES" "$APKS/glasses-debug.apk" glasses
  scan_logs "$GLASSES" "$GLASSES_PKG" glasses
fi
if [ -n "$WATCH" ]; then
  install_apk "$WATCH" "$APKS/watch-debug.apk" watch
  if [ "$GO" = 1 ]; then
    wvc="$(inst_vc "$WATCH" "$WATCH_PKG")" || wvc=""
    [ "$wvc" = "$WATCH_VC" ] || fail "watch installed versionCode is '$wvc', expected $WATCH_VC"
    say "watch verified: installed versionCode=$wvc"
  fi
fi
if [ "$GO" = 1 ]; then say "DONE. Backup: $BACKUP_TAR (keep until the trip is over). Open the app and do the manual checks (v17-upgrade-recovery.md section 3)."
else say "DRY-RUN complete: nothing was changed or written. Re-run with --go to execute."; fi
exit 0
