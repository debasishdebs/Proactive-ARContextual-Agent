#!/bin/bash
# Place attempt report for PCA (READ-ONLY, bash 3.2 / macOS awk compatible).
#   tools/place-attempt-report.sh --phone <serial> [filter]      read every pca.log segment from the phone (run-as; debuggable builds only)
#   tools/place-attempt-report.sh --file <oldest> ... [filter]   saved log files, OLDEST FIRST (highest pca.log.N first, pca.log last; no adb)
#   tools/place-attempt-report.sh --fixtures                     parsing fixtures, no adb
# filter: --date MM-DD  |  --since "MM-DD HH:MM" [--until "MM-DD HH:MM"]   (default: every day in the logs)
# --pin-map <file>: two-column text file "id<TAB>label"; labels are printed next to ids on this laptop only (never stored in logs).
# Parses the content-free `place attempt outcome=... ` lines of PlaceSampler (field names as written there, `cand=` or `cands=`) and the
# `pack lookup cell=...` lines of PlacePack. Prints counts, fixed tokens, validated ids and numbers: NEVER a log line, a path or free text.
# Any value that is not a known token / a well-formed id / a number prints as "(garbled)".
set -u
export LC_ALL=C

usage() { sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

REPORT_AWK='
function isnum(s) { return s ~ /^-?[0-9]+$/ && length(s) <= 9 }
function tok(s) { return (s ~ /^[a-z0-9_]+$/ && length(s) <= 40) ? s : "(garbled)" }
function isid(s) { return s ~ /^(wd|osm):[A-Za-z0-9_.\/-]+$/ && length(s) <= 60 }
function islegacypin(s) { return s ~ /^user:/ }   # a line of an older build: a user-pin id is never read into the report, never printed
function secs(t,   a) { split(t, a, ":"); return a[1] * 3600 + a[2] * 60 + a[3] }
function doy(d,   a, m, n, i) { split(d, a, "-"); m = a[1] + 0; n = a[2] + 0; for (i = 1; i < m; i++) n += dim[i]; return n }
function lab(id) { return (id in label) ? "  " label[id] : "" }
function bump(arr, k) { arr[k]++; if (!((k) in order_seen_dummy)) order_seen_dummy[k] = 1 }
# sorted key list of arr into keys[] by value descending then key ascending; returns n
function sortkeys(arr, keys,   k, n, i, j, t) {
  n = 0; for (k in arr) keys[++n] = k
  for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++)
    if (arr[keys[j]] > arr[keys[i]] || (arr[keys[j]] == arr[keys[i]] && keys[j] < keys[i])) { t = keys[i]; keys[i] = keys[j]; keys[j] = t }
  return n
}
function pct(a, n, p,   i, j, t, r) {   # a[1..n] sorted in place (insertion); nearest-rank percentile
  for (i = 2; i <= n; i++) { t = a[i]; j = i - 1; while (j >= 1 && a[j] > t) { a[j + 1] = a[j]; j-- } a[j + 1] = t }
  r = int((p * n + 99) / 100); if (r < 1) r = 1; if (r > n) r = n
  return a[r]
}
function stats(name, a, n) {
  if (n == 0) { printf "  %-8s n=0\n", name; return }
  printf "  %-8s n=%d p50=%d p95=%d max=%d\n", name, n, pct(a, n, 50), pct(a, n, 95), a[n]
}
BEGIN {
  split("31 28 31 30 31 30 31 31 30 31 30 31", dim, " ")
  nk = split("DISABLED ZONE NO_FRAME STALE_FRAME SEEN NO_FIX STALE_FIX NO_CANDIDATES DUPLICATE RATE_LIMITED BUSY MATCHED NONE CONTRACT LOW_CONFIDENCE NEARBY NOTHING_NEARBY DROPPED FAILED NOT_ADMITTED", ko, " ")
  for (i = 1; i <= nk; i++) known[ko[i]] = 1
  if (mapfile != "") { while ((getline ln < mapfile) > 0) { gsub(/\r/, "", ln); p = index(ln, "\t"); if (p > 1) { k = substr(ln, 1, p - 1); v = substr(ln, p + 1); gsub(/[^ -~]/, "", v); if (isid(k)) label[k] = v } } close(mapfile) }
}
NF < 4 || $1 !~ /^[0-9][0-9]-[0-9][0-9]$/ || $2 !~ /^[0-9][0-9]:[0-9][0-9]:[0-9][0-9]/ { next }
{ stamp = $1 " " substr($2, 1, 5) }
since != "" && stamp < since { next }
until != "" && stamp > until { next }
index($0, "pack lookup cell=") > 0 {
  nlk++
  line = $0; p = index(line, "pack lookup "); n = split(substr(line, p + 12), t, " ")
  for (i = 1; i <= n; i++) { e = index(t[i], "="); if (e > 1) lk[substr(t[i], 1, e - 1)] = substr(t[i], e + 1) }
  lcell[tok(tolower(lk["cell"]))]++
  if (lk["online"] == "true" || lk["online"] == "false") lonl[lk["online"]]++
  if (isnum(lk["hits"])) { nh++; hitsv[nh] = lk["hits"] + 0 }
  delete lk
  next
}
index($0, "place attempt") == 0 { next }
{
  line = $0; p = index(line, "place attempt"); n = split(substr(line, p + 13), t, " ")
  for (k in f) delete f[k]
  for (i = 1; i <= n; i++) { e = index(t[i], "="); if (e > 1) { kk = substr(t[i], 1, e - 1); if (!(kk in f)) f[kk] = substr(t[i], e + 1) } }
  if (!("outcome" in f)) { unparsed++; next }
  o = f["outcome"]; if (!(o in known)) { o = "(unknown)" }
  natt++
  h = $1 " " substr($2, 1, 2)
  if (!(h in hs)) { hs[h] = 1; hl[++nhrs] = h }
  hc[h, o]++; oc[o]++
  s = doy($1) * 86400 + secs($2)
  if (haveprev && s >= prevs && s - prevs > maxgap) maxgap = s - prevs
  if (haveprev && s >= prevs) { ngap++ }
  prevs = s; haveprev = 1
  if (o != "MATCHED") { r = ("reason" in f) ? tok(f["reason"]) : "(missing)"; rc[o " " r]++ }
  # call
  c = ("call" in f) ? tok(f["call"]) : "(missing)"; cc[c]++
  if (isnum(f["callMs"]) && f["callMs"] + 0 >= 0) { ncm++; cmv[ncm] = f["callMs"] + 0 }
  if (isnum(f["totalMs"]) && f["totalMs"] + 0 >= 0) { ntm++; tmv[ntm] = f["totalMs"] + 0 }
  # ids
  nid = split(("ids" in f) ? f["ids"] : "", il, ",")
  for (i = 1; i <= nid; i++) { if (isid(il[i])) { offered[il[i]]++; seenid[il[i]] = 1 } else if (il[i] ~ /^\+[0-9]+$/) trunc++; else if (islegacypin(il[i])) legacypin++; else if (il[i] != "-" && il[i] != "") badid++ }
  # user pins are counted only (pinCand = how many were offered, chosen=pin): the logger no longer writes their ids
  if (isnum(f["pinCand"]) && f["pinCand"] + 0 >= 0 && f["pinCand"] + 0 <= 99 && f["pinCand"] + 0 > 0) { pinatt++; pinoffered += f["pinCand"] + 0 }
  ch = ("chosen" in f) ? f["chosen"] : "-"
  if (ch == "pin" || islegacypin(ch)) { pinchosen++; if (o == "MATCHED" && f["card"] == "shown") pinmatched++ }
  else if (isid(ch)) { chosen[ch]++; seenid[ch] = 1; if (o == "MATCHED" && f["card"] == "shown") matched[ch]++
    if (isnum(f["confPct"]) && f["confPct"] + 0 >= 0 && f["confPct"] + 0 <= 100) { v = f["confPct"] + 0; b = (v < 50) ? "00-49" : (v >= 90 ? "90-100" : (int(v / 10) * 10) "-" (int(v / 10) * 10 + 9)); conf[b]++; nconf++ } }
  else if (ch != "-" && ch != "") badid++
  if (o == "MATCHED" && f["card"] == "shown" && !isid(ch) && ch != "pin" && !islegacypin(ch)) matchedNoId++
  # dedupe / rate limit
  if (isnum(f["callGapS"]) && f["callGapS"] + 0 >= 0) { ngs++; gsv[ngs] = f["callGapS"] + 0 }
  if (isnum(f["dedupeAgeS"]) && f["dedupeAgeS"] + 0 >= 0) ndd++
  # cards
  cd = ("card" in f) ? tok(f["card"]) : "(missing)"; cdc[cd]++
  if (cd == "dropped") cw[("cardWhy" in f) ? tok(f["cardWhy"]) : "(missing)"]++
  if ("reveal" in f && f["reveal"] != "-") rv[tok(f["reveal"])]++
  # shadow scoring (1.4.4a): only lines written with placeShadowScore on carry shadow=
  if ("shadow" in f) {
    sh = tok(f["shadow"]); shc[sh]++; nsh++
    if (sh == "ok") {
      ag = ("agrees" in f) ? tok(f["agrees"]) : "(missing)"; if (ag != "true" && ag != "false" && ag != "na") ag = "(garbled)"
      agc[ag]++
      mx = ("shMixed" in f && isnum(f["shMixed"]) && f["shMixed"] + 0 >= 0 && f["shMixed"] + 0 <= 99) ? f["shMixed"] + 0 : 0
      if (ag == "true" || ag == "false") { if (mx > 0) mixc[ag]++; else cleanc[ag]++ }
      tb = ("scoreTop" in f) ? tok(f["scoreTop"]) : "(missing)"; mb = ("margin" in f) ? tok(f["margin"]) : "(missing)"
      if (ag == "true" || ag == "false") { agtop[tb, ag]++; agtopseen[tb] = 1; agmar[mb, ag]++; agmarseen[mb] = 1 }
      if ("chosenRank" in f && isnum(f["chosenRank"]) && f["chosenRank"] + 0 >= 1 && f["chosenRank"] + 0 <= 99) { crk = f["chosenRank"] + 0; if (crk > 5) crk = 5; rkc[crk]++ }
      if ("embedMs" in f && isnum(f["embedMs"]) && f["embedMs"] + 0 >= 0) { nem++; emv[nem] = f["embedMs"] + 0 }
      if ("shCh" in f) shch[tok(f["shCh"])]++
    }
  }
}
END {
  printf "place attempt lines: %d parsed, %d unparseable; pack lookup lines: %d\n", natt + 0, unparsed + 0, nlk + 0
  if (natt == 0 && nlk == 0) { print "no place attempt lines in range"; exit 0 }
  print ""
  print "(1) attempts per hour by outcome"
  nout = sortkeys(oc, ok)
  for (i = 1; i <= nhrs; i++) for (j = i + 1; j <= nhrs; j++) if (hl[j] < hl[i]) { t1 = hl[i]; hl[i] = hl[j]; hl[j] = t1 }
  hdr = sprintf("  %-9s %6s", "hour", "total"); for (j = 1; j <= nout; j++) hdr = hdr sprintf(" %s", ok[j]); print hdr
  for (i = 1; i <= nhrs; i++) { tt = 0; row = ""; for (j = 1; j <= nout; j++) { v = hc[hl[i], ok[j]] + 0; tt += v; row = row sprintf(" %d", v) }
    printf "  %s:xx %6d%s\n", hl[i], tt, row }
  row = ""; for (j = 1; j <= nout; j++) row = row sprintf(" %d", oc[ok[j]]); printf "  %-9s %6d%s\n", "all", natt, row
  if (nlk > 0) {
    printf "  pack lookups: %d", nlk; nlc = sortkeys(lcell, lk2); for (j = 1; j <= nlc; j++) printf "  cell=%s:%d", lk2[j], lcell[lk2[j]]
    printf "  online=true:%d online=false:%d\n", lonl["true"] + 0, lonl["false"] + 0
    if (nh > 0) printf "  pack hits per lookup p50=%d max=%d\n", pct(hitsv, nh, 50), hitsv[nh]
  }
  print ""
  print "(2) reason histogram, non-MATCHED attempts"
  nr = sortkeys(rc, rk); if (nr == 0) print "  (none)"
  for (j = 1; j <= nr; j++) printf "  %5d  %s\n", rc[rk[j]], rk[j]
  print ""
  print "(3) per candidate id: offered / chosen / matched-with-card-shown"
  nids = sortkeys(offered, ik)
  # ids only chosen (never offered) are listed too
  for (k in seenid) if (!(k in offered)) { offered[k] = 0 }
  nids = sortkeys(offered, ik); if (nids == 0) print "  (none)"
  for (j = 1; j <= nids; j++) printf "  %-28s offered=%d chosen=%d matched_card=%d%s\n", ik[j], offered[ik[j]], chosen[ik[j]] + 0, matched[ik[j]] + 0, lab(ik[j])
  printf "  user pins (counts only, no ids): attempts_with_pins=%d pins_offered=%d chosen=%d matched_card=%d\n", pinatt + 0, pinoffered + 0, pinchosen + 0, pinmatched + 0
  if (legacypin + 0 > 0) printf "  note: %d user-pin identifiers from an older build were ignored (never printed)\n", legacypin
  if (trunc + 0 > 0) printf "  note: %d id lists were truncated by the logger (+N tail); those ids are not counted as offered\n", trunc
  if (badid + 0 > 0) printf "  note: %d malformed ids ignored\n", badid
  if (matchedNoId + 0 > 0) printf "  note: %d MATCHED+card=shown attempts had no valid chosen id\n", matchedNoId
  print ""
  print "(4) calls"
  printf "  made=%d skipped_offline=%d skipped_not_admitted=%d none=%d other=%d\n", cc["made"] + 0, cc["skipped_offline"] + 0, cc["skipped_not_admitted"] + 0, cc["none"] + 0, natt - cc["made"] - cc["skipped_offline"] - cc["skipped_not_admitted"] - cc["none"]
  stats("callMs", cmv, ncm); stats("totalMs", tmv, ntm)
  print ""
  print "(5) confidence of chosen candidates (confPct)"
  if (nconf == 0) print "  (none)"
  else { nb = split("00-49 50-59 60-69 70-79 80-89 90-100", bo, " "); for (j = 1; j <= nb; j++) printf "  %-7s %d\n", bo[j], conf[bo[j]] + 0 }
  print ""
  print "(6) dedupe / rate limit / gaps"
  printf "  DUPLICATE=%d RATE_LIMITED=%d SEEN=%d BUSY=%d NOT_ADMITTED=%d  attempts_with_dedupeAge=%d\n", oc["DUPLICATE"] + 0, oc["RATE_LIMITED"] + 0, oc["SEEN"] + 0, oc["BUSY"] + 0, oc["NOT_ADMITTED"] + 0, ndd + 0
  stats("callGapS", gsv, ngs)
  if (ngap > 0) printf "  longest gap without an attempt: %d s (%d min; between consecutive attempt lines in file order)\n", maxgap, int(maxgap / 60); else print "  longest gap without an attempt: n/a (fewer than 2 attempts)"
  print ""
  print "(7) cards"
  printf "  shown=%d dropped=%d none=%d other=%d\n", cdc["shown"] + 0, cdc["dropped"] + 0, cdc["none"] + 0, natt - cdc["shown"] - cdc["dropped"] - cdc["none"]
  ncw = sortkeys(cw, wk); for (j = 1; j <= ncw; j++) printf "  dropped because %-28s %d\n", wk[j], cw[wk[j]]
  nrv = sortkeys(rv, vk); for (j = 1; j <= nrv; j++) printf "  reveal=%-10s %d\n", vk[j], rv[vk[j]]
  print ""
  print "(8) shadow agreement (the model choice vs the top on-device score; shadow never changes a card)"
  if (nsh + 0 == 0) print "  (no shadow lines: placeShadowScore was off)"
  else {
    printf "  shadow lines=%d ok=%d err=%d na=%d\n", nsh, shc["ok"] + 0, shc["err"] + 0, shc["na"] + 0
    nd = agc["true"] + agc["false"]
    if (nd > 0) printf "  agreement: agrees=%d disagrees=%d (%d%% of %d decided); not decided (na)=%d\n", agc["true"], agc["false"], int((agc["true"] * 100) / nd + 0.5), nd, agc["na"] + 0
    else printf "  agreement: no decided attempts; not decided (na)=%d\n", agc["na"] + 0
    # shMixed>0 = a pin + pack attempt on the image channel: the pack places were dropped from the ranking, so its agreement is skewed near a pin and is shown apart
    printf "  unmixed (no pack place dropped from the ranking): agrees=%d disagrees=%d\n", cleanc["true"] + 0, cleanc["false"] + 0
    printf "  mixed (shMixed>0, pack places dropped from the image ranking): agrees=%d disagrees=%d\n", mixc["true"] + 0, mixc["false"] + 0
    nch = sortkeys(shch, chk); for (j = 1; j <= nch; j++) printf "  channel %-6s %d\n", chk[j], shch[chk[j]]
    nb = split("lt200 lt300 lt400 lt500 lt600 lt700 lt800 lt900 ge900", tbo, " ")
    for (j = 1; j <= nb; j++) if (agtopseen[tbo[j]]) printf "  scoreTop=%-6s agrees=%d disagrees=%d\n", tbo[j], agtop[tbo[j], "true"] + 0, agtop[tbo[j], "false"] + 0
    nb = split("lt10 lt25 lt50 lt100 ge100", mbo, " ")
    for (j = 1; j <= nb; j++) if (agmarseen[mbo[j]]) printf "  margin=%-6s   agrees=%d disagrees=%d\n", mbo[j], agmar[mbo[j], "true"] + 0, agmar[mbo[j], "false"] + 0
    printf "  chosenRank by score: 1=%d 2=%d 3=%d 4=%d 5+=%d\n", rkc[1] + 0, rkc[2] + 0, rkc[3] + 0, rkc[4] + 0, rkc[5] + 0
    stats("embedMs", emv, nem)
  }
}'

report_stdin() { # args: since until mapfile
  awk -v since="${1:-}" -v until="${2:-}" -v mapfile="${3:-}" "$REPORT_AWK"
}

segment_order_all() {
  awk '$0 == "pca.log" { print "0 " $0; next } $0 ~ /^pca\.log\.[0-9]+$/ { print (substr($0, 9) + 0) " " $0 }' | sort -rn | awk '{ print $2 }'
}

fixtures() {
  local pass=0 fail=0 out
  check() { if [ "$2" = "$3" ]; then pass=$((pass + 1)); echo "  fixture ok      : $1"; else fail=$((fail + 1)); echo "  FIXTURE FAILED  : $1"; echo "    want: $2"; echo "    got : $3"; fi; }
  has() { printf '%s\n' "$2" | grep -c -- "$3"; }
  local A='11-19 21:00:00.000 I PcPlace place attempt outcome=MATCHED reason=matched call=made online=true cell=FRESH cand=3 ids=wd:Q1,osm:node/5 pinCand=1 frameAgeS=2 fixAgeB=le5s accB=le10m candMs=12 callMs=900 totalMs=1200 chosen=wd:Q1 distB=lt25m confPct=95 dedupeAgeS=-1 dedupeDistB=- callGapS=-1 card=shown cardWhy=- reveal=ok err=-'
  local B='11-19 21:10:00.000 I PcPlace place attempt outcome=NEARBY reason=nearby_card call=skipped_offline online=false cell=STALE cand=2 ids=wd:Q1,wd:Q2 frameAgeS=3 fixAgeB=le5s accB=le25m candMs=5 callMs=-1 totalMs=40 chosen=wd:Q2 distB=lt50m confPct=-1 dedupeAgeS=-1 dedupeDistB=- callGapS=-1 card=shown cardWhy=- reveal=ok err=-'
  local C='11-19 22:30:00.000 I PcPlace place attempt outcome=LOW_CONFIDENCE reason=low_confidence call=made online=true cell=FRESH cand=2 ids=wd:Q1,wd:Q2 frameAgeS=1 fixAgeB=le5s accB=le10m candMs=9 callMs=1500 totalMs=1800 chosen=wd:Q2 distB=lt100m confPct=42 dedupeAgeS=-1 dedupeDistB=- callGapS=600 card=none cardWhy=- reveal=- err=-'
  local D='11-19 22:31:00.000 I PcPlace place attempt outcome=NOT_ADMITTED reason=not_admitted call=skipped_not_admitted online=true cell=FRESH cand=1 ids=wd:Q1 frameAgeS=1 fixAgeB=le5s accB=le10m candMs=3 callMs=-1 totalMs=20 chosen=- distB=- confPct=-1 dedupeAgeS=-1 dedupeDistB=- callGapS=-1 card=none cardWhy=- reveal=- err=-'
  local E='11-19 22:32:00.000 I PcPlace place attempt outcome=DUPLICATE reason=same_place call=none online=true cell=- cand=0 ids=- frameAgeS=1 fixAgeB=- accB=- candMs=-1 callMs=-1 totalMs=2 chosen=- distB=- confPct=-1 dedupeAgeS=120 dedupeDistB=lt25m callGapS=-1 card=none cardWhy=- reveal=- err=-'
  local F='11-19 22:33:00.000 I PcPlace place attempt outcome=RATE_LIMITED reason=call_gap call=none online=true cell=- cand=0 ids=- frameAgeS=1 fixAgeB=- accB=- candMs=-1 callMs=-1 totalMs=2 chosen=- distB=- confPct=-1 dedupeAgeS=-1 dedupeDistB=- callGapS=30 card=none cardWhy=- reveal=- err=-'
  local G='11-19 22:34:00.000 I PcPlace place attempt outcome=DROPPED reason=card_dropped call=made online=true cell=FRESH cand=1 ids=osm:node/5 frameAgeS=1 fixAgeB=le5s accB=le10m candMs=3 callMs=700 totalMs=900 chosen=osm:node/5 distB=lt25m confPct=88 dedupeAgeS=-1 dedupeDistB=- callGapS=-1 card=dropped cardWhy=withdrawn_or_fenced reveal=- err=-'
  local H='11-19 22:35:00.000 I PcPlace place attempt outcome=FAILED reason=exception call=made online=true cell=FRESH cand=1 ids=wd:Q9 frameAgeS=1 fixAgeB=le5s accB=le10m candMs=3 callMs=100 totalMs=150 chosen=- distB=- confPct=-1 dedupeAgeS=-1 dedupeDistB=- callGapS=-1 card=none cardWhy=- reveal=- err=IOException'
  local I='11-19 22:36:00.000 I PcPlace place attempt outcome=NO_FIX reason=no_fix call=none'
  local J='11-19 22:37:00.000 I PcPlace place attempt outcome=NO_CANDIDATES reason=empty call=none ids=- card=none'
  local K='11-19 22:38:00.000 I PcPlace place attempt outcome=ZONE reason=cancelled_by_zone_fence call=none'
  local L='11-19 22:39:00.000 I PcPlace place attempt outcome=NONE reason=none_of_them call=made ids=wd:Q1 callMs=800 totalMs=900 chosen=- confPct=-1 card=none'
  local ALL; ALL="$(printf '%s\n' "$A" "$B" "$C" "$D" "$E" "$F" "$G" "$H" "$I" "$J" "$K" "$L")"

  out="$(printf '%s\n' "$ALL" | report_stdin)"
  check "12 attempts parsed, none unparseable" "place attempt lines: 12 parsed, 0 unparseable; pack lookup lines: 0" "$(printf '%s\n' "$out" | sed -n 1p)"
  check "every outcome in the hour table (all row)" "1" "$(has x "$(printf '%s\n' "$out" | grep '^  all ')" '^  all  *12 ')"
  check "hours: 21 has 2 attempts, 22 has 10" "2 10" "$(printf '%s\n' "$out" | awk '/^  11-19 2[12]:xx/{printf "%s ", $3}' | sed 's/ $//')"
  check "reason histogram excludes MATCHED" "0" "$(has x "$(printf '%s\n' "$out" | sed -n '/^(2)/,/^(3)/p')" 'MATCHED matched')"
  check "reason histogram counts nearby_card" "1" "$(has x "$(printf '%s\n' "$out" | sed -n '/^(2)/,/^(3)/p')" 'NEARBY nearby_card')"
  check "id wd:Q1 offered 4 chosen 1 matched_card 1" "  wd:Q1                        offered=5 chosen=1 matched_card=1" "$(printf '%s\n' "$out" | grep '^  wd:Q1 ')"
  check "id osm:node/5: offered 2, chosen 1, matched 0 (dropped card)" "  osm:node/5                   offered=2 chosen=1 matched_card=0" "$(printf '%s\n' "$out" | grep '^  osm:node/5 ')"
  check "call counts" "made=5 skipped_offline=1 skipped_not_admitted=1 none=5 other=0" "$(printf '%s\n' "$out" | grep '^  made=' | head -1 | sed 's/^ *//')"
  check "callMs p50/p95/max over 5 values (100 700 800 900 1500)" "  callMs   n=5 p50=800 p95=1500 max=1500" "$(printf '%s\n' "$out" | grep '^  callMs')"
  check "confidence histogram" "00-49:1 50-59:0 60-69:0 70-79:0 80-89:1 90-100:1" "$(printf '%s\n' "$out" | sed -n '/^(5)/,/^(6)/p' | awk '$1 ~ /^[0-9]/{printf "%s:%s ", $1, $2}' | sed 's/ $//')"
  check "dedupe/rate counts and longest gap (21:10 -> 22:30 = 4800 s)" "1 1 4800" "$(printf '%s\n' "$out" | awk '/^  DUPLICATE=/{split($1,a,"="); split($2,b,"="); d=a[2]; r=b[2]} /longest gap/{print d, r, $6}')"
  check "cards: shown 2 dropped 1, cause named" "shown=2 dropped=1 none=7 other=2|dropped because withdrawn_or_fenced" "$(printf '%s\n' "$out" | sed -n '/^(7)/,$p' | awk '/^  shown=/{a=$0} /dropped because/{b=$1" "$2" "$3}END{gsub(/^ +/,"",a); print a "|" b}')"

  out="$(printf '%s\n' "$ALL" | report_stdin "11-19 22:00" "11-19 22:31")"
  check "--since/--until window keeps 22:30 and 22:31 only" "place attempt lines: 2 parsed, 0 unparseable; pack lookup lines: 0" "$(printf '%s\n' "$out" | sed -n 1p)"
  out="$(printf '%s\n' "$ALL" | report_stdin "11-20 00:00" "11-20 23:59")"
  check "a day with no lines says so" "no place attempt lines in range" "$(printf '%s\n' "$out" | tail -1)"

  # hostile / garbled / missing
  local HOSTILE; HOSTILE="$(printf '%s\n' 'x' '' '   ' '11-19 23:00:00.000 I PcPlace place attempt' '11-19 23:00:01.000 I PcPlace place attempt outcome=BOGUS reason=Foo/Bar-SECRETNAME call=made ids=Hello,wd:Q1,<script>,+4,user:leak-1 World callMs=abc totalMs=99999999999 chosen=/etc/passwd confPct=999 card=shown cardWhy=x' '11-19 23:00:02.000 I PcPlace place attempt outcome=MATCHED outcome=NONE reason=ok=ok=ok ids===,,, chosen=wd:Q7 confPct=-5 card=SHOWN' 'garbage place attempt outcome=MATCHED' '11-19 23:00:03.000 I PcPlace place attempt outcome=DROPPED card=dropped')"
  out="$(printf '%s\n' "$HOSTILE" | report_stdin)"
  check "hostile lines: counted, never crash (1 unparseable, 3 parsed)" "place attempt lines: 3 parsed, 1 unparseable; pack lookup lines: 0" "$(printf '%s\n' "$out" | sed -n 1p)"
  check "garbled reason/outcome printed as tokens, not text" "1" "$(has x "$out" '(unknown) (garbled)')"
  check "missing fields reported as (missing), duplicate key keeps the first" "2" "$(printf '%s\n' "$out" | grep -c '(missing)\|MATCHED ok')"
  check "out-of-range confPct ignored (no histogram row filled)" "0" "$(printf '%s\n' "$out" | sed -n '/^(5)/,/^(6)/p' | awk '$1 ~ /^[0-9]/ && $2 > 0' | wc -l | tr -d ' ')"
  check "malformed ids counted, truncated tail noted" "1|1" "$(printf '%s\n' "$out" | grep -c 'malformed ids ignored')|$(printf '%s\n' "$out" | grep -c 'truncated by the logger')"

  # sentinel: nothing from the line text is echoed
  local SENT='11-19 23:10:00.000 I PcPlace place attempt outcome=MATCHED reason=SENTINEL-Secret_Name call=made ids=Cafe_Rosa_Sentinel,wd:Q5 chosen=Cafe_Rosa_Sentinel card=shown cardWhy=Priya_Sentinel err=/data/user/0/Sentinel/x.jpg'
  out="$(printf '%s\n' "$SENT" "$HOSTILE" "$ALL" | report_stdin)"
  check "sentinel: no line text echoed" "0" "$(has x "$out" -i 'sentinel\|Cafe_Rosa\|Priya\|/data/\|passwd\|script\|Hello\|SECRETNAME\|place attempt outcome\|ids=')"

  # pack lookups
  local P='11-19 21:00:00.000 I PcPlace pack lookup cell=FRESH online=true inWindow=5 hits=3 expanded=4 pins=1 ms=12
11-19 21:01:00.000 I PcPlace pack lookup cell=STALE online=false inWindow=0 hits=0 expanded=0 pins=0 ms=3'
  out="$(printf '%s\n%s\n' "$P" "$A" | report_stdin)"
  check "pack lookups counted by cell and online" "  pack lookups: 2  cell=fresh:1  cell=stale:1  online=true:1 online=false:1" "$(printf '%s\n' "$out" | grep '^  pack lookups')"

  # pin map: labels appear only through the map file
  local MAP; MAP="$(mktemp -t pinmap)"; printf 'wd:Q1\tHome\nbadid\tNope\nwd:Q2\tOffice\r\n' > "$MAP"
  out="$(printf '%s\n' "$ALL" | report_stdin "" "" "$MAP")"; rm -f "$MAP"
  check "pin map labels printed next to ids, bad rows ignored" "1|1|0" "$(printf '%s\n' "$out" | grep -c '^  wd:Q1 .*  Home$')|$(printf '%s\n' "$out" | grep -c '^  wd:Q2 .*  Office$')|$(printf '%s\n' "$out" | grep -c 'Nope')"

  # segment ordering: oldest first, so the longest gap spans segments and arrives in order
  check "segments come out oldest first, active file last, junk ignored" "pca.log.12 pca.log.2 pca.log.1 pca.log" "$(printf '%s\n' pca.log pca.log.1 pca.log.2 notes.txt pca.log.12 pca.log.x pca.log.bak | segment_order_all | tr '\n' ' ' | sed 's/ $//')"
  out="$(printf '%s\n%s\n' "$A" "$B" | report_stdin | grep 'longest gap')"
  local out2; out2="$(printf '%s\n%s\n' "$B" "$A" | report_stdin | grep 'longest gap')"
  check "gap needs file order oldest first (reversed input yields no negative gap)" "longest gap without an attempt: 600 s (10 min; between consecutive attempt lines in file order)|longest gap without an attempt: n/a (fewer than 2 attempts)" "$(printf '%s' "$out" | sed 's/^ *//')|$(printf '%s' "$out2" | sed 's/^ *//')"
  check "CRLF logs are accepted via tr" "place attempt lines: 1 parsed, 0 unparseable; pack lookup lines: 0" "$(printf '%s\r\n' "$A" | tr -d '\r' | report_stdin | sed -n 1p)"
  # shadow agreement section
  local S1='11-20 10:00:00.000 I PcPlace place attempt outcome=MATCHED reason=matched call=made ids=wd:Q1,wd:Q2 chosen=wd:Q1 card=shown shadow=ok shCh=text scoreTop=lt300 margin=ge100 chosenRank=1 agrees=true embedMs=120'
  local S2='11-20 10:01:00.000 I PcPlace place attempt outcome=MATCHED reason=matched call=made ids=wd:Q1,wd:Q2 chosen=wd:Q2 card=shown shadow=ok shCh=text scoreTop=lt300 margin=lt10 chosenRank=2 agrees=false embedMs=140'
  local S3='11-20 10:02:00.000 I PcPlace place attempt outcome=MATCHED reason=matched call=made ids=wd:Q1,wd:Q2 chosen=wd:Q1 card=shown shadow=ok shCh=img scoreTop=ge900 margin=ge100 chosenRank=1 agrees=true embedMs=160'
  local S4='11-20 10:03:00.000 I PcPlace place attempt outcome=NONE reason=model_none call=made ids=wd:Q1 chosen=- card=none shadow=ok shCh=text scoreTop=lt400 margin=na chosenRank=na agrees=na embedMs=100'
  local S5='11-20 10:04:00.000 I PcPlace place attempt outcome=MATCHED reason=matched call=made ids=wd:Q1 chosen=wd:Q1 card=shown shadow=err'
  local S6='11-20 10:05:00.000 I PcPlace place attempt outcome=MATCHED reason=matched call=made ids=wd:Q1 chosen=wd:Q1 card=shown shadow=na'
  local SHALL; SHALL="$(printf '%s\n' "$S1" "$S2" "$S3" "$S4" "$S5" "$S6")"
  out="$(printf '%s\n' "$SHALL" | report_stdin)"
  check "shadow: line counts" "shadow lines=6 ok=4 err=1 na=1" "$(printf '%s\n' "$out" | grep '^  shadow lines=' | sed 's/^ *//')"
  check "shadow: agreement 2 of 3 decided, one undecided" "agreement: agrees=2 disagrees=1 (67% of 3 decided); not decided (na)=1" "$(printf '%s\n' "$out" | grep '^  agreement:' | sed 's/^ *//')"
  check "shadow: by score bucket" "scoreTop=lt300 agrees=1 disagrees=1|scoreTop=ge900 agrees=1 disagrees=0" "$(printf '%s\n' "$out" | grep '^  scoreTop=' | sed 's/^ *//' | tr -s ' ' | tr '\n' '|' | sed 's/|$//')"
  check "shadow: by margin bucket" "margin=lt10 agrees=0 disagrees=1|margin=ge100 agrees=2 disagrees=0" "$(printf '%s\n' "$out" | grep '^  margin=' | sed 's/^ *//' | tr -s ' ' | tr '\n' '|' | sed 's/|$//')"
  check "shadow: chosenRank and embedMs" "chosenRank by score: 1=2 2=1 3=0 4=0 5+=0|embedMs n=4 p50=120 p95=160 max=160" "$(printf '%s\n' "$out" | grep -E '^  (chosenRank|embedMs)' | sed 's/^ *//' | tr -s ' ' | tr '\n' '|' | sed 's/|$//')"
  local M1='11-20 10:06:00.000 I PcPlace place attempt outcome=MATCHED reason=matched call=made ids=wd:Q1,wd:Q2 pinCand=1 chosen=pin card=shown shadow=ok shCh=image scoreTop=ge900 margin=ge100 chosenRank=1 agrees=true shMixed=2 embedMs=150'
  local M2='11-20 10:07:00.000 I PcPlace place attempt outcome=MATCHED reason=matched call=made ids=wd:Q1 pinCand=1 chosen=wd:Q1 card=shown shadow=ok shCh=image scoreTop=lt600 margin=lt25 chosenRank=na agrees=na shMixed=1 embedMs=170'
  local M3='11-20 10:08:00.000 I PcPlace place attempt outcome=MATCHED reason=matched call=made pinCand=1 chosen=wd:Q1 card=shown shadow=ok shCh=image scoreTop=lt600 margin=lt25 chosenRank=2 agrees=false shMixed=0 embedMs=130'
  out="$(printf '%s\n' "$SHALL" "$M1" "$M2" "$M3" | report_stdin)"
  check "shadow: mixed attempts are shown apart" "unmixed (no pack place dropped from the ranking): agrees=2 disagrees=2|mixed (shMixed>0, pack places dropped from the image ranking): agrees=1 disagrees=0" "$(printf '%s\n' "$out" | grep -E '^  (unmixed|mixed) ' | sed 's/^ *//' | tr '\n' '|' | sed 's/|$//')"
  check "user pins: counted only (pinCand, chosen=pin), no id anywhere" "  user pins (counts only, no ids): attempts_with_pins=3 pins_offered=3 chosen=1 matched_card=1" "$(printf '%s\n' "$out" | grep '^  user pins')"
  out="$(printf '%s\n' "$A" "$HOSTILE" | report_stdin)"
  check "an older line with a user-pin id: counted as ignored, never printed" "1|0" "$(printf '%s\n' "$out" | grep -c 'user-pin identifiers from an older build were ignored')|$(printf '%s\n' "$out" | grep -c 'leak-1')"
  out="$(printf '%s\n' 'x' '11-20 10:00:00.000 I PcPlace place attempt outcome=MATCHED shadow=ok agrees=true shMixed=/etc/passwd chosenRank=1 shCh=image' | report_stdin)"
  check "shadow: a garbled shMixed counts as unmixed and is never echoed" "0" "$(has x "$out" -i 'passwd')"
  out="$(printf '%s\n' "$ALL" | report_stdin)"
  check "shadow: no shadow lines says so" "(no shadow lines: placeShadowScore was off)" "$(printf '%s\n' "$out" | grep 'no shadow lines' | sed 's/^ *//')"
  out="$(printf '%s\n' 'x' '11-20 10:00:00.000 I PcPlace place attempt outcome=MATCHED shadow=ok agrees=/etc/passwd scoreTop=SECRET_Name chosenRank=999 embedMs=abc shCh=Foo/Bar' | report_stdin)"
  check "shadow: hostile values never echoed" "0" "$(has x "$out" -i 'passwd\|SECRET_Name\|Foo/Bar')"
  echo "fixtures: $pass passed, $fail failed"
  [ "$fail" = 0 ]
}

PHONE="" SINCE="" UNTIL="" DAY="" MAP="" FILES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --phone) PHONE="${2:-}"; shift 2 ;;
    --date) DAY="${2:-}"; shift 2 ;;
    --since) SINCE="${2:-}"; shift 2 ;;
    --until) UNTIL="${2:-}"; shift 2 ;;
    --pin-map) MAP="${2:-}"; shift 2 ;;
    --file) FILES+=("${2:-}"); shift 2 ;;
    --fixtures) fixtures; exit $? ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done
if [ -n "$DAY" ]; then
  case "$DAY" in [0-1][0-9]-[0-3][0-9]) ;; *) echo "REFUSED: --date must be MM-DD" >&2; exit 2 ;; esac
  [ -z "$SINCE$UNTIL" ] || { echo "REFUSED: use --date OR --since/--until" >&2; exit 2; }
  SINCE="$DAY 00:00"; UNTIL="$DAY 23:59"
fi
for v in "$SINCE" "$UNTIL"; do
  case "$v" in ""|[0-1][0-9]-[0-3][0-9]\ [0-2][0-9]:[0-5][0-9]) ;; *) echo "REFUSED: --since/--until must be \"MM-DD HH:MM\"" >&2; exit 2 ;; esac
done
[ -z "$MAP" ] || [ -r "$MAP" ] || { echo "REFUSED: cannot read pin map $MAP" >&2; exit 2; }
if [ -n "$PHONE" ] && [ ${#FILES[@]} -gt 0 ]; then echo "REFUSED: use --phone OR --file, not both" >&2; exit 2; fi
if [ -z "$PHONE" ] && [ ${#FILES[@]} -eq 0 ]; then usage; fi

echo "place attempt report: ${SINCE:-all days}${UNTIL:+ .. $UNTIL} (counts only; read-only)"
if [ -n "$PHONE" ]; then
  SEGS="$(adb -s "$PHONE" exec-out run-as com.rokid.context ls files/logs </dev/null 2>/dev/null | tr -d '\r' | segment_order_all)"
  [ -n "$SEGS" ] || SEGS="$(printf 'pca.log.1\npca.log\n')"
  { for f in $SEGS; do adb -s "$PHONE" exec-out run-as com.rokid.context cat "files/logs/$f" </dev/null 2>/dev/null; done; } | tr -d '\r' | report_stdin "$SINCE" "$UNTIL" "$MAP"
else
  for f in "${FILES[@]}"; do [ -r "$f" ] || { echo "REFUSED: cannot read $f" >&2; exit 2; }; done
  cat "${FILES[@]}" | tr -d '\r' | report_stdin "$SINCE" "$UNTIL" "$MAP"
fi
