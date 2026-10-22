### Task 29: Listen metadata contract (S63) [CORE, trimmed 1 h; listenId LATER LA11]
**Stream C · 2.5 h · depends on: 5.**

**Files:**
- `GlassesEvent.Cmd(command, listenId: String? = null)`
- `GlassesInbound` (pass `env.listenId`)
- `phone/.../hub/HubVoice.kt` (`onListenStart(listenId: String?)`)
- `phone/.../voice/PipelineAdapters.kt`
- `services/voice/.../VoicePipeline.kt`:
  - `glassesListenStart(listenId: String? = null)` stores it per generation;
  - `SttResult` gains a `genuineFinal: Boolean` from the platform (a timeout partial = false), carried into `think`;
  - an `interceptor` hook runs **before** `Thinking` is published;
- the voice graph wiring

**Tests:**
- `listenIdReachesPipeline`;
- `listenIdScopedToGeneration`;
- `timeoutPartialNotGenuine`;
- `interceptorRunsBeforeThinking`;
- `existingPipelineTestsUnchanged`.

**Commands:** `gw.sh :services:glasses-link:testDebugUnitTest`, `gw.sh :services:voice:testDebugUnitTest`, `gw.sh :phone:testDebugUnitTest --tests '*Voice*' --tests '*Hub*'`.

**Commit:** `feat(voice): carry listenId and genuine-final status from the glasses envelope to the voice pipeline; interceptor seam before Thinking`

