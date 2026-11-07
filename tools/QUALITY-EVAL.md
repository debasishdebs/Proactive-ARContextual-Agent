# Quality evaluation harness (read-only)

`tools/quality-eval.sh` (wrapper for `tools/quality-eval.py`, python3 stdlib only) evaluates the Proactive Context phone app through the
canvas LAN server and writes a markdown report. It never mutates the phone.

## Usage

```sh
tools/m1-run.sh pair <host:port> <code> <fp8>      # once: stores host, pinned certificate and session token (0600) in $TMPDIR/m1-run-<uid>/
tools/quality-eval.sh all --out report.md          # every section + scores + prioritised issues
tools/quality-eval.sh capture|retrieval|canvas|output [--out file.md]
tools/test-quality-eval.sh                         # harness self-test against a local mock (no device)
```

Options: `--samples N` adds up to N text snippets (80 chars) per list (default none: no memory, question or answer text in the report);
`-n 100` retrieval sample size; `--seed 7`; `--rate 5` requests/s (capped at 5); `--max-frames 3000`; `--max-detail 400` (frames whose memories are read);
`--max-days 14` (Moments); `--max-asks 50`; `--max-pipelines 30`; `--runs-per-pipeline 3`; `--jaccard 0.8` (near-duplicate threshold);
`--retrieve-route` (default `/api/retrieve/debug`; `none` = local BM25 only; any other path must name a retrieval route) and `--modes LEXICAL,SEMANTIC,HYBRID` (see Retrieval);
`--max-harvest 300` (retrieval queries spent harvesting memory snippets when frame ids cannot be read; 0 = none);
`--include-maintenance-reads` (off by default; see Safety: with it the run is NOT read-only).

## Safety

- Only `GET`, plus a `POST` on an explicit read-only allowlist: the one retrieval route (`/api/retrieve/debug` by default, none with `--retrieve-route none`). `guard()` refuses any other verb or path
  before the socket is opened, and a final check confirms that no other request was sent. The test asserts that the mock saw no `PUT`, `DELETE` or `PATCH`.
- Maintenance reads: the phone runs its trace retention (at most hourly, it DELETEs old trace rows) inside `GET /api/publish`, `/api/publish/history` and `/api/live`.
  By default the harness never sends these three (Canvas says "skipped"; the rollback and live-interpreter scores are then not measured) and a final check asserts it.
  `--include-maintenance-reads` sends them: the run is then **not read-only** (the phone may delete old traces, debug data only). The verb guard and the single allowed POST are unchanged.
- TLS: the peer certificate must be byte-identical to the `cert.der` that `m1-run.sh pair` pinned after the fingerprint check. On a mismatch nothing is sent.
- The token is read from `auth.hdr` and is never printed. Errors show only the HTTP status and the fixed `error` code.
- `GET /api/asks/:id` returns the composed prompt. The harness reads only its scores and ids and never writes the prompt to the report.

## Frame ids and the memory corpus

Every `/api/<x>/:id` route accepts only ids matching `^[A-Za-z0-9:_-]{1,64}$`, sent literally (the phone does not percent-decode). Real frame ids end in `.v2`
(`<ts>_rokid_..._<hash>.v2`), and `.` is outside that pattern, so `GET /api/frames/:id` answers `400 bad_request` for every real frame (a phone-side bug: the
id pattern or the frame id format has to change). The harness never sends an id the route would reject; it counts them and, so the retrieval self-test still runs,
harvests memories through `POST /api/retrieve/debug` with `snippets: true` (read-only, <= 80 chars per memory, `--max-harvest` queries, seeded with generic words and
then the rarest words seen). That corpus is only what the LEXICAL retriever can surface, so recall is optimistic; the report says so. Frame-route memories (full text) win when both exist.
Ask views answering `409 refused` count as refused by the forget gate, not as an unavailable route.

## Routes per section

| section | routes | unavailable |
|---|---|---|
| 1 Capture | `GET /api/frames` (paged by `before`), `/api/frames/:id` (memory text, spread evenly over time), `/api/moments?day=`, `/api/scenes` | photo byte size, extraction confidence, per-frame needs-review, glasses-to-phone clock offset |
| 2 Retrieval | `POST /api/retrieve/debug` (the phone's real retriever per mode); local BM25 reference over the memories read (comparison column); real-ask diagnostics from `GET /api/asks/:id` (re-runs retrieval as of the ask: mode, fallback, lexical/semantic/fused scores) | `POST /api/asks/:id/replay` calls the Answer model and is never sent. An older phone build without the route answers 404: the section then shows BM25 only |
| 3 Canvas | `GET /api/pipelines(/:id)`, `/api/runs(/:id, /:id/proposal)`, `/api/publish`, `/api/publish/history`, `/api/live` (these three only with `--include-maintenance-reads`), `/api/plans/status`, `/api/signals`, `/api/traces`, `/api/replays`, `/api/sweeps`, `/api/devices` | guard configs, flow (Query/Nudge) execution history; traces and replays need Developer mode (403 otherwise) |
| 4 Output | `GET /api/asks`, `/api/asks/:id` | ask latency, Nudges (no route), proposal undo |

A route that answers anything other than 200 is recorded under "Unavailable routes" with its status code, and that section continues without it.

## The retrieval route

`POST /api/retrieve/debug`, body `{"query": "...", "mode": "LEXICAL|SEMANTIC|HYBRID" (optional, default the user's setting), "topK": 1..30 (optional), "notAfterMs": n (optional), "snippets": true (optional)}`.
Reply `{mode, semanticUnavailable, fallback, hits:[{memoryId, score, rank, reviewState, createdAt, snippet?}]}`. It runs `AnswerService.retrieveScored` (the Ask retriever)
and nothing else: no model or role call, no write, no photo hits, no other context source. Memory text is returned only as an 80-char `snippet` when `snippets` is true
(this tool never sets it). The query is never logged or echoed. It is authenticated and same-origin like every `/api` route (and so needs Developer mode, which
runs the server), has the router's body limit, and answers `409 busy` while an export, delete or save runs or if a wipe finished during the call. `fallback: true` means
nothing matched and the hits are the newest memories (score 0). A SEMANTIC request on a phone without the embedder reports `semanticUnavailable` and the mode actually run.

## Retrieval self-test method

N memories are sampled (default 100), stratified round-robin by day and by length tercile. Memories with fewer than 3 distinct content words are skipped.
Two queries are derived for each memory without an LLM:

- **tfidf**: the 3 to 5 content words with the highest tf x idf over the corpus.
- **paraphrase**: stopwords and verb-like words are dropped, nouns and numbers are kept, and the words are reordered longest first.

The harness reports recall@1/3/5/10 and MRR@10 per query kind and per mode, the failing memory ids (missed, or ranked below 5), and the failure rate by
length band, day, memory type and frame state. The negative control is 12 queries about things never captured. Any query whose words occur in the corpus
vocabulary is dropped. The false-positive rate is the share of negative-control queries whose top score is at or above the median score of true top-1 hits.
Each phone mode's row also reports the mode the phone actually ran, how often semantic was unavailable and how often it fell back. A per-mode table compares R@5 and MRR@10
with `LOCAL-BM25`, the harness's own reference retriever (a baseline column: it shows whether the summaries can be retrieved at all; it is not the phone's retriever).

## Scoring

Each area starts at 100. The penalties are applied to shares between 0 and 1, and the result is clamped to the range 0..100. An area with no data is not scored. The formulas appear in the report:

- Capture = 100 - 40 failed - 20 no-memory - 20 duplicate - 10 refusal - 5 empty/short - 5 bad-timestamp
- Retrieval = 80 R@5 + 20 MRR - 20 negative-false-positive (mean over phone modes; the reference baseline is used only when the route is unavailable, and is labelled as such)
- Canvas = 100 - 25 runs-failed - 20 node-errors - 15 pipelines-with-problems - 15 needs-review - 10 rolled-back - 10 live-batch-errors - 5 trace/replay-bad
- Output = 100 - 30 empty-answer - 25 unresolved-citation - 25 citation-outside-hits - 20 idk

Issues are ranked by severity weight (1 to 3) x share. Each issue shows its evidence as "k of n".
