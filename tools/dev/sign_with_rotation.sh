#!/bin/bash
# usage: tools/dev/sign_with_rotation.sh <in.apk> <out.apk> [lineage-file]
# Signs a release APK with a v3/v3.1 rotation lineage: OLD = ~/.android/debug.keystore (the key of the installed builds),
# NEW = the release key from $PCA_KEYSTORE_PROPERTIES (default ~/.pca/keystore.properties, outside the repo). Passwords are read, never printed. Needs no device.
set -euo pipefail
IN="$1"; OUT="$2"; LIN="${3:-${OUT%.apk}.lineage}"
B=$(ls -d "$HOME/Desktop/rokid/android-sdk/build-tools"/* | sort -V | tail -1)
# keystore.properties is Java properties DATA, never shell code: read the four keys with python (no eval, no word splitting) into the environment
PROPS="${PCA_KEYSTORE_PROPERTIES:-$HOME/.pca/keystore.properties}"
read_prop() { PROPS="$PROPS" python3 -c 'import os,sys
k=sys.argv[1]
for l in open(os.environ["PROPS"],encoding="utf-8"):
    l=l.rstrip("\r\n")
    if l.lstrip().startswith(("#","!")) or "=" not in l: continue
    a,b=l.split("=",1)
    if a.strip()==k: sys.stdout.write(b); break
else: sys.exit(1)' "$1"; }
storeFile="$(read_prop storeFile)"; export storePassword="$(read_prop storePassword)" keyAlias="$(read_prop keyAlias)" keyPassword="$(read_prop keyPassword)"
OLD=(--ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey)
NEW=(--ks "$storeFile" --ks-pass env:storePassword --key-pass env:keyPassword --ks-key-alias "$keyAlias")
rm -f "$LIN"
"$B/apksigner" rotate --out "$LIN" --old-signer "${OLD[@]}" --new-signer "${NEW[@]}"
"$B/apksigner" sign "${OLD[@]}" --next-signer "${NEW[@]}" --lineage "$LIN" --out "$OUT" "$IN"
"$B/apksigner" verify -v --print-certs "$OUT" | grep -E "^Verifies|Verified using v3"
