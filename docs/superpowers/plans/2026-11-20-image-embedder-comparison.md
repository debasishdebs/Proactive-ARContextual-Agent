# Image embedder for Place Guide: comparison and plan (2026-11-20)

Status: research only. No code was changed, and no Gradle or adb was run.
Scope (user ruling, 2026-11-20): **add** a second image-text (CLIP-family) space used only for Place Guide and photo similarity. The text space (bge-small or Nomic text for Ask and memory retrieval) **does not change**.
Legend: **VERIFIED (file)** = read in this repo. **VERIFIED (src)** = read at the linked source today. **UNVERIFIED** = an estimate or recollection that no file or source here confirms.

## 0. Answer in one paragraph

Do not swap anything. Add **SigLIP 2 base patch16-224** (Apache-2.0) as a separate, lazily filled photo space for Place Guide. Keep **Nomic vision v1.5** as the control in the eval. The fallback is **SigLIP (v1) base patch16-224** (Apache-2.0, English only, text tower about 2.5x smaller). **MobileCLIP and MobileCLIP2 are excluded**: their weights are under Apple's research-only licence. Ship the new space only if it beats Nomic on the user's own pins and monuments (§5). Estimate: **6.5–9 agent-days** plus device time (§7). That does not fit before the 26 Nov install freeze, so it is a post-trip item (**flagging the delay now**).

## 1. What the repo does today (VERIFIED, file)

| Topic | Where | Fact |
|---|---|---|
| Space identity | `services/ai/.../embed/EmbeddingSpec.kt` | The identity is `endpointId, kind, baseUrl, apiVersion, model, revision, dimensions, dim, l2, docPrefix, queryPrefix, maxInputChars`, plus `imageDim` when present. Canonical sorted JSON goes through SHA-256 to give `fingerprint`, and the config is stored in `embedding_space.configJson`. |
| Nomic is ONE combined space | `local/NomicBundle.kt`, `embed/EndpointEmbedder.kt` | The fingerprint is `nomic-embed-text-v1.5\|mean\|ln\|d=256\|l2+nomic-embed-vision-v1.5\|cls\|l2\|d=768\|l2`. Text is stored at 256 and images at 768 (`NomicVision.STORED_DIMS`). IMAGE is available only when the binding is the on-device `nomic-embed-v1.5` and the vision ONNX file exists. |
| Binding → embedder | `embed/Embedders.kt` | `forBinding()` probes one text and builds the spec. `forSpace()` rebuilds from the stored config and refuses if the endpoint moved. |
| Text query path | `memory/.../retrieval/SemanticRetriever.kt` | It reads only `spaces.current()` when that is ACTIVE. |
| One live space only | `memory/.../db/EmbeddingDao.kt:37-38` | `current()` = newest ACTIVE/STALE, `building()` = one BUILDING. `swapRetiring` retires the old space (7-day rollback, `EmbeddingSpaces.GRACE_MS`). **A second space put in `embedding_space` would become `current()` and break Ask.** A separate table is therefore required, not optional. |
| Photo vectors today | `FrameEntities.kt:79` | Table `frame_vector(frameId, spaceId, blob)`. It is written by `SceneIndexer` for **every** photo (backfill via `missingImageIds`) in the Nomic space, and read by `PhotoRecall` (text → `embedQueryFull` 768 → cosine scan). It exists only when Nomic is bound, and the default is bge-small (text-only). |
| Swap rule for images | `EmbeddingSpaces.swapIfComplete(requireImages)`, `swapImpact()` | A text-space rebuild needs every stored photo re-embedded. Photos whose JPEG is gone are "reduced" or "lost". |
| ONNX runtime | `services/ai/build.gradle.kts:37` | `com.microsoft.onnxruntime:onnxruntime-android:1.31.0`, arm64 only, `minSdk 29`. `OrtImageSession` uses default `SessionOptions` (**CPU EP**, no NNAPI or QNN). |
| Preprocess | `local/NomicVision.kt` | PIL-exact bicubic, stretch to 224, CLIP mean/std. `PreprocessConfig.fromJson` accepts only `resample == 3` (bicubic). |
| Speed evidence | `androidTest/.../NomicVisionDeviceTest.kt` | Asserts at least 20 img/s (ViT-B/16 class, int8, CPU). **No pass result is recorded in `docs/`** (UNVERIFIED that it passed). |
| Parity test | `NomicReferenceDeviceTest.kt`, `RealNomicDeviceRuntime.kt` | Device output must match the PyTorch reference (`tools/nomic_reference.py`) at cosine ≥ 0.99. This is the pattern to copy. |
| Download and checksum | `local/LocalModels.kt`, `local/ModelFileVerifier.kt` | A fixed catalog with multi-file bundles (`files`), URLs pinned to a commit, size check then streaming SHA-256. A new model is "one entry here". |
| Canaries | `embed/Canaries.kt` | Three fixed texts (English, Hindi, mixed) with min cosine 0.995. These are text only, and there is no image canary. |
| Power | `embed/LocalModelPower.kt` | Bulk text is battery-gated. `embedImages` is not (Ruling 18). Live image work goes through `PhonePowerPolicy IMAGE_VECTORS`. |
| Place Guide plan | `docs/superpowers/plans/2026-11-20-place-guide-resolver-and-semantic-ranking.md` §3 | Two channels: `sImg` (dwell photo ↔ pin Moment photos) and `sTxt` (dwell photo ↔ `"<name>. <kind>. <note>"`). The bands are UNVERIFIED. Shadow mode comes first. |

## 2. Comparison

Size columns: ONNX files on Hugging Face, int8 or "quantized" unless noted. Latency: vendor or source numbers. The repo's own phone has **no** measured number for any of these.

| Model | Licence: code / **weights** | Params img + txt | ONNX available, size (int8) | Mobile latency (source) | IN-1k zero-shot | COCO R@1 (T→I / I→T) | Text tower, languages | One shared space? | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| **Nomic Embed Vision v1.5** (today) | Apache-2.0 / **Apache-2.0** (VERIFIED src, HF card) | 92.9M (VERIFIED src) + Nomic text 137M (UNVERIFIED) | In use: 96.7 MB quantized vision (VERIFIED file) + 146 MB text GGUF | Repo asserts ≥ 20 img/s CPU (VERIFIED file; result UNVERIFIED) | **71.0** (VERIFIED src) | not on card (UNVERIFIED) | Nomic text v1.5, 8k ctx, **English** (VERIFIED src) | Yes, with nomic-embed-text-v1.5 (VERIFIED src) | **Control** for the eval |
| **SigLIP 2 B/16-224** | Apache-2.0 / **Apache-2.0** (VERIFIED src) | ~86M + ~280M; HF card says 0.4B total (VERIFIED src). The text side is large because of its 256k Gemma vocab. | `onnx-community/siglip2-base-patch16-224-ONNX`: vision int8 **94.6 MB**, fp16 186 MB; text int8 **283 MB**, fp16 565 MB (VERIFIED src) | Qualcomm AI Hub, **NPU** (QNN via ONNX): SD 8 Gen 3 image 5.8 ms fp / 3.7 ms w8a16, text 2.7 / 1.5 ms (VERIFIED src). On CPU ORT, as this app runs it: about 40–80 ms per image (UNVERIFIED, same ViT-B/16 class as Nomic) | **78.2** (VERIFIED src, paper Table 1) | **68.9 / 80.7** (VERIFIED src) | Gemma SentencePiece 256k, 64 tokens, multilingual (WebLI, 109 languages; VERIFIED src). Hindi quality UNVERIFIED | Yes | **Primary** |
| SigLIP 2 B/32-256 | Apache-2.0 (UNVERIFIED for this checkpoint; family VERIFIED) | ~88M + ~280M (UNVERIFIED) | ONNX export UNVERIFIED | About 4x fewer image tokens than B/16, so faster (UNVERIFIED) | **74.0** (VERIFIED src) | 63.7 / 75.5 (VERIFIED src) | as above | Yes | Speed fallback if B/16 is too slow |
| **SigLIP (v1) B/16-224** | Apache-2.0 / **Apache-2.0** (VERIFIED src) | ~0.2B total (VERIFIED src) | `Xenova/siglip-base-patch16-224`: vision int8 **94.1 MB**, text int8 **111 MB** (VERIFIED src) | as SigLIP 2 B/16 (UNVERIFIED) | **76.2** (VERIFIED src, SigLIP 2 paper) | 64.5 / 77.9 (VERIFIED src) | **English**, 64 tokens (VERIFIED src) | Yes | **Fallback** (smaller text tower) |
| MobileCLIP S0 / S1 / S2 | MIT / **Apple ML Research Model TOU: research only, "does not include … use in any commercial product or service"** (VERIFIED src, `LICENSE_MODELS`) | S0 11.4+42.4M, S1 21.5+63.4M, S2 35.7+63.4M (VERIFIED src) | Third-party ONNX only (e.g. `felixhrdyn/mobileclip2-s0-onnx`; sizes UNVERIFIED) | iPhone 12 Pro Max: S0 1.5+1.6 ms, S2 3.6+3.3 ms (VERIFIED src). Android UNVERIFIED | 67.8 / 72.6 / 74.4 (VERIFIED src) | UNVERIFIED | English (UNVERIFIED), 77 tokens (UNVERIFIED) | Yes | **Excluded: weights not licensed for a product** |
| MobileCLIP2 S0 / S2 / B (Aug 2025) | MIT / **same research-only TOU** (`apple-amlr`, VERIFIED src) | S0 11.4+63.4M, S2 35.7+63.4M, B 86.3+63.4M (VERIFIED src) | Third-party ONNX (UNVERIFIED) | S0 1.5+3.3 ms, S2 3.6+3.3 ms (iPhone 12 Pro Max, VERIFIED src) | 71.5 / 77.2 / 79.4 (VERIFIED src) | UNVERIFIED | English (UNVERIFIED) | Yes | **Excluded (licence)**. Desktop-only reference in the eval at most |
| OpenCLIP ViT-B/32 DataComp-XL | MIT / **MIT**, but the card says "any deployed use case … is currently out of scope" (advisory, VERIFIED src) | ~88M + 63M (UNVERIFIED) | No official ONNX (open_clip export is a script, UNVERIFIED); OpenAI B/32 on Xenova: vision int8 88.6 MB, text 64.1 MB (VERIFIED src) | Fast (49 tokens); number UNVERIFIED | **72.7** (VERIFIED src) | UNVERIFIED | English BPE, 77 tokens (UNVERIFIED) | Yes | Possible, but weaker than SigLIP B/32 at a similar cost, and the card advises against deployment |
| OpenCLIP ViT-B/16 DataComp-XL | as above | ~86M + 63M (UNVERIFIED) | export needed (UNVERIFIED) | as Nomic class (UNVERIFIED) | 73.5 (VERIFIED src, search snippet of the OpenCLIP table) | UNVERIFIED | English | Yes | Dominated by SigLIP B/16 |
| OpenAI CLIP ViT-B/16 | MIT / MIT (VERIFIED src, Qualcomm card) | 150M total (VERIFIED src) | Qualcomm export 571 MB fp (VERIFIED src) | NPU SD 8 Gen 3: 18.5 ms fp (VERIFIED src) | ~68 (UNVERIFIED) | UNVERIFIED | English, 77 tokens (VERIFIED src) | Yes | Dominated |
| Meta PE-Core B16 / S16 / T16 (Apr 2025) | Apache-2.0 (VERIFIED src, S16 card) | UNVERIFIED | No ONNX found (UNVERIFIED) | UNVERIFIED | B16 **78.4** (VERIFIED src) | B16 50.9 (metric VERIFIED src, direction unclear) | English (UNVERIFIED) | Yes | Watch list. Export work is the cost |
| MetaCLIP 2 worldwide (S16/B32/B16) | **CC-BY-NC-4.0** (VERIFIED src, S16 card) | S16 card: 0.4B | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | Multilingual (300+ languages UNVERIFIED) | Yes | **Excluded (non-commercial)** |
| jina-clip-v2 | **CC-BY-NC-4.0** (UNVERIFIED) | ~0.9B (UNVERIFIED) | | | | | 89 languages (UNVERIFIED) | Yes | Excluded (licence, size) |
| DINOv2-S/14 (image-only) | Apache-2.0 (UNVERIFIED) | 21M (UNVERIFIED) | | | n/a | n/a | **no text tower** | n/a | Image↔image instance matching only; it cannot do "PS5 box". Option for a third channel later |

### 2.1 Expected behaviour on these photos (all UNVERIFIED until §5)
- **Close-up home objects** (air purifier, Hot Wheels track, PS5 box): these three are different categories, so any CLIP-family model should separate them on image↔image. The real risk is distractors of the same category (another white box, another appliance). CLIP and SigLIP embeddings are category and semantic level, not instance level. SigLIP 2 adds dense and localisation objectives, and its card claims better fine-grained features. Glasses close-ups (partial view, motion blur, low light) are the hard part.
- **Text→image "PS5 box"**: SigLIP and SigLIP 2 read printed text and brand logos better than older CLIP (well known in the community, UNVERIFIED). The PS5 box has a large logo, so this is likely the case where SigLIP 2 beats Nomic most.
- **Landmarks** (Charminar, Ramoji Film City): web-scale CLIP training has seen famous landmarks, and SigLIP 2's WebLI is the largest corpus here. Multilingual training helps the Hindi or Telugu names in pack text.
- **Score scale**: SigLIP image↔text cosines are low in absolute terms. SigLIP's sigmoid head gives `sigmoid(t·cos + b)`, a per-pair probability with no softmax over candidates. That may replace the plan's softmax `p1` and τ = 0.01 (calibration UNVERIFIED).

## 3. Recommendation

1. **Primary: SigLIP 2 base patch16-224.** It is permissive (Apache-2.0 weights), has the best zero-shot and COCO numbers among the permissive base models (78.2 / 68.9), is multilingual, has ready ONNX int8 files, and has vendor NPU numbers if we later enable an EP. The vision tower is the same size as today's Nomic file (94.6 vs 96.7 MB). The cost is a 283 MB int8 text tower. Mitigation: the text tower is used only for anchor texts (pin and pack names). It loads lazily, its vectors are cached, and it unloads after use.
2. **Fallback: SigLIP (v1) base patch16-224.** Same vision cost, text tower 111 MB, English only. Pick it if the RAM or download budget refuses SigLIP 2's text tower, or if its tokenizer proves hard to port.
3. **Speed fallback:** SigLIP 2 B/32-256 (74.0) if B/16 on CPU misses the latency target.
4. **Control:** Nomic vision v1.5, already on the device. If neither SigLIP model beats it on §5, ship nothing new and use Nomic.
5. **Do not ship** MobileCLIP, MobileCLIP2, MetaCLIP 2 or jina-clip-v2 (weight licences). MobileCLIP2-S2 may appear in a desktop-only research eval for reference.

## 4. What must change in the repo (two spaces side by side)

### 4.1 Principle
The text space is untouched: `embedding_space`, `memory_vector`, `frame_vector`, `Embedders`, `SemanticRetriever` and `Canaries` do not change. Re-embedding cost for the existing index is **zero**. Nomic `frame_vector` rows, if any, stay for `PhotoRecall`.

### 4.2 Identity (EmbeddingSpec pattern, new class)
`EmbeddingSpec` is endpoint-shaped (`endpointId`, `kind`, `baseUrl`), and its `forSpace` path assumes an endpoint binding, so do not reuse the class. Add `PhotoSpaceSpec` in `services/ai/.../embed/` with the same canonical-JSON → SHA-256 fingerprint rule:
`model` (catalog id), `revision` (pinned HF commit), `visionSha256`, `textSha256`, `tokenizerSha256`, `preprocess` (resize, resample, mean, std, crop), `pooling` (`pooler_output`), `dim` (768), `l2` (true), `textTemplate` (e.g. `"a photo of {}"`, a ruling to take after the eval), and `maxTokens` (64).
Any change is a new photo space. Old rows are dropped and re-embedded on demand. There is no BUILDING, rollback or swap machinery, because the space is a lazy cache and not a full index.

### 4.3 Storage (new tables, `context.db`)
- `photo_space(id, fingerprint UNIQUE, configJson, dim, createdAt)`: normally one row.
- `photo_vector(frameId, photoSpaceId, blob, createdAt)` with PK `(frameId, photoSpaceId)`. It holds **only pin reference Moment photos**.
- Dwell or query photos that Place Guide evaluates are embedded, compared and **discarded**. Only scores go to the log. Not persisting them keeps the Forget surface minimal. Persist them only if the eval shows re-evaluation of the same frame is common (UNVERIFIED).
- Text anchors (pin and pack names) are embedded with the text tower and cached in memory (plan §3.2 LRU). If persistence is needed later, use `photo_text_anchor(anchorKey, photoSpaceId, blob)`, which then also joins Forget.

Per-vector size at 768-d (SigLIP B/16 width; UNVERIFIED from config, likely 768):

| Encoding | Bytes per vector | 100 pins × 3 photos | 10,000 photos (if ever backfilled) |
|---|---|---|---|
| fp32 (as `VectorCodec` stores today) | 3,072 | 0.9 MB | 30.7 MB |
| fp16 | 1,536 | 0.46 MB | 15.4 MB |
| int8 + one fp32 scale | 772 | 0.23 MB | 7.7 MB |

Recommendation: keep fp32. At pin-only volume the size is negligible, and it avoids a new codec and its cosine error.

### 4.4 Compute and battery (UNVERIFIED estimates; measure in §5)
- Image embed on CPU ORT int8 is about 40–80 ms (Nomic ViT-B/16 class; the repo asserts ≥ 20/s). At roughly 3–5 W for the big cores that is about 0.15–0.4 J per image. Place Guide embeds at most once per model-call window (≥ 20 s, plan §3.4), so ≤ 180 per hour ≈ 30–70 J/h, about 0.05–0.1 % of a 5,000 mAh battery per hour.
- The text tower runs only on an anchor miss: about 16 names per new cell, then cached.
- Cold load is UNVERIFIED (guess 0.5–2 s for a 95 MB vision model, and more for the 283 MB text model). RAM: about 150–250 MB vision and 300–400 MB text at int8 (UNVERIFIED). That comes on top of the Nomic text GGUF (350 MB estimate, `LocalModels.kt`) and any 1.4.5 VLM.
- Rules: never hold the SigLIP text tower and a VLM at once, and unload it after the anchor batch.
- Power gate: image embeds follow `IMAGE_VECTORS` like today. Pin anchor backfill (first enable, or after a model change) counts as bulk, so it uses `LocalModelPower.bulkAllowed` or runs on demand when a pin is evaluated.

### 4.5 Code touch list
1. `local/LocalModels.kt`: a new catalog entry `siglip2-base-p16-224` with `files` (vision int8, text int8, tokenizer, preprocessor_config), each pinned to a commit with sha256 and bytes. The download UI is `phone/.../settings/ai/LocalModelsSource.kt`, and `LocalModelsPageTest` gets a case. Note the roaming data saver (`2026-11-20-roaming-data-saver-design.md`): about 380 MB must be Wi-Fi only.
2. Preprocess: generalise `NomicVision.PreprocessConfig` so it accepts `resample = 2` (bilinear) and SigLIP's mean and std (0.5 / 0.5; UNVERIFIED, take them from the pinned `preprocessor_config.json` and test against the committed file, as the Nomic test does).
3. `OnnxImageEmbedder` / `OrtImageSession`: parameterise the output width and name (`pooler_output` / `image_embeds`) instead of `NomicVision.CLS_DIMS`. Add an `OrtTextSession` for `input_ids`.
4. **Tokenizer** (highest risk): the Gemma SentencePiece 256k vocabulary in Kotlin, or `onnxruntime-extensions` SentencePiece custom op (availability on Android with ORT 1.31 is UNVERIFIED). SigLIP v1 has a 32k SentencePiece vocabulary (UNVERIFIED), which is simpler.
5. A new `PhotoEmbedder` interface (`embedImages`, `embedTexts`, `fingerprint`, `dims`) in `services/memory`, kept separate from `EmbeddingService` so nothing in the text path sees it. Wire it into the Place Guide resolver `PLACE_EMBED` role (ranking plan §2) in place of the Nomic binding. Ranking then works even when EMBED is bge-small, which removes ranking plan risk #1.
6. Room: `photo_space` and `photo_vector` entities and DAO, a migration, and an exported schema under `schemas/`.
7. Canaries: one fixed JPEG plus two texts (English and Hindi) at cosine ≥ 0.995. This detects a silently changed file.
8. Device tests: `SiglipReferenceDeviceTest` (parity ≥ 0.99 against a Python reference, the `NomicReferenceDeviceTest` pattern) and a throughput test (the `NomicVisionDeviceTest` pattern).

### 4.6 Forget and Delete-all inventory (every new derived vector must join)
Repo places that enumerate derived data (VERIFIED, file):
- `services/memory/.../forget/DeletionGraph.kt`: `frame_vector` is registered at `:56` ("deleted with the photo"). Add `photo_vector` (and `photo_space`, a kept or wiped decision with a reason).
- `services/memory/.../db/WipeDao.kt:22,83`: Delete-all `DELETE` statements. Add both tables.
- `services/memory/.../forget/lifecycle/Families.kt:36`: the frame family table list. `photo_vector` is owned by the frame family (keyed by `frameId`).
- `services/memory/.../db/ForgetDao.kt:40` (per-frame delete), `:44` (residual-row count after a forget; must include `photo_vector`), `:252` (retention query).
- `services/orchestration/.../forget/RetentionWorker.kt:137`: `imageVectorDays` must also expire `photo_vector`. Re-embedding on demand needs the JPEG, which `photoDays` may also remove; `PinMomentLinks.sweep` then clears the link and the pin falls back to text.
- `services/orchestration/.../MemoryWipe.kt`: the Delete-all orchestration, including clearing the in-memory anchor cache and closing the sessions.
- Pin delete and `setMoment` (ranking plan §3.2: `UserPlace.kt:100` fires no listener) must drop that pin's `photo_vector` rows.
- Tests that will (correctly) fail until these are registered: `services/memory/src/test/.../forget/DeletionGraphInventoryTest.kt` and `.../forget/lifecycle/FamilyInventoryTest.kt` (both read the exported Room schema), `DeletionGraphWipeTest.kt`, and `services/orchestration/src/test/.../canvas/plan/DeletionInventoryFilesTest.kt` (prefs and folders: register any persisted anchor cache file). Also `PlaceSurfaceInventoryTest.kt`, `work/frames/place/PinMomentLinksTest.kt`, and `services/memory/src/sharedTest/.../db/V1Fixture.kt:13` (`FRAME_TABLES`).
- Model files in `filesDir/models` are not user data, but check how `DeletionInventoryFilesTest` treats the models folder (UNVERIFIED).

## 5. Eval plan (user's home pins and monuments)

Offline first, on the desktop (no device): export JPEGs and run Python reference encoders (open_clip / transformers). Port to the phone only for the winner.

1. **Data.**
   - Home: 3 pins (MI air purifier, Hot Wheels race track, PS5 box), each with its pin Moment photo(s) as reference.
   - Home queries: 10 new glasses photos per pin, varying distance (close-up ≤ 0.5 m, 1–2 m), angle, lighting (day and night lamp) and partial occlusion.
   - Home distractors: 30 other glasses photos from the same home, including same-category items (another box, another white appliance, other toys).
   - Monuments: Charminar (22 Nov) and Ramoji Film City (23 Nov) glasses photos, about 15 each, plus 30 non-landmark street photos from the same trips. Text anchors are pack name + kind (+ a Hindi and a Telugu name variant). Before the trip, Wikimedia images are a labelled **proxy** only.
2. **Models.** Nomic v1.5 (control), SigLIP 2 B/16-224, SigLIP B/16-224, SigLIP 2 B/32-256. Optionally MobileCLIP2-S2 as a desktop-only research reference, never shipped.
3. **Metrics.**
   - Image↔image: top-1 accuracy over the 3 pins (plus "none" for distractors), the distribution of `margin = s1 − s2`, and precision at the ACCEPT band.
   - Text→image: recall@1 and recall@3 for "PS5 box", "air purifier", "Hot Wheels race track", the pin names as typed, and Hindi variants.
   - Monuments: photo→pack-text top-1 among the nearby pack places (the real candidate list from the resolver).
   - Calibrate the bands of ranking plan §3.4 per model. Generalise `RecallAtK` (today it hard-codes the Nomic transforms) to take any vector pair.
4. **Ship rule** (a proposal, for the user to confirm):
   - Adopt SigLIP 2 only if it beats Nomic by at least 10 points of text→image recall@1 **or** at least 5 points of image↔image top-1, with no drop on the other channel.
   - Device checks: parity ≥ 0.99, image embed p50 ≤ 100 ms on the user's phone, and RAM within budget.
   - Otherwise keep Nomic.
5. **Device.** Throughput and cold load on the user's phone, then a battery run (`tools/battery-run.sh`) with Place Guide on for one hour in shadow mode (needs the user and device time).

## 6. Risks

1. **Licence drift:** the fastest models (MobileCLIP and MobileCLIP2) are research-only. Pin commits and re-read the licences at catalog time.
2. **Tokenizer port** (Gemma 256k SentencePiece) is the biggest engineering unknown. Mismatched tokens silently degrade text→image; the parity test catches it.
3. **RAM and co-residency** with the Nomic text GGUF and a 1.4.5 VLM. The SigLIP 2 text tower is large.
4. **Download size** of about 380 MB (int8 vision + text) under roaming data saver: Wi-Fi only.
5. **Preprocess parity:** SigLIP uses bilinear resampling and different normalisation, and today's code accepts only bicubic.
6. **Category-level embeddings**: two similar boxes in one home may not separate. DINOv2 as an extra image channel is a later option.
7. **Score scales** differ per model, so every band in the ranking plan must be re-tuned per fingerprint and never shared.
8. **ORT 1.31 CPU EP** op coverage for the SigLIP 2 export is UNVERIFIED. The NPU numbers above do not apply to the app's CPU session.
9. **Forget completeness**: a new table missed in any §4.6 list leaves derived personal data. The schema-driven inventory tests are the guard.
10. **Timing**: install freeze 26 Nov and trips 22–23 Nov and 1–6 Dec. This lands after the trip at the earliest. On-device Nomic ranking (plan 1.4.4a shadow mode) remains the path for the trip.

## 7. Agent-day estimate (UNVERIFIED)

| Step | Days |
|---|---|
| Desktop eval harness and runs on exported photos (§5, all 4 models) | 1.0 |
| Catalog entry, pinned files, sha256, download UI, Wi-Fi gating | 0.5 |
| Preprocess generalisation (bilinear, config test) | 0.5 |
| Tokenizer port (or ORT-extensions) and its tests | 1.0–2.0 |
| ORT image and text sessions, `PhotoEmbedder`, `PhotoSpaceSpec`, canaries | 1.0 |
| Room tables, migration, schema, DAO, Forget/Wipe/Families/Retention plus inventory tests | 1.0–1.5 |
| Place Guide `PLACE_EMBED` wiring, anchor cache invalidation, band config per fingerprint | 0.5–1.0 |
| Device parity and throughput tests (needs the user's phone) | 0.5 |
| Review pass (Codex at medium effort, or Opus low as fallback) and fixes | 0.5 |
| **Total** | **6.5–9** |

## Sources
- Nomic vision v1.5 card: https://huggingface.co/nomic-ai/nomic-embed-vision-v1.5
- MobileCLIP-S2 card: https://huggingface.co/apple/MobileCLIP-S2 · MobileCLIP2-S2 card: https://huggingface.co/apple/MobileCLIP2-S2
- ml-mobileclip repo: https://github.com/apple/ml-mobileclip · weights licence: https://raw.githubusercontent.com/apple/ml-mobileclip/main/LICENSE_MODELS
- SigLIP 2 paper (Table 1): https://arxiv.org/html/2502.14786v1 · card: https://huggingface.co/google/siglip2-base-patch16-224
- SigLIP 2 ONNX: https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX · SigLIP ONNX: https://huggingface.co/Xenova/siglip-base-patch16-224 · SigLIP card: https://huggingface.co/google/siglip-base-patch16-224
- Qualcomm AI Hub: https://huggingface.co/qualcomm/SigLIP2 · https://huggingface.co/qualcomm/OpenAI-Clip
- OpenCLIP DataComp B/32: https://huggingface.co/laion/CLIP-ViT-B-32-DataComp.XL-s13B-b90K · OpenAI B/32 ONNX: https://huggingface.co/Xenova/clip-vit-base-patch32
- PE-Core: https://huggingface.co/facebook/PE-Core-S16-384 · MetaCLIP 2: https://huggingface.co/facebook/metaclip-2-worldwide-s16
