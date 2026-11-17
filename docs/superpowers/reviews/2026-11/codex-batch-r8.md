- **P1 — “Previous” restores the excluded episode when no earlier episode exists.** [OrderRules.kt:110](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/retrieval/OrderRules.kt:110) returns `null`; RagCore then keeps the original pool without an exclusion note. A previous-occurrence question can therefore receive the newest occurrence. Return an explicit empty selection instead.

- **P1 — The fallback cache freezes a moving window.** [LlmWindowResolver.kt:38](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/retrieval/LlmWindowResolver.kt:38) caches the resolved window after clipping its end to `now`. “What happened after breakfast?” asked at 09:00 still excludes 09:00–11:00 when repeated at 11:00. An existing relevant hit prevents widening. Cache the interpretation and recompute bounds and event anchors.

- **P1 — State detection drops evidence for unrelated questions.** [OrderRules.kt:69](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/retrieval/OrderRules.kt:69) classifies “What did the sign on the door say?” as a state question (`door` + `on`). Built-in Ask then keeps only three newest episodes: three newer door episodes above the relevance floor can exclude the highest-scoring older sign. Require an actual device-state predicate.

**A:** The original fixes are present: WindowRelevance controls widening, built-in retrieval enables `stateNewest`, and rule 3a qualifies incomplete evidence. I independently recomputed the previous fingerprint: `81a974…e4d2a4`, correctly registered in `PREVIOUS`.

**B:** No memory payload leak found: TIME_ANCHOR receives question/date metadata with `PayloadSources.NONE`; event retrieval stays local. `TRACE_SCHEMA` remains 1 with no frozen-field changes. Default Query’s canonical inputs are unchanged; `f5782e0d8610` belongs to the **capture** plan, not Query.

Static review; Android tests could not run in the read-only sandbox.

NOT READY