Four remaining P1s:

1. **Unbounded Binder calls can freeze privacy fences.** [NexusHudSink.kt:94](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusHudSink.kt:94) holds the sink and both real fence locks across synchronous transport calls. A hung bridge blocks `hold()`, `trip()`, and withdrawal indefinitely. Atomic dispatch is implemented, but privacy shutdown now depends on the remote call returning.

2. **STT can open after a completed trip.** [BridgeRenderer.kt:77](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/nexus-bridge/src/main/kotlin/io/github/pca/nexusbridge/BridgeRenderer.kt:77): authorization and `current(gen)` pass → zone trip increments the generation and finishes stopping → `startListening()` opens capture. The post-start check stops it only afterward. Missing/throwing authorization fails closed; an unanswered Binder call hangs without opening STT.

3. **An old wipe acknowledgement can erase newer debt.** [NexusHudSink.kt:352](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/nexus-port/src/main/kotlin/io/github/pca/nexusport/NexusHudSink.kt:352) unconditionally clears `forgetOwed`. Sequence: wipe A pending → new notice B → Forget B requests another wipe, which fails → delayed acknowledgement A clears both sink and persisted debt. B remains replayable. Acknowledgements need correlation with a debt generation.

4. **Process death can lose mode-off debt before it is recorded.** [NexusHostStore.kt:59](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/nexus/NexusHostStore.kt:59) commits host=PCA before the asynchronous host collector calls `refresh()` and records debt. Death between these steps restarts with PCA selected and no wipe owed. Persist the transition and debt together.

Other checks: same-signer, rotation-lineage and PINNED verification precedes binding and binder acceptance, with package-change revalidation; no task-affinity or late-install spoof bypass found. AskAnswer/TTS now receive pause admission checks. No reverse place→ask lock cycle found. Normal teardown bounds binding, but failed bind attempts can stop automatic debt retries. Retention expiry explicitly does not request history wiping.

`e01ba94e` changes test logging only. Static review; tests were not run in the read-only workspace.

NOT READY