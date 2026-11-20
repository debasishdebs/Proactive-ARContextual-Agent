# Offline VLM bake-off for PLACE_MATCH (Qwen3-VL-2B vs SmolVLM2-2.2B)

Date: 2026-11-20. Status: **INCONCLUSIVE. Harness and test set are built and verified; the Mac could not run the models without thrashing, so there is no usable latency and accuracy rests on N=4 images (Qwen3-VL-2B only). SmolVLM2 and Gemma 4 E2B produced no results.** No repo code was changed. Nothing was run on a phone.

## 1. What happened (read first)

- Mac: Apple M2, 8 GB RAM, 4 performance cores. During the run `top` showed about 7.5 GB used, 2.1 GB wired, 0.1 GB free and 5 of 6 GB swap used by other work, so a 2 GB model could not stay resident.
- Result: the same call took **17-25 s when the weights were resident and 550-930 s when they were paged out** (calls of 754 s, 924 s, 927 s, 668 s, 547 s observed). SmolVLM2 reached only 0.06 tokens/s of prompt processing and was stopped.
- Therefore: **latency numbers below are not usable** except as a "best case, resident" figure for one model, and **accuracy is N=4 for Qwen (3 present, 1 absent), 0 for the others.** Do not decide from this. The harness is ready to re-run on a quiet machine (section 6).
- Gemma 4 E2B (3.4 GB) was downloaded, never run (RAM), and its files were deleted to free disk. Its sizes matched the exploration doc exactly; sha256 NOT checked (UNVERIFIED).

## 2. Setup (VERIFIED unless noted)

- llama.cpp: Homebrew `llama.cpp` 1.0.0, build 12359 (commit b1b770d90), AppleClang, arm64. This is newer than the repo pin b11618 (@4dd6dd4b); behaviour of the pinned tree is not tested.
- Run mode: `llama-server`, CPU only (`-ngl 0 --no-mmproj-offload`), 4 threads, OpenAI-style chat endpoint, temperature 0.
- Models (commit URLs and sha256 from the exploration doc; I re-computed sha256 for the four files used and all matched):

| Model | Repo @ commit | File | Bytes | sha256 (verified) |
|---|---|---|---|---|
| Qwen3-VL-2B | Qwen/Qwen3-VL-2B-Instruct-GGUF @ 52d6c8ffea26cc873ac5ad116f8631268d7eb503 | Qwen3VL-2B-Instruct-Q4_K_M.gguf | 1107409952 | 089d75c5...a6c2ae |
| | | mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf | 445053216 | f9a68fab...f9c82 |
| SmolVLM2-2.2B | ggml-org/SmolVLM2-2.2B-Instruct-GGUF @ 1bc3c9f74ceafd4c8d4411cc9cf188bba3798f91 | SmolVLM2-2.2B-Instruct-Q4_K_M.gguf | 1112602656 | 0cf76814...c9f58 |
| | | mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf | 592523200 | ae07ea1f...dd024 |

- Prompt: the repo seed `place_match.txt` with `{recent_context}` replaced by the same data block the app builds (`BEGIN_DATA candidates`, lines `- N. name | kind`, `END_DATA candidates`). Output constrained by a GBNF grammar to `{"choice": "1".."4"|"NONE", "confidence": 0..1}` (**mode A**, the shipped JSON contract).
- **Mode B** (logprob scoring): same candidates, instruction "Reply with only the number from the list, or NONE.", `max_tokens=1`, top-12 logprobs; first-token probabilities are grouped into 1-4 / NONE and renormalised. This is the exploration doc's "score the answer token" idea.
- Test set: 24 Commons images (960 px thumbnails resized to <= 800 px; listing below). Each gets 4 numbered candidates (true + near-lookalike + 2 distractors, true position shuffled with seed 7); 8 of 24 (golconda, mecca, ramoji, kanch, galle, purifier, sofa, toycar) have the true name removed (expected NONE). Substitutions: no Hot Wheels race track on Commons (used a Hot Wheels toy display); no PS5 box (used a PS5 console photo); the Galle image is a street building inside the fort town, not the rampart; Ramoji shows the readable "RAMOJI" gate signage (easy). Household candidates use kind "appliance/furniture/toy/game console" and are only a generic image-matching test (the real Place Guide would answer NONE for them).

## 3. Results actually obtained (Qwen3-VL-2B Q4_K_M + mmproj Q8_0, N=4)

Attempt 1 (first four images of the dict order, server with 4 slots, thrashing; pick and confidence are real, times are thrash-contaminated):

| image | expected | A pick (conf) | A time | B pick (p) | B time |
|---|---|---|---|---|---|
| charminar | 3 | 3 (0.95) correct | 753.9 s | 3 (0.997) | 25.1 s |
| golconda | NONE | 4 (0.95) **false match** | 25.2 s | 4 (0.961) | 924.2 s |
| qutb | 2 (Qutb Shahi Tombs) | 1 (0.85) **wrong pick** (picked Paigah Tombs lookalike) | 24.4 s | 1 (0.659) | 18.5 s |
| chowmahalla | 3 | 3 (0.95) correct | 927.2 s | 3 (1.0) | 17.4 s |

Attempt 2 (single slot, ctx 2048; weights resident): charminar 3 (0.95) correct in **17.5 s** (A) and 21.9 s (B); the run then thrashed again (second image 667 s / 547 s) and was stopped. Raw logs: `run_qwen3vl2b_attempt1_thrash.log`, `run_qwen3vl2b_attempt2_partial.log`, `results_qwen3vl2b.csv`, `server_qwen3vl2b.log` in the scratchpad `vlm/` folder.

Headline (N=4, **not statistically meaningful**): present 2/3 correct, 1/3 wrong confident pick (0.85, lookalike tomb); absent 0/1 (false match at 0.95); format failures 0/4 (the grammar guarantees parseable JSON). Confidence was 0.95 for correct and for the false match, so the self-reported confidence did not separate right from wrong here. Mode B probability was lower for the wrong qutb pick (0.66) than for the correct ones (0.997, 1.0), but also 0.96 for the golconda false match; again N is tiny.

Token counts (Qwen3-VL-2B, one 800 px image, dynamic resolution, no cap): prompt 595 tokens for charminar, of which about 143 are text+template, so **about 452 image tokens**; 578-620 total for other images. The server warned Qwen-VL wants >= 1024 image tokens for grounding; not tested.

Best-case resident timings (from `server_qwen3vl2b.log`): prompt eval 17.6 s for 595 tokens (29.6 ms/token, about 34 tok/s), decode about 30 tok/s (16 tokens in 0.48 s); text-only 143 tokens in 1.9 s. RSS read by `ps` was only about 480-700 MB because weights are mmap'ed and partly paged out, so **resident memory is NOT measured**; expected about 1.5 GB weights + KV + buffers (UNVERIFIED). NOT representative of a phone. To estimate on-device cost: about 600 prompt tokens per call (about 450 image + 150 text), 16 generated tokens in mode A, 1 in mode B.

## 4. Per model

| | Qwen3-VL-2B | SmolVLM2-2.2B | Gemma 4 E2B |
|---|---|---|---|
| present accuracy | 2/3 (N=3) | no data | no data |
| absent accuracy | 0/1 | no data | no data |
| confident wrong picks (>= 0.70) | 2 (qutb 0.85, golconda 0.95) of 4 | no data | no data |
| format failures | 0/4 (grammar-constrained) | no data | no data |
| latency | 17.5 s resident best case, 550-930 s thrash | no data | no data |
| memory | not measured | not measured | not run |

## 5. Recommendation and calibration (opinion, thin evidence)

- No model can be picked from this data. Keep the exploration doc's order (Qwen3-VL-2B first, SmolVLM2 as the speed floor) and re-run section 6 on a quiet machine before deciding.
- Needs logprob scoring? The only signal so far: JSON-mode confidence was 0.95 on both a correct pick and a false match, so **free-generation confidence looks uncalibrated; plan on scoring the answer token (mode B)** and read its softmax over {1..4, NONE} as the confidence. Not proven at N=4.
- Calibrating the 0.70 floor: on the full 24-image set, per model, sweep the floor from 0.5 to 0.99 over mode B probabilities (`analyze.py` prints answers, correct and wrong-confident per floor). Pick the lowest floor with 0 (or <= 1 in 24) confident wrong picks, and report the fraction of correct answers lost. Lookalike cases (qutb vs Paigah, charminar vs Gulzar Houz) and absent cases are the ones that set the floor. Also test with the image token cap (256) the exploration doc proposes, and consider requiring a margin between the top two probabilities.

## 6. Exact commands (re-run on a quiet Mac, one model at a time)

Scratchpad: `/private/tmp/claude-501/-Users-debasishkanhar/5f738bb7-228a-40ad-8dd1-9f72f0041163/scratchpad`, folder `vlm/`.

```
brew install llama.cpp                      # build 12359; no cmake needed
python3 vlm/fetch.py  [skip titles...]      # Commons API -> vlm/img/*.jpg + vlm/images.csv (then: sips -Z 800 vlm/img/*.jpg)
curl -L -o <file> https://huggingface.co/<repo>/resolve/<commit>/<file>    # for the four files in section 2
mkdir scratchpad/gradle.lock                # hold only while a model is loaded; rmdir after
llama-server -m vlm/models/<llm>.gguf --mmproj vlm/models/<mmproj>.gguf -ngl 0 --no-mmproj-offload -t 4 -c 2048 -np 1 --cache-ram 0 --host 127.0.0.1 --port 8099 --no-webui
python3 vlm/bake.py <label> 8099 <server_pid>     # writes vlm/results_<label>.csv incrementally
python3 vlm/analyze.py <label>                    # accuracy, false-match, calibration table
```
`vlm/run_model.sh <label> <llm> <mmproj>` wraps lock, server and client. Note: the shipped `run_model.sh` does not release the lock when killed with TERM.

## 7. Test set and candidate lists (all public Commons images, CC BY-SA / CC BY / CC0)

| key | kind | true name | Commons file | licence | author |
|---|---|---|---|---|---|
| charminar | place | Charminar | File:Charminar Hyderabad 1.jpg | CC BY-SA 3.0 | DidierTais |
| golconda | place | Golconda Fort | File:Golconda Fort, Hyderabad (Oct 2025) - 3.jpg | CC BY-SA 4.0 | Kuldeepburjbhalaike |
| qutb | place | Qutb Shahi Tombs | File:Qutb Shahi Tombs - small tomb.jpg | CC BY-SA 3.0 | Bernard Gagnon |
| chowmahalla | place | Chowmahalla Palace | File:Chowmahalla Palace gate tower 01.jpg | CC BY-SA 3.0 | Bernard Gagnon |
| mecca | place | Mecca Masjid | File:Mecca Masjid Hyderabad.JPG | CC BY-SA 3.0 | Suraj Garg |
| falaknuma | place | Falaknuma Palace | File:Falaknuma Palace 01.jpg | CC BY-SA 3.0 | Bernard Gagnon |
| buddha | place | Hussain Sagar Buddha | File:Buddha Statue, Hussain Sagar, Hyd.JPG | CC BY-SA 4.0 | Nikhilb239 |
| ramoji | place | Ramoji Film City | File:Ramoji entrance.jpg | CC BY-SA 3.0 | Joydeep |
| rumtek | place | Rumtek Monastery | File:Rumtek Monastery 05.jpg | CC BY-SA 4.0 | Bernard Gagnon |
| batasia | place | Batasia Loop | File:Batasia Loop of Darjeeling Himalayan Railway.jpg | CC BY-SA 3.0 | Vikramjit Kakati |
| tigerhill | place | Tiger Hill sunrise view | File:Tiger Hill sunrise point, Darjeeling.jpg | CC BY-SA 3.0 | Aditya thaokar |
| kanch | place | Kanchenjunga | File:Kanchenjunga India.jpg | CC BY-SA 2.0 | Aaron Ostrovsky |
| sigiriya | place | Sigiriya | File:Sigiriya Rock Fortress View from Pidurangala Rock.jpg | CC BY-SA 4.0 | Gayomiw |
| tooth | place | Temple of the Tooth | File:Temple of the Tooth, Sitting Buddha statues.- Kandy.jpg | CC BY-SA 4.0 | Pierre André Leclercq |
| galle | place | Galle Fort | File:Galle.- Le fort Old dutch (2).JPG | CC BY-SA 4.0 | PIERRE ANDRE LECLERCQ |
| ninearch | place | Nine Arch Bridge | File:Nine arch bridge, Ella, Sri lanka.jpg | CC BY-SA 4.0 | Ishan shanuka |
| purifier | object | air purifier | File:Air Purifier (Levoit LV-H133) (49317867758).jpg | CC BY-SA 2.0 | Home Air Quality Guides |
| ps5 | object | PlayStation 5 console (closest to box) | File:PlayStation 5 and DualSense.jpg | CC BY-SA 4.0 | Osh33m |
| hotwheels | object | Hot Wheels toys (closest to race track) | File:Hot Wheels display at Safeway.jpg | CC BY-SA 4.0 | Ser Amantio di Nicolao |
| sofa | object | sofa | File:Cozy living room with a gray sofa.jpg | CC BY 2.0 | Shixart1985 |
| tv | object | television | File:Techwood flat screen television from 2010.jpg | CC BY-SA 4.0 | Percivalor |
| kettle | object | electric kettle | File:2023 Czajnik elektryczny N'OVEEN.jpg | CC BY-SA 4.0 | Jacek Halicki |
| toycar | object | toy car | File:Tiny Die-cast Model Car of the 1950s in Rear View - November 11, 2018.jpg | CC0 | Accurend |
| bookshelf | object | bookshelf | File:Part of a bookshelf containing books by ancient philosophers (1.1).jpg | CC BY-SA 4.0 | Roman Eisele |

Per-image CSV (names only): `vlm/images.csv` (image sources) and `vlm/results_qwen3vl2b.csv` (candidates, truth, picks); candidate definitions are in `vlm/bake.py` (table `T`). Authors are Commons usernames, truncated.
