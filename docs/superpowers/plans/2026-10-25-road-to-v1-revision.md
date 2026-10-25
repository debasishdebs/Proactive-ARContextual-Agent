# Road to v1: revision of 2026-10-25 (user decisions; supersedes the v0.9.5-v1.0.0-rc1 rows of 2026-10-24-road-to-v1.md)

## Decisions (user, 2026-10-25)
1. Glasses LIVE for guards and value programs: agreed in principle; ENABLE in v1 behind the existing M1 evidence flag after the worn X7/X10 gate and M1 pass (re-read the plan's battery items LA7/LA9 first). Marked for the remainder in a new conversation.
2. Canvas must have a PLAYGROUND mode: with no memory-write node selected a run is only Run + an output dialog; with the memory node selected the user chooses 'Run and Save' or 'Run, review, then Save'. Saved results go through the normal save, review state from the confidence check, tagged as a canvas run, Forget-covered via lineage. (Today the Save memory node is a preview that writes nothing.)
3. Query (Ask) and Nudge flows must be fully editable in the canvas like the memory-build pipeline end to end (today they are Agent Flow settings): retrieval (hybrid search, weights, embedding model, top-k), answer prompt, nudge triggers, source device -> phone -> return to the caller device (glasses HUD / phone / watch), all editable.
4. Moments carry TEXT: item text = timestamp + the visible memory text (facts/captions) of its photos; Photo is the compulsory part, Text an optional part (full voice transcript is its OWN object, with its intent/summary as further separate objects; all null until audio flows exist).
5. A MARK TOUCHES CAPTURE: a mark (voice or other channel) opens the camera explicitly and keeps one photo (hold 2-4 s, a global setting, default 3 s) even when capture is paused or the battery pause is on; if it still cannot (call, camera busy) the glasses voice/display tell the user and the phone records a mark-only Moment (fallback) with the error shown. Photo attaches to the camera source, Text to the STT source. **Implemented in v0.9.6 slice C** (`handoffs/2026-10-26-v096c-handoff.md`): the exception is only in `MarkPhotoCapture`/`GlassesRuntime.bufferMarkPhoto` on the glasses and `FrameAdmission` on the phone; battery items LA7/LA9 of the slice 3a plan carry the bound.
6. Sensors, guard signals and operators must be configurable, not a fixed catalogue: user-defined derived signals (expression boxes reused as signals, e.g. activity = mean stepHz over 10 s above 1.2), editable classifier thresholds, sensor on/off and rates; devices' capabilities (mic, display, camera, IMU, watch motion) are editable across devices.
7. A router needs no new node type: Prompt (own prompt and output schema) -> Switch / Compare. Verify in the browser pass that the Prompt node can define an output schema and that the Switch can pick the JSON field.

8. EXPORT + MIGRATION (user, 2026-10-25 evening): all exports go behind the DataOpGate and never include invalid/hidden memories; the Forget dialog names the reason and when to retry (an export is running; try again shortly). When v0.9.6 ships Moments fully, a ONE-TIME migration deletes every INVALID Moment from everywhere (a Moment without a timestamp or without a live photo, both required parts; the explicit fallback Moment created when a mark could not take its photo keeps its error reason and is NOT invalid) and memories whose source frames no longer exist; the app is still debug so destructive is acceptable. Safeguards agreed to propose: a phone backup first (manual adb tar, as always), counts-only PcLog lines with the numbers deleted, a one-time marker in canvas_meta. The outbox drain deferring to a running export is accepted as is.

## Revised version train
| Version | Scope |
|---|---|
| v0.9.5 | finish hardening (rulings slice, log migration done, catch-up scheduling fix done); the Moments palette; A/B per-arm photo settings; export/Forget gate; Scene/forget settings |
| v0.9.6 | Playground vs Run-and-Save (decision 2); Moments carry text (4); marks touch capture with fallback and error (5); the mark transcript object scaffold (null); other fixes found by the wear test and browser pass |
| v0.9.7 | configurable sensors, signals, classifier thresholds and user-defined derived signals; operators extensible through the expression catalogue; enable LIVE guards/values behind the M1 flag after the worn gate (1) |
| v0.9.8 | Query and Nudge flows in the canvas, source/target devices incl. the return to the caller device; mic and display as editable device capabilities |
| v1.0.0-rc1 | freeze: full worn day, M1 on a second trace, 3b live steps, Task 38, final Codex pass, release-signed build and version bumps LAST |

## Honest cost note (agent-hours, rough)
- v0.9.6: playground 8-12; Moment text 6-10; marks touching capture (glasses code, battery policy exception, HUD/voice feedback, STT hook scaffold) 15-25; fixes 10-20.
- v0.9.7: configurable signals and classifier 20-35; LIVE enablement 8-12.
- v0.9.8: Query on canvas 15-25; Nudge on canvas 15-25; devices/capabilities 15-30.
Total about 110-190 agent-hours, plus Codex reviews and fix rounds (every glasses or deletion slice so far needed one). Credits were 88% spent on 2026-10-24 night; the user must decide which of v0.9.7 and v0.9.8 are v1 and which are v1.x.

## Open questions for the user
- Nudge flow scope today vs target (triggers, targets, return path); the mark-photo hold default 3 s; how much of device capability editing (mic, display) is v1.

## Ruling 2026-10-25 (late): 01:50 mark-only Moments
The v0.9.6 one-time migration DELETES the 2026-10-25 01:50 mark-only Moments (invalid: no photo); they are not kept with an "old, no photo" note. User also said: continue building towards v1 (v0.9.6 starts after v0.9.5 is merged, verified and APKs saved).
