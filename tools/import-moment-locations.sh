#!/usr/bin/env bash
# v18 WP5: imports the GPS backfill TSV (backfill_gps.py --emit-import) into a paired phone through
# POST /api/moments/locations/import. bash 3.2 + curl + python3. Reuses the tools/m1-run.sh pair state
# (host, pin, Authorization header file); pair first:  tools/m1-run.sh pair <host:port> <code> <fp8>
# Sends 2,000-row chunks 11 s apart (the route rate-limits to one call per 10 s). Prints COUNTS ONLY: never a
# coordinate, an id, the token or the response body. The chunks and the TSV are deleted on exit (success or failure).
#   tools/import-moment-locations.sh <moment-loc-import.tsv> [--keep]     (--keep leaves the TSV, never the chunks)
# Env: M1_SCHEME=http only for a local mock test; IMPORT_CHUNK_ROWS (default 2000); IMPORT_GAP_S (default 11).
set -u
SCHEME="${M1_SCHEME:-https}"
STATE="${TMPDIR:-/tmp}"; STATE="${STATE%/}/m1-run-$(id -u)"
TOKHDR="$STATE/auth.hdr"
ROWS="${IMPORT_CHUNK_ROWS:-2000}"; GAP="${IMPORT_GAP_S:-11}"
HEADER=$'momentId\tatMs\tanchorKind\tanchorId\tlat\tlon\taccuracyM\tfixAgeMs\tspeedClass\tmethod'
die() { echo "$*" >&2; exit 1; }

TSV="${1:-}"; KEEP=0; [ "${2:-}" = "--keep" ] && KEEP=1
[ -n "$TSV" ] && [ -f "$TSV" ] || die "usage: tools/import-moment-locations.sh <moment-loc-import.tsv> [--keep]"
[ -f "$TOKHDR" ] && [ -f "$STATE/host" ] || die "not paired: run  tools/m1-run.sh pair <host:port> <code> <fp8>"
[ "$(head -n 1 "$TSV")" = "$HEADER" ] || die "the TSV header is not the import header"
case "$ROWS" in ''|*[!0-9]*) die "IMPORT_CHUNK_ROWS must be a number" ;; esac
[ "$ROWS" -ge 1 ] && [ "$ROWS" -le 2000 ] || die "IMPORT_CHUNK_ROWS must be 1..2000"

umask 077
WORK=$(mktemp -d "${TMPDIR:-/tmp}/moment-loc-import.XXXXXX") || die "cannot create a work dir"
cleanup() { rm -rf "$WORK"; [ "$KEEP" = 1 ] || rm -f "$TSV"; }
trap cleanup EXIT INT TERM

HOST=$(cat "$STATE/host"); PIN=$(cat "$STATE/pin" 2>/dev/null || true)
TOTAL=$(($(wc -l < "$TSV") - 1)); [ "$TOTAL" -ge 1 ] || die "no rows in the TSV"
tail -n +2 "$TSV" | split -l "$ROWS" - "$WORK/part."
N=$(ls "$WORK"/part.* | wc -l | tr -d ' ')
echo "importing $TOTAL candidate rows in $N chunk(s)"

TW=0; TR=0; i=0; FAILED=0
for part in "$WORK"/part.*; do
  i=$((i + 1))
  { printf '%s\n' "$HEADER"; cat "$part"; } > "$part.body"
  args=(-sS -o "$WORK/resp.json" -w '%{http_code}' -X POST -H "@$TOKHDR" -H 'Content-Type: text/tab-separated-values' --data-binary "@$part.body" --max-time 120)
  [ "$SCHEME" = https ] && { args+=(-k); [ -n "$PIN" ] && args+=(--pinnedpubkey "$PIN"); }
  st=$(curl "${args[@]}" "$SCHEME://$HOST/api/moments/locations/import" 2>/dev/null || echo 000)
  rm -f "$part" "$part.body"
  if [ "$st" = 200 ]; then
    line=$(python3 -c 'import json,sys
j=json.load(open(sys.argv[1]))
sk=j.get("skipped",{})
print(j.get("received",0), j.get("written",0), ",".join("%s:%s"%(k,sk[k]) for k in sorted(sk)) or "none")' "$WORK/resp.json" 2>/dev/null) || line=""
    if [ -z "$line" ]; then echo "chunk $i/$N: http=200 but an unreadable answer"; FAILED=1; break; fi
    set -- $line
    TR=$((TR + $1)); TW=$((TW + $2))
    echo "chunk $i/$N: received=$1 written=$2 skipped=$3"
  else
    err=$(python3 -c 'import json,sys
try: print(json.load(open(sys.argv[1])).get("error"))
except Exception: print("?")' "$WORK/resp.json" 2>/dev/null || echo "?")
    echo "chunk $i/$N failed: http=$st error=$err"; FAILED=1; break
  fi
  [ "$i" -lt "$N" ] && sleep "$GAP"
done
echo "total: received=$TR written=$TW (of $TOTAL candidates)"
[ "$FAILED" = 0 ] || exit 1
