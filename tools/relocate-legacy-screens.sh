#!/usr/bin/env bash
# Move legacy dev captures from the repo root into docs/screens/legacy/ (git-ignored).
#   tools/relocate-legacy-screens.sh <repo-root>
# Moves root phone-*.png, window-*.xml, .tmp_glasses_*.png, check-*.png. Idempotent; prints the count.
set -u
[ $# -eq 1 ] && [ -d "$1" ] || { echo "usage: relocate-legacy-screens.sh <repo-root>" >&2; exit 2; }
ROOT="${1%/}"
DEST="$ROOT/docs/screens/legacy"
mkdir -p "$DEST" || exit 1
n=0
for f in "$ROOT"/phone-*.png "$ROOT"/window-*.xml "$ROOT"/.tmp_glasses_*.png "$ROOT"/check-*.png; do
  [ -f "$f" ] || continue
  mv "$f" "$DEST/" && n=$((n + 1))
done
echo "moved $n file(s) to docs/screens/legacy/"
