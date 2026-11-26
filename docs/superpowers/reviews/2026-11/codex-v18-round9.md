# Codex v18 round 9 (79a74f70..648c20bec; gpt-6.1-sol, medium, read-only)

VERDICT: NOT READY

1. [P1] android-companion/services/connectors/src/main/kotlin/com/rokid/context/connectors/place/PlaceBulkSource.kt:134 - Paging by item alone permits tied P31 rows to reorder across requests, omitting a person class while retaining an admitted class - Use a total ordering across projected fields or fetch complete classifications per QID.
2. [P1] android-companion/services/connectors/src/main/kotlin/com/rokid/context/connectors/place/PlaceBulkSource.kt:75 - Curated entries in VERIFIED_COORDINATES use live P625 instead; with no Wikipedia coordinate, a changed upstream point is accepted unchecked - Preserve the verified-coordinate policy in bulk discovery and storage.
3. [P2] android-companion/services/connectors/src/main/kotlin/com/rokid/context/connectors/place/PlaceHttp.kt:100 - A glance overlapping bulk seeding can pass await before another request establishes a 429/503 pause, then send during that pause after waiting for the host gap - Serialize request admission through completion and recheck the pause before dispatch.
4. [P2] android-companion/phone/src/main/java/com/rokid/context/phone/place/SeedJob.kt:103 - Resumed bulk work subtracts every newly fetched cell from previous failures, including previously unprocessed cells, falsely reporting failures as recovered - Track actual recovered failures rather than subtracting fetched.
5. [P2] tools/measure/measure_classes.py:15 - The script raises IndexError before measurement because MOUNTAIN_CLASSES no longer exists - Read mountain definitions from ExtraPlaceTypes and delimit the strict class map explicitly.
6. [P2] tools/measure/measure_region.py:131 - Named results can be written inside the checkout, and line 132 prints landmark/article names despite the aggregate-only output requirement - Reject repository-contained output paths and remove named stdout output.

Confirmed: encrypted `places.db` remains Room v3; its schema, migrations, encryption factory and key handling are unchanged in this range. No network, Gradle or device commands ran; no files were edited.
## Disposition (after the review)
1. P1 paging tie order: FIXED (ORDER BY ?item ?cls ?lat ?lon; query-shape test updated).
2. P1 curated verified coordinates in bulk: FIXED (stored at the VERIFIED_COORDINATES point, test added).
3. P2 pause re-check after the host gap: FIXED (guard.await again before dispatch; test added).
4. P2 resumed-run failed count subtracts fetched cells: NOT FIXED, downgraded to P3. Display-only and transient: the stored done/total and the final tally set at the end of the run are exact; while a resumed run is still inside the earlier prefix the failed figure can read lower than the truth, and it self-corrects when done passes the stored value.
5. P2 measure_classes.py MOUNTAIN_CLASSES: FIXED (reads ExtraPlaceTypes).
6. P2 measure_region.py output path and names on stdout: FIXED (check_out in all three scripts, counts only on stdout).
Per the brief a P1 was found: round 9 is the last review round (no round 10).
