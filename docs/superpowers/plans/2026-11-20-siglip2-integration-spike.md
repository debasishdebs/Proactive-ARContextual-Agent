# SigLIP 2 integration spike (2026-11-20)

Status: feasibility spike. I read the code and public sources only: no Gradle, no adb, no device run. Inputs: `2026-11-20-image-embedder-comparison.md` (which chose SigLIP 2 B/16-224) and `2026-11-20-place-guide-resolver-and-semantic-ranking.md`. In this doc, **VERIFIED** means one of two things. Either I read it from the pinned Hugging Face API, file bytes or code in this session, or the source is quoted. **UNVERIFIED** means an estimate or something not checked.

## 0. Top findings

1. **The ONNX export is ready, and ORT 1.31 should run it.** Both towers use opset 14, the plain `ai.onnx` domain and no contrib ops. VERIFIED: I parsed the graph headers. A device load test is still needed.
2. **One existing code path would give wrong vectors without any error.** `OrtImageSession` reads output `r[0]` and treats a 3-D output as "CLS = token 0". For SigLIP, output 0 is `last_hidden_state` and the real embedding is output 1, `pooler_output` (a MAP attention-pool head, no CLS token). VERIFIED from the ONNX output order. The session must pick the output by name.
3. **The tokenizer is smaller work than feared.** `tokenizer.model` is SentencePiece **BPE** (not unigram): 256,000 pieces, identity normaliser, no dummy prefix, byte fallback. VERIFIED: I parsed the proto. A pure-Kotlin port that reads the 4.2 MB `tokenizer.model` is about 1–1.5 agent-days. The repo already ships a second SentencePiece-BPE implementation (vendored llama.cpp, `vocab_only`) as a fallback.
4. **The shadow phase needs no Room table and no migration.** `PinReferenceVectors` and `PlaceShadow` keep vectors in memory only, registered with `ShadowCaches`. SigLIP vectors can follow the same pattern. The `photo_vector` table and the Forget inventory are needed only if SigLIP is later promoted to PhotoRecall or backfill.
5. **Cheapest first step: vision tower only.** Download 94.6 MB, no tokenizer, no 283 MB text tower. This tests the image↔image channel (pin reference photo vs dwell frame) against Nomic. The text channel stays on Nomic until the tokenizer lands.

## 1. Artefacts (pinned commits, sizes, hashes)

**Upstream:** `google/siglip2-base-patch16-224`, commit `75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2`. Licence `apache-2.0` (VERIFIED, HF API `cardData.license`). Not gated.

**ONNX:** `onnx-community/siglip2-base-patch16-224-ONNX`, commit `ba1f3b0843f24bc5417d38e19c37b287d719b2f4` (last modified 2025-02-21). Its card has **no licence field** (VERIFIED: `cardData.license` is null). It is a Transformers.js conversion of the Apache-2.0 weights. That the licence carries over is UNVERIFIED legally. Ship a NOTICE that credits Google, and record the ruling.

The hashes below are the **LFS oids the HF API publishes** (that is, SHA-256 of the file). I did not download the large files, so a human must download each one and confirm `shasum -a 256` before it goes into `LocalModels.kt`.

| File (onnx-community, pinned) | Bytes | Published LFS sha256 | Use |
|---|---|---|---|
| `onnx/vision_model_quantized.onnx` (identical to `_uint8`) | 94,553,333 | `5f2b401c1a4fc095702a5d45348e17ad46c4f87064085365b43c6e8eaa5c0070` | **Proposed vision file**. It is the same kind of file as Nomic's `model_quantized.onnx` |
| `onnx/vision_model_int8.onnx` | 94,553,333 | `0dd31785a2713f1113ef2272472165c69d580473dae38d7b47568ac587795e70` | Alternative. It uses `ConvInteger` with int8 weights; ORT CPU kernel support for that is UNVERIFIED |
| `onnx/vision_model_fp16.onnx` | 186,039,516 | `a1959f7b…aabd95` | Reference only |
| `onnx/vision_model.onnx` (fp32) | 371,807,752 | `c0573e3f…63bcad` | Desktop parity reference |
| `onnx/text_model_quantized.onnx` (identical to `_int8`) | 283,438,275 | `3a0603d3a00c05a80a6ded4743c16aaac7b1e62cdcc7e362e7ce418659b96400` | **Proposed text file** |
| `onnx/text_model_uint8.onnx` | 283,438,275 | `07c4ce79…b9b242` | Alternative |
| `onnx/text_model_fp16.onnx` / `text_model.onnx` | 564,862,230 / 1,129,469,657 | `711da56a…738d0eb0` / `baf12d94…d65e6293` | Reference only |
| `tokenizer.model` (SentencePiece) | 4,241,003 | `61a7b147390c64585d6c3543dd6fc636906c9af3865a5548f27f31aee1d4c8e2` | Kotlin tokenizer input. This is the same oid as in the Google repo, and I computed the same value locally from the downloaded file |
| `tokenizer.json` | 34,363,039 | `cb9140fa…fd8322` | **Not needed** on the phone (desktop reference only) |
| `preprocessor_config.json` | 394 | not LFS. My local sha256: `9b36b57ebaf20f09bf4c22100ccc21877ea6bfe5aead0c00c59f8af8ccefacfc` (human to re-verify) | Commit it as a test fixture (Nomic pattern) |
| `tokenizer_config.json` | 47,240 | not LFS. My local sha256: `7c3a247599e741bceba1a3fe0285aea88d1044dc1fad2caa1e48cdd9fd25f630` (human to re-verify) | Test fixture |

**Phone download:** vision + text + tokenizer.model = **≈ 382.2 MB** (≈ 0.38 GB, less than the 0.5 GB first assumed). Vision only = **94.6 MB**.

**Graph facts (VERIFIED: I fetched the first 4 MB and last 64 KB of each file with range requests and parsed the protobuf):**

- Vision int8: IR 7, producer `onnx.quantize 0.1.0`, **opset 14**, 1,008 nodes. Ops used: Add, Cast, Concat, ConvInteger, Div, DynamicQuantizeLinear, Gather, MatMul, MatMulInteger, Mul, Pow, ReduceMean, Reshape, Shape, Softmax, Sqrt, Squeeze, Sub, Tanh, Tile, Transpose, Unsqueeze. Outputs in order: `last_hidden_state`, then `pooler_output`. The input batch dimension is dynamic.
- Text int8: IR 7, **opset 14**, 1,021 nodes. The same op set, plus DequantizeLinear and Slice and minus ConvInteger. The only input is **`input_ids`**: there is no `attention_mask`, which matches `model_input_names: ["input_ids"]`. Outputs: `last_hidden_state`, then `pooler_output`.
- The text embedding table is gathered **while still int8**, and DequantizeLinear runs only on the gathered rows. So the 256k×768 table is never expanded to fp32 (≈786 MB) at run time. VERIFIED from the node inputs.
- `com.microsoft.onnxruntime:onnxruntime-android:1.31.0` (`services/ai/build.gradle.kts:37`, arm64 only) supports far above opset 14, and every op listed is a standard CPU-EP kernel. UNVERIFIED on the device until the load test.
- Logit constants for the sigmoid head (from `model.safetensors` at the pinned Google commit, read with range requests): `logit_scale` = 4.7244534 (exp ≈ **112.669**) and `logit_bias` = **−16.7717247**. VERIFIED bytes, but a human should re-read them in Python. Probability = `sigmoid(112.669 · cos − 16.772)`, so p = 0.5 at cos ≈ 0.149. This can replace the plan's softmax with τ = 0.01 as a calibrated per-pair score (still to be measured on real data).
- Both heads are 768 wide: `text_model.head.weight` is [768, 768] and the vision MAP head's MLP output is 768. VERIFIED from the safetensors header.

## 2. Text tokenizer

**What the repo has today:** no Kotlin tokenizer at all. Nomic text runs through llama.cpp GGUF (`services/ai/src/main/cpp/llama_jni.cpp:99`, `llama_tokenize`), and bge-small does the same. There is no WordPiece in Kotlin.

**What SigLIP 2 needs** (VERIFIED from `tokenizer.model` and `tokenizer_config.json` at the pinned commit, and from the HF SigLIP 2 docs):

- SentencePiece `model_type = BPE`, vocab 256,000. Piece types: 255,495 normal, 256 byte pieces (`<0x00>`..`<0xFF>`, byte fallback), 245 user-defined (atomic, for example `<mask>`, `<start_of_turn>`), 3 control and 1 unknown. Normaliser `identity`, `add_dummy_prefix = 0`, `remove_extra_whitespaces = 0`. The trainer flag `split_digits = 1` affects training only. Whether digits split at encode time comes out of the merges, and the golden tests cover it.
- IDs: `<pad>` = 0, `<eos>` = 1, `<bos>` = 2, `<unk>` = 3. `add_bos_token = false`, `add_eos_token = true`.
- **Lowercase all input text** and use `padding="max_length"`, `max_length=64`, `truncation=True` (VERIFIED, HF SigLIP 2 docs: "Overriding them may lead to degraded retrieval quality"). Padding is on the right with `<pad>`. The text pooler takes the last position, so padding to exactly 64 is mandatory. That the pooler takes the last token is UNVERIFIED from source, but it is built into the ONNX `pooler_output`. Whether `<eos>` survives truncation is UNVERIFIED, and the golden tests decide it.
- The HF pipeline template is `"This is a photo of {label}."` (VERIFIED, docs). Today `PlaceShadow.anchorText` is `"<name>. <kind>"`. The template is an eval ruling.

**Options:**

| Option | Size | Licence | Effort | Notes |
|---|---|---|---|---|
| **A. Pure Kotlin SentencePiece-BPE** (recommended) | 0 extra code deps; reads the 4.2 MB `tokenizer.model` (protobuf, about 60 lines of hand-parsing, as this spike did) | own code; data Apache-2.0 | 1.0–1.5 d | Algorithm: lowercase → replace `' '` with `▁` → split out user-defined pieces → per segment, repeatedly merge the adjacent pair whose joined string is in the vocab with the highest score → map unknown symbols to UTF-8 byte pieces → append EOS → pad or truncate to 64. Runs on the JVM, so parity tests need no device. Heap: a 256k `HashMap<String, Int>` is ~25–40 MB (UNVERIFIED). Use a sorted `Array<String>` with a binary search, or a double-array trie, if that is too much. Load it only while the text tower is open |
| B. llama.cpp vocab-only GGUF through the existing JNI | +1 JNI function (`nativeTokenize`), a ~4–5 MB vocab GGUF made with the vendored `gguf-py` | MIT code | ~1.0 d | **Fallback.** llama.cpp's SPM tokenizer is score-based BPE merging with byte fallback, so the same algorithm. `llama_model_params.vocab_only` exists (`llama.h:351`). Needs a native rebuild and a derived file we host or bundle (and therefore hash) ourselves |
| C. `onnxruntime-extensions-android` 0.13.0 (last release 31 Oct 2024; VERIFIED, mvnrepository) | AAR size UNVERIFIED | MIT | 1–2 d | A SentencePiece custom op needs a tokenizer graph built on the desktop. Compatibility with ORT 1.31 is UNVERIFIED (the release is a year older). Least preferred |
| D. Google `sentencepiece` C++ through NDK | ~1 MB .so (UNVERIFIED) | Apache-2.0 | 1.5–2 d | Reference-exact, but a new native build |

**Parity test plan** (desktop Python, committed fixtures):

- `tools/siglip2-ref/make_golden.py` uses `transformers` `AutoProcessor.from_pretrained("google/siglip2-base-patch16-224", revision="75de2d5…")`, called with the exact training settings. It writes `siglip2_tokens.json`: for each of 30 strings, the input, the lowercased form and the 64 ids.
- The 30 strings: English pins ("ps5 box", "air purifier", "hot wheels track", "my desk"); landmarks in Latin script ("Charminar", "Ramoji Film City", "Golconda Fort", "Tiger Hill, Darjeeling", "Rumtek Monastery", "Sigiriya", "Temple of the Tooth, Kandy", "Galle Fort"); Hindi ("चारमीनार", "गोलकोंडा किला"); Telugu ("చార్మినార్", "రామోజీ ఫిల్మ్ సిటీ", "గోల్కొండ కోట"); Sinhala ("සීගිරිය", "දළදා මාළිගාව", "ගාල්ල කොටුව"); Bengali or Nepali ("दार्जिलिङ" for Sikkim and Darjeeling); edge cases (empty string, digits "Gate 22B", double spaces, leading and trailing space, an emoji, "<mask>" typed literally, a 200-character name to force truncation, mixed "Charminar चारमीनार", accents "Café Niloufer", and the template "This is a photo of charminar.").
- Also check that the Python `sentencepiece` library on `tokenizer.model` gives the same ids as HF. If they differ, HF is the reference.
- `Siglip2TokenizerTest` (JVM) requires an **exact id match on all 30**. One mismatch is a bug, not a tolerance.

## 3. Image preprocessing

Exact steps, from the pinned `preprocessor_config.json` (VERIFIED: `SiglipImageProcessor`, `do_resize` with `size {224×224}`, `resample: 2`, `rescale_factor 1/255`, `image_mean = image_std = [0.5, 0.5, 0.5]`, no `crop_size`, `do_convert_rgb: null`):

1. Decode the JPEG to RGB and drop alpha. EXIF orientation is **not** applied, the same as today and as PIL by default.
2. **Stretch** to 224×224 without keeping the aspect ratio, using **PIL BILINEAR** (the triangle filter, support 1.0 scaled by the downscale factor, so it antialiases), rounding to uint8 between the two passes.
3. Multiply by 1/255, then compute (x − 0.5) / 0.5, giving the range [−1, 1].
4. NCHW float32, [n, 3, 224, 224].

Compared with `NomicVision` (`services/ai/.../local/NomicVision.kt`):

- The resize order and stretch are the same, and Nomic's centre crop is a no-op too.
- What differs: bicubic becomes **bilinear**, and the CLIP mean and std become 0.5/0.5.
- `PreprocessConfig.fromJson` today throws on `resample != 3`. It parses `do_convert_rgb: null` as true (Android `optBoolean` fallback) and the missing `crop_size` as crop = resize. Both are fine.
- Work: add a triangle kernel to `coeffs()` (selected by `resample`) and allow `resample = 2`. Commit `preprocessor_config.json` as a fixture.
- Pixel parity: compare 5 JPEGs against the **slow** `SiglipImageProcessor(use_fast=False)`, because the torchvision path is not PIL-exact (UNVERIFIED). The gate is max |Δ| ≤ 1/127.5, one uint8 step.

## 4. Download, verify and storage

- **Catalog:** add one entry to `services/ai/.../local/LocalModels.kt`, `id = "siglip2-base-p16-224"`, `files =` [vision, text, tokenizer.model] with the pinned URLs `https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/ba1f3b0843f24bc5417d38e19c37b287d719b2f4/<path>`. Fill in bytes and sha256 from §1 **only after a human has confirmed them**.
  - `ModelFileVerifier` (streaming SHA-256) and `LocalModelDownloadWorker` (resolves through `LocalModels::byId`, line 191) work as they are.
  - **Needed:** a role or kind field (`EMBED` vs `PHOTO_SPACE`), so that the EMBED picker and rebinder (`phone/.../settings/ai/LocalModelsSource.kt:80-119`) can never bind SigLIP as the text embedder. `LocalModelsPageTest` and `LocalModelsTest` get cases.
  - A vision-only first step means two catalog ids (`…-vision` at 94.6 MB, `…-text` at 287.7 MB) or per-file optional download. Two ids is simpler.
- **Wi-Fi only:** per `2026-11-20-roaming-data-saver-design.md`, 0.38 GB must never download on cellular.
- **Space identity:** do **not** put SigLIP in `embedding_space` or `EmbeddingSpec`. The comparison doc §2 says a second row would become `EmbeddingDao.current()` and break Ask. Use a fingerprint string in the `NomicBundle.fingerprint` style, for example `siglip2-b16-224@ba1f3b0|vision_quantized|pooler|l2|d=768+text_quantized|lower|eos|pad64|pooler|l2`. Expose it as `ImageTextEmbedder.spaceId`, which already exists (`services/memory/.../ImageTextEmbedder.kt`).
- **Storage in the shadow phase:** none. The vectors stay in memory, using the `PinReferenceVectors` and `ShadowCaches` pattern (registered for Delete all and Forget). The anchor LRU stays in `PlaceShadow`. The model files themselves must join Delete-all and model removal, the same as the Nomic files. Check `DeletionInventoryFilesTest`.
- **Storage on promotion only:** the `photo_vector(frameId, photoSpaceId, blob)` table, a Room migration 17→18 (`ContextDatabase.kt:24`), and registration in `DeletionGraph.kt` (next to `frame_vector`, `:65`), `Families.kt:36`, `WipeDao`, `ForgetDao`, `RetentionWorker` and `V1Fixture.FRAME_TABLES`. Then `FamilyInventoryTest` and `DeletionGraphInventoryTest` must go green. The full list is in comparison doc §4.6.

## 5. Memory, latency and battery

| Quantity | Expectation | Source |
|---|---|---|
| Vision tower RAM (int8, ORT CPU) | ~120–200 MB resident | UNVERIFIED (file 94.6 MB plus arena). Qualcomm reports a 0–195 MB peak for the image encoder on NPU (VERIFIED src, qualcomm/SigLIP2) |
| Text tower RAM | ~300–350 MB (the int8 256k×768 table alone is ~197 MB) | UNVERIFIED estimate from the file and graph |
| Tokenizer heap (option A) | 25–40 MB as a HashMap, <10 MB as a trie | UNVERIFIED |
| Image latency, mid-range phone CPU (ORT, 4 threads) | ~150–400 ms per image | UNVERIFIED. Qualcomm's CPU-only TFLite fp32 numbers on automotive SoCs are 269–405 ms for the image encoder and ~97 ms for the text encoder (VERIFIED src). The NPU number of 1.7 ms does not apply, because the app uses the CPU EP |
| Text latency, 64 tokens | ~50–150 ms per string | UNVERIFIED |
| Cold load | vision ~0.5–1.5 s; text ~1–3 s (reading 283 MB from flash plus session init) | UNVERIFIED. Measure it in a device test |
| Battery | Small: the shadow runs only on dwell attempts (a few per hour) behind the `mayEmbed` power gate. Anchor texts are embedded once and cached | UNVERIFIED |

For comparison, the repo's `NomicVisionDeviceTest` asserts ≥ 20 images/s batched. Whether it passes on the user's phone is not recorded.

**Loading rules:**

- Load the two towers **lazily and independently**. The vision session lives like Nomic's, created on first use, with an idle unload after N minutes (new; N is a ruling).
- The text session follows open → batch all new anchors → close, together with the tokenizer vocabulary.
- Never hold the text tower and a 1.4.5 VLM at the same time.
- The failure policy stays `OnnxImageEmbedder`'s: one retry, then unavailable for the process, and the shadow logs `shadow=na`.

## 6. Plan (agent-days; Sonnet implements, Codex sol reviews at medium, Opus low as fallback)

| # | Work item | Days |
|---|---|---|
| 0 | Human: confirm the hashes and sizes in §1, and rule on the onnx-community licence and NOTICE (see §7) | user |
| 1 | `tools/siglip2-ref/`: Python golden generator (30 token-id lists; fp32 and int8-ONNX vectors for ~10 JPEGs and the 30 texts; preprocessed tensors for 5 JPEGs; logit constants). Fixtures committed | 0.5 |
| 2 | Preprocessing: bilinear kernel, `resample = 2`, a generic `PreprocessConfig`, fixture test, pixel parity | 0.5 |
| 3 | Runtime: `OrtImageSession` picks the output **by name** (`pooler_output`) with a width parameter (removes the `CLS_DIMS` hard-code). `Siglip2ImageText : ImageTextEmbedder` with a vision session only, and `embedText` throws (so the text channel is "no score") | 1.0 |
| 4 | Catalog (vision-only id), role field, picker filter, Wi-Fi-only rule, tests | 0.5–1.0 |
| 5 | Shadow wiring: `PlaceSamplers.kt:132` today picks the active Nomic target. Add a dev switch `placeShadowSpace = nomic\|siglip2\|both`. Under `both`, score both and log both with a `space=` token. Register the SigLIP caches in `ShadowCaches` | 0.5 |
| 6 | `place-attempt-report.sh`: split section (8), shadow agreement, per `space=`, plus fixtures | 0.5 |
| 7 | Device tests: load and run both quantised variants, parity cos ≥ 0.999 against Python running the **same int8 ONNX** (≥ 0.98 against fp32, UNVERIFIED target), throughput, cold load, RAM (`Debug.getPss`) | 0.5–1.0 + device time |
| **M1** | **Vision-only milestone: about 4–5 days.** The image↔image eval can start | |
| 8 | Tokenizer, option A, plus `Siglip2TokenizerTest` (30 exact) | 1.0–1.5 |
| 9 | `OrtTextSession` (int64 `input_ids` [n, 64]), text catalog id, open-batch-close lifecycle, text device parity | 1.0 |
| 10 | Eval on the user's home pins (air purifier, Hot Wheels, PS5 box) and the trip monuments (Charminar, Ramoji, then Sikkim and Sri Lanka). Comparison doc §5 gates: +10 pts text→image R@1 **or** +5 pts image↔image top-1, no drop on the other. Sigmoid calibration replaces the τ softmax | 1.0 |
| 11 | Promotion only (if the eval passes and PhotoRecall wants it): `photo_vector` table, migration 17→18, the full Forget, Wipe, Families and Retention registration, inventory tests green | 1.0–1.5 |
| 12 | Final review pass (one pass, parallel worktrees per the SDD rule) | 0.5 |

**Total: about 8–10.5 agent-days**, or about 7–9 without item 11. That is in line with the comparison doc's 6.5–9. None of it fits before the 26 Nov install freeze. It is a **post-trip** item. M1 could run during the trip only if it has device time, which it will not.

**Fallback if tokenizer parity fails** (at item 8, after 1.5 days):

1. Switch to option B (llama.cpp vocab-only), about 1 day.
2. Otherwise stay vision-only. SigLIP 2 takes image↔image, and Nomic keeps text→image. The two spaces are never mixed, because `spaceId` guards that.
3. Otherwise SigLIP v1 B/16 (32k vocab, English, 111 MB text). Its tokenizer is also SentencePiece (UNVERIFIED type).

## 7. Risks and what needs the user

**Risks:**

1. The output-index bug (§0.2). It is easy to miss because the shapes still look valid.
2. `ConvInteger` int8 vs uint8 kernel support on the ORT 1.31 CPU EP (UNVERIFIED). Load both variants first.
3. Text tower RAM (~300+ MB) beside the Nomic GGUF and the VLM.
4. Licence of the onnx-community repo (no field).
5. Lowercasing differences between Kotlin and Python (`lowercase(Locale.ROOT)` vs `str.lower()`). Indic scripts have no case, but the Latin edge cases are in the golden set.
6. CPU latency could miss a 300 ms shadow wait. The speed fallback is B/32-256.
7. Promotion without the full Forget registration would leave derived personal data. The inventory tests are the guard.

**Needs the user:**

- (a) The download budget: 94.6 MB vision-only first, or ≈ 382 MB for both towers, Wi-Fi only.
- (b) Approve the hashes in §1 after re-downloading and checking them.
- (c) The licence and NOTICE ruling.
- (d) Timing: start after the trip (proposed), or M1 only before 26 Nov (not recommended; it needs device time).
- (e) Photos for the eval set (home pins, then the monuments from the trip).

## Sources

- HF API, pinned: https://huggingface.co/api/models/onnx-community/siglip2-base-patch16-224-ONNX/tree/main?recursive=true · https://huggingface.co/api/models/google/siglip2-base-patch16-224
- HF SigLIP 2 docs (lowercase, max_length 64, sigmoid): https://huggingface.co/docs/transformers/en/model_doc/siglip2
- Qualcomm AI Hub SigLIP2 (NPU and CPU timings, memory): https://huggingface.co/qualcomm/SigLIP2
- onnxruntime-extensions-android: https://mvnrepository.com/artifact/com.microsoft.onnxruntime/onnxruntime-extensions-android
- Code: `services/ai/build.gradle.kts:37`, `services/ai/.../local/{OnnxImageEmbedder,NomicVision,NomicBundle,LocalModels,ModelFileVerifier}.kt`, `services/ai/src/main/cpp/llama_jni.cpp`, `services/memory/.../ImageTextEmbedder.kt`, `services/orchestration/.../work/frames/place/{PlaceShadow,PinReferenceVectors,PlaceSamplers,ShadowCaches}.kt`, `tools/place-attempt-report.sh`
