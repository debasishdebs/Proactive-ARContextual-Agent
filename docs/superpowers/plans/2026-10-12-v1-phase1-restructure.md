# Phase 1 — Module Restructure + Core Modules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the build to Live Fit's toolchain and module layout with **no behaviour change**, and add the three shared core modules (`:core:model` protocol, `:core:ui` phone design system, `:core:hud` glasses design system + gesture primitives) that later phases build on.

**Architecture:** `android-companion/` becomes `:phone`, `:glasses`, `:core:model` (JVM), `:core:ui`, `:core:hud`, `:services:ai`, `:services:memory`, `:services:orchestration` (Android libraries). Legacy code moves with `git mv` and keeps its Kotlin packages; the only code edits are (a) replacing app `BuildConfig` reads inside libraries with `EnvDefaults`, initialised by a new `ContextApp`, and (b) decoupling `ModelProviderResolver` from `AppConfig` via `ProviderSettings`.

**Tech Stack:** Gradle 8.13, AGP 8.13.2, Kotlin 2.0.21 (+ compose, serialization, KSP plugins), Compose BOM 2024.09.00, kotlinx-serialization 1.7.3, coroutines 1.9.0, Robolectric 4.13, kotlin-test.

**Spec:** `docs/superpowers/specs/2026-10-12-v1-redesign-design.md` §1.1–1.4, §1.5–1.6 (types), §2.3, §3.3, §3.5. Master plan: `docs/superpowers/plans/2026-10-12-v1-master-plan.md` (Global Constraints apply).

## Global Constraints

- All Global Constraints in the master plan apply.
- Behaviour-neutral phase: the 45 existing unit tests must pass after every task; `:phone:assembleDebug` and `:glasses:assembleDebug` must succeed after every task.
- Run Gradle from `android-companion/` as `ANDROID_HOME=/Users/<user>/Desktop/rokid/android-sdk ./gradlew …` (abbreviated `./gradlew` below).
- Robolectric tests use `@Config(sdk = [34])`.

## Review Focus

1. **Protocol messages from a newer/older app version** — expected: decoding returns `VersionMismatch`, never throws or half-applies (Task 3).
2. **Unknown JSON fields added by a future version** — expected: ignored, message still decodes (Task 3).
3. **Quiet hours spanning midnight (22:00–07:00) and start == end** — expected: wrap handled; equal start/end means "no quiet hours" (Task 3).
4. **Worker started by WorkManager without the Activity ever running** — expected: `EnvDefaults` already initialised by `ContextApp.onCreate`, so providers get the `.env` values (Task 6).
5. **HUD scale outside 0.3–1.0 from a bad settings push** — expected: clamped, never crashes or renders off-screen (Task 4).

---

### Task 1: Toolchain upgrade to Live Fit versions

**Files:**
- Modify: `android-companion/gradle/wrapper/gradle-wrapper.properties`, `android-companion/gradle/wrapper/gradle-wrapper.jar`
- Modify: `android-companion/build.gradle.kts`, `android-companion/settings.gradle.kts`, `android-companion/gradle.properties`
- Modify: `android-companion/app/build.gradle.kts`, `android-companion/glasses-app/build.gradle.kts`

**Interfaces:**
- Produces: plugin ids `com.android.library`, `org.jetbrains.kotlin.jvm`, `org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization`, `com.google.devtools.ksp` available to all modules; Rokid Maven repo configured.

- [ ] **Step 1: Record the baseline**

Run: `cd android-companion && ./gradlew :app:testDebugUnitTest :app:assembleDebug :glasses-app:assembleDebug -q`
Expected: BUILD SUCCESSFUL; 45 tests, 0 failures (count with `grep -h "<testsuite" app/build/test-results/testDebugUnitTest/*.xml`).

- [ ] **Step 2: Gradle 8.13 wrapper**

```bash
cp /Users/<user>/Desktop/rokid/live-fitness-tracker/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/
cp /Users/<user>/Desktop/rokid/live-fitness-tracker/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
grep distributionUrl gradle/wrapper/gradle-wrapper.properties   # expect gradle-8.13-bin.zip
```

- [ ] **Step 3: Root build and settings**

`android-companion/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}
```

In `settings.gradle.kts`, replace the `dependencyResolutionManagement` block with:
```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        google()
        mavenCentral()
    }
}
```

`gradle.properties`:
```properties
android.useAndroidX=true
org.gradle.jvmargs=-Xmx4g
kotlin.code.style=official
```

- [ ] **Step 4: Phone app module build file**

In `app/build.gradle.kts`:
- `plugins {}` → add `id("org.jetbrains.kotlin.plugin.compose")`.
- `compileSdk = 35` → `compileSdk = 36` (keep `targetSdk = 35`).
- Delete the whole `composeOptions { … }` block (the compose compiler plugin replaces it).
- Replace the `dependencies {}` versions:
```kotlin
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
```

In `glasses-app/build.gradle.kts`: `compileSdk = 35` → `compileSdk = 36`; coroutines → `1.9.0`.

- [ ] **Step 5: Verify behaviour-neutral**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug :glasses-app:assembleDebug -q`
Expected: BUILD SUCCESSFUL, 45 tests, 0 failures. If Kotlin 2.0 reports new errors in existing code, fix only those lines (no refactors) and note each fix in the commit message.

- [ ] **Step 6: Commit**

```bash
git add -A android-companion/gradle android-companion/build.gradle.kts android-companion/settings.gradle.kts android-companion/gradle.properties android-companion/app/build.gradle.kts android-companion/glasses-app/build.gradle.kts
git commit -m "build: upgrade to Gradle 8.13, AGP 8.13.2, Kotlin 2.0.21 (Live Fit toolchain)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Rename `:app` → `:phone`, `:glasses-app` → `:glasses`

**Files:**
- Move: `android-companion/app/` → `android-companion/phone/`; `android-companion/glasses-app/` → `android-companion/glasses/`
- Modify: `android-companion/settings.gradle.kts`, `android-companion/glasses/build.gradle.kts`, `README.md`

**Interfaces:**
- Produces: Gradle paths `:phone`, `:glasses` (applicationIds unchanged: `com.rokid.context`, `com.rokid.context.glasses`).

- [ ] **Step 1: Move**

```bash
cd android-companion
git mv app phone
git mv glasses-app glasses
```

- [ ] **Step 2: Settings**

In `settings.gradle.kts` replace `include(":app")` / `include(":glasses-app")` with:
```kotlin
include(":phone", ":glasses")
```

- [ ] **Step 3: Glasses SDK levels (spec §1.1)**

In `glasses/build.gradle.kts`: `minSdk = 29` → `minSdk = 28`; `targetSdk = 35` → `targetSdk = 32`; inside `android {}` add:
```kotlin
    lint { disable += "ExpiredTargetSdkVersion" }
```

- [ ] **Step 4: README paths**

In `README.md`: `android-companion/glasses-app/` → `android-companion/glasses/`; `./gradlew :app:assembleDebug :glasses-app:assembleDebug` → `./gradlew :phone:assembleDebug :glasses:assembleDebug`; `android-companion/app/src/main/assets/prompts/` → `android-companion/phone/src/main/assets/prompts/`; `` `glasses-app` captures `` → `` `glasses` captures ``.

- [ ] **Step 5: Verify**

Run: `./gradlew :phone:testDebugUnitTest :phone:assembleDebug :glasses:assembleDebug -q`
Expected: BUILD SUCCESSFUL, 45 tests pass.
Run: `./gradlew :glasses:compileDebugKotlin --warning-mode=summary 2>&1 | grep -i "requires API level" || echo none`
Expected: `none`. If an API-29-only call appears in glasses code, wrap it in `if (Build.VERSION.SDK_INT >= 29)` with the existing behaviour as the else-branch no-op, and list it in the commit message.

- [ ] **Step 6: Commit**

```bash
git add -A android-companion README.md
git commit -m "build: rename modules to :phone and :glasses; glasses minSdk 28 / targetSdk 32

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `:core:model` — shared CXR protocol

**Files:**
- Create: `android-companion/core/model/build.gradle.kts`
- Create: `android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/Protocol.kt`
- Create: `android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/Wire.kt`
- Test: `android-companion/core/model/src/test/kotlin/com/rokid/context/core/model/ProtocolTest.kt`
- Modify: `android-companion/settings.gradle.kts`

**Interfaces:**
- Produces (exact; Phases 3–7 consume): `PROTOCOL_VERSION: Int`, `Channels.{STATE,SETTINGS,FRAME,FRAME_ACK,CMD,AUDIO,LISTEN_END,HELLO}`, `PauseOrigin`, `PauseRecord(pauseId, origin, createdAtMs, untilMs?)` + `isActiveAt(nowMs)`, `CaptureState.{Running, Paused(records), Deferred(reason), Blocked(reason)}`, `Health.{Ok, Degraded(text), Blocked(text)}`, `NudgeLine(text, atMs)`, `MemoryLine(id, atMs, text)`, `Overlay.{Listening(partial), Answer(question,text,speaking), Nudge(text), Confirm(proposalId,title,detail), PausedSplash, Toast(text)}`, `StateFrame`, `HudPage`, `Verbosity`, `HudPosition`, `Gesture`, `GestureAction`, `GestureDefaults.map`, `QuietHours(startMinute, endMinute)` + `contains(minuteOfDay)`, `HudSettings`, `Command.*`, `CommandEnvelope(v, command)`, `Hello`, `FrameMeta`, `FrameAck`, `Decoded<T>.{Ok, VersionMismatch(remote), Malformed(reason)}`, `Wire.encode(value)`, `Wire.decode<T>(text)`.

- [ ] **Step 1: Module build + settings**

`core/model/build.gradle.kts`:
```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
```
In `settings.gradle.kts` add: `include(":core:model")`.

- [ ] **Step 2: Write the failing test**

`core/model/src/test/kotlin/com/rokid/context/core/model/ProtocolTest.kt`:
```kotlin
package com.rokid.context.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtocolTest {
    private fun frame(overlay: Overlay?) = StateFrame(
        seq = 7,
        capture = CaptureState.Paused(listOf(PauseRecord("p1", PauseOrigin.GLASSES, createdAtMs = 10, untilMs = null))),
        nextCaptureAtMs = 1_000,
        intervalSec = 300,
        glassesBatteryPct = 82,
        phoneLinked = true,
        aiHealth = Health.Degraded("AI slow"),
        queueDepth = 2,
        latestNudge = NudgeLine("Milk almost empty", 99),
        today = listOf(MemoryLine("m1", 5, "Keys on hallway shelf")),
        overlay = overlay,
    )

    @Test fun stateFrameRoundTripsWithEveryOverlay() {
        val overlays = listOf(
            null, Overlay.Listening("what do"), Overlay.Answer("q?", "Milk and eggs", speaking = true), Overlay.Nudge("Heads up"),
            Overlay.Confirm("prop-1", "Add milk?", "Shopping list"), Overlay.PausedSplash, Overlay.Toast("Saved"),
        )
        for (o in overlays) {
            val decoded = Wire.decode<StateFrame>(Wire.encode(frame(o)))
            assertEquals(Decoded.Ok(frame(o)), decoded)
        }
    }

    @Test fun everyCommandRoundTripsInAnEnvelope() {
        val pause = PauseRecord("p2", PauseOrigin.PHONE, 1, 2)
        val commands = listOf(
            Command.Tap, Command.DoubleTap, Command.Swipe(forward = true, long = false), Command.Confirm("prop-1", yes = true),
            Command.Pause(pause), Command.Resume(setOf("p1", "p2")), Command.PageChanged(HudPage.TODAY), Command.ListenStart, Command.Cancel,
        )
        for (c in commands) assertEquals(Decoded.Ok(CommandEnvelope(command = c)), Wire.decode<CommandEnvelope>(Wire.encode(CommandEnvelope(command = c))))
    }

    @Test fun otherVersionIsAMismatchNotAnError() {
        val text = Wire.encode(frame(null)).replace("\"v\":$PROTOCOL_VERSION", "\"v\":${PROTOCOL_VERSION + 1}")
        assertEquals(Decoded.VersionMismatch(PROTOCOL_VERSION + 1), Wire.decode<StateFrame>(text))
    }

    @Test fun missingVersionIsAMismatch() {
        assertEquals(Decoded.VersionMismatch(null), Wire.decode<FrameAck>("""{"frameId":"f","ok":true}"""))
    }

    @Test fun unknownFieldsAreIgnored() {
        val text = Wire.encode(FrameAck(frameId = "f1", ok = true)).replace("{", """{"future":42,""")
        assertEquals(Decoded.Ok(FrameAck(frameId = "f1", ok = true)), Wire.decode<FrameAck>(text))
    }

    @Test fun malformedTextIsReported() {
        assertIs<Decoded.Malformed>(Wire.decode<StateFrame>("not json"))
        assertIs<Decoded.Malformed>(Wire.decode<StateFrame>("""{"v":$PROTOCOL_VERSION,"seq":"x"}"""))
    }

    @Test fun quietHoursWrapPastMidnight() {
        val q = QuietHours(startMinute = 22 * 60, endMinute = 7 * 60)
        assertTrue(q.contains(23 * 60)); assertTrue(q.contains(0)); assertTrue(q.contains(6 * 60 + 59))
        assertFalse(q.contains(7 * 60)); assertFalse(q.contains(12 * 60)); assertTrue(q.contains(22 * 60))
    }

    @Test fun quietHoursSameDayAndEmpty() {
        val q = QuietHours(9 * 60, 17 * 60)
        assertTrue(q.contains(9 * 60)); assertFalse(q.contains(17 * 60)); assertFalse(q.contains(8 * 60))
        assertFalse(QuietHours(600, 600).contains(600), "start == end means no quiet hours")
    }

    @Test fun pauseRecordActivity() {
        assertTrue(PauseRecord("a", PauseOrigin.PHONE, 0, null).isActiveAt(Long.MAX_VALUE))
        assertTrue(PauseRecord("a", PauseOrigin.PHONE, 0, 100).isActiveAt(99))
        assertFalse(PauseRecord("a", PauseOrigin.PHONE, 0, 100).isActiveAt(100))
    }

    @Test fun defaultGesturesMatchSpec() {
        assertEquals(GestureAction.ASK, GestureDefaults.map[Gesture.TAP])
        assertEquals(GestureAction.PAUSE_RESUME, GestureDefaults.map[Gesture.DOUBLE_TAP])
        assertEquals(GestureAction.NEXT_PAGE, GestureDefaults.map[Gesture.SWIPE_FORWARD])
        assertEquals(GestureAction.PREV_PAGE, GestureDefaults.map[Gesture.SWIPE_BACK])
        assertEquals(HudSettings().gestures, GestureDefaults.map)
    }

    @Test fun hudSettingsDefaults() {
        val s = HudSettings()
        assertEquals(Verbosity.MEDIUM, s.verbosity); assertEquals(0.4f, s.scale); assertEquals(HudPosition.BOTTOM_CENTER, s.position)
        assertEquals(Decoded.Ok(s), Wire.decode<HudSettings>(Wire.encode(s)))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :core:model:test`
Expected: FAIL — compilation errors (`Unresolved reference: StateFrame`, …).

- [ ] **Step 4: Implement `Protocol.kt`**

```kotlin
package com.rokid.context.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Bump on any incompatible change to a message below (spec §1.5). */
const val PROTOCOL_VERSION = 1

object Channels {
    const val STATE = "pc_state"
    const val SETTINGS = "pc_settings"
    const val FRAME = "pc_frame"
    const val FRAME_ACK = "pc_frame_ack"
    const val CMD = "pc_cmd"
    const val AUDIO = "pc_audio"
    const val LISTEN_END = "pc_listen_end"
    const val HELLO = "pc_hello"
}

@Serializable enum class PauseOrigin { GLASSES, PHONE, QUIET_HOURS }

/** One reason capture is paused (spec §5.5). Capture is allowed only when no record is active. */
@Serializable
data class PauseRecord(val pauseId: String, val origin: PauseOrigin, val createdAtMs: Long, val untilMs: Long? = null) {
    fun isActiveAt(nowMs: Long): Boolean = untilMs == null || nowMs < untilMs
}

@Serializable
sealed interface CaptureState {
    @Serializable @SerialName("running") data object Running : CaptureState
    @Serializable @SerialName("paused") data class Paused(val records: List<PauseRecord>) : CaptureState
    @Serializable @SerialName("deferred") data class Deferred(val reason: String) : CaptureState
    @Serializable @SerialName("blocked") data class Blocked(val reason: String) : CaptureState
}

@Serializable
sealed interface Health {
    @Serializable @SerialName("ok") data object Ok : Health
    @Serializable @SerialName("degraded") data class Degraded(val text: String) : Health
    @Serializable @SerialName("blocked") data class Blocked(val text: String) : Health
}

@Serializable data class NudgeLine(val text: String, val atMs: Long)
@Serializable data class MemoryLine(val id: String, val atMs: Long, val text: String)

@Serializable
sealed interface Overlay {
    @Serializable @SerialName("listening") data class Listening(val partial: String) : Overlay
    @Serializable @SerialName("answer") data class Answer(val question: String, val text: String, val speaking: Boolean) : Overlay
    @Serializable @SerialName("nudge") data class Nudge(val text: String) : Overlay
    @Serializable @SerialName("confirm") data class Confirm(val proposalId: String, val title: String, val detail: String) : Overlay
    @Serializable @SerialName("paused_splash") data object PausedSplash : Overlay
    @Serializable @SerialName("toast") data class Toast(val text: String) : Overlay
}

/** Full HUD state pushed phone → glasses on `pc_state` (spec §1.6). Short human strings only. */
@Serializable
data class StateFrame(
    val v: Int = PROTOCOL_VERSION,
    val seq: Long,
    val capture: CaptureState,
    val nextCaptureAtMs: Long? = null,
    val intervalSec: Int,
    val glassesBatteryPct: Int? = null,
    val phoneLinked: Boolean,
    val aiHealth: Health,
    val queueDepth: Int = 0,
    val lastSentAtMs: Long? = null,
    val latestNudge: NudgeLine? = null,
    val today: List<MemoryLine> = emptyList(),
    val overlay: Overlay? = null,
    val error: String? = null,
)

@Serializable enum class HudPage { GLANCE, TODAY, STATUS }
@Serializable enum class Verbosity { SILENT, MEDIUM, MAX }

@Serializable
enum class HudPosition { TOP_LEFT, TOP_CENTER, TOP_RIGHT, CENTER_LEFT, CENTER, CENTER_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

/** Long-press is reserved for "Hi Rokid" and vertical swipes are not sensed (spec §3.5), so they are not listed. */
@Serializable enum class Gesture { TAP, DOUBLE_TAP, SWIPE_FORWARD, SWIPE_BACK }
@Serializable enum class GestureAction { ASK, PAUSE_RESUME, NEXT_PAGE, PREV_PAGE, DISMISS, NONE }

object GestureDefaults {
    val map: Map<Gesture, GestureAction> = mapOf(
        Gesture.TAP to GestureAction.ASK,
        Gesture.DOUBLE_TAP to GestureAction.PAUSE_RESUME,
        Gesture.SWIPE_FORWARD to GestureAction.NEXT_PAGE,
        Gesture.SWIPE_BACK to GestureAction.PREV_PAGE,
    )
}

/** Local-time window in minutes after midnight; wraps past midnight when start > end; start == end = none. */
@Serializable
data class QuietHours(val startMinute: Int, val endMinute: Int) {
    fun contains(minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }
}

/** Phone → glasses on `pc_settings`. [pauses] is the merged pause set (spec §5.5). */
@Serializable
data class HudSettings(
    val v: Int = PROTOCOL_VERSION,
    val verbosity: Verbosity = Verbosity.MEDIUM,
    val scale: Float = 0.4f,
    val position: HudPosition = HudPosition.BOTTOM_CENTER,
    val gestures: Map<Gesture, GestureAction> = GestureDefaults.map,
    val captureIntervalSec: Int = 300,
    val burstOnChange: Boolean = true,
    val quietHours: QuietHours? = null,
    val pauses: List<PauseRecord> = emptyList(),
)

@Serializable
sealed interface Command {
    @Serializable @SerialName("tap") data object Tap : Command
    @Serializable @SerialName("double_tap") data object DoubleTap : Command
    @Serializable @SerialName("swipe") data class Swipe(val forward: Boolean, val long: Boolean) : Command
    @Serializable @SerialName("confirm") data class Confirm(val proposalId: String, val yes: Boolean) : Command
    @Serializable @SerialName("pause") data class Pause(val pause: PauseRecord) : Command
    @Serializable @SerialName("resume") data class Resume(val clears: Set<String>) : Command
    @Serializable @SerialName("page_changed") data class PageChanged(val page: HudPage) : Command
    @Serializable @SerialName("listen_start") data object ListenStart : Command
    @Serializable @SerialName("cancel") data object Cancel : Command
}

/** `pc_cmd` payload: commands are wrapped so every message carries [v]. */
@Serializable data class CommandEnvelope(val v: Int = PROTOCOL_VERSION, val command: Command)

/** Glasses → phone on `pc_hello`, sent on every (re)connect. */
@Serializable
data class Hello(
    val v: Int = PROTOCOL_VERSION,
    val glassesBatteryPct: Int?,
    val bufferSize: Int,
    val glassesNowMs: Long,
    val pauses: List<PauseRecord>,
)

/** First Caps string of every `pc_frame` message; the second string is one Base64 chunk (spec §1.8). */
@Serializable
data class FrameMeta(
    val v: Int = PROTOCOL_VERSION,
    val frameId: String,
    val chunkIdx: Int,
    val chunkCount: Int,
    val crc32: Long,
    val capturedAtMs: Long,
    val luma: Int? = null,
    val phash: String? = null,
    val batteryPct: Int? = null,
)

@Serializable data class FrameAck(val v: Int = PROTOCOL_VERSION, val frameId: String, val ok: Boolean, val reason: String? = null)
```

- [ ] **Step 5: Implement `Wire.kt`**

```kotlin
package com.rokid.context.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface Decoded<out T> {
    data class Ok<T>(val value: T) : Decoded<T>
    /** [remote] is the sender's `v`, or null when absent. */
    data class VersionMismatch(val remote: Int?) : Decoded<Nothing>
    data class Malformed(val reason: String) : Decoded<Nothing>
}

object Wire {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "kind"
    }

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)

    /** Checks `v` before decoding so a peer on another version never half-applies a message. */
    inline fun <reified T> decode(text: String): Decoded<T> {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { return Decoded.Malformed("not a JSON object") }
        val v = runCatching { obj["v"]?.jsonPrimitive?.intOrNull }.getOrNull()
        if (v != PROTOCOL_VERSION) return Decoded.VersionMismatch(v)
        return runCatching { Decoded.Ok(json.decodeFromJsonElement<T>(obj)) }
            .getOrElse { Decoded.Malformed(it.message?.take(120) ?: it.javaClass.simpleName) }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :core:model:test`
Expected: PASS (11 tests).

- [ ] **Step 7: Commit**

```bash
git add android-companion/core/model android-companion/settings.gradle.kts
git commit -m "feat(core-model): shared CXR protocol types and versioned wire codec

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `:core:hud` — HUD design primitives + touchpad gestures

**Files:**
- Create: `android-companion/core/hud/build.gradle.kts`, `android-companion/core/hud/src/main/AndroidManifest.xml`
- Create: `android-companion/core/hud/src/main/kotlin/com/rokid/context/core/hud/Hud.kt`
- Create (copied from Live Fit): `.../core/hud/SwipeClassifier.kt`, `.../core/hud/DoubleTapDetector.kt`
- Test (copied): `android-companion/core/hud/src/test/kotlin/com/rokid/context/core/hud/SwipeClassifierTest.kt`, `DoubleTapDetectorTest.kt`
- Test: `android-companion/core/hud/src/test/kotlin/com/rokid/context/core/hud/HudPrimitivesTest.kt`
- Modify: `android-companion/settings.gradle.kts`

**Interfaces:**
- Produces: `Hud.{Green, PRIMARY, SECONDARY, TERTIARY, STROKE, STROKE_HIGHLIGHT}`, `Glyph(icon, size, level)`, `Label(text, size, level, weight, maxLines, modifier, overflow)`, `BoxScope.Scaled(scale, alignment, content)` (scale clamped to 0.3–1.0), `clampHudScale(scale): Float`, `OutlinedBox(modifier, highlighted, radius, content)`, `HudToast(text, modifier)`, `SwipeKey`, `Swipe(forward, long)`, `SwipeClassifier` (`onTouch`, `onKey`, `onTimer`, `deadlineMs`, `CLOSE_MS`, `TWIN_MS`), `DoubleTapDetector` (`onNotificationKey`, `onGestureKey`, `onBack`, `WINDOW_MS`, `SETTLE_MS`).

- [ ] **Step 1: Module build + manifest + settings**

`core/hud/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.rokid.context.core.hud"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.material:material-icons-extended")
    testImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```
`core/hud/src/main/AndroidManifest.xml`: `<manifest />`
`settings.gradle.kts`: add `include(":core:hud")`.

- [ ] **Step 2: Copy the gesture classifiers and their tests from Live Fit**

```bash
LF=/Users/<user>/Desktop/rokid/live-fitness-tracker/glasses/src
HUD=android-companion/core/hud/src
mkdir -p $HUD/main/kotlin/com/rokid/context/core/hud $HUD/test/kotlin/com/rokid/context/core/hud
cp $LF/main/java/com/debasish/livefit/glasses/hud/SwipeClassifier.kt $HUD/main/kotlin/com/rokid/context/core/hud/
cp $LF/test/java/com/debasish/livefit/glasses/hud/SwipeClassifierTest.kt $HUD/test/kotlin/com/rokid/context/core/hud/
# DoubleTap: keep only the detector (lines up to and including DoubleTapDetector's closing brace)
awk '/^enum class DoubleTapAction/{exit} {print}' $LF/main/java/com/debasish/livefit/glasses/hud/DoubleTap.kt > $HUD/main/kotlin/com/rokid/context/core/hud/DoubleTapDetector.kt
awk '/^class CloseConfirmTest/{exit} {print}' $LF/test/java/com/debasish/livefit/glasses/hud/DoubleTapTest.kt > $HUD/test/kotlin/com/rokid/context/core/hud/DoubleTapDetectorTest.kt
cd $HUD
sed -i '' 's/package com.debasish.livefit.glasses.hud/package com.rokid.context.core.hud/; s/import com.debasish.livefit.glasses.hud\./import com.rokid.context.core.hud./' $(find . -name "*.kt")
sed -i '' '/import com.debasish.livefit.model.WorkoutPhase/d' $(find . -name "*.kt")
grep -rn "livefit\|WorkoutPhase\|CloseConfirm" . || echo clean
```
Expected: `clean`. In `DoubleTapDetector.kt`, edit the KDoc's "(spec §6.3)" to "(spec §3.5)".

- [ ] **Step 3: Write the failing primitives test**

`core/hud/src/test/kotlin/com/rokid/context/core/hud/HudPrimitivesTest.kt`:
```kotlin
package com.rokid.context.core.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HudPrimitivesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scaleIsClampedToTheSupportedRange() {
        assertEquals(0.3f, clampHudScale(0.05f))
        assertEquals(1f, clampHudScale(4f))
        assertEquals(0.4f, clampHudScale(0.4f))
        assertEquals(0.4f, clampHudScale(Float.NaN), "NaN falls back to the default")
    }

    @Test fun labelAndToastRenderInsideScaledBox() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Scaled(scale = 9f, alignment = Alignment.BottomCenter) {
                    OutlinedBox(highlighted = true) { Label("NEXT 4:12", 20.sp, Hud.SECONDARY) }
                }
            }
            HudToast("Saved")
        }
        compose.onNodeWithText("NEXT 4:12").assertIsDisplayed()
        compose.onNodeWithText(" Saved").assertIsDisplayed()
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew :core:hud:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: clampHudScale` (the copied classifier tests compile).

- [ ] **Step 5: Implement `Hud.kt`** (tokens/`Glyph`/`Label`/`Scaled` from Live Fit `HudScreen.kt`, made public)

```kotlin
package com.rokid.context.core.hud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Monochrome HUD for the green Micro-LED (spec §3.3). Rules: black = transparent, outlines not fills,
 * hierarchy by brightness (100 / 60 / 35 %), centre kept clear, numbers in tabular digits.
 */
object Hud {
    val Green = Color(0xFF3CFF6E)
    const val PRIMARY = 1f
    const val SECONDARY = 0.6f
    const val TERTIARY = 0.35f
    val STROKE: Dp = 2.dp
    val STROKE_HIGHLIGHT: Dp = 3.dp
    const val MIN_SCALE = 0.3f
    const val MAX_SCALE = 1f
    const val DEFAULT_SCALE = 0.4f
}

fun clampHudScale(scale: Float): Float = if (scale.isNaN()) Hud.DEFAULT_SCALE else scale.coerceIn(Hud.MIN_SCALE, Hud.MAX_SCALE)

@Composable
fun Glyph(icon: ImageVector, size: Dp, level: Float) {
    Image(rememberVectorPainter(icon), contentDescription = null, colorFilter = ColorFilter.tint(Hud.Green), modifier = Modifier.size(size).alpha(level))
}

@Composable
fun Label(
    text: String,
    size: TextUnit,
    level: Float,
    weight: FontWeight = FontWeight.Medium,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        style = TextStyle(color = Hud.Green.copy(alpha = level), fontSize = size, fontWeight = weight, fontFamily = FontFamily.SansSerif, fontFeatureSettings = "tnum"),
    )
}

/** Draws [content] in a box of [scale] × the screen at [alignment], with everything shrunk to match. */
@Composable
fun BoxScope.Scaled(scale: Float, alignment: Alignment, content: @Composable () -> Unit) {
    val s = clampHudScale(scale)
    val base = LocalDensity.current
    Box(Modifier.align(alignment).fillMaxWidth(s).fillMaxHeight(s)) {
        CompositionLocalProvider(LocalDensity provides Density(base.density * s, base.fontScale)) { content() }
    }
}

/** Outlined container: 2 dp at 60 % normally, 3 dp at 100 % when [highlighted]. */
@Composable
fun OutlinedBox(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    radius: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .border(if (highlighted) Hud.STROKE_HIGHLIGHT else Hud.STROKE, Hud.Green.copy(alpha = if (highlighted) Hud.PRIMARY else Hud.SECONDARY), RoundedCornerShape(radius))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
fun HudToast(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color.Black).border(Hud.STROKE, Hud.Green.copy(alpha = Hud.SECONDARY), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Icons.Outlined.Check, 28.dp, Hud.PRIMARY)
        Label(" $text", 26.sp, Hud.PRIMARY, FontWeight.Bold)
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :core:hud:testDebugUnitTest`
Expected: PASS (SwipeClassifierTest + DoubleTapDetectorTest + 2 new).

- [ ] **Step 7: Commit**

```bash
git add android-companion/core/hud android-companion/settings.gradle.kts
git commit -m "feat(core-hud): HUD tokens and primitives, ported swipe/double-tap classifiers

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `:core:ui` — phone design system

**Files:**
- Create: `android-companion/core/ui/build.gradle.kts`, `android-companion/core/ui/src/main/AndroidManifest.xml`
- Create: `.../core/ui/src/main/kotlin/com/rokid/context/core/ui/theme/PcTheme.kt`
- Create: `.../core/ui/src/main/kotlin/com/rokid/context/core/ui/components/Basics.kt`
- Create: `.../core/ui/src/main/kotlin/com/rokid/context/core/ui/components/Navigation.kt`
- Create: `.../core/ui/src/main/kotlin/com/rokid/context/core/ui/components/Controls.kt`
- Create: `.../core/ui/src/main/kotlin/com/rokid/context/core/ui/components/Tiles.kt`
- Test: `android-companion/core/ui/src/test/kotlin/com/rokid/context/core/ui/ComponentsTest.kt`
- Modify: `android-companion/settings.gradle.kts`

**Interfaces:**
- Produces: `PcColors` (all tokens incl. `Chip*` pairs `Pair<Color, Color>`, `HeaderGradient`, `PrimaryGradient`), `PcTheme(content)`, `IconChip(icon, colors, size, shapeRadius)`, `TextChip(text, colors, size)`, `SoftCard(modifier, onClick, content)`, `ScreenHeader(title, onBack, actions)`, `SectionLabel(text)`, `ChipRow(icon, colors, title, subtitle, onClick, trailing, enabled)`, `PillNavItem(route, label, icon)`, `PillNav(items, selectedRoute, onSelect, modifier)`, `SegmentedSwitch(options, selectedIndex, onSelect, modifier)`, `GradientButton(text, onClick, modifier, enabled)`, `GradientCircleButton(icon, contentDescription, onClick, modifier, size, enabled)`, `MetricTile(icon, colors, value, label, modifier, unit, onClick)`, `GradientHeader(title, subtitle, modifier, actions)`, `BannerTone.{CORAL, AMBER, SLATE, SKY}`, `Banner(tone, title, modifier, actionLabel, onAction)`.

- [ ] **Step 1: Module build + manifest + settings**

`core/ui/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.rokid.context.core.ui"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-extended")
    testImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```
`core/ui/src/main/AndroidManifest.xml`: `<manifest />`; `settings.gradle.kts`: add `include(":core:ui")`.

- [ ] **Step 2: Write the failing test**

`core/ui/src/test/kotlin/com/rokid/context/core/ui/ComponentsTest.kt`:
```kotlin
package com.rokid.context.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.components.Banner
import com.rokid.context.core.ui.components.BannerTone
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.components.GradientButton
import com.rokid.context.core.ui.components.PillNav
import com.rokid.context.core.ui.components.PillNavItem
import com.rokid.context.core.ui.components.SegmentedSwitch
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ComponentsTest {
    @get:Rule val compose = createComposeRule()
    private val tabs = listOf(
        PillNavItem("home", "Home", Icons.Outlined.Home),
        PillNavItem("activity", "Activity", Icons.Outlined.Timeline),
        PillNavItem("settings", "Settings", Icons.Outlined.Settings),
    )

    @Test fun pillNavReportsTheTappedRouteAndMarksSelection() {
        var picked: String? = null
        compose.setContent { PcTheme { PillNav(tabs, selectedRoute = "home", onSelect = { picked = it }) } }
        compose.onNodeWithText("Home").assertIsSelected()
        compose.onNodeWithText("Activity").assertIsNotSelected()
        compose.onNodeWithText("Activity").performClick()
        assertEquals("activity", picked)
    }

    @Test fun segmentedSwitchReportsIndex() {
        var picked = -1
        compose.setContent { PcTheme { SegmentedSwitch(listOf("Today", "Week", "Month"), selectedIndex = 0, onSelect = { picked = it }) } }
        compose.onNodeWithText("Today").assertIsSelected()
        compose.onNodeWithText("Week").performClick()
        assertEquals(1, picked)
    }

    @Test fun disabledControlsIgnoreTaps() {
        var rowTaps = 0; var buttonTaps = 0
        compose.setContent {
            PcTheme {
                ChipRow(Icons.Outlined.Settings, PcColors.ChipSlate, "Diagnostics", onClick = { rowTaps++ }, enabled = false)
                GradientButton("Save", onClick = { buttonTaps++ }, enabled = false)
            }
        }
        compose.onNodeWithText("Diagnostics").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(0, rowTaps); assertEquals(0, buttonTaps)
    }

    @Test fun bannerActionFires() {
        var fixed = false
        compose.setContent { PcTheme { Banner(BannerTone.CORAL, "AI key rejected", actionLabel = "Fix now", onAction = { fixed = true }) } }
        compose.onNodeWithText("Fix now").performClick()
        assertEquals(true, fixed)
    }

    @Test fun tokensMatchSpec() {
        assertEquals(0xFF14C3A2.toInt(), PcColors.Mint.toArgb())
        assertEquals(0xFF3D8BFF.toInt(), PcColors.Sky.toArgb())
        assertEquals(0xFF15181D.toInt(), PcColors.Ink.toArgb())
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :core:ui:testDebugUnitTest`
Expected: FAIL — unresolved references.

- [ ] **Step 4: `PcTheme.kt`** (Live Fit `Theme.kt` with names changed)

```kotlin
package com.rokid.context.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Live Fit palette (spec §2.3): white surfaces, mint/sky accents, pastel icon chips. Light only. */
object PcColors {
    val Mint = Color(0xFF14C3A2)
    val MintDeep = Color(0xFF0E9F86)
    val Sky = Color(0xFF3D8BFF)
    val Ink = Color(0xFF15181D)
    val InkSoft = Color(0xFF6B7280)
    val Line = Color(0xFFECEFF3)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceSoft = Color(0xFFF6F8FA)

    // Pastel chip pairs (background, foreground).
    val ChipMint = Color(0xFFE3F8F3) to Color(0xFF0E9F86)
    val ChipSky = Color(0xFFE6F0FF) to Color(0xFF2F6FE0)
    val ChipCoral = Color(0xFFFFEAE5) to Color(0xFFE5583B)
    val ChipAmber = Color(0xFFFFF4DC) to Color(0xFFD08A00)
    val ChipViolet = Color(0xFFF0EAFF) to Color(0xFF7550E0)
    val ChipRose = Color(0xFFFFE8F1) to Color(0xFFD63F7A)
    val ChipSlate = Color(0xFFEEF1F5) to Color(0xFF55606E)

    val HeaderGradient = Brush.verticalGradient(listOf(Color(0xFFDDF7F0), Color(0xFFE8F1FF), Color(0xFFFFFFFF)))
    val PrimaryGradient = Brush.linearGradient(listOf(Mint, Sky))
}

private val colors = lightColorScheme(
    primary = PcColors.Mint,
    onPrimary = Color.White,
    primaryContainer = PcColors.ChipMint.first,
    onPrimaryContainer = PcColors.MintDeep,
    secondary = PcColors.Sky,
    background = PcColors.Surface,
    onBackground = PcColors.Ink,
    surface = PcColors.Surface,
    onSurface = PcColors.Ink,
    surfaceVariant = PcColors.SurfaceSoft,
    onSurfaceVariant = PcColors.InkSoft,
    outline = PcColors.Line,
    outlineVariant = PcColors.Line,
)

private val typography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

private val shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp))

@Composable
fun PcTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
```

- [ ] **Step 5: `Basics.kt`** (Live Fit `Components.kt`, renamed)

```bash
cp /Users/<user>/Desktop/rokid/live-fitness-tracker/phone/src/main/java/com/debasish/livefit/phone/ui/components/Components.kt \
   android-companion/core/ui/src/main/kotlin/com/rokid/context/core/ui/components/Basics.kt
cd android-companion/core/ui/src/main/kotlin/com/rokid/context/core/ui/components
sed -i '' 's/package com.debasish.livefit.phone.ui.components/package com.rokid.context.core.ui.components/; s/import com.debasish.livefit.phone.ui.theme.LiveFitColors/import com.rokid.context.core.ui.theme.PcColors/; s/LiveFitColors/PcColors/g' Basics.kt
grep -n "livefit\|LiveFit" Basics.kt || echo clean
```
Expected: `clean`. Then make `ChipRow` expose selection-free semantics unchanged (no edit needed).

- [ ] **Step 6: `Navigation.kt`**

```kotlin
package com.rokid.context.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.theme.PcColors

data class PillNavItem(val route: String, val label: String, val icon: ImageVector)

/** Floating white pill with a soft shadow; the selected tab sits on a mint chip (Live Fit `PillNav`). */
@Composable
fun PillNav(items: List<PillNavItem>, selectedRoute: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val pill = RoundedCornerShape(32.dp)
    Row(
        modifier.shadow(16.dp, pill).clip(pill).background(PcColors.Surface).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val isSelected = item.route == selectedRoute
            val tint = if (isSelected) PcColors.MintDeep else PcColors.ChipSlate.second
            Column(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isSelected) PcColors.ChipMint.first else Color.Transparent)
                    .clickable { onSelect(item.route) }
                    .semantics(mergeDescendants = true) { selected = isSelected; role = Role.Tab }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                Text(item.label, style = MaterialTheme.typography.labelMedium, color = tint)
            }
        }
    }
}
```

- [ ] **Step 7: `Controls.kt`**

```kotlin
package com.rokid.context.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.theme.PcColors

/** Soft segmented control (Today / Week / Month, Silent / Medium / Max, Lexical / Semantic / Hybrid). */
@Composable
fun SegmentedSwitch(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PcColors.SurfaceSoft).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val isSelected = i == selectedIndex
            Box(
                Modifier.weight(1f)
                    .then(if (isSelected) Modifier.shadow(1.dp, RoundedCornerShape(11.dp)) else Modifier)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (isSelected) PcColors.Surface else Color.Transparent)
                    .clickable { onSelect(i) }
                    .semantics(mergeDescendants = true) { selected = isSelected; role = Role.Tab }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) PcColors.Ink else PcColors.InkSoft,
                )
            }
        }
    }
}

/** Primary action: Mint → Sky gradient pill. */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.clip(RoundedCornerShape(28.dp)).background(PcColors.PrimaryGradient).alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White) }
}

/** Round gradient hero button (Home mic, Pause/Resume); mint-tinted shadow like Live Fit `StartHero`. */
@Composable
fun GradientCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 84.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier.size(size).shadow(14.dp, CircleShape, ambientColor = PcColors.Mint, spotColor = PcColors.Mint).clip(CircleShape)
            .background(PcColors.PrimaryGradient).alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(size * 0.42f)) }
}
```

- [ ] **Step 8: `Tiles.kt`**

```kotlin
package com.rokid.context.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.context.core.ui.theme.PcColors

/** SoftCard with a 36 dp chip, a big value, optional unit and a label (Live Fit `MetricTile`). */
@Composable
fun MetricTile(
    icon: ImageVector,
    colors: Pair<Color, Color>,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    onClick: (() -> Unit)? = null,
) {
    SoftCard(modifier, onClick) {
        Column(Modifier.padding(14.dp)) {
            IconChip(icon, colors, size = 36.dp, shapeRadius = 10.dp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PcColors.Ink)
                if (unit != null) { Spacer(Modifier.width(4.dp)); Text(unit, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft) }
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
        }
    }
}

/** Top-of-screen gradient header with a title, subline and trailing actions. */
@Composable
fun GradientHeader(title: String, subtitle: String?, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().background(PcColors.HeaderGradient).statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = PcColors.Ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

enum class BannerTone(val colors: Pair<Color, Color>) {
    CORAL(PcColors.ChipCoral), AMBER(PcColors.ChipAmber), SLATE(PcColors.ChipSlate), SKY(PcColors.ChipSky)
}

/** One-line status banner with an optional action (spec §2.2). */
@Composable
fun Banner(tone: BannerTone, title: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tone.colors.first).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = tone.colors.second, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel, color = tone.colors.second, fontWeight = FontWeight.SemiBold) }
        }
    }
}
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `./gradlew :core:ui:testDebugUnitTest`
Expected: PASS (5 tests).

- [ ] **Step 10: Commit**

```bash
git add android-companion/core/ui android-companion/settings.gradle.kts
git commit -m "feat(core-ui): Live Fit design tokens and phone components

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `:services:ai` — move providers + prompts; `EnvDefaults`, `ProviderSettings`, `ContextApp`

**Files:**
- Create: `android-companion/services/ai/build.gradle.kts`, `.../services/ai/src/main/AndroidManifest.xml`
- Move: `phone/src/main/java/com/rokid/context/{model,prompt}` → `services/ai/src/main/java/com/rokid/context/{model,prompt}`; `phone/src/test/java/com/rokid/context/{model,prompt}` → `services/ai/src/test/java/com/rokid/context/{model,prompt}`
- Create: `services/ai/src/main/java/com/rokid/context/ai/EnvDefaults.kt`, `.../ai/ProviderSettings.kt`
- Modify: `services/ai/src/main/java/com/rokid/context/model/ModelProviderResolver.kt`, `.../provider/OpenAiCompatProvider.kt`, `.../provider/OllamaProvider.kt`, `.../provider/LocalProviderSelector.kt`
- Modify: `phone/src/main/java/com/rokid/context/data/runtime/AppConfigStore.kt`, `phone/src/main/java/com/rokid/context/work/QueueDrainWorker.kt`, `phone/src/main/java/com/rokid/context/MainActivity.kt`, `phone/build.gradle.kts`, `phone/src/main/AndroidManifest.xml`
- Create: `phone/src/main/java/com/rokid/context/ContextApp.kt`
- Test: `services/ai/src/test/java/com/rokid/context/model/ModelProviderResolverTest.kt`
- Modify: `android-companion/settings.gradle.kts`

**Interfaces:**
- Produces: `EnvDefaults` (`azureEndpoint`, `azureApiKey`, `azureDeployment`, `ollamaBaseUrl`, `ollamaModel`, `localRuntimeMode`, `localNativeModelPath`; `fun init(...)`), `ProviderSettings(providerType, endpoint, apiKey, deploymentOrModel, nativeModelPath, localModeEnabled)`, `ModelProviderResolver.primary(settings: ProviderSettings)`, `ModelProviderResolver.localFallback(settings: ProviderSettings)`, `AppConfig.toProviderSettings()` (in `com.rokid.context.data.runtime`), `ContextApp` (Application).

- [ ] **Step 1: Module build + manifest + settings**

`services/ai/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rokid.context.services.ai"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
```
`services/ai/src/main/AndroidManifest.xml`: `<manifest />`; `settings.gradle.kts`: add `include(":services:ai")`.
In `phone/build.gradle.kts` dependencies add `implementation(project(":services:ai"))`.

- [ ] **Step 2: Move sources and tests**

```bash
cd android-companion
mkdir -p services/ai/src/main/java/com/rokid/context services/ai/src/test/java/com/rokid/context
git mv phone/src/main/java/com/rokid/context/model services/ai/src/main/java/com/rokid/context/model
git mv phone/src/main/java/com/rokid/context/prompt services/ai/src/main/java/com/rokid/context/prompt
git mv phone/src/test/java/com/rokid/context/model services/ai/src/test/java/com/rokid/context/model
git mv phone/src/test/java/com/rokid/context/prompt services/ai/src/test/java/com/rokid/context/prompt
```

- [ ] **Step 3: Write the failing test**

`services/ai/src/test/java/com/rokid/context/model/ModelProviderResolverTest.kt`:
```kotlin
package com.rokid.context.model

import com.rokid.context.ai.EnvDefaults
import com.rokid.context.ai.ProviderSettings
import com.rokid.context.model.provider.LlamaCppNativeProvider
import com.rokid.context.model.provider.OllamaProvider
import com.rokid.context.model.provider.OpenAiCompatProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProviderResolverTest {
    private fun s(type: String, endpoint: String = "https://x.example", local: Boolean = false) =
        ProviderSettings(providerType = type, endpoint = endpoint, apiKey = "k", deploymentOrModel = "m", nativeModelPath = "", localModeEnabled = local)

    @Test fun picksProviderByType() {
        assertTrue(ModelProviderResolver.primary(s("openai")) is OpenAiCompatProvider)
        assertTrue(ModelProviderResolver.primary(s("OLLAMA")) is OllamaProvider)
        assertTrue(ModelProviderResolver.primary(s("llama_cpp")) is LlamaCppNativeProvider)
        assertTrue(ModelProviderResolver.primary(s("unknown")) is OpenAiCompatProvider)
    }

    @Test fun noLocalFallbackWhenLocalModeOff() = assertNull(ModelProviderResolver.localFallback(s("ollama", local = false)))

    @Test fun ollamaFallbackOnlyWhenExplicitlyOllama() {
        assertTrue(ModelProviderResolver.localFallback(s("ollama", "http://10.0.2.2:11434", local = true)) is OllamaProvider)
        assertNull(ModelProviderResolver.localFallback(s("openai", local = true)))
    }

    @Test fun envDefaultsMatchGradleDefaultsUntilInitialised() {
        assertEquals("gpt-5.4", EnvDefaults.azureDeployment)
        assertEquals("http://10.0.2.2:11434", EnvDefaults.ollamaBaseUrl)
        assertEquals("llama3.1:8b", EnvDefaults.ollamaModel)
        assertEquals("native_first", EnvDefaults.localRuntimeMode)
    }
}
```

- [ ] **Step 4: Run to verify failure**

Run: `./gradlew :services:ai:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: BuildConfig` in providers and `EnvDefaults`/`ProviderSettings` unresolved.

- [ ] **Step 5: Add `EnvDefaults` and `ProviderSettings`**

`services/ai/src/main/java/com/rokid/context/ai/EnvDefaults.kt`:
```kotlin
package com.rokid.context.ai

/**
 * `.env`-derived build defaults. Libraries cannot read the app's BuildConfig, so `ContextApp.onCreate` calls [init]
 * before any provider or worker runs. Initial values equal the Gradle fallbacks in `phone/build.gradle.kts`.
 */
object EnvDefaults {
    @Volatile var azureEndpoint: String = ""; private set
    @Volatile var azureApiKey: String = ""; private set
    @Volatile var azureDeployment: String = "gpt-5.4"; private set
    @Volatile var ollamaBaseUrl: String = "http://10.0.2.2:11434"; private set
    @Volatile var ollamaModel: String = "llama3.1:8b"; private set
    @Volatile var localRuntimeMode: String = "native_first"; private set
    @Volatile var localNativeModelPath: String = ""; private set

    fun init(
        azureEndpoint: String,
        azureApiKey: String,
        azureDeployment: String,
        ollamaBaseUrl: String,
        ollamaModel: String,
        localRuntimeMode: String,
        localNativeModelPath: String,
    ) {
        this.azureEndpoint = azureEndpoint
        this.azureApiKey = azureApiKey
        this.azureDeployment = azureDeployment
        this.ollamaBaseUrl = ollamaBaseUrl
        this.ollamaModel = ollamaModel
        this.localRuntimeMode = localRuntimeMode
        this.localNativeModelPath = localNativeModelPath
    }
}
```

`services/ai/src/main/java/com/rokid/context/ai/ProviderSettings.kt`:
```kotlin
package com.rokid.context.ai

/** Provider-relevant subset of the legacy AppConfig; replaced by the endpoint registry in Phase 2. */
data class ProviderSettings(
    val providerType: String,
    val endpoint: String,
    val apiKey: String,
    val deploymentOrModel: String,
    val nativeModelPath: String,
    val localModeEnabled: Boolean,
)
```

- [ ] **Step 6: Replace `BuildConfig` in the moved providers; retarget the resolver**

```bash
cd android-companion/services/ai/src/main/java/com/rokid/context/model
sed -i '' 's/^import com.rokid.context.BuildConfig$/import com.rokid.context.ai.EnvDefaults/' provider/OpenAiCompatProvider.kt provider/OllamaProvider.kt provider/LocalProviderSelector.kt
sed -i '' 's/BuildConfig.AZURE_KG_ENDPOINT/EnvDefaults.azureEndpoint/; s/BuildConfig.AZURE_KG_API_KEY/EnvDefaults.azureApiKey/; s/BuildConfig.AZURE_KG_DEPLOYMENT/EnvDefaults.azureDeployment/; s/BuildConfig.OLLAMA_BASE_URL/EnvDefaults.ollamaBaseUrl/; s/BuildConfig.OLLAMA_MODEL/EnvDefaults.ollamaModel/; s/BuildConfig.LOCAL_RUNTIME_MODE/EnvDefaults.localRuntimeMode/; s/BuildConfig.LOCAL_NATIVE_MODEL_PATH/EnvDefaults.localNativeModelPath/' provider/*.kt
grep -rn "BuildConfig" . || echo clean
```
Expected: `clean`.

In `ModelProviderResolver.kt`: replace `import com.rokid.context.data.runtime.AppConfig` with `import com.rokid.context.ai.ProviderSettings`, and both signatures `config: AppConfig` with `config: ProviderSettings` (bodies unchanged — the field names are identical).

- [ ] **Step 7: Bridge the phone code**

Append to `phone/src/main/java/com/rokid/context/data/runtime/AppConfigStore.kt`:
```kotlin
fun AppConfig.toProviderSettings(): com.rokid.context.ai.ProviderSettings = com.rokid.context.ai.ProviderSettings(
    providerType = providerType,
    endpoint = endpoint,
    apiKey = apiKey,
    deploymentOrModel = deploymentOrModel,
    nativeModelPath = nativeModelPath,
    localModeEnabled = localModeEnabled,
)
```
In `phone/src/main/java/com/rokid/context/work/QueueDrainWorker.kt`: add `import com.rokid.context.data.runtime.toProviderSettings`; replace each `ModelProviderResolver.primary(config)` with `ModelProviderResolver.primary(config.toProviderSettings())` and `ModelProviderResolver.localFallback(config)` with `ModelProviderResolver.localFallback(config.toProviderSettings())` (3 call sites, lines ~384, ~388, ~403).
In `phone/src/main/java/com/rokid/context/MainActivity.kt` inside `onTestConnectivity`: the `AppConfig(...)` argument passed to `ModelProviderResolver.primary(` — append `.toProviderSettings()` after its closing parenthesis, and add `import com.rokid.context.data.runtime.toProviderSettings`.

- [ ] **Step 8: `ContextApp` initialises `EnvDefaults` before anything runs**

`phone/src/main/java/com/rokid/context/ContextApp.kt`:
```kotlin
package com.rokid.context

import android.app.Application
import com.rokid.context.ai.EnvDefaults

class ContextApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EnvDefaults.init(
            azureEndpoint = BuildConfig.AZURE_KG_ENDPOINT,
            azureApiKey = BuildConfig.AZURE_KG_API_KEY,
            azureDeployment = BuildConfig.AZURE_KG_DEPLOYMENT,
            ollamaBaseUrl = BuildConfig.OLLAMA_BASE_URL,
            ollamaModel = BuildConfig.OLLAMA_MODEL,
            localRuntimeMode = BuildConfig.LOCAL_RUNTIME_MODE,
            localNativeModelPath = BuildConfig.LOCAL_NATIVE_MODEL_PATH,
        )
    }
}
```
In `phone/src/main/AndroidManifest.xml`, add `android:name=".ContextApp"` to the `<application` tag.

- [ ] **Step 9: Run tests and builds**

Run: `./gradlew :services:ai:testDebugUnitTest :phone:testDebugUnitTest :phone:assembleDebug -q`
Expected: PASS. Test counts: `:services:ai` = moved (LocalProviderSelectorTest 3, ModelSlotManagerTest 3, PromptComposerTest 2) + 4 new; `:phone` = remaining 37. Total legacy still 45.

- [ ] **Step 10: Commit**

```bash
git add -A android-companion
git commit -m "refactor(ai): move providers and prompts to :services:ai; EnvDefaults + ProviderSettings

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: `:services:memory` — move memory store + retrieval

**Files:**
- Create: `android-companion/services/memory/build.gradle.kts`, `.../src/main/AndroidManifest.xml`
- Move: `phone/src/main/java/com/rokid/context/data/runtime/MemoryRecordStore.kt` → `services/memory/src/main/java/com/rokid/context/data/runtime/MemoryRecordStore.kt`; `phone/src/main/java/com/rokid/context/retrieval/` → `services/memory/src/main/java/com/rokid/context/retrieval/`; `phone/src/test/java/com/rokid/context/retrieval/` → `services/memory/src/test/java/com/rokid/context/retrieval/`
- Modify: `android-companion/settings.gradle.kts`, `phone/build.gradle.kts`
- Test: `services/memory/src/test/java/com/rokid/context/data/runtime/MemoryRecordStoreTest.kt`

**Interfaces:**
- Produces: module `:services:memory` (namespace `com.rokid.context.services.memory`); `MemoryRecordStore`, `MemoryRecord` unchanged API.

- [ ] **Step 1: Module build + manifest + settings**

`services/memory/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rokid.context.services.memory"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
```
Manifest `<manifest />`; settings `include(":services:memory")`; phone deps `implementation(project(":services:memory"))`.

- [ ] **Step 2: Move**

```bash
cd android-companion
mkdir -p services/memory/src/main/java/com/rokid/context/data/runtime services/memory/src/test/java/com/rokid/context/data/runtime
git mv phone/src/main/java/com/rokid/context/data/runtime/MemoryRecordStore.kt services/memory/src/main/java/com/rokid/context/data/runtime/
git mv phone/src/main/java/com/rokid/context/retrieval services/memory/src/main/java/com/rokid/context/retrieval
git mv phone/src/test/java/com/rokid/context/retrieval services/memory/src/test/java/com/rokid/context/retrieval
```

- [ ] **Step 3: Characterisation test for the legacy store (pins behaviour Phase 2 must keep)**

`services/memory/src/test/java/com/rokid/context/data/runtime/MemoryRecordStoreTest.kt`:
```kotlin
package com.rokid.context.data.runtime

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoryRecordStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun recentIsNewestFirstAndSanitisesContent() {
        MemoryRecordStore.append(ctx, MemoryRecord("rokid_mem_1", "short_term", "a\tb\nc", 0.9, 1))
        MemoryRecordStore.append(ctx, MemoryRecord("rokid_mem_2", "short_term", "second", 0.5, 2))
        val r = MemoryRecordStore.recent(ctx, 10)
        assertEquals(listOf("rokid_mem_2", "rokid_mem_1"), r.map { it.id })
        assertEquals("a b c", r[1].content)
    }

    @Test fun purgeNonRokidKeepsOnlyRokidIds() {
        MemoryRecordStore.append(ctx, MemoryRecord("other_1", "short_term", "x", 0.1, 1))
        MemoryRecordStore.append(ctx, MemoryRecord("rokid_mem_3", "short_term", "y", 0.1, 2))
        MemoryRecordStore.purgeNonRokid(ctx)
        assertEquals(listOf("rokid_mem_3"), MemoryRecordStore.recent(ctx, 10).map { it.id })
    }

    @Test fun purgeLowSignalDropsReviewsAndMarkers() {
        MemoryRecordStore.append(ctx, MemoryRecord("rokid_review_1", "review", "needs review", 0.1, 1))
        MemoryRecordStore.append(ctx, MemoryRecord("rokid_mem_4", "short_term", "Metadata only frame", 0.1, 2))
        MemoryRecordStore.append(ctx, MemoryRecord("rokid_mem_5", "short_term", "Milk carton nearly empty", 0.8, 3))
        MemoryRecordStore.purgeLowSignalSummaries(ctx)
        assertEquals(listOf("rokid_mem_5"), MemoryRecordStore.recent(ctx, 10).map { it.id })
    }
}
```

- [ ] **Step 4: Run tests and builds**

Run: `./gradlew :services:memory:testDebugUnitTest :phone:testDebugUnitTest :phone:assembleDebug -q`
Expected: PASS (memory: LanguageRouterTest 3 + 3 new).

- [ ] **Step 5: Commit**

```bash
git add -A android-companion
git commit -m "refactor(memory): move memory store and retrieval to :services:memory; pin legacy behaviour

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: `:services:orchestration` — move domain, workers, data, telemetry, security

**Files:**
- Create: `android-companion/services/orchestration/build.gradle.kts`, `.../src/main/AndroidManifest.xml`
- Move (main): `phone/src/main/java/com/rokid/context/{domain,work,telemetry,security}` and `phone/src/main/java/com/rokid/context/data/{db,frames}` and the remaining `phone/src/main/java/com/rokid/context/data/runtime/*` → same paths under `services/orchestration/src/main/java/com/rokid/context/`
- Move (test): `phone/src/test/java/com/rokid/context/{data,domain,security,telemetry}` → `services/orchestration/src/test/java/com/rokid/context/`
- Modify: moved `data/runtime/AppConfigStore.kt` (BuildConfig → EnvDefaults), `android-companion/settings.gradle.kts`, `phone/build.gradle.kts`
- Test: `services/orchestration/src/test/java/com/rokid/context/data/runtime/AppConfigDefaultsTest.kt`

**Interfaces:**
- Produces: module `:services:orchestration` (namespace `com.rokid.context.services.orchestration`) depending on `:services:ai`, `:services:memory`; `:phone` keeps only `MainActivity.kt`, `ContextApp.kt`, `bridge/`, `ui/`, `OnboardingFlowTest`.

- [ ] **Step 1: Module build + manifest + settings**

`services/orchestration/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rokid.context.services.orchestration"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":services:ai"))
    api(project(":services:memory"))
    api("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
```
Manifest `<manifest />`; settings `include(":services:orchestration")`; phone deps `implementation(project(":services:orchestration"))`.

- [ ] **Step 2: Move**

```bash
cd android-companion
O=services/orchestration/src
mkdir -p $O/main/java/com/rokid/context/data $O/test/java/com/rokid/context
for d in domain work telemetry security; do git mv phone/src/main/java/com/rokid/context/$d $O/main/java/com/rokid/context/$d; done
git mv phone/src/main/java/com/rokid/context/data/db $O/main/java/com/rokid/context/data/db
git mv phone/src/main/java/com/rokid/context/data/frames $O/main/java/com/rokid/context/data/frames
git mv phone/src/main/java/com/rokid/context/data/runtime $O/main/java/com/rokid/context/data/runtime
for d in data domain security telemetry; do git mv phone/src/test/java/com/rokid/context/$d $O/test/java/com/rokid/context/$d; done
ls phone/src/main/java/com/rokid/context   # expect: ContextApp.kt MainActivity.kt bridge ui
```

- [ ] **Step 3: Write the failing defaults test**

`services/orchestration/src/test/java/com/rokid/context/data/runtime/AppConfigDefaultsTest.kt`:
```kotlin
package com.rokid.context.data.runtime

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.EnvDefaults
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppConfigDefaultsTest {
    @Test fun unsetPrefsFallBackToEnvDefaults() {
        EnvDefaults.init("https://az.example", "key-1", "gpt-x", "http://o:11434", "llama", "ollama_only", "/m.gguf")
        val c = AppConfigStore.get(ApplicationProvider.getApplicationContext())
        assertEquals("https://az.example", c.endpoint)
        assertEquals("gpt-x", c.deploymentOrModel)
        assertEquals("/m.gguf", c.nativeModelPath)
        assertEquals(c.endpoint, c.toProviderSettings().endpoint)
    }
}
```

- [ ] **Step 4: Run to verify failure**

Run: `./gradlew :services:orchestration:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: BuildConfig` in `AppConfigStore.kt`.

- [ ] **Step 5: Replace `BuildConfig` in `AppConfigStore.kt`**

```bash
F=services/orchestration/src/main/java/com/rokid/context/data/runtime/AppConfigStore.kt
sed -i '' 's/^import com.rokid.context.BuildConfig$/import com.rokid.context.ai.EnvDefaults/; s/BuildConfig.AZURE_KG_ENDPOINT/EnvDefaults.azureEndpoint/g; s/BuildConfig.AZURE_KG_API_KEY/EnvDefaults.azureApiKey/g; s/BuildConfig.AZURE_KG_DEPLOYMENT/EnvDefaults.azureDeployment/g; s/BuildConfig.LOCAL_NATIVE_MODEL_PATH/EnvDefaults.localNativeModelPath/g' $F
grep -rn "BuildConfig" services/ || echo clean
```
Expected: `clean`. (The phone's `ConfigHubScreen.kt` and `MainActivity.kt` keep using the app `BuildConfig` — they stay in `:phone`.)

- [ ] **Step 6: Run everything**

Run: `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: PASS for every module. Legacy totals: orchestration 31 (+1 new), ai 8 (+4), memory 3 (+3), phone 3 (OnboardingFlowTest) → 45 legacy tests all green.

- [ ] **Step 7: Manual smoke on device (needs the user)**

```bash
ADB=/Users/<user>/Desktop/rokid/android-sdk/platform-tools/adb
$ADB -s <phone> install -r phone/build/outputs/apk/debug/phone-debug.apk
$ADB -s <phone> shell am start -n com.rokid.context/.MainActivity
$ADB -s <phone> logcat -d -s QueueDrainWorker FrameStreamStore AndroidRuntime | tail -40
```
Expected: app opens to the existing Home; no `FATAL EXCEPTION` in logcat. Ask the user to tap **Run Smoke** and confirm it behaves as before.

- [ ] **Step 8: Commit**

```bash
git add -A android-companion
git commit -m "refactor(orchestration): move domain, workers, data, telemetry, security to :services:orchestration

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
Phase 1 done when: all modules' tests green, both APKs build, smoke OK. R-01 (partial: P1 modules), R-02 (theme/components/gestures), R-03, R-05/R-06 (types), R-12 (base) covered.
