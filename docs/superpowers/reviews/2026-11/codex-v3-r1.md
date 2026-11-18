Found **6 P1 issues**:

- **Driving fails open when the context source is OFF.** [ContextApp.kt:43](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/ContextApp.kt:43) treats “car source disabled” as “not driving.” With no vehicle signal, state is unknown and must suppress would-shows under ruling 10.

- **Gates can close before commit without stopping recording.** [ProactiveEngine.kt:119](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/ProactiveEngine.kt:119) rechecks provenance and the zone ticket, but not master/family, pause, wear, driving or quiet hours after suspending work. Changing these through `testBeforeCommit` still permits `store.add`. Revalidate at commit.

- **Forget dependencies are incomplete.** [PlaceRecall.kt:58](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/generators/PlaceRecall.kt:58) records only the latest earlier memory and first mark, despite deriving visit/mark counts from more sources. Arrival memory and blank-note pin dependencies are omitted. Forgetting an omitted source leaves its derived record/counts intact; pin removal also lacks an immediate withdrawal hook.

- **Replay can return forgotten data.** [ProactiveReplay.kt:47](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/ProactiveReplay.kt:47) accumulates text and IDs after each step’s lease closes. Forget during later steps does not revoke earlier results; the response has no final liveness check. Unselected candidates’ IDs also bypass provenance verification.

- **Rendered settings text survives Forget/Delete-all.** [SuggestionsSettings.kt:125](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/ui/settings/suggestions/SuggestionsSettings.kt:125) caches text in `StateFlow` without observing deletion. Store withdrawal does not clear displayed rows; an overlapping refresh can republish deleted content.

- **Encrypted-store failure bypasses existing suppression.** [ProactiveGraph.kt:48](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/ProactiveGraph.kt:48) substitutes an empty memory store, losing persisted Never entries and budget/cooldown history while allowing would-shows. Storage failure must disable recording rather than assume empty history.

Other gaps: UI actions can initialize encrypted prefs on the main thread; shared LLM gap/source-overlap dedupe is missing; dismissing records resets budget history. Local-midnight arithmetic uses timezone rules, but DST/timezone-transition tests are absent.

Confirmed statically: no HUD/notification/Nexus delivery; backup and transfer exclusions exist; logs avoid payloads; replay has bearer authentication, backend Developer-mode/flag checks, deadline and disable cancellation; triggers reuse existing hooks with battery constraints and OFF enqueue guards.

Validation: 10 Python checks passed. Android tests were not run in the read-only workspace.

**NOT READY**