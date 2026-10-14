# On-device Embeddings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This is a **lean plan**, per the user's standing preference: exact interfaces, behaviour bullets and named tests. Streams N, D and U run in parallel worktrees.

**Goal:** Settings → AI → On-device models downloads bge-small-en-v1.5 (GGUF, verified) and binds it as the EMBED model, running on-device through a llama.cpp JNI runtime.

**Architecture:** N builds llama.cpp plus a JNI shim behind the existing `LlamaCppNativeProvider` externals. D adds a model catalog and a resumable, verified WorkManager downloader. U adds the downloader page on the generic `ListScreen` and the Use/Delete wiring through existing repositories.

**Tech Stack:** Android NDK + CMake (arm64-v8a), llama.cpp (git submodule, pinned tag), Kotlin, WorkManager, OkHttp/MockWebServer (whatever the app's HTTP stack already uses: check `services/ai` HttpClient rules), Compose.

**Spec:** `docs/superpowers/specs/2026-10-14-on-device-embeddings-design.md`

## Global Constraints

- Day-one catalog: exactly one model, `bge-small-en-v1.5-q8`, with the URL, bytes and sha256 copied verbatim from spec §3. e5-small is not listed.
- arm64-v8a only. The model is never bundled in the APK. The models dir is excluded from all backups.
- Downloads are UNMETERED by default and resume through HTTP Range. A file is renamed into place only after size + SHA-256 verify.
- EMBED binding goes through `ai.bindings.set` → `onEmbedBindingChanged` (reindex, generation swap). There is no new write path and no Room schema change (v1 frozen).
- No raw error text in the UI. Logs carry only the model id plus a reason code, never user text.
- Gradle: `./gradlew --no-daemon`. Never `gradlew --stop`. One Gradle run per worktree. Never print secrets, never read .env, never push. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No unescaped `}` in a Regex. Grep the diff for accidental non-ASCII in .kt files.
- Tooling prerequisite (controller, before N starts, with user OK): `sdkmanager "ndk;27.2.12479018" "cmake;3.22.1"` into `$ANDROID_HOME`.

## Review Focus

1. **The device has low memory while embedding.** It must fall back to lexical search with a clear banner and never crash. N's `loadFailureThrowsConfigNotCrash` covers it (fault injection via a corrupt file), and U's banner test covers the UI side.
2. **Downloading while the app is killed.** WorkManager resumes from the `.part` file. D owns `resumesFromPartAfterProcessDeath`.
3. **Two quick taps on Download.** Unique work (KEEP) means one download, and the row shows a single progress. D owns `secondDownloadRequestIsANoOp`.
4. **Use for Embeddings while a reindex is already running.** It goes through the existing generation-swap path. U owns `rebindDuringReindexStartsANewGeneration` (assert it calls `onEmbedBindingChanged` once per real change).
5. **Delete while in use.** It unbinds first, and search keeps working lexically. U owns `deleteWhileBoundUnbindsThenDeletes`.

---

## Stream N: native runtime (`:services:ai`)

### Task N1: llama.cpp JNI build

**Files:**
- Add a git submodule `android-companion/services/ai/src/main/cpp/llama.cpp`, pinned to the latest llama.cpp release tag at build time. Record the tag and commit in the task report.
- Create `services/ai/src/main/cpp/CMakeLists.txt` and `services/ai/src/main/cpp/llama_jni.cpp`.
- Modify `services/ai/build.gradle.kts`: `ndkVersion = "27.2.12479018"`, `externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }`, `defaultConfig.ndk { abiFilters += "arm64-v8a" }`, plus release `-O3`. Build llama.cpp with `LLAMA_BUILD_TESTS/EXAMPLES/SERVER=OFF`, `GGML_OPENMP=OFF`, and CPU only.

**Interfaces:** implement exactly the externals in `com.rokid.context.model.provider.LlamaCppNativeProvider` (JNI names `Java_com_rokid_context_model_provider_LlamaCppNativeProvider_native…`):
- `nativeLoadModel(path): Long`, which returns 0 on failure;
- `nativeEmbed(handle, text): FloatArray`: embeddings mode, pooling from the GGUF metadata (default mean), L2-normalised, length = n_embd;
- `nativeGenerate` / `nativeGenerateWithGrammar`: a minimal greedy loop, max 256 tokens;
- `nativeReleaseModel(handle)`.
- Kotlin change in `LlamaCppNativeProvider.embed`/load: a 0 handle throws `ProviderConfigException("On-device model couldn't load")`.

**Tests:**
- `LlamaCppNativeProviderTest` (JVM): `loadFailureThrowsConfigNotCrash` drives the Kotlin wrapper with the native lib absent and with a 0 handle (seam: make the native calls injectable through an internal interface, keeping the default as the JNI).
- `nativeUnavailableMessageIsUserSafe`.
- Build check: `./gradlew --no-daemon :services:ai:assembleDebug` produces `lib/arm64-v8a/libllama_jni.so` inside the AAR. Assert with `unzip -l`.

- [ ] Tests → RED → submodule + CMake + JNI + Kotlin seam → GREEN, plus the `.so` present. Commit `feat(native): llama.cpp JNI runtime for on-device embeddings (arm64-v8a)`.

## Stream D: catalog + downloader (`:services:ai` catalog, `:services:orchestration` worker)

### Task D1: Catalog and verifier

**Files:**
- `services/ai/src/main/java/com/rokid/context/ai/local/LocalModels.kt` (`LocalModel`, `LocalModels` as in spec §3);
- `services/ai/src/main/java/com/rokid/context/ai/local/ModelFileVerifier.kt` (`fun verify(file: File, bytes: Long, sha256: String): Boolean`, streaming SHA-256).

**Tests (`LocalModelsTest`, `ModelFileVerifierTest`):**
- `catalogEntriesArePinnedAndWellFormed`: https, `/resolve/<40-hex>/`, 64-hex sha, bytes > 0, unique ids;
- `onlyBgeSmallIsListedOnDayOne`;
- `verifierAcceptsMatchAndRejectsSizeOrHashMismatch`.

### Task D2: `LocalModelDownloadWorker`

**Files:**
- `services/orchestration/src/main/java/com/rokid/context/work/LocalModelDownloadWorker.kt`;
- `services/orchestration/src/main/java/com/rokid/context/data/models/LocalModelStore.kt`, which holds `modelsDir(context)`, `fileFor(model)`, `state(model): Flow<LocalModelState>`, `enqueue(model, allowMetered)`, `cancel(model)` and `delete(model)`;
- the app's backup rules XML (find it via `grep -rl data-extraction-rules phone/src/main`): exclude `file/models/` in both `cloud-backup` and `device-transfer`.

**Interfaces (Produces):**
```kotlin
sealed interface LocalModelState { data object Absent; data class Downloading(val fraction: Float?); data object Ready; data class Failed(val message: String) }
```

**Behaviour:** spec §4, exactly:
- unique work `local_model_<id>` with KEEP;
- an UNMETERED constraint unless `allowMetered`;
- Range resume from the `.part` file;
- the free-space check at `bytes × 1.2`;
- verify, then atomically rename;
- on mismatch, delete the `.part` and fail with `Failed("Download didn't verify — try again")`;
- progress through `setProgress`;
- logs only `id` + reason code.

**Tests (Robolectric + MockWebServer + WorkManager test helpers):**
- `fullDownloadVerifiesAndRenames`;
- `interruptedDownloadResumesWithRangeHeader`;
- `resumesFromPartAfterProcessDeath` (Review Focus 2);
- `hashMismatchDeletesPartAndFailsWithUserMessage`;
- `meteredNetworkBlockedByDefault`;
- `secondDownloadRequestIsANoOp` (Review Focus 3);
- `notEnoughSpaceFailsBeforeDownloading`;
- `modelsDirIsExcludedFromBackups`, which parses the rules XML.

- [ ] D1 then D2: tests → RED → implement → GREEN. Commit after each.

## Stream U: downloader page + wiring (`:phone`)

### Task U1: On-device models page

**Files:**
- `phone/src/main/java/com/rokid/context/phone/ui/settings/ai/LocalModelsSource.kt` (a `ListSource`);
- `Routes.kt`: `const val SETTINGS_LOCAL_MODELS = "settings/local-models"` and add it to `LIVE_SETTINGS`;
- `SettingsRoot.kt`: an AI-section row "On-device models", "Run search models on your phone", placed after Agent flows;
- `SettingsDestinations.kt`: the composable.

**Interfaces:**
- Consumes `LocalModels`, `LocalModelStore`, `LocalModelState` (D). Until D merges, use a `LocalModelGateway` interface with a fake, and swap in the real one at integration.
- Consumes `ai.endpoints`, `ai.bindings`, `s.onEmbedBindingChanged`.

**Behaviour:**
- spec §5 rows, statuses and confirm dialogs (Download shows size + RAM);
- Use for Embeddings creates/updates the endpoint `local_<id>` (LLAMACPP_LOCAL, `baseUrl` = file path, models `[id]`, caps `{EMBEDDINGS}`) and then calls `ai.bindings.set(RoleBinding(Role.EMBED, "local_<id>", id))`;
- Delete while bound unbinds EMBED (`ai.bindings.clear(Role.EMBED)` → `onEmbedBindingChanged`), deletes the endpoint and then the file;
- the header shows the "Wi-Fi only" note and the "Allow mobile data" switch, stored in an existing app prefs store;
- non-arm64 shows "On-device models need a 64-bit ARM phone" and disables Download.

**Tests (`LocalModelsPageTest`):**
- `rowsShowSizeLanguagesRamAndState`;
- `downloadConfirmShowsSizeAndRam`;
- `useForEmbeddingsCreatesEndpointAndBindsEmbed`;
- `rebindDuringReindexStartsANewGeneration` (Review Focus 4);
- `deleteWhileBoundUnbindsThenDeletes` (Review Focus 5);
- `deleteWhileBoundWarnsKeywordsOnly`;
- `nonArm64DisablesDownload`;
- `loadFailureBannerIsUserSafe` (Review Focus 1, UI side);
- `AllRoutesSmokeTest` covers the new route.

- [ ] Tests → RED → implement → GREEN. Commit `feat(models): on-device models page with download, use for embeddings, delete`.

---

## Merge order
1. The controller installs NDK and CMake (user OK). N, D and U run in parallel worktrees off `feat/proactive-mvp-implementation`.
2. Merge N, D and U into `on-device-embeddings`. U swaps its gateway fake for `LocalModelStore`. Run the full gate.
3. Run the final review (opus) and a fix pass for Critical/Important findings. Merge into feat with `--no-ff`, locally.
4. Device check (spec §7).
