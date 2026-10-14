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
