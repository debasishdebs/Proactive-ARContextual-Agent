# Review: shadow-eval route (22c2e0bb) + coord-conflict rule (0b71d96b..9d69e05a) + curated v4 (b07969ad)

Reviewer: Opus 5.5 (stand-in for Codex gpt-6.1-sol, low effort). Read-only; no Gradle, no devices.

## P1
None.

## P2
None.

## P3
- PlaceCellSource.kt:44-55 (pages loop): if two Wikipedia pages share one QID (redirect or duplicate article), a page that fails the 1 km check is skipped, but a sibling page that passes still writes `kept["wd:$q"]`, and the last one written wins. So the rule is per page, not per entity. This is fail-open only for that rare case. Optional fix: record rejected QIDs and skip any later page with the same QID.
- PlaceCellSource.kt:52-53: the stored point prefers Wikidata P625, and the pages path never checks it against `box`. A kept place can therefore sit up to 1 km outside the cell it was fetched for. The extras path does filter with `box.contains`. This existed before the change, and the new rule now bounds the drift at 1 km. Noting it only for the cell-edge question.
- PlaceCellSource.kt:52-53: if Wikidata has only lat or only lon, the check is skipped and the stored lat/lon can mix sources. P625 is parsed from one value object (PlaceSources.kt:140), so this looks unreachable. Not verified.
- CanvasExtras.kt:121-123 (shadowEvalInput): this makes two pause-DAO count queries per distinct record timestamp, so the work grows with the number of stored records. That is fine for a Developer-only GET over a bounded store, but worth knowing.
- b07969ad: Hussain Sagar (Q3275994, a reservoir) is ATTRACTION with the default radius unless the Overpass way join enlarges it (capped at MAX_RADIUS_M). That radius is small next to the lake's size. This affects quality only.

## Checked and fine
- Route gating: auth (401) runs in CanvasRouter.route before any extra. The checks run in the order exact path (null, then 404), GET-only (405), and Developer mode (403 `developer_mode_off`, same helper and text as the replay route). Developer mode is checked again after evaluate. A query string or trailing slash falls through to 404.
- Body and logs: the body is the fixed `toText()` labels plus two fixed NOTE lines. The logs hold a literal, a day count and the exception simpleName only. The 500 error echoes nothing. Pause-read failure and missing entries default to leak=true (fail closed). No schema, permission or protocol change.
- Haversine maths are correct (R = 6,371,008.8 m, a clamped to [0,1]). The test is strict `>` 1000 m. A NaN or infinite result counts as a disagreement (coerceIn passes NaN through, then isFinite is false). The rule runs after the malformed, person and kind gates, so curated QIDs are covered. Single-source candidates and VERIFIED_COORDINATES extras are not checked, as intended. The new log field is a count, with no names.
- Curated v4: 7 QIDs, the version was bumped, and none is a person. Taramati Q7685364 has no Wikipedia point, so it goes through extras with the verified coordinate, and the VERIFIED_COORDINATES version was bumped.

READY
