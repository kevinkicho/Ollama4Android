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
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ollama-compatible HTTP API server running on localhost.
 * Exposes /api/generate, /api/chat, /api/tags, and /api/version endpoints
 * so other apps on the device can use the local LLM.
 */
class OllamaApiServer(
    port: Int = DEFAULT_PORT
) : NanoHTTPD("127.0.0.1", port) {

    private val llama = LlamaAndroid.instance
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    companion object {
        const val DEFAULT_PORT = 11434
        private const val TAG = "OllamaApiServer"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // Add CORS headers to all responses
        Log.d(TAG, "${method.name} $uri")

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

        val response = JSONObject().put("models", models)
        return jsonResponse(Response.Status.OK, response)
    }

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

        if (!llama.isModelLoaded || !llama.isContextReady) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "no model loaded. Load a model in the Ollama 4 Android app first.")
            )
        }

        val stream = json.optBoolean("stream", true)
        val nPredict = json.optInt("num_predict", 1024)
        val temperature = json.optDouble("temperature", 0.7).toFloat()
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
            put("model", "local")
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
                        put("model", "local")
                        put("created_at", isoDateFormat.format(Date()))
                        put("response", token)
                        put("done", false)
                    }
                    val line = chunk.toString() + "\n"
                    pipedOut.write(line.toByteArray(Charsets.UTF_8))
                    pipedOut.flush()
                }

                // Final "done" message
                val elapsed = (System.currentTimeMillis() - startTime) / 1_000_000_000.0
                val doneChunk = JSONObject().apply {
                    put("model", "local")
                    put("created_at", isoDateFormat.format(Date()))
                    put("response", "")
                    put("done", true)
                    put("total_duration", (elapsed * 1e9).toLong())
                    put("eval_count", tokenCount)
                }
                val doneLine = doneChunk.toString() + "\n"
                pipedOut.write(doneLine.toByteArray(Charsets.UTF_8))
                pipedOut.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Streaming generate error", e)
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }

        val response = newChunkedResponse(
            Response.Status.OK,
            "application/x-ndjson",
            pipedIn
        )
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

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

        if (!llama.isModelLoaded || !llama.isContextReady) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "no model loaded. Load a model in the Ollama 4 Android app first.")
            )
        }

        // Build prompt from messages using ChatML format
        val prompt = buildChatPrompt(messagesArray)
        val stream = json.optBoolean("stream", true)
        val nPredict = json.optInt("num_predict", 1024)
        val temperature = json.optDouble("temperature", 0.7).toFloat()
        val topP = json.optDouble("top_p", 0.9).toFloat()
        val topK = json.optInt("top_k", 40)
        val repeatPenalty = json.optDouble("repeat_penalty", 1.1).toFloat()

        if (!stream) {
            return handleChatNonStreaming(prompt, nPredict, temperature, topP, topK, repeatPenalty)
        }

        return handleChatStreaming(prompt, nPredict, temperature, topP, topK, repeatPenalty)
    }

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
            put("model", "local")
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
                        put("model", "local")
                        put("created_at", isoDateFormat.format(Date()))
                        put("message", JSONObject().apply {
                            put("role", "assistant")
                            put("content", token)
                        })
                        put("done", false)
                    }
                    val line = chunk.toString() + "\n"
                    pipedOut.write(line.toByteArray(Charsets.UTF_8))
                    pipedOut.flush()
                }

                val elapsed = (System.currentTimeMillis() - startTime) / 1_000_000_000.0
                val doneChunk = JSONObject().apply {
                    put("model", "local")
                    put("created_at", isoDateFormat.format(Date()))
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", "")
                    })
                    put("done", true)
                    put("total_duration", (elapsed * 1e9).toLong())
                    put("eval_count", tokenCount)
                }
                val doneLine = doneChunk.toString() + "\n"
                pipedOut.write(doneLine.toByteArray(Charsets.UTF_8))
                pipedOut.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Streaming chat error", e)
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }

        val response = newChunkedResponse(
            Response.Status.OK,
            "application/x-ndjson",
            pipedIn
        )
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) return "{}"
        val buffer = ByteArray(contentLength)
        session.inputStream.read(buffer, 0, contentLength)
        return String(buffer, Charsets.UTF_8)
    }

    private fun jsonResponse(status: Response.Status, json: JSONObject): Response {
        val response = newFixedLengthResponse(
            status,
            "application/json",
            json.toString()
        )
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return response
    }

    fun shutdown() {
        scope.cancel()
        stop()
    }
}
