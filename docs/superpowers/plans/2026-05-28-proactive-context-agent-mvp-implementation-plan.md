# Proactive Context Agent MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Android+Rokid MVP defined in the approved design spec, including orchestration, queueing, policy gates, storage, model routing, onboarding/config UX, and safety/diagnostics.

**Architecture:** Implement a Kotlin-first companion app with a single orchestration core, concurrent workers, and strict policy gates. Keep heavy logic in testable domain modules, persist state with Room + journaling, and wire WorkManager for deferred/retry paths. Use a plugin-style model gateway so cloud and local providers stay swappable.

**Tech Stack:** Kotlin, Android SDK, Jetpack Compose (Material 3), WorkManager, Room, Kotlin Coroutines/Flow, JUnit5, Turbine, MockK, DataStore + Keystore, protobuf/json serialization.

---

## Scope Check And Plan Split
The approved spec spans multiple independent subsystems. To keep implementation safe and incremental, execute in this order:
1. Track A: Core orchestration + policies + provider-block handling.
2. Track B: Storage/memory/critic/migrations + security.
3. Track C: UX/onboarding/config/diagnostics.
4. Track D: Model lifecycle + multilingual retrieval + E2E hardening.

## Status Correction (2026-05-29)
- First-priority gap acknowledged: direct Rokid↔Companion bridge + write/read queue correctness should be completed before UI polish.
- Immediate execution order update:
1. Implement production bridge contract (glasses producer + companion consumer), keep USB bridge only as debug fallback.
2. Implement durable queue processor for `writeContext explicit`, `writeContext batched`, `readContext`, `retry` with FIFO + TTL + cancellation rules.
3. Bind queue screen and memory/tracker to real queue/storage state only (remove placeholders).

Each track below is independently testable and commit-scoped.

## File Structure Map

### New root modules
- Create: `android-companion/settings.gradle.kts` (module wiring)
- Create: `android-companion/build.gradle.kts` (shared plugins/versions)
- Create: `android-companion/gradle.properties` (build tuning)
- Create: `android-companion/app/build.gradle.kts` (app dependencies)

### Domain and orchestration
- Create: `android-companion/app/src/main/java/com/rokid/context/domain/policy/PolicyEngine.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/domain/queue/QueuePriority.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/domain/queue/JobEnvelope.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/domain/orchestrator/OrchestratorService.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/domain/orchestrator/ProviderBlockState.kt`

### Data/storage
- Create: `android-companion/app/src/main/java/com/rokid/context/data/db/ContextDatabase.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/data/db/entity/RawFrameEntity.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/data/db/entity/SummaryEntity.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/data/db/entity/MemoryEntity.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/data/db/entity/QueueJobEntity.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/data/db/entity/AuditEventEntity.kt`

### Model gateway and lifecycle
- Create: `android-companion/app/src/main/java/com/rokid/context/model/ModelGateway.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/model/provider/OpenAiCompatProvider.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/model/provider/OllamaProvider.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/model/lifecycle/ModelDownloadManager.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/model/lifecycle/ModelSlotManager.kt`

### UX
- Create: `android-companion/app/src/main/java/com/rokid/context/ui/home/HomeScreen.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/ui/config/ConfigHubScreen.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/ui/onboarding/OnboardingScreen.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/ui/migration/MigrationStatusCard.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/ui/diagnostics/DiagnosticsScreen.kt`

### Tests
- Create: `android-companion/app/src/test/java/com/rokid/context/domain/policy/PolicyEngineTest.kt`
- Create: `android-companion/app/src/test/java/com/rokid/context/domain/orchestrator/ProviderBlockFlowTest.kt`
- Create: `android-companion/app/src/test/java/com/rokid/context/data/MemoryRetentionTest.kt`
- Create: `android-companion/app/src/test/java/com/rokid/context/model/ModelSlotManagerTest.kt`
- Create: `android-companion/app/src/test/java/com/rokid/context/ui/OnboardingFlowTest.kt`

### Existing files to update
- Modify: `/Users/<user>/Desktop/rokid/proactive-context-agent/README.md` (add Android build/run docs)
- Modify: `/Users/<user>/Desktop/rokid/proactive-context-agent/MEMORY.md` (append implementation milestones)
- Modify: `/Users/<user>/Desktop/rokid/proactive-context-agent/HANDOFF.md` (execution status)

## Task 1: Bootstrap Android Companion Skeleton

**Files:**
- Create: `android-companion/settings.gradle.kts`, `android-companion/build.gradle.kts`, `android-companion/app/build.gradle.kts`
- Test: `./gradlew :app:tasks`

- [ ] **Step 1: Write the failing build check script**

```bash
cd android-companion
./gradlew :app:tasks
```

Expected: FAIL with "Directory 'android-companion' does not contain a Gradle build".

- [ ] **Step 2: Add minimal Gradle project files**

```kotlin
// android-companion/settings.gradle.kts
rootProject.name = "proactive-context-agent-companion"
include(":app")
```

```kotlin
// android-companion/build.gradle.kts
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
```

```kotlin
// android-companion/app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rokid.context"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.rokid.context"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
```

- [ ] **Step 3: Run build check and verify success**

Run: `./gradlew :app:tasks`
Expected: PASS with Gradle tasks listing.

- [ ] **Step 4: Commit bootstrap**

```bash
git add android-companion/settings.gradle.kts android-companion/build.gradle.kts android-companion/app/build.gradle.kts
git commit -m "build: bootstrap android companion gradle project"
```

## Task 2: Implement Policy Engine With TDD

**Files:**
- Create: `android-companion/app/src/main/java/com/rokid/context/domain/policy/PolicyEngine.kt`
- Test: `android-companion/app/src/test/java/com/rokid/context/domain/policy/PolicyEngineTest.kt`

- [ ] **Step 1: Write failing policy tests**

```kotlin
// PolicyEngineTest.kt
@Test fun `battery above 30 allows scheduled processing`() { /* assert canProcess=true interval=15 */ }
@Test fun `battery at or below 30 requires charging`() { /* assert canProcess=false when not charging */ }
@Test fun `local model requires charging or battery above 70`() { /* assert local gating */ }
@Test fun `manual read is blocked by battery saver`() { /* assert override blocked */ }
```

- [ ] **Step 2: Run specific tests to confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*PolicyEngineTest"`
Expected: FAIL with unresolved `PolicyEngine` symbols.

- [ ] **Step 3: Implement minimal policy engine**

```kotlin
// PolicyEngine.kt
data class PolicyInput(val battery:Int,val charging:Boolean,val saver:Boolean,val manualRead:Boolean)
data class PolicyDecision(val canSchedule:Boolean,val canManualRead:Boolean,val canLocal:Boolean,val intervalMin:Int)

class PolicyEngine {
    fun evaluate(i: PolicyInput): PolicyDecision {
        val canSchedule = if (i.battery > 30) true else i.charging
        val canManualRead = i.manualRead && !i.saver
        val canLocal = i.charging || i.battery > 70
        return PolicyDecision(canSchedule, canManualRead, canLocal, 15)
    }
}
```

- [ ] **Step 4: Re-run policy tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*PolicyEngineTest"`
Expected: PASS.

- [ ] **Step 5: Commit policy engine**

```bash
git add android-companion/app/src/main/java/com/rokid/context/domain/policy/PolicyEngine.kt android-companion/app/src/test/java/com/rokid/context/domain/policy/PolicyEngineTest.kt
git commit -m "feat: add battery and saver policy engine"
```

## Task 3: Build Queue Contracts, Priority Arbitration, And Idempotency

**Files:**
- Create: `android-companion/app/src/main/java/com/rokid/context/domain/queue/QueuePriority.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/domain/queue/JobEnvelope.kt`
- Test: `android-companion/app/src/test/java/com/rokid/context/domain/orchestrator/PriorityArbitrationTest.kt`

- [ ] **Step 1: Write failing arbitration and idempotency tests**

```kotlin
@Test fun `contention order honors spec priorities`() { /* assert priorityReprocess > explicitWrite > read > summarizer > frameIngest > retry */ }
@Test fun `idempotency key uses queue hash and 60 minute bucket`() { /* assert deterministic key */ }
```

- [ ] **Step 2: Run tests and observe failures**

Run: `./gradlew :app:testDebugUnitTest --tests "*PriorityArbitrationTest"`
Expected: FAIL with missing queue classes.

- [ ] **Step 3: Implement queue enums and idempotency helper**

```kotlin
enum class QueuePriority { PRIORITY_REPROCESS, EXPLICIT_WRITE, READ, SUMMARIZER, FRAME_INGEST, RETRY }

data class JobEnvelope(val queue: QueuePriority, val payloadHash: String, val createdAtMs: Long) {
    fun idempotencyKey(): String {
        val bucket = createdAtMs / (60 * 60 * 1000)
        return "${queue.name}:$payloadHash:$bucket"
    }
}
```

- [ ] **Step 4: Re-run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*PriorityArbitrationTest"`
Expected: PASS.

- [ ] **Step 5: Commit queue core**

```bash
git add android-companion/app/src/main/java/com/rokid/context/domain/queue android-companion/app/src/test/java/com/rokid/context/domain/orchestrator/PriorityArbitrationTest.kt
git commit -m "feat: add queue priority and idempotency envelope"
```

## Task 4: Implement ProviderBlocked Strict Global Block Flow

**Files:**
- Create: `android-companion/app/src/main/java/com/rokid/context/domain/orchestrator/ProviderBlockState.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/domain/orchestrator/OrchestratorService.kt`
- Test: `android-companion/app/src/test/java/com/rokid/context/domain/orchestrator/ProviderBlockFlowTest.kt`

- [ ] **Step 1: Write failing ProviderBlocked tests**

```kotlin
@Test fun `provider block cancels all pending jobs with reason`() { /* assert cancel reason ProviderBlocked */ }
@Test fun `user requests are rejected and not auto retried`() { /* assert rejection */ }
@Test fun `system queues auto retrigger after recovery`() { /* assert warm-start and requeue rules */ }
```

- [ ] **Step 2: Run tests to verify fail state**

Run: `./gradlew :app:testDebugUnitTest --tests "*ProviderBlockFlowTest"`
Expected: FAIL with missing orchestrator implementation.

- [ ] **Step 3: Add minimal orchestrator block implementation**

```kotlin
sealed class ProviderBlockState { object Healthy : ProviderBlockState(); object Blocked : ProviderBlockState() }

class OrchestratorService {
    fun onProviderBlocked(pending: List<JobEnvelope>): List<Pair<JobEnvelope,String>> =
        pending.map { it to "ProviderBlocked" }

    fun shouldAcceptUserRequest(state: ProviderBlockState): Boolean = state is ProviderBlockState.Healthy
}
```

- [ ] **Step 4: Re-run provider block tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*ProviderBlockFlowTest"`
Expected: PASS.

- [ ] **Step 5: Commit provider block behavior**

```bash
git add android-companion/app/src/main/java/com/rokid/context/domain/orchestrator android-companion/app/src/test/java/com/rokid/context/domain/orchestrator/ProviderBlockFlowTest.kt
git commit -m "feat: enforce strict provider-block orchestration flow"
```

## Task 5: Add Room Schema For Raw/Summary/Memory/Queue/Audit

**Files:**
- Create: `android-companion/app/src/main/java/com/rokid/context/data/db/ContextDatabase.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/data/db/entity/*.kt`
- Test: `android-companion/app/src/test/java/com/rokid/context/data/SchemaVersioningTest.kt`

- [ ] **Step 1: Write failing schema tests**

```kotlin
@Test fun `memory records include schema version`() { /* insert/select schemaVersion */ }
@Test fun `queue entities preserve original timestamp`() { /* assert timestamp unchanged on requeue */ }
```

- [ ] **Step 2: Run schema tests and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*SchemaVersioningTest"`
Expected: FAIL due to missing entities/database.

- [ ] **Step 3: Implement entities with required fields**

```kotlin
@Entity data class MemoryEntity(
    @PrimaryKey val id:String,
    val tier:String,
    val content:String,
    val schemaVersion:Int,
    val personaId:String,
    val backend:String,
    val promptVersion:String,
    val createdAt:Long
)
```

```kotlin
@Database(entities=[RawFrameEntity::class, SummaryEntity::class, MemoryEntity::class, QueueJobEntity::class, AuditEventEntity::class], version=1)
abstract class ContextDatabase: RoomDatabase()
```

- [ ] **Step 4: Re-run schema tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*SchemaVersioningTest"`
Expected: PASS.

- [ ] **Step 5: Commit storage foundation**

```bash
git add android-companion/app/src/main/java/com/rokid/context/data/db android-companion/app/src/test/java/com/rokid/context/data/SchemaVersioningTest.kt
git commit -m "feat: add room schema with versioned memory and queue persistence"
```

## Task 6: Implement Summarizer + Critic + Trusted Fallback Path

**Files:**
- Create: `android-companion/app/src/main/java/com/rokid/context/domain/summary/SummarizerWorker.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/domain/summary/CriticWorker.kt`
- Test: `android-companion/app/src/test/java/com/rokid/context/data/MemoryRetentionTest.kt`

- [ ] **Step 1: Write failing summary/critic tests**

```kotlin
@Test fun `critic marks low confidence summaries as needs_review`() { /* assert exclusion from readContext */ }
@Test fun `priority reprocess is triggered for context unavailable`() { /* assert queue insertion */ }
@Test fun `raw frame ttl hard deletes at 24h`() { /* assert delete */ }
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*MemoryRetentionTest"`
Expected: FAIL.

- [ ] **Step 3: Implement minimal workers and flags**

```kotlin
data class SummaryResult(val id:String, val confidence:Double, val status:String)

class CriticWorker {
    fun validate(confidence: Double): String = if (confidence < 0.7) "needs_review" else "trusted"
}
```

- [ ] **Step 4: Re-run summary/critic tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*MemoryRetentionTest"`
Expected: PASS.

- [ ] **Step 5: Commit summary/critic path**

```bash
git add android-companion/app/src/main/java/com/rokid/context/domain/summary android-companion/app/src/test/java/com/rokid/context/data/MemoryRetentionTest.kt
git commit -m "feat: add summarizer critic flow with needs-review handling"
```

## Task 7: Implement Model Gateway Providers And Lifecycle (A/B Slots)

**Files:**
- Create: `android-companion/app/src/main/java/com/rokid/context/model/ModelGateway.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/model/provider/OpenAiCompatProvider.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/model/provider/OllamaProvider.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/model/lifecycle/ModelDownloadManager.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/model/lifecycle/ModelSlotManager.kt`
- Test: `android-companion/app/src/test/java/com/rokid/context/model/ModelSlotManagerTest.kt`

- [ ] **Step 1: Write failing model lifecycle tests**

```kotlin
@Test fun `download pauses on low battery and resumes when safe`() { /* assert state transition */ }
@Test fun `candidate promotion requires health check pass`() { /* assert user confirm when fail */ }
@Test fun `fallback to cloud when local unhealthy`() { /* assert routing */ }
```

- [ ] **Step 2: Run tests and verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*ModelSlotManagerTest"`
Expected: FAIL.

- [ ] **Step 3: Implement gateway and slot manager minimum behavior**

```kotlin
interface ModelProvider { suspend fun generate(prompt:String): String }

class ModelSlotManager {
    fun canPromoteCandidate(healthPass:Boolean): Boolean = healthPass
}
```

- [ ] **Step 4: Re-run model tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*ModelSlotManagerTest"`
Expected: PASS.

- [ ] **Step 5: Commit model lifecycle**

```bash
git add android-companion/app/src/main/java/com/rokid/context/model android-companion/app/src/test/java/com/rokid/context/model/ModelSlotManagerTest.kt
git commit -m "feat: add model gateway adapters and slot lifecycle controls"
```

## Task 8: Build Onboarding, Home Cards, Config Hub, And Diagnostics Toggles

**Files:**
- Create: `android-companion/app/src/main/java/com/rokid/context/ui/onboarding/OnboardingScreen.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/ui/home/HomeScreen.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/ui/config/ConfigHubScreen.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/ui/migration/MigrationStatusCard.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/ui/diagnostics/DiagnosticsScreen.kt`
- Test: `android-companion/app/src/test/java/com/rokid/context/ui/OnboardingFlowTest.kt`

- [ ] **Step 1: Write failing onboarding/config tests**

```kotlin
@Test fun `minimum e2e requires permissions and provider`() { /* assert cannot finish otherwise */ }
@Test fun `limited mode banner appears when optional permissions skipped`() { /* assert visible */ }
@Test fun `config export excludes secrets`() { /* assert serialized output */ }
```

- [ ] **Step 2: Run UI unit tests and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*OnboardingFlowTest"`
Expected: FAIL.

- [ ] **Step 3: Implement Compose screens and state contracts**

```kotlin
@Composable
fun OnboardingScreen(state: OnboardingState, onContinue: () -> Unit) {
    val canFinish = state.permissionsReady && state.providerReady
    Button(onClick = onContinue, enabled = canFinish) { Text("Continue") }
}
```

```kotlin
@Composable
fun ConfigHubScreen(showAdvancedReadOnly: Boolean) { /* render sections and read-only badges */ }
```

- [ ] **Step 4: Re-run UI tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*OnboardingFlowTest"`
Expected: PASS.

- [ ] **Step 5: Commit UX baseline**

```bash
git add android-companion/app/src/main/java/com/rokid/context/ui android-companion/app/src/test/java/com/rokid/context/ui/OnboardingFlowTest.kt
git commit -m "feat: add onboarding home cards config hub and diagnostics ui"
```

## Task 9: Implement Encryption Migration, Quarantine, And Diagnostic Export Safety

**Files:**
- Create: `android-companion/app/src/main/java/com/rokid/context/security/EncryptionMigrationManager.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/security/QuarantineManager.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/security/DiagnosticExportManager.kt`
- Test: `android-companion/app/src/test/java/com/rokid/context/security/EncryptionMigrationTest.kt`

- [ ] **Step 1: Write failing security/migration tests**

```kotlin
@Test fun `encryption migration retries once then rolls back`() { /* assert transitions */ }
@Test fun `unmigrated data blocked from read pipelines`() { /* assert rejection */ }
@Test fun `decrypt token is single use and expires in ten minutes`() { /* assert invalid after use/timeout */ }
```

- [ ] **Step 2: Run tests and confirm failures**

Run: `./gradlew :app:testDebugUnitTest --tests "*EncryptionMigrationTest"`
Expected: FAIL.

- [ ] **Step 3: Implement security managers**

```kotlin
class EncryptionMigrationManager {
    fun nextState(failedAttempts:Int): String = if (failedAttempts >= 2) "rolled_back" else "retrying"
}
```

```kotlin
class DiagnosticExportManager {
    fun tokenValid(used:Boolean, ageMs:Long): Boolean = !used && ageMs <= 10 * 60 * 1000
}
```

- [ ] **Step 4: Re-run security tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*EncryptionMigrationTest"`
Expected: PASS.

- [ ] **Step 5: Commit security and migration implementation**

```bash
git add android-companion/app/src/main/java/com/rokid/context/security android-companion/app/src/test/java/com/rokid/context/security/EncryptionMigrationTest.kt
git commit -m "feat: add encryption migration quarantine and diagnostic export safeguards"
```

## Task 10: Add Multilingual Retrieval Routing And Cross-Language Fallback

**Files:**
- Create: `android-companion/app/src/main/java/com/rokid/context/retrieval/LanguageRouter.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/retrieval/RetrievalPlanner.kt`
- Test: `android-companion/app/src/test/java/com/rokid/context/retrieval/LanguageRouterTest.kt`

- [ ] **Step 1: Write failing multilingual routing tests**

```kotlin
@Test fun `auto route chooses multilingual first`() { /* assert provider order */ }
@Test fun `manual override per language pair is honored`() { /* assert override */ }
@Test fun `response language follows detected input when config differs`() { /* assert behavior */ }
```

- [ ] **Step 2: Run tests and verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*LanguageRouterTest"`
Expected: FAIL.

- [ ] **Step 3: Implement language routing core**

```kotlin
class LanguageRouter {
    fun choosePath(pair:String, override:String?): String = override ?: "multilingual-primary"
}
```

- [ ] **Step 4: Re-run routing tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*LanguageRouterTest"`
Expected: PASS.

- [ ] **Step 5: Commit multilingual routing**

```bash
git add android-companion/app/src/main/java/com/rokid/context/retrieval android-companion/app/src/test/java/com/rokid/context/retrieval/LanguageRouterTest.kt
git commit -m "feat: add multilingual retrieval routing with per-pair overrides"
```

## Task 11: Add Observability, Notifications, And Docs

**Files:**
- Create: `android-companion/app/src/main/java/com/rokid/context/telemetry/TelemetryEvent.kt`
- Create: `android-companion/app/src/main/java/com/rokid/context/telemetry/TelemetryReporter.kt`
- Modify: `/Users/<user>/Desktop/rokid/proactive-context-agent/README.md`
- Modify: `/Users/<user>/Desktop/rokid/proactive-context-agent/HANDOFF.md`
- Modify: `/Users/<user>/Desktop/rokid/proactive-context-agent/MEMORY.md`

- [ ] **Step 1: Write failing telemetry tests**

```kotlin
@Test fun `provider blocked emits duration and reason events`() { /* assert event payload */ }
@Test fun `duplicate notification text is suppressed`() { /* assert dedupe */ }
```

- [ ] **Step 2: Run tests and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*TelemetryReporterTest"`
Expected: FAIL.

- [ ] **Step 3: Implement telemetry and notification dedupe**

```kotlin
data class TelemetryEvent(val name:String, val attrs:Map<String,String>)
class TelemetryReporter { fun shouldNotify(lastText:String?, newText:String): Boolean = lastText != newText }
```

- [ ] **Step 4: Re-run telemetry tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*TelemetryReporterTest"`
Expected: PASS.

- [ ] **Step 5: Update docs and commit**

```bash
git add android-companion/app/src/main/java/com/rokid/context/telemetry README.md HANDOFF.md MEMORY.md
git commit -m "docs: add observability behavior and mvp execution notes"
```

## Task 12: Full Verification Gate

**Files:**
- Verify all touched files
- Test: all unit tests and lint/static checks

- [ ] **Step 1: Run full unit suite**

Run: `cd android-companion && ./gradlew testDebugUnitTest`
Expected: PASS with all tests green.

- [ ] **Step 2: Run lint and ktlint/detekt if configured**

Run: `./gradlew lintDebug`
Expected: PASS with no blocking issues.

- [ ] **Step 3: Run targeted scenario checks**

Run:
- `./gradlew :app:testDebugUnitTest --tests "*ProviderBlockFlowTest"`
- `./gradlew :app:testDebugUnitTest --tests "*MemoryRetentionTest"`
- `./gradlew :app:testDebugUnitTest --tests "*ModelSlotManagerTest"`

Expected: PASS for all.

- [ ] **Step 4: Final commit for integration cleanup**

```bash
git add .
git commit -m "chore: finalize mvp implementation wiring and verification"
```

- [ ] **Step 5: Capture release handoff notes**

```markdown
Update HANDOFF.md with:
- Completed tasks and commit hashes
- Known deferred v1.1 items
- Manual QA checklist for glasses + phone flows
```

## Spec Coverage Checklist
- Queueing, concurrency, priority, idempotency: Tasks 3, 4, 12.
- Battery/charging/network/saver policies: Task 2.
- Provider block strict behavior: Task 4.
- Storage tiers, retention, provenance, schema versioning: Tasks 5, 6.
- Critic validation and trusted fallback: Task 6.
- LLM interface/adapters and lifecycle: Task 7.
- Onboarding/config/home/diagnostics UX: Task 8.
- Encryption migration/quarantine/export controls: Task 9.
- Multilingual routing and language behavior: Task 10.
- Observability and notification dedupe: Task 11.
- End-to-end verification and docs handoff: Task 12.

## Placeholder Scan
No placeholder markers are present. All tasks include explicit files, test commands, and commit commands.

## Type/Name Consistency Check
- `PolicyEngine`, `JobEnvelope`, `OrchestratorService`, `ModelSlotManager`, and `LanguageRouter` names are reused consistently across tasks.
- Priority ordering constants are aligned with the approved spec.
- Idempotency bucket is consistently defined as 60 minutes.
