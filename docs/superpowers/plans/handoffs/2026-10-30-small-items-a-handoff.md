# Small items A (2026-10-30, branch feat/v099-watch)

No migration, PROTOCOL_VERSION 1, TRACE_SCHEMA 1, plan hash f5782e0d8610, Room v15 unchanged.

## 1. Forget for photo-less marks (answers and nudges REMOVED)
- Answer and Nudge Forget was built and then removed before rc1 (Codex review, P1): a single-row redaction left the question in Room, previews and exports, and a nudge's text lives on in derived rows and in-flight writers. `forgetAnswer/forgetNudge`, `ForgetTarget.Answer/Nudge`, their dialog copy and wiring are gone; `AnswerPreview`/`NudgePreview.forgetTarget()` are null again. Parked in `v1.1-forget-lifecycle-backlog.md`.
- Kept: Forget for a photo-less mark. `ForgetTarget.Mark` (level PHOTO, rejected by `ScopedForget.plan`), run by `ForgetActions.forgetScopedOutcome(..., undoMark = MarkRecorder::undoMark)` (the glasses undo path: fence, gated delete, its second rebuilt so the fallback Moment goes). Typed EXPORT/SAVE_IN_PROGRESS, FAILED. `ForgetKind.MARK` (`single`), dialog says it forgets one item and the Forget scope does not apply, no counts.
- P1 fix: `CanvasSlice3Dao.insertLabelFenced` refuses a label whose `mark_undone:<id>` marker exists, inside its own transaction, so a delayed duplicate `pc_mark` cannot bring an undone mark back (test: undo, replay, no row, labelRev unmoved).
- `DeleterGateInventoryTest` was stale since the preview migration (it looked for the deleted `MemoryDetailRoute.kt`); it now checks `ProductionForget.kt`. The mark undo does not match the unscoped-entry patterns, so no new exemption was needed.

## 2. Show that time
`Routes.day(date, atMs)` = `day/{date}?at={at}`; `StatusSection.showTimeAtMs`, `PreviewHost.showTime(day, at)`; `TimelineRow.atMs`; `DayScreen(scrollToMs)` scrolls once when the day loads; pure `DayScroll.indexFor(sections, atMs, hasPhotosChip)`. The day list is one query (not paged), so no load-more loop is needed.

## Tests
`MarkRecorderTest` (no resurrection), `ScopedForgetActionsTest` (mark outcomes), `PreviewMomentTest`, `PreviewScreenTest` (mark dialog), `MemoryDetailViewModelTest` (mark forget, typed messages), `DayTest.showThatTimePicksTheNearestRowsListIndex`, `RoutesTest`, `AllRoutesSmokeTest` fixture for `at`. Full `:phone:testDebugUnitTest` passed.
