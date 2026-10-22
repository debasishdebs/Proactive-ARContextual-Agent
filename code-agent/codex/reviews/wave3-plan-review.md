1. **Critical — Task 3: saved photo answers survive Forget.** `ForgetService.cascade()` redacts asks by memory IDs; it never searches for `frame:<id>`. Updating `ForgetFence` only protects answers still being generated. Extend explicit Forget to redact photo-citing asks and scrub their proposals/alarms. Test **answer commits → Forget moment → answer and proposal redacted**, including export output.

2. **Critical — Tasks 1, 3: expired, undescribed photos cannot be forgotten.** Such photos retain meta/vector but have neither a frame row nor memories. `forgetMoment()` immediately returns `EMPTY` for that combination, leaving searchable data and a live `frame:` citation. Recognize metadata/vector-only moments; delete their retained records and redact citations. Test expiry without a caption, followed by Forget.

3. **Important — Task 2: the retention promise fails for SUMMARIZING members.** Direct description leaves them `IN_CLUSTER`; cluster completion changes them to `CLUSTERED`. A nonrepresentative then qualifies for one-day deletion despite its new FACT provenance. Preserve successful user-description status through cluster settlement, and test settlement followed by retention.

4. **Important — Task 2: description and retention race.** Retention can select a due NEAR_DUP, description can save/promote it, then `expirePhotos(ids)` deletes it without rechecking skipped eligibility. Save and promote atomically, and revalidate skipped eligibility inside the expiry transaction. Add an interleaving test with retention paused after selection.

5. **Important — Task 2: `extractNow(): Set<String>` loses necessary outcomes.** It cannot distinguish failed extraction, lost contention, wipe, or successful extraction with no memories. Routing unsuccessful frames directly into the no-claim path can duplicate calls and bypass another extractor. Return per-frame typed outcomes; reread state after contention and preserve `Wiped` distinctly from stale claims.

6. **Important — Task 2: eight hex characters permit destructive ID collisions.** `PipelineRecords.appendFact()` upserts by ID, so a repeated prefix overwrites earlier facts/captions. `KeyframeGraph.factPrefix()` explicitly documents a previous truncated-ID collision. Use full UUIDs and test two descriptions with a forced identifier collision.

7. **Important — Task 1: first target does not necessarily mean ACTIVE.** `SpaceImageSpaces.targets()` can return only BUILDING when ACTIVE is text-only or unavailable. Select `firstOrNull { it.active }`. Test a BUILDING-only list; it must produce `NO_IMAGE_SPACE`.

8. **Important — Task 1: the caption pool is silently capped at 20.** `MemoryRetriever.retrieve()` calls `RetrievalSettings.clamped()`, which limits `topK` to 20. Passing 60 cannot implement R3, and the fake retriever hides this. Add an internal retrieval-limit seam independent of UI settings, with a real-retriever test where photo-backed hits rank below twentieth.

9. **Important — Tasks 1, 3: expired-photo times misrepresent capture time.** `frame_meta.createdAt` is receipt time; handoff §6 records buffered deliveries. Showing it as capture time can produce incorrect “where/when” evidence. Preserve corrected capture time before expiry, or explicitly show receipt/unknown time. Test capture and receipt several hours apart.

10. **Important — Task 3: images lack a reliable citation mapping.** `photoImages(...).values.take(2)` trusts map iteration order; missing/unreadable photos also shift image positions relative to prompt lines. Assemble images in `PhotoCite` order and explicitly associate each attached image index with its cite ID. Test reversed map order and an unreadable first photo.

11. **Important — Task 4: cached search results have no invalidation contract.** Forget, expiry, or description can leave old captions, decoded thumbnails, paths, and ✓ flags on screen or in its retained ViewModel. Observe relevant Room changes and re-enrich/remove results; reject stale search completions. Test Forget while visible, return from Forget, and successful description updating ✓.

12. **Important — Task 5: the settle interval starts before hang-up.** Stamping only non-NORMAL samples permits reopening less than 15 seconds after a NORMAL transition. Track transitions and stamp entry into NORMAL after a call; use a monotonic clock. Test hang-up immediately before the next poll, rather than supplying an already-computed duration to `callIdle()`.

13. **Important — Task 6: reboot detection is incomplete.** After reboot, current uptime can exceed the old saved uptime, so stale work/rest state is restored. Persist a boot identity alongside the snapshot, such as [`Settings.Global.BOOT_COUNT`](https://developer.android.com/reference/kotlin/android/provider/Settings.Global#BOOT_COUNT), and discard mismatches. Test a reboot whose new uptime exceeds `savedAtElapsedMs`.

14. **Important — Task 8: PID classification cannot establish caller identity reliably.** Android’s [proc visibility restrictions](https://android.googlesource.com/platform/system/core/+/c39ba5a) can make a live foreign PID appear absent; PID reuse can identify an unrelated process. Treat inaccessible/absent callers as UNKNOWN unless independently verified, and report UNKNOWN separately. Also require a fresh exit timestamp before counting it; test hidden, reused, and stale PIDs.

15. **Important — Task 9: the wearing-log fix targets the wrong module.** `WearingStatus.kt` and its tests live in `:services:glasses-link`, which global constraints forbid editing and the test commands omit. Correct the file/module ownership and run that module’s tests. Otherwise the “site missing → skip” instruction leaves the free-text log intact.

16. **Important — Task 9: the proposed tests miss the failures.** `CanvasRouter` already adds headers to its catch responses; D26’s uncovered catch is in `CanvasServer.Daemon.serve()`. Exercise an adapter/conversion failure through the server. Execute D39/D36 in the JS harness with malformed fragments and fake timers; source scans cannot prove recovery, backoff reset, cancellation, or stopping on authorization errors.

17. **Important — Global constraints: v5 is already reserved.** Both documents suggest v5 for an unexpected wave-3 migration, colliding with wave B. Reserve v3 canvas MVP, v4 wave 2, v5 wave B, and canvas slice 2’s allocated next version. Any wave-3 migration must follow all reservations; update both builders, exported schemas, and upgrade tests.

18. **Important — Task 10: “this wave has no migration” does not establish device safety.** The handoff permits one merged install over the installed phone build, which can cross earlier schema versions. Check the installed schema first; follow the handoff’s SQLCipher migration test on a copy and use a consistent encrypted backup. Coordinate retention/ingest when comparing counts.

19. **Minor — Tasks 2, 4: navigation and scene-selection contracts are incomplete.** `PhotoTile` lacks the caption memory ID required by expired-photo “Open”; some expired hits have no caption at all. Carry a nullable ID and define disabled behavior. “Medoid by frame_meta” also needs an explicit algorithm—the table has no medoid field.

20. **Minor — Tasks 8, 10: the “other force-stops” device check is unreachable.** Task 8 keeps the count glasses-local, while phone Diagnostics receives only the existing fields; `summary()` currently has no production caller. Publish count-only local diagnostics and check those, or explicitly approve additive transport/UI work.

21. **Minor — Tasks 1, 7: two tests encode misleading assumptions.** Vector `rowid DESC` means latest vector writes, not newest captures; reindexing breaks the “newest N photos” wording. Specify the intended ordering. For Task 7, compare `decide()` and `onStart()` on separate gate instances: both consume the one-shot pending state.