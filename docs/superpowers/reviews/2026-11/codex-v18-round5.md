# Codex review round 5 (295be471..00d0651c, gpt-6.1-sol, medium)

VERDICT: NOT READY

1. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/dumpimport/ImportStores.kt:50 - Delete all can finish after this check but before the write/ingest; FrameIntake has no request-generation fence, and line 62 deletes only the file, leaving newly inserted frame/meta/state rows after the wipe - check the request generation inside the insert transaction and coordinate file publication with the wipe.

2. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/dumpimport/ImportStores.kt:49 - A live ingest can create the same archive path after `exists()`; AtomicJpegWriter’s rename replaces it, then the import’s duplicate-result cleanup deletes the winning photo - reserve ownership atomically and publish without replacement; delete only files owned by this attempt.

3. [P2] tools/push_import.py:181 - Every card resend has `hasPhoto=False`, no photos and no marks; ImportCore.kt:250 therefore rejects it as `empty`, so cards needing subsequently imported pins remain missing. The new fake API accepts everything and masks this failure - support a validated card-only resend for existing Moments and test it through the real parser.

The added Kotlin tests check meaningful database effects, but do not cover the production frame races. The extracted installer comparison correctly refused A→B versus A→C; no new installer bypass, uninstall path, or secret/log leakage was identified in the scoped changes. Shell syntax checks passed. No Gradle or device commands ran; no files were edited.

Recommended device checks:

- Barrier-test Delete all between generation check, file publication and DB insert; verify no surviving rows, files or rebuilt Moments.
- Race live capture against import of the same frame ID; verify the winning photo’s bytes and path survive.
- Restore cards referencing later-imported pins, resend twice, then repeat after Forget, retention expiry and with pause zones enabled/unreadable.
- On disposable devices, verify valid rotation preserves encrypted data and divergent lineage refuses without changing installed apps.
