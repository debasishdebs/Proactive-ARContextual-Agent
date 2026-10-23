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
P=$(adb -s "$G" exec-out run-as "$PKG" sh -c 'test -e files/trace_cleanup_pending && echo PENDING || echo CLEAR' 2>/dev/null | tr -d '\r')
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
