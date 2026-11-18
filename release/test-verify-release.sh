#!/usr/bin/env bash
# Fixture self-tests for verify-release.sh using fake aapt2/apksigner on PATH. No Android SDK needed.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"; : > "$tmp/x.apk"
PIN=$(printf 'ab%.0s' $(seq 32)); echo "$PIN" > "$tmp/pin.txt"
cat > "$tmp/bin/aapt2" <<'F'
#!/usr/bin/env bash
case "${FAKE_AAPT2:-ok}" in
  fail) echo "boom" >&2; exit 1 ;;
  empty) exit 0 ;;
  garbage) echo "something else"; exit 0 ;;
  debuggable) echo "package: name='com.x' versionCode='7' versionName='1.0'"; echo "application-debuggable"; exit 0 ;;
  *) echo "package: name='com.x' versionCode='7' versionName='1.0'"; for i in $(seq 3000); do echo "uses-permission: name='p$i'"; done; exit 0 ;;
esac
F
cat > "$tmp/bin/apksigner" <<'F'
#!/usr/bin/env bash
case "${FAKE_SIGNER:-ok}" in
  fail) exit 1 ;;
  empty) exit 0 ;;
esac
if [ "$2" = "-v" ]; then echo "Verified using v3 scheme (APK Signature Scheme v3): true"
else echo "Signer #1 certificate SHA-256 digest: $FAKE_PIN"; fi
F
chmod +x "$tmp/bin/"*
export PIN_FILE="$tmp/pin.txt" FAKE_PIN="$PIN"
n=0; bad=0
t() { # <expect: 0|nz> <name> [env assignments...]
  local want=$1 name=$2; shift 2
  env PATH="$tmp/bin:$PATH" "$@" "$here/verify-release.sh" "$tmp/x.apk" > "$tmp/out" 2>&1; local rc=$?
  n=$((n+1))
  if { [ "$want" = 0 ] && [ $rc -eq 0 ]; } || { [ "$want" = nz ] && [ $rc -ne 0 ]; }; then echo "ok   $name"
  else echo "FAIL $name (rc=$rc)"; cat "$tmp/out"; bad=$((bad+1)); fi
}
t 0  "aapt2 ok, debuggable absent, large output"
t nz "aapt2 fails" FAKE_AAPT2=fail
t nz "aapt2 empty output" FAKE_AAPT2=empty
t nz "aapt2 garbage output" FAKE_AAPT2=garbage
t nz "debuggable=true" FAKE_AAPT2=debuggable
t nz "apksigner fails" FAKE_SIGNER=fail
t nz "apksigner empty output" FAKE_SIGNER=empty
t nz "signer differs from pin" FAKE_PIN=$(printf 'cd%.0s' $(seq 32))
t nz "aapt2 missing" PATH="$tmp/bin2:/usr/bin:/bin"
echo "$((n-bad))/$n passed"; [ $bad -eq 0 ]
