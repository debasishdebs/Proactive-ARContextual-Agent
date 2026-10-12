#!/usr/bin/env bash
set -euo pipefail

GLASSES_SERIAL="${1:-}"
PHONE_SERIAL="${2:-}"

find_serial_by_model() {
  local needle="$1"
  local serials
  serials="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
  for s in $serials; do
    local model
    model="$(adb -s "$s" shell getprop ro.product.model | tr -d '\r')"
    if [[ "$model" == *"$needle"* ]]; then
      echo "$s"
      return 0
    fi
  done
  return 1
}

if [[ -z "$GLASSES_SERIAL" ]]; then
  GLASSES_SERIAL="$(find_serial_by_model "RG-glasses" || true)"
fi
if [[ -z "$PHONE_SERIAL" ]]; then
  PHONE_SERIAL="$(find_serial_by_model "SM-" || true)"
fi

if [[ -z "$GLASSES_SERIAL" || -z "$PHONE_SERIAL" ]]; then
  echo "Unable to auto-detect devices."
  echo "Usage: $0 <glasses_serial> <phone_serial>"
  adb devices
  exit 1
fi

TS="$(date +%s)"
TMP_LOCAL="/tmp/rokid_frame_${TS}.png"
GLASS_REMOTE="/sdcard/Download/rokid_frame_${TS}.png"
PHONE_TMP_REMOTE="/data/local/tmp/rokid_frame_${TS}.png"

echo "Glasses: $GLASSES_SERIAL"
echo "Phone:   $PHONE_SERIAL"

adb -s "$GLASSES_SERIAL" shell screencap -p "$GLASS_REMOTE"
adb -s "$GLASSES_SERIAL" pull "$GLASS_REMOTE" "$TMP_LOCAL" >/dev/null
adb -s "$GLASSES_SERIAL" shell rm -f "$GLASS_REMOTE"

BLACK_CHECK="$(python3 - "$TMP_LOCAL" <<'PY'
import sys
from PIL import Image
p = sys.argv[1]
im = Image.open(p).convert("RGB")
pix = list(im.getdata())
avg = tuple(sum(c[i] for c in pix)/len(pix) for i in range(3))
is_black = all(v < 1.0 for v in avg)
print("black" if is_black else "ok")
PY
)"
if [[ "$BLACK_CHECK" == "black" ]]; then
  echo "Warning: captured frame is fully black. This screencap path is grabbing display output, not camera sensor frames."
fi

adb -s "$PHONE_SERIAL" push "$TMP_LOCAL" "$PHONE_TMP_REMOTE" >/dev/null
adb -s "$PHONE_SERIAL" shell "run-as com.rokid.context mkdir -p files/rokid_inbox && run-as com.rokid.context cp '$PHONE_TMP_REMOTE' files/rokid_inbox/ && rm -f '$PHONE_TMP_REMOTE'"

adb -s "$PHONE_SERIAL" shell am start -n com.rokid.context/.MainActivity >/dev/null || true

echo "Pushed frame into app internal inbox: /data/data/com.rokid.context/files/rokid_inbox"
echo "Local temp file: $TMP_LOCAL"
echo "Open app -> Home (Run Smoke) or Live Pipeline (Capture Now) to queue frameIngest immediately."
