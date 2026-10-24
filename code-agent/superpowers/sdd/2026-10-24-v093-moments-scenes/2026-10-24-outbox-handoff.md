# Handoff: cleanup outbox drainer (branch feat/outbox, from v0.9.3 tip 1c4538a8)

Implements item 1 of "Still to build" in `docs/superpowers/specs/2026-10-24-moment-scene-design-decisions.md` section 6: rows in `cleanup_outbox` become real deletions on the PHONE. Glasses and laptop helper transports are NOT built; their ports and the "not reachable yet: keep the row, retry later" behaviour are.

## Built
- `OutboxDrainer` (`services/orchestration/.../canvas/outbox/`): pure logic over ports `OutboxStore`, `PhoneFilePort`, `GlassesCleanupPort`, `HelperCleanupPort` (all in `OutboxPorts.kt`).
  - A row is removed ONLY after its port answers DONE (file confirmed gone; a file that is already missing is DONE). A crash between the delete and the row removal leaves the row; the next run finds the file gone and removes the row.
  - Phone rows (`PHONE_FILE`) first, then the rest, each by id. At most 50 rows attempted per run (`MAX_ROWS_PER_RUN`).
  - Failure keeps the row and charges an attempt; retry only after `backoffMs(count)`: 5 min doubling to a 6 h cap. A row is never dropped; past 20 attempts it is only counted as `stuck` in the log.
  - NOT_REACHABLE (port probe false, or the call says so mid-run) keeps the row, charges NO attempt. Probe once per port per run. `ALL` (Delete all) needs glasses AND helper; each reachable half runs, the row stays until both confirm.
  - Unknown target or unreadable payload: never acted on, kept, counted as `malformed`.
  - Logs one line per run through `PcLog.port("outbox")`: counts only (removed, failed, deferred, waiting, malformed, stuck, left). No ids, paths, ranges or exception messages (a test pins that a path never reaches the log).
  - A `Mutex` makes overlapping runs serial.
- `PhoneFileCleanup` (production file port): deletes only when the canonical path is strictly inside `filesDir/rokid_inbox` or `filesDir/rokid_archive` (`RokidFrameInbox.FRAME_DIRS`); refuses relative paths, `..`, symlink escapes, sibling dirs with a shared name prefix, and directories; confirms the file is gone after the delete.
- `RoomOutboxStore`: pending rows, `removeOutbox(id)` (new DAO query, `MomentDao`), attempt book in ONE `canvas_meta` value `outbox_attempts` (`id:count:lastAtMs;...`, ids and numbers only). NO Room schema change (still v11).
- `CleanupPayload.parseOne` / `parseRange` (memory module, next to the builders): exact inverses, round-trip tested including quotes, backslashes, control characters, unicode.
- `OutboxTriggers` (production wiring, like `MomentTriggers`) and the hook: `MomentCadenceWorker` (the existing 15-minute unique periodic work) drains FIRST (a deletion comes before any rebuild), in its own try/catch so a drain problem never blocks the Moment/Scene rebuild. No new worker, no service. Glasses and helper are bound to `NotReachableGlasses` / `NotReachableHelper` (probe false), so their rows simply stay.
- Delete all: `WipeDao` still keeps unacknowledged rows and writes the `ALL` row; a test wipes, then drains: the queued phone file goes, the `ALL` row waits and goes once glasses and helper confirm.

## Files
Main: `canvas/outbox/{OutboxPorts,OutboxDrainer,PhoneFileCleanup,RoomOutboxStore,OutboxTriggers}.kt`, `canvas/moment/MomentCadenceWorker.kt` (hook), `memory/db/MomentDao.kt` (`removeOutbox`), `memory/db/MomentEntities.kt` (`parseOne`, `parseRange`).
Tests: `canvas/outbox/{OutboxDrainerTest,PhoneFileCleanupTest,OutboxRoomTest,OutboxWiringTest,OutboxFakes}.kt`, `memory/db/CleanupPayloadTest.kt`.

## Mutations (each turned a test red, then restored)
M1 failed treated as confirmed; M2 missing file not done; M3 backoff ignored; M4 no per-run bound; M5 id order instead of phone-first; M6 unreachable charges an attempt; M7 no root guard; M8 ALL needs only glasses; M9 malformed row removed; M10 ack instead of delete (re-queue of the same path swallowed); M11 log leaks payload; M12 worker not hooked; M13 no per-run probe cache; M14 stale attempts not pruned; M15 relative path accepted; memory: inverted range accepted. M15 first stayed green (the guard was redundant), so `aRelativePathIsRefusedEvenWhenItResolvesInsideARoot` was added.

## My decisions and reasons
1. Rows are DELETED on confirm, not acked: an acked row keeps the unique (target, payload) key and `insertOutbox(IGNORE)` would swallow a later re-queue of the same path. `ack` is left in place, unused by the drainer.
2. Attempt state in `canvas_meta`, not a new column: avoids a Room v11->v12 migration for a counter. Cost: the book is one string; fine for hundreds of rows. If the outbox can grow to thousands, move it to a column (additive v12).
3. Never give up on a row: a privacy deletion must not expire; stuck rows are surfaced as a count.
4. Phone roots limited to the two frame folders: the only phone files the outbox writers name are frame JPEGs and their `.meta.json` sidecars. A row naming anything else is refused (kept, retried at the cap rate, counted failed).
5. Drain first in the worker, separately guarded.
6. `ALL` is glasses + helper only on the phone side: the phone's own part of Delete all is `MemoryWipe` (it has its own leftover retry at the next start).

## NOT done
- Glasses transport and helper transport (see below). Production binds the not-reachable ports.
- Only the cadence worker triggers a drain (up to 15 minutes after a Forget). The existing listeners (`ForgetListeners`, `CanvasForgetCoordinator`) still delete files at once; the outbox is the durable net. An "after Forget" kick is a one-line `OutboxTriggers.get(ctx).drain()` call if wanted.
- Writers: only `MomentForget` and `FrameIntake` (fenced late frames) queue rows. `ForgetService.forgetMoment/forgetFact` and retention expiry (not the Moment Forget path) do not queue, they rely on the listeners. Helper copies of forgotten items have no row kind of their own (helper jobs are removed in `forgetRangeTx`); only `ALL` reaches the helper port.
- No settings UI, no metrics screen. Not run on a device or emulator (nothing installed).

## What the glasses side needs (implement `GlassesCleanupPort`)
Transport message(s) from phone to glasses, each idempotent on the glasses, answered with an explicit confirmation (DONE only after the glasses report it; missing = done), plus a cheap reachability probe (session up and hello received):
- `deleteFrame(wireFrameId)`: frame file by wire id in glasses `TraceStorage`/frame store; also from any unsent queue.
- `deleteTrace(traceId)`: the sealed trace and its transferred marker, respecting the cleanup fence.
- `deleteRange(fromMs, toMs)`: phone-clock range: traces, queued marks, queued frames inside it. Needs the clock mapping the trace clock fix already uses; unmapped glasses data must be treated conservatively.
- `wipeAll()`: Delete all: today the NEW deletion epoch already reaches the glasses via `collect.refresh()`; the port should confirm the glasses applied that epoch (hello with the new counter) before DONE.
- Must not raise PROTOCOL_VERSION: use an existing control channel or an additive optional field, then decide.
- Reply path must carry no user content in logs (ids only inside the payload).

## What the helper side needs (implement `HelperCleanupPort`)
- `reachable()`: supervisor/session up (HelperJobService).
- `wipeAll()`: delete every staged input, job record, journal entry and container volume the helper holds for the phone; confirm with a count. Idempotent.
- Later row kinds, if per-item helper cleanup is wanted: a new `CleanupTarget` (e.g. `HELPER_JOB`) written by the Forget paths, plus a port method; nothing writes one today.

## Merge steps
1. Branch `feat/outbox` (3 commits after 1c4538a8; list below) merges cleanly into the v0.9.3 line: it touches `MomentDao`, `MomentEntities.kt`, `MomentCadenceWorker.kt` and new files only.
2. `./gradlew --no-daemon -Dorg.gradle.workers.max=2 --offline :services:memory:cleanTestDebugUnitTest :services:memory:testDebugUnitTest :services:orchestration:cleanTestDebugUnitTest :services:orchestration:testDebugUnitTest` (then `:phone:`), rerunning flaky-looking failures alone.
3. No schema, protocol or manifest change; no backup needed beyond the usual before an install.
