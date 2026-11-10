**NOT READY — 1 P1.**

1. **P1 — Diagnostics keeps displaying deleted memory text.** [Diagnostics.kt:177](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/ui/settings/Diagnostics.kt:177), [Diagnostics.kt:355](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/ui/settings/Diagnostics.kt:355).

   **Concrete repro:** Seed a live photo and a linked, OK, non-derived SHORT memory written before `1794085517000`, with `imageDelivered = null` and an expired SHORT retention deadline. Open Diagnostics until its suspect snippet appears. Keep the screen open while `RetentionWorker` runs and deletes that memory. The database row disappears, but Diagnostics continues displaying its text indefinitely.

   Suspects are cached in `extras`; database invalidation and deletion notifications do not refresh them. Subsequent stats emissions reuse that snapshot. Make the suspect list observe database changes and invalidate cached text when its memory is removed.

No additional P1 found. The r18/r19 provenance fixes are present. Migration checks found exactly three nullable columns, matching v16 structure and generated debug/release identity hashes; both production migration paths include v15→v16, with no destructive fallback. Frozen values remain unchanged.

Verification: static tracing and in-memory SQLite checks. **No Gradle or real SQLCipher device upgrade executed.**