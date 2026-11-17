Five P1 findings:

1. **Zone holds race with content delivery.** [NexusHudSink.kt:77](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusHudSink.kt:77) checks fences under its own lock, then sends notice/TTS without `ZoneFence.act(ticket)`. A zone can hold after the check while its sink trip waits for that lock; private text still reaches Nexus. Serialize admission and dispatch with the actual fences.

2. **Assist STT starts before privacy admission.** [BridgeRenderer.kt:70](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/nexus-bridge/src/main/kotlin/io/github/pca/nexusbridge/BridgeRenderer.kt:70) opens Nexus speech before `onStarted` asks the phone’s gates. Zone/not-worn refusal therefore arrives after capture starts; pause is excluded by `syncBlock(false)` altogether. Require sink authorization before opening STT, including pause, with cancellation on trips.

3. **The phone accepts a spoofed bridge.** [NexusProvider.kt:54](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/nexus/NexusProvider.kt:54) binds an explicit package/component without verifying its signer. If the genuine bridge is absent, an attacker-installed APK using that package can expose an unprotected service and receive gated memory content and callback binders. The genuine service’s signature permission authenticates callers, not this replacement service. Verify the bridge signer before accepting its binder.

4. **Pause does not gate answer delivery or TTS.** [NexusHudSink.kt:136](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusHudSink.kt:136) checks pause only for proactive events; `AskAnswer` is non-proactive. Feeder answers can therefore arrive while paused. Assist answers also have a gap between their handler’s pause check, suspending source lookup, and sink delivery. Enforce pause at final answer/TTS admission.

5. **Unacknowledged history wipes are discarded.** [NexusProvider.kt:178](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/nexus/NexusProvider.kt:178) drops the sink after 30 seconds even when a wipe remains owed; `onGone` also discards it, and disconnected trips return immediately. Forgotten notices can remain replayable in Nexus history without a later retry. Persist wipe debt across disconnects, teardown and process restarts until acknowledged.

Static review only; tests were not run in the read-only workspace.

NOT READY