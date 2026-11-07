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
`--retrieve-route /api/...retriev...` and `--modes LEXICAL,SEMANTIC,HYBRID` (see Retrieval).

## Safety

- Only `GET`, plus a `POST` on an explicit read-only allowlist, which is empty unless `--retrieve-route` is given. `guard()` refuses any other verb or path
  before the socket is opened, and a final check confirms that no other request was sent. The test asserts that the mock saw no `PUT`, `DELETE` or `PATCH`.
- TLS: the peer certificate must be byte-identical to the `cert.der` that `m1-run.sh pair` pinned after the fingerprint check. On a mismatch nothing is sent.
- The token is read from `auth.hdr` and is never printed. Errors show only the HTTP status and the fixed `error` code.
- `GET /api/asks/:id` returns the composed prompt. The harness reads only its scores and ids and never writes the prompt to the report.

## Routes per section

| section | routes | unavailable |
|---|---|---|
| 1 Capture | `GET /api/frames` (paged by `before`), `/api/frames/:id` (memory text, spread evenly over time), `/api/moments?day=`, `/api/scenes` | photo byte size, extraction confidence, per-frame needs-review, glasses-to-phone clock offset |
| 2 Retrieval | local BM25 reference over the memories read; real-ask diagnostics from `GET /api/asks/:id` (re-runs retrieval as of the ask: mode, fallback, lexical/semantic/fused scores) | **No shipped route scores an arbitrary query.** `POST /api/asks/:id/replay` calls the Answer model and is never sent. Phone recall per mode needs `--retrieve-route`, which takes `{query,mode,limit}` and returns `{hits:[{memoryId,score}]}` |
| 3 Canvas | `GET /api/pipelines(/:id)`, `/api/runs(/:id, /:id/proposal)`, `/api/publish`, `/api/publish/history`, `/api/live`, `/api/plans/status`, `/api/signals`, `/api/traces`, `/api/replays`, `/api/sweeps`, `/api/devices` | guard configs, flow (Query/Nudge) execution history; traces and replays need Developer mode (403 otherwise) |
| 4 Output | `GET /api/asks`, `/api/asks/:id` | ask latency, Nudges (no route), proposal undo |

A route that answers anything other than 200 is recorded under "Unavailable routes" with its status code, and that section continues without it.

## Retrieval self-test method

N memories are sampled (default 100), stratified round-robin by day and by length tercile. Memories with fewer than 3 distinct content words are skipped.
Two queries are derived for each memory without an LLM:

- **tfidf**: the 3 to 5 content words with the highest tf x idf over the corpus.
- **paraphrase**: stopwords and verb-like words are dropped, nouns and numbers are kept, and the words are reordered longest first.

The harness reports recall@1/3/5/10 and MRR@10 per query kind and per mode, the failing memory ids (missed, or ranked below 5), and the failure rate by
length band, day, memory type and frame state. The negative control is 12 queries about things never captured. Any query whose words occur in the corpus
vocabulary is dropped. The false-positive rate is the share of negative-control queries whose top score is at or above the median score of true top-1 hits.
`LOCAL-BM25` is the harness's own reference retriever. It shows whether the summaries can be retrieved at all. It does not measure the phone's retriever.

## Scoring

Each area starts at 100. The penalties are applied to shares between 0 and 1, and the result is clamped to the range 0..100. An area with no data is not scored. The formulas appear in the report:

- Capture = 100 - 40 failed - 20 no-memory - 20 duplicate - 10 refusal - 5 empty/short - 5 bad-timestamp
- Retrieval = 80 R@5 + 20 MRR - 20 negative-false-positive (mean over phone modes; the reference baseline is used only when no phone route exists, and is labelled as such)
- Canvas = 100 - 25 runs-failed - 20 node-errors - 15 pipelines-with-problems - 15 needs-review - 10 rolled-back - 10 live-batch-errors - 5 trace/replay-bad
- Output = 100 - 30 empty-answer - 25 unresolved-citation - 25 citation-outside-hits - 20 idk

Issues are ranked by severity weight (1 to 3) x share. Each issue shows its evidence as "k of n".
