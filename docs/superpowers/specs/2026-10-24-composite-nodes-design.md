# Expandable composite nodes: inventory and design (phase A)

Decision (user, final): a black-box node (its behaviour is a hidden fixed rule) is drawn collapsed in a distinct colour with a small "expandable" marker. Double click, Enter, or the Expand button opens it in place into its inner flow: small light-filled boxes with white-text name pills wired together (the visual language of `exprview.js`). The node's real settings appear as editable parameters inside the box that owns them, showing their defaults. Collapse returns to the single node. Execution is unchanged by default: the outer node still executes as today and reads the exposed params; absent params mean today's behaviour. Pipeline JSON and node ids do not change.

## 1. Inventory of `NodeTypes.kt` (20 types)

Black box = a fixed rule the params do not show. Plain = params (or the node's own editor) already say everything.

| Type | Label | Lane | Class | Why |
|---|---|---|---|---|
| input.photos | Photo | async | plain | source, no rule |
| ai.vision_extract | Facts from frames | async | black box | batches frames, one model call, parses facts; only `batch`/`prompt` visible |
| gate.local_critic | Local trust check | async | black box (phase A, done) | mean confidence vs fixed 0.70 |
| ai.critic | Critic | async | black box | skips the model when no facts, fails when unbound, else asks the model |
| ai.prompt | Prompt | async | plain | role/template/schema are the behaviour |
| route.switch | Switch | async | plain | field and cases are the behaviour |
| memory.write | Save memory (preview) | async | black box | which facts/captions become rows, their length class and review flag |
| imu.head_motion | Head movement | glasses | black box | onset/release hysteresis and bout filter over gyro speed |
| imu.settle | Settle | glasses | black box | quiet-for-N-ms detector |
| gate.threshold | Threshold | glasses | plain | feature, operator, value |
| expr.guard | Guard (expression) | glasses | plain | already the boxed expression editor |
| gate.novelty | Novelty | glasses | black box | window + minimum turn decide whether the view is new |
| hub.context | Motion from context hub | phone | black box, no params | fixed fusion, nothing tunable (read-only inner flow, phase D) |
| signal.in / signal.out | Receive / Send signal | glasses / phone | plain | name and domain |
| policy.capture_rate | Capture frequency by activity | glasses | plain | the table shows every number |
| gate.rate_cap | Rate cap | glasses | black box | big-change and big-turn override of the per-minute cap |
| capture.image | Capture image | glasses | black box | perceptual-hash dedupe against the ring |
| input.moments | Moments | async | plain | source |
| py.fn | Python (experiment) | async | plain | the source text is the behaviour |

## 2. Inner flows and exposed params (defaults are the CURRENT code's)

Notation: `[box]` inner node, `->` wire, `{param = default}` exposed to the outer node's ParamSchema.

- **gate.local_critic** (phase A). `[Facts] -> [Aggregate {aggregate = mean}] -> [Compare {operator = >=}, [Cutoff {cutoff = 0.70}]] -> [Branch trusted | unsure]`. Real code: `ExtractFollowUp.locallyTrusted = facts.isEmpty() || CriticWorker(0.70).validate(id, mean(confidence)).status == TRUSTED`, and `validate` says NEEDS_REVIEW when `confidence < 0.70`, so TRUSTED when `confidence >= 0.70`. No facts means trusted without a compare (equivalent to confidence 1.0, since `ExtractFollowUp.confidence` returns 1.0 for none). Aggregates: mean (default), min, max. Operators: `>=` (default, today's direction), `>`. Cutoff range 0..1. One source of truth: `LocalTrust` constants in `:services:ai` are used by the node's ParamSchema default, the inner description and the executor.
- **ai.vision_extract**: `[Frames] -> [Batch {batch = ExtractSave default}] -> [Prompt {prompt = live}] -> [Model call] -> [Parse facts]`. Exposed: batch, prompt.
- **ai.critic**: `[Facts] -> [Any facts? no: pass] / [Model bound? no: fail] -> [Model review {prompt = live}] -> [pass | fail]`. Exposed: prompt. The two shortcuts stay fixed (read-only boxes).
- **memory.write**: `[Facts] -> [Drop blank text] -> [Row: fact, SHORT]`, `[Captions] -> [Drop blank] -> [Row: caption, LONG]`, `[Trusted flag] -> [Review OK / NEEDS_REVIEW]`. Exposed: none today (read-only first; later `lengthClass`).
- **imu.head_motion**: `[Gyro speed] -> [Onset {onsetRadS = 0.6}] -> [Release {releaseRadS = 0.3}] -> [Bridge gaps {bridgeMs = 250}] -> [Bout filter {minBoutMs = 300, minBoutRad = 0.26}]`.
- **imu.settle**: `[Motion] -> [Quiet for {settleMs = 1500}] -> [Settled event]`.
- **gate.novelty**: `[Head turn] -> [Window {noveltyWindowMs = 20000}] -> [Turn at least {noveltyMinRad = 0.3}] -> [Trigger]`.
- **gate.rate_cap**: `[Trigger] -> [Big scene change {bigChange = 22}] / [Big turn {bigTurnRad = 0.8}] -> [Either overrides the cap] -> [Trigger]`.
- **capture.image**: `[Trigger] -> [Take photo] -> [Hash] -> [Hamming distance {hamming = 10}] -> [Compare with ring {ringCompare = all}] -> [Keep | drop]`.
- **hub.context**: fixed fusion, no params; read-only inner flow.

Glasses-lane inner flows must keep the S6/publish guards and the compiled glasses plan unchanged: only `params` are read, the plan compiler is not touched.

## 3. Phase order

A: mechanism + gate.local_critic (this change). B: ai.critic, memory.write, ai.vision_extract (async, same lane as A, golden tests exist). C: capture lane (head_motion, settle, novelty, rate_cap, capture.image), one PR per node with the plan-compiler golden. D: hub.context (read-only).

## 4. Mechanism

Registry: `NodeType.inner: InnerFlow?` (null for plain nodes), served as `inner` in `/api/node-types`. `InnerFlow = nodes[{id, label, kind, op?}], wires[{from, to, port?}], exposed[{param, node}]`. `exposed.param` must name a param in the outer `ParamSchema` (checked by a unit test); `ParamSchema` stays the single description of values and ranges. Front end (`compositeview.js`): collapsed card gets class `composite` and an "expandable" marker; expand renders inner boxes and wires, exposed params as inputs inside their owning box (via `paramform.js` fields/coerce), other boxes read-only; all text through `textContent`; no network. Expanded state is view-only (not saved).
