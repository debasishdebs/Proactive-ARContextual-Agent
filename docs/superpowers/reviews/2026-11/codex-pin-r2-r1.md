VERDICT: NOT READY

Paths below are relative to `android-companion/`.

1. [P1] services/memory/src/main/java/com/rokid/context/memory/place/UserPlace.kt:187 — Store schema-invalid pin JSON such as `[[{"moment":"0000abcd","at":123}]]` → `JSONArray` parses it → `optJSONObject` skips the nested array → settings export shares the Moment ID and timestamp unchanged. Earlier P1 #5 is incompletely closed. Fix: validate the complete pin schema and export only explicitly permitted fields, or `[]` on failure.

2. [P1] services/orchestration/src/main/java/com/rokid/context/work/frames/place/PinMomentLinks.kt:244 — An older committed arming remains owed and the pin list becomes unreadable → fresh Delete all arms nothing → `wipeCommittedStore` sees the older operation ID and returns false → the completed wipe remains pending → capture new memories → restart deletes those newer memories again. Fix: keep an unrelated committed pin obligation independent of the new wipe’s pending marker.

3. [P2] services/connectors/src/main/kotlin/com/rokid/context/connectors/place/PlaceSources.kt:202 — A mirror slowly streams its response → blocking `HttpURLConnection` reads continue beyond 25 seconds → `withTimeout` cannot stop those reads → failover waits until blocking I/O ends. Fix: enforce a cancellable HTTP call deadline that aborts the connection. [Kotlin timeout semantics](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout.html).

4. [P2] phone/src/main/java/com/rokid/context/phone/hub/AutoStopMachine.kt:300 — Trigger records a start timestamp → a call begins before the final call check → `NearbyCallBlocked` defers without removing that timestamp → the 15-second retry hits the 60-second debounce and clears the pending signal. Fix: refund an attempt that issued no start, or retain its pending retry until debounce expires.

5. [P2] phone/src/main/java/com/rokid/context/phone/hub/GlassesAclReceiver.kt:63 — Unrecognized-name glasses connected before process startup → an unrelated bonded headset connects recently → SDK link establishment learns the headset’s address, replacing a valid stored glasses address → glasses reconnects no longer wake the hub. Fix: preserve a still-bonded stored identity and require device-specific evidence before replacing it.

Earlier P1 #1–#4 close on their original traced scenarios; selection confirmation binding also closes. Static review only: no files edited, Gradle, adb, or tools scripts run.