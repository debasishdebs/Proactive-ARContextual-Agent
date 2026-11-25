# 1.4.4 calibration on the owner's real pins and Moments (image channel), 26 Nov 2026

Counts and milli scores only. No coordinates, names, ids or file names. Harness `MatchCalibrationRealDataTest` (unchanged), desktop ONNX Runtime 1.31.0, Nomic vision v1.5 (quantized) and SigLIP 2 base p16-224 (quantized vision), one run. Source: the phone pull of 26 Nov (read-only). Thresholds in `MatchThresholds` are NOT changed (owner decides).

## What the data was

- All 9 attached pins resolved: each pin's `moment` id maps to a FRAME moment whose photo is in the pull. These 9 photos are the REAL references (no proxies).
- The 3 home pins (same room, a few metres apart) were merged into ONE place with 3 reference photos (max cosine over its refs in the single run). Frames carry no GPS in context.db and places.db is encrypted, so the data cannot separate them by place. Result: 7 places (3 home refs + 4 museum pins + 1 fort show + 1 other).
- Probes: other app frames within +-180 s of the pin moment (home: of the span of the 3 moments), at least 25 s apart from each other and from the refs, up to 6 per place: 42 probes with a right place (6 per place). Same-place evidence is TIME proximity only (no per-frame GPS exists); the 4 museum pins are rooms of one building visited minutes apart, so some museum probes may really show a neighbouring room (pessimistic label noise).
- `_none`: 30 frames, each within 10 min of a phone-camera GPS fix more than 1 km from every pin and more than 40 min from every pin moment, at least 25 s apart.
- Marks: only 2 exist and neither is near a pin in time, so none were used. Phone Camera photos are not in the tar (only the GPS/time CSV), so only the app's own glasses frames were used.
- Leave-one-out: the harness ALREADY does it. The centroid and medoid rows below hold the scored probe out of its own place vector (`loo-ref=...`); no harness change was needed. Note the LOO variants count 74 probes (the 2 extra home refs are also scored as members of the merged place) versus 72 in the single run.

## Results (milli = cosine x 1000)

Pairs: 462 other-place pairs per space in the single run (474 in LOO rows).

| Space | Reference | same p10 / p50 / p90 | other p10 / p50 / p90 | top-1 correct | best-F1 t | precision | recall | F1 | accept at 98% | reject below |
|---|---|---|---|---|---|---|---|---|---|---|
| Nomic | single (real attachment) | 817 / 875 / 926 | 795 / 840 / 879 | 19 of 42 | 852 | 31.1% | 45.2% | 36.9% | 945 | 852 |
| Nomic | single, LOO set | 812 / 867 / 926 | 793 / 837 / 875 | 18 of 44 | 852 | 29.5% | 40.9% | 34.3% | 945 | 852 |
| Nomic | centroid LOO | 887 / 927 / 955 | 860 / 894 / 920 | 29 of 44 | 914 | 49.1% | 59.1% | 53.6% | 967 | 879 |
| Nomic | medoid LOO | 827 / 891 / 949 | 815 / 852 / 882 | 26 of 44 | 925 | 89.5% | 38.6% | 54.0% | 943 | 839 |
| SigLIP 2 | single (real attachment) | 778 / 840 / 934 | 713 / 783 / 852 | 24 of 42 | 910 | 92.3% | 28.6% | 43.6% | 914 | 782 |
| SigLIP 2 | single, LOO set | 778 / 840 / 931 | 712 / 780 / 851 | 26 of 44 | 782 | 37.1% | 59.1% | 45.6% | 914 | 782 |
| SigLIP 2 | centroid LOO | 851 / 903 / 960 | 780 / 849 / 900 | 28 of 44 | 851 | 41.2% | 63.6% | 50.0% | 957 | 851 |
| SigLIP 2 | medoid LOO | 778 / 855 / 942 | 730 / 796 / 855 | 26 of 44 | 782 | 36.6% | 59.1% | 45.2% | 932 | 782 |

`separation` (same p10 minus other p90) is negative everywhere: Nomic -62 / -63 / -33 / -55, SigLIP 2 -74 / -73 / -49 / -77. The best-F1 points of the "single" SigLIP row (t=910, 13 shown) and the medoid Nomic row (t=925, 19 shown) are high-precision / low-recall corners; the F1 optimiser picks them because few probes clear a high bar, not because the space separates well.

## Comparison with the proxy run (25/26 Nov)

| Item | Proxy run | Real run |
|---|---|---|
| Places / probes with truth / none | 7 / 40 / 14 | 7 / 42 / 30 |
| Nomic single top-1, F1 | 20 of 40, 46.8% | 19 of 42, 36.9% |
| SigLIP single top-1, F1 | 24 of 40, 60.6% | 24 of 42, 43.6% |
| Nomic centroid top-1, F1 | 28 of 40, 63.5% | 29 of 44, 53.6% |
| SigLIP centroid top-1, F1 | 30 of 40, 69.0% | 28 of 44, 50.0% |
| Accept at 98% (single, N / S) | 946 / 917 | 945 / 914 |
| Accept at 98% (centroid, N / S) | 954 / 959 | 967 / 957 |
| Reject below (single, N / S) | 846 / 814 | 852 / 782 |
| Reject below (centroid, N / S) | 885 / 879 | 879 / 851 |

- Top-1 counts are about the same; F1 is lower, mainly because the real `_none` set is bigger (30 vs 14) and was chosen from genuinely different places, so there are more wrong cards to avoid.
- The accept-at-98 values barely moved (about 914-967): this is the stable, trustworthy number from both runs. The reject-below values moved by 6 to 32 milli and are noisier.
- SigLIP 2 still leads Nomic with a single reference (24 vs 19 correct; other-place median 783 vs 840 milli, i.e. a lower noise floor), but with a centroid the two are now level (28 vs 29 correct; Nomic F1 53.6% vs SigLIP 50.0%). The proxy run's "SigLIP centroid is best" did not repeat.
- A centroid of several photos still beats a single reference clearly in Nomic (19 to 29 correct) and mildly in SigLIP (24 to 28).

## What this means for thresholds

1. Neither space separates same-place from other-place well on real data (same p10 below other p90 everywhere), so neither can replace the vision model. The image channel is a prefilter and a hint, not a decision.
2. ACCEPT (skip the model): only a very high bar reaches 98% precision: about 915 (SigLIP single) and 945 (Nomic single); with a centroid about 957 and 967. At these values only roughly the top 10 percent of same-place scores qualify, so the model will be skipped rarely. Keep accept very conservative; consider leaving it unset until the on-phone `place shadow2` buckets confirm it.
3. REJECT (never a right top-1 below it): single 782 (SigLIP) and 852 (Nomic); centroid 851 and 879. Rejecting below these is safe on this data but the 42-probe sample is small.
4. No margin over the runner-up was measured; scores overlap heavily, so keep the margin rule (first guess 20 milli) as a later measurement.
5. Label noise caveat: probes are time-window frames (no per-frame GPS), museum rooms are close, and probes of one place are near-duplicates seconds apart. The same-place side is flattered by near-duplicates and hurt by neighbouring-room frames; treat the table as a range.

## Proposed MatchThresholds (NOT applied; VERSION stays 0)

| Constant (space, channel, reference) | Proposal | Basis |
|---|---|---|
| SIGLIP2 image accept, single ref | 915 | accept-at-98 real 914, proxy 917 |
| SIGLIP2 image accept, centroid ref | 960 | real 957, proxy 959 |
| SIGLIP2 image reject, single ref | 782 | real (proxy 814); use the lower of the two runs |
| SIGLIP2 image reject, centroid ref | 850 | real 851, proxy 879 |
| NOMIC image accept, single ref | 945 | real 945, proxy 946 |
| NOMIC image accept, centroid ref | 967 | real 967, proxy 954 |
| NOMIC image reject, single ref | 846 | proxy 846, real 852 |
| NOMIC image reject, centroid ref | 879 | real 879, proxy 885 |
| Margin over runner-up | 0 (unmeasured) | measure with the decision view first |

Recommendation: ship SigLIP 2 only if the 94.6 MB vision tower is acceptable (clearly better with a single attached reference); with several reference photos per place Nomic is as good and already on the phone. Prefer a centroid of several photos per place over one attachment. Keep accept at the 98% values above or unset; use reject only as a weak prefilter. A second pass with the owner's Camera photos at each pin (with GPS) would remove the time-window label noise.
