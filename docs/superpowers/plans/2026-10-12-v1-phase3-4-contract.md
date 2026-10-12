# Phase 3 ↔ Phase 4 contract (parallel execution)

Phases 3 and 4 run in parallel in separate git worktrees off `feat/proactive-mvp-implementation` @ fcb5cee and merge back (Phase 3 first, then Phase 4, then a small integration task in the Phase 4 plan). This file fixes what each side owns and the one interface between them.

## Ownership (files each plan may touch)
- **Phase 3 only:** `android-companion/glasses/**`, new `android-companion/services/glasses-link/**`, `phone/src/main/AndroidManifest.xml` (services/permissions; Phase 4 may change only `android:icon`/`android:roundIcon`), `phone/src/main/java/com/rokid/context/ContextApp.kt`, new `phone/src/main/java/com/rokid/context/phone/hub/**` (ContextHubService, StateBroadcaster, HubGraph wiring), deletion of `phone/src/main/java/com/rokid/context/bridge/**` and glasses `RokidBluetoothProtocol.kt`/`CompanionConfigServer.kt` (R-04), `:core:hud` additions, `:core:model` additions (additive only; PROTOCOL_VERSION bump only if a message changes incompatibly).
- **Phase 4 only:** `phone/src/main/java/com/rokid/context/MainActivity.kt` (rewritten: NavHost shell, no polling loop), `phone/src/main/java/com/rokid/context/ui/**` and new `phone/src/main/java/com/rokid/context/phone/ui/**`, `:core:ui` additions, phone `res/` (icon, strings), `settings.gradle.kts` only if a new module is needed (avoid).
- **Shared, additive-only:** `settings.gradle.kts` (each adds its own include lines), `phone/build.gradle.kts` (each adds its own dependency lines), `:services:memory` (new DAOs/queries only; schema v1 frozen — any new table/column requires version bump + Migration + MigrationTestHelper test, coordinate by putting it in Phase 3 only).
- Phase 3 must remove the RokidBridgeService start/stop calls from the legacy `MainActivity` minimally (R-04); at merge Phase 4's rewritten `MainActivity` wins and must contain no `bridge` references.

## Interface (Phase 3 produces, Phase 4 consumes)
In `:core:model` (exists already): `StateFrame`, `CaptureState`, `Health`, `NudgeLine`, `MemoryLine`, `Overlay`, `HudSettings`, `PauseRecord`.
New, defined by **Phase 4** in `phone/src/main/java/com/rokid/context/phone/hub/HubState.kt` is NOT allowed (Phase 3 owns `phone/hub/**`). Instead the interface lives in `:core:model` and is created by **both plans identically** (the merge is a no-op if byte-identical):

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
`:core:model` must add `api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")` for `StateFlow` (whichever plan lands first adds it; the other's identical line merges cleanly).
Phase 4 ships `FakeGlassesStatusSource` (test/debug fixture) and its screens take a `GlassesStatusSource`; Phase 3 ships the real implementation in `phone/hub/` and exposes it via `HubGraph.glassesStatus(context): GlassesStatusSource`. Phase 4's integration task (run after both merge) swaps the fake for `HubGraph.glassesStatus(context)`.
