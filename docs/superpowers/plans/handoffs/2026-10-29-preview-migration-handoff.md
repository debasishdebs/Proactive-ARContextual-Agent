# Preview migration: one place to preview an item (2026-10-29, branch feat/v098-flows)

UI-only. No change to Forget logic, the database, protocol, TRACE_SCHEMA, plan hash f5782e0d8610, Room v15 or the glasses. Builds on `2026-10-29-activity-preview-handoff.md`.

## What changed

- Every tap that opens an item goes through `previewMoment` (always `Screen`, route `preview/{ref}`): Activity marks and moments, Day rows and "View N photos" (pager from the day's first photo), Photo search "Open" (frame, or the caption memory when the photo is gone), Ask citations and cited photos, Nudge sources, Home answer and nudge cards, "Open this day's photos". `PreviewDestination.Detail` is gone.
- New `Previewable`s: `AnswerPreview` and `NudgePreview` (`ui/preview/CitedPreview.kt`), with new sections `RatingSection`, `ProposalSection` (the live ProposalCard flows: propose, confirm, undo) and `SourcesSection` ("Based on": memories and photos, expired photo kept inert, "N sources no longer available", "More photos").
- `ImageSection` took over the photo viewer: day pager with time labels ("2 / 5 · 09:05"), slideshow Play/Pause, "Open this day's photos", Forget photo, debug "Add to photo test" and "Copy frame id".
- The previewer reloads on resume, so a cited memory forgotten from a source card, or a new rating, shows when you come back. Header names the kind (Memory, Photos, Mark, Answer, Heads up). Answers and nudges keep the Home tab highlighted (`Routes.tabFor(route, ref)`).
- Kept: Forget (shared `ForgetDialog`, Forget scope, `productionMemoryForget`), Trust, Edit, review state, tags, confidence, redacted answer and nudge handling, "Why".

## Removed

Composables and their routes: `MemoryDetailScreen/Route`, `FrameViewerScreen/ViewModel`, `AskDetailScreen/Route/ViewModel/Model`, `NudgeDetail.kt` (screen, model, view model, route). Routes `ask/{id}`, `memory/{id}`, `nudge/{id}`, `frames/{date}` and their builders; `Routes.showsNav` no longer special-cases the viewer. `DayUiState.frameCount` is now derived from `frameIds`; `MemoryDetailUiState.frameDate`, `MemoryDetailViewModel.forgetShown`, `ProposalLine`, `SourceCard` are gone.
Moved (not rewritten): `ForgetDialog` (memory/ForgetDialog.kt), `productionMemoryForget` (memory/ProductionForget.kt), `ZoomableFrame` (preview/ZoomableFrame.kt). `MemoryDetailViewModel` and `MemoryDetailModel` stay: the previewer runs Trust, Edit and Forget through them.

## Not preserved exactly (user-visible differences)

- The photo viewer was full-screen black without the bottom nav; a photo now opens in the normal previewer page (nav bar visible, light theme).
- A memory's previewer offers "Forget fact" and "Forget photo" (as in the baseline previewer); the old "Forget moment" button is the photo Forget with the Forget scope setting.
- The old read-only proposal line (when no ProposalCard) is gone; the card is always shown. A nudge's card now also offers "Enable" when actions are off (as on Answers).
- Rating shows the tapped choice at once from local state; it is re-read from the database on resume.
- A cited memory removed while the Answer stays open updates on resume, not live.
- `PhotoTile.date` is now unused by the screen (kept for its tests).

## Review round (Codex, NOT READY) fixes

- P1 Forget photo targets the photo on screen: frame previews and photo-only marks have no opening-frame `forgetTarget()`; their `ImageSection` carries Forget photo for the current pager page (test: swipe, then Forget).
- P1 A forgotten or missing memory shows "This memory is no longer available", never its cached text.
- P1 The previewer observes its item: `ActivityData.changes(ref)` (memory, answer or nudge row plus the memories they cite) re-reads on every change, so redactions and Forgets disappear at once.
- P1 Forget and edit outcomes (export or save running, content changed, too large, failed, cache left) show in a previewer-level message slot for every kind.
- P2 Resume refresh flag lives in the view model (survives the composition being disposed). Answer and nudge lookup is by id (`asks.observe`, `nudgeGateDao().observe`), no longer `recent(1000)`. Level, Trust and Confidence in the Config section follow the live memory state.
- P2 After Forget photo with more than one photo in the pager the previewer stays on the neighbour (`photoAfterForget`); it closes only when none remain (this reverses the earlier "goes back" note above).

Deferred (not cheap or no old behaviour): Forget for Answers, Nudges and photo-less marks (the old screens had none); passing a list key instead of the id list in `list/{kind}?ids=` and paging `ListKind` loads (the lists still read all ids and render lazily).

## Measured since baseline 68c84585 (git diff --shortstat 68c84585..HEAD)

| Scope | Files changed | Added | Removed | Net |
|---|---|---|---|---|
| Overall (all of android-companion; no other files touched) | 46 | 834 | 1638 | -804 |
| `phone/.../ui/` main code | 31 | 417 | 970 | -553 |
| `phone/src/test` | 15 | 417 | 668 | -251 |

Net per ui package (main + test): ask -559 (all), frames -285 (all), nudge -199 (all), memory -345, shell -46, activity +6, home +2, photos +1, preview +621 (the one maintained place: two new kinds, six sections, the pager and the tests), settings/diagnostics 0.

Deleted files (main): ask/AskDetailModel.kt, AskDetailRoute.kt, AskDetailScreen.kt, AskDetailViewModel.kt; frames/FrameViewerScreen.kt, FrameViewerViewModel.kt; memory/MemoryDetailRoute.kt, MemoryDetailScreen.kt; nudge/NudgeDetail.kt.
Deleted files (tests): ask/AskDetailModelTest.kt, AskDetailScreenTest.kt, AskDetailViewModelTest.kt; frames/FrameViewerTest.kt; memory/MemoryDetailScreenTest.kt; nudge/NudgeDetailTest.kt.
Added: preview/CitedPreview.kt, preview/ZoomableFrame.kt, memory/ForgetDialog.kt, memory/ProductionForget.kt (moved code); tests preview/PreviewScreenTest.kt (Forget dialog and scope, Trust, Edit, review state, pager, slideshow, rating, sources, photo cards, proposal slot, nudge) and preview/FakeActivityData.kt; PreviewMomentTest gained Answer, Nudge, redaction and source cases; AllRoutesSmokeTest renders every kind through `preview/{ref}`.

## Add a new kind (for example "Place" or "Person")

Files touched: 3 for a kind made of existing sections, 4 with a new block.
1. `list/ListKind.kt`: add the value (title, search hint, empty text, `RefKind`). `preview/PreviewModel.kt`: add a `RefKind` (prefix, header title) and, if its rows are new, an `ActivityData` read.
2. `preview/<Kind>Preview.kt` (or `Previewable.kt`): a `Previewable` (`summary()` the row, `sections()` the layout, `forgetTarget()`) and its `PreviewableFactory`; register it in `Previewables`. `PreviewMomentTest.everyListKindAndEveryRefKindHasAPreviewable` fails until you do.
3. New block only: a `PreviewSection` subtype with its `Content` in `preview/PreviewSection.kt`.
4. Give the card or row its ids and call `previewMoment` / `listView`.
No route, destination, screen, view model or navigation change: `preview/{ref}` and `list/{kind}` already serve it. Before this migration a new kind meant a screen, a view model, a model, a route constant, a destination and a test class (Answer: 5 files, 559 lines).

## Tests

Full `:phone:testDebugUnitTest` once at the end: 2023 tests; the only failure was the known flaky `LocalModelsPageTest`, which passes alone.
