#!/usr/bin/env bash
# Fixture test for tools/quality-eval.py against a local python3 mock (http, no TLS, no device). Run from anywhere.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; T="$HERE/testdata/quality-eval"
W=$(mktemp -d); export TMPDIR="$W" M1_SCHEME=http
REQ="$W/requests.log"; : > "$REQ"
python3 "$T/mock_server.py" "$W/port" "$REQ" & MOCK=$!
trap 'kill $MOCK 2>/dev/null; wait $MOCK 2>/dev/null; rm -rf "$W"' EXIT
for i in 1 2 3 4 5 6 7 8 9 10; do [ -s "$W/port" ] && break; sleep 0.3; done
HP="127.0.0.1:$(cat "$W/port")"; Q="$HERE/quality-eval.sh"; fail=0; all=""
chk() { case "$all" in *"$1"*) ;; *) echo "FAIL: expected '$1'"; fail=1 ;; esac; }
no() { case "$all" in *"$1"*) echo "FAIL: must not contain '$1'"; fail=1 ;; esac; }
run() { local o; o=$("$Q" "$@" 2>&1); local rc=$?; echo "\$ quality-eval $1 -> rc=$rc"; all="$all$o"$'\n'; return $rc; }
add() { all="$all$(cat "$1")"$'\n'; }

run capture >/dev/null 2>&1 && { echo "FAIL: capture before pair should fail"; fail=1; }
chk "not paired"
"$HERE/m1-run.sh" pair "$HP" 123456 >/dev/null 2>&1 || { echo "FAIL: pair"; fail=1; }

# all, no samples: no memory text in the report
run all --out "$W/r.md" -n 20 --include-maintenance-reads || { echo "FAIL: all rc"; fail=1; }
chk "report written: $W/r.md"; chk "scores: capture="
add "$W/r.md"; [ -n "${QE_DUMP:-}" ] && cat "$W/r.md"
chk "frames fetched: 20"                       # paging over 5-row pages
chk "frames with no live memory: 4 (20.0 %)"
chk "failed 1 (5.0 %)"; chk "rejected (near-dup) 1"
chk "future (> now+5 min) 1"
chk "exact (normalised) 1"; chk "near-duplicate (content-word Jaccard >= 0.80) 1 memories"
chk "refusal/boilerplate phrases: 2 memories"; chk "'i cannot'=1"
chk "very short < 20 chars 1"
chk "photo byte size: unavailable"
chk "blur below 300: 4 (20.0 %); below 1000: 10 (50.0 %)"
chk "pending frames (CAPTION_QUEUED / PENDING / EXTRACTING): 2 (10.0 %); age in hours"; chk "1-6 h 1; 6-24 h 0; 1-3 days 1"
chk "Moments holding pending frames (matched by time; Moments expose no frame ids): 1; of them without memory: 1"
no "cannot take"; no "snippets=true"; no "80-char snippets"
chk "memories read: 16 from 16 frames"
no "http=400"; no "error=bad_request"; no "views refused/failed"
chk "views refused by the forget gate 2 (incl. redacted), failed 0"
chk "Moments (last 4 days"; chk "photo-less 4 (33.3 %)"
chk "Scenes: **unavailable** (http=404 error=not_found)"
chk "LOCAL-BM25 (reference baseline"; chk "| tfidf | 14 |"; chk "MRR@10"; chk "negative control: "
chk "failure rate by length"; chk "POST /api/retrieve/debug runs the phone's real retriever"
chk "**phone /api/retrieve/debug LEXICAL**"; chk "**phone /api/retrieve/debug SEMANTIC**"; chk "**phone /api/retrieve/debug HYBRID**"
chk "per-mode comparison"; chk "R@5 vs BM25"; chk "semantic unavailable on"
chk "| retrieval (phone) |"
chk "real asks re-retrieved: 3 views; 2 refused by the forget gate"; chk "fallback 1"
chk "pipelines: 2 (built-in 1, with a live version 1, draft with validation problems 1)"
chk "| p1 | 2 | DONE | 4/4 | 0 | 1/3 | 2 |"          # 1 NEEDS_REVIEW item + 1 NEEDS_REVIEW proposal memory ('OK' is not counted)
chk "rolled back 1 (50.0 %)"; chk "flow plans (Query/Nudge) published: 1; still active 1"
chk "batch ERROR 1 (50.0 %)"; chk "replays: **unavailable** (http=403 error=developer_mode_off)"; chk "traces: 2"
chk "answer empty 1 (25.0 %); 'I don't know'-style 1 (25.0 %)"
chk "not resolving to a memory/photo read by this tool or a retrieved hit 1 (50.0 %)"
chk "Nudges (count, dedupe, text length, sourceIds, shown-on): **unavailable**"
chk "run proposals (Run-and-Save): 2 saveable/saved, 1 saved (50.0 %)"
chk "## 5. Scores"; chk "### Prioritised issues"; chk "runs failed/cancelled: 1 of 2 runs (50.0 %)"
chk "## Unavailable routes"
no "Kitchen counter"; no "PROMPT-SHOULDNOTAPPEAR"; no "where did I park"

# default: the maintenance reads (publish, publish history, live: the phone runs trace retention on them) are never sent
all=""; n0=$(grep -cE '^GET /api/(publish|publish/history|live)$' "$REQ"); run canvas --out "$W/m.md"; add "$W/m.md"
chk "publish, publish history, live interpreter: skipped"; no "rolled back 1"
[ "$(grep -cE '^GET /api/(publish|publish/history|live)$' "$REQ")" = "$n0" ] || { echo "FAIL: a maintenance read was sent by default"; fail=1; }

# samples: snippets appear, at most 80 chars
all=""; run capture --out "$W/s.md" --samples 2; add "$W/s.md"
chk "samples, near-duplicate pairs"; chk "Kitchen counter"
long=$(grep -o '`[^`]*`' "$W/s.md" | awk '{ if (length($0) > 82) print }' | head -1)
[ -z "$long" ] || { echo "FAIL: snippet over 80 chars: $long"; fail=1; }

# --retrieve-route none: BM25 only, no POST at all
all=""; n0=$(grep -c '^POST ' "$REQ"); run retrieval --out "$W/p.md" -n 10 --retrieve-route none --modes LEXICAL; add "$W/p.md"
chk "Phone recall is not measured"; no "**phone /api"
[ "$(grep -c '^POST ' "$REQ")" = "$n0" ] || { echo "FAIL: POST sent with the route disabled"; fail=1; }
"$Q" retrieval --retrieve-route /api/runs/r1/cancel >/dev/null 2>&1 && { echo "FAIL: a non-retrieval POST route was accepted"; fail=1; }

# the guard refuses every mutation before sending
python3 - "$HERE/quality-eval.py" <<'PY' || fail=1
import importlib.util, sys
s = importlib.util.spec_from_file_location("qe", sys.argv[1]); qe = importlib.util.module_from_spec(s); s.loader.exec_module(qe)
bad = [("DELETE", "/api/runs/r1"), ("PUT", "/api/pipelines/p1"), ("PATCH", "/api/x"), ("POST", "/api/runs"), ("POST", "/api/publish/rollback"),
       ("POST", "/api/asks/a1/replay"), ("POST", "/api/moments/rebuild"), ("GET", "/etc/passwd")]
for m, p in bad:
    try: qe.guard(m, p); print("FAIL: guard let %s %s through" % (m, p)); sys.exit(1)
    except qe.Refused: pass
qe.READ_ONLY_POSTS.add("/api/runs/r1/cancel")
try: qe.guard("POST", "/api/runs/r1/cancel"); print("FAIL: destructive-looking POST passed"); sys.exit(1)
except qe.Refused: pass
qe.guard("GET", "/api/frames?limit=1")
assert qe.quartiles([1, 2, 3, 4, 5])["median"] == 3 and qe.quartiles([1, 2, 3, 4])["q1"] == 1.75
assert qe.jaccard({"a", "b"}, {"a", "c"}) == 1 / 3.0
assert len(qe.snip("x" * 200)) == 80
assert qe.is_needs_review("NEEDS_REVIEW") and qe.is_needs_review("needs_review") and qe.is_needs_review(True)
assert not qe.is_needs_review("OK") and not qe.is_needs_review("") and not qe.is_needs_review(None) and not qe.is_needs_review(False)
PY

# only GETs (and the allowlisted retrieval POST) ever reached the server
grep -vE '^(GET /api/|POST /api/retrieve/debug$)' "$REQ" | head -3 | grep . && { echo "FAIL: a non-read-only request reached the server"; fail=1; }
grep -qE '^(DELETE|PUT|PATCH) ' "$REQ" && { echo "FAIL: destructive verb sent"; fail=1; }

# rate limit: <= 5 requests/s
n=$(wc -l < "$REQ" | tr -d ' ')
start=$(date +%s); "$Q" canvas --out "$W/c.md" >/dev/null 2>&1; end=$(date +%s)
m=$(( $(wc -l < "$REQ" | tr -d ' ') - n )); el=$(( end - start ))
[ "$m" -le $(( (el + 1) * 5 )) ] || { echo "FAIL: $m requests in ${el}s exceeds 5/s"; fail=1; }

all="$all$(cat "$W"/*.md)"; no "mock-token-xyz"; no "Bearer"; no "123456"
[ "$fail" = 0 ] && echo "ALL OK" || { echo "FAILED"; exit 1; }
