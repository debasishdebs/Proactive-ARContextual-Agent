# Place fixes, track B (card history on the phone), handoff 2026-11-25

## RESUME HERE
- Branch `place-fixes-B` (from `feat/v101-ask-eval`), worktree `.claude/worktrees/agent-ad92c343c13ba91f4`. Spec: `docs/superpowers/plans/2026-11-24-place-fixes-build-spec.md` (B1, B2, B3).
- State: B1, B2 and B3 are all written. Verification in progress: memory (1 failure on the first run was only the missing exported `2.json` in the test assets, now generated), then orchestration and phone test runs. Commit per step after green.
- Gradle only through `run_wt.sh`, one job at a time (the lock is shared with other agents; a job can wait several minutes for it).
- Seams track A must know:
  - `PlaceHistoryEvent`, `HistoryKind` and `PlaceCardHistory` live in `services/orchestration/.../work/frames/place/PlaceCardHistory.kt` (track B owns the file; track A's S1 should use these types and not redefine them). `PlaceGraph.history(context)` gives the store. `DeveloperFlags.placeCardHistory(context)` is the flag (default ON).
  - `PlaceHistoryEvent` has one extra optional field `delivery: String? = null`, appended last (null: POSSIBLE gets `NOT_SENT_POSSIBLE`, every other kind `PENDING`).
  - The sampler must call `history.add(event)` with the card's `cardId` (the id `PlaceCardHolder` stamped). The Hub reports delivery by that id. A delivery that arrives before the row exists is remembered (bounded, in memory) and applied by `add`, so the order publish then add is safe.
  - New `PlaceCardHolder.publishIfId(card, epoch): String?` (like `publishIf`, returns the new card id), used by "Show on glasses again".
  - `PlaceGraph.existingHistory(context)` is the read accessor that never creates `places.db`.
  - No `PlaceSampler.kt` change was made in track B.

## Steps
| Step | State |
|---|---|
| B1 places.db v2, `place_card_log`, DAO, `PlaceCardHistory`, Forget wiring | written; memory tests green except the schema-asset issue (fixed by the generated `2.json`) |
| B2 phone UI (list, Home row, detail, show again) | written, tests written |
| B3 Hub delivery state | written, `HubTest` placeDelivery_* tests written |

## Decisions and notes
- Photo retention also fires the time-link range (marks and traces around an expired photo). The history listener ignores that (`onRangeExpired` is a no-op for it), so a retention expiry never deletes a row. New `ForgetListener.onPhotosExpired` and `onRangeExpired` default to the Forget callbacks, so every other listener is unchanged. `ForgetService.Pending` and `DeleteCtx.notifyAfterCommit` carry an `expired` flag (set at the expiry call sites in `ForgetService` and `ForgetExecutor`).
- `place_card_log` is registered in `DeletionGraph` as SCRUBBED (it lives in places.db like `place`/`cell`; `WipeDao` is context.db only, and `DeletionGraphInventoryTest` ties WIPED to WipeDao) and in `PlacePackFamily.tables`.
- The history listener is registered in `PlaceGraph` next to the card listener. The Application calls `PlaceGraph.attach(this)` so a Forget before any place use still reaches the file. A Forget never creates `places.db`.
- Extra delivery state `NOT_SENT_BUSY` (another message had the glasses for the whole life of the card). The spec lists link, host and zone only; without it a card that lost to an answer would be mislabelled.
- "Show on glasses again" is disabled for POSSIBLE rows ("This was only a possible match"): a card must not claim more than the model was sure of. PHOTO rows need a live photo; pin, cluster, text and nearby rows do not.
- Show-again rebuilds cards with `PlaceCardRebuild` (new file). Pin, sign and cluster cards use `PlaceConfidence.PHOTO` with their own source label ("Your pin", "Sign read · your pin", "Your pins"). Track A adds `PlaceConfidence.PIN`; switch the rebuild to it at merge.
- Home polls the newest row every 4 s only while Home is collected, and never creates `places.db`.
- No data, names, coordinates or ids in logs: literal lines with counts only.
