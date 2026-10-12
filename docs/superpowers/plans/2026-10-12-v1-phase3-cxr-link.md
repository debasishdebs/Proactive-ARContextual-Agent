# Phase 3 — CXR Link + Glasses App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the raw RFCOMM relay with the Rokid CXR link: glasses capture into a persisted buffer and send chunked, acked frames to a phone hub service that ingests them into the existing pipeline. The phone pushes a full `StateFrame` (≤ 4 Hz) that a new Compose glasses HUD renders as Glance / Today / Status pages with overlays. Capture pause is enforced on both sides and fails closed.

**Architecture:** New `:services:glasses-link` (phone-side CXR-L session ported from Live Fit: `CxrGlassesLink`, `AuthActivity`/`AuthGate`, `GlassAppWatcher`, `GlassesSessionPolicy`, plus the pure frame-receive logic: reassembly, clock offset, dedupe ledger, admission). In `:phone`, `phone/hub/**` adds `ContextHubService` (connectedDevice foreground service) around a `Hub` that handles glasses messages, keeps the pause set in Room, writes frames atomically into the legacy `rokid_inbox` (so `FrameIngestWorker`/`QueueDrainWorker` stay unchanged) and broadcasts `StateFrame`s. It exposes `HubGraph.glassesStatus(context)` for Phase 4. The glasses app becomes a Compose (foundation-only) HUD: `HudController` (CXR-S bridge, session-open tracking, `pc_hello`), `GlassesPauses` (DataStore), `OutboundBuffer` + `FrameUplink` (stop-and-wait), `CaptureService` (the only capture path, with the legacy guardrails), `KeyGestures`/`InputRouter`/`HudNav`/`HudPresenter`, and the `ui/` pages. RFCOMM is deleted last, after frames are verified end to end over CXR.

**Tech Stack:** Kotlin 2.0.21, AGP 8.13.2, Rokid CXR `com.rokid.cxr:client-l:1.1.2` (phone) + `com.rokid.cxr:cxr-service-bridge:1.5` (both), Compose BOM 2024.09.00 (glasses: foundation + `material-icons-extended` only), `androidx.datastore:datastore-preferences-core:1.1.1` (glasses pause set), Room 2.6.1 (phone pause set, existing schema v1), kotlinx-coroutines 1.9.0 (+ `-test`), kotlinx-serialization 1.7.3, Robolectric 4.13.

**Spec:** `docs/superpowers/specs/2026-10-12-v1-redesign-design.md` §1 (all; §1.7/§1.8 as amended by Phase 0), §3, §5.5, §6.3, §6.4–6.5, §8. Master plan: `docs/superpowers/plans/2026-10-12-v1-master-plan.md` (its Global Constraints and Review Focus items 4 and 5 apply). Measured values: `docs/spikes/2026-10-12-phase0-results.md`. Parallel-work contract (binding): `docs/superpowers/plans/2026-10-12-v1-phase3-4-contract.md`. Phases 0 and 2 are complete.

## Global Constraints

- All master-plan Global Constraints apply (SDK levels, foundation-only glasses UI with Outlined icons, light-only phone, `v: PROTOCOL_VERSION` on every JSON payload, `UserFacingError` for every user-visible failure, fail-closed pause, commit trailer).
- **Commands:** run every Gradle command from `android-companion/` after `export ANDROID_HOME=/Users/<user>/Desktop/rokid/android-sdk`. Device steps use `ADB=/Users/<user>/Desktop/rokid/android-sdk/platform-tools/adb`, `PHONE=adb-RZCY902F76K-nmSM2g._adb-tls-connect._tcp` (Samsung, API 37), `GLASSES=1901092545029120` (RG-glasses, API 32). Never use `installDebug` (it installs on every attached device); use `$ADB -s … install -r`.
- **Gate:** `./gradlew test :phone:assembleDebug :glasses:assembleDebug` is green at the start (364 tests). It must stay green after every task.
- **CXR versions are pinned:** phone `com.rokid.cxr:client-l:1.1.2` + `com.rokid.cxr:cxr-service-bridge:1.5`; glasses `com.rokid.cxr:cxr-service-bridge:1.5`. `sendCustomCmd` returns `SessionResult` (success = `isSuccess` / `code=OK`).
- **Frame transfer (Phase 0 values, spec §1.8):** `SAFE_CHUNK_CHARS = 32 768`; frame-level acks only (no per-chunk acks); stop-and-wait, one frame at a time; ack timeout 5 s; at most 3 attempts with 2 s backoff, then stop the flush and keep the rest; `ok=false` → resend once, then drop; phone discards a partial frame after 10 s without a chunk; phone dedupes by `frameId`.
- **Glasses buffer:** at most 200 frames (≈ 4 MB) on disk in app storage, FIFO drop-oldest, frames older than 72 h dropped; a frame leaves the buffer only after `pc_frame_ack ok=true`, a second NACK, or a privacy pause.
- **Send only while the CUSTOM_APP session is open** (glasses activity not exited; display sleep is fine). `CXRServiceBridge.sendMessage` returning 0 does **not** mean delivered; only `pc_frame_ack` does. PTT (listening) suspends the flush; `pc_audio` is never queued behind frame chunks.
- **No auto-reconnect on `GLASSES_EXIT`:** reconnect only through `GlassAppWatcher` (the user reopened the glasses app) or an explicit phone action (`GlassesStatusSource.requestReconnect()`). BT drops pause and resume the session by themselves.
- **StateFrame:** full frames, at most 4 per second (≥ 250 ms apart). Instants on the wire are phone clock, except `PauseRecord` fields, which the phone converts to the glasses clock (the glasses enforce pauses on their own clock, spec §5.5).
- **File ownership (contract):** this plan touches only `android-companion/glasses/**`, `android-companion/services/glasses-link/**`, `phone/src/main/AndroidManifest.xml` (services/permissions only), `phone/src/main/java/com/rokid/context/ContextApp.kt`, `phone/src/main/java/com/rokid/context/phone/hub/**`, phone tests under `phone/src/test/java/com/rokid/context/phone/hub/**` and `phone/src/test/java/com/rokid/context/RfcommGoneTest.kt`, the R-04 deletions, additive `:core:model` / `:core:hud` / `:services:memory` (new DAO only, schema v1 unchanged) changes, `:services:ai` / `:services:orchestration` (Task 2 only), and dependency lines in `settings.gradle.kts` / `phone/build.gradle.kts`. The legacy phone `MainActivity.kt` is edited only in Task 12, minimally (bridge → hub). Never edit `phone/src/main/java/com/rokid/context/ui/**`, `:core:ui` or phone `res/`.
- `GlassesStatus.kt` in `:core:model` is **byte-identical** to the contract block (Task 1 checks it with `diff`).
- **Logging:** never pass a `Throwable` to `Log` when it may be (or wrap) a `ModelCallException`; log `SafeLog.describe(t)`. Never log Base64/JPEG content, tokens or keys. Link/hub logs use tags `PcGlassesLink`, `ContextHub` (phone) and `PcHud`, `PcUplink`, `PcCapture`, `PcPause`, `PcGlasses` (glasses); device steps grep these.
- **Tests:** pure logic gets JVM tests; Android-touching code uses Robolectric `@Config(sdk = [34])` (phone hub tests add `application = android.app.Application::class` so `ContextApp` does not start WorkManager). Phone hub tests that touch Room use `testDb(context)` (Task 5), which runs queries on the calling thread.
- **Device steps:** prefer adb-driven checks; a step that needs a human physical action is marked **(needs the user)**. The user is often away: when a marked step cannot run, record it as pending in the task report and continue.

## Review Focus

Inputs/conditions the spec implies but no requirement line spells out, most likely first; each is pinned by a named test in the owning task.

1. **Glasses clock far off the phone's (wrong time, travel, DST night)** — pause expiry and the paused-frame filter must use offset-corrected instants, never wall times (master Review Focus 4). → Task 4 `frameInsideAPauseIsDroppedUsingTheCorrectedTime`, `withoutAHelloOffsetFramesAreHeld`, `timedGlassesPauseExpiresAtTheSamePhoneInstant`, `dstChangeDoesNotMoveAnExpiry`; Task 6 `helloMeasuresTheOffsetAndUnionsGlassesPausesInPhoneClock`.
2. **Phone storage full while a frame is written** — clean failure, `UserFacingErrors.StorageFull` ("Phone storage full"), no ack (the glasses keep the frame), no half-written file, recovery on the next resend (master Review Focus 5). → Task 6 `storageFullLeavesNoFileSendsNoAckAndRecoversLater`; glasses side Task 8 `diskFullLeavesNothingBehind`.
3. **Process killed or link dropped mid-flush / mid-write** (glasses reboot, BT off, ack lost) — nothing lost, nothing ingested twice. → Task 4 `idsSurviveARestart`, `alreadyIngestedFrameIsAckedNotReingested`; Task 6 `resentFrameAfterALostAckIsNotStoredTwice`; Task 8 `framesSurviveARestartInCaptureOrder`, `crashLeftoversAreDiscardedOnLoad`, `gateClosingMidBacklogKeepsTheRestAndRestartsFromChunkZero`.
4. **Phone and glasses apps on different protocol versions** — glasses show "Update Proactive Context" and send no frames; phone shows `VERSION_MISMATCH`; no frame is acked by a mismatched peer. → Task 3 `otherVersionIsReportedNotDecoded`; Task 6 `outdatedGlassesShowVersionMismatchUntilANewSession`; Task 7 `otherPhoneVersionShowsOutdatedAndNeverOpensASession`.
5. **Unreadable or corrupted pause store on the glasses** — capture stays paused until the wearer resumes (fail-closed), and a reboot never resumes capture. → Task 7 `unreadableStoreFailsClosed`, `doubleTapPausesAtOnceAndSurvivesARestart`.

---

## File map

| Path (under `android-companion/`) | Responsibility | Task |
|---|---|---|
| `core/model/.../GlassesStatus.kt` | Phase 3↔4 contract (byte-identical) | 1 |
| `core/model/.../UserFacingError.kt` | `UserFacingError`, `ErrorAction`, `UserFacingErrors` presets + link mapping | 1 |
| `core/model/.../FrameCodec.kt` | `SAFE_CHUNK_CHARS`, chunk split/join + CRC, ack reasons, frame-id rule | 1 |
| `core/model/.../Protocol.kt` | `Hello.nextCaptureAtMs` (additive) | 1 |
| `services/ai/.../ai/ModelErrorMapper.kt`, `SafeLog.kt` | model failure → `UserFacingError`; safe log text | 2 |
| `services/orchestration/.../orchestration/RetryPolicy.kt`, `work/QueueDrainWorker.kt` | retry cap; user-facing trace/queue notes; safe logs | 2 |
| `services/glasses-link/**` | CXR-L link, auth, watcher, policy, inbound gate; frame receive logic | 3, 4 |
| `services/memory/.../db/AppDaos.kt`, `ContextDatabase.kt` | `PauseDao` (no schema change) | 5 |
| `phone/.../phone/hub/HubPauses.kt`, `StateBroadcaster.kt`, `StateFrameBuilder.kt`, `HubState.kt` | phone pause set, ≤ 4 Hz broadcast, frame building, status | 5 |
| `phone/.../phone/hub/FrameIngestor.kt`, `HubSettingsStore.kt`, `HubSources.kt`, `Hub.kt`, `HubGraph.kt`, `ContextHubService.kt`, `HubActivityHook.kt` | ingest, settings, hub wiring, FGS | 6 |
| `glasses/.../link/*` | `GlassesBridge`/`CxrBridge`, `LivenessMonitor`, `HudSettingsCache`, `HudController`, `FrameUplink` | 7, 8 |
| `glasses/.../pause/*` | `PauseStore` (DataStore), `GlassesPauses` | 7 |
| `glasses/.../capture/*` | `OutboundBuffer`, `FrameGuard`, `CaptureCadence`, `CaptureScheduler`, `CaptureService` | 8, 11 |
| `glasses/.../hud/*` | `KeyGestures`, `InputRouter`, `HudNav`, `ConfirmInput`, `HudPresenter` | 9 |
| `glasses/.../ui/*`, `GlassesRuntime.kt`, `MainActivity.kt` | Compose HUD, process runtime, activity | 10 |
| deleted: `phone/.../bridge/**`, glasses `RokidBluetoothProtocol.kt`, `CompanionConfigServer.kt`, `Pulse*.kt`, `GlassesSettingsStore.kt`, XML layout/drawables/styles | R-04 | 10, 12 |

---

### Task 1: `:core:model` — GlassesStatus contract, UserFacingError, frame codec

**Requirements:** R-05 (message shapes, `Hello` clock field), R-08 (chunking + CRC per §1.8 with `SAFE_CHUNK_CHARS = 32 768`), R-72 (`UserFacingError` introduced), contract `GlassesStatus`.

**Files:**
- Modify: `android-companion/core/model/build.gradle.kts`
- Create: `android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/GlassesStatus.kt`
- Create: `android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/UserFacingError.kt`
- Create: `android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/FrameCodec.kt`
- Modify: `android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/Protocol.kt` (the `Hello` class only)
- Test: `android-companion/core/model/src/test/kotlin/com/rokid/context/core/model/FrameCodecTest.kt`, `.../UserFacingErrorTest.kt`

**Interfaces:**
- Consumes: existing `FrameMeta`, `Hello`, `PauseRecord`, `StateFrame`, `Wire`, `Decoded`, `PROTOCOL_VERSION`.
- Produces:
  - `enum class LinkState { NOT_AUTHORIZED, CONNECTING, CONNECTED, PAUSED_BT, CLOSED_ON_GLASSES, VERSION_MISMATCH, OFFLINE }`, `data class GlassesStatus(link, glassesBatteryPct: Int?, bufferedFrames: Int, lastFrameAtMs: Long?, frame: StateFrame?)`, `interface GlassesStatusSource { val status: StateFlow<GlassesStatus>; fun requestReconnect() }`.
  - `enum class ErrorAction { NONE, RECONNECT, OPEN_ON_GLASSES, AUTHORIZE, UPDATE_GLASSES_APP, FIX_ENDPOINT, FREE_STORAGE, RETRY_LATER }`; `data class UserFacingError(title: String, hint: String, action: ErrorAction) { val line: String }`; `object UserFacingErrors { GlassesOffline, ClosedOnGlasses, NotAuthorized, VersionMismatch, StorageFull, AiKeyRejected, AiNotSet, AiRequestRejected, AiUnavailable, AiGaveUp, Unknown; val all: List<UserFacingError>; fun forLink(link: LinkState): UserFacingError? }`.
  - `const val SAFE_CHUNK_CHARS = 32_768`; `object FrameAckReason { CRC, DECODE, DUPLICATE, PAUSED_DROP, TOO_OLD }`; `data class FrameChunk(meta: FrameMeta, chunk: String)`; `sealed interface JoinResult { class Ok(jpeg: ByteArray); BadBase64; CrcMismatch }`; `object FrameCodec { fun isValidFrameId(id: String): Boolean; fun crc32(bytes: ByteArray): Long; fun split(frameId, jpeg, capturedAtMs, luma: Int? = null, phash: String? = null, batteryPct: Int? = null, chunkChars: Int = SAFE_CHUNK_CHARS): List<FrameChunk>; fun join(meta: FrameMeta, chunks: List<String>): JoinResult }`.
  - `Hello(..., nextCaptureAtMs: Long? = null)` (glasses clock; additive, no version bump).

- [ ] **Step 1: Add coroutines to `:core:model`**

In `core/model/build.gradle.kts`, change the `dependencies` block to:
```kotlin
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // For StateFlow in GlassesStatusSource (Phase 3↔4 contract; Phase 4 adds the identical line).
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
}
```

- [ ] **Step 2: Create `GlassesStatus.kt` byte-identical to the contract**

`core/model/src/main/kotlin/com/rokid/context/core/model/GlassesStatus.kt` (the first line is part of the contract block and stays):
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
Check it (run from the repo root):
```bash
awk '/^```kotlin$/{f=1;next} /^```$/{f=0} f' docs/superpowers/plans/2026-10-12-v1-phase3-4-contract.md \
  | diff - android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/GlassesStatus.kt && echo identical
```
Expected: `identical`.

- [ ] **Step 3: Write the failing codec and error tests**

`core/model/src/test/kotlin/com/rokid/context/core/model/FrameCodecTest.kt`:
```kotlin
package com.rokid.context.core.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrameCodecTest {
    private fun jpeg(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    /** Phase 0 sample: 20 396 B → 27 196 Base64 chars, below SAFE_CHUNK_CHARS, so one message. */
    @Test fun phaseZeroSampleFitsInOneChunk() {
        val chunks = FrameCodec.split("f1", jpeg(20_396), capturedAtMs = 5)
        assertEquals(1, chunks.size)
        assertEquals(27_196, chunks.single().chunk.length)
        assertEquals(1, chunks.single().meta.chunkCount)
    }

    @Test fun largeFrameSplitsAtSafeChunkChars() {
        val data = jpeg(60_000) // 80 000 Base64 chars
        val chunks = FrameCodec.split("f2", data, capturedAtMs = 5)
        assertEquals(listOf(32_768, 32_768, 14_464), chunks.map { it.chunk.length })
        assertEquals(listOf(0, 1, 2), chunks.map { it.meta.chunkIdx })
        assertTrue(chunks.all { it.meta.chunkCount == 3 && it.meta.crc32 == FrameCodec.crc32(data) }, "every chunk carries the whole-frame CRC")
    }

    @Test fun joinRestoresTheBytes() {
        val original = jpeg(9_999)
        val chunks = FrameCodec.split("f3", original, capturedAtMs = 1, chunkChars = 1_000)
        val r = FrameCodec.join(chunks.first().meta, chunks.map { it.chunk })
        assertIs<JoinResult.Ok>(r)
        assertContentEquals(original, r.jpeg)
    }

    @Test fun alteredChunkIsACrcMismatch() {
        val c = FrameCodec.split("f4", jpeg(3_000), capturedAtMs = 1).single()
        val altered = c.chunk.replaceRange(10, 11, if (c.chunk[10] == 'A') "B" else "A")
        assertEquals(JoinResult.CrcMismatch, FrameCodec.join(c.meta, listOf(altered)))
    }

    @Test fun invalidBase64IsReported() {
        val meta = FrameCodec.split("f5", jpeg(10), capturedAtMs = 1).single().meta
        assertEquals(JoinResult.BadBase64, FrameCodec.join(meta, listOf("**not base64**")))
    }

    @Test fun frameIdsAreFileNameSafe() {
        assertTrue(FrameCodec.isValidFrameId("g1793000000000_a1b2c3"))
        assertFalse(FrameCodec.isValidFrameId("../etc"))
        assertFalse(FrameCodec.isValidFrameId(""))
        assertFalse(FrameCodec.isValidFrameId("a".repeat(65)))
        assertFailsWith<IllegalArgumentException> { FrameCodec.split("bad/id", jpeg(10), capturedAtMs = 1) }
    }

    @Test fun metaRoundTripsOnTheWire() {
        val meta = FrameCodec.split("f6", jpeg(10), capturedAtMs = 42, luma = 90, phash = "ff", batteryPct = 81).single().meta
        assertEquals(Decoded.Ok(meta), Wire.decode<FrameMeta>(Wire.encode(meta)))
    }

    @Test fun helloWithoutNextCaptureStillDecodes() {
        val old = """{"v":$PROTOCOL_VERSION,"glassesBatteryPct":80,"bufferSize":2,"glassesNowMs":9,"pauses":[]}"""
        assertEquals(Decoded.Ok(Hello(glassesBatteryPct = 80, bufferSize = 2, glassesNowMs = 9, pauses = emptyList())), Wire.decode<Hello>(old))
    }
}
```

`core/model/src/test/kotlin/com/rokid/context/core/model/UserFacingErrorTest.kt`:
```kotlin
package com.rokid.context.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserFacingErrorTest {
    /** Spec §6.3 / §3.6: titles double as HUD strings; nothing that looks like exception text. */
    @Test fun everyPresetIsShortHumanText() {
        for (e in UserFacingErrors.all) {
            assertTrue(e.title.length <= 40, e.title)
            assertTrue(e.hint.length <= 80, e.hint)
            for (bad in listOf("Exception", "exception", "null", "http", "Error:", "{")) {
                assertFalse(bad in e.title || bad in e.hint, "'$bad' in ${e.line}")
            }
        }
    }

    @Test fun storageFullWordingIsExact() = assertEquals("Phone storage full", UserFacingErrors.StorageFull.title)

    @Test fun lineJoinsTitleAndHint() =
        assertEquals("Glasses offline · Bring your glasses near the phone.", UserFacingErrors.GlassesOffline.line)

    @Test fun linkStatesMapToTheirBanner() {
        assertEquals(UserFacingErrors.GlassesOffline, UserFacingErrors.forLink(LinkState.OFFLINE))
        assertEquals(UserFacingErrors.ClosedOnGlasses, UserFacingErrors.forLink(LinkState.CLOSED_ON_GLASSES))
        assertEquals(UserFacingErrors.NotAuthorized, UserFacingErrors.forLink(LinkState.NOT_AUTHORIZED))
        assertEquals(UserFacingErrors.VersionMismatch, UserFacingErrors.forLink(LinkState.VERSION_MISMATCH))
        assertEquals(ErrorAction.RECONNECT, UserFacingErrors.forLink(LinkState.OFFLINE)!!.action)
        // Transient states have no banner: CXR resumes a BT pause by itself (~2 s, phase 0).
        for (s in listOf(LinkState.CONNECTED, LinkState.CONNECTING, LinkState.PAUSED_BT)) assertNull(UserFacingErrors.forLink(s), "$s")
    }
}
```

- [ ] **Step 4: Run to verify failure**

Run: `./gradlew :core:model:test --tests "*FrameCodecTest" --tests "*UserFacingErrorTest"`
Expected: FAIL — compilation errors `Unresolved reference: FrameCodec`, `UserFacingErrors`, `JoinResult`.

- [ ] **Step 5: Implement `FrameCodec.kt`**

```kotlin
package com.rokid.context.core.model

import java.util.Base64
import java.util.zip.CRC32

/** Max Base64 characters in one `pc_frame` chunk (spec §1.8; phase 0 saw no limit up to 256 KB, so 8× headroom). */
const val SAFE_CHUNK_CHARS = 32_768

/**
 * `FrameAck.reason` values. With `ok=false` ([CRC], [DECODE]) the glasses resend once, then drop the frame.
 * With `ok=true` ([DUPLICATE], [PAUSED_DROP], [TOO_OLD]) the glasses delete it like any delivered frame.
 */
object FrameAckReason {
    const val CRC = "crc"
    const val DECODE = "decode"
    const val DUPLICATE = "duplicate"
    const val PAUSED_DROP = "paused_drop"
    const val TOO_OLD = "too_old"
}

/** One `pc_frame` message: [meta] is the first Caps string (JSON), [chunk] the second (Base64 text, spec §1.5). */
data class FrameChunk(val meta: FrameMeta, val chunk: String)

sealed interface JoinResult {
    class Ok(val jpeg: ByteArray) : JoinResult
    data object BadBase64 : JoinResult
    data object CrcMismatch : JoinResult
}

/** Frame chunking shared by both apps (spec §1.8): Base64 of the whole JPEG, split at [SAFE_CHUNK_CHARS]. */
object FrameCodec {
    private val FRAME_ID = Regex("[A-Za-z0-9_-]{1,64}")

    /** Frame ids end up in file names on both sides, so only this safe alphabet is accepted. */
    fun isValidFrameId(id: String): Boolean = FRAME_ID.matches(id)

    fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    fun split(
        frameId: String,
        jpeg: ByteArray,
        capturedAtMs: Long,
        luma: Int? = null,
        phash: String? = null,
        batteryPct: Int? = null,
        chunkChars: Int = SAFE_CHUNK_CHARS,
    ): List<FrameChunk> {
        require(isValidFrameId(frameId)) { "bad frame id" }
        require(jpeg.isNotEmpty()) { "empty frame" }
        require(chunkChars > 0) { "chunkChars must be positive" }
        val parts = Base64.getEncoder().encodeToString(jpeg).chunked(chunkChars)
        val crc = crc32(jpeg)
        return parts.mapIndexed { i, part ->
            FrameChunk(
                FrameMeta(frameId = frameId, chunkIdx = i, chunkCount = parts.size, crc32 = crc, capturedAtMs = capturedAtMs, luma = luma, phash = phash, batteryPct = batteryPct),
                part,
            )
        }
    }

    /** Joins chunks in index order, decodes and checks the whole-frame CRC from [meta]. */
    fun join(meta: FrameMeta, chunks: List<String>): JoinResult {
        val bytes = try {
            Base64.getDecoder().decode(chunks.joinToString(""))
        } catch (e: IllegalArgumentException) {
            return JoinResult.BadBase64
        }
        if (bytes.isEmpty()) return JoinResult.BadBase64
        return if (crc32(bytes) == meta.crc32) JoinResult.Ok(bytes) else JoinResult.CrcMismatch
    }
}
```

- [ ] **Step 6: Implement `UserFacingError.kt`**

```kotlin
package com.rokid.context.core.model

/** What the UI offers next to an error (spec §6.3). */
enum class ErrorAction { NONE, RECONNECT, OPEN_ON_GLASSES, AUTHORIZE, UPDATE_GLASSES_APP, FIX_ENDPOINT, FREE_STORAGE, RETRY_LATER }

/**
 * A failure as people see it (spec §6.3): a short [title] (also the HUD string), a one-line [hint] and an [action].
 * Never built from exception or socket text; full detail goes to Diagnostics logs only.
 */
data class UserFacingError(val title: String, val hint: String, val action: ErrorAction) {
    /** Title and hint on one line, for trace notes and queue rows. */
    val line: String get() = "$title · $hint"
}

object UserFacingErrors {
    val GlassesOffline = UserFacingError("Glasses offline", "Bring your glasses near the phone.", ErrorAction.RECONNECT)
    val ClosedOnGlasses = UserFacingError("Closed on glasses", "Open Proactive Context on your glasses.", ErrorAction.OPEN_ON_GLASSES)
    val NotAuthorized = UserFacingError("Glasses not authorized", "Authorize Proactive Context in Hi Rokid.", ErrorAction.AUTHORIZE)
    val VersionMismatch = UserFacingError("Update glasses app", "Phone and glasses apps are on different versions.", ErrorAction.UPDATE_GLASSES_APP)
    val StorageFull = UserFacingError("Phone storage full", "Free up space so new frames can be saved.", ErrorAction.FREE_STORAGE)
    val AiKeyRejected = UserFacingError("AI key rejected", "Check the API key for this endpoint.", ErrorAction.FIX_ENDPOINT)
    val AiNotSet = UserFacingError("AI model not set up", "Finish the endpoint and model in Settings.", ErrorAction.FIX_ENDPOINT)
    val AiRequestRejected = UserFacingError("AI request rejected", "Check the endpoint and model in Settings.", ErrorAction.FIX_ENDPOINT)
    val AiUnavailable = UserFacingError("AI unavailable", "Will retry automatically.", ErrorAction.RETRY_LATER)
    val AiGaveUp = UserFacingError("AI kept failing", "Stopped retrying; check the endpoint in Settings.", ErrorAction.FIX_ENDPOINT)
    val Unknown = UserFacingError("Something went wrong", "Details are in Diagnostics.", ErrorAction.NONE)

    val all: List<UserFacingError> = listOf(
        GlassesOffline, ClosedOnGlasses, NotAuthorized, VersionMismatch, StorageFull,
        AiKeyRejected, AiNotSet, AiRequestRejected, AiUnavailable, AiGaveUp, Unknown,
    )

    /** Banner for a link state (spec §2.2); null for states that need no action. */
    fun forLink(link: LinkState): UserFacingError? = when (link) {
        LinkState.OFFLINE -> GlassesOffline
        LinkState.CLOSED_ON_GLASSES -> ClosedOnGlasses
        LinkState.NOT_AUTHORIZED -> NotAuthorized
        LinkState.VERSION_MISMATCH -> VersionMismatch
        LinkState.CONNECTING, LinkState.CONNECTED, LinkState.PAUSED_BT -> null
    }
}
```

- [ ] **Step 7: Add `nextCaptureAtMs` to `Hello`**

In `Protocol.kt`, replace the `Hello` class (KDoc included) with:
```kotlin
/**
 * Glasses → phone on `pc_hello`: sent when a session opens and again after each buffered frame (a status report).
 * [glassesNowMs] gives the phone the clock offset; [pauses] and [nextCaptureAtMs] are in glasses clock.
 */
@Serializable
data class Hello(
    val v: Int = PROTOCOL_VERSION,
    val glassesBatteryPct: Int?,
    val bufferSize: Int,
    val glassesNowMs: Long,
    val pauses: List<PauseRecord>,
    val nextCaptureAtMs: Long? = null,
)
```
(Additive with a default: older senders decode unchanged, so `PROTOCOL_VERSION` stays 1.)

- [ ] **Step 8: Run the tests**

Run: `./gradlew :core:model:test`
Expected: PASS — `FrameCodecTest` (8), `UserFacingErrorTest` (4) and the existing `ProtocolTest` (11).

- [ ] **Step 9: Commit**

```bash
git add android-companion/core/model
git commit -m "feat(model): GlassesStatus contract, UserFacingError and frame codec

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Phase 2 carry-overs — user-facing queue notes, retry cap, safe logging

**Requirements:** R-72 (no raw provider text in trace notes or queue rows; Phase 2 ledger "Final: Ruling: carry to Phase 3" — UserFacingError for queue trace notes, retry cap for RETRY outcomes, and the Task 7 ruling "never log a ModelCallException throwable"), R-03 (existing queue behaviour otherwise unchanged).

`SummarizerTrackerScreen` lives in `phone/.../ui/**`, which Phase 4 owns. The note is therefore mapped where it is written (`QueueDrainWorker`), so the unchanged screen shows only user-facing text.

**Files:**
- Modify: `android-companion/services/ai/build.gradle.kts`
- Create: `android-companion/services/ai/src/main/java/com/rokid/context/ai/ModelErrorMapper.kt`
- Create: `android-companion/services/ai/src/main/java/com/rokid/context/ai/SafeLog.kt`
- Create: `android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/RetryPolicy.kt`
- Modify: `android-companion/services/orchestration/src/main/java/com/rokid/context/work/QueueDrainWorker.kt` (the `onFailure` block in `doWork`, imports)
- Test: `android-companion/services/ai/src/test/java/com/rokid/context/ai/ModelErrorMapperTest.kt`, `.../ai/SafeLogTest.kt`, `android-companion/services/orchestration/src/test/java/com/rokid/context/orchestration/RetryPolicyTest.kt`
- Modify (test): `android-companion/services/orchestration/src/test/java/com/rokid/context/work/QueueDrainWorkerRetryTest.kt`

**Interfaces:**
- Consumes: `UserFacingError`, `UserFacingErrors` (Task 1); `ModelCallException(message, retryable, cause)`, `ProviderErrors.isPermanent(t)`; `RetryClassifier.Outcome { RETRY, FAIL, CANCELLED }`; `QueueRuntimeStore.markDeferred(context, id, error, backoffMs, nowEpochMs)`, `markFailed`; `SummaryTraceStore.upsert/recent`.
- Produces: `object ModelErrorMapper { fun toUserFacing(t: Throwable): UserFacingError }`; `object SafeLog { fun describe(t: Throwable): String }`; `object RetryPolicy { const val MAX_ATTEMPTS = 8; enum class Decision { DEFER, FAIL }; fun decide(outcome: RetryClassifier.Outcome, attemptCount: Int): Decision }`. Trace notes and queue `lastError` on failure are now `UserFacingError.line`.

- [ ] **Step 1: Let `:services:ai` see `:core:model`**

In `services/ai/build.gradle.kts`, add to `dependencies` (after `api(project(":services:memory"))`):
```kotlin
    api(project(":core:model"))
```

- [ ] **Step 2: Write the failing mapper and log tests**

`services/ai/src/test/java/com/rokid/context/ai/ModelErrorMapperTest.kt`:
```kotlin
package com.rokid.context.ai

import com.rokid.context.core.model.UserFacingErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.SocketTimeoutException

class ModelErrorMapperTest {
    private fun map(t: Throwable) = ModelErrorMapper.toUserFacing(t)

    @Test fun missingBindingIsNotSetUp() =
        assertEquals(UserFacingErrors.AiNotSet, map(ModelCallException("No model is set for ANSWER", retryable = true)))

    @Test fun deletedEndpointIsNotSetUp() =
        assertEquals(UserFacingErrors.AiNotSet, map(ModelCallException("Endpoint for ANSWER was deleted", retryable = true)))

    @Test fun rejectedKeyIsKeyRejected() = assertEquals(
        UserFacingErrors.AiKeyRejected,
        map(ModelCallException("Primary provider failed: Azure OpenAI request failed: 401 invalid ***", retryable = false)),
    )

    @Test fun otherPermanentFailureIsRequestRejected() = assertEquals(
        UserFacingErrors.AiRequestRejected,
        map(ModelCallException("Primary provider failed: Azure OpenAI request failed: 404 deployment not found", retryable = false)),
    )

    @Test fun transientFailureIsUnavailable() = assertEquals(
        UserFacingErrors.AiUnavailable,
        map(ModelCallException("Primary provider failed: Azure OpenAI request failed: 500 upstream", retryable = true)),
    )

    @Test fun networkErrorIsUnavailable() = assertEquals(UserFacingErrors.AiUnavailable, map(SocketTimeoutException("timeout")))

    @Test fun anythingElseIsGeneric() =
        assertEquals(UserFacingErrors.Unknown, map(IllegalStateException("No Rokid frames available for summarization")))

    @Test fun providerTextNeverReachesTheLine() {
        val line = map(ModelCallException("Primary provider failed: 500 secret-body", retryable = true)).line
        assertFalse(line, "secret" in line || "500" in line)
    }
}
```

`services/ai/src/test/java/com/rokid/context/ai/SafeLogTest.kt`:
```kotlin
package com.rokid.context.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeLogTest {
    /** Phase 2 ruling: a ModelCallException's cause keeps the raw provider throwable, which may echo the key. */
    @Test fun modelFailureUsesOnlyItsRedactedMessage() {
        val raw = RuntimeException("Azure OpenAI request failed: 500 echoed sk-live-123")
        val e = ModelCallException("Primary provider failed: Azure OpenAI request failed: 500 echoed ***", retryable = true, cause = raw)
        val text = SafeLog.describe(e)
        assertTrue(text, text.startsWith("ModelCallException(retryable=true): Primary provider failed"))
        assertFalse(text, "sk-live-123" in text)
    }

    @Test fun longTextIsTruncated() = assertEquals(301, SafeLog.describe(IllegalStateException("x".repeat(1_000))).length)
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :services:ai:testDebugUnitTest --tests "*ModelErrorMapperTest" --tests "*SafeLogTest"`
Expected: FAIL — `Unresolved reference: ModelErrorMapper`, `SafeLog`.

- [ ] **Step 4: Implement `ModelErrorMapper.kt` and `SafeLog.kt`**

`services/ai/src/main/java/com/rokid/context/ai/ModelErrorMapper.kt`:
```kotlin
package com.rokid.context.ai

import com.rokid.context.core.model.UserFacingError
import com.rokid.context.core.model.UserFacingErrors
import java.io.IOException
import java.util.Locale

/** Model failures → what people see (spec §6.3). Never echoes provider text; Diagnostics logs keep the detail. */
object ModelErrorMapper {
    /** Config problems raised by ModelRouter itself (bindings, deleted endpoints, missing address/model/key). */
    private val NOT_SET = listOf("no model is set", "no model set", "no address set", "no model file set", "no api key saved", "was deleted")
    private val AUTH = Regex("request failed: (401|403)\\b|api_key is missing|api key is missing")

    fun toUserFacing(t: Throwable): UserFacingError {
        val m = t.message.orEmpty().lowercase(Locale.US)
        return when {
            t is ModelCallException && NOT_SET.any { it in m } -> UserFacingErrors.AiNotSet
            ProviderErrors.isPermanent(t) && AUTH.containsMatchIn(m) -> UserFacingErrors.AiKeyRejected
            ProviderErrors.isPermanent(t) -> UserFacingErrors.AiRequestRejected
            t is ModelCallException || t is IOException -> UserFacingErrors.AiUnavailable
            else -> UserFacingErrors.Unknown
        }
    }
}
```

`services/ai/src/main/java/com/rokid/context/ai/SafeLog.kt`:
```kotlin
package com.rokid.context.ai

/**
 * Log text for a failure (Phase 2 ruling). A [ModelCallException]'s message is already redacted, but its cause keeps
 * the raw provider throwable, which may echo an API key: never pass such a throwable to `Log`, never print its cause.
 */
object SafeLog {
    private const val MAX = 300

    fun describe(t: Throwable): String {
        val text = when (t) {
            is ModelCallException -> "ModelCallException(retryable=${t.retryable}): ${t.message.orEmpty()}"
            else -> "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
        }
        return if (text.length <= MAX) text else text.take(MAX) + "…"
    }
}
```

- [ ] **Step 5: Run the ai tests**

Run: `./gradlew :services:ai:testDebugUnitTest`
Expected: PASS (all existing ai tests plus 10 new).

- [ ] **Step 6: Write the failing retry-policy and worker tests**

`services/orchestration/src/test/java/com/rokid/context/orchestration/RetryPolicyTest.kt`:
```kotlin
package com.rokid.context.orchestration

import com.rokid.context.orchestration.RetryClassifier.Outcome
import com.rokid.context.orchestration.RetryPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class RetryPolicyTest {
    @Test fun retryIsDeferredBelowTheCap() {
        assertEquals(Decision.DEFER, RetryPolicy.decide(Outcome.RETRY, attemptCount = 0))
        assertEquals(Decision.DEFER, RetryPolicy.decide(Outcome.RETRY, attemptCount = RetryPolicy.MAX_ATTEMPTS - 2))
    }

    @Test fun theLastAllowedAttemptFails() =
        assertEquals(Decision.FAIL, RetryPolicy.decide(Outcome.RETRY, attemptCount = RetryPolicy.MAX_ATTEMPTS - 1))

    @Test fun aPermanentFailureFailsAtOnce() = assertEquals(Decision.FAIL, RetryPolicy.decide(Outcome.FAIL, attemptCount = 0))
}
```

In `QueueDrainWorkerRetryTest.kt`:
- add imports:
```kotlin
import com.rokid.context.core.model.UserFacingErrors
import com.rokid.context.data.runtime.SummaryTraceStore
import com.rokid.context.orchestration.RetryPolicy
import org.robolectric.shadows.ShadowLog
```
- replace the `bind()` helper with one that takes the key:
```kotlin
    private fun bind(key: String = "k") = runBlocking {
        ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt"), setOf(Capability.CHAT), 1), apiKey = key).getOrThrow()
        ai.bindings.set(RoleBinding(Role.EXPLICIT_REMEMBER, "az", "gpt")).getOrThrow()
    }
```
- add these tests at the end of the class:
```kotlin
    @Test fun traceNoteAndQueueRowAreUserFacing() {
        bind(); behaviour = { error("Azure OpenAI request failed: 500 upstream body") }
        runWorker()
        assertEquals(UserFacingErrors.AiUnavailable.line, SummaryTraceStore.recent(ctx, 5).first { it.state == "RETRY" }.note)
        assertEquals(UserFacingErrors.AiUnavailable.line, QueueRuntimeStore.recent(ctx, 10).single().lastError)
    }

    @Test fun retryStopsAtTheCap() {
        bind(); behaviour = { error("Azure OpenAI request failed: 500 upstream") }
        val id = QueueRuntimeStore.recent(ctx, 10).single().id
        // Earlier attempts, already due again (notBefore = now - 60 s + 15 s minimum backoff).
        repeat(RetryPolicy.MAX_ATTEMPTS - 1) {
            QueueRuntimeStore.markDeferred(ctx, id, "earlier", backoffMs = 0, nowEpochMs = System.currentTimeMillis() - 60_000)
        }
        runWorker()
        assertEquals(QueueRuntimeStore.STATE_FAILED, state())
        assertEquals(UserFacingErrors.AiGaveUp.line, SummaryTraceStore.recent(ctx, 5).first { it.state == "FAILED" }.note)
    }

    @Test fun failuresAreLoggedWithoutThrowablesOrKeys() {
        ShadowLog.reset()
        bind(key = "sk-live-123"); behaviour = { error("Azure OpenAI request failed: 500 echoed sk-live-123") }
        runWorker()
        val ours = listOf("QueueDrainWorker", "ModelRouter").flatMap { ShadowLog.getLogsForTag(it) }
        assertTrue("the failure is logged", ours.any { "RETRY" in it.msg })
        assertTrue("no throwable is logged", ours.none { it.throwable != null })
        assertTrue("the key is never logged", ours.none { "sk-live-123" in it.msg })
    }
```

- [ ] **Step 7: Run to verify failure**

Run: `./gradlew :services:orchestration:testDebugUnitTest --tests "*RetryPolicyTest" --tests "*QueueDrainWorkerRetryTest"`
Expected: FAIL — `Unresolved reference: RetryPolicy` (compilation).

- [ ] **Step 8: Implement `RetryPolicy.kt`**

```kotlin
package com.rokid.context.orchestration

/**
 * Phase 2 carry: a RETRY outcome is retried at most [MAX_ATTEMPTS] times in total, then the job fails. With the
 * 30 s × 2ⁿ backoff (capped at 30 min) the last attempt runs about an hour after the first. Policy deferrals
 * (battery saver, low battery) also count, as they always incremented `attemptCount`; the 24 h TTL still applies.
 */
object RetryPolicy {
    const val MAX_ATTEMPTS = 8

    enum class Decision { DEFER, FAIL }

    /** [attemptCount] = attempts already made before this one (`QueueJobRecord.attemptCount`). */
    fun decide(outcome: RetryClassifier.Outcome, attemptCount: Int): Decision =
        if (outcome == RetryClassifier.Outcome.RETRY && attemptCount + 1 < MAX_ATTEMPTS) Decision.DEFER else Decision.FAIL
}
```

- [ ] **Step 9: Use it in `QueueDrainWorker`**

Add imports:
```kotlin
import com.rokid.context.ai.ModelErrorMapper
import com.rokid.context.ai.SafeLog
import com.rokid.context.core.model.UserFacingErrors
import com.rokid.context.orchestration.RetryPolicy
```
Replace the whole `.onFailure { error -> … }` block in `doWork()` with:
```kotlin
            }.onFailure { error ->
                val outcome = RetryClassifier.classify(error)
                if (outcome == RetryClassifier.Outcome.CANCELLED) {
                    // WorkManager stopped us: keep the job for the next run, then let cancellation propagate.
                    QueueRuntimeStore.markDeferred(applicationContext, job.id, "cancelled; will retry", backoffMs = 0)
                    throw error
                }
                val decision = RetryPolicy.decide(outcome, job.attemptCount)
                // Detail for Diagnostics logs only: text, never the throwable (a ModelCallException's cause may echo a key).
                Log.w(TAG, "job ${job.id} ${outcome.name} attempt ${job.attemptCount + 1}/${RetryPolicy.MAX_ATTEMPTS}: ${SafeLog.describe(error)}")
                val shown = if (decision == RetryPolicy.Decision.FAIL && outcome == RetryClassifier.Outcome.RETRY) {
                    UserFacingErrors.AiGaveUp
                } else {
                    ModelErrorMapper.toUserFacing(error)
                }
                if (decision == RetryPolicy.Decision.DEFER) {
                    QueueRuntimeStore.markDeferred(context = applicationContext, id = job.id, error = shown.line, backoffMs = computeBackoffMs(job.attemptCount))
                } else {
                    QueueRuntimeStore.markFailed(applicationContext, job.id, shown.line)
                }
                SummaryTraceStore.upsert(
                    applicationContext,
                    SummaryTrace(
                        summaryId = job.id,
                        state = if (decision == RetryPolicy.Decision.DEFER) "RETRY" else "FAILED",
                        updatedAtEpochMs = System.currentTimeMillis(),
                        note = shown.line,
                    ),
                )
            }
```

- [ ] **Step 10: Run the orchestration tests**

Run: `./gradlew :services:orchestration:testDebugUnitTest`
Expected: PASS — the six existing `QueueDrainWorkerRetryTest` cases (states unchanged), the three new ones, and `RetryPolicyTest` (3).

- [ ] **Step 11: Run the gate and commit**

Run: `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: PASS.
```bash
git add android-companion/services/ai android-companion/services/orchestration
git commit -m "fix(queue): user-facing trace notes, retry cap and safe failure logs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `:services:glasses-link` — CXR-L session, auth, watcher, policy and inbound gate (Live Fit port)

**Requirements:** R-01 (`:services:glasses-link`), R-02 (`CxrGlassesLink`, `AuthActivity`/`AuthGate`, `GlassAppWatcher` copied with their tests), R-05 (channels, version gate, mismatch reported), R-07 (link lifecycle owned by the hub; no auto-reconnect on `GLASSES_EXIT`).

Ported from `/Users/<user>/Desktop/rokid/live-fitness-tracker` (branch `design/livefit-v1-v2`):
- `services/glasses-link/src/main/kotlin/com/debasish/livefit/services/glasses/{AuthGate,AuthActivity,CxrGlassesLink,GlassAppWatcher,GlassesInbound,GlassesSessionPolicy,FakeGlassesLink}.kt` and `src/main/AndroidManifest.xml`
- `services/sync/src/main/kotlin/com/debasish/livefit/sync/Backoff.kt`
- tests `services/glasses-link/src/test/kotlin/com/debasish/livefit/services/glasses/{AuthGateTest,GlassesSessionPolicyTest,GlassesIdentityTest}.kt`

Changes made while porting (the code below is the result, not a diff): package `com.rokid.context.link`; Live Fit's map/queue/page/discoverable channels, `DeviceStatus` and companion-device presence removed; link states use the contract `LinkState`; a BT pause is `PAUSED_BT` (new `LinkAction.MarkPaused`); `SendSettings` becomes a `ready` event the hub answers; phone-app resume reconnects only an `OFFLINE` link, never one closed on the glasses (spec §1.7); `pc_frame` messages carry two Caps strings.

**Files:**
- Modify: `android-companion/settings.gradle.kts`
- Create: `android-companion/services/glasses-link/build.gradle.kts`
- Create: `android-companion/services/glasses-link/src/main/AndroidManifest.xml`
- Create (all in `android-companion/services/glasses-link/src/main/kotlin/com/rokid/context/link/`): `AuthGate.kt`, `Backoff.kt`, `GlassesSessionPolicy.kt`, `GlassesEvent.kt`, `GlassesInbound.kt`, `GlassesLink.kt`, `FakeGlassesLink.kt`, `GlassAppWatcher.kt`, `AuthActivity.kt`, `CxrGlassesLink.kt`
- Test (in `.../services/glasses-link/src/test/kotlin/com/rokid/context/link/`): `AuthGateTest.kt`, `GlassesSessionPolicyTest.kt`, `GlassesInboundTest.kt`, `GlassesIdentityTest.kt`

**Interfaces:**
- Consumes: `Channels`, `Wire`, `Decoded`, `Hello`, `CommandEnvelope`, `Command`, `FrameMeta`, `FrameChunk`, `LinkState` (Task 1).
- Produces:
  - `sealed interface GlassesEvent { HelloReceived(hello: Hello); Cmd(command: Command); Frame(chunk: FrameChunk); Audio(pcm: ByteArray); ListenEnd; Outdated(remote: Int?) }`
  - `class GlassesInbound { fun reset(); fun onMessage(channel: String, parts: List<String>): List<GlassesEvent> }`
  - `interface GlassesLink { val state: StateFlow<LinkState>; val events: SharedFlow<GlassesEvent>; val ready: SharedFlow<Unit>; fun connect(); fun connectOnce(); fun connectOnResume(); suspend fun send(channel: String, json: String): Boolean; fun close() }`
  - `class FakeGlassesLink(initial: LinkState = LinkState.CONNECTED) : GlassesLink` with `sent: MutableList<Pair<String, String>>`, `connectCalls`, `connectOnceCalls`, `setState(s)`, `suspend emit(e)`, `emitReady()`.
  - `class CxrGlassesLink(context: Context, scope: CoroutineScope) : GlassesLink` (call its members from `scope`, Main); constants `GLASSES_PKG = "com.rokid.context.glasses"`, `GLASSES_ACTIVITY = "com.rokid.context.glasses.MainActivity"`, `TAG = "PcGlassesLink"`; logs `link=<STATE>` on every change.
  - `GlassesSessionPolicy`, `LinkEvent`, `LinkAction` (+ `MarkPaused`), `fun LinkAction.linkState(): LinkState?`, `fun shouldReconnectOnResume(link: LinkState, hasToken: Boolean, authDeclined: Boolean): Boolean`, `AuthGate`, `Backoff`.

- [ ] **Step 1: Register the module and its build file**

In `settings.gradle.kts`, after `include(":services:orchestration")`, add:
```kotlin
include(":services:glasses-link")
```

`services/glasses-link/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rokid.context.services.glasseslink"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
}

dependencies {
    api(project(":core:model"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Rokid CXR-L through the Hi Rokid app. client-l 1.1.2 depends on a cxr-service-bridge SNAPSHOT; pin the release.
    implementation("com.rokid.cxr:client-l:1.1.2")
    implementation("com.rokid.cxr:cxr-service-bridge:1.5")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

`services/glasses-link/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- preferGlobalHiRokid() looks up the global Hi Rokid build (package visibility on Android 11+). -->
    <queries>
        <package android:name="com.rokid.sprite.global.aiapp" />
    </queries>
    <application>
        <activity
            android:name="com.rokid.context.link.AuthActivity"
            android:excludeFromRecents="true"
            android:exported="false"
            android:launchMode="singleTop"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
    </application>
</manifest>
```

- [ ] **Step 2: Write the failing tests**

`AuthGateTest.kt` (copied from Live Fit unchanged except the package):
```kotlin
package com.rokid.context.link

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthGateTest {
    @Test fun launchesOncePerAttemptUntilItGoesStale() {
        val g = AuthGate(timeoutMs = 30_000)
        assertTrue(g.begin(0))
        assertFalse(g.begin(10_000))
        assertTrue(g.begin(30_000), "stale attempt may be relaunched")
    }

    @Test fun expiryAndStaleClearFreeTheGate() {
        val g = AuthGate(30_000)
        g.begin(0); g.expire()
        assertTrue(g.begin(1))
        g.clearStale(31_001)
        assertTrue(g.begin(31_002))
    }

    @Test fun lateFailureAfterSuccessIsIgnored() {
        val g = AuthGate()
        g.begin(0)
        assertTrue(g.result(true))
        assertFalse(g.result(false))
        assertFalse(g.result(true), "duplicate success")
        assertTrue(g.authorized)
    }

    @Test fun failureIsAppliedAndClearsInFlight() {
        val g = AuthGate()
        g.begin(0)
        assertTrue(g.result(false))
        assertTrue(g.begin(1))
    }

    @Test fun rejectedTokenRevokesAuthorizationAndAllowsOneReauthorization() {
        val g = AuthGate()
        g.begin(0); g.result(true)
        assertTrue(g.tokenRejected(), "first rejection: go through authorization again")
        assertFalse(g.authorized)
        assertTrue(g.begin(1), "a new attempt may launch at once")
        assertTrue(g.result(true))
        assertTrue(g.authorized)
    }

    @Test fun tokenRejectedAgainRightAfterReauthorizingGivesUp() {
        val g = AuthGate()
        g.begin(0); g.result(true)
        assertTrue(g.tokenRejected())
        g.begin(1); g.result(true)
        assertFalse(g.tokenRejected(), "no relaunch loop")
        assertFalse(g.authorized)
    }

    @Test fun aStartedSessionRearmsReauthorization() {
        val g = AuthGate()
        g.begin(0); g.result(true)
        g.tokenRejected(); g.begin(1); g.result(true)
        g.sessionStarted()
        assertTrue(g.tokenRejected(), "a later expiry is a new rejection")
    }
}
```

`GlassesSessionPolicyTest.kt` (Live Fit's tests; `MarkConnecting` on pause becomes `MarkPaused`, the resume rule follows spec §1.7):
```kotlin
package com.rokid.context.link

import com.rokid.context.core.model.LinkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlassesSessionPolicyTest {
    @Test fun startedSendsSettingsAndMarksConnected() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkConnected, LinkAction.SendSettings), p.onEvent(LinkEvent.Started))
    }

    /** BT drop → CXR pauses the session and resumes it by itself (phase 0): keep it, no second session. */
    @Test fun pausedSessionIsKeptAndResumeResendsSettings() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent); p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkPaused), p.onEvent(LinkEvent.Paused))
        assertEquals(listOf(LinkAction.MarkConnected, LinkAction.SendSettings), p.onEvent(LinkEvent.Resumed))
    }

    @Test fun closedWhilePresentRetriesWithBackoffAndNeverDoubleConnects() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.Closed))
        assertEquals(listOf(LinkAction.Connect), p.onEvent(LinkEvent.RetryTimer))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty(), "connect already in flight")
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(5_000)), p.onEvent(LinkEvent.ConnectFailed))
        p.onEvent(LinkEvent.RetryTimer); p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.Closed), "backoff reset after success")
    }

    @Test fun deviceGoneStopsRetrying() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        p.onEvent(LinkEvent.Closed)
        assertEquals(listOf(LinkAction.MarkDisconnected), p.onEvent(LinkEvent.DeviceGone))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
    }

    @Test fun manualConnectReplacesAStuckConnectingAttemptButNotAnOpenSession() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.manualConnect())
        p.onEvent(LinkEvent.Started)
        assertTrue(p.manualConnect().isEmpty())
        p.onEvent(LinkEvent.Paused)
        assertTrue(p.manualConnect().isEmpty())
    }

    @Test fun connectTimeoutBehavesLikeFailureOnlyWhileConnecting() {
        val p = GlassesSessionPolicy()
        assertTrue(p.onEvent(LinkEvent.ConnectTimeout).isEmpty(), "nothing in flight")
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.ConnectTimeout))
        assertTrue(p.onEvent(LinkEvent.ConnectTimeout).isEmpty(), "already waiting to retry")
    }

    /** Spec §1.7: GLASSES_EXIT never auto-reconnects (it would reopen the activity and steal the foreground). */
    @Test fun glassesExitDoesNotAutoRetryUntilManualConnect() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent); p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkClosedOnGlasses), p.onEvent(LinkEvent.GlassesExited))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.manualConnect())
    }

    @Test fun linkLossStillRetriesWithBackoff() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent); p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.Closed))
    }

    @Test fun authFailureStopsAutoRetry() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkAuthNeeded), p.onEvent(LinkEvent.AuthFailed))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
    }

    @Test fun autoConnectTriesOnceWithoutARetryLoop() {
        val p = GlassesSessionPolicy()
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.autoConnect())
        assertEquals(listOf(LinkAction.MarkDisconnected), p.onEvent(LinkEvent.ConnectFailed), "no ScheduleRetry")
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
    }

    @Test fun autoConnectLeavesAnAttemptInFlightOrAnOpenSessionAlone() {
        val p = GlassesSessionPolicy()
        p.manualConnect()
        assertTrue(p.autoConnect().isEmpty(), "a connect is already in flight")
        p.onEvent(LinkEvent.Started)
        assertTrue(p.autoConnect().isEmpty(), "open session")
    }

    /** The user reopened the app on the glasses after closing it there: that is consent, so reconnect. */
    @Test fun glassesAppOpenedReconnectsOnlyAfterAClosedOnGlasses() {
        val p = GlassesSessionPolicy()
        assertTrue(p.onEvent(LinkEvent.GlassesAppOpened).isEmpty(), "never closed on glasses")
        p.onEvent(LinkEvent.DevicePresent); p.onEvent(LinkEvent.Started)
        assertTrue(p.onEvent(LinkEvent.GlassesAppOpened).isEmpty(), "session open")
        p.onEvent(LinkEvent.GlassesExited)
        assertTrue(p.closedOnGlasses)
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.onEvent(LinkEvent.GlassesAppOpened))
        assertFalse(p.closedOnGlasses)
        assertTrue(p.onEvent(LinkEvent.GlassesAppOpened).isEmpty(), "connect already in flight")
    }

    @Test fun closedOnGlassesEndsOnDeviceGoneOrManualConnect() {
        val p = GlassesSessionPolicy()
        p.manualConnect(); p.onEvent(LinkEvent.Started); p.onEvent(LinkEvent.GlassesExited)
        p.onEvent(LinkEvent.DeviceGone)
        assertFalse(p.closedOnGlasses)
        assertTrue(p.onEvent(LinkEvent.GlassesAppOpened).isEmpty())
        p.manualConnect(); p.onEvent(LinkEvent.Started); p.onEvent(LinkEvent.GlassesExited)
        p.manualConnect()
        assertFalse(p.closedOnGlasses)
    }

    @Test fun closedOnGlassesStillAllowsAHubStartAttempt() {
        val p = GlassesSessionPolicy()
        p.manualConnect(); p.onEvent(LinkEvent.Started); p.onEvent(LinkEvent.GlassesExited)
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.autoConnect())
    }

    /** Spec §1.7: phone-app resume is not an explicit action, so a close on the glasses is never undone by it. */
    @Test fun resumeReconnectsOnlyAnAuthorizedOfflineLink() {
        assertTrue(shouldReconnectOnResume(LinkState.OFFLINE, hasToken = true, authDeclined = false))
        assertFalse(shouldReconnectOnResume(LinkState.OFFLINE, hasToken = false, authDeclined = false), "never authorized")
        assertFalse(shouldReconnectOnResume(LinkState.OFFLINE, hasToken = true, authDeclined = true), "declined")
        assertFalse(shouldReconnectOnResume(LinkState.CLOSED_ON_GLASSES, hasToken = true, authDeclined = false), "closed on glasses")
        for (s in listOf(LinkState.CONNECTING, LinkState.CONNECTED, LinkState.PAUSED_BT, LinkState.NOT_AUTHORIZED, LinkState.VERSION_MISMATCH)) {
            assertFalse(shouldReconnectOnResume(s, hasToken = true, authDeclined = false), "$s")
        }
    }

    @Test fun markActionsMapToLinkStates() {
        assertEquals(LinkState.CONNECTED, LinkAction.MarkConnected.linkState())
        assertEquals(LinkState.CONNECTING, LinkAction.MarkConnecting.linkState())
        assertEquals(LinkState.PAUSED_BT, LinkAction.MarkPaused.linkState())
        assertEquals(LinkState.OFFLINE, LinkAction.MarkDisconnected.linkState())
        assertEquals(LinkState.CLOSED_ON_GLASSES, LinkAction.MarkClosedOnGlasses.linkState())
        assertEquals(LinkState.NOT_AUTHORIZED, LinkAction.MarkAuthNeeded.linkState())
        assertNull(LinkAction.Connect.linkState())
        assertNull(LinkAction.ScheduleRetry(1).linkState())
    }
}
```

`GlassesInboundTest.kt`:
```kotlin
package com.rokid.context.link

import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.CommandEnvelope
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.Wire
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassesInboundTest {
    private val inbound = GlassesInbound()
    private fun cmd(c: Command) = Wire.encode(CommandEnvelope(command = c))
    private val hello = Hello(glassesBatteryPct = 80, bufferSize = 3, glassesNowMs = 1_000, pauses = emptyList())

    @Test fun helloIsDecoded() =
        assertEquals(listOf<GlassesEvent>(GlassesEvent.HelloReceived(hello)), inbound.onMessage(Channels.HELLO, listOf(Wire.encode(hello))))

    @Test fun otherVersionIsReportedNotDecoded() {
        val text = Wire.encode(hello).replace("\"v\":$PROTOCOL_VERSION", "\"v\":${PROTOCOL_VERSION + 1}")
        assertEquals(listOf<GlassesEvent>(GlassesEvent.Outdated(PROTOCOL_VERSION + 1)), inbound.onMessage(Channels.HELLO, listOf(text)))
        val frame = FrameCodec.split("f1", ByteArray(10) { 1 }, capturedAtMs = 5).single()
        val oldMeta = Wire.encode(frame.meta).replace("\"v\":$PROTOCOL_VERSION", "\"v\":${PROTOCOL_VERSION + 1}")
        assertEquals(listOf<GlassesEvent>(GlassesEvent.Outdated(PROTOCOL_VERSION + 1)), inbound.onMessage(Channels.FRAME, listOf(oldMeta, frame.chunk)), "never ingested")
    }

    @Test fun frameNeedsMetaAndChunk() {
        val c = FrameCodec.split("f1", ByteArray(10) { 1 }, capturedAtMs = 5).single()
        assertEquals(listOf<GlassesEvent>(GlassesEvent.Frame(c)), inbound.onMessage(Channels.FRAME, listOf(Wire.encode(c.meta), c.chunk)))
        assertTrue(inbound.onMessage(Channels.FRAME, listOf(Wire.encode(c.meta))).isEmpty(), "chunk missing")
    }

    @Test fun commandsAreDecoded() = assertEquals(
        listOf<GlassesEvent>(GlassesEvent.Cmd(Command.Resume(setOf("p1")))),
        inbound.onMessage(Channels.CMD, listOf(cmd(Command.Resume(setOf("p1"))))),
    )

    @Test fun malformedMessagesAreDropped() {
        assertTrue(inbound.onMessage(Channels.CMD, listOf("not json")).isEmpty())
        assertTrue(inbound.onMessage(Channels.HELLO, emptyList()).isEmpty())
        assertTrue(inbound.onMessage("pc_unknown", listOf("{}")).isEmpty())
    }

    /** pc_audio has no `v`: it counts only after an accepted (same-version) ListenStart. */
    @Test fun audioCountsOnlyAfterAnAcceptedListenStart() {
        val pcm = Base64.getEncoder().encodeToString(ByteArray(3_200) { 2 })
        assertTrue(inbound.onMessage(Channels.AUDIO, listOf(pcm)).isEmpty())
        inbound.onMessage(Channels.CMD, listOf(cmd(Command.ListenStart)))
        assertEquals(3_200, (inbound.onMessage(Channels.AUDIO, listOf(pcm)).single() as GlassesEvent.Audio).pcm.size)
        assertEquals(listOf<GlassesEvent>(GlassesEvent.ListenEnd), inbound.onMessage(Channels.LISTEN_END, listOf("")))
        assertTrue(inbound.onMessage(Channels.AUDIO, listOf(pcm)).isEmpty(), "closed by listen end")
    }

    @Test fun resetForgetsAnOpenListen() {
        inbound.onMessage(Channels.CMD, listOf(cmd(Command.ListenStart)))
        inbound.reset()
        assertTrue(inbound.onMessage(Channels.LISTEN_END, listOf("")).isEmpty())
    }
}
```

`GlassesIdentityTest.kt`:
```kotlin
package com.rokid.context.link

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassesIdentityTest {
    @Test fun sessionTargetsOurGlassesApp() {
        assertEquals("com.rokid.context.glasses", CxrGlassesLink.GLASSES_PKG)
        assertEquals("com.rokid.context.glasses.MainActivity", CxrGlassesLink.GLASSES_ACTIVITY)
    }

    /** The CUSTOM_APP session opens the glasses app by id and class: a rename there must fail here. */
    @Test fun glassesBuildUsesTheSameIdAndActivity() {
        val gradle = File("../../glasses/build.gradle.kts").readText()
        assertTrue("applicationId = \"${CxrGlassesLink.GLASSES_PKG}\"" in gradle)
        assertTrue(File("../../glasses/src/main/java/com/rokid/context/glasses/MainActivity.kt").isFile)
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :services:glasses-link:testDebugUnitTest`
Expected: FAIL — compilation errors (`Unresolved reference: AuthGate`, `GlassesSessionPolicy`, `GlassesInbound`, `CxrGlassesLink`).

- [ ] **Step 4: Port the pure pieces**

`AuthGate.kt` (copied unchanged except the package):
```kotlin
package com.rokid.context.link

/** Pure bookkeeping for the single in-flight Hi Rokid authorization attempt (ported from Live Fit). */
class AuthGate(private val timeoutMs: Long = AUTH_TIMEOUT_MS) {
    var authorized = false
        private set
    private var inFlight = false
    private var startedAt = 0L
    private var rejected = false // the SDK rejected a token since the last started session

    /** True when the caller should launch the authorization UI now (none is in flight, or the last one is stale). */
    fun begin(now: Long): Boolean {
        if (inFlight && now - startedAt < timeoutMs) return false
        inFlight = true
        startedAt = now
        return true
    }

    /** Drops an attempt older than the timeout (e.g. before a manual connect). */
    fun clearStale(now: Long) { if (inFlight && now - startedAt >= timeoutMs) inFlight = false }

    /** The timeout fired without any result. */
    fun expire() { inFlight = false }

    /** Returns true if this result should be applied; late/duplicate reports for a resolved attempt are ignored. */
    fun result(ok: Boolean): Boolean {
        if (authorized && !inFlight) return false
        inFlight = false
        if (ok) authorized = true
        return true
    }

    /**
     * The SDK rejected the saved token (TOKEN_EXPIRED / NOT_AUTHENTICATED): authorization is gone. True when the caller
     * should re-authorize; false when a token was already rejected since the last started session (no relaunch loop).
     */
    fun tokenRejected(): Boolean {
        authorized = false
        inFlight = false
        return !rejected.also { rejected = true }
    }

    /** A session started with the current token: a later rejection may re-authorize again. */
    fun sessionStarted() { rejected = false }

    companion object { const val AUTH_TIMEOUT_MS = 30_000L }
}
```

`Backoff.kt`:
```kotlin
package com.rokid.context.link

/** Reconnect delays 2 s → 5 s → 10 s → 30 s, then 30 s forever (ported from Live Fit `sync/Backoff.kt`). */
class Backoff(private val stepsMs: List<Long> = listOf(2_000, 5_000, 10_000, 30_000)) {
    private var i = 0
    fun next(): Long = stepsMs[minOf(i++, stepsMs.lastIndex)]
    fun reset() { i = 0 }
}
```

`GlassesSessionPolicy.kt`:
```kotlin
package com.rokid.context.link

import com.rokid.context.core.model.LinkState

sealed interface LinkEvent {
    data object DevicePresent : LinkEvent
    data object DeviceGone : LinkEvent
    data object Started : LinkEvent
    data object Paused : LinkEvent
    data object Resumed : LinkEvent
    data object Closed : LinkEvent
    data object ConnectFailed : LinkEvent
    data object RetryTimer : LinkEvent
    /** The connect attempt produced no result in time; treated like [ConnectFailed] while connecting. */
    data object ConnectTimeout : LinkEvent
    /** The glasses left our app (user exit / app switch): do not auto-reconnect (spec §1.7). */
    data object GlassesExited : LinkEvent
    /** The user brought our app back to the foreground on the glasses ([GlassAppWatcher]). */
    data object GlassesAppOpened : LinkEvent
    /** Hi Rokid authorization failed or was declined: stop auto-relaunching it. */
    data object AuthFailed : LinkEvent
}

sealed interface LinkAction {
    data object Connect : LinkAction
    /** The session (re)opened: the hub pushes `pc_settings` and `pc_state` now. */
    data object SendSettings : LinkAction
    data class ScheduleRetry(val delayMs: Long) : LinkAction
    data object MarkConnected : LinkAction
    data object MarkConnecting : LinkAction
    /** BT dropped: CXR pauses the session and resumes it by itself ~2 s after BT returns (phase 0). */
    data object MarkPaused : LinkAction
    data object MarkDisconnected : LinkAction
    data object MarkClosedOnGlasses : LinkAction
    data object MarkAuthNeeded : LinkAction
}

/** The [LinkState] a Mark* action sets; null for actions that do not change it. */
fun LinkAction.linkState(): LinkState? = when (this) {
    LinkAction.MarkConnected -> LinkState.CONNECTED
    LinkAction.MarkConnecting -> LinkState.CONNECTING
    LinkAction.MarkPaused -> LinkState.PAUSED_BT
    LinkAction.MarkDisconnected -> LinkState.OFFLINE
    LinkAction.MarkClosedOnGlasses -> LinkState.CLOSED_ON_GLASSES
    LinkAction.MarkAuthNeeded -> LinkState.NOT_AUTHORIZED
    LinkAction.Connect, LinkAction.SendSettings, is LinkAction.ScheduleRetry -> null
}

/** Pure session lifecycle rules for the CXR-L link (ported from Live Fit; spec §1.7). */
class GlassesSessionPolicy(private val backoff: Backoff = Backoff()) {
    /** ClosedOnGlasses is Idle after the user closed the app on the glasses: only their reopening it reconnects. */
    private enum class S { Idle, ClosedOnGlasses, Connecting, Open, Paused, WaitingRetry }
    private var state = S.Idle
    private var present = false
    private val idle get() = state == S.Idle || state == S.ClosedOnGlasses

    /** True while the app was closed on the glasses and nothing else has happened since (the link watches for a reopen). */
    val closedOnGlasses: Boolean get() = state == S.ClosedOnGlasses

    fun onEvent(e: LinkEvent): List<LinkAction> = when (e) {
        LinkEvent.DevicePresent -> {
            present = true
            if (idle || state == S.WaitingRetry) { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) } else emptyList()
        }
        LinkEvent.DeviceGone -> { present = false; state = S.Idle; listOf(LinkAction.MarkDisconnected) }
        LinkEvent.Started -> { state = S.Open; backoff.reset(); listOf(LinkAction.MarkConnected, LinkAction.SendSettings) }
        LinkEvent.Paused -> { state = S.Paused; listOf(LinkAction.MarkPaused) }
        LinkEvent.Resumed -> { state = S.Open; listOf(LinkAction.MarkConnected, LinkAction.SendSettings) }
        LinkEvent.GlassesExited -> { state = S.ClosedOnGlasses; listOf(LinkAction.MarkClosedOnGlasses) }
        LinkEvent.GlassesAppOpened ->
            if (state == S.ClosedOnGlasses) { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) } else emptyList()
        LinkEvent.AuthFailed -> { state = S.Idle; listOf(LinkAction.MarkAuthNeeded) }
        LinkEvent.ConnectTimeout -> if (state == S.Connecting) onEvent(LinkEvent.ConnectFailed) else emptyList()
        LinkEvent.Closed, LinkEvent.ConnectFailed -> {
            if (present) { state = S.WaitingRetry; listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(backoff.next())) }
            else { state = S.Idle; listOf(LinkAction.MarkDisconnected) }
        }
        LinkEvent.RetryTimer -> if (present && state == S.WaitingRetry) { state = S.Connecting; listOf(LinkAction.Connect) } else emptyList()
    }

    /** Explicit connect (phone "Open on glasses", after authorization). Replaces a stuck attempt; leaves an Open/Paused session alone. */
    fun manualConnect(): List<LinkAction> {
        present = true
        return if (state == S.Open || state == S.Paused) emptyList()
        else { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) }
    }

    /** Hub (re)start: one attempt from Idle; a failure ends Idle instead of entering the retry loop. */
    fun autoConnect(): List<LinkAction> =
        if (idle) { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) } else emptyList()
}

/**
 * The phone app came to the foreground: reconnect an OFFLINE link once when Hi Rokid authorized us before. Unlike
 * Live Fit, never a link closed on the glasses (spec §1.7: only the wearer reopening the app there, or an explicit
 * phone action, reconnects it), and never after a declined authorization (that would relaunch AuthActivity).
 */
fun shouldReconnectOnResume(link: LinkState, hasToken: Boolean, authDeclined: Boolean): Boolean =
    link == LinkState.OFFLINE && hasToken && !authDeclined
```

`GlassesEvent.kt`:
```kotlin
package com.rokid.context.link

import com.rokid.context.core.model.Command
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.Hello

/** Glasses → phone messages that passed the protocol-version gate (spec §1.5). */
sealed interface GlassesEvent {
    data class HelloReceived(val hello: Hello) : GlassesEvent
    data class Cmd(val command: Command) : GlassesEvent
    data class Frame(val chunk: FrameChunk) : GlassesEvent
    /** PCM 16 kHz mono (consumed in Phase 6). */
    class Audio(val pcm: ByteArray) : GlassesEvent
    data object ListenEnd : GlassesEvent
    /** The glasses app speaks another protocol version ([remote] = its `v`, null when absent). */
    data class Outdated(val remote: Int?) : GlassesEvent
}
```

`GlassesInbound.kt`:
```kotlin
package com.rokid.context.link

import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.CommandEnvelope
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.FrameMeta
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.Wire
import java.util.Base64

/**
 * Pure decoding and version gate for glasses → phone messages (ported from Live Fit `GlassesInbound`). [parts] are a
 * message's Caps strings in order: one JSON string, except `pc_frame` (meta JSON + Base64 chunk). A message from another
 * protocol version becomes [GlassesEvent.Outdated] and is never applied. `pc_audio` / `pc_listen_end` carry no `v`, so
 * they count only after an accepted `ListenStart`.
 */
class GlassesInbound {
    private var listenAccepted = false

    /** A new CXR session: nothing from the previous one is in flight. */
    @Synchronized fun reset() { listenAccepted = false }

    @Synchronized fun onMessage(channel: String, parts: List<String>): List<GlassesEvent> {
        val first = parts.firstOrNull() ?: return emptyList()
        return when (channel) {
            Channels.HELLO -> gate(Wire.decode<Hello>(first)) { GlassesEvent.HelloReceived(it) }
            Channels.CMD -> gate(Wire.decode<CommandEnvelope>(first)) { env ->
                when (env.command) {
                    Command.ListenStart -> listenAccepted = true
                    Command.Cancel -> listenAccepted = false
                    else -> Unit
                }
                GlassesEvent.Cmd(env.command)
            }
            Channels.FRAME -> if (parts.size < 2) emptyList() else gate(Wire.decode<FrameMeta>(first)) { GlassesEvent.Frame(FrameChunk(it, parts[1])) }
            Channels.AUDIO -> if (!listenAccepted) emptyList() else listOfNotNull(
                runCatching { Base64.getDecoder().decode(first) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { GlassesEvent.Audio(it) },
            )
            Channels.LISTEN_END -> if (!listenAccepted) emptyList() else { listenAccepted = false; listOf(GlassesEvent.ListenEnd) }
            else -> emptyList()
        }
    }

    private inline fun <T> gate(d: Decoded<T>, ok: (T) -> GlassesEvent): List<GlassesEvent> = when (d) {
        is Decoded.Ok -> listOf(ok(d.value))
        is Decoded.VersionMismatch -> listOf(GlassesEvent.Outdated(d.remote))
        is Decoded.Malformed -> emptyList()
    }
}
```

`GlassesLink.kt`:
```kotlin
package com.rokid.context.link

import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Phone side of the glasses link (spec §1.7). [CxrGlassesLink] in the app; [FakeGlassesLink] in tests. */
interface GlassesLink {
    /** Never [LinkState.VERSION_MISMATCH]: the hub derives that from [GlassesEvent.Outdated]. */
    val state: StateFlow<LinkState>
    val events: SharedFlow<GlassesEvent>
    /** A session opened or resumed: push `pc_settings` then `pc_state` now. */
    val ready: SharedFlow<Unit>
    /** Explicit user action (e.g. "Open on glasses"): connects even after a close on the glasses. */
    fun connect()
    /** Hub start: one attempt, no retry loop when the glasses are away. */
    fun connectOnce()
    /** Phone app resumed: reconnects only an OFFLINE, authorized link ([shouldReconnectOnResume]). */
    fun connectOnResume()
    /** Phone → glasses JSON on [channel]; false when no session is open or CXR refused it. */
    suspend fun send(channel: String, json: String): Boolean
    fun close()
}
```

`FakeGlassesLink.kt`:
```kotlin
package com.rokid.context.link

import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections

/** Test double: records what the hub sends and lets a test play glasses messages. */
class FakeGlassesLink(initial: LinkState = LinkState.CONNECTED) : GlassesLink {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<LinkState> = _state
    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<GlassesEvent> = _events
    private val _ready = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val ready: SharedFlow<Unit> = _ready
    val sent: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    var connectCalls = 0
        private set
    var connectOnceCalls = 0
        private set

    fun setState(s: LinkState) { _state.value = s }
    suspend fun emit(e: GlassesEvent) = _events.emit(e)
    fun emitReady() { _ready.tryEmit(Unit) }

    override fun connect() { connectCalls++ }
    override fun connectOnce() { connectOnceCalls++ }
    override fun connectOnResume() = Unit
    override suspend fun send(channel: String, json: String): Boolean {
        if (_state.value != LinkState.CONNECTED) return false
        sent += channel to json
        return true
    }
    override fun close() = Unit
}
```

- [ ] **Step 5: Port the CXR adapters**

`GlassAppWatcher.kt`:
```kotlin
package com.rokid.context.link

import android.content.Context
import android.util.Log
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo

/**
 * Watches the glasses' foreground app while no CUSTOM_APP session is open (ported from Live Fit). Each CxrSession owns
 * its own CXRLink and unbinds it on close, so after a close on the glasses nothing is listening; this keeps a
 * separate, session-less CXRLink bound only while the link is "closed on glasses" and reports our app's resume.
 * [CXRLink.configCXRSession] is local state only: no session is opened and nothing is sent to the glasses.
 */
internal class GlassAppWatcher(private val app: Context, private val glassesPkg: String, private val onOurAppResumed: () -> Unit) {
    private var link: CXRLink? = null

    val active: Boolean get() = link != null

    fun start(token: String) {
        if (link != null) return
        try {
            val l = CXRLink(app)
            l.configCXRSession(CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, glassesPkg))
            l.setCXRLinkCbk(NoLinkCbk) // CXRLink dereferences it on every service callback
            l.setCXRGlassAppCbk(object : IGlassAppCbk {
                override fun onGlassAppResume(resumed: Boolean) {
                    Log.i(CxrGlassesLink.TAG, "watcher: glasses app resume ours=$resumed")
                    if (resumed) onOurAppResumed()
                }
                override fun onInstallAppResult(ok: Boolean) = Unit
                override fun onUnInstallAppResult(ok: Boolean) = Unit
                override fun onOpenAppResult(ok: Boolean) = Unit
                override fun onStopAppResult(ok: Boolean) = Unit
                override fun onQueryAppResult(ok: Boolean) = Unit
            })
            if (!l.connect(token)) { Log.w(CxrGlassesLink.TAG, "watcher: bind failed"); return }
            link = l
            Log.i(CxrGlassesLink.TAG, "watcher: watching for our app reopened on glasses")
        } catch (e: Exception) {
            Log.w(CxrGlassesLink.TAG, "watcher: start failed (${e.javaClass.simpleName})")
        }
    }

    fun stop() {
        val l = link ?: return
        link = null
        try { l.disconnect() } catch (e: Exception) { Log.w(CxrGlassesLink.TAG, "watcher: stop failed (${e.javaClass.simpleName})") }
        Log.i(CxrGlassesLink.TAG, "watcher: stopped")
    }

    private object NoLinkCbk : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) = Unit
        override fun onGlassBtConnected(connected: Boolean) = Unit
        override fun onGlassDeviceInfo(info: GlassInfo) = Unit
        override fun onGlassWearingStatus(wearing: Boolean) = Unit
        override fun onGlassAiAssistStart() = Unit
        override fun onGlassAiAssistStop() = Unit
        override fun onGlassAiInterrupt(interrupted: Boolean) = Unit
        override fun onGlassLauncherResume() = Unit
    }
}
```

`AuthActivity.kt`:
```kotlin
package com.rokid.context.link

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Runs Hi Rokid authorization in-process (CXR-L needs an Activity) and closes itself (ported from Live Fit). */
class AuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = CxrGlassesLink.instance ?: return finish()
        link.authorize(this) { finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        CxrGlassesLink.instance?.let { link ->
            val r = runCatching { com.rokid.cxr.session.CxrSessionManager.getInstance(this).parseAuthorizationResult(resultCode, data) }.getOrNull()
            link.onAuthResult(r?.isSuccess == true)
        }
        finish()
    }

    companion object {
        fun launch(context: Context): Result<Unit> = runCatching {
            context.startActivity(
                Intent(context, AuthActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }
}
```

`CxrGlassesLink.kt`:
```kotlin
package com.rokid.context.link

import android.app.Activity
import android.content.Context
import android.util.Log
import com.rokid.context.core.model.LinkState
import com.rokid.cxr.Caps
import com.rokid.cxr.session.AiInterceptMode
import com.rokid.cxr.session.CloseReason
import com.rokid.cxr.session.CxrSession
import com.rokid.cxr.session.CxrSessionManager
import com.rokid.cxr.session.GlassPermission
import com.rokid.cxr.session.ICustomCmdSessionCallback
import com.rokid.cxr.session.ISessionLifecycleCbk
import com.rokid.cxr.session.PausedReason
import com.rokid.cxr.session.SessionConfig
import com.rokid.cxr.session.SessionErrorCode
import com.rokid.cxr.session.SessionTimeouts
import com.rokid.cxr.session.SessionType
import com.rokid.cxr.session.TerminatingReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Live glasses link over Rokid CXR-L via the Hi Rokid app (spec §1.5, §1.7), ported from Live Fit's CxrGlassesLink.
 * Lifecycle decisions come from [GlassesSessionPolicy]; this class only performs actions. Everything below is touched
 * only on [scope] (Main): CXR callbacks are posted there first, and callers use [send] from that scope.
 */
class CxrGlassesLink(context: Context, private val scope: CoroutineScope) : GlassesLink {
    private val app = context.applicationContext
    private val manager = CxrSessionManager.getInstance(app)
    private val policy = GlassesSessionPolicy()
    private var session: CxrSession? = null
    private val auth = AuthGate()
    private var authJob: Job? = null
    private var authDeclined = false
    private var retryJob: Job? = null
    private var connectJob: Job? = null
    private val inbound = GlassesInbound() // thread-safe: fed from CXR callback threads
    /** Session-less CXRLink kept bound only while "closed on glasses", to notice the wearer reopening our app there. */
    private val watcher = GlassAppWatcher(app, GLASSES_PKG) { post { act(policy.onEvent(LinkEvent.GlassesAppOpened)) } }

    private val _state = MutableStateFlow(if (token() == null) LinkState.NOT_AUTHORIZED else LinkState.OFFLINE)
    override val state: StateFlow<LinkState> = _state
    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 256) // audio arrives as ~10 chunks/s
    override val events: SharedFlow<GlassesEvent> = _events
    private val _ready = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val ready: SharedFlow<Unit> = _ready

    init { instance = this }

    private fun post(block: () -> Unit) { scope.launch { block() } }
    private fun token(): String? = app.getSharedPreferences(PREFS, 0).getString(KEY_TOKEN, null)
    private fun now() = System.currentTimeMillis()
    private fun setState(s: LinkState) { if (_state.value != s) { _state.value = s; Log.i(TAG, "link=$s") } }

    /** Silent when already authorized in Hi Rokid; sets CXR-L's in-memory flags (Live Fit quirk 2.1.1). */
    fun authorize(activity: Activity, onDone: (Boolean) -> Unit) {
        try {
            manager.requestAuthorization(activity, listOf(GlassPermission.MICROPHONE, GlassPermission.DEVICE_MANAGE, GlassPermission.MEDIA)) { r ->
                Log.i(TAG, "authorize ok=${r.isSuccess} code=${r.errorCode}")
                r.token?.takeIf { r.isSuccess }?.let { app.getSharedPreferences(PREFS, 0).edit().putString(KEY_TOKEN, it).apply() }
                // Success without any usable token would loop AuthActivity: treat it as a failure.
                val ok = r.isSuccess && (r.token != null || token() != null)
                post { onAuthResult(ok) }
                onDone(ok)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "authorize failed (${e.javaClass.simpleName})")
            post { onAuthResult(false) }
            onDone(false)
        }
    }

    /** Result of an in-process authorization (also fed by [AuthActivity.onActivityResult]). */
    internal fun onAuthResult(ok: Boolean) {
        if (!auth.result(ok)) return // late or duplicate report for an attempt already resolved
        authJob?.cancel(); authJob = null
        if (ok) { authDeclined = false; act(policy.manualConnect()) }
        else { authDeclined = true; act(policy.onEvent(LinkEvent.AuthFailed)) } // no auto-relaunch
    }

    override fun connect() = post { auth.clearStale(now()); authDeclined = false; act(policy.manualConnect()) }

    override fun connectOnce() = post { auth.clearStale(now()); act(policy.autoConnect()) }

    override fun connectOnResume() = post {
        if (!shouldReconnectOnResume(_state.value, token() != null, authDeclined)) return@post
        Log.i(TAG, "phone app resumed: reconnecting")
        auth.clearStale(now())
        act(policy.manualConnect())
    }

    override fun close() = post {
        watcher.stop()
        retryJob?.cancel(); connectJob?.cancel(); authJob?.cancel()
        val s = session
        session = null
        s?.close()
        setState(if (token() == null) LinkState.NOT_AUTHORIZED else LinkState.OFFLINE)
    }

    private fun act(actions: List<LinkAction>) {
        if (actions.isNotEmpty()) { retryJob?.cancel(); retryJob = null; connectJob?.cancel(); connectJob = null }
        for (a in actions) when (a) {
            LinkAction.Connect -> openSession()
            LinkAction.SendSettings -> _ready.tryEmit(Unit)
            is LinkAction.ScheduleRetry -> retryJob = scope.launch { delay(a.delayMs); act(policy.onEvent(LinkEvent.RetryTimer)) }
            else -> a.linkState()?.let(::setState)
        }
        syncWatcher()
    }

    /** Watch the glasses' foreground app only while closed on glasses; any other state releases the extra bind. */
    private fun syncWatcher() {
        if (!policy.closedOnGlasses) { watcher.stop(); return }
        if (watcher.active) return
        val t = token() ?: return
        preferGlobalHiRokid()
        watcher.start(t)
    }

    private fun openSession() {
        watcher.stop() // unbind the watcher before a session binds its own CXRLink
        val token = token()
        if (token == null || !auth.authorized) {
            // CXR-L only works after an in-process requestAuthorization, so connect waits for AuthActivity.
            if (authDeclined) { act(policy.onEvent(LinkEvent.AuthFailed)); return }
            if (!auth.begin(now())) return
            // A blocked background activity start never reports back: give up after the timeout.
            authJob = scope.launch {
                delay(AuthGate.AUTH_TIMEOUT_MS)
                auth.expire()
                authDeclined = true
                act(policy.onEvent(LinkEvent.AuthFailed))
            }
            if (AuthActivity.launch(app).isFailure) onAuthResult(false)
            return
        }
        connectJob = scope.launch { delay(CONNECT_TIMEOUT_MS); act(policy.onEvent(LinkEvent.ConnectTimeout)) }
        try {
            preferGlobalHiRokid()
            // Detach the old session first so its late close callback cannot clobber the new one.
            val old = session
            session = null
            old?.close()
            inbound.reset()
            val s = manager.create(
                SessionConfig(
                    sessionType = SessionType.CUSTOM_APP,
                    glassesPackageName = GLASSES_PKG,
                    aiInterceptMode = AiInterceptMode.ALLOW_WITH_PAUSE,
                    terminatingGracePeriodMs = 5_000L,
                    timeouts = SessionTimeouts(),
                    viewData = "",
                    viewIconData = "",
                    glassesActivityName = GLASSES_ACTIVITY,
                    glassesApkPath = "",
                ),
            )
            s.addLifecycleCallback(lifecycleFor(s))
            s.addCustomCmdCallback(object : ICustomCmdSessionCallback {
                override fun onCustomCmdResult(cmd: String, bytes: ByteArray?) = onGlassesMessage(cmd, bytes)
            })
            session = s
            s.connect(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "openSession failed (${e.javaClass.simpleName})")
            session = null
            act(policy.onEvent(LinkEvent.ConnectFailed))
        }
    }

    /** Forget the rejected token; the retry goes through the bounded authorization path in [openSession]. */
    private fun onTokenRejected() {
        app.getSharedPreferences(PREFS, 0).edit().remove(KEY_TOKEN).apply()
        if (auth.tokenRejected()) act(policy.onEvent(LinkEvent.ConnectFailed))
        else { authDeclined = true; act(policy.onEvent(LinkEvent.AuthFailed)) }
    }

    /** CXR callback thread. Glasses send text only (phase 0): read up to [MAX_PARTS] Caps strings in order. */
    private fun onGlassesMessage(cmd: String, bytes: ByteArray?) {
        val parts = try {
            val caps = Caps.fromBytes(bytes ?: return)
            buildList { for (i in 0 until MAX_PARTS) add(runCatching { caps.at(i).string }.getOrNull() ?: break) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "bad payload on $cmd: ${e.javaClass.simpleName}")
            return
        }
        inbound.onMessage(cmd, parts).forEach { _events.tryEmit(it) }
    }

    override suspend fun send(channel: String, json: String): Boolean {
        if (_state.value != LinkState.CONNECTED) return false
        val s = session ?: return false
        return try {
            val r = s.sendCustomCmd(channel, Caps().apply { write(json) }, ByteArray(0))
            if (!r.isSuccess) Log.w(TAG, "send $channel failed ${r.code}")
            r.isSuccess
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "send $channel threw ${e.javaClass.simpleName}")
            false
        }
    }

    /** CXR callbacks arrive on binder threads: hop onto [scope] before touching the policy or session. */
    private fun lifecycleFor(s: CxrSession) = object : ISessionLifecycleCbk {
        private fun ifCurrent(event: LinkEvent) = post { if (s === session) act(policy.onEvent(event)) }
        override fun onSessionStarted() = post { if (s === session) { auth.sessionStarted(); act(policy.onEvent(LinkEvent.Started)) } }
        override fun onSessionPaused(reason: PausedReason) = ifCurrent(LinkEvent.Paused)
        override fun onSessionResumed() = ifCurrent(LinkEvent.Resumed)
        override fun onSessionTerminating(reason: TerminatingReason, graceMs: Long) = Unit
        override fun onSessionClosed(reason: CloseReason) = post {
            if (s !== session) return@post
            session = null
            val glassesLeft = reason == CloseReason.GLASSES_EXIT || reason == CloseReason.USER_CLOSED
            Log.i(TAG, "session closed $reason")
            act(policy.onEvent(if (glassesLeft) LinkEvent.GlassesExited else LinkEvent.Closed))
        }
        override fun onConnectResult(ok: Boolean, code: SessionErrorCode?) = post {
            if (ok || s !== session) return@post
            session = null
            Log.w(TAG, "connect failed $code")
            if (code == SessionErrorCode.TOKEN_EXPIRED || code == SessionErrorCode.NOT_AUTHENTICATED) onTokenRejected()
            else act(policy.onEvent(LinkEvent.ConnectFailed))
        }
    }

    /** Live Fit quirk 2.1.1: after a restart CXR-L would bind the China Hi Rokid package (phase 0 used the global build). */
    private fun preferGlobalHiRokid() {
        val global = runCatching { app.packageManager.getPackageInfo("com.rokid.sprite.global.aiapp", 0) }.isSuccess
        if (!global) return
        try {
            Class.forName("com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper")
                .getDeclaredField("a").apply { isAccessible = true }.setBoolean(null, true)
        } catch (e: Exception) {
            Log.w(TAG, "could not force global Hi Rokid (${e.javaClass.simpleName})")
        }
    }

    companion object {
        const val TAG = "PcGlassesLink"
        /** The glasses app's applicationId (`glasses/build.gradle.kts`). */
        const val GLASSES_PKG = "com.rokid.context.glasses"
        const val GLASSES_ACTIVITY = "com.rokid.context.glasses.MainActivity"
        const val CONNECT_TIMEOUT_MS = 15_000L
        /** `pc_frame` carries two strings (meta + chunk); every other channel one. */
        private const val MAX_PARTS = 2
        internal const val PREFS = "rokid_link"
        internal const val KEY_TOKEN = "token"
        @Volatile var instance: CxrGlassesLink? = null
    }
}
```

- [ ] **Step 6: Run the tests and build the module**

Run: `./gradlew :services:glasses-link:testDebugUnitTest :services:glasses-link:assembleDebug`
Expected: PASS — `AuthGateTest` (7), `GlassesSessionPolicyTest` (16), `GlassesInboundTest` (7), `GlassesIdentityTest` (2); the AAR compiles against `client-l:1.1.2` / `cxr-service-bridge:1.5`.

- [ ] **Step 7: Commit**

```bash
git add android-companion/settings.gradle.kts android-companion/services/glasses-link
git commit -m "feat(link): add :services:glasses-link (CXR-L session, auth, watcher, inbound gate)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Phone frame-receive logic — reassembly, clock offset, dedupe ledger, admission

**Requirements:** R-08 (reassembly per `frameId`, 10 s partial discard, CRC → `ok=false`, restart from chunk 0, phone dedupe by `frameId`), R-65 (part: offset-corrected pause instants, flush filter over open and closed intervals, fail-closed before a clock offset, 7-day history window — Review Focus 1 / master Review Focus 4).

All pure JVM code in `:services:glasses-link` (no CXR imports), so it is fully unit-tested here and wired in Task 6.

**Files:**
- Create (in `android-companion/services/glasses-link/src/main/kotlin/com/rokid/context/link/`): `FrameReassembler.kt`, `ClockOffset.kt`, `ReceivedFrameLedger.kt`, `FrameAdmission.kt`
- Test (in `.../services/glasses-link/src/test/kotlin/com/rokid/context/link/`): `FrameReassemblerTest.kt`, `ClockOffsetTest.kt`, `ReceivedFrameLedgerTest.kt`, `FrameAdmissionTest.kt`

**Interfaces:**
- Consumes: `FrameChunk`, `FrameMeta`, `FrameCodec`, `JoinResult`, `FrameAckReason`, `PauseRecord` (Task 1).
- Produces:
  - `sealed interface Assembly { Pending; class Complete(meta: FrameMeta, jpeg: ByteArray); data class Rejected(frameId: String, reason: String); Ignored }`; `class FrameReassembler(staleMs: Long = 10_000) { fun accept(chunk: FrameChunk, nowMs: Long): Assembly; fun sweep(nowMs: Long): Int; val pendingCount: Int }`.
  - `data class ClockOffset(glassesMinusPhoneMs: Long) { fun toPhone(glassesMs): Long; fun toGlasses(phoneMs): Long; fun recordToPhone(r): PauseRecord; fun recordToGlasses(r): PauseRecord; companion { fun fromHello(glassesNowMs, phoneReceivedAtMs): ClockOffset; fun anchoredAt(r: PauseRecord, nowMs: Long): PauseRecord } }`.
  - `class ReceivedFrameLedger(file: File, capacity: Int = 1_000) { fun contains(frameId): Boolean; fun add(frameId) /* throws IOException */ }`.
  - `interface PauseHistory { suspend fun pausedAt(phoneMs: Long): Boolean }`; `sealed interface Admission { data class Ingest(capturedAtPhoneMs: Long); data class AckOnly(reason: String); data class Reject(reason: String); Hold }`; `class FrameAdmission(ledger, pauses: PauseHistory, historyMs: Long = HISTORY_MS) { suspend fun decide(meta: FrameMeta, offset: ClockOffset?, nowMs: Long): Admission; companion { const val HISTORY_MS = 7 days } }`.

- [ ] **Step 1: Write the failing tests**

`FrameReassemblerTest.kt`:
```kotlin
package com.rokid.context.link

import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.FrameCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FrameReassemblerTest {
    private val jpeg = ByteArray(5_000) { (it % 251).toByte() }
    /** 5 000 B → 6 668 Base64 chars → four chunks of ≤ 2 000. */
    private fun chunks(id: String = "f1") = FrameCodec.split(id, jpeg, capturedAtMs = 7, chunkChars = 2_000)

    @Test fun singleChunkFrameCompletesAtOnce() {
        val r = FrameReassembler().accept(FrameCodec.split("f1", jpeg, capturedAtMs = 7).single(), nowMs = 0)
        assertIs<Assembly.Complete>(r)
        assertContentEquals(jpeg, r.jpeg)
    }

    @Test fun chunksInAnyOrderComplete() {
        val r = FrameReassembler()
        val c = chunks()
        assertEquals(Assembly.Pending, r.accept(c[2], 0))
        assertEquals(Assembly.Pending, r.accept(c[0], 1))
        assertEquals(Assembly.Pending, r.accept(c[3], 2))
        val done = r.accept(c[1], 3)
        assertIs<Assembly.Complete>(done)
        assertContentEquals(jpeg, done.jpeg)
        assertEquals(0, r.pendingCount)
    }

    @Test fun crcMismatchIsRejected() {
        val r = FrameReassembler()
        val c = chunks()
        val bad = c[1].copy(chunk = c[1].chunk.replaceRange(5, 6, if (c[1].chunk[5] == 'A') "B" else "A"))
        r.accept(c[0], 0); r.accept(bad, 0); r.accept(c[2], 0)
        assertEquals(Assembly.Rejected("f1", FrameAckReason.CRC), r.accept(c[3], 0))
    }

    @Test fun partialFrameIsDiscardedAfterTenSecondsWithoutAChunk() {
        val r = FrameReassembler()
        val c = chunks()
        r.accept(c[0], 0); r.accept(c[1], 4_000)
        assertEquals(0, r.sweep(13_999))
        assertEquals(1, r.sweep(14_000))
        assertEquals(0, r.pendingCount)
        r.accept(c[2], 14_001)
        assertEquals(Assembly.Pending, r.accept(c[3], 14_002), "the discarded half cannot complete")
    }

    @Test fun retryFromChunkZeroRestartsTheFrame() {
        val r = FrameReassembler()
        val c = chunks()
        r.accept(c[0], 0); r.accept(c[1], 1) // attempt 1 lost chunks 2 and 3
        c.forEachIndexed { i, ch ->
            val out = r.accept(ch, 10L + i)
            if (i < 3) assertEquals(Assembly.Pending, out) else assertIs<Assembly.Complete>(out)
        }
    }

    @Test fun badIndexesAreIgnored() {
        val m = chunks()[0].meta
        assertEquals(Assembly.Ignored, FrameReassembler().accept(FrameChunk(m.copy(chunkIdx = 4), "AAAA"), 0))
        assertEquals(Assembly.Ignored, FrameReassembler().accept(FrameChunk(m.copy(chunkCount = 0, chunkIdx = 0), "AAAA"), 0))
        assertEquals(Assembly.Ignored, FrameReassembler().accept(FrameChunk(m.copy(chunkCount = 65, chunkIdx = 0), "AAAA"), 0))
    }

    @Test fun twoFramesInterleave() {
        val r = FrameReassembler()
        val a = chunks("fa"); val b = chunks("fb")
        for (i in 0 until 3) { r.accept(a[i], 0); r.accept(b[i], 0) }
        assertIs<Assembly.Complete>(r.accept(b[3], 0))
        assertIs<Assembly.Complete>(r.accept(a[3], 0))
    }
}
```

`ClockOffsetTest.kt`:
```kotlin
package com.rokid.context.link

import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClockOffsetTest {
    private val twoHours = 2 * 3_600_000L

    /** Phase 0: the glasses wall clock ran ~107–144 ms ahead of the phone. */
    @Test fun helloMeasuresGlassesAhead() {
        val o = ClockOffset.fromHello(glassesNowMs = 10_140, phoneReceivedAtMs = 10_000)
        assertEquals(140, o.glassesMinusPhoneMs)
        assertEquals(10_000, o.toPhone(10_140))
        assertEquals(10_140, o.toGlasses(10_000))
    }

    /** Review Focus 1: a glasses clock 2 h off still expires a 30-min pause at the same real instant. */
    @Test fun timedGlassesPauseExpiresAtTheSamePhoneInstant() {
        val o = ClockOffset(twoHours)
        val g0 = 1_793_000_000_000L
        val onGlasses = PauseRecord("p", PauseOrigin.GLASSES, createdAtMs = g0, untilMs = g0 + 30 * 60_000)
        val onPhone = o.recordToPhone(onGlasses)
        assertEquals(g0 - twoHours, onPhone.createdAtMs)
        assertTrue(onPhone.isActiveAt(g0 - twoHours + 30 * 60_000 - 1))
        assertFalse(onPhone.isActiveAt(g0 - twoHours + 30 * 60_000))
        assertEquals(onGlasses, o.recordToGlasses(onPhone), "round trip")
    }

    /** 2026-10-25 01:00 UTC: Europe/London falls back an hour. Epoch instants are untouched by it. */
    @Test fun dstChangeDoesNotMoveAnExpiry() {
        val start = Instant.parse("2026-10-25T00:45:00Z").toEpochMilli()
        val r = ClockOffset(0).recordToPhone(PauseRecord("p", PauseOrigin.PHONE, start, start + 30 * 60_000))
        assertEquals(Instant.parse("2026-10-25T01:15:00Z").toEpochMilli(), r.untilMs)
    }

    @Test fun anchoringKeepsTheDurationWithoutAnOffset() {
        val r = ClockOffset.anchoredAt(PauseRecord("p", PauseOrigin.GLASSES, createdAtMs = 5_000, untilMs = 65_000), nowMs = 1_000_000)
        assertEquals(1_000_000, r.createdAtMs)
        assertEquals(1_060_000, r.untilMs)
        assertNull(ClockOffset.anchoredAt(PauseRecord("q", PauseOrigin.GLASSES, 5_000, null), nowMs = 9).untilMs)
    }
}
```

`ReceivedFrameLedgerTest.kt`:
```kotlin
package com.rokid.context.link

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceivedFrameLedgerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file get() = File(tmp.root, "ledger.txt")

    @Test fun idsSurviveARestart() {
        ReceivedFrameLedger(file).add("g1")
        assertTrue(ReceivedFrameLedger(file).contains("g1"))
        assertFalse(ReceivedFrameLedger(file).contains("g2"))
    }

    @Test fun oldestIdsAreEvictedAtCapacity() {
        val l = ReceivedFrameLedger(file, capacity = 3)
        listOf("a", "b", "c", "d").forEach(l::add)
        assertFalse(l.contains("a"))
        assertTrue(l.contains("d"))
        assertFalse(ReceivedFrameLedger(file, capacity = 3).contains("a"))
    }

    @Test fun unsafeLinesAreIgnored() {
        file.writeText("ok_1\n../x\n\n")
        val l = ReceivedFrameLedger(file)
        assertTrue(l.contains("ok_1"))
        assertFalse(l.contains("../x"))
    }

    /** A failed write must not leave the id "known": the resend would then be acked as a duplicate and lost. */
    @Test fun failedWriteForgetsTheId() {
        val dir = File(tmp.root, "ro").apply { mkdirs() }
        val l = ReceivedFrameLedger(File(dir, "ledger.txt"))
        dir.setWritable(false)
        try {
            assertFailsWith<IOException> { l.add("g1") }
            assertFalse(l.contains("g1"))
        } finally {
            dir.setWritable(true)
        }
    }
}
```

`FrameAdmissionTest.kt`:
```kotlin
package com.rokid.context.link

import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameMeta
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameAdmissionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val twoHours = 2 * 3_600_000L
    private val phoneNow = 1_793_000_000_000L

    /** Half-open phone-clock intervals [start, end); end null = still paused. */
    private class Intervals(vararg val spans: Pair<Long, Long?>) : PauseHistory {
        override suspend fun pausedAt(phoneMs: Long) = spans.any { (s, e) -> phoneMs >= s && (e == null || phoneMs < e) }
    }
    private fun ledger() = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
    private fun meta(id: String, capturedAtGlassesMs: Long) = FrameMeta(frameId = id, chunkIdx = 0, chunkCount = 1, crc32 = 1, capturedAtMs = capturedAtGlassesMs)

    /** Spec §5.5 fail-closed: no `pc_hello` offset yet → no ack, the glasses keep the frame. */
    @Test fun withoutAHelloOffsetFramesAreHeld() = runTest {
        assertEquals(Admission.Hold, FrameAdmission(ledger(), Intervals()).decide(meta("g1", phoneNow), offset = null, nowMs = phoneNow))
    }

    /** Review Focus 1: glasses 2 h ahead; phone paused for 30 min ending 30 min ago; a frame taken 10 min into it is dropped. */
    @Test fun frameInsideAPauseIsDroppedUsingTheCorrectedTime() = runTest {
        val pauseStart = phoneNow - 60 * 60_000
        val a = FrameAdmission(ledger(), Intervals(pauseStart to pauseStart + 30 * 60_000))
        val capturedOnGlasses = pauseStart + 10 * 60_000 + twoHours
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("g1", capturedOnGlasses), ClockOffset(twoHours), phoneNow))
        // Uncorrected, the same stamp lands 70 min in the future, is clamped to now and slips past the pause.
        assertEquals(Admission.Ingest(phoneNow), a.decide(meta("g1", capturedOnGlasses), ClockOffset(0), phoneNow))
    }

    @Test fun frameOutsidePausesIsIngestedAtPhoneTime() = runTest {
        val a = FrameAdmission(ledger(), Intervals((phoneNow - 3_600_000) to (phoneNow - 1_800_000)))
        assertEquals(Admission.Ingest(phoneNow - 60_000), a.decide(meta("g2", phoneNow - 60_000 + twoHours), ClockOffset(twoHours), phoneNow))
    }

    @Test fun closedIntervalsAreHalfOpen() = runTest {
        val start = phoneNow - 600_000
        val end = phoneNow - 300_000
        val a = FrameAdmission(ledger(), Intervals(start to end))
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("a", start), ClockOffset(0), phoneNow))
        assertEquals(Admission.Ingest(end), a.decide(meta("b", end), ClockOffset(0), phoneNow))
    }

    @Test fun anOpenIntervalDropsEverythingSinceItsStart() = runTest {
        val a = FrameAdmission(ledger(), Intervals((phoneNow - 1_000) to null))
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("c", phoneNow - 10), ClockOffset(0), phoneNow))
    }

    /** Ack lost after ingest → the glasses resend → acked again, never ingested twice (also before a hello). */
    @Test fun alreadyIngestedFrameIsAckedNotReingested() = runTest {
        val l = ledger()
        l.add("g9")
        assertEquals(Admission.AckOnly(FrameAckReason.DUPLICATE), FrameAdmission(l, Intervals()).decide(meta("g9", phoneNow), offset = null, nowMs = phoneNow))
    }

    @Test fun framesOlderThanThePauseHistoryAreDropped() = runTest {
        val a = FrameAdmission(ledger(), Intervals())
        assertEquals(Admission.AckOnly(FrameAckReason.TOO_OLD), a.decide(meta("old", phoneNow - FrameAdmission.HISTORY_MS - 1), ClockOffset(0), phoneNow))
        assertEquals(Admission.Ingest(phoneNow - FrameAdmission.HISTORY_MS), a.decide(meta("edge", phoneNow - FrameAdmission.HISTORY_MS), ClockOffset(0), phoneNow))
    }

    @Test fun unsafeIdIsRejected() = runTest {
        assertEquals(Admission.Reject(FrameAckReason.DECODE), FrameAdmission(ledger(), Intervals()).decide(meta("../x", phoneNow), ClockOffset(0), phoneNow))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :services:glasses-link:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: FrameReassembler`, `ClockOffset`, `ReceivedFrameLedger`, `FrameAdmission`.

- [ ] **Step 3: Implement `FrameReassembler.kt`**

```kotlin
package com.rokid.context.link

import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.FrameMeta
import com.rokid.context.core.model.JoinResult

sealed interface Assembly {
    data object Pending : Assembly
    class Complete(val meta: FrameMeta, val jpeg: ByteArray) : Assembly
    /** Ack `ok=false` with [reason]: the glasses resend once, then drop the frame (spec §1.8). */
    data class Rejected(val frameId: String, val reason: String) : Assembly
    /** Not a usable chunk (bad index/count): no ack, so the glasses' timeout retries it. */
    data object Ignored : Assembly
}

/**
 * Phone-side reassembly per `frameId` (spec §1.8). Chunks may arrive in any order; a second chunk 0 for a frame that
 * already has one is the glasses' retry from chunk 0 and restarts it; a partial frame without a new chunk for
 * [staleMs] is discarded. Thread-safe.
 */
class FrameReassembler(private val staleMs: Long = STALE_MS) {
    private class Partial(val meta: FrameMeta, val parts: Array<String?>, var lastChunkAtMs: Long)
    private val partials = HashMap<String, Partial>()

    val pendingCount: Int @Synchronized get() = partials.size

    @Synchronized fun accept(chunk: FrameChunk, nowMs: Long): Assembly {
        sweep(nowMs)
        val m = chunk.meta
        if (m.chunkCount !in 1..MAX_CHUNKS || m.chunkIdx !in 0 until m.chunkCount) return Assembly.Ignored
        val existing = partials[m.frameId]
        val restart = existing == null || existing.meta.chunkCount != m.chunkCount || existing.meta.crc32 != m.crc32 ||
            (m.chunkIdx == 0 && existing.parts[0] != null)
        val p = if (restart) Partial(m, arrayOfNulls(m.chunkCount), nowMs).also { partials[m.frameId] = it } else existing!!
        p.parts[m.chunkIdx] = chunk.chunk
        p.lastChunkAtMs = nowMs
        if (p.parts.any { it == null }) return Assembly.Pending
        partials.remove(m.frameId)
        return when (val r = FrameCodec.join(p.meta, p.parts.map { it!! })) {
            is JoinResult.Ok -> Assembly.Complete(p.meta, r.jpeg)
            JoinResult.CrcMismatch -> Assembly.Rejected(m.frameId, FrameAckReason.CRC)
            JoinResult.BadBase64 -> Assembly.Rejected(m.frameId, FrameAckReason.DECODE)
        }
    }

    /** Drops partial frames idle for [staleMs] or more; returns how many. */
    @Synchronized fun sweep(nowMs: Long): Int {
        val stale = partials.filterValues { nowMs - it.lastChunkAtMs >= staleMs }.keys.toList()
        stale.forEach { partials.remove(it) }
        return stale.size
    }

    companion object {
        const val STALE_MS = 10_000L
        /** 64 × 32 768 chars ≈ 1.5 MB of JPEG: far above a 20–40 KB frame, small enough to bound memory. */
        const val MAX_CHUNKS = 64
    }
}
```

- [ ] **Step 4: Implement `ClockOffset.kt`**

```kotlin
package com.rokid.context.link

import com.rokid.context.core.model.PauseRecord

/**
 * Glasses wall clock minus phone wall clock, measured from `pc_hello` (spec §5.5); one-way latency (30–60 ms in phase 0)
 * is ignored. All values are epoch ms, so time zones and DST never enter this math.
 */
data class ClockOffset(val glassesMinusPhoneMs: Long) {
    fun toPhone(glassesMs: Long): Long = glassesMs - glassesMinusPhoneMs
    fun toGlasses(phoneMs: Long): Long = phoneMs + glassesMinusPhoneMs
    fun recordToPhone(r: PauseRecord): PauseRecord = r.copy(createdAtMs = toPhone(r.createdAtMs), untilMs = r.untilMs?.let(::toPhone))
    fun recordToGlasses(r: PauseRecord): PauseRecord = r.copy(createdAtMs = toGlasses(r.createdAtMs), untilMs = r.untilMs?.let(::toGlasses))

    companion object {
        fun fromHello(glassesNowMs: Long, phoneReceivedAtMs: Long) = ClockOffset(glassesNowMs - phoneReceivedAtMs)

        /** No offset yet: keep the record's duration but start it at the phone's [nowMs] (never shorter: fail-closed). */
        fun anchoredAt(r: PauseRecord, nowMs: Long): PauseRecord =
            r.copy(createdAtMs = nowMs, untilMs = r.untilMs?.let { nowMs + (it - r.createdAtMs).coerceAtLeast(0) })
    }
}
```

- [ ] **Step 5: Implement `ReceivedFrameLedger.kt`**

```kotlin
package com.rokid.context.link

import com.rokid.context.core.model.FrameCodec
import java.io.File
import java.io.IOException

/**
 * Frame ids the phone has already stored (spec §1.8 dedupe). The newest [capacity] ids are kept on disk, so a resend
 * after a lost ack is never ingested twice, even across a restart. The glasses hold at most 200 frames, so 1 000 ids
 * covers every frame that can still be resent.
 */
class ReceivedFrameLedger(private val file: File, private val capacity: Int = 1_000) {
    private val ids = LinkedHashSet<String>()

    init {
        if (file.isFile) file.readLines().map { it.trim() }.filter { FrameCodec.isValidFrameId(it) }.forEach { ids += it }
        trim()
    }

    @Synchronized fun contains(frameId: String): Boolean = frameId in ids

    /** Records [frameId] durably (atomic rewrite) before the caller acks the frame; on failure the id is not kept. */
    @Synchronized fun add(frameId: String) {
        require(FrameCodec.isValidFrameId(frameId)) { "bad frame id" }
        if (!ids.add(frameId)) return
        try {
            trim()
            val dir = file.absoluteFile.parentFile ?: throw IOException("no ledger directory")
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeText(ids.joinToString("\n", postfix = "\n"))
            if (!tmp.renameTo(file)) throw IOException("ledger rename failed")
        } catch (e: IOException) {
            ids.remove(frameId)
            throw e
        }
    }

    private fun trim() { while (ids.size > capacity) ids.remove(ids.first()) }
}
```

- [ ] **Step 6: Implement `FrameAdmission.kt`**

```kotlin
package com.rokid.context.link

import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.FrameMeta

/** Retained pause history in phone clock (spec §5.5); the hub's Room-backed `HubPauses` in production. */
interface PauseHistory {
    /** True when [phoneMs] falls inside a retained pause interval, open or closed. */
    suspend fun pausedAt(phoneMs: Long): Boolean
}

sealed interface Admission {
    /** Store and ingest; [capturedAtPhoneMs] is the offset-corrected capture time. Ack ok=true after the write. */
    data class Ingest(val capturedAtPhoneMs: Long) : Admission
    /** Ack ok=true with [reason] without ingesting: the glasses delete the frame. */
    data class AckOnly(val reason: String) : Admission
    /** Ack ok=false with [reason]: the glasses resend once, then drop it. */
    data class Reject(val reason: String) : Admission
    /** No ack: the glasses keep the frame and retry on a later flush (fail-closed). */
    data object Hold : Admission
}

/** What the phone does with a complete frame (spec §1.8 dedupe, §5.5 flush filter). */
class FrameAdmission(private val ledger: ReceivedFrameLedger, private val pauses: PauseHistory, private val historyMs: Long = HISTORY_MS) {
    suspend fun decide(meta: FrameMeta, offset: ClockOffset?, nowMs: Long): Admission {
        if (!FrameCodec.isValidFrameId(meta.frameId)) return Admission.Reject(FrameAckReason.DECODE)
        if (ledger.contains(meta.frameId)) return Admission.AckOnly(FrameAckReason.DUPLICATE)
        offset ?: return Admission.Hold
        // A frame "from the future" means the offset is stale: it cannot have been taken after now.
        val t = offset.toPhone(meta.capturedAtMs).coerceAtMost(nowMs)
        if (t < nowMs - historyMs) return Admission.AckOnly(FrameAckReason.TOO_OLD)
        if (pauses.pausedAt(t)) return Admission.AckOnly(FrameAckReason.PAUSED_DROP)
        return Admission.Ingest(t)
    }

    companion object {
        /** Pause intervals are kept 7 days (spec §5.5); an older frame cannot be checked, so it is dropped. */
        const val HISTORY_MS = 7 * 24 * 3_600_000L
    }
}
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew :services:glasses-link:testDebugUnitTest`
Expected: PASS — Task 3's 32 tests plus `FrameReassemblerTest` (7), `ClockOffsetTest` (4), `ReceivedFrameLedgerTest` (4), `FrameAdmissionTest` (8).

- [ ] **Step 8: Commit**

```bash
git add android-companion/services/glasses-link
git commit -m "feat(link): frame reassembly, clock offset, dedupe ledger and admission

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Phone hub state — pause store, StateFrame builder, ≤ 4 Hz broadcaster, status mapping

**Requirements:** R-06 (full `StateFrame`, ≤ 4 Hz, heartbeat), R-07 (pause authority exception: phone set persisted, union with glasses, nothing resumes implicitly), R-65 (part: `pause_record` + retained `pause_interval` with effective end, 7-day pruning, phone→glasses clock conversion), R-02 (Live Fit `StateBroadcaster` adapted).

Ported: `/Users/<user>/Desktop/rokid/live-fitness-tracker/services/sync/src/main/kotlin/com/debasish/livefit/sync/StateBroadcaster.kt` (coalescing + heartbeat; here a hard ≥ 250 ms gap between sends).

**Files:**
- Modify: `android-companion/services/memory/src/main/java/com/rokid/context/memory/db/AppDaos.kt` (add `PauseDao`), `.../memory/db/ContextDatabase.kt` (add `pauseDao()`; schema unchanged, version stays 1)
- Test: `android-companion/services/memory/src/test/java/com/rokid/context/memory/db/PauseDaoTest.kt`
- Modify: `android-companion/phone/build.gradle.kts` (dependency lines + `testOptions`)
- Create (in `android-companion/phone/src/main/java/com/rokid/context/phone/hub/`): `HubPauses.kt`, `StateBroadcaster.kt`, `StateFrameBuilder.kt`, `HubState.kt`
- Test (in `android-companion/phone/src/test/java/com/rokid/context/phone/hub/`): `TestDb.kt`, `HubPausesTest.kt`, `StateBroadcasterTest.kt`, `StateFrameBuilderTest.kt`, `HubStateTest.kt`

**Interfaces:**
- Consumes: `PauseRecordEntity(pauseId, origin, createdAtMs, untilMs)`, `PauseIntervalEntity(pauseId, origin, startMs, effectiveEndMs)` (Phase 2 schema); `PauseHistory`, `ClockOffset`, `FrameAdmission.HISTORY_MS` (Task 4); `GlassesStatus`, `LinkState`, `UserFacingError(s)` (Task 1); `SummaryTrace(summaryId, state, updatedAtEpochMs, note)`.
- Produces:
  - `interface PauseDao { upsertRecord, records(), deleteRecords(ids), insertInterval(i): Long, closeInterval(pauseId, endMs), countCovering(t): Int, pruneEndedBefore(before), intervals() }`; `ContextDatabase.pauseDao()`.
  - `class HubPauses(dao: PauseDao, now: () -> Long = System::currentTimeMillis) : PauseHistory { suspend fun active(): List<PauseRecord>; suspend fun add(r); suspend fun union(rs); suspend fun resume(clears: Set<String>); suspend fun pruneHistory() }`
  - `class StateBroadcaster(scope, minIntervalMs = 250, heartbeatMs = 10_000, onError: (Throwable) -> Unit = {}, send: suspend () -> Unit) { fun markDirty(); fun start(): Job }`
  - `data class HubInputs(...)`; `object StateFrameBuilder { fun build(i: HubInputs): StateFrame; fun settingsFor(settings: HudSettings, activePauses: List<PauseRecord>, offset: ClockOffset?): HudSettings }`; `object AiHealthRule { fun from(traces: List<SummaryTrace>, nowMs: Long): Health }`
  - `data class HubState(link, versionMismatch, glassesBatteryPct, bufferedFrames, lastFrameAtMs, nextCaptureAtMs, offset, error, lastFrame) { fun toGlassesStatus(): GlassesStatus }`
  - test helper `fun testDb(context: Context): ContextDatabase`.

- [ ] **Step 1: Write the failing DAO test**

`services/memory/src/test/java/com/rokid/context/memory/db/PauseDaoTest.kt`:
```kotlin
package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PauseDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao get() = db.pauseDao()
    @After fun close() = db.close()

    @Test fun coveringIsHalfOpenAndAnOpenIntervalCoversTheFuture() = runTest {
        dao.insertInterval(PauseIntervalEntity("a", "PHONE", startMs = 100, effectiveEndMs = 200))
        dao.insertInterval(PauseIntervalEntity("b", "GLASSES", startMs = 500, effectiveEndMs = null))
        assertEquals(1, dao.countCovering(100)); assertEquals(1, dao.countCovering(199)); assertEquals(0, dao.countCovering(200))
        assertEquals(0, dao.countCovering(499)); assertEquals(1, dao.countCovering(10_000))
    }

    @Test fun closingOnlyEndsAnOpenInterval() = runTest {
        dao.insertInterval(PauseIntervalEntity("a", "PHONE", 100, null))
        dao.closeInterval("a", 300)
        dao.closeInterval("a", 900)
        assertEquals(300L, dao.intervals().single().effectiveEndMs)
    }

    @Test fun duplicateIntervalInsertIsIgnored() = runTest {
        assertTrue(dao.insertInterval(PauseIntervalEntity("a", "PHONE", 100, null)) > 0)
        assertEquals(-1L, dao.insertInterval(PauseIntervalEntity("a", "PHONE", 999, null)))
        assertEquals(100L, dao.intervals().single().startMs)
    }

    @Test fun pruneKeepsOpenAndRecentIntervals() = runTest {
        dao.insertInterval(PauseIntervalEntity("old", "PHONE", 1, 50))
        dao.insertInterval(PauseIntervalEntity("recent", "PHONE", 1, 500))
        dao.insertInterval(PauseIntervalEntity("open", "PHONE", 1, null))
        dao.pruneEndedBefore(100)
        assertEquals(setOf("recent", "open"), dao.intervals().map { it.pauseId }.toSet())
    }

    @Test fun recordsUpsertAndDelete() = runTest {
        dao.upsertRecord(PauseRecordEntity("r1", "GLASSES", 10, null))
        dao.upsertRecord(PauseRecordEntity("r1", "GLASSES", 10, 99))
        assertEquals(99L, dao.records().single().untilMs)
        dao.deleteRecords(listOf("r1"))
        assertTrue(dao.records().isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :services:memory:testDebugUnitTest --tests "*PauseDaoTest"`
Expected: FAIL — `Unresolved reference: pauseDao`.

- [ ] **Step 3: Add `PauseDao`**

In `AppDaos.kt`, add the imports `androidx.room.Insert` and `androidx.room.OnConflictStrategy`, then append:
```kotlin
/** Pause set (spec §5.5). `pause_record` = active records; `pause_interval` = retained history (open = effectiveEndMs null). */
@Dao
interface PauseDao {
    @Upsert suspend fun upsertRecord(record: PauseRecordEntity)
    @Query("SELECT * FROM pause_record ORDER BY createdAtMs ASC") suspend fun records(): List<PauseRecordEntity>
    @Query("DELETE FROM pause_record WHERE pauseId IN (:ids)") suspend fun deleteRecords(ids: List<String>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertInterval(interval: PauseIntervalEntity): Long
    @Query("UPDATE pause_interval SET effectiveEndMs = :endMs WHERE pauseId = :pauseId AND effectiveEndMs IS NULL")
    suspend fun closeInterval(pauseId: String, endMs: Long)
    @Query("SELECT COUNT(*) FROM pause_interval WHERE startMs <= :t AND (effectiveEndMs IS NULL OR :t < effectiveEndMs)")
    suspend fun countCovering(t: Long): Int
    @Query("DELETE FROM pause_interval WHERE effectiveEndMs IS NOT NULL AND effectiveEndMs < :before")
    suspend fun pruneEndedBefore(before: Long)
    @Query("SELECT * FROM pause_interval ORDER BY startMs ASC") suspend fun intervals(): List<PauseIntervalEntity>
}
```
In `ContextDatabase.kt`, add after `abstract fun roleBindingDao(): RoleBindingDao`:
```kotlin
    abstract fun pauseDao(): PauseDao
```
(No entity or column changes: `version = 1` and `schemas/.../1.json` stay as they are.)

- [ ] **Step 4: Run the memory tests**

Run: `./gradlew :services:memory:testDebugUnitTest`
Expected: PASS — including `SchemaTest` unchanged and `PauseDaoTest` (5).

- [ ] **Step 5: Phone dependencies and test setup**

In `phone/build.gradle.kts`, inside `android { … }` add:
```kotlin
    testOptions { unitTests.isIncludeAndroidResources = true }
```
and in `dependencies` add:
```kotlin
    implementation(project(":core:model"))
    implementation(project(":services:glasses-link"))
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
```

`phone/src/test/java/com/rokid/context/phone/hub/TestDb.kt`:
```kotlin
package com.rokid.context.phone.hub

import android.content.Context
import androidx.room.Room
import com.rokid.context.memory.db.ContextDatabase

/** In-memory Room that runs queries on the calling thread, so hub coroutines stay on the test scheduler. */
fun testDb(context: Context): ContextDatabase =
    Room.inMemoryDatabaseBuilder(context, ContextDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()
```

- [ ] **Step 6: Write the failing hub-state tests**

`HubPausesTest.kt`:
```kotlin
package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HubPausesTest {
    private val db = testDb(ApplicationProvider.getApplicationContext())
    private var t = 1_793_000_000_000L
    private val pauses = HubPauses(db.pauseDao()) { t }
    @After fun close() = db.close()

    @Test fun indefinitePauseStaysUntilResumedByName() = runTest {
        pauses.add(PauseRecord("g1", PauseOrigin.GLASSES, createdAtMs = t, untilMs = null))
        t += 86_400_000
        assertEquals(listOf("g1"), pauses.active().map { it.pauseId })
        pauses.resume(setOf("other"))
        assertEquals(1, pauses.active().size)
        pauses.resume(setOf("g1"))
        assertTrue(pauses.active().isEmpty())
    }

    @Test fun timedPauseExpiresAndKeepsItsInterval() = runTest {
        val start = t
        pauses.add(PauseRecord("p1", PauseOrigin.PHONE, start, start + 1_800_000))
        assertTrue(pauses.pausedAt(start + 60_000))
        t = start + 1_800_000
        assertTrue(pauses.active().isEmpty())
        assertTrue("closed interval is retained", pauses.pausedAt(start + 1_799_999))
        assertFalse(pauses.pausedAt(start + 1_800_000))
    }

    @Test fun resumeClosesTheIntervalAtResumeTime() = runTest {
        pauses.add(PauseRecord("g1", PauseOrigin.GLASSES, t, null))
        t += 5_000
        pauses.resume(setOf("g1"))
        assertTrue(pauses.pausedAt(t - 1))
        assertFalse(pauses.pausedAt(t))
    }

    @Test fun unionAddsOnlyNewRecords() = runTest {
        pauses.add(PauseRecord("p1", PauseOrigin.PHONE, t, null))
        pauses.union(listOf(PauseRecord("p1", PauseOrigin.PHONE, t, null), PauseRecord("g2", PauseOrigin.GLASSES, t, null)))
        assertEquals(setOf("p1", "g2"), pauses.active().map { it.pauseId }.toSet())
    }

    @Test fun anAlreadyExpiredRecordOnlyAddsHistory() = runTest {
        pauses.union(listOf(PauseRecord("old", PauseOrigin.GLASSES, t - 10_000, t - 5_000)))
        assertTrue(pauses.active().isEmpty())
        assertTrue(pauses.pausedAt(t - 7_000))
        assertFalse(pauses.pausedAt(t - 5_000))
    }

    @Test fun historyOlderThanSevenDaysIsPruned() = runTest {
        pauses.add(PauseRecord("p", PauseOrigin.PHONE, t, t + 1_000))
        t += 8 * 86_400_000L
        pauses.active()
        pauses.pruneHistory()
        assertFalse(pauses.pausedAt(t - 8 * 86_400_000L + 500))
    }
}
```

`StateBroadcasterTest.kt`:
```kotlin
package com.rokid.context.phone.hub

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateBroadcasterTest {
    /** Spec §1.6: ≤ 4 Hz even when state changes 20 times a second. */
    @Test fun neverSendsMoreThanFourTimesASecond() = runTest {
        val sentAt = mutableListOf<Long>()
        val b = StateBroadcaster(backgroundScope, heartbeatMs = 60_000) { sentAt += testScheduler.currentTime }
        b.start()
        repeat(40) { b.markDirty(); delay(50) }
        advanceTimeBy(1_000)
        assertTrue("$sentAt", sentAt.zipWithNext { a, c -> c - a }.all { it >= 250 })
        assertTrue("$sentAt", sentAt.count { it < 1_000 } <= 4)
    }

    @Test fun theLatestChangeIsAlwaysSent() = runTest {
        var value = 0
        var sentValue = -1
        val b = StateBroadcaster(backgroundScope, heartbeatMs = 60_000) { sentValue = value }
        b.start(); runCurrent()
        repeat(10) { value = it + 1; b.markDirty(); delay(10) }
        advanceTimeBy(500)
        assertEquals(10, sentValue)
    }

    @Test fun heartbeatSendsWithoutChanges() = runTest {
        var sends = 0
        val b = StateBroadcaster(backgroundScope, heartbeatMs = 10_000) { sends++ }
        b.start()
        advanceTimeBy(35_000) // at 10 000, 20 250, 30 500
        assertEquals(3, sends)
    }

    @Test fun aFailingSendDoesNotStopTheLoop() = runTest {
        var calls = 0
        val errors = mutableListOf<Throwable>()
        val b = StateBroadcaster(backgroundScope, heartbeatMs = 60_000, onError = { errors += it }) { calls++; if (calls == 1) error("link down") }
        b.start()
        b.markDirty(); runCurrent()
        advanceTimeBy(300)
        b.markDirty(); advanceTimeBy(300)
        assertEquals(2, calls)
        assertEquals(1, errors.size)
    }
}
```

`StateFrameBuilderTest.kt`:
```kotlin
package com.rokid.context.phone.hub

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.MemoryLine
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.UserFacingErrors
import com.rokid.context.data.runtime.SummaryTrace
import com.rokid.context.link.ClockOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateFrameBuilderTest {
    private val now = 1_793_000_000_000L
    private fun inputs(
        link: LinkState = LinkState.CONNECTED,
        pauses: List<PauseRecord> = emptyList(),
        offset: ClockOffset? = ClockOffset(0),
        today: List<MemoryLine> = emptyList(),
    ) = HubInputs(
        seq = 3, nowMs = now, link = link, activePauses = pauses, offset = offset, settings = HudSettings(captureIntervalSec = 120),
        glassesBatteryPct = 64, nextCaptureAtMs = now + 60_000, queueDepth = 2, lastFrameAtMs = now - 5_000, today = today,
        aiHealth = Health.Ok, overlay = null, error = UserFacingErrors.StorageFull,
    )

    @Test fun noPausesIsRunning() {
        val f = StateFrameBuilder.build(inputs())
        assertEquals(CaptureState.Running, f.capture)
        assertEquals(3L, f.seq); assertEquals(120, f.intervalSec); assertEquals(64, f.glassesBatteryPct)
        assertEquals(2, f.queueDepth); assertEquals(now - 5_000, f.lastSentAtMs)
        assertEquals("Phone storage full", f.error)
    }

    /** Spec §5.5: the glasses enforce pauses on their own clock, so records go out converted. */
    @Test fun pausesGoOutInGlassesClock() {
        val r = PauseRecord("p1", PauseOrigin.PHONE, now - 1_000, now + 60_000)
        val o = ClockOffset(7_200_000)
        val f = StateFrameBuilder.build(inputs(pauses = listOf(r), offset = o))
        assertEquals(CaptureState.Paused(listOf(o.recordToGlasses(r))), f.capture)
        assertEquals(listOf(o.recordToGlasses(r)), StateFrameBuilder.settingsFor(HudSettings(), listOf(r), o).pauses)
    }

    @Test fun withoutAnOffsetPausesAreSentUnchanged() {
        val r = PauseRecord("p1", PauseOrigin.PHONE, now, null)
        assertEquals(CaptureState.Paused(listOf(r)), StateFrameBuilder.build(inputs(pauses = listOf(r), offset = null)).capture)
    }

    @Test fun phoneLinkedOnlyWhenConnected() {
        assertTrue(StateFrameBuilder.build(inputs(link = LinkState.CONNECTED)).phoneLinked)
        assertFalse(StateFrameBuilder.build(inputs(link = LinkState.PAUSED_BT)).phoneLinked)
    }

    @Test fun todayIsCappedAtEight() {
        val lines = (1..12).map { MemoryLine("m$it", now - it, "memory $it") }
        assertEquals(8, StateFrameBuilder.build(inputs(today = lines)).today.size)
    }

    @Test fun aiHealthComesFromTheNewestRecentTrace() {
        fun t(state: String, ago: Long, note: String = "") = SummaryTrace("s$ago", state, now - ago, note)
        assertEquals(Health.Ok, AiHealthRule.from(emptyList(), now))
        assertEquals(Health.Blocked("AI key rejected"), AiHealthRule.from(listOf(t("FAILED", 1_000, UserFacingErrors.AiKeyRejected.line)), now))
        assertEquals(Health.Degraded("AI unavailable"), AiHealthRule.from(listOf(t("RETRY", 1_000, UserFacingErrors.AiUnavailable.line)), now))
        assertEquals(Health.Ok, AiHealthRule.from(listOf(t("FAILED", 5_000, UserFacingErrors.AiKeyRejected.line), t("TRUSTED", 1_000)), now))
        assertEquals("stale", Health.Ok, AiHealthRule.from(listOf(t("FAILED", 31 * 60_000L, UserFacingErrors.AiKeyRejected.line)), now))
    }

    /** Notes written before Task 2 may hold raw provider text: it never reaches the HUD. */
    @Test fun rawLegacyNoteNeverReachesTheHud() = assertEquals(
        Health.Blocked("AI unavailable"),
        AiHealthRule.from(listOf(SummaryTrace("s", "FAILED", now - 1_000, "Azure OpenAI request failed: 500 {\"error\":1}")), now),
    )
}
```

`HubStateTest.kt`:
```kotlin
package com.rokid.context.phone.hub

import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.LinkState
import org.junit.Assert.assertEquals
import org.junit.Test

class HubStateTest {
    @Test fun fieldsMapIntoTheContractStatus() = assertEquals(
        GlassesStatus(LinkState.CONNECTED, glassesBatteryPct = 70, bufferedFrames = 3, lastFrameAtMs = 9, frame = null),
        HubState(link = LinkState.CONNECTED, glassesBatteryPct = 70, bufferedFrames = 3, lastFrameAtMs = 9).toGlassesStatus(),
    )

    @Test fun aMismatchOverridesConnected() =
        assertEquals(LinkState.VERSION_MISMATCH, HubState(link = LinkState.CONNECTED, versionMismatch = true).toGlassesStatus().link)

    @Test fun aStaleMismatchIsHiddenOnceTheLinkIsDown() =
        assertEquals(LinkState.OFFLINE, HubState(link = LinkState.OFFLINE, versionMismatch = true).toGlassesStatus().link)
}
```

- [ ] **Step 7: Run to verify failure**

Run: `./gradlew :phone:testDebugUnitTest --tests "com.rokid.context.phone.hub.*"`
Expected: FAIL — `Unresolved reference: HubPauses`, `StateBroadcaster`, `StateFrameBuilder`, `HubInputs`, `AiHealthRule`, `HubState`.

- [ ] **Step 8: Implement `HubPauses.kt`**

```kotlin
package com.rokid.context.phone.hub

import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.PauseHistory
import com.rokid.context.memory.db.PauseDao
import com.rokid.context.memory.db.PauseIntervalEntity
import com.rokid.context.memory.db.PauseRecordEntity

/**
 * Phone pause set (spec §5.5), persisted in Room: `pause_record` = active records, `pause_interval` = retained history.
 * Instants are phone clock. A record ends only by its own expiry or a Resume naming it; nothing resumes implicitly.
 */
class HubPauses(private val dao: PauseDao, private val now: () -> Long = System::currentTimeMillis) : PauseHistory {
    /** Active records; expired ones are first closed at their `untilMs` and removed. */
    suspend fun active(): List<PauseRecord> {
        val t = now()
        val (live, expired) = dao.records().map { it.toModel() }.partition { it.isActiveAt(t) }
        expired.forEach { dao.closeInterval(it.pauseId, it.untilMs ?: t) }
        if (expired.isNotEmpty()) dao.deleteRecords(expired.map { it.pauseId })
        return live
    }

    /** Union semantics: adds [record] (or keeps it); an already expired record only adds history. */
    suspend fun add(record: PauseRecord) {
        dao.insertInterval(PauseIntervalEntity(record.pauseId, record.origin.name, record.createdAtMs, null))
        if (record.isActiveAt(now())) dao.upsertRecord(record.toEntity())
        else dao.closeInterval(record.pauseId, record.untilMs ?: now())
    }

    suspend fun union(records: List<PauseRecord>) = records.forEach { add(it) }

    /** Ends only the named records (spec §5.5: a Resume never clears pauses its sender did not know). */
    suspend fun resume(clears: Set<String>) {
        if (clears.isEmpty()) return
        val t = now()
        val known = dao.records().map { it.pauseId }.filter { it in clears }
        known.forEach { dao.closeInterval(it, t) }
        if (known.isNotEmpty()) dao.deleteRecords(known)
    }

    override suspend fun pausedAt(phoneMs: Long): Boolean {
        active() // close anything that expired before checking
        return dao.countCovering(phoneMs) > 0
    }

    /** Closed intervals are kept 7 days (spec §5.5); frames older than that are dropped by [FrameAdmission]. */
    suspend fun pruneHistory() = dao.pruneEndedBefore(now() - FrameAdmission.HISTORY_MS)
}

private fun PauseRecordEntity.toModel() =
    PauseRecord(pauseId, runCatching { PauseOrigin.valueOf(origin) }.getOrDefault(PauseOrigin.PHONE), createdAtMs, untilMs)

private fun PauseRecord.toEntity() = PauseRecordEntity(pauseId, origin.name, createdAtMs, untilMs)
```

- [ ] **Step 9: Implement `StateBroadcaster.kt`**

```kotlin
package com.rokid.context.phone.hub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Full-frame push on change, at most once per [minIntervalMs] (250 ms → ≤ 4 Hz, spec §1.6), plus a heartbeat every
 * [heartbeatMs] that keeps the glasses' liveness check and the `today` list fresh. Changes inside the window merge into
 * one send that reads the latest state. Adapted from Live Fit `sync/StateBroadcaster.kt`.
 */
class StateBroadcaster(
    private val scope: CoroutineScope,
    private val minIntervalMs: Long = 250,
    private val heartbeatMs: Long = 10_000,
    private val onError: (Throwable) -> Unit = {},
    private val send: suspend () -> Unit,
) {
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    fun markDirty() { dirty.trySend(Unit) }

    fun start(): Job = scope.launch {
        while (isActive) {
            withTimeoutOrNull(heartbeatMs) { dirty.receive() }
            dirty.tryReceive() // a change that raced with the timeout is part of this send
            try {
                send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
            delay(minIntervalMs)
        }
    }
}
```

- [ ] **Step 10: Implement `StateFrameBuilder.kt`**

```kotlin
package com.rokid.context.phone.hub

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.MemoryLine
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.UserFacingError
import com.rokid.context.core.model.UserFacingErrors
import com.rokid.context.data.runtime.SummaryTrace
import com.rokid.context.link.ClockOffset

/** Everything one [StateFrame] is built from. Instants are phone clock; [activePauses] are phone-clock records. */
data class HubInputs(
    val seq: Long,
    val nowMs: Long,
    val link: LinkState,
    val activePauses: List<PauseRecord>,
    val offset: ClockOffset?,
    val settings: HudSettings,
    val glassesBatteryPct: Int?,
    val nextCaptureAtMs: Long?,
    val queueDepth: Int,
    val lastFrameAtMs: Long?,
    val today: List<MemoryLine>,
    val aiHealth: Health,
    val overlay: Overlay?,
    val error: UserFacingError?,
)

object StateFrameBuilder {
    /**
     * Pause records go out in glasses clock (the glasses enforce them on their own clock, spec §5.5); every other
     * instant stays phone clock. Without an offset the records go unchanged (a pause is never dropped).
     */
    fun build(i: HubInputs): StateFrame {
        val pauses = toGlasses(i.activePauses, i.offset)
        return StateFrame(
            seq = i.seq,
            capture = if (pauses.isEmpty()) CaptureState.Running else CaptureState.Paused(pauses),
            nextCaptureAtMs = i.nextCaptureAtMs,
            intervalSec = i.settings.captureIntervalSec,
            glassesBatteryPct = i.glassesBatteryPct,
            phoneLinked = i.link == LinkState.CONNECTED,
            aiHealth = i.aiHealth,
            queueDepth = i.queueDepth,
            lastSentAtMs = i.lastFrameAtMs,
            latestNudge = null, // NudgeEngine arrives in Phase 7
            today = i.today.take(8),
            overlay = i.overlay,
            error = i.error?.title,
        )
    }

    /** `pc_settings` carries the merged pause set (spec §5.5), converted like [build]. */
    fun settingsFor(settings: HudSettings, activePauses: List<PauseRecord>, offset: ClockOffset?): HudSettings =
        settings.copy(pauses = toGlasses(activePauses, offset))

    private fun toGlasses(records: List<PauseRecord>, offset: ClockOffset?) = records.map { r -> offset?.recordToGlasses(r) ?: r }
}

/** HUD AI health from the newest model outcome in the last 30 min (spec §3.2 Status page; short text only). */
object AiHealthRule {
    private const val WINDOW_MS = 30 * 60_000L
    private val OK_STATES = setOf("TRUSTED", "NEEDS_REVIEW", "REPROCESSED", "WRITE_CONTEXT_OK", "READ_CONTEXT_OK")
    private val TITLES = UserFacingErrors.all.map { it.title }.toSet()

    fun from(traces: List<SummaryTrace>, nowMs: Long): Health {
        val latest = traces
            .filter { nowMs - it.updatedAtEpochMs <= WINDOW_MS && (it.state in OK_STATES || it.state == "RETRY" || it.state == "FAILED") }
            .maxByOrNull { it.updatedAtEpochMs } ?: return Health.Ok
        // Only a known UserFacingError title may reach the glasses; anything else (pre-Task-2 notes) is replaced.
        val title = latest.note.substringBefore(" · ").takeIf { it in TITLES } ?: UserFacingErrors.AiUnavailable.title
        return when (latest.state) {
            "FAILED" -> Health.Blocked(title)
            "RETRY" -> Health.Degraded(title)
            else -> Health.Ok
        }
    }
}
```

- [ ] **Step 11: Implement `HubState.kt`**

```kotlin
package com.rokid.context.phone.hub

import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.UserFacingError
import com.rokid.context.link.ClockOffset

/** What the hub knows about the glasses: one immutable snapshot. Instants are phone clock. */
data class HubState(
    val link: LinkState = LinkState.OFFLINE,
    /** The glasses app spoke another protocol version in this session. */
    val versionMismatch: Boolean = false,
    val glassesBatteryPct: Int? = null,
    val bufferedFrames: Int = 0,
    val lastFrameAtMs: Long? = null,
    val nextCaptureAtMs: Long? = null,
    val offset: ClockOffset? = null,
    val error: UserFacingError? = null,
    val lastFrame: StateFrame? = null,
) {
    /** Phase 4 contract view. A mismatch matters only while a session is up. */
    fun toGlassesStatus(): GlassesStatus = GlassesStatus(
        link = if (versionMismatch && link == LinkState.CONNECTED) LinkState.VERSION_MISMATCH else link,
        glassesBatteryPct = glassesBatteryPct,
        bufferedFrames = bufferedFrames,
        lastFrameAtMs = lastFrameAtMs,
        frame = lastFrame,
    )
}
```

- [ ] **Step 12: Run the tests**

Run: `./gradlew :phone:testDebugUnitTest`
Expected: PASS — the 3 existing phone tests plus `HubPausesTest` (6), `StateBroadcasterTest` (4), `StateFrameBuilderTest` (7), `HubStateTest` (3).

- [ ] **Step 13: Commit**

```bash
git add android-companion/services/memory android-companion/phone/build.gradle.kts android-companion/phone/src/main/java/com/rokid/context/phone/hub android-companion/phone/src/test/java/com/rokid/context/phone/hub
git commit -m "feat(hub): pause store, StateFrame builder and 4 Hz broadcaster

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Phone hub service — frame ingest, `ContextHubService`, `HubGraph`, wiring

**Requirements:** R-07 (`ContextHubService` owns the link and the broadcast; started from the foreground), R-05 (channel handling, `pc_hello` clock + pause records, mismatch → `VERSION_MISMATCH`), R-08 (ack only after a durable write, dedupe, CRC NACK), R-72 (storage full → `UserFacingErrors.StorageFull`, master Review Focus 5), R-65 (part: Pause/Resume commands, Hello union), contract (`HubGraph.glassesStatus(context)`).

Ported: `/Users/<user>/Desktop/rokid/live-fitness-tracker/phone/src/main/java/com/debasish/livefit/phone/LiveFitHubService.kt` (FGS start/notification/`ensureRunning` pattern; companion-device and location parts dropped).

The RFCOMM `RokidBridgeService` keeps running beside the hub until Task 12, so frames keep flowing on the old path while the CXR path is built. Frames from the hub go into the same `rokid_inbox` the bridge used, so `QueueDrainWorker` and `RokidFrameInbox` stay unchanged.

**Files:**
- Create (in `android-companion/phone/src/main/java/com/rokid/context/phone/hub/`): `FrameIngestor.kt`, `HubSettingsStore.kt`, `HubSources.kt`, `Hub.kt`, `HubGraph.kt`, `ContextHubService.kt`, `HubActivityHook.kt`
- Modify: `android-companion/phone/src/main/AndroidManifest.xml` (service entry only)
- Modify: `android-companion/phone/src/main/java/com/rokid/context/ContextApp.kt` (one line)
- Test (in `android-companion/phone/src/test/java/com/rokid/context/phone/hub/`): `FrameIngestorTest.kt`, `HubTest.kt`, `ContextHubServiceTest.kt`

**Interfaces:**
- Consumes: Task 3 `GlassesLink`, `GlassesEvent`, `FakeGlassesLink`, `CxrGlassesLink`; Task 4 `FrameReassembler`, `Assembly`, `FrameAdmission`, `Admission`, `ReceivedFrameLedger`, `ClockOffset`; Task 5 `HubPauses`, `StateBroadcaster`, `StateFrameBuilder`, `HubInputs`, `AiHealthRule`, `HubState`, `testDb`; `RokidFrameInbox.inboxDir(context)`, `QueueRuntimeStore.enqueue`, `QueuePayloads.frameIngest(source)`, `OrchestrationScheduler(context).enqueueQueueDrainNow()`, `AppConfigStore.get(context).persona`, `AppConfigStore.glassesSilencyMode(context)`, `SummaryTraceStore.recent`, `MemoryGraph.get(context)`.
- Produces:
  - `fun interface JpegWriter { fun write(target: File, bytes: ByteArray) }`, `object AtomicJpegWriter : JpegWriter`, `fun isNoSpace(t: Throwable): Boolean`, `data class IngestResult(ack: FrameAck?, ingestedAtMs: Long? = null, error: UserFacingError? = null)`, `class FrameIngestor(inboxDir: File, reassembler, admission, ledger, writer: JpegWriter = AtomicJpegWriter, onStored: () -> Unit) { suspend fun onChunk(chunk: FrameChunk, offset: ClockOffset?, nowMs: Long): IngestResult }`, `fun enqueueInboxIngest(context: Context)`.
  - `class HubSettingsStore(prefs: SharedPreferences, legacyMode: () -> String) { fun read(): HudSettings; fun write(s: HudSettings); companion { fun verbosityFor(mode: String): Verbosity } }`.
  - `interface HubSources { suspend fun today(): List<MemoryLine>; suspend fun queueDepth(): Int; suspend fun aiHealth(nowMs: Long): Health }`, `class LegacyHubSources(context): HubSources`.
  - `class Hub(scope, link: GlassesLink, pauses, settings, ingestor, sources, clock = System::currentTimeMillis, io = Dispatchers.IO) : GlassesStatusSource { val state: StateFlow<HubState>; val started: Boolean; fun start(); fun stop(); fun onAppResumed(); fun updateSettings(transform: (HudSettings) -> HudSettings) }`.
  - `object HubGraph { fun get(context): Hub; fun glassesStatus(context): GlassesStatusSource; fun resetForTests(h: Hub?) }`.
  - `class ContextHubService : Service` with companion `ensureRunning(context)`, `stop(context)`, `isRunning()`, `statusLine()`, internal `canStart(sdk, bluetoothGranted)`, `shouldStart(running, canStart)`, `notificationText(status)`.
  - `object HubActivityHook : Application.ActivityLifecycleCallbacks`.

- [ ] **Step 1: Write the failing ingest test**

`FrameIngestorTest.kt`:
```kotlin
package com.rokid.context.phone.hub

import android.app.Application
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.UserFacingErrors
import com.rokid.context.link.ClockOffset
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.PauseHistory
import com.rokid.context.link.ReceivedFrameLedger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FrameIngestorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 1_793_000_000_000L
    private val offset = ClockOffset(140) // glasses 140 ms ahead (phase 0)
    private val jpeg = ByteArray(3_000) { (it % 200).toByte() }
    private val noPauses = object : PauseHistory { override suspend fun pausedAt(phoneMs: Long) = false }
    private var stored = 0
    private val inbox get() = File(tmp.root, "rokid_inbox")

    private fun ingestor(writer: JpegWriter = AtomicJpegWriter): FrameIngestor {
        val ledger = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
        return FrameIngestor(inbox, FrameReassembler(), FrameAdmission(ledger, noPauses), ledger, writer) { stored++ }
    }
    private fun chunk(id: String = "g1") = FrameCodec.split(id, jpeg, capturedAtMs = now - 60_000 + 140).single()

    @Test fun completeFrameIsStoredAckedAndQueued() = runTest {
        val r = ingestor().onChunk(chunk(), offset, now)
        assertEquals(FrameAck(frameId = "g1", ok = true), r.ack)
        assertEquals(now - 60_000, r.ingestedAtMs)
        val f = File(inbox, "rokid_${now - 60_000}_g1.jpg")
        assertArrayEquals(jpeg, f.readBytes())
        assertTrue("capture time is the file time RokidFrameInbox reads", abs(f.lastModified() - (now - 60_000)) < 1_000)
        assertEquals(1, stored)
    }

    @Test fun resentFrameAfterALostAckIsNotStoredTwice() = runTest {
        val ing = ingestor()
        ing.onChunk(chunk(), offset, now)
        val again = ing.onChunk(chunk(), offset, now + 7_000)
        assertEquals(FrameAck(frameId = "g1", ok = true, reason = FrameAckReason.DUPLICATE), again.ack)
        assertEquals(1, inbox.listFiles()!!.size)
        assertEquals(1, stored)
    }

    /** Review Focus 2 (master 5): clean failure, no ack (the glasses keep it), nothing half-written, later resend works. */
    @Test fun storageFullLeavesNoFileSendsNoAckAndRecoversLater() = runTest {
        var full = true
        val writer = JpegWriter { f, b -> if (full) throw IOException("write failed: ENOSPC (No space left on device)") else AtomicJpegWriter.write(f, b) }
        val ing = ingestor(writer)
        val r = ing.onChunk(chunk(), offset, now)
        assertNull(r.ack)
        assertEquals(UserFacingErrors.StorageFull, r.error)
        assertTrue(inbox.listFiles().orEmpty().isEmpty())
        assertEquals(0, stored)
        full = false
        assertEquals(FrameAck(frameId = "g1", ok = true), ing.onChunk(chunk(), offset, now + 7_000).ack)
        assertEquals(1, stored)
    }

    @Test fun corruptFrameIsNackedAndAFrameBeforeHelloIsHeld() = runTest {
        val ing = ingestor()
        val c = chunk()
        val bad = c.copy(chunk = c.chunk.replaceRange(4, 5, if (c.chunk[4] == 'A') "B" else "A"))
        assertEquals(FrameAck(frameId = "g1", ok = false, reason = FrameAckReason.CRC), ing.onChunk(bad, offset, now).ack)
        assertNull(ing.onChunk(chunk("g2"), offset = null, nowMs = now).ack)
        assertEquals(0, stored)
    }

    @Test fun atomicWriterLeavesOnlyTheTarget() {
        val dir = File(tmp.root, "in")
        AtomicJpegWriter.write(File(dir, "a.jpg"), jpeg)
        assertEquals(listOf("a.jpg"), dir.list()!!.toList())
    }

    @Test fun noSpaceIsRecognisedThroughTheCauseChain() {
        assertTrue(isNoSpace(IOException("write failed", ErrnoException("write", OsConstants.ENOSPC))))
        assertTrue(isNoSpace(IOException("No space left on device")))
        assertFalse(isNoSpace(IOException("Permission denied")))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :phone:testDebugUnitTest --tests "*FrameIngestorTest"`
Expected: FAIL — `Unresolved reference: FrameIngestor`, `JpegWriter`, `AtomicJpegWriter`, `isNoSpace`.

- [ ] **Step 3: Implement `FrameIngestor.kt`**

```kotlin
package com.rokid.context.phone.hub

import android.content.Context
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.UserFacingError
import com.rokid.context.core.model.UserFacingErrors
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.link.Admission
import com.rokid.context.link.Assembly
import com.rokid.context.link.ClockOffset
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.ReceivedFrameLedger
import com.rokid.context.work.OrchestrationScheduler
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Writes [bytes] to [target] so a reader never sees a partial file. */
fun interface JpegWriter {
    @Throws(IOException::class)
    fun write(target: File, bytes: ByteArray)
}

object AtomicJpegWriter : JpegWriter {
    override fun write(target: File, bytes: ByteArray) {
        val dir = target.parentFile ?: throw IOException("no parent directory")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
        // ".part" is not an inbox extension, so RokidFrameInbox never picks up a half-written file.
        val tmp = File(dir, "${target.name}.part")
        try {
            FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
            if (!tmp.renameTo(target)) throw IOException("rename failed")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }
}

/** ENOSPC anywhere in the cause chain (Review Focus 2). */
fun isNoSpace(t: Throwable): Boolean = generateSequence(t) { it.cause }.take(8).any { c ->
    (c is ErrnoException && c.errno == OsConstants.ENOSPC) ||
        c.message?.let { "ENOSPC" in it || it.contains("No space left", ignoreCase = true) } == true
}

/** One handled chunk: [ack] to send (null = none), [ingestedAtMs] = phone capture time of a newly stored frame. */
data class IngestResult(val ack: FrameAck?, val ingestedAtMs: Long? = null, val error: UserFacingError? = null)

/**
 * `pc_frame` chunk → reassembly → admission (dedupe, clock offset, pause filter) → atomic inbox write → ledger → queue
 * (spec §1.8). Ack ok=true only after the JPEG is durable and its id is in the ledger, so a crash never loses a frame
 * the glasses already deleted. A failed write sends no ack: the glasses keep the frame and retry on a later flush.
 */
class FrameIngestor(
    private val inboxDir: File,
    private val reassembler: FrameReassembler,
    private val admission: FrameAdmission,
    private val ledger: ReceivedFrameLedger,
    private val writer: JpegWriter = AtomicJpegWriter,
    private val onStored: () -> Unit,
) {
    suspend fun onChunk(chunk: FrameChunk, offset: ClockOffset?, nowMs: Long): IngestResult =
        when (val a = reassembler.accept(chunk, nowMs)) {
            Assembly.Pending, Assembly.Ignored -> IngestResult(ack = null)
            is Assembly.Rejected -> IngestResult(FrameAck(frameId = a.frameId, ok = false, reason = a.reason))
            is Assembly.Complete -> admit(a, offset, nowMs)
        }

    private suspend fun admit(c: Assembly.Complete, offset: ClockOffset?, nowMs: Long): IngestResult {
        val id = c.meta.frameId
        return when (val d = admission.decide(c.meta, offset, nowMs)) {
            Admission.Hold -> IngestResult(ack = null)
            is Admission.Reject -> IngestResult(FrameAck(frameId = id, ok = false, reason = d.reason))
            is Admission.AckOnly -> IngestResult(FrameAck(frameId = id, ok = true, reason = d.reason))
            is Admission.Ingest -> store(id, c.jpeg, d.capturedAtPhoneMs)
        }
    }

    private fun store(id: String, jpeg: ByteArray, capturedAtMs: Long): IngestResult {
        val target = File(inboxDir, "rokid_${capturedAtMs}_$id.jpg")
        try {
            writer.write(target, jpeg)
            target.setLastModified(capturedAtMs) // RokidFrameInbox takes the capture time from lastModified
            ledger.add(id)
        } catch (e: IOException) {
            target.delete()
            Log.w(TAG, "frame $id not stored: ${e.javaClass.simpleName}")
            return IngestResult(ack = null, error = if (isNoSpace(e)) UserFacingErrors.StorageFull else UserFacingErrors.Unknown)
        }
        onStored()
        Log.i(TAG, "frame ingested $id")
        return IngestResult(FrameAck(frameId = id, ok = true), ingestedAtMs = capturedAtMs)
    }

    companion object { const val TAG = "ContextHub" }
}

/** Production `onStored`: queue the inbox ingest exactly as the RFCOMM bridge did. */
fun enqueueInboxIngest(context: Context) {
    QueueRuntimeStore.enqueue(
        context = context,
        queue = QueuePriority.FRAME_INGEST,
        payload = QueuePayloads.frameIngest(source = "rokid_cxr"),
        personaSnapshot = AppConfigStore.get(context).persona,
        ttlHours = 24,
    )
    OrchestrationScheduler(context).enqueueQueueDrainNow()
}
```

- [ ] **Step 4: Run the ingest test**

Run: `./gradlew :phone:testDebugUnitTest --tests "*FrameIngestorTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Write the failing hub tests**

`HubTest.kt`:
```kotlin
package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.MemoryLine
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Verbosity
import com.rokid.context.core.model.Wire
import com.rokid.context.link.FakeGlassesLink
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.GlassesEvent
import com.rokid.context.link.ReceivedFrameLedger
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HubTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val db = testDb(ctx)
    @After fun close() = db.close()
    private val now = 1_793_000_000_000L
    private val sources = object : HubSources {
        override suspend fun today() = listOf(MemoryLine("m1", now, "Keys on the hallway shelf"))
        override suspend fun queueDepth() = 2
        override suspend fun aiHealth(nowMs: Long): Health = Health.Ok
    }

    private fun TestScope.hub(link: FakeGlassesLink): Hub {
        val pauses = HubPauses(db.pauseDao()) { now }
        val ledger = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
        val ingestor = FrameIngestor(File(tmp.root, "inbox"), FrameReassembler(), FrameAdmission(ledger, pauses), ledger) {}
        val settings = HubSettingsStore(ctx.getSharedPreferences("hub_test_${tmp.root.name}", 0)) { "hud" }
        return Hub(backgroundScope, link, pauses, settings, ingestor, sources, clock = { now }, io = StandardTestDispatcher(testScheduler))
            .also { it.start(); runCurrent() }
    }
    private fun FakeGlassesLink.lastOn(channel: String) = sent.last { it.first == channel }.second
    private fun hello(glassesNowMs: Long = now, pauses: List<PauseRecord> = emptyList()) =
        GlassesEvent.HelloReceived(Hello(glassesBatteryPct = 77, bufferSize = 4, glassesNowMs = glassesNowMs, pauses = pauses))

    @Test fun startConnectsOnceAndReadyPushesSettingsThenState() = runTest {
        val link = FakeGlassesLink()
        hub(link)
        assertEquals(1, link.connectOnceCalls)
        link.sent.clear()
        link.emitReady(); runCurrent()
        assertEquals(listOf(Channels.SETTINGS, Channels.STATE), link.sent.map { it.first }.take(2))
        val frame = (Wire.decode<StateFrame>(link.lastOn(Channels.STATE)) as Decoded.Ok).value
        assertEquals(CaptureState.Running, frame.capture)
        assertEquals(2, frame.queueDepth)
        assertTrue(frame.phoneLinked)
        assertEquals("Keys on the hallway shelf", frame.today.single().text)
    }

    /** Review Focus 1: glasses 2 h ahead; their pause is stored in phone clock and sent back in glasses clock. */
    @Test fun helloMeasuresTheOffsetAndUnionsGlassesPausesInPhoneClock() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        val g = now + 7_200_000
        link.emit(hello(glassesNowMs = g, pauses = listOf(PauseRecord("g1", PauseOrigin.GLASSES, g - 60_000, null)))); runCurrent()
        assertEquals(7_200_000L, h.state.value.offset!!.glassesMinusPhoneMs)
        assertEquals(GlassesStatus(LinkState.CONNECTED, 77, 4, null, h.status.value.frame), h.status.value)
        advanceTimeBy(300)
        val paused = (Wire.decode<StateFrame>(link.lastOn(Channels.STATE)) as Decoded.Ok).value.capture as CaptureState.Paused
        assertEquals("sent back in glasses clock", g - 60_000, paused.records.single().createdAtMs)
        assertEquals("stored in phone clock", now - 60_000, HubPauses(db.pauseDao()) { now }.active().single().createdAtMs)
    }

    @Test fun pauseAndResumeCommandsChangeTheCaptureState() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        link.emit(hello()); runCurrent()
        link.emit(GlassesEvent.Cmd(Command.Pause(PauseRecord("g2", PauseOrigin.GLASSES, now, null)))); advanceTimeBy(300)
        assertTrue(h.status.value.frame!!.capture is CaptureState.Paused)
        link.emit(GlassesEvent.Cmd(Command.Resume(setOf("unknown")))); advanceTimeBy(300)
        assertTrue("an unknown id clears nothing", h.status.value.frame!!.capture is CaptureState.Paused)
        link.emit(GlassesEvent.Cmd(Command.Resume(setOf("g2")))); advanceTimeBy(300)
        assertEquals(CaptureState.Running, h.status.value.frame!!.capture)
    }

    @Test fun frameIsHeldBeforeHelloThenIngestedAndAcked() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        val c = FrameCodec.split("g5", ByteArray(2_000) { 3 }, capturedAtMs = now - 1_000).single()
        link.emit(GlassesEvent.Frame(c)); runCurrent()
        assertTrue("held before hello", link.sent.none { it.first == Channels.FRAME_ACK })
        link.emit(hello()); runCurrent()
        link.emit(GlassesEvent.Frame(c)); runCurrent()
        assertEquals(FrameAck(frameId = "g5", ok = true), (Wire.decode<FrameAck>(link.lastOn(Channels.FRAME_ACK)) as Decoded.Ok).value)
        assertEquals(now - 1_000, h.status.value.lastFrameAtMs)
    }

    /** Review Focus 4: a mismatched glasses app is shown as such until a new session starts. */
    @Test fun outdatedGlassesShowVersionMismatchUntilANewSession() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        link.emit(GlassesEvent.Outdated(PROTOCOL_VERSION + 1)); runCurrent()
        assertEquals(LinkState.VERSION_MISMATCH, h.status.value.link)
        link.emitReady(); runCurrent()
        assertEquals(LinkState.CONNECTED, h.status.value.link)
    }

    @Test fun linkStateFlowsIntoTheStatus() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        link.setState(LinkState.CLOSED_ON_GLASSES); runCurrent()
        assertEquals(LinkState.CLOSED_ON_GLASSES, h.status.value.link)
    }

    @Test fun reconnectIsAnExplicitConnect() = runTest {
        val link = FakeGlassesLink()
        hub(link).requestReconnect()
        assertEquals(1, link.connectCalls)
    }

    @Test fun nothingIsSentWhileTheLinkIsDownButTheFrameIsKept() = runTest {
        val link = FakeGlassesLink(LinkState.OFFLINE)
        val h = hub(link)
        advanceTimeBy(11_000)
        assertTrue(link.sent.isEmpty())
        assertNotNull(h.status.value.frame)
    }

    @Test fun settingsChangesArePushed() = runTest {
        val link = FakeGlassesLink()
        val h = hub(link)
        h.updateSettings { it.copy(verbosity = Verbosity.MAX) }; runCurrent()
        assertEquals(Verbosity.MAX, (Wire.decode<HudSettings>(link.lastOn(Channels.SETTINGS)) as Decoded.Ok).value.verbosity)
    }
}
```

`ContextHubServiceTest.kt`:
```kotlin
package com.rokid.context.phone.hub

import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextHubServiceTest {
    /** Android 14+ needs a runtime prerequisite for a connectedDevice FGS; BLUETOOTH_CONNECT exists from API 31. */
    @Test fun startsOnlyWithTheBluetoothPrerequisite() {
        assertTrue(ContextHubService.canStart(sdk = 30, bluetoothGranted = false))
        assertFalse(ContextHubService.canStart(sdk = 34, bluetoothGranted = false))
        assertTrue(ContextHubService.canStart(sdk = 37, bluetoothGranted = true))
    }

    @Test fun startsOnce() {
        assertTrue(ContextHubService.shouldStart(running = false, canStart = true))
        assertFalse(ContextHubService.shouldStart(running = true, canStart = true))
        assertFalse(ContextHubService.shouldStart(running = false, canStart = false))
    }

    @Test fun notificationTextIsShortAndHuman() {
        fun text(l: LinkState) = ContextHubService.notificationText(GlassesStatus(l, null, 0, null, null))
        assertEquals("Glasses connected", text(LinkState.CONNECTED))
        assertEquals("Connecting to glasses", text(LinkState.CONNECTING))
        assertEquals("Reconnecting to glasses", text(LinkState.PAUSED_BT))
        assertEquals("Glasses offline", text(LinkState.OFFLINE))
        assertEquals("Closed on glasses", text(LinkState.CLOSED_ON_GLASSES))
        assertEquals("Glasses not authorized", text(LinkState.NOT_AUTHORIZED))
        assertEquals("Update glasses app", text(LinkState.VERSION_MISMATCH))
    }
}
```

- [ ] **Step 6: Run to verify failure**

Run: `./gradlew :phone:testDebugUnitTest --tests "*HubTest" --tests "*ContextHubServiceTest"`
Expected: FAIL — `Unresolved reference: Hub`, `HubSources`, `HubSettingsStore`, `ContextHubService`.

- [ ] **Step 7: Implement `HubSettingsStore.kt` and `HubSources.kt`**

`HubSettingsStore.kt`:
```kotlin
package com.rokid.context.phone.hub

import android.content.SharedPreferences
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.Verbosity
import com.rokid.context.core.model.Wire

/** The phone's copy of [HudSettings] (`pc_settings`). Pauses are not stored here: the hub adds them at push time. */
class HubSettingsStore(private val prefs: SharedPreferences, private val legacyMode: () -> String) {
    fun read(): HudSettings = prefs.getString(KEY, null)?.let { (Wire.decode<HudSettings>(it) as? Decoded.Ok)?.value }
        ?: HudSettings(verbosity = verbosityFor(legacyMode()))

    fun write(s: HudSettings) { prefs.edit().putString(KEY, Wire.encode(s.copy(pauses = emptyList()))).apply() }

    companion object {
        private const val KEY = "hud_settings"

        /** Legacy Config screen values (`AppConfigStore.glassesSilencyMode`) → spec §3.4 verbosity. */
        fun verbosityFor(mode: String): Verbosity = when (mode.trim().lowercase()) {
            "stealth", "silent" -> Verbosity.SILENT
            "compact", "max" -> Verbosity.MAX
            else -> Verbosity.MEDIUM
        }
    }
}
```

`HubSources.kt`:
```kotlin
package com.rokid.context.phone.hub

import android.content.Context
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.MemoryLine
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.data.runtime.SummaryTraceStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ReviewState

/** What a [StateFrame][com.rokid.context.core.model.StateFrame] reads from the rest of the app. Called on IO. */
interface HubSources {
    suspend fun today(): List<MemoryLine>
    suspend fun queueDepth(): Int
    suspend fun aiHealth(nowMs: Long): Health
}

/** Phase 3 sources: Room memories, the legacy queue file and summary traces. */
class LegacyHubSources(private val context: Context) : HubSources {
    override suspend fun today(): List<MemoryLine> = MemoryGraph.get(context).memories.recent(24).asSequence()
        .filter { it.reviewState == ReviewState.OK && !it.type.contains("review", ignoreCase = true) }
        .take(8)
        .map { MemoryLine(it.id, it.createdAt, it.text.take(80)) }
        .toList()

    override suspend fun queueDepth(): Int = QueueRuntimeStore.snapshot(context).totalPending

    override suspend fun aiHealth(nowMs: Long): Health = AiHealthRule.from(SummaryTraceStore.recent(context, 20), nowMs)
}
```

- [ ] **Step 8: Implement `Hub.kt`**

```kotlin
package com.rokid.context.phone.hub

import android.util.Log
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.Wire
import com.rokid.context.link.ClockOffset
import com.rokid.context.link.GlassesEvent
import com.rokid.context.link.GlassesLink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone hub (spec §1.7): handles glasses messages, keeps the pause set, ingests frames and broadcasts `StateFrame`s.
 * Runs on [scope] (Main in the app, so [GlassesLink] calls stay on its thread); disk and DB work hop to [io].
 * The phone is the source of truth for everything except whether capture is allowed (pause union, spec §5.5).
 */
class Hub(
    private val scope: CoroutineScope,
    val link: GlassesLink,
    private val pauses: HubPauses,
    private val settings: HubSettingsStore,
    private val ingestor: FrameIngestor,
    private val sources: HubSources,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : GlassesStatusSource {
    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = _state
    private val _status = MutableStateFlow(_state.value.toGlassesStatus())
    override val status: StateFlow<GlassesStatus> = _status
    private var seq = 0L
    private var jobs: List<Job> = emptyList()
    private val broadcaster = StateBroadcaster(scope, onError = { Log.w(TAG, "broadcast failed: ${it.javaClass.simpleName}") }) { broadcast() }

    val started: Boolean get() = jobs.isNotEmpty()

    fun start() {
        if (started) return
        jobs = listOf(
            scope.launch { link.state.collect { s -> set { it.copy(link = s) } } },
            scope.launch { link.events.collect { onEvent(it) } },
            scope.launch { link.ready.collect { onReady() } },
            scope.launch { withContext(io) { pauses.pruneHistory() } },
            broadcaster.start(),
        )
        link.connectOnce()
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        link.close()
    }

    override fun requestReconnect() = link.connect()

    fun onAppResumed() = link.connectOnResume()

    /** A settings change from the phone: persisted, pushed now (when linked) and reflected in the next frame. */
    fun updateSettings(transform: (HudSettings) -> HudSettings) {
        settings.write(transform(settings.read()))
        scope.launch { pushSettings(); broadcaster.markDirty() }
    }

    private fun set(markDirty: Boolean = true, f: (HubState) -> HubState) {
        _state.update(f)
        _status.value = _state.value.toGlassesStatus()
        if (markDirty) broadcaster.markDirty()
    }

    private suspend fun onReady() {
        set { it.copy(versionMismatch = false) }
        pushSettings()
        broadcast()
    }

    private suspend fun pushSettings() {
        val active = withContext(io) { pauses.active() }
        link.send(Channels.SETTINGS, Wire.encode(StateFrameBuilder.settingsFor(settings.read(), active, _state.value.offset)))
    }

    private suspend fun onEvent(e: GlassesEvent) {
        val now = clock()
        when (e) {
            is GlassesEvent.HelloReceived -> {
                val h = e.hello
                val offset = ClockOffset.fromHello(h.glassesNowMs, now)
                withContext(io) { pauses.union(h.pauses.map(offset::recordToPhone)) }
                Log.i(TAG, "hello battery=${h.glassesBatteryPct} buffered=${h.bufferSize} offsetMs=${offset.glassesMinusPhoneMs}")
                set {
                    it.copy(
                        versionMismatch = false, offset = offset, glassesBatteryPct = h.glassesBatteryPct ?: it.glassesBatteryPct,
                        bufferedFrames = h.bufferSize, nextCaptureAtMs = h.nextCaptureAtMs?.let(offset::toPhone),
                    )
                }
                pushSettings()
            }
            is GlassesEvent.Cmd -> onCommand(e.command, now)
            is GlassesEvent.Frame -> {
                val r = withContext(io) { ingestor.onChunk(e.chunk, _state.value.offset, now) }
                r.ack?.let { link.send(Channels.FRAME_ACK, Wire.encode(it)) }
                set { s ->
                    s.copy(
                        glassesBatteryPct = e.chunk.meta.batteryPct ?: s.glassesBatteryPct,
                        lastFrameAtMs = r.ingestedAtMs ?: s.lastFrameAtMs,
                        error = r.error ?: if (r.ingestedAtMs != null) null else s.error,
                    )
                }
            }
            is GlassesEvent.Outdated -> {
                Log.w(TAG, "glasses protocol v=${e.remote}, phone v=$PROTOCOL_VERSION")
                set { it.copy(versionMismatch = true) }
            }
            is GlassesEvent.Audio, GlassesEvent.ListenEnd -> Unit // voice: Phase 6
        }
    }

    private suspend fun onCommand(c: Command, now: Long) {
        when (c) {
            is Command.Pause -> {
                val r = _state.value.offset?.recordToPhone(c.pause) ?: ClockOffset.anchoredAt(c.pause, now)
                withContext(io) { pauses.add(r) }
                Log.i(TAG, "pause added ${r.pauseId} origin=${r.origin}")
                pushSettings(); broadcaster.markDirty()
            }
            is Command.Resume -> {
                withContext(io) { pauses.resume(c.clears) }
                Log.i(TAG, "resume asked for ${c.clears.size} pause(s)")
                pushSettings(); broadcaster.markDirty()
            }
            else -> Log.i(TAG, "command ${c::class.simpleName} is handled in a later phase")
        }
    }

    /** Computed on every change/heartbeat; sent only while linked. Sent even to an outdated app so it can show "Update". */
    private suspend fun broadcast() {
        val s = _state.value
        val now = clock()
        val active = withContext(io) { pauses.active() }
        val today = withContext(io) { sources.today() }
        val depth = withContext(io) { sources.queueDepth() }
        val health = withContext(io) { sources.aiHealth(now) }
        val frame = StateFrameBuilder.build(
            HubInputs(
                seq = ++seq, nowMs = now, link = s.link, activePauses = active, offset = s.offset, settings = settings.read(),
                glassesBatteryPct = s.glassesBatteryPct, nextCaptureAtMs = s.nextCaptureAtMs, queueDepth = depth,
                lastFrameAtMs = s.lastFrameAtMs, today = today, aiHealth = health, overlay = null, error = s.error,
            ),
        )
        set(markDirty = false) { it.copy(lastFrame = frame) }
        if (s.link == LinkState.CONNECTED) link.send(Channels.STATE, Wire.encode(frame))
    }

    companion object { const val TAG = "ContextHub" }
}
```

- [ ] **Step 9: Implement `HubGraph.kt`, `ContextHubService.kt` and `HubActivityHook.kt`**

`HubGraph.kt`:
```kotlin
package com.rokid.context.phone.hub

import android.content.Context
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.link.CxrGlassesLink
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.ReceivedFrameLedger
import com.rokid.context.memory.MemoryGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Process-wide hub (no DI in v1). `ContextApp` has already initialised [MemoryGraph]. */
object HubGraph {
    @Volatile private var hub: Hub? = null

    fun get(context: Context): Hub = hub ?: synchronized(this) { hub ?: build(context.applicationContext).also { hub = it } }

    /** Phase 3 → Phase 4 contract: the real glasses status for the phone UI. */
    fun glassesStatus(context: Context): GlassesStatusSource = get(context)

    fun resetForTests(h: Hub?) { synchronized(this) { hub = h } }

    private fun build(app: Context): Hub {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ledger = ReceivedFrameLedger(File(app.filesDir, "cxr_received_frames.txt"))
        val pauses = HubPauses(MemoryGraph.get(app).db.pauseDao())
        val ingestor = FrameIngestor(RokidFrameInbox.inboxDir(app), FrameReassembler(), FrameAdmission(ledger, pauses), ledger) { enqueueInboxIngest(app) }
        val settings = HubSettingsStore(app.getSharedPreferences("hub_settings", Context.MODE_PRIVATE)) { AppConfigStore.glassesSilencyMode(app) }
        return Hub(scope, CxrGlassesLink(app, scope), pauses, settings, ingestor, LegacyHubSources(app))
    }
}
```

`ContextHubService.kt`:
```kotlin
package com.rokid.context.phone.hub

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.UserFacingErrors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground (connectedDevice) service that keeps the hub — glasses link, frame ingest, `StateFrame` broadcast — alive
 * (spec §1.7). Adapted from Live Fit `LiveFitHubService`. Started from a resumed activity ([HubActivityHook]).
 */
class ContextHubService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Glasses link", NotificationManager.IMPORTANCE_LOW))
        runCatching { ServiceCompat.startForeground(this, ID, notification(READY), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
            .onFailure { Log.w(TAG, "foreground refused: ${it.javaClass.simpleName}"); stopSelf(); return }
        running = true
        val hub = HubGraph.get(this)
        hub.start()
        watcher = CoroutineScope(Dispatchers.Main.immediate).launch {
            hub.status.map(::notificationText).distinctUntilChanged().collect { text -> lastText = text; nm.notify(ID, notification(text)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        watcher?.cancel()
        HubGraph.get(this).stop()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Proactive Context")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    companion object {
        const val TAG = "ContextHub"
        private const val CHANNEL = "hub"
        private const val ID = 31
        private const val READY = "Glasses link ready"
        @Volatile private var running = false
        @Volatile private var lastText = READY

        fun isRunning(): Boolean = running

        /** One line for the legacy Live screen (Task 12 shows it in place of the RFCOMM bridge message). */
        fun statusLine(): String = lastText

        /** Idempotent: called on every activity resume. */
        fun ensureRunning(context: Context) {
            val granted = Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!shouldStart(running, canStart(Build.VERSION.SDK_INT, granted))) return
            runCatching { context.startForegroundService(Intent(context, ContextHubService::class.java)) }
                .onFailure { Log.w(TAG, "start refused: ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) { context.stopService(Intent(context, ContextHubService::class.java)) }

        internal fun canStart(sdk: Int, bluetoothGranted: Boolean) = sdk < 31 || bluetoothGranted
        internal fun shouldStart(running: Boolean, canStart: Boolean) = !running && canStart

        internal fun notificationText(s: GlassesStatus): String = UserFacingErrors.forLink(s.link)?.title ?: when (s.link) {
            LinkState.CONNECTED -> "Glasses connected"
            LinkState.PAUSED_BT -> "Reconnecting to glasses"
            else -> "Connecting to glasses"
        }
    }
}
```

`HubActivityHook.kt`:
```kotlin
package com.rokid.context.phone.hub

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Starts the hub whenever one of our activities resumes (a connectedDevice FGS may only start from the foreground)
 * and gives an OFFLINE link one reconnect. Lives in ContextApp so it survives Phase 4's MainActivity rewrite.
 */
object HubActivityHook : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        ContextHubService.ensureRunning(activity)
        if (ContextHubService.isRunning()) HubGraph.get(activity).onAppResumed()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
```

- [ ] **Step 10: Register the service and the hook**

In `phone/src/main/AndroidManifest.xml`, inside `<application>` after the existing `.bridge.RokidBridgeService` entry, add:
```xml
        <service
            android:name=".phone.hub.ContextHubService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
```
In `ContextApp.kt`, add as the last line of `onCreate()` (after `LegacyImportWorker.enqueue(this)`):
```kotlin
        registerActivityLifecycleCallbacks(com.rokid.context.phone.hub.HubActivityHook)
```

- [ ] **Step 11: Run the tests and the gate**

Run: `./gradlew :phone:testDebugUnitTest`
Expected: PASS — `FrameIngestorTest` (6), `HubTest` (9), `ContextHubServiceTest` (3) plus Task 5's tests.

Run: `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: PASS.

- [ ] **Step 12: Device smoke (adb only)**

```bash
ADB=/Users/<user>/Desktop/rokid/android-sdk/platform-tools/adb; PHONE=adb-RZCY902F76K-nmSM2g._adb-tls-connect._tcp
$ADB -s $PHONE install -r phone/build/outputs/apk/debug/phone-debug.apk
$ADB -s $PHONE logcat -c
$ADB -s $PHONE shell am start -n com.rokid.context/.MainActivity
sleep 5
$ADB -s $PHONE shell dumpsys activity services com.rokid.context | grep -c ContextHubService
$ADB -s $PHONE logcat -d -s PcGlassesLink:I ContextHub:I AndroidRuntime:E | tail -20
```
Expected: count ≥ 1 (the hub FGS is running next to the RFCOMM bridge); a `PcGlassesLink: link=…` line; no `FATAL EXCEPTION`. The glasses still run the RFCOMM build, so the session itself is not expected to work yet (that is Task 10). If Hi Rokid shows an authorization prompt on the phone, it needs a tap **(needs the user)**; record it as pending if nobody is there.

- [ ] **Step 13: Commit**

```bash
git add android-companion/phone
git commit -m "feat(hub): ContextHubService with CXR frame ingest and HubGraph status

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Glasses link layer — CXR-S bridge, `HudController`, session tracking, local pause set

**Requirements:** R-02 (`HudController` from Live Fit), R-05 (version check on every phone message; mismatch → OUTDATED and no session), R-07 (glasses-local pause authority; session-open tracked locally per §1.7), R-30 (units per §3.1), R-65 (part: glasses pause records persisted in DataStore, union with phone records, never removed implicitly, fail-closed on an unreadable store — Review Focus 5).

Ported: `/Users/<user>/Desktop/rokid/live-fitness-tracker/glasses/src/main/java/com/debasish/livefit/glasses/hud/HudController.kt` (subscribe/sendMessage, `connectionFor`, persisted settings) and `services/sync/src/main/kotlin/com/debasish/livefit/sync/LivenessMonitor.kt`. Live Fit's map/queue/page/discoverable handling is dropped; session-open tracking, `pc_hello` and frame acks are new.

**Files:**
- Modify: `android-companion/glasses/build.gradle.kts` (full replacement below)
- Create (in `android-companion/glasses/src/main/java/com/rokid/context/glasses/link/`): `GlassesBridge.kt`, `LivenessMonitor.kt`, `HudSettingsCache.kt`, `HudController.kt`
- Create (in `android-companion/glasses/src/main/java/com/rokid/context/glasses/pause/`): `PauseStore.kt`, `GlassesPauses.kt`
- Test (in `android-companion/glasses/src/test/java/com/rokid/context/glasses/`): `link/FakeBridge.kt`, `link/HudControllerTest.kt`, `pause/GlassesPausesTest.kt`

**Interfaces:**
- Consumes: `Channels`, `Wire`, `Decoded`, `StateFrame`, `CaptureState`, `HudSettings`, `Hello`, `Command`, `CommandEnvelope`, `FrameAck`, `FrameChunk`, `PauseRecord`, `PauseOrigin`, `PROTOCOL_VERSION` (`:core:model`).
- Produces:
  - `interface GlassesBridge { fun subscribe(channel: String, onText: (String) -> Unit); fun send(channel: String, vararg parts: String): Int; fun setConnectionListener(connected: () -> Unit, disconnected: () -> Unit) }`; `class CxrBridge(context: Context) : GlassesBridge`.
  - `class LivenessMonitor(clock: () -> Long, timeoutMs: Long = 25_000) { fun onFrame(); fun onTransportDisconnected(); fun isOnline(): Boolean }`.
  - `class HudSettingsCache(prefs: SharedPreferences) { fun read(): HudSettings; fun write(s: HudSettings); companion { const val PREFS = "hud" } }`.
  - `enum class HudConnection { CONNECTING, LIVE, OFFLINE, OUTDATED }`; `fun connectionFor(everReceived, online, outdated, sinceStartMs): HudConnection`.
  - `class HudController(scope, bridge: GlassesBridge, cache: HudSettingsCache, hello: () -> Hello, clock: () -> Long = System::currentTimeMillis)` with `frame: StateFlow<StateFrame?>`, `settings: StateFlow<HudSettings>`, `connection: StateFlow<HudConnection>`, `sessionOpen: StateFlow<Boolean>`, callbacks `onFrameAck: (FrameAck) -> Unit`, `onSessionOpened: () -> Unit`, `onPhonePauses: (List<PauseRecord>) -> Unit`, and `start()`, `send(command): Boolean`, `sendHello(): Boolean`, `sendFrameChunk(chunk): Int`, `onActivityExit()`; `TAG = "PcHud"`.
  - `class PauseStore(file: File, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis) { suspend fun read(): List<PauseRecord>; suspend fun write(records); companion { const val UNREADABLE_ID = "unreadable-store" } }`.
  - `sealed interface PauseToggle { data class Paused(record: PauseRecord); data class Resumed(clears: Set<String>) }`; `class GlassesPauses(store, clock = System::currentTimeMillis, newId = { UUID }, log = { Log.w(TAG, it) })` with `records: StateFlow<List<PauseRecord>>`, `suspend ensureLoaded()`, `active(nowMs)`, `captureAllowed(nowMs): Boolean`, `suspend toggle(): PauseToggle`, `suspend mergeFromPhone(records)`.

- [ ] **Step 1: Replace `glasses/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.rokid.context.glasses"
    lint { disable += "ExpiredTargetSdkVersion" }
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rokid.context.glasses"
        minSdk = 28
        targetSdk = 32 // Rokid OS is fixed
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:hud"))
    implementation("com.rokid.cxr:cxr-service-bridge:1.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0") // legacy MainActivity; removed in Task 10
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-service:2.8.5")
    implementation("androidx.datastore:datastore-preferences-core:1.1.1")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4") // legacy MainActivity; removed in Task 10
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Compose UI tests need ui-test-manifest's host activity, which is debug-only; release unit tests would have none.
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false } }
```

Run: `./gradlew :glasses:assembleDebug -q`
Expected: PASS (the legacy app still builds with the new dependencies).

- [ ] **Step 2: Write the failing tests**

`link/FakeBridge.kt` (test sources):
```kotlin
package com.rokid.context.glasses.link

/** Records sends; [phone] plays a phone → glasses message. */
class FakeBridge : GlassesBridge {
    private val subs = mutableMapOf<String, (String) -> Unit>()
    val sent = mutableListOf<Pair<String, List<String>>>()
    var rc = 0
    var connected: () -> Unit = {}
        private set
    var disconnected: () -> Unit = {}
        private set

    override fun subscribe(channel: String, onText: (String) -> Unit) { subs[channel] = onText }
    override fun send(channel: String, vararg parts: String): Int { sent += channel to parts.toList(); return rc }
    override fun setConnectionListener(connected: () -> Unit, disconnected: () -> Unit) {
        this.connected = connected
        this.disconnected = disconnected
    }
    fun phone(channel: String, text: String) = subs.getValue(channel)(text)
}
```

`link/HudControllerTest.kt`:
```kotlin
package com.rokid.context.glasses.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Verbosity
import com.rokid.context.core.model.Wire
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HudControllerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val bridge = FakeBridge()
    private var now = 1_000L
    private fun hello() = Hello(glassesBatteryPct = 80, bufferSize = 2, glassesNowMs = now, pauses = emptyList())
    private fun TestScope.controller(prefs: String = "hud_test", b: FakeBridge = bridge) =
        HudController(backgroundScope, b, HudSettingsCache(ctx.getSharedPreferences(prefs, 0)), ::hello) { now }.also { it.start() }
    private fun state(capture: CaptureState = CaptureState.Running) =
        Wire.encode(StateFrame(seq = 1, capture = capture, intervalSec = 300, phoneLinked = true, aiHealth = Health.Ok))
    private fun hellos() = bridge.sent.count { it.first == Channels.HELLO }

    @Test fun firstPhoneMessageOpensTheSessionAndSaysHelloOnce() = runTest {
        val c = controller()
        var opened = 0
        c.onSessionOpened = { opened++ }
        bridge.phone(Channels.STATE, state()); bridge.phone(Channels.STATE, state())
        assertTrue(c.sessionOpen.value)
        assertEquals(1, opened)
        assertEquals(1, hellos())
        assertEquals(HudConnection.LIVE, c.connection.value)
    }

    @Test fun btDisconnectClosesTheSessionAndTheNextMessageSaysHelloAgain() = runTest {
        val c = controller()
        bridge.phone(Channels.STATE, state())
        bridge.disconnected()
        assertFalse(c.sessionOpen.value)
        assertEquals("no frames while closed", -1, c.sendFrameChunk(FrameCodec.split("g1", ByteArray(4) { 1 }, capturedAtMs = 1).single()))
        bridge.phone(Channels.SETTINGS, Wire.encode(HudSettings()))
        assertTrue(c.sessionOpen.value)
        assertEquals(2, hellos())
    }

    @Test fun activityExitClosesTheSession() = runTest {
        val c = controller()
        bridge.phone(Channels.STATE, state())
        c.onActivityExit()
        assertFalse(c.sessionOpen.value)
    }

    /** Review Focus 4: another phone version → "Update", no hello, no frames. */
    @Test fun otherPhoneVersionShowsOutdatedAndNeverOpensASession() = runTest {
        val c = controller()
        bridge.phone(Channels.STATE, state().replace("\"v\":$PROTOCOL_VERSION", "\"v\":${PROTOCOL_VERSION + 1}"))
        assertEquals(HudConnection.OUTDATED, c.connection.value)
        assertFalse(c.sessionOpen.value)
        assertTrue(bridge.sent.isEmpty())
    }

    @Test fun settingsArePersistedAcrossRestarts() = runTest {
        controller(prefs = "persist")
        bridge.phone(Channels.SETTINGS, Wire.encode(HudSettings(verbosity = Verbosity.MAX, captureIntervalSec = 120)))
        val again = HudController(backgroundScope, FakeBridge(), HudSettingsCache(ctx.getSharedPreferences("persist", 0)), ::hello) { now }
        assertEquals(Verbosity.MAX, again.settings.value.verbosity)
        assertEquals(120, again.settings.value.captureIntervalSec)
    }

    @Test fun phonePausesAreForwardedFromStateAndSettings() = runTest {
        val c = controller()
        val got = mutableListOf<List<PauseRecord>>()
        c.onPhonePauses = { got += it }
        val p = PauseRecord("p1", PauseOrigin.PHONE, 1, null)
        bridge.phone(Channels.STATE, state(CaptureState.Paused(listOf(p))))
        bridge.phone(Channels.SETTINGS, Wire.encode(HudSettings(pauses = listOf(p))))
        assertEquals(listOf(listOf(p), listOf(p)), got)
    }

    @Test fun frameAcksReachTheUplinkAndGarbageDoesNot() = runTest {
        val c = controller()
        val acks = mutableListOf<FrameAck>()
        c.onFrameAck = { acks += it }
        bridge.phone(Channels.FRAME_ACK, Wire.encode(FrameAck(frameId = "g1", ok = true)))
        bridge.phone(Channels.FRAME_ACK, "garbage")
        assertEquals(listOf(FrameAck(frameId = "g1", ok = true)), acks)
    }

    @Test fun aSilentPhoneGoesOfflineAndClosesTheSession() = runTest {
        val c = controller()
        bridge.phone(Channels.STATE, state())
        now += 25_000
        advanceTimeBy(1_100)
        assertEquals(HudConnection.OFFLINE, c.connection.value)
        assertFalse(c.sessionOpen.value)
    }

    @Test fun chunksGoOutAsMetaPlusBase64() = runTest {
        val c = controller()
        bridge.phone(Channels.STATE, state())
        val chunk = FrameCodec.split("g1", ByteArray(10) { 1 }, capturedAtMs = 1).single()
        assertEquals(0, c.sendFrameChunk(chunk))
        assertEquals(Channels.FRAME to listOf(Wire.encode(chunk.meta), chunk.chunk), bridge.sent.last())
    }

    @Test fun connectionStates() {
        assertEquals(HudConnection.CONNECTING, connectionFor(everReceived = false, online = false, outdated = false, sinceStartMs = 11_999))
        assertEquals(HudConnection.OFFLINE, connectionFor(everReceived = false, online = false, outdated = false, sinceStartMs = 12_000))
        assertEquals(HudConnection.OFFLINE, connectionFor(everReceived = true, online = false, outdated = false, sinceStartMs = 1))
        assertEquals(HudConnection.LIVE, connectionFor(everReceived = true, online = true, outdated = false, sinceStartMs = 1))
        assertEquals(HudConnection.OUTDATED, connectionFor(everReceived = true, online = true, outdated = true, sinceStartMs = 1))
    }
}
```

`pause/GlassesPausesTest.kt`:
```kotlin
package com.rokid.context.glasses.pause

import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GlassesPausesTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000_000L
    private var n = 0
    private var job: Job? = null

    /** One DataStore per file at a time: the previous scope is closed first. */
    private fun pauses(name: String = "pauses.preferences_pb"): GlassesPauses = runBlocking {
        job?.cancelAndJoin()
        val j = SupervisorJob().also { job = it }
        GlassesPauses(PauseStore(File(tmp.root, name), CoroutineScope(Dispatchers.IO + j)) { now }, { now }, { "id${++n}" }, log = {})
    }
    @After fun tearDown() = runBlocking { job?.cancelAndJoin(); Unit }

    /** Review Focus 5: applied at once, persisted, and a restart never resumes capture. */
    @Test fun doubleTapPausesAtOnceAndSurvivesARestart() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        val t = p.toggle() as PauseToggle.Paused
        assertEquals(PauseRecord("id1", PauseOrigin.GLASSES, now, null), t.record)
        assertFalse(p.captureAllowed())
        val again = pauses().also { it.ensureLoaded() }
        assertFalse(again.captureAllowed())
    }

    @Test fun secondDoubleTapResumesOnlyTheKnownPauses() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.mergeFromPhone(listOf(PauseRecord("phone1", PauseOrigin.PHONE, now, null)))
        assertEquals(setOf("phone1"), (p.toggle() as PauseToggle.Resumed).clears)
        assertTrue(p.captureAllowed())
    }

    @Test fun phoneRecordsAreMergedNeverRemoved() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.toggle()
        p.mergeFromPhone(emptyList())
        assertEquals(listOf("id1"), p.records.value.map { it.pauseId })
        p.mergeFromPhone(listOf(PauseRecord("phone1", PauseOrigin.PHONE, now, null)))
        assertEquals(setOf("id1", "phone1"), p.records.value.map { it.pauseId }.toSet())
    }

    @Test fun aTimedPauseExpiresOnTheGlassesClock() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.mergeFromPhone(listOf(PauseRecord("t", PauseOrigin.PHONE, now, now + 1_800_000)))
        assertFalse(p.captureAllowed())
        now += 1_800_000
        assertTrue(p.captureAllowed())
    }

    /** Review Focus 5: an unreadable store is one indefinite pause the wearer can resume; afterwards the store works. */
    @Test fun unreadableStoreFailsClosed() = runBlocking {
        File(tmp.root, "bad.preferences_pb").writeText("not a protobuf")
        val p = pauses("bad.preferences_pb").also { it.ensureLoaded() }
        assertFalse(p.captureAllowed())
        assertEquals(setOf(PauseStore.UNREADABLE_ID), (p.toggle() as PauseToggle.Resumed).clears)
        assertTrue(p.captureAllowed())
        assertTrue("the repaired store reads back resumed", pauses("bad.preferences_pb").also { it.ensureLoaded() }.captureAllowed())
    }

    @Test fun nothingIsAllowedBeforeLoading() = assertFalse(pauses().captureAllowed())
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :glasses:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: GlassesBridge`, `HudController`, `HudSettingsCache`, `GlassesPauses`, `PauseStore`.

- [ ] **Step 4: Implement the bridge, liveness and settings cache**

`link/GlassesBridge.kt`:
```kotlin
package com.rokid.context.glasses.link

import android.content.Context
import android.util.Log
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps

/** The CXR-S service bridge as the HUD uses it; [CxrBridge] in the app, a fake in tests. */
interface GlassesBridge {
    /** [onText] gets the first Caps string of each message on [channel] (called on a bridge thread). */
    fun subscribe(channel: String, onText: (String) -> Unit)
    /** CXR-S `sendMessage` code: 0 = handed to the CXR service, NOT delivered (phase 0); negative = refused. */
    fun send(channel: String, vararg parts: String): Int
    fun setConnectionListener(connected: () -> Unit, disconnected: () -> Unit)
}

class CxrBridge(context: Context) : GlassesBridge {
    private val bridge = CXRServiceBridge(context)

    override fun subscribe(channel: String, onText: (String) -> Unit) {
        bridge.subscribe(channel, CXRServiceBridge.MsgCallback { _, caps, _ ->
            runCatching { caps?.at(0)?.string }.getOrNull()?.let(onText)
        })
    }

    override fun send(channel: String, vararg parts: String): Int = try {
        bridge.sendMessage(channel, Caps().apply { parts.forEach { write(it) } })
    } catch (e: Exception) {
        Log.w(HudController.TAG, "send $channel threw ${e.javaClass.simpleName}")
        -3
    }

    override fun setConnectionListener(connected: () -> Unit, disconnected: () -> Unit) {
        bridge.setStatusListener(object : CXRServiceBridge.StatusListener {
            override fun onConnected(name: String?, mac: String?, type: Int) = connected()
            override fun onDisconnected() = disconnected()
            override fun onConnecting(name: String?, mac: String?, type: Int) = Unit
            override fun onARTCStatus(p0: Float, p1: Boolean) = Unit
            override fun onRokidAccountChanged(p0: String?) = Unit
            override fun onAudioNoise(p0: Float) = Unit
        })
    }
}
```

`link/LivenessMonitor.kt`:
```kotlin
package com.rokid.context.glasses.link

/**
 * Offline after [timeoutMs] without a phone message or on an explicit transport disconnect (ported from Live Fit
 * `sync/LivenessMonitor.kt`). The phone's heartbeat is 10 s, so 25 s tolerates two missed beats.
 */
class LivenessMonitor(private val clock: () -> Long, private val timeoutMs: Long = 25_000) {
    private var lastMessageMs: Long? = null
    private var disconnected = true

    @Synchronized fun onFrame() { lastMessageMs = clock(); disconnected = false }
    @Synchronized fun onTransportDisconnected() { disconnected = true }
    @Synchronized fun isOnline(): Boolean = !disconnected && lastMessageMs?.let { clock() - it < timeoutMs } == true
}
```

`link/HudSettingsCache.kt`:
```kotlin
package com.rokid.context.glasses.link

import android.content.SharedPreferences
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.Wire

/** Last `pc_settings`, kept across restarts: verbosity, gestures and cadence apply offline (spec §5.5). */
class HudSettingsCache(private val prefs: SharedPreferences) {
    fun read(): HudSettings = prefs.getString(KEY, null)?.let { (Wire.decode<HudSettings>(it) as? Decoded.Ok)?.value } ?: HudSettings()
    fun write(s: HudSettings) { prefs.edit().putString(KEY, Wire.encode(s)).apply() }

    companion object {
        const val PREFS = "hud"
        private const val KEY = "hudSettings"
    }
}
```

- [ ] **Step 5: Implement `HudController.kt`**

```kotlin
package com.rokid.context.glasses.link

import android.util.Log
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.CommandEnvelope
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class HudConnection { CONNECTING, LIVE, OFFLINE, OUTDATED }

/** Pure: what the HUD shows about the phone link (spec §3.6; adapted from Live Fit `connectionFor`). */
fun connectionFor(everReceived: Boolean, online: Boolean, outdated: Boolean, sinceStartMs: Long): HudConnection = when {
    outdated -> HudConnection.OUTDATED
    online -> HudConnection.LIVE
    everReceived || sinceStartMs >= 12_000 -> HudConnection.OFFLINE
    else -> HudConnection.CONNECTING
}

/**
 * Glasses side of the CXR link (spec §3.1): renders phone frames and sends commands, `pc_hello` and frame chunks.
 * Session-open is tracked locally (spec §1.7): set on the first phone message of a session (and then `pc_hello` goes
 * out), cleared on a bridge disconnect, a silent phone, a version mismatch or when the activity exits. Frames go out
 * only while it is open; a send result of 0 never means delivered, only `pc_frame_ack` does.
 */
class HudController(
    private val scope: CoroutineScope,
    private val bridge: GlassesBridge,
    private val cache: HudSettingsCache,
    private val hello: () -> Hello,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val liveness = LivenessMonitor(clock)
    private val startedAt = clock()
    @Volatile private var everReceived = false
    @Volatile private var outdated = false

    private val _frame = MutableStateFlow<StateFrame?>(null)
    val frame: StateFlow<StateFrame?> = _frame
    private val _settings = MutableStateFlow(cache.read())
    val settings: StateFlow<HudSettings> = _settings
    private val _connection = MutableStateFlow(HudConnection.CONNECTING)
    val connection: StateFlow<HudConnection> = _connection
    private val _sessionOpen = MutableStateFlow(false)
    val sessionOpen: StateFlow<Boolean> = _sessionOpen

    /** All three are called on a bridge thread. */
    @Volatile var onFrameAck: (FrameAck) -> Unit = {}
    @Volatile var onSessionOpened: () -> Unit = {}
    @Volatile var onPhonePauses: (List<PauseRecord>) -> Unit = {}

    fun start() {
        bridge.subscribe(Channels.STATE) { onState(it) }
        bridge.subscribe(Channels.SETTINGS) { onSettings(it) }
        bridge.subscribe(Channels.FRAME_ACK) { onAck(it) }
        bridge.setConnectionListener(connected = { Log.i(TAG, "phone connected") }, disconnected = { closeSession("bt disconnected") })
        scope.launch { while (isActive) { refreshConnection(); delay(1_000) } }
    }

    private fun onState(text: String) {
        when (val d = Wire.decode<StateFrame>(text)) {
            is Decoded.Ok -> {
                phoneMessage()
                _frame.value = d.value
                (d.value.capture as? CaptureState.Paused)?.let { onPhonePauses(it.records) }
            }
            is Decoded.VersionMismatch -> markOutdated(d.remote)
            is Decoded.Malformed -> Log.w(TAG, "bad pc_state: ${d.reason}")
        }
        refreshConnection()
    }

    private fun onSettings(text: String) {
        when (val d = Wire.decode<HudSettings>(text)) {
            is Decoded.Ok -> {
                phoneMessage()
                _settings.value = d.value
                cache.write(d.value)
                onPhonePauses(d.value.pauses)
            }
            is Decoded.VersionMismatch -> markOutdated(d.remote)
            is Decoded.Malformed -> Log.w(TAG, "bad pc_settings: ${d.reason}")
        }
        refreshConnection()
    }

    private fun onAck(text: String) { (Wire.decode<FrameAck>(text) as? Decoded.Ok)?.value?.let { onFrameAck(it) } }

    /** First message of a session: say hello (clock, battery, buffer, pauses) before any frame is sent. */
    private fun phoneMessage() {
        everReceived = true
        outdated = false
        liveness.onFrame()
        if (_sessionOpen.compareAndSet(false, true)) {
            Log.i(TAG, "session open")
            sendHello()
            onSessionOpened()
        }
    }

    private fun markOutdated(remote: Int?) {
        Log.w(TAG, "phone protocol v=$remote, glasses v=$PROTOCOL_VERSION")
        outdated = true
        closeSession("version mismatch")
    }

    private fun closeSession(why: String) {
        liveness.onTransportDisconnected()
        if (_sessionOpen.compareAndSet(true, false)) Log.i(TAG, "session closed: $why")
        refreshConnection()
    }

    /** The activity is going away: the phone's CUSTOM_APP session closes with it (spec §1.7). */
    fun onActivityExit() = closeSession("activity exit")

    fun send(command: Command): Boolean = bridge.send(Channels.CMD, Wire.encode(CommandEnvelope(command = command))) == 0

    fun sendHello(): Boolean = bridge.send(Channels.HELLO, Wire.encode(hello())) == 0

    /** -1 while the session is closed; otherwise CXR's code (0 = handed over, not delivered). */
    fun sendFrameChunk(chunk: FrameChunk): Int =
        if (!_sessionOpen.value) -1 else bridge.send(Channels.FRAME, Wire.encode(chunk.meta), chunk.chunk)

    private fun refreshConnection() {
        if (_sessionOpen.value && !liveness.isOnline()) { closeSession("phone silent"); return }
        _connection.value = connectionFor(everReceived, liveness.isOnline(), outdated, clock() - startedAt)
    }

    companion object { const val TAG = "PcHud" }
}
```

- [ ] **Step 6: Implement the pause store and set**

`pause/PauseStore.kt`:
```kotlin
package com.rokid.context.glasses.pause

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * The glasses' persisted pause set (spec §5.5: DataStore on the glasses). Fail-closed: an unreadable file or bad JSON
 * reads as one indefinite pause ([UNREADABLE_ID]) the wearer can resume; the corrupt file is replaced on the next write.
 * [file] must end in `.preferences_pb`; only one store per file may be active.
 */
class PauseStore(file: File, scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    private val store = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { preferencesOf(KEY to encode(listOf(unreadable(clock())))) },
        scope = scope,
        produceFile = { file },
    )

    suspend fun read(): List<PauseRecord> {
        val text = store.data.first()[KEY] ?: return emptyList()
        return runCatching { Wire.json.decodeFromString(SERIALIZER, text) }.getOrElse { listOf(unreadable(clock())) }
    }

    suspend fun write(records: List<PauseRecord>) { store.edit { it[KEY] = encode(records) } }

    companion object {
        const val UNREADABLE_ID = "unreadable-store"
        private val KEY = stringPreferencesKey("pauses")
        private val SERIALIZER = ListSerializer(PauseRecord.serializer())
        private fun encode(records: List<PauseRecord>) = Wire.json.encodeToString(SERIALIZER, records)
        private fun unreadable(nowMs: Long) = PauseRecord(UNREADABLE_ID, PauseOrigin.GLASSES, nowMs, null)
    }
}
```

`pause/GlassesPauses.kt`:
```kotlin
package com.rokid.context.glasses.pause

import android.util.Log
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

sealed interface PauseToggle {
    data class Paused(val record: PauseRecord) : PauseToggle
    data class Resumed(val clears: Set<String>) : PauseToggle
}

/**
 * Glasses-local pause enforcement (spec §1.7 exception, §5.5). A double-tap pause applies at once and persists, linked
 * or not; phone records merge by union and are never removed here; capture runs only when no record is active on the
 * glasses clock; before the store is loaded nothing is allowed. A Resume names only the records the glasses know.
 */
class GlassesPauses(
    private val store: PauseStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val mutex = Mutex()
    private val _records = MutableStateFlow<List<PauseRecord>>(emptyList())
    val records: StateFlow<List<PauseRecord>> = _records
    @Volatile private var loaded = false

    suspend fun ensureLoaded() = mutex.withLock { loadLocked() }

    fun active(nowMs: Long = clock()): List<PauseRecord> = _records.value.filter { it.isActiveAt(nowMs) }

    fun captureAllowed(nowMs: Long = clock()): Boolean = loaded && active(nowMs).isEmpty()

    suspend fun toggle(): PauseToggle = mutex.withLock {
        loadLocked()
        val now = clock()
        val active = active(now)
        if (active.isNotEmpty()) {
            val clears = active.map { it.pauseId }.toSet()
            save(_records.value.filter { it.pauseId !in clears && it.isActiveAt(now) })
            PauseToggle.Resumed(clears)
        } else {
            val r = PauseRecord(newId(), PauseOrigin.GLASSES, createdAtMs = now, untilMs = null)
            save(_records.value.filter { it.isActiveAt(now) } + r)
            PauseToggle.Paused(r)
        }
    }

    /** Union with the phone's merged set (most restrictive wins); expired or known records change nothing. */
    suspend fun mergeFromPhone(records: List<PauseRecord>) = mutex.withLock {
        loadLocked()
        val known = _records.value.map { it.pauseId }.toSet()
        val now = clock()
        val added = records.filter { it.pauseId !in known && it.isActiveAt(now) }
        if (added.isNotEmpty()) save(_records.value + added)
    }

    private suspend fun loadLocked() {
        if (loaded) return
        _records.value = try {
            store.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("pause store unreadable (${e.javaClass.simpleName}): staying paused")
            listOf(PauseRecord(PauseStore.UNREADABLE_ID, PauseOrigin.GLASSES, clock(), null))
        }
        loaded = true
    }

    /** Memory first, so enforcement holds even if the disk write fails. */
    private suspend fun save(list: List<PauseRecord>) {
        _records.value = list
        try {
            store.write(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("pause store write failed (${e.javaClass.simpleName})")
        }
    }

    companion object { const val TAG = "PcPause" }
}
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew :glasses:testDebugUnitTest`
Expected: PASS — `HudControllerTest` (10), `GlassesPausesTest` (6).

- [ ] **Step 8: Commit**

```bash
git add android-companion/glasses
git commit -m "feat(glasses): CXR bridge, HudController with session tracking, local pause set

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Glasses outbound buffer and stop-and-wait uplink

**Requirements:** R-08 (one frame at a time, frame-level ack, 5 s timeout → retry from chunk 0, at most 3 attempts with 2 s backoff, `ok=false` → resend once then drop, delete only after ack, interrupted flush restarts from chunk 0, PTT suspends the flush), R-30 (`OutboundBuffer` 200 frames on disk, FIFO drop-oldest), R-35 (offline: capture continues into the buffer, flush on reconnect), R-65 (part: 72 h buffer age cap; `clear()` for a privacy pause).

Behaviour is the Phase 0 probe's flush (`spikes/cxr-probe/glasses-probe/.../MainActivity.kt` on `spike/cxr-probe` @ 341c3e2), rebuilt as tested production code with a persisted buffer and a session/listening gate.

**Files:**
- Create: `android-companion/glasses/src/main/java/com/rokid/context/glasses/capture/OutboundBuffer.kt`
- Create: `android-companion/glasses/src/main/java/com/rokid/context/glasses/link/FrameUplink.kt`
- Test: `android-companion/glasses/src/test/java/com/rokid/context/glasses/capture/OutboundBufferTest.kt`, `.../glasses/link/FrameUplinkTest.kt`

**Interfaces:**
- Consumes: `FrameCodec`, `FrameChunk`, `FrameAck`, `FrameAckReason`, `SAFE_CHUNK_CHARS`, `Wire` (Task 1).
- Produces:
  - `@Serializable data class BufferedMeta(frameId: String, capturedAtMs: Long, luma: Int? = null, phash: String? = null, batteryPct: Int? = null)`; `data class BufferedFrame(meta: BufferedMeta, jpeg: File)`; `fun atomicWrite(target: File, bytes: ByteArray)`.
  - `class OutboundBuffer(dir: File, capacity: Int = 200, maxAgeMs: Long = 72 h, clock = System::currentTimeMillis, writer: (File, ByteArray) -> Unit = ::atomicWrite, log: (String) -> Unit = {}) { val size: Int; fun add(frameId, jpeg, capturedAtMs, luma, phash, batteryPct): Boolean; fun oldest(): BufferedFrame?; fun remove(frameId); fun clear(); fun readJpeg(f): ByteArray }`.
  - `data class UplinkConfig(chunkChars = SAFE_CHUNK_CHARS, ackTimeoutMs = 5_000, maxAttempts = 3, retryBackoffMs = 2_000, maxNacks = 2)`; `class FrameUplink(scope, buffer, sendChunk: (FrameChunk) -> Int, canSend: () -> Boolean, config = UplinkConfig(), log: (String) -> Unit = {}) { fun kick(); fun onAck(ack: FrameAck); val flushing: StateFlow<Boolean> }`.

- [ ] **Step 1: Write the failing buffer test**

`capture/OutboundBufferTest.kt`:
```kotlin
package com.rokid.context.glasses.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class OutboundBufferTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_793_000_000_000L
    private val dir get() = File(tmp.root, "outbound")
    private fun buffer(capacity: Int = 200, writer: (File, ByteArray) -> Unit = ::atomicWrite) =
        OutboundBuffer(dir, capacity, clock = { now }, writer = writer)

    /** Spec §1.7: persisted in app storage, so a process restart keeps the frames, oldest first. */
    @Test fun framesSurviveARestartInCaptureOrder() {
        val b = buffer()
        b.add("g2", byteArrayOf(2), now - 10, null, null, null)
        b.add("g1", byteArrayOf(1), now - 20, 5, "ab", 80)
        val again = buffer()
        assertEquals(2, again.size)
        val first = again.oldest()!!
        assertEquals(BufferedMeta("g1", now - 20, 5, "ab", 80), first.meta)
        assertArrayEquals(byteArrayOf(1), again.readJpeg(first))
    }

    @Test fun aFullBufferDropsTheOldest() {
        val b = buffer(capacity = 3)
        (1..5).forEach { b.add("g$it", byteArrayOf(it.toByte()), now - 10 + it, null, null, null) }
        assertEquals(3, b.size)
        assertEquals("g3", b.oldest()!!.meta.frameId)
        assertEquals(6, dir.list()!!.size) // jpg + json per frame
    }

    @Test fun framesOlderThan72HoursAreDropped() {
        val b = buffer()
        b.add("old", byteArrayOf(1), now - 72 * 3_600_000L - 1, null, null, null)
        b.add("new", byteArrayOf(2), now - 1, null, null, null)
        assertEquals("new", b.oldest()!!.meta.frameId)
        assertEquals(1, b.size)
    }

    /** Review Focus 2 (glasses side): storage full → nothing half-written, the buffer is unchanged. */
    @Test fun diskFullLeavesNothingBehind() {
        var calls = 0
        val b = buffer(writer = { f, bytes -> if (++calls == 2) throw IOException("ENOSPC") else atomicWrite(f, bytes) })
        assertFalse(b.add("g1", byteArrayOf(1), now, null, null, null))
        assertEquals(0, b.size)
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    /** Review Focus 3: a crash between the two writes, or mid-write, leaves files that load as nothing. */
    @Test fun crashLeftoversAreDiscardedOnLoad() {
        dir.mkdirs()
        File(dir, "000000000000001_g1.jpg").writeBytes(byteArrayOf(1))
        File(dir, "000000000000002_g2.json").writeText("{}")
        File(dir, "000000000000003_g3.jpg.tmp").writeText("x")
        assertEquals(0, buffer().size)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun removeAndClear() {
        val b = buffer()
        b.add("g1", byteArrayOf(1), now - 2, null, null, null)
        b.add("g2", byteArrayOf(2), now - 1, null, null, null)
        b.remove("g1")
        assertEquals("g2", b.oldest()!!.meta.frameId)
        b.clear()
        assertNull(b.oldest())
        assertTrue(dir.list()!!.isEmpty())
    }
}
```

- [ ] **Step 2: Write the failing uplink test**

`link/FrameUplinkTest.kt`:
```kotlin
package com.rokid.context.glasses.link

import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.glasses.capture.OutboundBuffer
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FrameUplinkTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 1_793_000_000_000L
    private val sent = mutableListOf<FrameChunk>()
    private var open = true
    private fun buffer() = OutboundBuffer(File(tmp.root, "out"), clock = { now })
    private fun jpeg(n: Int) = ByteArray(n) { (it % 7).toByte() }

    /** [answer] is the phone: called for each frame's last chunk, its ack (if any) arrives before the wait starts. */
    private fun TestScope.uplink(b: OutboundBuffer, config: UplinkConfig = UplinkConfig(), rc: Int = 0, answer: (FrameChunk) -> FrameAck? = { null }): FrameUplink {
        lateinit var u: FrameUplink
        u = FrameUplink(backgroundScope, b, sendChunk = { c ->
            sent += c
            if (rc == 0 && c.meta.chunkIdx == c.meta.chunkCount - 1) answer(c)?.let(u::onAck)
            rc
        }, canSend = { open }, config = config)
        return u
    }
    private fun okFor(c: FrameChunk) = FrameAck(frameId = c.meta.frameId, ok = true)

    @Test fun ackedFramesAreDeletedOldestFirst() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now - 2, null, null, 80); b.add("g2", jpeg(100), now - 1, null, null, 80)
        uplink(b, answer = ::okFor).kick(); advanceUntilIdle()
        assertEquals(listOf("g1", "g2"), sent.map { it.meta.frameId })
        assertEquals(0, b.size)
    }

    @Test fun aLargeFrameSendsEveryChunkBeforeWaiting() = runTest {
        val b = buffer()
        b.add("g1", jpeg(5_000), now, null, null, null)
        uplink(b, UplinkConfig(chunkChars = 2_000), answer = ::okFor).kick(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 3), sent.map { it.meta.chunkIdx })
        assertEquals(0, b.size)
    }

    /** Phase 0: 5 s ack timeout + 2 s backoff, 3 attempts, then stop and keep the backlog. */
    @Test fun noAckRetriesThreeTimesThenKeepsTheBacklog() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now - 1, null, null, null); b.add("g2", jpeg(100), now, null, null, null)
        val sentAt = mutableListOf<Long>()
        val u = FrameUplink(backgroundScope, b, sendChunk = { sentAt += testScheduler.currentTime; sent += it; 0 }, canSend = { open })
        u.kick(); advanceUntilIdle()
        assertEquals(listOf(0L, 7_000L, 14_000L), sentAt)
        assertEquals("later frames wait for the next flush", setOf("g1"), sent.map { it.meta.frameId }.toSet())
        assertEquals(2, b.size)
        assertFalse(u.flushing.value)
    }

    @Test fun nackResendsOnceThenDropsAndMovesOn() = runTest {
        val b = buffer()
        b.add("bad", jpeg(100), now - 1, null, null, null); b.add("good", jpeg(100), now, null, null, null)
        uplink(b, answer = { c ->
            if (c.meta.frameId == "bad") FrameAck(frameId = "bad", ok = false, reason = FrameAckReason.CRC) else okFor(c)
        }).kick(); advanceUntilIdle()
        assertEquals(listOf("bad", "bad", "good"), sent.map { it.meta.frameId })
        assertEquals(0, b.size)
    }

    /** rc ≠ 0 (CXR refused) is a failed attempt without the 5 s wait. */
    @Test fun aRefusedSendCountsAsATimeout() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now, null, null, null)
        uplink(b, rc = -1).kick(); advanceUntilIdle()
        assertEquals(3, sent.size)
        assertEquals(1, b.size)
        assertEquals(4_000L, testScheduler.currentTime)
    }

    /** Session closed or listening (PTT priority): nothing is sent; the next kick after the gate opens flushes. */
    @Test fun aClosedGateSendsNothing() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now, null, null, null)
        open = false
        val u = uplink(b, answer = ::okFor)
        u.kick(); advanceUntilIdle()
        assertTrue(sent.isEmpty())
        assertEquals(1, b.size)
        open = true
        u.kick(); advanceUntilIdle()
        assertEquals(0, b.size)
    }

    /** Review Focus 3: link drop mid-frame keeps it and the rest; the next flush restarts it from chunk 0. */
    @Test fun gateClosingMidBacklogKeepsTheRestAndRestartsFromChunkZero() = runTest {
        val b = buffer()
        b.add("g1", jpeg(5_000), now - 1, null, null, null); b.add("g2", jpeg(100), now, null, null, null)
        val first = FrameUplink(backgroundScope, b, sendChunk = { c ->
            sent += c
            if (c.meta.frameId == "g1" && c.meta.chunkIdx == 1) open = false
            0
        }, canSend = { open }, config = UplinkConfig(chunkChars = 2_000))
        first.kick(); advanceUntilIdle()
        assertEquals(listOf(0, 1), sent.map { it.meta.chunkIdx })
        assertEquals(2, b.size)
        sent.clear(); open = true
        uplink(b, UplinkConfig(chunkChars = 2_000), answer = ::okFor).kick(); advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 3, 0), sent.map { it.meta.chunkIdx })
        assertEquals(0, b.size)
    }

    @Test fun kickWhileFlushingDoesNotStartASecondFlush() = runTest {
        val b = buffer()
        b.add("g1", jpeg(100), now, null, null, null)
        val u = uplink(b)
        u.kick(); u.kick(); advanceTimeBy(1)
        assertEquals(1, sent.size)
        assertTrue(u.flushing.value)
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :glasses:testDebugUnitTest --tests "*OutboundBufferTest" --tests "*FrameUplinkTest"`
Expected: FAIL — `Unresolved reference: OutboundBuffer`, `atomicWrite`, `BufferedMeta`, `FrameUplink`, `UplinkConfig`.

- [ ] **Step 4: Implement `OutboundBuffer.kt`**

```kotlin
package com.rokid.context.glasses.capture

import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.Wire
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.TreeMap

@Serializable
data class BufferedMeta(val frameId: String, val capturedAtMs: Long, val luma: Int? = null, val phash: String? = null, val batteryPct: Int? = null)

data class BufferedFrame(val meta: BufferedMeta, val jpeg: File)

/** Write-to-temp, fsync, rename: a reader sees the whole file or none of it. */
fun atomicWrite(target: File, bytes: ByteArray) {
    val tmp = File(target.parentFile, "${target.name}.tmp")
    try {
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(target)) throw IOException("rename failed")
    } catch (e: IOException) {
        tmp.delete()
        throw e
    }
}

/**
 * Frames waiting for the phone (spec §1.7, §3.1): up to [capacity] (200 ≈ 4 MB) in app storage so a restart keeps
 * them; FIFO, the oldest dropped first when full; frames older than [maxAgeMs] (72 h, spec §5.5) are dropped. Each
 * entry is a JPEG plus a meta file, both written atomically; an entry missing either one is a crash leftover and is
 * discarded on load. A frame leaves only through [remove] (positive ack or second NACK) or [clear] (privacy pause).
 */
class OutboundBuffer(
    private val dir: File,
    private val capacity: Int = 200,
    private val maxAgeMs: Long = 72 * 3_600_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val writer: (File, ByteArray) -> Unit = ::atomicWrite,
    private val log: (String) -> Unit = {},
) {
    /** Key "<capturedAtMs, 15 digits>_<frameId>" sorts in capture order. */
    private val frames = TreeMap<String, BufferedFrame>()

    init { load() }

    val size: Int @Synchronized get() = frames.size

    /** False when the frame could not be stored (e.g. storage full); nothing is left behind then. */
    @Synchronized fun add(frameId: String, jpeg: ByteArray, capturedAtMs: Long, luma: Int?, phash: String?, batteryPct: Int?): Boolean {
        require(FrameCodec.isValidFrameId(frameId)) { "bad frame id" }
        val key = keyOf(capturedAtMs, frameId)
        val j = File(dir, "$key.jpg")
        val m = File(dir, "$key.json")
        val meta = BufferedMeta(frameId, capturedAtMs, luma, phash, batteryPct)
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create buffer directory")
            writer(j, jpeg)
            writer(m, Wire.json.encodeToString(BufferedMeta.serializer(), meta).toByteArray())
        } catch (e: IOException) {
            j.delete(); m.delete()
            log("frame $frameId not buffered: ${e.javaClass.simpleName}")
            return false
        }
        frames[key] = BufferedFrame(meta, j)
        while (frames.size > capacity) { log("buffer full: dropped ${frames.firstEntry().value.meta.frameId}"); delete(frames.firstKey()) }
        return true
    }

    /** The oldest frame young enough to send; older ones are dropped on the way. */
    @Synchronized fun oldest(): BufferedFrame? {
        val cutoff = clock() - maxAgeMs
        while (frames.isNotEmpty()) {
            val (key, f) = frames.firstEntry()
            if (f.meta.capturedAtMs >= cutoff) return f
            log("dropped ${f.meta.frameId}: older than 72 h")
            delete(key)
        }
        return null
    }

    @Synchronized fun remove(frameId: String) {
        frames.entries.firstOrNull { it.value.meta.frameId == frameId }?.key?.let { delete(it) }
    }

    @Synchronized fun clear() { frames.keys.toList().forEach { delete(it) } }

    fun readJpeg(f: BufferedFrame): ByteArray = f.jpeg.readBytes()

    private fun delete(key: String) {
        frames.remove(key)
        File(dir, "$key.jpg").delete()
        File(dir, "$key.json").delete()
    }

    private fun load() {
        val files = dir.listFiles().orEmpty()
        files.filter { it.extension == "tmp" }.forEach { it.delete() }
        val jpgs = files.filter { it.extension == "jpg" }.associateBy { it.nameWithoutExtension }
        val metas = files.filter { it.extension == "json" }.associateBy { it.nameWithoutExtension }
        for (key in jpgs.keys + metas.keys) {
            val j = jpgs[key]
            val m = metas[key]
            val meta = if (j != null && m != null) runCatching { Wire.json.decodeFromString(BufferedMeta.serializer(), m.readText()) }.getOrNull() else null
            if (j == null || meta == null || !FrameCodec.isValidFrameId(meta.frameId)) { j?.delete(); m?.delete(); continue }
            frames[key] = BufferedFrame(meta, j)
        }
        while (frames.size > capacity) delete(frames.firstKey())
    }

    private fun keyOf(capturedAtMs: Long, frameId: String) = String.format(Locale.ROOT, "%015d_%s", capturedAtMs, frameId)
}
```

- [ ] **Step 5: Implement `FrameUplink.kt`**

```kotlin
package com.rokid.context.glasses.link

import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.SAFE_CHUNK_CHARS
import com.rokid.context.glasses.capture.OutboundBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Phase 0 values (spec §1.8). */
data class UplinkConfig(
    val chunkChars: Int = SAFE_CHUNK_CHARS,
    val ackTimeoutMs: Long = 5_000,
    val maxAttempts: Int = 3,
    val retryBackoffMs: Long = 2_000,
    /** `ok=false` acks before a frame is dropped: the first NACK resends once. */
    val maxNacks: Int = 2,
)

/**
 * Stop-and-wait frame flush (spec §1.8): one frame at a time, all its chunks, then wait for `pc_frame_ack`. ok=true →
 * delete; ok=false → resend once, then drop; no ack within 5 s (or a refused send) → retry from chunk 0 after 2 s, at
 * most 3 attempts, then stop and keep the rest for the next flush. [canSend] (session open and not listening) is
 * checked before every chunk, so push-to-talk audio never queues behind frames; an interrupted frame restarts from
 * chunk 0 next time.
 */
class FrameUplink(
    private val scope: CoroutineScope,
    private val buffer: OutboundBuffer,
    private val sendChunk: (FrameChunk) -> Int,
    private val canSend: () -> Boolean,
    private val config: UplinkConfig = UplinkConfig(),
    private val log: (String) -> Unit = {},
) {
    private enum class Outcome { DELIVERED, DROPPED, RETAINED, INTERRUPTED }

    private val acks = ConcurrentHashMap<String, CompletableDeferred<FrameAck>>()
    private var job: Job? = null
    private val _flushing = MutableStateFlow(false)
    val flushing: StateFlow<Boolean> = _flushing

    /** Start a flush unless one is running: on session open, after a capture, when listening ends. */
    @Synchronized fun kick() {
        if (job?.isActive == true) return
        _flushing.value = true
        job = scope.launch {
            try { flush() } finally { _flushing.value = false }
        }
    }

    /** Called on a bridge thread. A late ack for a frame no longer awaited is ignored. */
    fun onAck(ack: FrameAck) { acks[ack.frameId]?.complete(ack) }

    private suspend fun flush() {
        var sent = 0
        var dropped = 0
        while (canSend()) {
            val f = buffer.oldest() ?: break
            val jpeg = try {
                buffer.readJpeg(f)
            } catch (e: IOException) {
                log("unreadable ${f.meta.frameId}: dropped")
                buffer.remove(f.meta.frameId)
                continue
            }
            val chunks = FrameCodec.split(f.meta.frameId, jpeg, f.meta.capturedAtMs, f.meta.luma, f.meta.phash, f.meta.batteryPct, config.chunkChars)
            when (deliver(f.meta.frameId, chunks)) {
                Outcome.DELIVERED -> { buffer.remove(f.meta.frameId); sent++; log("delivered ${f.meta.frameId}") }
                Outcome.DROPPED -> { buffer.remove(f.meta.frameId); dropped++; log("dropped ${f.meta.frameId} after ${config.maxNacks} NACKs") }
                Outcome.RETAINED -> { log("flush stopped after ${config.maxAttempts} timeouts; retained=${buffer.size}"); break }
                Outcome.INTERRUPTED -> { log("flush interrupted; retained=${buffer.size}"); break }
            }
        }
        log("flush done sent=$sent dropped=$dropped retained=${buffer.size}")
    }

    private suspend fun deliver(frameId: String, chunks: List<FrameChunk>): Outcome {
        var timeouts = 0
        var nacks = 0
        while (true) {
            val waiter = CompletableDeferred<FrameAck>()
            acks[frameId] = waiter
            try {
                var refused = false
                for (c in chunks) {
                    if (!canSend()) return Outcome.INTERRUPTED
                    if (sendChunk(c) != 0) { refused = true; break }
                }
                val ack = if (refused) null else withTimeoutOrNull(config.ackTimeoutMs) { waiter.await() }
                when {
                    ack == null -> {
                        timeouts++
                        log("frame $frameId timeout $timeouts/${config.maxAttempts}")
                        if (timeouts >= config.maxAttempts) return Outcome.RETAINED
                        delay(config.retryBackoffMs)
                    }
                    ack.ok -> return Outcome.DELIVERED
                    else -> {
                        nacks++
                        log("frame $frameId NACK ${ack.reason} $nacks/${config.maxNacks}")
                        if (nacks >= config.maxNacks) return Outcome.DROPPED
                    }
                }
            } finally {
                acks.remove(frameId, waiter)
            }
        }
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew :glasses:testDebugUnitTest`
Expected: PASS — `OutboundBufferTest` (6), `FrameUplinkTest` (8) plus Task 7's tests.

- [ ] **Step 7: Commit**

```bash
git add android-companion/glasses
git commit -m "feat(glasses): persisted outbound buffer and stop-and-wait frame uplink

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Glasses input and navigation — keys, gesture routing, `HudNav`, `ConfirmInput`, verbosity

**Requirements:** R-34 (defaults tap = ask, double-tap = pause/resume / cancel while Listening / dismiss on Answer, swipe = next/prev page; remapping through `HudSettings.gestures`; long-press and vertical swipes not used), R-33 (Silent / Medium / Max per §3.4), R-31 (overlay timing: Nudge 8 s, Confirm 15 s without voting, Paused splash 3 s), R-02 (`HudNav`, `ConfirmInput` adapted from Live Fit with their tests; `SwipeClassifier`/`DoubleTapDetector` from `:core:hud`).

Ported: `/Users/<user>/Desktop/rokid/live-fitness-tracker/glasses/src/main/java/com/debasish/livefit/glasses/MainActivity.kt` (`dispatchKeyEvent` key handling → `KeyGestures`), `glasses/.../hud/HudNav.kt` (page state, idle timeout, `visibleRows`), `glasses/.../hud/ConfirmInput.kt` and `glasses/src/test/java/.../hud/ConfirmInputTest.kt`. Live Fit's music/playlist/scroll-mode tables are dropped; Today's highlight scrolls on a long swipe.

**Files:**
- Create (in `android-companion/glasses/src/main/java/com/rokid/context/glasses/hud/`): `KeyGestures.kt`, `InputRouter.kt`, `HudNav.kt`, `ConfirmInput.kt`, `HudPresenter.kt`
- Test (in `android-companion/glasses/src/test/java/com/rokid/context/glasses/hud/`): `KeyGesturesTest.kt`, `InputRouterTest.kt`, `HudNavTest.kt`, `ConfirmInputTest.kt`, `HudPresenterTest.kt`

**Interfaces:**
- Consumes: `SwipeClassifier`, `SwipeKey`, `Swipe`, `DoubleTapDetector` (`:core:hud`); `Gesture`, `GestureAction`, `GestureDefaults`, `HudPage`, `Verbosity`, `Overlay`, `Command`, `StateFrame` (`:core:model`).
- Produces:
  - `sealed interface PadInput { data class Swiped(swipe: Swipe); Tapped; DoubleTapped }`; `data class KeyResult(consumed: Boolean, inputs: List<PadInput> = emptyList())`; `class KeyGestures { val deadlineMs: Long?; fun onKeyDown(keyCode, repeatCount, uptimeMs, wallMs): KeyResult; fun onKeyUp(keyCode): KeyResult; fun onBack(wallMs): List<PadInput>; fun onTimer(uptimeMs): List<PadInput> }`.
  - `sealed interface Routed { data class Mapped(action: GestureAction, long: Boolean = false); ConfirmToggle; ConfirmChoose; ConfirmNo; CancelListening; DismissOverlay }`; `object InputRouter { fun route(input: PadInput, overlay: Overlay?, gestures: Map<Gesture, GestureAction>): Routed }`.
  - `fun pagesFor(v: Verbosity): List<HudPage>`; `data class HudNav(page = GLANCE, todayRow = 0, lastInputMs = 0) { fun onSwipeAction(action, long, pages, todayRows, nowMs): HudNav; fun step(delta, pages, nowMs): HudNav; fun touched(nowMs): HudNav; fun tick(nowMs): HudNav; fun reconcile(pages, todayRows): HudNav; companion { IDLE_MS = 6_000 } }`; `fun visibleRows(size: Int, highlight: Int?, rows: Int): IntRange`.
  - `class ConfirmInput { val highlightYes: Boolean; val hasPending: Boolean; fun onConfirm(c: Overlay.Confirm?): Boolean; fun onSwipe(); fun onTap(): Command.Confirm?; fun onNo(): Command.Confirm? }`.
  - `sealed interface LocalOverlay { val atMs: Long; data class Toast(text, atMs); data class Splash(atMs) }`; `data class HudView(page: HudPage?, overlay: Overlay?)`; `class HudPresenter { fun dismiss(o: Overlay?); fun present(frame: StateFrame?, verbosity: Verbosity, nav: HudNav, local: LocalOverlay?, nowMs: Long): HudView; companion { NUDGE_MS, CONFIRM_MS, SPLASH_MS, TOAST_MS, fun allowed(o, v): Boolean, fun limitMs(o): Long } }`.

- [ ] **Step 1: Write the failing tests**

`KeyGesturesTest.kt`:
```kotlin
package com.rokid.context.glasses.hud

import com.rokid.context.core.hud.Swipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyGesturesTest {
    private val k = KeyGestures()

    @Test fun enterKeyUpIsATap() {
        assertTrue(k.onKeyDown(KeyGestures.KEY_ENTER, 0, 0, 0).consumed)
        assertEquals(listOf<PadInput>(PadInput.Tapped), k.onKeyUp(KeyGestures.KEY_ENTER).inputs)
    }

    /** Device-verified: a double-tap is two KEYCODE_NOTIFICATION presses ~150 ms apart. */
    @Test fun twoNotificationKeysQuicklyAreADoubleTap() {
        assertTrue(k.onKeyDown(KeyGestures.KEY_NOTIFICATION, 0, uptimeMs = 0, wallMs = 0).inputs.isEmpty())
        assertEquals(listOf<PadInput>(PadInput.DoubleTapped), k.onKeyDown(KeyGestures.KEY_NOTIFICATION, 0, uptimeMs = 150, wallMs = 150).inputs)
    }

    @Test fun backIsADoubleTapOnOtherFirmware() = assertEquals(listOf<PadInput>(PadInput.DoubleTapped), k.onBack(wallMs = 0))

    @Test fun shortSwipeRightThenTwinIsOneForwardSwipe() {
        k.onKeyDown(KeyGestures.KEY_NOTIFICATION, 0, 0, 0)
        assertTrue(k.onKeyDown(KeyGestures.KEY_RIGHT, 0, 10, 10).inputs.isEmpty())
        assertEquals(listOf<PadInput>(PadInput.Swiped(Swipe(forward = true, long = false))), k.onKeyDown(KeyGestures.KEY_DOWN, 0, 50, 50).inputs)
    }

    @Test fun twoStepsAreALongSwipe() {
        k.onKeyDown(KeyGestures.KEY_NOTIFICATION, 0, 0, 0)
        k.onKeyDown(KeyGestures.KEY_LEFT, 0, 10, 10)
        k.onKeyDown(KeyGestures.KEY_LEFT, 0, 70, 70)
        assertEquals(listOf<PadInput>(PadInput.Swiped(Swipe(forward = false, long = true))), k.onKeyDown(KeyGestures.KEY_UP, 0, 110, 110).inputs)
    }

    @Test fun theTimerClosesALoneStep() {
        k.onKeyDown(KeyGestures.KEY_RIGHT, 0, 0, 0)
        assertEquals(150L, k.deadlineMs)
        assertEquals(listOf<PadInput>(PadInput.Swiped(Swipe(forward = true, long = false))), k.onTimer(150))
    }

    /** A quick swipe then a touch is not a double-tap: the swipe key breaks the 83 pair. */
    @Test fun aSwipeBreaksADoubleTapPair() {
        k.onKeyDown(KeyGestures.KEY_NOTIFICATION, 0, 0, 0)
        k.onKeyDown(KeyGestures.KEY_RIGHT, 0, 20, 20)
        k.onKeyDown(KeyGestures.KEY_DOWN, 0, 50, 50)
        assertFalse(PadInput.DoubleTapped in k.onKeyDown(KeyGestures.KEY_NOTIFICATION, 0, 200, 200).inputs)
    }

    @Test fun otherKeysAreNotConsumed() {
        assertFalse(k.onKeyDown(KeyGestures.KEY_VOLUME_UP, 0, 0, 0).consumed)
        assertFalse(k.onKeyUp(KeyGestures.KEY_VOLUME_UP).consumed)
    }

    @Test fun repeatsAreSwallowed() = assertTrue(k.onKeyDown(KeyGestures.KEY_RIGHT, repeatCount = 1, uptimeMs = 0, wallMs = 0).let { it.consumed && it.inputs.isEmpty() })
}
```

`InputRouterTest.kt`:
```kotlin
package com.rokid.context.glasses.hud

import com.rokid.context.core.hud.Swipe
import com.rokid.context.core.model.Gesture
import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.GestureDefaults
import com.rokid.context.core.model.Overlay
import kotlin.test.Test
import kotlin.test.assertEquals

class InputRouterTest {
    private val defaults = GestureDefaults.map
    private val fwd = PadInput.Swiped(Swipe(forward = true, long = false))
    private val back = PadInput.Swiped(Swipe(forward = false, long = true))

    @Test fun defaultsFollowSpec35() {
        assertEquals(Routed.Mapped(GestureAction.ASK), InputRouter.route(PadInput.Tapped, null, defaults))
        assertEquals(Routed.Mapped(GestureAction.PAUSE_RESUME), InputRouter.route(PadInput.DoubleTapped, null, defaults))
        assertEquals(Routed.Mapped(GestureAction.NEXT_PAGE), InputRouter.route(fwd, null, defaults))
        assertEquals(Routed.Mapped(GestureAction.PREV_PAGE, long = true), InputRouter.route(back, null, defaults))
    }

    @Test fun remappedGesturesUseTheUsersTable() {
        val custom = defaults + (Gesture.TAP to GestureAction.PAUSE_RESUME) + (Gesture.DOUBLE_TAP to GestureAction.NONE)
        assertEquals(Routed.Mapped(GestureAction.PAUSE_RESUME), InputRouter.route(PadInput.Tapped, null, custom))
        assertEquals(Routed.Mapped(GestureAction.NONE), InputRouter.route(PadInput.DoubleTapped, null, custom))
        assertEquals(Routed.Mapped(GestureAction.NONE), InputRouter.route(PadInput.Tapped, null, emptyMap()), "unmapped = none")
    }

    @Test fun confirmHasItsOwnRules() {
        val c = Overlay.Confirm("p1", "Add milk?", "Shopping list")
        assertEquals(Routed.ConfirmToggle, InputRouter.route(fwd, c, defaults))
        assertEquals(Routed.ConfirmChoose, InputRouter.route(PadInput.Tapped, c, defaults))
        assertEquals(Routed.ConfirmNo, InputRouter.route(PadInput.DoubleTapped, c, defaults))
    }

    @Test fun doubleTapCancelsWhileListening() {
        val l = Overlay.Listening("what")
        assertEquals(Routed.CancelListening, InputRouter.route(PadInput.DoubleTapped, l, defaults))
        assertEquals(Routed.Mapped(GestureAction.ASK), InputRouter.route(PadInput.Tapped, l, defaults), "tap again = send")
    }

    @Test fun swipeOrDoubleTapDismissesAnAnswer() {
        val a = Overlay.Answer("q", "a", speaking = true)
        assertEquals(Routed.DismissOverlay, InputRouter.route(fwd, a, defaults))
        assertEquals(Routed.DismissOverlay, InputRouter.route(PadInput.DoubleTapped, a, defaults))
        assertEquals(Routed.Mapped(GestureAction.ASK), InputRouter.route(PadInput.Tapped, a, defaults))
    }
}
```

`HudNavTest.kt`:
```kotlin
package com.rokid.context.glasses.hud

import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.Verbosity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HudNavTest {
    private val medium = pagesFor(Verbosity.MEDIUM)
    private val max = pagesFor(Verbosity.MAX)

    @Test fun pagesPerVerbosity() {
        assertTrue(pagesFor(Verbosity.SILENT).isEmpty())
        assertEquals(listOf(HudPage.GLANCE, HudPage.TODAY), medium)
        assertEquals(listOf(HudPage.GLANCE, HudPage.TODAY, HudPage.STATUS), max)
    }

    @Test fun startsOnGlanceAndWraps() {
        val n = HudNav()
        assertEquals(HudPage.GLANCE, n.page)
        assertEquals(HudPage.TODAY, n.step(1, medium, 0).page)
        assertEquals(HudPage.GLANCE, n.step(1, medium, 0).step(1, medium, 0).page)
        assertEquals(HudPage.STATUS, n.step(-1, max, 0).page)
    }

    @Test fun longSwipeOnTodayMovesTheHighlightAndClamps() {
        val today = HudNav(page = HudPage.TODAY)
        assertEquals(1, today.onSwipeAction(GestureAction.NEXT_PAGE, long = true, medium, todayRows = 3, nowMs = 1).todayRow)
        assertEquals(0, today.onSwipeAction(GestureAction.PREV_PAGE, long = true, medium, todayRows = 3, nowMs = 1).todayRow)
        assertEquals(2, HudNav(HudPage.TODAY, todayRow = 2).onSwipeAction(GestureAction.NEXT_PAGE, long = true, medium, 3, 1).todayRow)
        assertEquals(HudPage.TODAY, today.onSwipeAction(GestureAction.NEXT_PAGE, long = true, medium, 3, 1).page)
    }

    @Test fun shortSwipeOnTodayChangesPage() =
        assertEquals(HudPage.GLANCE, HudNav(page = HudPage.TODAY).onSwipeAction(GestureAction.NEXT_PAGE, long = false, medium, 3, 1).page)

    /** Spec §3.1: 6 s idle → Glance. */
    @Test fun idleReturnsToGlanceAfterSixSeconds() {
        val n = HudNav(page = HudPage.TODAY, todayRow = 2, lastInputMs = 1_000)
        assertEquals(HudPage.TODAY, n.tick(6_999).page)
        assertEquals(HudNav(lastInputMs = 1_000), n.tick(7_000))
    }

    @Test fun anyInputRestartsTheIdleTimer() = assertEquals(HudPage.TODAY, HudNav(page = HudPage.TODAY, lastInputMs = 0).touched(5_000).tick(10_000).page)

    @Test fun droppingToMediumLeavesStatus() = assertEquals(HudPage.GLANCE, HudNav(page = HudPage.STATUS).reconcile(medium, todayRows = 0).page)

    @Test fun aShorterTodayListClampsTheHighlight() = assertEquals(1, HudNav(HudPage.TODAY, todayRow = 5).reconcile(medium, todayRows = 2).todayRow)

    @Test fun visibleRowsCentreTheHighlight() {
        assertEquals(0 until 4, visibleRows(size = 8, highlight = 0, rows = 4))
        assertEquals(3 until 7, visibleRows(size = 8, highlight = 5, rows = 4))
        assertEquals(4 until 8, visibleRows(size = 8, highlight = 7, rows = 4))
        assertTrue(visibleRows(size = 0, highlight = 0, rows = 4).isEmpty())
    }
}
```

`ConfirmInputTest.kt` (Live Fit's tests, on `Overlay.Confirm` / `Command.Confirm`):
```kotlin
package com.rokid.context.glasses.hud

import com.rokid.context.core.model.Command
import com.rokid.context.core.model.Overlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfirmInputTest {
    private fun c(id: String) = Overlay.Confirm(id, "Add milk?", "Shopping list")

    @Test fun aNewProposalStartsOnYes() {
        val i = ConfirmInput()
        assertTrue(i.onConfirm(c("a")))
        assertTrue(i.highlightYes)
        assertFalse(i.onConfirm(c("a")), "same id is not new")
    }

    @Test fun swipeTogglesAndTapAnswersTheHighlight() {
        val i = ConfirmInput(); i.onConfirm(c("a"))
        i.onSwipe()
        assertEquals(Command.Confirm("a", yes = false), i.onTap())
    }

    @Test fun answerFollowsAReplacedProposal() {
        val i = ConfirmInput(); i.onConfirm(c("a")); i.onSwipe()
        i.onConfirm(c("b"))
        assertEquals(Command.Confirm("b", yes = true), i.onTap())
    }

    @Test fun doubleTapMeansNo() {
        val i = ConfirmInput(); i.onConfirm(c("a"))
        assertEquals(Command.Confirm("a", yes = false), i.onNo())
    }

    @Test fun nothingPendingMeansNoAnswer() {
        val i = ConfirmInput(); i.onConfirm(c("a")); i.onConfirm(null)
        assertFalse(i.hasPending)
        assertNull(i.onTap()); assertNull(i.onNo())
    }
}
```

`HudPresenterTest.kt`:
```kotlin
package com.rokid.context.glasses.hud

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Verbosity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HudPresenterTest {
    private fun frame(o: Overlay?) = StateFrame(seq = 1, capture = CaptureState.Running, intervalSec = 300, phoneLinked = true, aiHealth = Health.Ok, overlay = o)
    private val nudge = Overlay.Nudge("Leave in 10 min")
    private val confirm = Overlay.Confirm("p1", "Add milk?", "Shopping list")

    @Test fun silentShowsNoPageAndNoNudge() {
        val v = HudPresenter().present(frame(nudge), Verbosity.SILENT, HudNav(page = HudPage.TODAY), local = null, nowMs = 0)
        assertEquals(HudView(page = null, overlay = null), v)
    }

    /** Spec §3.4: user-initiated flows still show in Silent. */
    @Test fun silentKeepsUserFlows() {
        for (o in listOf(Overlay.Listening("wh"), Overlay.Answer("q", "a", speaking = false), confirm, Overlay.PausedSplash)) {
            assertEquals(o, HudPresenter().present(frame(o), Verbosity.SILENT, HudNav(), null, 0).overlay)
        }
    }

    @Test fun mediumShowsANudgeOnlyAsTheOneLiner() {
        val v = HudPresenter().present(frame(nudge), Verbosity.MEDIUM, HudNav(), null, 0)
        assertEquals(HudView(HudPage.GLANCE, null), v)
    }

    @Test fun maxShowsANudgeToastForEightSeconds() {
        val p = HudPresenter()
        assertEquals(nudge, p.present(frame(nudge), Verbosity.MAX, HudNav(), null, 1_000).overlay)
        assertEquals(nudge, p.present(frame(nudge), Verbosity.MAX, HudNav(), null, 8_999).overlay)
        assertNull(p.present(frame(nudge), Verbosity.MAX, HudNav(), null, 9_000).overlay)
    }

    /** Spec §3.2/§5.3: the Confirm overlay hides after 15 s and never votes. */
    @Test fun confirmHidesAfterFifteenSeconds() {
        val p = HudPresenter()
        assertEquals(confirm, p.present(frame(confirm), Verbosity.MEDIUM, HudNav(), null, 0).overlay)
        assertNull(p.present(frame(confirm), Verbosity.MEDIUM, HudNav(), null, 15_000).overlay)
    }

    @Test fun pausedSplashLastsThreeSecondsLocallyOrFromThePhone() {
        val p = HudPresenter()
        assertEquals(Overlay.PausedSplash, p.present(frame(null), Verbosity.MEDIUM, HudNav(), LocalOverlay.Splash(atMs = 0), 2_999).overlay)
        assertNull(p.present(frame(null), Verbosity.MEDIUM, HudNav(), LocalOverlay.Splash(atMs = 0), 3_000).overlay)
        val q = HudPresenter()
        q.present(frame(Overlay.PausedSplash), Verbosity.MEDIUM, HudNav(), null, 0)
        assertNull(q.present(frame(Overlay.PausedSplash), Verbosity.MEDIUM, HudNav(), null, 3_000).overlay)
    }

    @Test fun aLocalToastBeatsAPhoneToastButNotAConfirm() {
        val local = LocalOverlay.Toast("Phone not connected", atMs = 0)
        assertEquals(Overlay.Toast("Phone not connected"), HudPresenter().present(frame(Overlay.Toast("Saved")), Verbosity.MEDIUM, HudNav(), local, 1).overlay)
        assertEquals(confirm, HudPresenter().present(frame(confirm), Verbosity.MEDIUM, HudNav(), local, 1).overlay)
    }

    @Test fun aDismissedAnswerStaysHiddenUntilANewOne() {
        val p = HudPresenter()
        val a = Overlay.Answer("q", "On the shelf", speaking = true)
        p.dismiss(a)
        assertNull(p.present(frame(a), Verbosity.MEDIUM, HudNav(), null, 0).overlay)
        val b = Overlay.Answer("q2", "Level 2", speaking = true)
        assertEquals(b, p.present(frame(b), Verbosity.MEDIUM, HudNav(), null, 1).overlay)
    }

    @Test fun statusPageOnlyInMax() {
        assertEquals(HudPage.GLANCE, HudPresenter().present(frame(null), Verbosity.MEDIUM, HudNav(page = HudPage.STATUS), null, 0).page)
        assertEquals(HudPage.STATUS, HudPresenter().present(frame(null), Verbosity.MAX, HudNav(page = HudPage.STATUS), null, 0).page)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :glasses:testDebugUnitTest --tests "com.rokid.context.glasses.hud.*"`
Expected: FAIL — `Unresolved reference: KeyGestures`, `InputRouter`, `HudNav`, `ConfirmInput`, `HudPresenter`.

- [ ] **Step 3: Implement `KeyGestures.kt`**

```kotlin
package com.rokid.context.glasses.hud

import com.rokid.context.core.hud.DoubleTapDetector
import com.rokid.context.core.hud.Swipe
import com.rokid.context.core.hud.SwipeClassifier
import com.rokid.context.core.hud.SwipeKey

sealed interface PadInput {
    data class Swiped(val swipe: Swipe) : PadInput
    data object Tapped : PadInput
    data object DoubleTapped : PadInput
}

/** [consumed] = the activity must not pass the key on (83 would move us to the back). */
data class KeyResult(val consumed: Boolean, val inputs: List<PadInput> = emptyList())

/**
 * Rokid touchpad keys → pad inputs (spec §3.5), ported from Live Fit `MainActivity.dispatchKeyEvent`. Every gesture
 * starts with KEYCODE_NOTIFICATION (83); DPAD keys are swipe steps ([SwipeClassifier], event uptime); two 83 presses or
 * one BACK are a double-tap ([DoubleTapDetector], wall clock); ENTER / DPAD_CENTER key-up is a tap. Long-press belongs
 * to Hi Rokid and vertical swipes are only the twin of a horizontal one.
 */
class KeyGestures(
    private val swipes: SwipeClassifier = SwipeClassifier(),
    private val doubleTap: DoubleTapDetector = DoubleTapDetector(),
) {
    /** When [onTimer] must run (uptime ms) to close an open swipe; null = none open. */
    val deadlineMs: Long? get() = swipes.deadlineMs

    fun onKeyDown(keyCode: Int, repeatCount: Int, uptimeMs: Long, wallMs: Long): KeyResult {
        val first = repeatCount == 0
        if (keyCode == KEY_NOTIFICATION) {
            if (!first) return KeyResult(true)
            val out = mutableListOf<PadInput>()
            swipes.onTouch(uptimeMs)?.let { out += PadInput.Swiped(it) } // a new touch closes the previous swipe
            if (doubleTap.onNotificationKey(wallMs)) out += PadInput.DoubleTapped
            return KeyResult(true, out)
        }
        val key = swipeKey(keyCode) ?: return KeyResult(keyCode == KEY_ENTER || keyCode == KEY_DPAD_CENTER)
        if (!first) return KeyResult(true)
        doubleTap.onGestureKey()
        return KeyResult(true, listOfNotNull(swipes.onKey(key, uptimeMs)?.let { PadInput.Swiped(it) }))
    }

    fun onKeyUp(keyCode: Int): KeyResult = when (keyCode) {
        KEY_ENTER, KEY_DPAD_CENTER -> KeyResult(true, listOf(PadInput.Tapped))
        KEY_NOTIFICATION, KEY_RIGHT, KEY_LEFT, KEY_DOWN, KEY_UP -> KeyResult(true)
        else -> KeyResult(false)
    }

    fun onBack(wallMs: Long): List<PadInput> = if (doubleTap.onBack(wallMs)) listOf(PadInput.DoubleTapped) else emptyList()

    fun onTimer(uptimeMs: Long): List<PadInput> = listOfNotNull(swipes.onTimer(uptimeMs)?.let { PadInput.Swiped(it) })

    private fun swipeKey(code: Int): SwipeKey? = when (code) {
        KEY_RIGHT -> SwipeKey.Right
        KEY_LEFT -> SwipeKey.Left
        KEY_DOWN -> SwipeKey.Down
        KEY_UP -> SwipeKey.Up
        else -> null
    }

    companion object {
        // android.view.KeyEvent values, repeated so this class stays plain JVM code.
        const val KEY_NOTIFICATION = 83
        const val KEY_RIGHT = 22
        const val KEY_LEFT = 21
        const val KEY_DOWN = 20
        const val KEY_UP = 19
        const val KEY_ENTER = 66
        const val KEY_DPAD_CENTER = 23
        /** KEYCODE_VOLUME_UP: a key we leave to the system. */
        const val KEY_VOLUME_UP = 24
    }
}
```

- [ ] **Step 4: Implement `InputRouter.kt`**

```kotlin
package com.rokid.context.glasses.hud

import com.rokid.context.core.model.Gesture
import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.Overlay

/** What a pad input means right now. */
sealed interface Routed {
    /** Through the wearer's gesture map; [long] = a long swipe (Today scroll). */
    data class Mapped(val action: GestureAction, val long: Boolean = false) : Routed
    data object ConfirmToggle : Routed
    data object ConfirmChoose : Routed
    data object ConfirmNo : Routed
    data object CancelListening : Routed
    data object DismissOverlay : Routed
}

/**
 * Spec §3.5: an overlay's own rules first (Confirm: swipe moves, tap answers, double-tap = No; Listening: double-tap
 * cancels; Answer: swipe or double-tap dismisses), otherwise `HudSettings.gestures` (unmapped = NONE).
 */
object InputRouter {
    fun route(input: PadInput, overlay: Overlay?, gestures: Map<Gesture, GestureAction>): Routed = when (overlay) {
        is Overlay.Confirm -> when (input) {
            is PadInput.Swiped -> Routed.ConfirmToggle
            PadInput.Tapped -> Routed.ConfirmChoose
            PadInput.DoubleTapped -> Routed.ConfirmNo
        }
        is Overlay.Listening -> if (input == PadInput.DoubleTapped) Routed.CancelListening else mapped(input, gestures)
        is Overlay.Answer -> if (input == PadInput.Tapped) mapped(input, gestures) else Routed.DismissOverlay
        else -> mapped(input, gestures)
    }

    private fun mapped(input: PadInput, g: Map<Gesture, GestureAction>): Routed = when (input) {
        is PadInput.Swiped -> Routed.Mapped(g[if (input.swipe.forward) Gesture.SWIPE_FORWARD else Gesture.SWIPE_BACK] ?: GestureAction.NONE, input.swipe.long)
        PadInput.Tapped -> Routed.Mapped(g[Gesture.TAP] ?: GestureAction.NONE)
        PadInput.DoubleTapped -> Routed.Mapped(g[Gesture.DOUBLE_TAP] ?: GestureAction.NONE)
    }
}
```

- [ ] **Step 5: Implement `HudNav.kt` and `ConfirmInput.kt`**

`HudNav.kt`:
```kotlin
package com.rokid.context.glasses.hud

import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.Verbosity

/** Pages in swipe rotation (spec §3.4): Silent shows none (REC dot only); Max adds Status. */
fun pagesFor(v: Verbosity): List<HudPage> = when (v) {
    Verbosity.SILENT -> emptyList()
    Verbosity.MEDIUM -> listOf(HudPage.GLANCE, HudPage.TODAY)
    Verbosity.MAX -> listOf(HudPage.GLANCE, HudPage.TODAY, HudPage.STATUS)
}

/**
 * Page and Today-row state (spec §3.1 `HudNav`), adapted from Live Fit `HudNav`: next/prev page wraps through
 * [pagesFor]; on Today a long swipe moves the highlighted row; [IDLE_MS] without input returns to Glance.
 */
data class HudNav(val page: HudPage = HudPage.GLANCE, val todayRow: Int = 0, val lastInputMs: Long = 0) {
    fun onSwipeAction(action: GestureAction, long: Boolean, pages: List<HudPage>, todayRows: Int, nowMs: Long): HudNav {
        val pageAction = action == GestureAction.NEXT_PAGE || action == GestureAction.PREV_PAGE
        if (page == HudPage.TODAY && long && pageAction && todayRows > 0) {
            val d = if (action == GestureAction.NEXT_PAGE) 1 else -1
            return copy(todayRow = (todayRow + d).coerceIn(0, todayRows - 1), lastInputMs = nowMs)
        }
        return when (action) {
            GestureAction.NEXT_PAGE -> step(1, pages, nowMs)
            GestureAction.PREV_PAGE -> step(-1, pages, nowMs)
            else -> touched(nowMs)
        }
    }

    fun step(delta: Int, pages: List<HudPage>, nowMs: Long): HudNav {
        if (pages.isEmpty()) return touched(nowMs)
        val i = pages.indexOf(page).coerceAtLeast(0)
        val target = pages[Math.floorMod(i + delta, pages.size)]
        return if (target == page) touched(nowMs) else HudNav(page = target, lastInputMs = nowMs)
    }

    /** Any input restarts the idle timer. */
    fun touched(nowMs: Long): HudNav = copy(lastInputMs = nowMs)

    fun tick(nowMs: Long): HudNav =
        if (page != HudPage.GLANCE && nowMs - lastInputMs >= IDLE_MS) HudNav(lastInputMs = lastInputMs) else this

    /** The visible page left the rotation (verbosity changed) → Glance; a shorter Today list clamps the highlight. */
    fun reconcile(pages: List<HudPage>, todayRows: Int): HudNav = when {
        pages.isNotEmpty() && page !in pages -> HudNav(lastInputMs = lastInputMs)
        todayRows > 0 && todayRow >= todayRows -> copy(todayRow = todayRows - 1)
        else -> this
    }

    companion object { const val IDLE_MS = 6_000L }
}

/** The [rows] rows shown around [highlight] (centred where possible) out of [size] (from Live Fit `HudNav.kt`). */
fun visibleRows(size: Int, highlight: Int?, rows: Int): IntRange {
    if (size <= 0) return IntRange.EMPTY
    val start = ((highlight ?: 0) - rows / 2).coerceIn(0, maxOf(0, size - rows))
    return start until minOf(size, start + rows)
}
```

`ConfirmInput.kt`:
```kotlin
package com.rokid.context.glasses.hud

import com.rokid.context.core.model.Command
import com.rokid.context.core.model.Overlay

/** Touchpad rules while a Confirm overlay shows (Live Fit `ConfirmInput`): swipe moves, tap answers, double-tap = No. */
class ConfirmInput {
    private var currentId: String? = null
    var highlightYes: Boolean = true
        private set
    val hasPending: Boolean get() = currentId != null

    /** True when a new proposal appeared; the highlight starts on ✓ (only a tap votes, spec §5.3). */
    fun onConfirm(c: Overlay.Confirm?): Boolean {
        val isNew = c != null && c.proposalId != currentId
        currentId = c?.proposalId
        if (isNew) highlightYes = true
        return isNew
    }

    fun onSwipe() { if (currentId != null) highlightYes = !highlightYes }
    fun onTap(): Command.Confirm? = currentId?.let { Command.Confirm(it, highlightYes) }
    fun onNo(): Command.Confirm? = currentId?.let { Command.Confirm(it, yes = false) }
}
```

- [ ] **Step 6: Implement `HudPresenter.kt`**

```kotlin
package com.rokid.context.glasses.hud

import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Verbosity

/** Glasses-originated feedback shown at once (offline toast, local pause splash). */
sealed interface LocalOverlay {
    val atMs: Long
    data class Toast(val text: String, override val atMs: Long) : LocalOverlay
    data class Splash(override val atMs: Long) : LocalOverlay
}

/** What to draw: [page] null = no page (Silent: REC dot only); at most one [overlay]. */
data class HudView(val page: HudPage?, val overlay: Overlay?)

/**
 * Verbosity and overlay timing (spec §3.2, §3.4). Silent: no page; overlays only for user flows (a Nudge never shows).
 * Medium: Glance/Today; a Nudge only as the Glance one-liner. Max: also Nudge toasts and the Status page. Nudge hides
 * after 8 s, Confirm after 15 s without voting, Paused splash and toasts after 3 s; Listening and Answer stay until the
 * phone clears them or the wearer dismisses them. A local overlay wins over a phone one, except over Confirm.
 */
class HudPresenter {
    private var shown: Overlay? = null
    private var shownAtMs = 0L
    private var dismissed: Overlay? = null

    fun dismiss(o: Overlay?) { dismissed = o }

    fun present(frame: StateFrame?, verbosity: Verbosity, nav: HudNav, local: LocalOverlay?, nowMs: Long): HudView {
        val pages = pagesFor(verbosity)
        val page = if (pages.isEmpty()) null else nav.page.takeIf { it in pages } ?: HudPage.GLANCE
        val phone = frame?.overlay?.takeIf { it != dismissed && allowed(it, verbosity) }
        if (phone != shown) { shown = phone; shownAtMs = nowMs }
        val phoneVisible = phone?.takeIf { nowMs - shownAtMs < limitMs(it) }
        val localVisible = when (local) {
            is LocalOverlay.Toast -> Overlay.Toast(local.text).takeIf { nowMs - local.atMs < TOAST_MS }
            is LocalOverlay.Splash -> Overlay.PausedSplash.takeIf { nowMs - local.atMs < SPLASH_MS }
            null -> null
        }
        val overlay = when {
            phoneVisible is Overlay.Confirm -> phoneVisible
            localVisible != null -> localVisible
            else -> phoneVisible
        }
        return HudView(page, overlay)
    }

    companion object {
        const val NUDGE_MS = 8_000L
        const val CONFIRM_MS = 15_000L
        const val SPLASH_MS = 3_000L
        const val TOAST_MS = 3_000L

        fun allowed(o: Overlay, v: Verbosity): Boolean = !(o is Overlay.Nudge && v != Verbosity.MAX)

        fun limitMs(o: Overlay): Long = when (o) {
            is Overlay.Nudge -> NUDGE_MS
            is Overlay.Confirm -> CONFIRM_MS
            Overlay.PausedSplash -> SPLASH_MS
            is Overlay.Toast -> TOAST_MS
            is Overlay.Listening, is Overlay.Answer -> Long.MAX_VALUE
        }
    }
}
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew :glasses:testDebugUnitTest`
Expected: PASS — `KeyGesturesTest` (9), `InputRouterTest` (5), `HudNavTest` (9), `ConfirmInputTest` (5), `HudPresenterTest` (9) plus Tasks 7–8.

- [ ] **Step 8: Commit**

```bash
git add android-companion/glasses
git commit -m "feat(glasses): touchpad gestures, gesture routing, HudNav, confirm input and verbosity

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Glasses HUD — pages, overlays, Compose `MainActivity`

**Requirements:** R-31 (Glance / Today / Status pages; Listening, Answer, Nudge, Confirm, Paused, Toast overlays; page dots), R-32 (`:core:hud` rendering rules: black = transparent, outlines, 1.0/0.6/0.35, tabular digits, Outlined icons, `Scaled` at `HudSettings.scale`/`position`, overlays at 1.0 in the lower band), R-33, R-34 (wiring), R-35 (offline: "offline" in the status row, tap → "Phone not connected"; no raw errors — only `StateFrame.error`/`aiHealth` short strings or local text), R-05 (mismatch UX: "Update Proactive Context"), R-02 (`ConfirmOverlay`, `MainActivity` adapted from Live Fit), R-30 (`MainActivity` unit), R-04 (part: XML layout and drawables removed; the activity no longer captures or uses RFCOMM).

Ported: `/Users/<user>/Desktop/rokid/live-fitness-tracker/glasses/src/main/java/com/debasish/livefit/glasses/MainActivity.kt` (bridge setup, key dispatch, swipe timer, BACK = double-tap, FLAG_KEEP_SCREEN_ON) and `glasses/.../hud/ConfirmOverlay.kt` (✓/✕ choices; the voice-answer parts are dropped).

After this task the glasses app speaks CXR (state, settings, hello, pauses). Scheduled capture still goes through the RFCOMM-era `PulseKeepAliveService` until Task 11; the phone's RFCOMM bridge still runs, so frames keep arriving on the old path meanwhile.

**Files:**
- Create: `android-companion/glasses/src/main/java/com/rokid/context/glasses/GlassesRuntime.kt`
- Create (in `android-companion/glasses/src/main/java/com/rokid/context/glasses/ui/`): `Format.kt`, `HudRoot.kt`, `Pages.kt`, `Overlays.kt`, `ConfirmOverlay.kt`
- Replace: `android-companion/glasses/src/main/java/com/rokid/context/glasses/MainActivity.kt`
- Modify: `android-companion/glasses/src/main/AndroidManifest.xml`, `android-companion/glasses/src/main/res/values/strings.xml`, `android-companion/glasses/build.gradle.kts` (drop two legacy lines)
- Delete: `android-companion/glasses/src/main/res/layout/activity_main.xml`, `.../res/drawable/` (all four `pulse_*.xml`), `.../res/values/styles.xml`
- Test (in `android-companion/glasses/src/test/java/com/rokid/context/glasses/ui/`): `FormatTest.kt`, `HudRootTest.kt`

**Interfaces:**
- Consumes: Task 7 `HudController`, `CxrBridge`, `HudSettingsCache`, `HudConnection`, `GlassesPauses`, `PauseStore`, `PauseToggle`; Task 8 `OutboundBuffer`, `FrameUplink`; Task 9 `KeyGestures`, `KeyResult`, `PadInput`, `InputRouter`, `Routed`, `HudNav`, `pagesFor`, `visibleRows`, `ConfirmInput`, `HudPresenter`, `HudView`, `LocalOverlay`; `:core:hud` `Hud`, `Glyph`, `Label`, `Scaled`, `OutlinedBox`, `HudToast`; legacy `PulseCaptureScheduler`, `PulseKeepAliveService.startPulse` (until Task 11).
- Produces:
  - `class GlassesRuntime` (process singleton: `get(context)`), with `scope`, `pauses: GlassesPauses`, `buffer: OutboundBuffer`, `listening: MutableStateFlow<Boolean>` (set by Phase 6 PTT), `uplink: FrameUplink`, `attach(c: HudController)`, `detach(c)`, `hello(nextCaptureAtMs: Long?): Hello`, `onFrameBuffered()`, `suspend togglePause(): PauseToggle`, `batteryPct(): Int?`; constants `TAG = "PcGlasses"`, `UPLINK_TAG = "PcUplink"`, `FLUSH_AFTER_HELLO_MS = 500`.
  - `data class HudModel(view, frame, settings, connection, batteryPct, paused, nextCaptureAtMs, nowMs, nav, confirmHighlightYes)`; `@Composable fun HudRoot(m: HudModel)`; `fun formatCountdown(nextAtMs: Long?, nowMs: Long): String`; `fun formatClock(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String`; `fun formatInterval(sec: Int): String`; `fun HudPosition.alignment(): Alignment`.
  - `MainActivity` companion: `TAG = "PcGlasses"`, `ACTION_CAPTURE_NOW = "com.rokid.context.glasses.action.CAPTURE_NOW"`, legacy `ACTION_PULSE_CAPTURE`, `EXTRA_SILENCY_MODE` (removed in Task 12). Private helpers `nextCaptureAt()`, `ensureCaptureScheduled()`, `handleIntent(i)` are the three places Task 11 changes.

- [ ] **Step 1: Write the failing UI tests**

`FormatTest.kt`:
```kotlin
package com.rokid.context.glasses.ui

import androidx.compose.ui.Alignment
import com.rokid.context.core.model.HudPosition
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test fun countdownIsMinutesAndSeconds() {
        assertEquals("4:12", formatCountdown(nextAtMs = 252_000, nowMs = 0))
        assertEquals("0:01", formatCountdown(nextAtMs = 1, nowMs = 0))
        assertEquals("--:--", formatCountdown(nextAtMs = null, nowMs = 0))
        assertEquals("--:--", formatCountdown(nextAtMs = 5, nowMs = 5))
    }

    /** Times are shown in the glasses' zone at display time (master Review Focus 4). */
    @Test fun clockUsesTheGivenZone() {
        val t = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli()
        assertEquals("01:30", formatClock(t, ZoneId.of("Europe/London")))
        assertEquals("06:00", formatClock(t, ZoneId.of("Asia/Kolkata")))
    }

    @Test fun intervalIsShort() {
        assertEquals("5 min", formatInterval(300))
        assertEquals("45 s", formatInterval(45))
    }

    @Test fun positionsMapToAlignments() {
        assertEquals(Alignment.BottomCenter, HudPosition.BOTTOM_CENTER.alignment())
        assertEquals(Alignment.TopStart, HudPosition.TOP_LEFT.alignment())
        assertEquals(Alignment.CenterEnd, HudPosition.CENTER_RIGHT.alignment())
    }
}
```

`HudRootTest.kt`:
```kotlin
package com.rokid.context.glasses.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.MemoryLine
import com.rokid.context.core.model.NudgeLine
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Verbosity
import com.rokid.context.glasses.hud.HudNav
import com.rokid.context.glasses.hud.HudView
import com.rokid.context.glasses.link.HudConnection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HudRootTest {
    @get:Rule val compose = createComposeRule()
    private val now = 1_793_000_000_000L
    private fun frame(overlay: Overlay? = null) = StateFrame(
        seq = 1, capture = CaptureState.Running, intervalSec = 300, glassesBatteryPct = 82, phoneLinked = true,
        aiHealth = Health.Degraded("AI unavailable"), queueDepth = 3, latestNudge = NudgeLine("Milk is almost out", now),
        today = listOf(MemoryLine("m1", now - 60_000, "Keys on the hallway shelf"), MemoryLine("m2", now - 120_000, "Parked on level 2")),
        overlay = overlay,
    )
    private fun model(
        page: HudPage? = HudPage.GLANCE,
        overlay: Overlay? = null,
        verbosity: Verbosity = Verbosity.MEDIUM,
        connection: HudConnection = HudConnection.LIVE,
        paused: Boolean = false,
    ) = HudModel(
        view = HudView(page, overlay), frame = frame(overlay), settings = HudSettings(verbosity = verbosity), connection = connection,
        batteryPct = 82, paused = paused, nextCaptureAtMs = now + 252_000, nowMs = now, nav = HudNav(page = page ?: HudPage.GLANCE),
        confirmHighlightYes = true,
    )

    @Test fun glanceShowsCountdownNudgeAndBattery() {
        compose.setContent { HudRoot(model()) }
        compose.onNodeWithText("NEXT").assertExists()
        compose.onNodeWithText("4:12").assertExists()
        compose.onNodeWithText("Milk is almost out").assertExists()
        compose.onNodeWithText("82%").assertExists()
    }

    @Test fun offlineGlanceSaysOffline() {
        compose.setContent { HudRoot(model(connection = HudConnection.OFFLINE)) }
        compose.onNodeWithText("offline").assertExists()
    }

    @Test fun pausedGlanceShowsPausedNotACountdown() {
        compose.setContent { HudRoot(model(paused = true)) }
        compose.onAllNodesWithText("PAUSED").assertCountEquals(2)
        compose.onNodeWithText("4:12").assertDoesNotExist()
    }

    @Test fun todayListsMemories() {
        compose.setContent { HudRoot(model(page = HudPage.TODAY)) }
        compose.onNodeWithText("TODAY").assertExists()
        compose.onNodeWithText("Keys on the hallway shelf", substring = true).assertExists()
        compose.onNodeWithText("Parked on level 2", substring = true).assertExists()
    }

    @Test fun statusShowsShortHumanText() {
        compose.setContent { HudRoot(model(page = HudPage.STATUS, verbosity = Verbosity.MAX)) }
        compose.onNodeWithText("AI unavailable", substring = true).assertExists()
        compose.onNodeWithText("3 waiting", substring = true).assertExists()
        compose.onNodeWithText("every 5 min", substring = true).assertExists()
    }

    @Test fun silentShowsOnlyTheRecDot() {
        compose.setContent { HudRoot(model(page = null, verbosity = Verbosity.SILENT)) }
        compose.onNodeWithText("NEXT").assertDoesNotExist()
        compose.onNodeWithText("82%").assertDoesNotExist()
    }

    @Test fun outdatedPhoneShowsTheUpdatePrompt() {
        compose.setContent { HudRoot(model(connection = HudConnection.OUTDATED)) }
        compose.onNodeWithText("Update Proactive Context").assertExists()
        compose.onNodeWithText("NEXT").assertDoesNotExist()
    }

    @Test fun everyOverlayRenders() {
        var m by mutableStateOf(model())
        compose.setContent { HudRoot(m) }
        val cases = listOf(
            Overlay.Listening("what did I") to "what did I",
            Overlay.Answer("Where are my keys?", "On the hallway shelf", speaking = true) to "On the hallway shelf",
            Overlay.Nudge("Leave in 10 min") to "Leave in 10 min",
            Overlay.Confirm("p1", "Add milk?", "Shopping list") to "Add milk?",
            Overlay.PausedSplash to "Capture paused",
            Overlay.Toast("Phone not connected") to "Phone not connected",
        )
        for ((o, text) in cases) {
            m = model(overlay = o)
            compose.onNodeWithText(text, substring = true).assertExists()
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :glasses:testDebugUnitTest --tests "com.rokid.context.glasses.ui.*"`
Expected: FAIL — `Unresolved reference: HudRoot`, `HudModel`, `formatCountdown`, `formatClock`, `formatInterval`, `alignment`.

- [ ] **Step 3: Implement `Format.kt`**

```kotlin
package com.rokid.context.glasses.ui

import androidx.compose.ui.Alignment
import com.rokid.context.core.model.HudPosition
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "4:12" until [nextAtMs]; "--:--" when unknown or due. */
fun formatCountdown(nextAtMs: Long?, nowMs: Long): String {
    if (nextAtMs == null || nextAtMs <= nowMs) return "--:--"
    val s = (nextAtMs - nowMs + 999) / 1_000
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

/** HH:mm in [zone], resolved at display time. */
fun formatClock(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(zone).format(Instant.ofEpochMilli(atMs))

fun formatInterval(sec: Int): String = if (sec % 60 == 0) "${sec / 60} min" else "$sec s"

/** 3×3 HUD position (spec §2.4 Glasses settings) → where [com.rokid.context.core.hud.Scaled] anchors the page. */
fun HudPosition.alignment(): Alignment = when (this) {
    HudPosition.TOP_LEFT -> Alignment.TopStart
    HudPosition.TOP_CENTER -> Alignment.TopCenter
    HudPosition.TOP_RIGHT -> Alignment.TopEnd
    HudPosition.CENTER_LEFT -> Alignment.CenterStart
    HudPosition.CENTER -> Alignment.Center
    HudPosition.CENTER_RIGHT -> Alignment.CenterEnd
    HudPosition.BOTTOM_LEFT -> Alignment.BottomStart
    HudPosition.BOTTOM_CENTER -> Alignment.BottomCenter
    HudPosition.BOTTOM_RIGHT -> Alignment.BottomEnd
}
```

- [ ] **Step 4: Implement `Pages.kt`**

```kotlin
package com.rokid.context.glasses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhonelinkOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.context.core.hud.Glyph
import com.rokid.context.core.hud.Hud
import com.rokid.context.core.hud.Label
import com.rokid.context.core.hud.OutlinedBox
import com.rokid.context.core.model.Health
import com.rokid.context.glasses.hud.visibleRows
import com.rokid.context.glasses.link.HudConnection

/** Silent verbosity shows only this (spec §3.4): REC dot, or ❚❚ while paused. */
@Composable
fun RecDot(m: HudModel) {
    if (m.paused) Glyph(Icons.Outlined.Pause, 24.dp, Hud.PRIMARY) else Glyph(Icons.Outlined.FiberManualRecord, 18.dp, Hud.PRIMARY)
}

/** Status row (spec §3.2): REC / ❚❚, phone link (offline per §3.6), battery. */
@Composable
fun StatusRow(m: HudModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RecDot(m)
        Label(if (m.paused) "PAUSED" else "REC", 20.sp, Hud.SECONDARY)
        when (m.connection) {
            HudConnection.LIVE -> Glyph(Icons.Outlined.PhoneAndroid, 22.dp, Hud.SECONDARY)
            HudConnection.CONNECTING -> Glyph(Icons.Outlined.PhoneAndroid, 22.dp, Hud.TERTIARY)
            HudConnection.OFFLINE, HudConnection.OUTDATED -> {
                Glyph(Icons.Outlined.PhonelinkOff, 22.dp, Hud.SECONDARY)
                Label("offline", 20.sp, Hud.SECONDARY)
            }
        }
        val battery = m.batteryPct
        if (battery != null) {
            Glyph(Icons.Outlined.BatteryStd, 22.dp, Hud.TERTIARY)
            Label("$battery%", 20.sp, Hud.SECONDARY)
        }
    }
}

/** Glance (default page): status row, NEXT countdown in an outlined box, latest nudge one-liner. */
@Composable
fun GlancePage(m: HudModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusRow(m)
        OutlinedBox {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Label(if (m.paused) "PAUSED" else "NEXT", 20.sp, Hud.SECONDARY)
                if (m.paused) Glyph(Icons.Outlined.Pause, 56.dp, Hud.PRIMARY)
                else Label(formatCountdown(m.nextCaptureAtMs, m.nowMs), 56.sp, Hud.PRIMARY, FontWeight.Bold)
            }
        }
        val nudge = m.frame?.latestNudge
        if (nudge != null) Label(nudge.text, 22.sp, Hud.SECONDARY, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Today: recent memories, time at 60 %, text at 100 %, outlined highlight on the selected row. */
@Composable
fun TodayPage(m: HudModel) {
    val rows = m.frame?.today.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label("TODAY", 20.sp, Hud.SECONDARY)
        if (rows.isEmpty()) Label("Nothing yet today", 22.sp, Hud.SECONDARY)
        for (i in visibleRows(rows.size, m.nav.todayRow, rows = 4)) {
            val r = rows[i]
            val line: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(formatClock(r.atMs), 20.sp, Hud.SECONDARY)
                    Label("  ${r.text}", 22.sp, Hud.PRIMARY, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (i == m.nav.todayRow) OutlinedBox(highlighted = true) { line() }
            else Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { line() }
        }
    }
}

/** Status (Max only): interval, phone link, AI health, queue depth, last sent — short human text. */
@Composable
fun StatusPage(m: HudModel) {
    val f = m.frame
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label("STATUS", 20.sp, Hud.SECONDARY)
        StatusLine("Capture", "every ${formatInterval(f?.intervalSec ?: m.settings.captureIntervalSec)}")
        StatusLine("Phone", if (m.connection == HudConnection.LIVE) "linked" else "offline")
        StatusLine("AI", when (val h = f?.aiHealth) { null, Health.Ok -> "ok"; is Health.Degraded -> h.text; is Health.Blocked -> h.text })
        StatusLine("Queue", "${f?.queueDepth ?: 0} waiting")
        StatusLine("Last sent", f?.lastSentAtMs?.let { formatClock(it) } ?: "—")
        f?.error?.let { StatusLine("Note", it) }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(label, 20.sp, Hud.SECONDARY)
        Label("  $value", 22.sp, Hud.PRIMARY, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
```

- [ ] **Step 5: Implement `Overlays.kt` and `ConfirmOverlay.kt`**

`Overlays.kt`:
```kotlin
package com.rokid.context.glasses.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.context.core.hud.Glyph
import com.rokid.context.core.hud.Hud
import com.rokid.context.core.hud.HudToast
import com.rokid.context.core.hud.Label
import com.rokid.context.core.hud.OutlinedBox
import com.rokid.context.core.model.Overlay

/** One overlay at full scale in the lower band (spec §3.2–§3.3). */
@Composable
fun OverlayView(o: Overlay, confirmHighlightYes: Boolean) {
    when (o) {
        is Overlay.Listening -> ListeningOverlay(o.partial)
        is Overlay.Answer -> AnswerCard(o)
        is Overlay.Nudge -> NudgeToast(o.text)
        is Overlay.Confirm -> ConfirmOverlay(o.title, o.detail, confirmHighlightYes)
        Overlay.PausedSplash -> PausedSplash()
        is Overlay.Toast -> HudToast(o.text)
    }
}

@Composable
fun ListeningOverlay(partial: String) {
    val ring by rememberInfiniteTransition(label = "listening")
        .animateFloat(Hud.TERTIARY, Hud.PRIMARY, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "ring")
    Column(
        Modifier.background(Color.Black).border(Hud.STROKE_HIGHLIGHT, Hud.Green.copy(alpha = ring), RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Glyph(Icons.Outlined.Mic, 36.dp, Hud.PRIMARY)
        if (partial.isNotBlank()) Label(partial, 24.sp, Hud.PRIMARY, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Label("tap to send · double-tap cancel", 18.sp, Hud.TERTIARY)
    }
}

@Composable
fun AnswerCard(a: Overlay.Answer) {
    Column(Modifier.background(Color.Black).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Label("YOU ASKED", 18.sp, Hud.SECONDARY)
        Label(a.question, 22.sp, Hud.SECONDARY, maxLines = 1, overflow = TextOverflow.Ellipsis)
        OutlinedBox(highlighted = true, radius = 16.dp) { Label(a.text, 26.sp, Hud.PRIMARY, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (a.speaking) Glyph(Icons.AutoMirrored.Outlined.VolumeUp, 20.dp, Hud.TERTIARY)
            Label(if (a.speaking) " speaking · swipe to dismiss" else "swipe to dismiss", 18.sp, Hud.TERTIARY)
        }
    }
}

@Composable
fun NudgeToast(text: String) {
    Column(
        Modifier.background(Color.Black).border(Hud.STROKE, Hud.Green.copy(alpha = Hud.SECONDARY), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.Outlined.NotificationsActive, 22.dp, Hud.SECONDARY)
            Label(" HEADS UP", 18.sp, Hud.SECONDARY)
        }
        Label(text, 24.sp, Hud.PRIMARY, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PausedSplash() {
    Column(
        Modifier.background(Color.Black).border(Hud.STROKE, Hud.Green.copy(alpha = Hud.SECONDARY), RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Glyph(Icons.Outlined.Pause, 48.dp, Hud.PRIMARY)
        Label("Capture paused", 26.sp, Hud.PRIMARY, FontWeight.Bold)
    }
}
```

`ConfirmOverlay.kt`:
```kotlin
package com.rokid.context.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.context.core.hud.Glyph
import com.rokid.context.core.hud.Hud
import com.rokid.context.core.hud.Label

/** Outlined ✓ Yes / ✕ No (Live Fit `ConfirmOverlay`); the highlight is brighter and thicker, never filled. */
@Composable
fun ConfirmOverlay(title: String, detail: String?, highlightYes: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Color.Black).border(2.dp, Hud.Green.copy(alpha = Hud.SECONDARY), RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(title, 28.sp, Hud.PRIMARY, FontWeight.Bold)
        if (!detail.isNullOrBlank()) Label(detail, 22.sp, Hud.SECONDARY)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Choice(Icons.Outlined.Check, "Yes", selected = highlightYes)
            Choice(Icons.Outlined.Close, "No", selected = !highlightYes)
        }
    }
}

@Composable
private fun Choice(icon: ImageVector, label: String, selected: Boolean) {
    val level = if (selected) Hud.PRIMARY else Hud.TERTIARY
    Row(
        Modifier.border(if (selected) 4.dp else 2.dp, Hud.Green.copy(alpha = level), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, 28.dp, level)
        Label(" $label", 26.sp, level, FontWeight.Bold)
    }
}
```

- [ ] **Step 6: Implement `HudRoot.kt`**

```kotlin
package com.rokid.context.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.context.core.hud.Hud
import com.rokid.context.core.hud.Label
import com.rokid.context.core.hud.OutlinedBox
import com.rokid.context.core.hud.Scaled
import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.StateFrame
import com.rokid.context.glasses.hud.HudNav
import com.rokid.context.glasses.hud.HudView
import com.rokid.context.glasses.hud.pagesFor
import com.rokid.context.glasses.link.HudConnection

/** Everything one HUD render needs; built by MainActivity from the controller, presenter and local state. */
data class HudModel(
    val view: HudView,
    val frame: StateFrame?,
    val settings: HudSettings,
    val connection: HudConnection,
    val batteryPct: Int?,
    /** The glasses' own pause set has an active record (glasses authority, spec §1.7). */
    val paused: Boolean,
    /** Local scheduler, glasses clock: the countdown survives link drops. */
    val nextCaptureAtMs: Long?,
    val nowMs: Long,
    val nav: HudNav,
    val confirmHighlightYes: Boolean,
)

/**
 * HUD root (spec §3.2–§3.3): black is transparent on the display; the page is bottom-anchored inside [Scaled] at
 * `HudSettings.scale`/`position`; an overlay draws at full scale in the lower band, keeping the centre clear.
 */
@Composable
fun HudRoot(m: HudModel) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (m.connection == HudConnection.OUTDATED) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
                OutlinedBox(highlighted = true) { Label("Update Proactive Context", 26.sp, Hud.PRIMARY, FontWeight.Bold) }
            }
            return@Box
        }
        Scaled(m.settings.scale, m.settings.position.alignment()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                val page = m.view.page
                if (page == null) {
                    RecDot(m)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (page) {
                            HudPage.GLANCE -> GlancePage(m)
                            HudPage.TODAY -> TodayPage(m)
                            HudPage.STATUS -> StatusPage(m)
                        }
                        PageDots(pagesFor(m.settings.verbosity), page)
                    }
                }
            }
        }
        val overlay = m.view.overlay
        if (overlay != null) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) { OverlayView(overlay, m.confirmHighlightYes) }
    }
}

@Composable
private fun PageDots(pages: List<HudPage>, current: HudPage) {
    if (pages.size < 2) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in pages) {
            val on = p == current
            Box(Modifier.size(if (on) 10.dp else 8.dp).border(2.dp, Hud.Green.copy(alpha = if (on) Hud.PRIMARY else Hud.TERTIARY), CircleShape))
        }
    }
}
```

- [ ] **Step 7: Implement `GlassesRuntime.kt`**

```kotlin
package com.rokid.context.glasses

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.Overlay
import com.rokid.context.glasses.capture.OutboundBuffer
import com.rokid.context.glasses.link.FrameUplink
import com.rokid.context.glasses.link.HudController
import com.rokid.context.glasses.pause.GlassesPauses
import com.rokid.context.glasses.pause.PauseStore
import com.rokid.context.glasses.pause.PauseToggle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

/**
 * Process-wide glasses state shared by [MainActivity] and the capture service: pause set, outbound buffer, uplink and
 * the current [HudController]. A controller exists only while the activity does, i.e. while the CXR session can be
 * open (spec §1.7), so nothing is sent without one.
 */
class GlassesRuntime private constructor(private val app: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val pauses = GlassesPauses(PauseStore(File(app.filesDir, "pauses.preferences_pb"), CoroutineScope(SupervisorJob() + Dispatchers.IO)))
    val buffer = OutboundBuffer(File(app.filesDir, "outbound"), log = { Log.i(UPLINK_TAG, it) })
    /** Push-to-talk recording (set by Phase 6); frames never flush while true (spec §1.8). */
    val listening = MutableStateFlow(false)
    @Volatile private var controller: HudController? = null
    private var attachJob: Job? = null
    val uplink = FrameUplink(scope, buffer, sendChunk = { c -> controller?.sendFrameChunk(c) ?: -1 }, canSend = ::canSend, log = { Log.i(UPLINK_TAG, it) })

    /** Session open, not listening, and the phone is not showing Listening (flush resumes once the answer is up). */
    private fun canSend(): Boolean {
        val c = controller ?: return false
        return c.sessionOpen.value && !listening.value && c.frame.value?.overlay !is Overlay.Listening
    }

    fun attach(c: HudController) {
        controller = c
        c.onFrameAck = uplink::onAck
        // Hello goes out first; give the phone a moment to take the clock offset before frames follow.
        c.onSessionOpened = { scope.launch { delay(FLUSH_AFTER_HELLO_MS); uplink.kick() } }
        c.onPhonePauses = { records -> scope.launch { pauses.mergeFromPhone(records) } }
        attachJob?.cancel()
        attachJob = scope.launch {
            combine(c.frame, listening) { f, l -> l || f?.overlay is Overlay.Listening }
                .distinctUntilChanged()
                .collect { busy -> if (!busy) uplink.kick() }
        }
    }

    fun detach(c: HudController) {
        if (controller !== c) return
        controller = null
        attachJob?.cancel()
    }

    /** `pc_hello`: clock, battery, buffer size, local pauses, next capture (all glasses clock). */
    fun hello(nextCaptureAtMs: Long?): Hello = Hello(
        glassesBatteryPct = batteryPct(),
        bufferSize = buffer.size,
        glassesNowMs = System.currentTimeMillis(),
        pauses = pauses.records.value,
        nextCaptureAtMs = nextCaptureAtMs,
    )

    /** A frame was buffered: report the new buffer size and flush if the session is open. */
    fun onFrameBuffered() {
        controller?.sendHello()
        uplink.kick()
    }

    /** Double-tap pause/resume, applied here at once (spec §5.5); a pause drops the buffered frames. */
    suspend fun togglePause(): PauseToggle {
        val t = pauses.toggle()
        if (t is PauseToggle.Paused) buffer.clear()
        return t
    }

    fun batteryPct(): Int? = app.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    companion object {
        const val TAG = "PcGlasses"
        const val UPLINK_TAG = "PcUplink"
        const val FLUSH_AFTER_HELLO_MS = 500L
        @Volatile private var instance: GlassesRuntime? = null
        fun get(context: Context): GlassesRuntime =
            instance ?: synchronized(this) { instance ?: GlassesRuntime(context.applicationContext).also { instance = it } }
    }
}
```

- [ ] **Step 8: Replace `MainActivity.kt`**

```kotlin
package com.rokid.context.glasses

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.Overlay
import com.rokid.context.glasses.hud.ConfirmInput
import com.rokid.context.glasses.hud.HudNav
import com.rokid.context.glasses.hud.HudPresenter
import com.rokid.context.glasses.hud.InputRouter
import com.rokid.context.glasses.hud.KeyGestures
import com.rokid.context.glasses.hud.KeyResult
import com.rokid.context.glasses.hud.LocalOverlay
import com.rokid.context.glasses.hud.PadInput
import com.rokid.context.glasses.hud.Routed
import com.rokid.context.glasses.hud.pagesFor
import com.rokid.context.glasses.link.CxrBridge
import com.rokid.context.glasses.link.HudConnection
import com.rokid.context.glasses.link.HudController
import com.rokid.context.glasses.link.HudSettingsCache
import com.rokid.context.glasses.pause.PauseToggle
import com.rokid.context.glasses.ui.HudModel
import com.rokid.context.glasses.ui.HudRoot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Glasses HUD (spec §3.1), adapted from Live Fit `MainActivity`: one activity, screen kept on, [HudRoot] drawn from
 * the phone's StateFrame plus local state, touchpad keys routed through [KeyGestures] and [InputRouter]. Its lifetime
 * is the CXR session's: destroying it closes the phone's CUSTOM_APP session (spec §1.7).
 */
class MainActivity : ComponentActivity() {
    private val runtime by lazy { GlassesRuntime.get(this) }
    private lateinit var controller: HudController
    private val keys = KeyGestures()
    private val swipeTimer = Handler(Looper.getMainLooper())
    private val closeSwipe = Runnable { handle(keys.onTimer(SystemClock.uptimeMillis())); scheduleSwipeClose() }
    private val presenter = HudPresenter()
    private val confirm = ConfirmInput()
    private var nav by mutableStateOf(HudNav())
    private var highlightYes by mutableStateOf(true)
    private var local by mutableStateOf<LocalOverlay?>(null)
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ensureCaptureScheduled() else Log.w(TAG, "camera permission denied: capture stays off")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = HudController(
            lifecycleScope, CxrBridge(this), HudSettingsCache(getSharedPreferences(HudSettingsCache.PREFS, MODE_PRIVATE)),
            hello = { runtime.hello(nextCaptureAt()) },
        )
        runtime.attach(controller)
        controller.start()
        lifecycleScope.launch { runtime.pauses.ensureLoaded() }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) ensureCaptureScheduled()
        else cameraPermission.launch(Manifest.permission.CAMERA)
        handleIntent(intent)
        setContent { Hud() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    @Composable
    private fun Hud() {
        val frame by controller.frame.collectAsStateWithLifecycle()
        val settings by controller.settings.collectAsStateWithLifecycle()
        val connection by controller.connection.collectAsStateWithLifecycle()
        val pauses by runtime.pauses.records.collectAsStateWithLifecycle()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var battery by remember { mutableStateOf(runtime.batteryPct()) }
        LaunchedEffect(Unit) {
            var ticks = 0
            while (true) {
                delay(1_000)
                now = System.currentTimeMillis()
                nav = nav.tick(now)
                if (++ticks % 30 == 0) battery = runtime.batteryPct()
            }
        }
        val todayRows = frame?.today?.size ?: 0
        LaunchedEffect(settings.verbosity, todayRows) { nav = nav.reconcile(pagesFor(settings.verbosity), todayRows) }
        val pendingConfirm = frame?.overlay as? Overlay.Confirm
        LaunchedEffect(pendingConfirm?.proposalId) { confirm.onConfirm(pendingConfirm); highlightYes = confirm.highlightYes }
        HudRoot(
            HudModel(
                view = presenter.present(frame, settings.verbosity, nav, local, now), frame = frame, settings = settings,
                connection = connection, batteryPct = battery, paused = pauses.any { it.isActiveAt(now) },
                nextCaptureAtMs = nextCaptureAt(), nowMs = now, nav = nav, confirmHighlightYes = highlightYes,
            ),
        )
    }

    /** Touchpad keys are ours: KEYCODE_NOTIFICATION must never reach the system, or it moves our task to the back. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val r = when (event.action) {
            KeyEvent.ACTION_DOWN -> keys.onKeyDown(event.keyCode, event.repeatCount, event.eventTime, System.currentTimeMillis())
            KeyEvent.ACTION_UP -> keys.onKeyUp(event.keyCode)
            else -> KeyResult(consumed = false)
        }
        if (!r.consumed) return super.dispatchKeyEvent(event)
        handle(r.inputs)
        scheduleSwipeClose()
        return true
    }

    /** BACK is a double-tap on some firmware (Live Fit); never page navigation, never "exit". */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() = handle(keys.onBack(System.currentTimeMillis()))

    private fun scheduleSwipeClose() {
        swipeTimer.removeCallbacks(closeSwipe)
        keys.deadlineMs?.let { swipeTimer.postAtTime(closeSwipe, it) } // KeyEvent.eventTime and postAtTime share the uptime clock
    }

    private fun handle(inputs: List<PadInput>) {
        for (input in inputs) {
            val now = System.currentTimeMillis()
            val shown = presenter.present(controller.frame.value, controller.settings.value.verbosity, nav, local, now).overlay
            confirm.onConfirm(shown as? Overlay.Confirm)
            when (val r = InputRouter.route(input, shown, controller.settings.value.gestures)) {
                Routed.ConfirmToggle -> { confirm.onSwipe(); highlightYes = confirm.highlightYes }
                Routed.ConfirmChoose -> confirm.onTap()?.let { controller.send(it); presenter.dismiss(shown) }
                Routed.ConfirmNo -> confirm.onNo()?.let { controller.send(it); presenter.dismiss(shown) }
                Routed.CancelListening -> controller.send(Command.Cancel)
                Routed.DismissOverlay -> presenter.dismiss(shown)
                is Routed.Mapped -> onAction(r, now)
            }
            nav = nav.touched(now)
        }
    }

    private fun onAction(r: Routed.Mapped, now: Long) {
        val settings = controller.settings.value
        when (r.action) {
            GestureAction.NEXT_PAGE, GestureAction.PREV_PAGE -> {
                val next = nav.onSwipeAction(r.action, r.long, pagesFor(settings.verbosity), controller.frame.value?.today?.size ?: 0, now)
                if (next.page != nav.page) controller.send(Command.PageChanged(next.page))
                nav = next
            }
            // Push-to-talk arrives in Phase 6; until then a tap gives feedback instead of silence (spec §3.6 offline toast).
            GestureAction.ASK -> local = LocalOverlay.Toast(if (controller.connection.value == HudConnection.LIVE) "Ask coming soon" else "Phone not connected", now)
            GestureAction.PAUSE_RESUME -> lifecycleScope.launch { onPauseToggle(runtime.togglePause(), now) }
            GestureAction.DISMISS -> { presenter.dismiss(controller.frame.value?.overlay); local = null }
            GestureAction.NONE -> Unit
        }
    }

    /** Applied on the glasses first (spec §5.5); the phone hears it now, or from `pc_hello` after a reconnect. */
    private fun onPauseToggle(t: PauseToggle, now: Long) {
        when (t) {
            is PauseToggle.Paused -> {
                controller.send(Command.Pause(t.record))
                local = LocalOverlay.Splash(now)
                Log.i(TAG, "paused locally ${t.record.pauseId}")
            }
            is PauseToggle.Resumed -> {
                controller.send(Command.Resume(t.clears))
                local = LocalOverlay.Toast("Capture resumed", now)
                Log.i(TAG, "resumed ${t.clears.size} pause(s)")
            }
        }
    }

    // Capture still runs through the RFCOMM-era PulseKeepAliveService; Task 11 moves these three to CaptureService.
    private fun nextCaptureAt(): Long? = PulseCaptureScheduler(this).nextTriggerAtMs().takeIf { it > 0 }

    private fun ensureCaptureScheduled() {
        val s = PulseCaptureScheduler(this)
        if (s.nextTriggerAtMs() < System.currentTimeMillis()) s.scheduleNext(30_000)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == ACTION_PULSE_CAPTURE || i?.action == ACTION_CAPTURE_NOW) PulseKeepAliveService.startPulse(this)
    }

    override fun onDestroy() {
        swipeTimer.removeCallbacks(closeSwipe)
        controller.onActivityExit()
        runtime.detach(controller)
        super.onDestroy()
    }

    companion object {
        const val TAG = "PcGlasses"
        /** adb and foreground-fallback entry: capture one frame now. */
        const val ACTION_CAPTURE_NOW = "com.rokid.context.glasses.action.CAPTURE_NOW"
        /** Sent by the RFCOMM-era PulseKeepAliveService; removed with it in Task 12. */
        const val ACTION_PULSE_CAPTURE = "com.rokid.context.glasses.action.PULSE_CAPTURE"
        const val EXTRA_SILENCY_MODE = "silency_mode"
    }
}
```

- [ ] **Step 9: Manifest, resources and build file**

Replace `glasses/src/main/AndroidManifest.xml` with:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.CAMERA" />
    <!-- RFCOMM-era permissions, removed with PulseKeepAliveService in Task 12. -->
    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />

    <application
        android:allowBackup="true"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar.Fullscreen">
        <service
            android:name=".PulseKeepAliveService"
            android:exported="false"
            android:foregroundServiceType="camera|dataSync" />
        <receiver
            android:name=".PulseAlarmReceiver"
            android:exported="false" />
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```
In `glasses/src/main/res/values/strings.xml` set `<string name="app_name">Proactive Context</string>`.

Delete the XML UI:
```bash
G=android-companion/glasses/src/main/res
git rm $G/layout/activity_main.xml $G/drawable/pulse_camera_dot.xml $G/drawable/pulse_event_bg.xml $G/drawable/pulse_hud_bg.xml $G/drawable/pulse_silent_indicator_bg.xml $G/values/styles.xml
```
In `glasses/build.gradle.kts` delete the two lines marked `// legacy MainActivity; removed in Task 10` (`androidx.appcompat:appcompat:1.7.0` and `androidx.camera:camera-view:1.3.4`).

- [ ] **Step 10: Run the tests and the gate**

Run: `./gradlew :glasses:testDebugUnitTest`
Expected: PASS — `FormatTest` (4), `HudRootTest` (8) plus Tasks 7–9.

Run: `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: PASS.

- [ ] **Step 11: Device check — CXR session and HUD (adb; first authorization may need the user)**

```bash
ADB=/Users/<user>/Desktop/rokid/android-sdk/platform-tools/adb; PHONE=adb-RZCY902F76K-nmSM2g._adb-tls-connect._tcp; GLASSES=1901092545029120
$ADB -s $GLASSES install -r glasses/build/outputs/apk/debug/glasses-debug.apk
$ADB -s $GLASSES shell pm grant com.rokid.context.glasses android.permission.CAMERA
$ADB -s $PHONE logcat -c; $ADB -s $GLASSES logcat -c
$ADB -s $PHONE shell am start -n com.rokid.context/.MainActivity
sleep 20
$ADB -s $PHONE logcat -d -s PcGlassesLink:I ContextHub:I | tail -20
$ADB -s $GLASSES logcat -d -s PcHud:I PcGlasses:I AndroidRuntime:E | tail -20
$ADB -s $GLASSES exec-out screencap -p > /tmp/pc_hud_glance.png
```
Expected: phone `link=CONNECTED` and `ContextHub: hello battery=… offsetMs=…` (|offset| < 1 000 ms); glasses `PcHud: session open`; no `FATAL EXCEPTION`; the screenshot shows the Glance page (REC, phone glyph, battery, NEXT countdown). If Hi Rokid asks to authorize on the phone, tap Allow **(needs the user)**; if nobody is there, record "authorization pending" and stop the device part of this task.

Pause round trip (adb BACK is the firmware double-tap):
```bash
$ADB -s $GLASSES shell input keyevent 4; sleep 2
$ADB -s $PHONE logcat -d -s ContextHub:I | grep "pause added" | tail -1
$ADB -s $GLASSES shell input keyevent 4; sleep 2
$ADB -s $PHONE logcat -d -s ContextHub:I | grep "resume asked" | tail -1
```
Expected: `pause added … origin=GLASSES`, then `resume asked for 1 pause(s)`.

- [ ] **Step 12: Commit**

```bash
git add -A android-companion/glasses
git commit -m "feat(glasses): Compose HUD pages and overlays on CXR; drop XML UI

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: `CaptureService` on CXR — guardrails, scheduler, end-to-end on device

**Requirements:** R-30 (single capture path in `CaptureService`; guardrails preserved: battery ≤ 10 % not charging, dark frame, pHash dedupe, throttle, JPEG q62 @ 360 px; `CaptureScheduler` on AlarmManager), R-07 (capture timing lives on the glasses and survives link drops; pause checked before every capture, fail-closed), R-08 / R-35 (frames buffer while offline and flush on reconnect), R-03 (existing capture behaviour kept apart from the cadence, which now comes from `HudSettings.captureIntervalSec`).

Ported from this repo: `android-companion/glasses/src/main/java/com/rokid/context/glasses/PulseKeepAliveService.kt` (camera binding, analyzer window, image helpers, guardrail constants, foreground fallback) and `PulseCaptureScheduler.kt` (exact-alarm logic). The RFCOMM sender is not ported. The old files stay compiled but unused until Task 12 deletes them. After this task frames travel only over CXR; the device steps below prove it before anything is deleted.

**Files:**
- Create (in `android-companion/glasses/src/main/java/com/rokid/context/glasses/capture/`): `FrameGuard.kt`, `CaptureCadence.kt`, `CaptureScheduler.kt`, `CaptureService.kt`
- Modify: `android-companion/glasses/src/main/java/com/rokid/context/glasses/MainActivity.kt` (imports + the three capture helpers), `android-companion/glasses/src/main/AndroidManifest.xml` (service entry)
- Test (in `android-companion/glasses/src/test/java/com/rokid/context/glasses/capture/`): `FrameGuardTest.kt`, `CaptureCadenceTest.kt`, `CaptureSchedulerTest.kt`

**Interfaces:**
- Consumes: `GlassesRuntime.pauses/buffer/onFrameBuffered()` (Task 10), `HudSettingsCache` (Task 7), `OutboundBuffer.add` (Task 8), `MainActivity.ACTION_CAPTURE_NOW` (Task 10).
- Produces:
  - `enum class SkipReason { LOW_BATTERY, DARK, DUPLICATE, THROTTLED }`; `object FrameGuard { fun check(batteryPct: Int, charging: Boolean, meanLuma: Double, hash: Long, lastHash: Long?, nowMs: Long, lastKeptAtMs: Long, skipDedupe: Boolean = false): SkipReason?; fun hamming(a: Long, b: Long): Int }`.
  - `object CaptureCadence { fun nextDelayMs(intervalSec: Int, batteryPct: Int, batterySaver: Boolean, failed: Boolean): Long }`.
  - `class CaptureScheduler(context) { fun scheduleNext(delayMs: Long); fun nextTriggerAtMs(): Long?; fun ensureScheduled(); fun cancel() }`.
  - `class CaptureService : LifecycleService` with `ACTION_SCHEDULED`, `ACTION_NOW`, `EXTRA_SKIP_DEDUPE = "skip_dedupe"` (honoured in debug builds only), `fun captureNow(context, skipDedupe = false)`; logs `PcCapture: buffered <id>` / `capture skipped: <reason>`.

- [ ] **Step 1: Write the failing tests**

`FrameGuardTest.kt`:
```kotlin
package com.rokid.context.glasses.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameGuardTest {
    private fun check(
        battery: Int = 80, charging: Boolean = false, luma: Double = 90.0, hash: Long = 0L, last: Long? = null,
        now: Long = 10_000, lastKept: Long = 0, skipDedupe: Boolean = false,
    ) = FrameGuard.check(battery, charging, luma, hash, last, now, lastKept, skipDedupe)

    @Test fun aNormalFrameIsKept() = assertNull(check())

    @Test fun lowBatteryWithoutChargingIsSkipped() {
        assertEquals(SkipReason.LOW_BATTERY, check(battery = 10))
        assertNull(check(battery = 10, charging = true))
        assertNull(check(battery = 11))
    }

    @Test fun darkFramesAreSkipped() {
        assertEquals(SkipReason.DARK, check(luma = 7.9))
        assertNull(check(luma = 8.0))
    }

    @Test fun nearDuplicatesAreSkipped() {
        assertEquals(SkipReason.DUPLICATE, check(hash = 0b1111L, last = 0L)) // 4 bits apart
        assertNull(check(hash = 0b11111L, last = 0L)) // 5 bits apart
    }

    @Test fun oneKeptFramePer2500Ms() {
        assertEquals(SkipReason.THROTTLED, check(now = 12_499, lastKept = 10_000))
        assertNull(check(now = 12_500, lastKept = 10_000))
    }

    /** Debug adb checks may bypass dedupe and throttle, never the battery or dark-frame guards. */
    @Test fun skipDedupeBypassesOnlyDedupeAndThrottle() {
        assertNull(check(hash = 1, last = 1, now = 1, lastKept = 0, skipDedupe = true))
        assertEquals(SkipReason.DARK, check(luma = 1.0, skipDedupe = true))
        assertEquals(SkipReason.LOW_BATTERY, check(battery = 5, skipDedupe = true))
    }
}
```

`CaptureCadenceTest.kt`:
```kotlin
package com.rokid.context.glasses.capture

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureCadenceTest {
    @Test fun intervalComesFromSettings() = assertEquals(300_000L, CaptureCadence.nextDelayMs(300, batteryPct = 80, batterySaver = false, failed = false))

    @Test fun lowBatteryOrBatterySaverDoubles() {
        assertEquals(600_000L, CaptureCadence.nextDelayMs(300, batteryPct = 20, batterySaver = false, failed = false))
        assertEquals(600_000L, CaptureCadence.nextDelayMs(300, batteryPct = 80, batterySaver = true, failed = false))
        assertEquals(300_000L, CaptureCadence.nextDelayMs(300, batteryPct = -1, batterySaver = false, failed = false), "unknown battery")
    }

    @Test fun aFailureRetriesSoon() = assertEquals(15_000L, CaptureCadence.nextDelayMs(300, batteryPct = 80, batterySaver = false, failed = true))

    @Test fun tooShortIntervalsAreRaised() = assertEquals(30_000L, CaptureCadence.nextDelayMs(5, batteryPct = 80, batterySaver = false, failed = false))
}
```

`CaptureSchedulerTest.kt`:
```kotlin
package com.rokid.context.glasses.capture

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CaptureSchedulerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val am get() = ctx.getSystemService(AlarmManager::class.java)
    private fun alarmIntents() = shadowOf(am).scheduledAlarms.map { shadowOf(it.operation).savedIntent }

    @Test fun ensureScheduledArmsOneAlarmForCaptureService() {
        CaptureScheduler(ctx).ensureScheduled()
        assertEquals(CaptureService::class.java.name, alarmIntents().single().component!!.className)
        assertNotNull(CaptureScheduler(ctx).nextTriggerAtMs())
    }

    @Test fun ensureScheduledKeepsAPendingAlarm() {
        val s = CaptureScheduler(ctx)
        s.scheduleNext(60_000)
        val at = s.nextTriggerAtMs()
        s.ensureScheduled()
        assertEquals(at, s.nextTriggerAtMs())
        assertEquals(1, alarmIntents().size)
    }

    /** The pre-CXR app armed PulseKeepAliveService; that chain must not keep running beside CaptureService. */
    @Test fun theLegacyPulseAlarmIsRetired() {
        val legacy = Intent().setClassName(ctx, "com.rokid.context.glasses.PulseKeepAliveService").setAction("com.rokid.context.glasses.action.PULSE_SERVICE")
        am.set(AlarmManager.RTC_WAKEUP, 1_000_000, PendingIntent.getForegroundService(ctx, 30_071, legacy, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        CaptureScheduler(ctx).ensureScheduled()
        assertTrue(alarmIntents().none { it.action == "com.rokid.context.glasses.action.PULSE_SERVICE" })
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :glasses:testDebugUnitTest --tests "com.rokid.context.glasses.capture.*"`
Expected: FAIL — `Unresolved reference: FrameGuard`, `SkipReason`, `CaptureCadence`, `CaptureScheduler`, `CaptureService` (Task 8's `OutboundBufferTest` in the same package still compiles).

- [ ] **Step 3: Implement `FrameGuard.kt` and `CaptureCadence.kt`**

`FrameGuard.kt`:
```kotlin
package com.rokid.context.glasses.capture

/** Why a captured frame is not kept. */
enum class SkipReason { LOW_BATTERY, DARK, DUPLICATE, THROTTLED }

/**
 * Capture guardrails (spec §1.3, §3.1 `FrameGuard`), values unchanged from PulseKeepAliveService: battery ≤ 10 % and
 * not charging, mean luma < 8, a 64-bit average hash within Hamming 4 of the last kept frame, at most one kept frame
 * per 2.5 s. [skipDedupe] (debug adb checks) bypasses only the last two.
 */
object FrameGuard {
    const val LOW_BATTERY_PCT = 10
    const val DARK_MEAN_LUMA = 8.0
    const val DEDUP_HAMMING = 4
    const val MIN_KEEP_INTERVAL_MS = 2_500L

    fun check(
        batteryPct: Int, charging: Boolean, meanLuma: Double, hash: Long, lastHash: Long?,
        nowMs: Long, lastKeptAtMs: Long, skipDedupe: Boolean = false,
    ): SkipReason? = when {
        batteryPct in 0..LOW_BATTERY_PCT && !charging -> SkipReason.LOW_BATTERY
        meanLuma < DARK_MEAN_LUMA -> SkipReason.DARK
        !skipDedupe && lastHash != null && hamming(lastHash, hash) <= DEDUP_HAMMING -> SkipReason.DUPLICATE
        !skipDedupe && nowMs - lastKeptAtMs < MIN_KEEP_INTERVAL_MS -> SkipReason.THROTTLED
        else -> null
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
```

`CaptureCadence.kt`:
```kotlin
package com.rokid.context.glasses.capture

/**
 * Delay to the next capture (spec §3.1 `CaptureScheduler`): `HudSettings.captureIntervalSec` (default 300 s, at least
 * 30 s), doubled on battery ≤ 20 % or battery saver (the old 30 s → 60 s rule), 15 s after a failed attempt.
 */
object CaptureCadence {
    const val RETRY_MS = 15_000L
    const val MIN_INTERVAL_MS = 30_000L

    fun nextDelayMs(intervalSec: Int, batteryPct: Int, batterySaver: Boolean, failed: Boolean): Long {
        val base = (intervalSec * 1_000L).coerceAtLeast(MIN_INTERVAL_MS)
        if (failed) return minOf(RETRY_MS, base)
        return if (batterySaver || batteryPct in 0..20) base * 2 else base
    }
}
```

- [ ] **Step 4: Implement `CaptureScheduler.kt`**

```kotlin
package com.rokid.context.glasses.capture

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Exact-ish AlarmManager chain that wakes [CaptureService] (logic from PulseCaptureScheduler). */
class CaptureScheduler(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val alarms: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    fun scheduleNext(delayMs: Long) {
        val at = System.currentTimeMillis() + delayMs.coerceAtLeast(1_000L)
        val am = alarms ?: return
        val pi = captureIntent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
        prefs.edit().putLong(KEY_NEXT, at).apply()
    }

    /** Glasses clock; drives the Glance countdown and `Hello.nextCaptureAtMs`. */
    fun nextTriggerAtMs(): Long? = prefs.getLong(KEY_NEXT, -1L).takeIf { it > 0 }

    /** Arms the chain when nothing is pending (app start, camera grant) and retires the RFCOMM-era pulse alarm. */
    fun ensureScheduled() {
        cancelLegacyPulse()
        val next = nextTriggerAtMs()
        if (next == null || next < System.currentTimeMillis() - STALE_GRACE_MS) scheduleNext(FIRST_DELAY_MS)
    }

    fun cancel() {
        alarms?.cancel(captureIntent())
        prefs.edit().remove(KEY_NEXT).apply()
    }

    private fun captureIntent(): PendingIntent = PendingIntent.getForegroundService(
        context, REQUEST_CODE, Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_SCHEDULED),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Named by string so this keeps working after Task 12 deletes the old service class. */
    private fun cancelLegacyPulse() {
        val legacy = Intent().setClassName(context, LEGACY_SERVICE).setAction(LEGACY_ACTION)
        val pi = PendingIntent.getForegroundService(context, LEGACY_REQUEST_CODE, legacy, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        alarms?.cancel(pi)
        pi.cancel()
    }

    companion object {
        private const val PREFS = "capture_scheduler"
        private const val KEY_NEXT = "next_trigger_at_ms"
        private const val REQUEST_CODE = 30_101
        private const val LEGACY_REQUEST_CODE = 30_071
        private const val LEGACY_SERVICE = "com.rokid.context.glasses.PulseKeepAliveService"
        private const val LEGACY_ACTION = "com.rokid.context.glasses.action.PULSE_SERVICE"
        const val FIRST_DELAY_MS = 30_000L
        private const val STALE_GRACE_MS = 5_000L
    }
}
```

- [ ] **Step 5: Create `CaptureService.kt`**

```kotlin
package com.rokid.context.glasses.capture

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.rokid.context.glasses.BuildConfig
import com.rokid.context.glasses.GlassesRuntime
import com.rokid.context.glasses.MainActivity
import com.rokid.context.glasses.link.HudSettingsCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The only capture path (spec §3.1): a foreground camera service woken by [CaptureScheduler] (or [captureNow]). It
 * checks the local pause set first (fail-closed), takes one frame, applies [FrameGuard], encodes JPEG q62 @ 360 px and
 * buffers it; [GlassesRuntime.onFrameBuffered] then flushes when the session is open. Ported from PulseKeepAliveService
 * without its RFCOMM sender. While paused the alarm keeps ticking but nothing is captured, so a timed pause ends on time.
 */
class CaptureService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scheduler by lazy { CaptureScheduler(this) }
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val runtime by lazy { GlassesRuntime.get(this) }
    private val inFlight = AtomicBoolean(false)

    private enum class Outcome { BUFFERED, SKIPPED, FAILED }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (action != ACTION_SCHEDULED && action != ACTION_NOW) { stopSelf(startId); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, notification())
        if (!inFlight.compareAndSet(false, true)) return START_NOT_STICKY // the running capture stops the service
        val skipDedupe = BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_SKIP_DEDUPE, false) == true
        scope.launch {
            var failed = false
            try {
                runtime.pauses.ensureLoaded()
                failed = captureOnce(skipDedupe) == Outcome.FAILED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                Log.w(TAG, "capture failed: ${e.javaClass.simpleName}")
            } finally {
                val s = HudSettingsCache(getSharedPreferences(HudSettingsCache.PREFS, Context.MODE_PRIVATE)).read()
                scheduler.scheduleNext(CaptureCadence.nextDelayMs(s.captureIntervalSec, batteryPercent(), isBatterySaverOn(), failed))
                inFlight.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun captureOnce(skipDedupe: Boolean): Outcome {
        if (!runtime.pauses.captureAllowed()) { Log.i(TAG, "capture skipped: paused"); return Outcome.SKIPPED }
        if (isBatterySaverOn()) { Log.i(TAG, "capture skipped: battery saver"); return Outcome.SKIPPED }
        val provider = awaitCameraProvider() ?: return Outcome.FAILED
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val texture = SurfaceTexture(0)
        val preview = Preview.Builder().build().also { p ->
            p.setSurfaceProvider { request ->
                texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                val surface = Surface(texture)
                request.provideSurface(surface, cameraExecutor) { runCatching { surface.release() }; runCatching { texture.release() } }
            }
        }
        val result = CompletableDeferred<Outcome>()
        val analyzed = AtomicInteger(0)
        var lastAnalyzeAtMs = 0L
        analysis.setAnalyzer(cameraExecutor) { image ->
            try {
                if (result.isCompleted) return@setAnalyzer
                analyzed.incrementAndGet()
                val now = System.currentTimeMillis()
                if (now - lastAnalyzeAtMs < ANALYZE_INTERVAL_MS) return@setAnalyzer
                lastAnalyzeAtMs = now
                evaluate(image, now, skipDedupe)?.let { result.complete(it) }
            } catch (e: Exception) {
                Log.w(TAG, "analyze failed: ${e.javaClass.simpleName}")
                result.complete(Outcome.FAILED)
            } finally {
                image.close()
            }
        }
        provider.unbindAll()
        if (runCatching { provider.bindToLifecycle(this, resolveCameraSelector(provider), analysis, preview) }.isFailure) {
            provider.unbindAll()
            Log.w(TAG, "camera bind failed")
            return Outcome.FAILED
        }
        val outcome = withTimeoutOrNull(WINDOW_MS) { result.await() } ?: run {
            if (analyzed.get() == 0 || isBackgroundRestricted()) launchForegroundFallback()
            Outcome.FAILED
        }
        provider.unbindAll()
        runCatching { texture.release() }
        return outcome
    }

    /** Null = this camera frame is unusable; the analyzer tries the next one. */
    private fun evaluate(image: ImageProxy, nowMs: Long, skipDedupe: Boolean): Outcome? {
        if (image.yPlaneToPackedByteArray().isEmpty()) return null
        val raw = yuv420ToJpeg(image, JPEG_QUALITY_CAPTURE).takeIf { it.isNotEmpty() } ?: return null
        val prepared = prepareFrameForSend(jpegRaw = raw, quality = JPEG_QUALITY, maxWidth = MAX_SEND_WIDTH, rotationDegrees = image.imageInfo.rotationDegrees) ?: return null
        val lastHash = if (prefs.contains(KEY_LAST_HASH)) prefs.getLong(KEY_LAST_HASH, 0L) else null
        val battery = batteryPercent()
        val skip = FrameGuard.check(battery, isCharging(), prepared.meanLuma, prepared.hash, lastHash, nowMs, prefs.getLong(KEY_LAST_KEPT_AT, 0L), skipDedupe)
        if (skip != null) { Log.i(TAG, "capture skipped: $skip"); return Outcome.SKIPPED }
        val frameId = "g${nowMs}_${Random.nextInt(0x1000000).toString(16).padStart(6, '0')}"
        val stored = runtime.buffer.add(frameId, prepared.jpeg, nowMs, prepared.meanLuma.roundToInt(), java.lang.Long.toHexString(prepared.hash), battery.takeIf { it in 0..100 })
        if (!stored) return Outcome.FAILED
        prefs.edit().putLong(KEY_LAST_HASH, prepared.hash).putLong(KEY_LAST_KEPT_AT, nowMs).apply()
        Log.i(TAG, "buffered $frameId (${prepared.jpeg.size / 1024} KB) buffer=${runtime.buffer.size}")
        runtime.onFrameBuffered()
        return Outcome.BUFFERED
    }

    /** Background camera restriction: bring the HUD forward, where capture works (PulseKeepAliveService behaviour), at most every 20 s. */
    private fun launchForegroundFallback() {
        val now = System.currentTimeMillis()
        if (now - lastFallbackAtMs < FALLBACK_COOLDOWN_MS) return
        lastFallbackAtMs = now
        Log.w(TAG, "camera restricted in background; opening the HUD to capture")
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_CAPTURE_NOW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.presence_video_online)
        .setContentTitle("Proactive Context")
        .setContentText("Capturing a frame")
        .setOngoing(true)
        .build()

    // ---- image helpers copied from PulseKeepAliveService.kt (Step 6) ----

    companion object {
        const val TAG = "PcCapture"
        const val ACTION_SCHEDULED = "com.rokid.context.glasses.action.CAPTURE_SCHEDULED"
        const val ACTION_NOW = "com.rokid.context.glasses.action.CAPTURE_SERVICE_NOW"
        /** Debug builds only: ignore dedupe and throttle so adb checks can capture a static scene. */
        const val EXTRA_SKIP_DEDUPE = "skip_dedupe"
        private const val CHANNEL_ID = "pc_capture"
        private const val NOTIFICATION_ID = 30_102
        private const val PREFS = "capture_guard"
        private const val KEY_LAST_HASH = "last_hash"
        private const val KEY_LAST_KEPT_AT = "last_kept_at"
        // Unchanged from PulseKeepAliveService (spec §1.3: JPEG q62 @ 360 px).
        private const val WINDOW_MS = 8_000L
        private const val ANALYZE_INTERVAL_MS = 1_000L
        private const val JPEG_QUALITY_CAPTURE = 72
        private const val JPEG_QUALITY = 62
        private const val MAX_SEND_WIDTH = 360
        private const val FALLBACK_COOLDOWN_MS = 20_000L
        @Volatile private var lastFallbackAtMs = 0L

        fun captureNow(context: Context, skipDedupe: Boolean = false) {
            context.startForegroundService(Intent(context, CaptureService::class.java).setAction(ACTION_NOW).putExtra(EXTRA_SKIP_DEDUPE, skipDedupe))
        }
    }
}
```

- [ ] **Step 6: Copy the image helpers unchanged**

`PulseKeepAliveService.kt` is unchanged since commit `f67fc91`, so these line numbers are exact: 348–570 are `isBackgroundRestricted`, `awaitCameraProvider`, `resolveCameraSelector`, `batteryPercent`, `isBatterySaverOn`, `isCharging`, `yuv420ToJpeg`, `yuv420ToNv21`, `ImageProxy.yPlaneToPackedByteArray`, `prepareFrameForSend`, `sampledLumaMean`, `frameHash`; 644–648 are `PreparedFrame`. They are self-contained private members (no fields, no constants), so they compile as is inside `CaptureService`.
```bash
G=android-companion/glasses/src/main/java/com/rokid/context/glasses
sed -n '348,570p;644,648p' $G/PulseKeepAliveService.kt > "$TMPDIR/pc_capture_helpers.kt"
head -1 "$TMPDIR/pc_capture_helpers.kt"; tail -1 "$TMPDIR/pc_capture_helpers.kt"
sed -i '' "/image helpers copied from PulseKeepAliveService/r $TMPDIR/pc_capture_helpers.kt" $G/capture/CaptureService.kt
grep -c "private fun yuv420ToJpeg\|private data class PreparedFrame" $G/capture/CaptureService.kt
```
Expected: first line `    private fun isBackgroundRestricted(): Boolean {`, last line `    )`, final count `2`.

- [ ] **Step 7: Switch the activity and manifest to `CaptureService`**

In `MainActivity.kt` add the imports
```kotlin
import com.rokid.context.glasses.capture.CaptureScheduler
import com.rokid.context.glasses.capture.CaptureService
```
and replace the three helpers (from the `// Capture still runs through …` comment to the end of `handleIntent`) with:
```kotlin
    // CaptureService is the only capture path (spec §3.1); its scheduler drives the Glance countdown.
    private fun nextCaptureAt(): Long? = CaptureScheduler(this).nextTriggerAtMs()

    private fun ensureCaptureScheduled() = CaptureScheduler(this).ensureScheduled()

    private fun handleIntent(i: Intent?) {
        if (i == null || (i.action != ACTION_CAPTURE_NOW && i.action != ACTION_PULSE_CAPTURE)) return
        CaptureService.captureNow(this, skipDedupe = i.getBooleanExtra(CaptureService.EXTRA_SKIP_DEDUPE, false))
    }
```
In `AndroidManifest.xml`, add after the `.PulseAlarmReceiver` entry:
```xml
        <service
            android:name=".capture.CaptureService"
            android:exported="false"
            android:foregroundServiceType="camera" />
```

- [ ] **Step 8: Run the tests and the gate**

Run: `./gradlew :glasses:testDebugUnitTest`
Expected: PASS — `FrameGuardTest` (6), `CaptureCadenceTest` (4), `CaptureSchedulerTest` (3) plus Tasks 7–10.

Run: `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: PASS.

- [ ] **Step 9: Device — frames end to end over CXR (adb)**

```bash
ADB=/Users/<user>/Desktop/rokid/android-sdk/platform-tools/adb; PHONE=adb-RZCY902F76K-nmSM2g._adb-tls-connect._tcp; GLASSES=1901092545029120
$ADB -s $GLASSES install -r glasses/build/outputs/apk/debug/glasses-debug.apk
$ADB -s $PHONE shell am start -n com.rokid.context/.MainActivity
$ADB -s $GLASSES shell am start -n com.rokid.context.glasses/.MainActivity; sleep 15
$ADB -s $PHONE logcat -c; $ADB -s $GLASSES logcat -c
$ADB -s $GLASSES shell am start -n com.rokid.context.glasses/.MainActivity -a com.rokid.context.glasses.action.CAPTURE_NOW --ez skip_dedupe true
sleep 15
$ADB -s $GLASSES logcat -d -s PcCapture:I PcUplink:I | tail -10
$ADB -s $PHONE logcat -d -s ContextHub:I RokidFrameInbox:I QueueDrainWorker:I | tail -15
```
Expected: glasses `PcCapture: buffered g…` then `PcUplink: delivered g…` and `flush done sent=1 dropped=0 retained=0`; phone `ContextHub: frame ingested g…` (same id) and `RokidFrameInbox: ingestFile … source=rokid_glasses`. A `capture skipped: DARK` means the camera saw nothing; point the glasses at a lit scene **(needs the user)** and repeat.

- [ ] **Step 10: Device — offline buffering and flush after a BT drop (adb)**

```bash
$ADB -s $PHONE logcat -c; $ADB -s $GLASSES logcat -c
$ADB -s $PHONE shell svc bluetooth disable; sleep 3
for i in 1 2 3; do $ADB -s $GLASSES shell am start -n com.rokid.context.glasses/.MainActivity -a com.rokid.context.glasses.action.CAPTURE_NOW --ez skip_dedupe true; sleep 12; done
$ADB -s $GLASSES logcat -d -s PcCapture:I | grep -c buffered
$ADB -s $PHONE shell svc bluetooth enable; sleep 30
$ADB -s $GLASSES logcat -d -s PcUplink:I | tail -6
$ADB -s $PHONE logcat -d -s ContextHub:I | grep "frame ingested" | sort | uniq -d | wc -l
$ADB -s $PHONE logcat -d -s ContextHub:I | grep -c "frame ingested"
```
Expected: `3` buffered while BT was off; after BT returns (CXR resumes the session by itself, ~2 s in phase 0) the uplink logs `delivered` three times and `retained=0`; zero duplicate ids; `3` ingested.

- [ ] **Step 11: Device — closed on glasses is not reopened by the phone (adb)**

```bash
$ADB -s $PHONE logcat -c
$ADB -s $GLASSES shell am force-stop com.rokid.context.glasses; sleep 40
$ADB -s $PHONE logcat -d -s PcGlassesLink:I | tail -8
$ADB -s $GLASSES shell dumpsys activity activities | grep -c "com.rokid.context.glasses/.MainActivity"
$ADB -s $GLASSES shell am start -n com.rokid.context.glasses/.MainActivity; sleep 15
$ADB -s $PHONE logcat -d -s PcGlassesLink:I | tail -5
```
Expected: `session closed GLASSES_EXIT` and `link=CLOSED_ON_GLASSES`, no `link=CONNECTING` during the 40 s, the glasses activity not running (count `0`); after the manual start `watcher: glasses app resume ours=true` then `link=CONNECTED`. If the phone logs a different close reason followed by retries, record the reason in the task report: it decides how Phase 8's checklist exits the glasses app.

- [ ] **Step 12: Commit**

```bash
git add -A android-companion/glasses
git commit -m "feat(glasses): CaptureService is the only capture path, frames flow over CXR

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Remove RFCOMM (R-04) and re-verify on device

**Requirements:** R-04 (raw RFCOMM/SPP removed per §1.4: glasses `RokidBluetoothProtocol.kt`, `bridge/RokidBridgeService.kt`, `bridge/RokidConfigPushClient.kt`, glasses `CompanionConfigServer.kt`, plus the RFCOMM pulse sender and its stores), R-07 (the legacy `MainActivity` starts the hub, not the bridge — contract: "remove the RokidBridgeService start/stop calls minimally"), R-73 (a guard test keeps RFCOMM out).

Do this only after Task 11 Steps 9–10 passed on device (frames arrive over CXR). If they could not run (nobody to authorize Hi Rokid), stop here and report; do not delete the working path blind.

**Files:**
- Delete: `android-companion/phone/src/main/java/com/rokid/context/bridge/RokidBridgeService.kt`, `.../bridge/RokidConfigPushClient.kt`
- Delete (in `android-companion/glasses/src/main/java/com/rokid/context/glasses/`): `RokidBluetoothProtocol.kt`, `CompanionConfigServer.kt`, `PulseKeepAliveService.kt`, `PulseCaptureScheduler.kt`, `PulseAlarmReceiver.kt`, `PulseHudEventStore.kt`, `GlassesSettingsStore.kt`
- Modify: `android-companion/phone/src/main/java/com/rokid/context/MainActivity.kt` (bridge → hub, minimal), `android-companion/phone/src/main/AndroidManifest.xml`, `android-companion/glasses/src/main/AndroidManifest.xml`, `android-companion/glasses/src/main/java/com/rokid/context/glasses/MainActivity.kt` (legacy constants)
- Test: `android-companion/phone/src/test/java/com/rokid/context/RfcommGoneTest.kt`

**Interfaces:**
- Consumes: `ContextHubService.ensureRunning/stop/isRunning/statusLine`, `HubGraph.get(context).updateSettings`, `HubSettingsStore.verbosityFor` (Task 6).
- Produces: nothing new; the legacy Config screen's glasses-verbosity choice now goes to `HudSettings.verbosity` via `pc_settings`.

- [ ] **Step 1: Write the failing guard test**

`phone/src/test/java/com/rokid/context/RfcommGoneTest.kt`:
```kotlin
package com.rokid.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** R-04: no raw RFCOMM/SPP code is left in either app (spec §1.4); frames travel only over CXR. */
class RfcommGoneTest {
    private val roots = listOf(File("src/main"), File("../glasses/src/main"))
    private val banned = listOf(
        "BluetoothSocket", "BluetoothServerSocket", "createRfcommSocket", "createInsecureRfcommSocket",
        "listenUsingRfcomm", "listenUsingInsecureRfcomm", "RokidBridgeService", "RokidConfigPushClient",
        "CompanionConfigServer", "RokidBluetoothProtocol",
    )

    @Test fun bothSourceTreesAreScanned() = roots.forEach { assertTrue(it.path, it.isDirectory) }

    @Test fun noRfcommCodeRemains() {
        val hits = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }.toList() }
            .flatMap { f -> banned.filter { it in f.readText() }.map { "${f.path}: $it" } }
        assertEquals(emptyList<String>(), hits)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :phone:testDebugUnitTest --tests "*RfcommGoneTest"`
Expected: FAIL — `noRfcommCodeRemains` lists the bridge files, the phone `MainActivity.kt`, the phone manifest, and the glasses `CompanionConfigServer.kt`, `RokidBluetoothProtocol.kt`, `PulseKeepAliveService.kt`.

- [ ] **Step 3: Point the legacy phone `MainActivity` at the hub**

```bash
F=android-companion/phone/src/main/java/com/rokid/context/MainActivity.kt
perl -pi -e 's/^import com\.rokid\.context\.bridge\.RokidBridgeService$/import com.rokid.context.phone.hub.ContextHubService\nimport com.rokid.context.phone.hub.HubGraph\nimport com.rokid.context.phone.hub.HubSettingsStore/; s/RokidBridgeService\.isListening\(context\)/ContextHubService.isRunning()/g; s/RokidBridgeService\.lastMessage\(context\)/ContextHubService.statusLine()/g; s/RokidBridgeService\.start\(context\)/ContextHubService.ensureRunning(context)/g; s/context\.stopService\(Intent\(context, RokidBridgeService::class\.java\)\)/ContextHubService.stop(context)/g' "$F"
grep -n "RokidBridgeService" "$F"
```
Expected: two remaining lines, both inside `onSelectGlassesSilencyMode` (`queueSilencyModeSync`, `pushSilencyModeNow`).

Then replace this block in the same file:
```kotlin
                        RokidBridgeService.queueSilencyModeSync(context, mode)
                        scope.launch {
                            val result = RokidBridgeService.pushSilencyModeNow(context, mode)
                            val label = when (mode.trim().lowercase()) {
                                "stealth", "silent" -> "SILENT"
                                "compact", "max" -> "MAX"
                                else -> "MEDIUM"
                            }
                            lastAction = if (result.ok) {
                                "Glasses verbosity synced: $label"
                            } else {
                                "Glasses verbosity queued ($label): ${result.message}"
                            }
                        }
```
with:
```kotlin
                        val verbosity = HubSettingsStore.verbosityFor(mode)
                        HubGraph.get(context).updateSettings { it.copy(verbosity = verbosity) }
                        lastAction = "Glasses verbosity: ${verbosity.name} (applied when linked)"
```
Check: `grep -c "RokidBridgeService\|com.rokid.context.bridge" "$F"` → Expected: `0`.

- [ ] **Step 4: Delete the RFCOMM code and manifest entries**

```bash
P=android-companion/phone/src/main/java/com/rokid/context
G=android-companion/glasses/src/main/java/com/rokid/context/glasses
git rm $P/bridge/RokidBridgeService.kt $P/bridge/RokidConfigPushClient.kt
git rm $G/RokidBluetoothProtocol.kt $G/CompanionConfigServer.kt $G/PulseKeepAliveService.kt $G/PulseCaptureScheduler.kt $G/PulseAlarmReceiver.kt $G/PulseHudEventStore.kt $G/GlassesSettingsStore.kt
```
Phone manifest: delete the `<service android:name=".bridge.RokidBridgeService" … />` element and the `BLUETOOTH_ADMIN` permission, and change the `BLUETOOTH` permission to
```xml
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
```
Glasses manifest: delete the comment `<!-- RFCOMM-era permissions … -->`, the three `BLUETOOTH*` permissions, and the `.PulseKeepAliveService` service and `.PulseAlarmReceiver` receiver elements (keep `.capture.CaptureService`).

Glasses `MainActivity.kt`: change `handleIntent` to
```kotlin
    private fun handleIntent(i: Intent?) {
        if (i == null || i.action != ACTION_CAPTURE_NOW) return
        CaptureService.captureNow(this, skipDedupe = i.getBooleanExtra(CaptureService.EXTRA_SKIP_DEDUPE, false))
    }
```
and delete the companion lines for `ACTION_PULSE_CAPTURE` (with its KDoc) and `EXTRA_SILENCY_MODE`.

- [ ] **Step 5: Run the guard test and the full gate**

Run: `./gradlew :phone:testDebugUnitTest --tests "*RfcommGoneTest"`
Expected: PASS (2 tests).

Run: `./gradlew test :phone:assembleDebug :glasses:assembleDebug`
Expected: BUILD SUCCESSFUL; more than the starting 364 tests, 0 failures.

- [ ] **Step 6: Device re-verification (adb)**

```bash
ADB=/Users/<user>/Desktop/rokid/android-sdk/platform-tools/adb; PHONE=adb-RZCY902F76K-nmSM2g._adb-tls-connect._tcp; GLASSES=1901092545029120
$ADB -s $PHONE install -r phone/build/outputs/apk/debug/phone-debug.apk
$ADB -s $GLASSES install -r glasses/build/outputs/apk/debug/glasses-debug.apk
$ADB -s $PHONE shell am force-stop com.rokid.context
$ADB -s $PHONE shell am start -n com.rokid.context/.MainActivity
$ADB -s $GLASSES shell am start -n com.rokid.context.glasses/.MainActivity; sleep 20
$ADB -s $PHONE shell dumpsys activity services com.rokid.context | grep -c "RokidBridgeService"
$ADB -s $PHONE shell dumpsys activity services com.rokid.context | grep -c "ContextHubService"
$ADB -s $PHONE logcat -c
$ADB -s $GLASSES shell am start -n com.rokid.context.glasses/.MainActivity -a com.rokid.context.glasses.action.CAPTURE_NOW --ez skip_dedupe true; sleep 15
$ADB -s $PHONE logcat -d -s ContextHub:I AndroidRuntime:E | tail -10
```
Expected: `0` bridge services, ≥ `1` hub service, a `ContextHub: frame ingested g…` line, no `FATAL EXCEPTION`.

- [ ] **Step 7: Commit**

```bash
git add -A android-companion
git commit -m "refactor: remove RFCOMM bridge and relay; frames travel only over CXR

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

Phase 3 done when: all tests green, both APKs build, frames verified end to end over CXR on device (Task 11 Steps 9–11, Task 12 Step 6), no RFCOMM code left. Requirements covered: R-01 (`:services:glasses-link`), R-02 (`CxrGlassesLink`, `AuthActivity`/`AuthGate`, `GlassAppWatcher`, `HudController`, `HudNav`, `ConfirmOverlay`/`ConfirmInput`, `StateBroadcaster`, `LivenessMonitor`, with tests), R-04, R-05 (wiring + mismatch UX on both sides), R-06, R-07 (hub service; glasses-side capture timing; pause authority exception — the legacy `MainActivity` polling loop goes in Phase 4), R-08, R-30–R-34, R-35 (offline part; TTS is Phase 6), R-65 (part, see below), R-72 (`UserFacingError` introduced and used for link, storage and queue notes).

**Handed to later phases:** Phase 4 — swap its `FakeGlassesStatusSource` for `HubGraph.glassesStatus(context)`; its rewritten `MainActivity` must request `BLUETOOTH_CONNECT` (the hub cannot start on API 31+ without it; until Phase 7's onboarding, the legacy screen's launcher does this), and Phase 4's own `UserFacingError` use should take the Task 1 types. Phase 6 — set `GlassesRuntime.listening` from PushToTalk and replace the "Ask coming soon" toast; handle `GlassesEvent.Audio`/`ListenEnd`. Phase 7 — pause triggers on the phone (Home/Activity ⏸, notification action), durations, quiet hours on the glasses, the "Still paused on glasses — Resume?" prompt, phone → glasses resume of phone-origin records, and `Overlay.Confirm` producers; the stores, union, intervals and flush filter already exist (Tasks 4–7).

## Self-review (writing-plans checklist)

**1. Spec coverage**

| Spec item | Where |
|---|---|
| §1.1 `:services:glasses-link` | Task 3 |
| §1.2 Live Fit copies (CxrGlassesLink, AuthActivity/AuthGate, GlassAppWatcher, HudController, ConfirmOverlay, HudNav, SwipeClassifier/DoubleTap from `:core:hud`) | Tasks 3, 7, 9, 10 |
| §1.3 glasses guardrails ported | Task 11 (`FrameGuard`, helpers copied) |
| §1.4 RFCOMM, XML glasses layout removed | Tasks 10, 12 |
| §1.5 channels, `pc_frame` two Caps strings, `pc_frame_ack`, `pc_hello` clock + pauses, version on every payload, mismatch UX both sides | Tasks 1, 3, 6, 7, 10 |
| §1.6 full StateFrame ≤ 4 Hz | Task 5 (`StateBroadcaster`), Task 6 |
| §1.7 hub FGS, glasses capture timing, 200-frame persisted buffer, send only while session open, rc=0 ≠ delivered, no auto-reconnect on GLASSES_EXIT, watcher reconnect, BT pause/resume | Tasks 3, 6, 7, 8, 11 |
| §1.7 pause authority exception | Tasks 5, 6, 7, 10 |
| §1.8 chunking 32 768, CRC, frame-level ack, 10 s partial discard, NACK once then drop, 5 s / 3 / 2 s, restart from chunk 0, dedupe, PTT priority | Tasks 1, 4, 8, 10 (`canSend`) |
| §3.1 units (MainActivity, HudController, CaptureService + CaptureScheduler + FrameGuard + OutboundBuffer, InputRouter, HudNav, ui/) | Tasks 7–11 (`PushToTalk` is Phase 6 by the master plan) |
| §3.2 pages, overlays, timings, page dots | Tasks 9, 10 |
| §3.3 rendering rules | Task 10 (via `:core:hud`) |
| §3.4 verbosity | Task 9 (`HudPresenter`, `pagesFor`), Task 10 |
| §3.5 gestures + remapping | Task 9 (`InputRouter`), Task 10 |
| §3.6 offline + no raw errors (TTS → Phase 6) | Tasks 1, 5 (`AiHealthRule`), 10 |
| §5.5 glasses-local enforcement, persistence, union, Resume names only known ids, timed expiry with offset, retained intervals 7 d, 72 h buffer cap, flush filter, fail-closed before offset, pause drops buffer | Tasks 4, 5, 6, 7, 8, 10 (quiet hours, phone triggers, durations UI → Phase 7) |
| §6.3 `UserFacingError` | Tasks 1, 2, 6 |
| §6.4 unit tests: frame chunk reassembly/CRC/dedupe, StateFrame round trip + version check, SwipeClassifier/DoubleTap/HudNav, pause merge/resume-clears/offline-drop, closed interval filtering; HUD pages from fixture StateFrames | Tasks 1, 3, 4, 5, 7, 9, 10 |
| Phase 2 carries (trace notes, retry cap, no ModelCallException throwables in logs) | Task 2 |
| Master Review Focus 4 (offset-corrected pause instants) and 5 (low storage during frame write) | Tasks 4, 6, 8 |

Gaps found and fixed while writing: the contract needs a real `GlassesStatusSource` → `Hub` implements it and `HubGraph.glassesStatus` exposes it (Task 6); `nextCaptureAtMs` had no source on the phone → additive `Hello.nextCaptureAtMs` (Task 1); the legacy pulse alarm would have kept the RFCOMM chain alive beside `CaptureService` → `CaptureScheduler.ensureScheduled` cancels it (Task 11).

**2. Placeholder scan.** No TBD/TODO. Every code step has the code. The one copy step (Task 11 Step 6) is an exact `sed` line range from a file that is unchanged since `f67fc91`, with an expected first/last line and a count.

**3. Type consistency.** Checked across tasks: `FrameChunk(meta, chunk)`, `FrameAckReason.*`, `Assembly`/`Admission` variants, `ClockOffset.recordToPhone/recordToGlasses/anchoredAt`, `PauseHistory.pausedAt` (suspend), `HubPauses.active/add/union/resume/pruneHistory`, `HubInputs` fields, `HubState.toGlassesStatus()`, `GlassesLink.ready`, `FakeGlassesLink.emit/emitReady/connectCalls/connectOnceCalls`, `HudController.onFrameAck/onSessionOpened/onPhonePauses/sendFrameChunk`, `GlassesRuntime.hello(nextCaptureAtMs)`, `PauseToggle.Paused/Resumed`, `Routed.*`, `HudView(page, overlay)`, `LocalOverlay.Toast/Splash`, `CaptureService.ACTION_NOW/EXTRA_SKIP_DEDUPE/captureNow`, `MainActivity.ACTION_CAPTURE_NOW`.

**4. Review Focus.** Five items above, each pinned by named tests in its owning task. Also covered by tests but not in the list: ≤ 4 Hz under a burst of changes (Task 5), a resend after a lost ack (Tasks 4, 6), a silent phone closing the glasses session (Task 7), and NACK-twice drop (Task 8).
