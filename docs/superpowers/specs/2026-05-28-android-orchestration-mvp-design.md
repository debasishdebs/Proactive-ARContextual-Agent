# Android Orchestration MVP Design

Date: 2026-05-28
Project: proactive-context-agent
Status: Approved for planning

## 1. Scope
Define the phone-side and glasses-integrated orchestration MVP for proactive context processing.

This spec covers:
- Queue model, orchestration responsibilities, and contention policy.
- Battery/charging/network/pressure/provider gating.
- LLM interface and provider adapters.
- Companion app UX, config hub, onboarding, diagnostics, and alerts.
- Activity-aware capture inputs and fallback behavior.
- Summarization, critic validation, memory tiers, and retention.
- Multilingual input/output and retrieval behavior.
- Security, encryption migration, schema migration, and diagnostic export.
- Model lifecycle (download, verification, slot promotion).
- Failure taxonomy, user responses, and testing.

This spec intentionally keeps Option 1 orchestration for MVP and defers Option 3 state-machine migration by one week after MVP completion.

## 1.1 Implementation Reality Check (As Of 2026-05-29)
- Implemented:
  - Companion app UI shell with Home/Live/Queues/Frames/Memory/Config.
  - Summarizer + critic workers and tracker/memory runtime logs.
  - Frame slideshow UI with fullscreen controls.
  - USB debug bridge script for host-mediated glasses screenshot ingest.
- Not fully implemented yet:
  - Production Rokid↔Companion direct bridge (no socket/BLE/Wi-Fi direct transport yet).
  - Durable multi-queue orchestration for `writeContext explicit`, `writeContext batched`, `readContext`, `retry` with true FIFO persistence and cancellation semantics.
  - End-to-end write/read context pipeline where queues are authoritative (current queue counts partly derived from runtime counters and placeholders).

## 2. Chosen Approach
Chosen for MVP: Single Orchestrator + Concurrent Queues (Option 1).

Deferred:
- Option 3 (explicit state machine) target start: June 4, 2026.

## 3. Core Architecture

### 3.1 Components
- OrchestratorService: central scheduler, gating, cancel/retry/defer logic.
- raw_frame_store: append-only raw frame packets + metadata.
- summarizerWorker: FIFO summarization worker from raw_frame_store.
- summary_store: read-optimized memory/query store.
- memory_tiers_store: short/medium/long records + episodic tags.
- criticWorker: periodic quality validator for summaries.
- retry/deferred queue: retriable failures and policy deferrals.
- modelGateway: backend-agnostic LLM interface and routing.

### 3.2 Logical Queues
- frameIngest
- summarizer
- writeContext
- readContext
- priorityReprocess
- retry/deferred

## 4. Concurrency And Contention

### 4.1 Default execution
- Queues run concurrently by default.
- frameIngest/writeContext/readContext/summarizer can run in parallel with atomic-write and snapshot-read contracts.

### 4.2 Contention policy
Priority order when constrained resources exist (CPU/thermal/model slots/IO locks):
1. priorityReprocess
2. explicit writeContext
3. readContext
4. summarizer
5. frameIngest
6. retry

Additional latency guarantee:
- Reserve a dedicated low-latency execution slot for readContext.

### 4.3 Pressure handling
- Under high CPU, thermal pressure, or low-battery risk, throttled work is deferred into FIFO deferred queue with 24h TTL.

## 5. Capture, Buffering, And Ingest

### 5.1 Glasses-side pre-filter
- Send only meaningfully different frames (basic dedup).
- Glass policy may block send due to battery/dedup/capture rules.

### 5.2 Disconnect buffering
- Glasses keep a bounded ring buffer during disconnect.
- Cap: 500 frame packets.
- Eviction: FIFO drop-oldest.
- Auto-throttle when buffer >=75%.
- Hysteresis: return to normal only after buffer drops sufficiently (target below 50%).

### 5.3 Activity-aware capture inputs
Signal priority:
- Primary: Android Activity Recognition.
- Secondary: Health Connect.

Fallback chain:
- If Activity Recognition unavailable, use Health Connect.
- If both unavailable, battery-only fixed cadence:
  - battery >50%: 1 frame per 30s
  - battery <=50%: 1 frame per 60s

Activity mapping defaults:
- STILL: slow capture interval
- WALKING: medium interval
- RUNNING/IN_VEHICLE: faster burst mode

All activity logic remains gated by battery and dedup policy.

## 6. Battery, Charging, Network, And Saver Rules
- If phone battery >30%: scheduled processing allowed every 15 minutes.
- If phone battery <=30% and not charging: defer queued work.
- Deferred retention TTL: 24 hours.
- Local model allowed only when charging OR battery >70%.
- Network poor/offline: retry with backoff; try local if configured and allowed; otherwise remain queued until recovery or TTL.
- Manual readContext bypasses schedule unless Android Battery Saver is ON.
- Explicit writeContext is immediate unless Battery Saver is ON; then defer with TTL.

## 7. LLM Interface And Routing

### 7.1 Interface contract
- Use custom backend-agnostic interface aligned to [language_models.py](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/reference/language_models.py).
- Support messages-capable request/response shape.

### 7.2 Provider support
- Cloud adapter: OpenAI-compatible HTTP (`baseUrl`, `apiKey`, `model`, timeout).
- Local adapter: Ollama-first.
- Abstraction remains model-agnostic for later HF/llama.cpp adapters.

### 7.3 Routing
- Policy-driven route selection from battery/network/saver/local availability.
- Local fallback during retry only when enabled and allowed.
- Return standardized backend metadata for observability.

## 8. Provider-Block Behavior

### 8.1 Trigger
- If no healthy local model and no usable cloud provider path exists, enter `ProviderBlocked`.

### 8.2 Strict global block
- Block all pipelines: capture, ingest, read, write, summarizer, critic, retry.
- Incoming user read/write requests are rejected with clear reason.
- Rejection includes `Fix now` deep-link; glasses voice includes resolution guidance.
- Glasses UI shows persistent status chip (error text only).

### 8.3 Queue handling
- Cancel pending jobs with reason `ProviderBlocked`.
- After recovery, auto-retrigger system pipelines only.
- User-originated rejected requests are not auto-retried; user must ask again.
- Recovery warm-start window: 10 minutes.
- If not recovered in warm-start, re-queue normally.
- Re-queued items preserve original timestamps.

### 8.4 Recovery checks
- Unblock gate requires provider connectivity/auth check success.
- Test inference runs opportunistically after unblocking for diagnostics only.

### 8.5 Notifications
- Home: persistent high-priority card + banner/notification.
- No forced full-screen modal.
- Throttle repeat notifications via dedupe on notification text/content.

## 9. Summarization, Critic, And Trusted Read Path

### 9.1 Two-stage summarization
- Stage A: per-frame/per-chunk factual extraction.
- Stage B: periodic consolidation into memory entries.

### 9.2 Critic validation
- Runs every 3 hours.
- Validates summaries with rolling time window:
  - window: 30 minutes
  - step: 15 minutes

### 9.3 Raw-frame lifecycle
- Raw frames retained until critic success, then deleted.
- Hard cap: delete raw frames by 24h max TTL even if critic path is delayed.

### 9.4 Critic failure behavior
- Mark summary `needs_review`.
- Exclude it from readContext.
- Queue re-summarization from source with critic reasoning attached.

### 9.5 Query fallback chain
- Use last trusted summary for relevant topic/window.
- If no high-confidence trusted summary exists, return `ContextTemporarilyUnavailable`.
- Include ETA if reliably available.
- Trigger immediate priority reprocessing for affected topic/window.

## 10. Memory Model, Retention, And Pressure

### 10.1 Tiers
- short_term
- medium_term
- long_term
- episodic_events tags

### 10.2 Defaults
- Retention configurable from mobile settings.
- Default values:
  - short_term: 24h
  - medium_term: 7d
  - long_term: 30d

### 10.3 Promotion
- Auto-promote to long_term via composite score:
  - frequency
  - recency spread
  - explicit user emphasis
  - critic confidence
- Manual voice trigger for promotion is also configurable.

### 10.4 Storage quota
- Soft quota default: 100 MB (configurable).
- Pressure thresholds:
  - 70% warning
  - 90% critical
- Eviction strategy: short_term first, protect high-importance long_term.
- Show memory-pressure status card on Rokid side.

### 10.5 Provenance
- Store per-item audit metadata internally:
  - persona_id
  - model/backend
  - prompt_template_version
  - timestamp
- Not exposed in MVP UI.

## 11. Persona, Intents, And Language

### 11.1 Persona
- Persona is global in MVP.
- Every read/write request captures persona snapshot at enqueue time.
- Downstream processing uses captured snapshot.
- If query persona differs from source persona, preserve facts and reframe response in current persona tone.

### 11.2 Shortcut intents
- Fixed intent catalog for MVP.
- Each action maps to selectable intent from dropdown.
- Full action-intent customization deferred.

### 11.3 Shortcut feedback
- Minimal confirmation cues on success and failure.
- Failure cues should provide specific spoken reason.

### 11.4 Language behavior
- Supported languages in MVP: English, Hindi, Oriya.
- Separate configurable input language and output/view language.
- Input language auto-detected per request.
- Processing runs in detected input language.
- If detected input differs from configured output, respond in detected input language; optional language-context suffix allowed.

### 11.5 Memory/retrieval language strategy
- Store memory text in original language only.
- Retrieval prioritizes multilingual embeddings.
- Fallback to Indic-focused models when needed.
- Routing mode: hybrid auto-selection with manual override.
- Manual override granularity: per language pair.

## 12. Companion UX And Config Hub

### 12.1 Home screen
- Cards for mode/status, queue health, memory, ops signals, and migration status.
- Each card has dedicated icon and deep-links to detailed screens.

### 12.2 Config hub
- Home `Config` card opens centralized detailed config hub.
- Include:
  - LLM config
  - local mode/runtime settings
  - prompt personas
  - glasses shortcut mapping
  - activity identification settings
  - memory settings
  - queue/retry/policy controls
  - diagnostics toggles
- MVP should expose full detail unless a concrete risk requires deferral.
- Deferred controls remain visible read-only and marked for v1.1.

### 12.3 Secrets and security in settings
- API keys/secrets masked in UI.
- Sensitive values in secure encrypted storage.

### 12.4 Developer diagnostics
- Visible toggle in Config Hub.
- Verbose logging control is separate.
- Verbose log retention default: 72 hours.

### 12.5 Limited mode indicator
- If optional permissions are skipped, show persistent Limited Mode banner.

## 13. Onboarding And Permission Model

### 13.1 Onboarding style
- Guided setup wizard.
- Bright slideshow style.
- Top image area occupies about 70-80%, text below.
- Use Material UI patterns for components, colors, and shapes.

### 13.2 Setup order
1. Welcome
2. Language (input/output)
3. Permissions
4. LLM provider
5. Persona
6. Shortcuts
7. Finish

### 13.3 Minimum E2E gate
- Mandatory: permissions + provider configured.
- Defaults: English and default LLM prefilled.
- Shortcuts can be configured later.

### 13.4 Mandatory vs optional permissions
Mandatory for minimum E2E:
- Glasses: camera + microphone.
- Phone: notifications/foreground execution + device-link permission path.

Optional with degradation:
- Activity Recognition
- Health Connect

Deferred:
- Phone-mic voice fallback (v1.1).

## 14. Model Lifecycle Management

### 14.1 Download manager
- Include model download manager in MVP.
- Show status, size, progress, pause/resume.
- Default Wi-Fi-only download, user override allowed.
- Auto-pause downloads when battery <=30% and not charging.

### 14.2 Integrity and activation
- Verify checksum/signature before activation.
- On verification failure: auto-retry once, then mark unusable and require user action.

### 14.3 A/B slots
- Use active + candidate model slots.
- Candidate promotion requires health check (test prompt + latency/quality thresholds).
- If candidate fails health check, user confirmation required.
- Default recommendation in failure dialog: keep current active model.
- No one-tap rollback action in MVP UI.

### 14.4 Runtime fallback
- If active local model becomes unhealthy, auto-fallback to cloud if configured, with user notification.

## 15. Encryption And Data-Migration Policy

### 15.1 Encryption toggle
- Local data-at-rest encryption is user-toggleable.
- MVP default: OFF.

### 15.2 Enable-encryption migration
- Turning ON triggers background migration with progress state.
- Resume from checkpoint on interruption.
- Retry once automatically.
- If retry fails, rollback and require manual migrate trigger each app open.

### 15.3 Unmigrated legacy data behavior
- Unmigrated unencrypted data is blocked from read pipelines (readContext, critic, and related reads).
- New incoming data can still be accepted and written encrypted.
- Legacy unmigrated data has hard TTL of 1 week.

### 15.4 Migration UX
- Dedicated Migration Status home card.
- Hidden/disabled when no migration state is active.
- Popup cadence while pending:
  - once per app open
  - every 30 minutes while still pending
- Migration card provides full status and action controls.

## 16. Schema Evolution, Quarantine, And Diagnostic Export

### 16.1 Schema versioning
- Every memory record has `schema_version` from day 1.

### 16.2 Migration strategy
- Lazy migration on read/write + background sweeper.

### 16.3 Failure handling
- On migration failure/corruption:
  - quarantine record
  - log reason
  - exclude from retrieval
  - continue pipeline
- Provide manual retry trigger.
- Quarantine TTL default: 30 days.
- Show quarantine indicator with count and `retry migration now` action.

### 16.4 Diagnostic export/decrypt
- Support encrypted diagnostic bundle export for quarantined data.
- Decrypt paths:
  - USB debugger CLI (host-connected)
  - optional in-app decrypt-and-save flow
- In-app decrypt requires strong re-auth (biometric/device credential).
- USB decrypt requires Developer Mode + one-time consent token.
- Token is single-use, non-reusable, and expires in 10 minutes.
- Block screenshots/screen-recording on decrypt preview screen.
- Decrypted artifacts are ephemeral:
  - delete immediately after successful share/export
  - force delete after 5 minutes if session remains open
- Audit all decrypt attempts (success/failure, timestamp, mode, reason code).

## 17. Config Sync Between Phone And Glasses
- Apply config on phone immediately.
- Push to glasses via versioned sync with retry.
- If sync fails persistently, require manual sync trigger and show message in app + home widget/notification.
- Conflict policy:
  - newest timestamp wins automatically
  - if timestamps equal but payload differs, phone wins and warn user

## 18. Failure Taxonomy
- NetworkUnavailable
- BatterySaverBlocked
- PolicyDeferred
- PressureDeferred
- JobStuck
- TTLExpired
- ConnectionError
- GlassPolicyBlocked
- ContextTemporarilyUnavailable
- ProviderBlocked

## 19. User-Facing Response Rules
- Queue ack for readContext FIFO state.
- Battery saver defer message for blocked immediate actions.
- Local fallback active message when applicable.
- TTL expiry message.
- Stuck read cancellation message (watchdog 15 minutes).
- ConnectionError and GlassPolicyBlocked messages for debug + voice-safe responses.
- For disconnected-glasses reads: use trusted local memory with stale warning; if not confident, return unavailable status.

## 20. Performance Targets
- readContext response latency target: p95 <= 6 seconds.
- First acknowledgement/audio feedback target: <= 1.5 seconds, then stream remainder.
- Config sync to glasses is best-effort in MVP (no strict latency SLA).

## 21. Testing Plan

### 21.1 Unit tests
- Policy gates (battery/charging/saver/network).
- Routing decisions (cloud/local/defer/provider-block).
- Activity fallback chain.
- Persona snapshot and provenance persistence.
- Multilingual routing and fallback selection.
- Quota, eviction, and pressure thresholds.

### 21.2 Queue and lifecycle tests
- FIFO behavior and contention arbitration.
- Idempotency with per-queue + payload hash + 60-minute bucket.
- JobStuck cancellation and progression.
- ProviderBlocked cancellation/retrigger behavior.
- Warm-start and re-queue with timestamp preservation.

### 21.3 Migration and security tests
- Encryption migration happy path and failure/rollback path.
- Unmigrated-data read blocking.
- Quarantine TTL and retry flows.
- Decrypt token single-use/expiry enforcement.
- Screenshot-blocking and ephemeral decrypted-file cleanup.

### 21.4 Model lifecycle tests
- Download pause/resume and battery gating.
- Checksum/signature verification.
- A/B candidate health-check promotion flow.
- Failed candidate user-confirmation flow.

### 21.5 End-to-end tests
- Onboarding minimum E2E gate.
- Limited Mode banners.
- Strict ProviderBlocked behavior.
- Multilingual query path and trusted-fallback logic.

## 22. Observability
Minimum metrics/events:
- Queue depth by queue type
- Retry counts and backoff stages
- TTL expiries
- JobStuck cancellations
- Local fallback usage
- GlassPolicyBlocked counts
- ConnectionError frequency/recovery latency
- Activity-state distribution
- Auto-promotion counts
- ProviderBlocked duration/events
- Migration and quarantine states
- Model slot health/promotion outcomes

## 23. Privacy And Telemetry Defaults
- Diagnostics upload is local-only by default.
- Only manual user-triggered export is allowed.
- Config export is supported in MVP, import is deferred.
- Config export always excludes secrets/API keys.

## 24. Deferred Items
- Option 3 state-machine rewrite (post-MVP).
- Full custom persona prompt editing (v1.1).
- Full shortcut action-intent customization (later phase).
- Phone-side voice fallback (v1.1).
- Config import (post-MVP).
