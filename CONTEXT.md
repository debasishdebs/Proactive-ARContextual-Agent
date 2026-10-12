# CONTEXT

Last Updated: 2026-05-28

## Objective
Build a proactive assistant that uses sparse camera-derived memory to answer contextual voice queries (example: grocery guidance from recent observed consumption and fridge state).

## Current Design Direction
1. Glasses side
- Capture sparse frames, use bursts only when needed.
- Keep on-glasses compute minimal.
- Offload heavy vision/LLM work to phone.

2. Phone side
- Batch ingest frames from glasses.
- Perform memory extraction + intent classification.
- Update short/medium/long memory artifacts.

3. Retrieval side
- For voice triggers (e.g., "Hi Rokid ..."), retrieve from recent + long-term context.
- RAG/vector indexing will be introduced as memory scales.

## Constraints
- Battery safety first (both glasses and phone).
- Keep user control via configurable thresholds in companion app.
- Repository and code boundaries must remain clean across projects.
