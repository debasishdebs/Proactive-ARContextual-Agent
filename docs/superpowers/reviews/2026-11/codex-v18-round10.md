# Codex v18 round 10 (8615f63fa..8be7d90ae; gpt-6.1-sol, medium, read-only)

VERDICT: NOT READY (6 x P2, no P1)

1. [P2] PlaceSamplers.kt:163 - L1 read frame_vector by the space fingerprint; producers key it by the space row id, so stored dwell vectors, multi-frame glance and REF vectors never loaded.
2. [P2] PlaceCardHistory.kt:167 - promoting an existing CARD link kept the card's unchecked anchor; a RIGHT row could keep an anchor whose label became WRONG.
3. [P2] PlaceCardHistory.kt:171 - promoting the oldest CARD at the cap excluded it after the oldest slice was taken, leaving six references.
4. [P2] PlacePack.kt:501 - removing places left their REF links in context.db.
5. [P2] PlacePack.kt:551 - the photo cap could be exceeded by embedding (vector bytes added, no trim) and the fetch phase then stopped at the cap for good.
6. [P2] PlacePhotoWorker.kt:174 - the embed worker finished with success on NOT_CHARGING / NO_SPACE with thumbnails still queued.

Confirmed by Codex: migration SQL matches the exported columns (in-memory SQLite); installed encrypted upgrades and Android execution were not run (the device test exists, not run: no device).

## Disposition
1. FIXED (frameSpaceId maps fingerprint to the space row id for storedVector, glance, glanceRefs; test FrameSpaceIdTest).
2. FIXED (setAnchor on promotion, on an existing REF, and when the approving frame loses its label; tests refAnchorFollowsTheApprovedFrame).
3. FIXED (protected Moment excluded before the oldest slice; test promotingTheOldestCardLinkAtTheCapStillEvictsDownToFive). 2 and 3 share one commit (same function).
4. FIXED (PlaceRefSweep.links at start-up and the pack's onPlacesRemoved hook after area removal, Clear fetched, Delete all; test PlaceRefLinkSweepTest).
5. FIXED (trim inside markEmbedded; the fetch phase trims before returning CAP_REACHED; test embeddingAddsBytesAndTheCapHoldsAfterTheVectorWrite).
6. FIXED (retry up to 6 times, re-queue the fetch after DONE; source-rule test).
No P1: round 10 verdict after fixes is READY, with the caveat that finding 1 (a wiring bug) was only unit-tested at the mapping helper, and the real Android path is still to be seen on a device.
