## v0.9.3-beta

Moments and Scenes. Debug-signed; no APKs are attached. Phone database is Room v12 (additive migrations; one-way: back up before every `adb install -r`, never uninstall).

**Moments, Scenes and sensors**
- Scenes (Room v11): hour windows, a cut on activity change, otherwise on a frame change (hash distance > 25% or embedding similarity < 0.75), a 5-minute cap, the cut rule recorded; rebuilt on a stored cadence; forgetting a photo hides the Scene summary at once. `input.scenes` canvas node and run page (`GET /api/scenes`), usable by Prompt and Python nodes.
- `moment_sensor` (Room v12): per-second motion features, activity and context recomputed from the imported trace with the glasses' own classifier; a second whose clock cannot be placed gets no row; trace import triggers the backfill.
- Cleanup outbox drainer for phone files (confirm-then-delete, bounded retries); glasses and helper transports are stubs (rows are kept).
- Derive step for Moment and Scene summaries and captions, OFF by default; guarded writes, kill switch re-checked per call, one admission per model call.

**Canvas**
- Photo node settings (source, since-hours, marked only, limit; defaults equal today's behaviour), palette grouped by the five families (Source, Aggregate, Process, Condition, Outcome), "Next step" wording, a built-in "Starter: capture and extract" pipeline with both halves, composite nodes phase B (Facts from frames, Critic, Save memory expand to their real stages).

**Reviews**
- Codex found 3 Important defects each in the Scene and derive work (fixed) and none in the sensor/v12 work; the canvas slices had no extra review.

**Known limitations**
- No Summary node yet, so the drawn chain Moments → Prompt → Summary → Confidence check has no starter; the derive step is off and has no settings screen; Scene text is empty until it runs.
- Sensor rows may be rare on real traces (clock tolerance 500 ms): check after the first import.
- Not yet verified in a real browser or on a worn day; glasses-side lane editing and the watch lane are planned for v0.9.4.
