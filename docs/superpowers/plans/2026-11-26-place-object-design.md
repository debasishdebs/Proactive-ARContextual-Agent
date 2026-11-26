# Place as its own object (design, 26 Nov 2026)

Status: DESIGN only (nothing built). Owner ruling round 6b (`handoffs/owner-decisions-2026-11-26.md` line 72): do it now, fold it into the unshipped context.db v18, independent of Nexus. Deleting a Place offers (a) "only the place and its caption" or (b) "full cascade to its Moments, Scenes and children".
Goal of this doc: the SMALLEST version that gives the owner both delete modes, can land in about a day, and cannot lose data or break Friday's install.

Verdict up front: safe to do before Friday, if it is cut as in section 6. The schema part is one additive table (the same pattern as `moment_location`, WP2). Mode (b) needs no new Forget engine: it reuses the multi-select Forget that is already built (it is behind the switch `forgetV2`, which turns on the new Forget engine; the owner wants it ON for the trip). If the table is not green and reviewed by Fri 27 Nov 12:00 IST, drop it and ship both modes on the pointers that already exist (section 6, "safe subset").

## 1. What a Place is (one identity rule)

RULE: a Place is exactly one user pin. Its id is the pin id `user:<uuid>` (`memory/.../place/UserPlace.kt:52`). Nothing else is a Place, and nothing new is created on first launch.

| Thing | Is it a Place? | Relation |
|---|---|---|
| User pin (`UserPlace`, prefs `place_pins`, `UserPinStore`, UserPlace.kt:69) | YES | The Place itself. Still the source of truth; places.db keeps its mirror row (`PlacePack.replaceUserPlaces`). |
| Pack PoI (`wd:`/`osm:` rows in places.db `place`) | No | Public reference text. It can be a Place's `parentId` (UserPlace.kt:20). A Place delete never touches it. |
| Seeded area (places.db `area`, new `name` column, WP11b) | No | A download region. Unrelated. |
| Mark | No | A Moment event. Never linked to a Place automatically. |
| Place card / card history row (`place_card_log`, context.db v18) | No | An event that CITES a placeId. A single-place card for a pin creates a Moment pointer (section 2). Cluster cards ("pins nearby") create none. |
| "Looked at X" place memory (`PlaceMemory`, `orchestration/.../place/PlaceSamplers.kt:284`) | No | A memory on a photo, so it is Moment content. Its tags carry only `wd:` ids, never a pin id (PlaceSamplers.kt:292). |

Pointer direction: Place -> Moment only. Moments never point at Places, which keeps the standing rule "pins are NOT children of Moments (one-way reference)".
The Place's Moments = its reference Moment (`UserPlace.momentId`, 1.4.2, read live from the pin and NOT copied) UNION the Moments in the new `place_link` table. The function `PlaceMoments.of(pinId)` is the only code that computes this set.
Proximity (Moments saved near the pin, `moment_location`) is NOT a pointer. Reason: a Home pin would claim thousands of Moments, and mode (b) would erase them. This is open question Q3.
Name and caption: they stay where they are. The "caption" in the owner's wording = the pin's `name` + `note` (UserPlace.kt:15,23), stored in `place_pins` and mirrored to places.db `place.name`/`summary` (UserPlace.kt:40-42). No new text column and no coordinates anywhere new: the coordinates stay only in the pin.
Not done now: moving pins from SharedPreferences into a database table. It would migrate user-authored data, settings export/import, backup rules, corrupt-list handling and the 1.4.2 arming (UserPlace.kt:124-162). That is too much risk for one day, and the identity rule does not need it.

## 2. Schema deltas (v18 edited in place, no version bump)

context.db v18: ONE new table, added at the end of `MIGRATION_17_18` (`memory/.../db/ContextDatabase.kt:370-380`) and in the exported `schemas/com.rokid.context.memory.db.ContextDatabase/18.json`:
```sql
CREATE TABLE IF NOT EXISTS `place_link` (`placeId` TEXT NOT NULL, `momentId` TEXT NOT NULL, `atMs` INTEGER NOT NULL,
  `anchorId` TEXT NOT NULL, `role` TEXT NOT NULL, `linkedAt` INTEGER NOT NULL, PRIMARY KEY(`placeId`, `momentId`));
CREATE INDEX IF NOT EXISTS `index_place_link_momentId` ON `place_link` (`momentId`);
CREATE INDEX IF NOT EXISTS `index_place_link_atMs` ON `place_link` (`atMs`);
CREATE INDEX IF NOT EXISTS `index_place_link_anchorId` ON `place_link` (`anchorId`);
```
- `placeId` = the pin id; `momentId` = the 8-hex Moment id; `atMs` = the Moment's second times 1000 (so a range Forget can delete by time); `anchorId` = the frame id of the photo whose card made the link (so a photo Forget can delete by anchor, like `moment_location`); `role` = `CARD` in v18 (`USER` is reserved for a later "add this Moment to a place"). The table holds no coordinates, no name and no text.
- No foreign key and no trigger (the same as `moment_location`, MomentLocation.kt:13). Rows are deleted only at the Forget choke points (section 3).
- One link per (Place, Moment). A second photo of the same Moment does nothing (INSERT OR IGNORE). If the anchor photo is later forgotten, the link goes, even when another photo of that Moment stays (this errs toward deleting).

places.db v3: NO change. It is public text plus the pin mirror, rebuilt from prefs at every start, and by its own contract it is "never a position or a visit" (PlacesDb.kt:114-115). It is also never a Forget target (PLACE_PACK row, ForgetPolicy.kt). Moment links are visits, so they belong in context.db.

Mapping on first launch: there is nothing to migrate. Every existing pin already is a Place (same id). Its 1.4.2 link stays in prefs and is read live. The v18 card history starts EMPTY on the phone: places.db v2 history is dropped by `MIGRATION_PLACES_2_3` (PlacesDb.kt:139) and nothing imports it. So `place_link` starts empty, and the migration is the CREATE statements above.
Idempotency, no data loss and failure safety follow from that: no prefs write, no row moved, no read-then-write. A failed migration fails exactly like any v18 migration (recovery: `handoffs/v17-upgrade-recovery.md` section 4).
The writer `PlaceCardHistory.add` (`orchestration/.../place/PlaceCardHistory.kt:76-100`) adds the link INSIDE its existing transaction, after its existing guards (pin being removed or inactive, frame forgotten, authority), and only for a single-place card whose placeId starts with `user:`. It therefore inherits every fence the card row has.
Note: with the switch `placeCardHistory` off (it records the card history; default ON), no links are written.

## 3. Forget semantics (in the Forget framework)

New family (`Family.PLACE_LINK("placelink")`, Ref.kt; `PlaceLinkFamily` in Families.kt with `tables = listOf("place_link")`, `selfDeletes = false`) and a new `ForgetChild.PLACE_LINK` with one policy row (ForgetPolicy.kt). Its sources are the ones that remove a MOMENT:

| Source | PLACE_LINK action | Where |
|---|---|---|
| User Forget (single, scope, multi-select, range) | FORGET: rows deleted in the same transaction, by anchor, second and time span | next to `momentLocationDao()` at MomentForget.kt:249 (switch OFF) and ForgetExecutor.kt:283,309 (switch ON) |
| Delete all (and its crash resume) | FORGET: `DELETE FROM place_link` in `WipeDao` | WipeDao.kt (inside `wipeMemory`) |
| Retention expiry, sweep, invalid-Moment purge | KEEP: an expired Moment stays a Moment (`momentsAliveOrExpired`); readers filter out tombstoned Moments the way DumpReader.kt:224 does for pins | read side |

- DeletionGraph.kt: add `w("place_link", "Moment pointers of the user's places (no text, no position); deleted with the Moment by every user Forget and by Delete all, and with the place; never exported or backed up")`.
- DesignDivergence.kt: entry `PLACE_LINK_NOT_PLANNED` ("the planner does not enumerate it; deleted by step code"), golden `placeLinkRows`, the same shape as MOMENT_LOCATION_NOT_PLANNED.
- SideDelete `PLACE_LINK_PLACE_REMOVAL` (explicit = true, entry point `.deleteLinksForPlaces(`, caller `PlaceCardHistory.kt`): it runs inside the same transaction as `deleteCardLogForPins` (PlaceCardHistory.kt:119-124), called from `PlaceGraph.savePins` step 3 (PlaceGraph.kt:110-127).
  EVERY pin removal goes through `savePins`: the UI delete (PlacePinning.kt:170), the 1.4.2 cascade, its settle after a crash, and the wipe (PinMomentLinks.kt:81,265,278). So one hook covers all of them.
- Ratchet: the new DAO method names are leaf deletes and stay out of the token list of `ForgetExecutorInventoryTest` (the moment_location precedent). The ratchet numbers (ForgetService.kt 30, MomentForget.kt 8, ...) must NOT change: the slice proves this with the test green and unedited.

Mode (a) "only the place and its caption". It is today's pin delete, now behind a confirmation. `PlaceGraph.savePins` removes the pin (name, note, kind, radius, parent, reference link), its card-history rows and its `place_link` rows, then the places.db mirror. The card is withdrawn (fence trip, PlaceGraph.kt:120-121).
Mode (a) KEEPS: every Moment, its photos, its memories (also the "Looked at" ones, Q1), its marks, its Scenes and its locations.
Dialog: "Delete place "Antera"? Its name, note and card history go, and its links to 7 Moments. The Moments, photos and notes stay; they may now be cleaned up automatically like any other photo. This cannot be undone." [Cancel] [Delete place].

Mode (b) "the place and its Moments". It runs ONLY when the switch `forgetV2` is ON (otherwise the choice is hidden, Q4). Steps:
1. roots = a PHOTO Ref for every live photo of each Moment in `PlaceMoments.of(pin)` (`forgetDao().frameIdsBetween(sec*1000, sec*1000+999)`, as QuotaProtection.kt:22-25 does), plus a MARK Ref for every mark of those seconds. The planner accepts PHOTO, MEMORY and MARK roots (ForgetPlanner.kt:33-38). If the Activity multi-select builds a Moment's roots differently, reuse its builder.
2. `ForgetActions.previewSelection(roots)` (ForgetActions.kt:142) gives the counts, the plan token and the pins it would delete. This includes this pin when its reference Moment loses its last photo, AND any OTHER pin whose reference Moment is in the set (1.4.2): the dialog must name them. Limits: more than 200 needs the acknowledgement, more than 5,000 is refused (SelectionLimits.kt).
3. `forgetSelectionOutcome(roots, token, pins = preview.pinIds)` (ForgetActions.kt:161) runs inside the DataOpGate (the lock that allows one data operation at a time) with the 1.4.2 arming and the usual post-commit cleanup (files, glasses, listeners). Inside the same transaction it takes the photos, their memories (with "Looked at"), card-history rows, Moment locations, `place_link` rows and marks. Each Moment's projection goes when its last photo goes. Scenes follow SCENE_REBUILD_VS_DROP: they shrink or rebuild, and an empty Scene is dropped (Q2).
4. Only on COMMITTED, call mode (a) for the pin if it still exists. A crash between steps 3 and 4 leaves the pin with no live Moments ("Needs a Moment"), and the user deletes it again. Nothing beyond the confirmed set is ever deleted. CONTENT_CHANGED or TOO_LARGE: nothing is deleted; preview again.
Dialog (counts from the preview): "Delete "Antera" and its 7 Moments? Forgets 23 photos, 31 notes and 2 marks from those Moments, then the place. Also deleted: pinned place "Gate" (its Moment is one of these). Scenes keep their other Moments. This cannot be undone." [Cancel] [Delete all of it] (plus the acknowledgement checkbox above 200).
"Children" = the Forget children of those Moments. A Place has no child Places: a pin's parent is never another pin (UserPlace.kt:36).

Interplay:
- 1.4.2 (explicit Forget of the reference Moment): UNCHANGED. The pin is deleted through `savePins`, so its links go too, while its other Moments stay. Forgetting a non-reference linked Moment deletes only that link row. Undoing a mark still only de-links (ruling O6).
- Retention P2 (exempts card, mark, pin and place-memory photos; behind the switch `retentionP2`): UNCHANGED. Protection stays on the reference Moment and card-history photos (QuotaProtection.kt:16-25; RetentionWorker.kt:100). `place_link` adds NO protection. Mode (a) therefore un-protects those photos, which is why the dialog says so. An explicit mode (b) overrides protection, as every explicit Forget does.
- Matching (1.4.4 centroid and shadow): UNCHANGED. The pin is still the match target, and no matcher or shadow reads `place_link`. Using linked card Moments as extra reference photos is a follow-up. It needs a guard against self-confirmation: a card makes a link, the link makes a reference, the reference makes more cards.
- Dump (Developer only): pins gain `momentIds` (live linked ids) and the manifest gains `placeLinks` (a count), in slice 4.
- Exclusions: not in the settings export (prefs only; `withoutLinks` already strips the 1.4.2 link, UserPlace.kt:185), not in the memory export (no reader; a test asserts it), not in backup or transfer (context.db excluded, `backup_rules.xml:17`, `data_extraction_rules.xml:16`), never in logs (counts only), and never in AI prompts, Ask or the canvas API.
  Guard test: `PlaceLinkSourceRulesTest`, an allow-list of the files that may name `place_link`, like `SceneCentroidSourceRulesTest`.

## 4. Minimal UI (no new navigation)

Settings > Place Guide > "Your pinned places" (`phone/.../ui/settings/place/PlaceGuideSettings.kt:597-612`). Each pin card gains one line, "7 Moments" (the live size of `PlaceMoments.of`), only in slice 4.
"Delete" (`pin_delete_<id>`, which deletes at once today at line 340) opens one dialog with two choices: "Only the place" (default) and "The place and its 7 Moments" (hidden when `forgetV2` is off or the place has 0 Moments). The second choice shows the step-2 preview text before its confirm button is enabled.
No Activity change now (a Place filter in Activity is a follow-up). The labels keep the word "place" so they match the existing screen.

## 5. Build slices (one Sonnet agent each, in order; agent-time estimates)

S1 schema + Forget wiring (about 3 h). Entity and DAO (`PlaceLink.kt`, the shape of MomentLocation.kt), MIGRATION_17_18 lines, 18.json, Family/ForgetChild/policy row, DeletionGraph, DesignDivergence, WipeDao, and the deletes at MomentForget.kt:249 and ForgetExecutor.kt:283,309 (and the range path).
Tests: MigrationV17to18Test (the table and indexes exist, other rows untouched), FamilyInventoryTest, ForgetPolicyTest, ForgetPolicyConformanceTest, DesignDivergenceTest, golden `placeLinkRows` with the switch OFF and ON, `PlaceLinkForgetTest` (photo, Moment, Scene, range, multi-select, Delete all; expiry/sweep/purge keep), ForgetExecutorInventoryTest green with UNCHANGED ratchet numbers, PlaceLinkSourceRulesTest, BackupRulesTest unchanged.
S2 writer + pin removal + mode (a) dialog (about 2 h). Link written inside `PlaceCardHistory.add`; `deleteLinksForPlaces` inside the `deletePins` transaction; SideDelete row; the confirmation dialog for today's delete.
Tests: PlaceCardHistoryTest (link written; not written for a cluster card, a `wd:` card, a removed pin, a forgotten frame, or history off), PinMomentLinksTest (the 1.4.2 cascade and the wipe remove links), PlacePinningTest, ForgetPolicyTest side-delete caller scan, a dialog UI test.
S3 mode (b) (about 3 h). `PlaceMoments.of` (the union; it works with or without S1), `PlaceDelete.preview/run` in orchestration, the second dialog choice.
Tests: `PlaceDeleteTest` (mode b forgets photos, memories, marks, links and the pin; another pin whose reference Moment is in the set is named and deleted; a crash after commit keeps the pin; CONTENT_CHANGED and TOO_LARGE delete nothing; with `forgetV2` OFF the choice is hidden; mode a keeps every Moment), RetentionNeverLosesTest unchanged.
S4 (CAN SLIP) count line + dump (about 1.5 h). "N Moments" on the pin card; dump `momentIds` + manifest count; `tools/post-install-check.sh` counts `place_link`.
Tests: DumpReader tests (tombstoned Moments filtered out), the real-Forget dump test.
Then: one Codex review (sol, medium; Opus low if the quota is out) over S1-S3, one fix round (about 1 h), and `tools/dev/verify_final.sh` 15/15 on the tip.
Total about 9.5 agent hours plus review. S4 can slip without touching the install. S3 can slip too: S1+S2 still ship a correct mode (a).

## 6. Risks to Friday and the recommended cut

- Queue pressure: Thursday already holds the Codex round 2 fixes, the offline captioner V1, the offline tier V2, Codex round 3, the rest of the dump spec and turning `forgetV2` ON. Place S1-S3 adds about 8 h of serial agent time. The owner must choose the order (Q5).
- The schema is late: 18.json changes after the real-DB rehearsal (ab2af761). The rehearsal and `EncryptedMigrationV17to18DeviceTest` (encrypted DBs, on the emulator Friday) must be run again on the final tip. That is planned anyway, and it should also assert `place_link`.
- A missed choke point means a link row (place + time) survives a Forget. That is a privacy leak, not data loss. The golden with the switch OFF and ON plus `PlaceLinkForgetTest` are the guard.
- Mode (b) depends on `forgetV2` being ON and device-checked. With it OFF, only mode (a) is offered.
- Not unsafe: no prefs migration, no move of the source of truth, no change to 1.4.2, the matcher, Retention P2 or places.db.
RECOMMENDED CUT: build S1 + S2 now (they must be in v18 to avoid a v19 migration), and S3 once `forgetV2` is ON and green. S4 and everything in "follow-up" come after the trip.
Go/no-go: if S1 is not green with Codex findings fixed by Fri 27 Nov 12:00 IST, revert S1 (and the S2 link writer).
SAFE SUBSET (no schema): keep the mode (a) dialog, and run mode (b) on `PlaceMoments.of` = reference Moment + Moments of the pin's `place_card_log` rows (a table already in v18, pruned at 90 days / 500 rows). `place_link` then moves to v19.
Follow-up (not now): manual "add this Moment to a place" (`role = USER`), a Place filter in Activity, linked Moments as match references (with the loop guard), pins into a database table.

## 7. Open questions for the owner (recommended default in brackets)

Q1. Mode (a): should the "Looked at <place>" notes on the place's photos also go? [No: they belong to the Moments; mode (a) keeps them and the dialog says so. Mode (b) removes them.]
Q2. Mode (b): forget only the place's Moments, or every whole Scene that contains one? [Only the Moments: Scenes keep their other Moments, and an emptied Scene goes.]
Q3. Should Moments saved NEAR a pin count as its Moments, even with no card? [No: only pointers (the reference Moment + Moments where a card for this place was recorded); a Home pin would otherwise claim thousands.]
Q4. When multi-select Forget (`forgetV2`) is switched off in Settings, hide mode (b)? [Yes, hide it; mode (a) always works.]
Q5. Build order today: Place S1-S2 before or after the offline captioner V1/V2? [After the Codex round 2 fixes and BEFORE V1, because only the schema part has a deadline (v18). S3 after V2.]
