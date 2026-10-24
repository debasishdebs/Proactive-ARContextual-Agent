# Moments, Scenes and the common object model: design decisions (2026-10-24)

Status: decisions agreed with the user in the 2026-10-24 brainstorm. Builds on `2026-10-23-moment-model-design.md` (kept in the main checkout) and does not repeat it. Where the two differ, THIS file wins. Everything below marked **built** is on branch `v0.9.2`; everything marked **later** is designed only.

## 1. Intent
A **Scene** is a stretch of the user's life: the collection of Moments (and later audio and sensor objects) from one activity. The first place the user wants to use Scenes is **as an input node in the canvas** next to `input.moments`. Scene tables are optional on the first day; the building blocks (Moments, audit, derived fields, clock fix) come first.

Anti-goal: any design in which forgetting a photo leaves a Scene summary, caption or embedding that still reflects it.

## 2. One family of objects (built for Moment; Audio and Scene later)
- All objects inherit a common base `MomentObject` (kind, id, buildRev). Moment, Audio, Sensor and Scene share the same interfaces and the same derived fields.
- **References, not copies.** A Scene stores references to its parts (Moments, audio, sensor objects). Deleting a part shrinks the Scene.
- **Part level keeps its own details.** A Moment or an audio clip keeps its own summary, caption and embedding. The **collective** summary, caption, info and embedding live on the Scene row.
- **Derived fields** (summary, caption, embedding) live in one table `object_derived(objectKind, objectId, field, value|ref, builtRev, staleSince, atMs)`. A **stale** field is hidden from readers immediately and rebuilt later. The user chose: hide at once; rebuild on a user-configurable cadence ("X times a day") with a warning about frequency and compute. While a Scene summary is hidden, QnA answers from the other, non-hidden memories; revisit later if the hidden combined text hurts.
- **Audit.** One content-free table `object_audit(objectKind, objectId, seq, op, atMs, step, refKind, refId)`: what was done, when, by which step, plus whatever is needed to redo dependent operations. Never text, labels or photo data. Append-only, deleted with its object, emptied by Delete all. Ops in use: BUILT, REBUILT, PART_ADDED, PART_REMOVED, FORGOTTEN, INVALIDATED, OUTBOX_QUEUED, DERIVED_BUILT, DERIVED_STALE, DERIVED_HIDDEN.
- **Generic processing.** `SummarizeInput`, `CaptionInput`, `EmbedInput` are typed requests that accept any family object; a registry keyed by object kind supplies the implementation (built: ports plus a test-only no-op; no model calls yet).
- **Derived writes are guarded** (built): a write must carry the deletion generation and input revision it was computed from; both and the parent's existence are checked in the write transaction. An obsolete result is rejected with nothing written (the fence is mandatory, no null default).
- **Source changes hide derived fields in the same transaction** (built): mark delete/undo/correct/retag, memory edit/trust/review-state, frame/memory forget.

## 3. Part kinds
FRAME, MEMORY, CITE, MARK, SUMMARY, OTHER (built for Moments). **Sensor** and **Audio** are part kinds of the same shape (later). Audio is a reserved kind with no schema today; adding it needs its own deletion protection.
- **Activity and sensor are separate fields** on a Scene. Activity comes from the watch, ring or phone when available, otherwise from the sensor data; both are stored.

## 4. Scene boundaries (version 1: simple, tunable)
1. Work in **one-hour clock windows** (a Scene does not cross an hour boundary: my guess, to confirm).
2. **When an activity is logged:** a **change of that activity** breaks the Scene (frame similarity is not used then).
3. **When no activity is logged:** consecutive frames that do not change much stay in one Scene; a frame whose change exceeds the threshold breaks it. Measure: perceptual-hash distance as a fraction of bits (start at **25%**, i.e. 16 of 64 bits; a random pair is about 50% so the user's 50/75% would never fire) or, with the image embedding model, cosine similarity below **0.75**. Starting values only; tune on real traces and photos through the replay and tuner machinery.
4. **Time fallback:** a Scene never exceeds **5 minutes**: longer ones are cut again, in activity and non-activity cases alike.
5. The Scene **records which rule cut it** (activity change, frame change, 5-minute cap) as a Scene property, with its start and end triggers (for example head flip, head dwell).
6. Later, an activity can be a user-defined expression box (aggregate plus comparison over sensor signals, see the 3c design); version 1 uses the built-in motion classifier states.
7. Scenes are built on a **user-configurable cadence**; the settings screen warns by frequency and compute cost.

## 5. Clocks and deletion (built)
- Imported traces carry phone-time columns plus quality and uncertainty (Room v10); Forget selects by interval overlap with uncapped uncertainty over ALL traces; legacy rows are conservative until backfilled.
- An UNMAPPED trace is admitted when its coarse interval (glasses wall time plus or minus 24 h, plus receipt time) is clear of every Forget range; otherwise it is stored QUARANTINED (hidden from every reader, replay and plan evidence; still selected by every Forget; wiped by Delete all).
- Frames with unknown capture time never become Moments and are refused for 24 h after any Moment Forget (the tombstone refresh keeps that marker current).
- Stored Moments are ON by default (a kill switch only). A builder trigger (coalesced, off the main thread) plus a 15-minute cadence worker rebuild the previous and current hour.

## 6. Still to build (in order)
1. Outbox drainer (phone JPEG files, glasses frames and ranges, laptop helper) so cleanup rows become real deletions.
2. Sensor features per Moment second (`moment_sensor`, canonical phone-second windows) now that the clock fix exists.
3. Scene tables (membership revision, boundary rule, trigger fields), `SceneBuilder`, the `input.scenes` canvas node, scene-level derived fields.
4. Audio part kind with its own deletion protection.
5. ItemRef/CanvasItem refactor and unified Forget entry points (spec phases 2 and 3).

## 7. Open, with defaults
- 8 or 10 hex digits for MomentId: **8**.
- A lost Scene member invalidates the Scene artifacts (**yes**); a warning that exports already handed out cannot be recalled (**yes**).
- Hour windows as a hard Scene boundary (**yes**, to confirm).
- Quarantined trace files stay on disk until a Forget or Delete all (**accepted**).

## 8. Update 2026-10-25 (user): a Moment can be born from a mark alone
Found on the first real wear window after v0.9.3: four marks at 01:50-01:51 (tap + voice, all recorded and acknowledged on the phone) produced no Moment and appear nowhere in the app, because capture was paused at 20% battery (no frames) and a Moment so far needed a photo second; marks lived only as `canvas_label` rows that no screen reads.
New definition (supersedes section 2/3 wording that makes a photo the anchor):
- A **Moment is a timestamp** (phone-clock second, same `MomentId`) that may carry **any of**: photos, sensor seconds, marks, memories. **Photos and sensors are optional.** A mark creates a Moment by itself.
- A mark carries a **code** (its source: tap, voice, retro, ask) so later queries can say "my marks" or "ask marks". No text is stored in the label (privacy rule unchanged); `Text` style marks, if added later, follow the same Moment rule.
- A mark-only Moment records WHY there is no photo/sensor part when known (capture paused: battery / user / persisted pause; capture off; no trace), as a content-free reason code on the Moment, not as free text.
- Forget, Delete all, tombstones, the outbox and the audit table treat a mark-only Moment exactly like any other (the mark label is the source; deleting it deletes the Moment; Scene membership shrinks as before).
- Readers: `input.moments`, `GET /api/moments`, the Activity tab ("Marks" section: time, code, joined Moment, reason for no photo) and later Scene building all see mark-only Moments. Scene rules: a mark-only Moment has no frame hash, so it joins the neighbouring Scene by time/activity and never cuts one by frame change.
- Capture is NOT triggered by a mark (anti-goal kept: a mark never touches capture). Whether a mark should also take one photo while capture is paused is a separate, undecided product question.
