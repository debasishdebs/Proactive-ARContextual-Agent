# 1.4.6 pack Moments: design note (2026-11-25)

Status: built behind the Developer flag `packMoments` (default OFF), shadow only. No Room schema change (v18 stays frozen).

## What a pack Moment is
A pack place (`wd:` or `osm:` row of the place pack, offline file or online fetch) seen by the matcher in the same shape as a pin with a reference Moment. It is a derived, in-memory object built per attempt: id (the pack id), the text anchor `name. kind. summary (first 300 chars)`, and the two tie-break distances the sampler already computed. It has NO photo and NO user location.

## Where it is stored
Nowhere new. The pack row already stored in the `place` table is the only source. The derived text vector uses the existing in-memory pack text cache (max 64). A counts-only holder `PackMomentStats` (four integers) is the only new state, in memory. It is listed in the Forget inventory as `place_shadow.pack_moment_counts` and in the PLACE_CARD family holders. It is not a `MomentEntity`, so it is never in the Moments list, never in an export, never counted as a user memory.

## How it enters the same matching path
One interface `MomentCandidate` (id, origin PIN or PACK, anchor text, cacheable flag, tie-break distances, `toScorer`). `PinMoment` and `PackMoment` implement it; `MomentCandidates.of(hits, flag)` builds the list; `PlaceShadow` embeds the anchors and calls `CandidateScorer` on the result. Pins stay one-way references to their Moment (the pin holds the `momentId`; a Moment holds no pin). With the flag OFF the pack text is the older `name. kind`, so behaviour is as before. With it ON the pack text gains the summary and one counts-only line is logged: `place pack moments v=1 pack=N pin=M`. The Developer toggle shows the last counts. Nothing changes on a card, a model call, a send or a log line the user sees. The sampler's zone guard and the shadow's own zone check are untouched.

## Forget behaviour
Delete all, every Forget listener event and the flag going OFF call `ShadowCaches.clearAll()`, which now also resets `PackMomentStats`. A pack Moment has no photo, so a photo or Moment Forget has nothing to remove beyond that reset. A pin's own Forget flow is unchanged.

## Privacy rules
- No user photo and no user location is stored in or sent for a pack Moment.
- No network: the pack place is already on the phone, so Offline-only mode makes no remote call here (a test scans the source for network and database classes).
- The log line has counts only: no id, name, text, coordinate or score.
- The flag is per-device Developer state (`pc_dev`), off after a restore, never exported.

## Not built (needs a decision)
- A reference IMAGE per pack place (for example the Wikimedia lead image) would need a fetch and a stored vector. A stored vector derived from a public image is not a user photo, but it needs a Room table or a file (`pack_moment_vector`), so a schema change: STOPPED here, not done. It would be v19 material after the trip install.
- Showing a pack Moment as a card source (changing what the user sees) is the step after the shadow lines are read.

## Questions for the owner (yes / no)
1. Is the text-only pack Moment (name, kind, 300 chars of summary) enough for the shadow phase?
2. Do you want a reference image per pack place later, fetched online only and never in Offline-only mode?
3. If yes to 2, may the image vector live in a new Room table in v19 (after the trip), with its own Forget family?
4. Should the Developer toggle also be shown outside Developer mode? (Built: no.)
5. After the shadow lines look sane, may the next step let a pack Moment influence which cards are shown (still behind the same flag)?
