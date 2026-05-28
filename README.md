# Proactive Context Agent

Battery-aware memory and context agent for Rokid glasses + Android companion.

## Goal
Build a separate app stack that:
- Captures sparse/burst visual context on glasses.
- Processes and summarizes frames on phone.
- Maintains `memory.md` + structured context stores.
- Answers voice-triggered queries using recent and long-term memory.

## Repository Layout
- `docs/ARCHITECTURE.md`: system design and state flow.
- `docs/PHONE_PROCESSING_POLICY.md`: battery + charging processing rules.
- `src/model_gateway.py`: uniform provider interface (API/local interchangeable).
- `src/phone_request_policy.py`: scheduling policy for uploads + local inference.
- `reference/language_models.py`: copied reference abstraction source.

## Locked Decisions (Current)
- Glasses capture policy uses activity-driven intervals + battery guardrails.
- Phone processing:
  - If battery `> 30%`: send/process every `15 min`.
  - If battery `<= 30%`: send/process only while charging.
- Local model processing:
  - Run only when charging, or when battery `> 70%`.

## Next Build Steps
1. Implement Android WorkManager jobs from `phone_request_policy.py` logic.
2. Add mobile settings screen for intervals/thresholds/provider endpoints.
3. Add memory store (`short_term`, `medium_term`, `long_term`) + weekly compaction.
4. Add retrieval path (keyword + vector index) for voice query answering.

## Project Tracking Files
- `MEMORY.md`: durable facts and locked decisions.
- `CONTEXT.md`: active problem framing and constraints.
- `HANDOFF.md`: current session status and next steps.
- `DECISIONS.md`: timestamped decision log.

## Android Companion Progress
Implemented baseline modules:
- policy engine and provider-block orchestration
- queue/idempotency core
- room schema entities
- summarizer/critic worker contracts
- model gateway lifecycle skeleton
- onboarding/config/diagnostics ui contracts
- security migration/export safeguards
- multilingual retrieval routing
- telemetry event reporter

## Real Client Wiring (Android)
- Azure KG GPT client now reads build-time values from `.env`:
  - `AZURE_KG_ENDPOINT`
  - `AZURE_KG_54_API_KEY`
  - optional `AZURE_KG_DEPLOYMENT` (defaults to `gpt-5.4`)
- Ollama client uses `OLLAMA_BASE_URL` + `OLLAMA_MODEL` (default model `llama3.1:8b`).

### Ollama runtime note
- Docker is not run inside Android app runtime.
- Recommended setup is running Ollama on host/laptop or local server and connecting from Android over network:
  - Emulator default: `http://10.0.2.2:11434`
  - Physical device: use your machine LAN IP, e.g. `http://192.168.x.x:11434`

### Local Ollama quick start (host)
```bash
docker run -d --name ollama -p 11434:11434 ollama/ollama:latest
docker exec -it ollama ollama pull llama3.1:8b
```

### Android orchestration
- WorkManager scheduler added:
  - periodic frame ingest (15 min)
  - periodic critic job (3h)
  - one-shot summarizer enqueue
