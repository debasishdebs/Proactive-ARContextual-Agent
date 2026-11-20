# Combined-flows audit (2026-11-20)

Branch `feat/v101-ask-eval`. Read-only: no files changed, no Gradle, no adb. Paths are relative to `android-companion/`.

The question: where do two or more independent Room/DAO reads (flows, or a flow plus a one-shot suspend lookup) feed ONE user-visible state, so that a Forget, Delete all or expiry can show removed content beside updated content? The reference bug is the Diagnostics suspect list (count and rows were two flows). It is already fixed with one query (`ForgetDao.observeSuspects`, total as a subquery).

## How the deletes behave (this decides the staleness)

- **Forget moment/photo** (`ForgetService.forgetMomentTx`): one transaction deletes the memory rows, the `frame` row and the moment parts, and redacts asks and nudges that cite `frame:<id>`.
- **Photo expiry, retention** (`ForgetService.expirePhotosTx`, ~line 150): deletes the `frame` row and the JPEG. **The memories stay, and cites are not redacted.** This is the path that leaves a memory flow with nothing to re-emit.
- **Forget fact / expire** (`cascade`): memory rows, plus redaction of asks and nudges.
- Room invalidates per table. Each observed query re-runs on its own, and there is no `distinctUntilChanged` on `framesIn` or `visibleMemoriesIn`. When one transaction touches both tables, a `combine` of the two flows emits once with one side fresh and the other stale, and the next emission corrects it. That is bounded to one emission. Staleness is unbounded only when a value comes from a one-shot read that no flow re-triggers.

## Findings

### P2-1: answer/nudge previewer keeps showing an expired photo (persists)

- `phone/.../ui/preview/PreviewSection.kt:269`: `SourceCardRow` reads `produceState(null, src.frameId) { host.framePath(id) }`. This is a single read, never observed.
- `phone/.../ui/preview/RepoActivityData.kt:87-88,110-112`: `changes(ASK|NUDGE)` re-loads on the ask/nudge row and the cited memory rows only. Photo cites (`frame:` prefix) are filtered out, and the `frame` table is not watched.
- `core/ui/.../FrameImages.kt:75`: the decoded bitmap is kept while `path` is unchanged.
- **Scenario:** an answer is open in the previewer. It cites a photo, or a memory whose photo is shown as a thumbnail. Retention runs `expirePhotos` on that frame. The row and the JPEG are gone, but there is no redaction, so the ask row is unchanged and no reload happens. The card keeps the decoded photo, with the label "Photo, opens the photo previewer", until the screen resumes or is reopened.
- **Content:** a photo (privacy-relevant). It is not bounded. It is a retention removal, not a user Forget. A user Forget moment redacts the ask, which triggers a reload. A Forget run from a screen on top triggers the ON_RESUME reload.
- **Minimal fix:** in `SourceCardRow`, use `host.observeFramePath` the way `ImageContent` already does (PreviewSection.kt:172-176, "a removed row drops the decoded photo at once"). Alternatively, add a `frame` table watch to `changes(ASK/NUDGE)`.

### P2-2: memory previewer shows the forgotten memory's tags while the reload runs (bounded)

- `phone/.../ui/preview/PreviewRoute.kt:82-90`: two sources feed one screen. `PreviewViewModel.item` is a snapshot reloaded on `changes(ref)`. `MemoryDetailViewModel.state` is the live `timeline.memory(id)`.
- `PreviewSection.kt:287-293` (`TextContent`): the text is gated on `d.found` (good).
- `PreviewSection.kt:328-339` (`ConfigContent`): only Level and Confidence are live. **Tags and Saved come from the stale item, and when `!d.found` all entries fall back to the item's values.**
- **Scenario:** a memory is forgotten while its preview, or a mark preview that holds it, is open (for example a retention `expire`). The detail flow emits `found=false` first, so the text says "This memory is no longer available". The async `reload()` has not finished yet, so "Tags: ..." of the forgotten memory is still on screen.
- **Bounding:** bounded to one reload. For a mark, `observeMark` joins `memory` (MomentDao.kt:212-214), so it reloads too.
- **Content:** tag text.
- **Minimal fix:** in `ConfigContent`, when `s.live && !d.loading && !d.found`, render nothing. This gates the section on the primary flow, as `TextContent` already does.

### P3-1: Activity Today, Activity period and Day combine memories with frames (bounded, one emission)

- `phone/.../ui/activity/ActivityViewModel.kt:56` and `:60`: `visibleMemoriesIn` (memory), `framesIn` (frame), `asks.timesIn` (ask), `nudges.timesIn` (nudge), `marksIn` (one joined query: canvas_label, moment_part, moment, memory).
- `phone/.../ui/activity/DayViewModel.kt:55`: `visibleMemoriesIn` plus `framesIn`.
- Thumbnails of out-of-window frames come from `Timeline.framesFor` (ActivityModel.kt:95), a live `framesByIds` read on every emission.
- **Scenario:** Forget moment runs one transaction over memory and frame. If the frames flow re-emits first, for one emission the forgotten memory's caption row is still listed. Its thumbnail resolves to null, because the lookup is live. The other order leaves only stale counts (`Tiles`, Day's "View N photos"). Photo expiry alone and Forget fact alone are consistent, because only one side changes and the other side is re-read in the same lambda.
- **Content:** caption text, for one emission (about a millisecond).
- **Fix, only if this is wanted:** one `@Query` (memory LEFT JOIN frame for the window), or `db.tableChanges("memory","frame")` that triggers a single `withTransaction` snapshot read.

### P3-2: memory detail view model has a one-shot frame lookup inside the memory flow (latent, persists)

- `phone/.../ui/memory/MemoryDetailViewModel.kt:40`: `timeline.memory(id).map { ... timeline.frame(id) }`. After `expirePhotos`, the memory row does not change, so `framePath` stays set and `photoExpired` stays false until the view model is recreated.
- **Why it is P3:** a grep found no UI reader of `detail.framePath` or `photoExpired`. The previewer images use `observeFramePath`. `frameId` comes from the memory row and is correct.
- **Minimal fix:** drop the lookup, or observe a memory LEFT JOIN frame query, so this does not become a bug when a screen starts reading the field.

### P3-3: Diagnostics keyframe block and KeyframeStats (counts only)

- `phone/.../ui/settings/Diagnostics.kt:184`: `combine(keyframes.stats, extras, suspects)`. The suspects are one query now (fixed). `extras` holds counts loaded once at open or on Retry all.
- `services/orchestration/.../work/frames/KeyframeStats.kt:67`: five flows (frame_state counts, budget, latency, shed, cluster counts). Inside the lambda, suspend reads of `frame_meta` (`keyframesPerHour`, `unrouted`) and `summarizedSince` re-run only when one of the five emits. A `frame_meta` change alone leaves them stale until the next emission.
- **Content:** numbers only, no text or photo. No action needed for privacy.

### P3-4: Home suggestion cards re-derive on store, tick and gate events, not on a memory Forget (UNVERIFIED)

- `phone/.../ui/home/SuggestionFeed.kt:72-78`: cards are rendered from live sources, but only on `store.changes`, a 10 s tick (`TICK_MS`), the switches or gate-close events.
- I found a Forget-time withdraw only for pins (`ProactiveGraph.pinsChanged`). DeletionGraph says "a Forget withdraws records whose sources are gone". **UNVERIFIED:** whether a memory or frame Forget bumps `store.changes` at once.
- **Worst case:** a card rendered from a forgotten source stays on Home for up to 10 s.
- **Fix, if it is not already wired:** merge `db.tableChanges("memory","frame")` into `triggers`.

## Checked and fine

- **Home** (`HomeViewModel.kt:83,85-86,88-100`): the asks and nudges feed builds each card from its own row's `redacted` flag. `lastAnswer` is a single `asks.observe`. `photosToday` is a count. `reply` holds only fixed reply text or a notice.
- **Lists** (`ListKind.kt:134`): paging reloads on `listChanges` table sets that include the cited tables.
- **Photo search** (PhotoSearchDeps.kt:53): refreshes on frame, frame_meta, memory and frame_source changes, debounced 400 ms (bounded).
- **Mark preview** (`observeMark`): one joined query, so it reloads when the caption memory goes.
- **Settings combines** (SettingsRoot, Capture, Signal, Sources, Privacy, Zones, Roles, Endpoints, Prompts, FlowView, Nexus, Onboarding, Canvas): prefs, endpoint and prompt config. No memory, photo, ask, nudge or mark content.
- **`EmbeddingSpaces.rollbackAvailable`** (combine of `observeSpaces` with a ticker): a boolean.
