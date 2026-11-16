Three P1 paths remain:

1. **Photo upload can start after a zone trip.** Once the provider enters its IO block, payload construction and blocking [`execute()`](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/ai/src/main/java/com/rokid/context/model/provider/OpenAiCompatProvider.kt:76) have neither a fence check nor a cancellation hook. Trip during serialization → cancelled coroutine still uploads. Ollama has the same issue. Fence request admission and connect cancellation to the actual HTTP call.

2. **Place fetch cancellation has a registration race.** In [`PlaceHttp.kt:60`](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/connectors/src/main/kotlin/com/rokid/context/connectors/place/PlaceHttp.kt:60), trip can occur after `ticket.check()` but before the default-start watcher executes. A watcher cancelled before starting never enters its `finally`; the parent continues to blocking `outputStream`/`responseCode`, disclosing the cell after protection activates. Register cancellation reliably and serialize dispatch admission with the fence.

3. **Failed rollback can leave readable memory indefinitely.** [`takeBack()`](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSampler.kt:150) stops after three failures. Its durable mark is retried only by another sampler poke; there is no startup or independent retry worker. Zone protection can stop further frames, leaving a committed, rejected photo memory accessible indefinitely. Schedule recovery independently and exclude pending memories from reads.

Popup withdrawal appears fixed; the three P2 changes are present. Card publication uses the shared lock. No concrete fence deadlock/starvation path found. `seedArea` uses an explicitly entered/preset bounding box and does not read the current position.

Static review only; tests were not executed in this read-only workspace.

NOT READY