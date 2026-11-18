#!/usr/bin/env bash
# Read-only check of a local signed release APK. Usage: release/verify-release.sh <apk> [expected-package]
# Needs aapt2, apksigner (Android build-tools; set BUILD_TOOLS=/path/to/build-tools/36.0.0 if not on PATH).
# Fail closed: a missing or failing tool, or empty tool output, is a FAILURE, never "not debuggable" / "ok".
# Tool output is captured into variables with explicit status checks and parsed with grep on a here-string
# (no `producer | grep -q`, so an early grep exit cannot SIGPIPE the producer into a false result).
# Self-test with fake tools: release/test-verify-release.sh. PIN_FILE overrides the pin file (tests only).
set -euo pipefail

apk=${1:?usage: verify-release.sh <apk> [expected-package]}
want_pkg=${2:-}
here=$(cd "$(dirname "$0")" && pwd)
pin_file=${PIN_FILE:-$here/signer-sha256.txt}
bt=${BUILD_TOOLS:-}
aapt2=${bt:+$bt/}aapt2
apksigner=${bt:+$bt/}apksigner
# The debug key's certificate starts with this (rehearsal 2026-11-18); a release APK must never carry it as current signer.
DEBUG_PREFIX=61a831

fail() { echo "FAIL: $*" >&2; exit 1; }
[ -f "$apk" ] || fail "no such file: $apk"
for t in "$aapt2" "$apksigner"; do
  command -v "$t" > /dev/null 2>&1 || fail "required tool not found: $t"
done

pin=$(grep -v '^[[:space:]]*#' "$pin_file" | tr -d '[:space:]' | tr 'A-F' 'a-f') || fail "cannot read $pin_file"
grep -Eq '^[0-9a-f]{64}$' <<<"$pin" || fail "$pin_file is still a placeholder"

badging=$("$aapt2" dump badging "$apk") || fail "aapt2 dump badging failed"
[ -n "$badging" ] || fail "aapt2 dump badging produced no output"
grep -q "^package: name='" <<<"$badging" || fail "aapt2 output has no package line (unusable)"
pkg=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging")
vcode=$(sed -n "s/^package:.* versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging")
vname=$(sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p" <<<"$badging")
[ -n "$pkg" ] && [ -n "$vcode" ] || fail "could not read package or versionCode"
[ -z "$want_pkg" ] || [ "$pkg" = "$want_pkg" ] || fail "package is $pkg, expected $want_pkg"
if grep -Eq "application-debuggable|debuggable='(true|-1|0xffffffff)'" <<<"$badging"; then fail "APK is debuggable"; fi

certs=$("$apksigner" verify --print-certs "$apk") || fail "apksigner verify failed"
[ -n "$certs" ] || fail "apksigner verify --print-certs produced no output"
digests=$(sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p' <<<"$certs" | tr 'A-F' 'a-f')
[ "$(grep -c . <<<"$digests" || true)" = 1 ] || fail "expected exactly one signer"
[ "$digests" = "$pin" ] || fail "signer SHA-256 does not match release/signer-sha256.txt"
case "$digests" in "$DEBUG_PREFIX"*) fail "signed by the debug key" ;; esac

verbose=$("$apksigner" verify -v "$apk") || fail "apksigner verify -v failed"
[ -n "$verbose" ] || fail "apksigner verify -v produced no output"
grep -q 'Verified using v3 scheme (APK Signature Scheme v3): true' <<<"$verbose" || fail "no v3 signature"
# apksigner does not print the lineage itself. With --rotation-min-sdk-version 28 the rehearsal showed a single
# v3 block (v3.1 false); a v3.1 block means the flag was missed and API 24-32 devices would see the old key.
if grep -q 'Verified using v3.1 scheme (APK Signature Scheme v3.1): true' <<<"$verbose"; then
  fail "v3.1 block present: signed without --rotation-min-sdk-version 28"
fi
echo "package=$pkg versionCode=$vcode versionName=$vname"
echo "signer=$digests (matches pin)"
echo "v3 verified, no v3.1 block. Lineage itself: confirm on a device (dumpsys package: past signatures) per release/README.md."
echo "OK"
