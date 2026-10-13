# Proactive Context v1.0 — Phase 7: Nudges, Actions, Sources, Pause, Onboarding

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (or superpowers:executing-plans). This is a **lean** plan: it gives exact names, signatures, values, copy and edge cases, but not full code. Stream 0 lands first; Streams A, B, C, F and E then run **at the same time**, each in its own worktree. TDD applies to every task.

**Goal:** Nudges reach the phone feed and the HUD; proposed actions (reminder, shopping, calendar) are confirmed, executed once and can be undone; data sources feed context; privacy pause works fail-closed in both directions; Forget fact / Forget moment cascade; the onboarding wizard (including the voice probe) replaces the one-step welcome.

**Architecture:** A new `:services:connectors` module holds the action engine, executors and data sources. `:services:orchestration` gains `NudgeEngine`, re-summarise and retention workers. `:services:memory` gains the proposal compare-and-set (CAS), action, forget and nudge-gate DAOs (schema v1 unchanged: every table already exists). The phone hub gains two ports (`HubActions`, `HubNudges`) next to `HubVoice`. The glasses gain the phone→glasses Resume, quiet hours and undo-toast handling. Stream 0 lands all shared seams: interfaces, placeholder files, routes and DB registration. After that, every stream owns a set of files that no other stream touches.

**Tech Stack:** As in the master plan (Kotlin 2.0.21, AGP 8.13.2, Compose BOM 2024.09.00, Room 2.6.1 + KSP, WorkManager 2.9.1, kotlinx-serialization 1.7.3, Robolectric 4.13). Android APIs: `AlarmManager`, `CalendarContract`, `ContactsContract`, `LocationManager` + `Geocoder` (no Play services), `NotificationManagerCompat`, `ActivityResultContracts.TakePicture`.

**Spec:** `docs/superpowers/specs/2026-10-12-v1-redesign-design.md`, §2.4 (Home feed, Ask detail, Memory detail, Settings › Privacy/Connectors, Onboarding), §3.2/§3.4 (Nudge/Confirm overlays, verbosity), §4.4 (`ProposedAction`), §5.1.1 (onboarding probe), §5.2–§5.6, §6.1.1. Requirement IDs: R-19, R-61, R-62, R-63, R-64, R-65, R-66, R-77, plus R-01 (connectors module), R-02 (setup scaffold), R-16 (Forget cascade), R-67 (onboarding probe, moved from Phase 6).

**Preconditions (controller):** Phase 6 Tasks 11–16 are merged into `phase7/nudges-actions` **before Stream 0 starts**. They edit `Routes`, `SettingsDestinations`, `SettingsRoot`, `Destinations`, `PhoneDeps`, `HomeScreen`/`HomeViewModel`, `PhoneInputs`, `GlassesSettings`, `OrchestrationScheduler` and `PromptComposer`, which this plan builds on. Stream worktrees are cut from the branch tip after Stream 0 merges, e.g. `git worktree add ../pca-p7-a -b p7/a-pause phase7/nudges-actions` (also `../pca-p7-b` `p7/b-actions`, `../pca-p7-c` `p7/c-nudges`, `../pca-p7-f` `p7/f-forget`, `../pca-p7-e` `p7/e-onboarding`).

**Path prefixes** (all under `android-companion/`; tests mirror the path under `src/test/…`):
`PH` = `phone/src/main/java/com/rokid/context/phone` · `GL` = `glasses/src/main/java/com/rokid/context/glasses` · `CM` = `core/model/src/main/kotlin/com/rokid/context/core/model` · `CH` = `core/hud/src/main/kotlin/com/rokid/context/core/hud` · `MEM` = `services/memory/src/main/java/com/rokid/context/memory` · `ORC` = `services/orchestration/src/main/java/com/rokid/context` · `AI` = `services/ai/src/main/java/com/rokid/context` · `CON` = `services/connectors/src/main/kotlin/com/rokid/context/connectors` (new; Kotlin source root like `:services:voice`).

## Global Constraints

- Every Phase 5/6 dispatch rule applies (`pca-p6/.superpowers/sdd/2026-10-12-v1-phase6-voice-answers/dispatch-common.md`). Each stream gets a copy at `.superpowers/sdd/2026-10-12-v1-phase7-nudges-actions/dispatch-common.md` in its worktree, with the worktree path and branch replaced.
- A stream edits **only** its listed files (plus new tests under the mirrored test paths). It needs a file outside its set? Stop and ask the controller. Never "just a one-liner".
- Schema stays **version 1**. No entity, column or index changes. New DAOs and queries only.
- Protocol changes are additive only (defaults on every new field), so `PROTOCOL_VERSION` stays 1. Both APKs ship together.
- Run one Gradle invocation at a time per worktree, with `ANDROID_HOME=/Users/<user>/Desktop/rokid/android-sdk`, from `android-companion/`.
- No DB, file, Keystore, `ContentResolver`, `Geocoder` or `AlarmManager` work on Main. Inject a `work`/`io` dispatcher. Rethrow `CancellationException`.
- Logs go through `SafeLog.describe` only. Never log memory text, nudge text, action params, contact names, calendar titles, place labels or transcripts.
- Never show raw exception or provider text in any UI or HUD. Use the fixed copy in this plan or `UserFacingError`.
- Data-source content (`{{context_sources}}`) never reaches the glasses (spec §5.4): no source text in `StateFrame` or `HudSettings`.
- Nothing resumes capture implicitly. Every new pause path is fail-closed.
- Robolectric tests use `@Config(sdk = [34])`. Regex literals are ICU-safe. Data-block labels match `[a-z_]+`.
- Commit after every task. Messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never push. Never run adb (device steps are controller-run).
- Task gate = the module test command given in the task. Stream gate (before hand-off) = `./gradlew test :phone:assembleDebug :glasses:assembleDebug > ../.superpowers/sdd/2026-10-12-v1-phase7-nudges-actions/<stream>-gate.log 2>&1; echo EXIT=$? >> <same file>`.

## Review Focus (the 5 riskiest untested input classes)

1. **Clocks on pause records.** Inputs: a phone pause pushed before any `pc_hello` (phone clock); a glasses reboot that moves its clock; a timed pause that outlives a change of offset; quiet hours over midnight, over a DST change, with start == end, and after a timezone change mid-window. Expected: never less restrictive than intended; converges once an exact push arrives. Owner: A1, A2.
2. **Concurrent proposal answers.** HUD ✓, phone ✕, a notification action and expiry all within the same millisecond, plus process death between `EXECUTING` and `EXECUTED`. Expected: exactly one terminal state, one execution, idempotent recovery. Owner: S0.1 (CAS), B2.
3. **Model-proposed action params at the edges.** Inputs: `dueAt` a few seconds in the past by the time it is handled; a local time inside a DST gap (02:30 on spring-forward day); `endAt` absent or ≤ `startAt`; 10 shopping items with duplicate or Devanagari names; titles of exactly 80 chars. Expected: stored `EXPIRED` (past), shifted forward to a valid instant (gap), default +30 min, and items deduped by name per proposal. Owner: B1, B2.
4. **Forget graph shapes.** A cycle in `memory_source`, a diamond (A→B, A→C, B→D, C→D), a closure of more than 999 ids, and Forget while a reprocess job queued with the old text is still pending. Expected: terminates, one invalidation each, chunked `IN` lists, and forgotten text never sent to a model. Owner: F1, F2.
5. **Permissions revoked or denied forever mid-flow.** Calendar read/write, contacts, coarse location, notifications and exact alarms are revoked after a source or action was enabled. Expected: the source yields nothing plus "Permission needed"; the action ends `FAILED` with "Calendar permission needed"; the reminder falls back to an inexact alarm; nothing crashes. Owner: B2, C1.

---

## Requirement and carry coverage

| R-ID / carry | Task |
|---|---|
| R-01 `:services:connectors` with dependency rules | S0.1 (`ConnectorsModuleRulesTest`) |
| R-02 setup wizard scaffold (Live Fit) | E1 |
| R-19 onboarding steps (§2.4) | E1, E2 |
| R-67 onboarding Permissions step runs the voice probe and shows status | E1 |
| R-61 NudgeEngine + gates (quiet hours, daily cap, 24 h dedupe, verbosity) | C3, C4 |
| R-62 action matrix, phone-owned expiry, CAS, idempotent executors, Undo, action_log | S0.1, B1–B4 |
| R-63 `ToolConnector` + `McpConnector` stub + "Coming in v2" row | B4 |
| R-64 data sources, on-demand permissions, context only | C1, C2 |
| R-65 pause triggers, durations, buffer drop, indications, both-side persistence, quiet hours offline, union, Resume-named-only, intervals | A1–A4 |
| R-66 phone camera "Capture now" into the same pipeline | C2 |
| R-77 / R-16 Forget fact / moment, extraction lock, transitive invalidation, re-summarise into new ids, redaction, proposal expiry | F1–F3 |
| R-13 For you feed nudge cards; R-14 Ask detail action state | C4; B4 |
| R-05 / R-06 additive protocol fields; `latestNudge` in `StateFrame` | S0.1, S0.2, C4 |
| R-11 "Still paused on glasses — Resume?" banner | A3 |
| R-17 Settings › Privacy, Data sources, Actions & tools, Shopping list | S0.2 (rows/routes), A4, B4, C2 |
| R-72 no raw errors; R-73 tests | every task |
| Carry: phone-origin pauses — first `pc_settings` before hello is in phone clock; glasses keep the first version of a known id (should merge most-restrictive) | S0.1 (`pausesExact`), A1, A2 |
| Carry: no phone→glasses Resume path; define both directions | S0.1 (`resumedPauseIds`), A1, A2, A3 |
| Carry: QUIET_HOURS pause ids must be unique per occurrence | A1 (phone), A2 (glasses) |
| Carry: phone-merged pause does not clear already-buffered frames | A2 |
| Carry: 👎 critic payload must exclude INVALIDATED memory text (forgotten content never reaches a model) | F2 |
| Carry: 👎 creates a trusted summary through the old reprocess path | F2 |
| Carry: retention windows are shown but never enforced | F3 |
| Carry: TestBedInputs NUDGE should use real retrieval once a production NUDGE path exists | C4 |
| Carry: RetryClassifier matches wording for non-model errors | F2 |
| Carry (P6 T4 ruling): hub-paused banner shows no action until Phase 7 | A3 |
| Carry (P6 T5 minors): `active()` expiry close+delete not one transaction | A1 |
| Carry (P6 T6 minors): rating written before an unguarded enqueue; non-atomic double-tap enqueue; no 👎-selected test | F2 |
| Carry (P6 T6 minor): Ask source cards don't observe memory changes | B4 |
| Carry (P6 T11 minor): `togglePause` on Main may wait on one JPEG write | A2 |
| Ruling kept: Ask and the test bed call AI while paused (allowed) | — (no change) |
| Not Phase 7: non-`rokid_` memory purge on launch; secrets-prefs plaintext fallback | Phase 8 carry list |

---

## Shared interfaces (fixed here; Stream 0 lands them)

```kotlin
// CM/Protocol.kt — additive fields only
data class HudSettings(/* existing fields… */
    val resumedPauseIds: List<String> = emptyList(), // ids the phone resumed (≤ 7 d, ≤ 200 newest); glasses drop them
    val pausesExact: Boolean = false,                // true = `pauses` were converted with a measured clock offset
    val zoneId: String? = null,                      // phone ZoneId.id; glasses evaluate quiet hours in it
)
@Serializable @SerialName("toast") data class Toast(val text: String, val undoProposalId: String? = null) : Overlay
@Serializable @SerialName("undo") data class Undo(val proposalId: String) : Command

// MEM/Proposals.kt (new) — package com.rokid.context.memory
enum class ProposalOrigin { ASK, NUDGE }
enum class ProposalState { PENDING, EXECUTING, EXECUTED, DECLINED, EXPIRED, SUPPRESSED, FAILED }
enum class Decision { APPROVE, DECLINE }
sealed interface Resolve { data class Won(val to: ProposalState) : Resolve; data class Lost(val current: ProposalState?) : Resolve }
// FeedRepositories.kt — ProposalRepository(dao: ProposalDao, actions: ActionDao, db: ContextDatabase)
suspend fun create(p: ProposalEntity, nowMs: Long): Boolean                 // insertIgnore + log "CREATED:<state>"
suspend fun resolve(id: String, d: Decision, by: String, nowMs: Long): Resolve // one tx: PENDING & expiresAt>now → EXECUTING|DECLINED; PENDING & expired → EXPIRED (Lost(EXPIRED))
suspend fun markExecuted(id: String, createdRef: String, nowMs: Long): Boolean // EXECUTING → EXECUTED, sets createdRef
suspend fun markFailed(id: String, detailCode: String, nowMs: Long): Boolean   // EXECUTING → FAILED
suspend fun markUndone(id: String, by: String, nowMs: Long): Boolean           // EXECUTED → DECLINED, resolvedBy "undo:<by>", log "UNDONE"
suspend fun expireDue(nowMs: Long): List<String>                               // PENDING & expiresAt ≤ now → EXPIRED
suspend fun expireForOrigins(originIds: Collection<String>, nowMs: Long): Int  // PENDING → EXPIRED (Forget); chunks of 500
suspend fun inState(s: ProposalState): List<ProposalEntity>
suspend fun byId(id: String): ProposalEntity?
fun forOrigin(originId: String): Flow<List<ProposalEntity>>                    // existing
fun pending(): Flow<List<ProposalEntity>>

// MEM/db/ActionDaos.kt (new)
@Dao interface ActionDao {
  @Query("UPDATE proposal SET state = :to, resolvedBy = :by WHERE id = :id AND state = :from") suspend fun cas(id: String, from: String, to: String, by: String?): Int
  @Insert suspend fun log(e: ActionLogEntity)
  @Query("SELECT * FROM action_log WHERE proposalId = :id ORDER BY id") suspend fun logFor(id: String): List<ActionLogEntity>
}
@Dao interface ShoppingDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(items: List<ShoppingItemEntity>): List<Long>
  @Query("DELETE FROM shopping_item WHERE proposalId = :proposalId") suspend fun deleteForProposal(proposalId: String): Int
  @Query("SELECT * FROM shopping_item ORDER BY done ASC, createdAt DESC") fun observeAll(): Flow<List<ShoppingItemEntity>>
  @Query("SELECT * FROM shopping_item WHERE done = 0 ORDER BY createdAt DESC LIMIT :limit") suspend fun open(limit: Int): List<ShoppingItemEntity>
  @Query("UPDATE shopping_item SET done = :done WHERE id = :id") suspend fun setDone(id: String, done: Boolean): Int
  @Query("DELETE FROM shopping_item WHERE id = :id") suspend fun delete(id: String): Int
}
@Dao interface NoteDao { @Query("SELECT * FROM note ORDER BY createdAt DESC LIMIT :limit") suspend fun recent(limit: Int): List<NoteEntity> }
// MEM/db/ForgetDao.kt (new, empty abstract @Dao class — F fills)   MEM/db/NudgeGateDao.kt (new, empty @Dao interface — C fills)
// ContextDatabase: actionDao(), shoppingDao(), noteDao(), forgetDao(), nudgeGateDao(); MemoryGraph: proposals (new ctor), shopping, notes

// CON/ProposalIntake.kt
fun interface ProposalIntake {
    /** After the ask/nudge row is committed. Returns the proposalId, or null if nothing was stored. Never throws. */
    suspend fun onProposed(origin: ProposalOrigin, originId: String, action: ProposedAction, nowMs: Long): String?
    companion object { val None = ProposalIntake { _, _, _, _ -> null } }
}
// CON/ContextSourceProvider.kt
fun interface ContextSourceProvider {
    /** Lines for {{context_sources}}; [question] null = nudge context. Never throws; ≤ 1 500 chars total. */
    suspend fun lines(question: String?, nowMs: Long): List<String>
    companion object { val None = ContextSourceProvider { _, _ -> emptyList() } }
}
// CON/actions/ActionsGraph.kt (placeholder → B)
object ActionsGraph { fun get(context: Context): ActionsGraphApi; /* placeholder: intake = ProposalIntake.None */ }
interface ActionsGraphApi { val intake: ProposalIntake }
// CON/sources/SourcesGraph.kt (placeholder → C)
object SourcesGraph { fun get(context: Context): SourcesGraphApi }
interface SourcesGraphApi { val provider: ContextSourceProvider }

// ORC/orchestration/AskActions.kt — AskOutcome.Answered gains proposalId
data class Answered(val askId: String, val proposedAction: ProposedAction? = null, val proposalId: String? = null) : AskOutcome
// ORC/orchestration/AnswerService.kt — new ctor param `proposals: ProposalIntake = ProposalIntake.None`,
// called after the ask row commits; forApp wires ActionsGraph.get(ctx).intake and SourcesGraph.get(ctx).provider.
// ORC/orchestration/nudge/NudgeTrigger.kt (placeholder → C)
object NudgeTrigger { fun afterSummarizerPass(context: Context) = Unit }

// PH/hub/HubPorts.kt
interface HubActions {
    val overlay: StateFlow<Overlay?>          // Overlay.Confirm or an undo Overlay.Toast; null otherwise
    fun onConfirm(proposalId: String, yes: Boolean)
    fun onUndo(proposalId: String)
    companion object { val None: HubActions }
}
interface HubNudges {
    val latest: StateFlow<NudgeLine?>         // phone clock
    val overlay: StateFlow<Overlay.Nudge?>
    companion object { val None: HubNudges }
}
// Hub.kt additions
val activePauses: StateFlow<List<PauseRecord>>       // phone clock, refreshed in broadcast()
suspend fun pausePhone(untilMs: Long?): PauseRecord   // new UUID, origin PHONE, createdAt = now
suspend fun resume(ids: Set<String>)                 // pauses.resume(ids) + pushSettings + markDirty
fun refresh()                                        // broadcaster.markDirty()
// overlay precedence in broadcast(): voice Listening > actions.overlay > voice.overlay > nudges.overlay
// PH/actions/PhoneActions.kt (placeholder → B):  fun hubPort(app: Context, hud: () -> HudSettings): HubActions = HubActions.None
// PH/nudge/PhoneNudges.kt  (placeholder → C):  fun hubPort(app: Context, hud: () -> HudSettings): HubNudges = HubNudges.None
// PH/ui/status/CapturePauses.kt (placeholder → A): fun control(app: Context): PipelineControl = LegacyPipelineControl(app, OrchestrationScheduler(app))
// PH/ui/actions/ProposalCard.kt (placeholder → B): @Composable fun ProposalCard(originId: String, modifier: Modifier = Modifier) {}

// Route composables (S0.2 moves today's inline wiring into them unchanged; the owning stream edits them afterwards)
@Composable fun HomeRoute(deps: PhoneDeps, nav: NavController, onBanner: (BannerAction, (BannerAction) -> Unit) -> Unit) // PH/ui/home/HomeRoute.kt → C
@Composable fun AskDetailRoute(id: String, deps: PhoneDeps, nav: NavController)       // PH/ui/ask/AskDetailRoute.kt → B
@Composable fun MemoryDetailRoute(id: String, deps: PhoneDeps, nav: NavController)    // PH/ui/memory/MemoryDetailRoute.kt → F
@Composable fun NudgeDetailRoute(id: String, deps: PhoneDeps, nav: NavController)     // PH/ui/nudge/NudgeDetail.kt → C (placeholder: "Nudge" header + back)
@Composable fun OnboardingRoute(deps: PhoneDeps, onDone: () -> Unit)                  // PH/ui/onboarding/OnboardingWizard.kt → E (placeholder: WelcomeScreen)
@Composable fun PrivacyRoute(deps: SettingsDeps, back: () -> Unit)                    // PH/ui/settings/privacy/PrivacySettings.kt → A
@Composable fun SourcesRoute(deps: SettingsDeps, back: () -> Unit)                    // PH/ui/settings/connectors/SourcesSettings.kt → C
@Composable fun ActionsRoute(deps: SettingsDeps, back: () -> Unit, openShopping: () -> Unit) // PH/ui/settings/connectors/ActionsSettings.kt → B
@Composable fun ShoppingRoute(deps: SettingsDeps, back: () -> Unit)                   // PH/ui/settings/connectors/ShoppingList.kt → B
```
Routes (S0.2): `SETTINGS_PRIVACY = "settings/privacy"`, `SETTINGS_SOURCES = "settings/sources"`, `SETTINGS_ACTIONS = "settings/actions"`, `SETTINGS_SHOPPING = "settings/shopping"`, `NUDGE = "nudge/{id}"`, `fun nudge(id)`. All four settings routes are added to `LIVE_SETTINGS`, and `tabFor("nudge/…") = HOME`.

Placeholders compile and keep today's behaviour. A stream may replace a placeholder's body and add parameters with defaults. It must **never** change a shared signature above. Need a change? Ask the controller, who lands it on the branch and tells every stream to rebase.

---

## Stream 0 — Seams (one implementer, lands before A/B/C/F/E)

**Owns:** `settings.gradle.kts`; `services/connectors/**` (creates it); `services/orchestration/build.gradle.kts`; `phone/build.gradle.kts`; `CM/Protocol.kt`; `MEM/db/{ContextDatabase,ActionDaos,ForgetDao,NudgeGateDao}.kt`; `MEM/{Proposals,FeedRepositories,MemoryGraph}.kt`; `ORC/orchestration/{AnswerService,AskActions}.kt` (the `AskOutcome` change only); `ORC/work/QueueDrainWorker.kt` (two hook calls only); `ORC/orchestration/nudge/NudgeTrigger.kt`; `PH/hub/{HubPorts,Hub,HubGraph,HubPauses,StateFrameBuilder}.kt`; `PH/actions/PhoneActions.kt`; `PH/nudge/PhoneNudges.kt`; `PH/ui/status/CapturePauses.kt`; `PH/ui/actions/ProposalCard.kt`; `PH/ui/shell/{Routes,Destinations,SettingsDestinations,PhoneDeps}.kt`; `PH/ui/settings/SettingsRoot.kt`; `phone/src/main/AndroidManifest.xml` (the two empty P7 block markers only); all route/placeholder files listed above.

### Task S0.1: Protocol, connectors module, proposal CAS, DB seams

**Requirements:** R-01, R-05, R-62 (CAS). **Files:** as owned above, except the phone files.

- Add `include(":services:connectors")`. `services/connectors/build.gradle.kts` mirrors `:services:voice`: namespace `com.rokid.context.services.connectors`, minSdk 29, `src/main/kotlin`. `api(project(":core:model"))`, `api(project(":services:memory"))`, `implementation(project(":services:ai"))`, `androidx.core:core-ktx:1.13.1`, `androidx.work:work-runtime-ktx:2.9.1`, `kotlinx-serialization-json:1.7.3`, coroutines-android 1.9.0. Test deps as in voice + Room testing. Add an empty `src/main/AndroidManifest.xml`; B fills it.
- `:services:orchestration` gets `api(project(":services:connectors"))`. `:phone` gets `implementation(project(":services:connectors"))`. Connectors never depend on orchestration, phone or voice.
- Protocol: the additive fields above. `Overlay.Toast(text)` call sites still compile (default param).
- `ProposalRepository`: every write runs in `db.withTransaction`. Each successful transition writes `action_log(ts, proposalId, transition = "<FROM>→<TO>", detail = by)`. `resolve` reads the row in the transaction: unknown → `Lost(null)`; not PENDING → `Lost(current)`; PENDING and `expiresAt ≤ now` → CAS to EXPIRED, then `Lost(EXPIRED)`.
- `MemoryGraph.proposals` is built with `(db.proposalDao(), db.actionDao(), db)`. Add `shopping = db.shoppingDao()` and `notes = db.noteDao()`.
- `AnswerService.answerOrThrow`: after the transaction commits, `out.proposedAction?.let { proposals.onProposed(ProposalOrigin.ASK, id, it, nowMs) }`, and return `Answered(id, action, proposalId)`.
- `QueueDrainWorker`: call `NudgeTrigger.afterSummarizerPass(applicationContext)` right after the `"TRUSTED"` trace in `processBatchedSummarization` and after `"REPROCESSED"` in `processPriorityReprocess`. Change nothing else.

**Tests:**
- `ProposalCasTest.concurrentHudPhoneAndExpiryEndInOneState`: 50 runs of 3 coroutines (APPROVE / DECLINE / `expireDue`) on one PENDING row → exactly one `Won` per run; `logFor` has one transition out of PENDING.
- `ProposalCasTest.resolveAfterExpiryIsLostExpired`: `expiresAt = now` → `Lost(EXPIRED)` and state EXPIRED.
- `ProposalCasTest.undoOnlyFromExecuted`: `markUndone` on PENDING → false; on EXECUTED → true, state DECLINED, `resolvedBy == "undo:hud"`.
- `ProposalCasTest.expireForOriginsChunksOver999`: 1 200 PENDING rows → returns 1 200, all EXPIRED.
- `ProtocolTest.newHudSettingsFieldsDefaultAndRoundTrip`: old JSON without the fields decodes with defaults; a round trip keeps `resumedPauseIds`/`pausesExact`/`zoneId`.
- `ProtocolTest.undoCommandAndUndoToastRoundTrip`: `CommandEnvelope(Command.Undo("p1"))` and `Overlay.Toast("x", "p1")` round-trip.
- `ConnectorsModuleRulesTest.connectorsDependsOnNoAppModule`: parse `services/connectors/build.gradle.kts` → no `:phone`, `:glasses`, `:services:orchestration`, `:services:voice`.
- `AnswerServiceTest.proposedActionGoesToIntakeAfterCommit`: fake intake records `(ASK, askId)`, and the ask row exists when it is called; `Answered.proposalId == "p-fake"`.

**Gate:** `./gradlew :core:model:test :services:memory:testDebugUnitTest :services:connectors:testDebugUnitTest :services:orchestration:testDebugUnitTest`

### Task S0.2: Phone seams — hub ports, routes, route composables, placeholders

**Requirements:** R-17 (rows), R-06. **Files:** the phone files owned above.

- `HubPorts.kt` as fixed above. The `None` objects return `MutableStateFlow(null)` and ignore calls.
- `Hub`: new ctor params `actions: HubActions = HubActions.None` and `nudges: HubNudges = HubNudges.None`. In `start()`, collect `actions.overlay.drop(1)`, `nudges.overlay.drop(1)` and `nudges.latest.drop(1)` → `markDirty`. `onCommand`: `Confirm` → `actions.onConfirm(id, yes)`; `Undo` → `actions.onUndo(id)`. Add `activePauses`, `pausePhone`, `resume` and `refresh` as fixed above. `pushSettings` passes `resumed = withContext(io) { pauses.recentlyResumedIds() }` and `zoneId = ZoneId.systemDefault().id`.
- `HubPauses`: new ctor params `quietHours: () -> QuietHours? = { null }` and `zone: () -> ZoneId = ZoneId::systemDefault` (stored, unused until A1). Add `suspend fun recentlyResumedIds(): List<String> = emptyList()` (A1 implements it).
- `StateFrameBuilder`: `HubInputs.latestNudge: NudgeLine? = null`. `build` sets `latestNudge = i.latestNudge?.let { n -> i.offset?.let { o -> n.copy(atMs = o.toGlasses(n.atMs)) } ?: n }`. `settingsFor(settings, active, offset, askBlockedReason, resumed: List<String> = emptyList(), zoneId: String? = null)` sets `resumedPauseIds = resumed`, `pausesExact = offset != null` and `zoneId`.
- `HubGraph`: `actions = PhoneActions.hubPort(app) { settings.read() }`, `nudges = PhoneNudges.hubPort(app) { settings.read() }`, and `HubPauses(dao, quietHours = { settings.read().quietHours })`.
- `Destinations`: HOME, ASK and MEMORY call the route composables. Their bodies are the current inline code, moved verbatim. Add ONBOARDING → `OnboardingRoute(deps) { markComplete + navigate HOME }` and NUDGE → `NudgeDetailRoute`. `SettingsDestinations` gets the four new settings routes. `SettingsRoot` rows: Privacy → `SETTINGS_PRIVACY` "Pause, quiet hours"; Data sources → `SETTINGS_SOURCES` "Calendar, contacts, notes, location"; Actions & tools → `SETTINGS_ACTIONS` "Reminders, shopping list, calendar".
- `PhoneDeps.create`: `pipeline = CapturePauses.control(app)`. Everything else stays unchanged.

**Tests:**
- `HubTest.confirmAndUndoCommandsReachActionsPort`: fake port records `onConfirm("p",true)` and `onUndo("p")`.
- `HubTest.overlayPrecedence`: voice Listening + actions Confirm → Listening; voice Answer + actions Confirm → Confirm; only a nudge → Nudge.
- `HubTest.pausePhonePushesSettingsWithNewRecord`: the next `pc_settings` lists one PHONE record; `activePauses` has it.
- `StateFrameBuilderTest.latestNudgeConvertedToGlassesClock`: offset +5 000 → `atMs + 5 000`; no offset → unchanged.
- `StateFrameBuilderTest.settingsForMarksExactOnlyWithOffset`: `pausesExact` is false without an offset and true with one.
- `RoutesTest.phase7RoutesLiveAndNudgeTabIsHome`: four routes ⊂ `LIVE_SETTINGS`; `tabFor("nudge/{id}") == HOME`.
- `SettingsRootTest.connectorsAndPrivacyRowsOpen`: the Privacy, Data sources and Actions rows navigate to their routes.
- All existing Home/Ask/Memory screen and navigation tests still pass unchanged.

**Gate:** `./gradlew :phone:testDebugUnitTest :phone:assembleDebug :glasses:assembleDebug`, then the full stream gate. Merge to `phase7/nudges-actions` before cutting the other worktrees.

---

## Stream A — Privacy pause, both directions (R-65 + pause carries)

**Owns:** `PH/hub/{HubPauses,StateFrameBuilder,HubSettingsStore,ContextHubService}.kt`; `MEM/db/AppDaos.kt` (PauseDao only); `PH/ui/status/{CapturePauses,PipelineControl,CaptureLine,StatusUi}.kt`; `PH/ui/banner/{Banners,BannerHost}.kt`; `PH/ui/settings/privacy/PrivacySettings.kt`; `GL/pause/{GlassesPauses,PauseStore,QuietHoursClock}.kt` (`QuietHoursClock` is new); `GL/GlassesRuntime.kt`; `GL/link/{HudController,HudSettingsCache}.kt`; `GL/capture/OutboundBuffer.kt`.
**Must not touch:** `Hub.kt`, `HubGraph.kt`, `PhoneDeps.kt`, shell routing, `ui/home/**`, `ui/activity/*Screen.kt`, `GL/MainActivity.kt`, `GL/hud/**`, `CH/**`, `FrameAdmission`.

### Task A1: Phone pause set — quiet hours, resumed ids, atomic expiry

**Requirements:** R-65. **Files:** `HubPauses.kt`, `AppDaos.kt` (PauseDao), `StateFrameBuilder.kt` (tests only, unless a fix is needed).

```kotlin
object QuietWindow { fun occurrenceAt(q: QuietHours, nowMs: Long, zone: ZoneId): LongRange? } // in HubPauses.kt; [start, end) instants
suspend fun HubPauses.active(): List<PauseRecord>            // now also synthesises the QUIET_HOURS record
suspend fun HubPauses.recentlyResumedIds(): List<String>     // closed intervals, no live record, end ≥ now − 7 d, newest 200
@Transaction suspend fun PauseDao.expire(ids: List<String>, ends: Map<String, Long>)
@Query("SELECT * FROM pause_interval WHERE origin = 'QUIET_HOURS' AND startMs = :startMs LIMIT 1") suspend fun PauseDao.quietOccurrence(startMs: Long): PauseIntervalEntity?
@Query("SELECT pauseId FROM pause_interval WHERE effectiveEndMs IS NOT NULL AND effectiveEndMs >= :since AND pauseId NOT IN (SELECT pauseId FROM pause_record) ORDER BY effectiveEndMs DESC LIMIT 200") suspend fun PauseDao.resumedSince(since: Long): List<String>
```
- Expiry close + delete runs in one transaction (`PauseDao.expire`). This fixes the P6 T5 minor.
- Quiet hours (phone): when `quietHours()` contains the local now in `zone()` and there is **no** interval with `origin = QUIET_HOURS AND startMs = occurrence.start` (open or closed), add `PauseRecord(UUID, QUIET_HOURS, createdAtMs = occurrence.start, untilMs = occurrence.end)`. Each occurrence gets a fresh UUID. Once resumed, an occurrence is never re-created.
- Occurrence math uses `ZonedDateTime` of the local start/end minute on the right date. A wrap past midnight ends the next day. A local time inside a DST gap shifts forward (`ZonedDateTime.of` semantics); an overlap takes the earlier offset. `start == end` → no window.
- `recentlyResumedIds` feeds `HudSettings.resumedPauseIds` (wired by S0.2).
- Phone clock only. Glasses-clock conversion stays in `StateFrameBuilder`. Without an offset, records go unchanged with `pausesExact = false`.

**Tests (`HubPausesTest`):**
- `quietHoursCreatesOneRecordPerOccurrence`: two `active()` calls in one window → 1 record; the next night → a second record with a different id.
- `resumedQuietOccurrenceIsNotRecreated`: resume the QH id, then `active()` in the same window → empty.
- `quietWindowOverMidnight`: 22:00–07:00 at 23:30 → `until` = next day 07:00 in zone.
- `quietWindowOnSpringForward`: 02:30–03:30 on the DST-gap day → a valid, non-empty range, never a negative length.
- `quietStartEqualsEndNeverPauses`: `active()` stays empty all day.
- `zoneChangeMidWindowKeepsExistingRecord`: switch the zone → the existing record keeps its `untilMs`; no duplicate.
- `expiryIsAtomic`: a DAO that throws on delete → neither the interval close nor the delete persists (record still listed).
- `recentlyResumedIdsExcludesLiveAndOld`: resumed 8 d ago → absent; resumed 1 h ago → present; a live record → absent.

**Gate:** `./gradlew :services:memory:testDebugUnitTest :phone:testDebugUnitTest --tests '*HubPauses*' --tests '*StateFrameBuilder*'`

### Task A2: Glasses — phone resumes, exact/provisional merge, quiet hours, buffer drop

**Requirements:** R-65. **Files:** `GL/pause/{GlassesPauses,PauseStore,QuietHoursClock}.kt`, `GL/GlassesRuntime.kt`, `GL/link/{HudController,HudSettingsCache}.kt`, `GL/capture/OutboundBuffer.kt`.

```kotlin
data class PauseState(val records: List<PauseRecord>, val tombstones: Map<String, Long> = emptyMap(),
    val provisional: Set<String> = emptySet(), val phoneResumed: Map<String, Long> = emptyMap(), val quietHandled: Map<Long, String> = emptyMap())
data class MergeResult(val added: List<PauseRecord>, val removed: Set<String>)
suspend fun GlassesPauses.mergeFromPhone(s: HudSettings): MergeResult   // replaces mergeFromPhone(records)
fun GlassesPauses.captureAllowed(nowMs: Long = clock()): Boolean        // also evaluates quiet hours (synthesise first)
class QuietHoursClock(private val settings: () -> HudSettings?) { fun occurrenceAt(nowMs: Long): LongRange? } // zone = settings.zoneId ?: system
// HudController: var onPhoneSettings: (HudSettings) -> Unit   (replaces onPhonePauses)
```
- **Phone→glasses Resume:** every id in `s.resumedPauseIds` is removed from `records` (any origin) and recorded in `phoneResumed` (pruned after 7 d). A later push or hello never re-adds an id in `phoneResumed` or `tombstones`.
- **Exact push** (`pausesExact`): for a known id, merge most-restrictive (`untilMs` null beats any; else the later wins). For a known **provisional** id, take the phone's value (it may narrow) and clear the provisional flag. Unknown ids are added.
- **Inexact push:** an unknown, timed record whose `origin != GLASSES` is stored with `untilMs = null` and its id added to `provisional` (fail-closed until an exact push). Indefinite records are stored as-is. A known id is never narrowed by an inexact push.
- **Buffer:** when `MergeResult.added` contains a record active now, clear the buffer (`buffer.clear()`) under `captureGate`, the same as a local pause (spec §5.5 "drops buffered frames"). Run this and `togglePause`'s clear on the runtime's IO scope, never on Main (fixes the P6 T11 minor).
- **Quiet hours (glasses):** when `QuietHoursClock` says now is inside an occurrence not in `quietHandled`, add `PauseRecord(UUID, QUIET_HOURS, occurrence.start, occurrence.end)` and record `quietHandled[start] = id` (keep the newest 14). A local double-tap Resume clears it like any record, and the occurrence stays handled. No link is needed. The settings come from `HudSettingsCache` (persisted).
- `PauseStore` reads old files without the new fields (defaults) and keeps the unreadable → paused rule.

**Tests (`GlassesPausesTest` unless noted):**
- `phoneResumedIdRemovesPhoneRecordAndIsNeverReadded`: merge with `resumedPauseIds=[p]` → removed; a later push listing p → still absent.
- `exactPushMergesMostRestrictive`: local `until 10:00`, push `until 11:00` → 11:00; push `until 09:00` → stays 11:00.
- `inexactTimedPhoneRecordIsIndefiniteUntilExact`: inexact `until 10:00` → stored null and provisional; an exact push at 09:58 → 09:58, not provisional.
- `inexactPushNeverNarrowsKnownId`: known indefinite, inexact `until` → still null.
- `newActivePhoneRecordClearsBuffer` (`GlassesRuntimeTest`): 3 buffered frames, merge adds an active record → buffer size 0; an expired record → buffer kept.
- `quietHoursOfflineCreatesUniqueIdsPerNight`: no phone for 2 nights → 2 records, distinct ids, each `until` = that morning's end.
- `quietHoursUseSentZone`: `zoneId="Asia/Kolkata"` with the system zone UTC → the window matches Kolkata local time.
- `localResumeInsideQuietWindowStaysResumed`: toggle → Resumed; `captureAllowed()` true for the rest of the window.
- `oldPauseStoreFileStillLoads` (`PauseStoreTest`): a JSON without the new keys → records loaded, empty sets.

**Gate:** `./gradlew :glasses:testDebugUnitTest`

### Task A3: Phone pause control, banners, notification action

**Requirements:** R-65, R-11. **Files:** `PH/ui/status/{CapturePauses,PipelineControl,CaptureLine,StatusUi}.kt`, `PH/ui/banner/{Banners,BannerHost}.kt`, `PH/hub/{ContextHubService,HubSettingsStore}.kt`.

```kotlin
class PausePipelineControl(private val hub: Hub, private val scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis,
    private val legacyFlag: LegacyFlag) : PipelineControl
// enabled = hub.activePauses.map { it.isEmpty() };  setEnabled(false) = pausePhone(null);  setEnabled(true)/toggle-resume = hub.resume(ids shown at tap time)
val lastResumeAtMs: StateFlow<Long?>
object CapturePauses { fun control(app: Context): PipelineControl }   // returns PausePipelineControl(HubGraph.get(app), …)
```
- ⏸ on Home/Activity/banner/notification creates an indefinite **PHONE** record through `Hub.pausePhone(null)`. ▶ resumes **only the ids in `activePauses` at the moment of the tap** (spec §5.5).
- One-time migration (in `CapturePauses.control`, done off Main): if the legacy `isPipelineEnabled` is false, create one indefinite PHONE pause, then `setPipelineEnabled(true)`. Store the marker `pause_migrated_v7` in the `hub_settings` prefs. ⏸ no longer drives the legacy flag. Diagnostics and the legacy Config keep their own switch.
- `CaptureLine`/`StatusUi`: `paused` = any active record. "Paused" for PHONE/QUIET_HOURS; "Paused on glasses" when every active record is GLASSES-origin; "Quiet hours" when only QUIET_HOURS. The ⏸/▶ control is actionable in all three cases (closes the P6 T4 no-op ruling).
- Banners: paused → `CAPTURE_PAUSED` slate, "Capture paused", action "Resume". If a Resume happened ≤ 10 min ago (`lastResumeAtMs`) and records are still active → title "Still paused on glasses — Resume?", action "Resume", which resumes the now-visible ids.
- `ContextHubService` notification: one action, "Pause capture" while running and "Resume capture" while paused. Intents `ACTION_PAUSE`/`ACTION_RESUME` go to `onStartCommand` → hub on its scope. Resume names the ids active at that moment.

**Tests:**
- `PausePipelineControlTest.toggleWhileRunningCreatesPhonePause`: one PHONE record, indefinite.
- `PausePipelineControlTest.resumeNamesOnlyShownIds`: shown {a}, then a hello adds b before the IO runs → `resume({a})`; b stays.
- `PausePipelineControlTest.legacyFalseFlagMigratesOnce`: flag false → one PHONE pause + flag true; a second build → no new pause.
- `BannersTest.stillPausedAfterResumeShowsSecondPrompt`: `lastResume = now − 1 min`, active non-empty → title "Still paused on glasses — Resume?".
- `BannersTest.glassesOnlyPauseHasResumeAction`: action == RESUME (not null).
- `CaptureLineTest.quietHoursOnlyReadsQuietHours`: text "Quiet hours".
- `ContextHubServiceTest.notificationActionFollowsPauseState`: paused → action title "Resume capture".

**Gate:** `./gradlew :phone:testDebugUnitTest`

### Task A4: Settings › Privacy

**Requirements:** R-65, R-17. **Files:** `PH/ui/settings/privacy/PrivacySettings.kt` (route + VM + screen).

- Section "Pause capture": rows "For 30 minutes" (`pausePhone(now + 30 min)`), "For 1 hour" (+60 min) and "Until I resume" (null).
- "Active pauses" list, one row per record: "On glasses" / "On phone" / "Quiet hours" + "until 14:30" or "until you resume". Single button "Resume all shown" (the shown ids).
- "Quiet hours" switch (default off). "From" and "To" are 15-minute-step pickers (default 22:00–07:00) written through `deps.hud.update { it.copy(quietHours = QuietHours(s, e)) }`. Off → `quietHours = null`. Equal start/end → inline "Start and end must differ", and Save is blocked.
- "Battery policy" card is read-only: "Capture defers below 20 % phone battery · glasses stop below 10 %". Values come from the existing policy constants. Read them, don't hard-code a second copy.
- Stateless `PrivacyScreen(state, callbacks)` and a `PrivacyViewModel` with an injected `work` dispatcher.

**Tests (`PrivacySettingsTest`, Robolectric Compose):**
- `thirtyMinutesCreatesTimedPhonePause`: fake hub gets `pausePhone(now + 1 800 000)`.
- `quietHoursEqualBoundsBlocked`: shows the error text; `hud.update` not called.
- `quietHoursOffClearsSetting`: `quietHours == null` after toggling off.
- `activePauseRowsLabelOrigin`: GLASSES record → "On glasses · until you resume".

**Gate:** `./gradlew :phone:testDebugUnitTest --tests '*Privacy*'`, then the stream gate.

---

## Stream B — Actions (R-62, R-63)

**Owns:** `CON/actions/**` (`ActionsGraph`, `ActionEngine`, `ActionSettings`, `ActionCopy`, `executors/*`, `ReminderReceiver`, `BootReceiver`); `CON/tools/{ToolConnector,McpConnector}.kt`; `services/connectors/src/main/AndroidManifest.xml`; `PH/actions/{PhoneActions,HubActionsImpl,ProposalNotifier,ProposalActionReceiver}.kt`; `PH/ui/actions/**` (`ProposalCard`, `ActionsUi`); `PH/ui/ask/**`; `PH/ui/settings/connectors/{ActionsSettings,ShoppingList}.kt`; `GL/hud/InputRouter.kt`; `GL/MainActivity.kt`; `CH/render/HudPresenter.kt`; `phone/src/main/AndroidManifest.xml` (the proposal receiver only).
**Must not touch:** `CON/sources/**`, `ORC/**`, `MEM/**` (it uses `ProposalRepository`/`ShoppingDao` as fixed), the hub files, `GL/pause/**`, `GL/GlassesRuntime.kt`.

### Task B1: ActionEngine — the behaviour matrix and intake

**Requirements:** R-62, R-33. **Files:** `CON/actions/{ActionsGraph,ActionEngine,ActionSettings,ActionCopy}.kt`.

```kotlin
enum class ActionType { REMINDER, SHOPPING_ADD, CALENDAR_EVENT }
data class ActionToggle(val enabled: Boolean, val alwaysConfirm: Boolean = true)
class ActionSettingsStore(prefs: SharedPreferences) { val settings: StateFlow<Map<ActionType, ActionToggle>>; fun set(t: ActionType, v: ActionToggle) }
enum class HudSurface { CONFIRM, UNDO_TOAST, NONE }
data class Proposed(val proposalId: String, val state: ProposalState, val hud: HudSurface, val undoUntilMs: Long?)
class ActionEngine(repo: ProposalRepository, settings: ActionSettingsStore, executors: Map<ActionType, ActionExecutor>,
    verbosity: () -> Verbosity, scope: CoroutineScope, work: CoroutineDispatcher = Dispatchers.IO, clock: () -> Long, newId: () -> String) : ProposalIntake {
  val events: SharedFlow<Proposed>
  suspend fun propose(origin: ProposalOrigin, originId: String, a: ProposedAction, nowMs: Long): Proposed?
  suspend fun respond(proposalId: String, yes: Boolean, by: String): Resolve   // by ∈ hud|phone|notification
  suspend fun undo(proposalId: String, by: String): Boolean                    // only while now < undoUntil (10 s)
  suspend fun recover()                                                         // EXECUTING → re-execute (idempotent); expireDue
}
object ActionsGraph { fun get(c: Context): ActionsGraphApi; fun install(c: Context, verbosity: () -> Verbosity) }  // ActionsGraphApi adds `engine`
```
- Defaults: REMINDER on, SHOPPING_ADD on, CALENDAR_EVENT **off** (it needs WRITE_CALENDAR). `alwaysConfirm` defaults to on for each.
- Params: `paramsJson` = canonical JSON of the action (kotlinx); `type` = enum name. `dueAt`/`startAt` are local → instant in the system zone at receipt (DST gap shifts forward). `endAt` absent → start + 30 min. Shopping items are deduped by `name.trim().lowercase()` (first wins).
- Already past at receipt (`dueAt`/`startAt` ≤ now) → stored `EXPIRED`, log "PAST". Returns `Proposed(…, EXPIRED, NONE)`.
- Matrix (spec §5.3): disabled → `SUPPRESSED`. Enabled + confirm → `PENDING`, with `expiresAt` = now + 60 000 (ASK) / now + 600 000 (NUDGE). HUD = CONFIRM iff ASK, or NUDGE and verbosity = MAX. Enabled + no confirm → insert as `EXECUTING`, execute, then `EXECUTED`; HUD = UNDO_TOAST iff the HUD would have shown a Confirm, else NONE; `undoUntil` = now + 10 000.
- Expiry: on PENDING, schedule `scope.launch { delay(expiresAt − now); repo.expireDue(clock()) }`. `recover()` runs at `install` and sweeps expiry too.
- `respond(yes)` → `resolve(APPROVE)`; `Won(EXECUTING)` → execute → `markExecuted` / `markFailed`. `respond(no)` → `resolve(DECLINE)`. A late answer gets `Lost(current)`. No surface ever turns a timeout into ✓.
- `onProposed` (intake) = `propose(...)?.proposalId`. It catches everything (SafeLog) and returns null on failure. It never fails the ask.

**Tests (`ActionEngineTest`):**
- `disabledTypeIsSuppressed`: state SUPPRESSED, hud NONE, no executor call.
- `askConfirmShowsHudConfirmAtAnyVerbosity`: SILENT → hud CONFIRM, expiresAt = now + 60 000.
- `nudgeConfirmHudOnlyAtMax`: MEDIUM → NONE; MAX → CONFIRM; expiresAt = now + 600 000.
- `noConfirmExecutesImmediatelyWithUndoWindow`: EXECUTED, `undoUntil == now + 10 000`, executor called once.
- `pastDueAtStoredExpired`: dueAt = now − 5 s → EXPIRED, no executor call.
- `dstGapDueAtShiftsForward`: 02:30 on a spring-forward date in `Europe/Berlin` → the instant equals 03:30 local.
- `duplicateShoppingNamesDeduped`: ["Milk","milk ","दूध","दूध"] → 2 items.
- `expiryFiresWithoutAnswer`: advance 60 s → EXPIRED; `respond` → `Lost(EXPIRED)`.
- `intakeNeverThrows`: the executor throws → `onProposed` returns the id, state FAILED.

**Gate:** `./gradlew :services:connectors:testDebugUnitTest`

### Task B2: Executors — reminder, shopping, calendar; idempotency, undo, boot re-arm

**Requirements:** R-62. **Files:** `CON/actions/executors/{ActionExecutor,ReminderExecutor,ShoppingExecutor,CalendarExecutor}.kt`, `CON/actions/{ReminderReceiver,BootReceiver}.kt`, connectors `AndroidManifest.xml`.

```kotlin
interface ActionExecutor { suspend fun execute(p: ProposalEntity): ExecResult; suspend fun undo(p: ProposalEntity): Boolean }
sealed interface ExecResult { data class Done(val createdRef: String) : ExecResult; data class Failed(val code: FailCode) : ExecResult }
enum class FailCode { NO_PERMISSION, NO_CALENDAR, NOTIFICATIONS_OFF, UNKNOWN }
```
- **Reminder:** `AlarmManager` with `PendingIntent.getBroadcast(requestCode = proposalId.hashCode(), Intent(ReminderReceiver).setData("pc-reminder://<proposalId>"), FLAG_IMMUTABLE|FLAG_UPDATE_CURRENT)`, so re-arming is idempotent. `canScheduleExactAlarms()` → `setExactAndAllowWhileIdle`, else `setWindow(dueAt, 10 min)`. `createdRef = "alarm:<proposalId>"`. Undo = `cancel` the same PendingIntent. The receiver posts on channel `reminders` ("Reminders"): title = reminder title, text = note or "Reminder". `POST_NOTIFICATIONS` denied → still `Done` (the alarm is set), and log code NOTIFICATIONS_OFF.
- **BootReceiver** (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`): re-arms every `EXECUTED` REMINDER whose `dueAt` > now. It runs through `goAsync` + IO and is never on Main for DB.
- **Shopping:** row ids `"<proposalId>#<i>"` with `proposalId` set; `insertIgnore`, so a retry adds nothing new. `createdRef = "shopping:<count>"`. Undo = `deleteForProposal`.
- **Calendar:** first query `Events` with `UID_2445 = "pc-<proposalId>"`. If it exists → `Done("event:<id>")`, no second insert. Calendar = the primary visible calendar with access ≥ CONTRIBUTOR. None → `Failed(NO_CALENDAR)`. `SecurityException` → `Failed(NO_PERMISSION)`. Undo = delete by event id.
- Manifest: `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, `POST_NOTIFICATIONS`, `WRITE_CALENDAR`, `READ_CALENDAR`, plus the two receivers (not exported except boot).
- `ActionCopy` failure titles: NO_PERMISSION → "Calendar permission needed"; NO_CALENDAR → "No calendar on this phone"; UNKNOWN → "Couldn't add that — try again".

**Tests:**
- `ReminderExecutorTest.reExecuteDoesNotDuplicateAlarm` (Robolectric `ShadowAlarmManager`): execute twice → 1 scheduled alarm.
- `ReminderExecutorTest.inexactWhenExactNotAllowed`: `canScheduleExactAlarms=false` → window alarm, `Done`.
- `ReminderExecutorTest.undoCancelsAlarm`: 0 alarms after undo.
- `BootReceiverTest.rearmsOnlyFutureExecutedReminders`: past, future, FAILED → 1 alarm.
- `ShoppingExecutorTest.retryAfterCrashAddsNoDuplicates`: execute, then execute again → item count unchanged.
- `CalendarExecutorTest.existingUidIsReused` (fake `ContentResolver`/provider): 1 insert across 2 executes.
- `CalendarExecutorTest.securityExceptionIsNoPermission`: `Failed(NO_PERMISSION)`.
- `ActionEngineRecoveryTest.executingRowReExecutedOnRecover`: EXECUTING → EXECUTED, executor idempotent (1 effect).

**Gate:** `./gradlew :services:connectors:testDebugUnitTest`

### Task B3: Hub port, HUD Confirm and undo toast, glasses double-tap undo

**Requirements:** R-62, R-31. **Files:** `PH/actions/{PhoneActions,HubActionsImpl}.kt`, `GL/hud/InputRouter.kt`, `GL/MainActivity.kt`, `CH/render/HudPresenter.kt`.

- `PhoneActions.hubPort(app, hud)`: `ActionsGraph.install(app) { hud().verbosity }` → `HubActionsImpl(engine)`. It collects `engine.events` and pending/terminal changes.
- `overlay`: CONFIRM proposal while PENDING → `Overlay.Confirm(id, title, detail)`. Cleared on any terminal state or expiry. An UNDO_TOAST → `Overlay.Toast("Added · double-tap undo", undoProposalId = id)` for 10 s, then null. The newest proposal wins.
- Confirm copy: REMINDER title "Remind me?", detail "<title> · <EEE HH:mm>"; SHOPPING "Add to shopping list?", detail "milk, eggs +2" (first 2 names + "+N"); CALENDAR "Add to calendar?", detail "<title> · <EEE HH:mm>". Each is ≤ 60 chars, ellipsised with "…".
- `onConfirm(id, yes)` → `engine.respond(id, yes, "hud")`. `onUndo(id)` → `engine.undo(id, "hud")`. A late or second vote is a no-op, and the overlay clears.
- Glasses `InputRouter`: overlay `Toast` with `undoProposalId != null` + `DoubleTapped` → `Routed.Undo(id)`. It does **not** toggle pause. `MainActivity` sends `Command.Undo(id)`. Other toasts keep today's routing.
- `HudPresenter`: a toast with `undoProposalId` lasts `UNDO_TOAST_MS = 10_000`; plain toasts stay 3 s. Confirm stays 15 s without voting (existing).

**Tests:**
- `HubActionsImplTest.confirmOverlayClearsOnPhoneDecline`: phone respond(no) → overlay null.
- `HubActionsImplTest.secondHudVoteIsNoOp`: two `onConfirm` → executor once.
- `HubActionsImplTest.undoToastLastsTenSeconds`: at 9.9 s present; at 10.1 s null.
- `HubActionsImplTest.shoppingDetailShowsTwoNamesPlusCount`: detail == "milk, eggs +2".
- `InputRouterTest.doubleTapOnUndoToastUndoesNotPauses`: → `Routed.Undo("p")`.
- `HudPresenterTest.undoToastVisibleForTenSeconds`: visible at 9 s, hidden at 11 s; a plain toast is hidden at 4 s.

**Gate:** `./gradlew :phone:testDebugUnitTest --tests '*HubActions*' :glasses:testDebugUnitTest :core:hud:testDebugUnitTest`

### Task B4: Phone surfaces — ProposalCard, Ask detail, notifications, Actions & Shopping screens, ToolConnector

**Requirements:** R-62, R-63, R-14. **Files:** `PH/ui/actions/{ActionsUi,ProposalCard}.kt`, `PH/ui/ask/**`, `PH/actions/{ProposalNotifier,ProposalActionReceiver}.kt`, phone manifest (receiver), `PH/ui/settings/connectors/{ActionsSettings,ShoppingList}.kt`, `CON/tools/{ToolConnector,McpConnector}.kt`.

```kotlin
interface ToolConnector { suspend fun listTools(): List<ToolSpec>; suspend fun invoke(name: String, args: JsonObject): JsonObject }
data class ToolSpec(val name: String, val description: String, val inputSchema: JsonObject)
class McpConnector : ToolConnector  // listTools = emptyList(); invoke throws UnsupportedOperationException; never registered
data class ProposalUi(val id: String, val type: ActionType, val summary: String, val state: ProposalState, val canAnswer: Boolean, val canUndo: Boolean, val failure: String?)
```
- `ProposalCard(originId)`: observes `forOrigin`.
  - SUPPRESSED: "Suggested: <summary>" + link "Enable in Settings" (→ `SETTINGS_ACTIONS`); no buttons.
  - PENDING: summary + ✓ "Add" / ✕ "No thanks".
  - EXECUTED: "Added" (+ "Undo" for 10 s).
  - DECLINED: "Declined" (resolvedBy `undo:*` → "Undone").
  - EXPIRED: "Expired". FAILED: the `ActionCopy` title.
  - A late tap → "Already added" / "Declined" / "Expired" as a snackbar.
- Ask detail: the source cards **observe** memory changes (`timeline.memory(id)` flows), so Forget removes them live (P6 T6 carry). `ProposalCard(askId)` sits under the answer.
- `ProposalNotifier`: posts one notification per PENDING proposal (channel `proposals`, "Suggested actions"), with actions "Add" and "No thanks" → `ProposalActionReceiver` → `engine.respond(id, yes, "notification")`. It cancels the notification on any terminal state. No notification permission → skip silently.
- Actions screen: per type a switch + "Always confirm" switch (disabled when off). Enabling Calendar requests WRITE_CALENDAR; denied → switch stays off + "Calendar permission needed". Row "Shopping list" → `ShoppingRoute`. A disabled row "MCP servers" with "Coming in v2".
- Shopping list: open items first, a checkbox toggles `done`, swipe deletes, an empty state "Nothing on your list".

**Tests:**
- `ProposalCardTest.suppressedShowsEnableLinkNoButtons`: "Enable in Settings" shown; no node "Add".
- `ProposalCardTest.lateTapShowsAlreadyAdded`: respond → `Lost(EXECUTED)` → snackbar "Already added".
- `AskDetailViewModelTest.forgottenSourceDisappearsLive`: the memory flow emits null → `sources` loses the card.
- `ProposalNotifierTest.cancelledOnResolve`: PENDING → posted; EXECUTED → cancelled.
- `ActionsSettingsTest.calendarDeniedStaysOff`: the permission callback false → toggle off + text.
- `ActionsSettingsTest.mcpRowDisabledComingInV2`: the node is disabled with text "Coming in v2".
- `McpConnectorTest.notRegisteredAndEmpty`: `ActionsGraph` has no ToolConnector entries; `listTools()` is empty.

**Gate:** `./gradlew :phone:testDebugUnitTest :services:connectors:testDebugUnitTest`, then the stream gate.

---

## Stream C — Data sources, phone capture, nudges (R-64, R-66, R-61)

**Owns:** `CON/sources/**` (`SourcesGraph`, `SourceSettings`, `CalendarSource`, `ContactsSource`, `NotesSource`, `LocationSource`, `EnabledSources`); `ORC/orchestration/nudge/**` (`NudgeTrigger`, `NudgeEngine`, `NudgeWorker`, `NudgeGates`, `NudgeInputs`, `NudgeEnvironment`); `ORC/orchestration/TestBedInputs.kt`; `ORC/work/FrameIngestWorker.kt` (place label only); `MEM/db/NudgeGateDao.kt`; `PH/nudge/PhoneNudges.kt`; `PH/ui/nudge/NudgeDetail.kt`; `PH/ui/home/**`; `PH/ui/settings/connectors/SourcesSettings.kt`; `PH/ui/settings/inputs/PhoneInputs.kt` ("Capture now" only); `PH/capture/PhoneCapture.kt` (new); `phone/src/main/AndroidManifest.xml` (READ_CONTACTS, ACCESS_COARSE_LOCATION, FileProvider path); `phone/src/main/res/xml/` file-provider paths.
**Must not touch:** `CON/actions/**`, `QueueDrainWorker.kt`, `AnswerService.kt`, the hub files, `ui/ask/**`, `ui/memory/**`. `ProposalCard` is called, never edited.

### Task C1: Context sources

**Requirements:** R-64. **Files:** `CON/sources/*`.

```kotlin
enum class SourceKind { CALENDAR, CONTACTS, NOTES, LOCATION }
interface ContextSource { val kind: SourceKind; val permissions: List<String>; suspend fun lines(question: String?, nowMs: Long): List<String> }
class SourceSettingsStore(prefs: SharedPreferences) { val enabled: StateFlow<Set<SourceKind>>; fun set(k: SourceKind, on: Boolean); val nudgesPerDay: StateFlow<Int> /* 0|3|6|10, default 6 */ }
class EnabledSources(sources: List<ContextSource>, settings: SourceSettingsStore, granted: (String) -> Boolean, work: CoroutineDispatcher) : ContextSourceProvider
fun EnabledSources.needsPermission(): StateFlow<Set<SourceKind>>   // enabled but not granted now
```
- All sources default **off**. A source enabled but missing a permission returns nothing and appears in `needsPermission`. It never throws (catch SecurityException → empty).
- Calendar: `Instances` from now to now + 24 h, ≤ 5, sorted by begin. Format: `"event · <title> · <EEE HH:mm>–<HH:mm>[ · <location>]"`, title ≤ 80, location ≤ 60.
- Contacts: only with a question. Tokens of ≥ 3 letters (via `SearchNormalizer`, English and Devanagari) are matched against `DISPLAY_NAME_PRIMARY` with a word-prefix match. ≤ 3 lines `"contact · <name>"`. Never numbers or emails.
- Notes: `ShoppingDao.open(10)` → `"shopping · <name>[ ×<qty><unit>]"`; `NoteDao.recent(5)` → `"note · <text ≤ 200>"`.
- Location: `LocationManager.getLastKnownLocation(NETWORK_PROVIDER ?: PASSIVE)` no older than 30 min → `Geocoder` (on IO, 3 s timeout) → `"place · <subLocality or locality>, <locality or adminArea>"`. Cached 10 min. Also `suspend fun placeLabel(nowMs): String?` for frames.
- `EnabledSources.lines` keeps the order CALENDAR, NOTES, CONTACTS, LOCATION; `AskText.collapse` per line; total ≤ 1 500 chars (drop whole lines from the end). `SourcesGraph.get(c).provider` = this.

**Tests:**
- `EnabledSourcesTest.disabledSourcesContributeNothing`: all off → empty, the fakes never called.
- `EnabledSourcesTest.revokedPermissionYieldsEmptyAndFlag`: enabled + `granted=false` → empty; `needsPermission` = {CALENDAR}.
- `EnabledSourcesTest.boundedToFifteenHundredChars`: 40 long lines → total ≤ 1 500, whole lines only.
- `ContactsSourceTest.matchesHindiAndEnglishNames`: "call राहुल and Priya?" → 2 lines, no digits.
- `ContactsSourceTest.noQuestionNoContacts`: `lines(null)` → empty.
- `CalendarSourceTest.nextTwentyFourHoursOnlyFiveMax` (fake cursor): 7 events, 1 tomorrow+2 d → 5 lines, sorted.
- `LocationSourceTest.staleFixGivesNoPlace`: a fix 31 min old → null.

**Gate:** `./gradlew :services:connectors:testDebugUnitTest`

### Task C2: Sources screen, place labels on frames, phone "Capture now"

**Requirements:** R-64, R-66, R-17. **Files:** `PH/ui/settings/connectors/SourcesSettings.kt`, `ORC/work/FrameIngestWorker.kt`, `PH/ui/settings/inputs/PhoneInputs.kt`, `PH/capture/PhoneCapture.kt`, the phone manifest and file-provider xml.

- Sources screen rows:
  - "Calendar" ("Next 24 hours of events")
  - "Contacts" ("Match names you mention")
  - "Notes" ("Your shopping list and notes")
  - "Location" ("Label photos with a rough place")
  - Turning one on requests its permissions **then** (on demand). Denied → it stays off. Denied forever → "Permission needed · Open settings" (app details intent).
  - Footer: "Used only on this phone to answer and nudge. Never sent to your glasses."
  - Card "Nudges": "Nudges per day" choice 0 / 3 / 6 / 10 (0 = "Off").
- `FrameIngestWorker`: when LOCATION is enabled and granted, set `FrameEntity.placeLabel = SourcesGraph…placeLabel(now)` (one lookup per worker run). Otherwise null. Nothing else changes.
- "Capture now" (Settings › Phone camera & mic): `ActivityResultContracts.TakePicture` into `cacheDir/capture/phone_<ts>.jpg` via FileProvider → `PhoneCapture.ingest(file)` on IO. That scales to 360 px and q62 (the same guardrails as glasses JPEG), writes into `RokidFrameInbox` with `source = "phone"` and enqueues the inbox ingest. Disabled while capture is paused: "Paused — resume to capture". Cancelled → nothing written. Success snackbar "Captured"; failure "Couldn't save the photo".

**Tests:**
- `SourcesSettingsTest.enableRequestsPermissionThenStaysOffWhenDenied`.
- `SourcesSettingsTest.nudgesPerDayZeroShowsOff`.
- `FrameIngestWorkerTest.placeLabelOnlyWhenLocationEnabled`: disabled → null; enabled → fake label stored.
- `PhoneCaptureTest.ingestWritesPhoneSourceFrameAtThreeSixtyPx`: inbox entry source "phone", width ≤ 360.
- `PhoneInputsTest.captureNowDisabledWhilePaused`: node disabled + text "Paused — resume to capture".

**Gate:** `./gradlew :phone:testDebugUnitTest :services:orchestration:testDebugUnitTest`

### Task C3: NudgeEngine — inputs, call, gates

**Requirements:** R-61. **Files:** `ORC/orchestration/nudge/{NudgeTrigger,NudgeWorker,NudgeEngine,NudgeGates,NudgeInputs,NudgeEnvironment}.kt`, `MEM/db/NudgeGateDao.kt`.

```kotlin
data class NudgeHud(val quietHours: QuietHours?, val verbosity: Verbosity, val zone: ZoneId)
object NudgeEnvironment { @Volatile var hud: (() -> NudgeHud)? = null }
class NudgeInputs(memory: MemoryGraph, sources: ContextSourceProvider) { suspend fun build(nowMs: Long, extraText: String? = null): Prepared } // vars + inputIds
class NudgeGates(dao: NudgeGateDao, cap: () -> Int) { suspend fun check(text: String, hud: NudgeHud, nowMs: Long): Gate }
enum class Gate { PASS, QUIET_HOURS, CAP_REACHED, DUPLICATE, OFF }
class NudgeEngine(inputs: NudgeInputs, runner: RoleRunner, gates: NudgeGates, memory: MemoryGraph, intake: ProposalIntake, clock: () -> Long) { suspend fun runOnce(): NudgeRun }
// NudgeGateDao: countSince(fromMs): Int; existsHashSince(hash, fromMs): Boolean; lastNudges(sinceMs): List<NudgeEntity>
```
- `NudgeTrigger.afterSummarizerPass` enqueues the unique work `"nudge_engine"` (KEEP). `NudgeWorker` skips if the last **run** was < 30 min ago (prefs `nudge_last_run_ms`), stamped at run start. With `NudgeEnvironment.hud == null` (hub not built) the run is skipped: fail-closed, because the quiet hours are unknown.
- Inputs: OK memories of the last 6 h, newest 10 (`id=… · HH:mm · <text ≤ 300>`), as data block `memories`. `sources.lines(null, now)` as `sources` in `{{context_sources}}`. Last 24 h nudges (text only) as data block `recent_nudges`. No memories → skip (no model call).
- NUDGE call through `RoleRunner` with `inputIds` = memory ids, so the role's semantic checks enforce `sourceIds ⊆ inputs`. `shouldNudge=false` → done.
- Gates, in order: cap 0 → OFF; quiet hours contains local now → QUIET_HOURS (dropped, not deferred); nudges since local midnight ≥ cap (default 6) → CAP_REACHED; `hash` = SHA-256 hex of `AskText.collapse(text).lowercase()` seen in the last 24 h → DUPLICATE.
- PASS → insert `NudgeEntity(id = "nudge_<uuid>", ts, text ≤ 200, why ≤ 300, sourceIdsJson, priority, hash, shownOn = "phone"|"glance"|"toast" from verbosity SILENT/MEDIUM/MAX, redacted = false)`. Then `proposedAction?.let { intake.onProposed(NUDGE, id, it, now) }`.
- Model failure → `Result.success()` with a trace note "NUDGE_FAILED" (no retry storm). The next summarizer pass retries.

**Tests (`NudgeEngineTest` / `NudgeGatesTest`):**
- `quietHoursDropsNudge`: 23:00 inside 22–07 → QUIET_HOURS, no row.
- `capCountsFromLocalMidnight`: 6 nudges yesterday 23:59 + 0 today → PASS at 00:01.
- `duplicateTextWithinDayDropped`: same text with different spacing and case → DUPLICATE.
- `capZeroIsOff`: OFF, and the model is not called.
- `shownOnFollowsVerbosity`: MAX → "toast"; SILENT → "phone".
- `proposedActionGoesToIntakeWithNudgeOrigin`: the fake intake gets `(NUDGE, nudgeId)`.
- `noRecentMemoriesSkipsModel`: runner not called.
- `workerSkipsWithinThirtyMinutes` (`NudgeWorkerTest`): last run 29 min ago → no engine call.
- `unknownHudSkipsRun`: `NudgeEnvironment.hud = null` → no engine call.

**Gate:** `./gradlew :services:orchestration:testDebugUnitTest :services:memory:testDebugUnitTest`

### Task C4: Nudge delivery — hub port, Home feed, nudge detail, test bed carry

**Requirements:** R-61, R-13, R-06, R-33. **Files:** `PH/nudge/PhoneNudges.kt`, `PH/ui/home/**`, `PH/ui/nudge/NudgeDetail.kt`, `ORC/orchestration/TestBedInputs.kt`.

- `PhoneNudges.hubPort(app, hud)`: sets `NudgeEnvironment.hud = { NudgeHud(hud().quietHours, hud().verbosity, ZoneId.systemDefault()) }`. It observes `nudges.recent(1)` for non-redacted rows newer than port start − 6 h.
  - `latest` = `NudgeLine(text ≤ 80, ts)` when `shownOn != "phone"` and age ≤ 6 h; else null.
  - `overlay` = `Overlay.Nudge(text ≤ 80)` for 8 s only when `shownOn == "toast"` and the row is new since the port started (never replayed after a restart).
  - Redaction (Forget) clears both at once.
- Home "For you": a nudge card tap → `Routes.nudge(id)`, replacing the expand-in-place. `ProposalCard(nudge.id)` shows under the card text. Redacted cards show "Removed — a source was forgotten" and are not tappable.
- Nudge detail: header "Heads up". The text, a "Why" section (the `why`), "Based on" source cards (thumb + text + confidence, the same `SourceCard` look as Ask detail; INVALIDATED/missing hidden), then `ProposalCard(id)`. Unknown id → "This nudge is gone" + back.
- `TestBedInputs` NUDGE now uses `NudgeInputs.build(now, extraText = text)`: the same memories, sources and recent-nudges blocks as production (closes the carry). `inputIds` = memory ids.

**Tests:**
- `PhoneNudgesTest.toastOnlyForNewMaxNudges`: an old row at start → overlay null; a new `toast` row → `Nudge` for 8 s, then null.
- `PhoneNudgesTest.silentNudgeNotOnGlance`: `shownOn="phone"` → latest null.
- `PhoneNudgesTest.redactionClearsLatest`: the row becomes redacted → latest null.
- `HomeScreenTest.nudgeCardOpensDetail`: click → `onOpenNudge("n1")`.
- `NudgeDetailTest.unknownIdShowsGone`.
- `TestBedInputsTest.nudgeUsesProductionInputs`: the prepared vars equal `NudgeInputs.build` output for the same clock (+ the extra text line).

**Gate:** `./gradlew :phone:testDebugUnitTest :services:orchestration:testDebugUnitTest`, then the stream gate.

---

## Stream F — Forget, retention, critic carries (R-77, R-16)

**Owns:** `MEM/forget/ForgetService.kt` (new); `MEM/db/ForgetDao.kt`; `ORC/orchestration/forget/{ResummarizeWorker,RetentionWorker,ForgetActions}.kt` (new); `ORC/work/{QueueDrainWorker,CriticWorkerJob,OrchestrationScheduler}.kt`; `ORC/data/runtime/QueuePayloads.kt`; `ORC/orchestration/{AskActions,PipelineRecords,RetryClassifier}.kt`; `PH/ui/memory/**`.
**Must not touch:** `AnswerService.kt`, `TestBedInputs.kt`, `FrameIngestWorker.kt`, `FeedRepositories.kt`, `ViewDaos.kt`, `ContextDatabase.kt`, the hub files except calling `HubGraph.get(app).refresh()`, `CON/**`, `ui/ask/**`, `ui/home/**`. Keep the S0.1 `NudgeTrigger` call lines in `QueueDrainWorker` exactly where they are.

### Task F1: ForgetService — one transaction cascade

**Requirements:** R-77, R-16. **Files:** `MEM/forget/ForgetService.kt`, `MEM/db/ForgetDao.kt`.

```kotlin
class ForgetService(private val db: ContextDatabase, private val proposals: ProposalRepository, private val clock: () -> Long = System::currentTimeMillis) {
  data class Result(val forgotten: Set<String>, val invalidated: Set<String>, val redactedAsks: Int, val redactedNudges: Int, val framesToDelete: List<String /*path*/>)
  suspend fun forgetFact(memoryId: String): Result
  suspend fun forgetMoment(frameId: String): Result
  suspend fun forgetFacts(ids: Collection<String>, lockFrames: Boolean = true): Result
  companion object { const val REDACTED = "Removed — a source was forgotten" }
}
// ForgetDao: descendants(ids) via WITH RECURSIVE … UNION (cycle-safe); deleteMemories, deleteVectorsFor, deleteEdgesTouching,
// invalidate(ids), insertTombstone, remainingRefs(frameId): Int, asksCiting(likePattern), nudgesCiting(likePattern), redactAsk(id), redactNudge(id), deleteFrame(id)
```
- One `db.withTransaction`:
  1. Closure D = descendants of the forgotten set F over `memory_source` (`memoryId` derived from `sourceMemoryId`), minus F.
  2. Delete F's rows (the FTS triggers follow), their vectors in **all** spaces and every edge touching F.
  3. Set D to `reviewState = INVALIDATED` and delete D's vectors.
  4. `forget_tombstone(frameId)` for each F frame (when `lockFrames`).
  5. Redact asks/nudges citing F ∪ D: a `LIKE '%"<id>"%'` prefilter per id chunk, then an exact JSON parse in Kotlin. Ask: answer = `REDACTED`, sourcesJson `[]`, `redacted = 1`. Nudge: text = `REDACTED`, why `""`, sourceIdsJson `[]`, `redacted = 1`.
  6. `proposals.expireForOrigins(redactedIds)`.
- Forget fact: after commit, return the frame JPEG path in `framesToDelete` only if `remainingRefs(frameId) == 0` and the frame row is kept; the caller deletes the file on IO.
- Forget moment: F = all memories of the frame. Also delete the `frame` row, tombstone the frame and always list its JPEG.
- Every `IN (…)` is chunked at 500. Unknown ids → an empty Result, no error. Trace notes are untouched (they hold ids, never text — pinned by a test).

**Tests (`ForgetServiceTest`, in-memory Room):**
- `transitiveClosureInvalidatesBAndC`: A→B→C; forget A → B, C INVALIDATED; A gone.
- `diamondInvalidatesOnce`: A→B, A→C, B→D, C→D → `invalidated == {B,C,D}`.
- `cycleTerminates`: B→C, C→B, A→B → it completes, `{B,C}` invalidated.
- `closureOver999Ids`: 1 500 descendants → all invalidated; no SQLite variable error.
- `forgetFactTombstonesFrameAndKeepsSiblings`: the frame has m1, m2; forget m1 → m2 stays OK; tombstone exists; `framesToDelete` empty.
- `lastMemoryOfFrameListsJpeg`: forget the only memory → `framesToDelete == [path]`.
- `forgetMomentDeletesFrameAndAllMemories`: frame row gone, 0 memories with that frameId.
- `asksAndNudgesCitingClosureRedacted`: the ask cites C (a descendant) → answer == REDACTED, redacted true; an ask citing "A1" ≠ "A" stays unredacted.
- `pendingProposalOfRedactedAskExpires`: PENDING → EXPIRED.
- `vectorsRemovedFromAllSpaces`: 2 spaces → 0 vectors for F.
- `traceNotesHoldNoMemoryText`: after Forget, no `summary_trace.note` contains the memory text.

**Gate:** `./gradlew :services:memory:testDebugUnitTest`

### Task F2: Re-summarise worker, reprocess path fixes, 👎 critic review, RetryClassifier

**Requirements:** R-77. **Files:** `ORC/orchestration/forget/ResummarizeWorker.kt`, `ORC/work/{QueueDrainWorker,CriticWorkerJob}.kt`, `ORC/data/runtime/QueuePayloads.kt`, `ORC/orchestration/{AskActions,PipelineRecords,RetryClassifier}.kt`.

```kotlin
fun QueuePayloads.reprocess(reason: String, sourceIds: List<String>, frameIds: List<String> = emptyList()): String // kind "priority_reprocess", source_ids_csv, frame_ids_csv, v=2
fun QueuePayloads.criticReview(memoryIds: List<String>): String                                                    // kind "critic_review", memory_ids_csv
class StalePayloadException : IllegalStateException("stale payload")   // never retried
class ResummarizeWorker : CoroutineWorker   // unique work "resummarize", KEEP; enqueue(context)
```
- **No text in queue payloads.** Every PRIORITY_REPROCESS payload carries ids. The drain re-reads the memories at run time and drops missing or INVALIDATED ones. Frames come from `frame_ids_csv` minus tombstoned frames (the extraction lock). It never uses `recentFrames` for reprocess. A payload with `source_context` (old format) or no surviving sources throws `StalePayloadException` → FAIL with trace "Skipped: sources changed". So forgotten text queued before the upgrade never reaches a model.
- Reprocess output is written with `derived = true` plus `memory_source` edges to the surviving sources (`PipelineRecords.appendFact(…, derived, sourceIds)`). Forget therefore cascades to it.
- Batched summarization and backfill exclude tombstoned frames (`PipelineRecords.framesByIds`/`recentFrames` filter `forget_tombstone`). `CriticWorkerJob`'s periodic scan enqueues ids, not text.
- **👎** (`AskActions.rate(up=false)`):
  - Cited, still-OK memories move to NEEDS_REVIEW (`moveReviewState`).
  - It enqueues **one** `critic_review` job (dedupe key = askId; a second 👎 is a no-op) **before** writing the rating, so a failed enqueue leaves the rating unchanged and returns false (P6 T6 carry).
  - The drain runs CRITIC on the surviving NEEDS_REVIEW memories (data blocks `facts` + `source` from non-tombstoned frames). Pass → back to OK; fail → stays NEEDS_REVIEW (Review queue).
  - It never writes a summary (closes the "trusted summary via reprocess" carry). INVALIDATED text is never included (closes the critic carry).
- **ResummarizeWorker** (enqueued by `ForgetActions` after every Forget):
  - Work through the INVALIDATED derived memories in topological order (Kahn; leftover cycle members are treated as having no valid sources).
  - Sources = parents that exist and are not INVALIDATED. None → delete the memory (and its edges/vectors).
  - Otherwise SUMMARIZE over the source texts (`memories` + `critic_notes` = "re-summarise after a source was forgotten") → new id `mem_rs_<uuid>`, `derived = true`, the same tier, OK, `confidence = min(out.confidence, max(source conf))`. Add edges new→sources, re-point child edges old→new, then delete old. Then `EmbeddingWorker.enqueue`.
  - Model failure → `Result.retry()` (backoff 15 min); the memories stay hidden.
- `RetryClassifier`: classify `StalePayloadException`, `EmptyQuestionException` and `NoMemoriesException` by **type** (FAIL), before the legacy wording rules. The wording rules apply only to unknown throwables (closes the carry).

**Tests:**
- `QueueDrainReprocessTest.invalidatedSourceTextNeverSentToModel`: the fake runner captures vars; the forgotten text is absent; the surviving text is present.
- `QueueDrainReprocessTest.legacyTextPayloadSkipped`: a `source_context` payload → runner not called, trace "Skipped: sources changed", outcome FAIL.
- `QueueDrainReprocessTest.summaryHasEdgesAndIsDerived`: the new memory has `derived = true` and 2 `memory_source` edges.
- `QueueDrainReprocessTest.tombstonedFrameNotSentAsImage`: `frame_ids_csv` includes a tombstoned frame → images exclude it.
- `AskActionsTest.thumbsDownQueuesCriticReviewNotSummary`: the drain runs CRITIC pass → memories OK again; memory count unchanged.
- `AskActionsTest.thumbsDownSelectedTwiceEnqueuesOnce`: 2 calls → 1 job; rating −1.
- `AskActionsTest.enqueueFailureKeepsRating`: the enqueue throws → returns false; rating unchanged.
- `ResummarizeWorkerTest.parentProcessedBeforeChildAndEdgesRepointed`: A→B→C with B, C invalid and B's other source S → new B′ from S; C re-summarised from B′; old ids gone.
- `ResummarizeWorkerTest.noSourcesDeletes`: B whose only source was forgotten → deleted.
- `ResummarizeWorkerTest.modelFailureRetriesAndStaysHidden`: `Result.retry`, B still INVALIDATED.
- `RetryClassifierTest.typedFailuresBeatWording`: `NoMemoriesException("…timeout…")` → FAIL.

**Gate:** `./gradlew :services:orchestration:testDebugUnitTest`

### Task F3: Memory detail Forget UI, ForgetActions, retention enforcement

**Requirements:** R-16, R-77, retention carry. **Files:** `PH/ui/memory/**`, `ORC/orchestration/forget/{ForgetActions,RetentionWorker}.kt`, `ORC/work/OrchestrationScheduler.kt`.

```kotlin
object ForgetActions { suspend fun forgetFact(context: Context, memoryId: String): Boolean; suspend fun forgetMoment(context: Context, frameId: String): Boolean } // IO; deletes files; enqueues Resummarize; HubGraph refresh via a callback param
class RetentionWorker : CoroutineWorker  // periodic 24 h, unique "retention", KEEP; OrchestrationScheduler.ensureRetention() called from its existing ensure-periodic path
```
- Memory detail: enable "Forget fact" (always, unless INVALIDATED) and "Forget moment" (only when `frameId != null`).
  - Dialog for Forget fact: title "Forget this fact?"; body "It's removed everywhere, and Proactive Context won't learn it from this photo again. Answers that used it will show as removed."; buttons "Forget" / "Cancel".
  - Dialog for Forget moment: title "Forget this moment?"; body "The photo and every fact from it are deleted. Answers that used them will show as removed."; buttons "Forget" / "Cancel".
  - Success → navigate back + snackbar "Forgotten". Failure → "Couldn't forget that — try again" (no exception text).
  - Buttons are disabled while running; a double-tap runs once.
- `MemoryDetailRoute` passes `ForgetActions` and `onForgotten = { HubGraph.get(app).refresh() }`, so the next `StateFrame` drops the memory from `today`.
- **Retention** (spec §2.4 windows): SHORT older than `shortHours` h, MEDIUM older than `mediumDays` d, LONG older than `longDays` d (by `createdAt`, any review state) → `forgetFacts(chunk of 200, lockFrames = true)`. Frames older than `longDays` with no memories → `forgetMoment`. Redaction applies as for a user Forget (ruling).
- Retention runs while capture is paused (it removes data, never adds). It enqueues `ResummarizeWorker` when anything was invalidated.

**Tests:**
- `MemoryDetailScreenTest.forgetMomentHiddenWithoutFrame`.
- `MemoryDetailScreenTest.forgetFactDialogCopy`: the dialog shows the exact title and body.
- `MemoryDetailViewModelTest.doubleTapForgetRunsOnce`: the fake is called once.
- `MemoryDetailViewModelTest.failureShowsFixedCopy`: the fake throws `IOException("disk /data/x")` → message == "Couldn't forget that — try again".
- `RetentionWorkerTest.tiersUseTheirWindows`: SHORT 25 h old forgotten, MEDIUM 6 d kept, LONG 31 d forgotten.
- `RetentionWorkerTest.oldFrameWithoutMemoriesDeleted`: the frame row and JPEG are gone.
- `RetentionWorkerTest.redactsAsksCitingExpiredMemory`.

**Gate:** `./gradlew :phone:testDebugUnitTest :services:orchestration:testDebugUnitTest`, then the stream gate.

---

## Stream E — Onboarding wizard (R-19, R-02, R-67 probe)

**Owns:** `PH/ui/onboarding/**` (`OnboardingWizard`, `OnboardingSteps`, `OnboardingViewModel`, `OnboardingDeps`, `OnboardingStore`, `WelcomeScreen`).
**Must not touch:** anything outside `ui/onboarding/`. It may **call** existing public APIs: `EndpointRepository`, `KeyVault`, `EndpointTester`, `UrlNormalizer`, `PersonaSettings`, `VoiceGraph`/`VoiceCapabilityService`, `AuthActivity`, `GlassesStatusSource`, `GestureDefaults`, and the Phase 6 voice-status mapping if it is public. Otherwise it maps `VoiceCapabilities` itself with the same strings.

### Task E1: Scaffold + Welcome, Permissions (with probe), Glasses

**Requirements:** R-19, R-02, R-67. **Files:** `OnboardingWizard.kt`, `OnboardingSteps.kt`, `OnboardingViewModel.kt`, `OnboardingDeps.kt`.

```kotlin
enum class Step { WELCOME, PERMISSIONS, GLASSES, AI, PERSONA, GESTURES, DONE }
data class OnboardingUiState(val step: Step, val mic: PermState, val notifications: PermState, val voice: VoiceLine, val glasses: LinkState, val ai: AiStepState, val persona: PersonaState)
enum class PermState { NOT_ASKED, GRANTED, DENIED, DENIED_FOREVER }
class OnboardingViewModel(deps: OnboardingDeps, work: CoroutineDispatcher = Dispatchers.IO) { fun next(); fun back(); /* step callbacks */ }
```
- Scaffold (Live Fit setup): `HeaderGradient` background; a top progress bar of 7 segments (Mint fill); a 96 dp `IconChip`; a centred title (headlineMedium) and body (bodyMedium, InkSoft); a bottom `GradientButton` (primary) + a text button (secondary). System back → previous step (on WELCOME: leave the app). The step survives rotation (`SavedStateHandle`).
- Welcome: "Proactive Context" / "Your glasses notice. Your phone remembers. Ask anything about your day." / "Get started".
- Permissions:
  - Rows "Microphone" (for asking from the phone) and "Notifications" (API 33+, for reminders and suggestions). Calendar: "You'll be asked when you turn on Calendar."
  - Each row: "Allow" → system dialog; denied forever → "Open settings".
  - The voice row shows after the mic decision, via `VoiceCapabilityService.refresh(allowProbe = true)`: "Checking voice…" → "Voice: Supported" / "Voice: Needs speech pack" (+ "Download" API ≥ 34 or "Open settings") / "Voice: Needs Android 13+ for glasses voice" / "Voice: Not supported on this phone — you can type questions".
  - "Continue" is always enabled (nothing here is required).
- Glasses: the link state from `GlassesStatusSource`. "Authorize glasses" → `AuthActivity.launch`. Connected → "Connected ✓" and the primary label "Continue". Secondary "Set up later".

**Tests (`OnboardingWizardTest` / `OnboardingViewModelTest`):**
- `progressHasSevenSegmentsAndAdvances`: after next ×2, segments 1–3 filled.
- `backFromPermissionsReturnsToWelcome`.
- `probeRunsWithAllowProbeAfterMicDecision`: fake service records `refresh(true)` once.
- `voiceNeedsPackShowsDownloadOnApi34`: the text and the "Download" node exist.
- `deniedForeverShowsOpenSettings`.
- `glassesStepSkippable`: "Set up later" → step AI.
- `stepSurvivesRecreation`: restore at GLASSES.

**Gate:** `./gradlew :phone:testDebugUnitTest --tests '*Onboarding*'`

### Task E2: AI endpoint, Persona & language, Gestures, Done

**Requirements:** R-19. **Files:** `OnboardingSteps.kt`, `OnboardingViewModel.kt`, `OnboardingDeps.kt`, `OnboardingStore.kt`, `OnboardingWizard.kt`.

- AI endpoint:
  - A seeded endpoint exists → the card "Use <name>" (selected by default) + "Test".
  - Presets "Azure OpenAI", "OpenAI-compatible", "Ollama (on your network)" each show the URL field (+ "API version" for Azure) and a key field (masked).
  - "Test" → `EndpointTester` → "Works · 420 ms" or its short failure text. Save through `EndpointRepository` + `KeyVault` (key never in Room or logs).
  - Primary "Continue" is enabled after a successful Test or when using the seed. Secondary "Set up later".
  - The URL goes through `UrlNormalizer`; invalid → inline "Check the address".
- Persona & language: language `SegmentedSwitch` English / हिन्दी (writes `PersonaSettings.language`) and persona choice "Concise" / "Friendly" / "Detailed" (writes the persona preset text). Default English + Concise.
- Gestures (read-only): "Tap — Ask", "Double-tap — Pause / resume", "Swipe — Next / previous page", "Long-press — Hi Rokid (reserved)" from `GestureDefaults`. Footer "Change these in Settings › Glasses".
- Done: "You're set" / "Tap your glasses or the mic on Home to ask." / "Open Home" → `OnboardingStore.markComplete()` → `onDone()`.
- `OnboardingStore`: same key and flag. Phase-4 users who completed onboarding are **not** sent through the wizard again (ruling). Writes go off Main.

**Tests:**
- `aiContinueDisabledUntilTestPasses`: a failed test keeps it disabled; a passed test enables it.
- `seedEndpointSelectedByDefault`.
- `invalidUrlShowsCheckTheAddress`.
- `keyNeverInLogsOrRoom`: after save, `endpoint` rows contain no key text; captured logs contain no key.
- `hindiSelectionPersists`: `PersonaSettings.language == "hi"`.
- `doneMarksCompleteAndCallsOnDone`.
- `completedPhase4UserStartsAtHome` (`RoutesTest`-style, via `startRoute(true) == HOME`).

**Gate:** `./gradlew :phone:testDebugUnitTest`, then the stream gate.

---

## File-ownership matrix (no file appears twice after Stream 0)

| Stream | Files (summary) |
|---|---|
| 0 | `settings.gradle.kts`, `services/connectors/**` (skeleton, `ProposalIntake`, `ContextSourceProvider`), orchestration/phone `build.gradle.kts`, `CM/Protocol.kt`, `MEM/{Proposals,FeedRepositories,MemoryGraph}.kt`, `MEM/db/{ContextDatabase,ActionDaos}.kt`, `AnswerService.kt`, `AskOutcome` in `AskActions.kt`, two lines in `QueueDrainWorker.kt`, `PH/hub/{HubPorts,Hub,HubGraph}.kt` + initial `HubPauses`/`StateFrameBuilder` edits, `PH/ui/shell/{Routes,Destinations,SettingsDestinations,PhoneDeps}.kt`, `SettingsRoot.kt`, all placeholder/route files |
| A | `PH/hub/{HubPauses,StateFrameBuilder,HubSettingsStore,ContextHubService}.kt`, `MEM/db/AppDaos.kt`, `PH/ui/status/{CapturePauses,PipelineControl,CaptureLine,StatusUi}.kt`, `PH/ui/banner/*`, `PH/ui/settings/privacy/*`, `GL/pause/*`, `GL/GlassesRuntime.kt`, `GL/link/{HudController,HudSettingsCache}.kt`, `GL/capture/OutboundBuffer.kt` |
| B | `CON/actions/**`, `CON/tools/**`, connectors manifest, `PH/actions/**`, `PH/ui/actions/**`, `PH/ui/ask/**`, `PH/ui/settings/connectors/{ActionsSettings,ShoppingList}.kt`, `GL/hud/InputRouter.kt`, `GL/MainActivity.kt`, `CH/render/HudPresenter.kt`, phone manifest (proposal receiver block) |
| C | `CON/sources/**`, `ORC/orchestration/nudge/**`, `TestBedInputs.kt`, `FrameIngestWorker.kt`, `MEM/db/NudgeGateDao.kt`, `PH/nudge/**`, `PH/ui/nudge/**`, `PH/ui/home/**`, `PH/ui/settings/connectors/SourcesSettings.kt`, `PH/ui/settings/inputs/PhoneInputs.kt`, `PH/capture/**`, phone manifest (permission + provider block), file-provider xml |
| F | `MEM/forget/**`, `MEM/db/ForgetDao.kt`, `ORC/orchestration/forget/**`, `ORC/work/{QueueDrainWorker,CriticWorkerJob,OrchestrationScheduler}.kt`, `QueuePayloads.kt`, `ORC/orchestration/{AskActions,PipelineRecords,RetryClassifier}.kt`, `PH/ui/memory/**` |
| E | `PH/ui/onboarding/**` |

The phone `AndroidManifest.xml` is shared by B and C in **separate, marked blocks** (`<!-- P7-B proposals -->`, `<!-- P7-C sources -->`). S0.2 inserts both empty comment markers, and each stream edits only inside its block. This is the one allowed shared file; any conflict there is resolved by the controller at merge.

## Merge order

1. **Stream 0** → `phase7/nudges-actions` (full gate green). Cut the five worktrees from it.
2. Streams run in parallel. Each finishes with its stream gate green and a report at `.superpowers/sdd/2026-10-12-v1-phase7-nudges-actions/<stream>-report.md`.
3. The controller merges in this order, rebasing each onto the branch tip and rerunning the full gate before `git merge --no-ff`:
   - **A** (pause; the oldest code paths)
   - **F** (queue/drain changes)
   - **C** (nudges call the intake through the S0 placeholder)
   - **B** (fills the intake and `ProposalCard` that C/Home already call)
   - **E** (UI only)
4. After all merges: one combined review (`superpowers:requesting-code-review`) over `phase7/nudges-actions` against the spec sections listed in the header, then the device checks below.

## Device checks (controller-run, phone + glasses)

1. Phone ⏸ on Home → HUD shows ❚❚ within 2 s; glasses buffered frames are dropped (hello `bufferSize` = 0); ▶ on phone → glasses capture resumes.
2. Glasses offline: double-tap pause, then tap ▶ on the phone (before reconnect) → reconnect → still paused + "Still paused on glasses — Resume?" → Resume → capture runs.
3. Phone "For 30 minutes" pause sent before the first hello → glasses stay paused; after hello the HUD countdown/state matches; it ends at 30 min on both sides.
4. Quiet hours set 2 min ahead with the glasses link down → glasses pause on their own; reconnect → phone shows "Quiet hours"; the next night produces a new pause id (Diagnostics log).
5. Nudge at Max → HUD "HEADS UP" toast for 8 s + Glance one-liner; at Silent → phone feed only.
6. Ask "remind me to call mom at 6" → HUD Confirm → ✓ on glasses while tapping ✕ on phone within 1 s → exactly one outcome; the reminder notification fires at 18:00 (also after a phone reboot).
7. Always-confirm off for Shopping → ask to add milk → HUD "Added · double-tap undo" → double-tap → the item is gone from the Shopping list; capture is **not** paused.
8. Calendar action with WRITE_CALENDAR revoked in system settings → "Calendar permission needed"; no crash.
9. Sources: enable Calendar + Location → an Ask about "my next meeting" uses the event; new frames get a place label; nothing about either appears on the HUD.
10. Phone "Capture now" → a photo appears in Today with source phone; disabled while paused.
11. Forget fact on a memory cited by an Ask → the Ask shows "Removed — a source was forgotten"; the HUD Today drops it on the next frame; "Reprocess old photos" never brings it back.
12. Forget moment → the photo and all its facts are gone; Delete all still works.
13. Fresh install → wizard: the Permissions step runs the voice probe and shows the status; AI Test passes; Done lands on Home.

## Rulings (spec silent or the platform forces a choice)

- Phone→glasses Resume travels as additive `HudSettings.resumedPauseIds` (closed intervals ≤ 7 d). A record older than that stays paused (fail-closed) and is resumed explicitly.
- A push before any offset sets `pausesExact = false`. The glasses hold phone-owned timed records as indefinite and provisional until an exact push, which may narrow them. Exact pushes merge most-restrictive.
- The glasses evaluate quiet hours in the phone-sent `zoneId`. Each side mints one random UUID per occurrence, and a resumed occurrence is never re-created.
- Any newly merged active phone pause clears the glasses buffer, the same as a local pause (spec §5.5 "drops buffered frames").
- Phone ⏸ is a PHONE `PauseRecord` (capture only). It no longer drives the legacy pipeline flag. A legacy "off" flag migrates once into an indefinite PHONE pause.
- Reminders have no table (schema v1 frozen). The alarm is keyed by proposalId and re-armed on boot from EXECUTED REMINDER proposals. Without exact-alarm permission the alarm is inexact (10 min window).
- Undo = `EXECUTED → DECLINED` with `resolvedBy = "undo:<surface>"` (the spec has no UNDONE state).
- `Command.Undo` and `Overlay.Toast.undoProposalId` are additive. On an undo toast, glasses double-tap undoes instead of toggling pause.
- Default action toggles: Reminder on, Shopping on, Calendar off (it needs WRITE_CALENDAR).
- A notification with ✓/✕ is posted for every PENDING proposal (both origins) and cancelled on resolve.
- 👎 = critic review of the cited memories (NEEDS_REVIEW; pass → OK). It never writes a summary.
- Retention uses the Forget-fact cascade, including redaction and the extraction lock.
- The nudge daily cap lives on Settings › Data sources ("Nudges per day" 0/3/6/10; 0 = off). Quiet hours drop a nudge (no deferral).
- Nudges run while capture is paused (they use stored memories). Phone "Capture now" is disabled while paused.
- The nudge engine skips a run when the hub hasn't supplied the HUD settings (quiet hours unknown → fail-closed).
- A nudge detail route `nudge/{id}` is added (the spec names a "nudge detail" without a route).
- Phase-4 users who already completed onboarding are not re-run through the wizard.
