# Architecture

## Components
1. Glasses Capture Agent
- Applies capture interval policy from activity and battery state.
- Produces compressed frame packets and metadata.

2. Phone Ingestion Pipeline
- Receives batches from glasses.
- Applies duplicate/novelty filter.
- Runs two-pass analysis:
  - Memory/context extraction.
  - Intent classification (`remember` vs `answer`).

3. Model Gateway
- Single interface across cloud API and local models.
- Routes request by policy and availability.

4. Memory Store
- `memory.md` for human-readable timeline.
- Structured JSON records for retrieval and compaction.
- Weekly refresh/compaction configurable from mobile app.

5. Query Engine
- Voice-triggered retrieval over recent + long-term memory.
- Future-ready for RAG/vector expansion.

## High-Level Data Flow
1. Glasses capture frame + metadata.
2. Phone queues frame batch.
3. Policy decides immediate vs deferred processing.
4. Model gateway runs analysis.
5. Memory/context files updated.
6. Voice query runs retrieval + answer generation.

## Policy Hooks
- Battery level
- Charging state
- Thermal state (next phase)
- Network type (Wi-Fi vs cellular)
- Local model readiness
