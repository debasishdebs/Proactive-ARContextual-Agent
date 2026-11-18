#!/usr/bin/env bash
# SAFE install helper for the trip build (bash 3.2 compatible). DRY-RUN BY DEFAULT: real actions only with --go.
#   tools/install-trip-build.sh --phone <serial> --glasses <serial> [--watch <serial>] --apks <dir> [--tag <tag>] [--go]
#        [--allow-emulator] [--allow-downgrade]
#   tools/install-trip-build.sh --self-test --phone emulator-5554 --apks <dir> [--go]    (emulator only, phone only)
# Serials are never auto-detected and every adb call carries -s (only `adb devices` has none). Never uninstalls, never `pm clear`,
# never kills any process on the host. Prints counts, hashes and statuses only: no logcat line, token or pairing code.
# Procedure source: docs/superpowers/plans/handoffs/v17-upgrade-recovery.md. See tools/INSTALL-TRIP-BUILD.md.
set -u

PHONE_PKG=com.rokid.context
GLASSES_PKG=com.rokid.context.glasses
WATCH_PKG=com.rokid.context        # same applicationId as the phone app: hazard, see device-checklist A0
BACKUP_DIR="${PCA_BACKUP_DIR:-$HOME/Desktop/rokid/backups}"
SCAN_SECS="${PCA_SCAN_SECS:-60}"

PHONE="" GLASSES="" WATCH="" APKS="" TAG="" GO=0 ALLOW_EMU=0 ALLOW_DOWN=0 SELFTEST=0 PLAIN_OK=0
TMP=""
BACKUP_TAR="" PREV_APK=""

die()  { echo "REFUSED: $*" >&2; cleanup; exit 2; }
say()  { echo "$*"; }
cleanup() { [ -n "$TMP" ] && [ -d "$TMP" ] && rm -rf "$TMP"; TMP=""; }

usage() { sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

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
    -h|--help) usage ;;
    *) die "unknown argument: $1" ;;
  esac
done

is_emu() { case "$1" in emulator-*) return 0 ;; *) return 1 ;; esac; }

# ---------- argument rules ----------
[ "$PLAIN_OK" = 0 ] || [ "$SELFTEST" = 1 ] || die "--allow-plaintext-db is only valid with --self-test"
[ -n "$PHONE" ] || die "--phone <serial> is required (no auto-detect)"
[ -n "$APKS" ] || die "--apks <dir> is required"
[ -d "$APKS" ] || die "APK folder not found: $APKS"
if [ "$SELFTEST" = 1 ]; then
  [ -z "$WATCH" ] || die "--self-test is phone-only: no --watch"
  is_emu "$PHONE" || die "--self-test accepts only an emulator-* serial"
  { [ -z "$GLASSES" ] || is_emu "$GLASSES"; } || die "--self-test accepts only emulator-* serials"
  ALLOW_EMU=1
else
  [ -n "$GLASSES" ] || die "--glasses <serial> is required (no auto-detect)"
fi
[ -z "$GLASSES" ] || [ "$PHONE" != "$GLASSES" ] || die "phone and glasses serials are equal"
[ -z "$WATCH" ] || { [ "$WATCH" != "$PHONE" ] && [ "$WATCH" != "$GLASSES" ]; } || die "watch serial equals the phone or glasses serial (watch-debug.apk would REPLACE that app)"
if [ "$GO" = 1 ] && [ "$ALLOW_EMU" = 0 ]; then
  for s in "$PHONE" "$GLASSES" "$WATCH"; do
    [ -n "$s" ] && is_emu "$s" && die "serial $s looks like an emulator: add --allow-emulator to run --go on it"
  done
fi
[ -n "$TAG" ] || TAG="trip-$(date +%Y%m%d-%H%M)"
case "$TAG" in *[!A-Za-z0-9._-]*) die "tag may only contain letters, digits . _ -" ;; esac

# ---------- tools ----------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-}"
[ -n "$ADB" ] || { [ -x "$SDK/platform-tools/adb" ] && ADB="$SDK/platform-tools/adb"; }
[ -n "$ADB" ] || ADB="$(command -v adb || true)"
[ -n "$ADB" ] && [ -x "$ADB" ] || die "adb not found (set ADB or ANDROID_HOME)"
BT=""
if [ -d "$SDK/build-tools" ]; then BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"; BT="${BT%/}"; fi
AAPT=""; [ -n "$BT" ] && [ -x "$BT/aapt2" ] && AAPT="$BT/aapt2"; [ -z "$AAPT" ] && [ -n "$BT" ] && [ -x "$BT/aapt" ] && AAPT="$BT/aapt"
[ -n "$AAPT" ] || AAPT="$(command -v aapt2 || command -v aapt || true)"
APKSIGNER=""; [ -n "$BT" ] && [ -x "$BT/apksigner" ] && APKSIGNER="$BT/apksigner"; [ -z "$APKSIGNER" ] && APKSIGNER="$(command -v apksigner || true)"
APKANALYZER="$(command -v apkanalyzer || true)"
[ -n "$AAPT" ] || [ -n "$APKANALYZER" ] || die "neither aapt/aapt2 nor apkanalyzer found: cannot verify package names"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/install-trip.XXXXXX")" || die "no temp dir"
trap 'cleanup' EXIT

# A <serial> args... : the ONLY way adb is called with a device
A() { local s="$1"; shift; "$ADB" -s "$s" "$@"; }

print_rollback() {
  cat >&2 <<RB
--- ROLLBACK (if the failure came before the phone install step, nothing changed on the phone except a force-stop) (docs/superpowers/plans/handoffs/v17-upgrade-recovery.md section 4) ---
 Never uninstall, never pm clear. Not during a call.
 1. adb -s $PHONE shell am force-stop $PHONE_PKG   (confirm: adb -s $PHONE shell pidof $PHONE_PKG prints nothing)
 2. Preserve the broken state first: adb -s $PHONE exec-out run-as $PHONE_PKG tar c . > $BACKUP_DIR/pca-phone-broken-$TAG.tar  (then tar tf it)
    and on-device: adb -s $PHONE shell run-as $PHONE_PKG sh -c 'mkdir -p quarantine-$TAG && cp -p databases/context.db* quarantine-$TAG/ && cp -p shared_prefs/db_key.xml quarantine-$TAG/'
 3. Move (never delete) the live databases/context.db* into quarantine-$TAG/replaced/, then:
    cat ${BACKUP_TAR:-$BACKUP_DIR/pca-phone-pre-$TAG.tar} | adb -s $PHONE shell run-as $PHONE_PKG tar x
    adb -s $PHONE install -r ${PREV_APK:-<previous phone apk, same signature>}
 4. Open the app and run the section 3 checks. If it still fails: stop, delete nothing, ask for help.
 Glasses: adb -s <glasses> install -r <old 1.0.0 glasses apk> (staged in $BACKUP_DIR/apks-v100-*).
RB
}
fail() { echo "FAILED: $*" >&2; print_rollback; cleanup; exit 1; }

# ---------- APK helpers ----------
sha256_of() { shasum -a 256 "$1" | awk '{print $1}'; }
apk_pkg() { # package name
  if [ -n "$AAPT" ]; then "$AAPT" dump badging "$1" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1
  else "$APKANALYZER" manifest application-id "$1" 2>/dev/null | head -1; fi
}
apk_vc() { # versionCode
  if [ -n "$AAPT" ]; then "$AAPT" dump badging "$1" 2>/dev/null | sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p" | head -1
  else "$APKANALYZER" manifest version-code "$1" 2>/dev/null | head -1; fi
}
apk_cert() { [ -n "$APKSIGNER" ] && "$APKSIGNER" verify --print-certs "$1" 2>/dev/null | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1; }
inst_vc() { A "$1" shell dumpsys package "$2" 2>/dev/null | tr -d '\r' | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1; }
inst_vn() { A "$1" shell dumpsys package "$2" 2>/dev/null | tr -d '\r' | sed -n 's/.*versionName=//p' | head -1; }

# ---------- device presence ----------
DEVS="$("$ADB" devices 2>/dev/null | tr -d '\r' | awk 'NR>1 && $2=="device"{print $1}')"
has_dev() { echo "$DEVS" | grep -qx -- "$1"; }
for s in "$PHONE" "$GLASSES" "$WATCH"; do
  [ -z "$s" ] || has_dev "$s" || die "serial '$s' is not listed (state device) in 'adb devices'"
done

# ---------- call guard ----------
call_guard() { # $1 = label
  local out bad
  if [ "$SELFTEST" = 1 ] && [ -n "${PCA_SELFTEST_CALLSTATE:-}" ]; then out="mCallState=$PCA_SELFTEST_CALLSTATE"
  else out="$(A "$PHONE" shell dumpsys telephony.registry 2>/dev/null | tr -d '\r' | grep mCallState)"; fi
  [ -n "$out" ] || { echo "REFUSED: cannot read mCallState on the phone ($1)" >&2; return 1; }
  bad="$(echo "$out" | grep -v 'mCallState=0' | wc -l | tr -d ' ')"
  if [ "$bad" != 0 ]; then echo "REFUSED: a call is active or state unknown on the phone ($1)" >&2; return 1; fi
  say "call guard ($1): mCallState=0 on all slots"
}

# ---------- preflight ----------
check_apk() { # file pkg label -> sets APK_VC
  local f="$1" pkg="$2" label="$3" p
  [ -f "$f" ] || die "$label APK missing: $f"
  p="$(apk_pkg "$f")"; [ "$p" = "$pkg" ] || die "$label APK package is '$p', expected '$pkg'"
  APK_VC="$(apk_vc "$f")"; case "$APK_VC" in ''|*[!0-9]*) die "cannot read versionCode of $f" ;; esac
  say "$label: $(basename "$f") package=$p versionCode=$APK_VC sha256=$(sha256_of "$f")"
}
check_installed() { # serial pkg apk label : version (no downgrade) + signature
  local s="$1" pkg="$2" apk="$3" label="$4" ivc path want have
  ivc="$(inst_vc "$s" "$pkg")"
  [ -n "$ivc" ] || die "$label: $pkg is not installed on $s (this helper only upgrades; never first-installs)"
  say "$label: installed versionName=$(inst_vn "$s" "$pkg") versionCode=$ivc -> new versionCode=$APK_VC"
  if [ "$ivc" -gt "$APK_VC" ] && [ "$ALLOW_DOWN" = 0 ]; then die "$label: downgrade ($ivc > $APK_VC); --allow-downgrade is NOT recommended (Room refuses older schemas)"; fi
  path="$(A "$s" shell pm path "$pkg" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)"
  want="$(apk_cert "$apk")"
  if [ -n "$path" ] && [ -n "$want" ] && A "$s" pull "$path" "$TMP/installed-$label.apk" >/dev/null 2>&1; then
    have="$(apk_cert "$TMP/installed-$label.apk")"
    [ -n "$have" ] || { say "WARNING: $label: could not read the installed signature: signature NOT verified"; return 0; }
    [ "$have" = "$want" ] || die "$label: signing certificate differs (installed ${have%????????????????????????????????????????????????????????} vs new ${want%????????????????????????????????????????????????????????}); a different signature makes the encrypted DB unreadable"
    say "$label: signature matches installed (SHA-256 ${want%????????????????????????????????????????????????????????}...)"
    [ "$label" = phone ] && cp "$TMP/installed-phone.apk" "$TMP/prev-phone.apk"
  else
    say "WARNING: $label: signature NOT verified (apksigner missing or installed APK not pullable)"
  fi
  return 0
}

say "mode: $([ "$GO" = 1 ] && echo GO || echo DRY-RUN) tag=$TAG self-test=$SELFTEST"
say "phone=$PHONE glasses=${GLASSES:-none} watch=${WATCH:-none}"
check_apk "$APKS/phone-debug.apk" "$PHONE_PKG" phone; PHONE_VC="$APK_VC"
check_installed "$PHONE" "$PHONE_PKG" "$APKS/phone-debug.apk" phone
if [ -n "$GLASSES" ]; then
  check_apk "$APKS/glasses-debug.apk" "$GLASSES_PKG" glasses
  check_installed "$GLASSES" "$GLASSES_PKG" "$APKS/glasses-debug.apk" glasses
fi
if [ -n "$WATCH" ]; then
  check_apk "$APKS/watch-debug.apk" "$WATCH_PKG" watch
  check_installed "$WATCH" "$WATCH_PKG" "$APKS/watch-debug.apk" watch
  say "watch: watch-debug.apk shares applicationId $WATCH_PKG with the phone app; it will be installed ONLY to $WATCH"
fi
call_guard preflight || { cleanup; exit 2; }

# ---------- steps ----------
do_backup() {
  local t i pid n; BACKUP_TAR="$BACKUP_DIR/pca-phone-pre-$TAG.tar"; PREV_APK="$BACKUP_DIR/pca-phone-pre-$TAG.apk"
  say "STEP backup: force-stop $PHONE_PKG, tar c . via run-as -> $BACKUP_TAR (validated with tar -t, listing, size, header, sha256)"
  [ "$GO" = 1 ] || return 0
  [ ! -e "$BACKUP_TAR" ] || fail "backup file already exists: $BACKUP_TAR (pick another --tag)"
  mkdir -p "$BACKUP_DIR" || fail "cannot create $BACKUP_DIR"
  call_guard backup || fail "call active"
  for i in 1 2 3; do
    A "$PHONE" shell am force-stop "$PHONE_PKG" >/dev/null 2>&1; sleep 1
    pid="$(A "$PHONE" shell pidof "$PHONE_PKG" 2>/dev/null | tr -d '\r')"; [ -z "$pid" ] && break
  done
  [ -z "$pid" ] || fail "app still running after force-stop; backup not taken"
  A "$PHONE" exec-out run-as "$PHONE_PKG" tar c . > "$BACKUP_TAR" 2>"$TMP/tar.err" || fail "run-as tar failed (is the installed build debuggable?)"
  t="$TMP/bk"; mkdir -p "$t"
  tar tf "$BACKUP_TAR" > "$TMP/list.txt" 2>/dev/null || fail "backup validation: tar -t could not read the archive to its end"
  grep -Eq '(^|/)databases/context\.db$' "$TMP/list.txt" || fail "backup validation: databases/context.db missing from the tar"
  grep -Eq '(^|/)shared_prefs/db_key\.xml$' "$TMP/list.txt" || [ "$PLAIN_OK" = 1 ] || fail "backup validation: shared_prefs/db_key.xml missing from the tar"
  n="$(grep -cE 'context\.db-(wal|shm)$' "$TMP/list.txt" || true)"
  [ "$(stat -f%z "$BACKUP_TAR" 2>/dev/null || stat -c%s "$BACKUP_TAR")" -ge 8192 ] || fail "backup validation: tar is only a few KB (failed snapshot)"
  tar xf "$BACKUP_TAR" -C "$t" ./databases/context.db 2>/dev/null || tar xf "$BACKUP_TAR" -C "$t" databases/context.db 2>/dev/null || fail "backup validation: cannot extract context.db"
  local db="$t/databases/context.db" sz
  sz="$(stat -f%z "$db" 2>/dev/null || stat -c%s "$db")"
  [ "$sz" -gt 0 ] && { [ $((sz % 4096)) -eq 0 ] || [ "$PLAIN_OK" = 1 ]; } || fail "backup validation: context.db size $sz is not a multiple of 4096"
  [ "$(head -c 15 "$db")" != "SQLite format 3" ] || [ "$PLAIN_OK" = 1 ] || fail "backup validation: context.db has a plaintext header (bad snapshot or unencrypted install)"
  say "backup OK: $BACKUP_TAR bytes=$(stat -f%z "$BACKUP_TAR" 2>/dev/null || stat -c%s "$BACKUP_TAR") entries=$(wc -l < "$TMP/list.txt" | tr -d ' ') wal/shm=$n context.db=$sz sha256=$(sha256_of "$BACKUP_TAR")"
  [ -f "$TMP/prev-phone.apk" ] && cp "$TMP/prev-phone.apk" "$PREV_APK" && say "previous phone APK kept: $PREV_APK sha256=$(sha256_of "$PREV_APK")"
  { echo "tag=$TAG"; echo "tar_sha256=$(sha256_of "$BACKUP_TAR")"; echo "installed_before: versionName=$(inst_vn "$PHONE" "$PHONE_PKG") versionCode=$(inst_vc "$PHONE" "$PHONE_PKG")"; echo "new_apk_sha256=$(sha256_of "$APKS/phone-debug.apk")"; } > "$BACKUP_DIR/pca-phone-pre-$TAG.notes.txt"
}

install_apk() { # serial apk role (phone|glasses|watch): the role must match the serial
  local s="$1" apk="$2" role="$3" flags="-r" out
  case "$role" in
    phone) [ "$s" = "$PHONE" ] && [ "$(basename "$apk")" = phone-debug.apk ] || fail "internal check: wrong serial/apk pairing for phone" ;;
    glasses) [ "$s" = "$GLASSES" ] && [ "$s" != "$PHONE" ] && [ "$(basename "$apk")" = glasses-debug.apk ] || fail "internal check: wrong serial/apk pairing for glasses" ;;
    watch) [ "$s" = "$WATCH" ] && [ "$s" != "$PHONE" ] && [ "$s" != "$GLASSES" ] && [ "$(basename "$apk")" = watch-debug.apk ] || fail "internal check: watch APK may only go to the watch serial" ;;
  esac
  [ "$ALLOW_DOWN" = 1 ] && flags="-r -d"
  say "STEP install $role: adb -s $s install $flags $(basename "$apk")"
  [ "$GO" = 1 ] || return 0
  call_guard "before $role install" || fail "call active"
  # shellcheck disable=SC2086
  out="$(A "$s" install $flags "$apk" 2>&1 | tr -d '\r' | tail -1)"
  [ "$out" = Success ] || fail "$role install result: $out"
  say "$role install: Success"
}

scan_logs() { # serial pkg label
  local s="$1" pkg="$2" label="$3" pid fatal mig comp
  say "STEP verify $label: clear logcat, launch $pkg, wait ${SCAN_SECS}s, report counts of FATAL / Migration / Room errors"
  [ "$GO" = 1 ] || return 0
  A "$s" logcat -c >/dev/null 2>&1
  comp="$(A "$s" shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$pkg" 2>/dev/null | tr -d '\r' | tail -1)"
  case "$comp" in "$pkg"/*) A "$s" shell am start -n "$comp" >/dev/null 2>&1 || fail "$label: could not launch $comp" ;;
    *) A "$s" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || fail "$label: no launcher activity for $pkg" ;; esac
  sleep "$SCAN_SECS"
  pid="$(A "$s" shell pidof "$pkg" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  fatal="$(A "$s" logcat -d -b all 2>/dev/null | grep -cE "FATAL EXCEPTION|Process: ${pkg}[,: ]" || true)"
  if [ -n "$pid" ]; then
    mig="$(A "$s" logcat -d --pid="$pid" 2>/dev/null | grep -ciE "Migration didn.t|SQLiteException|not a database|IllegalStateException.*room|room.*(error|exception)" || true)"
  else mig="n/a"; fi
  say "$label log scan: running=$([ -n "$pid" ] && echo yes || echo NO) fatal_count=$fatal migration_room_error_count=$mig"
  [ -n "$pid" ] || fail "$label: app is not running after ${SCAN_SECS}s"
  [ "$fatal" = 0 ] || fail "$label: $fatal FATAL lines"
  [ "$mig" = 0 ] || fail "$label: $mig Migration/Room error lines"
}

do_backup
install_apk "$PHONE" "$APKS/phone-debug.apk" phone
scan_logs "$PHONE" "$PHONE_PKG" phone
if [ "$GO" = 1 ]; then
  nvc="$(inst_vc "$PHONE" "$PHONE_PKG")"; [ "$nvc" = "$PHONE_VC" ] || fail "phone installed versionCode is '$nvc', expected $PHONE_VC"
  say "phone verified: installed versionCode=$nvc"
fi
if [ -n "$GLASSES" ]; then
  install_apk "$GLASSES" "$APKS/glasses-debug.apk" glasses
  scan_logs "$GLASSES" "$GLASSES_PKG" glasses
fi
if [ -n "$WATCH" ]; then
  install_apk "$WATCH" "$APKS/watch-debug.apk" watch
  [ "$GO" = 1 ] && say "watch verified: installed versionCode=$(inst_vc "$WATCH" "$WATCH_PKG")"
fi
if [ "$GO" = 1 ]; then say "DONE. Backup: $BACKUP_TAR (keep until the trip is over). Open the app and do the manual checks (v17-upgrade-recovery.md section 3)."
else say "DRY-RUN complete: nothing was changed. Re-run with --go to execute."; fi
exit 0
