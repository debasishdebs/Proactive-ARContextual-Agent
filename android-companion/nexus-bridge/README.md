# PCA Nexus bridge (v2.0, optional, OFF by default)

A separate, headless Android app (`io.github.pca.nexusbridge`, deliberately not `com.rokid.context`) that is a Rokid Nexus **plugin**. It draws what PCA's phone app hands it on the glasses through Nexus
(notices, a status pin, optional speech) and relays notice taps back. It holds no data, persists nothing and has no logic of its own. PCA's own glasses app and link are untouched (architecture "Option A",
which ASSUMES PCA's CXR link and the Nexus hub can coexist; hardware spike S2 has not run). Everything is OFF until the user switches it on inside PCA: Nexus HUD, Nexus speech (TTS), speech to text and the assist button.

Design: `docs/superpowers/specs/2026-11-17-nexus-spike-and-v2-plan.md` (sections 3-6). Rulings: `docs/superpowers/decisions/2026-11-17-codex-proxy-rulings-2.md` section B.

## Pieces

| Where | What |
|---|---|
| `:nexus-port` (library, no Nexus SDK) | `NexusPort` / `NexusPortCallback` AIDL, the PCA-owned types, `HudMapper`, `NexusGates`, and `NexusHudSink`, the ONE choke point (provenance, zone fences, pause, not worn, fail closed, withdraw on close). |
| `:nexus-bridge` (this APK) | `NexusPortService` (signature-guarded server of `NexusPort`), `BridgeRenderer` (validation + draw through PCA's own `NexusBusClient` interface), `SdkNexusBusClient` (the only file family that touches the SDK), `BridgeNexusService` (the plugin), `SettingsActivity` (NexusUi; state, defaults, how to remove). |

The transport is the thin `NexusTransport` / `NexusBusClient` pair. Option B (PCA traffic over the Nexus bus) replaces those, not the sink or the gates.

## How the Nexus SDK resolves

Pinned to the upstream tag **`sdk-v0.27.0`** (github.com/Anezium/Rokid-Nexus, Apache-2.0), consumed as the normal Maven artifact
`com.github.Anezium.Rokid-Nexus:bus-client:sdk-v0.27.0` (its `shared` and `ink-engine` come transitively). The pin is the `nexusSdk` value in `build.gradle.kts`. JitPack is added in
`settings.gradle.kts` for that one group only (`content { includeGroup("com.github.Anezium.Rokid-Nexus") }`), so no other dependency can come from it. First build needs the network once
(drop `--offline`); after that the Gradle cache serves it offline. To refresh: change `nexusSdk` to a newer `sdk-v*` tag, read the upstream `docs/PLUGIN_SDK.md` diff first (24 Oct to 14 Nov was six SDK versions),
rebuild online once, run `:nexus-port:testDebugUnitTest :nexus-bridge:testDebugUnitTest`. Fallback if JitPack is down: build the SDK offline from an upstream checkout at the tag (as in
`docs/superpowers/reviews/2026-11/nexus-spike-s0-s1-2026-11-17.md` S1: a module of the upstream worktree depending on `:bus-client`) and publish it to `mavenLocal()`.
Digests of the pinned artifacts (sha256): bus-client AAR `c1d87cae...cb743`, shared AAR `647d92fb...b9d1`, ink-engine JAR `5011949d...e23f`. No secret is vendored; nothing is checked in.
The SDK AAR is compiled with a newer Kotlin than the project's 2.0.21, hence `-Xskip-metadata-version-check` for this module only.

License: the Nexus SDK is Apache License 2.0, (c) 2026 Anezium (it also carries adapted code from alvarosw/Rokid-Nexus, Apache 2.0). Its NOTICE travels with the AAR; keep upstream's notice when redistributing the bridge APK.

## Manifest permissions (the whole list, pinned by `NexusBridgeManifestTest`)

`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` (normal, install-time, as in the SDK sample), plus a signature permission this app DEFINES, `io.github.pca.nexusbridge.permission.BIND_NEXUS_PORT`.
The merged APK also gets `io.github.pca.nexusbridge.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a self-defined signature permission that AndroidX adds. No INTERNET, storage, location, Bluetooth, camera, microphone or notification permission.
The PCA phone app will need `<uses-permission BIND_NEXUS_PORT/>` and a `<queries>` entry for `io.github.pca.nexusbridge` when its provider is added (not done in this change: the phone is untouched).

## Remove it

PCA works without it. Android Settings, Apps, "PCA Nexus bridge", Uninstall. Nothing is stored in the bridge. In Nexus, Settings, Plugin access, you can also deny it first.
