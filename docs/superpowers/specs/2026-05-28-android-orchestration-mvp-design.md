# Android Orchestration MVP Design

Date: 2026-05-28
Project: proactive-context-agent
Status: Approved for planning

## 1. Scope
Define the phone-side orchestration MVP for proactive context processing with battery-aware and pressure-aware scheduling.

This spec covers:
- Queue model and orchestration responsibilities.
- Processing/defer rules from battery, charging, network, battery saver, and pressure signals.
- Concurrency model across ingest, summarize, read, and write flows.
- Failure handling, retry behavior, and user-visible responses.
- Testing strategy for MVP readiness.

This spec does not cover full state-machine architecture yet. Migration to explicit state machine (Option 3) is intentionally deferred by one week until after MVP completion and token refresh.

## 2. Chosen Approach
Chosen for MVP: Single Orchestrator + Priority Queues (Option 1).

Deferred:
- Option 3 (state-machine-first) migration target: June 4, 2026.

Rationale:
- Fastest path to a controllable MVP.
- Keeps policy behavior explicit and testable.
- Allows future refactor to formal state machine without changing queue semantics.

## 3. Architecture

### 3.1 Core components
- OrchestratorService: single decision point for scheduling, gating, and queue transitions.
- raw_frame_store: append-only store for incoming dedup-filtered frame packets + metadata.
- summarizerWorker: FIFO consumer from raw_frame_store; writes normalized summaries.
- summary_store: read-optimized context store used by readContext queries.
- deferred/retry queue: FIFO queue for policy/pressure/network deferrals and retriable failures.

### 3.2 Logical queues
- frameIngest queue: persists raw frames + metadata.
- writeContext queue: explicit remember/write requests.
- readContext queue: user answer/query requests.
- retryQueue: transient failures and backoff-managed retries.

### 3.3 Concurrency model
- frameIngest and writeContext can run concurrently.
- frameIngest and readContext can run concurrently.
- summarizerWorker runs in parallel by reading raw_frame_store FIFO and writing summary_store.
- readContext reads summary_store only.

Consistency contract:
- Atomic writes for ingest/summarization/writes.
- Snapshot reads for readContext.

Auto-pause policy:
- Concurrent execution is default.
- Auto-pause/throttle only under high CPU, thermal pressure, or low-battery risk.
- Pressure-triggered items are deferred into FIFO deferred queue with 24h TTL.

## 4. Policy Rules

### 4.1 Phone battery + charging
- If battery >30%: normal processing cadence allowed (every 15 minutes for scheduled processing).
- If battery <=30% and not charging: defer processing and queue for later.
- Deferred queue retention: 24-hour TTL; expire oldest beyond TTL.

### 4.2 Local model guardrail
- Local model allowed only when:
  - Device is charging, or
  - Battery >70%.

### 4.3 Network behavior
- Poor/offline network: retry later with backoff and keep queued.
- During retry: if local model is enabled in companion settings and policy allows, run local path.
- If local unavailable/not enabled: stay queued until network recovers or TTL expires.

### 4.4 Manual voice query override
- Manual readContext query can bypass normal schedule.
- Exception: if Android Battery Saver is ON, do not force immediate processing.

### 4.5 Explicit writeContext rule
- Explicit remember/write command: immediate processing.
- Exception: if Battery Saver is ON, defer to queued processing (24h TTL applies).
- writeContext derived from scheduled/batch glasses path: queued/scheduled (not immediate).

### 4.6 Glasses-side pre-filter
- Basic dedup on glasses side: only meaningfully different frames are sent to phone queue.
- Glasses policy guardrails (battery/capture/dedup) can block frame send.

## 5. Queue Semantics

### 5.1 frameIngest queue
- Purpose: persist raw frames + metadata.
- Mode: continuous intake; concurrent with others.
- Gate: default companion + Rokid battery rules apply.
- Pressure trigger: defer affected jobs to FIFO deferred queue with 24h TTL.

### 5.2 writeContext queue
- Explicit remember request: immediate unless Battery Saver blocks.
- Battery Saver block: defer with TTL.
- Scheduled/batch writes: queued and processed by policy window.

### 5.3 summarizerWorker queue
- FIFO from raw_frame_store.
- Writes summaries to summary_store.
- Failures route to retryQueue.

### 5.4 readContext queue
- FIFO with queue-status user message.
- If running read job exceeds 15 minutes, cancel as stuck and continue with next queued request.

### 5.5 retryQueue/deferred queue
- FIFO with exponential backoff for retriable errors.
- Optional local fallback if configured and policy-allowed.
- Drop/expire at 24h TTL.

## 6. Failure Taxonomy And Handling

### 6.1 Failure classes
- NetworkUnavailable: internet unavailable/poor for cloud path.
- BatterySaverBlocked: Android Battery Saver blocked immediate work.
- PolicyDeferred: battery/charging gate deferred work.
- PressureDeferred: CPU/thermal/low-battery pressure deferred work.
- JobStuck: readContext job exceeded 15-minute watchdog.
- TTLExpired: request expired after 24 hours queued.
- ConnectionError: transport/link failure between glasses and phone.
- GlassPolicyBlocked: frame blocked on glasses side (battery/dedup/capture policy).

### 6.2 Handling rules
- Retriable: NetworkUnavailable, ConnectionError, PolicyDeferred, PressureDeferred.
- Not retriable: GlassPolicyBlocked (counted as expected policy drop).
- JobStuck (readContext): cancel and continue queue.
- TTLExpired: terminate request with expiry status.

### 6.3 User-visible responses
- Queue acknowledged: "I got it. You're in queue; I'll respond shortly."
- Battery Saver block: "Power-saving mode is on. I queued this and will process it when power conditions improve."
- Local fallback active: "Network is limited, using on-device processing."
- TTL expiry: "This request expired after 24 hours without safe processing conditions."
- Stuck read canceled: "The previous request took too long, so I moved to your latest request."
- ConnectionError (companion/debug): "Glasses connection was interrupted. Retrying automatically."
- ConnectionError (voice-safe status): "I lost connection to the glasses for a moment and I'm retrying."
- GlassPolicyBlocked (companion/debug): "Frame skipped by glasses policy (battery/dedup/capture rule)."
- GlassPolicyBlocked (voice-safe status): "I skipped a frame to protect battery and avoid duplicates."

## 7. Observability
Emit reason-coded events for all defer/cancel/drop transitions.

Minimum metrics:
- Queue depth by queue type.
- Retry count and backoff stage.
- TTL expiry count.
- JobStuck cancellation count.
- Local fallback usage count.
- GlassPolicyBlocked count.
- ConnectionError frequency and reconnect latency.

## 8. Testing Plan

### 8.1 Unit tests
- Battery/charging thresholds and saver gating.
- Cloud/local/defer routing decisions.
- Dedup admission acceptance/rejection.

### 8.2 Queue behavior tests
- FIFO ordering for readContext and deferred queue.
- 15-minute stuck watchdog cancel + next-item progression.
- 24h TTL deterministic eviction.

### 8.3 Concurrency and integrity tests
- frameIngest + writeContext concurrent atomic writes.
- summarizerWorker FIFO processing while ingest continues.
- readContext snapshot stability during concurrent summary writes.

### 8.4 Failure-path tests
- Network unavailable -> backoff -> local fallback (if enabled) -> queued otherwise.
- Battery Saver defer message and queued behavior.
- ConnectionError reconnect + eventual flush.
- GlassPolicyBlocked classified as policy drop, no retries.

### 8.5 Pressure tests
- CPU/thermal/low-battery pressure triggers auto-defer to TTL queue.
- Resume processing when pressure clears.

## 9. Implementation Boundary For Next Phase
The next phase should convert this design into an implementation plan without expanding scope.

Explicitly out of scope for MVP implementation plan:
- Full finite-state machine rewrite.
- Advanced ranking/RAG beyond current summary_store read path.
- Non-essential UX polish beyond required user-visible status messages.

## 10. Deferred Follow-up
Post-MVP (target start: June 4, 2026):
- Migrate orchestration core from Option 1 to Option 3 (explicit state machine) while preserving queue contracts and policy semantics defined in this spec.
