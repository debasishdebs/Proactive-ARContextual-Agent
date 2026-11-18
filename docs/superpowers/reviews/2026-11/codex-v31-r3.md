Three P1s remain:

- **Deadlock in the new final gate.** [Delivery.kt:217](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/Delivery.kt:217) reads driving through `DrivingSignal.blocks()` → synchronized `DrivingTracker.assess()`. Nexus holds the zone fences before taking that monitor; delivery/`phoneFeed()` hold the driving monitor before taking the zone fence. Concurrent execution can deadlock. The “direct” read still takes a lock.

- **Driving closure still permits delivery and continued rendering.** The driving monitor is released before the transport call, so driving can close after the last check. Its close event only signals Home; it neither trips Nexus nor clears the HUD holder. Already-published Nexus handles also discard the suggestion gate: [tick():527](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusHudSink.kt:527) checks only the generic gates/provenance. A displayed card therefore survives driving closure until expiry. Home’s quiet-boundary timer cannot protect other surfaces when Home is absent; quiet-hours withdrawal relies on the subsequent hub pause refresh.

- **Dismissal persistence still fails open after restart.** [SuggestionStore.kt:169](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/SuggestionStore.kt:169) retains failed dismissal writes only in `unsaved` memory. Four persisted dismissals plus a failed fifth correctly pause this process, but a crash before another successful append loses the fifth and reopens `DISMISS_PAUSE`.

The reservation-before-claim fix closes delivery-consumption loss. Orphaned RESERVED entries do **not** permanently block delivery: accounting windows age out, and the next append prunes entries older than 48 hours. Pruning is not automatic at 48 hours.

Home’s explicit zone/pause/driving event wiring is present. No additional double-delivery or withdrawn resurrection established. Static review; tests were not run in the read-only sandbox.

NOT READY