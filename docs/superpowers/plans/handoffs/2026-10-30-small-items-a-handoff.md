# Small items A (2026-10-30, branch feat/v099-watch)

No migration, PROTOCOL_VERSION 1, TRACE_SCHEMA 1, plan hash f5782e0d8610, Room v15 unchanged.

## 1. Forget for Answer, Nudge, photo-less mark
- No app-level entry existed to forget one answer or nudge (they were only redacted by the cascade of a forgotten source). `ForgetService.forgetAnswer/forgetNudge` redact ONE row with the cascade's own steps: step 6 (expire PENDING proposals, scrub params and logs) was extracted into `expireAndScrubOrigins`, used by both. Redaction text and `redacted` flag are the cascade's, so the previewer and lists show the same "removed" state as a cascade-redacted item. Cited memories and photos stay.
- `ForgetTarget.Answer/Nudge/Mark` (level PHOTO). `ScopedForget.plan` rejects them; `ForgetActions.forgetScopedOutcome` runs them through the same `run` (DataOpGate, typed outcomes, cache cleanup, refresh). Mark: `MarkRecorder.undoMark(labelId)` (the glasses undo path: fence, gated delete, its second rebuilt so the fallback Moment goes) passed in as `undoMark` (phone: `MarkGraph.recorder`); typed EXPORT/SAVE_IN_PROGRESS, FAILED. NOTE: `undoMark` sits before `onForgotten` in the signature so the trailing-lambda callers keep working.
- Previewables: `AnswerPreview`/`NudgePreview` return their target unless redacted; `MarkPreview` returns `Mark` only for the status layout (no caption, no photo). Memory/photo-backed marks are unchanged.
- Dialog: shared `ForgetDialog`, new `ForgetKind.ANSWER/NUDGE/MARK` (`single`), copy says "forgets just this one item, the Forget scope setting does not apply", no counts. `MemoryDetailViewModel.askForgetObject`, `MemoryForget.forgetObject`; export/save/cache/failed messages as the others.
- A forgotten answer or nudge stays in lists as its removed row (existing redaction behaviour); the previewer then shows no Forget and no sources. A forgotten mark leaves the list.

## 2. Show that time
`Routes.day(date, atMs)` = `day/{date}?at={at}`; `StatusSection.showTimeAtMs`, `PreviewHost.showTime(day, at)`; `TimelineRow.atMs`; `DayScreen(scrollToMs)` scrolls once when the day loads; pure `DayScroll.indexFor(sections, atMs, hasPhotosChip)`. The day list is one query (not paged), so no load-more loop is needed.

## Tests
`ForgetServiceTest` (answer, nudge), `ScopedForgetActionsTest` (answer, nudge, mark outcomes), `PreviewMomentTest`, `PreviewScreenTest` (3 dialogs, answer and nudge Forget, removed answer), `MemoryDetailViewModelTest` (object forget, typed messages), `DayTest.showThatTimePicksTheNearestRowsListIndex`, `RoutesTest`, `AllRoutesSmokeTest` fixture for `at`. Full `:phone:testDebugUnitTest` passed.
