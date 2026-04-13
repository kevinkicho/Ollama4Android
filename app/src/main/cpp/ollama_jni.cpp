#include <jni.h>
#include <android/log.h>
#include <string>
#include <thread>
#include <mutex>
#include <atomic>

#include "llama.h"
#include "common.h"

#define TAG "OllamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── Global state ──────────────────────────────────────────────────────────────

static llama_model  *g_model   = nullptr;
static llama_context *g_ctx    = nullptr;
static std::mutex     g_mutex;
static std::atomic<bool> g_abort{false};

// ─── Helper: throw Java exception ──────────────────────────────────────────────

static void throw_java_exception(JNIEnv *env, const char *msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) env->ThrowNew(cls, msg);
}

// ─── JNI functions ─────────────────────────────────────────────────────────────

extern "C" {

// Initialize llama backend (call once at app startup)
JNIEXPORT void JNICALL
Java_com_ollama_android_llama_LlamaAndroid_nativeInit(JNIEnv *env, jobject /* this */) {
    LOGI("Initializing llama backend");
    llama_backend_init();
    llama_numa_init(GGML_NUMA_STRATEGY_DISABLED);
}

// Load a GGUF model from the given file path
JNIEXPORT jlong JNICALL
Java_com_ollama_android_llama_LlamaAndroid_nativeLoadModel(
        JNIEnv *env, jobject /* this */,
        jstring model_path,
        jint n_gpu_layers,
        jint n_threads) {

    std::lock_guard<std::mutex> lock(g_mutex);

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model: %s", path);

    auto params = llama_model_default_params();
    params.n_gpu_layers = n_gpu_layers;

    LOGI("Attempting to load with %d GPU layers", n_gpu_layers);
    llama_model *model = llama_model_load_from_file(path, params);

    // If GPU loading failed, retry with CPU-only
    if (!model && n_gpu_layers > 0) {
        LOGI("GPU loading failed, retrying with CPU-only (0 GPU layers)");
        params.n_gpu_layers = 0;
        model = llama_model_load_from_file(path, params);
    }

    env->ReleaseStringUTFChars(model_path, path);

    if (!model) {
        throw_java_exception(env, "Failed to load model");
        return 0;
    }

    g_model = model;
    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(model);
}

// Create a context for inference
JNIEXPORT jlong JNICALL
Java_com_ollama_android_llama_LlamaAndroid_nativeCreateContext(
        JNIEnv *env, jobject /* this */,
        jlong model_ptr,
        jint n_ctx,
        jint n_threads,
        jint n_batch) {

    std::lock_guard<std::mutex> lock(g_mutex);

    auto *model = reinterpret_cast<llama_model *>(model_ptr);
    if (!model) {
        throw_java_exception(env, "Invalid model pointer");
        return 0;
    }

    auto params = llama_context_default_params();
    params.n_ctx    = n_ctx;
    params.n_threads = n_threads;
    params.n_threads_batch = n_threads;
    params.n_batch  = n_batch;

    llama_context *ctx = llama_init_from_model(model, params);
    if (!ctx) {
        throw_java_exception(env, "Failed to create context");
        return 0;
    }

    g_ctx = ctx;
    LOGI("Context created: n_ctx=%d, n_threads=%d", n_ctx, n_threads);
    return reinterpret_cast<jlong>(ctx);
}

// Run completion: tokenize prompt, run inference, stream tokens back via callback
JNIEXPORT void JNICALL
Java_com_ollama_android_llama_LlamaAndroid_nativeComplete(
        JNIEnv *env, jobject /* this */,
        jlong model_ptr,
        jlong ctx_ptr,
        jstring prompt,
        jint n_predict,
        jfloat temperature,
        jfloat top_p,
        jint top_k,
        jfloat repeat_penalty,
        jobject callback) {

    auto *model = reinterpret_cast<llama_model *>(model_ptr);
    auto *ctx   = reinterpret_cast<llama_context *>(ctx_ptr);

    if (!model || !ctx) {
        throw_java_exception(env, "Model or context not initialized");
        return;
    }

    // Get callback method
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onComplete = env->GetMethodID(callbackClass, "onComplete", "()V");

    if (!onToken || !onComplete) {
        throw_java_exception(env, "Invalid callback object");
        return;
    }

    // Get prompt string
    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);

    g_abort.store(false);

    // Tokenize the prompt
    const llama_vocab *vocab = llama_model_get_vocab(model);
    int n_prompt_tokens = -llama_tokenize(vocab, prompt_str.c_str(), prompt_str.size(), nullptr, 0, true, true);

    std::vector<llama_token> tokens(n_prompt_tokens);
    llama_tokenize(vocab, prompt_str.c_str(), prompt_str.size(), tokens.data(), tokens.size(), true, true);

    LOGI("Prompt tokens: %d, max predict: %d", n_prompt_tokens, n_predict);

    // Clear memory (KV cache)
    llama_memory_clear(llama_get_memory(ctx), true);

    // Process prompt in batch
    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) {
        common_batch_add(batch, tokens[i], i, {0}, false);
    }
    batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(ctx, batch) != 0) {
        llama_batch_free(batch);
        throw_java_exception(env, "Failed to decode prompt");
        return;
    }

    // Setup sampler chain
    auto *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(0));

    // Generate tokens
    int n_cur = batch.n_tokens;
    int n_generated = 0;

    while (n_generated < n_predict && !g_abort.load()) {
        llama_token new_token = llama_sampler_sample(smpl, ctx, -1);

        // Check for end of generation
        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGI("End of generation after %d tokens", n_generated);
            break;
        }

        // Convert token to text
        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string piece(buf, n);
            jstring jpiece = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(callback, onToken, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        // Prepare next batch
        common_batch_clear(batch);
        common_batch_add(batch, new_token, n_cur, {0}, true);

        if (llama_decode(ctx, batch) != 0) {
            LOGE("Failed to decode token at position %d", n_cur);
            break;
        }

        n_cur++;
        n_generated++;
    }

    llama_sampler_free(smpl);
    llama_batch_free(batch);

    // Notify completion
    env->CallVoidMethod(callback, onComplete);
    LOGI("Generation complete: %d tokens generated", n_generated);
}

// Abort ongoing generation
JNIEXPORT void JNICALL
Java_com_ollama_android_llama_LlamaAndroid_nativeAbort(JNIEnv *env, jobject /* this */) {
    g_abort.store(true);
    LOGI("Generation abort requested");
}

// Free context
JNIEXPORT void JNICALL
Java_com_ollama_android_llama_LlamaAndroid_nativeFreeContext(
        JNIEnv *env, jobject /* this */, jlong ctx_ptr) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto *ctx = reinterpret_cast<llama_context *>(ctx_ptr);
    if (ctx) {
        llama_free(ctx);
        if (g_ctx == ctx) g_ctx = nullptr;
        LOGI("Context freed");
    }
}

// Free model
JNIEXPORT void JNICALL
Java_com_ollama_android_llama_LlamaAndroid_nativeFreeModel(
        JNIEnv *env, jobject /* this */, jlong model_ptr) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto *model = reinterpret_cast<llama_model *>(model_ptr);
    if (model) {
        llama_model_free(model);
        if (g_model == model) g_model = nullptr;
        LOGI("Model freed");
    }
}

// Get system info string
JNIEXPORT jstring JNICALL
Java_com_ollama_android_llama_LlamaAndroid_nativeSystemInfo(JNIEnv *env, jobject /* this */) {
    const char *info = llama_print_system_info();
    return env->NewStringUTF(info);
}

// Cleanup backend
JNIEXPORT void JNICALL
Java_com_ollama_android_llama_LlamaAndroid_nativeBackendFree(JNIEnv *env, jobject /* this */) {
    llama_backend_free();
    LOGI("Backend freed");
}

} // extern "C"
