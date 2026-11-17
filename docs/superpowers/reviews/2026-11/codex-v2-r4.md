Three P1s remain:

1. **The accepted 2.25 s window is not enforced.** [NexusDispatcher.call](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusDispatcher.kt:110) cancels the future, but an uninterruptible Binder call continues. A delayed send can draw arbitrarily later; bridge cleanup waits for that send to return. This exceeds the documented bound.

2. **A late stale show can permanently escape the wipe.** After 120 s, [onForgetAccepted](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusHudSink.kt:607) ignores the unfinished show and settles debt. If it subsequently creates history, [showAt](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/nexus-bridge/src/main/kotlin/io/github/pca/nexusbridge/BridgeRenderer.kt:155) hides it and returns `STALE`. [trackedShow](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusHudSink.kt:206) re-owes only for `OK`, leaving the new history unwiped.

3. **Reconnect resets invalidate protection against old in-flight sends.** [setCallback](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/nexus-bridge/src/main/kotlin/io/github/pca/nexusbridge/NexusPortService.kt:54) resets the live renderer’s epoch to zero. An old epoch-8 send blocked inside rendering can resume after reconnect and a new epoch-2 zone trip: both stale checks accept 8. The new sink has no handle for it. Connections need distinct session identities. Actual bridge process death terminates old transactions; reconnect to a surviving renderer is the problematic case.

Prior P1s: **#2 closed; #4’s saturation path addressed; #1 and #3 remain incomplete.** No additional lock-order deadlock found; thread counts are capped per dispatcher. Wipe acknowledgements can drive repeated wipes until the 120 s cutoff.

Static review only; tests were not run in the read-only workspace.

NOT READY