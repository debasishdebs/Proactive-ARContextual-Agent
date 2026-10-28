# v0.9.8 slice B: Query and Nudge as canvas pipelines (addendum, 2026-10-28)

Builds on the design in `2026-10-28-v098-query-nudge-design.md` and slice A (`RagCore`). All decisions below are resolved from the code and the user rulings; none needs the user.

## Node types (all schema 2, ASYNC domain, phone side, `ai` module)
| type | in -> out | params |
|---|---|---|
| `input.utterance` (root) | - -> TEXT | none (the caller's text) |
| `embed.text` (optional) | TEXT -> VECTOR | none; descriptive: the retriever embeds per its mode (lexical needs none) |
| `trigger.summarizer_pass` (root) | - -> TRIGGER | none (today's NudgeWorker trigger) |
| `trigger.moment/scene/signal/schedule` | - -> TRIGGER | stubs for slice C: palette off, not liveable, publish refused |
| `rag.retrieve` | TEXT, VECTOR or TRIGGER -> RAG | source flags `memories photos recent history imageQuery`, windows `recentWindowMin historyWindowMin`, and every `RagParams.SCHEMA` field (a test keeps the two schemas equal). Absent = the flow's built-in value |
| `ai.prompt` (reused) | JSON/RAG... -> JSON | in a flow: `role` ANSWER (Query) or NUDGE (Nudge) and `prompt.source = live`; the draft/schema form is refused in flows (prompt text stays in Settings) |
| `gate.nudge` | JSON -> JSON | none: quiet hours, daily cap, dedupe, shown-gap; required, cannot be removed |
| `out.deliver` | JSON -> PREVIEW | `target` caller, phone, glasses, both |

New port type RAG. The Forget fence (commit time, inside AnswerService/NudgeEngine) is not a node: it runs in the shared code and cannot be edited out.

## Shape, storage, validation, hash
- A flow is an ordinary pipeline in `canvas_pipeline` / versions (same JSON, same `PipelineHash`, same freeze). No Room change.
- Kind comes from the root: `input.utterance` = Query, `trigger.*` = Nudge. Query is the chain utterance, [embed.text], rag.retrieve, ai.prompt, out.deliver. Nudge is trigger, rag.retrieve, ai.prompt, gate.nudge, out.deliver. Nothing else may be in the graph.
- `PipelineValidator` treats the flow roots as input roots (one required), reuses the edge/port/cycle/reachability/param rules, and adds `FlowPlan.shapeProblems`. Photo nodes in a flow fail the one-root rule.
- `FlowPlan.compile(Pipeline)` turns a valid flow into a small data class (sources, windows, params, target). It also refuses what is not live yet.

## Publish and live
- `CanvasPublisher.publishFlow(id, version)`: decode, validate, compile, then one `canvas_publish` row, kind `flow`, component `query` or `nudge` (existing columns), superseding the active row of that component in one transaction. Rollback of a flow row is the plain mark (like an interpreter row). The prompts/interpreter stacks ignore flow rows.
- Not gated on a test run: a flow edit changes retrieval inputs only, never prompt text, so there is no untested prompt to publish.
- Live: `FlowPlans` (DAO lookup, cached by publish id) hands `AnswerService.answerOrThrow` and `NudgeEngine.runLocked` the active plan. The same code runs for the built-in and a pipeline: the built-in is the plan constant `FlowPlan.BUILT_IN_QUERY/NUDGE`. A plan that fails to load, compile, or retrieve falls back to the built-in plan for that call (logged by class name only). Model errors are not pipeline errors and behave as today.
- Delivery: Ask always answers the caller (VoicePipeline/chat return path unchanged). Nudge keeps its verbosity-driven routing. Live accepts only `target = caller` (Query) and `both` (Nudge, today's behaviour); phone/glasses routing is slice C and publish says so.

## Default pipelines and proof
`DefaultPipelines.defaultQuery()` (`default-query`) and `defaultNudge()` (`default-nudge`) are built-ins seeded read-only. Their compiled plans are asserted equal to `BUILT_IN_QUERY/NUDGE`; the slice A goldens (`RagCoreGoldenTest`) run through the plan path and through no plan and must match the same files. Quiet hours, cap, dedupe and the Forget fence are exercised under a plan by the existing engine tests plus new plan variants.

## Editor
Palette and params come from `/api/node-types` (no JS for the nodes). The publish dialog gets one `flow` mode, shown only for flow versions (a few JS lines; the size budget is raised by exactly that amount).
