# 1.4.4 calibration result (image channel), 25/26 Nov 2026

Counts and milli scores only. No coordinates, names or file names. Ran on the JVM (desktop ONNX Runtime 1.31.0), harness `MatchCalibrationRealDataTest`, one run, Nomic vision v1.5 (quantized) and SigLIP 2 base p16-224 (quantized vision). All model files matched the pinned size and sha256 (tokenizer: size only, sha256 not pinned yet; its hash is not used by this run).

## IMPORTANT caveat: the reference photos are PROXIES, not the owner's attachments

The post-attach `place_pins.xml` (the 9 pins with their `moment` ids, written 25 Nov 01:40) is in NO backup: the 24 Nov dump and `place_pins.xml.pre-attach-20261125` both pre-date the attachment, and every `pca-phone-pre-*` tar is earlier still. So the true pin-to-Moment mapping could not be rebuilt locally. Substitute used (from the 24 Nov phone dump, glasses frames in `photos.tar`, times from the replay bundle truth windows):

- reference = one STILL glasses frame inside that place's dwell window (nearest the earlier place-recognition anchor where one existed, else the middle one);
- probes = up to 6 other glasses frames in the same window, at least 25 s apart;
- `_none` = 14 frames from windows with no pin (a non-pinned site, a no-pin stop, a car ride).
- The phone's Camera photos are not in any local backup (only their GPS/time CSV), so they were not used.
- The 3 home pins (about 3-5 m apart, one room) are indistinguishable by place and by photo, so they were merged into ONE place. Result: 7 places, not 9. Two more weaknesses: the 4 museum pins sit in one building (hard, realistic negatives), and probes are near-duplicate frames of the same seconds-apart scene (this flatters same-place scores).
- Reference and probes are all glasses frames. Real attachments chosen by the owner may be sharper or more typical; re-run once the owner's 9 attached photos are exported (same folder format).

## Setup

7 places, 54 probes (40 with a right place, 14 with none), 378 same/other-place pairs per space. Variants:
- single: one reference photo per place (current design).
- centroid / medoid (leave-one-out): the place vector = centroid (mean of L2-normalised vectors, then L2) or medoid (most central vector) of reference + the other probes of the SAME place, with the probe being scored held out. Other places use all their vectors. Same 54 probes as single. Note the centroid/medoid variants see more photos per place than single (up to 6), so they measure what "several photos per place" buys, not a free lunch.
- The "decision view": top-1 place shown when its score >= t; precision = correct/shown, recall = correct / 40.

## Results (milli = cosine x 1000)

| Space | Reference | same p10 / p50 / p90 | other p10 / p50 / p90 | top-1 correct (of 40) | best-F1 t | precision | recall | F1 | accept at 98% | reject below |
|---|---|---|---|---|---|---|---|---|---|---|
| Nomic | single | 805 / 869 / 946 | 769 / 836 / 875 | 20 | 875 | 48.6% | 45.0% | 46.8% | 946 | 846 |
| Nomic | centroid | 889 / 934 / 962 | 854 / 896 / 925 | 28 | 900 | 60.0% | 67.5% | 63.5% | 954 | 885 |
| Nomic | medoid | 854 / 903 / 965 | 820 / 863 / 897 | 24 | 899 | 55.6% | 50.0% | 52.6% | 958 | 858 |
| SigLIP 2 | single | 815 / 888 / 919 | 741 / 812 / 865 | 24 | 887 | 76.9% | 50.0% | 60.6% | 917 | 814 |
| SigLIP 2 | centroid | 867 / 931 / 960 | 802 / 861 / 910 | 30 | 901 | 65.9% | 72.5% | 69.0% | 959 | 879 |
| SigLIP 2 | medoid | 826 / 899 / 954 | 755 / 819 / 881 | 19 | 895 | 56.3% | 45.0% | 50.0% | 954 | 834 |

(`separation` = same p10 minus other p90 is negative in all six rows: -70, -36, -43, -50, -43, -55.)

## Reading

1. Neither space separates same-place from other-place cleanly on this data: the same-place low tail sits below the other-place high tail in every row. Neither supports skipping the vision model for a meaningful share of cases at 98% precision: the accept-at-98 threshold is 917-959, above the same-place p90 for most rows, i.e. only the top few percent of same-place pairs would be auto-accepted.
2. SigLIP 2 beats Nomic with a single reference (top-1 24 vs 20, F1 60.6 vs 46.8%, other-place scores lower by about 24 milli at p50), and with a centroid (30 vs 28 correct, F1 69.0 vs 63.5%). Best overall: SigLIP 2 centroid.
3. Centroid clearly beats single for both spaces (top-1 +8 Nomic, +6 SigLIP, F1 +17 and +8 points). Medoid does not help (it is a single photo again, with a leave-one-out artefact).
4. A "reject below" cut (a score under it never belonged to a right top-1) is usable only as a weak prefilter: SigLIP single 814, centroid 879; Nomic single 846, centroid 885.
5. The numbers are pessimistic where the museum pins share a building and optimistic where near-duplicate frames repeat; treat them as a first measurement, not final thresholds. A re-run with the owner's real attachments and Camera photos would be more representative.

## Proposed thresholds (NOT applied: owner decides; `MatchThresholds` is untouched and `VERSION` stays 0)

| Constant (space, channel) | Proposal | Basis |
|---|---|---|
| SIGLIP2 image accept (single ref) | 917 | accept-at-98 on this run; very conservative, shows few cards |
| SIGLIP2 image accept (centroid ref) | 959 | accept-at-98, centroid |
| SIGLIP2 image reject (single ref) | 814 | rejectBelow |
| SIGLIP2 image reject (centroid ref) | 879 | rejectBelow |
| NOMIC image accept (single / centroid) | 946 / 954 | accept-at-98 |
| NOMIC image reject (single / centroid) | 846 / 885 | rejectBelow |
| Margin over runner-up | not measured here | needs the decision view with margin; keep 0 until measured |

Do not set accept values from a 54-probe run with proxy references; use them as a sanity range only.

## What this means for the 7 yes/no questions (`decisions-1.4.4-2026-11-25.md`)

1. Sideloaded files only, no in-app download until it beats Nomic: yes, keep. SigLIP 2 does beat Nomic here (single and centroid), so a download path becomes justified after the re-run on real attachments.
2. About 380 MB resident ONNX: the image channel needs only the 94.6 MB vision tower, so for the image channel alone memory is far smaller; the text tower (283 MB) is the cost and its value is not measured here. Owner decision, but the data does not force the full 380 MB.
3. Calibration folder (9 pins, 2+ probes, 10+ none): done today with proxies (7 places, 54 probes, 14 none). A proper pass with the owner's 9 attached photos plus Camera photos is still worth doing.
4. Margin over the runner-up: not decided by this run. With top-1 correct at 24-30 of 40 and scores that overlap, a margin rule is probably needed; measure it next (first guess 20 milli stands).
5. 98% precision for accept without the model: reachable only at 917-959 milli, which accepts few probes. Keep 98% as the target for "accept"; do not expect the model to be skipped often. Everything else stays borderline (model decides).
6. Tokenizer sha256 and NOTICE entry: sha256 of the downloaded tokenizer.model is `61a7b147390c64585d6c3543dd6fc636906c9af3865a5548f27f31aee1d4c8e2` (4,241,003 B, onnx-community commit ba1f3b0), recorded here for the owner to confirm; add the Google/Apache-2.0 NOTICE entry before any download path.
7. Second pass for the text channel from `place shadow2` buckets after one real walk: yes, this run covers only the image channel and cannot say how the text anchors behave.

Also decided by the data, for the owner: consider using a per-place cluster centre (several photos per place) as the reference instead of one photo; it was the largest single gain in this run.
