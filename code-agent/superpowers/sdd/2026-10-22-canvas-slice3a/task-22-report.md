# Task 22 report (stream B, branch s3a/b, merged s3a/a 6c825388 first: clean merge)

Status: DONE with seams. Gate: `:phone:testDebugUnitTest` 1453 tests, 0 failed (one earlier run showed PromptEditorTest red once; it passed on re-run, unrelated); `:services:orchestration` new test 1/1.
New tests: PlanRoutesTest 7, PlanSenderTest 5, CanvasSlice3aRoutesTest 6, StoredReplayTracesTest 1 = 19.
Order note: production drafted before the tests, so no true RED run; instead mutation checks (all KILLED):
- remove SHADOW-only check -> debugShadowRefusesLiveAndTrial fails
- remove Developer-mode check -> debugShadowNeedsDeveloperMode fails
- PlanSender no resend on session ready -> reconcileSurvivesCanvasServerStop + aSendWithNoSession... fail
- PlanSender no persistence -> reconcileSurvivesCanvasServerStop + revertToSettings... fail

Built: PlanSender/PlanPublishPort (hub-owned, SHADOW only, durable gen in `canvas_plan_state`, re-sent per session), TraceControlPublisher (CollectSession.flow -> pc_trace_ctl), Hub `onSessionReady` hook, HubGraph wiring (phoneInstall, onPlanAck), PlanRoutes (POST /api/plans/compile, /api/plans/debug-shadow), TraceRoutes (GET /api/traces[/id], GET /api/collect, POST /api/collect/start|stop), StoredReplayTraces (ReplayTraces over canvas_traces files), CanvasExtras + CanvasApi `extras` (registers TraceRoutes, ReplayRoutes, PlanRoutes), BackendCore wiring.

Seams (no-op, TODO named): PhoneRtApplierSeam (Task 13); TraceIntakeSeam bound to Hub.onTraceChunk (Task 18); HubTraceRecorder (LA4) not wired, comment only; ReplayService thermalSevere flow not passed (TODO).
Concerns: PlanSender has no PlanPeer TOO_OLD gate or ack-timeout handling (later publish task); Delete all wiping of canvas_plan_state/canvas_collect prefs not verified by me; debug-shadow gen is shared with the real publish path's future gen counter.
