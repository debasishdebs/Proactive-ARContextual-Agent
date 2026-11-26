// JNI wrapper over llama.cpp + libmtmd for the offline photo captioner (com.rokid.context.vision.local.NativeVlm).
// Derived from the offline-vlm-spike (2026-11-26) with the benchmark statistics removed. One global model/context; Kotlin serialises every call.
//
// PRIVACY: nothing here logs a path, a prompt or generated text. llama.cpp's own log is forwarded at WARN/ERROR only (model load problems; it never prints the prompt).

#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "VisionLocal"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

llama_model * g_model = nullptr;
llama_context * g_ctx = nullptr;
mtmd_context * g_mtmd = nullptr;
llama_sampler * g_sampler = nullptr;
std::string g_error;

constexpr int32_t kNBatch = 512;
constexpr uint32_t kNCtx = 4096;

void log_cb(ggml_log_level level, const char * text, void *) {
    if (level != GGML_LOG_LEVEL_ERROR && level != GGML_LOG_LEVEL_WARN) return;
    __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "VisionLocal-llama", "%s", text);
}

void release_all() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_mtmd) { mtmd_free(g_mtmd); g_mtmd = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

std::string jstr(JNIEnv * env, jstring s) {
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// The error text is a fixed phrase only (never a path): the Kotlin side maps it to a refusal name.
jstring fail_load(JNIEnv * env, const char * what) { g_error = what; release_all(); LOGE("load failed: %s", what); return env->NewStringUTF(what); }

}  // namespace

// Returns "" on success, else a fixed error phrase.
extern "C" JNIEXPORT jstring JNICALL
Java_com_rokid_context_vision_local_NativeVlm_nativeLoad(JNIEnv * env, jobject, jstring jModel, jstring jMmproj, jint threads) {
    release_all();
    g_error.clear();
    const std::string model_path = jstr(env, jModel);
    const std::string mmproj_path = jstr(env, jMmproj);

    llama_log_set(log_cb, nullptr);
    mtmd_helper_log_set(log_cb, nullptr);
    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!g_model) return fail_load(env, "model load failed");

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = kNCtx;
    cp.n_batch = kNBatch;
    cp.n_ubatch = kNBatch;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.no_perf = true;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) return fail_load(env, "context create failed");

    mtmd_context_params mcp = mtmd_context_params_default();
    mcp.use_gpu = false;
    mcp.n_threads = threads;
    mcp.print_timings = false;
    g_mtmd = mtmd_init_from_file(mmproj_path.c_str(), g_model, mcp);
    if (!g_mtmd) return fail_load(env, "mmproj load failed");
    if (!mtmd_support_vision(g_mtmd)) return fail_load(env, "mmproj has no vision support");

    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_greedy());
    return env->NewStringUTF("");
}

// rgb: w*h*3 bytes RGBRGB. userText must hold exactly one media marker. Returns the UTF-8 bytes of the generated text, or null on failure (nativeLastError()).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_rokid_context_vision_local_NativeVlm_nativeRun(JNIEnv * env, jobject, jbyteArray jRgb, jint w, jint h, jstring jPrompt, jint maxTokens) {
    g_error.clear();
    if (!g_ctx || !g_mtmd) { g_error = "model not loaded"; return nullptr; }
    if (w <= 0 || h <= 0 || (size_t) env->GetArrayLength(jRgb) != (size_t) w * (size_t) h * 3) { g_error = "rgb size mismatch"; return nullptr; }

    llama_memory_clear(llama_get_memory(g_ctx), true);
    llama_sampler_reset(g_sampler);

    std::vector<unsigned char> rgb((size_t) w * (size_t) h * 3);
    env->GetByteArrayRegion(jRgb, 0, (jsize) rgb.size(), reinterpret_cast<jbyte *>(rgb.data()));
    mtmd_bitmap * bm = mtmd_bitmap_init((uint32_t) w, (uint32_t) h, rgb.data());
    if (!bm) { g_error = "bitmap init failed"; return nullptr; }

    // SmolVLM2 / idefics3 chat format; the media marker is replaced by the image tokens.
    const std::string user = jstr(env, jPrompt);
    const std::string prompt = std::string("<|im_start|>User:") + user + "<end_of_utterance>\nAssistant:";
    mtmd_input_text text;
    text.text = prompt.c_str();
    text.text_len = prompt.size();
    text.add_special = false;  // the template already starts with <|im_start|>
    text.parse_special = true;
    const mtmd_bitmap * bmc[1] = { bm };
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const int32_t rc = mtmd_tokenize(g_mtmd, chunks, &text, bmc, 1);
    mtmd_bitmap_free(bm);
    if (rc != 0) { g_error = "tokenize failed"; mtmd_input_chunks_free(chunks); return nullptr; }

    size_t total = 0;
    for (size_t i = 0; i < mtmd_input_chunks_size(chunks); ++i) total += mtmd_input_chunk_get_n_tokens(mtmd_input_chunks_get(chunks, i));
    if (total + (size_t) maxTokens > kNCtx) { g_error = "prompt too long"; mtmd_input_chunks_free(chunks); return nullptr; }

    llama_pos n_past = 0;
    const size_t n_chunks = mtmd_input_chunks_size(chunks);
    for (size_t i = 0; i < n_chunks; ++i) {
        const mtmd_input_chunk * chunk = mtmd_input_chunks_get(chunks, i);
        if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_IMAGE) {
            if (mtmd_encode_chunk(g_mtmd, chunk) != 0) { g_error = "image encode failed"; mtmd_input_chunks_free(chunks); return nullptr; }
            if (mtmd_helper_decode_image_chunk(g_mtmd, g_ctx, chunk, mtmd_get_output_embd(g_mtmd), n_past, 0, kNBatch, &n_past, nullptr, nullptr) != 0) {
                g_error = "image decode failed"; mtmd_input_chunks_free(chunks); return nullptr;
            }
        } else {
            const bool logits_last = (i + 1 == n_chunks);
            if (mtmd_helper_eval_chunk_single(g_mtmd, g_ctx, chunk, n_past, 0, kNBatch, logits_last, &n_past) != 0) {
                g_error = "prompt eval failed"; mtmd_input_chunks_free(chunks); return nullptr;
            }
        }
    }
    mtmd_input_chunks_free(chunks);

    // greedy generation
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    std::string out;
    for (int i = 0; i < maxTokens; ++i) {
        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        char buf[256];
        const int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (n > 0) out.append(buf, (size_t) n);
        llama_batch b = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, b) != 0) { g_error = "generation failed"; break; }
    }
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    if (!out.empty()) env->SetByteArrayRegion(arr, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rokid_context_vision_local_NativeVlm_nativeLastError(JNIEnv * env, jobject) { return env->NewStringUTF(g_error.c_str()); }

extern "C" JNIEXPORT void JNICALL
Java_com_rokid_context_vision_local_NativeVlm_nativeRelease(JNIEnv *, jobject) { release_all(); }
