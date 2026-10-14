# On-device embeddings — design

**Date:** 2026-10-14 · **Status:** approved in chat 2026-10-14 · **Builds on:** v1 spec §4.1 (endpoints), §4.2 (roles, EMBED), retrieval and embedding spaces (generation swap, reindex), Agent flows spec (shared Model facet).

## 1. Intent

What the user said:
- Host a lightweight embedding model on the phone itself, so hybrid search gets its semantic half without a cloud endpoint. Today there is no Embeddings endpoint, so search is lexical only.
- Start English-only with **bge-small-en-v1.5**. Keep the door open for **multilingual-e5-small** later. The user is unsure how ~120 MB of RAM behaves on the phone, so e5 is not part of day one.
- Provide a **model downloader page** so this is easy.

Success:
- From Settings → AI → On-device models, one tap downloads bge-small (Wi-Fi, verified).
- One more tap ("Use for Embeddings") binds the EMBED role and starts the reindex. After that, hybrid search returns meaning-based matches with no network.
- Adding e5-small later is one catalog entry.

Non-goals:
- on-device chat models (the runtime would allow it later);
- bundling a model in the APK;
- user-supplied arbitrary URLs.

## 2. Native runtime (`:services:ai`)

- Build **llama.cpp** for **arm64-v8a** with the Android NDK + CMake, as a git submodule pinned to one release tag (commit recorded in the plan).
- A JNI shim `llama_jni.cpp` implements exactly the five externals already declared in `LlamaCppNativeProvider`: `nativeLoadModel`, `nativeGenerate`, `nativeGenerateWithGrammar`, `nativeEmbed`, `nativeReleaseModel`.
  - `nativeEmbed` runs the model in embedding mode (mean/CLS pooling as the GGUF's metadata specifies) and returns an L2-normalised `FloatArray`.
  - The generate functions are implemented minimally, so the native interface is complete. They are not exercised by this feature.
- Only arm64-v8a. Other ABIs keep `isNativeAvailable() == false`, and the UI says "On-device models need a 64-bit ARM phone."
- The APK grows by about 3–5 MB. No model ships inside the APK.
- Tooling: NDK and CMake installed through `sdkmanager` into `$ANDROID_HOME`; versions pinned in `android-companion/*/build.gradle.kts` (`ndkVersion`, `externalNativeBuild.cmake.version`).

## 3. Model catalog (`:services:ai`, `com.rokid.context.ai.local`)

```kotlin
data class LocalModel(val id: String, val title: String, val file: String, val url: String, val sha256: String, val bytes: Long,
                      val languages: String, val dims: Int, val ramEstimateMb: Int, val license: String, val note: String? = null)
object LocalModels { val all: List<LocalModel>; fun byId(id: String): LocalModel? }
```

Day one has exactly one entry:

| id | title | url (pinned revision) | bytes | sha256 | languages | dims | RAM est. | licence |
|---|---|---|---|---|---|---|---|---|
| `bge-small-en-v1.5-q8` | bge-small (English) | `https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/d32f8c040ea3b516330eeb75b72bcc2d3a780ab7/bge-small-en-v1.5-q8_0.gguf` | 36 806 944 | `ec38e8da142596baa913124ae50550de284b6916bf59577ef2f0cb9660c2f514` (HF LFS sha256; re-verified by downloading once during build) | English | 384 | ~90 MB | MIT (upstream BAAI MIT) |

The values were checked live on 2026-10-14: HTTP 200, X-Linked-Size and X-Linked-ETag as above, repo commit `d32f8c0…`. multilingual-e5-small is **not listed** on day one. Adding it later means one entry, with `note = "Larger, uses more memory"`.

## 4. Downloader (`:services:orchestration`)

- `LocalModelDownloadWorker` is a WorkManager unique work per model id. It requires an UNMETERED network by default; a Settings switch "Allow mobile data" overrides this.
- It writes to `filesDir/models/<file>.part` using HTTP Range resume, so an interrupted download continues from the bytes already on disk.
- It reports progress through WorkManager progress data.
- On completion it verifies the size and SHA-256, then atomically renames the file to `filesDir/models/<file>`. On a mismatch it deletes the `.part` and fails with "Download didn't verify — try again".
- The models dir is excluded from backups: add it to `data_extraction_rules.xml` / `backup_rules.xml`, consistent with the frame and key exclusions.
- Storage: before starting, it checks that free space is at least `bytes × 1.2`, and otherwise shows "Not enough space".
- No user data and no keys are involved, and logs carry only the model id plus a reason code.

## 5. UI: Settings → AI → On-device models (`:phone`)

- The page is the generic `ListScreen` (from Agent flows) with a `LocalModelsSource`.
- Each row: title, then "<size> · <languages> · ~<RAM> MB RAM", with a status of Download / Downloading NN % / Downloaded / In use.
- Row actions, each with a confirm dialog:
  - **Download** (blocking progress overlay with a cancel option);
  - **Use for Embeddings**;
  - **Delete**.
- A Wi-Fi-only note in the header, plus the "Allow mobile data" switch.
- The page lives only under Settings → AI, below Agent flows.

**Use for Embeddings:**
1. Creates or updates the endpoint `local_<id>`: kind `LLAMACPP_LOCAL`, name "On-device · <title>", `baseUrl` = the model file path, models = `[id]`, capabilities = `{EMBEDDINGS}`.
2. Binds `Role.EMBED` to it through the existing `ai.bindings.set` path, which triggers `onEmbedBindingChanged` and the reindex: a new embedding space and a generation swap, so search never mixes spaces.

Agent flows' *Embed* and *Search memories* cards then show the model.

**Delete:**
- If the model is bound to EMBED, the confirm text says "Search will use keywords only until you pick another embeddings model". Confirming unbinds EMBED first, then deletes the endpoint and the file.
- A model that isn't bound is deleted directly.

## 6. Runtime behaviour and errors

- The model loads on first use and is released after 60 s idle. Native calls stay serialised (the existing `nativeLock`) and timed (`nativeTimeoutMs`).
- If loading fails (missing or corrupt file, out of memory, or a non-arm64 ABI), embedding throws a `ProviderConfigException`. The existing retrieval fallback then runs lexical only, and the existing banner/diagnostics mapping shows "On-device model couldn't load", never raw text (§6.3).
- After two consecutive load failures, the row shows "Couldn't load — re-download". It never crash-loops.

## 7. Testing

- **Unit (JVM):**
  - catalog sanity: unique ids, https URL pinned to a 40-hex commit, 64-hex sha256, bytes > 0;
  - the SHA/size verifier;
  - Range-resume request building;
  - the free-space check;
  - endpoint creation from a downloaded model;
  - the delete-while-bound flow, which unbinds before deleting.
- **Worker (Robolectric + MockWebServer):**
  - a full download verifies and renames;
  - an interrupted download resumes with `Range: bytes=N-`;
  - a hash mismatch deletes the `.part` and fails with the user message;
  - metered networks are blocked by default.
- **Compose:**
  - row states;
  - the download confirm shows size and RAM;
  - "Use for Embeddings" binds EMBED and calls `onEmbedBindingChanged`;
  - delete-while-bound shows the keywords-only warning;
  - the route smoke test covers the new route.
- **Native:** `bash -n`-level build check. `:services:ai:externalNativeBuild` builds `libllama_jni.so` for arm64-v8a in the gate.
- **Device (controller):**
  - download on Wi-Fi;
  - Use for Embeddings, then the reindex completes;
  - the Retrieval "Try it" shows non-zero semantic scores;
  - a meaning-only query matches;
  - force-stop mid-download, then resume;
  - check phone RAM (`dumpsys meminfo com.rokid.context`) while embedding.

---

## Amendments (as built, 2026-10-14)

These entries are appended; the body above is unchanged.

**E1. §2: native toolchain and build.**
- *Now:*
  - NDK **28.2.13676358** is pinned in `:services:ai`, `:phone` and `:glasses`, with CMake 3.22.1. NDK 27.2 isn't in the SDK channel, and 28.2 gives 16 KB page alignment.
  - llama.cpp is a submodule at tag b11618 (`4dd6dd4`): CPU-only, statically built, with the `c++_static` STL and a baseline armv8-a target (no dotprod/i8mm).
  - `-O3` applies to every variant, not only release.
  - Pinning `ndkVersion` in the apps lets AGP strip native libraries, so `libllama_jni.so` is about 4.5 MB in the APK.
- *Why:*
  - `c++_static` avoids a clash with the CXR SDK's `libc++_shared`;
  - armv8-a runs on A53/A55;
  - the debug ggml build was -O0.
- *Commits:* `9fed70d`, `818c196`, `93ed4ea`, `c3d6966`. *Date:* 2026-10-14.

**E2. §2: JNI load.**
- *Now:* `nativeLoadModel(path, forEmbedding)` also creates the embedding context and returns 0 on failure, including out of memory. That counts as a load failure. Native functions return null, and Kotlin maps that to `ProviderConfigException`.
- *Why:* an OOM at context creation never raised the load-failure UI (review I2).
- *Commits:* `818c196`, `af208fc`. *Date:* 2026-10-14.

**E3. §6: embeds batched 8 per call.**
- *Now:* on-device embeds go **8 texts per timed native call** (`NATIVE_BATCH`), each call with its own timeout, instead of one call for a whole 64-text batch.
- *Why:* a 64-text batch could pass the 60 s timeout (review I1). Cost: about 8× more JNI round trips. A query embed can still wait behind a running reindex batch (final review M6).
- *Commit:* `c3d6966`. *Date:* 2026-10-14. Not measured on the device.

**E4. §4: download client and worker.**
- *Now:* the download client is https-only and follows redirects, but never from https to http. It has a 20 s connect timeout, a 60 s read timeout and no call timeout. WorkManager input is the catalog `model_id` only, never a URL.
- IO errors and 408/429/5xx retry with exponential backoff from 30 s, up to 6 attempts. A cancelled download keeps its `.part`; Delete removes it.
- Each model file has an in-process mutex, and the HTTP call is cancelled with the coroutine.
- *Why:* the pinned Hugging Face URL redirects to a CDN; a stopped worker had appended to its replacement's `.part` (review I4).
- *Commits:* `eab2656`, `bd91105`. *Date:* 2026-10-14.

**E5. §5: endpoint id and mobile-data switch.**
- *Now:* the endpoint id is `local_<id>` with characters outside `[A-Za-z0-9_-]` replaced, so it reads `local_bge-small-en-v1_5-q8`.
- "Allow mobile data" is stored in `app_config` under the key `local_models_allow_metered`. That file is excluded from backups, so a restore brings the switch back to off. Changing the switch re-enqueues any queued or running download with REPLACE, keeping the `.part`.
- *Why:* `EndpointRules` rejects '.'; the switch didn't reach live work (review I3).
- *Commits:* `cad5287`, `b7a5e9b`, `6439310`. *Date:* 2026-10-14.

**E6. §6: model cache and failure count.**
- *Now:* a process-wide `EmbedModelCache` loads on first use, releases after 60 s idle, reloads when the file's path, size or mtime changes, and releases at once on an embed failure. The consecutive load-failure count is kept in memory per model path and isn't persisted.
- *Commit:* `867fe9c`. *Date:* 2026-10-14.

**E7. Status.** Merged as `7d37580`. The §7 device check (download, use, reindex, semantic scores, resume, meminfo) is still pending. Open minors include the free-space check ignoring `.part` bytes and the overlay text "Keep the app open" (decision log §4 ODE).
