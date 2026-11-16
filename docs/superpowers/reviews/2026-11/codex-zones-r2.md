Remaining findings:

1. **P1 — Mark capture still uses the camera inside a zone.** When the zone activates during the hold, the callback discards frames but leaves the camera open for the remaining 2–4 seconds. r1-2 is only partially fixed. [MarkPhotoCapture.kt:82](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/glasses/src/main/java/com/rokid/context/glasses/mark/MarkPhotoCapture.kt:82)

2. **P1 — Recovery protection races reconnection.** `beginRestart()` only clears `ready`; reconciliation runs asynchronously. Meanwhile, an exact empty-pause sync can release the glasses’ approach hold, and admission has no restart barrier. Capture/admission can reopen before NO_FIX protection exists. [HubGraph.kt:268](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/HubGraph.kt:268)

3. **P1 — Failed persistence loses historical enforcement on exit.** If pause creation keeps failing until two exit fixes arrive, reconciliation clears `ZoneHold` without retaining the missed interval. Buffered inside-zone frames subsequently pass admission. [ZoneController.kt:258](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/zone/ZoneController.kt:258)

4. **P1 — Clock rollback can go undetected.** The guard samples at interval creation/admission, not continuously. Example: entry observed at 1000; clock advances to 1600, rolls back to 1100; an early buffered inside frame shifts below the interval start. Because 1100 exceeds the recorded peak, widening remains zero. Clock-state commits also ignore failure. [ZoneHold.kt:33](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/zone/ZoneHold.kt:33)

5. **P1 — Configuration persistence still fails open.** Android SharedPreferences updates memory even when disk `commit()` returns false; the next `zones()` rereads that uncommitted value and overwrites `good`. The failure fake suppresses the underlying commit entirely, masking this behavior. Additionally, valid JSON containing invalid zones is silently filtered into an unprotected empty list. [ZoneStore.kt:28](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/zone/ZoneStore.kt:28)

6. **P1 — Missing recovery state leaves the outage uncovered.** Missing memo data is considered readable, and a missing clear time starts protection at recovery “now.” Process death before the first successful clear-state save therefore still admits earlier outage frames. [ZoneController.kt:213](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/zone/ZoneController.kt:213)

7. **P1 — Failed zone publication is not retried.** `lastEnforce` advances before `publish()` succeeds. Following ticks see unchanged state and no record changes, so connected glasses can continue capturing until another event republishes settings. [ZoneController.kt:265](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/zone/ZoneController.kt:265)

8. **P2 — Bulk replacement bypasses inside-zone mutation guards.** `replaceAll(emptyList())` succeeds while inside or before initialization and ends protection. No production UI caller found, but the public recovery API defeats r1-11’s invariant. [ZoneController.kt:153](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/zone/ZoneController.kt:153)

r1 status: **1, 3, 7, 10, 12–14 addressed locally; 2, 4–6, 8–9 remain incomplete; 11 has the bulk-replacement bypass.** Ordinary resume paths preserve zone records. Historical admission checks capture time before mark allowance; genuinely pre-entry frames remain admissible with complete history. No automatic coordinate leakage found in the reviewed changes. No additional P3 findings.

Validation: existing compiled artifacts passed 41 evaluator methods and 16 mark-photo tests. No fresh Gradle build or device validation in this read-only workspace.

NOT READY