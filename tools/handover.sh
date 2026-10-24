#!/usr/bin/env bash
# Hand-over tool (LGC10-min, plan 3.10), bash 3.2 + python3. Counts and names only: never a device secret, the pairing code, the session token or a system-log line.
#   tools/handover.sh <out-dir> [--phone-tid ID] [--watch-tid ID] [--no-import] [--host HOST:PORT --code CODE --fp FP8] [--allow-no-heartbeat]
# Reads only (adb exec-out run-as ... cat/tar/test on the app's OWN files; never the glasses logcat, which leaks the device secret). It never installs, uninstalls, starts or stops an app.
# The one write on the glasses is the zero-byte transferred marker files/imu/transferred/<traceId>, written AFTER a successful import of that trace (outside the frozen trace dir).
# Steps: devices and tids -> refuse while files/trace_cleanup_pending exists on the glasses (or cannot be read) -> glasses capture_diag.log{,.1}, compute the WEAR WINDOW from the heartbeats
#   (no heartbeat in it = reject; --allow-no-heartbeat continues but labels) -> glasses traces (trace-pull.sh) -> phone logs (pca.log{,.1}, qna_eval.jsonl{,.1}; run-as only) -> watch logcat only when
#   WATCH_TAGS is set -> import to the phone (pairing ONLY here; the host, code and fingerprint are args or prompts and are kept in a private temp dir that is removed on exit).
# Phone and watch are selected by adb transport id, never by serial. With glasses + phone + watch attached M1_PHONE_TID/--phone-tid and M1_WATCH_TID/--watch-tid are REQUIRED.
# Env: G glasses serial; M1_PHONE_PKG (default com.rokid.context); M1_PHONE_TID; M1_WATCH_TID; WATCH_TAGS; M1_HB_MAX_AGE_MIN (deprecated, ignored); PCA_HELPER_HOME (default ~/.pca-helper); M1_SCHEME=http only for the local mock test.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
G="${G:-1901092545029120}"
PKG=com.rokid.context.glasses
PHONE_PKG="${M1_PHONE_PKG:-com.rokid.context}"
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
# WEAR WINDOW (glasses clock): from the LAST unplug (last chg=false heartbeat that follows a chg=true one; with no earlier chg=true line, the first heartbeat) to the first later
# chg=true heartbeat (plugged in again) or the last heartbeat. All heartbeats chg=true (never worn) = no window. The helper below is the only place that reads the log's times.
WIN="$PRIV/win.py"
cat > "$WIN" <<'PY'
import sys,os,glob,datetime,shutil,json,base64
cmd=sys.argv[1]; now=sys.argv[2].strip(); d=sys.argv[3]
def res(s,nw):  # "MM-DD HH:MM:SS.mmm" (no year) -> datetime in the year of the glasses "now"; a time more than a day ahead belongs to the previous year
    t=datetime.datetime.strptime("%d-%s"%(nw.year,s),"%Y-%m-%d %H:%M:%S.%f")
    return t.replace(year=t.year-1) if t>nw+datetime.timedelta(days=1) else t
try: nw=datetime.datetime.strptime("%d-%s"%(datetime.datetime.now().year,now),"%Y-%m-%d %H:%M:%S")
except Exception: nw=datetime.datetime.now()
def stamp(line):
    p=line.split()
    if len(p)<2: return None
    try: return res(p[0]+" "+p[1],nw),p[0]+" "+p[1]
    except Exception: return None
h=[]
for f in glob.glob(os.path.join(d,"capture_diag.log*")):
    for line in open(f,errors="replace"):
        if "heartbeat v=2" not in line: continue
        st=stamp(line)
        if not st: continue
        kv=dict(x.split("=",1) for x in line.split() if "=" in x)
        h.append((st[0],st[1],kv.get("chg")))
h.sort(key=lambda r:r[0])
start=None; prev=None
for i,(t,s,c) in enumerate(h):
    if c=="false" and prev=="true": start=i
    if c in ("true","false"): prev=c
if start is None and h and h[0][2]!="true": start=0
if cmd=="window":
    if start is None: print("status=none heartbeats=%d" % len(h)); sys.exit(0)
    end=None
    for j in range(start+1,len(h)):
        if h[j][2]=="true": end=j; break
    plugged=end is not None
    if end is None: end=len(h)-1
    n=(end-start) if plugged else (end-start+1)
    unplug=(h[start][0]+(datetime.datetime.now()-nw)).strftime("%Y-%m-%dT%H:%M:%S") if plugged else ""
    print("status=ok start=%s end=%s count=%d replugged=%d unplug_host=%s" % (h[start][1].replace(" ","_"),h[end][1].replace(" ","_"),n,1 if plugged else 0,unplug))
elif cmd=="logs":  # keep only the lines at or after the window start (a line without a stamp follows the line before it)
    ws=h[start][0]; dropped=0
    for f in glob.glob(os.path.join(d,"capture_diag.log*")):
        keep=True; out=[]
        for line in open(f,errors="replace"):
            st=stamp(line)
            if st: keep=st[0]>=ws
            if keep: out.append(line)
            else: dropped+=1
        open(f,"w").write("".join(out))
    print("log_lines_dropped=%d" % dropped)
elif cmd=="traces":  # traces whose end time (manifest endWallMs, glasses wall ms) is before the window start are removed from the local copy; unreadable time = kept, untimed
    ws=h[start][0]; kept=skipped=untimed=0
    for m in glob.glob(os.path.join(sys.argv[4],"*","manifest.json")):
        try:
            d=json.load(open(m))
            if isinstance(d,dict) and "b64" in d:  # real format (TRACE_SCHEMA=1): the manifest is an envelope whose b64 holds the manifest JSON
                d=json.loads(base64.b64decode(d["b64"]))
            e=int(d["endWallMs"]); assert e>0
            t=datetime.datetime.fromtimestamp(e/1000.0)
        except Exception:
            kept+=1; untimed+=1; continue
        if t<ws: shutil.rmtree(os.path.dirname(m)); skipped+=1
        else: kept+=1
    print("traces_pulled=%d skipped_before_window=%d untimed=%d" % (kept,skipped,untimed))
PY
[ -z "${M1_HB_MAX_AGE_MIN:-}" ] || echo "note: M1_HB_MAX_AGE_MIN is deprecated and ignored (the wear window comes from the heartbeat log)"
HB=$(python3 "$WIN" window "$NOW" "$OUT/logs/glasses")
set -- $HB
case "$1" in
  status=ok)
    WSTART=$(echo "$2" | sed 's/^start=//; s/_/ /'); WEND=$(echo "$3" | sed 's/^end=//; s/_/ /'); WCOUNT=$(echo "$4" | sed 's/^count=//')
    echo "wear window (glasses clock): start $WSTART, end $WEND ($([ "$5" = replugged=1 ] && echo 'plugged in again' || echo 'last heartbeat')); heartbeats in window: $WCOUNT"
    python3 "$WIN" logs "$NOW" "$OUT/logs/glasses"
    # OPTIONAL and informational (the log is the source of truth): the last unplug seen with a later plug-in, host clock, ISO
    if [ "$5" = replugged=1 ]; then
      UH="${PCA_HELPER_HOME:-$HOME/.pca-helper}"
      { mkdir -p "$UH" && echo "$6" | sed 's/^unplug_host=//' > "$UH/glasses-usb-last"; } 2>/dev/null || true
    fi ;;
  *)
    msg="NO HEARTBEAT EVIDENCE: $([ "$2" = heartbeats=0 ] && echo 'no heartbeat v=2 line in the glasses capture_diag.log' || echo "no wear window: every heartbeat shows chg=true (the glasses were never worn)"); diagnostics may be off, so this run cannot be attributed to a recording build"
    if [ "$ALLOWHB" = 1 ]; then echo "$msg: continuing (--allow-no-heartbeat), the pulled data is LABELLED unverified"
    else die "$msg. Rejected before any trace was pulled; turn Developer mode on and rerun, or pass --allow-no-heartbeat to label and continue."; fi ;;
esac

# --- glasses traces: the existing pull (sealed traces only; checks the pending fence itself too)
"$HERE/trace-pull.sh" "$OUT" || die "trace pull failed"
if [ "${1:-}" = status=ok ]; then echo "$(python3 "$WIN" traces "$NOW" "$OUT/logs/glasses" "$OUT") (only traces ending inside the wear window are kept; untimed = manifest time unreadable, included)"; fi

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
[ "$ALLOWHB" = 1 ] && [ "${1:-}" != status=ok ] && echo "NOTE the data above is LABELLED: no heartbeat evidence"
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
