# Place Guide: model resolver (1.4.3) and semantic ranking (1.4.4)

Design only, 2026-11-20. Branch read: `feat/v101-ask-eval` at `39f7ad95`. No code was changed, no Gradle or adb was run.
Paths are relative to `android-companion/` unless they start with `docs/` or `tools/`.
Anything not confirmed in a file is marked **UNVERIFIED**.

User rulings this design follows (2026-11-20):
- Match by location first. Then rank the candidates semantically against each pin's reference Moment (text vs text, image vs image, or cross-modal). Call the vision model only for borderline scores.
- Two pipelines. Online uses a cloud model (more accurate, more items). Offline uses a small on-device vision model, as the fallback when there is no network or when the user picked "offline only" for privacy.
- ONE resolver path (`makeOrCreateModel`-style). It picks the backend from the setting, then the network. It caches one instance per (role, backend), fails closed (never falls back online silently under "offline only"), and logs which backend ran.
- Minor versions: 1.4.1 logging (done), 1.4.2 pin to Moment (done), 1.4.3 resolver, 1.4.4 semantic ranking, 1.4.5 offline backend, 1.4.6 pack Moments.

---

## 1. Current state map

### 1.1 Place Guide flow today

| What | Where | Fact |
|---|---|---|
| Entry | `services/orchestration/.../work/frames/place/PlaceSampler.kt:139` `poke()` | Runs after a frame is stored. Single-flight through `PlaceFlight` (`:65-70`). |
| Attempt | `PlaceSampler.kt:207-277` `attempt()` | Order: zone gate → newest dwell frame (≤ 90 s, `RECENT_MS` `:368`) → fix (≤ 60 s, ≤ 50 m, `:370-371`) → zone check on the fix (`:229`) → `candidates()` (`:235`) → dedupe (`:238-242`) → `cutKeepingPins` (`:243`) → call gap 20 s (`:246-249`) → offline fallback (`:252`) → `admit()` (`:254`) → ONE `match()` call (`:262`) → choice / confidence checks (`:268-273`) → `commit()` (`:276`). |
| Offline today | `PlaceSampler.kt:248,252,329-337` | `online && gap ok` is the only call path. Offline means NO model call: you get a "nearby · not checked" card only when the nearest place is within 100 m and there is no second place within 100 m. |
| Online model call | `PlaceSamplers.kt:86-96` `matchCall()` | `RoleRunner.run(RoleCall(PromptKey.PLACE_MATCH, …))` with the dwell photo, the numbered `name \| kind` lines (`PlaceSampler.kt:256`), liveness and `PayloadSources.frames`. |
| Role of PLACE_MATCH | `services/ai/.../contract/PromptKey.kt:28` | `PLACE_MATCH(Role.VISION_EXTRACT, …, ImageUse.REQUIRED)`. It **shares the VISION_EXTRACT binding** with photo extraction. |
| Output contract | `services/ai/.../contract/RoleContracts.kt:55-57` | `choice` (number or NONE) plus `confidence` 0..1. Nothing else is trusted. |
| Confidence threshold | `PlaceSampler.kt:377` | `MIN_CONFIDENCE = 0.5f`. A single constant, the same for every backend. |
| Candidate list | `services/memory/.../place/PlacePack.kt:125-135`, `Geo.kt:89-92,113-125` | Places whose edge is within 300 m, the 8 nearest, plus up to 3 pins (`PIN_SLOTS`), plus sub-places. The sampler then cuts to 16 (`PlaceSampler.kt:356-366,376`). Ties break by edge gap, then distance, then id. |
| Pack place content | `services/memory/.../place/PlacesDb.kt:17-27`; `services/connectors/.../PlaceCellSource.kt:62` | Text only: name, kind and `summary` (the Wikipedia extract, at most 400 chars, `PlacePack.kt:281`). **No image per pack place.** |
| Decision log (1.4.1) | `PlaceSampler.kt:130-135,155` | One `place attempt …` line with outcome, reason, call, online, cell, cand, ids, ages, buckets, candMs, callMs, totalMs, chosen, distB, confPct, dedupe, callGapS, card, reveal, err. It has **no backend field and no score fields**. |
| Settings | `PlaceGraph.kt:90-114` (`PlaceGuideSettings`) | Prefs `place_guide`, keys `enabled`, `budgetMb`, `cardSide`. **No backend or offline setting exists** (searched for offline_only, localOnly, wifiOnly and similar: no hits). |
| Network check | `services/orchestration/.../work/frames/KeyframeGraph.kt:201,216-222` | `networkUp()` is `NET_CAPABILITY_VALIDATED`. It **fails open**: no ConnectivityManager or any exception counts as up. `admitBackgroundCall()` requires a validated network too (`:201`), so it would also refuse an on-device model call while offline. |

### 1.2 Pins and Moments (1.4.2)

| What | Where | Fact |
|---|---|---|
| Pin | `services/memory/.../place/UserPlace.kt:13-29` | Holds id `user:<uuid>`, name, kind, lat/lon, radius (default 60 m), parentId, note and an optional `momentId` (8-hex). The KDoc at `:25-27` says "**the matcher never reads it**". 1.4.4 changes that. |
| Pin store | `UserPlace.kt:67-175` | Prefs `place_pins`. `setMoment()` (`:103`) fires no change listener, because the link is "not part of what the matcher sees" (`:92-93,100`). |
| Moment → photo | `services/memory/.../db/MomentDao.kt:98-102` `photoMomentsOf` | A Moment is one epoch second (`moment/MomentId.kt`). Its photos are the `frame` rows in that second. The first frame's path and ts are returned. |
| Link lifecycle | `services/orchestration/.../place/PinMomentLinks.kt:27-117` | Explicit Forget deletes the pin. Every other removal clears only the link. |

### 1.3 Model routing

| What | Where | Fact |
|---|---|---|
| Roles | `services/ai/.../Role.kt:4-12` | 7 roles. A binding has one primary endpoint+model and an optional fallback endpoint+model (`:14-22`). |
| Binding store | `RoleBindingRepository.kt:13-58` | Rows are keyed by `role.name`. `Role.valueOf` drops any unknown string (`:25`). |
| Router | `ModelRouter.kt:37-259` | `complete(role)` → `route()` (`:116-152`): primary first, then the fallback binding. **No backend policy**: the fallback can be any endpoint kind. `completeFromFrames` (`:74-92`) also falls through to SUMMARIZE's chain. |
| Choke point | `ModelRouter.kt:175-223` `call()` | Every provider call passes here (liveness, `requireImages`, upload slot). The provider is **created per call** (`providers.create`, `:177`). There is no instance cache. |
| Endpoint kinds | `Endpoint.kt:3` | `AZURE_OPENAI, OPENAI_COMPAT, OLLAMA, LLAMACPP_LOCAL`. |
| On-device LLM cannot see images | `EndpointRules.kt:13,17`; `model/provider/LlamaCppNativeProvider.kt:109-114` | `LLAMACPP_LOCAL` is in `NO_IMAGES`, and `acceptsImages = false` fails closed. **Today there is no on-device vision LLM.** 1.4.5 needs a new runtime. |
| Graph | `AiGraph.kt:35-49` | One `ModelRouter`, one `Embedders` and one `RoleRunner` per process. |

### 1.4 Embeddings

| What | Where | Fact |
|---|---|---|
| Interface | `services/memory/.../Embedder.kt:3-18` | `Modality {TEXT, IMAGE}`; `embedQuery`, `embedQueryFull` (768 for Nomic), `embedImages`, `imageDims`. |
| Binding → embedder | `services/ai/.../embed/Embedders.kt:29-42` | The EMBED binding. For the on-device Nomic bundle: text stored at 256, `imageDim = 768`. |
| Image capability | `embed/EndpointEmbedder.kt:31-45` | IMAGE only when the bound model is the on-device `nomic-embed-v1.5` and the vision ONNX file is present. **A cloud embedding endpoint is text-only in this code.** There is no cloud image embedding path. |
| Catalog | `services/ai/.../local/LocalModels.kt:27-68` | `bge-small` (text only, the default per the comment at `:40`) and `nomic-embed-v1.5` (text GGUF + `nomic-embed-vision-v1.5` quantized ONNX, 96.7 MB, RAM estimate 350 MB). |
| Vision embedder | `local/NomicVision.kt:14-22`, `local/OnnxImageEmbedder.kt:24-88`, `local/NomicBundle.kt:20` | 224×224 CLIP preprocessing. The stored width is 768 (`STORED_DIMS`), then L2. One process-wide session per (file, dims) (`NomicBundle.kt:20`). |
| Speed evidence | `services/ai/src/androidTest/.../NomicVisionDeviceTest.kt:16-28` | Asserts at least 20 images/s at 256 dims after warm-up (about 50 ms per image). No cold-load figure is recorded (**UNVERIFIED**). |
| Power gate | `embed/LocalModelPower.kt:39,59-81` | Text bulk is gated by battery. `embedImages` is **not** gated (`:76`, Ruling 18); live work goes through `PhonePowerPolicy` `IMAGE_VECTORS` (`work/budget/PhonePowerPolicy.kt:9`). |
| Photo vectors stored | `services/memory/.../db/FrameEntities.kt:79-81`; `FrameDaos.kt:432-469` | Table `frame_vector(frameId, spaceId, blob)`. `get(frameId, spaceId)` is at `:439`, `forFrames` at `:469`. Written by `SceneIndexer.kt:97` in the background (batch 8, `BACKGROUND_WAIT_MS` 15 s at `:333`). |
| Retention | `MemorySettingsStore.kt:17`; `RetentionWorker.kt:137-139` | `imageVectorDays` (default forever). When set, the vector of a pin's reference photo can expire while the photo stays. |
| Cross-modal search already exists | `work/frames/recall/PhotoRecall.kt:94-120` | Text → `embedQueryFull` (768) → truncate to `imageDims` → L2 → cosine scan of `frame_vector`. This is the same math 1.4.4 needs for photo → text anchors. |
| Text retrieval | `services/memory/.../retrieval/SemanticRetriever.kt:27-61` | Memory text vectors (256 for Nomic). They are **not usable** against 768-wide image vectors. |
| Photo captions | `FrameDaos.kt:413` `newestCaptions` | The newest live caption memory per frame. It exists only after VISION_EXTRACT has described that photo (**UNVERIFIED** for a pin's reference photo). |
| Vector math | `services/memory/.../retrieval/VectorCodec.kt:38-51` | `usable`, `cosine`, `l2`. |

### 1.5 Which channels exist today

| Channel | Query side | Anchor side | Exists today? |
|---|---|---|---|
| Image ↔ image | dwell photo → Nomic vision | pin's Moment photo → Nomic vision (`frame_vector`, or embed on demand) | **Primitives yes, wiring no.** It works only when EMBED is bound to the Nomic bundle with the vision file present. Whether the user's phone has that binding is **UNVERIFIED**. |
| Photo ↔ text (cross-modal) | dwell photo → Nomic vision | pin name/kind/note, or pack name/kind/summary → Nomic text `embedQueryFull` | **Primitives yes** (the PhotoRecall math). The score scale for place names is **UNVERIFIED**. |
| Text ↔ text | caption of the dwell photo | caption or note of the anchor | **Not usable live.** The dwell photo has no caption at match time. Getting one is a VISION_EXTRACT call, which is the call we are trying to skip. Useful only for evals. |
| Pack place images | n/a | none stored | **Missing** (this is slice 1.4.6). |
| Cloud image embedding | n/a | n/a | **Missing.** Online ranking uses the same on-device Nomic vectors. "Online" differs only in the confirmation model and in how many items it can take. |

---

## 2. Resolver design (1.4.3)

### 2.1 Where it sits

The resolver goes in front of the router, not in place of it. Every call must still pass through `ModelRouter.call()` (`ModelRouter.kt:175-223`), so the image-dispatch guard, liveness and upload slot stay the one choke point.

```kotlin
// services/ai/src/main/java/com/rokid/context/ai/resolve/ModelResolver.kt (new)
enum class Backend { ONLINE, OFFLINE }
enum class BackendMode { AUTO, OFFLINE_ONLY }            // the setting; AUTO = online when allowed and up, else offline
enum class ResolverRole { PLACE_CONFIRM, PLACE_EMBED }   // grows per consumer; maps to a Role binding per backend

sealed interface Resolved<out T> {
    data class Ready<T>(val backend: Backend, val instance: T, val label: String, val cacheHit: Boolean, val loadMs: Long) : Resolved<T>
    data class Unavailable(val reason: ResolveReason, val mode: BackendMode) : Resolved<Nothing>
}
enum class ResolveReason { OFFLINE_ONLY_NO_LOCAL_MODEL, NO_NETWORK_NO_LOCAL_MODEL, NOT_BOUND, LOCAL_LOAD_FAILED, POWER_PAUSED }

class ModelResolver(
    private val settings: () -> BackendMode,
    private val network: () -> NetState,              // VALIDATED | NONE | UNKNOWN (UNKNOWN never counts as up: see 2.4)
    private val factories: Map<Pair<ResolverRole, Backend>, BackendFactory<*>>,
    private val log: (String) -> Unit,
) {
    suspend fun <T> makeOrCreateModel(role: ResolverRole): Resolved<T>
    fun invalidate(role: ResolverRole? = null)        // binding/endpoint/model-file change, Delete all, mode change
}
```

Decision order (it is the same for every role):
1. `mode = settings()`.
2. `OFFLINE_ONLY`: return OFFLINE if it is bound and loads. Otherwise return `Unavailable(OFFLINE_ONLY_NO_LOCAL_MODEL)`. **It never returns ONLINE.**
3. `AUTO`, network `VALIDATED`: return ONLINE if it is bound. If ONLINE is not bound, return OFFLINE if it is bound. Otherwise `NOT_BOUND`.
4. `AUTO`, network `NONE` or `UNKNOWN`: return OFFLINE if it is bound. Otherwise `NO_NETWORK_NO_LOCAL_MODEL`. The sampler then keeps today's no-call behaviour (`PlaceSampler.kt:252`).

When a *call* fails on ONLINE in AUTO mode, the resolver does not retry offline inside the same attempt. The sampler's G1 rules (`PlaceSampler.kt:258-266`) still decide whether the "nearby" card shows. A later attempt can resolve to OFFLINE when the network has dropped. This keeps one backend per attempt, so the log line stays truthful.

### 2.2 Backends per role

| ResolverRole | ONLINE instance | OFFLINE instance |
|---|---|---|
| PLACE_CONFIRM | The VISION_EXTRACT binding through `ModelRouter` with `BackendPolicy.ONLINE` (cloud endpoint kinds only) | 1.4.5: an on-device VLM. It must be a new endpoint kind or provider that `EndpointRules.deliversImages` accepts and that sets `acceptsImages = true`, still called through `ModelRouter.call()`. In 1.4.3 this is **not bound**, so OFFLINE_ONLY resolves to `Unavailable`. |
| PLACE_EMBED | The same on-device Nomic bundle (no cloud image embedder exists, §1.4) | The on-device Nomic bundle |

PLACE_EMBED is on-device on both backends. Under OFFLINE_ONLY it is always allowed. In AUTO it ignores the network.

### 2.3 Cache

- Key `(ResolverRole, Backend)`. The value holds the instance and a fingerprint: endpoint id + kind + normalised baseUrl + model + (for local) the model file's sha256 from `LocalModels`.
- A fingerprint mismatch, `RoleBindingRepository.observeAll()` emitting, an endpoint save or delete, a model file delete, or a mode change all `invalidate()` the entry. Local instances are closed when evicted (`OnnxImageEmbedder.close()` exists, `OnnxImageEmbedder.kt:71`).
- Remote "instances" are cheap: `providers.create` already runs per call (`ModelRouter.kt:177`). Caching them is optional. What the cache really protects is the local model load (ONNX session today, VLM in 1.4.5). The ONNX session is already process-cached in `NomicBundle.kt:20`, so the resolver reuses that map rather than holding a second copy.
- Single-flight on creation (a `Mutex` per key), so two callers never load the same model twice.

### 2.4 Settings keys and network

- New prefs file `ai_backend`, key `mode` = `auto` | `offline_only`. Default `auto`. Unknown values read as `offline_only`, which fails closed.
- Why a new file and not `place_guide`: the user said ONE resolver path, and the scope of "offline only" beyond Place Guide is an open question (§6 Q1). It is device-local like `place_guide` (not backed up). Whether it goes into the settings export is **open** (§6).
- Network: add `NetState` from `ConnectivityManager`: `VALIDATED` only when `NET_CAPABILITY_VALIDATED` is present. Any exception counts as `UNKNOWN`, which is treated as down. **Do not reuse `KeyframeGraph.networkValidated()` as is: it fails open (`KeyframeGraph.kt:218-222`).** The roaming/data-saver design (`plans/2026-11-20-roaming-data-saver-design.md`, not read here: **UNVERIFIED**) should plug in here as one more input ("metered roaming → treat as NONE for photo uploads").

### 2.5 "Offline only" semantics (fail closed)

- No photo, name, note or summary leaves the phone for that role. That covers no cloud endpoint, no OLLAMA host on the LAN (see §6 Q2) and no fallback binding.
- `ModelRouter.route()` currently tries `b.fallbackEndpointId` with no kind check (`ModelRouter.kt:127-136`). It needs a `BackendPolicy` parameter. Under OFFLINE, every endpoint that is not on-device is refused **before `endpoints.keyFor`**, and the result is a `ModelCallException(kind = NOT_SET)` carrying the fixed token `offline_only_refused`. `completeFromFrames` (`:74-92`) needs the same filter on its SUMMARIZE hop.
- The pack fetch is network too (`PlacePack.candidates(… networkUp)`, `PlacePack.kt:125-126`). It sends only a 5 km cell box, never a photo. Whether OFFLINE_ONLY also stops cell fetches is a user call (§6 Q3). The design passes `networkUp = false` to the pack when the mode is OFFLINE_ONLY, which is the safe default.
- `admitBackgroundCall()` requires a validated network (`KeyframeGraph.kt:201`). An OFFLINE call needs an admit variant without the network term (thermal, power and battery only).

### 2.6 Per-backend confidence thresholds

Replace the single `MIN_CONFIDENCE` (`PlaceSampler.kt:377`) with a table:

| Backend | Model self-confidence floor | Why |
|---|---|---|
| ONLINE (cloud VLM) | 0.50 (today's value) | Unchanged, until the eval says otherwise. |
| OFFLINE (small VLM, 1.4.5) | 0.70 **and** embedding agreement (the VLM's choice must be the top-1 or top-2 by embedding score) | Small VLMs report poorly calibrated confidences (**UNVERIFIED**; to be measured in the 1.4.5 eval). |
| EMBED-only accept (no VLM call) | see §3.4 bands | No self-confidence exists. The band itself is the confidence. |

Constants live in one `PlaceThresholds` object with a `version` int that is logged (`thrV=`).

### 2.7 Logging fields

The resolver writes one line per resolution:

`resolve role=PLACE_CONFIRM mode=auto net=validated backend=online label=remote:AZURE_OPENAI cache=hit loadMs=0 reason=-`

- `label` is `remote:<EndpointKind>` or `local:<catalogId>`. **Never the endpoint name or a user-typed model string** (those are user-typed text; the 1.4.1 rule is fixed literals only).
- The `place attempt` line gains `mode=`, `backend=online|offline|none`, `resolve=<ResolveReason|ok>` and `thrV=`.

### 2.8 Honest list of what must change (1.4.3)

1. New `ModelResolver` + `BackendFactory` + `NetState` in `services/ai` (new files).
2. `ModelRouter.route/complete/completeFromFrames` get a `BackendPolicy` parameter with an endpoint-kind filter, including on the fallback. The `LanguageModel` interface (`model/LanguageModel.kt`) changes, so every implementer and fake does too.
3. `RoleRunner.run` passes the policy through (`RoleRunner.kt:112`). `RoleCall` gets a `policy` field.
4. A binding per backend. Today a role has one primary and one fallback. Options: (a) reuse primary/fallback and filter by kind (no schema change, but "online primary, local fallback" is then the only shape); (b) a second binding row per role with a suffixed key (`VISION_EXTRACT@OFFLINE`), which needs `RoleBindingRepository.toDomain` (`:25`) and the Settings UI to understand it. **Recommend (a) for 1.4.3** (no Room migration) and (b) only if 1.4.5 needs a different model per backend.
5. `PlaceSampler` gains a `resolve` seam. The `online` boolean (`:232-252`) becomes `resolved.backend`, and the offline branch can call the model when OFFLINE resolves Ready.
6. An `admit` variant for local calls (`KeyframeGraph.kt:201`).
7. Settings UI: one switch, "Offline only (no photos or place names leave the phone)", in Settings > AI. Placement is **open**.
8. Tests: a resolver decision table (mode × net × bound), the fallback-kind refusal, cache invalidation, and log literals (`LogContentScanTest`).

---

## 3. Semantic ranking design (1.4.4)

### 3.1 Pipeline

```
fix → pack candidates (today, ≤16 after cutKeepingPins)            [location first: unchanged]
   → q = image vector of the dwell photo                           [frame_vector row, else embed the JPEG on demand]
   → anchors per candidate (3.2) → scores (3.3)
   → band (3.4): ACCEPT  → commit without a vision call
                 REJECT  → no card (outcome NONE_RANKED)
                 BORDERLINE → vision confirm with a shortlist (online: up to 16 items; offline: top 3)
```

If ranking cannot run (no image space, the vector failed, power denied), it reports `rank=off reason=…` and the attempt behaves exactly as today. Ranking is an optimisation layer and never a new gate.

### 3.2 Anchors

| Candidate | Image anchor | Text anchor |
|---|---|---|
| Pin with a live `momentId` | Vectors of the Moment's photos: `photoMomentsOf` (`MomentDao.kt:98`) → frames in that second → `FrameVectorDao.forFrames(activeSpace, ids)` (`FrameDaos.kt:469`). If missing (not indexed yet, or expired by `imageVectorDays`), embed the JPEG on demand. | `"<name>. <kind>. <note>"` (note only when it is not blank). |
| Pin without a Moment | none | same text |
| Pack place (`wd:`/`osm:`) | none until 1.4.6 | `"<name>. <kind>. <summary first 300 chars>"` |

- Text anchors are embedded with `embedQueryFull` (768, `search_query:` prefix), the exact path PhotoRecall uses for text → image (`PhotoRecall.kt:107-111`), then truncated to `imageDims` and L2-normalised. Stored 256-wide memory vectors are not reusable (§1.4).
- Anchor cache: in memory, keyed `(candidateId, sha256(anchorText or frameIds), spaceFingerprint)`, LRU about 512 entries. It is dropped on `UserPinStore` change listeners (`UserPlace.kt:149`), on any `setMoment` (that fires no listener today, `UserPlace.kt:100`, so it needs its own hook), on space change and on Delete all. Nothing is persisted in 1.4.4, so no new Forget family is needed.
- Every anchor photo read goes through `ProvenanceGate` or the tombstone check first, like `PlaceFrameStore.tombstoned` (`PlaceSamplers.kt:38`). A forgotten Moment is never an anchor.

### 3.3 Scores

- `sImg(c) = max over anchor photos of cosine(q, a)`. Image ↔ image.
- `sTxt(c) = cosine(q, textAnchor(c))`. Cross-modal.
- The two channels have different scales. For CLIP-family models, image ↔ image same-object cosines are usually far higher than image ↔ text cosines (**UNVERIFIED** for Nomic v1.5 on these photos; the eval measures both). **Never add raw scores across channels.**
- Per channel, compute top-1, top-2 and `margin = s1 - s2` over the candidates that have that channel. For the text channel, also compute `p1 = softmax(sTxt / τ)[top1]` with τ = 0.01 as a starting value (**UNVERIFIED**, to be tuned).
- Decision channel: the **image channel when at least one candidate has an image anchor** (this is the home-pins case: three pins inside one home radius, where location cannot separate them). Otherwise the text channel (the monument case).
- Scores are rounded to 1e-3 before any comparison or log, so a last-bit difference from ONNX threading cannot flip a decision (whether ONNX runs bit-identically here is **UNVERIFIED**).

### 3.4 Bands (starting values, all UNVERIFIED until the eval)

| Channel | ACCEPT (no vision call) | BORDERLINE (vision confirm) | REJECT (no call, no card) |
|---|---|---|---|
| Image (pins with a Moment) | `s1 ≥ 0.85` and `margin ≥ 0.05` | `0.70 ≤ s1 < 0.85`, or `s1 ≥ 0.85` with `margin < 0.05` | `s1 < 0.70` **and** no text-channel candidate is BORDERLINE or better |
| Text (cross-modal) | `p1 ≥ 0.80` and `margin ≥ 0.02` | everything between | `s1 < floor`, floor from the eval (no starting guess: scale unknown) |

Skip-the-vision rules:
1. ACCEPT → no vision call. The card text and memory come from the pack/pin exactly as today (`PlaceCards`). The memory text says `on-device match <score>` instead of `photo match <confidence>` (`PlaceSamplers.kt:104-105`), so provenance stays honest.
2. REJECT → no vision call and no card. This is **not** a "genuine failure" under G1, so it never becomes a nearby card (it matches today's treatment of NONE).
3. BORDERLINE → vision call with the **shortlist**: candidates within `s1 - 0.10` on the decision channel, at most 16 online (today's `MAX_LIST`) and at most 3 offline, in score order. The numbering in the prompt follows the shortlist order.
4. Ranking off → today's path (call with the full list).
5. **Shadow mode first (1.4.4a):** compute, log, and *always* make today's call. Skipping (1.4.4b) is enabled only after the eval meets the targets in §4.3. Until then this is behind a Developer-mode switch `place_guide/rankSkip`, default off.

Tie-breaks (deterministic): rounded score descending, then edge gap ascending, then distance ascending, then id ascending. This extends the existing comparator at `PlaceSampler.kt:357`.

### 3.5 Online versus offline in ranking

- The ranking itself is identical on both backends (on-device Nomic).
- The difference is the confirmer and the shortlist size: ONLINE sends up to 16 names (and optionally the pin's reference photo as a second image; see §6 Q5), OFFLINE sends at most 3.
- With no offline confirmer bound (until 1.4.5), an offline BORDERLINE has two options: today's nearby fallback, or no card. The design keeps today's rule (nearby card only under the existing 100 m uniqueness test), so offline behaviour improves only through ACCEPT.

### 3.6 Logging

A new line per ranked attempt, beside `place attempt`:

`place rank v=1 ch=img|txt|off why=- n=8 img=3 txt=8 top1=user:… s1=912 s2=640 m=272 p1=-1 band=accept vision=skipped_accept|skipped_reject|made|made_shadow qMs=48 aMs=120 cache=5/8`

- Scores are integers ×1000. Ids follow the same rule as today (`wd:`, `osm:`, `user:<uuid>`): no names, notes, coordinates or frame ids.
- `place attempt` gains `rank=accept|borderline|reject|off`. A shadow-mode line also records whether the VLM's chosen id equals `top1` (`agree=1|0|-`), which is the main eval signal.

### 3.7 Cost, latency, battery

- The query photo embed costs about 50 ms warm (from the ≥20/s device test). The cold ONNX load of the 96.7 MB model is **UNVERIFIED** (guess 0.5–2 s). Keep the session warm while Place Guide is on.
- Text anchor embeds go through on-device llama.cpp Nomic Q8. The per-text cost is **UNVERIFIED**. With the cache, a new cell costs at most about 16 embeds once, and repeat attempts cost about 0.
- The power gate: on-device text embeds are gated by `LocalModelPower.queryAllowed` (`LocalModelPower.kt:41`), and anchor texts are under 512 chars, so they pass. Image embeds follow `IMAGE_VECTORS` live policy. Ranking runs only when a model call would run (once per ≥ 20 s, `PlaceSampler.kt:372`), so the battery impact is small. **UNVERIFIED** until a battery-run (`tools/battery-run.sh`).
- RAM: the Nomic bundle estimate is 350 MB (`LocalModels.kt:52`). A 1.4.5 VLM on top is the real risk (§5).
- Every ACCEPT/REJECT saves one photo upload and one cloud call (usually seconds; size and latency **UNVERIFIED**). That directly helps the roaming data budget.

---

## 4. Eval plan

### 4.1 Data

- **Home pins** (one home, all inside one 60 m radius, so location cannot separate them): MI air purifier, Hot Wheels race track, PS5 box. Each needs a reference Moment (the user still owes pin times, per `handoffs/overnight-queue-2026-11-20.md` D). Per object: 10 dwell photos at varied angle, distance and light, plus 10 negatives (other home objects, an empty wall, a person-free room shot). Total 30 positives and 10+ negatives.
- **Monuments:** Charminar (22 Nov) and Ramoji (23 Nov) during the trip, Sikkim/Darjeeling (1–6 Dec) later. Field data comes from the on-device log. Labels are written by the user after each day (time window → expected id or NONE).
- **Replay set:** the stored frames of the above, re-run through the ranker on the phone. That gives threshold tuning without walking around again.

### 4.2 Harness

- `tools/place_rank_eval.py` (new). Input: pca.log segments, read the way `tools/link-health-report.sh` does (run-as, oldest first; or saved files with `--file`), plus `tools/testdata/place-eval/labels.jsonl` (`{"from":"11-22 10:10","to":"11-22 10:20","expect":"wd:Q…"|"user:…"|"NONE"}`). It parses `place attempt` and `place rank`, joins by timestamp window, and prints metrics. Like link-health-report it never prints a raw log line.
- Optional dev route `POST /api/place/rank-eval` (Developer mode, paired host, same auth as `/api/ask/eval` in `tools/eval_pca.py`). Body: `{frameId, lat, lon, expect}`. It runs location + ranking with **no card, no memory and no cloud call**, and returns scores and the band. Frame ids already leave the phone to the paired host in `eval_pca.py` (`photoFrameId`), so this is consistent. The user should still confirm it (§6 Q7).

### 4.3 Metrics and targets (targets are proposals)

| Metric | Definition | Target to enable skip |
|---|---|---|
| Top-1 accuracy (ranked) | expected id == top1, over positives | ≥ 90% home pins; report monuments separately |
| False-accept rate | ACCEPT with a wrong id, or ACCEPT on a negative | ≤ 2% |
| Skip rate | (ACCEPT + REJECT) / ranked attempts | report; higher is better at fixed false-accept |
| Shadow agreement | VLM chosen id == top1 | report; disagreement cases feed the band tuning |
| Borderline recall | the correct id is inside the shortlist when BORDERLINE | ≥ 98% |
| Latency | qMs, aMs, totalMs p50/p95 | ranking p95 < 300 ms warm |
| Per-channel score histograms | sImg and sTxt for positives vs negatives | used to pick the bands (ROC sweep) |

---

## 5. Slice plan

| Slice | Content | Estimate (agent-days) | Depends on |
|---|---|---|---|
| 1.4.3 resolver | §2.8 items 1–8. ONLINE only bound, OFFLINE resolves `Unavailable`. Setting present. Logs. Fallback-kind filter. | User: 1. Mine: **1–1.5** (the `LanguageModel` signature change touches fakes across modules) | nothing |
| 1.4.4a ranking, shadow | Anchors, cache, scores, `place rank` line, always call. Harness script. | **1.5** | 1.4.3 for the `backend` field (it can also land first with `backend=online` hard-wired) |
| 1.4.4b ranking, skip | Bands enabled behind the Developer switch, shortlist prompt, memory text variant, tests | **0.5–1** (user total for 1.4.4: 1.5–2; mine **2–2.5**) | eval data from 4.1 |
| 1.4.5 offline backend | On-device VLM runtime + catalog entry + `deliversImages` / `acceptsImages` + RAM/thermal gating | **3–5, UNVERIFIED**: depends on `plans/2026-11-20-offline-vision-model-exploration.md` (not read here) | 1.4.3 |
| 1.4.6 pack Moments | A reference image per pack place (e.g. the Wikimedia lead image), embedded and stored as an anchor; fetch privacy rules | **2–3, UNVERIFIED** | 1.4.4 |

Risks:
1. **The EMBED binding may not be the Nomic bundle.** The default is bge-small, which is text-only (`LocalModels.kt:40`). Without Nomic there are no image vectors at all, and ranking is `off`. Check the device before the trip (**UNVERIFIED**).
2. The cross-modal score scale for place names is unknown. The text-channel bands may come out too weak to skip anything for monuments. Expect most monument attempts to be BORDERLINE at first.
3. Reference photos lose their vectors under `imageVectorDays`. On-demand embedding covers it, but it needs the photo, which `photoDays` retention can also remove (the link is then cleared by `PinMomentLinks.sweep`, and the pin falls back to text).
4. Two on-device models plus a VLM (1.4.5) may exceed RAM on the phone, and a cold ONNX load inside the attempt adds latency.
5. Changing `ModelRouter`'s signature touches the most-reviewed code path (codex r9–r18 rules). It needs a full review pass. The Codex limit resets 21 Nov 21:36, so Opus low-effort reviews until then.
6. Trip timing: none of this belongs in the staged candidate. These are branch slices per the overnight queue (C7).
7. `networkValidated()` failing open (`KeyframeGraph.kt:218-222`) is an existing gap for any "offline" claim. The resolver must not inherit it.

---

## 6. Open questions for the user

1. **Scope of "offline only":** Place Guide only, or every AI role (Ask, photo describe, nudges)? One resolver suggests all of them, but 1.4.3 would then also gate Ask.
2. Does an **Ollama host on the home LAN** count as offline? The design says no: it is off-phone.
3. Under "offline only", may the pack still **fetch cells** (a 5 km box, no photo)? The design default is no fetch.
4. With an ACCEPT and no vision call, should the card or memory say "on-device match" visibly, or keep today's wording?
5. Online borderline: may the cloud model also get the **pin's reference photo** as a second image? That likely improves accuracy, but it is a second upload of your own photo.
6. Acceptable **false-card rate** to turn on skipping (proposed ≤ 2%), and whether to start in shadow mode (proposed yes).
7. May the replay route (`/api/place/rank-eval`) return frame ids to the paired laptop, like `eval_pca.py` already does?
8. Should a pin allow **more than one reference Moment** (several angles)? The design takes the max over the Moment's photos; several Moments would need a list on `UserPlace`.
9. Is the phone's EMBED binding the Nomic bundle (text + images)? If not, may we switch it? That re-indexes.
10. Should the `ai_backend` mode go into the settings export (`ConfigExport.PREFS_FILES`)?
