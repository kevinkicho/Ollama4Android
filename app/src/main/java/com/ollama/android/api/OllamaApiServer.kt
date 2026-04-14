package com.ollama.android.api

import android.util.Log
import com.ollama.android.data.ModelCatalog
import com.ollama.android.data.ModelRepository
import com.ollama.android.llama.LlamaAndroid
import com.ollama.android.ui.chat.ChatViewModel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ollama-compatible HTTP API server running on localhost.
 * Exposes /api/generate, /api/chat, /api/tags, /api/version, and
 * OpenAI-compatible /v1/chat/completions endpoints.
 * Proxies to Ollama cloud when a cloud model is active.
 */
class OllamaApiServer(
    port: Int = DEFAULT_PORT
) : NanoHTTPD("127.0.0.1", port) {

    private val llama = LlamaAndroid.instance
    private val cloudClient = OllamaCloudClient.getInstance()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    companion object {
        const val DEFAULT_PORT = 0
        private const val TAG = "OllamaApiServer"
    }

    private val isCloudActive: Boolean
        get() = ChatViewModel.currentModelIsCloud

    private val cloudModelTag: String?
        get() = ChatViewModel.currentCloudModelTag

    private val hasAnyModel: Boolean
        get() = if (isCloudActive) cloudModelTag != null
                else llama.isModelLoaded && llama.isContextReady

    /**
     * Resolve the model tag to use for a cloud request.
     * If the request specifies a known cloud model tag, use that.
     * Otherwise fall back to the currently loaded cloud model.
     */
    private fun resolveCloudModel(requestedModel: String?): String? {
        if (!requestedModel.isNullOrBlank()) {
            // Check if the requested model is a known cloud model tag
            val knownCloudTag = ModelCatalog.cloudModels.find {
                it.cloudModelTag == requestedModel
            }?.cloudModelTag
            if (knownCloudTag != null) return knownCloudTag

            // Also accept cloud model tags that end with "-cloud"
            if (requestedModel.endsWith("-cloud")) return requestedModel
        }
        // Fall back to currently loaded cloud model
        return cloudModelTag
    }

    /**
     * Check if the requested model is a cloud model (even if a local model is currently loaded).
     */
    private fun isCloudModelRequest(requestedModel: String?): Boolean {
        if (requestedModel.isNullOrBlank()) return isCloudActive
        val knownCloud = ModelCatalog.cloudModels.any { it.cloudModelTag == requestedModel }
        return knownCloud || requestedModel.endsWith("-cloud")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "${method.name} $uri (cloud=${isCloudActive})")

        // Handle CORS preflight
        if (method == Method.OPTIONS) {
            return corsResponse(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
        }

        return try {
            when {
                uri == "/api/version" || uri == "/api/version/" ->
                    handleVersion()

                uri == "/api/tags" || uri == "/api/tags/" ->
                    handleTags()

                uri == "/api/generate" || uri == "/api/generate/" ->
                    handleGenerate(session)

                uri == "/api/chat" || uri == "/api/chat/" ->
                    handleChat(session)

                // OpenAI-compatible endpoint
                uri == "/v1/chat/completions" || uri == "/v1/chat/completions/" ->
                    handleOpenAIChatCompletions(session)

                // OpenAI models list
                uri == "/v1/models" || uri == "/v1/models/" ->
                    handleOpenAIModels()

                uri == "/" ->
                    jsonResponse(Response.Status.OK, JSONObject().put("status", "Ollama 4 Android is running"))

                else ->
                    jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "not found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $uri", e)
            jsonResponse(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("error", e.message ?: "internal error")
            )
        }
    }

    private fun handleVersion(): Response {
        val json = JSONObject().put("version", "0.1.0-ollama4android")
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleTags(): Response {
        val modelRepo = ModelRepository.getInstance(com.ollama.android.OllamaApp.instance)
        val models = JSONArray()

        // List locally downloaded models
        val localModels = runBlocking { modelRepo.getLocalModels() }
        for (model in localModels) {
            val modelJson = JSONObject().apply {
                put("name", model.name.lowercase().replace(" ", ":"))
                put("model", model.name.lowercase().replace(" ", ":"))
                put("modified_at", isoDateFormat.format(Date()))
                put("size", model.sizeBytes)
                put("digest", "local-${model.id}")
                put("details", JSONObject().apply {
                    put("format", "gguf")
                    put("quantization_level", model.quantization)
                })
            }
            models.put(modelJson)
        }

        // List cloud models if API key is set
        val cloudModels = modelRepo.getCloudModels()
        for (model in cloudModels) {
            val modelJson = JSONObject().apply {
                put("name", model.cloudModelTag ?: model.name.lowercase().replace(" ", ":"))
                put("model", model.cloudModelTag ?: model.name.lowercase().replace(" ", ":"))
                put("modified_at", isoDateFormat.format(Date()))
                put("size", 0)
                put("digest", "cloud-${model.id}")
                put("details", JSONObject().apply {
                    put("format", "cloud")
                    put("quantization_level", "cloud")
                })
            }
            models.put(modelJson)
        }

        val response = JSONObject().put("models", models)
        return jsonResponse(Response.Status.OK, response)
    }

    // ─── /api/generate ──────────────────────────────────────────────

    private fun handleGenerate(session: IHTTPSession): Response {
        val body = readBody(session)
        val json = JSONObject(body)

        val prompt = json.optString("prompt", "")
        if (prompt.isEmpty()) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "prompt is required")
            )
        }

        val requestedModel = json.optString("model", "")
        val useCloud = isCloudModelRequest(requestedModel)

        if (!useCloud && !hasAnyModel) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "no model loaded. Load a model in the Ollama 4 Android app first.")
            )
        }

        val stream = json.optBoolean("stream", true)
        val temperature = json.optDouble("temperature", 0.7).toFloat()

        if (useCloud) {
            // Convert generate prompt to a single-message chat for cloud
            val messages = listOf(OllamaCloudClient.ChatMessage("user", prompt))
            return handleCloudChat(messages, stream, temperature, isGenerateFormat = true, requestedModel = requestedModel)
        }

        val nPredict = json.optInt("num_predict", 1024)
        val topP = json.optDouble("top_p", 0.9).toFloat()
        val topK = json.optInt("top_k", 40)
        val repeatPenalty = json.optDouble("repeat_penalty", 1.1).toFloat()

        if (!stream) {
            return handleGenerateNonStreaming(prompt, nPredict, temperature, topP, topK, repeatPenalty)
        }
        return handleGenerateStreaming(prompt, nPredict, temperature, topP, topK, repeatPenalty)
    }

    private fun handleGenerateNonStreaming(
        prompt: String, nPredict: Int, temperature: Float,
        topP: Float, topK: Int, repeatPenalty: Float
    ): Response {
        val responseBuilder = StringBuilder()
        val startTime = System.currentTimeMillis()
        var tokenCount = 0

        runBlocking {
            llama.complete(prompt, nPredict, temperature, topP, topK, repeatPenalty).collect { token ->
                responseBuilder.append(token)
                tokenCount++
            }
        }

        val elapsed = (System.currentTimeMillis() - startTime) / 1_000_000_000.0
        val json = JSONObject().apply {
            put("model", ChatViewModel.currentLoadedModelName ?: "local")
            put("created_at", isoDateFormat.format(Date()))
            put("response", responseBuilder.toString())
            put("done", true)
            put("total_duration", (elapsed * 1e9).toLong())
            put("eval_count", tokenCount)
        }

        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleGenerateStreaming(
        prompt: String, nPredict: Int, temperature: Float,
        topP: Float, topK: Int, repeatPenalty: Float
    ): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 64 * 1024)

        scope.launch {
            try {
                var tokenCount = 0
                val startTime = System.currentTimeMillis()

                llama.complete(prompt, nPredict, temperature, topP, topK, repeatPenalty).collect { token ->
                    tokenCount++
                    val chunk = JSONObject().apply {
                        put("model", ChatViewModel.currentLoadedModelName ?: "local")
                        put("created_at", isoDateFormat.format(Date()))
                        put("response", token)
                        put("done", false)
                    }
                    pipedOut.write((chunk.toString() + "\n").toByteArray(Charsets.UTF_8))
                    pipedOut.flush()
                }

                val elapsed = (System.currentTimeMillis() - startTime) / 1_000_000_000.0
                val doneChunk = JSONObject().apply {
                    put("model", ChatViewModel.currentLoadedModelName ?: "local")
                    put("created_at", isoDateFormat.format(Date()))
                    put("response", "")
                    put("done", true)
                    put("total_duration", (elapsed * 1e9).toLong())
                    put("eval_count", tokenCount)
                }
                pipedOut.write((doneChunk.toString() + "\n").toByteArray(Charsets.UTF_8))
                pipedOut.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Streaming generate error", e)
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }

        return streamResponse(pipedIn)
    }

    // ─── /api/chat ──────────────────────────────────────────────────

    private fun handleChat(session: IHTTPSession): Response {
        val body = readBody(session)
        val json = JSONObject(body)

        val messagesArray = json.optJSONArray("messages")
        if (messagesArray == null || messagesArray.length() == 0) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "messages array is required")
            )
        }

        val requestedModel = json.optString("model", "")
        val useCloud = isCloudModelRequest(requestedModel)

        if (!useCloud && !hasAnyModel) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "no model loaded. Load a model in the Ollama 4 Android app first.")
            )
        }

        val stream = json.optBoolean("stream", true)
        val temperature = json.optDouble("temperature", 0.7).toFloat()

        if (useCloud) {
            val messages = (0 until messagesArray.length()).map { i ->
                val msg = messagesArray.getJSONObject(i)
                OllamaCloudClient.ChatMessage(
                    role = msg.optString("role", "user"),
                    content = msg.optString("content", "")
                )
            }
            return handleCloudChat(messages, stream, temperature, isGenerateFormat = false, requestedModel = requestedModel)
        }

        // Local model path
        if (!llama.isModelLoaded || !llama.isContextReady) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "no model loaded. Load a model in the Ollama 4 Android app first.")
            )
        }

        val prompt = buildChatPrompt(messagesArray)
        val nPredict = json.optInt("num_predict", 1024)
        val topP = json.optDouble("top_p", 0.9).toFloat()
        val topK = json.optInt("top_k", 40)
        val repeatPenalty = json.optDouble("repeat_penalty", 1.1).toFloat()

        if (!stream) {
            return handleChatNonStreaming(prompt, nPredict, temperature, topP, topK, repeatPenalty)
        }
        return handleChatStreaming(prompt, nPredict, temperature, topP, topK, repeatPenalty)
    }

    // ─── Cloud proxy ────────────────────────────────────────────────

    private fun handleCloudChat(
        messages: List<OllamaCloudClient.ChatMessage>,
        stream: Boolean,
        temperature: Float,
        isGenerateFormat: Boolean,
        requestedModel: String? = null
    ): Response {
        val modelRepo = ModelRepository.getInstance(com.ollama.android.OllamaApp.instance)
        val apiKey = modelRepo.getApiKey()
        val modelTag = resolveCloudModel(requestedModel) ?: return jsonResponse(
            Response.Status.BAD_REQUEST,
            JSONObject().put("error", "no cloud model selected")
        )

        if (apiKey.isBlank()) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "Ollama API key not configured")
            )
        }

        Log.d(TAG, "Proxying to cloud: model=$modelTag, stream=$stream, msgs=${messages.size}")

        if (!stream) {
            return handleCloudChatNonStreaming(apiKey, modelTag, messages, temperature, isGenerateFormat)
        }

        return handleCloudChatStreaming(apiKey, modelTag, messages, temperature, isGenerateFormat)
    }

    private fun handleCloudChatNonStreaming(
        apiKey: String,
        modelTag: String,
        messages: List<OllamaCloudClient.ChatMessage>,
        temperature: Float,
        isGenerateFormat: Boolean
    ): Response {
        val content = cloudClient.chatBlocking(apiKey, modelTag, messages, temperature)

        val json = JSONObject().apply {
            put("model", modelTag)
            put("created_at", isoDateFormat.format(Date()))
            if (isGenerateFormat) {
                put("response", content)
            } else {
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", content)
                })
            }
            put("done", true)
        }

        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleCloudChatStreaming(
        apiKey: String,
        modelTag: String,
        messages: List<OllamaCloudClient.ChatMessage>,
        temperature: Float,
        isGenerateFormat: Boolean
    ): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 64 * 1024)

        scope.launch(Dispatchers.IO) {
            try {
                if (isGenerateFormat) {
                    // For /api/generate, we need to reformat chat NDJSON to generate NDJSON
                    val responseBuilder = StringBuilder()
                    cloudClient.chatStream(apiKey, modelTag, messages, temperature).collect { token ->
                        responseBuilder.append(token)
                        val chunk = JSONObject().apply {
                            put("model", modelTag)
                            put("created_at", isoDateFormat.format(Date()))
                            put("response", token)
                            put("done", false)
                        }
                        pipedOut.write((chunk.toString() + "\n").toByteArray(Charsets.UTF_8))
                        pipedOut.flush()
                    }
                    // Done chunk
                    val doneChunk = JSONObject().apply {
                        put("model", modelTag)
                        put("created_at", isoDateFormat.format(Date()))
                        put("response", "")
                        put("done", true)
                    }
                    pipedOut.write((doneChunk.toString() + "\n").toByteArray(Charsets.UTF_8))
                    pipedOut.flush()
                } else {
                    // For /api/chat, pass through cloud NDJSON directly
                    cloudClient.chatStreamToPipe(apiKey, modelTag, messages, pipedOut, temperature)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cloud streaming error", e)
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }

        return streamResponse(pipedIn)
    }

    // ─── OpenAI-compatible /v1/chat/completions ─────────────────────

    private fun handleOpenAIChatCompletions(session: IHTTPSession): Response {
        val body = readBody(session)
        val json = JSONObject(body)

        val messagesArray = json.optJSONArray("messages")
        if (messagesArray == null || messagesArray.length() == 0) {
            return openAIError("messages array is required", "invalid_request_error")
        }

        val requestedModel = json.optString("model", "")
        val useCloud = isCloudModelRequest(requestedModel)

        if (!useCloud && !hasAnyModel) {
            return openAIError("no model loaded", "invalid_request_error")
        }

        val stream = json.optBoolean("stream", false)
        val temperature = json.optDouble("temperature", 0.7).toFloat()
        val maxTokens = json.optInt("max_tokens", 1024)

        // Parse messages
        val messages = (0 until messagesArray.length()).map { i ->
            val msg = messagesArray.getJSONObject(i)
            OllamaCloudClient.ChatMessage(
                role = msg.optString("role", "user"),
                content = msg.optString("content", "")
            )
        }

        val modelName = ChatViewModel.currentLoadedModelName ?: "ollama4android"
        val completionId = "chatcmpl-${UUID.randomUUID().toString().take(8)}"

        if (useCloud) {
            val modelRepo = ModelRepository.getInstance(com.ollama.android.OllamaApp.instance)
            val apiKey = modelRepo.getApiKey()
            val modelTag = resolveCloudModel(requestedModel) ?: return openAIError("no cloud model selected", "invalid_request_error")

            if (stream) {
                return handleOpenAICloudStreaming(apiKey, modelTag, messages, temperature, completionId, modelName)
            } else {
                val content = cloudClient.chatBlocking(apiKey, modelTag, messages, temperature)
                return openAIChatResponse(completionId, modelName, content)
            }
        }

        // Local model path
        if (!llama.isModelLoaded || !llama.isContextReady) {
            return openAIError("no model loaded", "invalid_request_error")
        }

        val prompt = buildChatPromptFromList(messages)

        if (stream) {
            return handleOpenAILocalStreaming(prompt, maxTokens, temperature, completionId, modelName)
        }

        // Non-streaming local
        val responseBuilder = StringBuilder()
        runBlocking {
            llama.complete(prompt, maxTokens, temperature).collect { token ->
                responseBuilder.append(token)
            }
        }
        return openAIChatResponse(completionId, modelName, responseBuilder.toString())
    }

    private fun handleOpenAICloudStreaming(
        apiKey: String,
        modelTag: String,
        messages: List<OllamaCloudClient.ChatMessage>,
        temperature: Float,
        completionId: String,
        modelName: String
    ): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 64 * 1024)

        scope.launch(Dispatchers.IO) {
            try {
                cloudClient.chatStream(apiKey, modelTag, messages, temperature).collect { token ->
                    val chunk = openAIStreamChunk(completionId, modelName, token)
                    pipedOut.write(("data: ${chunk.toString()}\n\n").toByteArray(Charsets.UTF_8))
                    pipedOut.flush()
                }
                pipedOut.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                pipedOut.flush()
            } catch (e: Exception) {
                Log.e(TAG, "OpenAI cloud streaming error", e)
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }

        val response = newChunkedResponse(Response.Status.OK, "text/event-stream", pipedIn)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }

    private fun handleOpenAILocalStreaming(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        completionId: String,
        modelName: String
    ): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 64 * 1024)

        scope.launch {
            try {
                llama.complete(prompt, maxTokens, temperature).collect { token ->
                    val chunk = openAIStreamChunk(completionId, modelName, token)
                    pipedOut.write(("data: ${chunk.toString()}\n\n").toByteArray(Charsets.UTF_8))
                    pipedOut.flush()
                }
                pipedOut.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                pipedOut.flush()
            } catch (e: Exception) {
                Log.e(TAG, "OpenAI local streaming error", e)
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }

        val response = newChunkedResponse(Response.Status.OK, "text/event-stream", pipedIn)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }

    private fun handleOpenAIModels(): Response {
        val modelRepo = ModelRepository.getInstance(com.ollama.android.OllamaApp.instance)
        val data = JSONArray()

        val localModels = runBlocking { modelRepo.getLocalModels() }
        for (model in localModels) {
            data.put(JSONObject().apply {
                put("id", model.name.lowercase().replace(" ", "-"))
                put("object", "model")
                put("owned_by", "local")
            })
        }

        val cloudModels = modelRepo.getCloudModels()
        for (model in cloudModels) {
            data.put(JSONObject().apply {
                put("id", model.cloudModelTag ?: model.name.lowercase().replace(" ", "-"))
                put("object", "model")
                put("owned_by", "ollama-cloud")
            })
        }

        val json = JSONObject().apply {
            put("object", "list")
            put("data", data)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun openAIChatResponse(completionId: String, model: String, content: String): Response {
        val json = JSONObject().apply {
            put("id", completionId)
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", content)
                })
                put("finish_reason", "stop")
            }))
            put("usage", JSONObject().apply {
                put("prompt_tokens", 0)
                put("completion_tokens", 0)
                put("total_tokens", 0)
            })
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun openAIStreamChunk(completionId: String, model: String, content: String): JSONObject {
        return JSONObject().apply {
            put("id", completionId)
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("delta", JSONObject().apply {
                    put("content", content)
                })
                put("finish_reason", JSONObject.NULL)
            }))
        }
    }

    private fun openAIError(message: String, type: String): Response {
        val json = JSONObject().apply {
            put("error", JSONObject().apply {
                put("message", message)
                put("type", type)
            })
        }
        return jsonResponse(Response.Status.BAD_REQUEST, json)
    }

    // ─── Local prompt building ──────────────────────────────────────

    private fun buildChatPrompt(messages: JSONArray): String {
        val modelName = ChatViewModel.currentLoadedModelName?.lowercase() ?: ""
        val useGemma = modelName.contains("gemma")

        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val role = msg.optString("role", "user")
            val content = msg.optString("content", "")

            if (useGemma) {
                val gemmaRole = if (role == "assistant") "model" else "user"
                if (content.isNotEmpty()) {
                    sb.append("<start_of_turn>$gemmaRole\n$content<end_of_turn>\n")
                }
            } else {
                sb.append("<|im_start|>$role\n$content<|im_end|>\n")
            }
        }

        if (useGemma) {
            sb.append("<start_of_turn>model\n")
        } else {
            sb.append("<|im_start|>assistant\n")
        }
        return sb.toString()
    }

    private fun buildChatPromptFromList(messages: List<OllamaCloudClient.ChatMessage>): String {
        val modelName = ChatViewModel.currentLoadedModelName?.lowercase() ?: ""
        val useGemma = modelName.contains("gemma")

        val sb = StringBuilder()
        for (msg in messages) {
            if (useGemma) {
                val gemmaRole = if (msg.role == "assistant") "model" else "user"
                if (msg.content.isNotEmpty()) {
                    sb.append("<start_of_turn>$gemmaRole\n${msg.content}<end_of_turn>\n")
                }
            } else {
                sb.append("<|im_start|>${msg.role}\n${msg.content}<|im_end|>\n")
            }
        }

        if (useGemma) sb.append("<start_of_turn>model\n")
        else sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    // ─── Local chat (non-cloud) ─────────────────────────────────────

    private fun handleChatNonStreaming(
        prompt: String, nPredict: Int, temperature: Float,
        topP: Float, topK: Int, repeatPenalty: Float
    ): Response {
        val responseBuilder = StringBuilder()
        val startTime = System.currentTimeMillis()
        var tokenCount = 0

        runBlocking {
            llama.complete(prompt, nPredict, temperature, topP, topK, repeatPenalty).collect { token ->
                responseBuilder.append(token)
                tokenCount++
            }
        }

        val elapsed = (System.currentTimeMillis() - startTime) / 1_000_000_000.0
        val json = JSONObject().apply {
            put("model", ChatViewModel.currentLoadedModelName ?: "local")
            put("created_at", isoDateFormat.format(Date()))
            put("message", JSONObject().apply {
                put("role", "assistant")
                put("content", responseBuilder.toString())
            })
            put("done", true)
            put("total_duration", (elapsed * 1e9).toLong())
            put("eval_count", tokenCount)
        }

        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleChatStreaming(
        prompt: String, nPredict: Int, temperature: Float,
        topP: Float, topK: Int, repeatPenalty: Float
    ): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 64 * 1024)

        scope.launch {
            try {
                var tokenCount = 0
                val startTime = System.currentTimeMillis()

                llama.complete(prompt, nPredict, temperature, topP, topK, repeatPenalty).collect { token ->
                    tokenCount++
                    val chunk = JSONObject().apply {
                        put("model", ChatViewModel.currentLoadedModelName ?: "local")
                        put("created_at", isoDateFormat.format(Date()))
                        put("message", JSONObject().apply {
                            put("role", "assistant")
                            put("content", token)
                        })
                        put("done", false)
                    }
                    pipedOut.write((chunk.toString() + "\n").toByteArray(Charsets.UTF_8))
                    pipedOut.flush()
                }

                val elapsed = (System.currentTimeMillis() - startTime) / 1_000_000_000.0
                val doneChunk = JSONObject().apply {
                    put("model", ChatViewModel.currentLoadedModelName ?: "local")
                    put("created_at", isoDateFormat.format(Date()))
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", "")
                    })
                    put("done", true)
                    put("total_duration", (elapsed * 1e9).toLong())
                    put("eval_count", tokenCount)
                }
                pipedOut.write((doneChunk.toString() + "\n").toByteArray(Charsets.UTF_8))
                pipedOut.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Streaming chat error", e)
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }

        return streamResponse(pipedIn)
    }

    // ─── Utilities ──────────────────────────────────────────────────

    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) return "{}"
        val buffer = ByteArray(contentLength)
        session.inputStream.read(buffer, 0, contentLength)
        return String(buffer, Charsets.UTF_8)
    }

    private fun jsonResponse(status: Response.Status, json: JSONObject): Response {
        return corsResponse(newFixedLengthResponse(status, "application/json", json.toString()))
    }

    private fun streamResponse(pipedIn: PipedInputStream): Response {
        val response = newChunkedResponse(Response.Status.OK, "application/x-ndjson", pipedIn)
        response.addHeader("Cache-Control", "no-cache")
        return corsResponse(response)
    }

    private fun corsResponse(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }

    fun shutdown() {
        scope.cancel()
        stop()
    }
}
