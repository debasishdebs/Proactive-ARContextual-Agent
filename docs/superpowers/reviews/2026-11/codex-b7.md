- **P2 — Ritigala’s mountain coordinate is used for the monastery.** [PlaceCellSource.kt:150](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/connectors/src/main/kotlin/com/rokid/context/connectors/place/PlaceCellSource.kt:150) stores the mountain’s point while assigning `archaeological_site`. A [field study of the monastic complex, p. 4](https://dl.lib.uom.lk/server/api/core/bitstreams/62560cd1-ef41-44bc-b366-faf7d12f98e5/content) locates the heritage site at `(8.11858, 80.65929)`, approximately **1,162 m away**. With the default 250 m radius plus 300 m candidate gap, that location cannot match this entry without additional Overpass enrichment. Verify and pin the monastery’s coordinate and identity.

- **P3 — Documentation overstates HTTP 429 handling.** [trip-seed-coverage-2026-11-18.md:100](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/reviews/2026-11/trip-seed-coverage-2026-11-18.md:100) says the existing HTTP layer handles 429. It throws `PlaceHttpException`, discards `Retry-After`, and fails the cell; seeding later retries failed cells with a fixed backoff. Describe that behavior accurately. This is an existing runtime limitation, not a new regression.

Other requested checks pass static review: person and malformed-P31 gates precede admission; extras use only table coordinates; QIDs are deduplicated against pages and batched sequentially; no endpoint, schema, protocol, or logging change. Inclusive cell edges match `GeoBox` semantics, and cells do not cross the antimeridian. The three coordinates are geographically plausible for their stated regions.

`git diff --check` passed. Unit tests could not start because the read-only sandbox prevents creating Gradle’s lock file.

No P1 findings.

READY