# Task 3 report (NodeTypes v2, domains, bindings, v2 route)

Status: DONE with concerns. Scope follows Round 3: the ASYNC projection (asyncProjection, runnerUsesProjection, asyncProjectionHashEqualsSlice2Hash) is LATER per the core table and is NOT implemented.

Implemented
- ai: NodeType gains version, sinceSchema, domain, category, executor, short, palette, params (ParamSchema), cost, liveable, root, mixedIn, domainFromParams. New files ParamSchema.kt (ParamSpec/TableSpec/ColSpec, CaptureParams, ParamCheck). Ten new types (imu.head_motion, imu.settle, gate.threshold, gate.novelty, gate.rate_cap, hub.context, signal.in/out, policy.capture_rate, capture.image), bounds from spec section 8.
- S41: NodeTypes.resolve(type, schema): schema 1 resolves only slice 2 types (v1). PipelineJson decodes schema 1 and 2; schema-2 canonical form uses type@version, schema 1 canonical unchanged.
- S34: validator per rooted domain (one root each, reachable from own root, no cross-domain edge except capture.image -> input.photos), signal domain per instance ("node <id>: signal domain <d> not allowed here"), binding name once per side. input.photos is required only when there is an ASYNC half or no RT half.
- policy.capture_rate table: gap columns grouped "timer photo gap (settled/quiet/walk/still cap)", gaps 0 or 5-300, capPerMin blank or 1-4, WALKING/UNKNOWN rows refused, DRIVING nudges locked off.
- phone: nodeTypes() serves registry fields (version, domain, domainFromParams, executor, root, sinceSchema, liveable, cost, param bounds, table); SHORT/categoryOf removed.
Tests: NodeTypesV2Test (12), phone nodeTypesRouteServesV2. Updated: PipelineJsonTest wrong-schema now uses 3; nodeTypesCarryCategory limited to the 7 slice 2 types.
RED: compile-level (new symbols absent); no behavioural mutation REDs run.
Results: ai canvas.* green, orchestration canvas.* green, phone canvas.* green.
