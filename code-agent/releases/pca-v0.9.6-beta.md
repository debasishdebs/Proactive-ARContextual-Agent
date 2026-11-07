## v0.9.6-beta

Debug-signed; no APKs are attached. Phone database is Room v15 (additive migrations, one-way: back up before every `adb install -r`, never uninstall).

**Moments (slice A)**
- A Moment carries text (timestamp plus the visible memory text of its photos), a transcript scaffold and an error reason that survives a rebuild; the text is in `GET /api/moments` and the `input.moments` item; a one-time purge removes invalid Moments and orphan memories at first start; memories of retention-expired photos are no longer treated as orphans.

**Run & Save (slice B)**
- The canvas run page separates a playground run from Run & Save, which writes through the real memory write path (idempotent, gated, forgettable); a save blocks deletes and exports with typed outcomes.

**A mark takes a photo (slice C)**
- A mark touches capture: the glasses open the camera explicitly, hold it for a setting, and take the mark's photo within a bounded pause and battery exception; additive wire fields (`MarkMessage.photo`, `FrameMeta.markRef`, `pc_mark_photo`, `markPhotoHoldMs`) and a Settings row.
- The photo is linked on the mark's label (Room v15), built into its Moment, or the Moment falls back to mark-only with the error reason; undo forgets the photo; shown in Activity and the Moments API. The pause allowance is claimed durably, one frame per mark.

**Reviews**
- Codex review of slices B and C found Important defects in claim validation, undo ordering, gate release and wipe generation (all fixed); slice A had no extra review.

**Known limitations**
- Not yet verified on a worn day; a mark-only Moment still happens when the camera is busy or the photo fails.
