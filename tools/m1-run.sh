#!/usr/bin/env bash
# Slice 3a M1 device-run controller (Task 23b), bash 3.2 + curl + python3. Prints counts, hashes and statuses only: never the session token,
# the pairing code, a device secret or any system-log line. The glasses are read only through files/capture_diag.log heartbeat lines.
#   tools/m1-run.sh pair <host:port> <code> [fp8]   pair with the phone's canvas LAN server (HTTPS, pinned)
#   tools/m1-run.sh plan                            compile the identity plan (expect configHash f5782e0d8610)
#   tools/m1-run.sh shadow                          POST /api/plans/debug-shadow (SHADOW only; Developer mode)
#   tools/m1-run.sh status                          collect state, trace and replay counts, glasses heartbeat plan fields
#   tools/m1-run.sh collect start [minutes] | stop | status
#   tools/m1-run.sh pull <dir> [--allow-partial]    glasses -> <dir> (trace-pull.sh) + heartbeat lines; then each trace is adb-pushed to the phone's staging dir and imported
#                                                   (POST /api/traces/import; staging dir deleted by the phone on success); lists the phone's traces
#   tools/m1-run.sh replay [<traceId>] [--diag file] [--tol pct] [--legacy-total] [--max-fail-pct T]   (no id: the last trace imported by pull; --legacy-total = old +-5 % of ALL opens, SETTLE-wake scripts only)
# Gate: failing windows (base_fail) / compared windows (base_pass + base_fail) must be <= T percent; T = --max-fail-pct or M1_MAX_FAIL_PCT, default 3, HARD CAP 5 (above 5 or non-numeric: refused before any replay).
# Env: G glasses serial (default as trace-pull.sh); M1_PHONE_TID adb transport id of the phone (auto: the only other device in `adb devices -l`; never selected by serial,
#   the wireless serial contains spaces); M1_PHONE_PKG phone package (default com.rokid.context); M1_SCHEME=http only for the local mock test; M1_DIAG=<file> reads heartbeats from a file (no adb).
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
G="${G:-1901092545029120}"
PKG=com.rokid.context.glasses
EXPECT_HASH=f5782e0d8610
SCHEME="${M1_SCHEME:-https}"
STATE="${TMPDIR:-/tmp}"; STATE="${STATE%/}/m1-run-$(id -u)"
TOKHDR="$STATE/auth.hdr"
LAST="$STATE/last.json"
PHONE_PKG="${M1_PHONE_PKG:-com.rokid.context}"
PHONE_STAGING="/sdcard/Android/data/$PHONE_PKG/files/trace-staging"
LAST_TRACE="$STATE/last-trace"

die() { echo "$*" >&2; exit 1; }
need_state() { [ -f "$TOKHDR" ] && [ -f "$STATE/host" ] || die "not paired: run  tools/m1-run.sh pair <host:port> <code> <fp8>"; }
init_state() { umask 077; mkdir -p "$STATE" && chmod 700 "$STATE" || die "cannot create state dir"; }

# api METHOD PATH [json-body-file]  -> prints the HTTP status; the body is in $LAST
api() {
  local m="$1" p="$2" bf="${3:-}" host pin
  host=$(cat "$STATE/host")
  local args=(-sS -o "$LAST" -w '%{http_code}' -X "$m" -H "@$TOKHDR" --max-time 60)
  if [ "$SCHEME" = https ]; then
    args+=(-k); pin=$(cat "$STATE/pin" 2>/dev/null || true)
    [ -n "$pin" ] && args+=(--pinnedpubkey "$pin")
  fi
  [ -n "$bf" ] && args+=(-H 'Content-Type: application/json' --data-binary "@$bf")
  curl "${args[@]}" "$SCHEME://$host$p" 2>/dev/null || echo 000
}

# pj <python expression over j (the parsed last body)>: prints a result, "?" when the body is not JSON
pj() { python3 -c 'import json,sys
try: j=json.load(open(sys.argv[1]))
except Exception: print("?"); sys.exit(0)
try: print(eval(sys.argv[2]))
except Exception: print("?")' "$LAST" "$1"; }

errline() { # prints the fixed error code of a failed call
  echo "failed: http=$1 error=$(pj 'j.get("error")')"
}

cmd_pair() {
  [ $# -ge 2 ] || die "usage: m1-run.sh pair <host:port> <code> [fp8]"
  local hp="$1" code="$2" fp="${3:-}" pin="" got
  case "$hp" in *:[0-9]*) ;; *) die "host:port expected" ;; esac
  case "$code" in [0-9][0-9][0-9][0-9][0-9][0-9]) ;; *) die "the pairing code is 6 digits" ;; esac
  init_state
  if [ "$SCHEME" = https ]; then
    openssl s_client -connect "$hp" </dev/null 2>/dev/null | openssl x509 -outform der > "$STATE/cert.der" 2>/dev/null || die "no TLS certificate from $hp (is the LAN server on?)"
    got=$(shasum -a 256 "$STATE/cert.der" | cut -c1-64 | tr 'a-f' 'A-F')
    if [ -z "$fp" ]; then echo "certificate fingerprint starts $(echo "$got" | cut -c1-8): compare with the app, then rerun with it as the 3rd argument"; exit 2; fi
    fp=$(echo "$fp" | tr -d ' :' | tr 'a-f' 'A-F')
    case "$got" in "$fp"*) ;; *) die "FINGERPRINT MISMATCH: not pairing" ;; esac
    [ "${#fp}" -ge 8 ] || die "give at least 8 hex digits of the fingerprint"
    pin="sha256//$(openssl x509 -inform der -in "$STATE/cert.der" -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64)"
    echo "$pin" > "$STATE/pin"
  fi
  echo "$hp" > "$STATE/host"; rm -f "$TOKHDR"
  printf '{"code":"%s"}' "$code" > "$STATE/pair.json"
  local args=(-sS -o "$LAST" -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary "@$STATE/pair.json" --max-time 30)
  [ "$SCHEME" = https ] && args+=(-k --pinnedpubkey "$pin")
  local st; st=$(curl "${args[@]}" "$SCHEME://$hp/api/pair" 2>/dev/null || echo 000)
  rm -f "$STATE/pair.json"
  if [ "$st" = 200 ]; then
    python3 -c 'import json,sys
t=json.load(open(sys.argv[1]))["token"]
open(sys.argv[2],"w").write("Authorization: Bearer "+t+"\n")' "$LAST" "$TOKHDR" 2>/dev/null || die "pair: unexpected response"
    chmod 600 "$TOKHDR"; : > "$LAST"
    echo "paired (session token stored 0600; not shown)"
  else
    echo "pair failed: http=$st error=$(pj 'j.get("error")') triesLeft=$(pj 'j.get("triesLeft")')"; rm -f "$LAST"; exit 1
  fi
}

# The default capture-half graph (every node at its defaults): it compiles to the identity plan (as PlanRoutesTest / PlanCompilerTest).
identity_body() {
  python3 - "$1" <<'PY'
import json,sys
def n(i,t,p=None): return {"id":i,"type":t,"title":i,"params":p or {}}
nodes=[n("head","imu.head_motion"),n("thresh","gate.threshold",{"feature":"gyroPeak","op":">","value":0.6}),n("settle","imu.settle"),
 n("novel","gate.novelty"),n("rate","policy.capture_rate"),n("cap","gate.rate_cap"),n("cam","capture.image"),
 n("cin","signal.in",{"name":"ctx","domain":"RT_GLASSES"}),n("hub","hub.context"),n("cout","signal.out",{"name":"ctx","domain":"RT_PHONE"})]
e=[("head","thresh"),("thresh","settle"),("settle","novel"),("novel","rate"),("cin","rate"),("rate","cap"),("cap","cam"),("hub","cout")]
p={"schema":2,"id":"p","name":"identity","nodes":nodes,"edges":[{"from":a,"to":b} for a,b in e]}
json.dump({"json":p},open(sys.argv[1],"w"))
PY
}

cmd_plan() {
  need_state; init_state; identity_body "$STATE/identity.json"
  local st; st=$(api POST /api/plans/compile "$STATE/identity.json")
  [ "$st" = 200 ] || { errline "$st"; exit 1; }
  local h pr w; h=$(pj 'j.get("configHash")'); pr=$(pj 'len(j.get("problems",[]))'); w=$(pj 'len(j.get("warnings",[]))')
  echo "configHash=$h problems=$pr warnings=$w"
  if [ "$h" = "$EXPECT_HASH" ] && [ "$pr" = 0 ]; then echo "PASS identity plan hash"; else echo "FAIL expected $EXPECT_HASH with 0 problems"; exit 1; fi
}

cmd_store() { # saves the identity plan as a frozen pipeline version so a replay can load its configuration by hash
  need_state; init_state; identity_body "$STATE/identity.json"
  local st id rev ver
  python3 - "$STATE/dup.json" <<'PY'
import json,sys
json.dump({"from":"live-extract","name":"M1 identity capture plan"},open(sys.argv[1],"w"))
PY
  st=$(api POST /api/pipelines "$STATE/dup.json"); [ "$st" = 200 ] || { errline "$st"; exit 1; }
  id=$(pj 'j.get("id")'); [ "$id" != "?" ] || die "no pipeline id"
  python3 - "$STATE/identity.json" "$STATE/save.json" <<'PY'
import json,sys
j=json.load(open(sys.argv[1]))["json"]; j["id"]=sys.argv[0] and j["id"]
json.dump({"json":j,"draftRev":0},open(sys.argv[2],"w"))
PY
  st=$(api PUT "/api/pipelines/$id" "$STATE/save.json"); [ "$st" = 200 ] || { errline "$st"; exit 1; }
  rev=$(pj 'j.get("draftRev")'); echo "draft saved rev=$rev problems=$(pj 'len(j.get("problems",[]))')"
  echo "{\"note\":\"M1 identity capture plan\",\"draftRev\":$rev}" > "$STATE/freeze.json"
  st=$(api POST "/api/pipelines/$id/freeze" "$STATE/freeze.json"); [ "$st" = 200 ] || { errline "$st"; exit 1; }
  echo "frozen: $(python3 -c 'import json;print(json.dumps({k:v for k,v in json.load(open("'"$LAST"'")).items() if k in ("version","n","configHash","hash")}))')"
}

cmd_shadow() {
  need_state; init_state; identity_body "$STATE/identity.json"
  python3 - "$STATE/identity.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1])); d["mode"]="SHADOW"; json.dump(d,open(sys.argv[1],"w"))
PY
  local st; st=$(api POST /api/plans/debug-shadow "$STATE/identity.json")
  [ "$st" = 200 ] || { errline "$st"; [ "$st" = 403 ] && echo "turn Developer mode on in the app"; [ "$st" = 503 ] && echo "the glasses link is not up"; exit 1; }
  echo "label=$(pj 'j.get("label")') mode=$(pj 'j.get("mode")') gen=$(pj 'j.get("gen")') sent=$(pj 'j.get("sent")') configHash=$(pj 'j.get("configHash")') warnings=$(pj 'len(j.get("warnings",[]))')"
  [ "$(pj 'j.get("sent")')" = True ] || echo "NOTE sent=false: the plan is stored and goes out when the glasses link is up"
}

# heartbeat_summary [fromMs toMs] < diag text on stdin: only heartbeat lines, only counts/short fields; the SHADOW heartbeats of the identity plan. With fromMs/toMs (the trace
# interval in wall-clock ms, as the replay job reports it) only heartbeats stamped inside it count, and only those of the expected plan hash; the heartbeat timestamp (MM-DD HH:MM:SS.mmm,
# no year or zone) is read in this machine's zone and in the year of fromMs. Without an interval every SHADOW heartbeat counts (the caller must supply a trimmed log) and the line says so.
heartbeat_summary() {
  python3 -c '
import sys,datetime
lo=int(sys.argv[1]) if len(sys.argv)>2 and sys.argv[1].isdigit() else None
hi=int(sys.argv[2]) if lo is not None and sys.argv[2].isdigit() else None
plan=sys.argv[3] if len(sys.argv)>3 else ""
year=datetime.datetime.fromtimestamp(lo/1000.0).year if lo is not None else None
rows=[]
for line in sys.stdin:
    if "heartbeat v=2" not in line: continue
    kv=dict(t.split("=",1) for t in line.split() if "=" in t)
    p=line.split()
    if lo is not None and hi is not None:
        try: ms=datetime.datetime.strptime("%d-%s %s" % (year,p[0],p[1]),"%Y-%m-%d %H:%M:%S.%f").timestamp()*1000
        except Exception: continue
        if not (lo<=ms<=hi): continue
    rows.append(kv)
sh=[r for r in rows if r.get("planMode")=="SHADOW" and (not plan or r.get("plan","").startswith(plan))]
def i(r,k):
    try: return int(r.get(k,"0"))
    except ValueError: return 0
last=rows[-1] if rows else {}
print("hb_lines=%d shadow_hb=%d plan=%s gen=%s planMode=%s planRevert=%s shOpen=%d shSkip=%d window=%s" % (len(rows),len(sh),last.get("plan","none"),last.get("gen","0"),last.get("planMode","na"),last.get("planRevert","na"),sum(i(r,"shOpen") for r in sh),sum(i(r,"shSkip") for r in sh),"trace-interval" if lo is not None and hi is not None else "UNFILTERED"))' "$@"
}

# diag_text: heartbeat lines from the glasses own-tag log (or $M1_DIAG); no other line is ever read onward
diag_text() {
  if [ -n "${M1_DIAG:-}" ]; then grep 'heartbeat v=2' "$M1_DIAG"; return; fi
  command -v adb >/dev/null 2>&1 || return 0
  { adb -s "$G" exec-out run-as "$PKG" cat files/capture_diag.log.1 2>/dev/null; adb -s "$G" exec-out run-as "$PKG" cat files/capture_diag.log 2>/dev/null; } | grep 'heartbeat v=2'
}

cmd_collect() {
  need_state; init_state
  local sub="${1:-status}" st
  case "$sub" in
    start) local min="${2:-25}"; printf '{"minutes":%s}' "$min" > "$STATE/c.json"; st=$(api POST /api/collect/start "$STATE/c.json") ;;
    stop) st=$(api POST /api/collect/stop) ;;
    status) st=$(api GET /api/collect) ;;
    *) die "usage: collect start [minutes] | stop | status" ;;
  esac
  [ "$st" = 200 ] || { errline "$st"; exit 1; }
  echo "collect active=$(pj 'j.get("active")') untilMs=$(pj 'j.get("untilMs")')"
}

traces_summary() {
  local st; st=$(api GET /api/traces); [ "$st" = 200 ] || { errline "$st"; return 1; }
  python3 -c 'import json,sys
j=json.load(open(sys.argv[1]))
c={}
for t in j: c[t["status"]]=c.get(t["status"],0)+1
print("traces=%d %s"%(len(j)," ".join("%s=%d"%kv for kv in sorted(c.items()))))
for t in j[:10]: print("  trace %s source=%s status=%s exact=%s rev=%s"%(t["traceId"],t["source"],t["status"],t["exact"],t["revision"]))' "$LAST"
}

cmd_status() {
  need_state
  local st; st=$(api GET /api/collect)
  [ "$st" = 200 ] && echo "collect active=$(pj 'j.get("active")') untilMs=$(pj 'j.get("untilMs")')" || errline "$st"
  traces_summary
  st=$(api GET /api/replays); [ "$st" = 200 ] && echo "replays=$(pj 'len(j)') running=$(pj 'sum(1 for r in j if r.get("jobState")=="RUNNING")')"
  local hbs; hbs=$(diag_text | tail -200 | heartbeat_summary)
  echo "glasses (heartbeat tail; the phone has no plan-ack route): $hbs"
  case "$hbs" in "hb_lines=0 "*) echo "NO HEARTBEAT EVIDENCE: no heartbeat v=2 line in the glasses log (diagnostics may be off)" ;; esac
  echo "expect plan=$(echo $EXPECT_HASH | cut -c1-8) planMode=SHADOW once the ack is EFFECTIVE"
}

# phone_tid: the adb transport id of the phone (wireless adb); prints it, or nothing. Never a serial (it has spaces).
phone_tid() {
  if [ -n "${M1_PHONE_TID:-}" ]; then echo "$M1_PHONE_TID"; return; fi
  command -v adb >/dev/null 2>&1 || return 0
  adb devices -l 2>/dev/null | python3 -c 'import re,sys
g=sys.argv[1]; ids=[]
for line in sys.stdin:
    m=re.match(r"^(.*?)\s+device\s.*transport_id:(\d+)",line)
    if m and m.group(1)!=g: ids.append(m.group(2))
print(ids[0] if len(ids)==1 else "")' "$G"
}

# import_traces <dir> <allow-partial 0|1>: adb push each pulled trace dir to the phone staging dir, then POST /api/traces/import (name only)
import_traces() {
  local out="$1" allow="$2" tid d id st n=0 bad=0
  tid=$(phone_tid)
  [ -n "$tid" ] || { echo "FAIL phone adb transport not found: attach the phone (wireless adb) or set M1_PHONE_TID=<transport id from adb devices -l>"; return 1; }
  adb -t "$tid" shell "mkdir -p '$PHONE_STAGING'" >/dev/null 2>&1
  for d in "$out"/*/; do
    [ -f "$d/manifest.json" ] || continue
    id=$(basename "$d")
    case "$id" in *[!a-z0-9-]*|"") echo "skip odd dir name"; continue ;; esac
    adb -t "$tid" shell "rm -rf '$PHONE_STAGING/$id'" >/dev/null 2>&1
    if ! adb -t "$tid" push "${d%/}" "$PHONE_STAGING/" >/dev/null 2>&1; then echo "FAIL adb push of $id"; bad=$((bad + 1)); continue; fi
    printf '{"name":"%s","allowPartial":%s}' "$id" "$([ "$allow" = 1 ] && echo true || echo false)" > "$STATE/imp.json"
    st=$(api POST /api/traces/import "$STATE/imp.json")
    if [ "$st" = 200 ]; then
      echo "imported traceId=$(pj 'j.get("traceId")') status=$(pj 'j.get("status")') events=$(pj 'j.get("events")') chunks=$(pj 'j.get("chunks")') unchanged=$(pj 'j.get("unchanged")')"
      pj 'j.get("traceId")' > "$LAST_TRACE"; n=$((n + 1))
    else
      errline "$st"; [ "$st" = 403 ] && echo "turn Developer mode on in the app"
      [ "$st" = 409 ] && [ "$(pj 'j.get("error")')" = partial_trace ] && echo "PARTIAL trace: rerun with --allow-partial to keep it for replay"
      bad=$((bad + 1))
    fi
  done
  echo "imported $n trace(s), failed $bad"
  [ "$bad" = 0 ] && [ "$n" -gt 0 ]
}

cmd_pull() {
  local out="" allow=0
  while [ $# -gt 0 ]; do case "$1" in --allow-partial) allow=1; shift ;; *) [ -z "$out" ] && out="$1" || die "usage: m1-run.sh pull <dir> [--allow-partial]"; shift ;; esac; done
  [ -n "$out" ] || die "usage: m1-run.sh pull <dir> [--allow-partial]"
  mkdir -p "$out" || die "cannot create $out"
  if [ -z "${M1_DIAG:-}" ]; then "$HERE/trace-pull.sh" "$out" || exit 1; fi
  diag_text > "$out/heartbeats.log"
  echo "heartbeat lines saved: $(wc -l < "$out/heartbeats.log" | tr -d ' ') (no other log read)"
  # LGC4 hazard: with the diagnostic flag off no heartbeat reaches the log: such a run is rejected before the import (M1_ALLOW_NO_HEARTBEAT=1 labels and continues).
  if [ ! -s "$out/heartbeats.log" ]; then
    echo "NO HEARTBEAT EVIDENCE: no heartbeat v=2 line in the glasses log (diagnostics may be off), so the run cannot be attributed to a recording build"
    if [ "${M1_ALLOW_NO_HEARTBEAT:-0}" = 1 ]; then echo "continuing (M1_ALLOW_NO_HEARTBEAT=1): the imported trace is LABELLED unverified"; else echo "rejected before the import; turn Developer mode on and rerun (or M1_ALLOW_NO_HEARTBEAT=1)"; exit 1; fi
  fi
  need_state; init_state
  import_traces "$out" "$allow" || { traces_summary; exit 1; }
  traces_summary
}

# import <dir> [--allow-partial]: only the phone half of pull (used by tools/handover.sh): adb push each trace dir of <dir> to the staging dir and import it; prints "imported traceId=..." lines
cmd_import() {
  local out="" allow=0
  while [ $# -gt 0 ]; do case "$1" in --allow-partial) allow=1; shift ;; *) [ -z "$out" ] && out="$1" || die "usage: m1-run.sh import <dir> [--allow-partial]"; shift ;; esac; done
  [ -d "$out" ] || die "usage: m1-run.sh import <dir> [--allow-partial]"
  need_state; init_state
  import_traces "$out" "$allow"
}

cmd_replay() {
  need_state; init_state
  local tid="" tol=5 diag="" legacy=0 maxfail="${M1_MAX_FAIL_PCT:-3}"
  case "${1:-}" in ""|--*) tid=$(cat "$LAST_TRACE" 2>/dev/null) ;; *) tid="$1"; shift ;; esac
  [ -n "$tid" ] || die "usage: m1-run.sh replay [<traceId>] [--diag file] [--tol pct] [--legacy-total] (no id and no earlier pull)"
  while [ $# -gt 0 ]; do case "$1" in --diag) diag="$2"; shift 2 ;; --tol) tol="$2"; shift 2 ;; --legacy-total) legacy=1; shift ;; --max-fail-pct) [ $# -ge 2 ] || die "--max-fail-pct needs a value"; maxfail="$2"; shift 2 ;; *) die "unknown option" ;; esac; done
  # the failing-window threshold: numeric, at most the hard cap 5; refused before anything is replayed
  python3 -c 'import re,sys
v=sys.argv[1]
sys.exit(0 if re.fullmatch(r"[0-9]+(\.[0-9]+)?",v) and float(v)<=5 else 1)' "$maxfail" || die "REFUSED: max failing-window share '$maxfail' is not a number from 0 to 5 (hard cap 5 percent); nothing was replayed"
  case "$tid" in *[!A-Za-z0-9:_-]*|"") die "bad trace id" ;; esac
  printf '{"traceIds":["%s"],"candidate":{"configHash":"%s"}}' "$tid" "$EXPECT_HASH" > "$STATE/r.json"
  local st; st=$(api POST /api/replays "$STATE/r.json")
  [ "$st" = 202 ] || { errline "$st"; exit 1; }
  local rid; rid=$(pj 'j.get("replayId")'); echo "replay started (counterfactual, identity plan)"
  local i state=RUNNING
  for i in $(seq 1 "${M1_POLLS:-180}"); do
    st=$(api GET "/api/replays/$rid"); [ "$st" = 200 ] || { errline "$st"; exit 1; }
    state=$(pj 'j.get("jobState")'); [ "$state" = RUNNING ] || break
    sleep "${M1_POLL_S:-2}"
  done
  echo "jobState=$state"
  [ "$state" = DONE ] || { echo "FAIL replay did not finish DONE"; exit 1; }
  local dt=""; if [ -n "$diag" ]; then dt=$(grep 'heartbeat v=2' "$diag"); else dt=$(diag_text); fi
  [ -n "$dt" ] || echo "NO HEARTBEAT EVIDENCE: no heartbeat v=2 line in the glasses log (diagnostics may be off): the shadow comparison below cannot pass"
  # The trace interval and plan hash restrict the heartbeats counted (fromMs/toMs of the replay job: the trace's wall-clock interval); an unknown interval leaves them unfiltered.
  local fms tms; fms=$(pj 'j.get("fromMs")'); tms=$(pj 'j.get("toMs")')
  local sh; sh=$(printf '%s\n' "$dt" | heartbeat_summary "$fms" "$tms" "${EXPECT_HASH:0:8}")
  case "$sh" in *UNFILTERED*) echo "NOTE trace interval unknown: every SHADOW heartbeat of the log counts (use --diag with a log trimmed to the run)" ;; esac
  python3 - "$LAST" "$tol" "$sh" "$legacy" "$maxfail" <<'PY'
import json,sys
j=json.load(open(sys.argv[1])); tol=float(sys.argv[2]); sh=dict(t.split("=",1) for t in sys.argv[3].split() if "=" in t); legacy=sys.argv[4]=="1"; maxfail=float(sys.argv[5])
rep=(j.get("result") or {}).get("reports") or []
if not rep: print("FAIL no report in the result"); sys.exit(1)
r=rep[0]["report"]; b=r["baseline"]; c=r.get("candidate") or {}
passed,failed,inex=r["basePassed"],r["baseFailed"],r["inexactWindows"]
print("windows_compared=%d inexact_windows_excluded=%d base_pass=%d base_fail=%d minutes=%.1f" % (passed+failed,inex,passed,failed,r["minutes"]))
print("BatteryLine hours_from_full=%s pct_per_min=%s (estimate)" % (b.get("estimatedHoursFromFull"),b.get("estimatedPctPerMin")))
print("counterfactual opens=%s baseline opens=%s unobservedOpensPct=%s warnings=%d" % (c.get("opens"),b.get("opens"),r.get("unobservedOpensPct"),len(r.get("warnings",[]))))
# Inexact windows are BOUNDED, not ignored (reported separately from the verdict): reasons, minutes covered, delivery stalls, opens as [low, high].
st=r.get("stalls") or {}
print("inexact_bounds reasons=%s covered=%.2f min (%.1f %% of the span) | delivery stalls: %s (%.0f s, %.1f %% of the span)" % (
    json.dumps(r.get("inexactReasons",{}),sort_keys=True),r.get("inexactMinutes",0.0),r.get("inexactSharePct",0.0),st.get("count",0),st.get("seconds",0.0),st.get("sharePct",0.0)))
print("opens_range baseline=[%s, %s] counterfactual=[%s, %s] (low = replayed opens in exact windows + recorded opens in inexact ones; high = low + replayed opens in inexact ones; covers the excluded windows only and assumes every other window passed: NOT a bound while base_fail>0)" % (
    b.get("opensLow",b.get("opens")),b.get("opensHigh",b.get("opens")),c.get("opensLow",c.get("opens")),c.get("opensHigh",c.get("opens"))))
# Gate (user decision): failing windows / ALL compared windows (base_pass + base_fail; the inexact ones are excluded and bounded above) <= T percent (T <= 5, hard cap enforced before the replay)
total=passed+failed; share=(100.0*failed/total) if total else 100.0
ok=total>0 and share<=maxfail
kinds=r.get("baseFailKinds")
print("failing_windows=%d total_windows=%d share=%.2f %% T=%s %%" % (failed,total,share,("%g" % maxfail)))
if kinds: print("failure classification (counts by kind): %s" % ", ".join("%s=%s" % (k,kinds[k]) for k in sorted(kinds)))
else: print("failure classification: unclassified (%d), informational, not a failure" % failed)
print("%s failing-window share %.2f %% <= %s %% (windows>0)" % ("PASS" if ok else "FAIL",share,"%g" % maxfail))
s=int(sh.get("shOpen","0")); n=int(sh.get("shadow_hb","0"))
print("shadow shOpen=%d over %d SHADOW heartbeats (plan=%s planMode=%s)" % (s,n,sh.get("plan"),sh.get("planMode")))
if not c or n==0: print("FAIL no counterfactual or no SHADOW heartbeats to compare"); sys.exit(1)
def verdict(name,cf,limit_pct):
    # tolerance max(limit_pct %, 4 opens) of the counterfactual figure
    allow=max(limit_pct/100.0*cf,4.0); diff=s-cf
    dev=abs(diff)/cf*100 if cf else (0.0 if s==0 else 100.0)
    return abs(diff)<=allow, "%s shadow shOpen=%d vs counterfactual %s=%s, diff %+d (%.1f %%), allowed +-%.1f" % ("PASS" if abs(diff)<=allow else "FAIL",s,name,cf,diff,dev,allow)
if legacy:
    # The old criterion, only valid for SETTLE-wake scripted traces (GoldenScripts): shadow total within +-tol %% of ALL counterfactual opens.
    cf=c.get("opens"); dev=abs(s-cf)/cf*100 if cf else (0 if s==0 else 100)
    ok2=dev<=tol
    print("%s shadow vs counterfactual opens (legacy total criterion, SETTLE-wake scripts only): %d vs %s, deviation %.1f%% (limit %.1f%%)" % ("PASS" if ok2 else "FAIL",s,cf,dev,tol))
else:
    # Real walking traces: shOpen counts glance ADMISSIONS (GlanceAdmission OPEN, whatever the camera is doing, no timer opens), so it is compared with the counterfactual's glance
    # admissions; the DWELL+ONSET opens (admissions that also became a camera open) are printed next to it, never the total opens.
    adm=c.get("glanceAdmissions"); go=c.get("glanceOpens")
    if adm is None: print("FAIL the report has no glanceAdmissions: the phone build predates the stall model (rebuild and reinstall), or use --legacy-total for SETTLE-wake scripts"); sys.exit(1)
    ok2,line=verdict("glance_admissions",adm,tol)
    print(line+" (limit max(%.1f %%, 4 opens))" % tol)
    if go is not None:
        _,l2=verdict("dwell_onset_opens",go,tol); print("INFO "+l2.split(" ",1)[1])
sys.exit(0 if ok and ok2 else 1)
PY
}

[ $# -ge 1 ] || die "usage: m1-run.sh pair|plan|shadow|status|collect|pull|replay ..."
sub="$1"; shift
case "$sub" in
  pair) cmd_pair "$@" ;; store) cmd_store ;; plan) cmd_plan ;; shadow) cmd_shadow ;; status) cmd_status ;;
  collect) cmd_collect "$@" ;; pull) cmd_pull "$@" ;; import) cmd_import "$@" ;; replay) cmd_replay "$@" ;;
  get) need_state; [ -n "${1:-}" ] || die "usage: m1-run.sh get <path>"; api GET "$1" >/dev/null; cat "$LAST"; echo ;;  # read-only: prints the JSON body (the token is only in a header, never in a body)
  *) die "unknown subcommand: $sub" ;;
esac
