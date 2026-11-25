# Offline caption-match test: pack PoI text and averaged references (2026-11-26)

Mac-only analysis. No phone, no device. Captions: SmolVLM2-500M, the 25 frames of the spike (7 places x 3 frames, 4 "none" in-car frames). Public model downloads only, nothing stored in the repo; no coordinates in this doc.

## Method
1. Captions come from the spike log (`answer[...]` lines). "strip" = the opening "The photo was taken in/from ..." removed.
2. Embedders: bge-small-en-v1.5 quantized (CLS pooling, L2) and nomic-embed-text-v1.5 quantized (mean pooling, L2, prefixes `search_query: ` for captions, `search_document: ` for PoI text).
3. Part 1 data gap: the pack lives in the phone's encrypted `places.db` (SQLCipher). The 2026-11-26 phone pull has no copy of it and the Mac copy does not open, so the pins' real positions and the real pack rows are NOT available here.
4. Stand-in used: the public Wikipedia pages of the landmarks (Salar Jung Museum for places 02-05, Golconda for 06) and Wikipedia geosearch around them. PoI text = title, short description, first 300 chars of the intro (the same text shape as the pack: name, kind, summary). OSM rows are NOT included, so the real pack will have more (and more confusable) neighbours.
5. Radius: 150 m around the landmark returned NO neighbours (only the landmark itself, which is the true place). So at 150 m the candidate set is one place and ranking is trivial. The tested band is 500 m: Salar Jung plus 6 neighbours (library, waterpark, ashurkhana, a palace ruin, a neighbourhood, a development authority); Golconda plus 1 neighbour (Jama Mosque). A harder "city" pool of 13 adds Charminar, Chowmahalla, Qutb Shahi tombs and Mecca Masjid.
6. Place 01 (home) and place 07 (Antera): no pack PoI known, so their 4 probes have no true place among the nearby PoIs. Only 10 of 14 probes (places 02-06) can be scored in Part 1.
7. Text forms tried: name; name + description (kind); name + kind + 300-char summary. Query = the photo caption.
8. Part 2: reference per place = mean of the L2-normalised caption embeddings, re-normalised. Leave-one-out over all 21 place frames: the held-out frame is the probe, its own place uses the mean of the other 2 captions, other places use the mean of their 3. Baseline "single" = one caption only (a_ref; p1 when the held-out frame is the ref). Top-1 of 7 over all places; best-of-3 inside the 3-candidate sets from `jobs-truth` (a_ref probes use the set of that place's p1).
9. Captions embedded with the query prefix for Nomic on both sides in Part 2 (symmetric).
10. Scripts and logs are in the scratchpad only. n is tiny throughout; counts, not rates, are what matter.

## Part 1 results (10 scorable probes, 4 none frames)
Top-1 / top-3 are among the 8 (Salar) or 2 (Golconda) nearby PoIs at 500 m; "city" = 13-PoI pool, top-1.

| Embedder | Caption | PoI text | Top-1 nearby | Top-3 nearby | Top-1 city(13) |
|---|---|---|---|---|---|
| bge-small | raw | name | 9/10 | 10/10 | 7/10 |
| bge-small | raw | name+kind | 5/10 | 10/10 | 4/10 |
| bge-small | raw | name+kind+summary | 5/10 | 10/10 | 5/10 |
| bge-small | strip | name | 9/10 | 10/10 | 5/10 |
| bge-small | strip | name+kind+summary | 4/10 | 9/10 | 4/10 |
| nomic-text | raw | name | 8/10 | 10/10 | 3/10 |
| nomic-text | raw | name+kind | 7/10 | 10/10 | 3/10 |
| nomic-text | strip | name | 9/10 | 10/10 | 3/10 |
| nomic-text | strip | name+kind+summary | 8/10 | 10/10 | 4/10 |

Top-1 of 9/10 is mostly a shortcut: the neighbours are a waterpark, a library, a neighbourhood, so any "museum" caption picks the museum. Against the 13-PoI pool (other monuments, mosques, palaces) it falls to 3-7/10. Probes with a true place among the nearby PoIs at all: 10 of 14 (71%).

Similarity (bge-small, raw caption, name-only text), true place vs none frames:

| Group | n | values |
|---|---|---|
| true place | 10 | 0.38 0.46 0.47 0.48 0.49 0.55 0.60 0.60 0.61 0.64 |
| none frames (best PoI) | 4 | 0.48 0.48 0.50 0.53 |

Nomic (raw, name+summary): true 0.53 to 0.67 (median 0.60), none 0.55 to 0.65; also overlapping. The none frames are in-car photos whose captions say "museum gallery" or "building", so they match museum texts as strongly as real probes. No cut-off separates them.

## Part 2 results (21 frames, leave-one-out)

| Embedder | Captions | Top-1 of 7 single | Top-1 of 7 averaged | Best-of-3 single | Best-of-3 averaged |
|---|---|---|---|---|---|
| bge-small | raw | 9/21 | 11/21 | 12/21 | 12/21 |
| bge-small | strip | 9/21 | 10/21 | 13/21 | 12/21 |
| nomic-text | raw | 10/21 | 8/21 | 12/21 | 12/21 |
| nomic-text | strip | 11/21 | 9/21 | 15/21 | 13/21 |

Averaging 2 captions helps bge-small a little on top-1 (+1 to +2 of 21), is neutral to negative for best-of-3 and hurts Nomic. All differences are 1 to 2 frames of 21: within noise. Stripping the opening helps Nomic slightly, bge-small not at all.

## Conclusion
- Pack-text matching does not work as a reliable identifier. The caption is a generic scene description ("museum gallery", "living room"); it carries the kind of place, not which one. It can separate a museum from a waterpark, not Salar Jung from another palace or museum.
- It cannot reject a wrong place: in-car "none" frames score as high as real probes against museum text. So it must never produce an accepted place, only a hedged "Maybe <place>" and only when the candidate list is already short.
- Places 02-05 (four galleries of one museum) and 06 (Golconda) get the museum or fort name right when the neighbours are dissimilar; places 01 (home) and 07 have no pack PoI and cannot be served by this at all. Averaged references do not change that: at best bge-small gains 1 to 2 frames of 21.
- Top-1 of 9/10 here is optimistic (weak neighbours, Wikipedia-only pool, no OSM shops).

## Recommendation
- Embedder: bge-small is good enough. Nomic text is not materially better here (nomic 8-9/10 vs bge 9/10 on easy pools, worse on the 13-PoI pool, 3-4/10 vs 4-7/10) and costs about 137 MB more than bge's quantized file. Use bge-small, name-only (or name+kind) PoI text, raw caption; skip the 300-char summary (it lowered bge top-1 to 4-5/10).
- Cut-off for the "Maybe" tier: 0.55 cosine (bge-small, name-only). In this tiny set it keeps 5 of 10 true probes and 0 of 4 none frames; 0.50 keeps 5/10 and 1/4. Treat as a first guess, not a measured threshold.
- Candidate band: medium-score embedding places plus seeded PoIs within 150 m (empty here, so it effectively means "the place itself plus any other medium-score places"); allow up to 500 m only if the list stays at 5 or fewer; show at most one "Maybe" and only if the margin to the runner-up is at least 0.03 (our top-1 vs top-2 gaps were not measured; this is a safety guess to confirm).
- Keep averaged references as optional (a free +1 to +2 of 21 for bge-small, no cost); not a reason to build anything.

## Limits
- Real pack rows and real pin positions were unavailable; stand-in is Wikipedia landmarks, so Part 1 proves nothing about OSM-heavy surroundings, home or Antera.
- n = 10 probes, 4 none frames, 21 frames for Part 2; a single frame moves a result by 5 to 10 points.
- The four Salar Jung probes are the same building, so they are not four independent places; the none frames are all in-car.
- The caption comes from a 500M model at 480x640 with one prompt; a different prompt that asks for readable signs or text could change everything and was not tested.
