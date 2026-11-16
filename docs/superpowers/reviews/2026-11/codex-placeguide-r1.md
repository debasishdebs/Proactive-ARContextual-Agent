- **P1 — Zone changes can leak fixes/network/photos.** [PlaceSampler.kt:103](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSampler.kt:103) checks zones only before several suspending operations. A zone becoming blocked during frame lookup, fix acquisition, pack fetching, or model-permit waiting does not prevent subsequent work/upload. `FrameLiveness` fences Forget/wipe, but has no zone check. Fence each side effect and recheck zone authority inside the upload permit, including retries.

- **P1 — Post-model zone race still publishes.** [PlaceSampler.kt:135](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSampler.kt:135): if a zone activates during `saveMemory()`, the following check omits `zoneBlocked()` and publishes the card. Fence memory commit and publication against zone changes.

- **P2 — Delete all can be undone by an outstanding fetch.** [PlacePack.kt:123](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/memory/src/main/java/com/rokid/context/memory/place/PlacePack.kt:123) neither shares `fetchLock` nor invalidates outstanding requests. A fetch started before deletion can subsequently insert fetched places, cells, and recency. Serialize deletion with pack operations or add a transactional generation fence.

- **P2 — The 20-second limit uses a stale timestamp.** [PlaceSampler.kt:124](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSampler.kt:124) records the attempt’s start time, before potentially lengthy pack fetching. After a fetch exceeding 20 seconds, another eligible frame can trigger a model call immediately afterward. Measure spacing at actual dispatch using a monotonic clock.

- **P2 — Published cards survive Forget/Delete all.** [PlaceCard.kt:31](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceCard.kt:31) retains photo-derived content process-wide; neither deletion path calls `clear()`. Withdraw matching cards on Forget and all cards on wipe, fencing concurrent publication.

Otherwise, static inspection found candidate allowlisting/no people output, the existing image choke point, coarse-cell-derived fetch bounds, backup exclusions, pack-only card text, outside-set suppression, transactional memory provenance/cascade, preserved zones, deferred Keystore access, and thermal admission correctly wired.

Tests were not run in the read-only environment.

NOT READY