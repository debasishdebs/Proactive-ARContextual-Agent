**NOT READY — 3 P1 findings, 2 P2.** Reviewed `815c1552..263a9252`; HEAD matches `263a9252` on `feat/v099-watch`.

1. **P1 — Canvas loses transitive photo provenance after transformations.** [Executors.kt:59](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/Executors.kt:59), [CanvasRunner.kt:754](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/CanvasRunner.kt:754).

   Repro: select photos A/B outside each other’s Forget radius. Run `Vision(batch=2) → Prompt → Summary`, with an A-item fact citing A+B. Prompt converts that fact to JSON; the subsequent item retains neither B nor the preceding request’s sources. Hold Summary at router admission, Forget B, then release. Its guard checks only A and sends B-derived text. Sources must travel with outputs across node transitions.

2. **P1 — Derive can reload empty provenance for already-loaded private text.** [DerivePass.kt:135](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/DerivePass.kt:135).

   Repro: pause after the final snapshot check at line 133, then Forget the source photo or run Delete all. The cascade removes Moment parts. `inputs.sources()` subsequently returns empty sets while `lines` still contains the old text. [ModelRouter.kt:194](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/ai/src/main/java/com/rokid/context/ai/ModelRouter.kt:194) bypasses upload protection for empty sources and sends it. Capture text, provenance and wipe generation together.

3. **P1 — Excluded citations can erase the newly described photo’s grace period.** [ForgetDao.kt:146](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/memory/src/main/java/com/rokid/context/memory/db/ForgetDao.kt:146).

   The grace timestamp still uses **every citation**, rather than qualifying descriptions. Repro: photo deadline day 10, excluded Canvas citation created day 1, first qualifying night description day 20. Retention computes `max(10,1)+3=13` and deletes immediately on day 20, instead of retaining until day 23. Reproduced using the actual SQL. This prematurely removes the photo.

4. **P2 — Empty night descriptions bypass retry limits when excluded citations exist.** [FrameDaos.kt:308](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/memory/src/main/java/com/rokid/context/memory/db/FrameDaos.kt:308).

   Repro: queue a Canvas-only-cited photo, then accept an extraction producing no facts/captions. `noteUncitedDescribed` still requires zero citations, so attempts and cooldown remain unchanged. The photo becomes eligible again every night and never reaches the five-attempt parking rule. Reproduced using the actual SQL.

5. **P2 — The queued-Ask wipe test now passes without completing a wipe.** [DeleteAllFenceTest.kt:96](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/test/java/com/rokid/context/work/DeleteAllFenceTest.kt:96).

   ANSWER now holds the upload slot. Its fake provider calls Delete all synchronously, which waits for that same call, then throws `UploadInProgress` after 30 seconds. The request fails; the empty-Ask assertion passes without exercising the intended wipe fence. Use separate coroutines and assert wipe completion.

The original cluster-member authorization hole is fixed. All 19 production request constructors above providers declare sources, and nonempty sources are rechecked inside the permit under the upload slot. Ask/debug, Nudge, resummarize and queue source-memory coverage is present; the remaining dispatch failures are above.

The new SQL assertions distinguish the original regressions, but the declaration test checks syntax, not complete provenance. The reprocess generation-only test is sound for its write fence. No new permanent production deadlock found: delete waits are bounded and dispatcher release uses `finally`.

Room v15, protocol 1 and trace schema 1 remain unchanged. No Gradle run; verification used static tracing and in-memory execution of the actual SQL.