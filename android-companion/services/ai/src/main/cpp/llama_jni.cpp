// JNI shim for com.rokid.context.model.provider.LlamaCppNativeProvider (arm64-v8a).
//
// Contract with the Kotlin side:
//   - nativeLoadModel returns 0 on any failure; any other value is a Handle* owned by the caller until nativeReleaseModel.
//     With forEmbedding it also creates the embed context (the n_ubatch compute buffers, the likeliest out-of-memory
//     point), so a context that can't be allocated is a failed load the caller counts, not a failed embed.
//   - nativeEmbed / nativeGenerate* return null on any failure.
//   - No C++ exception and no pending Java exception ever leaves these functions.
//   - Calls on one handle are serialised by the caller (EmbeddingClient's nativeLock); there is no other global state
//     beyond llama.cpp's own one-time backend init.
//   - llama.cpp logging is silenced, so no prompt or model text reaches logcat.

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"

namespace {

struct Handle {
    llama_model * model = nullptr;
    llama_context * embed_ctx = nullptr; // created by an embedding load, freed with the handle
};

constexpr int kMaxGenerateTokens = 256;
constexpr uint32_t kGenerateCtx = 2048;

void quiet_log(ggml_log_level, const char *, void *) {}

void init_backend_once() {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(quiet_log, nullptr);
        llama_backend_init();
    });
}

int32_t thread_count() {
    const unsigned hw = std::thread::hardware_concurrency();
    return static_cast<int32_t>(std::clamp<unsigned>(hw == 0 ? 2 : hw / 2, 1, 4));
}

Handle * from(jlong h) { return reinterpret_cast<Handle *>(static_cast<intptr_t>(h)); }

bool clear_pending(JNIEnv * env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionClear();
    return true;
}

// Java String -> UTF-8 bytes (real UTF-8, not JNI's modified UTF-8, so emoji tokenise correctly).
bool to_utf8(JNIEnv * env, jstring s, std::string & out) {
    if (s == nullptr) return false;
    jclass cls = env->GetObjectClass(s);
    jmethodID get_bytes = cls ? env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B") : nullptr;
    if (clear_pending(env) || get_bytes == nullptr) { if (cls) env->DeleteLocalRef(cls); return false; }
    jstring charset = env->NewStringUTF("UTF-8");
    if (clear_pending(env) || charset == nullptr) { env->DeleteLocalRef(cls); return false; }
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(s, get_bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(cls);
    if (clear_pending(env) || bytes == nullptr) return false;
    const jsize n = env->GetArrayLength(bytes);
    out.assign(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    env->DeleteLocalRef(bytes);
    return !clear_pending(env);
}

// UTF-8 bytes -> Java String via new String(byte[], "UTF-8"); malformed bytes become U+FFFD instead of aborting.
jstring from_utf8(JNIEnv * env, const std::string & s) {
    jclass cls = env->FindClass("java/lang/String");
    jmethodID ctor = cls ? env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V") : nullptr;
    if (clear_pending(env) || ctor == nullptr) { if (cls) env->DeleteLocalRef(cls); return nullptr; }
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(s.size()));
    jstring charset = env->NewStringUTF("UTF-8");
    jstring result = nullptr;
    if (!clear_pending(env) && bytes != nullptr && charset != nullptr) {
        if (!s.empty()) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
        if (!clear_pending(env)) {
            result = static_cast<jstring>(env->NewObject(cls, ctor, bytes, charset));
            if (clear_pending(env)) result = nullptr;
        }
    }
    if (bytes) env->DeleteLocalRef(bytes);
    if (charset) env->DeleteLocalRef(charset);
    env->DeleteLocalRef(cls);
    return result;
}

bool tokenize(const llama_vocab * vocab, const std::string & text, std::vector<llama_token> & out) {
    if (text.size() > static_cast<size_t>(INT32_MAX / 2)) return false;
    const auto len = static_cast<int32_t>(text.size());
    int32_t n = -llama_tokenize(vocab, text.data(), len, nullptr, 0, /*add_special*/ true, /*parse_special*/ false);
    if (n <= 0) return false;
    out.resize(static_cast<size_t>(n));
    return llama_tokenize(vocab, text.data(), len, out.data(), n, true, false) == n;
}

// One sequence (seq 0), every position an output, so any pooling type (and our own mean for NONE) has what it needs.
bool decode_all(llama_context * ctx, const std::vector<llama_token> & tokens) {
    llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
    for (size_t i = 0; i < tokens.size(); ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = static_cast<llama_pos>(i);
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = 1;
    }
    batch.n_tokens = static_cast<int32_t>(tokens.size());
    const int32_t rc = llama_decode(ctx, batch);
    llama_batch_free(batch);
    return rc == 0;
}

llama_context * make_embed_ctx(llama_model * model) {
    llama_context_params p = llama_context_default_params();
    const int32_t train = llama_model_n_ctx_train(model);
    const uint32_t n_ctx = static_cast<uint32_t>(train > 0 ? std::min(train, 8192) : 512);
    p.n_ctx = n_ctx;
    p.n_batch = n_ctx;  // an encoder needs the whole sequence in one batch
    p.n_ubatch = n_ctx;
    p.n_seq_max = 1;
    p.embeddings = true;
    p.pooling_type = LLAMA_POOLING_TYPE_UNSPECIFIED; // whatever the GGUF metadata says
    p.n_threads = thread_count();
    p.n_threads_batch = thread_count();
    p.no_perf = true;
    return llama_init_from_model(model, p);
}

jfloatArray embed(JNIEnv * env, Handle * h, jstring jtext, bool normalize) {
    if (h == nullptr || h->model == nullptr) return nullptr;
    llama_context * ctx = h->embed_ctx;
    if (ctx == nullptr) return nullptr;

    const enum llama_pooling_type pooling = llama_pooling_type(ctx);
    if (pooling == LLAMA_POOLING_TYPE_RANK) return nullptr; // a reranker, not an embedding model

    std::string text;
    if (!to_utf8(env, jtext, text)) return nullptr;
    const llama_vocab * vocab = llama_model_get_vocab(h->model);
    std::vector<llama_token> tokens;
    if (!tokenize(vocab, text, tokens)) return nullptr;
    const auto limit = static_cast<size_t>(llama_n_batch(ctx));
    if (tokens.size() > limit) { // keep the head; end on the separator the model was trained with
        tokens.resize(limit);
        const llama_token sep = llama_vocab_sep(vocab);
        if (sep != LLAMA_TOKEN_NULL && llama_vocab_get_add_sep(vocab)) tokens.back() = sep;
    }

    llama_memory_clear(llama_get_memory(ctx), true);
    if (!decode_all(ctx, tokens)) return nullptr;

    const int32_t n_embd = llama_model_n_embd(h->model);
    if (n_embd <= 0) return nullptr;
    std::vector<double> acc(static_cast<size_t>(n_embd), 0.0);
    if (pooling == LLAMA_POOLING_TYPE_NONE) { // no pooling in the metadata: mean over tokens
        for (size_t t = 0; t < tokens.size(); ++t) {
            const float * e = llama_get_embeddings_ith(ctx, static_cast<int32_t>(t));
            if (e == nullptr) return nullptr;
            for (int32_t i = 0; i < n_embd; ++i) acc[i] += e[i];
        }
    } else {
        const float * e = llama_get_embeddings_seq(ctx, 0);
        if (e == nullptr) return nullptr;
        for (int32_t i = 0; i < n_embd; ++i) acc[i] = e[i];
    }

    double norm = 0.0;
    for (double v : acc) norm += v * v;
    norm = std::sqrt(norm);
    if (!(norm > 0.0) || !std::isfinite(norm)) return nullptr;
    std::vector<float> out(acc.size());
    for (size_t i = 0; i < acc.size(); ++i) out[i] = static_cast<float>(normalize ? acc[i] / norm : acc[i]); // normalize=false: the raw pooled vector (Nomic: LayerNorm happens in Kotlin)

    jfloatArray arr = env->NewFloatArray(n_embd);
    if (clear_pending(env) || arr == nullptr) return nullptr;
    env->SetFloatArrayRegion(arr, 0, n_embd, out.data());
    if (clear_pending(env)) { env->DeleteLocalRef(arr); return nullptr; }
    return arr;
}

jstring generate(JNIEnv * env, Handle * h, jstring jprompt, jstring jgrammar) {
    if (h == nullptr || h->model == nullptr) return nullptr;
    std::string prompt, grammar;
    if (!to_utf8(env, jprompt, prompt)) return nullptr;
    if (jgrammar != nullptr && !to_utf8(env, jgrammar, grammar)) return nullptr;
    const llama_vocab * vocab = llama_model_get_vocab(h->model);
    std::vector<llama_token> tokens;
    if (!tokenize(vocab, prompt, tokens)) return nullptr;

    llama_context_params p = llama_context_default_params();
    const int32_t train = llama_model_n_ctx_train(h->model);
    p.n_ctx = std::min<uint32_t>(kGenerateCtx, train > 0 ? static_cast<uint32_t>(train) : kGenerateCtx);
    p.n_batch = 512;
    p.n_seq_max = 1;
    p.n_threads = thread_count();
    p.n_threads_batch = thread_count();
    p.no_perf = true;
    if (tokens.size() + kMaxGenerateTokens > p.n_ctx) return nullptr;

    llama_context * ctx = llama_init_from_model(h->model, p);
    if (ctx == nullptr) return nullptr;
    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (chain == nullptr) { llama_free(ctx); return nullptr; }
    bool ok = true;
    if (jgrammar != nullptr) {
        llama_sampler * g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (g == nullptr) ok = false; else llama_sampler_chain_add(chain, g); // the chain owns it now
    }
    if (ok) llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    std::string out;
    if (ok) { // prompt in n_batch chunks; logits only for the last token
        const size_t step = llama_n_batch(ctx);
        for (size_t i = 0; ok && i < tokens.size(); i += step) {
            const auto n = static_cast<int32_t>(std::min(step, tokens.size() - i));
            ok = llama_decode(ctx, llama_batch_get_one(tokens.data() + i, n)) == 0;
        }
    }
    for (int k = 0; ok && k < kMaxGenerateTokens; ++k) {
        llama_token id = llama_sampler_sample(chain, ctx, -1);
        if (id == LLAMA_TOKEN_NULL || llama_vocab_is_eog(vocab, id)) break;
        char buf[256];
        const int32_t n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
        if (n < 0) { ok = false; break; }
        out.append(buf, static_cast<size_t>(n));
        ok = llama_decode(ctx, llama_batch_get_one(&id, 1)) == 0;
    }
    llama_sampler_free(chain);
    llama_free(ctx);
    return ok ? from_utf8(env, out) : nullptr;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_rokid_context_model_provider_LlamaCppNativeProvider_nativeLoadModel(JNIEnv * env, jclass, jstring jpath, jboolean for_embedding) {
    try {
        init_backend_once();
        std::string path;
        if (!to_utf8(env, jpath, path) || path.empty()) return 0;
        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        llama_model * model = llama_model_load_from_file(path.c_str(), mp);
        if (model == nullptr) return 0;
        llama_context * ctx = nullptr;
        if (for_embedding) {
            ctx = make_embed_ctx(model);
            if (ctx == nullptr) { llama_model_free(model); return 0; }
        }
        auto * h = new (std::nothrow) Handle();
        if (h == nullptr) { if (ctx) llama_free(ctx); llama_model_free(model); return 0; }
        h->model = model;
        h->embed_ctx = ctx;
        return static_cast<jlong>(reinterpret_cast<intptr_t>(h));
    } catch (...) {
        return 0;
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_rokid_context_model_provider_LlamaCppNativeProvider_nativeEmbed(JNIEnv * env, jclass, jlong handle, jstring text, jboolean normalize) {
    try { return embed(env, from(handle), text, normalize == JNI_TRUE); } catch (...) { clear_pending(env); return nullptr; }
}

JNIEXPORT jstring JNICALL
Java_com_rokid_context_model_provider_LlamaCppNativeProvider_nativeGenerate(JNIEnv * env, jclass, jlong handle, jstring prompt) {
    try { return generate(env, from(handle), prompt, nullptr); } catch (...) { clear_pending(env); return nullptr; }
}

JNIEXPORT jstring JNICALL
Java_com_rokid_context_model_provider_LlamaCppNativeProvider_nativeGenerateWithGrammar(JNIEnv * env, jclass, jlong handle, jstring prompt, jstring grammar) {
    if (grammar == nullptr) return nullptr;
    try { return generate(env, from(handle), prompt, grammar); } catch (...) { clear_pending(env); return nullptr; }
}

JNIEXPORT void JNICALL
Java_com_rokid_context_model_provider_LlamaCppNativeProvider_nativeReleaseModel(JNIEnv *, jclass, jlong handle) {
    Handle * h = from(handle);
    if (h == nullptr) return;
    if (h->embed_ctx != nullptr) llama_free(h->embed_ctx);
    if (h->model != nullptr) llama_model_free(h->model);
    delete h;
}

} // extern "C"
