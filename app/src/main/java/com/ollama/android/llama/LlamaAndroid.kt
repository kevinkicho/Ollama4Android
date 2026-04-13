package com.ollama.android.llama

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kotlin wrapper around the llama.cpp JNI bridge.
 * Provides coroutine-friendly APIs for model loading and text generation.
 */
class LlamaAndroid private constructor() {

    private var modelPtr: Long = 0L
    private var contextPtr: Long = 0L

    val isModelLoaded: Boolean get() = modelPtr != 0L
    val isContextReady: Boolean get() = contextPtr != 0L

    /**
     * Load a GGUF model from the given file path.
     */
    suspend fun loadModel(
        modelPath: String,
        nGpuLayers: Int = 0,
        nThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
    ) = withContext(Dispatchers.IO) {
        unload()
        modelPtr = nativeLoadModel(modelPath, nGpuLayers, nThreads)
    }

    /**
     * Create an inference context.
     */
    suspend fun createContext(
        nCtx: Int = 2048,
        nThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
        nBatch: Int = 512
    ) = withContext(Dispatchers.IO) {
        check(modelPtr != 0L) { "Model not loaded" }
        if (contextPtr != 0L) {
            nativeFreeContext(contextPtr)
            contextPtr = 0L
        }
        contextPtr = nativeCreateContext(modelPtr, nCtx, nThreads, nBatch)
    }

    /**
     * Run text completion, returning a Flow of generated token strings.
     */
    fun complete(
        prompt: String,
        nPredict: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        topK: Int = 40,
        repeatPenalty: Float = 1.1f
    ): Flow<String> = callbackFlow {
        check(modelPtr != 0L) { "Model not loaded" }
        check(contextPtr != 0L) { "Context not created" }

        val callback = object : CompletionCallback {
            override fun onToken(token: String) {
                trySend(token)
            }

            override fun onComplete() {
                channel.close()
            }
        }

        // Launch the blocking JNI call on a background thread
        val thread = Thread {
            try {
                nativeComplete(
                    modelPtr, contextPtr, prompt,
                    nPredict, temperature, topP, topK, repeatPenalty,
                    callback
                )
            } catch (e: Exception) {
                channel.close(e)
            }
        }
        thread.start()

        awaitClose {
            nativeAbort()
        }
    }

    /**
     * Abort any ongoing generation.
     */
    fun abort() {
        nativeAbort()
    }

    /**
     * Unload model and free all resources.
     */
    suspend fun unload() = withContext(Dispatchers.IO) {
        if (contextPtr != 0L) {
            nativeFreeContext(contextPtr)
            contextPtr = 0L
        }
        if (modelPtr != 0L) {
            nativeFreeModel(modelPtr)
            modelPtr = 0L
        }
    }

    fun getSystemInfo(): String = nativeSystemInfo()

    // ── JNI declarations ───────────────────────────────────────────────────

    private external fun nativeLoadModel(modelPath: String, nGpuLayers: Int, nThreads: Int): Long
    private external fun nativeCreateContext(modelPtr: Long, nCtx: Int, nThreads: Int, nBatch: Int): Long
    private external fun nativeComplete(
        modelPtr: Long, ctxPtr: Long, prompt: String,
        nPredict: Int, temperature: Float, topP: Float, topK: Int,
        repeatPenalty: Float, callback: CompletionCallback
    )
    private external fun nativeAbort()
    private external fun nativeFreeContext(ctxPtr: Long)
    private external fun nativeFreeModel(modelPtr: Long)
    private external fun nativeSystemInfo(): String

    companion object {
        init {
            System.loadLibrary("ollama-jni")
            nativeInit()
        }

        @JvmStatic
        private external fun nativeInit()

        val instance: LlamaAndroid by lazy { LlamaAndroid() }
    }
}

/**
 * Callback interface for streaming token generation.
 * Called from native code via JNI.
 */
interface CompletionCallback {
    fun onToken(token: String)
    fun onComplete()
}
