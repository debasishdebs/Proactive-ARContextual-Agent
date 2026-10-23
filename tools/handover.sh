#!/usr/bin/env bash
# Hand-over tool (LGC10-min, plan 3.10), bash 3.2 + python3. Counts and names only: never a device secret, the pairing code, the session token or a system-log line.
#   tools/handover.sh <out-dir> [--phone-tid ID] [--watch-tid ID] [--no-import] [--host HOST:PORT --code CODE --fp FP8] [--allow-no-heartbeat]
# Reads only (adb exec-out run-as ... cat/tar/test on the app's OWN files; never the glasses logcat, which leaks the device secret). It never installs, uninstalls, starts or stops an app.
# The one write on the glasses is the zero-byte transferred marker files/imu/transferred/<traceId>, written AFTER a successful import of that trace (outside the frozen trace dir).
# Steps: devices and tids -> refuse while files/trace_cleanup_pending exists on the glasses (or cannot be read) -> glasses capture_diag.log{,.1}, require a FRESH heartbeat
#   (else reject; --allow-no-heartbeat continues but labels) -> glasses traces (trace-pull.sh) -> phone logs (pca.log{,.1}, qna_eval.jsonl{,.1}; run-as only) -> watch logcat only when
#   WATCH_TAGS is set -> import to the phone (pairing ONLY here; the host, code and fingerprint are args or prompts and are kept in a private temp dir that is removed on exit).
# Phone and watch are selected by adb transport id, never by serial. With glasses + phone + watch attached M1_PHONE_TID/--phone-tid and M1_WATCH_TID/--watch-tid are REQUIRED.
# Env: G glasses serial; M1_PHONE_PKG (default com.rokid.context); M1_PHONE_TID; M1_WATCH_TID; WATCH_TAGS; M1_HB_MAX_AGE_MIN (default 60, glasses clock); M1_SCHEME=http only for the local mock test.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
G="${G:-1901092545029120}"
PKG=com.rokid.context.glasses
PHONE_PKG="${M1_PHONE_PKG:-com.rokid.context}"
HB_MAX="${M1_HB_MAX_AGE_MIN:-60}"
MARKDIR=files/imu/transferred

die() { echo "$*" >&2; exit 1; }
usage() { die "usage: tools/handover.sh <out-dir> [--phone-tid ID] [--watch-tid ID] [--no-import] [--host HOST:PORT --code CODE --fp FP8] [--allow-no-heartbeat]"; }

OUT=""; PTID="${M1_PHONE_TID:-}"; WTID="${M1_WATCH_TID:-}"; NOIMPORT=0; HP=""; CODE=""; FP=""; ALLOWHB=0
while [ $# -gt 0 ]; do
  case "$1" in
    --phone-tid) [ $# -ge 2 ] || usage; PTID="$2"; shift 2 ;;
    --watch-tid) [ $# -ge 2 ] || usage; WTID="$2"; shift 2 ;;
    --no-import) NOIMPORT=1; shift ;;
    --host) [ $# -ge 2 ] || usage; HP="$2"; shift 2 ;;
    --code) [ $# -ge 2 ] || usage; CODE="$2"; shift 2 ;;
    --fp) [ $# -ge 2 ] || usage; FP="$2"; shift 2 ;;
    --allow-no-heartbeat) ALLOWHB=1; shift ;;
    -*) usage ;;
    *) [ -z "$OUT" ] || usage; OUT="$1"; shift ;;
  esac
done
[ -n "$OUT" ] || usage
# Every run exports into its OWN fresh subdirectory: an older export (a trace since deleted from the glasses) can never be imported or marked by this run.
OUT="${OUT%/}/handover-$(date +%Y%m%d-%H%M%S)-$$"
command -v adb >/dev/null 2>&1 || die "adb not found"
case "$PTID$WTID" in *[!0-9]*) die "transport ids are numbers (adb devices -l, transport_id:N)" ;; esac

# --- devices: the transport ids of everything attached except the glasses (by serial G)
DEVS=$(adb devices -l 2>/dev/null | python3 -c 'import re,sys
g=sys.argv[1]; gl=False; ids=[]
for line in sys.stdin:
    m=re.match(r"^(.*?)\s+device\s.*transport_id:(\d+)",line)
    if not m: continue
    if m.group(1)==g: gl=True
    else: ids.append(m.group(2))
print("glasses=%d" % gl); print("others=%s" % " ".join(ids))' "$G")
case "$DEVS" in *"glasses=1"*) ;; *) die "glasses not attached (serial \$G): nothing read" ;; esac
OTHERS=$(echo "$DEVS" | sed -n 's/^others=//p'); NOTH=$(echo $OTHERS | wc -w | tr -d ' ')
if [ "$NOTH" -ge 2 ]; then
  [ -n "$PTID" ] && [ -n "$WTID" ] || die "REFUSED: $NOTH non-glasses devices attached (transport ids: $OTHERS); set M1_PHONE_TID and M1_WATCH_TID (or --phone-tid and --watch-tid) from 'adb devices -l'. Nothing was read."
elif [ "$NOTH" -eq 1 ] && [ -z "$PTID" ]; then PTID="$OTHERS"
fi
for t in $PTID $WTID; do case " $OTHERS " in *" $t "*) ;; *) die "REFUSED: transport id $t is not an attached device (attached: ${OTHERS:-none}). Nothing was read." ;; esac; done
[ -z "$PTID" ] || [ "$PTID" != "$WTID" ] || die "REFUSED: the phone and watch transport ids are the same"
echo "devices: glasses=1 phone=$([ -n "$PTID" ] && echo 1 || echo 0) watch=$([ -n "$WTID" ] && echo 1 || echo 0)"

# --- fence: ANY trace export is refused while a received cleanup is pending (coarse, no quarantine index); an unreadable answer fails closed
# adb joins its arguments into ONE remote shell text, so the whole run-as command is a single string with the script in single quotes (no quote may be lost on the way).
P=$(adb -s "$G" exec-out "run-as $PKG sh -c 'test -e files/trace_cleanup_pending && echo PENDING || echo CLEAR'" 2>/dev/null | tr -d '\r')
case "$P" in
  CLEAR) ;;
  PENDING) die "REFUSED: files/trace_cleanup_pending exists on the glasses (a Delete all or Forget is still being carried out). No trace or log was exported; let the glasses finish (open the app, retry later)." ;;
  *) die "REFUSED: could not read files/trace_cleanup_pending on the glasses (run-as refused or the device is not ready). No trace or log was exported." ;;
esac

mkdir -p "$OUT/logs/glasses" "$OUT/logs/phone" || die "cannot create $OUT"
echo "export dir: $OUT"
PRIV=$(mktemp -d) || die "no tmp dir"
trap 'rm -rf "$PRIV"' EXIT

# pull_text <adb selector args...> -- <pkg> <remote file> <local file>: existence probe, then cat through the deny filter; echoes found|missing|refused
pull_text() {
  local sel=() pkg rel dest p
  while [ "$1" != -- ]; do sel+=("$1"); shift; done
  shift; pkg="$1"; rel="$2"; dest="$3"
  p=$(adb "${sel[@]}" exec-out "run-as $pkg sh -c 'test -f $rel && echo Y || echo N'" 2>/dev/null | tr -d '\r')
  case "$p" in
    Y) adb "${sel[@]}" exec-out run-as "$pkg" cat "$rel" 2>/dev/null | "$HERE/battery-run.sh" filter-diag > "$dest"; echo found ;;
    N) echo missing ;;
    *) echo refused ;;
  esac
}

# --- glasses logs (own tag, run-as only) and the heartbeat evidence
gl=0; gm=0
for f in capture_diag.log capture_diag.log.1; do
  case "$(pull_text -s "$G" -- "$PKG" "files/$f" "$OUT/logs/glasses/$f")" in found) gl=$((gl + 1)) ;; *) gm=$((gm + 1)); rm -f "$OUT/logs/glasses/$f" ;; esac
done
echo "glasses logs: pulled $gl, skipped $gm"
NOW=$(adb -s "$G" shell date '+%m-%d %H:%M:%S' 2>/dev/null | tr -d '\r')
HB=$(python3 - "$HB_MAX" "$NOW" "$OUT/logs/glasses" <<'PY'
import sys,os,glob,datetime
mx=float(sys.argv[1]); now=sys.argv[2].strip(); d=sys.argv[3]
def parse(s,year):
    return datetime.datetime.strptime("%d-%s"%(year,s[:14]),"%Y-%m-%d %H:%M:%S")
try:
    nw=datetime.datetime.strptime("%d-%s"%(datetime.datetime.now().year,now),"%Y-%m-%d %H:%M:%S")
except Exception:
    nw=datetime.datetime.now()
n=0; newest=None
for f in glob.glob(os.path.join(d,"capture_diag.log*")):
    for line in open(f,errors="replace"):
        if "heartbeat v=2" not in line: continue
        try: t=parse(line,nw.year)
        except Exception: continue
        if t>nw+datetime.timedelta(days=1): t=t.replace(year=t.year-1)
        n+=1
        if newest is None or t>newest: newest=t
if not n: print("none 0")
else:
    age=(nw-newest).total_seconds()/60.0
    print("%s %d %.0f" % ("fresh" if -5<=age<=mx else "stale",n,age))
PY
)
set -- $HB
case "$1" in
  fresh) echo "heartbeat evidence: $2 line(s), newest ${3} min old (glasses clock)" ;;
  *)
    msg="NO FRESH HEARTBEAT EVIDENCE: $([ "$1" = none ] && echo 'no heartbeat v=2 line in the glasses capture_diag.log' || echo "$2 heartbeat line(s), newest ${3} min old, limit $HB_MAX min"); diagnostics may be off, so this run cannot be attributed to a recording build"
    if [ "$ALLOWHB" = 1 ]; then echo "$msg: continuing (--allow-no-heartbeat), the pulled data is LABELLED unverified"
    else die "$msg. Rejected before any trace was pulled; turn Developer mode on and rerun, or pass --allow-no-heartbeat to label and continue."; fi ;;
esac

# --- glasses traces: the existing pull (sealed traces only; checks the pending fence itself too)
"$HERE/trace-pull.sh" "$OUT" || die "trace pull failed"

# --- phone logs: run-as only, both rotations of both files; a missing one is skipped with a count
pn=0; pm=0
if [ -n "$PTID" ]; then
  for f in pca.log pca.log.1 qna_eval.jsonl qna_eval.jsonl.1; do
    r=$(pull_text -t "$PTID" -- "$PHONE_PKG" "files/logs/$f" "$OUT/logs/phone/$f")
    case "$r" in
      found) pn=$((pn + 1)) ;;
      missing) pm=$((pm + 1)); rm -f "$OUT/logs/phone/$f" ;;
      *) rm -f "$OUT"/logs/phone/*; echo "phone logs not pulled: run-as refused (build not debuggable)"; pn=-1; break ;;
    esac
  done
  [ "$pn" -lt 0 ] || echo "phone logs: pulled $pn, skipped $pm (missing rotation)"
else
  echo "phone not attached: phone logs skipped"
fi

# --- watch logcat: only with WATCH_TAGS and a watch tid (filtered tags, through the deny list)
if [ -n "${WATCH_TAGS:-}" ] && [ -n "$WTID" ]; then
  case "$WATCH_TAGS" in *[!A-Za-z0-9_:.*\ -]*) die "WATCH_TAGS has odd characters" ;; esac
  mkdir -p "$OUT/logs/watch"
  # shellcheck disable=SC2086
  adb -t "$WTID" logcat -d -v time -s $WATCH_TAGS 2>/dev/null | "$HERE/battery-run.sh" filter-diag > "$OUT/logs/watch/logcat.txt"
  echo "watch log: $(wc -l < "$OUT/logs/watch/logcat.txt" | tr -d ' ') line(s) for the tags in WATCH_TAGS"
else
  echo "watch log: skipped (WATCH_TAGS not set)"
fi

# --- import (pairing only here) and the markers
[ "$ALLOWHB" = 1 ] && [ "$1" != fresh ] && echo "NOTE the data above is LABELLED: no fresh heartbeat evidence"
if [ "$NOIMPORT" = 1 ]; then echo "not imported: --no-import (files only)"; exit 0; fi
[ -n "$PTID" ] || { echo "not imported: the phone is not attached"; exit 0; }
if [ -z "$HP" ] && [ -t 0 ]; then printf 'phone host:port (Canvas screen): ' >&2; read -r HP; fi
if [ -n "$HP" ] && [ -z "$CODE" ] && [ -t 0 ]; then printf 'pairing code: ' >&2; read -r -s CODE; echo >&2; fi
if [ -n "$HP" ] && [ -z "$FP" ] && [ -t 0 ] && [ "${M1_SCHEME:-https}" = https ]; then printf 'certificate fingerprint (first 8 hex digits): ' >&2; read -r FP; fi
if [ -z "$HP" ] || [ -z "$CODE" ]; then echo "not imported: needs pairing (--host HOST:PORT --code CODE --fp FP8, or run it in a terminal to be prompted)"; exit 0; fi
# the pairing state (host, pin, session token) lives only in a private dir that is removed on exit
export TMPDIR="$PRIV"
"$HERE/m1-run.sh" pair "$HP" "$CODE" $FP || die "pairing failed: nothing imported, no marker written"
CODE=""
IMP=$(M1_PHONE_TID="$PTID" "$HERE/m1-run.sh" import "$OUT"); irc=$?
echo "$IMP"
marked=0
for id in $(echo "$IMP" | sed -n 's/^imported traceId=\([a-z0-9-]*\) .*/\1/p'); do
  # the marker: zero-byte, own directory, never inside the trace dir (the pull and TraceFiles.verify see an unchanged layout)
  if adb -s "$G" exec-out "run-as $PKG sh -c 'mkdir -p $MARKDIR && : > $MARKDIR/$id'" >/dev/null 2>&1; then marked=$((marked + 1)); else echo "marker not written for one trace (run-as refused)"; irc=1; fi
done
echo "marked $marked trace(s) as transferred"
exit "$irc"
