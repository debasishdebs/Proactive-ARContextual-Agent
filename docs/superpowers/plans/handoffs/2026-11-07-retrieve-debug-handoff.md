# Retrieve-debug route handoff (2026-11-07, branch feat/v099-watch)

Added `POST /api/retrieve/debug` (canvas server): the phone's real memory retrieval for an arbitrary query, no model call, no write.

- Body `{query (1..2000, non-blank), mode? LEXICAL|SEMANTIC|HYBRID, topK? 1..30, notAfterMs? >=0, snippets? bool}`. Reply `{mode, semanticUnavailable, fallback, hits:[{memoryId, score, rank, reviewState, createdAt, snippet?}]}`. Snippet (80 chars) only with `snippets:true`. Text retrieval only, no photo hits, no other context source.
- Code: `services/orchestration/.../canvas/RetrieveDebug.kt` (new); `AnswerService.retrieveScored` gained optional `params` and `withSources` (defaults keep every caller unchanged); `DataOpGate.busy()`; `CanvasBackend.retrieveDebug` (default throws) + `CanvasBackendImpl`; `CanvasApi` route (in READ_ONLY_POSTS: read deadline, not NonCancellable).
- Gating: router's token, Host/Origin, body limit and Developer mode (server runs only with it). No new rate limit (the router has none beyond pairing). Fails closed with 409 `busy` while an export/delete/save runs or if a wipe finished during the call. No logging in the new code; router logs only the route template.
- Tests: `RetrieveDebugTest` (orchestration: no model call, no writes, modes, topK, notAfter, snippets, busy), `CanvasApiTest` (validation, verbs, 409, no echo), `CanvasRouterTest` (401, 403 origin, log has no query).
- Tools: `tools/quality-eval.py` defaults `--retrieve-route /api/retrieve/debug` (`none` = BM25 only), sends `topK`, reports mode used / semantic unavailable / fallback and a per-mode vs BM25 table; mock server, `test-quality-eval.sh`, `QUALITY-EVAL.md` updated.
- Frozen untouched: PROTOCOL_VERSION 1, TRACE_SCHEMA 1, plan hash f5782e0d8610, Room v15, JS.
