# Task 5 report: wire channels, listenId, planAware, ack correlation, old-peer fixtures

Status: DONE. Commit 739b9793.
- :core:model Protocol.kt: Channels CAPTURE_PLAN, CAPTURE_PLAN_ACK, MARK, MARK_ACK, TRACE_CTL, TRACE_CHUNK, TRACE_ACK; CapturePlanMessage, CapturePlanAck, PlanMode, PlanAckState, MarkMessage, MarkAck, TraceControl, TraceChunk, TraceAck; CommandEnvelope.listenId; HudSettings.planAware. PROTOCOL_VERSION stays 1, defaults only, unknown enums coerce to the default.
- Task 4's local copies are deleted; PlanReceiver and its tests use the :core:model types (one set). The ack `caps` default is [] as pinned, so the receiver passes `caps = CAPS` on every ack it builds.
- :services:glasses-link: GlassesEvent.PlanAckReceived/MarkReceived/TraceChunkReceived, GlassesInbound decoding (only `v` gates; a mark older than the session is accepted), PlanAckGate (CAPS / MATCH / STALE, counts ackStale), PlanPeerTracker (UNKNOWN / CAPABLE / TOO_OLD after a 10 s grace with no caps ack).
- :phone Hub (no HubGraph, CanvasApi, MainActivity or DatabaseFactory change): new defaulted ctor params phoneInstall, onPlanAck, onPlanCaps, onMark, onTraceChunk; stale acks are dropped and counted (`planAckStale`, a count only in the log); `planPeer()`; StateFrameBuilder.settingsFor sets planAware = true.
- :glasses HudController: unsolicited caps ack (gen 0, no plan, caps = PlanReceiver.CAPS) on every session open via the overridable `capsAck`.
- Fixtures: core/model/src/test/resources/compat/{old_command_envelope,old_hud_settings,old_glasses_hello}.json; the old glasses peer session (hello and motion hint, no new channel) is a frozen constant in Slice3aWireTest.
Tests (named): oldGlassesNeverAcksPhoneShowsTooOld, oldPhoneHudSettingsDecodesPlanAwareFalse, capsAckOnReadyWithoutPlan, ackFromOtherInstallIgnored, ackFromOldSessionIgnored, queuedMarkOlderThanSessionAccepted (glasses-link and Hub), oldDecoderIgnoresListenId, plus round trips and version-mismatch tests.
REDs: model and glasses-link tests failed to compile before the types existed; the Hub tests likewise; mutation RED: removing the glasses caps-ack send fails capsAckOnReadyWithoutPlan.
Final runs (one at a time): :core:model, :core:capture, :services:glasses-link, :glasses (goldens), :services:ai CanonicalJsonParityTest, :phone hub.* all green.
Concerns: the gate treats an ack with gen 0, no install, no session and no reason as the caps announcement; any gen-0 ack carrying an install or session is matched like an ordinary ack. The Hub does not match the outstanding gen yet (Task 26 owns that). Marks and trace chunks are only handed to sinks (the receivers come in later tasks). `pc_trace_ctl` has the type and channel but no sender yet.

## Review fix round 1
- PlanReceiver acks to a message echo its sessionStartMs; acks the glasses originate (activated, tick, revert) carry the latest session start seen in a message (documented). Cross-module test runs receiver acks through PlanAckGate: MATCH.
- Hub marks the peer CAPABLE on any decoded plan ack; caps ack is re-sent on a hello request (before the hello), sent inside runCatching (throwing lambda or failed send is ignored, retried at the next hello request); capsAck KDoc follows S74 (Task 11 must NOT put the applied plan in it).
- Minors: deep-nesting receiver test asserts `bounds` (200 levels; the 8 KB cap and kotlinx parsing stop a 100,000-level text from reaching the validator through the receiver, the validator-level test keeps 100,000); queuedMarkOlderThanSessionAccepted fixed; stale-ack log only for the first and every 20th.
- NOTE: TRACE_CHUNK/TRACE_ACK/listenId are pre-landed beyond the core table. Inbound trace-chunk decode now drops a JSON over 90,000 + 4,096 chars (S73 cap); the full S73 bounds come with the transfer task.
- Runs: :core:model, :core:capture, :services:glasses-link, :glasses, :phone hub.* all green.
