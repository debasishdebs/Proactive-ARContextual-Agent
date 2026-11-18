Three P1s remain:

- **Nexus still publishes after driving closes.** [NexusFeeder.kt:86](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/nexus/NexusFeeder.kt:86) awaits `publishable()`, which releases both fences before `out.show()`. The sink then suspends for provenance and queues transport work; its final gates omit driving, Suggestions switches and quiet hours. Driving becoming active during that suspension still permits publication. Carry suggestion validation through the sink’s final admission/transport gate.

- **Home’s 10-second window is a P1 privacy leak.** [SuggestionFeed.kt:65](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/ui/home/SuggestionFeed.kt:65) observes store/settings changes and a timer, but no pause, zone or driving close event. Entering a zone leaves previously rendered private text visible until the next tick—potentially longer while rebuilding. `phoneFeed()` correctly rejects a fresh derivation, but nothing immediately clears the existing card. Wire gate-close/trip events to immediate clearing.

- **Consumption persistence fails open.** [Delivery.kt:161](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/Delivery.kt:161) appends the ledger after persisting `delivered` and publishing, ignoring append failure. A failed write or process death between those commits leaves delivered records without consumption entries; budgets exclude those records, and NudgeGates sees no ledger consumption. Dismissal-ledger failures likewise lose `DISMISS_PAUSE`. Persist consumption atomically with the delivery claim and fail closed on failure.

Six-P1 disposition:

1. Queued delivery: **open** for Nexus; HUD holder publication is synchronous inside the fences.
2. Home rendering: **open**, as above.
3. Final-step stale pause/clock: **closed**.
4. Withdrawal ordering/state machine: fixed for ordinary claim/publication interleavings; withdrawal precedes removal.
5. Budgets: normal-path 48-hour `ldg`, daily/family/gap accounting, NudgeGates integration and dismissal-pause calculation exist; **not closed** under persistence failure.
6. Original PMTiles bomb: blocked by the directory/count/depth/tile limits and decode OOM handling. Caveat: the decoded cache retains an oversized singleton, so 16 MiB is not a hard cap. No additional demonstrated crash/OOM found.

No additional concrete P1 deadlock, ranking exploit or withdrawal resurrection established. Static review; Android tests were not run in the read-only sandbox.

NOT READY