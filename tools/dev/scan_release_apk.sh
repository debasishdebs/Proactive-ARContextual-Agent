#!/bin/bash
# usage: tools/dev/scan_release_apk.sh <release.apk> [--allow-unsigned]
# Fails (exit 1) if the APK carries key-like strings, the debug certificate or debug signing, or local.properties content.
# Prints pattern NAMES and counts only, never the matched value.
set -uo pipefail
APK="${1:?apk}"; ALLOW_UNSIGNED=0; [ "${2:-}" = "--allow-unsigned" ] && ALLOW_UNSIGNED=1
B=$(ls -d "$HOME/Desktop/rokid/android-sdk/build-tools"/* 2>/dev/null | sort -V | tail -1)
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
unzip -q -o "$APK" -d "$W/x" || { echo "SCAN FAIL: cannot unzip"; exit 2; }
FAIL=0
fail() { echo "SCAN FAIL: $1"; FAIL=1; }
# every string of every dex, plus the other text-like entries (resources, assets, manifest, native libs)
find "$W/x" -type f ! -path '*/META-INF/*' -exec env LC_ALL=C grep -a -o -E '[[:print:]]{6,}' {} + 2>/dev/null | sed 's/^[^:]*://' > "$W/all.txt"
chk() { # name regex
  local n; n=$(grep -E -c -- "$2" "$W/all.txt"); [ "$n" -gt 0 ] && fail "$1 ($n lines)"; return 0; }
chk "Google API key (AIza)"        'AIza[0-9A-Za-z_-]{35}'
chk "OpenAI/Anthropic key (sk-)"   '(^|[^A-Za-z0-9_-])sk-(ant-)?[A-Za-z0-9_-]{20,}'
chk "GitHub token (ghp_/gho_/github_pat_)" '(ghp_|gho_|ghs_|github_pat_)[A-Za-z0-9_]{20,}'
chk "Slack token (xox)"            'xox[abprs]-[A-Za-z0-9-]{10,}'
chk "MapTiler-style key (key=...)" '[?&]key=[A-Za-z0-9]{16,}'
n=$(grep -E -i -c '(key|token|secret|passw(or)?d)["'"'"' :=]{1,4}[A-Za-z0-9+/_-]{32,}={0,2}\b' "$W/all.txt"); [ "$n" -gt 0 ] && fail "long hex/base64 value near key/token/secret ($n lines)"
chk "debug signing string (androiddebugkey)" 'androiddebugkey'
# local.properties content: the sdk.dir line and any non-comment line of the real file
grep -q -E '^sdk\.dir=' "$W/all.txt" && fail "local.properties content (sdk.dir)"
LP="$(cd "$(dirname "$0")/../.." && pwd)/android-companion/local.properties"
if [ -f "$LP" ]; then while IFS= read -r l; do case "$l" in ''|'#'*) continue;; esac; [ ${#l} -ge 8 ] && grep -qxF -- "$l" "$W/all.txt" && fail "local.properties line present"; done < "$LP"; fi
# certificate: the debug certificate must not sign it; unsigned is a failure unless allowed
if [ -n "$B" ] && [ -x "$B/apksigner" ]; then
  OUTC=$("$B/apksigner" verify --print-certs "$APK" 2>&1)
  if "$B/apksigner" verify "$APK" >/dev/null 2>&1; then
    echo "$OUTC" | grep -q -E 'CN=Android Debug' && fail "signed with the Android debug certificate"
    DBG=$(keytool -list -keystore "$HOME/.android/debug.keystore" -storepass android 2>/dev/null | grep -o -E '([0-9A-F]{2}:){31}[0-9A-F]{2}' | tr -d ':' | tr 'A-F' 'a-f' | head -1)
    [ -n "$DBG" ] && echo "$OUTC" | grep -q "$DBG" && fail "carries the debug certificate digest"
  else [ $ALLOW_UNSIGNED = 1 ] || fail "APK is not signed (use --allow-unsigned to skip)"; fi
else fail "apksigner not found"; fi
unzip -l "$APK" | grep -E -q 'local\.properties|\.jks$|\.keystore$|keystore\.properties' && fail "keystore/local.properties file inside the APK"
[ $FAIL = 0 ] && echo "SCAN OK: $APK" || echo "SCAN FAILED: $APK"
exit $FAIL
