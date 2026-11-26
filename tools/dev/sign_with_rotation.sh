#!/bin/bash
# usage: tools/dev/sign_with_rotation.sh <in.apk> <out.apk> [lineage-file]
# Signs a release APK with a v3/v3.1 rotation lineage: OLD = ~/.android/debug.keystore (the key of the installed builds),
# NEW = the release key from ~/.pca/keystore.properties. Passwords are read, never printed. Needs no device.
set -euo pipefail
IN="$1"; OUT="$2"; LIN="${3:-${OUT%.apk}.lineage}"
B=$(ls -d "$HOME/Desktop/rokid/android-sdk/build-tools"/* | sort -V | tail -1)
set -a; . "$HOME/.pca/keystore.properties"; set +a
OLD=(--ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey)
NEW=(--ks "$storeFile" --ks-pass env:storePassword --key-pass env:keyPassword --ks-key-alias "$keyAlias")
rm -f "$LIN"
"$B/apksigner" rotate --out "$LIN" --old-signer "${OLD[@]}" --new-signer "${NEW[@]}"
"$B/apksigner" sign "${OLD[@]}" --next-signer "${NEW[@]}" --lineage "$LIN" --out "$OUT" "$IN"
"$B/apksigner" verify -v --print-certs "$OUT" | grep -E "^Verifies|Verified using v3"
