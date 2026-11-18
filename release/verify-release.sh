#!/usr/bin/env bash
# Read-only check of a local signed release APK. Usage: release/verify-release.sh <apk> [expected-package]
# Needs aapt2, apksigner (Android build-tools; set BUILD_TOOLS=/path/to/build-tools/36.0.0 if not on PATH).
set -euo pipefail

apk=${1:?usage: verify-release.sh <apk> [expected-package]}
want_pkg=${2:-}
here=$(cd "$(dirname "$0")" && pwd)
bt=${BUILD_TOOLS:-}
aapt2=${bt:+$bt/}aapt2
apksigner=${bt:+$bt/}apksigner
# The debug key's certificate starts with this (rehearsal 2026-11-18); a release APK must never carry it as current signer.
DEBUG_PREFIX=61a831

fail() { echo "FAIL: $*" >&2; exit 1; }
[ -f "$apk" ] || fail "no such file: $apk"

pin=$(grep -v '^[[:space:]]*#' "$here/signer-sha256.txt" | tr -d '[:space:]' | tr 'A-F' 'a-f')
printf '%s' "$pin" | grep -Eq '^[0-9a-f]{64}$' || fail "release/signer-sha256.txt is still a placeholder"

badging=$("$aapt2" dump badging "$apk")
pkg=$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
vcode=$(printf '%s\n' "$badging" | sed -n "s/^package:.* versionCode='\([0-9]*\)'.*/\1/p")
vname=$(printf '%s\n' "$badging" | sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p")
[ -n "$pkg" ] && [ -n "$vcode" ] || fail "could not read package or versionCode"
[ -z "$want_pkg" ] || [ "$pkg" = "$want_pkg" ] || fail "package is $pkg, expected $want_pkg"
if printf '%s\n' "$badging" | grep -q "application-debuggable"; then fail "APK is debuggable"; fi

certs=$("$apksigner" verify --print-certs "$apk") || fail "apksigner verify failed"
digests=$(printf '%s\n' "$certs" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p' | tr 'A-F' 'a-f')
[ "$(printf '%s\n' "$digests" | grep -c .)" = 1 ] || fail "expected exactly one signer"
[ "$digests" = "$pin" ] || fail "signer SHA-256 does not match release/signer-sha256.txt"
case "$digests" in "$DEBUG_PREFIX"*) fail "signed by the debug key" ;; esac

verbose=$("$apksigner" verify -v "$apk")
printf '%s\n' "$verbose" | grep -q 'Verified using v3 scheme (APK Signature Scheme v3): true' || fail "no v3 signature"
# apksigner does not print the lineage itself. With --rotation-min-sdk-version 28 the rehearsal showed a single
# v3 block (v3.1 false); a v3.1 block means the flag was missed and API 24-32 devices would see the old key.
if printf '%s\n' "$verbose" | grep -q 'Verified using v3.1 scheme (APK Signature Scheme v3.1): true'; then
  fail "v3.1 block present: signed without --rotation-min-sdk-version 28"
fi
echo "package=$pkg versionCode=$vcode versionName=$vname"
echo "signer=$digests (matches pin)"
echo "v3 verified, no v3.1 block. Lineage itself: confirm on a device (dumpsys package: past signatures) per release/README.md."
echo "OK"
