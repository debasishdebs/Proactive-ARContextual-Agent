# Retention P2 (design note, 2026-11-26, for 1.8.0)

Builds on Retention v3 (`2026-11-24-retention-v3-keep-facts-design.md`, handoff `handoffs/retention-v3-2026-11-24.md`). Frozen for the trip install: Room context.db v18 and places.db v3, so NO schema or migration change. Pause clean-up stays default ON. System retention never deletes text. Forgetting is user-initiated only.

## Built now (Developer flag `retentionP2`, default OFF)

1. **Exempt photos from photo expiry.** With the flag ON, photo expiry (linked photos by the Photos setting, ordinary skipped photos, legacy orphan frames) leaves out a photo when:
   - a place card of the history names it (`PlaceCardHistory.photoIds()`), or a pin's reference Moment holds it (both through the existing `QuotaProtection.frames`, which fails closed);
   - a mark names it or lies within the privacy radius of its capture time (`canvas_label`);
   - a `place` memory cites it.

   Implemented as a post-filter on the unchanged selects (`Retention.dueNotExempt`), with a new read-only query `ForgetDao.explicitlyKeptAmong` (a query, not a schema change). The select is asked for `max + exempt ids known`, so exempt photos at the head of the oldest-first list never starve the rest and never loop. If the outside set cannot be read, no photo expires (fail closed). Quota eviction already had these exclusions (v3 K6) and is unchanged. With the flag OFF `dueNotExempt` is exactly the old `select(max)` call.
2. **Re-point dependents before expiry, only where no schema change is needed.** Already true from v3 and kept: a photo that does expire gets an `rx:` expiry marker in the same transaction, a Moment keeps its FRAME part and shows "Photo expired", a pin keeps its Moment (`momentsAliveOrExpired`), a place card history row keeps its frame id as lineage and gets `photoGone=1`, nudges and answers keep their text sources. Nothing more can be re-pointed without a new durable record (see parked).
3. **Retention preview (read-only, always shown, not behind the flag).** Settings, Memory, Storage card, "Coming up": what the real `Retention.plan` selects if a pass ran in 24 h and in 7 days, by kind (photos, skipped photos, image vectors, details leaving Quick recall), counts only. It mentions Pause when on and, with the flag ON, that card/mark/pin photos stay. It reads only, logs nothing, and an unreadable read shows nothing.

## Parked (needs a schema change, or a ruling)

- **A durable thumbnail or record per expired photo** (so a place card, mark or Moment could show a small image after the JPEG is gone). Needs a new table or column, so it waits for v19.
- **Exempting by "was ever cited by a nudge or answer"** (not only by an accepted proposal): there is no cheap exact reference index today; needs an index or table.
- **A per-photo "keep" star** chosen by the user: needs a new column.
- Mark `canvas_label.frameId` is exempted when set; marks without a frame are exempted by time radius only.

## Owner questions (yes / no)

1. Should the flag go to default ON in 1.8.0 once device-tested? (Recommended: yes, after one night of the counts-only log with Pause OFF.)
2. Should exempt photos count against the Storage cap? Today they do, but quota eviction never takes them, so the cap can be exceeded by them. (Recommended: yes, keep as is, show the banner.)
3. Is a place `memory` of type `place` enough to count as a "place-card photo" besides the card history? (Recommended: yes.)
4. Show the preview only in Developer mode instead of Settings, Memory? (Recommended: no, counts only is safe.)

## Tests

`RetentionP2Test` (both Forget modes): flag-off parity (plan and expiry equal the default Retention; the worker too), exemption of each kind with text byte-equal, a user Forget still removes an exempt photo, 450 exempt photos do not starve or loop, unreadable set expires nothing, preview counts by kind, read-only (frame, canvas_meta and text tables byte-equal), and wording. `RetentionNeverLosesTest`, `RetentionWorkerTest` and the Forget tests are unchanged and green. Phone: `MemorySettingsTest` (preview rows present, absent on a failed read).
