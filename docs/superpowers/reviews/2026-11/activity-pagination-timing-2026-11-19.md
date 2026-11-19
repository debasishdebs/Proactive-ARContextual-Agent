# Activity pagination: 100k timing, laptop half (2026-11-19)

Test: `android-companion/services/memory/src/test/java/com/rokid/context/memory/db/ActivityPaginationPerfTest.kt` (Robolectric, in-memory Room v17, real keyset DAOs). Run it with `:services:memory:testDebugUnitTest --tests '*ActivityPaginationPerfTest' -i` and grep `PAGINATION-PERF`.

Hardware: this Mac, Apple M2, 8 GB RAM, macOS 26.2, OpenJDK 17.0.19, Robolectric sdk 34 (native SQLite, not the Android one), one Gradle run with workers.max=2.

Data: 100,000 synthetic memories over 100 days (1,000 a day; every 50th is NEEDS_REVIEW, so 98,000 are visible), 80,000 frames (80% of moments have a photo), 5,000 answers and 10,000 developer eval asks that are newer than every answer (worst case for the eval skip). No personal content.

## Numbers (after the small fix below)

| What | Measured | Bound asserted |
|---|---|---|
| First page of 100 | cold 3.2 ms, warm median 1.5 ms, max 2.3 ms | cold < 2 s, warm < 500 ms |
| Row 1,500 (15 keyset pages) | 17 ms total | < 5 s |
| Walk all 980 pages (98,000 rows, no duplicate, none missed) | 2.8 s total, slowest page 16 ms | slowest page < 1 s |
| Jump to the oldest rows by cursor | 5.5 ms | none |
| Hour counts for the newest day (980 moments) | 1.1 ms | < 2 s |
| Busiest hour, page 1 of 25 / page 2 | 0.8 ms / 0.6 ms | < 1 s |
| Hour counts for all 100 days (user expands every day) | 85 ms total | none |
| Answers first page behind 10,000 newer eval asks | 2.0 ms | < 1 s |
| Heap growth over a full walk (98,000 ids kept for the duplicate check, one page of entities) | +8.6 MB | < 300 MB |

Query plans (printed by the test): all three page queries use an index range (`index_memory_createdAt`, `index_ask_ts`, frame by primary key); the test asserts no full table scan. The tail `TEMP B-TREE` is the id tie-break sort of same-millisecond groups only.

## Finding and fix (small, safe)

Before the fix, the keyset predicate `ts < :cKey OR (ts = :cKey AND id < :cId)` did not bound the index range, so a deep page walked and filtered every newer row first: the cost of a page grew with its depth (full walk 9.6 s, slowest page 28 ms; roughly 3.4x slower). Fix: add the implied `AND <key> <= :cKey` to the five keyset queries (frames, memories list view, answers, nudges, marks). The result set is identical (it is implied by the original predicate), no schema change, Room stays v17. `ActivityKeysetDaoTest`, `MarkByIdsTest`, `ActivityIdsDaoTest`, `FeedRepositoriesTest` stay green. `momentPageIn` is unchanged: its key is the display time (frame `ts`), not an indexed column, so it still scans the day's `createdAt` range (one day, about 1,000 rows here; design bound is a few thousand).

No N+1 or unbounded in-memory list found in the page DAOs. Not exercised here: the marks page (correlated subquery per row, at most 100), and the older non-paged paths the design lists (`MarkRepository.byIds` full scan, `RepoActivityData.asks` per-id reads), which belong to tasks A2/A4.

## What this does NOT show

- Real phone CPU and storage: a phone core is several times slower than an M2 and flash I/O is slower than RAM. The design target is p95 < 30 ms a page on the device, including the summary build. Treat the laptop numbers as a floor, not a prediction.
- Encrypted SQLCipher cost: the test uses plain in-memory SQLite, not the encrypted on-disk database with page decryption, so cold reads on the phone will be slower.
- Compose scroll jank, composition cost of rows, image decoding, thumbnails and the summary-factory work per row (the test measures the DAO only, not `RecordListSource`, `KeysetPager` or the screen).
- Cold file cache, WAL checkpoint behaviour, concurrent writers (capture running) and thermal throttling.
- Heap: JVM heap on a laptop, not the Android process budget.

## What the user must still check on the phone (design checklist section C)

1. Seed or import about 100,000 moments (or use the seed helper once A9 lands) and open Activity > list view: time to the first rows, and that nothing stutters.
2. Scroll to row 1,500 and further, then open a preview and come back: the position is kept and scrolling stays smooth.
3. Open the broken-down view: expand today, a busy day and an hour; page through "1 of N"; check the time to open each.
4. Forget a row from inside an open hour page: the row and the counts update once, without a jump.
5. Time the full 100k seed (design section 4, device list) and note the phone model and whether the database is encrypted.
6. Compare against the section 3 targets: a page under 30 ms p95, an hour page under 20 ms, the eval-ask skip under 50 ms.
