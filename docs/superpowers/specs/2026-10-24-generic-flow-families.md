# The generic flow: five node families across watch, glasses and phone (2026-10-24, approved by the user)

Intent (user): the canvas is the end-to-end place for settings, configuration and tuning, so it must show BOTH sides: the glasses-side capture (which the user can tune) and the phone-side processing. Every agentic build is the same generic flow, regardless of device:

**Source -> Aggregate -> Process -> Condition -> Outcome**

Lanes are where a node runs: WATCH, GLASSES (capture), PHONE. Lanes are not families.

## Examples (from the user)
- Phone: Sensors lead to a **Moment** (Source) -> **Prompt** (Process) -> **Summary** (Process) -> **Confidence check** (Condition: aggregate, compare against a cutoff, branch trusted/unsure) -> **Outcome/Result** (memory, result).
- Watch branch: **motion sensor on watch** (Source) -> **aggregate over an interval** (Aggregate) -> **ping phone** (signal) -> phone runs a **heuristic or ML model** (Process; the tuning lives in this node) -> **if motion / else** (Condition) -> **set frequency** (Outcome, applied to the glasses capture policy).
- Glasses: **head movement** (Source) -> **threshold / guard** (Condition) -> **capture image** / **capture frequency** (Outcome).
- Captured frames and sensor data from the glasses become Moments (the link between lanes).

## Mapping of today's registry onto the families (to regroup the palette; black boxes become composite nodes, see 2026-10-24-composite-nodes-design.md)
| Family | Existing nodes | Gap |
|---|---|---|
| Source | Photo (`input.photos`), Moments (`input.moments`), Head movement, Settle, Motion from context hub, Receive signal | watch sensor lane and node; sensor features inside a Moment (later) |
| Aggregate | none as nodes (hidden inside Confidence check and the motion classifier) | window/interval aggregate nodes (3c E2); "Combine confidences" is the first (composite inner box) |
| Process | Facts from frames, Prompt, Critic, Python (experiment) | tunable heuristic/ML node; Summary as an explicit node |
| Condition | Threshold, Novelty, Guard (expression), Confidence check, Switch, Rate cap | one generic compare + branch family; Confidence check decomposed (done, composite) |
| Outcome | Capture image, Capture frequency by activity, Save memory (preview), Send signal | "Set frequency" as the outcome of a watch branch; result node |

## Rules this adds
1. Palette categories are regrouped by family (the lane stays a visible attribute: lanes strip and colour).
2. Every black-box node is a composite: collapsed amber card, double click expands to its inner flow with the real settings editable (phase order in the composite-nodes doc).
3. Defaults of every exposed setting equal today's behaviour (golden tests); saved pipelines keep working.
4. The built-in `Default capture` pipeline shows the current glasses-side behaviour; the merged multi-pipeline view (selectable pipelines drawn together, links where `Send signal` and `Receive signal` names match) is the planned way to see watch + glasses + phone as one flow.

## Build order (proposed)
1. Composite phases B (async black boxes) and C (glasses lane), then merged multi-pipeline view.
2. Photo node source and filter settings.
3. Aggregate nodes with windows (3c E2), then the tunable Process node and the watch lane.
4. Sensor features inside Moments, then Scenes as inputs.
