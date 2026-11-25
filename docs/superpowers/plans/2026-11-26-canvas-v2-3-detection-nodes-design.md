# Canvas v2.3 (detection nodes): design (2026-11-26)

Designer: Opus. Implementer: Sonnet, in the work packages below. Reviewer: Codex sol at medium effort, or Opus at low effort when the Codex quota is out.
Branch `feat/v101-ask-eval`, `android-companion/`. This is a design only. It changes no source, needs no install and needs no phone.

ROADMAP row **v2.3** (old canvas label; it ships in the **1.9.x** canvas line, see ROADMAP "Versioning"): detection nodes, tier (a) "list objects and people" prompt preset, tier (b) on-device detector (person, object); recognition of identity stays unbuilt.

The needs were first listed in `2026-11-24-canvas-v2-1-live-e2e-and-v2-2-model-nodes-design.md` section 2.3. This doc turns them into a build. Labels: VERIFIED means read in the code on this branch. UNVERIFIED means inferred or estimated.

---

## 0. What already exists (VERIFIED), and the gap

- **ONNX Runtime 1.31 is already a dependency** of `:services:ai` (`services/ai/build.gradle.kts:37`, MIT, arm64 via abiFilters). It runs the Nomic vision model and the SigLIP 2 vision tower (1.4.4, milestone V built, not wired). So tier (b) needs **no MediaPipe** and no new runtime. This replaces ruling R7 of the v2.1/v2.2 doc.
- `ExecutorKind` is `BUILTIN, EXPR, TABLE, PROMPT, EMBED, TOOL, EVENT, SIGNAL, PYTHON, DRAFT` (`NodeTypes.kt:26`). There is no detector kind and no detection node type.
- Motion detection is already built: `imu.head_motion`, `imu.settle`, `gate.novelty` (frame-change hash), `src.watch_motion`.
- Tier (a) presets need `model.vision` and `model.classify` from v2.2 slice A (designed, **not built**). Until then they can ride `ai.vision_extract` / `ai.prompt`, which exist in `Executors.kt`.
- Scene tier (b) needs the SigLIP 2 **text** tower (1.4.4 remaining work: text session, catalog/download, space registration).
- The Forget policy table (`services/memory/.../forget/lifecycle/ForgetPolicy.kt`) has a test that fails when a data family has no row (ROADMAP v1.2).

**Gap:** no detector, no detection node, no identity guard that is enforced in code rather than only by a prompt.

## 1. Goal

A pipeline can ask "how many people, which objects, what kind of place" per photo, online or fully offline, with one output shape for both tiers and **no identity of any person**.

## 2. Scope

**In:**
- Three node types: `detect.people`, `detect.objects`, `detect.scene`, each with a tier (a) and a tier (b) backend.
- Tier (b) people/objects: one COCO detector (80 classes) on the phone CPU through ONNX Runtime.
- Tier (b) scene: zero-shot SigLIP 2 image embedding against fixed scene label texts. No new model.
- Motion: palette presets only (named param sets over the existing nodes).
- `IdentityGuard`: a pure validator on every detection output.
- Use in experiment runs and v2.1 Test runs. Use in a published live pipeline only for tier (b), behind the Developer flag `detectLive` (default OFF).

**Out:**
- Any identity: face detection, face crops, face or person embeddings, names, "who is this", re-identification, tracking a person across frames, counting unique people. Each needs its own spec and threat model.
- Open-vocabulary tier (b) (SigLIP over crops), custom classes, fine-tuning.
- A detector on the glasses (compute and battery unknown).
- Storing crops. Boxes are numbers only.

## 3. Node and API shape

Common params (as v2.2): `backend: auto | online | offline`, `maxLatencyMs`, `onUnavailable: error | skip | fallback_text`. Detection adds `minScore` (default 0.4), `maxBoxes` (default 20), `keepBoxes` (default false, counts only), `classes` (subset of the 80 COCO labels, default all).

**`auto` for detection prefers the offline detector when it is installed** (no photo leaves), else online under `AUTO` privacy. This differs from v2.2's online-first `auto` (owner question 3).

| Type | In -> out | Tier (a) (online, prompt preset on `model.vision`) | Tier (b) (offline) |
|---|---|---|---|
| `detect.people` | PHOTOS -> JSON | `{people: 0..50, groups: n}`, count only | detector `person` class: count, optional boxes |
| `detect.objects` | PHOTOS -> JSON | `{objects:[{label, count}]}`, labels from the `classes` vocabulary only | detector, `{objects:[{label, count, maxScore}]}`, optional boxes |
| `detect.scene` | PHOTOS -> JSON + branch per label | `{scene: enum, confidence}` (JSON schema output) | SigLIP 2 cosine to label texts; below threshold gives `none` |

**One envelope** for both tiers: `{tier:"a"|"b", model:"<fixed resolver label>", people?, objects?, scene?, confidence?, boxes?:[{label, score, x, y, w, h}]}` with normalised coordinates. Downstream `rule.when` / `model.route` work on either tier unchanged. Tier (a) never has boxes.

**Code (new):**
- `services/ai/.../detect/Detector.kt`: port `detect(image, classes, minScore, maxBoxes): List<Det>`.
- `OnnxDetector`: one ORT session per process, created lazily, closed after 60 s idle. Pure Kotlin letterbox preprocessing, output decoder and NMS (unit-testable without ORT).
- `services/orchestration/.../canvas/detect/DetectExecutor.kt`, `ExecutorKind.DETECT`. Runs off the main thread in chunks of 10 frames.
- `IdentityGuard` (pure): drops any key matching a fixed denylist (`name, identity, who, face, celebrity, person_id, ...`) at any depth; drops object labels outside the vocabulary; counts what it dropped (inspector shows "identity fields dropped: n").
- Tier (a) prompt presets say "count people; never identify or name anyone". The prompt is layer one. The guard is the enforcing layer.

**Detector model** (UNVERIFIED, check each model card before WP2): candidates SSD MobileNet V2 COCO or EfficientDet-Lite0 (TF model zoo, Apache-2.0), or YOLOX-Nano (Apache-2.0). Ultralytics YOLO is excluded (AGPL). Converted to ONNX on the Mac at an opset ORT 1.31 runs, pinned by SHA-256, about 4-15 MB. Delivered through the 1.4.4 model catalog: one download with consent, Wi-Fi only, size shown (or bundled, owner question 2).

**API:** no new run routes. The existing `runs/estimate` / `runs` carry the nodes. The estimate gains per node: backend, "detector not downloaded", and the photo count leaving per endpoint (0 for tier (b)). The model download reuses the 1.4.4 catalog route.

## 4. Data flow

```
stored frame (never captured inside a pause zone)
  -> estimate: effective backend per node, photos leaving, detector present?
  -> DetectExecutor
       offline: photo file -> decode + letterbox -> ORT session -> NMS -> class filter
       online : RunContext -> resolver CANVAS_VISION -> ModelRouter (confirmToken, data saver, liveness guard) -> schema check
  -> IdentityGuard -> envelope
  -> canvas_run_item.outputJson (runs)  |  canvas_live_trace (live, detectLive ON, tier b only)
  -> downstream rule.* / route / memory.write preview
```

## 5. Privacy and threat model

Threats: (1) photos sent online, (2) person-derived data surviving a Forget, (3) identity creep through prompts or free-text labels, (4) bystander counts in public places.

- **Forget coverage.** Detection outputs live only in `canvas_run_item.outputJson` and `canvas_live_trace`, both keyed by frame id and deleted by `CanvasDao.deleteCanvasOutputsForFrames`, mark undo (`deleteRunRowsOfLabels`), the run prune and Delete all. No new table and no new data family. A test asserts no detection value is written anywhere else. The model file is not personal data and stays after Delete all.
- **Offline-only mode.** Tier (a) is refused at estimate (`offline_only`) and again at call time (two layers). `auto` resolves to tier (b). The detector download is remote reach, so it is refused under Offline only with "switch to Automatic to download".
- **Pause zones fail closed.** No frame exists from inside a zone. A frame whose zone status is UNKNOWN is refused, as in `SignalTryReader`. The v2.1 live tap stops on zone entry.
- **No identity recognition.** COCO has no face class. No crops, no person embeddings, no tracking. The guard runs on every output, both tiers. Changing this needs its own spec.
- **No raw photos leaving.** Tier (b) sends nothing. Tier (a) sends only through the existing per-run `confirmToken`, which names the photo count and the endpoint label.
- **Logs:** count-only fixed tokens (node type, counts, latency). Never a label, a box or an id.

## 6. Failure modes

| Failure | Behaviour |
|---|---|
| Detector not downloaded | estimate says so; item error `detector_missing`, or `skip` per `onUnavailable` |
| SHA-256 mismatch | file deleted, `detector_bad_file`, never loaded |
| ORT load fails (OOM, unsupported op) | `detector_load_failed`; session closed; other nodes continue |
| Over `maxLatencyMs` | item error `slow`; run continues |
| Online output not in schema | `bad_output` |
| Guard dropped fields | not an error; counted in the inspector |
| Blur or low light | low scores, counts may be 0; inspector shows `maxScore`, never a guess |
| Heat on a 50-photo run | chunked with a yield between chunks; measured on the device (WP8) |

## 7. Test plan

**JVM (no device):** letterbox and NMS against hand-computed cases; output decoder over synthetic tensors; `IdentityGuard` table tests (name-like keys at any depth, labels outside the vocabulary); envelope parity tier (a) vs (b); estimate refusals (`offline_only`, `detector_missing`); `auto` choice table; Forget cascade (frame Forget deletes detection rows); logs count-only. An env-gated ORT desktop test (as the SigLIP parity test) runs the real model on a local image kept outside git. No real photos in git.

**Device (needs the user's phone):** latency per photo and memory (x86 emulator timings mean nothing), APK size delta if bundled, heat over 50 photos, one live run with `detectLive` on glasses frames.

## 8. Work packages (agent hours, UNVERIFIED)

| WP | Content | Hours | Depends on | Device? |
|---|---|---|---|---|
| WP1 | `Detector` port, `OnnxDetector`, letterbox, decoder, NMS, JVM tests | 4 | none | No |
| WP2 | model pick, licence record, ONNX conversion, SHA-256 pin, catalog entry | 3 | 1.4.4 catalog/download | No |
| WP3 | node types, `DetectExecutor`, params, estimate, `IdentityGuard` | 4 | WP1 | No |
| WP4 | tier (a) presets and schemas on `model.vision` | 2 | v2.2 slice A | No |
| WP5 | scene tier (b) zero-shot | 2.5 | 1.4.4 text tower | No |
| WP6 | inspector box overlay (SVG, `h()` only), motion presets | 2 | v2.1 P2 | No |
| WP7 | live use behind `detectLive` | 3 | WP3 | Yes |
| WP8 | device session: latency, size, heat | 1.5 + user | WP3, WP7 | Yes |
| Review | one Codex pass at the end | 1 | all | No |
| **Total** | | **~23 h** | | |

Order: WP1 -> WP3 -> WP2 -> WP6 -> WP4 -> WP5 -> WP7 -> WP8. WP1-WP3 can run in parallel worktrees with v2.2 slice A.

## 9. Owner questions (yes/no)

1. Tier (b) runs on the ONNX Runtime already in the app, with no MediaPipe dependency?
2. The detector model is downloaded once with consent (Wi-Fi, pinned), not bundled in the APK?
3. For detection nodes only, `auto` prefers the offline detector when it is installed?
4. People counts are allowed in public places (Sri Lanka), and person boxes may stay in run rows until Forget or prune (carries v2.2 R6)?
5. Tier (b) detection may run in a published live pipeline behind `detectLive` (default OFF)?
6. Tier (a) object labels are limited to the fixed COCO vocabulary (no open vocabulary in v2.3)?
