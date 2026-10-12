# Phase 4 — Phone Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the legacy god-composable `MainActivity` and its 1.8 s polling loop with a NavHost shell (floating 3-tab PillNav, onboarding gate) and the Live Fit–styled Home, Ask detail, Activity (Today / Week / Month + day drill-down), Memory detail and Frame viewer screens, all fed by Room `Flow`s and the Phase 3 `GlassesStatusSource`; then, after Phase 3 merges, wire the real hub status and delete the transitional legacy facades.

**Architecture:** Every screen is a stateless `XScreen(state, on…)` composable backed by exactly one `ViewModel` that `combine`s repository `Flow`s (new read DAOs in `:services:memory`, schema v1 unchanged), `GlassesStatusSource.status` (interface in `:core:model`, byte-identical to the Phase 3/4 contract) and a `PipelineControl`. Logic lives in pure builders (banner resolver, capture line, period windows, timeline grouping, feed) that are tested on the JVM. `AppShell` hosts the NavHost and PillNav; `MainActivity` only schedules workers and sets content. Until Task 11 the app runs against `FakeGlassesStatusSource` (OFFLINE); Task 11 (post-merge) swaps in `HubGraph.glassesStatus(context)` and replaces `MemoryRecordStore` / `FrameStreamStore` / `SummaryTraceStore` with suspend `PipelineRecords` calls.

**Tech Stack:** Kotlin 2.0.21, Compose BOM 2024.09.00 (Material3, foundation `HorizontalPager`), Navigation Compose 2.8.0, Lifecycle 2.8.5 (`lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`), Room 2.6.1 `Flow` queries, kotlinx-coroutines 1.9.0 (+ `kotlinx-coroutines-test`), WorkManager 2.9.1 (+ `work-testing`), Robolectric 4.13 + `ui-test-junit4`.

**Spec:** `docs/superpowers/specs/2026-10-12-v1-redesign-design.md` §2.1 (navigation), §2.2 (banners), §2.3 (design system), §2.4 (Home, Ask detail, Activity, Memory detail, Frame viewer, Settings root, Onboarding gate), §2.5 (state), §1.4 (removed stub screens, god-composable, polling loop), §1.7 (reconnect only on explicit action), §6.1.1 (Forget — shown disabled here, implemented in Phase 7), §6.3 (no raw errors). Master plan: `docs/superpowers/plans/2026-10-12-v1-master-plan.md` (its Global Constraints apply). Parallel contract (binding): `docs/superpowers/plans/2026-10-12-v1-phase3-4-contract.md`. Mocks: `.superpowers/brainstorm/79603-1791796256/content/phone-home.html` (option **B**, Ask-first), `activity-tab.html` (option **A** + its Week preview), `settings.html` (root list only). Design code ported from `/Users/<user>/Desktop/rokid/live-fitness-tracker` @ `4529735` (files named per task). Phase 2 must be complete; Phase 3 runs at the same time in another worktree.

## Global Constraints

- All master-plan Global Constraints apply (light theme only, `PcColors` tokens, no raw exception text in any UI, commit trailer, Gradle from `android-companion/` with `ANDROID_HOME=/Users/<user>/Desktop/rokid/android-sdk`).
- **File ownership (contract §Ownership, binding).** Tasks 1–10 may touch only: `phone/src/main/java/com/rokid/context/MainActivity.kt`; `phone/src/main/java/com/rokid/context/ui/**`; new `phone/src/main/java/com/rokid/context/phone/ui/**`; `phone/src/test/**`; phone `res/`; `phone/build.gradle.kts` (add lines only); `phone/src/main/AndroidManifest.xml` **`android:icon` / `android:roundIcon` only**; `:core:ui`; `:core:model` `GlassesStatus.kt` + its one build line; `:services:memory` **new DAOs/queries only**; new files in `:services:orchestration`. Never touch `glasses/**`, `phone/.../phone/hub/**`, `ContextApp.kt`, `bridge/**`, `FrameIngestWorker.kt`, `RokidFrameInbox.kt`, or the legacy facades before Task 11.
- `GlassesStatus.kt` is generated from the contract block (Task 1) and must stay byte-identical to it.
- Room schema v1 is frozen: no entity/column change, `ContextDatabase.version = 1`, `services/memory/schemas/` unchanged after every build.
- No DAO/Room call, file scan, image decode or registry sync on the main thread (Phase 2 ruling). ViewModels call Room `suspend`/`Flow` APIs (main-safe) from `viewModelScope`; `FrameDecode`, `StorageUsage`, `LegacyConfigBridge.sync`, `AppConfigStore.setProvider` / `setLocalModeEnabled` (Keystore-backed secrets) and `OrchestrationScheduler` queue/backfill calls run in `Dispatchers.IO`. Plain `SharedPreferences` reads (incl. `AppConfigStore.get`) at construction time are allowed, as in the legacy app. No new `runBlocking` anywhere.
- **No polling.** Screens get data from Room `Flow`s and `GlassesStatusSource.status`. The only timer is the 1 Hz UI clock (`PhoneDeps.ticker`) that re-formats the "next 4:12" countdown and detects local midnight; it performs no I/O. Legacy Diagnostics screens refresh on open, on the Refresh button and after each action.
- Never display `Throwable.message`, `ModelCallException.message` or provider bodies. Ask/test failures map to the fixed strings in `AskActions` / `ConfigTestText`. Log only `ModelCallException.message` (already redacted), never the throwable (Phase 2 ruling). Exception: Settings › Diagnostics › Summarizer tracker shows stored trace notes verbatim (spec §6.3 "full detail → Diagnostics").
- R-20 shape: `@Composable fun XScreen(state: XUiState, on…: callbacks)` — no repository, `Context` or ViewModel access inside screen composables. One ViewModel per screen. Route wrappers (ViewModel creation + `collectAsStateWithLifecycle`) exist only in `phone/ui/shell/Destinations.kt`.
- User-visible strings are English (spec §0.1: English primary). Dates/times are formatted with `Locale.ENGLISH` and `"HH:mm"`, digits via `Locale.US`, so tests are deterministic.
- Layout per spec §2.3: screen h-padding 16 dp, header 20 dp, tile gap 12 dp, card radius 20 dp, NavHost bottom padding 84 dp whenever PillNav is shown; PillNav hidden on `onboarding` and `frames/…`.
- Phone Robolectric tests: `@RunWith(AndroidJUnit4::class)` + `@Config(sdk = [34], application = android.app.Application::class)` (keeps `ContextApp`'s import/seed threads out of tests). In-memory Room via `TestGraph.fresh(ctx)`; ViewModel tests use `MainDispatcherRule`, cancel the VM scope with `vm.cancelScope()` **before** `TestGraph.close(ctx)`.
- Device checks are read-only (`dumpsys`, `uiautomator dump`, `logcat`). `adb install -r` only when `com.rokid.context` is **not** the resumed activity; never `am start` or `input`; ask the user to open the app. `ADB=/Users/<user>/Desktop/rokid/android-sdk/platform-tools/adb`, `S=adb-RZCY902F76K-nmSM2g._adb-tls-connect._tcp`.
- Working directory in steps: Gradle, `adb`, `awk`/`grep` and `git mv`/`git rm` run from `android-companion/`; `git add`/`git commit` run from the repository root (paths start with `android-companion/`).
- Gate (green at 364 tests before this phase): `./gradlew test :phone:assembleDebug :glasses:assembleDebug`. Task 1 disables the phone's *release* unit-test variant (Compose UI tests need the debug-only `ui-test-manifest`, same as `:core:ui`), so the total drops by the phone's release duplicates (6 tests) before new tests are added.

## Review Focus

1. **Raw provider / exception text reaching a screen** (typed Ask failure, legacy Config "Test", AI-blocked banner) → fixed short human strings only. Tests: Task 5 `failureNeverShowsProviderText`, Task 9 `testButtonShowsShortMessageOnly`, Task 4 `aiBlockedBannerTruncatesLongText`.
2. **First launch / empty database / empty period** (no frames, memories, asks or nudges; an all-zero week) → every screen shows an empty state and the bar chart never divides by zero. Tests: Task 2 `allZeroValuesGiveZeroHeights`, Task 5 `emptyFeedShowsHint`, Task 7 `emptyWeekShowsZeroBarsAndHint`.
3. **Frame JPEG missing on disk** (purged, legacy path, moved) → placeholder, no crash, the viewer still swipes. Tests: Task 2 `missingFileShowsPlaceholder`, Task 8 `viewerShowsPlaceholderForMissingFile`.
4. **Stale or malformed navigation arguments** (deleted ask/memory id, `day/2026-13-45`, `activity?period=yearly`) → "no longer available"/default state, no crash. Tests: Task 6 `unknownAskShowsNotFound`, Task 7 `malformedDateShowsNotFound` + `unknownPeriodFallsBackToToday`, Task 8 `deletedMemoryShowsNotFound`, Task 10 `staleAskLinkShowsNotFound`.
5. **Midnight, DST and time-zone changes while the app is open** → Today rolls over at local midnight; week/month buckets use the zone at query time and a DST week still has seven days. Tests: Task 7 `todayRollsOverAtLocalMidnight`, `dstWeekStillHasSevenBars`, `bucketsUseTheZoneGivenAtQueryTime`.

(Also pinned: malformed `sourcesJson` → Task 5 `malformedSourcesJsonGivesNoSources`; NaN/out-of-range confidence → Task 2 `confidenceIsClampedAndNaNShowsDash`; Hindi tags → Task 5 `hindiTagsBecomeSuggestions`.)

## File map

| Area | Files (all under `android-companion/`) | Task |
|---|---|---|
| Contract type | `core/model/.../core/model/GlassesStatus.kt` | 1 |
| Test harness | `phone/build.gradle.kts`; `phone/src/test/.../phone/ui/{Fixtures,MainDispatcherRule}.kt` | 1 |
| Fake status | `phone/src/main/.../phone/ui/glasses/FakeGlassesStatusSource.kt` (moved to `src/test` in 11) | 1, 11 |
| Design system | `core/ui/.../components/{Status,BarChart,FrameImages,GlassesIcon}.kt`; phone `res/mipmap-anydpi-v26/*`, `res/drawable/ic_launcher_*.xml` | 2 |
| Read model | `services/memory/.../memory/db/ViewDaos.kt`, `.../memory/FeedRepositories.kt`; `ContextDatabase.kt`, `MemoryGraph.kt` (additive) | 3 |
| Status + banners | `phone/.../phone/ui/banner/{Banners,BannerHost}.kt`, `phone/.../phone/ui/status/{CaptureLine,StatusUi,StorageUsage,PipelineControl}.kt` | 4 |
| Home + typed Ask | `phone/.../phone/ui/home/{HomeModel,AskService,HomeViewModel,HomeScreen}.kt`; `services/orchestration/.../orchestration/AskActions.kt` | 5 |
| Ask detail | `phone/.../phone/ui/ask/{AskDetailModel,AskDetailViewModel,AskDetailScreen}.kt`; `AskActions.rate` | 6 |
| Activity + Day | `phone/.../phone/ui/activity/{ActivityModel,ActivityViewModel,ActivityScreen,Timeline,DayViewModel,DayScreen}.kt` | 7 |
| Memory + Frames | `phone/.../phone/ui/memory/{MemoryDetailModel,MemoryDetailViewModel,MemoryDetailScreen}.kt`, `phone/.../phone/ui/frames/{FrameViewerViewModel,FrameViewerScreen}.kt` | 8 |
| Settings (interim) | `phone/.../phone/ui/settings/{SettingsRoot,Diagnostics,Queues,Tracker,LegacyConfig}*.kt` | 9 |
| Shell | `phone/.../phone/ui/shell/{Routes,PhoneDeps,Destinations,AppShell}.kt`, `phone/.../phone/ui/onboarding/{OnboardingStore,WelcomeScreen}.kt`, `MainActivity.kt`; legacy `ui/{home,live,memory,migration,onboarding,status,frames,diagnostics}` deleted | 10 |
| Integration | `PhoneDeps.kt` → `HubGraph`; `services/orchestration/.../orchestration/PipelineRecords.kt`; workers/scheduler/inbox migrated; facades deleted | 11 |

`…` = `src/main/java/com/rokid/context` (phone, services) or `src/main/kotlin/com/rokid/context` (core). Phone new-code package: `com.rokid.context.phone.ui.*`.

---

### Task 1: `GlassesStatus` contract type, fake status source, phone test harness

**Requirements:** R-07 (the UI reads hub state through `GlassesStatusSource` instead of polling), R-20 (ViewModel + test harness), R-73

**Files:**
- Modify: `android-companion/core/model/build.gradle.kts`
- Create: `android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/GlassesStatus.kt` (generated from the contract)
- Test: `android-companion/core/model/src/test/kotlin/com/rokid/context/core/model/GlassesStatusTest.kt`
- Modify: `android-companion/phone/build.gradle.kts`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/glasses/FakeGlassesStatusSource.kt`
- Create (test support): `android-companion/phone/src/test/java/com/rokid/context/phone/ui/Fixtures.kt`, `android-companion/phone/src/test/java/com/rokid/context/phone/ui/MainDispatcherRule.kt`
- Test: `android-companion/phone/src/test/java/com/rokid/context/phone/ui/glasses/FakeGlassesStatusSourceTest.kt`, `android-companion/phone/src/test/java/com/rokid/context/phone/ui/ComposeHarnessTest.kt`

**Interfaces:**
- Consumes: `StateFrame`, `CaptureState`, `Health` (`:core:model`, Phase 1); entities `MemoryEntity`, `FrameEntity`, `AskEntity`, `NudgeEntity`, `ProposalEntity`, `MemoryTier`, `ReviewState`, `DatabaseFactory.inMemory`, `MemoryGraph.resetForTests/get` (`:services:memory`, Phase 2).
- Produces:
  - `enum class LinkState { NOT_AUTHORIZED, CONNECTING, CONNECTED, PAUSED_BT, CLOSED_ON_GLASSES, VERSION_MISMATCH, OFFLINE }`; `data class GlassesStatus(link: LinkState, glassesBatteryPct: Int?, bufferedFrames: Int, lastFrameAtMs: Long?, frame: StateFrame?)`; `interface GlassesStatusSource { val status: StateFlow<GlassesStatus>; fun requestReconnect() }` — package `com.rokid.context.core.model`.
  - `class FakeGlassesStatusSource(initial: GlassesStatus = OFFLINE) : GlassesStatusSource` with `fun emit(status: GlassesStatus)`, `val reconnectRequests: Int`, `companion object { val OFFLINE: GlassesStatus; fun connected(frame: StateFrame, batteryPct: Int? = frame.glassesBatteryPct): GlassesStatus }` — package `com.rokid.context.phone.ui.glasses`.
  - Test support (package `com.rokid.context.phone.ui`): `object Fixtures { ZONE; TODAY; NOW; fun at(date, hour, minute = 0): Long; memory(...); frame(...); ask(...); nudge(...); proposal(...); stateFrame(...); jpg(ctx, name): String }`, `object TestGraph { fun fresh(ctx): MemoryGraph; fun close(ctx) }`, `fun ViewModel.cancelScope()`, `class MainDispatcherRule`.

- [ ] **Step 1: Write the failing `:core:model` test**

`core/model/src/test/kotlin/com/rokid/context/core/model/GlassesStatusTest.kt`:
```kotlin
package com.rokid.context.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class GlassesStatusTest {
    @Test fun linkStatesAreTheContractSetInOrder() {
        assertEquals(
            listOf("NOT_AUTHORIZED", "CONNECTING", "CONNECTED", "PAUSED_BT", "CLOSED_ON_GLASSES", "VERSION_MISMATCH", "OFFLINE"),
            LinkState.entries.map { it.name },
        )
    }

    @Test fun sourceExposesTheLastBroadcastFrame() {
        val frame = StateFrame(seq = 1, capture = CaptureState.Running, intervalSec = 300, phoneLinked = true, aiHealth = Health.Ok)
        val source = object : GlassesStatusSource {
            override val status: StateFlow<GlassesStatus> = MutableStateFlow(GlassesStatus(LinkState.CONNECTED, 82, 3, 10L, frame))
            override fun requestReconnect() = Unit
        }
        assertEquals(82, source.status.value.glassesBatteryPct)
        assertEquals(3, source.status.value.bufferedFrames)
        assertEquals(frame, source.status.value.frame)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:model:test --tests "*GlassesStatusTest"`
Expected: FAIL — `Unresolved reference: LinkState` and `Unresolved reference: kotlinx` (no coroutines on the classpath yet).

- [ ] **Step 3: Add the contract's coroutines line**

In `core/model/build.gradle.kts`, inside `dependencies { … }` add exactly (the contract's line; if Phase 3 lands first the identical line merges cleanly):
```kotlin
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
```

- [ ] **Step 4: Generate `GlassesStatus.kt` from the contract and check it is byte-identical**

Run (from `android-companion/`):
```bash
awk '/^```kotlin$/{f=1;next} /^```$/{f=0} f' ../docs/superpowers/plans/2026-10-12-v1-phase3-4-contract.md > core/model/src/main/kotlin/com/rokid/context/core/model/GlassesStatus.kt
awk '/^```kotlin$/{f=1;next} /^```$/{f=0} f' ../docs/superpowers/plans/2026-10-12-v1-phase3-4-contract.md | diff - core/model/src/main/kotlin/com/rokid/context/core/model/GlassesStatus.kt && echo IDENTICAL
```
Expected: `IDENTICAL`. The file must read exactly:
```kotlin
// android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/GlassesStatus.kt
package com.rokid.context.core.model

import kotlinx.coroutines.flow.StateFlow

/** Phone-side view of the glasses link, produced by Phase 3's hub, consumed by Phase 4's UI. */
enum class LinkState { NOT_AUTHORIZED, CONNECTING, CONNECTED, PAUSED_BT, CLOSED_ON_GLASSES, VERSION_MISMATCH, OFFLINE }

data class GlassesStatus(
    val link: LinkState,
    val glassesBatteryPct: Int?,
    val bufferedFrames: Int,
    val lastFrameAtMs: Long?,
    val frame: StateFrame?,        // last StateFrame the phone broadcast (phone is source of truth)
)

interface GlassesStatusSource {
    val status: StateFlow<GlassesStatus>
    fun requestReconnect()         // explicit phone action ("Open on glasses"), per spec §1.7 reconnect policy
}
```
Do not reformat this file (no IDE auto-format); any edit breaks the no-op merge with Phase 3.

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :core:model:test --tests "*GlassesStatusTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Phone build additions**

In `phone/build.gradle.kts`:
- inside `android { … }` add:
```kotlin
    testOptions { unitTests.isIncludeAndroidResources = true }
```
- in `dependencies { … }` add (add-only; keep every existing line):
```kotlin
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    testImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.work:work-testing:2.9.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
```
- after the `dependencies` block add (same rule as `:core:ui`):
```kotlin
// Compose UI tests need ui-test-manifest's host activity, which is debug-only; release unit tests would have none.
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false } }
```

- [ ] **Step 7: Write the failing phone tests**

`phone/src/test/java/com/rokid/context/phone/ui/glasses/FakeGlassesStatusSourceTest.kt`:
```kotlin
package com.rokid.context.phone.ui.glasses

import com.rokid.context.core.model.LinkState
import com.rokid.context.phone.ui.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeGlassesStatusSourceTest {
    @Test fun startsOfflineWithNoFrame() {
        val fake = FakeGlassesStatusSource()
        assertEquals(LinkState.OFFLINE, fake.status.value.link)
        assertNull(fake.status.value.frame)
        assertNull(fake.status.value.glassesBatteryPct)
    }

    @Test fun emitReplacesTheStatusAndReconnectsAreCounted() {
        val fake = FakeGlassesStatusSource()
        fake.emit(FakeGlassesStatusSource.connected(Fixtures.stateFrame()))
        assertEquals(LinkState.CONNECTED, fake.status.value.link)
        assertEquals(82, fake.status.value.glassesBatteryPct)
        fake.requestReconnect(); fake.requestReconnect()
        assertEquals(2, fake.reconnectRequests)
    }
}
```

`phone/src/test/java/com/rokid/context/phone/ui/ComposeHarnessTest.kt`:
```kotlin
package com.rokid.context.phone.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ComposeHarnessTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneModuleRendersCoreUiUnderRobolectric() {
        compose.setContent { PcTheme { SectionLabel("Today") } }
        compose.onNodeWithText("TODAY").assertIsDisplayed()
    }
}
```

- [ ] **Step 8: Run to verify failure**

Run: `./gradlew :phone:testDebugUnitTest --tests "*FakeGlassesStatusSourceTest" --tests "*ComposeHarnessTest"`
Expected: FAIL — `Unresolved reference: FakeGlassesStatusSource` / `Fixtures`.

- [ ] **Step 9: Implement the fake**

`phone/src/main/java/com/rokid/context/phone/ui/glasses/FakeGlassesStatusSource.kt`:
```kotlin
package com.rokid.context.phone.ui.glasses

import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.StateFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stand-in for Phase 3's hub (contract): OFFLINE until [emit] is called. Used by tests and, until the post-merge
 * Task 11 swaps in `HubGraph.glassesStatus(context)`, by the app itself — which truthfully shows "Glasses offline".
 */
class FakeGlassesStatusSource(initial: GlassesStatus = OFFLINE) : GlassesStatusSource {
    private val state = MutableStateFlow(initial)
    override val status: StateFlow<GlassesStatus> = state.asStateFlow()

    var reconnectRequests = 0
        private set

    override fun requestReconnect() { reconnectRequests++ }

    fun emit(status: GlassesStatus) { state.value = status }

    companion object {
        val OFFLINE = GlassesStatus(LinkState.OFFLINE, glassesBatteryPct = null, bufferedFrames = 0, lastFrameAtMs = null, frame = null)

        fun connected(frame: StateFrame, batteryPct: Int? = frame.glassesBatteryPct) =
            GlassesStatus(LinkState.CONNECTED, batteryPct, bufferedFrames = 0, lastFrameAtMs = null, frame = frame)
    }
}
```

- [ ] **Step 10: Test support files**

`phone/src/test/java/com/rokid/context/phone/ui/Fixtures.kt`:
```kotlin
package com.rokid.context.phone.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.StateFrame
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.cancel
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

object Fixtures {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    /** Monday 2026-10-12. */
    val TODAY: LocalDate = LocalDate.of(2026, 10, 12)
    fun at(date: LocalDate, hour: Int, minute: Int = 0): Long = date.atTime(hour, minute).atZone(ZONE).toInstant().toEpochMilli()
    /** 10:00 on [TODAY] in [ZONE]. */
    val NOW: Long = at(TODAY, 10)

    fun memory(
        id: String, text: String, at: Long, frameId: String? = null, confidence: Double = 0.8, tags: String = "",
        review: ReviewState = ReviewState.OK, trusted: Boolean = false,
    ) = MemoryEntity(id, frameId, text, "short_term", tags, MemoryTier.SHORT, confidence, review, trusted, at, false, searchText = "")

    fun frame(id: String, ts: Long, path: String = "/missing/$id.jpg") = FrameEntity(id = id, ts = ts, path = path, source = "rokid_glasses")

    fun ask(
        id: String, ts: Long, question: String = "Where are my keys?", answer: String = "On the hallway shelf.",
        sources: String = "[]", rating: Int? = null, redacted: Boolean = false,
    ) = AskEntity(id, ts, question, answer, sources, rating, "phone_typed", redacted)

    fun nudge(id: String, ts: Long, text: String = "Milk looked almost empty", why: String = "Seen in the fridge at 09:41") =
        NudgeEntity(id, ts, text, why, "[]", 1, "h_$id", "phone", false)

    fun proposal(id: String, originId: String, type: String = "SHOPPING_ADD", state: String = "PENDING") =
        ProposalEntity(id, "ASK", originId, type, "{}", state, NOW + 60_000, null, null, NOW)

    fun stateFrame(
        capture: CaptureState = CaptureState.Running, nextAtMs: Long? = NOW + 252_000, battery: Int? = 82, ai: Health = Health.Ok,
    ) = StateFrame(seq = 1, capture = capture, nextCaptureAtMs = nextAtMs, intervalSec = 300, glassesBatteryPct = battery, phoneLinked = true, aiHealth = ai)

    /** A small non-JPEG file: exists on disk but does not decode (exercises the "Missing" path too). */
    fun jpg(ctx: Context, name: String): String =
        File(ctx.filesDir, name).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }.absolutePath
}

object TestGraph {
    fun fresh(ctx: Context): MemoryGraph { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); return MemoryGraph.get(ctx) }
    fun close(ctx: Context) { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }
}

/** ViewModel scopes outlive a test; cancel them before closing the DB so no collector hits a closed database. */
fun ViewModel.cancelScope() = viewModelScope.cancel()
```

`phone/src/test/java/com/rokid/context/phone/ui/MainDispatcherRule.kt`:
```kotlin
package com.rokid.context.phone.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
```

- [ ] **Step 11: Run to verify pass**

Run: `./gradlew :phone:testDebugUnitTest --tests "*FakeGlassesStatusSourceTest" --tests "*ComposeHarnessTest"`
Expected: PASS (3 tests).

- [ ] **Step 12: Full gate**

Run: `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: BUILD SUCCESSFUL, 0 failures (`:phone:testReleaseUnitTest` no longer exists — expected).

- [ ] **Step 13: Commit**

```bash
git add android-companion/core/model android-companion/phone/build.gradle.kts android-companion/phone/src/main/java/com/rokid/context/phone android-companion/phone/src/test/java/com/rokid/context/phone
git commit -m "feat(phone): GlassesStatus contract type, fake status source, phone UI test harness

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 2: `:core:ui` — StatusStrip, ConfidenceChip, BarChart, FrameThumb; app icon

**Requirements:** R-12 (remaining §2.3 components `StatusStrip`, `ConfidenceChip`, `FrameThumb`, `BarChart`; app icon + adaptive icon)

**Files:**
- Modify: `android-companion/core/ui/build.gradle.kts`
- Create: `android-companion/core/ui/src/main/kotlin/com/rokid/context/core/ui/components/Status.kt`
- Create: `android-companion/core/ui/src/main/kotlin/com/rokid/context/core/ui/components/BarChart.kt`
- Create: `android-companion/core/ui/src/main/kotlin/com/rokid/context/core/ui/components/FrameImages.kt`
- Create: `android-companion/core/ui/src/main/kotlin/com/rokid/context/core/ui/components/GlassesIcon.kt` (copied from Live Fit `phone/src/main/java/com/debasish/livefit/phone/ui/components/GlassesIcon.kt`, package renamed)
- Test: `android-companion/core/ui/src/test/kotlin/com/rokid/context/core/ui/ShellComponentsTest.kt`
- Create: `android-companion/phone/src/main/res/drawable/ic_launcher_background.xml`, `android-companion/phone/src/main/res/drawable/ic_launcher_foreground.xml`, `android-companion/phone/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `android-companion/phone/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` (structure from Live Fit `phone/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` + `drawable/ic_launcher_foreground.xml`)
- Modify: `android-companion/phone/src/main/AndroidManifest.xml` (`android:icon`, add `android:roundIcon` — nothing else)
- Test: `android-companion/phone/src/test/java/com/rokid/context/phone/AppIconTest.kt`

**Interfaces:**
- Consumes: `PcColors`, `PcTheme` (Phase 1).
- Produces (package `com.rokid.context.core.ui.components`):
  - `object Confidence { fun percent(value: Double): Int?; fun colors(value: Double): Pair<Color, Color>; fun label(value: Double): String }`, `@Composable fun ConfidenceChip(confidence: Double, modifier: Modifier = Modifier)`.
  - `@Composable fun StatusStrip(text: String, live: Boolean, modifier: Modifier = Modifier, batteryPct: Int? = null, paused: Boolean = false, onTogglePause: (() -> Unit)? = null)` — pause button content descriptions `"Pause capture"` / `"Resume capture"`.
  - `object BarChartMath { fun fractions(values: List<Int>): List<Float>; fun indexAt(x: Float, width: Float, count: Int): Int?; fun describe(values: List<Int>, labels: List<String>): String }`, `@Composable fun BarChart(values: List<Int>, labels: List<String>, modifier: Modifier = Modifier, highlightIndex: Int? = null, onBarClick: ((Int) -> Unit)? = null)` — semantics content description `"Bar chart: M 3, T 0, …"`.
  - `object FrameDecode { fun sampleSize(width: Int, height: Int, targetPx: Int): Int; fun decode(path: String?, targetPx: Int): Bitmap? }`, `@Composable fun FrameThumb(path: String?, modifier: Modifier = Modifier, size: Dp = 56.dp, onClick: (() -> Unit)? = null)` (content description `"Photo"` / `"No photo"`), `@Composable fun FrameImage(path: String?, modifier: Modifier = Modifier, maxPx: Int = 1440, contentScale: ContentScale = ContentScale.Fit)` (missing → text `"Photo no longer on this phone"`).
  - `val GlassesIcon: ImageVector`.
  - Phone resources `R.mipmap.ic_launcher`, `R.mipmap.ic_launcher_round` (adaptive: glasses glyph, white stroke, on a Mint → Sky gradient).

- [ ] **Step 1: Write the failing component tests**

`core/ui/src/test/kotlin/com/rokid/context/core/ui/ShellComponentsTest.kt`:
```kotlin
package com.rokid.context.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.components.BarChart
import com.rokid.context.core.ui.components.BarChartMath
import com.rokid.context.core.ui.components.Confidence
import com.rokid.context.core.ui.components.ConfidenceChip
import com.rokid.context.core.ui.components.FrameDecode
import com.rokid.context.core.ui.components.FrameThumb
import com.rokid.context.core.ui.components.StatusStrip
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShellComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun confidenceIsClampedAndNaNShowsDash() {
        assertEquals("92%", Confidence.label(0.92))
        assertEquals("100%", Confidence.label(1.7))
        assertEquals("0%", Confidence.label(-0.2))
        assertEquals("—", Confidence.label(Double.NaN))
        assertEquals(PcColors.ChipMint, Confidence.colors(0.8))
        assertEquals(PcColors.ChipAmber, Confidence.colors(0.5))
        assertEquals(PcColors.ChipCoral, Confidence.colors(0.49))
        assertEquals(PcColors.ChipSlate, Confidence.colors(Double.NaN))
        compose.setContent { PcTheme { ConfidenceChip(0.71) } }
        compose.onNodeWithText("71%").assertIsDisplayed()
    }

    @Test fun statusStripShowsBatteryAndReportsPauseTap() {
        var taps = 0
        compose.setContent { PcTheme { StatusStrip("Capturing · next 4:12", live = true, batteryPct = 82, onTogglePause = { taps++ }) } }
        compose.onNodeWithText("Capturing · next 4:12").assertIsDisplayed()
        compose.onNodeWithText("82%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause capture").performClick()
        assertEquals(1, taps)
    }

    @Test fun pausedStripOffersResumeAndNoCallbackMeansNoButton() {
        compose.setContent {
            PcTheme {
                Column {
                    StatusStrip("Paused", live = false, paused = true, onTogglePause = {})
                    StatusStrip("Glasses offline", live = false)
                }
            }
        }
        compose.onNodeWithContentDescription("Resume capture").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Pause capture").assertCountEquals(0)
    }

    @Test fun allZeroValuesGiveZeroHeights() {
        assertEquals(listOf(0f, 0f, 0f), BarChartMath.fractions(listOf(0, 0, 0)))
        assertEquals(emptyList<Float>(), BarChartMath.fractions(emptyList()))
        assertEquals(listOf(0.5f, 1f, 0f), BarChartMath.fractions(listOf(2, 4, -1)))
        assertNull(BarChartMath.indexAt(5f, 100f, 0))
        assertNull(BarChartMath.indexAt(-1f, 70f, 7))
        assertEquals(0, BarChartMath.indexAt(0f, 70f, 7))
        assertEquals(6, BarChartMath.indexAt(70f, 70f, 7))
    }

    @Test fun barChartDescribesValuesAndReportsTheTappedBar() {
        var tapped: Int? = null
        compose.setContent { PcTheme { BarChart(listOf(3, 0, 5), listOf("M", "T", "W"), onBarClick = { tapped = it }) } }
        compose.onNodeWithContentDescription("Bar chart: M 3, T 0, W 5").performTouchInput { click(Offset(width - 2f, height / 2f)) }
        assertEquals(2, tapped)
    }

    @Test fun sampleSizeKeepsTheShortSideAboveTarget() {
        assertEquals(16, FrameDecode.sampleSize(1440, 1080, 56))
        assertEquals(1, FrameDecode.sampleSize(100, 100, 200))
        assertEquals(1, FrameDecode.sampleSize(0, 0, 56))
        assertNull(FrameDecode.decode(null, 56))
        assertNull(FrameDecode.decode("/definitely/missing.jpg", 56))
    }

    @Test fun missingFileShowsPlaceholder() {
        compose.setContent { PcTheme { FrameThumb("/definitely/missing.jpg") } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("No photo").fetchSemanticsNodes().isNotEmpty() }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "*ShellComponentsTest"`
Expected: FAIL — `Unresolved reference: Confidence`, `BarChart`, `FrameThumb`, `StatusStrip`.

- [ ] **Step 3: Coroutines for off-main decoding**

In `core/ui/build.gradle.kts` `dependencies { … }` add:
```kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
```

- [ ] **Step 4: `Status.kt`**

```kotlin
package com.rokid.context.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.theme.PcColors
import kotlin.math.roundToInt

/** Bands for [ConfidenceChip]: ≥ 80 % mint, ≥ 50 % amber, otherwise coral; NaN/infinite = unknown (slate, "—"). */
object Confidence {
    fun percent(value: Double): Int? = if (value.isNaN() || value.isInfinite()) null else (value.coerceIn(0.0, 1.0) * 100).roundToInt()

    fun colors(value: Double): Pair<Color, Color> = when (val p = percent(value)) {
        null -> PcColors.ChipSlate
        else -> if (p >= 80) PcColors.ChipMint else if (p >= 50) PcColors.ChipAmber else PcColors.ChipCoral
    }

    fun label(value: Double): String = percent(value)?.let { "$it%" } ?: "—"
}

@Composable
fun ConfidenceChip(confidence: Double, modifier: Modifier = Modifier) {
    val (bg, fg) = Confidence.colors(confidence)
    Text(
        Confidence.label(confidence),
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Thin capture status row (spec §2.4 Activity): live dot · text · glasses battery · mini pause/resume. */
@Composable
fun StatusStrip(
    text: String,
    live: Boolean,
    modifier: Modifier = Modifier,
    batteryPct: Int? = null,
    paused: Boolean = false,
    onTogglePause: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PcColors.SurfaceSoft)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) PcColors.Mint else PcColors.ChipSlate.second))
        Spacer(Modifier.width(8.dp))
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (batteryPct != null) {
            Icon(Icons.Outlined.BatteryStd, contentDescription = null, tint = PcColors.InkSoft, modifier = Modifier.size(16.dp))
            Text("$batteryPct%", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
            Spacer(Modifier.width(8.dp))
        }
        if (onTogglePause != null) {
            val label = if (paused) "Resume capture" else "Pause capture"
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(PcColors.Surface)
                    .clickable(role = Role.Button, onClickLabel = label, onClick = onTogglePause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = label, tint = PcColors.Ink, modifier = Modifier.size(18.dp))
            }
        }
    }
}
```

- [ ] **Step 5: `BarChart.kt`** (Canvas drawing pattern from Live Fit `phone/src/main/java/com/debasish/livefit/phone/ui/history/SessionDetailScreen.kt`)

```kotlin
package com.rokid.context.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.theme.PcColors

object BarChartMath {
    /** Height of each bar as a fraction of the tallest; all zeros (or empty) → all 0f; negatives count as 0. */
    fun fractions(values: List<Int>): List<Float> {
        val max = values.maxOfOrNull { it.coerceAtLeast(0) } ?: 0
        return values.map { if (max == 0) 0f else it.coerceAtLeast(0).toFloat() / max }
    }

    /** Bar under a tap at [x] in a chart [width] px wide with [count] equal slots; null outside the chart. */
    fun indexAt(x: Float, width: Float, count: Int): Int? =
        if (count <= 0 || width <= 0f || x < 0f || x > width) null else (x / (width / count)).toInt().coerceIn(0, count - 1)

    fun describe(values: List<Int>, labels: List<String>): String =
        "Bar chart: " + values.indices.joinToString(", ") { i -> "${labels.getOrNull(i)?.ifBlank { null } ?: "${i + 1}"} ${values[i]}" }
}

/** Frames per day (spec §2.4 Activity Week/Month). Zero days draw a 3 dp hairline so the axis stays readable. */
@Composable
fun BarChart(
    values: List<Int>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    highlightIndex: Int? = null,
    onBarClick: ((Int) -> Unit)? = null,
) {
    val fractions = BarChartMath.fractions(values)
    val description = BarChartMath.describe(values, labels)
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }
                .then(
                    if (onBarClick == null) Modifier else Modifier.pointerInput(values, onBarClick) {
                        detectTapGestures { o -> BarChartMath.indexAt(o.x, size.width.toFloat(), values.size)?.let(onBarClick) }
                    },
                ),
        ) {
            if (fractions.isEmpty()) return@Canvas
            val slot = size.width / fractions.size
            val barW = slot * 0.56f
            val minH = 3.dp.toPx()
            fractions.forEachIndexed { i, f ->
                val h = if (f == 0f) minH else (size.height * f).coerceAtLeast(minH)
                val color = when {
                    i == highlightIndex -> PcColors.MintDeep
                    f == 0f -> PcColors.Line
                    else -> PcColors.Mint
                }
                drawRoundRect(color, topLeft = Offset(slot * i + (slot - barW) / 2f, size.height - h), size = Size(barW, h), cornerRadius = CornerRadius(barW / 3f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEach {
                Text(it, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    }
}
```

- [ ] **Step 6: `FrameImages.kt`**

```kotlin
package com.rokid.context.core.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.theme.PcColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Downsampled JPEG decoding for frame thumbnails; never throws, never runs on the main thread (callers use IO). */
object FrameDecode {
    /** Largest power-of-two sample size that keeps both sides ≥ [targetPx]. */
    fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        if (width <= 0 || height <= 0 || targetPx <= 0) return 1
        var s = 1
        while (width / (s * 2) >= targetPx && height / (s * 2) >= targetPx) s *= 2
        return s
    }

    /** Null when the path is blank, the file is missing/empty, or it is not a decodable image. */
    fun decode(path: String?, targetPx: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx) })
        }.getOrNull()
    }
}

private sealed interface FrameLoad {
    data object Loading : FrameLoad
    data object Missing : FrameLoad
    data class Loaded(val bitmap: ImageBitmap) : FrameLoad
}

@Composable
private fun rememberFrame(path: String?, targetPx: Int): FrameLoad {
    val load by produceState<FrameLoad>(FrameLoad.Loading, path, targetPx) {
        value = withContext(Dispatchers.IO) { FrameDecode.decode(path, targetPx) }?.let { FrameLoad.Loaded(it.asImageBitmap()) } ?: FrameLoad.Missing
    }
    return load
}

/** Rounded square thumbnail (timeline rows, "Based on" source cards). Missing file → placeholder icon. */
@Composable
fun FrameThumb(path: String?, modifier: Modifier = Modifier, size: Dp = 56.dp, onClick: (() -> Unit)? = null) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val load = rememberFrame(path, px)
    Box(
        modifier.size(size).clip(RoundedCornerShape(12.dp)).background(PcColors.SurfaceSoft)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (load) {
            is FrameLoad.Loaded -> Image(load.bitmap, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            FrameLoad.Loading -> Unit
            FrameLoad.Missing -> Icon(Icons.Outlined.ImageNotSupported, contentDescription = "No photo", tint = PcColors.InkSoft, modifier = Modifier.size(size * 0.4f))
        }
    }
}

/** Large frame on black (Memory detail, Frame viewer). [maxPx] bounds the decoded size. */
@Composable
fun FrameImage(path: String?, modifier: Modifier = Modifier, maxPx: Int = 1440, contentScale: ContentScale = ContentScale.Fit) {
    val load = rememberFrame(path, maxPx)
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when (load) {
            is FrameLoad.Loaded -> Image(load.bitmap, contentDescription = "Photo", contentScale = contentScale, modifier = Modifier.fillMaxSize())
            FrameLoad.Loading -> Unit
            FrameLoad.Missing -> Text("Photo no longer on this phone", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
```

- [ ] **Step 7: `GlassesIcon.kt`** — copy Live Fit `phone/src/main/java/com/debasish/livefit/phone/ui/components/GlassesIcon.kt` with the package changed:

```kotlin
package com.rokid.context.core.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Outlined smart-glasses icon (Live Fit); used for the glasses everywhere and in the app icon. */
val GlassesIcon: ImageVector by lazy {
    ImageVector.Builder("Glasses", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(3f, 10f); lineTo(10f, 10f); lineTo(10f, 13f); curveTo(10f, 15f, 8.5f, 16f, 6.5f, 16f); curveTo(4.5f, 16f, 3f, 15f, 3f, 13f); close()
            moveTo(14f, 10f); lineTo(21f, 10f); lineTo(21f, 13f); curveTo(21f, 15f, 19.5f, 16f, 17.5f, 16f); curveTo(15.5f, 16f, 14f, 15f, 14f, 13f); close()
            moveTo(10f, 11f); curveTo(11f, 10f, 13f, 10f, 14f, 11f)
            moveTo(3f, 10f); lineTo(1.5f, 8f)
            moveTo(21f, 10f); lineTo(22.5f, 8f)
        }
    }.build()
}
```

- [ ] **Step 8: Run component tests**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "*ShellComponentsTest"`
Expected: PASS (7 tests).

- [ ] **Step 9: Write the failing icon test**

`phone/src/test/java/com/rokid/context/phone/AppIconTest.kt`:
```kotlin
package com.rokid.context.phone

import android.app.Application
import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AppIconTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun manifestUsesTheAdaptiveLauncherIcon() {
        assertEquals(R.mipmap.ic_launcher, ctx.applicationInfo.icon)
        assertTrue(ctx.getDrawable(R.mipmap.ic_launcher) is AdaptiveIconDrawable)
        assertTrue(ctx.getDrawable(R.mipmap.ic_launcher_round) is AdaptiveIconDrawable)
    }
}
```

- [ ] **Step 10: Run to verify failure**

Run: `./gradlew :phone:testDebugUnitTest --tests "*AppIconTest"`
Expected: FAIL — `Unresolved reference: ic_launcher`.

- [ ] **Step 11: Icon resources**

`phone/src/main/res/drawable/ic_launcher_background.xml` (Mint → Sky, spec §2.3):
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108"
                android:startColor="#FF14C3A2" android:endColor="#FF3D8BFF" />
        </aapt:attr>
    </path>
</vector>
```

`phone/src/main/res/drawable/ic_launcher_foreground.xml` (the `GlassesIcon` path, scaled into the 72 dp safe zone like Live Fit's foreground group):
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <group android:translateX="27" android:translateY="27" android:scaleX="2.25" android:scaleY="2.25">
        <path android:fillColor="#00000000" android:strokeColor="#FFFFFFFF" android:strokeWidth="2" android:strokeLineCap="round"
            android:pathData="M3,10L10,10L10,13C10,15 8.5,16 6.5,16C4.5,16 3,15 3,13Z M14,10L21,10L21,13C21,15 19.5,16 17.5,16C15.5,16 14,15 14,13Z M10,11C11,10 13,10 14,11 M3,10L1.5,8 M21,10L22.5,8" />
    </group>
</vector>
```

`phone/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and `phone/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` (identical; phone `minSdk` 29 ≥ 26 so no PNG fallbacks are needed):
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

In `phone/src/main/AndroidManifest.xml` change only the `<application>` icon attributes:
```xml
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
```
(replacing `android:icon="@android:drawable/sym_def_app_icon"`; touch no other line — Phase 3 owns the rest of this file).

- [ ] **Step 12: Run icon test + gate**

Run: `./gradlew :phone:testDebugUnitTest --tests "*AppIconTest"` then `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 13: Commit**

```bash
git add android-companion/core/ui android-companion/phone/src/main/res android-companion/phone/src/main/AndroidManifest.xml android-companion/phone/src/test/java/com/rokid/context/phone/AppIconTest.kt
git commit -m "feat(ui): StatusStrip, ConfidenceChip, BarChart, FrameThumb and the adaptive app icon

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 3: Read model — Flow DAOs and repositories for the phone screens

**Requirements:** R-15 (day/period buckets over Phase 2's `DayBucket` ranges), R-13/R-14 (asks, nudges, proposals readable), R-16 (Trust / Edit writes), R-20 (repositories expose Room `Flow`s, spec §2.5)

**Files:**
- Create: `android-companion/services/memory/src/main/java/com/rokid/context/memory/db/ViewDaos.kt`
- Modify: `android-companion/services/memory/src/main/java/com/rokid/context/memory/db/ContextDatabase.kt` (six abstract DAO getters — no entity or version change)
- Create: `android-companion/services/memory/src/main/java/com/rokid/context/memory/FeedRepositories.kt`
- Modify: `android-companion/services/memory/src/main/java/com/rokid/context/memory/MemoryGraph.kt` (five new `val`s)
- Test: `android-companion/services/memory/src/test/java/com/rokid/context/memory/FeedRepositoriesTest.kt`

**Interfaces:**
- Consumes: entities/`ContextDatabase`/`MemoryRepository`/`DayBucket` (Phase 2).
- Produces (package `com.rokid.context.memory` unless noted). All `LongRange` arguments are `DayBucket`-style (`first` inclusive, `last` inclusive; queried as `< last + 1`):
  - DAOs (package `com.rokid.context.memory.db`): `FrameViewDao`, `MemoryViewDao`, `TraceViewDao`, `AskDao`, `NudgeDao`, `ProposalDao`; `ContextDatabase.frameViewDao()/memoryViewDao()/traceViewDao()/askDao()/nudgeDao()/proposalDao()`.
  - `class TimelineRepository(frames: FrameViewDao, memories: MemoryViewDao, traces: TraceViewDao)`: `fun framesIn(range: LongRange): Flow<List<FrameEntity>>` (oldest first), `fun frameTimesIn(range): Flow<List<Long>>`, `fun visibleMemoriesIn(range): Flow<List<MemoryEntity>>` (reviewState = OK only, newest first), `fun visibleMemoryTimesIn(range): Flow<List<Long>>`, `fun recentVisibleMemories(limit: Int): Flow<List<MemoryEntity>>`, `fun memory(id: String): Flow<MemoryEntity?>`, `suspend fun frame(id: String): FrameEntity?`, `suspend fun memoriesByIds(ids: List<String>): List<MemoryEntity>`, `suspend fun framesByIds(ids: List<String>): List<FrameEntity>`, `fun recentTraces(limit: Int): Flow<List<SummaryTraceEntity>>`.
  - `class MemoryEdits(view: MemoryViewDao, memories: MemoryRepository)`: `suspend fun trust(id: String): Boolean`, `suspend fun editText(id: String, text: String): Boolean`.
  - `class AskRepository(dao: AskDao)`: `suspend fun upsert(ask: AskEntity)`, `suspend fun byId(id): AskEntity?`, `fun observe(id): Flow<AskEntity?>`, `fun recent(limit): Flow<List<AskEntity>>`, `fun timesIn(range): Flow<List<Long>>`, `suspend fun setRating(id: String, rating: Int?)`.
  - `class NudgeRepository(dao: NudgeDao)`: `suspend fun addIgnore(n: NudgeEntity): Boolean`, `fun recent(limit): Flow<List<NudgeEntity>>`, `fun timesIn(range): Flow<List<Long>>`.
  - `class ProposalRepository(dao: ProposalDao)`: `suspend fun addIgnore(p: ProposalEntity): Boolean`, `fun forOrigin(originId: String): Flow<List<ProposalEntity>>` (Phase 7 adds `resolve` to this class).
  - `MemoryGraph.timeline`, `.memoryEdits`, `.asks`, `.nudges`, `.proposals`.

Why new DAO interfaces instead of editing `FrameDao`/`MemoryDao`: Phase 3 may add queries to those files concurrently (frame dedupe); separate files keep the merge to the two additive lists in `ContextDatabase.kt` and `MemoryGraph.kt`.

- [ ] **Step 1: Write the failing test**

`services/memory/src/test/java/com/rokid/context/memory/FeedRepositoriesTest.kt`:
```kotlin
package com.rokid.context.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SummaryTraceEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FeedRepositoriesTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val memories = MemoryRepository(db.memoryDao())
    private val frames = FrameRepository(db.frameDao())
    private val traces = TraceRepository(db.traceDao())
    private val timeline = TimelineRepository(db.frameViewDao(), db.memoryViewDao(), db.traceViewDao())
    private val edits = MemoryEdits(db.memoryViewDao(), memories)
    private val asks = AskRepository(db.askDao())
    private val nudges = NudgeRepository(db.nudgeDao())
    private val proposals = ProposalRepository(db.proposalDao())
    private val day = DayBucket.bounds(LocalDate.of(2026, 10, 12), ZoneId.of("Asia/Kolkata"))

    @After fun close() = db.close()

    private fun m(id: String, text: String, at: Long, review: ReviewState = ReviewState.OK) =
        MemoryEntity(id, null, text, "short_term", "", MemoryTier.SHORT, 0.8, review, false, at, false, searchText = "")

    @Test fun visibleMemoriesExcludeReviewInvalidatedAndOtherDays() = runTest {
        memories.add(m("ok", "Keys on hallway shelf", day.first + 1))
        memories.add(m("rev", "Critic low confidence", day.first + 2, ReviewState.NEEDS_REVIEW))
        memories.add(m("inv", "Forgotten", day.first + 3, ReviewState.INVALIDATED))
        memories.add(m("old", "Yesterday", day.first - 1))
        assertEquals(listOf("ok"), timeline.visibleMemoriesIn(day).first().map { it.id })
        assertEquals(listOf(day.first + 1), timeline.visibleMemoryTimesIn(day).first())
        assertEquals(listOf("ok", "old"), timeline.recentVisibleMemories(10).first().map { it.id })
    }

    @Test fun memoryFlowUpdatesLiveAfterInsert() = runBlocking {
        val sawEmpty = CompletableDeferred<Unit>()
        val next = async(Dispatchers.IO) {
            timeline.visibleMemoriesIn(day).onEach { if (it.isEmpty()) sawEmpty.complete(Unit) }.first { it.isNotEmpty() }
        }
        sawEmpty.await()
        memories.add(m("new", "Fresh", day.first + 5))
        assertEquals(listOf("new"), withTimeout(5_000) { next.await() }.map { it.id })
    }

    @Test fun framesInRangeAreOldestFirstAndBounded() = runTest {
        frames.add(FrameEntity("b", day.first + 20, "/b.jpg", "rokid_glasses"))
        frames.add(FrameEntity("a", day.first + 10, "/a.jpg", "rokid_glasses"))
        frames.add(FrameEntity("next", day.last + 1, "/n.jpg", "rokid_glasses"))
        assertEquals(listOf("a", "b"), timeline.framesIn(day).first().map { it.id })
        assertEquals(2, timeline.frameTimesIn(day).first().size)
        assertEquals("/a.jpg", timeline.frame("a")?.path)
        assertEquals(listOf("a"), timeline.framesByIds(listOf("a", "a", "zzz")).map { it.id })
    }

    @Test fun editTextReindexesSearchAndRejectsBlankOrUnknown() = runTest {
        memories.add(m("m1", "Milk carton nearly empty", day.first))
        assertTrue(edits.editText("m1", "  Oat\tmilk bought\n "))
        assertEquals("Oat milk bought", timeline.memory("m1").first()?.text)
        assertEquals(listOf("m1"), memories.search("oat", 5).map { it.id })
        assertTrue(memories.search("carton", 5).isEmpty())
        assertFalse(edits.editText("m1", "   "))
        assertFalse(edits.editText("nope", "text"))
    }

    @Test fun trustSetsTheFlag() = runTest {
        memories.add(m("m1", "Keys", day.first))
        assertTrue(edits.trust("m1"))
        assertTrue(timeline.memory("m1").first()!!.trusted)
        assertFalse(edits.trust("missing"))
    }

    @Test fun asksAreNewestFirstAndRatingPersists() = runTest {
        asks.upsert(AskEntity("a1", day.first + 1, "q1", "a1", "[]", null, "phone_typed", false))
        asks.upsert(AskEntity("a2", day.first + 2, "q2", "a2", "[]", null, "phone_typed", false))
        assertEquals(listOf("a2", "a1"), asks.recent(10).first().map { it.id })
        asks.setRating("a1", -1)
        assertEquals(-1, asks.observe("a1").first()?.rating)
        assertEquals(null, asks.observe("missing").first())
        assertEquals(2, asks.timesIn(day).first().size)
    }

    @Test fun nudgesProposalsAndTracesReadBack() = runTest {
        assertTrue(nudges.addIgnore(NudgeEntity("n1", day.first + 3, "Milk low", "fridge", "[]", 1, "h1", "phone", false)))
        assertFalse(nudges.addIgnore(NudgeEntity("n1", day.first + 3, "dup", "", "[]", 1, "h1", "phone", false)))
        assertEquals(listOf("Milk low"), nudges.recent(5).first().map { it.text })
        assertEquals(listOf(day.first + 3), nudges.timesIn(day).first())
        proposals.addIgnore(ProposalEntity("p1", "ASK", "a1", "SHOPPING_ADD", "{}", "PENDING", 9, null, null, 1))
        assertEquals(listOf("p1"), proposals.forOrigin("a1").first().map { it.id })
        assertTrue(proposals.forOrigin("other").first().isEmpty())
        traces.upsert(SummaryTraceEntity("s1", "TRUSTED", 5, "ok"))
        assertEquals(listOf("s1"), timeline.recentTraces(10).first().map { it.summaryId })
    }

    @Test fun emptyIdListsNeverQuery() = runTest {
        assertTrue(timeline.memoriesByIds(emptyList()).isEmpty())
        assertTrue(timeline.framesByIds(emptyList()).isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :services:memory:testDebugUnitTest --tests "*FeedRepositoriesTest"`
Expected: FAIL — `Unresolved reference: frameViewDao` / `TimelineRepository`.

- [ ] **Step 3: `ViewDaos.kt`**

```kotlin
package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Read-side queries for the phone screens (Phase 4). Ranges are [fromMs, toMs). Schema v1 unchanged. */
@Dao
interface FrameViewDao {
    @Query("SELECT * FROM frame WHERE ts >= :fromMs AND ts < :toMs ORDER BY ts ASC")
    fun observeBetween(fromMs: Long, toMs: Long): Flow<List<FrameEntity>>

    @Query("SELECT ts FROM frame WHERE ts >= :fromMs AND ts < :toMs")
    fun observeTimes(fromMs: Long, toMs: Long): Flow<List<Long>>

    @Query("SELECT * FROM frame WHERE id = :id") suspend fun byId(id: String): FrameEntity?
    @Query("SELECT * FROM frame WHERE id IN (:ids)") suspend fun byIds(ids: List<String>): List<FrameEntity>
}

@Dao
interface MemoryViewDao {
    @Query("SELECT * FROM memory WHERE createdAt >= :fromMs AND createdAt < :toMs AND reviewState = 'OK' ORDER BY createdAt DESC")
    fun observeVisibleBetween(fromMs: Long, toMs: Long): Flow<List<MemoryEntity>>

    @Query("SELECT createdAt FROM memory WHERE createdAt >= :fromMs AND createdAt < :toMs AND reviewState = 'OK'")
    fun observeVisibleTimes(fromMs: Long, toMs: Long): Flow<List<Long>>

    @Query("SELECT * FROM memory WHERE reviewState = 'OK' ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecentVisible(limit: Int): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memory WHERE id = :id") fun observe(id: String): Flow<MemoryEntity?>
    @Query("SELECT * FROM memory WHERE id = :id") suspend fun byId(id: String): MemoryEntity?
    @Query("SELECT * FROM memory WHERE id IN (:ids)") suspend fun byIds(ids: List<String>): List<MemoryEntity>

    /** Returns the number of rows changed (0 = unknown id). */
    @Query("UPDATE memory SET trusted = :trusted WHERE id = :id") suspend fun setTrusted(id: String, trusted: Boolean): Int
}

@Dao
interface TraceViewDao {
    @Query("SELECT * FROM summary_trace ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SummaryTraceEntity>>
}

@Dao
interface AskDao {
    @Upsert suspend fun upsert(ask: AskEntity)
    @Query("SELECT * FROM ask WHERE id = :id") suspend fun byId(id: String): AskEntity?
    @Query("SELECT * FROM ask WHERE id = :id") fun observe(id: String): Flow<AskEntity?>
    @Query("SELECT * FROM ask ORDER BY ts DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<AskEntity>>
    @Query("SELECT ts FROM ask WHERE ts >= :fromMs AND ts < :toMs") fun observeTimes(fromMs: Long, toMs: Long): Flow<List<Long>>
    @Query("UPDATE ask SET rating = :rating WHERE id = :id") suspend fun setRating(id: String, rating: Int?)
}

@Dao
interface NudgeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(nudge: NudgeEntity): Long
    @Query("SELECT * FROM nudge ORDER BY ts DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<NudgeEntity>>
    @Query("SELECT ts FROM nudge WHERE ts >= :fromMs AND ts < :toMs") fun observeTimes(fromMs: Long, toMs: Long): Flow<List<Long>>
}

@Dao
interface ProposalDao {
    /** Seeding/tests only; Phase 7 adds the compare-and-set writes (spec §5.3). */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(proposal: ProposalEntity): Long
    @Query("SELECT * FROM proposal WHERE originId = :originId ORDER BY createdAt DESC")
    fun observeForOrigin(originId: String): Flow<List<ProposalEntity>>
}
```

- [ ] **Step 4: Register the DAOs**

In `ContextDatabase.kt` add after `abstract fun roleBindingDao(): RoleBindingDao` (keep the `@Database` annotation and `version = 1` untouched):
```kotlin
    abstract fun frameViewDao(): FrameViewDao
    abstract fun memoryViewDao(): MemoryViewDao
    abstract fun traceViewDao(): TraceViewDao
    abstract fun askDao(): AskDao
    abstract fun nudgeDao(): NudgeDao
    abstract fun proposalDao(): ProposalDao
```

- [ ] **Step 5: `FeedRepositories.kt`**

```kotlin
package com.rokid.context.memory

import com.rokid.context.memory.db.AskDao
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameViewDao
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryViewDao
import com.rokid.context.memory.db.NudgeDao
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.ProposalDao
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.db.SummaryTraceEntity
import com.rokid.context.memory.db.TraceViewDao
import kotlinx.coroutines.flow.Flow

/** Phone read model (spec §2.5). Ranges come from [DayBucket.bounds]: `first` and `last` are both inclusive. */
class TimelineRepository(private val frames: FrameViewDao, private val memories: MemoryViewDao, private val traces: TraceViewDao) {
    fun framesIn(range: LongRange): Flow<List<FrameEntity>> = frames.observeBetween(range.first, range.last + 1)
    fun frameTimesIn(range: LongRange): Flow<List<Long>> = frames.observeTimes(range.first, range.last + 1)
    fun visibleMemoriesIn(range: LongRange): Flow<List<MemoryEntity>> = memories.observeVisibleBetween(range.first, range.last + 1)
    fun visibleMemoryTimesIn(range: LongRange): Flow<List<Long>> = memories.observeVisibleTimes(range.first, range.last + 1)
    fun recentVisibleMemories(limit: Int): Flow<List<MemoryEntity>> = memories.observeRecentVisible(limit)
    fun memory(id: String): Flow<MemoryEntity?> = memories.observe(id)
    suspend fun frame(id: String): FrameEntity? = frames.byId(id)
    suspend fun memoriesByIds(ids: List<String>): List<MemoryEntity> = ids.distinct().let { if (it.isEmpty()) emptyList() else memories.byIds(it) }
    suspend fun framesByIds(ids: List<String>): List<FrameEntity> = ids.distinct().let { if (it.isEmpty()) emptyList() else frames.byIds(it) }
    fun recentTraces(limit: Int): Flow<List<SummaryTraceEntity>> = traces.observeRecent(limit)
}

/** Memory detail writes (spec §2.4): Trust and Edit. Forget is Phase 7 (spec §6.1.1, R-77). */
class MemoryEdits(private val view: MemoryViewDao, private val memories: MemoryRepository) {
    suspend fun trust(id: String): Boolean = view.setTrusted(id, true) > 0

    /** Single-line, trimmed, ≤ 500 chars; re-indexes FTS through [MemoryRepository.add]. False for blank text or unknown id. */
    suspend fun editText(id: String, text: String): Boolean {
        val clean = text.replace('\n', ' ').replace('\t', ' ').trim().take(500)
        if (clean.isEmpty()) return false
        val current = view.byId(id) ?: return false
        memories.add(current.copy(text = clean))
        return true
    }
}

class AskRepository(private val dao: AskDao) {
    suspend fun upsert(ask: AskEntity) = dao.upsert(ask)
    suspend fun byId(id: String): AskEntity? = dao.byId(id)
    fun observe(id: String): Flow<AskEntity?> = dao.observe(id)
    fun recent(limit: Int): Flow<List<AskEntity>> = dao.observeRecent(limit)
    fun timesIn(range: LongRange): Flow<List<Long>> = dao.observeTimes(range.first, range.last + 1)
    suspend fun setRating(id: String, rating: Int?) = dao.setRating(id, rating)
}

class NudgeRepository(private val dao: NudgeDao) {
    suspend fun addIgnore(nudge: NudgeEntity): Boolean = dao.insertIgnore(nudge) != -1L
    fun recent(limit: Int): Flow<List<NudgeEntity>> = dao.observeRecent(limit)
    fun timesIn(range: LongRange): Flow<List<Long>> = dao.observeTimes(range.first, range.last + 1)
}

class ProposalRepository(private val dao: ProposalDao) {
    suspend fun addIgnore(proposal: ProposalEntity): Boolean = dao.insertIgnore(proposal) != -1L
    fun forOrigin(originId: String): Flow<List<ProposalEntity>> = dao.observeForOrigin(originId)
}
```

- [ ] **Step 6: Expose them on `MemoryGraph`**

In `MemoryGraph.kt`, after `val traces = TraceRepository(db.traceDao())` add:
```kotlin
    val timeline = TimelineRepository(db.frameViewDao(), db.memoryViewDao(), db.traceViewDao())
    val memoryEdits = MemoryEdits(db.memoryViewDao(), memories)
    val asks = AskRepository(db.askDao())
    val nudges = NudgeRepository(db.nudgeDao())
    val proposals = ProposalRepository(db.proposalDao())
```

- [ ] **Step 7: Run to verify pass and that the schema did not move**

Run: `./gradlew :services:memory:testDebugUnitTest --tests "*FeedRepositoriesTest" --tests "*SchemaTest"` then `git status --porcelain services/memory/schemas`
Expected: PASS (8 + existing SchemaTest tests); the `git status` command prints nothing (schema JSON unchanged).

- [ ] **Step 8: Gate**

Run: `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add android-companion/services/memory
git commit -m "feat(memory): Flow read model for the phone screens (timeline, asks, nudges, proposals, edits)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 4: Banners, capture line, storage usage, pipeline control

**Requirements:** R-11 (one banner at a time, spec §2.2 severity order), R-13/R-15 (header subline and status strip text), R-72 (banner text is short; never raw)

**Files:**
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/banner/Banners.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/banner/BannerHost.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/status/CaptureLine.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/status/PipelineControl.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/status/StatusUi.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/status/StorageUsage.kt`
- Create (test support): `android-companion/phone/src/test/java/com/rokid/context/phone/ui/FakePipelineControl.kt`
- Test: `android-companion/phone/src/test/java/com/rokid/context/phone/ui/banner/BannerResolverTest.kt`, `.../phone/ui/banner/BannerHostTest.kt`, `.../phone/ui/status/CaptureLineTest.kt`, `.../phone/ui/status/StorageUsageTest.kt`, `.../phone/ui/status/LegacyPipelineControlTest.kt`

**Interfaces:**
- Consumes: `GlassesStatus`, `GlassesStatusSource`, `LinkState`, `CaptureState`, `Health` (`:core:model`); `Banner`, `BannerTone` (`:core:ui`); `AppConfigStore`, `OrchestrationScheduler` (`:services:orchestration`); `DatabaseFactory.NAME` (`:services:memory`).
- Produces:
  - `enum class IndexState { NONE, NEEDED, RUNNING }`, `enum class BannerKind { AI_BLOCKED, GLASSES_DISCONNECTED, PROTOCOL_MISMATCH, CAPTURE_DEFERRED, CAPTURE_PAUSED, INDEX, STORAGE }`, `enum class BannerAction { FIX_AI, RECONNECT, RESUME }`, `data class BannerInputs(aiBlockedText: String? = null, link: LinkState = CONNECTED, capture: CaptureState? = null, pipelineEnabled: Boolean = true, index: IndexState = NONE, storagePct: Int? = null)`, `data class BannerSpec(kind, tone: BannerTone, title: String, actionLabel: String? = null, action: BannerAction? = null)`, `object BannerResolver { fun resolve(i: BannerInputs): BannerSpec?; fun candidates(i: BannerInputs): List<BannerSpec> }` — package `com.rokid.context.phone.ui.banner`.
  - `@Composable fun BannerHost(spec: BannerSpec?, onAction: (BannerAction) -> Unit, modifier: Modifier = Modifier)`.
  - Package `com.rokid.context.phone.ui.status`: `object CaptureLine { fun paused(s: GlassesStatus, pipelineEnabled: Boolean): Boolean; fun live(s, pipelineEnabled): Boolean; fun text(s, pipelineEnabled, nowMs: Long): String; fun countdown(atMs: Long, nowMs: Long): String }`; `interface PipelineControl { val enabled: StateFlow<Boolean>; fun setEnabled(enabled: Boolean) }`, `class LegacyPipelineControl(context: Context, scheduler: OrchestrationScheduler) : PipelineControl`; `data class StatusUi(captureText: String, live: Boolean, paused: Boolean, batteryPct: Int?, banner: BannerSpec?)` with `companion { val Initial; fun build(s, pipelineEnabled, storagePct: Int?, nowMs: Long, index: IndexState = NONE): StatusUi; fun flow(glasses: GlassesStatusSource, pipeline: PipelineControl, clock: Flow<Long>, storagePct: Flow<Int?>): Flow<StatusUi> }`; `object BannerActions { fun apply(action: BannerAction, glasses: GlassesStatusSource, pipeline: PipelineControl) }`; `object StorageUsage { const val QUOTA_BYTES; val FRAME_DIRS: List<String>; fun percent(usedBytes: Long, quotaBytes: Long = QUOTA_BYTES): Int; suspend fun usedBytes(context: Context): Long; fun percentFlow(context: Context): Flow<Int?> }`.
  - Test support: `class FakePipelineControl(initial: Boolean = true) : PipelineControl` (package `com.rokid.context.phone.ui`).

Phase 4 meaning of pause: until Phase 7 ships `PauseRecord`s (R-65), the Home/Activity ⏸ and the banner's **Resume** drive the legacy "pipeline enabled" switch (what the removed Live screen toggled: phone workers stop/start). `CaptureState.Paused` coming from the hub also shows the paused banner.

- [ ] **Step 1: Write the failing pure tests**

`phone/src/test/java/com/rokid/context/phone/ui/banner/BannerResolverTest.kt`:
```kotlin
package com.rokid.context.phone.ui.banner

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.ui.components.BannerTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BannerResolverTest {
    @Test fun healthyStateHasNoBanner() = assertNull(BannerResolver.resolve(BannerInputs()))

    @Test fun severityOrderMatchesSpec() {
        val all = BannerInputs(
            aiBlockedText = "AI key rejected", link = LinkState.OFFLINE, capture = CaptureState.Deferred("Low battery"),
            pipelineEnabled = false, index = IndexState.NEEDED, storagePct = 75,
        )
        assertEquals(
            listOf(BannerKind.AI_BLOCKED, BannerKind.GLASSES_DISCONNECTED, BannerKind.CAPTURE_DEFERRED, BannerKind.CAPTURE_PAUSED, BannerKind.INDEX, BannerKind.STORAGE),
            BannerResolver.candidates(all).map { it.kind },
        )
        assertEquals(BannerKind.AI_BLOCKED, BannerResolver.resolve(all)?.kind)
        assertEquals(
            listOf(BannerKind.PROTOCOL_MISMATCH, BannerKind.CAPTURE_DEFERRED),
            BannerResolver.candidates(BannerInputs(link = LinkState.VERSION_MISMATCH, capture = CaptureState.Deferred("Low battery"))).map { it.kind },
        )
    }

    @Test fun disconnectedVariantsOfferReconnect() {
        val offline = BannerResolver.resolve(BannerInputs(link = LinkState.OFFLINE))!!
        assertEquals("Glasses disconnected", offline.title); assertEquals("Reconnect", offline.actionLabel); assertEquals(BannerAction.RECONNECT, offline.action)
        val closed = BannerResolver.resolve(BannerInputs(link = LinkState.CLOSED_ON_GLASSES))!!
        assertEquals("Open on glasses", closed.actionLabel); assertEquals(BannerAction.RECONNECT, closed.action)
        assertEquals(BannerKind.GLASSES_DISCONNECTED, BannerResolver.resolve(BannerInputs(link = LinkState.NOT_AUTHORIZED))?.kind)
        for (quiet in listOf(LinkState.CONNECTED, LinkState.CONNECTING, LinkState.PAUSED_BT)) assertNull(quiet.name, BannerResolver.resolve(BannerInputs(link = quiet)))
    }

    @Test fun pausedByPipelineOrByCaptureStateOffersResume() {
        val p = BannerResolver.resolve(BannerInputs(pipelineEnabled = false))!!
        assertEquals(BannerKind.CAPTURE_PAUSED, p.kind); assertEquals(BannerTone.SLATE, p.tone); assertEquals(BannerAction.RESUME, p.action)
        val rec = CaptureState.Paused(listOf(PauseRecord("p1", PauseOrigin.GLASSES, 1)))
        assertEquals(BannerKind.CAPTURE_PAUSED, BannerResolver.resolve(BannerInputs(capture = rec))?.kind)
        assertEquals(BannerTone.AMBER, BannerResolver.resolve(BannerInputs(capture = CaptureState.Deferred("Low battery")))?.tone)
    }

    @Test fun storageThresholds() {
        assertNull(BannerResolver.resolve(BannerInputs(storagePct = 69)))
        assertEquals(BannerTone.AMBER, BannerResolver.resolve(BannerInputs(storagePct = 70))?.tone)
        assertEquals(BannerTone.AMBER, BannerResolver.resolve(BannerInputs(storagePct = 89))?.tone)
        assertEquals(BannerTone.CORAL, BannerResolver.resolve(BannerInputs(storagePct = 90))?.tone)
        assertEquals("Memory storage 92% full", BannerResolver.resolve(BannerInputs(storagePct = 92))?.title)
    }

    @Test fun aiBlockedBannerTruncatesLongText() {
        val b = BannerResolver.resolve(BannerInputs(aiBlockedText = "x".repeat(200) + "\nstack"))!!
        assertTrue(b.title.startsWith("AI unavailable · "))
        assertTrue(b.title.length <= "AI unavailable · ".length + 60)
        assertTrue(b.title.endsWith("…"))
        assertEquals(BannerAction.FIX_AI, b.action)
    }
}
```

`phone/src/test/java/com/rokid/context/phone/ui/status/CaptureLineTest.kt`:
```kotlin
package com.rokid.context.phone.ui.status

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.NOW
import com.rokid.context.phone.ui.banner.BannerKind
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLineTest {
    private val running = FakeGlassesStatusSource.connected(Fixtures.stateFrame(nextAtMs = NOW + 252_000))

    @Test fun runningShowsCountdownAndIsLive() {
        assertEquals("Capturing · next 4:12", CaptureLine.text(running, pipelineEnabled = true, nowMs = NOW))
        assertTrue(CaptureLine.live(running, true))
        assertFalse(CaptureLine.paused(running, true))
    }

    @Test fun countdownRoundsUpAndHandlesPastAndHours() {
        assertEquals("0:01", CaptureLine.countdown(NOW + 1, NOW))
        assertEquals("now", CaptureLine.countdown(NOW - 5, NOW))
        assertEquals("1:02:03", CaptureLine.countdown(NOW + 3_723_000, NOW))
    }

    @Test fun pipelineOffOrPauseRecordsShowPaused() {
        assertEquals("Paused", CaptureLine.text(running, pipelineEnabled = false, nowMs = NOW))
        assertFalse(CaptureLine.live(running, false))
        val paused = FakeGlassesStatusSource.connected(Fixtures.stateFrame(capture = CaptureState.Paused(listOf(PauseRecord("p", PauseOrigin.GLASSES, 1)))))
        assertEquals("Paused", CaptureLine.text(paused, true, NOW))
        assertTrue(CaptureLine.paused(paused, true))
    }

    @Test fun offlineConnectingAndStaleFrames() {
        assertEquals("Glasses offline", CaptureLine.text(FakeGlassesStatusSource.OFFLINE, true, NOW))
        assertEquals("Connecting to glasses…", CaptureLine.text(GlassesStatus(LinkState.CONNECTING, null, 0, null, null), true, NOW))
        assertEquals("Glasses offline", CaptureLine.text(running.copy(link = LinkState.OFFLINE), true, NOW))
        assertFalse(CaptureLine.live(running.copy(link = LinkState.OFFLINE), true))
    }

    @Test fun deferredAndBlockedShowTheirReason() {
        val deferred = FakeGlassesStatusSource.connected(Fixtures.stateFrame(capture = CaptureState.Deferred("Low battery")))
        assertEquals("Deferred · Low battery", CaptureLine.text(deferred, true, NOW))
        val blocked = FakeGlassesStatusSource.connected(Fixtures.stateFrame(capture = CaptureState.Blocked("Camera busy")))
        assertEquals("Blocked · Camera busy", CaptureLine.text(blocked, true, NOW))
    }

    @Test fun statusUiPicksBatteryAndBanner() {
        val ui = StatusUi.build(running, pipelineEnabled = true, storagePct = 10, nowMs = NOW)
        assertEquals(82, ui.batteryPct)
        assertEquals(null, ui.banner)
        val blocked = FakeGlassesStatusSource.connected(Fixtures.stateFrame(ai = Health.Blocked("AI key rejected")))
        assertEquals(BannerKind.AI_BLOCKED, StatusUi.build(blocked, true, null, NOW).banner?.kind)
        assertEquals(BannerKind.GLASSES_DISCONNECTED, StatusUi.build(FakeGlassesStatusSource.OFFLINE, true, null, NOW).banner?.kind)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :phone:testDebugUnitTest --tests "*BannerResolverTest" --tests "*CaptureLineTest"`
Expected: FAIL — `Unresolved reference: BannerResolver` / `CaptureLine`.

- [ ] **Step 3: `Banners.kt`**

```kotlin
package com.rokid.context.phone.ui.banner

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.components.BannerTone

enum class IndexState { NONE, NEEDED, RUNNING }
enum class BannerKind { AI_BLOCKED, GLASSES_DISCONNECTED, PROTOCOL_MISMATCH, CAPTURE_DEFERRED, CAPTURE_PAUSED, INDEX, STORAGE }
enum class BannerAction { FIX_AI, RECONNECT, RESUME }

/** Everything the banner needs; [aiBlockedText] is the hub's short `Health.Blocked` text, never an exception message. */
data class BannerInputs(
    val aiBlockedText: String? = null,
    val link: LinkState = LinkState.CONNECTED,
    val capture: CaptureState? = null,
    val pipelineEnabled: Boolean = true,
    val index: IndexState = IndexState.NONE,
    val storagePct: Int? = null,
)

data class BannerSpec(
    val kind: BannerKind,
    val tone: BannerTone,
    val title: String,
    val actionLabel: String? = null,
    val action: BannerAction? = null,
)

/** spec §2.2: one banner at a time, highest severity first. */
object BannerResolver {
    private const val MAX_TEXT = 60

    fun resolve(i: BannerInputs): BannerSpec? = candidates(i).firstOrNull()

    fun candidates(i: BannerInputs): List<BannerSpec> = listOfNotNull(
        i.aiBlockedText?.let { BannerSpec(BannerKind.AI_BLOCKED, BannerTone.CORAL, "AI unavailable · ${short(it)}", "Fix now", BannerAction.FIX_AI) },
        when (i.link) {
            LinkState.OFFLINE -> BannerSpec(BannerKind.GLASSES_DISCONNECTED, BannerTone.SKY, "Glasses disconnected", "Reconnect", BannerAction.RECONNECT)
            LinkState.CLOSED_ON_GLASSES -> BannerSpec(BannerKind.GLASSES_DISCONNECTED, BannerTone.SKY, "Proactive Context is closed on the glasses", "Open on glasses", BannerAction.RECONNECT)
            LinkState.NOT_AUTHORIZED -> BannerSpec(BannerKind.GLASSES_DISCONNECTED, BannerTone.SKY, "Glasses not authorized", "Reconnect", BannerAction.RECONNECT)
            else -> null
        },
        if (i.link == LinkState.VERSION_MISMATCH) BannerSpec(BannerKind.PROTOCOL_MISMATCH, BannerTone.CORAL, "Update Proactive Context on your glasses") else null,
        when (val c = i.capture) {
            is CaptureState.Deferred -> BannerSpec(BannerKind.CAPTURE_DEFERRED, BannerTone.AMBER, "Capture deferred · ${short(c.reason)}")
            is CaptureState.Blocked -> BannerSpec(BannerKind.CAPTURE_DEFERRED, BannerTone.AMBER, "Capture blocked · ${short(c.reason)}")
            else -> null
        },
        if (!i.pipelineEnabled || i.capture is CaptureState.Paused) {
            BannerSpec(BannerKind.CAPTURE_PAUSED, BannerTone.SLATE, "Capture paused", "Resume", BannerAction.RESUME)
        } else null,
        when (i.index) {
            IndexState.NEEDED -> BannerSpec(BannerKind.INDEX, BannerTone.SKY, "Memory index needs a rebuild")
            IndexState.RUNNING -> BannerSpec(BannerKind.INDEX, BannerTone.SKY, "Rebuilding memory index…")
            IndexState.NONE -> null
        },
        i.storagePct?.let { p ->
            when {
                p >= 90 -> BannerSpec(BannerKind.STORAGE, BannerTone.CORAL, "Memory storage $p% full")
                p >= 70 -> BannerSpec(BannerKind.STORAGE, BannerTone.AMBER, "Memory storage $p% full")
                else -> null
            }
        },
    )

    private fun short(text: String): String {
        val t = text.replace('\n', ' ').trim()
        return if (t.length <= MAX_TEXT) t else t.take(MAX_TEXT - 1).trimEnd() + "…"
    }
}
```

- [ ] **Step 4: `CaptureLine.kt`**

```kotlin
package com.rokid.context.phone.ui.status

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.LinkState
import java.util.Locale
import kotlin.math.ceil

/** Home subline / Activity status strip text (spec §2.4): "Capturing · next 4:12", "Paused", "Glasses offline". */
object CaptureLine {
    fun paused(s: GlassesStatus, pipelineEnabled: Boolean): Boolean = !pipelineEnabled || s.frame?.capture is CaptureState.Paused

    fun live(s: GlassesStatus, pipelineEnabled: Boolean): Boolean =
        !paused(s, pipelineEnabled) && s.link == LinkState.CONNECTED && s.frame?.capture == CaptureState.Running

    fun text(s: GlassesStatus, pipelineEnabled: Boolean, nowMs: Long): String {
        if (paused(s, pipelineEnabled)) return "Paused"
        val frame = s.frame
        if (frame == null || s.link != LinkState.CONNECTED) return when (s.link) {
            LinkState.CONNECTING -> "Connecting to glasses…"
            LinkState.PAUSED_BT -> "Bluetooth paused · reconnecting"
            LinkState.CONNECTED -> "Connected"
            else -> "Glasses offline"
        }
        return when (val c = frame.capture) {
            CaptureState.Running -> frame.nextCaptureAtMs?.let { "Capturing · next ${countdown(it, nowMs)}" } ?: "Capturing"
            is CaptureState.Deferred -> "Deferred · ${c.reason.take(40)}"
            is CaptureState.Blocked -> "Blocked · ${c.reason.take(40)}"
            is CaptureState.Paused -> "Paused"
        }
    }

    /** m:ss (h:mm:ss past an hour), rounded up; "now" once due. */
    fun countdown(atMs: Long, nowMs: Long): String {
        val s = ceil((atMs - nowMs) / 1000.0).toLong()
        if (s <= 0) return "now"
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }
}
```

- [ ] **Step 5: `PipelineControl.kt`**

```kotlin
package com.rokid.context.phone.ui.status

import android.content.Context
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The ⏸/▶ switch behind Home, Activity, the paused banner and the legacy Config screen (one source of truth). */
interface PipelineControl {
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
}

/** Phase 4: the legacy "pipeline enabled" flag + worker pause/resume. Phase 7 replaces it with PauseRecords (R-65). */
class LegacyPipelineControl(private val context: Context, private val scheduler: OrchestrationScheduler) : PipelineControl {
    private val state = MutableStateFlow(AppConfigStore.get(context).pipelineEnabled)
    override val enabled: StateFlow<Boolean> = state.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        if (state.value == enabled) return
        AppConfigStore.setPipelineEnabled(context, enabled)
        if (enabled) scheduler.resumePipeline() else scheduler.pausePipeline()
        state.value = enabled
    }
}
```

- [ ] **Step 6: `StatusUi.kt`**

```kotlin
package com.rokid.context.phone.ui.status

import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.core.model.Health
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.banner.BannerInputs
import com.rokid.context.phone.ui.banner.BannerResolver
import com.rokid.context.phone.ui.banner.BannerSpec
import com.rokid.context.phone.ui.banner.IndexState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Shared status block for Home and Activity: subline/strip text, live dot, pause state, battery, top banner. */
data class StatusUi(
    val captureText: String,
    val live: Boolean,
    val paused: Boolean,
    val batteryPct: Int?,
    val banner: BannerSpec?,
) {
    companion object {
        val Initial = StatusUi("Glasses offline", live = false, paused = false, batteryPct = null, banner = null)

        fun build(s: GlassesStatus, pipelineEnabled: Boolean, storagePct: Int?, nowMs: Long, index: IndexState = IndexState.NONE) = StatusUi(
            captureText = CaptureLine.text(s, pipelineEnabled, nowMs),
            live = CaptureLine.live(s, pipelineEnabled),
            paused = CaptureLine.paused(s, pipelineEnabled),
            batteryPct = s.glassesBatteryPct ?: s.frame?.glassesBatteryPct,
            banner = BannerResolver.resolve(
                BannerInputs(
                    aiBlockedText = (s.frame?.aiHealth as? Health.Blocked)?.text,
                    link = s.link,
                    capture = s.frame?.capture,
                    pipelineEnabled = pipelineEnabled,
                    index = index,
                    storagePct = storagePct,
                ),
            ),
        )

        fun flow(glasses: GlassesStatusSource, pipeline: PipelineControl, clock: Flow<Long>, storagePct: Flow<Int?>): Flow<StatusUi> =
            combine(glasses.status, pipeline.enabled, clock, storagePct) { s, enabled, now, storage -> build(s, enabled, storage, now) }
    }
}

/** RECONNECT and RESUME act here; FIX_AI is navigation and is handled by the route (Destinations.kt). */
object BannerActions {
    fun apply(action: BannerAction, glasses: GlassesStatusSource, pipeline: PipelineControl) {
        when (action) {
            BannerAction.RECONNECT -> glasses.requestReconnect()
            BannerAction.RESUME -> pipeline.setEnabled(true)
            BannerAction.FIX_AI -> Unit
        }
    }
}
```

- [ ] **Step 7: `StorageUsage.kt`**

```kotlin
package com.rokid.context.phone.ui.status

import android.content.Context
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

/** Memory storage vs the 100 MB soft quota (spec §2.4 Memory) for the §2.2 storage banner. */
object StorageUsage {
    const val QUOTA_BYTES = 100L * 1024 * 1024
    /** App-private frame JPEG folders (RokidFrameInbox). Task 11 adds Phase 3's folder if it differs. */
    val FRAME_DIRS = listOf("rokid_archive", "rokid_inbox")

    fun percent(usedBytes: Long, quotaBytes: Long = QUOTA_BYTES): Int =
        if (quotaBytes <= 0) 0 else ((usedBytes.coerceAtLeast(0) * 100) / quotaBytes).toInt()

    suspend fun usedBytes(context: Context): Long = withContext(Dispatchers.IO) {
        val db = context.getDatabasePath(DatabaseFactory.NAME)
        val dbBytes = listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).sumOf { if (it.isFile) it.length() else 0L }
        dbBytes + FRAME_DIRS.sumOf { name -> File(context.filesDir, name).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }

    /** null first (no banner while measuring), then the measured percent. Cold: measured once per collector. */
    fun percentFlow(context: Context): Flow<Int?> = flow { emit(null); emit(percent(usedBytes(context))) }
}
```

- [ ] **Step 8: `BannerHost.kt` and the fake pipeline**

`phone/src/main/java/com/rokid/context/phone/ui/banner/BannerHost.kt`:
```kotlin
package com.rokid.context.phone.ui.banner

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.Banner

/** Renders the single highest-severity banner under the header (spec §2.2); nothing when [spec] is null. */
@Composable
fun BannerHost(spec: BannerSpec?, onAction: (BannerAction) -> Unit, modifier: Modifier = Modifier) {
    if (spec == null) return
    val action = spec.action
    Banner(
        tone = spec.tone,
        title = spec.title,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        actionLabel = spec.actionLabel,
        onAction = if (action != null) ({ onAction(action) }) else null,
    )
}
```

`phone/src/test/java/com/rokid/context/phone/ui/FakePipelineControl.kt`:
```kotlin
package com.rokid.context.phone.ui

import com.rokid.context.phone.ui.status.PipelineControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakePipelineControl(initial: Boolean = true) : PipelineControl {
    private val state = MutableStateFlow(initial)
    override val enabled: StateFlow<Boolean> = state.asStateFlow()
    override fun setEnabled(enabled: Boolean) { state.value = enabled }
}
```

- [ ] **Step 9: Run the pure tests**

Run: `./gradlew :phone:testDebugUnitTest --tests "*BannerResolverTest" --tests "*CaptureLineTest"`
Expected: PASS (12 tests).

- [ ] **Step 10: Write the Android-dependent tests**

`phone/src/test/java/com/rokid/context/phone/ui/banner/BannerHostTest.kt`:
```kotlin
package com.rokid.context.phone.ui.banner

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class BannerHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsTheBannerAndReportsItsAction() {
        var got: BannerAction? = null
        compose.setContent { PcTheme { BannerHost(BannerResolver.resolve(BannerInputs(link = LinkState.OFFLINE)), onAction = { got = it }) } }
        compose.onNodeWithText("Glasses disconnected").assertIsDisplayed()
        compose.onNodeWithText("Reconnect").performClick()
        assertEquals(BannerAction.RECONNECT, got)
    }
}
```

`phone/src/test/java/com/rokid/context/phone/ui/status/StorageUsageTest.kt`:
```kotlin
package com.rokid.context.phone.ui.status

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class StorageUsageTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun percentScalesAndClamps() {
        assertEquals(50, StorageUsage.percent(50L * 1024 * 1024))
        assertEquals(0, StorageUsage.percent(-1))
        assertEquals(250, StorageUsage.percent(250L * 1024 * 1024))
        assertEquals(0, StorageUsage.percent(10, quotaBytes = 0))
    }

    @Test fun usedBytesCountsFrameFolders() = runBlocking {
        File(ctx.filesDir, "rokid_archive").apply { mkdirs() }.resolve("a.jpg").writeBytes(ByteArray(1024 * 1024))
        File(ctx.filesDir, "rokid_inbox").apply { mkdirs() }.resolve("b.jpg").writeBytes(ByteArray(512 * 1024))
        File(ctx.filesDir, "unrelated.bin").writeBytes(ByteArray(4096))
        assertEquals(1_572_864L, StorageUsage.usedBytes(ctx))
    }
}
```

`phone/src/test/java/com/rokid/context/phone/ui/status/LegacyPipelineControlTest.kt`:
```kotlin
package com.rokid.context.phone.ui.status

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.work.OrchestrationScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LegacyPipelineControlTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() = WorkManagerTestInitHelper.initializeTestWorkManager(ctx)

    private fun drainStates() = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(OrchestrationScheduler.QUEUE_DRAIN_WORK).get().map { it.state }

    @Test fun pauseStopsWorkersAndPersistsResumeReschedules() {
        val scheduler = OrchestrationScheduler(ctx).apply { scheduleQueueDrain() }
        val control = LegacyPipelineControl(ctx, scheduler)
        assertTrue(control.enabled.value)

        control.setEnabled(false)
        assertFalse(control.enabled.value)
        assertFalse(AppConfigStore.get(ctx).pipelineEnabled)
        assertTrue(drainStates().all { it == WorkInfo.State.CANCELLED })

        control.setEnabled(true)
        assertTrue(AppConfigStore.get(ctx).pipelineEnabled)
        assertEquals(true, drainStates().any { it == WorkInfo.State.ENQUEUED })
    }
}
```

- [ ] **Step 11: Run them**

Run: `./gradlew :phone:testDebugUnitTest --tests "*BannerHostTest" --tests "*StorageUsageTest" --tests "*LegacyPipelineControlTest"`
Expected: PASS (4 tests). (`OrchestrationScheduler.QUEUE_DRAIN_WORK` is a public `const` in its companion — no change needed there.)

- [ ] **Step 12: Gate + commit**

Run: `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: BUILD SUCCESSFUL.
```bash
git add android-companion/phone/src/main/java/com/rokid/context/phone/ui android-companion/phone/src/test/java/com/rokid/context/phone/ui
git commit -m "feat(phone): banner resolver, capture line, storage usage and pipeline control

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 5: Home (Ask-first) + interim typed Ask

**Requirements:** R-13 (gradient header + subline + ⏸/▶, hero card with 84 dp gradient mic, "Ask about your day", 3 suggested-question chips from recent memories with static fallbacks, **For you** feed of nudges and past asks newest first), R-20, R-72 (ask failures are fixed strings)

**Files:**
- Create: `android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/AskActions.kt`
- Test: `android-companion/services/orchestration/src/test/java/com/rokid/context/orchestration/AskActionsTest.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/home/AskService.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/home/HomeModel.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/home/HomeViewModel.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/home/HomeScreen.kt` (layout from Live Fit `phone/src/main/java/com/debasish/livefit/phone/ui/home/HomeScreen.kt` — header, `StartHero` → Ask hero; mock `phone-home.html` option B)
- Test: `android-companion/phone/src/test/java/com/rokid/context/phone/ui/home/HomeModelTest.kt`, `.../home/HomeScreenTest.kt`, `.../home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `MemoryGraph.memories/asks/timeline` (Tasks 3, Phase 2), `AiGraph.router.generate(Role.ANSWER, prompt)` (Phase 2), `PromptComposer`, `PromptTemplateRepository`, `PromptTemplateId.READ_CONTEXT_ANSWER`, `AppConfigStore.get(ctx).persona`; `StatusUi`, `BannerActions`, `BannerHost`, `PipelineControl` (Task 4); `GradientHeader`, `GradientCircleButton`, `SoftCard`, `IconChip`, `SectionLabel` (Phase 1).
- Produces:
  - `sealed interface AskOutcome { data class Answered(val askId: String); data class Failed(val message: String) }`, `object AskSources { fun encode(ids: List<String>): String; fun parse(json: String): List<String> }`, `object AskActions { const val EMPTY_QUESTION; NO_MEMORIES; AI_UNAVAILABLE; FAILED; suspend fun ask(context: Context, question: String, nowMs: Long = System.currentTimeMillis()): AskOutcome }` — package `com.rokid.context.orchestration`. (Task 6 adds `AskActions.rate`.)
  - `fun interface AskService { suspend fun ask(question: String): AskOutcome }` — package `com.rokid.context.phone.ui.home`.
  - `sealed interface FeedItem { id; atMs; timeLabel; data class Ask(id, atMs, timeLabel, question, answer, redacted); data class Nudge(id, atMs, timeLabel, text, why, redacted) }`, `object Feed { fun label(atMs, today, zone): String; fun ask(a: AskEntity, today, zone): FeedItem.Ask; fun nudge(n: NudgeEntity, today, zone): FeedItem.Nudge; fun build(asks, nudges, today: LocalDate, zone: ZoneId, limit: Int = 30): List<FeedItem> }`, `object SuggestedQuestions { val FALLBACKS: List<String>; fun from(recent: List<MemoryEntity>): List<String> }`, `data class HomeUiState(status: StatusUi = StatusUi.Initial, suggestions: List<String> = FALLBACKS, feed: List<FeedItem> = emptyList(), question: String = "", asking: Boolean = false, askError: String? = null, lastAnswer: FeedItem.Ask? = null, voiceAvailable: Boolean = false)`.
  - `class HomeViewModel(glasses, pipeline, timeline: TimelineRepository, asks: AskRepository, nudges: NudgeRepository, askService: AskService, clock: Flow<Long>, storagePct: Flow<Int?>, zone: () -> ZoneId) : ViewModel` with `val state: StateFlow<HomeUiState>`, `fun onQuestionChange(text)`, `fun ask(question: String = current)`, `fun togglePause()`, `fun onBannerAction(action)`.
  - `@Composable fun HomeScreen(state: HomeUiState, onQuestionChange: (String) -> Unit, onAsk: (String) -> Unit, onOpenAsk: (String) -> Unit, onTogglePause: () -> Unit, onBannerAction: (BannerAction) -> Unit)`; test tag `"home_list"`.

**Phase 4 scope of Ask (spec conflict resolved):** voice Ask (phone mic, R-60/R-67) is Phase 6 and the R-52 answer path (retrievers, guided decode, sources schema) is Phase 5. So the hero mic renders **disabled** (`voiceAvailable = false`) and the hero has a typed "Type a question" field (the spec's own §5.1.1 fallback) that runs `AskActions.ask` — the legacy `READ_CONTEXT_ANSWER` prompt over lexical hits (or recent trusted memories) through the ANSWER binding — and writes a real `ask` row so Home's feed and Ask detail work end to end. Phase 5 replaces `AskActions.ask`'s body; the `AskService` seam stays.

- [ ] **Step 1: Write the failing orchestration test**

`services/orchestration/src/test/java/com/rokid/context/orchestration/AskActionsTest.kt`:
```kotlin
package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.model.ModelProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AskActionsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var reply: (String) -> String = { "Milk is almost empty." }
    private val prompts = mutableListOf<String>()

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        AiGraph.resetForTests(AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String): String { prompts += prompt; return reply(prompt) } }
        }))
        // Binds ANSWER (and the other roles) through the Phase 2 legacy bridge, as the Config screen does.
        AppConfigStore.setProvider(ctx, "openai", "https://az.example", "key-1", "gpt-5.4", "")
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun remember(id: String, text: String, at: Long) =
        MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory(id, "short_term", 0.9, at, text))

    @Test fun answersFromMatchingMemoriesAndStoresTheAsk() = runBlocking {
        remember("rokid_mem_1", "Milk carton nearly empty in the fridge", 10)
        remember("rokid_mem_2", "Keys on the hallway shelf", 20)
        val r = AskActions.ask(ctx, "  milk? ", nowMs = 99) as AskOutcome.Answered
        val ask = MemoryGraph.get(ctx).asks.byId(r.askId)!!
        assertEquals("milk?", ask.question)
        assertEquals("Milk is almost empty.", ask.answer)
        assertEquals(99L, ask.ts)
        assertEquals("phone_typed", ask.source)
        assertEquals(listOf("rokid_mem_1"), AskSources.parse(ask.sourcesJson))
        assertTrue(prompts.single().contains("Milk carton nearly empty"))
        assertFalse(prompts.single().contains("hallway"))
    }

    @Test fun fallsBackToRecentMemoriesWhenNothingMatches() = runBlocking {
        remember("rokid_mem_1", "Milk carton nearly empty", 10)
        remember("rokid_mem_2", "Keys on the hallway shelf", 20)
        val r = AskActions.ask(ctx, "what happened today", nowMs = 99) as AskOutcome.Answered
        assertEquals(listOf("rokid_mem_2", "rokid_mem_1"), AskSources.parse(MemoryGraph.get(ctx).asks.byId(r.askId)!!.sourcesJson))
    }

    @Test fun failureNeverShowsProviderText() = runBlocking {
        remember("rokid_mem_1", "Milk carton nearly empty", 10)
        reply = { throw RuntimeException("request failed: 401 {\"error\":\"key sk-live-123 rejected\"}") }
        val r = AskActions.ask(ctx, "milk") as AskOutcome.Failed
        assertEquals(AskActions.AI_UNAVAILABLE, r.message)
        assertFalse(r.message.contains("sk-live") || r.message.contains("401"))
        assertTrue(MemoryGraph.get(ctx).asks.recent(10).first().isEmpty())
    }

    @Test fun emptyQuestionAndNoMemoriesGiveShortMessages() = runBlocking {
        assertEquals(AskOutcome.Failed(AskActions.EMPTY_QUESTION), AskActions.ask(ctx, "   "))
        assertEquals(AskOutcome.Failed(AskActions.NO_MEMORIES), AskActions.ask(ctx, "milk"))
        assertTrue(prompts.isEmpty())
    }

    @Test fun malformedSourcesJsonGivesNoSources() {
        assertEquals(emptyList<String>(), AskSources.parse("not json"))
        assertEquals(emptyList<String>(), AskSources.parse("{}"))
        assertEquals(listOf("a"), AskSources.parse("""[1, "a", "a", " ", null]"""))
        assertEquals("""["a","b"]""", AskSources.encode(listOf("a", "b", "a")))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :services:orchestration:testDebugUnitTest --tests "*AskActionsTest"`
Expected: FAIL — `Unresolved reference: AskActions`.

- [ ] **Step 3: `AskActions.kt`**

```kotlin
package com.rokid.context.orchestration

import android.content.Context
import android.util.Log
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.Role
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptTemplateId
import com.rokid.context.prompt.PromptTemplateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

sealed interface AskOutcome {
    data class Answered(val askId: String) : AskOutcome
    data class Failed(val message: String) : AskOutcome
}

/** `ask.sourcesJson` = JSON array of cited memory ids (spec §4.4 ANSWER `sources`). Anything else decodes to none. */
object AskSources {
    fun encode(ids: List<String>): String = JsonArray(ids.distinct().map { JsonPrimitive(it) }).toString()

    fun parse(json: String): List<String> = runCatching {
        Json.parseToJsonElement(json).jsonArray
            .mapNotNull { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } }
            .distinct()
    }.getOrDefault(emptyList())
}

/**
 * Typed Ask from the phone (Phase 4 interim; Phase 5 swaps in the R-52 answer path behind the same call): lexical hits,
 * or the most recent trusted memories, → legacy READ_CONTEXT_ANSWER prompt → ANSWER binding → an `ask` row.
 * Failures return fixed short strings (spec §6.3); provider text is never surfaced.
 */
object AskActions {
    const val EMPTY_QUESTION = "Type a question first"
    const val NO_MEMORIES = "Nothing saved yet — ask again once a few moments are captured"
    const val AI_UNAVAILABLE = "AI unavailable — check Settings › Model endpoints"
    const val FAILED = "Couldn't answer that — try again"
    private const val TAG = "AskActions"

    suspend fun ask(context: Context, question: String, nowMs: Long = System.currentTimeMillis()): AskOutcome {
        val q = question.replace('\n', ' ').trim().take(500)
        if (q.isEmpty()) return AskOutcome.Failed(EMPTY_QUESTION)
        val graph = MemoryGraph.get(context)
        val hits = graph.memories.search(q, 8).filter { it.reviewState == ReviewState.OK }
        val pool = hits.ifEmpty { graph.memories.recent(30).filter { it.reviewState == ReviewState.OK } }.take(10)
        if (pool.isEmpty()) return AskOutcome.Failed(NO_MEMORIES)
        val prompt = withContext(Dispatchers.IO) {
            PromptComposer(PromptTemplateRepository(context)).compose(
                templateId = PromptTemplateId.READ_CONTEXT_ANSWER,
                variables = mapOf(
                    "persona" to AppConfigStore.get(context).persona,
                    "input_language" to "auto",
                    "query" to q,
                    "retrieved_context" to pool.joinToString("\n") { "${it.type} | conf=${"%.2f".format(Locale.US, it.confidence)} | ${it.text}" },
                ),
            )
        }
        val answer = try {
            AiGraph.get(context).router.generate(Role.ANSWER, prompt).trim()
        } catch (c: CancellationException) {
            throw c
        } catch (e: ModelCallException) {
            Log.i(TAG, "ask failed: ${e.message}") // message is already key-redacted (Phase 2); never log the throwable
            return AskOutcome.Failed(AI_UNAVAILABLE)
        } catch (e: Exception) {
            Log.i(TAG, "ask failed (${e.javaClass.simpleName})")
            return AskOutcome.Failed(FAILED)
        }
        if (answer.isEmpty()) return AskOutcome.Failed(FAILED)
        val id = "ask_${UUID.randomUUID()}"
        graph.asks.upsert(
            AskEntity(
                id = id, ts = nowMs, question = q, answer = answer.take(2_000), sourcesJson = AskSources.encode(pool.map { it.id }),
                rating = null, source = "phone_typed", redacted = false,
            ),
        )
        return AskOutcome.Answered(id)
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :services:orchestration:testDebugUnitTest --tests "*AskActionsTest"`
Expected: PASS (5 tests). (The fake provider's `RuntimeException` reaches `AskActions` as a `ModelCallException` — `ModelRouter` wraps every non-cancellation failure.)

- [ ] **Step 5: Write the failing phone model + screen tests**

`phone/src/test/java/com/rokid/context/phone/ui/home/HomeModelTest.kt`:
```kotlin
package com.rokid.context.phone.ui.home

import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.Fixtures.ZONE
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeModelTest {
    @Test fun feedMergesAsksAndNudgesNewestFirstWithStableTies() {
        val feed = Feed.build(
            asks = listOf(Fixtures.ask("a1", Fixtures.at(TODAY, 8, 15)), Fixtures.ask("a2", Fixtures.at(TODAY, 9, 41))),
            nudges = listOf(Fixtures.nudge("n1", Fixtures.at(TODAY, 9, 41)), Fixtures.nudge("n0", Fixtures.at(TODAY.minusDays(2), 7))),
            today = TODAY, zone = ZONE,
        )
        assertEquals(listOf("a2", "n1", "a1", "n0"), feed.map { it.id })
        assertEquals("09:41", feed[0].timeLabel)
        assertEquals("10 Oct", feed[3].timeLabel)
        assertEquals(2, Feed.build(feed.indices.map { Fixtures.ask("x$it", it.toLong()) }, emptyList(), TODAY, ZONE, limit = 2).size)
    }

    @Test fun suggestionsUseRecentTagsThenFallbacks() {
        val recent = listOf(
            Fixtures.memory("m1", "Milk", 3, tags = "milk, fridge"),
            Fixtures.memory("m2", "Keys", 2, tags = "Milk,x,😀emoji, a-b"),
        )
        assertEquals(listOf("What about milk?", "What about fridge?", SuggestedQuestions.FALLBACKS[0]), SuggestedQuestions.from(recent))
        assertEquals(SuggestedQuestions.FALLBACKS, SuggestedQuestions.from(emptyList()))
    }

    @Test fun hindiTagsBecomeSuggestions() {
        assertEquals("What about दूध?", SuggestedQuestions.from(listOf(Fixtures.memory("m1", "दूध खत्म", 1, tags = "दूध")))[0])
    }
}
```

`phone/src/test/java/com/rokid/context/phone/ui/home/HomeScreenTest.kt`:
```kotlin
package com.rokid.context.phone.ui.home

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.banner.BannerInputs
import com.rokid.context.phone.ui.banner.BannerResolver
import com.rokid.context.phone.ui.status.StatusUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val asked = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private var pauses = 0
    private var banner: BannerAction? = null

    private fun show(state: HomeUiState) = compose.setContent {
        PcTheme { HomeScreen(state, onQuestionChange = {}, onAsk = { asked += it }, onOpenAsk = { opened += it }, onTogglePause = { pauses++ }, onBannerAction = { banner = it }) }
    }

    private val live = StatusUi("Capturing · next 4:12", live = true, paused = false, batteryPct = 82, banner = null)

    @Test fun headerShowsCaptureLineAndPauseToggles() {
        show(HomeUiState(status = live))
        compose.onNodeWithText("Proactive Context").assertIsDisplayed()
        compose.onNodeWithText("● Capturing · next 4:12").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause capture").performClick()
        assertEquals(1, pauses)
    }

    @Test fun pausedHeaderOffersResumeAndShowsBanner() {
        show(HomeUiState(status = StatusUi("Paused", false, true, null, BannerResolver.resolve(BannerInputs(link = LinkState.OFFLINE)))))
        compose.onNodeWithText("❚❚ Paused").assertIsDisplayed()
        compose.onNodeWithContentDescription("Resume capture").assertIsDisplayed()
        compose.onNodeWithText("Reconnect").performClick()
        assertEquals(BannerAction.RECONNECT, banner)
    }

    @Test fun suggestionChipsAskImmediatelyAndMicIsDisabled() {
        show(HomeUiState(status = live, suggestions = listOf("What about milk?", "Where did I leave my keys?", "What did I eat today?")))
        compose.onNodeWithText("Ask about your day").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ask by voice").assertIsNotEnabled()
        compose.onNodeWithText("What about milk?").performClick()
        assertEquals(listOf("What about milk?"), asked)
    }

    @Test fun typedQuestionIsSentAndErrorsShow() {
        show(HomeUiState(status = live, question = "where are my keys", askError = "AI unavailable — check Settings › Model endpoints"))
        compose.onNodeWithContentDescription("Send question").performClick()
        assertEquals(listOf("where are my keys"), asked)
        compose.onNodeWithText("AI unavailable — check Settings › Model endpoints").assertIsDisplayed()
    }

    @Test fun feedShowsAsksAndNudgesAndOpensAnAsk() {
        val feed = listOf(
            FeedItem.Nudge("n1", 2, "09:41", "Milk looked almost empty", "Seen in the fridge at 09:41", redacted = false),
            FeedItem.Ask("a1", 1, "08:15", "Did I take my vitamins?", "Yes, at 07:52.", redacted = false),
        )
        show(HomeUiState(status = live, feed = feed))
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("You asked “Did I take my vitamins?”"))
        compose.onNodeWithText("You asked “Did I take my vitamins?”").performClick()
        assertEquals(listOf("a1"), opened)
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("Milk looked almost empty"))
        compose.onNodeWithText("Milk looked almost empty").performClick()
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("Seen in the fridge at 09:41"))
        compose.onNodeWithText("Seen in the fridge at 09:41").assertIsDisplayed()
    }

    @Test fun emptyFeedShowsHint() {
        show(HomeUiState(status = live))
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("Answers and heads-ups will appear here."))
        compose.onNodeWithText("Answers and heads-ups will appear here.").assertIsDisplayed()
    }

    @Test fun inlineAnswerOpensItsDetail() {
        show(HomeUiState(status = live, lastAnswer = FeedItem.Ask("a9", 1, "10:00", "milk?", "Milk is almost empty.", false)))
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("Milk is almost empty."))
        compose.onNodeWithText("Milk is almost empty.").performClick()
        assertEquals(listOf("a9"), opened)
    }
}
```

- [ ] **Step 6: Run to verify failure**

Run: `./gradlew :phone:testDebugUnitTest --tests "*HomeModelTest" --tests "*HomeScreenTest"`
Expected: FAIL — `Unresolved reference: Feed` / `HomeScreen`.

- [ ] **Step 7: `AskService.kt` + `HomeModel.kt`**

`phone/src/main/java/com/rokid/context/phone/ui/home/AskService.kt`:
```kotlin
package com.rokid.context.phone.ui.home

import com.rokid.context.orchestration.AskOutcome

/** Seam for the Home ask path: Phase 4 → `AskActions.ask`; Phase 5 → R-52 answer path; Phase 6 adds voice. */
fun interface AskService {
    suspend fun ask(question: String): AskOutcome
}
```

`phone/src/main/java/com/rokid/context/phone/ui/home/HomeModel.kt`:
```kotlin
package com.rokid.context.phone.ui.home

import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.phone.ui.status.StatusUi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface FeedItem {
    val id: String
    val atMs: Long
    val timeLabel: String

    data class Ask(override val id: String, override val atMs: Long, override val timeLabel: String, val question: String, val answer: String, val redacted: Boolean) : FeedItem
    data class Nudge(override val id: String, override val atMs: Long, override val timeLabel: String, val text: String, val why: String, val redacted: Boolean) : FeedItem
}

/** "For you" feed (spec §2.4 Home): nudges and past asks, newest first. */
object Feed {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun label(atMs: Long, today: LocalDate, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(atMs).atZone(zone)
        return if (t.toLocalDate() == today) TIME.format(t) else DATE.format(t)
    }

    fun ask(a: AskEntity, today: LocalDate, zone: ZoneId) = FeedItem.Ask(a.id, a.ts, label(a.ts, today, zone), a.question, a.answer, a.redacted)
    fun nudge(n: NudgeEntity, today: LocalDate, zone: ZoneId) = FeedItem.Nudge(n.id, n.ts, label(n.ts, today, zone), n.text, n.why, n.redacted)

    fun build(asks: List<AskEntity>, nudges: List<NudgeEntity>, today: LocalDate, zone: ZoneId, limit: Int = 30): List<FeedItem> =
        (asks.map { ask(it, today, zone) } + nudges.map { nudge(it, today, zone) })
            .sortedWith(compareByDescending<FeedItem> { it.atMs }.thenBy { it.id })
            .take(limit)
}

/** Three chips for the hero (spec §2.4): topics from recent memory tags (English or Hindi words), then static fallbacks. */
object SuggestedQuestions {
    val FALLBACKS = listOf("What do I need from the store?", "Where did I leave my keys?", "What did I eat today?")

    private fun isWordChar(c: Char): Boolean {
        val type = Character.getType(c)
        return Character.isLetter(c) || c == ' ' || type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
    }

    fun from(recent: List<MemoryEntity>): List<String> {
        val topics = recent.asSequence()
            .flatMap { it.tags.split(',').asSequence() }
            .map { it.trim().lowercase() }
            .filter { it.length in 2..24 && it.all(::isWordChar) }
            .distinct()
            .take(3)
            .map { "What about $it?" }
            .toList()
        return (topics + FALLBACKS).distinct().take(3)
    }
}

data class HomeUiState(
    val status: StatusUi = StatusUi.Initial,
    val suggestions: List<String> = SuggestedQuestions.FALLBACKS,
    val feed: List<FeedItem> = emptyList(),
    val question: String = "",
    val asking: Boolean = false,
    val askError: String? = null,
    val lastAnswer: FeedItem.Ask? = null,
    /** Phone voice arrives with Phase 6 (R-60/R-67); until then the mic is shown disabled. */
    val voiceAvailable: Boolean = false,
)
```
(`x` is dropped by the 2-character minimum, `a-b` and the emoji tag by the word-character rule; `दूध` is letter + combining vowel sign + letter and passes.)

- [ ] **Step 8: `HomeScreen.kt`**

```kotlin
package com.rokid.context.phone.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.GradientCircleButton
import com.rokid.context.core.ui.components.GradientHeader
import com.rokid.context.core.ui.components.IconChip
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.banner.BannerHost

/** Home, Ask-first (spec §2.4, mock phone-home.html option B). Stateless. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onQuestionChange: (String) -> Unit,
    onAsk: (String) -> Unit,
    onOpenAsk: (String) -> Unit,
    onTogglePause: () -> Unit,
    onBannerAction: (BannerAction) -> Unit,
) {
    var expandedNudge by rememberSaveable { mutableStateOf<String?>(null) }
    val glyph = when { state.status.live -> "● "; state.status.paused -> "❚❚ "; else -> "" }
    LazyColumn(Modifier.fillMaxSize().background(PcColors.Surface).testTag("home_list")) {
        item {
            GradientHeader(title = "Proactive Context", subtitle = glyph + state.status.captureText) {
                val label = if (state.status.paused) "Resume capture" else "Pause capture"
                IconButton(onClick = onTogglePause, modifier = Modifier.clip(CircleShape).background(PcColors.Surface)) {
                    Icon(if (state.status.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = label, tint = PcColors.Ink)
                }
            }
        }
        item { BannerHost(state.status.banner, onBannerAction) }
        item { AskHero(state, onQuestionChange, onAsk, onOpenAsk) }
        item { SectionLabel("For you") }
        if (state.feed.isEmpty()) {
            item {
                Text(
                    "Answers and heads-ups will appear here.", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        items(state.feed, key = { it.id }) { item ->
            when (item) {
                is FeedItem.Ask -> FeedCard(
                    icon = Icons.Outlined.ChatBubbleOutline, colors = PcColors.ChipSky, meta = item.timeLabel,
                    title = "You asked “${item.question}”", body = item.answer, onClick = { onOpenAsk(item.id) },
                )
                is FeedItem.Nudge -> FeedCard(
                    icon = Icons.Outlined.Lightbulb, colors = PcColors.ChipAmber, meta = "Heads up · ${item.timeLabel}", title = item.text,
                    body = if (expandedNudge == item.id && !item.redacted && item.why.isNotBlank()) item.why else null,
                    onClick = { expandedNudge = if (expandedNudge == item.id) null else item.id },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskHero(state: HomeUiState, onQuestionChange: (String) -> Unit, onAsk: (String) -> Unit, onOpenAsk: (String) -> Unit) {
    SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GradientCircleButton(Icons.Rounded.Mic, contentDescription = "Ask by voice", onClick = {}, enabled = state.voiceAvailable)
            Spacer(Modifier.height(12.dp))
            Text("Ask about your day", style = MaterialTheme.typography.titleLarge, color = PcColors.Ink)
            Text(
                if (state.voiceAvailable) "or tap your glasses" else "Type a question below, or tap your glasses",
                style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.suggestions.forEach { QuestionPill(it, enabled = !state.asking) { onAsk(it) } }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.question,
                onValueChange = onQuestionChange,
                placeholder = { Text("Type a question") },
                singleLine = true,
                enabled = !state.asking,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onAsk(state.question) }),
                trailingIcon = {
                    IconButton(onClick = { onAsk(state.question) }, enabled = !state.asking && state.question.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send question")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.asking) Text("Thinking…", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, modifier = Modifier.padding(top = 8.dp))
            state.askError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PcColors.ChipCoral.second, modifier = Modifier.padding(top = 8.dp)) }
            state.lastAnswer?.let { a ->
                SoftCard(Modifier.padding(top = 12.dp).fillMaxWidth(), onClick = { onOpenAsk(a.id) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(a.question, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                        Text(a.answer, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text("See sources ›", style = MaterialTheme.typography.labelMedium, color = PcColors.MintDeep, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionPill(text: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = PcColors.Ink,
        modifier = Modifier.clip(shape).background(PcColors.SurfaceSoft).border(1.dp, PcColors.Line, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun FeedCard(icon: ImageVector, colors: Pair<Color, Color>, meta: String, title: String, body: String?, onClick: () -> Unit) {
    SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(14.dp)) {
            IconChip(icon, colors, size = 36.dp, shapeRadius = 10.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(meta, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                Text(title, style = MaterialTheme.typography.titleMedium, color = PcColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
```
Ask cards show the answer as `body`; nudges show `why` only when expanded (spec §2.1 has no nudge route, so the nudge "detail" is this inline expansion).

- [ ] **Step 9: Run model + screen tests**

Run: `./gradlew :phone:testDebugUnitTest --tests "*HomeModelTest" --tests "*HomeScreenTest"`
Expected: PASS (10 tests).

- [ ] **Step 10: Write the failing ViewModel test**

`phone/src/test/java/com/rokid/context/phone/ui/home/HomeViewModelTest.kt`:
```kotlin
package com.rokid.context.phone.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.LinkState
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val glasses = FakeGlassesStatusSource()
    private val pipeline = FakePipelineControl()
    private val questions = mutableListOf<String>()
    private var outcome: suspend (String) -> AskOutcome = { AskOutcome.Failed("AI unavailable — check Settings › Model endpoints") }
    private lateinit var vm: HomeViewModel

    @Before fun setUp() {
        graph = TestGraph.fresh(ctx)
        vm = HomeViewModel(
            glasses, pipeline, graph.timeline, graph.asks, graph.nudges, AskService { q -> questions += q; outcome(q) },
            clock = flowOf(Fixtures.NOW), storagePct = flowOf(10), zone = { Fixtures.ZONE },
        )
    }

    @After fun tearDown() { vm.cancelScope(); TestGraph.close(ctx) }

    @Test fun feedMergesAsksAndNudgesNewestFirst() = runBlocking {
        graph.asks.upsert(Fixtures.ask("a1", Fixtures.at(Fixtures.TODAY, 8)))
        graph.nudges.addIgnore(Fixtures.nudge("n1", Fixtures.at(Fixtures.TODAY, 9)))
        val s = withTimeout(5_000) { vm.state.first { it.feed.size == 2 } }
        assertEquals(listOf("n1", "a1"), s.feed.map { it.id })
        assertEquals("Glasses offline", s.status.captureText)
    }

    @Test fun askSuccessClearsTheQuestionAndShowsTheAnswerInline() = runBlocking {
        outcome = { q -> graph.asks.upsert(Fixtures.ask("a9", Fixtures.NOW, question = q, answer = "On the shelf.")); AskOutcome.Answered("a9") }
        vm.onQuestionChange("where are my keys")
        vm.ask()
        val s = withTimeout(5_000) { vm.state.first { it.lastAnswer?.id == "a9" } }
        assertEquals("", s.question)
        assertFalse(s.asking)
        assertEquals("On the shelf.", s.lastAnswer?.answer)
        assertEquals(listOf("where are my keys"), questions)
    }

    @Test fun askFailureShowsTheServiceMessageAndBlankIsIgnored() = runBlocking {
        vm.ask("   ")
        assertTrue(questions.isEmpty())
        vm.ask("milk")
        val s = withTimeout(5_000) { vm.state.first { it.askError != null } }
        assertEquals("AI unavailable — check Settings › Model endpoints", s.askError)
        assertEquals("milk", s.question)
    }

    @Test fun pauseAndBannerActionsReachTheirTargets() = runBlocking {
        vm.togglePause()
        assertFalse(pipeline.enabled.value)
        assertTrue(withTimeout(5_000) { vm.state.first { it.status.paused } }.status.paused)
        vm.onBannerAction(BannerAction.RESUME)
        assertTrue(pipeline.enabled.value)
        vm.onBannerAction(BannerAction.RECONNECT)
        assertEquals(1, glasses.reconnectRequests)
        assertEquals(LinkState.OFFLINE, glasses.status.value.link)
    }
}
```

- [ ] **Step 11: Run to verify failure**

Run: `./gradlew :phone:testDebugUnitTest --tests "*HomeViewModelTest"`
Expected: FAIL — `Unresolved reference: HomeViewModel`.

- [ ] **Step 12: `HomeViewModel.kt`**

```kotlin
package com.rokid.context.phone.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.memory.AskRepository
import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.NudgeRepository
import com.rokid.context.memory.TimelineRepository
import com.rokid.context.orchestration.AskActions
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.status.BannerActions
import com.rokid.context.phone.ui.status.PipelineControl
import com.rokid.context.phone.ui.status.StatusUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val glasses: GlassesStatusSource,
    private val pipeline: PipelineControl,
    timeline: TimelineRepository,
    private val asks: AskRepository,
    nudges: NudgeRepository,
    private val askService: AskService,
    clock: Flow<Long>,
    storagePct: Flow<Int?>,
    private val zone: () -> ZoneId,
) : ViewModel() {
    private data class Local(val question: String = "", val asking: Boolean = false, val error: String? = null, val lastAskId: String? = null)

    private val local = MutableStateFlow(Local())
    private val today = clock.map { DayBucket.dayOf(it, zone()) }.distinctUntilChanged()

    private val feed: Flow<List<FeedItem>> =
        combine(asks.recent(30), nudges.recent(30), today) { a, n, d -> Feed.build(a, n, d, zone()) }

    private val lastAnswer: Flow<FeedItem.Ask?> = combine(local.map { it.lastAskId }.distinctUntilChanged(), today) { id, d -> id to d }
        .flatMapLatest { (id, d) -> if (id == null) flowOf(null) else asks.observe(id).map { a -> a?.let { Feed.ask(it, d, zone()) } } }

    val state: StateFlow<HomeUiState> = combine(
        StatusUi.flow(glasses, pipeline, clock, storagePct),
        timeline.recentVisibleMemories(20).map(SuggestedQuestions::from),
        feed,
        lastAnswer,
        local,
    ) { status, suggestions, items, last, l ->
        HomeUiState(status, suggestions, items, l.question, l.asking, l.error, last)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onQuestionChange(text: String) = local.update { it.copy(question = text.take(500), error = null) }

    fun ask(question: String = local.value.question) {
        val q = question.trim()
        if (q.isEmpty() || local.value.asking) return
        local.update { it.copy(question = q, asking = true, error = null) }
        viewModelScope.launch {
            val r = try { askService.ask(q) } catch (c: CancellationException) { throw c } catch (e: Exception) { AskOutcome.Failed(AskActions.FAILED) }
            when (r) {
                is AskOutcome.Answered -> local.update { it.copy(question = "", asking = false, lastAskId = r.askId) }
                is AskOutcome.Failed -> local.update { it.copy(asking = false, error = r.message) }
            }
        }
    }

    fun togglePause() = pipeline.setEnabled(!pipeline.enabled.value)

    fun onBannerAction(action: BannerAction) = BannerActions.apply(action, glasses, pipeline)
}
```

- [ ] **Step 13: Run, gate, commit**

Run: `./gradlew :phone:testDebugUnitTest --tests "*HomeViewModelTest"` then `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: PASS (4 tests); BUILD SUCCESSFUL.
```bash
git add android-companion/services/orchestration/src android-companion/phone/src/main/java/com/rokid/context/phone/ui/home android-companion/phone/src/test/java/com/rokid/context/phone/ui/home
git commit -m "feat(phone): Ask-first Home with suggestions, For you feed and interim typed Ask

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 6: Ask detail — sources, rating, action state

**Requirements:** R-14 (question, answer, "Based on" source cards with thumb + memory text + confidence, 👍/👎 where 👎 enqueues a critic review of the cited memories, proposed action + its state), R-20

**Files:**
- Modify: `android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/AskActions.kt` (add `rate`)
- Modify: `android-companion/services/orchestration/src/test/java/com/rokid/context/orchestration/AskActionsTest.kt` (add rating tests)
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/ask/AskDetailModel.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/ask/AskDetailViewModel.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/ask/AskDetailScreen.kt`
- Test: `android-companion/phone/src/test/java/com/rokid/context/phone/ui/ask/AskDetailModelTest.kt`, `.../ask/AskDetailScreenTest.kt`, `.../ask/AskDetailViewModelTest.kt`

**Interfaces:**
- Consumes: `AskSources`, `AskActions` (Task 5); `AskRepository.observe/byId/setRating`, `ProposalRepository.forOrigin`, `TimelineRepository.memoriesByIds/framesByIds` (Task 3); `QueueRuntimeStore.enqueue`, `QueuePayloads.reprocess`, `QueuePriority.PRIORITY_REPROCESS`, `OrchestrationScheduler.enqueueQueueDrainNow` (legacy); `FrameThumb`, `ConfidenceChip` (Task 2); `ScreenHeader`, `SoftCard`, `SectionLabel` (Phase 1).
- Produces:
  - `AskActions.REVIEW_REASON`, `suspend fun AskActions.rate(context: Context, askId: String, up: Boolean): Boolean` (👍 = 1, 👎 = −1; false for unknown or redacted asks).
  - `data class SourceCard(memoryId: String, text: String, confidence: Double, framePath: String?)`, `data class ProposalLine(title: String, state: String)`, `data class AskDetailUiState(loading = true, found = false, question = "", answer = "", timeLabel = "", redacted = false, rating: Int? = null, sources: List<SourceCard> = emptyList(), missingSources = 0, proposal: ProposalLine? = null)`, `object AskDetailModel { val NOT_FOUND; fun build(ask, memories, frames, proposals, zone): AskDetailUiState; fun proposalLine(p: ProposalEntity): ProposalLine }` — package `com.rokid.context.phone.ui.ask`.
  - `class AskDetailViewModel(askId: String, asks: AskRepository, proposals: ProposalRepository, timeline: TimelineRepository, rater: suspend (String, Boolean) -> Boolean, zone: () -> ZoneId) : ViewModel` — `val state: StateFlow<AskDetailUiState>`, `fun rate(up: Boolean)`.
  - `@Composable fun AskDetailScreen(state: AskDetailUiState, onBack: () -> Unit, onRate: (Boolean) -> Unit, onOpenMemory: (String) -> Unit)`; rating content descriptions `"Helpful"` / `"Not helpful"`.

The action row is read-only in Phase 4 (proposals are created and resolved by Phase 7, spec §5.3); it shows the stored state in plain words.

- [ ] **Step 1: Write the failing rating tests**

Append to `AskActionsTest` (add imports `androidx.work.testing.WorkManagerTestInitHelper`, `com.rokid.context.data.runtime.QueuePayloads`, `com.rokid.context.data.runtime.QueueRuntimeStore`, `com.rokid.context.domain.queue.QueuePriority`, `com.rokid.context.memory.db.AskEntity`; and add `WorkManagerTestInitHelper.initializeTestWorkManager(ctx)` as the first line of `setUp()`):
```kotlin
    private fun reviewJobs() = QueueRuntimeStore.recent(ctx, 50).filter { it.queue == QueuePriority.PRIORITY_REPROCESS }

    private suspend fun seedAsk(redacted: Boolean = false) {
        remember("rokid_mem_1", "Milk carton nearly empty", 10)
        MemoryGraph.get(ctx).asks.upsert(AskEntity("a1", 50, "milk?", "Almost empty", AskSources.encode(listOf("rokid_mem_1", "gone")), null, "phone_typed", redacted))
    }

    @Test fun thumbsDownEnqueuesOneReviewOfTheCitedMemories() = runBlocking {
        seedAsk()
        assertTrue(AskActions.rate(ctx, "a1", up = false))
        assertTrue(AskActions.rate(ctx, "a1", up = false)) // second tap is a no-op
        assertEquals(-1, MemoryGraph.get(ctx).asks.byId("a1")!!.rating)
        val jobs = reviewJobs()
        assertEquals(1, jobs.size)
        val payload = QueuePayloads.parse(jobs.single().payload)
        assertEquals(AskActions.REVIEW_REASON, QueuePayloads.string(payload, "reason"))
        assertEquals("Milk carton nearly empty", QueuePayloads.string(payload, "source_context"))
    }

    @Test fun thumbsUpOnlyStoresTheRating() = runBlocking {
        seedAsk()
        assertTrue(AskActions.rate(ctx, "a1", up = true))
        assertEquals(1, MemoryGraph.get(ctx).asks.byId("a1")!!.rating)
        assertTrue(reviewJobs().isEmpty())
    }

    @Test fun redactedOrUnknownAsksCannotBeRated() = runBlocking {
        seedAsk(redacted = true)
        assertFalse(AskActions.rate(ctx, "a1", up = false))
        assertFalse(AskActions.rate(ctx, "missing", up = true))
        assertEquals(null, MemoryGraph.get(ctx).asks.byId("a1")!!.rating)
        assertTrue(reviewJobs().isEmpty())
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :services:orchestration:testDebugUnitTest --tests "*AskActionsTest"`
Expected: FAIL — `Unresolved reference: rate` / `REVIEW_REASON`.

- [ ] **Step 3: Implement `rate`**

Add these imports to `AskActions.kt`: `com.rokid.context.data.runtime.QueuePayloads`, `com.rokid.context.data.runtime.QueueRuntimeStore`, `com.rokid.context.domain.queue.QueuePriority`, `com.rokid.context.work.OrchestrationScheduler`. Inside `object AskActions` add:
```kotlin
    const val REVIEW_REASON = "User marked an answer unhelpful"

    /**
     * 👍 = 1, 👎 = −1 (spec §2.4 Ask detail). Changing to 👎 enqueues one critic re-summarise over the cited memories
     * (the legacy PRIORITY_REPROCESS queue). Repeating the same rating is a no-op. Redacted or unknown asks → false.
     */
    suspend fun rate(context: Context, askId: String, up: Boolean): Boolean {
        val graph = MemoryGraph.get(context)
        val ask = graph.asks.byId(askId) ?: return false
        if (ask.redacted) return false
        val rating = if (up) 1 else -1
        if (ask.rating == rating) return true
        graph.asks.setRating(askId, rating)
        if (!up) {
            val cited = graph.timeline.memoriesByIds(AskSources.parse(ask.sourcesJson))
            if (cited.isNotEmpty()) withContext(Dispatchers.IO) {
                QueueRuntimeStore.enqueue(
                    context = context,
                    queue = QueuePriority.PRIORITY_REPROCESS,
                    payload = QueuePayloads.reprocess(reason = REVIEW_REASON, sourceContext = cited.joinToString("\n") { it.text }),
                    personaSnapshot = AppConfigStore.get(context).persona,
                    ttlHours = 24,
                )
                OrchestrationScheduler(context).enqueueQueueDrainNow()
            }
        }
        return true
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :services:orchestration:testDebugUnitTest --tests "*AskActionsTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Write the failing phone tests**

`phone/src/test/java/com/rokid/context/phone/ui/ask/AskDetailModelTest.kt`:
```kotlin
package com.rokid.context.phone.ui.ask

import com.rokid.context.memory.db.ReviewState
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.ZONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskDetailModelTest {
    private val ask = Fixtures.ask("a1", Fixtures.at(Fixtures.TODAY, 9, 41), sources = """["m2","m1","gone","m3"]""")
    private val memories = listOf(
        Fixtures.memory("m1", "Keys on shelf", 1, frameId = "f1", confidence = 0.71),
        Fixtures.memory("m2", "Milk low", 2, confidence = 0.92),
        Fixtures.memory("m3", "Hidden", 3, review = ReviewState.INVALIDATED),
    )
    private val frames = listOf(Fixtures.frame("f1", 1, path = "/frames/f1.jpg"))

    @Test fun sourcesFollowCitationOrderAndCountMissing() {
        val s = AskDetailModel.build(ask, memories, frames, emptyList(), ZONE)
        assertTrue(s.found)
        assertEquals(listOf("m2", "m1"), s.sources.map { it.memoryId })
        assertEquals("/frames/f1.jpg", s.sources[1].framePath)
        assertEquals(null, s.sources[0].framePath)
        assertEquals(2, s.missingSources) // "gone" and the invalidated m3
        assertEquals("Mon 12 Oct · 09:41", s.timeLabel)
    }

    @Test fun redactedAskHidesSources() {
        val s = AskDetailModel.build(ask.copy(redacted = true, answer = "Removed — a source was forgotten"), memories, frames, emptyList(), ZONE)
        assertTrue(s.redacted)
        assertTrue(s.sources.isEmpty())
        assertEquals(0, s.missingSources)
        assertEquals("Removed — a source was forgotten", s.answer)
    }

    @Test fun malformedSourcesGiveNoCards() {
        val s = AskDetailModel.build(ask.copy(sourcesJson = "{oops"), memories, frames, emptyList(), ZONE)
        assertTrue(s.sources.isEmpty()); assertEquals(0, s.missingSources)
    }

    @Test fun proposalStatesReadAsPlainWords() {
        assertEquals(ProposalLine("Add to shopping list", "Waiting for your answer"), AskDetailModel.proposalLine(Fixtures.proposal("p", "a1")))
        assertEquals(ProposalLine("Reminder", "Added"), AskDetailModel.proposalLine(Fixtures.proposal("p", "a1", type = "REMINDER", state = "EXECUTED")))
        assertEquals("Turned off in Settings", AskDetailModel.proposalLine(Fixtures.proposal("p", "a1", state = "SUPPRESSED")).state)
        assertEquals(ProposalLine("Suggested action", ""), AskDetailModel.proposalLine(Fixtures.proposal("p", "a1", type = "??", state = "??")))
    }
}
```

`phone/src/test/java/com/rokid/context/phone/ui/ask/AskDetailScreenTest.kt`:
```kotlin
package com.rokid.context.phone.ui.ask

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AskDetailScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ratings = mutableListOf<Boolean>()
    private val opened = mutableListOf<String>()

    private fun show(state: AskDetailUiState) = compose.setContent {
        PcTheme { AskDetailScreen(state, onBack = {}, onRate = { ratings += it }, onOpenMemory = { opened += it }) }
    }

    private val found = AskDetailUiState(
        loading = false, found = true, question = "Where are my keys?", answer = "On the hallway shelf.", timeLabel = "Mon 12 Oct · 09:41",
        rating = 1, sources = listOf(SourceCard("m1", "Keys on hallway shelf", 0.71, null)), missingSources = 1,
        proposal = ProposalLine("Reminder", "Waiting for your answer"),
    )

    @Test fun showsAnswerSourcesAndActionAndOpensAMemory() {
        show(found)
        compose.onNodeWithText("Where are my keys?").assertIsDisplayed()
        compose.onNodeWithText("On the hallway shelf.").assertIsDisplayed()
        compose.onNodeWithText("71%").assertIsDisplayed()
        compose.onNodeWithText("1 source is no longer available").assertIsDisplayed()
        compose.onNodeWithText("Reminder · Waiting for your answer").assertIsDisplayed()
        compose.onNodeWithText("Keys on hallway shelf").performClick()
        assertEquals(listOf("m1"), opened)
    }

    @Test fun ratingButtonsReportAndShowSelection() {
        show(found)
        compose.onNodeWithContentDescription("Helpful").assertIsSelected()
        compose.onNodeWithContentDescription("Not helpful").performClick()
        assertEquals(listOf(false), ratings)
    }

    @Test fun redactedAnswerHasNoRating() {
        show(found.copy(redacted = true, answer = "Removed — a source was forgotten", sources = emptyList(), missingSources = 0))
        compose.onNodeWithText("Removed — a source was forgotten").assertIsDisplayed()
        compose.onNodeWithContentDescription("Helpful").assertDoesNotExist()
    }

    @Test fun unknownAskShowsNotFound() {
        show(AskDetailModel.NOT_FOUND)
        compose.onNodeWithText("This answer is no longer available").assertIsDisplayed()
    }
}
```

`phone/src/test/java/com/rokid/context/phone/ui/ask/AskDetailViewModelTest.kt`:
```kotlin
package com.rokid.context.phone.ui.ask

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AskDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<AskDetailViewModel>()

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(id: String) = AskDetailViewModel(
        id, graph.asks, graph.proposals, graph.timeline,
        rater = { askId, up -> graph.asks.setRating(askId, if (up) 1 else -1); true }, zone = { Fixtures.ZONE },
    ).also { vms += it }

    @Test fun loadsSourcesAndProposalAndReflectsRating() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1))
        graph.asks.upsert(Fixtures.ask("a1", Fixtures.NOW, sources = """["m1"]"""))
        graph.proposals.addIgnore(Fixtures.proposal("p1", "a1", state = "EXPIRED"))
        val model = vm("a1")
        val s = withTimeout(5_000) { model.state.first { it.found } }
        assertEquals(listOf("Keys on shelf"), s.sources.map { it.text })
        assertEquals("Expired", s.proposal?.state)
        model.rate(up = true)
        assertEquals(1, withTimeout(5_000) { model.state.first { it.rating == 1 } }.rating)
    }

    @Test fun unknownAskIdIsNotFound() = runBlocking {
        val s = withTimeout(5_000) { vm("missing").state.first { !it.loading } }
        assertFalse(s.found)
    }
}
```

- [ ] **Step 6: Run to verify failure**

Run: `./gradlew :phone:testDebugUnitTest --tests "*AskDetail*"`
Expected: FAIL — `Unresolved reference: AskDetailModel`.

- [ ] **Step 7: `AskDetailModel.kt`**

```kotlin
package com.rokid.context.phone.ui.ask

import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.AskSources
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SourceCard(val memoryId: String, val text: String, val confidence: Double, val framePath: String?)
data class ProposalLine(val title: String, val state: String)

data class AskDetailUiState(
    val loading: Boolean = true,
    val found: Boolean = false,
    val question: String = "",
    val answer: String = "",
    val timeLabel: String = "",
    val redacted: Boolean = false,
    val rating: Int? = null,
    val sources: List<SourceCard> = emptyList(),
    /** Cited ids that no longer resolve to a visible memory (forgotten, invalidated, or never stored). */
    val missingSources: Int = 0,
    val proposal: ProposalLine? = null,
)

object AskDetailModel {
    val NOT_FOUND = AskDetailUiState(loading = false, found = false)
    private val TIME = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)

    fun build(ask: AskEntity, memories: List<MemoryEntity>, frames: List<FrameEntity>, proposals: List<ProposalEntity>, zone: ZoneId): AskDetailUiState {
        val ids = if (ask.redacted) emptyList() else AskSources.parse(ask.sourcesJson)
        val byId = memories.associateBy { it.id }
        val paths = frames.associate { it.id to it.path }
        val cards = ids.mapNotNull { id ->
            byId[id]?.takeIf { it.reviewState != ReviewState.INVALIDATED }?.let { m -> SourceCard(m.id, m.text, m.confidence, m.frameId?.let(paths::get)) }
        }
        return AskDetailUiState(
            loading = false, found = true, question = ask.question, answer = ask.answer,
            timeLabel = TIME.format(Instant.ofEpochMilli(ask.ts).atZone(zone)), redacted = ask.redacted, rating = ask.rating,
            sources = cards, missingSources = ids.size - cards.size, proposal = proposals.firstOrNull()?.let(::proposalLine),
        )
    }

    fun proposalLine(p: ProposalEntity) = ProposalLine(
        title = when (p.type) {
            "REMINDER" -> "Reminder"
            "SHOPPING_ADD" -> "Add to shopping list"
            "CALENDAR_EVENT" -> "Calendar event"
            else -> "Suggested action"
        },
        state = when (p.state) {
            "PENDING" -> "Waiting for your answer"
            "EXECUTING" -> "Adding…"
            "EXECUTED" -> "Added"
            "DECLINED" -> "Declined"
            "EXPIRED" -> "Expired"
            "SUPPRESSED" -> "Turned off in Settings"
            "FAILED" -> "Couldn't add"
            else -> ""
        },
    )
}
```

- [ ] **Step 8: `AskDetailViewModel.kt`**

```kotlin
package com.rokid.context.phone.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.memory.AskRepository
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.TimelineRepository
import com.rokid.context.orchestration.AskSources
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AskDetailViewModel(
    private val askId: String,
    asks: AskRepository,
    proposals: ProposalRepository,
    timeline: TimelineRepository,
    private val rater: suspend (String, Boolean) -> Boolean,
    zone: () -> ZoneId,
) : ViewModel() {
    val state: StateFlow<AskDetailUiState> = asks.observe(askId).flatMapLatest { ask ->
        if (ask == null) {
            flowOf(AskDetailModel.NOT_FOUND)
        } else {
            proposals.forOrigin(ask.id).map { props ->
                val memories = timeline.memoriesByIds(if (ask.redacted) emptyList() else AskSources.parse(ask.sourcesJson))
                val frames = timeline.framesByIds(memories.mapNotNull { it.frameId })
                AskDetailModel.build(ask, memories, frames, props, zone())
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AskDetailUiState())

    fun rate(up: Boolean) {
        viewModelScope.launch { rater(askId, up) }
    }
}
```

- [ ] **Step 9: `AskDetailScreen.kt`**

```kotlin
package com.rokid.context.phone.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.ConfidenceChip
import com.rokid.context.core.ui.components.FrameThumb
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors

/** Ask detail (spec §2.4). Stateless. */
@Composable
fun AskDetailScreen(state: AskDetailUiState, onBack: () -> Unit, onRate: (Boolean) -> Unit, onOpenMemory: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(PcColors.Surface).verticalScroll(rememberScrollState())) {
        ScreenHeader("Answer", onBack)
        if (state.loading) return@Column
        if (!state.found) {
            Text("This answer is no longer available", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(20.dp))
            return@Column
        }
        Text("YOU ASKED · ${state.timeLabel}", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = 20.dp))
        Text(state.question, style = MaterialTheme.typography.titleLarge, color = PcColors.Ink, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(state.answer, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink)
                if (!state.redacted) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = { onRate(true) }, modifier = Modifier.semantics { selected = state.rating == 1 }) {
                            Icon(if (state.rating == 1) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp, contentDescription = "Helpful", tint = PcColors.MintDeep)
                        }
                        IconButton(onClick = { onRate(false) }, modifier = Modifier.semantics { selected = state.rating == -1 }) {
                            Icon(if (state.rating == -1) Icons.Rounded.ThumbDown else Icons.Outlined.ThumbDown, contentDescription = "Not helpful", tint = PcColors.ChipCoral.second)
                        }
                    }
                }
            }
        }
        state.proposal?.let { p ->
            SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Text("${p.title} · ${p.state}", style = MaterialTheme.typography.titleMedium, color = PcColors.Ink, modifier = Modifier.padding(16.dp))
            }
        }
        if (!state.redacted) {
            SectionLabel("Based on")
            if (state.sources.isEmpty() && state.missingSources == 0) {
                Text("No saved memories were cited", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = 20.dp))
            }
            state.sources.forEach { s ->
                SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), onClick = { onOpenMemory(s.memoryId) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FrameThumb(s.framePath, size = 52.dp)
                        Text(s.text, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        ConfidenceChip(s.confidence)
                    }
                }
            }
            if (state.missingSources > 0) {
                val n = state.missingSources
                Text(
                    "$n source${if (n == 1) " is" else "s are"} no longer available", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
```

- [ ] **Step 10: Run, gate, commit**

Run: `./gradlew :phone:testDebugUnitTest --tests "*AskDetail*"` then `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: PASS (10 tests); BUILD SUCCESSFUL.
```bash
git add android-companion/services/orchestration/src android-companion/phone/src/main/java/com/rokid/context/phone/ui/ask android-companion/phone/src/test/java/com/rokid/context/phone/ui/ask
git commit -m "feat(phone): Ask detail with cited sources, rating with critic review, and action state

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 7: Activity (Today / Week / Month) and day drill-down

**Requirements:** R-15 (status strip on every period, `SegmentedSwitch` Today/Week/Month, Today tiles Frames/Memories/Answers/Nudges + timeline of FrameThumb + memory text + ConfidenceChip grouped Morning/Afternoon/Evening, Week/Month `BarChart` of frames per day + totals tiles + day rows → `day/{date}`; buckets via Phase 2 `DayBucket` in the zone at query time), R-20

**Files:**
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/activity/ActivityModel.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/activity/Timeline.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/activity/ActivityViewModel.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/activity/ActivityScreen.kt` (mock `activity-tab.html` option A and its Week preview; grouping idea from Live Fit `phone/src/main/java/com/debasish/livefit/phone/ui/history/DailyActivity.kt`, `dayLabel` ported from it)
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/activity/DayViewModel.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/activity/DayScreen.kt`
- Test: `android-companion/phone/src/test/java/com/rokid/context/phone/ui/activity/ActivityModelTest.kt`, `.../activity/ActivityScreenTest.kt`, `.../activity/ActivityViewModelTest.kt`, `.../activity/DayTest.kt`

**Interfaces:**
- Consumes: `TimelineRepository.framesIn/frameTimesIn/visibleMemoriesIn/visibleMemoryTimesIn`, `AskRepository.timesIn`, `NudgeRepository.timesIn` (Task 3); `DayBucket.bounds/dayOf` (Phase 2); `StatusUi`, `BannerActions`, `BannerHost`, `PipelineControl` (Task 4); `StatusStrip`, `BarChart`, `FrameThumb`, `ConfidenceChip` (Task 2); `MetricTile`, `SegmentedSwitch`, `GradientHeader`, `ChipRow`, `SectionLabel`, `SoftCard`, `ScreenHeader` (Phase 1).
- Produces (package `com.rokid.context.phone.ui.activity`):
  - `enum class Period { TODAY, WEEK, MONTH; companion fun parse(raw: String?): Period }`; `data class PeriodWindow(period, start: LocalDate, endExclusive: LocalDate)` with `val days: List<LocalDate>`, `fun millis(zone): LongRange`, `companion fun of(period, today): PeriodWindow` (week = ISO Monday–Sunday, month = calendar month).
  - `data class Tiles(frames, memories, answers, nudges: Int)`, `data class TimelineRow(memoryId, time, text, confidence, framePath: String?, frameId: String?)`, `data class TimelineSection(label, rows)`, `data class DayBar(date, label, count)`, `data class DayRow(date, title, subtitle)`.
  - `object Timeline { fun partOfDay(hour: Int): String; fun sections(memories, frames, zone): List<TimelineSection> }`; `object ActivityModel { fun countByDay(times, window, zone): Map<LocalDate, Int>; fun bars(window, frameCounts): List<DayBar>; fun dayRows(window, frameCounts, memoryCounts, today): List<DayRow>; fun dayLabel(date, today): String; fun header(window, today): String; fun plural(n, one, many = one + "s"): String }`.
  - `data class ActivityUiState(period = TODAY, header = "", status = StatusUi.Initial, tiles = Tiles(0,0,0,0), sections = emptyList(), bars = emptyList(), dayRows = emptyList(), today: LocalDate? = null)`.
  - `class ActivityViewModel(initial: Period, glasses, pipeline, timeline, asks, nudges, clock: Flow<Long>, storagePct: Flow<Int?>, zone: () -> ZoneId) : ViewModel` — `state`, `selectPeriod(p)`, `togglePause()`, `onBannerAction(a)`.
  - `@Composable fun ActivityScreen(state, onSelectPeriod: (Period) -> Unit, onTogglePause: () -> Unit, onBannerAction: (BannerAction) -> Unit, onOpenMemory: (String) -> Unit, onOpenFrames: (LocalDate) -> Unit, onOpenDay: (LocalDate) -> Unit)`; test tag `"activity_list"`.
  - `fun LazyListScope.timelineItems(sections: List<TimelineSection>, onOpenMemory: (String) -> Unit)`.
  - `data class DayUiState(loading = true, found = false, date: LocalDate? = null, title = "", sections = emptyList(), frameCount = 0)`, `class DayViewModel(dateArg: String?, timeline, zone: () -> ZoneId, today: () -> LocalDate)`, `@Composable fun DayScreen(state, onBack, onOpenMemory, onOpenFrames: (LocalDate) -> Unit)`.

Timeline rows are memories (`reviewState = OK`); a row's thumbnail is its own frame (`memory.frameId`). Legacy batched summaries carry no `frameId`, so they show the placeholder — the **Frames** tile opens that day's frame viewer instead (no time-proximity guessing).

- [ ] **Step 1: Write the failing pure test**

`phone/src/test/java/com/rokid/context/phone/ui/activity/ActivityModelTest.kt`:
```kotlin
package com.rokid.context.phone.ui.activity

import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.Fixtures.ZONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ActivityModelTest {
    @Test fun unknownPeriodFallsBackToToday() {
        assertEquals(Period.TODAY, Period.parse(null))
        assertEquals(Period.TODAY, Period.parse("yearly"))
        assertEquals(Period.WEEK, Period.parse(" week "))
        assertEquals(Period.MONTH, Period.parse("MONTH"))
    }

    @Test fun weekStartsMondayWithNarrowLabels() {
        val w = PeriodWindow.of(Period.WEEK, LocalDate.of(2026, 10, 15))
        assertEquals(LocalDate.of(2026, 10, 12), w.start)
        assertEquals(LocalDate.of(2026, 10, 19), w.endExclusive)
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), ActivityModel.bars(w, emptyMap()).map { it.label })
    }

    @Test fun monthCoversTheWholeMonth() {
        val w = PeriodWindow.of(Period.MONTH, LocalDate.of(2026, 2, 14))
        assertEquals(28, w.days.size)
        assertEquals(listOf("1", "8", "15", "22"), ActivityModel.bars(w, emptyMap()).map { it.label }.filter { it.isNotEmpty() })
    }

    @Test fun dstWeekStillHasSevenBars() {
        val ny = ZoneId.of("America/New_York")
        val w = PeriodWindow.of(Period.WEEK, LocalDate.of(2026, 3, 8)) // US DST starts Sun 8 Mar 2026 02:00
        assertEquals(LocalDate.of(2026, 3, 2), w.start)
        assertEquals(7, w.days.size)
        val r = w.millis(ny)
        assertEquals(167L * 3_600_000, r.last + 1 - r.first)
        val lateSunday = LocalDate.of(2026, 3, 8).atTime(23, 30).atZone(ny).toInstant().toEpochMilli()
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 1), ActivityModel.bars(w, ActivityModel.countByDay(listOf(lateSunday, r.first), w, ny)).map { it.count })
    }

    @Test fun bucketsUseTheZoneGivenAtQueryTime() {
        val instant = Instant.parse("2026-10-11T20:00:00Z").toEpochMilli() // 01:30 on 12 Oct in Kolkata, still 11 Oct in UTC
        val w = PeriodWindow.of(Period.WEEK, TODAY)
        assertEquals(mapOf(TODAY to 1), ActivityModel.countByDay(listOf(instant), w, ZONE))
        assertTrue(ActivityModel.countByDay(listOf(instant), w, ZoneOffset.UTC).isEmpty())
    }

    @Test fun sectionsSplitAtNoonAndFivePmOldestFirst() {
        val mems = listOf(
            Fixtures.memory("e", "Dinner", Fixtures.at(TODAY, 17, 0)),
            Fixtures.memory("a2", "Late lunch", Fixtures.at(TODAY, 16, 59)),
            Fixtures.memory("a1", "Lunch", Fixtures.at(TODAY, 12, 0)),
            Fixtures.memory("m2", "Coffee", Fixtures.at(TODAY, 11, 59)),
            Fixtures.memory("m1", "Breakfast", Fixtures.at(TODAY, 7, 40), frameId = "f1", confidence = 0.92),
        )
        val s = Timeline.sections(mems, listOf(Fixtures.frame("f1", 1, path = "/f1.jpg")), ZONE)
        assertEquals(listOf("Morning", "Afternoon", "Evening"), s.map { it.label })
        assertEquals(listOf("m1", "m2"), s[0].rows.map { it.memoryId })
        assertEquals(listOf("a1", "a2"), s[1].rows.map { it.memoryId })
        assertEquals("07:40", s[0].rows[0].time)
        assertEquals("/f1.jpg", s[0].rows[0].framePath)
        assertEquals(null, s[0].rows[1].framePath)
    }

    @Test fun emptyWeekShowsZeroBarsAndHint() {
        val w = PeriodWindow.of(Period.WEEK, TODAY)
        assertEquals(List(7) { 0 }, ActivityModel.bars(w, emptyMap()).map { it.count })
        assertTrue(ActivityModel.dayRows(w, emptyMap(), emptyMap(), TODAY).isEmpty())
    }

    @Test fun dayRowsAreNewestFirstWithCounts() {
        val today = TODAY.plusDays(1) // Tue 13 Oct
        val w = PeriodWindow.of(Period.WEEK, today)
        val rows = ActivityModel.dayRows(w, frameCounts = mapOf(TODAY to 1), memoryCounts = mapOf(TODAY to 2, today to 1), today = today)
        assertEquals(listOf(DayRow(today, "Today", "0 frames · 1 memory"), DayRow(TODAY, "Yesterday", "1 frame · 2 memories")), rows)
        assertEquals("Sat 10 Oct", ActivityModel.dayLabel(LocalDate.of(2026, 10, 10), today))
        assertEquals("Sat 10 Oct 2025", ActivityModel.dayLabel(LocalDate.of(2025, 10, 10), today))
    }

    @Test fun headersPerPeriod() {
        assertEquals("Mon 12 Oct", ActivityModel.header(PeriodWindow.of(Period.TODAY, TODAY), TODAY))
        assertEquals("Week of 12 Oct", ActivityModel.header(PeriodWindow.of(Period.WEEK, TODAY), TODAY))
        assertEquals("October 2026", ActivityModel.header(PeriodWindow.of(Period.MONTH, TODAY), TODAY))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :phone:testDebugUnitTest --tests "*ActivityModelTest"`
Expected: FAIL — `Unresolved reference: Period`.

- [ ] **Step 3: `ActivityModel.kt`**

```kotlin
package com.rokid.context.phone.ui.activity

import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.phone.ui.status.StatusUi
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class Period {
    TODAY, WEEK, MONTH;

    companion object {
        /** Route argument `activity?period=` (spec §2.1); anything unknown means Today. */
        fun parse(raw: String?): Period = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: TODAY
    }
}

/** Local dates [start, endExclusive). Millis come from [DayBucket.bounds] so DST days are 23/25 h. */
data class PeriodWindow(val period: Period, val start: LocalDate, val endExclusive: LocalDate) {
    val days: List<LocalDate> get() = generateSequence(start) { it.plusDays(1) }.takeWhile { it.isBefore(endExclusive) }.toList()

    fun millis(zone: ZoneId): LongRange = DayBucket.bounds(start, zone).first until DayBucket.bounds(endExclusive, zone).first

    companion object {
        fun of(period: Period, today: LocalDate): PeriodWindow = when (period) {
            Period.TODAY -> PeriodWindow(period, today, today.plusDays(1))
            Period.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { PeriodWindow(period, it, it.plusDays(7)) }
            Period.MONTH -> today.withDayOfMonth(1).let { PeriodWindow(period, it, it.plusMonths(1)) }
        }
    }
}

data class Tiles(val frames: Int, val memories: Int, val answers: Int, val nudges: Int)
data class TimelineRow(val memoryId: String, val time: String, val text: String, val confidence: Double, val framePath: String?, val frameId: String?)
data class TimelineSection(val label: String, val rows: List<TimelineRow>)
data class DayBar(val date: LocalDate, val label: String, val count: Int)
data class DayRow(val date: LocalDate, val title: String, val subtitle: String)

private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

object Timeline {
    private val ORDER = listOf("Morning", "Afternoon", "Evening")

    fun partOfDay(hour: Int): String = when {
        hour < 12 -> "Morning"
        hour < 17 -> "Afternoon"
        else -> "Evening"
    }

    /** Sections in day order, rows oldest first (the day reads as a story). */
    fun sections(memories: List<MemoryEntity>, frames: List<FrameEntity>, zone: ZoneId): List<TimelineSection> {
        val paths = frames.associate { it.id to it.path }
        val grouped = memories.sortedBy { it.createdAt }.groupBy { partOfDay(Instant.ofEpochMilli(it.createdAt).atZone(zone).hour) }
        return ORDER.mapNotNull { label ->
            grouped[label]?.let { rows ->
                TimelineSection(label, rows.map { m ->
                    TimelineRow(m.id, HHMM.format(Instant.ofEpochMilli(m.createdAt).atZone(zone)), m.text, m.confidence, m.frameId?.let(paths::get), m.frameId)
                })
            }
        }
    }
}

object ActivityModel {
    private val SHORT_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val SHORT_DAY_YEAR = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    private val MONTH_TICKS = setOf(1, 8, 15, 22, 29)

    fun countByDay(times: List<Long>, window: PeriodWindow, zone: ZoneId): Map<LocalDate, Int> =
        times.groupingBy { DayBucket.dayOf(it, zone) }.eachCount().filterKeys { !it.isBefore(window.start) && it.isBefore(window.endExclusive) }

    fun bars(window: PeriodWindow, frameCounts: Map<LocalDate, Int>): List<DayBar> = window.days.map { d ->
        val label = when (window.period) {
            Period.WEEK -> d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH)
            Period.MONTH -> if (d.dayOfMonth in MONTH_TICKS) "${d.dayOfMonth}" else ""
            Period.TODAY -> ""
        }
        DayBar(d, label, frameCounts[d] ?: 0)
    }

    fun dayRows(window: PeriodWindow, frameCounts: Map<LocalDate, Int>, memoryCounts: Map<LocalDate, Int>, today: LocalDate): List<DayRow> =
        window.days.filter { (frameCounts[it] ?: 0) + (memoryCounts[it] ?: 0) > 0 }.sortedDescending().map { d ->
            DayRow(d, dayLabel(d, today), "${plural(frameCounts[d] ?: 0, "frame")} · ${plural(memoryCounts[d] ?: 0, "memory", "memories")}")
        }

    /** Ported from Live Fit `DailyActivity.dayLabel`. */
    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(if (date.year == today.year) SHORT_DAY else SHORT_DAY_YEAR)
    }

    fun header(window: PeriodWindow, today: LocalDate): String = when (window.period) {
        Period.TODAY -> today.format(SHORT_DAY)
        Period.WEEK -> "Week of ${window.start.format(DAY_MONTH)}"
        Period.MONTH -> window.start.format(MONTH_YEAR)
    }

    fun plural(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"
}

data class ActivityUiState(
    val period: Period = Period.TODAY,
    val header: String = "",
    val status: StatusUi = StatusUi.Initial,
    val tiles: Tiles = Tiles(0, 0, 0, 0),
    /** Today only. */
    val sections: List<TimelineSection> = emptyList(),
    /** Week / Month only. */
    val bars: List<DayBar> = emptyList(),
    val dayRows: List<DayRow> = emptyList(),
    val today: LocalDate? = null,
)
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :phone:testDebugUnitTest --tests "*ActivityModelTest"`
Expected: PASS (9 tests).

- [ ] **Step 5: Write the failing screen + ViewModel + day tests**

`phone/src/test/java/com/rokid/context/phone/ui/activity/ActivityScreenTest.kt`:
```kotlin
package com.rokid.context.phone.ui.activity

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.status.StatusUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ActivityScreenTest {
    @get:Rule val compose = createComposeRule()
    private val periods = mutableListOf<Period>()
    private val memories = mutableListOf<String>()
    private val frameDays = mutableListOf<LocalDate>()
    private val days = mutableListOf<LocalDate>()
    private var pauses = 0
    private val status = StatusUi("Capturing · next 4:12", live = true, paused = false, batteryPct = 82, banner = null)

    private fun show(state: ActivityUiState) = compose.setContent {
        PcTheme {
            ActivityScreen(
                state, onSelectPeriod = { periods += it }, onTogglePause = { pauses++ }, onBannerAction = {},
                onOpenMemory = { memories += it }, onOpenFrames = { frameDays += it }, onOpenDay = { days += it },
            )
        }
    }

    @Test fun todayShowsStripTilesAndTimeline() {
        show(
            ActivityUiState(
                period = Period.TODAY, header = "Mon 12 Oct", status = status, tiles = Tiles(14, 2, 3, 1), today = TODAY,
                sections = listOf(TimelineSection("Morning", listOf(TimelineRow("m1", "07:40", "Oatmeal and coffee at the counter", 0.92, null, null)))),
            ),
        )
        compose.onNodeWithText("What your glasses noticed").assertIsDisplayed()
        compose.onNodeWithText("Capturing · next 4:12").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause capture").performClick()
        assertEquals(1, pauses)
        compose.onNodeWithText("14").assertIsDisplayed()
        compose.onNodeWithText("Frames").performClick()
        assertEquals(listOf(TODAY), frameDays)
        compose.onNodeWithTag("activity_list").performScrollToNode(hasText("Oatmeal and coffee at the counter"))
        compose.onNodeWithText("MORNING").assertIsDisplayed()
        compose.onNodeWithText("92%").assertIsDisplayed()
        compose.onNodeWithText("Oatmeal and coffee at the counter").performClick()
        assertEquals(listOf("m1"), memories)
    }

    @Test fun emptyTodayShowsHint() {
        show(ActivityUiState(period = Period.TODAY, header = "Mon 12 Oct", status = status, today = TODAY))
        compose.onNodeWithTag("activity_list").performScrollToNode(hasText("Nothing captured yet today"))
        compose.onNodeWithText("Nothing captured yet today").assertIsDisplayed()
    }

    @Test fun weekShowsChartAndOpensADay() {
        val week = PeriodWindow.of(Period.WEEK, TODAY)
        show(
            ActivityUiState(
                period = Period.WEEK, header = "Week of 12 Oct", status = status, tiles = Tiles(96, 210, 17, 9), today = TODAY,
                bars = ActivityModel.bars(week, mapOf(TODAY to 14)),
                dayRows = listOf(DayRow(TODAY.minusDays(1), "Yesterday", "9 frames · 20 memories")),
            ),
        )
        compose.onNodeWithContentDescription("Bar chart: M 14, T 0, W 0, T 0, F 0, S 0, S 0").assertIsDisplayed()
        compose.onNodeWithTag("activity_list").performScrollToNode(hasText("Yesterday"))
        compose.onNodeWithText("Yesterday").performClick()
        assertEquals(listOf(TODAY.minusDays(1)), days)
    }

    @Test fun emptyWeekShowsHintAndSwitchReportsPeriod() {
        show(ActivityUiState(period = Period.WEEK, header = "Week of 12 Oct", status = status, today = TODAY, bars = ActivityModel.bars(PeriodWindow.of(Period.WEEK, TODAY), emptyMap())))
        compose.onNodeWithText("Month").performClick() // before scrolling: off-screen LazyColumn items are disposed
        assertEquals(listOf(Period.MONTH), periods)
        compose.onNodeWithTag("activity_list").performScrollToNode(hasText("No activity this week"))
        compose.onNodeWithText("No activity this week").assertIsDisplayed()
    }
}
```

`phone/src/test/java/com/rokid/context/phone/ui/activity/ActivityViewModelTest.kt`:
```kotlin
package com.rokid.context.phone.ui.activity

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ActivityViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<ActivityViewModel>()

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(clock: kotlinx.coroutines.flow.Flow<Long>, initial: Period = Period.TODAY) = ActivityViewModel(
        initial, FakeGlassesStatusSource(), FakePipelineControl(), graph.timeline, graph.asks, graph.nudges,
        clock = clock, storagePct = flowOf(null), zone = { Fixtures.ZONE },
    ).also { vms += it }

    @Test fun switchingPeriodRequeriesTheWindow() = runBlocking {
        val tuesday = TODAY.plusDays(1)
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", Fixtures.at(tuesday, 9)))
        graph.frames.add(Fixtures.frame("f1", Fixtures.at(TODAY, 10)))
        val model = vm(flowOf(Fixtures.at(tuesday, 10)))
        val today = withTimeout(5_000) { model.state.first { it.today == tuesday && it.tiles.memories == 1 } }
        assertEquals(0, today.tiles.frames)
        assertEquals(listOf("Morning"), today.sections.map { it.label })
        model.selectPeriod(Period.WEEK)
        val week = withTimeout(5_000) { model.state.first { it.period == Period.WEEK && it.tiles.frames == 1 } }
        assertEquals(7, week.bars.size)
        assertEquals(1, week.bars[0].count)
        assertEquals(listOf("Today", "Yesterday"), week.dayRows.map { it.title })
        assertEquals("Week of 12 Oct", week.header)
    }

    @Test fun todayRollsOverAtLocalMidnight() = runBlocking {
        graph.memories.add(Fixtures.memory("late", "Late snack", Fixtures.at(TODAY, 22)))
        val clock = MutableStateFlow(Fixtures.at(TODAY, 23, 59))
        val model = vm(clock)
        withTimeout(5_000) { model.state.first { it.today == TODAY && it.tiles.memories == 1 } }
        clock.value = Fixtures.at(TODAY.plusDays(1), 0, 1)
        val next = withTimeout(5_000) { model.state.first { it.today == TODAY.plusDays(1) } }
        assertEquals(0, next.tiles.memories)
    }
}
```

`phone/src/test/java/com/rokid/context/phone/ui/activity/DayTest.kt`:
```kotlin
package com.rokid.context.phone.ui.activity

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DayTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<DayViewModel>()

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(arg: String?) = DayViewModel(arg, graph.timeline, zone = { Fixtures.ZONE }, today = { TODAY }).also { vms += it }

    @Test fun malformedDateShowsNotFound() = runBlocking {
        for (arg in listOf("2026-13-45", "", null)) assertFalse("$arg", withTimeout(5_000) { vm(arg).state.first { !it.loading } }.found)
    }

    @Test fun loadsThatDaysTimeline() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Market run", Fixtures.at(TODAY.minusDays(2), 18)))
        val s = withTimeout(5_000) { vm(TODAY.minusDays(2).toString()).state.first { it.found && it.sections.isNotEmpty() } }
        assertEquals("Sat 10 Oct", s.title)
        assertEquals(listOf("Evening"), s.sections.map { it.label })
    }

    @Test fun dayScreenListsRowsAndOpensFrames() {
        val opened = mutableListOf<LocalDate>()
        val state = DayUiState(
            loading = false, found = true, date = TODAY, title = "Today", frameCount = 3,
            sections = listOf(TimelineSection("Morning", listOf(TimelineRow("m1", "07:40", "Breakfast", 0.9, null, null)))),
        )
        compose.setContent { PcTheme { DayScreen(state, onBack = {}, onOpenMemory = {}, onOpenFrames = { opened += it }) } }
        compose.onNodeWithText("Breakfast").assertIsDisplayed()
        compose.onNodeWithText("View 3 photos").performClick()
        assertEquals(listOf(TODAY), opened)
    }

    @Test fun dayScreenNotFound() {
        compose.setContent { PcTheme { DayScreen(DayUiState(loading = false, found = false), onBack = {}, onOpenMemory = {}, onOpenFrames = {}) } }
        compose.onNodeWithText("Day not found").assertIsDisplayed()
    }
}
```

- [ ] **Step 6: Run to verify failure**

Run: `./gradlew :phone:testDebugUnitTest --tests "*ActivityScreenTest" --tests "*ActivityViewModelTest" --tests "*DayTest"`
Expected: FAIL — `Unresolved reference: ActivityScreen` / `ActivityViewModel` / `DayViewModel`.

- [ ] **Step 7: `Timeline.kt`**

```kotlin
package com.rokid.context.phone.ui.activity

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.ConfidenceChip
import com.rokid.context.core.ui.components.FrameThumb
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors

/** Morning / Afternoon / Evening timeline (spec §2.4 Activity Today, Day drill-down). */
fun LazyListScope.timelineItems(sections: List<TimelineSection>, onOpenMemory: (String) -> Unit) {
    sections.forEach { section ->
        item(key = "label_${section.label}") { SectionLabel(section.label) }
        items(section.rows, key = { it.memoryId }) { row -> TimelineCard(row, onOpenMemory) }
    }
}

@Composable
private fun TimelineCard(row: TimelineRow, onOpenMemory: (String) -> Unit) {
    SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth(), onClick = { onOpenMemory(row.memoryId) }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FrameThumb(row.framePath, size = 52.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(row.time, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                Text(row.text, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            ConfidenceChip(row.confidence)
        }
    }
}
```

- [ ] **Step 8: `ActivityScreen.kt`**

```kotlin
package com.rokid.context.phone.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.BarChart
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.components.GradientHeader
import com.rokid.context.core.ui.components.MetricTile
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SegmentedSwitch
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.components.StatusStrip
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.banner.BannerHost
import java.time.LocalDate

/** Activity tab (spec §2.4, mock activity-tab.html option A). Stateless. */
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    onSelectPeriod: (Period) -> Unit,
    onTogglePause: () -> Unit,
    onBannerAction: (BannerAction) -> Unit,
    onOpenMemory: (String) -> Unit,
    onOpenFrames: (LocalDate) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().background(PcColors.Surface).testTag("activity_list")) {
        item { GradientHeader("Activity", "What your glasses noticed") }
        item { BannerHost(state.status.banner, onBannerAction) }
        item {
            StatusStrip(
                state.status.captureText, live = state.status.live, batteryPct = state.status.batteryPct, paused = state.status.paused,
                onTogglePause = onTogglePause, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        item {
            SegmentedSwitch(
                listOf("Today", "Week", "Month"), selectedIndex = state.period.ordinal, onSelect = { onSelectPeriod(Period.entries[it]) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item { Text(state.header, style = MaterialTheme.typography.titleMedium, color = PcColors.Ink, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp)) }
        item {
            val framesClick = if (state.period == Period.TODAY && state.today != null) ({ onOpenFrames(state.today) }) else null
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(Icons.Outlined.Image, PcColors.ChipMint, "${state.tiles.frames}", "Frames", Modifier.weight(1f), onClick = framesClick)
                MetricTile(Icons.Outlined.Psychology, PcColors.ChipViolet, "${state.tiles.memories}", "Memories", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(Icons.Outlined.ChatBubbleOutline, PcColors.ChipSky, "${state.tiles.answers}", "Answers", Modifier.weight(1f))
                MetricTile(Icons.Outlined.Lightbulb, PcColors.ChipAmber, "${state.tiles.nudges}", "Nudges", Modifier.weight(1f))
            }
        }
        if (state.period == Period.TODAY) {
            if (state.sections.isEmpty()) item { Hint("Nothing captured yet today") } else timelineItems(state.sections, onOpenMemory)
        } else {
            item {
                SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
                    BarChart(
                        values = state.bars.map { it.count }, labels = state.bars.map { it.label },
                        highlightIndex = state.bars.indexOfFirst { it.date == state.today }.takeIf { it >= 0 },
                        onBarClick = { i -> state.bars.getOrNull(i)?.let { onOpenDay(it.date) } },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            item { SectionLabel("By day") }
            if (state.dayRows.isEmpty()) {
                item { Hint(if (state.period == Period.WEEK) "No activity this week" else "No activity this month") }
            }
            items(state.dayRows, key = { it.date.toString() }) { row ->
                ChipRow(Icons.Outlined.CalendarToday, PcColors.ChipSky, row.title, row.subtitle, onClick = { onOpenDay(row.date) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}
```

- [ ] **Step 9: `ActivityViewModel.kt`**

```kotlin
package com.rokid.context.phone.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.memory.AskRepository
import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.NudgeRepository
import com.rokid.context.memory.TimelineRepository
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.status.BannerActions
import com.rokid.context.phone.ui.status.PipelineControl
import com.rokid.context.phone.ui.status.StatusUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModel(
    initial: Period,
    private val glasses: GlassesStatusSource,
    private val pipeline: PipelineControl,
    private val timeline: TimelineRepository,
    private val asks: AskRepository,
    private val nudges: NudgeRepository,
    clock: Flow<Long>,
    storagePct: Flow<Int?>,
    private val zone: () -> ZoneId,
) : ViewModel() {
    private val period = MutableStateFlow(initial)
    /** Re-queries only when the local date changes (midnight, or a zone change noticed on the next tick). */
    private val today = clock.map { DayBucket.dayOf(it, zone()) }.distinctUntilChanged()

    private val content: Flow<ActivityUiState> = combine(period, today) { p, d -> p to d }.distinctUntilChanged().flatMapLatest { (p, d) ->
        val z = zone()
        val w = PeriodWindow.of(p, d)
        val r = w.millis(z)
        if (p == Period.TODAY) {
            combine(timeline.visibleMemoriesIn(r), timeline.framesIn(r), asks.timesIn(r), nudges.timesIn(r)) { mems, frames, a, n ->
                ActivityUiState(p, ActivityModel.header(w, d), tiles = Tiles(frames.size, mems.size, a.size, n.size), sections = Timeline.sections(mems, frames, z), today = d)
            }
        } else {
            combine(timeline.frameTimesIn(r), timeline.visibleMemoryTimesIn(r), asks.timesIn(r), nudges.timesIn(r)) { f, m, a, n ->
                val fc = ActivityModel.countByDay(f, w, z)
                val mc = ActivityModel.countByDay(m, w, z)
                ActivityUiState(p, ActivityModel.header(w, d), tiles = Tiles(f.size, m.size, a.size, n.size), bars = ActivityModel.bars(w, fc), dayRows = ActivityModel.dayRows(w, fc, mc, d), today = d)
            }
        }
    }

    val state: StateFlow<ActivityUiState> = combine(content, StatusUi.flow(glasses, pipeline, clock, storagePct)) { c, s -> c.copy(status = s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState(period = initial))

    fun selectPeriod(p: Period) { period.value = p }
    fun togglePause() = pipeline.setEnabled(!pipeline.enabled.value)
    fun onBannerAction(action: BannerAction) = BannerActions.apply(action, glasses, pipeline)
}
```

- [ ] **Step 10: `DayViewModel.kt` + `DayScreen.kt`**

`DayViewModel.kt`:
```kotlin
package com.rokid.context.phone.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.TimelineRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

data class DayUiState(
    val loading: Boolean = true,
    val found: Boolean = false,
    val date: LocalDate? = null,
    val title: String = "",
    val sections: List<TimelineSection> = emptyList(),
    val frameCount: Int = 0,
)

/** `day/{date}` (ISO yyyy-MM-dd). A malformed argument is "not found", never a crash. */
class DayViewModel(dateArg: String?, timeline: TimelineRepository, zone: () -> ZoneId, today: () -> LocalDate) : ViewModel() {
    private val date: LocalDate? = dateArg?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    val state: StateFlow<DayUiState> = (
        if (date == null) {
            flowOf(DayUiState(loading = false, found = false))
        } else {
            val z = zone()
            val r = DayBucket.bounds(date, z)
            combine(timeline.visibleMemoriesIn(r), timeline.framesIn(r)) { m, f ->
                DayUiState(false, true, date, ActivityModel.dayLabel(date, today()), Timeline.sections(m, f, z), f.size)
            }
        }
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayUiState())
}
```

`DayScreen.kt`:
```kotlin
package com.rokid.context.phone.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.activity.ActivityModel.plural
import java.time.LocalDate

@Composable
fun DayScreen(state: DayUiState, onBack: () -> Unit, onOpenMemory: (String) -> Unit, onOpenFrames: (LocalDate) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(PcColors.Surface)) {
        item { ScreenHeader(if (state.found) state.title else "Day", onBack) }
        when {
            state.loading -> Unit
            !state.found -> item { Text("Day not found", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(20.dp)) }
            else -> {
                val date = state.date
                if (state.frameCount > 0 && date != null) {
                    item { ChipRow(Icons.Outlined.PhotoLibrary, PcColors.ChipMint, "View ${plural(state.frameCount, "photo")}", onClick = { onOpenFrames(date) }) }
                }
                if (state.sections.isEmpty()) {
                    item { Text("No memories saved this day", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(20.dp)) }
                } else {
                    timelineItems(state.sections, onOpenMemory)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
```

- [ ] **Step 11: Run, gate, commit**

Run: `./gradlew :phone:testDebugUnitTest --tests "*ActivityScreenTest" --tests "*ActivityViewModelTest" --tests "*DayTest"` then `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: PASS (10 tests); BUILD SUCCESSFUL.
```bash
git add android-companion/phone/src/main/java/com/rokid/context/phone/ui/activity android-companion/phone/src/test/java/com/rokid/context/phone/ui/activity
git commit -m "feat(phone): Activity tab (Today/Week/Month, tiles, bar chart, timeline) and day drill-down

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 8: Memory detail and Frame viewer

**Requirements:** R-16 (Memory detail: frame with pinch-zoom, facts, tier, confidence, review state; **Trust** and **Edit** working; **Forget fact** / **Forget moment** shown as a disabled affordance because their cascade semantics are R-77 in Phase 7, spec §6.1.1; Frame viewer: full-screen swipe across a day's frames with play/pause, replacing the slideshow), R-20

**Files:**
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/memory/MemoryDetailModel.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/memory/MemoryDetailViewModel.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/memory/MemoryDetailScreen.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/frames/FrameViewerViewModel.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/frames/FrameViewerScreen.kt`
- Test: `android-companion/phone/src/test/java/com/rokid/context/phone/ui/memory/MemoryDetailTest.kt`, `android-companion/phone/src/test/java/com/rokid/context/phone/ui/frames/FrameViewerTest.kt`

**Interfaces:**
- Consumes: `TimelineRepository.memory/frame/framesIn`, `MemoryEdits.trust/editText` (Task 3); `ActivityModel.dayLabel` (Task 7); `DayBucket` (Phase 2); `FrameImage`, `ConfidenceChip` (Task 2); `ScreenHeader`, `SoftCard`, `GradientButton` (Phase 1).
- Produces:
  - Package `com.rokid.context.phone.ui.memory`: `data class MemoryDetailUiState(loading = true, found = false, text = "", tags: List<String> = emptyList(), tierLabel = "", confidence = 0.0, reviewLabel = "", trusted = false, createdLabel = "", framePath: String? = null, frameId: String? = null, frameDate: LocalDate? = null, draft: String? = null, message: String? = null)`, `object MemoryDetailModel { val NOT_FOUND; fun build(m: MemoryEntity, frame: FrameEntity?, zone: ZoneId): MemoryDetailUiState }`, `object Zoom { const val MIN = 1f; const val MAX = 5f; fun clamp(scale: Float): Float }`, `class MemoryDetailViewModel(memoryId: String, timeline, edits: MemoryEdits, zone: () -> ZoneId)` with `trust()`, `startEdit()`, `updateDraft(t)`, `cancelEdit()`, `saveEdit()`; `@Composable fun MemoryDetailScreen(state, onBack, onTrust, onStartEdit, onDraftChange: (String) -> Unit, onSaveEdit, onCancelEdit, onOpenFrames: (LocalDate, String) -> Unit)`.
  - Package `com.rokid.context.phone.ui.frames`: `data class ViewerFrame(id, path, time)`, `data class FrameViewerUiState(loading = true, found = false, title = "", frames: List<ViewerFrame> = emptyList(), startIndex = 0)`, `class FrameViewerViewModel(dateArg: String?, startId: String?, timeline, zone: () -> ZoneId, today: () -> LocalDate)`, `@Composable fun FrameViewerScreen(state: FrameViewerUiState, onBack: () -> Unit)`; pager test tag `"frame_pager"`, counter text `"N / M · HH:mm"`, play button content descriptions `"Play"` / `"Pause"`.

- [ ] **Step 1: Write the failing tests**

`phone/src/test/java/com/rokid/context/phone/ui/memory/MemoryDetailTest.kt`:
```kotlin
package com.rokid.context.phone.ui.memory

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MemoryDetailTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<MemoryDetailViewModel>()

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(id: String) = MemoryDetailViewModel(id, graph.timeline, graph.memoryEdits, zone = { Fixtures.ZONE }).also { vms += it }

    @Test fun modelLabelsAndFrameDate() {
        val m = Fixtures.memory("m1", "Keys on shelf", Fixtures.at(TODAY, 8, 5), frameId = "f1", tags = "keys, hallway", review = ReviewState.NEEDS_REVIEW)
            .copy(tier = MemoryTier.LONG)
        val s = MemoryDetailModel.build(m, Fixtures.frame("f1", Fixtures.at(TODAY, 8), path = "/f1.jpg"), Fixtures.ZONE)
        assertEquals(listOf("keys", "hallway"), s.tags)
        assertEquals("Long-term", s.tierLabel)
        assertEquals("Needs review", s.reviewLabel)
        assertEquals("Mon 12 Oct · 08:05", s.createdLabel)
        assertEquals(TODAY, s.frameDate)
        assertEquals("/f1.jpg", s.framePath)
        assertEquals(1f, Zoom.clamp(0.2f)); assertEquals(5f, Zoom.clamp(9f)); assertEquals(1f, Zoom.clamp(Float.NaN)); assertEquals(2.5f, Zoom.clamp(2.5f))
    }

    @Test fun trustPersists() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1))
        val model = vm("m1")
        withTimeout(5_000) { model.state.first { it.found } }
        model.trust()
        val s = withTimeout(5_000) { model.state.first { it.trusted && it.message != null } }
        assertEquals("Marked as trusted", s.message)
    }

    @Test fun editSavesReindexesAndRejectsBlank() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Milk carton nearly empty", 1))
        val model = vm("m1")
        withTimeout(5_000) { model.state.first { it.found } }
        model.startEdit()
        assertEquals("Milk carton nearly empty", model.state.value.draft)
        model.updateDraft("   ")
        model.saveEdit()
        assertEquals("Text can't be empty", withTimeout(5_000) { model.state.first { it.message != null } }.message)
        model.updateDraft("Oat milk bought")
        model.saveEdit()
        val s = withTimeout(5_000) { model.state.first { it.text == "Oat milk bought" && it.draft == null } }
        assertEquals("Saved", s.message)
        assertEquals(listOf("m1"), graph.memories.search("oat", 5).map { it.id })
    }

    @Test fun deletedMemoryShowsNotFound() = runBlocking {
        assertFalse(withTimeout(5_000) { vm("gone").state.first { !it.loading } }.found)
        compose.setContent { PcTheme { MemoryDetailScreen(MemoryDetailModel.NOT_FOUND, {}, {}, {}, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText("This memory is no longer available").assertIsDisplayed()
    }

    @Test fun screenActionsAndDisabledForget() {
        var trusts = 0; var edits = 0
        val state = MemoryDetailUiState(loading = false, found = true, text = "Keys on shelf", tierLabel = "Short-term", confidence = 0.71, reviewLabel = "Reviewed", createdLabel = "Mon 12 Oct · 08:05")
        compose.setContent { PcTheme { MemoryDetailScreen(state, {}, { trusts++ }, { edits++ }, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText("Keys on shelf").assertIsDisplayed()
        compose.onNodeWithText("71%").assertIsDisplayed()
        compose.onNodeWithText("Trust").performClick()
        compose.onNodeWithText("Edit").performClick()
        assertEquals(1, trusts); assertEquals(1, edits)
        compose.onNodeWithText("Forget fact").assertIsNotEnabled()
        compose.onNodeWithText("Forget moment").assertIsNotEnabled()
    }

    @Test fun editingShowsSaveAndCancel() {
        var saved = 0
        val state = MemoryDetailUiState(loading = false, found = true, text = "Keys", draft = "Keys on hallway shelf")
        compose.setContent { PcTheme { MemoryDetailScreen(state, {}, {}, {}, {}, { saved++ }, {}, { _, _ -> }) } }
        compose.onNodeWithText("Keys on hallway shelf").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        assertEquals(1, saved)
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
```

`phone/src/test/java/com/rokid/context/phone/ui/frames/FrameViewerTest.kt`:
```kotlin
package com.rokid.context.phone.ui.frames

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FrameViewerTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<FrameViewerViewModel>()

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(date: String?, start: String? = null) =
        FrameViewerViewModel(date, start, graph.timeline, zone = { Fixtures.ZONE }, today = { TODAY }).also { vms += it }

    private val two = FrameViewerUiState(
        loading = false, found = true, title = "Today",
        frames = listOf(ViewerFrame("f1", "/missing/f1.jpg", "09:00"), ViewerFrame("f2", "/missing/f2.jpg", "09:05")), startIndex = 0,
    )

    @Test fun framesOfTheDayOldestFirstStartingAtTheRequestedFrame() = runBlocking {
        graph.frames.add(Fixtures.frame("f2", Fixtures.at(TODAY, 9, 5)))
        graph.frames.add(Fixtures.frame("f1", Fixtures.at(TODAY, 9, 0)))
        graph.frames.add(Fixtures.frame("other", Fixtures.at(TODAY.minusDays(1), 9)))
        val s = withTimeout(5_000) { vm(TODAY.toString(), start = "f2").state.first { it.found } }
        assertEquals(listOf("f1", "f2"), s.frames.map { it.id })
        assertEquals(1, s.startIndex)
        assertEquals("Today", s.title)
        assertEquals(0, withTimeout(5_000) { vm(TODAY.toString(), start = "nope").state.first { it.found } }.startIndex)
        assertFalse(withTimeout(5_000) { vm("12-10-2026").state.first { !it.loading } }.found)
    }

    @Test fun swipingMovesToTheNextFrame() {
        compose.setContent { PcTheme { FrameViewerScreen(two, onBack = {}) } }
        compose.onNodeWithText("1 / 2 · 09:00").assertIsDisplayed()
        compose.onNodeWithTag("frame_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("2 / 2 · 09:05").assertIsDisplayed()
    }

    @Test fun startIndexAndPlayToggle() {
        compose.setContent { PcTheme { FrameViewerScreen(two.copy(startIndex = 1), onBack = {}) } }
        compose.onNodeWithText("2 / 2 · 09:05").assertIsDisplayed()
        compose.mainClock.autoAdvance = false // keep the 1.5 s autoplay step from elapsing before the assertion
        compose.onNodeWithContentDescription("Play").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test fun viewerShowsPlaceholderForMissingFile() {
        compose.setContent { PcTheme { FrameViewerScreen(two, onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Photo no longer on this phone").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun emptyOrUnknownDay() {
        compose.setContent { PcTheme { FrameViewerScreen(FrameViewerUiState(loading = false, found = true, title = "Today"), onBack = {}) } }
        compose.onNodeWithText("No photos for this day").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :phone:testDebugUnitTest --tests "*MemoryDetailTest" --tests "*FrameViewerTest"`
Expected: FAIL — `Unresolved reference: MemoryDetailViewModel` / `FrameViewerScreen`.

- [ ] **Step 3: `MemoryDetailModel.kt`**

```kotlin
package com.rokid.context.phone.ui.memory

import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class MemoryDetailUiState(
    val loading: Boolean = true,
    val found: Boolean = false,
    val text: String = "",
    val tags: List<String> = emptyList(),
    val tierLabel: String = "",
    val confidence: Double = 0.0,
    val reviewLabel: String = "",
    val trusted: Boolean = false,
    val createdLabel: String = "",
    val framePath: String? = null,
    val frameId: String? = null,
    val frameDate: LocalDate? = null,
    /** Non-null while editing. */
    val draft: String? = null,
    val message: String? = null,
)

object MemoryDetailModel {
    val NOT_FOUND = MemoryDetailUiState(loading = false, found = false)
    private val CREATED = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)

    fun build(m: MemoryEntity, frame: FrameEntity?, zone: ZoneId) = MemoryDetailUiState(
        loading = false,
        found = true,
        text = m.text,
        tags = m.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        tierLabel = when (m.tier) { MemoryTier.SHORT -> "Short-term"; MemoryTier.MEDIUM -> "Medium-term"; MemoryTier.LONG -> "Long-term" },
        confidence = m.confidence,
        reviewLabel = when (m.reviewState) { ReviewState.OK -> "Reviewed"; ReviewState.NEEDS_REVIEW -> "Needs review"; ReviewState.INVALIDATED -> "Hidden" },
        trusted = m.trusted,
        createdLabel = CREATED.format(Instant.ofEpochMilli(m.createdAt).atZone(zone)),
        framePath = frame?.path,
        frameId = frame?.id,
        frameDate = frame?.let { DayBucket.dayOf(it.ts, zone) },
    )
}

/** Pinch-zoom bounds for the memory's frame. */
object Zoom {
    const val MIN = 1f
    const val MAX = 5f
    fun clamp(scale: Float): Float = if (scale.isNaN()) MIN else scale.coerceIn(MIN, MAX)
}
```

- [ ] **Step 4: `MemoryDetailViewModel.kt`**

```kotlin
package com.rokid.context.phone.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.memory.MemoryEdits
import com.rokid.context.memory.TimelineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

class MemoryDetailViewModel(
    private val memoryId: String,
    private val timeline: TimelineRepository,
    private val edits: MemoryEdits,
    zone: () -> ZoneId,
) : ViewModel() {
    private data class Local(val draft: String? = null, val message: String? = null)
    private val local = MutableStateFlow(Local())

    val state: StateFlow<MemoryDetailUiState> = combine(
        timeline.memory(memoryId).map { m -> m?.let { MemoryDetailModel.build(it, it.frameId?.let { id -> timeline.frame(id) }, zone()) } ?: MemoryDetailModel.NOT_FOUND },
        local,
    ) { base, l -> if (base.found) base.copy(draft = l.draft, message = l.message) else base }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MemoryDetailUiState())

    fun trust() {
        viewModelScope.launch { if (edits.trust(memoryId)) local.update { it.copy(message = "Marked as trusted") } }
    }

    fun startEdit() = local.update { it.copy(draft = state.value.text, message = null) }
    fun updateDraft(text: String) = local.update { it.copy(draft = text.take(500)) }
    fun cancelEdit() = local.update { it.copy(draft = null, message = null) }

    fun saveEdit() {
        val draft = local.value.draft ?: return
        viewModelScope.launch {
            if (edits.editText(memoryId, draft)) local.update { it.copy(draft = null, message = "Saved") }
            else local.update { it.copy(message = "Text can't be empty") }
        }
    }
}
```
(`SharingStarted.Eagerly` so `startEdit()` can read `state.value.text` without a subscriber.)

- [ ] **Step 5: `MemoryDetailScreen.kt`**

```kotlin
package com.rokid.context.phone.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.ConfidenceChip
import com.rokid.context.core.ui.components.FrameImage
import com.rokid.context.core.ui.components.GradientButton
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import java.time.LocalDate

/** Memory detail (spec §2.4). Forget fact / Forget moment arrive with Phase 7's cascade (spec §6.1.1); shown disabled. */
@Composable
fun MemoryDetailScreen(
    state: MemoryDetailUiState,
    onBack: () -> Unit,
    onTrust: () -> Unit,
    onStartEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onOpenFrames: (LocalDate, String) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(PcColors.Surface).verticalScroll(rememberScrollState())) {
        ScreenHeader("Memory", onBack)
        if (state.loading) return@Column
        if (!state.found) {
            Text("This memory is no longer available", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(20.dp))
            return@Column
        }
        if (state.framePath != null) {
            ZoomableFrame(state.framePath, Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(260.dp))
            val date = state.frameDate
            val id = state.frameId
            if (date != null && id != null) {
                TextButton(onClick = { onOpenFrames(date, id) }, modifier = Modifier.padding(start = 8.dp)) { Text("Open this day's photos", color = PcColors.MintDeep) }
            }
        }
        SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val draft = state.draft
                if (draft != null) {
                    OutlinedTextField(value = draft, onValueChange = onDraftChange, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradientButton("Save", onClick = onSaveEdit)
                        TextButton(onClick = onCancelEdit) { Text("Cancel") }
                    }
                } else {
                    Text(state.text, style = MaterialTheme.typography.titleMedium, color = PcColors.Ink)
                }
                if (state.tags.isNotEmpty()) Text(state.tags.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfidenceChip(state.confidence)
                    Text("${state.tierLabel} · ${state.reviewLabel}${if (state.trusted) " · Trusted" else ""}", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                }
                Text(state.createdLabel, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                state.message?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = PcColors.MintDeep) }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GradientButton(if (state.trusted) "Trusted" else "Trust", onClick = onTrust, enabled = !state.trusted && state.draft == null)
            OutlinedButton(onClick = onStartEdit, enabled = state.draft == null) { Text("Edit") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {}, enabled = false) { Text("Forget fact") }
            OutlinedButton(onClick = {}, enabled = false) { Text("Forget moment") }
        }
        Text("Forgetting arrives with the privacy update.", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ZoomableFrame(path: String, modifier: Modifier) {
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = Zoom.clamp(scale * zoom)
        offset = if (scale == Zoom.MIN) Offset.Zero else offset + pan
    }
    Box(modifier.clip(RoundedCornerShape(20.dp)).transformable(transform)) {
        FrameImage(path, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
    }
}
```

- [ ] **Step 6: `FrameViewerViewModel.kt`**

```kotlin
package com.rokid.context.phone.ui.frames

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.TimelineRepository
import com.rokid.context.phone.ui.activity.ActivityModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ViewerFrame(val id: String, val path: String, val time: String)

data class FrameViewerUiState(
    val loading: Boolean = true,
    val found: Boolean = false,
    val title: String = "",
    val frames: List<ViewerFrame> = emptyList(),
    val startIndex: Int = 0,
)

/** `frames/{date}?start={frameId}` — the day's frames oldest first; unknown start → first frame; bad date → not found. */
class FrameViewerViewModel(dateArg: String?, startId: String?, timeline: TimelineRepository, zone: () -> ZoneId, today: () -> LocalDate) : ViewModel() {
    private val date: LocalDate? = dateArg?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    val state: StateFlow<FrameViewerUiState> = (
        if (date == null) {
            flowOf(FrameViewerUiState(loading = false, found = false))
        } else {
            val z = zone()
            timeline.framesIn(DayBucket.bounds(date, z)).map { frames ->
                FrameViewerUiState(
                    loading = false, found = true, title = ActivityModel.dayLabel(date, today()),
                    frames = frames.map { ViewerFrame(it.id, it.path, time.format(Instant.ofEpochMilli(it.ts).atZone(z))) },
                    startIndex = frames.indexOfFirst { it.id == startId }.coerceAtLeast(0),
                )
            }
        }
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FrameViewerUiState())
}
```

- [ ] **Step 7: `FrameViewerScreen.kt`**

```kotlin
package com.rokid.context.phone.ui.frames

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.FrameImage
import kotlinx.coroutines.delay

private const val PLAY_STEP_MS = 1_500L

/** Full-screen day viewer (spec §2.4 Frame viewer). PillNav is hidden on this route (Routes.showsNav). */
@Composable
fun FrameViewerScreen(state: FrameViewerUiState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White) }
            Text(state.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        when {
            state.loading -> Unit
            !state.found -> Message("Day not found")
            state.frames.isEmpty() -> Message("No photos for this day")
            else -> key(state.title) {
                val pager = rememberPagerState(initialPage = state.startIndex.coerceIn(0, state.frames.lastIndex)) { state.frames.size }
                var playing by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(playing, pager.currentPage) {
                    if (!playing) return@LaunchedEffect
                    delay(PLAY_STEP_MS)
                    if (pager.currentPage < state.frames.lastIndex) pager.animateScrollToPage(pager.currentPage + 1) else playing = false
                }
                HorizontalPager(pager, key = { state.frames[it].id }, modifier = Modifier.weight(1f).fillMaxWidth().testTag("frame_pager")) { page ->
                    FrameImage(state.frames[page].path, Modifier.fillMaxSize())
                }
                val current = state.frames[pager.currentPage.coerceIn(0, state.frames.lastIndex)]
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${pager.currentPage + 1} / ${state.frames.size} · ${current.time}", color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { playing = !playing }) {
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium) }
}
```
(The autoplay `delay` is UI pacing inside the composable — it reads no data.)

- [ ] **Step 8: Run, gate, commit**

Run: `./gradlew :phone:testDebugUnitTest --tests "*MemoryDetailTest" --tests "*FrameViewerTest"` then `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: PASS (11 tests); BUILD SUCCESSFUL.
```bash
git add android-companion/phone/src/main/java/com/rokid/context/phone/ui/memory android-companion/phone/src/main/java/com/rokid/context/phone/ui/frames android-companion/phone/src/test/java/com/rokid/context/phone/ui/memory android-companion/phone/src/test/java/com/rokid/context/phone/ui/frames
git commit -m "feat(phone): Memory detail (trust, edit, pinch-zoom) and full-screen day frame viewer

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 9: Settings root (interim), Diagnostics, and the legacy Config / Queues / Tracker screens

**Requirements:** R-17 (interim: Settings root with the spec's five groups; only **Model endpoints** and **Diagnostics** are live — the rest of the tree is Phase 5), R-03 (legacy Config screen, queue dashboard, summarizer tracker and the manual runs from the removed Live/Memory screens keep working), R-72 (Config "Test" goes through `EndpointTester` and shows only its short message), R-20

**Files:**
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/settings/SettingsRoot.kt` (`SettingsModel`, `SettingsRootUiState`, `SettingsRootViewModel`, `SettingsRootScreen`; rows per mock `settings.html`, ChipRow groups as Live Fit `phone/src/main/java/com/debasish/livefit/phone/ui/settings/SettingsScreen.kt`)
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/settings/Diagnostics.kt` (`DiagnosticsActions`, `SchedulerDiagnosticsActions`, `DiagnosticsUiState`, `DiagnosticsViewModel`, `DiagnosticsScreen`)
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/settings/Queues.kt` (`QueueActions`, `LegacyQueueActions`, `QueuesUiState`, `QueuesViewModel`, `QueuesScreen` wrapping legacy `QueueScreen.Render`)
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/settings/Tracker.kt` (`TrackerViewModel`, `TrackerScreen` wrapping legacy `SummarizerTrackerScreen.Render`)
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/settings/LegacyConfig.kt` (`ConfigTestText`, `LegacyConfigUiState`, `LegacyConfigViewModel`, `LegacyConfigScreen` wrapping legacy `ConfigHubScreen.Render`; state + callbacks moved out of the old `MainActivity` lines 155–223 and 560–674)
- Test: `android-companion/phone/src/test/java/com/rokid/context/phone/ui/settings/SettingsRootTest.kt`, `.../settings/DiagnosticsViewModelTest.kt`, `.../settings/QueuesAndTrackerTest.kt`, `.../settings/LegacyConfigViewModelTest.kt`

**Interfaces:**
- Consumes: `GlassesStatusSource` (Task 1), `PipelineControl` (Task 4), `TimelineRepository.recentTraces` (Task 3); legacy `ConfigHubScreen.Render` + `ConfigUiState`, `QueueScreen.Render` + `QueueSnapshot`, `SummarizerTrackerScreen.Render` + `TrackerItem` (unchanged, package `com.rokid.context.ui.*`); `OrchestrationScheduler.enqueueSummarizer/enqueueFrameIngestQueue/enqueueHistoricalPhotoBackfill/enqueueExplicitWrite/enqueueReadContext/enqueueQueueDrainNow`, `QueueRuntimeStore.snapshot/recent`, `PipelineStatsStore.get`, `AppConfigStore`, `LegacyConfigBridge.sync`; `AiGraph.bindings.get(Role.ANSWER)`, `AiGraph.tester.test(endpointId, model): TestResult`.
- Produces (package `com.rokid.context.phone.ui.settings`):
  - `object SettingsModel { const val COMING_SOON; fun glassesSummary(s: GlassesStatus): String }`, `data class SettingsRootUiState(glassesSummary: String = "Not connected")`, `class SettingsRootViewModel(glasses)`, `@Composable fun SettingsRootScreen(state, onOpenEndpoints: () -> Unit, onOpenDiagnostics: () -> Unit)` (test tag `"settings_list"`).
  - `interface DiagnosticsActions { suspend fun summarizeNow(): String; suspend fun captureNow(): String; suspend fun reprocessOldPhotos(): String }`, `class SchedulerDiagnosticsActions(scheduler)`, `data class DiagnosticsUiState(busy = false, message: String? = null)`, `class DiagnosticsViewModel(actions, pipeline)` with `summarizeNow()`, `captureNow()`, `reprocess()`, `companion { PAUSED; FAILED }`, `@Composable fun DiagnosticsScreen(state, onBack, onOpenQueues, onOpenTracker, onSummarizeNow, onCaptureNow, onReprocess)`.
  - `interface QueueActions { suspend fun snapshot(): QueueSnapshot; suspend fun queueExplicitWrite(text: String): String; suspend fun queueReadContext(query: String): String; suspend fun drainNow(): String }`, `class LegacyQueueActions(context, scheduler)`, `data class QueuesUiState(snapshot: QueueSnapshot? = null, explicitWrite = "", readQuery = "", message: String? = null)`, `class QueuesViewModel(actions)` with `refresh()`, `onExplicitWriteChange(t)`, `onReadQueryChange(t)`, `queueExplicitWrite()`, `queueReadContext()`, `drainNow()`; `@Composable fun QueuesScreen(state, onBack, onRefresh, onExplicitWriteChange, onReadQueryChange, onQueueExplicitWrite, onQueueReadContext, onDrainNow)`.
  - `class TrackerViewModel(timeline, zone)` (`state: StateFlow<List<TrackerItem>>`), `@Composable fun TrackerScreen(items: List<TrackerItem>, onBack: () -> Unit)`.
  - `object ConfigTestText { const val NOT_SET_UP; const val FAILED; fun of(result: TestResult?): String }`, `data class LegacyConfigUiState(config: ConfigUiState, notice: String? = null)`, `class LegacyConfigViewModel(context: Context, pipeline: PipelineControl, ai: () -> AiGraph = { AiGraph.get(context) })` with `togglePipeline`, `toggleLocalMode`, `toggleEncryption`, `cycleMemoryWindow`, `selectProvider`, `updateEndpoint`, `updateApiKey`, `updateModel`, `updateNativePath`, `selectGlassesSilencyMode`, `testConnectivity`, `companion { NOT_APPLIED; TYPING_DEBOUNCE_MS = 600L }`; `@Composable fun LegacyConfigScreen(state: LegacyConfigUiState, onBack, onTogglePipeline, onToggleLocalMode, onToggleEncryption, onCycleMemoryWindow, onSelectProvider, onUpdateEndpoint, onUpdateApiKey, onUpdateDeploymentOrModel, onUpdateNativePath, onSelectGlassesSilencyMode, onTestConnectivity)`.

Decision (Phase 2 ledger asked us to choose): **keep** the legacy Config screen, reachable at Settings › AI › Model endpoints (`settings/endpoints`), until Phase 5 replaces it. Its **Test** now runs `EndpointTester` against the ANSWER binding after flushing the pending registry sync, so it tests exactly what workers will use and shows only `TestResult.message` (`EndpointTester.humanize`'s fixed set) + latency. The glasses-verbosity selector on that screen now only stores the value — the RFCOMM push it used is gone with Phase 3; HUD settings are pushed by Phase 3/5. The tracker shows stored trace notes verbatim because it is a Diagnostics screen (spec §6.3).

- [ ] **Step 1: Write the failing tests**

`phone/src/test/java/com/rokid/context/phone/ui/settings/SettingsRootTest.kt`:
```kotlin
package com.rokid.context.phone.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SettingsRootTest {
    @get:Rule val compose = createComposeRule()

    @Test fun glassesSummaryFollowsTheLink() {
        assertEquals("Linked · 82%", SettingsModel.glassesSummary(FakeGlassesStatusSource.connected(Fixtures.stateFrame())))
        assertEquals("Linked", SettingsModel.glassesSummary(FakeGlassesStatusSource.connected(Fixtures.stateFrame(battery = null))))
        assertEquals("Not connected", SettingsModel.glassesSummary(FakeGlassesStatusSource.OFFLINE))
        assertEquals("Update the glasses app", SettingsModel.glassesSummary(GlassesStatus(LinkState.VERSION_MISMATCH, null, 0, null, null)))
    }

    @Test fun onlyEndpointsAndDiagnosticsAreLive() {
        var endpoints = 0; var diagnostics = 0
        compose.setContent { PcTheme { SettingsRootScreen(SettingsRootUiState("Linked · 82%"), onOpenEndpoints = { endpoints++ }, onOpenDiagnostics = { diagnostics++ }) } }
        compose.onNodeWithText("DEVICES").assertIsDisplayed()
        compose.onNodeWithText("Linked · 82%").assertIsDisplayed()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Model endpoints"))
        compose.onNodeWithText("Model endpoints").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Prompts"))
        compose.onNodeWithText("Prompts").assertIsNotEnabled()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Diagnostics"))
        compose.onNodeWithText("Diagnostics").performClick()
        assertEquals(1, endpoints); assertEquals(1, diagnostics)
    }
}
```

`phone/src/test/java/com/rokid/context/phone/ui/settings/DiagnosticsViewModelTest.kt`:
```kotlin
package com.rokid.context.phone.ui.settings

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// Robolectric because the failure path logs through android.util.Log.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DiagnosticsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val calls = mutableListOf<String>()
    private var failWith: Exception? = null
    private val actions = object : DiagnosticsActions {
        override suspend fun summarizeNow(): String { calls += "summarize"; failWith?.let { throw it }; return "Summarizer queued" }
        override suspend fun captureNow(): String { calls += "capture"; return "Capture pipeline queued" }
        override suspend fun reprocessOldPhotos(): String { calls += "reprocess"; return "Historical backfill queued." }
    }
    private val pipeline = FakePipelineControl()
    private val vm = DiagnosticsViewModel(actions, pipeline)

    @After fun tearDown() = vm.cancelScope()

    @Test fun runsActionsAndShowsTheirMessage() = runBlocking {
        vm.summarizeNow()
        assertEquals("Summarizer queued", withTimeout(5_000) { vm.state.first { it.message != null } }.message)
        vm.reprocess()
        assertEquals("Historical backfill queued.", withTimeout(5_000) { vm.state.first { it.message?.startsWith("Historical") == true } }.message)
        assertEquals(listOf("summarize", "reprocess"), calls)
    }

    @Test fun pausedPipelineBlocksSummarizeAndCaptureButNotReprocess() = runBlocking {
        pipeline.setEnabled(false)
        vm.summarizeNow(); vm.captureNow()
        assertEquals(DiagnosticsViewModel.PAUSED, vm.state.value.message)
        assertEquals(emptyList<String>(), calls)
        vm.reprocess()
        withTimeout(5_000) { vm.state.first { it.message?.startsWith("Historical") == true } }
        assertEquals(listOf("reprocess"), calls)
    }

    @Test fun failureShowsAFixedMessage() = runBlocking {
        failWith = IllegalStateException("No Rokid frames available for summarization sk-live-1")
        vm.summarizeNow()
        assertEquals(DiagnosticsViewModel.FAILED, withTimeout(5_000) { vm.state.first { it.message != null } }.message)
    }
}
```

`phone/src/test/java/com/rokid/context/phone/ui/settings/QueuesAndTrackerTest.kt`:
```kotlin
package com.rokid.context.phone.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.SummaryTraceEntity
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.ui.queues.QueueSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class QueuesAndTrackerTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private var snapshots = 0
    private val sent = mutableListOf<String>()
    private val actions = object : QueueActions {
        override suspend fun snapshot(): QueueSnapshot { snapshots++; return QueueSnapshot(1, 0, 2, 2, 0, 24, 5, 4, 99, "READ ENQUEUED attempts=0") }
        override suspend fun queueExplicitWrite(text: String): String { sent += "write:$text"; return "writeContext queued: abc" }
        override suspend fun queueReadContext(query: String): String { sent += "read:$query"; return "readContext queued: def" }
        override suspend fun drainNow(): String { sent += "drain"; return "Queue drain requested" }
    }
    private val vms = mutableListOf<androidx.lifecycle.ViewModel>()

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    @Test fun queuesLoadOnOpenAndRefreshAfterEachAction() = runBlocking {
        val vm = QueuesViewModel(actions).also { vms += it }
        assertEquals(99, withTimeout(5_000) { vm.state.first { it.snapshot != null } }.snapshot?.totalFramesCaptured)
        vm.onExplicitWriteChange("  I parked on level 3  ")
        vm.queueExplicitWrite()
        withTimeout(5_000) { vm.state.first { it.message == "writeContext queued: abc" } }
        assertEquals("", vm.state.value.explicitWrite)
        vm.onReadQueryChange("   ")
        vm.queueReadContext() // blank → ignored
        vm.drainNow()
        withTimeout(5_000) { vm.state.first { it.message == "Queue drain requested" } }
        assertEquals(listOf("write:I parked on level 3", "drain"), sent)
        assertEquals(3, snapshots)
    }

    @Test fun trackerFormatsRecentTraces() = runBlocking {
        graph.traces.upsert(SummaryTraceEntity("sum-1", "TRUSTED", Fixtures.at(Fixtures.TODAY, 9, 41), "frames=3 imported=1"))
        val vm = TrackerViewModel(graph.timeline, zone = { Fixtures.ZONE }).also { vms += it }
        val items = withTimeout(5_000) { vm.state.first { it.isNotEmpty() } }
        assertEquals("09:41:00", items.single().updatedAt)
        assertEquals("TRUSTED", items.single().state)
    }
}
```

`phone/src/test/java/com/rokid/context/phone/ui/settings/LegacyConfigViewModelTest.kt`:
```kotlin
package com.rokid.context.phone.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.ModelProvider
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class) // TestCoroutineScheduler.advanceTimeBy
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LegacyConfigViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val models = mutableListOf<String>()
    private var fail = false
    private val pipeline = FakePipelineControl()
    private lateinit var vm: LegacyConfigViewModel

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        AiGraph.resetForTests(AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, model, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String): String {
                    models += model
                    if (fail) throw RuntimeException("request failed: 401 {\"error\":{\"message\":\"Incorrect API key provided: sk-live-999\"}}")
                    return "OK"
                }
            }
        }))
        AppConfigStore.setProvider(ctx, "openai", "https://az.example", "key-1", "gpt-5.4", "")
        vm = LegacyConfigViewModel(ctx, pipeline)
    }

    @After fun tearDown() { vm.cancelScope(); AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun testResult() = withTimeout(10_000) { vm.state.first { it.config.testResult.contains(" ms") || it.config.testResult == ConfigTestText.NOT_SET_UP } }.config.testResult

    @Test fun loadsSavedSettings() {
        val c = vm.state.value.config
        assertEquals("https://az.example", c.endpoint); assertEquals("gpt-5.4", c.deploymentOrModel); assertEquals("Not tested", c.testResult)
    }

    @Test fun testButtonShowsShortMessageOnly() = runBlocking {
        fail = true
        vm.testConnectivity()
        val text = testResult()
        assertTrue(text, text.startsWith("Key rejected (401) · "))
        assertFalse(text.contains("sk-live") || text.contains("Incorrect"))
    }

    @Test fun testUsesTheModelJustTypedWithoutWaitingForTheDebounce() = runBlocking {
        vm.updateModel("gpt-6")
        vm.testConnectivity()
        assertTrue(testResult().startsWith("OK · "))
        assertEquals("gpt-6", models.last())
    }

    @Test fun halfTypedAddressShowsTheNotAppliedNotice() = runBlocking {
        vm.updateEndpoint("https://az exa")
        main.dispatcher.scheduler.advanceTimeBy(LegacyConfigViewModel.TYPING_DEBOUNCE_MS + 100)
        assertEquals(LegacyConfigViewModel.NOT_APPLIED, withTimeout(10_000) { vm.state.first { it.notice != null } }.notice)
    }

    @Test fun pipelineToggleUsesTheSharedControl() {
        vm.togglePipeline(false)
        assertFalse(pipeline.enabled.value)
        assertFalse(vm.state.value.config.pipelineEnabled)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :phone:testDebugUnitTest --tests "com.rokid.context.phone.ui.settings.*"`
Expected: FAIL — `Unresolved reference: SettingsRootScreen` / `DiagnosticsViewModel` / `QueuesViewModel` / `LegacyConfigViewModel`.

- [ ] **Step 3: `SettingsRoot.kt`**

```kotlin
package com.rokid.context.phone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.components.GlassesIcon
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

object SettingsModel {
    const val COMING_SOON = "Coming soon"

    fun glassesSummary(s: GlassesStatus): String = when (s.link) {
        LinkState.CONNECTED -> (s.glassesBatteryPct ?: s.frame?.glassesBatteryPct)?.let { "Linked · $it%" } ?: "Linked"
        LinkState.CONNECTING -> "Connecting…"
        LinkState.VERSION_MISMATCH -> "Update the glasses app"
        LinkState.NOT_AUTHORIZED -> "Not authorized"
        else -> "Not connected"
    }
}

data class SettingsRootUiState(val glassesSummary: String = "Not connected")

class SettingsRootViewModel(glasses: GlassesStatusSource) : ViewModel() {
    val state: StateFlow<SettingsRootUiState> = glasses.status.map { SettingsRootUiState(SettingsModel.glassesSummary(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRootUiState())
}

private data class SettingRow(val icon: ImageVector, val colors: Pair<Color, Color>, val title: String, val subtitle: String, val onClick: (() -> Unit)?)

/** Settings root (spec §2.4, mock settings.html). Phase 4: only Model endpoints + Diagnostics open; the rest is Phase 5. */
@Composable
fun SettingsRootScreen(state: SettingsRootUiState, onOpenEndpoints: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val soon = SettingsModel.COMING_SOON
    val groups = listOf(
        "Devices" to listOf(
            SettingRow(GlassesIcon, PcColors.ChipMint, "Rokid Glasses", state.glassesSummary, null),
            SettingRow(Icons.Outlined.PhoneAndroid, PcColors.ChipSky, "Phone camera & mic", soon, null),
        ),
        "Capture & privacy" to listOf(
            SettingRow(Icons.Outlined.Schedule, PcColors.ChipAmber, "Capture schedule", soon, null),
            SettingRow(Icons.Outlined.Shield, PcColors.ChipSlate, "Privacy", soon, null),
        ),
        "AI" to listOf(
            SettingRow(Icons.Outlined.Cloud, PcColors.ChipSky, "Model endpoints", "Provider, address, key and Test", onOpenEndpoints),
            SettingRow(Icons.Outlined.AutoAwesome, PcColors.ChipViolet, "Models by role", soon, null),
            SettingRow(Icons.Outlined.Edit, PcColors.ChipRose, "Prompts", soon, null),
            SettingRow(Icons.Outlined.Search, PcColors.ChipMint, "Retrieval", soon, null),
            SettingRow(Icons.Outlined.Face, PcColors.ChipAmber, "Persona & language", soon, null),
        ),
        "Connectors" to listOf(
            SettingRow(Icons.Outlined.Event, PcColors.ChipSky, "Data sources", soon, null),
            SettingRow(Icons.Outlined.Bolt, PcColors.ChipAmber, "Actions & tools", soon, null),
        ),
        "Memory & system" to listOf(
            SettingRow(Icons.Outlined.Memory, PcColors.ChipViolet, "Memory", soon, null),
            SettingRow(Icons.Outlined.MonitorHeart, PcColors.ChipCoral, "Diagnostics", "Queues, summarizer tracker, manual runs", onOpenDiagnostics),
        ),
    )
    LazyColumn(Modifier.fillMaxSize().background(PcColors.SurfaceSoft).testTag("settings_list")) {
        item { ScreenHeader("Settings", onBack = null) }
        groups.forEach { (label, rows) ->
            item(key = label) {
                SectionLabel(label)
                SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column {
                        rows.forEach { r -> ChipRow(r.icon, r.colors, r.title, r.subtitle, onClick = r.onClick ?: {}, enabled = r.onClick != null) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
```

- [ ] **Step 4: `Diagnostics.kt`**

```kotlin
package com.rokid.context.phone.ui.settings

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.status.PipelineControl
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** Manual runs that used to live on the removed Live and Memory screens (R-03). Messages are fixed/human. */
interface DiagnosticsActions {
    suspend fun summarizeNow(): String
    suspend fun captureNow(): String
    suspend fun reprocessOldPhotos(): String
}

class SchedulerDiagnosticsActions(private val scheduler: OrchestrationScheduler) : DiagnosticsActions {
    override suspend fun summarizeNow(): String = withContext(Dispatchers.IO) {
        scheduler.enqueueSummarizer(summaryId = UUID.randomUUID().toString(), confidence = 0.8, createdAt = System.currentTimeMillis())
        "Summarizer queued"
    }

    override suspend fun captureNow(): String = withContext(Dispatchers.IO) {
        scheduler.enqueueFrameIngestQueue(source = "manual_live")
        "Capture pipeline queued — ingest, then summarize the latest window"
    }

    override suspend fun reprocessOldPhotos(): String = withContext(Dispatchers.IO) {
        scheduler.enqueueHistoricalPhotoBackfill(maxFrames = 90, chunkSize = 6).let { "${it.reason} Frames ${it.selectedFrames}, jobs ${it.queuedJobs}." }
    }
}

data class DiagnosticsUiState(val busy: Boolean = false, val message: String? = null)

class DiagnosticsViewModel(private val actions: DiagnosticsActions, private val pipeline: PipelineControl) : ViewModel() {
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    fun summarizeNow() = run(requiresPipeline = true) { actions.summarizeNow() }
    fun captureNow() = run(requiresPipeline = true) { actions.captureNow() }
    fun reprocess() = run(requiresPipeline = false) { actions.reprocessOldPhotos() }

    private fun run(requiresPipeline: Boolean, block: suspend () -> String) {
        if (_state.value.busy) return
        if (requiresPipeline && !pipeline.enabled.value) { _state.value = DiagnosticsUiState(message = PAUSED); return }
        _state.value = DiagnosticsUiState(busy = true)
        viewModelScope.launch {
            val msg = try { block() } catch (c: CancellationException) { throw c } catch (e: Exception) {
                Log.i(TAG, "diagnostics action failed (${e.javaClass.simpleName})")
                FAILED
            }
            _state.value = DiagnosticsUiState(message = msg)
        }
    }

    companion object {
        const val PAUSED = "Capture is paused — resume it from Home first"
        const val FAILED = "That didn't work — try again"
        private const val TAG = "Diagnostics"
    }
}

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onBack: () -> Unit,
    onOpenQueues: () -> Unit,
    onOpenTracker: () -> Unit,
    onSummarizeNow: () -> Unit,
    onCaptureNow: () -> Unit,
    onReprocess: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(PcColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader("Diagnostics", onBack)
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column {
                ChipRow(Icons.Outlined.Inbox, PcColors.ChipSky, "Queues", "Pending, deferred and failed jobs", onClick = onOpenQueues)
                ChipRow(Icons.Outlined.Timeline, PcColors.ChipViolet, "Summarizer tracker", "Recent summary jobs", onClick = onOpenTracker)
            }
        }
        SectionLabel("Manual runs")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column {
                ChipRow(Icons.Outlined.AutoAwesome, PcColors.ChipMint, "Summarize now", "Summarize the latest frames", onClick = onSummarizeNow, enabled = !state.busy)
                ChipRow(Icons.Outlined.PhotoCamera, PcColors.ChipAmber, "Run capture pipeline now", "Ingest new glasses frames, then summarize", onClick = onCaptureNow, enabled = !state.busy)
                ChipRow(Icons.Outlined.History, PcColors.ChipRose, "Reprocess old photos", "Re-summarize up to 90 stored photos", onClick = onReprocess, enabled = !state.busy)
            }
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink, modifier = Modifier.padding(20.dp)) }
    }
}
```

- [ ] **Step 5: `Queues.kt`**

```kotlin
package com.rokid.context.phone.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.ui.queues.QueueScreen
import com.rokid.context.ui.queues.QueueSnapshot
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

interface QueueActions {
    suspend fun snapshot(): QueueSnapshot
    suspend fun queueExplicitWrite(text: String): String
    suspend fun queueReadContext(query: String): String
    suspend fun drainNow(): String
}

/** Same counts and actions the old MainActivity wired into QueueScreen (lines 505–539), now off the main thread. */
class LegacyQueueActions(private val context: Context, private val scheduler: OrchestrationScheduler) : QueueActions {
    override suspend fun snapshot(): QueueSnapshot = withContext(Dispatchers.IO) {
        val q = QueueRuntimeStore.snapshot(context)
        val stats = PipelineStatsStore.get(context)
        QueueSnapshot(
            readContext = q.pendingByQueue[QueuePriority.READ] ?: 0,
            writeContextExplicit = q.pendingByQueue[QueuePriority.EXPLICIT_WRITE] ?: 0,
            writeContextBatched = q.pendingByQueue[QueuePriority.SUMMARIZER] ?: 0,
            summarizer = q.pendingByQueue[QueuePriority.SUMMARIZER] ?: 0,
            retry = q.totalDeferred,
            deferredTtlHours = 24,
            totalSummarizerTriggered = stats.summarizerTriggered,
            totalSummarizerSucceeded = stats.summarizerSucceeded,
            totalFramesCaptured = stats.framesCaptured,
            lastQueueEvent = QueueRuntimeStore.recent(context, 1).firstOrNull()?.let { "${it.queue} ${it.state} attempts=${it.attemptCount}" } ?: "none",
        )
    }

    override suspend fun queueExplicitWrite(text: String): String = withContext(Dispatchers.IO) { "writeContext queued: ${scheduler.enqueueExplicitWrite(rawInput = text).takeLast(10)}" }
    override suspend fun queueReadContext(query: String): String = withContext(Dispatchers.IO) { "readContext queued: ${scheduler.enqueueReadContext(query = query).takeLast(10)}" }
    override suspend fun drainNow(): String = withContext(Dispatchers.IO) { scheduler.enqueueQueueDrainNow(); "Queue drain requested" }
}

data class QueuesUiState(val snapshot: QueueSnapshot? = null, val explicitWrite: String = "", val readQuery: String = "", val message: String? = null)

/** Refreshes on open, on Refresh, and after each action — no polling (spec §1.4). */
class QueuesViewModel(private val actions: QueueActions) : ViewModel() {
    private val _state = MutableStateFlow(QueuesUiState())
    val state: StateFlow<QueuesUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun onExplicitWriteChange(text: String) = _state.update { it.copy(explicitWrite = text) }
    fun onReadQueryChange(text: String) = _state.update { it.copy(readQuery = text) }

    fun queueExplicitWrite() {
        val text = _state.value.explicitWrite.trim()
        if (text.isEmpty()) return
        _state.update { it.copy(explicitWrite = "") }
        act { actions.queueExplicitWrite(text) }
    }

    fun queueReadContext() {
        val query = _state.value.readQuery.trim()
        if (query.isEmpty()) return
        _state.update { it.copy(readQuery = "") }
        act { actions.queueReadContext(query) }
    }

    fun drainNow() = act { actions.drainNow() }

    private fun act(block: suspend () -> String) {
        viewModelScope.launch {
            val msg = try { block() } catch (c: CancellationException) { throw c } catch (e: Exception) { DiagnosticsViewModel.FAILED }
            _state.update { it.copy(message = msg) }
            load()
        }
    }

    private suspend fun load() {
        val snap = try { actions.snapshot() } catch (c: CancellationException) { throw c } catch (e: Exception) { null }
        if (snap != null) _state.update { it.copy(snapshot = snap) }
    }
}

@Composable
fun QueuesScreen(
    state: QueuesUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onExplicitWriteChange: (String) -> Unit,
    onReadQueryChange: (String) -> Unit,
    onQueueExplicitWrite: () -> Unit,
    onQueueReadContext: () -> Unit,
    onDrainNow: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(PcColors.Surface)) {
        ScreenHeader("Queues", onBack) { IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, contentDescription = "Refresh") } }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PcColors.MintDeep, modifier = Modifier.padding(horizontal = 20.dp)) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val snap = state.snapshot
            if (snap == null) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft)
            } else {
                QueueScreen.Render(snap, state.explicitWrite, state.readQuery, onExplicitWriteChange, onReadQueryChange, onQueueExplicitWrite, onQueueReadContext, onDrainNow, onBack)
            }
        }
    }
}
```

- [ ] **Step 6: `Tracker.kt`**

```kotlin
package com.rokid.context.phone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.memory.TimelineRepository
import com.rokid.context.ui.tracker.SummarizerTrackerScreen
import com.rokid.context.ui.tracker.TrackerItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Live from Room (summary_trace Flow). Notes are shown verbatim: this is a Diagnostics screen (spec §6.3). */
class TrackerViewModel(timeline: TimelineRepository, zone: () -> ZoneId) : ViewModel() {
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH)

    val state: StateFlow<List<TrackerItem>> = timeline.recentTraces(40).map { rows ->
        rows.map { TrackerItem(it.summaryId, it.state, time.format(Instant.ofEpochMilli(it.updatedAt).atZone(zone())), it.note) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun TrackerScreen(items: List<TrackerItem>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(PcColors.Surface)) {
        ScreenHeader("Summarizer tracker", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SummarizerTrackerScreen.Render(items, onBack)
        }
    }
}
```

- [ ] **Step 7: `LegacyConfig.kt`**

```kotlin
package com.rokid.context.phone.ui.settings

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Role
import com.rokid.context.ai.TestResult
import com.rokid.context.core.ui.components.Banner
import com.rokid.context.core.ui.components.BannerTone
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.orchestration.LegacyConfigBridge
import com.rokid.context.phone.ui.status.PipelineControl
import com.rokid.context.ui.config.ConfigHubScreen
import com.rokid.context.ui.config.ConfigUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Test button text: `EndpointTester`'s short message (its fixed `humanize` set) + latency — never provider text. */
object ConfigTestText {
    const val NOT_SET_UP = "Not set up — enter an address and key"
    const val FAILED = "Test failed"

    fun of(result: TestResult?): String = when {
        result == null -> NOT_SET_UP
        result.ok -> "OK · ${result.latencyMs} ms"
        else -> "${result.message} · ${result.latencyMs} ms"
    }
}

data class LegacyConfigUiState(val config: ConfigUiState, val notice: String? = null)

/**
 * The legacy Config screen's state and callbacks, moved out of the old god-composable (kept until Phase 5's AI settings).
 * Prefs writes and registry syncs run on one serial IO lane so they apply in typing order; text edits sync after a
 * 600 ms debounce (Phase 2 ruling); Test flushes the pending sync first so it tests what the workers will use.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyConfigViewModel(
    private val context: Context,
    private val pipeline: PipelineControl,
    private val ai: () -> AiGraph = { AiGraph.get(context) },
) : ViewModel() {
    private val io = Dispatchers.IO.limitedParallelism(1)
    private var syncJob: Job? = null
    private val _state = MutableStateFlow(initial())
    val state: StateFlow<LegacyConfigUiState> = _state.asStateFlow()

    private fun initial(): LegacyConfigUiState {
        val c = AppConfigStore.get(context)
        return LegacyConfigUiState(
            ConfigUiState(
                pipelineEnabled = pipeline.enabled.value, localModeEnabled = c.localModeEnabled, encryptionEnabled = c.encryptionEnabled,
                memoryWindow = c.memoryWindow, persona = c.persona, providerType = c.providerType, endpoint = c.endpoint, apiKeyMasked = c.apiKey,
                deploymentOrModel = c.deploymentOrModel, nativeModelPath = c.nativeModelPath,
                glassesSilencyMode = AppConfigStore.glassesSilencyMode(context), testResult = NOT_TESTED,
            ),
        )
    }

    private fun config(change: ConfigUiState.() -> ConfigUiState) = _state.update { it.copy(config = it.config.change()) }

    fun togglePipeline(enabled: Boolean) { pipeline.setEnabled(enabled); config { copy(pipelineEnabled = enabled) } }

    fun toggleLocalMode(enabled: Boolean) {
        config { copy(localModeEnabled = enabled) }
        viewModelScope.launch(io) { AppConfigStore.setLocalModeEnabled(context, enabled, syncRegistry = false) }
        syncLater(0)
    }

    fun toggleEncryption(enabled: Boolean) {
        config { copy(encryptionEnabled = enabled) }
        viewModelScope.launch(io) { AppConfigStore.setEncryptionEnabled(context, enabled) }
    }

    fun cycleMemoryWindow() {
        val next = when (_state.value.config.memoryWindow) { "24h" -> "7d"; "7d" -> "30d"; else -> "24h" }
        config { copy(memoryWindow = next) }
        viewModelScope.launch(io) { AppConfigStore.setMemoryWindow(context, next) }
    }

    fun selectProvider(type: String) { config { copy(providerType = type, testResult = NOT_TESTED) }; persist(); syncLater(0) }
    fun updateEndpoint(value: String) { config { copy(endpoint = value) }; persist(); syncLater(TYPING_DEBOUNCE_MS) }
    fun updateApiKey(value: String) { config { copy(apiKeyMasked = value) }; persist(); syncLater(TYPING_DEBOUNCE_MS) }
    fun updateModel(value: String) { config { copy(deploymentOrModel = value) }; persist(); syncLater(TYPING_DEBOUNCE_MS) }
    fun updateNativePath(value: String) { config { copy(nativeModelPath = value) }; persist(); syncLater(TYPING_DEBOUNCE_MS) }

    /** Stored only: the RFCOMM push this used is removed (Phase 3); HUD verbosity is pushed via HudSettings (Phase 3/5). */
    fun selectGlassesSilencyMode(mode: String) {
        config { copy(glassesSilencyMode = mode) }
        viewModelScope.launch(io) { AppConfigStore.setGlassesSilencyMode(context, mode) }
    }

    fun testConnectivity() {
        syncJob?.cancel()
        config { copy(testResult = "Testing…") }
        viewModelScope.launch {
            val text = try {
                withContext(io) {
                    LegacyConfigBridge.sync(context)
                    val graph = ai()
                    val binding = graph.bindings.get(Role.ANSWER)
                    ConfigTestText.of(binding?.let { graph.tester.test(it.endpointId, it.model) })
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.i(TAG, "config test failed (${e.javaClass.simpleName})")
                ConfigTestText.FAILED
            }
            config { copy(testResult = text) }
        }
    }

    private fun persist() {
        val c = _state.value.config
        viewModelScope.launch(io) {
            AppConfigStore.setProvider(context, c.providerType, c.endpoint, c.apiKeyMasked, c.deploymentOrModel, c.nativeModelPath, syncRegistry = false)
        }
    }

    private fun syncLater(debounceMs: Long) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val ok = withContext(io) { LegacyConfigBridge.sync(context).isSuccess }
            _state.update { it.copy(notice = if (ok) null else NOT_APPLIED) }
        }
    }

    companion object {
        const val NOT_TESTED = "Not tested"
        const val NOT_APPLIED = "AI settings not applied — check the address, key and model"
        const val TYPING_DEBOUNCE_MS = 600L
        private const val TAG = "LegacyConfig"
    }
}

@Composable
fun LegacyConfigScreen(
    state: LegacyConfigUiState,
    onBack: () -> Unit,
    onTogglePipeline: (Boolean) -> Unit,
    onToggleLocalMode: (Boolean) -> Unit,
    onToggleEncryption: (Boolean) -> Unit,
    onCycleMemoryWindow: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onUpdateEndpoint: (String) -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onUpdateDeploymentOrModel: (String) -> Unit,
    onUpdateNativePath: (String) -> Unit,
    onSelectGlassesSilencyMode: (String) -> Unit,
    onTestConnectivity: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(PcColors.Surface)) {
        ScreenHeader("Model endpoint", onBack)
        state.notice?.let { Banner(BannerTone.AMBER, it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            ConfigHubScreen.Render(
                state = state.config, showAdvancedReadOnly = true, onTogglePipeline = onTogglePipeline, onToggleLocalMode = onToggleLocalMode,
                onToggleEncryption = onToggleEncryption, onCycleMemoryWindow = onCycleMemoryWindow, onSelectProvider = onSelectProvider,
                onUpdateEndpoint = onUpdateEndpoint, onUpdateApiKey = onUpdateApiKey, onUpdateDeploymentOrModel = onUpdateDeploymentOrModel,
                onUpdateNativePath = onUpdateNativePath, onSelectGlassesSilencyMode = onSelectGlassesSilencyMode,
                onTestConnectivity = onTestConnectivity, onBack = onBack,
            )
        }
    }
}
```
(`limitedParallelism(1)` gives a serial lane: per-keystroke writes, the debounced sync and Test run in call order.)

- [ ] **Step 8: Run to verify pass**

Run: `./gradlew :phone:testDebugUnitTest --tests "com.rokid.context.phone.ui.settings.*"`
Expected: PASS (12 tests). If `testButtonShowsShortMessageOnly` reports a different prefix, print `text` — it must still be one of `EndpointTester.humanize`'s fixed strings and must not contain `sk-live`.

- [ ] **Step 9: Gate + commit**

Run: `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: BUILD SUCCESSFUL.
```bash
git add android-companion/phone/src/main/java/com/rokid/context/phone/ui/settings android-companion/phone/src/test/java/com/rokid/context/phone/ui/settings
git commit -m "feat(phone): interim Settings root, Diagnostics, and ViewModels for the legacy Config/Queues/Tracker screens

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 10: NavHost shell, PillNav, onboarding gate; rewrite `MainActivity`; remove the legacy stub screens

**Requirements:** R-10 (NavHost, edge-to-edge, start = `onboarding` until complete then `home`, floating 3-tab PillNav hidden on onboarding and full-screen viewers, 84 dp content padding, Phase 4 routes of spec §2.1), R-07 (the `MainActivity` god-composable and its 1.8 s polling loop are gone), R-20, R-03 (workers still scheduled; legacy Config/Queues/Tracker reachable)

**Files:**
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/shell/Routes.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/shell/PhoneDeps.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/shell/Destinations.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/shell/AppShell.kt` (structure from Live Fit `phone/src/main/java/com/debasish/livefit/phone/ui/AppActivity.kt`: `Box` + `NavHost` with 84 dp bottom padding + `PillNav` aligned bottom-centre with `navigationBarsPadding().padding(bottom = 12.dp)`, tab `popUpTo("home")` + `launchSingleTop`)
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/onboarding/OnboardingStore.kt`
- Create: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/onboarding/WelcomeScreen.kt` (look of Live Fit `phone/src/main/java/com/debasish/livefit/phone/setup/SetupScreen.kt` Welcome step: gradient background, 96 dp chip, centred text, primary button)
- Modify (rewrite): `android-companion/phone/src/main/java/com/rokid/context/MainActivity.kt`
- Delete: `android-companion/phone/src/main/java/com/rokid/context/ui/home/HomeScreen.kt`, `.../ui/live/LivePipelineScreen.kt`, `.../ui/memory/MemoryScreen.kt`, `.../ui/migration/MigrationStatusCard.kt`, `.../ui/onboarding/OnboardingScreen.kt`, `.../ui/status/StatusScreen.kt`, `.../ui/frames/FrameSlideshowScreen.kt`, `.../ui/diagnostics/DiagnosticsScreen.kt`
- Delete: `android-companion/phone/src/test/java/com/rokid/context/ui/OnboardingFlowTest.kt`; Create: `android-companion/phone/src/test/java/com/rokid/context/ui/ConfigExportTest.kt` (keeps its `configExportExcludesSecrets` test)
- Test: `android-companion/phone/src/test/java/com/rokid/context/phone/ui/shell/RoutesTest.kt`, `.../shell/AppShellTest.kt`, `.../shell/ShellSourceRulesTest.kt`, `android-companion/phone/src/test/java/com/rokid/context/phone/ui/onboarding/OnboardingStoreTest.kt`

**Interfaces:**
- Consumes: every screen + ViewModel from Tasks 5–9; `FakeGlassesStatusSource` (Task 1); `LegacyPipelineControl`, `StorageUsage`, `BannerAction` (Task 4); `AskActions.ask/rate` (Tasks 5–6); `SchedulerDiagnosticsActions`, `LegacyQueueActions` (Task 9); `MemoryGraph.get` (Phase 2); `OrchestrationScheduler`, `QueueRuntimeStore.purgeSynthetic`, the three legacy facades' purge calls (unchanged until Task 11); `PillNav`, `PillNavItem`, `PcTheme`, `GradientButton`, `IconChip`, `GlassesIcon` (`:core:ui`).
- Produces:
  - `object Routes` (package `com.rokid.context.phone.ui.shell`): patterns `ONBOARDING`, `HOME`, `ASK = "ask/{id}"`, `ACTIVITY = "activity?period={period}"`, `DAY = "day/{date}"`, `MEMORY = "memory/{id}"`, `FRAMES = "frames/{date}?start={start}"`, `SETTINGS`, `SETTINGS_ENDPOINTS = "settings/endpoints"`, `SETTINGS_DIAGNOSTICS = "settings/diagnostics"`, `DIAGNOSTICS_QUEUES = "settings/diagnostics/queues"`, `DIAGNOSTICS_TRACKER = "settings/diagnostics/tracker"`; builders `ask(id)`, `activity(period: Period? = null)`, `day(date)`, `memory(id)`, `frames(date, start: String? = null)`, `startRoute(onboardingComplete)`; `enum class Tab(route, label)`; `tabFor(route: String?): Tab?`; `showsNav(route: String?): Boolean`.
  - `class PhoneDeps(memory, glasses, pipeline, onboarding, askService, rateAsk, diagnostics, queues, storagePct: Flow<Int?>, clock: Flow<Long>, zone: () -> ZoneId = { ZoneId.systemDefault() })` with `fun today(): LocalDate`, `companion { fun get(context): PhoneDeps; fun create(app: Context): PhoneDeps; fun ticker(periodMs: Long = 1_000, now: () -> Long = System::currentTimeMillis): Flow<Long> }` — Task 11 changes only the `glasses =` line of `create`.
  - `fun NavGraphBuilder.phoneDestinations(deps: PhoneDeps, nav: NavHostController)`; `@Composable fun AppShell(deps: PhoneDeps, startRoute: String, nav: NavHostController = rememberNavController())`.
  - `class OnboardingStore(context) { fun isComplete(): Boolean; fun markComplete(); companion { K_COMPLETE = "v1_complete" } }` (package `com.rokid.context.phone.ui.onboarding`), `@Composable fun WelcomeScreen(onGetStarted: () -> Unit)`.

Onboarding scope: the full wizard (permissions, glasses auth, endpoint, persona, gestures) is R-19 in Phase 7. Phase 4 ships the **gate** and a one-step Welcome so the start-route rule of spec §2.1 holds from now on; Phase 7 replaces `WelcomeScreen` and may bump `K_COMPLETE` to re-run the wizard for users who only saw this step. Routes for the rest of the Settings tree (`settings/glasses`, `settings/prompts/{id}`, …) are added by Phase 5.

- [ ] **Step 1: Write the failing pure tests**

`phone/src/test/java/com/rokid/context/phone/ui/shell/RoutesTest.kt`:
```kotlin
package com.rokid.context.phone.ui.shell

import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.activity.Period
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {
    @Test fun tabsFollowTheRoutePattern() {
        assertEquals(Routes.Tab.HOME, Routes.tabFor(Routes.HOME))
        assertEquals(Routes.Tab.HOME, Routes.tabFor(Routes.ASK))
        assertEquals(Routes.Tab.ACTIVITY, Routes.tabFor(Routes.ACTIVITY))
        assertEquals(Routes.Tab.ACTIVITY, Routes.tabFor(Routes.DAY))
        assertEquals(Routes.Tab.ACTIVITY, Routes.tabFor(Routes.MEMORY))
        assertEquals(Routes.Tab.SETTINGS, Routes.tabFor(Routes.DIAGNOSTICS_TRACKER))
        assertNull(Routes.tabFor(Routes.ONBOARDING))
        assertNull(Routes.tabFor(null))
    }

    @Test fun pillNavIsHiddenOnOnboardingAndTheViewer() {
        assertFalse(Routes.showsNav(Routes.ONBOARDING))
        assertFalse(Routes.showsNav(Routes.FRAMES))
        assertFalse(Routes.showsNav(null))
        assertTrue(Routes.showsNav(Routes.HOME))
        assertTrue(Routes.showsNav(Routes.SETTINGS_ENDPOINTS))
    }

    @Test fun buildersEncodeArguments() {
        assertEquals("ask/a%20b%2Fc", Routes.ask("a b/c"))
        assertEquals("activity", Routes.activity())
        assertEquals("activity?period=week", Routes.activity(Period.WEEK))
        assertEquals("day/2026-10-12", Routes.day(TODAY))
        assertEquals("frames/2026-10-12", Routes.frames(TODAY))
        assertEquals("frames/2026-10-12?start=f%201", Routes.frames(TODAY, "f 1"))
        assertEquals(Routes.ONBOARDING, Routes.startRoute(onboardingComplete = false))
        assertEquals(Routes.HOME, Routes.startRoute(onboardingComplete = true))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun tickerEmitsImmediatelyThenEverySecond() = runTest {
        assertEquals(listOf(0L, 1_000L, 2_000L), PhoneDeps.ticker(now = { currentTime }).take(3).toList())
    }
}
```

`phone/src/test/java/com/rokid/context/phone/ui/shell/ShellSourceRulesTest.kt`:
```kotlin
package com.rokid.context.phone.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source rules from spec §1.4 / R-07 / R-20 (unit tests run with the module directory as working dir). */
class ShellSourceRulesTest {
    private val main = File("src/main/java/com/rokid/context")

    @Test fun mainActivityHasNoPollingLoopNoBridgeAndNoGodComposable() {
        val src = File(main, "MainActivity.kt").readText()
        for (banned in listOf("delay(", "while (true)", "RokidBridgeService", ".bridge.", "@Composable", "mutableStateOf")) {
            assertFalse("MainActivity.kt must not contain '$banned'", src.contains(banned))
        }
        assertTrue(src.contains("AppShell("))
    }

    @Test fun viewModelsNeverPollOrBlock() {
        val vms = File(main, "phone/ui").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .filter { f -> f.readText().contains(": ViewModel()") }.toList()
        assertTrue("expected ViewModels under phone/ui", vms.size >= 9)
        for (f in vms) {
            val src = f.readText()
            assertFalse("${f.name}: runBlocking", src.contains("runBlocking"))
            assertFalse("${f.name}: while (true)", src.contains("while (true)"))
            // LegacyConfig's 600 ms debounce is the only allowed delay in a ViewModel file (Phase 2 ruling).
            if (f.name != "LegacyConfig.kt") assertFalse("${f.name}: delay(", src.contains("delay("))
        }
    }

    @Test fun newPhoneUiNeverUsesTheLegacyFacades() {
        File(main, "phone/ui").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            val src = f.readText()
            for (facade in listOf("MemoryRecordStore", "FrameStreamStore", "SummaryTraceStore")) assertFalse("${f.name}: $facade", src.contains(facade))
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :phone:testDebugUnitTest --tests "*RoutesTest" --tests "*ShellSourceRulesTest"`
Expected: FAIL — `Unresolved reference: Routes` / `PhoneDeps` (compilation), and once compiled, `mainActivityHasNoPollingLoopNoBridgeAndNoGodComposable` fails on the old `MainActivity.kt` (`delay(`).

- [ ] **Step 3: `Routes.kt`**

```kotlin
package com.rokid.context.phone.ui.shell

import com.rokid.context.phone.ui.activity.Period
import java.net.URLEncoder
import java.time.LocalDate

/** spec §2.1 routes owned by Phase 4 (Phase 5 adds the rest of `settings/…`). */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ASK = "ask/{id}"
    const val ACTIVITY = "activity?period={period}"
    const val DAY = "day/{date}"
    const val MEMORY = "memory/{id}"
    const val FRAMES = "frames/{date}?start={start}"
    const val SETTINGS = "settings"
    const val SETTINGS_ENDPOINTS = "settings/endpoints"
    const val SETTINGS_DIAGNOSTICS = "settings/diagnostics"
    const val DIAGNOSTICS_QUEUES = "settings/diagnostics/queues"
    const val DIAGNOSTICS_TRACKER = "settings/diagnostics/tracker"

    enum class Tab(val route: String, val label: String) { HOME("home", "Home"), ACTIVITY("activity", "Activity"), SETTINGS("settings", "Settings") }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun ask(id: String) = "ask/${enc(id)}"
    fun activity(period: Period? = null) = if (period == null) "activity" else "activity?period=${period.name.lowercase()}"
    fun day(date: LocalDate) = "day/$date"
    fun memory(id: String) = "memory/${enc(id)}"
    fun frames(date: LocalDate, start: String? = null) = "frames/$date" + (start?.let { "?start=${enc(it)}" } ?: "")
    fun startRoute(onboardingComplete: Boolean) = if (onboardingComplete) HOME else ONBOARDING

    /** [route] is the destination's pattern (`NavDestination.route`). */
    fun tabFor(route: String?): Tab? = when {
        route == null || route == ONBOARDING -> null
        route == HOME || route.startsWith("ask/") -> Tab.HOME
        route.startsWith("activity") || route.startsWith("day/") || route.startsWith("memory/") || route.startsWith("frames/") -> Tab.ACTIVITY
        route.startsWith("settings") -> Tab.SETTINGS
        else -> null
    }

    fun showsNav(route: String?): Boolean = route != null && route != ONBOARDING && !route.startsWith("frames/")
}
```

- [ ] **Step 4: `OnboardingStore.kt` + `WelcomeScreen.kt`**

```kotlin
package com.rokid.context.phone.ui.onboarding

import android.content.Context

/** The §2.1 gate. Phase 7's wizard writes the same flag (or bumps [K_COMPLETE] to re-run for Phase-4 users). */
class OnboardingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isComplete(): Boolean = prefs.getBoolean(K_COMPLETE, false)
    fun markComplete() { prefs.edit().putBoolean(K_COMPLETE, true).apply() }

    companion object {
        private const val PREFS = "onboarding"
        const val K_COMPLETE = "v1_complete"
    }
}
```

```kotlin
package com.rokid.context.phone.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.GlassesIcon
import com.rokid.context.core.ui.components.GradientButton
import com.rokid.context.core.ui.components.IconChip
import com.rokid.context.core.ui.theme.PcColors

/** One-step onboarding (Phase 4). Phase 7 replaces it with the full wizard (R-19). */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(PcColors.HeaderGradient).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconChip(GlassesIcon, PcColors.ChipMint, size = 96.dp, shapeRadius = 28.dp)
        Spacer(Modifier.height(24.dp))
        Text("Welcome to Proactive Context", style = MaterialTheme.typography.headlineMedium, color = PcColors.Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your glasses capture a few quiet moments. Your phone turns them into memories you can ask about.",
            style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        GradientButton("Get started", onClick = onGetStarted, modifier = Modifier.fillMaxWidth())
    }
}
```

- [ ] **Step 5: `PhoneDeps.kt`**

```kotlin
package com.rokid.context.phone.ui.shell

import android.content.Context
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.orchestration.AskActions
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import com.rokid.context.phone.ui.home.AskService
import com.rokid.context.phone.ui.onboarding.OnboardingStore
import com.rokid.context.phone.ui.settings.DiagnosticsActions
import com.rokid.context.phone.ui.settings.LegacyQueueActions
import com.rokid.context.phone.ui.settings.QueueActions
import com.rokid.context.phone.ui.settings.SchedulerDiagnosticsActions
import com.rokid.context.phone.ui.status.LegacyPipelineControl
import com.rokid.context.phone.ui.status.PipelineControl
import com.rokid.context.phone.ui.status.StorageUsage
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything the screens need, built once per process (no DI framework in v1). ViewModels survive configuration
 * changes, so this must be a singleton too — otherwise a rotated activity would hold a second PipelineControl.
 */
class PhoneDeps(
    val memory: MemoryGraph,
    val glasses: GlassesStatusSource,
    val pipeline: PipelineControl,
    val onboarding: OnboardingStore,
    val askService: AskService,
    val rateAsk: suspend (String, Boolean) -> Boolean,
    val diagnostics: DiagnosticsActions,
    val queues: QueueActions,
    val storagePct: Flow<Int?>,
    val clock: Flow<Long>,
    val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    fun today(): LocalDate = LocalDate.now(zone())

    companion object {
        @Volatile private var instance: PhoneDeps? = null

        fun get(context: Context): PhoneDeps = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        /** 1 Hz UI clock: re-formats the "next 4:12" countdown and notices local midnight. No I/O. Cold. */
        fun ticker(periodMs: Long = 1_000, now: () -> Long = System::currentTimeMillis): Flow<Long> = flow {
            while (currentCoroutineContext().isActive) {
                emit(now())
                delay(periodMs)
            }
        }

        fun create(app: Context): PhoneDeps {
            val scheduler = OrchestrationScheduler(app)
            return PhoneDeps(
                memory = MemoryGraph.get(app),
                glasses = FakeGlassesStatusSource(), // Task 11 (after Phase 3 merges): HubGraph.glassesStatus(app)
                pipeline = LegacyPipelineControl(app, scheduler),
                onboarding = OnboardingStore(app),
                askService = AskService { q -> AskActions.ask(app, q) },
                rateAsk = { id, up -> AskActions.rate(app, id, up) },
                diagnostics = SchedulerDiagnosticsActions(scheduler),
                queues = LegacyQueueActions(app, scheduler),
                storagePct = StorageUsage.percentFlow(app),
                clock = ticker(),
            )
        }
    }
}
```

- [ ] **Step 6: `Destinations.kt`**

```kotlin
package com.rokid.context.phone.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rokid.context.phone.ui.activity.ActivityScreen
import com.rokid.context.phone.ui.activity.ActivityViewModel
import com.rokid.context.phone.ui.activity.DayScreen
import com.rokid.context.phone.ui.activity.DayViewModel
import com.rokid.context.phone.ui.activity.Period
import com.rokid.context.phone.ui.ask.AskDetailScreen
import com.rokid.context.phone.ui.ask.AskDetailViewModel
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.frames.FrameViewerScreen
import com.rokid.context.phone.ui.frames.FrameViewerViewModel
import com.rokid.context.phone.ui.home.HomeScreen
import com.rokid.context.phone.ui.home.HomeViewModel
import com.rokid.context.phone.ui.memory.MemoryDetailScreen
import com.rokid.context.phone.ui.memory.MemoryDetailViewModel
import com.rokid.context.phone.ui.onboarding.WelcomeScreen
import com.rokid.context.phone.ui.settings.DiagnosticsScreen
import com.rokid.context.phone.ui.settings.DiagnosticsViewModel
import com.rokid.context.phone.ui.settings.LegacyConfigScreen
import com.rokid.context.phone.ui.settings.LegacyConfigViewModel
import com.rokid.context.phone.ui.settings.QueuesScreen
import com.rokid.context.phone.ui.settings.QueuesViewModel
import com.rokid.context.phone.ui.settings.SettingsRootScreen
import com.rokid.context.phone.ui.settings.SettingsRootViewModel
import com.rokid.context.phone.ui.settings.TrackerScreen
import com.rokid.context.phone.ui.settings.TrackerViewModel

private val idArg = listOf(navArgument("id") { type = NavType.StringType })
private val dateArg = navArgument("date") { type = NavType.StringType }

/** The only place ViewModels are created and wired to their stateless screens (R-20). */
fun NavGraphBuilder.phoneDestinations(deps: PhoneDeps, nav: NavHostController) {
    val back: () -> Unit = { nav.popBackStack() }
    val bannerNav: (BannerAction, (BannerAction) -> Unit) -> Unit = { a, apply -> if (a == BannerAction.FIX_AI) nav.navigate(Routes.SETTINGS_ENDPOINTS) else apply(a) }

    composable(Routes.ONBOARDING) {
        WelcomeScreen(onGetStarted = {
            deps.onboarding.markComplete()
            nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
        })
    }

    composable(Routes.HOME) {
        val vm = viewModel { HomeViewModel(deps.glasses, deps.pipeline, deps.memory.timeline, deps.memory.asks, deps.memory.nudges, deps.askService, deps.clock, deps.storagePct, deps.zone) }
        val state by vm.state.collectAsStateWithLifecycle()
        HomeScreen(
            state, onQuestionChange = vm::onQuestionChange, onAsk = { vm.ask(it) }, onOpenAsk = { nav.navigate(Routes.ask(it)) },
            onTogglePause = vm::togglePause, onBannerAction = { a -> bannerNav(a, vm::onBannerAction) },
        )
    }

    composable(Routes.ASK, arguments = idArg) { entry ->
        val id = entry.arguments?.getString("id").orEmpty()
        val vm = viewModel { AskDetailViewModel(id, deps.memory.asks, deps.memory.proposals, deps.memory.timeline, deps.rateAsk, deps.zone) }
        val state by vm.state.collectAsStateWithLifecycle()
        AskDetailScreen(state, onBack = back, onRate = vm::rate, onOpenMemory = { nav.navigate(Routes.memory(it)) })
    }

    composable(Routes.ACTIVITY, arguments = listOf(navArgument("period") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
        val period = Period.parse(entry.arguments?.getString("period"))
        val vm = viewModel { ActivityViewModel(period, deps.glasses, deps.pipeline, deps.memory.timeline, deps.memory.asks, deps.memory.nudges, deps.clock, deps.storagePct, deps.zone) }
        val state by vm.state.collectAsStateWithLifecycle()
        ActivityScreen(
            state, onSelectPeriod = vm::selectPeriod, onTogglePause = vm::togglePause, onBannerAction = { a -> bannerNav(a, vm::onBannerAction) },
            onOpenMemory = { nav.navigate(Routes.memory(it)) }, onOpenFrames = { nav.navigate(Routes.frames(it)) }, onOpenDay = { nav.navigate(Routes.day(it)) },
        )
    }

    composable(Routes.DAY, arguments = listOf(dateArg)) { entry ->
        val date = entry.arguments?.getString("date")
        val vm = viewModel { DayViewModel(date, deps.memory.timeline, deps.zone, deps::today) }
        val state by vm.state.collectAsStateWithLifecycle()
        DayScreen(state, onBack = back, onOpenMemory = { nav.navigate(Routes.memory(it)) }, onOpenFrames = { nav.navigate(Routes.frames(it)) })
    }

    composable(Routes.MEMORY, arguments = idArg) { entry ->
        val id = entry.arguments?.getString("id").orEmpty()
        val vm = viewModel { MemoryDetailViewModel(id, deps.memory.timeline, deps.memory.memoryEdits, deps.zone) }
        val state by vm.state.collectAsStateWithLifecycle()
        MemoryDetailScreen(
            state, onBack = back, onTrust = vm::trust, onStartEdit = vm::startEdit, onDraftChange = vm::updateDraft, onSaveEdit = vm::saveEdit,
            onCancelEdit = vm::cancelEdit, onOpenFrames = { date, frameId -> nav.navigate(Routes.frames(date, frameId)) },
        )
    }

    composable(Routes.FRAMES, arguments = listOf(dateArg, navArgument("start") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
        val date = entry.arguments?.getString("date")
        val start = entry.arguments?.getString("start")
        val vm = viewModel { FrameViewerViewModel(date, start, deps.memory.timeline, deps.zone, deps::today) }
        val state by vm.state.collectAsStateWithLifecycle()
        FrameViewerScreen(state, onBack = back)
    }

    composable(Routes.SETTINGS) {
        val vm = viewModel { SettingsRootViewModel(deps.glasses) }
        val state by vm.state.collectAsStateWithLifecycle()
        SettingsRootScreen(state, onOpenEndpoints = { nav.navigate(Routes.SETTINGS_ENDPOINTS) }, onOpenDiagnostics = { nav.navigate(Routes.SETTINGS_DIAGNOSTICS) })
    }

    composable(Routes.SETTINGS_ENDPOINTS) {
        val app = LocalContext.current.applicationContext
        val vm = viewModel { LegacyConfigViewModel(app, deps.pipeline) }
        val state by vm.state.collectAsStateWithLifecycle()
        LegacyConfigScreen(
            state, onBack = back, onTogglePipeline = vm::togglePipeline, onToggleLocalMode = vm::toggleLocalMode, onToggleEncryption = vm::toggleEncryption,
            onCycleMemoryWindow = vm::cycleMemoryWindow, onSelectProvider = vm::selectProvider, onUpdateEndpoint = vm::updateEndpoint,
            onUpdateApiKey = vm::updateApiKey, onUpdateDeploymentOrModel = vm::updateModel, onUpdateNativePath = vm::updateNativePath,
            onSelectGlassesSilencyMode = vm::selectGlassesSilencyMode, onTestConnectivity = vm::testConnectivity,
        )
    }

    composable(Routes.SETTINGS_DIAGNOSTICS) {
        val vm = viewModel { DiagnosticsViewModel(deps.diagnostics, deps.pipeline) }
        val state by vm.state.collectAsStateWithLifecycle()
        DiagnosticsScreen(
            state, onBack = back, onOpenQueues = { nav.navigate(Routes.DIAGNOSTICS_QUEUES) }, onOpenTracker = { nav.navigate(Routes.DIAGNOSTICS_TRACKER) },
            onSummarizeNow = vm::summarizeNow, onCaptureNow = vm::captureNow, onReprocess = vm::reprocess,
        )
    }

    composable(Routes.DIAGNOSTICS_QUEUES) {
        val vm = viewModel { QueuesViewModel(deps.queues) }
        val state by vm.state.collectAsStateWithLifecycle()
        QueuesScreen(
            state, onBack = back, onRefresh = vm::refresh, onExplicitWriteChange = vm::onExplicitWriteChange, onReadQueryChange = vm::onReadQueryChange,
            onQueueExplicitWrite = vm::queueExplicitWrite, onQueueReadContext = vm::queueReadContext, onDrainNow = vm::drainNow,
        )
    }

    composable(Routes.DIAGNOSTICS_TRACKER) {
        val vm = viewModel { TrackerViewModel(deps.memory.timeline, deps.zone) }
        val items by vm.state.collectAsStateWithLifecycle()
        TrackerScreen(items, onBack = back)
    }
}
```

- [ ] **Step 7: `AppShell.kt`**

```kotlin
package com.rokid.context.phone.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rokid.context.core.ui.components.PillNav
import com.rokid.context.core.ui.components.PillNavItem
import com.rokid.context.core.ui.theme.PcColors

private val TABS = listOf(
    PillNavItem(Routes.Tab.HOME.route, Routes.Tab.HOME.label, Icons.Outlined.Home),
    PillNavItem(Routes.Tab.ACTIVITY.route, Routes.Tab.ACTIVITY.label, Icons.Outlined.Timeline),
    PillNavItem(Routes.Tab.SETTINGS.route, Routes.Tab.SETTINGS.label, Icons.Outlined.Settings),
)

/** spec §2.1: NavHost + floating 3-tab PillNav (Live Fit AppActivity layout). */
@Composable
fun AppShell(deps: PhoneDeps, startRoute: String, nav: NavHostController = rememberNavController()) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showNav = Routes.showsNav(route)
    Box(Modifier.fillMaxSize().background(PcColors.Surface)) {
        NavHost(nav, startDestination = startRoute, modifier = if (showNav) Modifier.padding(bottom = 84.dp) else Modifier) {
            phoneDestinations(deps, nav)
        }
        if (showNav) {
            PillNav(
                items = TABS,
                selectedRoute = Routes.tabFor(route)?.route.orEmpty(),
                onSelect = { tab ->
                    nav.navigate(tab) {
                        popUpTo(Routes.HOME) { saveState = false }
                        launchSingleTop = true
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
            )
        }
    }
}
```

- [ ] **Step 8: Rewrite `MainActivity.kt`**

Replace the whole file with:
```kotlin
package com.rokid.context

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.data.frames.FrameStreamStore
import com.rokid.context.data.runtime.MemoryRecordStore
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.data.runtime.SummaryTraceStore
import com.rokid.context.phone.ui.shell.AppShell
import com.rokid.context.phone.ui.shell.PhoneDeps
import com.rokid.context.phone.ui.shell.Routes
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Thin host (spec §1.4): one-time cleanup + worker scheduling, then the NavHost shell. No polling, no UI state here. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Legacy cleanup, off the main thread (Room-backed calls may wait on the one-time import). Task 11 swaps the
        // three facade calls for PipelineRecords.
        lifecycleScope.launch(Dispatchers.IO) {
            QueueRuntimeStore.purgeSynthetic(this@MainActivity)
            FrameStreamStore.purgeNonRokidEntries(this@MainActivity)
            SummaryTraceStore.purgeSynthetic(this@MainActivity)
            MemoryRecordStore.purgeNonRokid(this@MainActivity)
        }
        val deps = PhoneDeps.get(this)
        // The legacy app re-scheduled workers on every launch even when the pipeline was off; honour the switch.
        if (deps.pipeline.enabled.value) {
            OrchestrationScheduler(this).apply { scheduleFrameIngest(); scheduleQueueDrain(); scheduleCritic() }
        }
        val start = Routes.startRoute(deps.onboarding.isComplete())
        setContent { PcTheme { AppShell(deps, start) } }
    }
}
```
This file must contain no `bridge` reference (contract): Phase 3's edits to the old file are superseded at merge.

- [ ] **Step 9: Delete the legacy stub screens and move the surviving onboarding-file test**

```bash
git rm phone/src/main/java/com/rokid/context/ui/home/HomeScreen.kt phone/src/main/java/com/rokid/context/ui/live/LivePipelineScreen.kt \
  phone/src/main/java/com/rokid/context/ui/memory/MemoryScreen.kt phone/src/main/java/com/rokid/context/ui/migration/MigrationStatusCard.kt \
  phone/src/main/java/com/rokid/context/ui/onboarding/OnboardingScreen.kt phone/src/main/java/com/rokid/context/ui/status/StatusScreen.kt \
  phone/src/main/java/com/rokid/context/ui/frames/FrameSlideshowScreen.kt phone/src/main/java/com/rokid/context/ui/diagnostics/DiagnosticsScreen.kt \
  phone/src/test/java/com/rokid/context/ui/OnboardingFlowTest.kt
```
Create `phone/src/test/java/com/rokid/context/ui/ConfigExportTest.kt` (the one `OnboardingFlowTest` case that covers code that still exists; the two removed cases tested the deleted stub `OnboardingScreen`):
```kotlin
package com.rokid.context.ui

import com.rokid.context.ui.config.ConfigHubScreen
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigExportTest {
    @Test
    fun configExportExcludesSecrets() {
        val export = ConfigHubScreen.buildExportPayload(
            providerType = "cloud",
            endpoint = "https://api.example.com",
            model = "gpt-5.4",
            timeoutMs = 10_000,
            apiKey = "super-secret-key",
        )
        assertEquals("****", export.apiKeyMasked)
    }
}
```

- [ ] **Step 10: Run the pure tests**

Run: `./gradlew :phone:testDebugUnitTest --tests "*RoutesTest" --tests "*ShellSourceRulesTest" --tests "*ConfigExportTest"`
Expected: PASS (8 tests).

- [ ] **Step 11: Write the shell + onboarding tests**

`phone/src/test/java/com/rokid/context/phone/ui/onboarding/OnboardingStoreTest.kt`:
```kotlin
package com.rokid.context.phone.ui.onboarding

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class OnboardingStoreTest {
    @Test fun completionPersistsAcrossInstances() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(OnboardingStore(ctx).isComplete())
        OnboardingStore(ctx).markComplete()
        assertTrue(OnboardingStore(ctx).isComplete())
    }
}
```

`phone/src/test/java/com/rokid/context/phone/ui/shell/AppShellTest.kt`:
```kotlin
package com.rokid.context.phone.ui.shell

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import com.rokid.context.phone.ui.home.AskService
import com.rokid.context.phone.ui.onboarding.OnboardingStore
import com.rokid.context.phone.ui.settings.DiagnosticsActions
import com.rokid.context.phone.ui.settings.QueueActions
import com.rokid.context.ui.queues.QueueSnapshot
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AppShellTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var deps: PhoneDeps
    private lateinit var nav: NavHostController

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        deps = PhoneDeps(
            memory = MemoryGraph.get(ctx), glasses = FakeGlassesStatusSource(), pipeline = FakePipelineControl(), onboarding = OnboardingStore(ctx),
            askService = AskService { AskOutcome.Failed("offline in tests") }, rateAsk = { _, _ -> true },
            diagnostics = object : DiagnosticsActions {
                override suspend fun summarizeNow() = "ok"; override suspend fun captureNow() = "ok"; override suspend fun reprocessOldPhotos() = "ok"
            },
            queues = object : QueueActions {
                override suspend fun snapshot() = QueueSnapshot(0, 0, 0, 0, 0, 24, 0, 0, 0, "none")
                override suspend fun queueExplicitWrite(text: String) = "ok"; override suspend fun queueReadContext(query: String) = "ok"; override suspend fun drainNow() = "ok"
            },
            storagePct = flowOf(10), clock = flowOf(Fixtures.NOW), zone = { Fixtures.ZONE },
        )
    }

    // ViewModels are cleared when the compose rule tears the activity down, which happens after @After;
    // so the in-memory DB is left open (GC'd) rather than closed under live collectors.
    @After fun tearDown() = MemoryGraph.resetForTests(null)

    private fun launch(start: String) = compose.setContent { nav = rememberNavController(); PcTheme { AppShell(deps, start, nav) } }

    /** Room emits on its own threads, which Compose idling does not track — wait for data-driven text explicitly. */
    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun firstRunShowsWelcomeWithoutPillNavThenHome() {
        launch(Routes.startRoute(deps.onboarding.isComplete()))
        compose.onNodeWithText("Welcome to Proactive Context").assertIsDisplayed()
        compose.onNodeWithText("Home").assertDoesNotExist()
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Ask about your day").assertIsDisplayed()
        compose.onNodeWithText("Home").assertIsDisplayed()
        assertTrue(deps.onboarding.isComplete())
    }

    @Test fun pillNavSwitchesBetweenTheThreeTabs() {
        launch(Routes.HOME)
        compose.onNodeWithText("Activity").performClick()
        compose.onNodeWithText("What your glasses noticed").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("DEVICES").assertIsDisplayed()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Ask about your day").assertIsDisplayed()
    }

    @Test fun frameViewerHidesThePillNavAndBackRestoresIt() {
        launch(Routes.HOME)
        compose.runOnIdle { nav.navigate(Routes.frames(Fixtures.TODAY)) }
        waitForText("No photos for this day")
        compose.onNodeWithText("Home").assertDoesNotExist()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test fun staleAskLinkShowsNotFound() {
        launch(Routes.HOME)
        compose.runOnIdle { nav.navigate(Routes.ask("ask_missing")) }
        waitForText("This answer is no longer available")
    }

    @Test fun homeShowsTheDisconnectedBannerAndDiagnosticsReachesTheTracker() {
        launch(Routes.HOME)
        waitForText("Glasses disconnected") // OFFLINE fake → disconnected banner (Home state waits for its Room flows)
        compose.runOnIdle { nav.navigate(Routes.SETTINGS_DIAGNOSTICS) }
        compose.onNodeWithText("Summarizer tracker").performClick()
        compose.onNodeWithText("No summary jobs yet").assertIsDisplayed()
    }
}
```
(The last test also proves the Diagnostics → Tracker route resolves; the legacy tracker shows "No summary jobs yet" on an empty DB.)

- [ ] **Step 12: Run them**

Run: `./gradlew :phone:testDebugUnitTest --tests "*AppShellTest" --tests "*OnboardingStoreTest"`
Expected: PASS (6 tests).

- [ ] **Step 13: Gate**

Run: `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: BUILD SUCCESSFUL. Phone tests: 2 legacy `OnboardingScreen` tests removed with the stub (spec §1.4), everything else green.

- [ ] **Step 14: Device smoke (read-only, guarded)**

```bash
ADB=/Users/<user>/Desktop/rokid/android-sdk/platform-tools/adb; S=adb-RZCY902F76K-nmSM2g._adb-tls-connect._tcp
$ADB -s $S shell dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity"
```
Expected: one line. **If it contains `com.rokid.context`, stop here** (the user is in the app) and record "device smoke deferred" in the ledger. Otherwise:
```bash
$ADB -s $S install -r phone/build/outputs/apk/debug/phone-debug.apk
```
Expected: `Success`. Do not launch the app. Ask the user to open Proactive Context when convenient (first launch shows **Welcome**; tap **Get started**). After they confirm it is in the foreground:
```bash
$ADB -s $S shell dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity"
$ADB -s $S shell uiautomator dump /sdcard/pc_shell.xml >/dev/null && $ADB -s $S exec-out cat /sdcard/pc_shell.xml | grep -o 'text="[^"]*"' | head -40
$ADB -s $S logcat -d -t 2000 | grep -E "FATAL EXCEPTION|AndroidRuntime" | grep -A3 com.rokid.context || echo "no crash"
```
Expected: resumed activity is `com.rokid.context/.MainActivity`; the dump contains `Proactive Context`, `Ask about your day`, `Home`, `Activity`, `Settings` (or `Welcome to Proactive Context` on first run); `no crash`. (Until Task 11 the subline reads "Glasses offline" — expected.)

- [ ] **Step 15: Commit**

```bash
git add -A android-companion/phone
git commit -m "feat(phone): NavHost shell with PillNav and onboarding gate; thin MainActivity without polling

Removes the legacy Live/Status/Queues-home/Memory/Migration/Frames/Onboarding stub screens (spec §1.4).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 11: Post-merge integration — hub status, no bridge, legacy facades deleted

**Runs only after** Phase 3 has merged into `feat/proactive-mvp-implementation` and this phase (Tasks 1–10) has merged on top (contract merge order: Phase 3 → Phase 4 → this task). Run it in the main checkout on that branch.

**Requirements:** R-07 (screens read the real `ContextHubService` state through `HubGraph.glassesStatus(context)`), R-04 (no RFCOMM bridge reference left in the phone UI), R-03 (the three transitional facades are gone; their callers use the Room repositories through suspend calls; their tests are ported), R-72

**Files:**
- Modify: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/shell/PhoneDeps.kt` (the `glasses =` line)
- Move: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/glasses/FakeGlassesStatusSource.kt` → `android-companion/phone/src/test/java/com/rokid/context/phone/ui/glasses/FakeGlassesStatusSource.kt`
- Modify: `android-companion/phone/src/test/java/com/rokid/context/phone/ui/shell/ShellSourceRulesTest.kt` (two rules)
- Create: `android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/PipelineRecords.kt`
- Test: `android-companion/services/orchestration/src/test/java/com/rokid/context/orchestration/PipelineRecordsTest.kt` (ports `LegacyFacadesTest` + `MemoryRecordStoreTest`)
- Modify: `android-companion/services/orchestration/src/main/java/com/rokid/context/work/QueueDrainWorker.kt`, `.../work/CriticWorkerJob.kt`, `.../work/SummarizerWorkerJob.kt`, `.../work/OrchestrationScheduler.kt`, `.../data/frames/RokidFrameInbox.kt` (as merged from Phase 3), `android-companion/phone/src/main/java/com/rokid/context/MainActivity.kt`, `android-companion/services/memory/src/main/java/com/rokid/context/memory/legacy/LegacyImporter.kt` (one comment)
- Delete: `android-companion/services/memory/src/main/java/com/rokid/context/data/runtime/MemoryRecordStore.kt`, `android-companion/services/orchestration/src/main/java/com/rokid/context/data/frames/FrameStreamStore.kt`, `android-companion/services/orchestration/src/main/java/com/rokid/context/data/runtime/SummaryTraceStore.kt`, `android-companion/services/orchestration/src/test/java/com/rokid/context/data/LegacyFacadesTest.kt`, `android-companion/services/memory/src/test/java/com/rokid/context/data/runtime/MemoryRecordStoreTest.kt`
- Conditional modify: `android-companion/phone/src/main/java/com/rokid/context/phone/ui/status/StorageUsage.kt` + `StorageUsageTest.kt` (only if Phase 3 stores frame JPEGs in a new folder — Step 6)

**Interfaces:**
- Consumes: `HubGraph.glassesStatus(context): GlassesStatusSource` (Phase 3, package `com.rokid.context.phone.hub`, per contract); `MemoryGraph.frames/memories/traces`, `MemoryGraph.ensureLegacyImported`, `LegacyImporter.toMemory` (Phase 2).
- Produces: `object PipelineRecords` (package `com.rokid.context.orchestration`): `data class PurgeResult(kept: Int, dropped: Int, deletedFiles: Int)`; frames `suspend appendFrame(context, frame: FrameEntity): Boolean`, `suspend recentFrames(context, limit): List<FrameEntity>`, `suspend allFrames(context)`, `suspend framesByIds(context, ids)`, `suspend purgeNonRokidFrames(context)`, `suspend purgeNonPhotoAndSynthetic(context): PurgeResult`, `fun isRealPhotoFrame(frame: FrameEntity): Boolean`; memories `suspend appendMemory(context, id, type, content, confidence, createdAt)`, `suspend recentMemories(context, limit): List<MemoryEntity>`, `suspend purgeNonRokidMemories(context)`, `suspend purgeLowSignalMemories(context)`; traces `suspend upsertTrace(context, summaryId, state, note, updatedAt = now)`, `suspend recentTraces(context, limit): List<SummaryTraceEntity>`, `suspend purgeSyntheticTraces(context)`. `OrchestrationScheduler.enqueueSummarizer` and `enqueueHistoricalPhotoBackfill`, and `RokidFrameInbox.ingestPending/ingestSinglePath`, become `suspend`.

`PipelineRecords` is not a renamed facade: it holds the legacy *pipeline rules* (rokid-only frames whose JPEG exists, photo filter, one-line text, purge rules) as suspend functions over the repositories — no `runBlocking`, no legacy DTOs (`CapturedFrame`, `MemoryRecord`, `SummaryTrace` are deleted with the facades). It keeps the facades' "wait for the one-time legacy import first" guarantee (Phase 2 Review Focus #5).

- [ ] **Step 1: Verify the merge state and the known conflict resolutions**

Run (from `android-companion/`):
```bash
git log --oneline -15 | cat
grep -n "fun glassesStatus" phone/src/main/java/com/rokid/context/phone/hub/*.kt
awk '/^```kotlin$/{f=1;next} /^```$/{f=0} f' ../docs/superpowers/plans/2026-10-12-v1-phase3-4-contract.md | diff - core/model/src/main/kotlin/com/rokid/context/core/model/GlassesStatus.kt && echo IDENTICAL
grep -c 'kotlinx-coroutines-core:1.9.0' core/model/build.gradle.kts
grep -E '^\s*(implementation|testImplementation|debugImplementation)' phone/build.gradle.kts | sort | uniq -d
grep -nE "RokidBridgeService|RokidConfigPushClient|com\.rokid\.context\.bridge" phone/src/main/java/com/rokid/context/MainActivity.kt phone/src/main/AndroidManifest.xml || echo "no bridge refs"
grep -n 'android:icon\|android:roundIcon' phone/src/main/AndroidManifest.xml
```
Expected: both phases' commits present; one `fun glassesStatus(context: Context): GlassesStatusSource` line; `IDENTICAL`; `1`; no duplicate dependency lines; `no bridge refs`; `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`. If a merge left a conflict resolved the wrong way, fix it now with these rules: `MainActivity.kt` = Phase 4's version; `phone/build.gradle.kts` = union of both sides' lines, deduplicated; `ContextDatabase.kt` / `MemoryGraph.kt` = union of both sides' DAO getters / `val`s; `AndroidManifest.xml` = Phase 3's file with Phase 4's two icon attributes. If `glassesStatus` has a different shape than the contract, adapt only the call in Step 3 and record it in the ledger.

- [ ] **Step 2: Write the failing rules (fake leaves production; facades are gone)**

In `ShellSourceRulesTest.kt` add:
```kotlin
    @Test fun productionDepsReadTheHubStatus() {
        val src = File(main, "phone/ui/shell/PhoneDeps.kt").readText()
        assertTrue(src.contains("HubGraph.glassesStatus("))
        assertFalse(src.contains("FakeGlassesStatusSource"))
    }

    @Test fun legacyFacadesAreDeletedEverywhere() {
        val roots = listOf(File("src/main"), File("../services/memory/src/main"), File("../services/orchestration/src/main"))
        val hits = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() }
            .filter { f -> listOf("MemoryRecordStore", "FrameStreamStore", "SummaryTraceStore").any { f.readText().contains(it) } }
            .map { it.path }
        assertTrue("facade references left: $hits", hits.isEmpty())
    }
```
Then move the fake out of the app:
```bash
git mv phone/src/main/java/com/rokid/context/phone/ui/glasses/FakeGlassesStatusSource.kt phone/src/test/java/com/rokid/context/phone/ui/glasses/FakeGlassesStatusSource.kt
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :phone:compileDebugKotlin`
Expected: FAIL — `PhoneDeps.kt: Unresolved reference: FakeGlassesStatusSource`.

- [ ] **Step 4: Swap in the hub status**

In `PhoneDeps.create`, replace the `glasses = FakeGlassesStatusSource(), // Task 11 …` line with:
```kotlin
                glasses = com.rokid.context.phone.hub.HubGraph.glassesStatus(app),
```
and delete the `import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource` line.

Run: `./gradlew :phone:testDebugUnitTest --tests "*ShellSourceRulesTest" --tests "*AppShellTest"`
Expected: `productionDepsReadTheHubStatus` PASS, `AppShellTest` PASS (tests still build their own `PhoneDeps` with the fake, now from the test source set), `legacyFacadesAreDeletedEverywhere` FAIL listing the facade files and their callers.

- [ ] **Step 5: Write the failing `PipelineRecords` test (ported facade tests)**

`services/orchestration/src/test/java/com/rokid/context/orchestration/PipelineRecordsTest.kt`:
```kotlin
package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PipelineRecordsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() = MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
    @After fun tearDown() = MemoryGraph.resetForTests(null)

    private fun jpg(name: String) = File(ctx.filesDir, name).apply { writeBytes(byteArrayOf(1)) }.absolutePath
    private fun frame(id: String, ts: Long, path: String, source: String = "rokid_glasses", payload: String = "p") =
        FrameEntity(id = id, ts = ts, path = path, source = source, payload = payload)

    @Test fun framesRoundTripAndRejectNonRokidOrMissingPreview() = runBlocking {
        assertTrue(PipelineRecords.appendFrame(ctx, frame("f1", 10, jpg("a.jpg"))))
        assertFalse(PipelineRecords.appendFrame(ctx, frame("f2", 11, jpg("b.jpg"), source = "phone")))
        assertFalse(PipelineRecords.appendFrame(ctx, frame("f3", 12, "/missing.jpg")))
        assertEquals(listOf("f1"), PipelineRecords.recentFrames(ctx, 10).map { it.id })
        assertEquals(listOf("f1"), PipelineRecords.framesByIds(ctx, listOf("f1", "nope", " ")).map { it.id })
        assertTrue(PipelineRecords.framesByIds(ctx, listOf(" ")).isEmpty())
    }

    @Test fun appendKeepsPayloadOnOneLine() = runBlocking {
        PipelineRecords.appendFrame(ctx, frame("f1", 10, jpg("a.jpg"), payload = "a\tb\nc"))
        assertEquals("a b c", PipelineRecords.allFrames(ctx).single().payload)
    }

    // NB: Robolectric names filesDir after the test method, so the name must not contain "synthetic"/"screenshot".
    @Test fun purgeNonPhotoDeletesFakeFrameAndItsFile() = runBlocking {
        val synth = jpg("synthetic_1.jpg")
        PipelineRecords.appendFrame(ctx, frame("s1", 10, synth, payload = "synthetic"))
        PipelineRecords.appendFrame(ctx, frame("r1", 11, jpg("r.jpg"), payload = "real"))
        val r = PipelineRecords.purgeNonPhotoAndSynthetic(ctx)
        assertEquals(PipelineRecords.PurgeResult(kept = 1, dropped = 1, deletedFiles = 1), r)
        assertFalse(File(synth).exists())
    }

    @Test fun tracesUpsertNewestFirstAndPurgeSynthetic() = runBlocking {
        PipelineRecords.upsertTrace(ctx, "s1", "QUEUED", "a", updatedAt = 1)
        PipelineRecords.upsertTrace(ctx, "s2", "QUEUED", "b\nplaceholder", updatedAt = 2)
        PipelineRecords.upsertTrace(ctx, "s1", "TRUSTED", "c", updatedAt = 3)
        assertEquals(listOf("s1" to "TRUSTED", "s2" to "QUEUED"), PipelineRecords.recentTraces(ctx, 10).map { it.summaryId to it.state })
        assertEquals("b placeholder", PipelineRecords.recentTraces(ctx, 10)[1].note)
        PipelineRecords.purgeSyntheticTraces(ctx)
        assertEquals(listOf("s1"), PipelineRecords.recentTraces(ctx, 10).map { it.summaryId })
    }

    @Test fun firstUseTriggersTheLegacyImport() = runBlocking {
        File(ctx.filesDir, "memory_records.log").writeText("rokid_mem_9\tshort_term\t0.8\t5\tImported before first use\n")
        assertEquals(listOf("rokid_mem_9"), PipelineRecords.recentMemories(ctx, 10).map { it.id })
    }

    @Test fun ensureLegacyImportedRunsImportOnlyOnce() = runBlocking {
        File(ctx.filesDir, "memory_records.log").writeText("rokid_mem_9\tshort_term\t0.8\t5\tOnce only\n")
        assertEquals(1, MemoryGraph.ensureLegacyImported(ctx)?.memories)
        assertEquals(null, MemoryGraph.ensureLegacyImported(ctx))
        assertEquals(1, PipelineRecords.recentMemories(ctx, 10).size)
    }

    @Test fun memoriesAreNewestFirstAndSanitised() = runBlocking {
        PipelineRecords.appendMemory(ctx, "rokid_mem_1", "short_term", "a\tb\nc", 0.9, 1)
        PipelineRecords.appendMemory(ctx, "rokid_mem_2", "short_term", "second", 0.5, 2)
        val r = PipelineRecords.recentMemories(ctx, 10)
        assertEquals(listOf("rokid_mem_2", "rokid_mem_1"), r.map { it.id })
        assertEquals("a b c", r[1].text)
    }

    @Test fun purgeNonRokidKeepsOnlyRokidIds() = runBlocking {
        PipelineRecords.appendMemory(ctx, "other_1", "short_term", "x", 0.1, 1)
        PipelineRecords.appendMemory(ctx, "rokid_mem_3", "short_term", "y", 0.1, 2)
        PipelineRecords.purgeNonRokidMemories(ctx)
        assertEquals(listOf("rokid_mem_3"), PipelineRecords.recentMemories(ctx, 10).map { it.id })
    }

    @Test fun purgeLowSignalDropsReviewsAndMarkers() = runBlocking {
        PipelineRecords.appendMemory(ctx, "rokid_review_1", "review", "needs review", 0.1, 1)
        PipelineRecords.appendMemory(ctx, "rokid_mem_4", "short_term", "Metadata only frame", 0.1, 2)
        PipelineRecords.appendMemory(ctx, "rokid_mem_5", "short_term", "Milk carton nearly empty", 0.8, 3)
        PipelineRecords.purgeLowSignalMemories(ctx)
        assertEquals(listOf("rokid_mem_5"), PipelineRecords.recentMemories(ctx, 10).map { it.id })
    }

    @Test fun inboxFramesArchivedInTheSameMillisecondBothStoreWithDistinctFullIds() = runBlocking {
        val a = jpg("1760000000000_rokid_1111_a.jpg")
        val b = jpg("1760000000000_rokid_1111_b.jpg")
        assertTrue(RokidFrameInbox.ingestSinglePath(ctx, a))
        assertTrue(RokidFrameInbox.ingestSinglePath(ctx, b))
        assertEquals(setOf("1760000000000_rokid_1111_a", "1760000000000_rokid_1111_b"), PipelineRecords.allFrames(ctx).map { it.id }.toSet())
    }
}
```
If Phase 3 changed `RokidFrameInbox`'s API, keep the last test's intent (two archive files from the same millisecond → two frames with their full ids) against the merged API.

Run: `./gradlew :services:orchestration:testDebugUnitTest --tests "*PipelineRecordsTest"`
Expected: FAIL — `Unresolved reference: PipelineRecords`.

- [ ] **Step 6: `PipelineRecords.kt`**

```kotlin
package com.rokid.context.orchestration

import android.content.Context
import android.util.Log
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.SummaryTraceEntity
import com.rokid.context.memory.legacy.LegacyImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The legacy pipeline's record rules over the Room repositories (replaces the Phase 2 facades FrameStreamStore /
 * MemoryRecordStore / SummaryTraceStore). Suspend and main-safe; every call first waits for the one-time legacy import.
 */
object PipelineRecords {
    private const val TAG = "PipelineRecords"
    private val LOW_SIGNAL = listOf(
        "no visual content", "only file references", "file references and timestamps", "metadata only", "no image pixels",
        "no visual content or scene details",
    )

    data class PurgeResult(val kept: Int, val dropped: Int, val deletedFiles: Int)

    private suspend fun graph(context: Context): MemoryGraph {
        withContext(Dispatchers.IO) { MemoryGraph.ensureLegacyImported(context) }
        return MemoryGraph.get(context)
    }

    private fun oneLine(s: String) = s.replace("\n", " ").replace("\t", " ")

    // --- frames -------------------------------------------------------------------------------------------------

    /** Only `rokid*` sources with a JPEG that exists; payload kept to one line (≤ 220 chars). False = rejected or duplicate id. */
    suspend fun appendFrame(context: Context, frame: FrameEntity): Boolean {
        if (!frame.source.startsWith("rokid", ignoreCase = true)) { Log.w(TAG, "append rejected non-rokid source=${frame.source}"); return false }
        val file = frame.path.takeIf { it.isNotBlank() }?.let(::File)
        if (file == null || !withContext(Dispatchers.IO) { file.exists() }) { Log.w(TAG, "append rejected preview missing frameId=${frame.id}"); return false }
        val ok = graph(context).frames.add(frame.copy(path = file.absolutePath, payload = oneLine(frame.payload).take(220)))
        Log.i(TAG, "append ok=$ok frameId=${frame.id}")
        return ok
    }

    private suspend fun existing(list: List<FrameEntity>): List<FrameEntity> = withContext(Dispatchers.IO) {
        list.filter { it.source.startsWith("rokid", ignoreCase = true) && it.path.isNotBlank() && File(it.path).exists() }
    }

    suspend fun recentFrames(context: Context, limit: Int): List<FrameEntity> {
        val n = limit.coerceAtLeast(1)
        return existing(graph(context).frames.recent(n * 2)).take(n)
    }

    suspend fun allFrames(context: Context): List<FrameEntity> = existing(graph(context).frames.all())

    suspend fun framesByIds(context: Context, ids: List<String>): List<FrameEntity> {
        val wanted = ids.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        return if (wanted.isEmpty()) emptyList() else existing(graph(context).frames.byIds(wanted))
    }

    suspend fun purgeNonRokidFrames(context: Context) {
        val g = graph(context)
        g.frames.deleteByIds(g.frames.all().filter { !it.source.startsWith("rokid", ignoreCase = true) || it.path.isBlank() }.map { it.id })
    }

    suspend fun purgeNonPhotoAndSynthetic(context: Context): PurgeResult {
        val g = graph(context)
        val (kept, dropped) = g.frames.all().partition(::isRealPhotoFrame)
        g.frames.deleteByIds(dropped.map { it.id })
        val deleted = withContext(Dispatchers.IO) {
            dropped.map { it.path }.filter { it.isNotBlank() }.distinct().count { File(it).let { f -> f.exists() && f.delete() } }
        }
        return PurgeResult(kept = kept.size, dropped = dropped.size, deletedFiles = deleted)
    }

    /** Same rule as the legacy facade (kept verbatim; the "match file name only" refinement is a Phase 2 deferred minor). */
    fun isRealPhotoFrame(frame: FrameEntity): Boolean {
        if (!frame.source.startsWith("rokid", ignoreCase = true)) return false
        if (frame.path.isBlank()) return false
        val payload = frame.payload.lowercase()
        val path = frame.path.lowercase()
        if (payload.contains("synthetic") || path.contains("synthetic")) return false
        if (payload.contains("screenshot") || path.contains("screenshot")) return false
        return File(frame.path).extension.lowercase() in setOf("jpg", "jpeg", "webp")
    }

    // --- memories -----------------------------------------------------------------------------------------------

    suspend fun appendMemory(context: Context, id: String, type: String, content: String, confidence: Double, createdAt: Long) {
        graph(context).memories.add(LegacyImporter.toMemory(id, type, confidence, createdAt, oneLine(content).take(260)))
    }

    suspend fun recentMemories(context: Context, limit: Int): List<MemoryEntity> = graph(context).memories.recent(limit)

    suspend fun purgeNonRokidMemories(context: Context) {
        val g = graph(context)
        g.memories.deleteByIds(g.memories.all().filterNot { it.id.startsWith("rokid_", ignoreCase = true) }.map { it.id })
    }

    suspend fun purgeLowSignalMemories(context: Context) {
        val g = graph(context)
        g.memories.deleteByIds(
            g.memories.all().filter { m ->
                m.id.startsWith("rokid_review_", ignoreCase = true) || LOW_SIGNAL.any { m.text.lowercase().contains(it) }
            }.map { it.id },
        )
    }

    // --- summary traces -----------------------------------------------------------------------------------------

    suspend fun upsertTrace(context: Context, summaryId: String, state: String, note: String, updatedAt: Long = System.currentTimeMillis()) {
        graph(context).traces.upsert(SummaryTraceEntity(summaryId, state, updatedAt, oneLine(note)))
    }

    suspend fun recentTraces(context: Context, limit: Int): List<SummaryTraceEntity> = graph(context).traces.recent(limit)

    suspend fun purgeSyntheticTraces(context: Context) {
        val g = graph(context)
        g.traces.deleteByIds(
            g.traces.recent(Int.MAX_VALUE).filter { it.note.contains("placeholder", ignoreCase = true) || it.summaryId.startsWith("frame_ingest", ignoreCase = true) }
                .map { it.summaryId },
        )
    }
}
```

- [ ] **Step 7: Migrate the callers**

Mechanical substitution table (apply to every remaining call site; `grep -rn "MemoryRecordStore\|FrameStreamStore\|SummaryTraceStore" --include='*.kt' services phone` lists them):

| Old | New |
|---|---|
| `SummaryTraceStore.upsert(ctx, SummaryTrace(summaryId = X, state = Y, updatedAtEpochMs = System.currentTimeMillis(), note = Z))` | `PipelineRecords.upsertTrace(ctx, X, Y, Z)` |
| `SummaryTraceStore.recent(ctx, n)` → `.updatedAtEpochMs` | `PipelineRecords.recentTraces(ctx, n)` → `.updatedAt` |
| `MemoryRecordStore.append(ctx, MemoryRecord(id = A, memoryType = B, content = C, confidence = D, createdAtEpochMs = E))` | `PipelineRecords.appendMemory(ctx, id = A, type = B, content = C, confidence = D, createdAt = E)` |
| `MemoryRecordStore.recent(ctx, n)` → `.memoryType` / `.content` / `.createdAtEpochMs` | `PipelineRecords.recentMemories(ctx, n)` → `.type` / `.text` / `.createdAt` |
| `MemoryRecordStore.purgeLowSignalSummaries(ctx)` / `.purgeNonRokid(ctx)` | `PipelineRecords.purgeLowSignalMemories(ctx)` / `.purgeNonRokidMemories(ctx)` |
| `FrameStreamStore.append(ctx, CapturedFrame(frameId = I, capturedAtEpochMs = T, source = S, payload = P, previewImagePath = F))` | `PipelineRecords.appendFrame(ctx, FrameEntity(id = I, ts = T, path = F, source = S, payload = P))` |
| `FrameStreamStore.recent/all/byIds(ctx, …)` → `CapturedFrame.frameId` / `.capturedAtEpochMs` / `.previewImagePath` | `PipelineRecords.recentFrames/allFrames/framesByIds(ctx, …)` → `FrameEntity.id` / `.ts` / `.path` |
| `FrameStreamStore.purgeNonPhotoAndSynthetic` / `.purgeNonRokidEntries` / `::isRealPhotoFrame` | `PipelineRecords.purgeNonPhotoAndSynthetic` / `.purgeNonRokidFrames` / `::isRealPhotoFrame` |
| `SummaryTraceStore.purgeSynthetic(ctx)` | `PipelineRecords.purgeSyntheticTraces(ctx)` |
| `List<com.rokid.context.data.frames.CapturedFrame>` | `List<com.rokid.context.memory.db.FrameEntity>` |

Every new call is `suspend`: a non-suspend caller becomes `suspend` (below). Never wrap one in `runBlocking`; if Phase 3 code calls one of these from a non-suspend callback, launch it on that class's existing coroutine scope with `Dispatchers.IO`.

File by file (imports: drop `CapturedFrame`, `FrameStreamStore`, `MemoryRecord`, `MemoryRecordStore`, `SummaryTrace`, `SummaryTraceStore`; add `com.rokid.context.orchestration.PipelineRecords` and, where frames are typed, `com.rokid.context.memory.db.FrameEntity`):

1. `work/SummarizerWorkerJob.kt` — the one trace call becomes `PipelineRecords.upsertTrace(applicationContext, summaryId, "ENQUEUED", "Queued for queue-drain summarization")`.
2. `work/CriticWorkerJob.kt` —
   ```kotlin
            val reviews = PipelineRecords.recentMemories(applicationContext, 80).filter { it.type.contains("review", ignoreCase = true) }
   ```
   `sourceContext = reviews.take(5).joinToString("\n") { it.text }`;
   ```kotlin
        val sourceContext = PipelineRecords.recentMemories(applicationContext, 20).joinToString("\n") { it.text }.ifBlank { "no-context" }
   ```
   and the two trace calls → `PipelineRecords.upsertTrace(applicationContext, summaryId, "TRUSTED", "Critic approved summary")` / `(…, "NEEDS_REVIEW", "Critic flagged low confidence")`.
3. `work/QueueDrainWorker.kt` — all 10 trace calls per the table; the 5 memory appends per the table; `private fun processFrameIngest` → `private suspend fun processFrameIngest`; and:
   ```kotlin
        val frames = if (frameIds.isNotEmpty()) {
            PipelineRecords.framesByIds(applicationContext, frameIds).takeLast(12)
        } else {
            PipelineRecords.recentFrames(applicationContext, 10)
        }
        if (frames.isEmpty()) {
            throw IllegalStateException("No Rokid frames available for summarization")
        }

        val frameWindow = frames.joinToString(separator = "\n") {
            "id=${it.id}, ts=${it.ts}, source=${it.source}, payload=${it.payload}"
        }
   ```
   `val frames = PipelineRecords.recentFrames(applicationContext, 6)` in `processPriorityReprocess`;
   ```kotlin
        val trusted = PipelineRecords.recentMemories(applicationContext, 60)
            .filter { !it.type.contains("review", ignoreCase = true) }
   ```
   ```kotlin
        val retrieved = trusted.take(10).joinToString(separator = "\n") {
            "${it.type} | conf=${"%.2f".format(Locale.US, it.confidence)} | ${it.text}"
        }
   ```
   and the helpers:
   ```kotlin
    private suspend fun generateBatchedSummary(prompt: String, frames: List<FrameEntity>): String =
        AiGraph.get(applicationContext).router.generateFromFrames(prompt, images = buildVisionInputs(frames, maxImages = 3))

    private fun buildAttachedImagesLabel(frames: List<FrameEntity>): String {
        val withPreview = frames.count { it.path.isNotBlank() }
        return "$withPreview candidate image(s) in current frame window"
    }
   ```
   with `buildVisionInputs(frames: List<FrameEntity>, maxImages: Int)` reading `val path = frame.path`.
4. `work/OrchestrationScheduler.kt` — `fun enqueueSummarizer(…)` → `suspend fun enqueueSummarizer(…)` with its trace call `PipelineRecords.upsertTrace(context, summaryId, "ENQUEUED", "Queued for summarization")`; `fun enqueueHistoricalPhotoBackfill(…)` → `suspend fun`, body:
   ```kotlin
        val config = AppConfigStore.get(context)
        val purgeResult = PipelineRecords.purgeNonPhotoAndSynthetic(context)
        val selected = PipelineRecords.allFrames(context)
            .filter(PipelineRecords::isRealPhotoFrame)
            .takeLast(maxFrames.coerceAtLeast(1))
   ```
   then `PipelineRecords.purgeLowSignalMemories(context)`, `frameIdsCsv = batch.joinToString(",") { it.id }`, and `PipelineRecords.upsertTrace(context, summaryId, "ENQUEUED", "Historical backfill chunk ${index + 1}/${batches.size}")`; in `runtimeSnapshot` read the traces once:
   ```kotlin
        val traces = PipelineRecords.recentTraces(context, 300)
        val focusId = focusSummarizerId?.toString().orEmpty()
        val focusState = if (focusId.isNotBlank()) traces.firstOrNull { it.summaryId == focusId }?.state ?: "IDLE" else "IDLE"
   ```
   and `summarizerSucceeded = traces.count { … }`, `summarizerFailed = traces.count { … }` (same state sets). `SchedulerDiagnosticsActions` already calls both from `withContext(Dispatchers.IO)` inside `suspend` functions, so the phone compiles unchanged.
5. `data/frames/RokidFrameInbox.kt` (as merged) — `ingestPending`, `ingestSinglePath` and the private `ingestFile` become `suspend`; the append becomes:
   ```kotlin
        val appended = PipelineRecords.appendFrame(
            context,
            FrameEntity(
                id = frameId,
                ts = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                path = file.absolutePath,
                source = source,
                payload = payload,
            ),
        )
   ```
   `FrameIngestWorker.doWork` and `QueueDrainWorker.processFrameIngest` are already `suspend` callers.
6. `phone/.../MainActivity.kt` — the cleanup block becomes:
   ```kotlin
        lifecycleScope.launch(Dispatchers.IO) {
            QueueRuntimeStore.purgeSynthetic(this@MainActivity)
            PipelineRecords.purgeNonRokidFrames(this@MainActivity)
            PipelineRecords.purgeSyntheticTraces(this@MainActivity)
            PipelineRecords.purgeNonRokidMemories(this@MainActivity)
        }
   ```
   (imports: drop the three facades, add `com.rokid.context.orchestration.PipelineRecords`; drop "Task 11 swaps…" from the comment).
7. `services/memory/.../legacy/LegacyImporter.kt` — change the comment `/** Same markers as \`MemoryRecordStore.purgeLowSignalSummaries\`. */` to `/** Same markers as \`PipelineRecords.purgeLowSignalMemories\` (orchestration). */`.

Then delete the facades and their tests:
```bash
git rm services/memory/src/main/java/com/rokid/context/data/runtime/MemoryRecordStore.kt \
  services/orchestration/src/main/java/com/rokid/context/data/frames/FrameStreamStore.kt \
  services/orchestration/src/main/java/com/rokid/context/data/runtime/SummaryTraceStore.kt \
  services/orchestration/src/test/java/com/rokid/context/data/LegacyFacadesTest.kt \
  services/memory/src/test/java/com/rokid/context/data/runtime/MemoryRecordStoreTest.kt
```

- [ ] **Step 8: Run to verify pass**

Run: `./gradlew :services:orchestration:testDebugUnitTest --tests "*PipelineRecordsTest"` then `./gradlew :phone:testDebugUnitTest --tests "*ShellSourceRulesTest"` then:
```bash
grep -rnE "MemoryRecordStore|FrameStreamStore|SummaryTraceStore|CapturedFrame|MemoryRecord\(|SummaryTrace\(" --include='*.kt' services phone glasses || echo "facades gone"
grep -rn "runBlocking" --include='*.kt' services phone | grep "/src/main/"
```
Expected: PASS (10 tests; replaces the 6 `LegacyFacadesTest` + 3 `MemoryRecordStoreTest` cases); `ShellSourceRulesTest` PASS (5 tests); `facades gone`; the `runBlocking` list is only `LegacyConfigBridge.kt` (deleted in Phase 5), `MemoryGraph.kt` (`ensureLegacyImported`) and the `ai-seed` thread in `ContextApp.kt` — all Phase 2. Anything Phase 3 added is recorded in the ledger for its owner, not changed here.

- [ ] **Step 9: Storage folder check**

Run: `grep -rnE "File\((context|ctx|app)?\.?filesDir, \"[a-z_]+\"" --include='*.kt' services phone glasses | grep -v "/test/"`
Expected: frame JPEG folders are only `rokid_inbox` / `rokid_archive`. If Phase 3's ingest writes JPEGs to another `filesDir` folder (for example `"frames"`), add that name to `StorageUsage.FRAME_DIRS` and, in `StorageUsageTest.usedBytesCountsFrameFolders`, write a 256 KiB file there and raise the expected total to `1_835_008L`; rerun `./gradlew :phone:testDebugUnitTest --tests "*StorageUsageTest"` → PASS.

- [ ] **Step 10: Full gate**

Run: `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 11: Device check (read-only, guarded)**

Same guard as Task 10 Step 14 (stop if `com.rokid.context` is resumed; install only otherwise; never launch). After the user opens the app with the glasses connected:
```bash
$ADB -s $S exec-out uiautomator dump /dev/tty 2>/dev/null | grep -o 'text="[^"]*"' | grep -E "Capturing|Paused|Glasses|Connecting|Bluetooth" | head -5
$ADB -s $S logcat -d -t 3000 | grep -E "FATAL EXCEPTION|AndroidRuntime" | grep -A3 com.rokid.context || echo "no crash"
```
Expected: the Home subline shows the live hub state (e.g. `● Capturing · next m:ss`), not the pre-integration "Glasses offline"; `no crash`. Then (read-only) check one capture reached Room through the migrated path: `$ADB -s $S logcat -d -t 3000 | grep -m3 "PipelineRecords: append ok=true"` → at least one line once a frame has been ingested.

- [ ] **Step 12: Commit**

```bash
git add -A android-companion
git commit -m "refactor: wire hub glasses status into the phone shell and delete the legacy store facades

PhoneDeps reads HubGraph.glassesStatus; FakeGlassesStatusSource is test-only. MemoryRecordStore, FrameStreamStore
and SummaryTraceStore are replaced by suspend PipelineRecords calls in the workers, scheduler, inbox and MainActivity.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Phase 4 done when: all tests green, both APKs build, the device shows the shell with live hub status, no polling loop or bridge reference in the phone, and no facade left. Requirements covered: R-07 (polling removal + hub status), R-10, R-11, R-12, R-13, R-14, R-15, R-16 (Trust/Edit + viewer; Forget deferred to R-77), R-17 (interim root), R-20, R-03, R-04 (phone side), R-72.

---

## Self-review (run against the spec while writing; findings fixed inline)

**Spec coverage**

| Spec item | Task |
|---|---|
| §2.1 NavHost, edge-to-edge, onboarding start gate, 3-tab PillNav, hidden on onboarding/viewer, 84 dp padding, Phase 4 routes | 10 (`Routes`, `AppShell`, `RoutesTest`, `AppShellTest`) |
| §2.1 snackbars 96 dp | Not added: no Phase 4 screen raises a snackbar (all messages are inline). The first phase that needs one adds the host at 96 dp. |
| §2.2 banners 1–7 with severity order | 4 (`BannerResolver`); inputs: hub `Health.Blocked` (AI), `LinkState` (disconnected/mismatch), `CaptureState` (deferred/paused) + pipeline switch, `IndexState` (always NONE until Phase 5 sets it), `StorageUsage` |
| §2.3 StatusStrip, ConfidenceChip, FrameThumb, BarChart, app icon | 2 (CodeBox, VersionStrip, HudPreview are Phase 5 per master plan) |
| §2.4 Home header/subline/⏸, hero mic + hint + 3 suggestions, For you feed, phone ask inline | 5 (voice → Phase 6; typed ask interim) |
| §2.4 Ask detail: sources, 👍/👎 (👎 → critic review), proposed action + state | 6 |
| §2.4 Activity: status strip, Today/Week/Month, tiles, timeline Morning/Afternoon/Evening, bar chart, day rows → `day/{date}` | 7 |
| §2.4 Memory detail: frame pinch-zoom, facts, tier, confidence, review state, Trust, Edit, Forget ×2 | 8 (Forget disabled → Phase 7 R-77) |
| §2.4 Frame viewer: full-screen swipe + play/pause, replaces slideshow | 8, 10 (slideshow deleted) |
| §2.4 Settings tree | 9 interim root (Model endpoints + Diagnostics live); full tree Phase 5 (R-17/R-18) |
| §2.4 Onboarding wizard | 10 gate + Welcome; wizard Phase 7 (R-19) |
| §2.5 one ViewModel per screen, stateless screens, Flows from Room, hub `StateFlow` | 3, 5–10 (`ShellSourceRulesTest`) |
| §1.4 remove god-composable, 1.8 s polling loop, stub screens | 10 |
| §1.7 reconnect only on explicit action | 4 (`BannerAction.RECONNECT` → `requestReconnect()`; nothing auto-reconnects) |
| §6.3 no raw errors | 4, 5, 9 (fixed strings; tracker verbatim only under Diagnostics) |
| Master plan: legacy facades deleted in Phase 4, callers on repositories | 11 |
| Contract: `GlassesStatus.kt` byte-identical, `FakeGlassesStatusSource`, post-merge `HubGraph` swap, MainActivity has no `bridge` | 1, 10, 11 |

**Placeholder scan:** no TBD/TODO; every code step has complete code; the only conditional steps (Task 11 Step 1 conflict rules, Step 9 storage folder) give the exact edit for each branch.

**Type consistency (checked):** `TimelineRepository` method names (`framesIn`, `frameTimesIn`, `visibleMemoriesIn`, `visibleMemoryTimesIn`, `recentVisibleMemories`, `memory`, `frame`, `memoriesByIds`, `framesByIds`, `recentTraces`) are used identically in Tasks 5–9; `AskOutcome`/`AskSources`/`AskActions` (Task 5) match their uses in Tasks 6 and 10; `StatusUi.flow(glasses, pipeline, clock, storagePct)` matches Home/Activity; `PhoneDeps` constructor parameters match `AppShellTest` and `Destinations`; `Routes` patterns match `navArgument` names (`id`, `period`, `date`, `start`); `PipelineRecords` signatures match the Task 11 table.

**Review Focus:** each of the five lines has its named test in the owning task (Tasks 2, 4, 5, 6, 7, 8, 9, 10).
