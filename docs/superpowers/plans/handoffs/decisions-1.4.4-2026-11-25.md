# 1.4.4 semantic ranking: SigLIP 2 shadow embedder and calibration harness (2026-11-25)

Branch `feat/v101-ask-eval`. Shadow only: nothing here changes a card, a model call, a log line of the first shadow, or anything the user sees. No model was downloaded and nothing ran on a device.

## What was built

1. **SigLIP 2 as a second shadow embedder** next to Nomic.
   - Developer flag `placeShadowSiglip` (`DeveloperFlags.setPlaceShadowSiglip`), default OFF, no UI. It rides on the existing `placeShadowScore` flag, so BOTH must be ON.
   - `SiglipShadowCompare` (orchestration, `work/frames/place`) wraps the Nomic and SigLIP `PlaceShadow` scorers. Nomic's outcome is returned untouched and at its own pace; SigLIP runs beside it on its own scope with its own 20 s budget and only feeds ONE extra line.
   - The line: `place shadow2 v=1 thrV=0 cand=N nomic=ok|na|err sig=ok|na|err|timeout|empty chN= chS= top1Same= top3Overlap= topN= scoreN= scoreS= marginN= marginS= bandN= bandS= msN= msS=`. Status words, buckets, booleans, counts and durations only: no id, name, coordinate, text or raw score. A test pins this.
   - `SigLipBundle` (ai module) opens the towers from `files/models/` and checks exact file sizes. No catalog entry, no download worker, no network class: inference is local ONNX Runtime, so Offline only changes nothing. The model resolver (1.4.3) is untouched.
   - SigLIP has its own pin-reference and pack-text caches (different space id, never mixed with Nomic's). They register into the same `ShadowCaches` items, so every Forget path and Delete all empties both.
2. **Calibration harness**
   - Pure maths `MatchCalibration` (memory module): per space, the distribution of same-place and other-place scores (n, min, p10, p50, p90, max, mean), the best threshold by F1, its precision and recall, `acceptAtPrecision98` and `rejectBelow`.
   - Real-data runner `MatchCalibrationRealDataTest` (ai module tests, skipped unless `CALIB_DIR` is set; refuses a folder or report inside a git tree; report holds counts and milli scores only).
3. **Thresholds** `MatchThresholds` (memory): named constants per space and channel (`NOMIC_IMAGE_ACCEPT_MILLI`, `SIGLIP2_TEXT_REJECT_MILLI`, ...), `VERSION = 0`. Default NOT SET: accept = `Int.MAX_VALUE`, reject = `Int.MIN_VALUE`, so `band()` is always `borderline` and nothing can skip the model. Set them from the calibration report and bump `VERSION`.
4. **Tests (synthetic vectors, no real data):** `MatchCalibrationTest` 7, `MatchThresholdsTest` 3, `SiglipShadowCompareTest` 8, `SigLipBundleTest` 3, `CalibrationFolderTest` 2, plus the env-gated real-data test (1, skipped).

## Files to sideload (the only manual step)

Put these into the app's `files/models/` folder (same place the Local models page uses), with exactly these names and sizes:

| File name | Source (pinned in the code) | Size |
|---|---|---|
| `siglip2-base-p16-224.vision_model_quantized.onnx` | `SigLipImageEmbedder.MODEL_URL`, sha256 `SigLipImageEmbedder.MODEL_SHA256` | 94,553,333 B |
| `siglip2-base-p16-224.text_model_quantized.onnx` (optional) | `SigLipTextEmbedder.MODEL_URL`, sha256 `SigLipTextEmbedder.MODEL_SHA256` | 283,438,275 B |
| `siglip2-base-p16-224.tokenizer.model` (with the text file) | onnx-community repo, commit `ba1f3b0` (URL in the KDoc of `SigLipImageTextEmbedder`); sha256 still to be recorded by a human | 4,241,003 B |

The vision file alone is enough for the image channel (pins with an attached Moment). Without the text pair, pack places have no SigLIP text channel (`sig` then scores the image channel only or logs `na`). Resident memory with both towers is roughly 380 MB plus ORT arenas (UNVERIFIED on device); the towers are not released after use (closing an ORT session under a running inference is unsafe).

## How to run the calibration on Saturday

1. Build the ground-truth folder OUTSIDE the repo, from the 9 attached pins:
   ```
   calib/
     <pin 1 folder>/ref1.jpg          the photo of the Moment attached to the pin
     <pin 1 folder>/anything.jpg      photos taken AT that pin (the more the better, at least 2 per pin; other Moments from the same place work)
     ...
     _none/*.jpg                      photos where NO pin is right (street, indoors, other places): these measure false cards
   ```
   File names starting with `ref` are the reference; everything else is a probe whose right answer is that pin. Folder names are never printed or written to the report.
2. Get the three model files on the Mac (the vision files are enough) and the desktop ONNX Runtime jar (`ORT_DESKTOP_JAR`, `onnxruntime-1.31.0.jar`; the Nomic vision ONNX is `nomic-embed-vision-v1.5.model_quantized.onnx`).
3. Run (one Gradle job):
   ```
   CALIB_DIR=~/Desktop/rokid/backups/calib NOMIC_VISION_ONNX=... SIGLIP_VISION_ONNX=... ORT_DESKTOP_JAR=... \
     tools/dev/run_gradle_locked.sh $PWD /tmp/calib.log :services:ai:testDebugUnitTest --tests '*MatchCalibrationRealDataTest*'
   ```
   The report lands next to the folder as `calibration-report.txt` (or `CALIB_OUT`). Read `bestThreshold`, `precision`, `recall`, `acceptAtPrecision98`, `rejectBelow` and `separation(p10 same - p90 other)` per space; the space with the larger separation and the better recall at the same precision wins.
4. Put the winner's numbers into `MatchThresholds` and bump `VERSION`.
5. On the phone (later): flip `placeShadowScore` and `placeShadowSiglip` ON in Developer mode, sideload the files, walk past the pins and read the `place shadow2` lines (`top1Same`, buckets).

Limits of the harness: it calibrates the IMAGE channel only (pin reference photo against dwell photo). The text channel (pack place names against the photo) needs the on-device text towers, so its distribution comes from the `place shadow2` log buckets, not from this run. The Nomic and SigLIP preprocessing run on the JVM (ImageIO, no EXIF rotation, same as the phone's decoder).

## Deviations and honest notes

- The ROADMAP says slices A and B1 are "in the staged train"; their code (PlaceShadow, PinReferenceVectors, CandidateScorer) is on this branch and was reused as it is. Nothing in `PlaceSampler.kt` changed: the SigLIP side hooks in through the existing `shadow` lambda in `PlaceSamplers`.
- Thresholds are deliberately unset, not guessed. Raw SigLIP image-to-text cosines are on a very different scale from Nomic's, so no shared default would be honest.
- The harness's decision view treats "the top-1 pin is shown when its score is at least t" (no margin rule yet); `MIN_ACCEPT_MARGIN_MILLI` is 0.
- Real ONNX parity of the new wiring was not run (no model files, no desktop ORT jar here); the existing SigLIP parity tests cover the towers.

## Questions for the owner (yes / no)

1. Is it fine that the SigLIP shadow needs the sideloaded files (no in-app download) until the numbers show it beats Nomic?
2. Is about 380 MB of resident ONNX memory acceptable on the phone while the SigLIP flag is ON (Developer, off by default)?
3. Can you build the calibration folder (9 pins, 2+ probes each, 10+ `_none` photos) on Saturday?
4. Should an ACCEPT also require a margin over the runner-up (a first guess of 20 milli), decided after the first report?
5. Should the target precision for "accept without the model" stay at 98 percent (the plan proposed a 2 percent false-card rate)?
6. Is it OK to record the tokenizer sha256 from your sideloaded copy and add the Google/Apache-2.0 NOTICE entry before any download path is added?
7. Do you want a second calibration pass for the text channel from the on-phone `place shadow2` buckets after one real walk?
