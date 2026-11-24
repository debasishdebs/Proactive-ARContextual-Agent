# Offline VLM links assessment (2026-11-25)

Tags: [PAGE] = stated on page (via WebFetch summary, not verbatim read); [INF] = my inference.
Fetch status: link 1 raw GitHub OK (github.com/blob URL gave 503); link 2 HTTP 403 (NOT read); link 3 OK; link 4 HTTP 403 (NOT read).

## 1. llama.cpp docs/multimodal/MobileVLM.md - PARTLY helpful
- [PAGE] Covers MobileVLM-1.7B and MobileVLM_V2-1.7B only: conversion (llava_surgery, convert_image_encoder_to_gguf, quantize q4_k), files = q4_k LLM GGUF + mmproj-model-f16.gguf.
- [PAGE] Uses `llama-mtmd-cli` (-m, --mmproj, -t, --image, --n-gpu-layers). Android build "via script in tools/mtmd/android/" (shell script, CLI binary run via adb).
- [PAGE] Speed: Snapdragon 888 ~35 s total; 778G ~28 s on older build, ~865 s on newer build (regression); i7 ~6 s.
- [INF] Confirms mtmd is the official image path and that a native build for Android exists, but it is a CLI, not JNI/Kotlin. Numbers are old chips, whole-run time, an obsolete model; the 865 s figure is a warning to benchmark ourselves. No dotprod/i8mm/KleidiAI flags, memory or license text seen (repo is MIT, from my knowledge, not the page).

## 2. farmaker47 Medium "Run Gemma and VLMs on mobile with llama.cpp" - UNVERIFIED
- Could not fetch (403). Nothing claimed from the page.
- [INF] Title suggests the most relevant article (Gemma + VLM + Android), so worth the owner opening it by hand and pasting the text if wanted.

## 3. Maid guide "llama-cpp-vision" - NOT helpful (for engineering)
- [PAGE] End-user quickstart: pick matched GGUF + mmproj from Hugging Face, attach photo in the UI (base64 to local model), start with 1B-3B at Q4_K_M, vision uses more memory, brief pause before generation.
- [PAGE] Missing: Android build, JNI/Kotlin, flags, benchmarks, licence.
- [INF] Only confirms the model+mmproj pairing concept. Maid itself (open source Flutter app) may be a code reference; not checked.

## 4. levelup.gitconnected "Complete guide to llama.cpp" - UNVERIFIED
- Could not fetch (403). Nothing claimed.
- [INF] Title says generic text-LLM guide; probably not Android/vision specific. Likely low value.

## Integration outline (all [INF])
1. Build: NDK r27+, CMake, arm64-v8a, build llama.cpp libs plus tools/mtmd as a shared lib; enable GGML_CPU_ARM_DOTPROD/i8mm via -march (armv8.2-a+dotprod+i8mm) or GGML_NATIVE off with runtime arm feature detection; KleidiAI optional (GGML_CPU_KLEIDIAI) - verify the flag names in current source. Study llama.cpp examples/llama.android for a JNI template; it has no vision, so add mtmd.
2. JNI wrapper: load model + mmproj, mtmd_bitmap from decoded RGB bytes (downscale in Kotlin first), eval chunks, token streaming callback, cancel flag, free on unload.
3. Model: 1-2B class VLM Q4 (e.g. SmolVLM, Qwen2-VL-2B, Gemma 3 4B-class if RAM allows), roughly 1-3 GB plus mmproj. Verify an mmproj exists for the chosen model and current licence.
4. Download/storage: app-private files dir, resumable download, SHA-256 check, user-initiated, delete on forget/wipe.
5. Router backend: new offline backend implementing the existing interface; load lazily, unload on memory pressure; honour privacy rules (image bytes never leave the process, no logging of output text, wipe covers model cache and temp bitmaps).
6. Background: foreground service with a wake lock and thermal check; single-flight queue; time-out.
7. Test: 20-photo set on the S-series device, measure load time, time to first token, tokens/s, peak RSS, battery/thermal, and description/place-confirm quality vs the cloud backend.

## Effort (experienced Android dev, code in hand) [INF]
- Build + mtmd native lib: 8-16 h. JNI/Kotlin wrapper: 12-20 h. Download/storage/integrity: 6-10 h.
- Router backend + privacy/wipe integration: 8-12 h. Background execution/lifecycle: 6-10 h.
- Device speed/quality test and tuning: 8-14 h. Total roughly 50-80 h (about 1.5-2 weeks), plus slack for NDK surprises.

## Top 3 risks [INF]
1. Speed/thermals: image encoding on CPU may take tens of seconds per photo (page 1 shows 28-865 s on older chips and a regression between versions); may be unusable without image downscaling or a smaller model.
2. Memory: 8 GB phone with the app plus OS leaves maybe 2-3 GB; model + mmproj + KV + image tokens risks OOM kills in the background.
3. API churn and quality: mtmd C API and flags change often (pin a llama.cpp commit); small VLMs are weak at place recognition, so "confirm a place" may disappoint.

## Verdicts
1 partly; 2 unverified (fetch failed); 3 not helpful; 4 unverified (fetch failed).
