# Canvas frame-id fix handoff (2026-11-08)

Bug: CanvasApi `ID` (`^[A-Za-z0-9:_-]{1,64}$`) rejected every real frame id (`...v2`), so GET /api/frames/:id, /thumb, /image answered 400.

Audit: ids only key DB lookups (`framesByIds`, dao); the photo file path comes from the DB row, never from the id. No filesystem resolution from ids, so no canonical-path check is needed.

Fix (CanvasApi.kt): new `FRAME_ID` = `^(?!\.)(?!.*\.\.)(?!.*\.$)[A-Za-z0-9:_.-]{1,64}$`, used by the three frame routes and `frameIds` in run bodies. Pipelines, runs, asks, trace, replay and sweep ids keep the strict pattern. `dispatch` also answers 400 for any `.`/`..` path segment and validates the frame id before routing, so `a/../b` is 400, not 404. Real ids measured at 58 chars; limit stays 64.
Origin gating on thumb/image is unchanged (valid ids with a foreign Origin still 403; a bad id now 400s first).

Tests: CanvasApiTest (real-shaped ids on all routes, unsafe ids, origin). Harness: quality-eval.py, mock_server.py, test-quality-eval.sh, QUALITY-EVAL.md updated (memory text via GET /api/frames/:id; tolerant fallback kept).
Frozen untouched: PROTOCOL_VERSION 1, TRACE_SCHEMA 1, plan hash f5782e0d8610, Room v15, no JS change.
