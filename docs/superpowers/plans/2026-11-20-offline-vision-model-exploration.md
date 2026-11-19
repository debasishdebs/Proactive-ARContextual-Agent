# Offline vision model exploration (Place Guide PLACE_MATCH, later "list objects and people")

Date: 2026-11-20. Status: RESEARCH ONLY. No code, no Gradle, no device run. Branch `docs/offline-vision-model-exploration` (from `feat/v101-ask-eval`).

**The question.** Which small vision-language model (VLM) could run the Place Guide confirmation step (`PromptKey.PLACE_MATCH` -> `PlaceMatchOutput(choice, confidence)`) offline on the phone, through llama.cpp's `mtmd` library? The input is one photo plus 2-5 numbered candidates (name + kind). The output is a candidate number or `NONE`, plus a confidence. Later the same model could also answer "list the objects and people" (no names).

**Budget.** Licence Apache-2.0/MIT or similar. Disk <= ~3 GB. Resident RAM <= ~3 GB on an 8 GB mid-range phone. <= 8 s per call. Fully offline. Weights downloaded once and pinned by SHA-256.

**Legend.** VERIFIED = checked this session, in the pinned llama.cpp tree or in the Hugging Face API or model card. UNVERIFIED = my estimate, my recollection, or a third-party claim I could not confirm. No number in this doc comes from a device run.

---

## 0. TL;DR

1. **The pinned llama.cpp already supports everything we need. VERIFIED.** The submodule is b11618 @ `4dd6dd4b`, read from the main checkout's copy. I could not run `git` on it, so that the checkout equals the pin is UNVERIFIED.
   - `tools/mtmd` is in the tree.
   - The tree has the projector types `qwen3vl_merger`, `gemma4v`, `idefics3`, `internvl`, `lfm2`, `minicpmv`, `granite4_vision` and others.
   - It has the LLM archs `qwen3vl`, `qwen35`, `gemma4` and `lfm2`.
   - The root CMake has `LLAMA_BUILD_MTMD` ("build tools/mtmd library standalone"), so we do **not** need `LLAMA_BUILD_TOOLS`/`LLAMA_BUILD_COMMON`.
2. **Shortlist to benchmark first:**
   - **Qwen3-VL-2B-Instruct**, the primary pick: 1.55 GB total, Apache-2.0.
   - **Gemma 4 E2B-it**, the quality challenger: 3.40 GB total, Apache-2.0. It is 0.4 GB over the disk budget.
   - **SmolVLM2-2.2B-Instruct**, the speed and size floor: 1.71 GB, Apache-2.0.
   - **Qwen3.5-2B** is the drop-in alternate for slot 1: 1.95 GB, Apache-2.0. Its GGUF is a third-party conversion.
3. **The biggest risk is latency, not quality.**
   - The JNI `.so` is built for **baseline armv8-a (no dotprod/i8mm)**. Vision encoders plus a 2B prefill of about 300-600 tokens on that build are likely **15-40 s** per call (UNVERIFIED estimate, §3).
   - Meeting <= 8 s very likely needs three things: an `armv8.2-a+dotprod` (ideally `+i8mm`) build variant, an image-token cap (`mtmd_context_params.image_max_tokens`, present in the pinned header), and logit-scored single-token answers instead of generated JSON.
4. **Integration is about 7.5-9.5 agent-days to a benchmarkable build (central about 8.5), then one device session with the user.** The download and checksum layer already exists (`LocalModels` / `ModelFile` with a pinned commit URL plus `sha256`, and `ModelFileVerifier`). Most of the new work is in the native layer.
5. **This does NOT fit before the 26 Nov install freeze** (UNVERIFIED that the freeze still stands; it is from memory notes). Charminar and Golconda (22-24 Nov) should stay on the online vision role. Sikkim (1-6 Dec) is the earliest realistic field use, and only if the benchmark lands by about 28 Nov.

---

## 1. Comparison table

Sizes are the exact Hugging Face file sizes from `api/models/<repo>/tree/main` (VERIFIED 2026-11-20). "Total" = LLM quant + mmproj. The RAM and latency columns are **estimates** (UNVERIFIED). §3 gives the method. Confidence: L = low, M = medium.

| Model (GGUF repo) | Params (LLM + vision) | Quant files, total on disk | Licence | mtmd in pinned tree | Est. resident RAM | Est. latency per PLACE_MATCH call, 8 GB mid-range phone (baseline armv8-a / dotprod+i8mm build) | Strengths | Weaknesses for monuments / objects / Indic signage |
|---|---|---|---|---|---|---|---|---|
| **Qwen3-VL-2B-Instruct** (`Qwen/Qwen3-VL-2B-Instruct-GGUF`, official) | ~1.7B + ~0.4B ViT (UNVERIFIED split); mmproj: 24 blocks, image 768, patch 16, merge 2 (VERIFIED from the GGUF header) | Q4_K_M 1.107 GB + mmproj Q8_0 0.445 GB = **1.55 GB** | Apache-2.0 (VERIFIED, HF tag) | **Yes.** `qwen3vl_merger` and the `qwen3vl` arch are in the tree (VERIFIED) | ~1.9-2.3 GB at a 256-token image cap (L) | 15-35 s / **5-10 s** at a 256-token cap (L) | Official first-party GGUF. Dynamic resolution, so the token count can be capped. Strong grounding and OCR for its size (UNVERIFIED). Same family as a common cloud vision model, so prompts carry over | 2B world knowledge of Indian monuments is thin (UNVERIFIED). Indic-script OCR quality unknown (UNVERIFIED) |
| **Qwen3.5-2B** (`unsloth/Qwen3.5-2B-GGUF`, third party) | 2.27B total (VERIFIED, HF safetensors total). Natively multimodal; mmproj 24 blocks, image 768 (VERIFIED header) | Q4_K_M 1.281 GB + mmproj F16 0.668 GB = **1.95 GB** (no Q8 mmproj in that repo) | Apache-2.0 (VERIFIED, HF tag on `Qwen/Qwen3.5-2B`) | **Probably.** `qwen3vl_merger` projector and `qwen35` arch are in the tree (VERIFIED). The end-to-end image path is untested | ~2.2-2.6 GB (L) | 15-40 s / 6-11 s (L). The hybrid linear-attention LLM (`qwen35`) CPU kernels on arm are unknown (UNVERIFIED) | Newest small Qwen (Feb 2026). Claimed very broad multilingual coverage (UNVERIFIED) | Third-party conversion: pin the unsloth commit, or wait for an official Qwen/ggml-org GGUF. Thinking-mode default must be switched off in the template (UNVERIFIED) |
| **Gemma 4 E2B-it** (`ggml-org/gemma-4-E2B-it-GGUF`) | "Effective 2B", Per-Layer Embeddings (PLE); vision encoder ~150M (from a search snippet of the model card, UNVERIFIED); mmproj 16 blocks, image 224 base (VERIFIED header); also has an audio tower | Q4_0 2.841 GB + mmproj Q8_0 0.557 GB = **3.40 GB** (over the ~3 GB budget) | Apache-2.0 (VERIFIED, HF tag on `google/gemma-4-E2B-it`) | **Yes.** `gemma4v` and the `gemma4` arch (VERIFIED) | ~2.0-2.8 GB; PLE tables may stay mmap'ed and paged (L) | 12-30 s / 5-9 s (L). Small 150M vision encoder; prefill cost depends on the token budget it uses (UNVERIFIED) | Light vision encoder. Google's multilingual coverage (claimed ~140 languages, UNVERIFIED). Likely the best world knowledge of the small set (UNVERIFIED) | Largest download. The mmproj carries an audio tower we would not use (wasted disk). ggml-org repo re-uploaded 2026-11-12 (VERIFIED lastModified), so pin the commit |
| **SmolVLM2-2.2B-Instruct** (`ggml-org/SmolVLM2-2.2B-Instruct-GGUF`) | ~1.7B SmolLM2 + SigLIP ~400M (UNVERIFIED split) | Q4_K_M 1.113 GB + mmproj Q8_0 0.593 GB = **1.71 GB** | Apache-2.0 (VERIFIED, HF tag) | **Yes.** `idefics3` (VERIFIED). Listed in llama.cpp `docs/multimodal.md` (VERIFIED) | ~1.8-2.2 GB (M) | 10-25 s / **4-8 s** with tiling off, about 81 image tokens per 384 px tile (L) | Fewest image tokens, so fastest. Most published phone runs (one report: ~3.4 s optimised vs ~8 s baseline CPU, from a search snippet, device and quant unknown, UNVERIFIED) | English-centric. Weak non-Latin OCR (UNVERIFIED, likely). Weakest landmark knowledge of the shortlist (UNVERIFIED) |
| InternVL3-2B (`ggml-org/InternVL3-2B-Instruct-GGUF`) | ~1.5B Qwen2.5 + InternViT-300M (UNVERIFIED) | Q4_K_M 1.117 GB + mmproj Q8_0 0.337 GB = 1.45 GB | Apache-2.0 per HF tag (VERIFIED tag). The model card says MIT plus Qwen2.5 Apache (UNVERIFIED) | Yes. `internvl` (VERIFIED) | ~1.8-2.2 GB (M) | 15-30 s / 5-10 s (L). 448 px tiles of 256 tokens each, so turn tiling off | Good OCR and documents for its size (UNVERIFIED) | April 2025, older. Superseded by Qwen3-VL on most benchmarks (UNVERIFIED) |
| LFM2.5-VL-1.6B (`LiquidAI/LFM2.5-VL-1.6B-GGUF`) | 1.6B incl. vision (UNVERIFIED) | Q4_K_M 0.731 GB + mmproj Q8_0 0.583 GB = 1.31 GB | **LFM Open License v1.0** (VERIFIED `license_name: lfm1.0`). Apache-based with commercial-use conditions (UNVERIFIED terms) | Yes. `lfm2` projector and arch (VERIFIED) | ~1.4-1.8 GB (M) | 8-20 s / 3-7 s (L). Hybrid conv LLM built for edge | Smallest and fastest | **Licence is not plain permissive.** Needs a legal reading before it can join the shortlist |
| Qwen3-VL-4B-Instruct (`Qwen/Qwen3-VL-4B-Instruct-GGUF`) | ~4B + ~0.4B | Q4_K_M 2.497 GB + mmproj Q8_0 0.454 GB = 2.95 GB | Apache-2.0 (VERIFIED, model family tag) | Yes (VERIFIED) | ~3.2-3.6 GB (L), over budget | 30-70 s / 10-18 s (L) | Clearly better knowledge than 2B (UNVERIFIED) | Over the RAM budget, and the latency target fails even with i8mm (L) |
| Qwen3.5-4B (`unsloth/Qwen3.5-4B-GGUF`) | ~4B | Q4_K_M 2.741 GB + mmproj F16 0.672 GB = 3.41 GB | Apache-2.0 (UNVERIFIED for 4B; tag checked on 2B only) | Probably (as Qwen3.5-2B) | ~3.4-3.8 GB (L) | as Qwen3-VL-4B (L) | | Over budget |
| MiniCPM-V 4 (`openbmb/MiniCPM-V-4`, 4.1B) | SigLIP2-400M + ~3B LLM (UNVERIFIED) | GGUF sizes not fetched (UNVERIFIED; probably ~2.2 GB + ~0.9 GB) | Apache-2.0 for weights and code per model card (VERIFIED, card text). The registration questionnaire is optional | Yes. `minicpmv` resampler (VERIFIED); later `MINICPMV4_6/4_7` types also exist | ~3 GB (L) | 20-45 s / 8-14 s (L) | Strong single-image quality for size (UNVERIFIED) | Borderline on every budget. Treat it as a later candidate only |
| Moondream2 2025-04-14 (`ggml-org/moondream2-20250414-GGUF`) | ~1.9B | **f16 only** on ggml-org: 2.84 + 0.91 = 3.75 GB (VERIFIED). We would quantise it ourselves | Apache-2.0 (VERIFIED, HF tag) | Listed in `docs/multimodal.md` (VERIFIED). The projector path (legacy/llava-style) is UNVERIFIED | ~2 GB after self-quantising (L) | unknown (L) | Good captions and pointing/detection in its own runtime (UNVERIFIED) | No official quant. The newest Moondream versions are not in GGUF (UNVERIFIED). Weak at multiple choice (UNVERIFIED) |
| Granite 4.0 3B Vision (`ibm-granite/granite-4.0-3b-vision`) | ~3B | GGUF availability UNVERIFIED | Apache-2.0 (from a search snippet of the model card, UNVERIFIED) | `granite4_vision` projector (VERIFIED) | ~2.5 GB (L) | unknown | Charts, tables, documents | Built for documents, **wrong domain for monuments** |
| ~~Qwen2.5-VL-3B~~ | 3B | 1.93 + 0.845 GB (VERIFIED) | **No Apache tag on HF** (VERIFIED). Recalled as the "Qwen Research" non-commercial licence (UNVERIFIED) | Yes | | | | **Excluded: licence** |
| ~~Gemma 3 4B / Gemma 3n E2B~~ | 4B / E2B | 2.49 + 0.85 GB / n.a. | **Gemma Terms**, gated (VERIFIED `license:gemma`, `gated: manual` for 3n) | Yes (`gemma3`, `gemma3nv`) | | | | **Excluded: licence.** Gemma 4 replaces them |
| ~~PaliGemma 2~~ | 3B | | Gemma Terms (UNVERIFIED) | **No** PaliGemma projector in the tree (VERIFIED by absence in `clip-impl.h`) | | | | **Excluded** |

Mid-range baseline used for the estimates: a 2023-25 SoC with 4 big cores (Cortex-A7x class), LPDDR4X/5, CPU only. The repo uses no GPU and no NPU backend (all GPU backends OFF in `CMakeLists.txt`, VERIFIED).

Published data points I could find (none is our exact setup):
- An arXiv study on a OnePlus 13R (Snapdragon 8 Gen 2), llama.cpp CPU, of older 3B/7B VLMs: MobileVLM-3B total **22.8 s** (image encode 3.05 s, prompt eval 15.3 s, generation 6.6 s). The CPU was the bottleneck and the GPU and NPU sat idle (VERIFIED, read on arxiv.org/html/2507.08505v2). This supports the view that **prompt eval (prefill), not the encoder, dominates**.
- A Galaxy A35 (Exynos 1380, 8 GB) blog: Gemma 4 E2B decodes at **5-7 tok/s** on CPU, text only, no image timing (third-party blog, UNVERIFIED).
- Arm's learning path ships **Qwen3-VL-2B Q4_K_M + Q8_0 mmproj through llama.cpp + libmtmd + KleidiAI**, built with `armv8.6-a+dotprod+i8mm`, n_ctx 4096 and 4 threads. It publishes no numbers (VERIFIED, learn.arm.com). This is the closest reference build to what we would do.

---

## 2. Recommended shortlist (benchmark on the device in this order)

1. **Qwen3-VL-2B-Instruct, Q4_K_M + mmproj Q8_0 (1.55 GB). Primary.**
   - Apache, a first-party GGUF, a projector verified in our pinned tree, and the same exact pair that Arm's reference app runs through mtmd.
   - It is the best size/quality/licence balance, and its dynamic resolution lets us trade accuracy for latency with `image_max_tokens`.
2. **Gemma 4 E2B-it, Q4_0 + mmproj Q8_0 (3.40 GB). Quality challenger.**
   - Apache, a light vision encoder, and probably the best landmark and world knowledge in this size class.
   - It breaks the disk budget by about 0.4 GB, and part of the mmproj is an unused audio tower. Keep it only if it clearly beats #1 on monuments.
3. **SmolVLM2-2.2B-Instruct, Q4_K_M + mmproj Q8_0 (1.71 GB). Speed floor and control.**
   - If even this misses 8 s on the user's phone, CPU-only offline vision is not viable at this budget, and the answer becomes "offline = name + distance only" (the existing offline-miss fallback).

Alternate: **Qwen3.5-2B** swaps in for #1 if #1 is weak on Indic signage, because of its claimed broader language coverage (UNVERIFIED). Pin unsloth commit `f6d5376b…` or wait for an official GGUF.

Pinned artefacts (repo commit = HF `sha`, file SHA-256 = HF LFS `oid`; both VERIFIED 2026-11-20):

| Model | Repo @ commit | File | Bytes | SHA-256 |
|---|---|---|---|---|
| Qwen3-VL-2B | `Qwen/Qwen3-VL-2B-Instruct-GGUF` @ `52d6c8ffea26cc873ac5ad116f8631268d7eb503` | `Qwen3VL-2B-Instruct-Q4_K_M.gguf` | 1107409952 | `089d75c52f4b7ffc56ba998ffc50aae89fcafc755f9e7208aacca281dca6c2ae` |
| | | `mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf` | 445053216 | `f9a68fabba69c3b81e153367b2c7521030b0fa8bb0de400c9599c8e6725f9c82` |
| Gemma 4 E2B | `ggml-org/gemma-4-E2B-it-GGUF` @ `2a8538c2e2d4687953a86c422c7c623cfd022880` | `gemma-4-E2B-it-Q4_0.gguf` | 2841481184 | `8e30dff3ac4c8434c49a7036fa15564bdbb6044e42bf04550bf1a096ad7e6a52` |
| | | `mmproj-gemma-4-E2B-it-Q8_0.gguf` | 557368096 | `042cc2561806cd3b5cb5efb25cab76911e3100c43cc09d854904f921924b1429` |
| SmolVLM2-2.2B | `ggml-org/SmolVLM2-2.2B-Instruct-GGUF` @ `1bc3c9f74ceafd4c8d4411cc9cf188bba3798f91` | `SmolVLM2-2.2B-Instruct-Q4_K_M.gguf` | 1112602656 | `0cf76814555b8665149075b74ab6b5c1d428ea1d3d01c1918c12012e8d7c9f58` |
| | | `mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf` | 592523200 | `ae07ea1facd07dd3230c4483b63e8cda96c6944ad2481f33d531f79e892dd024` |
| Qwen3.5-2B (alt) | `unsloth/Qwen3.5-2B-GGUF` @ `f6d5376be1edb4d416d56da11e5397a961aca8ae` | `Qwen3.5-2B-Q4_K_M.gguf` | 1280835840 | `aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223` |
| | | `mmproj-F16.gguf` | 668227264 | `7035e9cb8d7c6a9681d07eef9a364783e86ea4cd73faab2eabb4f43a101830c7` |

Re-check the hashes at implementation time. The `resolve/<commit>/` URL form is the same as the existing `LocalModels` entries.

---

## 3. How the latency and RAM estimates were made (all UNVERIFIED)

- **Image tokens.**
  - Qwen3-VL/Qwen3.5 use patch 16 and a 2x2 merge, so one LLM token covers 32x32 px. 768x768 gives 576 tokens and 512x512 gives 256 (geometry VERIFIED from the GGUF headers).
  - SmolVLM2 gives about 81 tokens per 384 px tile with pixel-shuffle 3 (UNVERIFIED). Turn image splitting off to keep one tile.
  - Gemma 4 has a configurable token budget (UNVERIFIED).
- **Encoder cost.** About 2 x encoder params x patches: about 0.4B x 1,024 patches x 2, roughly 0.8 TFLOP at 512 px.
- **Prefill cost.** About 2 x LLM params x (image + text tokens): 1.7B x ~400 x 2, roughly 1.4 TFLOP.
- **Decode.** Negligible if we score one answer token (below).
- **Phone throughput.** 4 big cores on a baseline armv8-a build give perhaps 60-120 GFLOP/s effective, so **15-35 s**. With `dotprod`/`i8mm` int8 kernels (plus optional KleidiAI) perhaps 2-4x more, so **5-10 s**. These multipliers are rules of thumb, not measurements.
- **RAM.**
  - Weights are mmap'ed, so the pages are file-backed and reclaimable, but they count in RSS while hot: LLM file + mmproj.
  - KV cache: Qwen3-VL-2B at n_ctx 1024 is about 0.1-0.12 GB in f16 (28 layers x 8 KV heads x 128 dim x 2 x 2 B x 1024; architecture numbers UNVERIFIED).
  - Encoder compute buffer: about 0.2-0.5 GB, depending on the patch count.
  - Text compute buffer: about 0.1-0.3 GB.
- **What makes 8 s plausible.**
  - Score, don't generate. Constrain the answer to one token and read the logits for `1`..`n` and `NONE`. That makes the call prefill + one decode step, and the confidence becomes a softmax over the allowed labels, which is more honest than a number the model makes up (UNVERIFIED that it is better calibrated on these models; measure it).
  - Cap image tokens at 256 (Qwen) or one tile (SmolVLM).
  - Keep the text short: about 120 tokens for 5 candidates.

---

## 4. What must change in the repo to add image input

Current state (VERIFIED):
- `android-companion/services/ai/src/main/cpp/CMakeLists.txt` sets the tools, common, examples and server builds OFF. It links only `llama` and `ggml`, statically, CPU only, with `-Wl,-z,max-page-size=16384` and `--exclude-libs,ALL`, plus `-fvisibility=hidden`.
- `llama_jni.cpp` exports 5 functions: `nativeLoadModel`, `nativeEmbed`, `nativeGenerate`, `nativeGenerateWithGrammar` and `nativeReleaseModel`. It uses a `Handle{model, embed_ctx}` and creates a fresh `llama_context` per generate.
- The `.so` targets baseline armv8-a (decision log ODE/N).
- `LocalModels`/`ModelFile` already model multi-file bundles with a commit-pinned URL plus `sha256` plus `bytes` (the Nomic text+vision bundle), and `ModelFileVerifier.verify(file, bytes, sha256)` exists.
- `PlaceMatchOutput(choice, confidence)` and `PromptKey.PLACE_MATCH` exist, and the prompt asks for JSON.

### 4.1 Build (CMake)
- Set `LLAMA_BUILD_MTMD ON`. It adds `tools/mtmd` without `common`/`tools`, guarded in the root `CMakeLists.txt` lines 266-269 (VERIFIED).
- Set `MTMD_VIDEO OFF` explicitly. It is already forced off when `LLAMA_SUBPROCESS` is OFF, but state it.
- `target_link_libraries(llama_jni PRIVATE mtmd llama ggml)`. mtmd privately pulls `vendor::stb` (JPEG/PNG decode), `vendor::miniaudio`, `vendor::hash` and `vendor::sheredom` (VERIFIED, mtmd `CMakeLists.txt`).
- Expect the `.so` to grow from 4.5 MB to roughly 7-10 MB. Every model graph plus miniaudio gets compiled in (UNVERIFIED). `--gc-sections` will drop some of it.
- Keep `max-page-size=16384`, which is already present. Re-run the existing check for 16 KB alignment and exported symbols, expecting 5 + the new symbols.
- **CPU-feature variant.**
  - `GGML_CPU_ALL_VARIANTS` needs `GGML_BACKEND_DL ON` (dynamic backend loading), which conflicts with the static design.
  - Cheapest option: build **two** JNI libraries, `llama_jni` (armv8-a, as today) and `llama_jni_v82` (`-march=armv8.2-a+dotprod`, optionally `+i8mm` as a third variant). Pick at load time from `getauxval(AT_HWCAP)` (`HWCAP_ASIMDDP`, `HWCAP2_I8MM`) in Kotlin via a tiny probe, or from `/proc/cpuinfo` features.
  - Optional: `GGML_CPU_KLEIDIAI ON` on the v8.2+ variant (UNVERIFIED that it builds cleanly with the pinned NDK 28.2).
  - Note: Cortex-A55 has dotprod. The "runs on A53/A55" reason for the baseline only binds A53.

### 4.2 JNI (new functions; keep the existing 5 untouched)
- `nativeLoadVision(textHandle: Long, mmprojPath: String, maxImageTokens: Int, threads: Int): Long`
  - Calls `mtmd_init_from_file(mmproj, h->model, params)` with `use_gpu=false`, `print_timings=false`, `warmup=false` (first-call cost moves to the call, so a cold load stays cheap), `image_max_tokens=maxImageTokens`, and `n_threads` from the existing `thread_count()`.
  - Returns a `VisionHandle{mtmd_context*}` (0 on failure).
  - Silence logs with `mtmd_helper_log_set(quiet_log, nullptr)` to keep the "no prompt or model text in logcat" contract.
- `nativeScoreImage(textHandle, visionHandle, jpeg: ByteArray, prompt: String, labels: Array<String>): FloatArray?`
  1. `mtmd_helper_bitmap_init_from_buf` decodes the JPEG via stb.
  2. Build an `mtmd_input_text` with `add_special=true`, `parse_special=true`. Chat-template tokens must parse, unlike the current `tokenize()`, which uses `parse_special=false`.
  3. Call `mtmd_tokenize` (the prompt must contain `mtmd_default_marker()` exactly once).
  4. Create a fresh `llama_context` with `n_ctx` about 1024 and `n_batch >= the largest image chunk`. Non-causal and M-RoPE models need the whole image chunk in one batch; the helper handles the per-model flags.
  5. `mtmd_helper_eval_chunks(..., logits_last=true)`.
  6. Read `llama_get_logits_ith(ctx, -1)` at the first token id of each label (`" 1"`…`" 5"`, `" NONE"`; check that each is a single token per model, or fall back to grammar-constrained generation).
  7. Return the label log-probs. Free bitmap, chunks and context on every path.
- `nativeGenerateImage(textHandle, visionHandle, jpeg, prompt, grammar?): String?`
  - Same pipeline, then the existing sampler loop (grammar + greedy).
  - Needed for "list objects and people" and as the fallback when the labels are not single tokens.
- `nativeReleaseVision(visionHandle)` calls `mtmd_free`. The text model is released by the existing `nativeReleaseModel`. Order: vision first, because `mtmd_context` holds a pointer to the `llama_model`.
- Same safety rules as today: every entry point is wrapped in `try/catch(...)`, never leaves a pending Java exception, and returns null/0 on failure. Calls on a handle stay serialised by the caller's lock.
- EXIF orientation: stb ignores EXIF orientation (UNVERIFIED for this stb version), so have Kotlin rotate and downscale to about 768 px long side before the call. That also cuts decode memory.

### 4.3 Prompt template per model (Kotlin, not native)
- `llama_chat_apply_template` only knows built-in templates, not Jinja, so keep one hand-written template per model id in Kotlin.
- Each template has a golden unit test pinning the exact string, and the media marker appears once:
  - Qwen3-VL / Qwen3.5 (ChatML): `<|im_start|>user\n<__media__>\n{task}<|im_end|>\n<|im_start|>assistant\n`. Qwen3.5 also needs the empty think block, prefilled `<think>\n\n</think>\n\n` (UNVERIFIED exact form; read it from the GGUF `tokenizer.chat_template` at implementation time).
  - Gemma 4: `<start_of_turn>user\n<__media__>{task}<end_of_turn>\n<start_of_turn>model\n` (UNVERIFIED for Gemma 4; it may differ from Gemma 3. Read it from the GGUF).
  - SmolVLM2: `<|im_start|>User:<__media__>{task}<end_of_utterance>\nAssistant:` (UNVERIFIED; check against the GGUF template).
- Task text (all models): `Candidates:\n1. Charminar (monument)\n2. Mecca Masjid (mosque)\n...\nWhich numbered candidate is the main building in the photo? If none of them, answer NONE. Never identify people. Answer with the number only.`
- Map the scored label to `PlaceMatchOutput(choice, confidence = softmax over allowed labels)`. A margin rule (top1 - top2 < 0.2 gives NONE) is tuned in the eval.
- The cloud `PLACE_MATCH` JSON prompt stays as it is. The offline path is a separate provider with its own prompt seed, so the plan hash is unaffected (per the PG design).

### 4.4 Download, checksum and storage
- Add `LocalModel` entries with two `ModelFile`s each (LLM + mmproj), copying the Nomic bundle pattern. Use the pins in §2.
- Reuse `ModelFileVerifier`. Downloads are Wi-Fi only, resumable, written to `noBackupFilesDir/models/…`, renamed atomically after the SHA-256 check, and preceded by a free-space check (file bytes + 10 %).
- Developer setting: "Offline vision model: none / Qwen3-VL-2B / Gemma 4 E2B / SmolVLM2". Default none.
- "Delete all" must not delete the models (they are not user data), but there needs to be a "Remove offline model" button.

### 4.5 Memory lifecycle
- Lazy-load the text model and mmproj on the first offline PLACE_MATCH. Unload after about 60 s idle, on `onTrimMemory(TRIM_MEMORY_RUNNING_LOW+)` and on app background.
- Never hold the VLM and a second generate model at once. The embedder (bge-small/nomic, about 0.1-0.3 GB) may stay.
- Use an mmap'ed load (llama.cpp default) so cold pages are reclaimable. Do not `mlock`.
- Count a failed context or encoder allocation as a failed load, the same rule as the embed context. Then fall back to "name + distance only" and back off for N minutes.
- Thermal: one call at a time, no queue. Drop a new frame if a call is in flight.

### 4.6 Agent-day estimates (honest, Sonnet implementing, Opus/Codex review)

| # | Work | Agent-days |
|---|---|---|
| 1 | CMake: mtmd standalone, link, size, 16 KB and exported-symbols checks | 0.5 |
| 2 | dotprod/i8mm variant `.so` + runtime CPU probe + fallback to baseline | 1.0-1.5 |
| 3 | JNI: load/score/generate/release vision + error paths + log silence | 1.5 |
| 4 | Host-side native smoke build (macOS, same sources) to test JNI logic without a device | 0.5-1.0 |
| 5 | Kotlin `OfflinePlaceMatchProvider` + per-model templates + golden tests + label scoring -> `PlaceMatchOutput` | 1.0 |
| 6 | `LocalModels` entries, download UI hook, Developer setting, remove button | 0.5-1.0 |
| 7 | Memory lifecycle (idle unload, trim, mutual exclusion, failure backoff) | 1.0 |
| 8 | On-device benchmark runner (Developer screen: fixed photo set -> CSV of choice/conf/latency/peak RSS) | 1.0 |
| 9 | Review rounds + fixes | 1.0 |
| | **Total to a benchmarkable build** | **about 8.5 (7.5-9.5)** |
| 10 | User device session: install, download about 5 GB for three models, run the eval (needs the user) | 0.5 user + 0.25 agent |

Add 1-2 days if KleidiAI or the i8mm variant fights the NDK, or if Qwen3.5's `qwen35` arch is slow on CPU.

---

## 5. Eval plan

**Phase A: desktop sanity (no device; Mac CPU via `llama-mtmd-cli` built outside the repo).** It only checks that the prompts, templates and label scoring work and gives rough accuracy. Desktop latency means nothing for the phone.

**Phase B: on device (the decisive run).** The user's phone, plugged in, screen on, after a 2-minute cool-down between models. Run every model on both builds (baseline and dotprod/i8mm) and at two image caps (256 and 576 tokens for Qwen, 1 tile for SmolVLM).

Photo set (about 60 photos, fixed, stored once on the phone, never uploaded):

| Set | Photos | Candidate list given to the model | Expected |
|---|---|---|---|
| Home pin: MI air purifier | 5 (front, angle, dim light, partial, cluttered shelf) | 1. MI air purifier (appliance), 2. PS5 box (box), 3. Hot Wheels race track (toy), plus 1-2 decoys (e.g. "table fan", "speaker") | 1 |
| Home pin: Hot Wheels race track | 5 (assembled, packed, partial, floor, close-up) | same list, shuffled order | the track's number |
| Home pin: PS5 box | 5 (front, side text, under a table, glare, far) | same list, shuffled | the box's number |
| Monuments, positive | 15-20 from the PCA photo store and online stock taken **before** the trip (Charminar, Golconda Fort, Mecca Masjid, Qutb Shahi Tombs, Ramoji Film City sets), each with the 3-5 real geosearch neighbours as candidates | real nearby list | the right number |
| Monuments, near-miss | 8: a real monument with the right answer **removed** from the list | neighbours only | NONE |
| Non-monument | 8: street, food, shop, car interior, people in a crowd (no names asked) | real nearby list | NONE |
| Indic signage | 6: Telugu/Urdu/Hindi signboards near the monuments (e.g. a "Charminar" sign in Telugu) | list including the place on the sign | the right number; separately log whether `nativeGenerateImage` can transcribe the sign |
| Objects/people (later feature) | 6: home rooms, a street | open prompt "List the objects and the number of people. No names." | human-graded: recall of the main objects, correct people count, **zero names or identities** |

Metrics per model x build x cap:
- top-1 accuracy on positives;
- NONE precision and recall (near-miss + non-monument);
- calibration (reliability of the softmax confidence; pick a threshold that keeps false positives <= 5 %);
- p50 and p90 wall time per call, cold (first call after load) and warm;
- load time;
- peak RSS (`/proc/self/status` VmHWM) and PSS;
- battery % and skin temperature over 20 calls in a row.

Pass bar. It matches the existing PG field bar: **>= 70 % top-1 on positives, NONE on >= 90 % of non-monuments and near-misses, p50 <= 8 s warm, peak RSS <= 3 GB, no OOM kill in 20 calls.** The home-pin items are an easy sanity floor: anything under 90 % there is a template or scoring bug, not the model.

Decision rule: pick the smallest model that passes. If none passes on the dotprod/i8mm build, ship offline as "name + distance only" and revisit when a GPU/NPU backend is in scope.

---

## 6. Risks

1. **Latency (high).**
   - CPU-only, baseline armv8-a is likely 2-4x too slow (§3, UNVERIFIED).
   - Mitigations: the dotprod/i8mm variant, the token cap, single-token scoring. If all fail, offline stays name + distance only.
2. **Thermal and battery (medium-high).**
   - The arXiv study saw 10-12 W and 90-95 °C on CPU-only llama.cpp VLM runs (VERIFIED, read on arxiv.org). That sustained duty would hurt a trip day.
   - Mitigation: confirm only on an explicit mark or a Place Guide trigger, never per frame.
3. **Small-model world knowledge (medium).**
   - 2B models may not recognise Golconda vs a generic fort. Multiple choice with neighbours helps a lot, because the model only has to compare and never name. Near-miss NONE handling is the weak spot.
4. **Indic OCR (medium, UNVERIFIED).** No source checked this session confirms Telugu/Urdu OCR quality for any shortlisted model. Measure it in Phase B.
5. **Licence drift (low-medium).**
   - The Gemma 4 Apache-2.0 tag is VERIFIED on HF, but Gemma 3/3n were under Gemma Terms, so re-read the Gemma 4 licence text before shipping.
   - LFM is conditional. Qwen2.5-VL-3B is excluded.
   - Qwen3.5-2B uses a third-party GGUF: the Apache weights are fine, but pin the conversion commit.
6. **Pinned llama.cpp vs model GGUFs (medium).**
   - The Gemma 4 GGUF was re-uploaded on 2026-11-12, possibly needing a newer llama.cpp than b11618 (UNVERIFIED).
   - Bumping the submodule touches the embedding path (re-run the Recall@K gate). Prefer models that load on the current pin, and test the load first.
7. **Binary size and 16 KB alignment (low).** mtmd brings in many model graphs plus miniaudio. Re-verify alignment and the exported-symbol whitelist.
8. **Memory pressure kills (medium).** On an 8 GB phone with the camera, glasses link and maps resident, a 2-3.4 GB VLM may push the app to the LMK. Lazy load, idle unload, fallback.
9. **Privacy (low, but a rule).** "Never identify people" is a prompt instruction, not a guarantee. The objects/people feature must post-filter any proper names, and must never send the photo anywhere (it doesn't; it is offline).
10. **Schedule (high).** About 8.5 agent-days plus a device session does not fit the 26 Nov freeze (UNVERIFIED that the freeze stands). Realistic target: a benchmark by about 28 Nov, usable for Sikkim (1-6 Dec) or Sri Lanka (23-31 Dec).

---

## Sources (checked 2026-11-20)

- Pinned tree (read-only, main checkout copy of the submodule): `tools/mtmd/CMakeLists.txt`, `tools/mtmd/mtmd.h`, `tools/mtmd/mtmd-helper.h`, `tools/mtmd/clip-impl.h`, `src/llama-arch.cpp`, root `CMakeLists.txt`.
- Repo: `services/ai/src/main/cpp/{CMakeLists.txt,llama_jni.cpp}`, `ai/local/{LocalModels,ModelFileVerifier}.kt`, `ai/contract/ContractOutputs.kt`, `prompt/PromptSeeds.kt`, `docs/runbook.md`, the on-device embeddings design, the 2026-11-16 trip handoff.
- Hugging Face API (`/api/models/<repo>` and `/tree/main`) for sizes, SHA-256, commit shas and licence tags. GGUF headers read with HTTP range requests for the mmproj projector type and image geometry.
- llama.cpp `docs/multimodal.md`: https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md
- VLM on OnePlus 13R study: https://arxiv.org/html/2507.08505v2
- Arm learning path, Qwen3-VL-2B via libmtmd + KleidiAI on Android: https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/ai-portal-mobile-vision-language/4-understand-app/
- Gemma 4 on Galaxy A35 (third-party blog): https://dev.to/baiju_rajyaguru_a70384dfd/i-ran-gemma-4-on-a-mid-range-android-phone-heres-what-actually-happened-40ge
- Model cards: Qwen3-VL-2B-Instruct-GGUF, Qwen3.5-2B, gemma-4-E2B, SmolVLM2-2.2B-Instruct, InternVL3-2B, LFM2.5-VL-1.6B, MiniCPM-V-4, granite-4.0-3b-vision (all on huggingface.co).
