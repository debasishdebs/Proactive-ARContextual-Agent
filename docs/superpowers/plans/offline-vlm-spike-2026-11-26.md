
## Addendum 26 Nov (night): place identification tests on real pin frames
Frames: 25 real pin photos (7 places: a reference and two probes each) plus 4 "none" frames. Phone S25, 6 threads. Moments and marks were NOT included (marks have no pin to score against; no labelled Moment set was built).

| Test | Model | Result |
|---|---|---|
| Closed list, "answer with the number" (8 options) | SmolVLM2-500M | 3-4/25 (about chance). It answered "5" almost every time at every resolution (640, 384, 256) and 4/6 threads. Resolution does not change latency (about 4.4 s at 6 threads, 6.9 s at 4 threads). |
| Free description, two sentences | SmolVLM2-500M | Coarse kind of place is right: home 3/3, Golconda night show 3/3, Antera restaurant 2/3, Salar Jung = "museum gallery" but the four galleries are indistinguishable. Hallucinated sign text ("O'Hare", "Thai Buddha Temple"). 5.3 s median. |
| Pick 1 of 3 candidate IMAGES, then the query | 500M | 5/18, always "3" (position bias); all 4 none frames wrongly matched. About 20 s per query. |
| Yes/No per pair (54 pairs: reference + probe) | 500M | 0 of 13 same-place pairs said Yes; always "No". 7.7 s per pair. |
| Yes/No per pair | 2.2B (Q8_0, 2.5 GB incl. projector) | 12 pairs run then stopped: every one "No". 42-47 s per pair. |
| Describe both then reason then verdict | 500M / 2.2B | Both see both images. The 2.2B reasoning is grounded but literal: it called two views of one room "different" because objects differed; its verdict sometimes contradicted its own text. |
| Same, with "same place = shared walls, floor, lighting, decor; ignore people and objects" | 2.2B | Flipped to "same" for ALL pairs including different places. The prompt steers the answer; the model does not discriminate. |
| Caption each image, then TF-IDF cosine between captions (Mac, no phone; 500M captions) | text only | Top-1 of 7 references 5/14 (chance 2/14); best of 3 candidates 7/14. Works for distinct places (home 2/2, Golconda 2/2, Antera 1/2); fails among the Salar Jung galleries. None frames score 0.02-0.05 vs a true-pair median 0.12. A real text embedder would likely do better than TF-IDF (untested). |

### Decisions (spike closed for the Sikkim build)
1. Neither SmolVLM2-500M nor 2.2B can compare two photos. Do not use a VLM as the place matcher or as an image-pair judge.
2. The offline vision model is at most a coarse describer ("home", "museum", "fort at night", "restaurant"), usable as a tie-break to narrow GPS candidates before the embedding match. The embedding centroid (Nomic) stays the matcher.
3. Pack-place images stay cancelled; this result gives no reason to revive them.
4. Nothing from the spike goes into the Sikkim build. Offline VLM integration stays in 1.10 (post-trip).
5. Post-trip options if wanted: caption each Moment/pin once (stored text), compare with a real text embedder; or per-candidate yes/no scored on token probabilities (needs a native change); or a larger or comparison-trained model. All are untested.
Phone state after tests: spike app installed; 2.2B in models/, 500M in models-500m/ (app files dir); 25 test frames removed.
