Prior P1s: Binder calls no longer hold fence locks; bridge STT compare/open is atomic; acknowledgements are generation-correlated; host+debt commit is atomic. Those fixes are present, but **four redesign P1s remain**:

1. **Trip does not close the final delivery race.** After `stillValid()` succeeds, a zone trip can complete before the Binder call starts. The bridge epoch update is [asynchronous and best-effort](android-companion/nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusHudSink.kt:325), so `showAt`/`speakAt` can accept the old epoch and send content while the zone is held. The bridge’s post-send stale check cannot retract disclosed content. Epoch invalidation needs ordering with delivery.

2. **An urgent hide can leave a later show untracked.** Queue replacement B behind a busy worker; Dismiss from visible A queues an urgent hide ahead of B. [runHide removes B’s registered handle](android-companion/nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusHudSink.kt:292), but `runShow(B)` checks neither withdrawal nor handle membership and subsequently draws B. A later zone trip finds no handle to hide; `tripAt` itself only updates the epoch. Cancel covered pending shows when withdrawing.

3. **A timed-out show can repopulate history after an acknowledged wipe.** Cancellation [does not guarantee termination of the Binder call](android-companion/nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusDispatcher.kt:93). A stalled show can resume after hide → wipe → acknowledgement has cleared debt. The bridge’s [post-send stale cleanup](android-companion/nexus-bridge/src/main/kotlin/io/github/pca/nexusbridge/BridgeRenderer.kt:148) hides the notice but does not wipe its newly created history entry. Generations correlate acknowledgements without establishing this ordering.

4. **Queue saturation can lose the only STT stop.** A queue full of unguarded operations [rejects further privacy operations](android-companion/nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusDispatcher.kt:48). `trip()` ignores rejection of its unconditional `stopListening`. If an authorized session is opening and has no LISTEN handle yet, tick cannot retry it. A rejected stop from the subsequent blocked `onListening(true)` also leaves no handle. `tripAt` does not invalidate listening generations. Reserve or coalesce non-droppable shutdown operations.

Old AIDL fails closed for the new content methods; legacy acknowledgement/bind loops are below the requested P1 threshold.

Static review; tests were not run in the read-only workspace.

NOT READY