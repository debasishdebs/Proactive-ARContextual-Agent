# Activity preview and list refactor (2026-10-29, branch feat/v098-flows)

UI-only. No change to Forget logic, the database, protocol, TRACE_SCHEMA, plan hash f5782e0d8610 or Room v15.

## What changed

- `previewMoment(ids, how, config)` (`ui/preview/PreviewModel.kt`) is the one way to open an item. Ids are `<kind>:<id>` (`PreviewRef.of`). It is pure and returns a `PreviewDestination` (`.route`):
  one item -> `Screen` (the previewer); an answer or nudge -> `Detail` (its existing screen, untouched); several ids or `PreviewMode.LIST` -> `Items` (the common list); an unknown id -> the empty list.
- `listView(kind, ids)` (`ui/list/ListKind.kt`) is the one list entry point (FRAMES, MEMORIES, ANSWERS, NUDGES, MARKS, MOMENTS). `RecordListSource` plugs into the existing `ListScreen` (new `ListSource.emptyText`; `ListItemCard` draws the same row inside Activity). Route `list/{kind}?ids=`.
- One presenter per kind, `Previewable` (`ui/preview/Previewable.kt`): `summary()` is the list row, `sections()` the previewer layout, `forgetTarget()` what Forget removes. Registry: `Previewables`.
- One previewer, `PreviewScreen` (route `preview/{ref}`): lays out `PreviewSection`s (Image, Text, Status, Config, Audio), each self-rendering and owning its actions; plus the Forget button when `forgetTarget() != null`. Sections hold ids and text only; photos load lazily.
  Forget, Trust and Edit run through the existing `MemoryDetailViewModel`, `ForgetDialog` and `productionMemoryForget`.
- Activity: the four top cards open `listView(kind, ids of the period)` (ids in `ActivityUiState.tileIds`); an empty kind shows its empty state. Marks and Moments rows call `previewMoment`; each section has "See all".
  The mark tap handlers, `MarkTarget`, `MarkRows.target`, the no-photo dialog and `TAP_HINT` are gone. A mark with no photo now opens the previewer with a Status section (reason code) and "Show that time".
- Not touched: `MemoryDetailScreen`, `FrameViewerScreen` and their routes still serve Day, Photo search and Ask. Answers and nudges still open their own detail screens.

## Add a new kind

1. Add a value to `ListKind` (title, empty text, `RefKind`); add a `RefKind` if it is a new object.
2. Add a `Previewable` + factory and register it in `Previewables` (`PreviewMomentTest.everyListKindAndEveryRefKindHasAPreviewable` fails until you do).
3. Need a new block (people, objects)? Add a `PreviewSection` subtype with its `Content`. Real audio: fill `AudioSection` (always present, greyed out today).
4. Give the card or section its ids and call `onOpenList(kind, ids)`.

## Notes

- Answer and nudge ids per period come from `recent(1000)` filtered by time (the repositories read only times by range); counts still come from `timesIn`.
- `MarkRepository.byIds` reads the existing marks query once (no new DAO query).
- A memory's previewer shows its own photo only (no day pager) so "Forget photo" and the detail view model agree on the frame; a frame or a photo-only mark pages the day.
