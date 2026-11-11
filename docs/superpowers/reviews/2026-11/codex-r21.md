**NOT READY — 5 P1 findings.** Reviewed `f84ab82e` on `feat/v099-watch`.

1. **P1 — Common record lists retain deleted text.** [ListKind.kt:79](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/ui/list/ListKind.kt:79), [ListScreen.kt:104](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/ui/list/ListScreen.kt:104).

   `RecordListSource` inherits an empty `changes` flow; displayed rows remain snapshots. **Repro:** open the Memories or Moments list containing an expired SHORT memory. Keep it open while retention deletes the memory. Its text remains visible indefinitely. Answers and Nudges lists similarly retain text after source-expiry redaction.

2. **P1 — Photo previews retain deleted JPEGs as decoded images.** [PreviewSection.kt:161](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/ui/preview/PreviewSection.kt:161), [FrameImages.kt:75](/Users/<user>/Desktop/rokid/pca-v098/android-companion/core/ui/src/main/kotlin/com/rokid/context/core/ui/components/FrameImages.kt:75).

   Paths load once per photo ID; decoded images update only when path or size changes. **Repro:** open a photo eligible for retention deletion, with its caption configured to survive. Run retention while the preview stays open. The frame row and JPEG disappear, but the decoded photo remains visible. Frame previews also have no database change observation.

3. **P1 — Canvas pages retain forgotten outputs, answers and photos.** [views.js:642](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/assets/canvas/views.js:642), [asks.js:12](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/assets/canvas/asks.js:12), [app.js:129](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/assets/canvas/app.js:129).

   Completed runs stop polling; Ask pages fetch once; image blobs persist until navigation. **Repro:** open a completed run or Ask debug/history page in the phone’s canvas. Let retention delete its source photo/memory and scrub the corresponding database content. The page keeps displaying its previous content indefinitely. Reproduced with the repository’s fake DOM/API harness: zero subsequent requests.

4. **P1 — Glasses HUD keeps displaying redacted answers.** [VoicePipeline.kt:120](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/voice/src/main/kotlin/com/rokid/context/voice/VoicePipeline.kt:120), [VoicePipeline.kt:303](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/voice/src/main/kotlin/com/rokid/context/voice/VoicePipeline.kt:303).

   Cached answer state has no redaction/deletion observer. **Repro:** mirror a typed answer to the glasses, then forget its cited source before the 30-second hold expires. Although the stored answer is redacted, the HUD retains the original text; hub broadcasts continue carrying it until the timer clears.

5. **P1 — A fired reminder can repost forgotten text after cleanup.** [ReminderReceiver.kt:53](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/connectors/src/main/kotlin/com/rokid/context/connectors/actions/ReminderReceiver.kt:53), [ReminderReceiver.kt:68](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/connectors/src/main/kotlin/com/rokid/context/connectors/actions/ReminderReceiver.kt:68).

   The database check and notification posting are not serialized with deletion. **Repro:** pause a fired reminder after its live-proposal check, complete Forget of its source—or Delete all—including notification cancellation, then resume. It posts the old title/note from the alarm Intent afterward, with no later cleanup scheduled.

The **r20 Diagnostics fix is correct** on static and SQLite inspection. No regression introduced by `30323a35..f84ab82e` was found; these five blockers predate that range. Accepted over-linking was excluded. Frozen values remain unchanged.

Verification: static tracing, in-memory SQLite checks, **41 existing JavaScript tests passed**, plus targeted canvas reproductions. **No Gradle or Android device tests run; no files modified.**