# LocalModelsPageTest flake: root cause (2026-11-07)

Branch feat/v099-watch. Only the test changed; there is no production defect.

## Root cause (two, stacked)

1. **A hang added by e2a8b265 (fails every time).** tearDown wrote the `shown` mutableState and then called `compose.waitForIdle()` in every
   test, including tests that never set content. When no compose view exists yet in the Robolectric sandbox (this class's first test,
   `rollbackAfterNomicSwapRebindsBge`, runs no UI), GlobalSnapshotManager has not started. Nothing applies the global-snapshot write,
   `Snapshot.current.hasPendingChanges()` stays true, and waitForIdle spins for 60 s and then throws `AppNotIdleException` from
   tearDown:101. Running the class alone reproduced this 3 out of 3 times, and a jstack during the hang showed the main thread
   inside `RobolectricIdlingStrategy.runUntilIdle`. The tests without UI also each waited about 2 s for a compose root that never appeared.
2. **The original race (load-dependent, so a different test failed each run).** The page reloads from Room on Room's process-wide arch_disk_io pool.
   Disposing the page cancels its coroutines, but a query that was already running can still reach the connection pool after
   `TestGraph.close()`, which gives "connection pool has been closed". The rollback tests also called `TestGraph.fresh` a second
   time, which left the setUp DB unclosed and `ai` pointing at a different DB from the one `spaces` used.

## Fix (LocalModelsPageTest.kt only)

- setUp builds the in-memory DB with this test's own query and transaction executor. One `graph` per test; the tests no longer call `fresh` a second time.
- tearDown: only if a page was shown, it flips `shown` inside `Snapshot.withMutableSnapshot` and waits for idle. It then shuts the executor down, asserts
  `awaitTermination(10 s)`, and closes the DB. No sleeps or retries were added, and no assertions were weakened.
- The class alone now takes 5 s (it took 79 s).

## Verification

Full `:phone:testDebugUnitTest --rerun`, three runs in a row: 2160 tests, 0 failures, 0 errors, 0 skipped each time.
