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
- `android-companion/glasses/`: glasses-side camera frame relay app (CameraX + SPP).
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

## Voice marks
Say a short command after push-to-talk (glasses or phone) to save a mark for the last 10 seconds. No hotword, English only. Voice marks are always on in this build (no switch yet).
<!-- voice-marks-readme:begin -->
- **Mark a moment**: "mark that", "mark this", "mark it"
- **I missed that**: "i missed that"
- **Undo a mark**: "undo mark", "cancel mark"
<!-- voice-marks-readme:end -->
Add what you were doing ("mark that, I'm driving"); unknown words in the same sentence as "mark that" save a plain mark, while "note that the meeting is at five" stays a normal note. Notification, tile and home-button marks are not available yet. Audio is never stored or uploaded and recognition is on-device. Full guide: [docs/voice-marks.md](docs/voice-marks.md).

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
- Local runtime selection is now configurable:
  - `LOCAL_RUNTIME_MODE=native_first|native_only|ollama_only`
  - `LOCAL_NATIVE_MODEL_PATH=/absolute/path/to/model.gguf`
  - Default is `native_first` (try on-device llama.cpp, fallback to Ollama only if unavailable).

### True on-device model (Android)
- Yes, native on-device inference is supported via llama.cpp JNI path in app (`LlamaCppNativeProvider`).
- No server hosting is required in `native_first` / `native_only` modes once:
  - native library is bundled in APK (`libllama_jni.so`), and
  - GGUF model is present at `LOCAL_NATIVE_MODEL_PATH`.
- If native runtime is unavailable and mode is:
  - `native_first`: app falls back to Ollama provider.
  - `native_only`: app raises configuration/runtime error (expected, by design).

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

## Glasses Frame Relay (Real Camera Path)
- `glasses` captures camera frames with CameraX (not display `screencap`).
- Guardrails enforced on glasses before send:
  - reject dark/black frames by luminance threshold
  - reject near-duplicate frames via perceptual hash distance
  - reject under low battery policy (`<=10%` and not charging)
  - send-rate throttle and single in-flight send
- Compression strategy:
  - compress on glasses to JPEG before transfer (default quality ~62, width capped to 360 px)
  - send over Bluetooth SPP to phone bridge (`RokidBridgeService`) payload type `rokid_frame`
- Phone bridge adds second-layer guardrail to reject blank/dark payloads before ingest.

### Build
```bash
cd android-companion
./gradlew :phone:assembleDebug :glasses:assembleDebug
```

## Prompt Templates (Editable)
- Location: `android-companion/phone/src/main/assets/prompts/`
- Files:
  - `system_global.txt`: shared system behavior for all tasks.
  - `write_context_explicit.txt`: explicit remember/writeContext requests.
  - `write_context_batched.txt`: scheduled/batched frame-to-memory summarization.
  - `read_context_answer.txt`: readContext / answer generation.
  - `critic_validation.txt`: critic validation pass for summaries.
  - `re_summarize.txt`: recovery prompt for low-confidence re-summarization.
- Runtime usage:
  - `SummarizerWorkerJob` composes `system_global + write_context_batched`.
  - `CriticWorkerJob` composes `system_global + critic_validation`.
  - Prompt hash is logged for audit/debug without exposing full prompt text.
