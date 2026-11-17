# Activity pagination design (roadmap v1.3 row 1, 2026-11-17)

Scope: `docs/superpowers/plans/2026-11-16-roadmap-v1.1-v1.3.md` v1.3 row 1. There are two views. The **list view** shows 100 rows a page. The **broken-down view** is collapsible: day, then hour, then pages of 25 moments. Answers, nudges and marks past today's caps (1,000 and 200) must be reachable. The design also covers how this works with v1.2 row 1 (multi-select Forget) on the Forget lifecycle (`2026-11-16-forget-lifecycle-design.md`). Leases (M3) are done there. `ForgetExecutor`/`plan(roots)` (M4) and the UI states (M5) are not.

Constraints: no schema migration, so Room stays at v16 (`mem/db/ContextDatabase.kt:24`) and `schemas/.../16.json` stays byte-identical. Room is 2.6.1 (`services/memory/build.gradle.kts:25-27`). **Paging 3 is not available offline.** `ls ~/.gradle/caches/modules-2/files-2.1 | grep -i paging` finds nothing (no `androidx.paging`, no `room-paging`), so the design uses a hand-rolled keyset pager. Abbreviations: `mem/` = `services/memory/src/main/java/com/rokid/context/memory`, `ui/` = `phone/src/main/java/com/rokid/context/phone/ui`.

## 1. Current architecture

**Read queries (all newest first, no cursor):** Frames: `FrameViewDao.observeBetween`/`observeTimes`, `mem/db/ViewDaos.kt:14-18`. No limit, and full entities over the whole range. Memories: `MemoryViewDao.observeVisibleBetween`/`observeVisibleTimes`/`observeRecentVisible`, `ViewDaos.kt:26-33`, filtered on `reviewState = 'OK'`. Answers: `AskDao.observeRecent(limit)`/`observeTimes`, `ViewDaos.kt:88-89`. Both leave out `source = 'canvas_eval'` (developer eval asks). Nudges: `NudgeDao.observeRecent(limit)`/`observeTimes`, `ViewDaos.kt:96-97`. `CanvasDao.recentAsks(limit)` (`mem/db/CanvasDao.kt:155`) is the developer canvas path only (`AskDebug.list(50)`, `orchestration/.../canvas/AskDebug.kt:108`), not Activity. It is out of scope. Marks: `MomentDao.marksIn(from, to)` (`mem/db/MomentDao.kt:152-158`) orders by `atMs DESC, labelId` and runs a correlated `memoryId` subquery per row. Repository wrappers: `mem/FeedRepositories.kt:25-35` (timeline), `:67-68` (asks), `:74-75` (nudges).

**The caps:** 1,000: `RepoActivityData.RECENT` (`ui/preview/RepoActivityData.kt:87`). `idsIn(ANSWERS|NUDGES)` reads `asks.recent(1000)`/`nudges.recent(1000)` and only then filters to the period (`:51-52`). So in a period list, answers and nudges older than the newest 1,000 *overall* are unreachable, even when the period itself is small. 200: `MarkRepository.MAX_ROWS` (`mem/MarkRepository.kt:14,23`) applies `take(200)` after the query. `idsIn(MARKS)` (`RepoActivityData.kt:53`) inherits the cap. A second 200: `RecordListSource.PAGE = 200` (`ui/list/ListKind.kt:88`) is the "Show more" step. **The roadmap says "current, 100 per page", but the code has 200 (Q1).**. Other paths read every mark ever made: `MarkRepository.byIds` and `observeById` (`MarkRepository.kt:17-21`) scan `marksIn(0, MAX)`. `asks(ids)` runs one `observe(id).first()` query per id (`RepoActivityData.kt:43`).

**Lists:** `RecordListSource` (`ListKind.kt:59-89`) resolves *every* id of the period on each `load()` (`:81-85`), then summarises `take(shown)`. "Show more" adds 200 (`:79`). `ListViewRoute` (`ui/list/ListRoute.kt:18-23`) holds the source in a plain `remember`. Opening a preview and coming back recreates the source, so `shown` falls back to one page and the scroll position past row 200 is lost. `ListScreen` (`ui/list/ListScreen.kt`): Reloads the whole list on every change (`:114-115`) and on every resume (`:117-123`). Search and filter run on the loaded rows only (`:145-148`). Puts *all* rows into one `RowsCard` lazy item (`:176-183`). `RowsCard` is a plain `Column` (`:274-283`), so 1,000 rows are composed at once. Already holds a saveable selection set (`:112`). Change signal: `ContextDatabase.tableChanges` (`ui/list/RoomChanges.kt:13`) and `listChanges(kind)` (`RepoActivityData.kt:68-74`).

**The Activity page:** `ActivityViewModel.query` (`ui/activity/ActivityViewModel.kt:52-66`): Today: `visibleMemoriesIn`, `framesIn`, the ask/nudge times and `marksIn` (cap 200), giving tiles, the Morning/Afternoon/Evening sections and the inline marks (`ActivityScreen.kt:111-120`). Week and Month: full frame entities over the period for the bars (`:60`). `Timeline.sections` (`ui/activity/ActivityModel.kt:75-81`) places a memory at its frame's `ts`, else at its `createdAt`, and puts other-day photos under "Earlier". The Day drill-down: `DayViewModel` (`ui/activity/DayViewModel.kt:55-56`). Routes: `Routes.list`/`listIn` (`ui/shell/Routes.kt:92,94`).

**Indexes we can use (no migration):**

| Table | Index | Declared at |
|---|---|---|
| `ask` | `ts` | `mem/db/AppEntities.kt:48` |
| `nudge` | `ts` | `AppEntities.kt:60` |
| `frame` | `ts` | `mem/db/MemoryEntities.kt:13` |
| `memory` | `createdAt` | `MemoryEntities.kt:26` |
| `canvas_label` | `atMs` | `mem/db/CanvasSlice3Entities.kt:106` |
| `pause_interval` | `startMs` | in `16.json` |

`moment` has no time column and no index, so "moments" in Activity means visible memories, as `ListKind.MOMENTS` already does (`ListKind.kt:24`).

## 2. Target design

### 2.1 Keyset queries (new `@Query`s only, all bounded)

The cursor is `Cursor(key: Long, id: String)`. The first page uses `Cursor(toExclusive, "")`. Order is `key DESC, id DESC` for every kind except marks, which keep today's tie order (`labelId ASC`). The predicate is `key < :cKey OR (key = :cKey AND id <cmp> :cId)`. It uses the existing key index for range plus order, and SQLite sorts the tiny same-millisecond groups.

| Kind | New query (DAO) | Key | Filter (must equal the tile-count filter) |
|---|---|---|---|
| ANSWERS | `AskDao.pageIn(from, to, cKey, cId, n)` | `ts` | `source != 'canvas_eval'` |
| NUDGES | `NudgeDao.pageIn` | `ts` | none |
| FRAMES | `FrameViewDao.pageIn` | `ts` | none |
| MEMORIES/MOMENTS (list view) | `MemoryViewDao.visiblePageIn` | `createdAt` | `reviewState = 'OK'` |
| MARKS | `MomentDao.marksPage` (the `marksIn` SQL plus the cursor and `LIMIT`) | `atMs` | `l.kind = 'MOMENT'` |
| MOMENTS (hour pages) | `MemoryViewDao.momentPageIn(dayFrom, dayTo, hFrom, hTo, cKey, cId, n)` | `COALESCE(f.ts, m.createdAt)` via `LEFT JOIN frame f ON f.id = m.frameId` | `OK`, and `createdAt` within the day (index), then the hour on the expression |
| Hour counts | `MemoryViewDao.visibleMomentTimes(dayFrom, dayTo)` returns `COALESCE(f.ts, m.createdAt)` | | same filter, bucketed in Kotlin (DST-safe) |
| Pauses | `PauseDao.overlapping(from, to)` (`mem/db/AppDaos.kt:30`, next to `countZoneOverlapping` at `:44-45`) | | |
| Fixes for the full scans | `MomentDao.marksByIds(ids)` and `markById(id)` (`WHERE l.labelId IN (:ids)`); `AskDao.byIds` | | |

**Rules:** Pages return entities. The summary factory receives the rows it already has (a `PreviewCtx` pre-filled cache), so there are no N+1 reads. `RECENT` and `MAX_ROWS` stop being list caps. `idsIn` is removed, because a list never resolves all of its ids. The inline marks on the Activity page keep a small cap with "See all".

### 2.2 Pager (pure Kotlin, `ui/list/paging/`)

The fetch interface is `PageSource<T>`:

```kotlin
suspend fun page(range, after: Cursor, n: Int): List<T>
suspend fun through(range, boundary: Cursor, cap: Int): List<T>
fun cursorOf(t: T): Cursor
val changes: Flow<Unit>
```

**`KeysetPager` (list view):** Holds pages of 100 in a **sliding window of 10 pages (1,000 rows)**. Loading page 11 drops page 1 and shows **Show newer** at the top, which reloads the dropped page above. Refresh after a change is one query: `through(range, boundary = last loaded cursor, cap = loaded + 100)`. It re-reads everything from the window end down to the bottom boundary, so deleted rows vanish, new rows appear and the bottom does not move. Changes are debounced by 300 ms, because one Forget touches many tables. Unlike OFFSET, rows inserted at the head never shift or duplicate the loaded pages.

**`HourPager` (broken-down view):** Tracks the pages of one hour as a cursor stack (`List<Cursor>`, with page i's start at index i), so Prev/Next are both one query. The page count is `ceil(hourCount / 25)`.

### 2.3 State model (`ListViewModel`, scoped to the nav back-stack entry; small parts in `SavedStateHandle`)

```kotlin
data class ListUi(val kind: ListKind, val scope: ListScope, val view: View /* LIST | BREAKDOWN */, val list: ListPane, val tree: TreePane,
                  val selection: Set<Ref>, val newCount: Int, val error: String?)
data class ListPane(val rows: List<ListItem>, val headDropped: Boolean, val hasMore: Boolean, val loading: Boolean)
data class TreePane(val days: List<DayNode>)
data class DayNode(val date: LocalDate, val count: Int, val expanded: Boolean, val hours: List<HourNode>?)          // hours null until expanded
data class HourNode(val startMs: Long, val endMs: Long, val label: String, val count: Int, val pauses: List<PauseChip>,
                    val expanded: Boolean, val page: Int, val pages: Int, val rows: List<ListItem>?)
```

What `SavedStateHandle` keeps (all small, and it survives process death): `view`, the expanded day dates, the expanded hour starts, `page` per expanded hour plus the cursor stack, the list-view anchor (the cursor of the first visible row and the window's top cursor), and `selection` as ref strings.

Live counts: day counts come from the kind's times flow (as today's tiles). Hour counts come from `visibleMomentTimes` for expanded days only.

### 2.4 UI behaviour

**Both views:** Same route with a **List | By time** toggle in the header. `ListScreen` rows become real lazy `items(key = ref)`, with the `RowsCard` styling kept per row. "Show more" becomes auto-load when the last 10 rows come into view, and the button stays for accessibility.

**Broken-down view:** Days are newest first. Today is expanded. Other days are collapsed and show "Tue 12 Nov · 143 moments". Expanding a day shows its non-empty hours, with counts. Consecutive empty hours fold into one "No activity 01:00-06:00" line. Expanding an hour shows page 1 of 25 with a "‹ 1 of 4 ›" control. Pages replace each other, they don't append (Q4). At most **4 expanded hours**. Expanding a fifth collapses the least recently used one. Rows whose display time falls outside the day stay in an "Earlier photos" group at the end of the day, as `Timeline.sections` does.

**Scroll position restore:** The view model survives forward navigation, so coming back from a preview keeps the pages, and `rememberLazyListState` (saveable) keeps the offset. After process death, the anchor is re-read with one `through(window top .. anchor)` query, then the screen scrolls to the anchor's key. If the anchor was forgotten, the screen scrolls to the nearest following key. `ON_RESUME` no longer reloads everything (`ListScreen.kt:117-123`); the change flow covers it.

**New items:** At the top (first visible index 0), new rows appear in place. When scrolled down, keyed items hold the position, the head is refreshed, and a "N new" pill (`newCount`) scrolls to the top. In the broken-down view, counts update live. Rows in an open hour page refresh only when that page is the hour's newest. A period window that ends in the past (Week and Month anchors) never receives new rows except late ones (re-describes).

**Forget during paging:** A single Forget from the preview fires `listChanges`. The debounced refresh removes the row and the counts drop. An open hour page that becomes short re-reads from its start cursor. A page that becomes empty steps back one page. An hour that becomes empty disappears. Delete all, which moves the wipe generation, resets the pager to the head and clears the selection. Retention deletes behave like a Forget. During a mass Forget the blocking overlay is shown (`ItemAction.blocking`), and paging requests queue behind it. WAL reads are not blocked, but the refresh waits for the receipt so the user sees one clean re-read.

**Zones, pauses and placeholders:** Hours that overlap a `pause_interval` show a chip: "Paused 14:05-14:40" for a manual or glasses pause, and "Privacy zone" for `zone:%`. The chip never shows the zone name or location. An hour that has only a pause shows the chip instead of a count. A pending mark ("Photo on its way", `ActivityModel.kt:136`) and a redacted answer ("Removed ...") stay rows that update in place. While a page loads, the known count reserves fixed-height skeleton rows, so the scroll does not jump. List view reserves 1 row. Marks keep their PAUSE_ZONE reason text.

**Developer-only items:** Eval asks (`canvas_eval`) are never in pages, counts or selection, Developer mode or not, as today. Developer-only preview sections (`ui/preview/PreviewSection.kt:189`, `PreviewRoute.kt:102-104`) are unchanged. Scoped Moment and Scene Forget stay Developer-mode only until forget-spec Q5 is ruled. A test pins that each page filter equals its tile-count filter.

**Search:** today it filters loaded rows only. In v1.3 the field searches the loaded window and says so ("Searching the loaded 1,000"). SQL search per kind is Q3.

### 2.5 Multi-select Forget (v1.2) over the pager

**Selection model:** Selection is a `Set<Ref>` (`"family:id"`, forget spec section 2.1), never positions. It survives page loads, window drops, collapse and expand, view switches and process death (it is saved). Tap-and-hold enters select mode, and tap then toggles. This replaces the checkbox-only `selectable` mode for Activity lists. Flows keep theirs.

**Top-right menu:** **Select this page**, **Select this hour (N)**, **Select this day (N)**, **Clear** and **Forget selected (N)**. Hour and day selection resolve ids with an ids-only keyset query, up to the Forget ceiling. **Q2** asks whether they should be a kind-scoped id set (proposed) or a Range Forget of everything in the time range.

**Forget flow:** Forget is one `plan(roots = selection)`, then preview, then `execute` (forget spec sections 2.4, 2.5 and 2.7). The acknowledgement dialog appears when the selection has more than 200 roots or user nodes, or more than 200 DROP nodes (`ackHash = planHash + "+ack"`). `ForgetContentChanged` re-opens the dialog with fresh counts. `ForgetTooLarge` (above 5,000 nodes) says "Select fewer". The menu disables Forget past the ceiling before planning.

**After the Forget:** On success the selection is cleared, and one refresh runs after the receipt. Refs that no longer exist (forgotten elsewhere meanwhile) are dropped by the planner. The selection count prunes them on the next refresh of loaded rows, and an unloaded ref is checked at plan time.

**Dependencies:** The Forget action needs M4 (planner, preview, ack) and M9. Until then, the pager ships the selection model with Forget hidden, behind the `ActivityMultiSelect` flag. If v1.2 multi-select lands first on today's capped, non-lazy list, v1.3 has to keep its `Set<Ref>` contract. Q5 asks for the order.

## 3. Performance bounds

| Operation | Bound / target |
|---|---|
| A page query (100 rows, any table size) | Index range scan plus `LIMIT`. Target p95 under 30 ms on device at 100k rows, including the summary build. Marks: at most 100 correlated subqueries |
| Eval-ask skip | The scan passes eval rows. Measure at 10k eval asks; acceptable under 50 ms, else Q6 |
| Hour page (25) | Scans one day's visible memories (a few thousand) for the expression filter. Under 20 ms |
| Refresh | One query, at most window rows plus 100. At most one per 300 ms |
| Memory held | List view: 1,000 rows. Broken-down view: 4 x 25 rows, plus day counts (one Long per row in the period) and hour counts for expanded days |
| Composition | Lazy items: only visible rows composed (today: all rows) |
| Room | No new tables, columns or indexes; v16 unchanged (`MigrationSchemaTest` / `16.json` identical) |

Not changed here (flagged): Week and Month tiles read full frame entities over the period (`ActivityViewModel.kt:60`). A count query is a cheap follow-up (Q7).

## 4. Tests

**Database (memory module, Robolectric Room):** `ActivityKeysetDaoTest`: every kind: ties at the same millisecond, range bounds (first inclusive, last exclusive), and no skips or duplicates while rows are inserted and deleted between pages. The union of all pages equals the count from `observeTimes`. Eval asks are excluded. Marks keep the `labelId ASC` tie order. `momentPageIn` uses the frame `ts` with a `createdAt` fallback.

**Database, other tests:** `MarkByIdsTest`: the new by-id queries equal the old full-scan results. `PauseOverlapTest`: open and closed intervals, and `zone:` vs manual. `SchemaUnchangedTest`: the exported v16 JSON is identical.

**Pager (phone, JVM):** `KeysetPagerTest` over a fake source: sliding window and Show newer. Refresh boundary. `newCount`. Debounce. Forget of the anchor. Process-death restore.

**Pager (phone, JVM), other tests:** `HourPagerTest`: Prev/Next, a short page after a delete, an empty page stepping back. `DayHourTreeTest`: DST 23- and 25-hour days, a zone change mid-session, the Earlier group, folding of empty hours, pause chips.

**Phone UI (Compose):** `ListScreenTest`: lazy rows, auto-load, the "N new" pill, scroll restore after navigating to a preview and back. `ActivityBreakdownScreenTest`: expand and collapse, the LRU cap of 4, page control. `RecordListChangesTest`: updated.

**Selection:** `SelectionModelTest`: survives view switches and process death. Select hour and select day resolve all ids beyond 1,000. Pruning. Ack over 200 (with a fake planner until M4).

**Regression:** `RepoActivityDataTest`: the 1,001st answer and nudge of a period, and the 201st mark, are listed and openable.

**Device (worn-test list):** Scroll to row 1,500 and back from a preview. Forget from inside an hour page. Timing of the 100k seed.

## 5. Task breakdown (Sonnet-sized; one worktree each; Codex reviews each)

| Task | Files | Acceptance |
|---|---|---|
| A1 Keyset DAO queries | `mem/db/ViewDaos.kt`, `MomentDao.kt`, `AppDaos.kt` (PauseDao); tests in `services/memory/src/test/.../db/` | `ActivityKeysetDaoTest`, `MarkByIdsTest`, `PauseOverlapTest` green; `16.json` unchanged; no `@Entity` touched |
| A2 Page sources | new `mem/ActivityPages.kt` (one `PageSource` per kind); `mem/FeedRepositories.kt`, `mem/MarkRepository.kt` (byIds and observeById via new queries) | No `RECENT` or `MAX_ROWS` on a list path; the 1,001st answer, 1,001st nudge and 201st mark are listable |
| A3 KeysetPager and HourPager | new `ui/list/paging/KeysetPager.kt`, `HourPager.kt`, `Cursor.kt` | `KeysetPagerTest` and `HourPagerTest` green; Android-free |
| A4 List view | `ui/list/ListScreen.kt` (lazy rows, auto-load, pill, Show newer), `ListKind.kt` (`RecordListSource` on the pager, PAGE = 100), `ListRoute.kt` (`ListViewModel`), `ui/preview/RepoActivityData.kt` (drop `idsIn`, pre-filled ctx) | `ListScreenTest` and `RecordListChangesTest` green; scroll restore after preview works; Flows list is unchanged |
| A5 Day/hour tree model | new `ui/list/paging/DayHourTree.kt`; `ListViewModel` tree pane and `SavedStateHandle` | `DayHourTreeTest` green |
| A6 Broken-down UI | new `ui/list/BreakdownPane.kt`; toggle in `ListScreen.kt` header | `ActivityBreakdownScreenTest` green; at most 4 open hours; skeleton rows |
| A7 Selection model | new `ui/list/paging/Selection.kt`; long-press in `ListScreen.kt`; top-right menu; flag | `SelectionModelTest` green; Forget hidden until M4 and M9 |
| A8 Multi-select Forget wiring (after forget M4 and M9) | `orch/.../forget/ForgetActions.kt`, the dialog | ack over 200, ContentChanged, and TooLarge paths; one refresh after the receipt |
| A9 Performance check | test seed helper; worn-test list entry | Timings recorded against section 3; device pass |

Order: A1 → A2 → (A3 ∥ A5) → A4 → A6 → A7 → A8 (gated) → A9. A1-A6 do not depend on the Forget work.

## 6. Open questions (user ruling needed)

1. **Page size.** The roadmap says "current, 100 per page", but `RecordListSource.PAGE` is 200 (`ListKind.kt:88`). Proposed: 100.
2. **Select hour or day.** Should it be a kind-scoped id set (proposed), or a Range Forget of everything in that time (photos, marks and traces too)?
3. **Search.** Within the loaded window, labelled as such (proposed for v1.3), or SQL search per kind (asks and nudges `LIKE`, memories FTS) now?
4. **Hour pages.** Replace (a "1 of 4" control, proposed) or append ("Next 25")?
5. **Order with v1.2.** Ship pagination (A1-A6) before multi-select, so selection lands on lazy, uncapped lists? Proposed: yes, which in practice means re-ordering v1.2 row 1 after v1.3 row 1.
6. **Eval-ask skip cost.** If it is over budget, accept a partial index? That is a migration, so it is out unless you rule for it.
7. **Week/Month tiles.** Fold the full-frame-entity read (`ActivityViewModel.kt:60`) into this work package, or leave it?
8. **Which kinds get the broken-down view.** MOMENTS only (the roadmap wording), or every kind, since the hierarchy is generic?
