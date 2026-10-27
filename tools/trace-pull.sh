#!/usr/bin/env bash
# Controller-run: pulls the glasses' sealed IMU traces for the phone's pulled-trace import (bash 3.2). Counts only; reads no system log.
#   tools/trace-pull.sh <out-dir>      (device serial from $G; a debuggable glasses build, run-as)
# Writes <out-dir>/<traceId>/{manifest.json,chunk-NNNN.json}, the frozen layout the importer verifies. A directory without manifest.json
# (a trace that was never closed cleanly) is not importable and is removed here. Nothing is deleted on the glasses.
set -u

G="${G:-1901092545029120}"
PKG=com.rokid.context.glasses
SRC=files/imu/traces

die() { echo "$*" >&2; exit 1; }
[ $# -eq 1 ] || die "usage: tools/trace-pull.sh <out-dir>"
OUT="$1"
mkdir -p "$OUT" || die "cannot create $OUT"

# LGC8-min fence: no trace leaves the glasses while a received cleanup is pending (or when that cannot be read): a trace in a wiped range may still exist.
P=$(adb -s "$G" exec-out "run-as $PKG sh -c 'test -e files/trace_cleanup_pending && echo PENDING || echo CLEAR'" 2>/dev/null | tr -d '\r')
[ "$P" = CLEAR ] || die "REFUSED: files/trace_cleanup_pending exists or cannot be read on the glasses ($([ "$P" = PENDING ] && echo pending || echo unreadable)): no trace exported"

LIST=$(mktemp) || die "no tmp file"
trap 'rm -f "$LIST"' EXIT
adb -s "$G" exec-out run-as "$PKG" tar -c -C files/imu traces 2>/dev/null | tar -t > "$LIST" || die "could not read $SRC on the glasses"
# Refuse anything that could write outside <out-dir>.
if grep -Eq '(^/|(^|/)\.\.(/|$))' "$LIST"; then die "unsafe path in the archive: refusing"; fi

TMP=$(mktemp -d) || die "no tmp dir"
trap 'rm -f "$LIST"; rm -rf "$TMP"' EXIT
adb -s "$G" exec-out run-as "$PKG" tar -c -C files/imu traces 2>/dev/null | tar -x -C "$TMP" || die "pull failed"

n=0; skipped=0
for d in "$TMP"/traces/*/; do
  [ -d "$d" ] || continue
  id=$(basename "$d")
  case "$id" in *[!a-z0-9-]*) skipped=$((skipped + 1)); continue ;; esac
  if [ ! -f "$d/manifest.json" ]; then skipped=$((skipped + 1)); continue; fi
  rm -rf "${OUT:?}/$id"; mv "$d" "$OUT/$id" && n=$((n + 1))
done
echo "pulled $n trace(s), skipped $skipped (no manifest or odd name) into $OUT"

# v0.9.7 B: the developer sensor recordings (files/imu/sensor_rec, written only with Developer mode and a chosen rate). Same method and the same cleanup fence as the traces (checked above);
# only files named s-<start>-<end>-<seq>.json are kept, in <out-dir>/sensor_rec/ (never inside a trace directory). Nothing is deleted on the glasses; none present = 0.
SL=$(mktemp) || die "no tmp file"
sn=0; sk=0
if adb -s "$G" exec-out run-as "$PKG" tar -c -C files/imu sensor_rec 2>/dev/null | tar -t > "$SL" 2>/dev/null && [ -s "$SL" ]; then
  if grep -Eq '(^/|(^|/)\.\.(/|$))' "$SL"; then rm -f "$SL"; die "unsafe path in the sensor archive: refusing"; fi
  adb -s "$G" exec-out run-as "$PKG" tar -c -C files/imu sensor_rec 2>/dev/null | tar -x -C "$TMP" || { rm -f "$SL"; die "sensor pull failed"; }
  mkdir -p "$OUT/sensor_rec" || die "cannot create $OUT/sensor_rec"
  for f in "$TMP"/sensor_rec/*; do
    [ -f "$f" ] || continue
    b=$(basename "$f")
    if printf '%s' "$b" | grep -Eq '^s-[0-9]{1,16}-[0-9]{1,16}-[0-9]{4}\.json$'; then mv "$f" "$OUT/sensor_rec/$b" && sn=$((sn + 1)); else sk=$((sk + 1)); fi
  done
fi
rm -f "$SL"
echo "pulled $sn sensor batch(es), skipped $sk (odd name) into $OUT/sensor_rec"
