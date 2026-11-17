**One new P1: a late connection can claim the new session.** [beginSession](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/nexus-bridge/src/main/kotlin/io/github/pca/nexusbridge/BridgeRenderer.kt:149) accepts any nonzero session while `conn.claimed == false`; it is not tied to the callback that triggered the reset.

Interleaving: new `setCallback` resets the connection → delayed old `beginSession(S1)` claims it → new `beginSession(S2)` and `tripIn(S2, …)` return `STALE`. An old content call continuing through `ensureSession(S1)` can then enter and return against the same S1 connection. The bridge cannot detect the new connection’s trip. Bind session adoption atomically to callback registration or a connection-specific token; add this interleaving to the tests.

- **P1-2 closed:** created-history and unknown outcomes re-owe the wipe after the cutoff; unfinished calls remain tracked.
- **P1-3 not fully closed:** connection identity fixes already-entered sends, but the adoption race above remains.
- **P1-1:** the documented uninterruptible-call limit is accepted; no finding for that limit.
- No other new P1 found in compatibility, retry loops, deadlocks, or crashes.

Static review; tests were not rerun in the read-only workspace.

NOT READY